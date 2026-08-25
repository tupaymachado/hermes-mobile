package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SessionRenameRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bot Mode's canonical-chat policy: "which session IS the conversation with
 * bot X", where a *bot* is a SERVER-side Hermes profile (`GET /api/profiles`),
 * not a local [com.m57.hermescontrol.data.config.ConnectionProfile].
 *
 * Pure data layer — no Compose, no Android, no ViewModel. Callers (Fase 2:
 * `ChatViewModel` / `BotsViewModel`) own the session lifecycle; this object
 * only decides and remembers.
 *
 * Resolution order in [resolveCanonicalSessionId]:
 *  1. the local map ([AuthManager.getBotChatSessionId]) — survives restarts;
 *  2. fallback: the most recent **pinned** session of that profile, read with
 *     an explicit `?profile=` so no profile switch is needed (cross-device
 *     recovery after a reinstall);
 *  3. null → the caller creates a fresh session and [adopt]s it.
 *
 * **Deferred pin.** `session.create` writes no server-side row until the first
 * prompt (`sessionHasServerPresence`), so `PATCH /api/sessions/{id}` on a
 * brand-new session 404s. [adopt] therefore only *schedules* the pin;
 * [flushPendingPin] performs it, and callers must invoke that only at the
 * points where server presence is confirmed (REST 200 / resume ok /
 * MessageStart).
 */
object BotChatRegistry {
    /** How deep the pinned-session fallback scan goes (matches the plan's `limit=50`). */
    private const val PINNED_SCAN_LIMIT = 50

    /** Sessions adopted as canonical but not yet pinned server-side. */
    private val pendingPins = mutableSetOf<String>()

    /**
     * Resolves the canonical chat session for [profile], or null when the bot
     * has none yet (the caller then creates one and [adopt]s it).
     *
     * A successful pinned-session fallback is written back to the local map,
     * so the next resolve is offline-cheap.
     */
    suspend fun resolveCanonicalSessionId(profile: String): String? {
        if (profile.isBlank()) return null

        val stored = runCatching { AuthManager.getBotChatSessionId(profile) }.getOrNull()
        if (stored != null) return stored

        val recovered = findMostRecentPinnedSession(profile) ?: return null
        runCatching { AuthManager.setBotChatSessionId(profile, recovered) }
        return recovered
    }

    /**
     * Records [sessionId] as the canonical chat of [profile] and schedules the
     * durable server-side marker. The `PATCH {pinned:true}` itself waits for
     * [flushPendingPin] — see the deferred-pin note on this object.
     */
    fun adopt(
        profile: String,
        sessionId: String,
    ) {
        if (profile.isBlank() || sessionId.isBlank()) return
        runCatching { AuthManager.setBotChatSessionId(profile, sessionId) }
        synchronized(pendingPins) { pendingPins.add(sessionId) }
    }

    /**
     * Pins [sessionId] server-side if (and only if) it was [adopt]ed and is
     * still waiting. Call this exactly where `sessionHasServerPresence` flips
     * to true — earlier and the PATCH 404s on a session the backend has not
     * written yet.
     *
     * A failed PATCH leaves the pin pending, so a later presence signal retries
     * it; the local map already works without it (the pin is only the
     * cross-device fallback).
     */
    suspend fun flushPendingPin(sessionId: String) {
        if (sessionId.isBlank()) return
        val pending = synchronized(pendingPins) { pendingPins.contains(sessionId) }
        if (!pending) return

        val result =
            withContext(Dispatchers.IO) {
                safeApiCall {
                    ApiClient.hermesApi.setSessionPinned(sessionId, SessionRenameRequest(pinned = true))
                }
            }
        if (result is NetworkResult.Success) {
            synchronized(pendingPins) { pendingPins.remove(sessionId) }
        }
    }

    /**
     * Self-heal: drops [sessionId] as the canonical chat of [profile] after the
     * backend says it is gone (4007 / 404 — e.g. deleted from another client),
     * so the map cannot resurrect a dead session on the next resolve.
     *
     * A no-op when the map points somewhere else — a stale invalidation must
     * not wipe a newer adoption.
     */
    fun invalidate(
        profile: String,
        sessionId: String,
    ) {
        if (profile.isBlank() || sessionId.isBlank()) return
        synchronized(pendingPins) { pendingPins.remove(sessionId) }
        val stored = runCatching { AuthManager.getBotChatSessionId(profile) }.getOrNull()
        if (stored == sessionId) {
            runCatching { AuthManager.clearBotChatSession(profile) }
        }
    }

    /** Test seam: forgets scheduled pins without touching persisted state. */
    fun clearPendingPinsForTest() {
        synchronized(pendingPins) { pendingPins.clear() }
    }

    /**
     * Most recent pinned session of [profile], read with an EXPLICIT `profile=`
     * so the scan works without switching the active profile
     * (`ProfileScopeInterceptor` leaves an explicit param untouched).
     */
    private suspend fun findMostRecentPinnedSession(profile: String): String? {
        val result =
            withContext(Dispatchers.IO) {
                safeApiCall {
                    ApiClient.hermesApi.getSessions(
                        limit = PINNED_SCAN_LIMIT,
                        offset = 0,
                        order = "recent",
                        profile = profile,
                    )
                }
            }
        if (result !is NetworkResult.Success) return null

        // `order=recent` already sorts newest-first; started_at breaks ties for
        // backends that ignore the param, and null timestamps sort last.
        return result.data.sessions
            .filter { it.pinned == true && it.id.isNotBlank() }
            .maxByOrNull { it.started_at ?: Double.NEGATIVE_INFINITY }
            ?.id
    }
}
