package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SessionRenameRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Which session IS bot X's thread in room R" — the 1:N sibling of
 * [BotChatRegistry] (§P3, slice b).
 *
 * Pure data layer: no Compose, no Android, no ViewModel. Callers own the
 * session lifecycle; this only decides and remembers, per member.
 *
 * Resolution order for a member, mirroring the 1:1 registry:
 *  1. the local map ([AuthManager.getGroupSessionId]) — survives restarts;
 *  2. fallback: that member's most recent session TITLED `Group: <name>`, read
 *     with an explicit `?profile=` so no profile switch is needed
 *     (cross-device recovery after a reinstall);
 *  3. null → the caller creates a fresh session and [adopt]s it.
 *
 * **The marker is the TITLE, never `pinned` — and that is load-bearing.** A
 * bot's `pinned` slot already means "this is my 1:1 canonical chat"
 * ([BotChatRegistry.resolveCanonicalSessionId] recovers by scanning for it).
 * Pinning a room thread would therefore let a group conversation surface as
 * that bot's private chat on the next reinstall: the same bot, the same
 * profile scope, one marker meaning two things. The title carries the room's
 * identity instead, and it cannot collide because it names the room.
 *
 * **Deferred rename, for the same reason the pin is deferred (§V3).**
 * `session.create` writes no server-side row until the first prompt, so
 * `PATCH /api/sessions/{id}` on a brand-new session 404s — the rename is the
 * same endpoint as the pin and fails the same way. [adopt] only *schedules*
 * the title; [flushPendingTitle] performs it, at the points where server
 * presence is confirmed.
 */
object GroupRoomCoordinator {
    /** How deep the title-scan fallback goes. Matches the 1:1 registry's scan. */
    private const val TITLE_SCAN_LIMIT = 50

    /** Adopted sessions whose durable title has not landed server-side yet. */
    private val pendingTitles = mutableMapOf<String, String>()

    /**
     * Resolves [member]'s thread in [room], or null when it has none yet (the
     * caller then creates one and [adopt]s it).
     *
     * A successful title-scan fallback is written back to the local map, so the
     * next resolve is offline-cheap.
     */
    suspend fun resolveMemberSessionId(
        room: GroupRoom,
        member: String,
    ): String? {
        if (room.id.isBlank() || member.isBlank()) return null
        // A member that left the room has no thread to resolve, even if the map
        // still remembers one — otherwise removing a bot would keep routing to
        // it.
        if (room.members.none { it.equals(member, ignoreCase = true) }) return null

        val stored = runCatching { AuthManager.getGroupSessionId(room.id, member) }.getOrNull()
        if (stored != null) return stored

        val recovered = findSessionByTitle(member, room.sessionTitle) ?: return null
        runCatching { AuthManager.setGroupSessionId(room.id, member, recovered) }
        return recovered
    }

    /**
     * Records [sessionId] as [member]'s thread in [room] and schedules the
     * durable title. The `PATCH {title}` itself waits for [flushPendingTitle].
     */
    fun adopt(
        room: GroupRoom,
        member: String,
        sessionId: String,
    ) {
        if (room.id.isBlank() || member.isBlank() || sessionId.isBlank()) return
        runCatching { AuthManager.setGroupSessionId(room.id, member, sessionId) }
        synchronized(pendingTitles) { pendingTitles[sessionId] = room.sessionTitle }
    }

    /**
     * Titles [sessionId] server-side if (and only if) it was [adopt]ed and is
     * still waiting. Call exactly where `sessionHasServerPresence` flips to
     * true — earlier and the PATCH 404s (§V3).
     *
     * A failed PATCH leaves the title pending, so a later presence signal
     * retries it; the local map already works without it (the title is only the
     * cross-device fallback).
     */
    suspend fun flushPendingTitle(sessionId: String) {
        if (sessionId.isBlank()) return
        val title = synchronized(pendingTitles) { pendingTitles[sessionId] } ?: return

        val result =
            withContext(Dispatchers.IO) {
                safeApiCall {
                    // title only: pinned stays untouched, which is the whole
                    // point of using the title as the room's marker.
                    ApiClient.hermesApi.setSessionPinned(sessionId, SessionRenameRequest(title = title))
                }
            }
        if (result is NetworkResult.Success) {
            synchronized(pendingTitles) { pendingTitles.remove(sessionId) }
        }
    }

    /**
     * Self-heal: drops [sessionId] as [member]'s thread in [roomId] after the
     * backend says it is gone (4007 / 404), so the map cannot resurrect a dead
     * session on the next resolve.
     *
     * A no-op when the map points somewhere else — a stale invalidation must
     * not wipe a newer adoption.
     */
    fun invalidate(
        roomId: String,
        member: String,
        sessionId: String,
    ) {
        if (roomId.isBlank() || member.isBlank() || sessionId.isBlank()) return
        synchronized(pendingTitles) { pendingTitles.remove(sessionId) }
        val stored = runCatching { AuthManager.getGroupSessionId(roomId, member) }.getOrNull()
        if (stored == sessionId) {
            runCatching { AuthManager.clearGroupSession(roomId, member) }
        }
    }

    /** Test seam: forgets scheduled titles without touching persisted state. */
    fun clearPendingTitlesForTest() {
        synchronized(pendingTitles) { pendingTitles.clear() }
    }

    /**
     * [member]'s most recent session carrying [title], read with an EXPLICIT
     * `profile=` so the scan works without switching the active profile (§V2).
     *
     * Cron-made sessions are skipped for the same reason every other scan skips
     * them (§V12) — a scheduled run can never be a room thread, and matching
     * one would hand the room a thread nobody spoke in.
     */
    private suspend fun findSessionByTitle(
        member: String,
        title: String,
    ): String? {
        val result =
            withContext(Dispatchers.IO) {
                safeApiCall {
                    ApiClient.hermesApi.getSessions(
                        limit = TITLE_SCAN_LIMIT,
                        offset = 0,
                        order = "recent",
                        profile = member,
                    )
                }
            }
        if (result !is NetworkResult.Success) return null

        return result.data.sessions
            .orEmpty()
            .filter { it.id.isNotBlank() && !it.isMachineOrigin() }
            .filter { it.title?.trim().equals(title, ignoreCase = true) }
            // `order=recent` already sorts newest-first; started_at breaks ties
            // for a backend that ignores the param, and null stamps sort last.
            .maxByOrNull { it.started_at ?: Double.NEGATIVE_INFINITY }
            ?.id
    }
}
