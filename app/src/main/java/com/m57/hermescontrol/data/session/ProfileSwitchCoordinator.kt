package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SetActiveProfileRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.HermesWsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * The single flow that performs a profile switch — the mobile equivalent of
 * desktop's re-home (``requestFreshSession`` + socket swap). Every surface
 * that switches profiles goes through here, so the switch is atomic instead
 * of a pile of scattered patches.
 *
 * Order matters:
 *  1. Flip the server's sticky active profile (REST).
 *  2. Persist the LOCAL selection — the REST interceptor (``?profile=``) and
 *     the WS params injector (``params.profile``) now scope everything to the
 *     new profile. The per-server token fallback (phase 1) keeps auth intact:
 *     no re-login, restart-safe.
 *  3. Emit [switched] BEFORE the socket re-dial, so chat wipes its stale
 *     conversation first — when the reconnected socket delivers
 *     ``gateway.ready``, ``handleGatewayReady`` sees no open session and
 *     auto-creates a FRESH session in the new profile (desktop parity).
 *  4. Re-dial the WebSocket so the gateway re-homes chat to the new profile.
 */
object ProfileSwitchCoordinator {
    private val _switched = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val switched: SharedFlow<String> = _switched.asSharedFlow()

    private val _connectionSwitched = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val connectionSwitched: SharedFlow<String> = _connectionSwitched.asSharedFlow()

    /**
     * Bot Mode (Fase 2) handoff: which conversation the chat should open after
     * a switch that came from the bot roster.
     *
     * @param profile the server-side Hermes profile (the bot) being homed to.
     * @param sessionId the bot's canonical chat, or null when it has none yet —
     *   the chat then creates one and adopts it as canonical.
     */
    data class PendingBotChat(
        val profile: String,
        val sessionId: String?,
    )

    /**
     * The pending bot-chat handoff, deliberately kept OUT of the [switched]
     * payload: that flow already has two independent collectors and widening
     * its type would churn both for a case neither cares about.
     *
     * `@Volatile` because it is written on the switching coroutine and read on
     * chat's collector, and it is consumed exactly once
     * ([consumePendingBotSession]) so a reconnect or a later plain profile flip
     * can never inherit a stale target.
     */
    @Volatile
    private var pendingBotChat: PendingBotChat? = null

    /**
     * Switches the SERVER-side Hermes profile.
     *
     * @param targetSessionId the bot's canonical chat to reopen after the
     *   switch (Bot Mode); null means "let chat decide" — a fresh session.
     * @param isBotContext whether the switch came from the bot roster. Defaults
     *   to "yes, if a target was given"; the roster passes it explicitly for a
     *   bot that has no canonical chat YET, because that case looks exactly
     *   like a plain profile flip from here but must end with the created
     *   session being adopted as canonical.
     */
    suspend fun switchProfile(
        name: String,
        targetSessionId: String? = null,
        isBotContext: Boolean = targetSessionId != null,
    ): NetworkResult<Unit> {
        val result =
            withContext(Dispatchers.IO) {
                safeApiCall { ApiClient.hermesApi.setActiveProfile(SetActiveProfileRequest(name)) }
            }
        if (result !is NetworkResult.Success) return result

        AuthManager.setActiveProfileId(name)
        // Armed BEFORE the broadcast so chat's collector — which consumes it in
        // the same dispatch as the wipe — already has the target by the time
        // the re-dialed socket delivers gateway.ready. A non-bot switch CLEARS
        // it: an arm that was never consumed must not leak into a plain flip.
        pendingBotChat =
            if (isBotContext) {
                PendingBotChat(profile = name, sessionId = targetSessionId?.takeIf { it.isNotBlank() })
            } else {
                null
            }
        _switched.emit(name)
        // The ticket mint inside connect() does blocking network I/O — it must
        // run off the main thread or the dial crashes with
        // NetworkOnMainThreadException and falls back to the 1s reconnect
        // retry (visible in the 2026-08-06 live logcat).
        withContext(Dispatchers.IO) {
            HermesWsClient.disconnect()
            HermesWsClient.connect()
        }
        return result
    }

    /**
     * Takes the bot-chat handoff armed by the last [switchProfile], or null
     * when the switch was a plain profile flip. Single-use by contract: every
     * later call returns null, so a reconnect can never reopen a stale thread.
     */
    fun consumePendingBotSession(): PendingBotChat? {
        val pending = pendingBotChat
        pendingBotChat = null
        return pending
    }

    /**
     * Switches the CONNECTION profile — which server the app talks to (e.g.
     * LAN "default" vs a Tailscale host). Unlike [switchProfile] (which only
     * re-scopes the SERVER-side Hermes profile over the same socket), this
     * re-points Retrofit AND re-dials the WebSocket, because the socket stays
     * glued to the old server otherwise: after a switch every REST tab talks
     * to the new server while chat keeps streaming from the old gateway
     * (split-brain reproduced live 2026-08-12 on the hyari emulator).
     *
     * Order matters:
     *  1. Persist the LOCAL selection — the token cache, cookie scope and
     *     [AuthManager.contextFlow] re-home to the new profile.
     *  2. Rebuild Retrofit so REST targets the new server.
     *  3. Emit [connectionSwitched] BEFORE the socket re-dial, so chat wipes
     *     its stale conversation first; the re-dialed socket then delivers
     *     gateway.ready → handleGatewayReady auto-creates a FRESH session on
     *     the new server (desktop requestFreshSession parity).
     *  4. Re-dial the WebSocket off the main thread (the ticket mint does
     *     blocking I/O — NetworkOnMainThreadException otherwise).
     */
    suspend fun switchConnectionProfile(profileId: String?) {
        AuthManager.setSelectedProfileId(profileId)
        ApiClient.rebuild()
        _connectionSwitched.emit(profileId.orEmpty())
        withContext(Dispatchers.IO) {
            // The WS ticket mint reads the cookie jar's ACTIVE store; the
            // selection change swaps that store asynchronously, so a dial
            // that races it mints with the PREVIOUS server's cookie → 401 →
            // aborted socket with no retry. Await the swap before dialing
            // (idempotent no-op when it already landed).
            AuthManager.syncCookieStoreForProfile(profileId)
            HermesWsClient.disconnect()
            HermesWsClient.connect()
        }
    }
}
