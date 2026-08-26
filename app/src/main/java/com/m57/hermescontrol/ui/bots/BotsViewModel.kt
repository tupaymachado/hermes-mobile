package com.m57.hermescontrol.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.BotChatRegistry
import com.m57.hermescontrol.data.session.BotDmAttribution
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.ui.common.ToastHost
import com.m57.hermescontrol.ui.common.refreshOnChange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class BotsUiState(
    val bots: List<BotRosterItem> = emptyList(),
    val activeBot: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
)

/**
 * Bot Mode roster (Fase 1): every server-side Hermes profile as a chat
 * partner, with presence and its last user message.
 *
 * **Load shape.** `GET /api/profiles` + `/active` in parallel (the
 * `ProfilesViewModel.loadProfiles` pattern), then a per-bot fan-out of
 * `sessions` + `messages`. That is 2N+1 requests, capped at
 * [ROSTER_FAN_OUT_LIMIT] concurrent bots — fine for the typical N < ~15. If
 * real installs grow past that, degrade to "last message for the active bot
 * only, lazily on scroll" rather than widening the cap.
 *
 * **Failure policy.** Only the profiles/active pair can set [BotsUiState.errorMessage]
 * — a per-bot lookup that fails degrades *that row* to a null last message and
 * leaves the roster standing (the rule already documented on
 * `ProfilesViewModel.loadModelOptions`).
 */
class BotsViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(BotsUiState())
    val uiState: StateFlow<BotsUiState> = _uiState.asStateFlow()

    init {
        // Silent backstop: a finished turn refreshes the last-message column
        // and a gateway going up/down refreshes presence, both without a
        // spinner. One collector for both types, so a burst on the two cannot
        // fire two concurrent fan-outs. No-op on backends without
        // change_events, and on backends that only emit one of the two this
        // degrades to that one — pull-to-refresh stays the floor.
        refreshOnChange(
            eventTypes = setOf(ChangeEvents.SESSIONS, ChangeEvents.GATEWAY),
            apiCall = { fetchRoster() },
            onSuccess = { snapshot ->
                _uiState.update { it.copy(bots = snapshot.bots, activeBot = snapshot.activeBot) }
            },
        )
    }

    fun loadRoster() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = fetchRoster()) {
                is NetworkResult.Success ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            bots = result.data.bots,
                            activeBot = result.data.activeBot,
                        )
                    }

                is NetworkResult.Failure ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "Failed to load bots: ${result.error.message}")
                    }
            }
        }
    }

    /**
     * Opens the chat with [name]: resolve the bot's canonical session, switch
     * the active profile with that session as the target, then land on the
     * chat screen.
     *
     * The target is resolved BEFORE the switch because the coordinator hands it
     * to `ChatViewModel` in the same dispatch as the switch broadcast — the
     * chat's `gateway.ready` must already know which thread to resume. A bot
     * with no canonical chat yet resolves to null and still goes through the
     * bot path, so the session the chat creates gets adopted as canonical.
     *
     * [onSwitched] runs only once the server has actually accepted the flip.
     * The switcher sheet hangs its dismissal on it: dismissing on tap would
     * close the sheet over a switch that may still fail, and the failure toast
     * would then arrive with nothing on screen to attach it to.
     */
    fun selectBot(
        name: String,
        onSwitched: () -> Unit = {},
    ) {
        val previousActive = _uiState.value.activeBot
        if (name == previousActive) {
            // Tapping the bot you are already talking to used to be a silent
            // no-op — from the sheet that read as "it closed and did nothing".
            // There is nothing to switch, so say that and stay put.
            _uiState.update { it.copy(toastMessage = "$name is already active") }
            return
        }
        // Optimistic — mirrors ProfilesViewModel.selectActiveProfile, and is
        // rolled back below if the server refuses the flip.
        _uiState.update { state -> state.copy(activeBot = name, bots = state.bots.withActive(name)) }

        viewModelScope.launch {
            // Best-effort: a registry lookup that fails degrades to "no target"
            // (a fresh chat), never to a blocked switch.
            val target = runCatching { BotChatRegistry.resolveCanonicalSessionId(name) }.getOrNull()
            val result = ProfileSwitchCoordinator.switchProfile(name, target, isBotContext = true)
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(toastMessage = "Switched to $name") }
                    onSwitched()
                    NavigationController.navigateTo(ChatScreen)
                    loadRoster()
                }

                is NetworkResult.Failure ->
                    _uiState.update { state ->
                        state.copy(
                            activeBot = previousActive,
                            bots = state.bots.withActive(previousActive),
                            toastMessage = "Failed to switch to $name: ${result.error.message}",
                        )
                    }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    // ── Loading ──────────────────────────────────────────────────────────

    /** Roster + active bot, so [loadRoster] and the silent refresh share one path. */
    private data class RosterSnapshot(
        val bots: List<BotRosterItem>,
        val activeBot: String?,
    )

    private suspend fun fetchRoster(): NetworkResult<RosterSnapshot> =
        coroutineScope {
            val profilesDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getProfiles() } }
            val activeDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getActiveProfile() } }

            val profilesResult = profilesDeferred.await()
            val activeResult = activeDeferred.await()

            if (profilesResult !is NetworkResult.Success) {
                return@coroutineScope profilesResult as NetworkResult.Failure
            }
            // A missing /active is NOT fatal: the roster is still useful without
            // the "you are here" check, so it degrades to no active bot.
            val activeName = (activeResult as? NetworkResult.Success)?.data?.active

            val profiles = profilesResult.data.profiles.orEmpty()
            val gate = Semaphore(ROSTER_FAN_OUT_LIMIT)
            val bots =
                profiles
                    .map { profile ->
                        async(ioDispatcher) { gate.withPermit { profile.toRosterItem(activeName) } }
                    }.awaitAll()

            NetworkResult.Success(
                RosterSnapshot(
                    // Most recently used bots first; never-used ones keep the
                    // backend's order at the bottom.
                    bots = bots.sortedByDescending { it.lastActivityAt ?: Double.NEGATIVE_INFINITY },
                    activeBot = activeName,
                ),
            )
        }

    /**
     * Builds one roster row. Both per-bot calls are best-effort — a failure
     * yields a row with no last message instead of failing the whole load.
     */
    private suspend fun ProfileInfo.toRosterItem(activeName: String?): BotRosterItem {
        val base =
            BotRosterItem(
                name = name,
                description = description?.takeIf { it.isNotBlank() },
                isActive = name == activeName,
                presence =
                    when {
                        name == activeName -> BotPresence.ACTIVE
                        gateway_running == true -> BotPresence.ONLINE
                        gateway_running == false -> BotPresence.OFFLINE
                        else -> BotPresence.UNKNOWN
                    },
            )

        // Explicit `profile=` reads THIS bot's sessions without switching the
        // active profile (ProfileScopeInterceptor leaves an explicit param alone).
        val sessionsResult =
            safeApiCall {
                ApiClient.hermesApi.getSessions(limit = 1, offset = 0, order = "recent", profile = name)
            }
        // Failure and "no sessions" both land on a null last message, but they
        // are NOT the same fact: only the first is a degraded row (Fase 4).
        if (sessionsResult !is NetworkResult.Success) {
            return base.copy(lastMessageUnavailable = true)
        }
        val session = sessionsResult.data.sessions?.firstOrNull() ?: return base

        val messagesResult =
            safeApiCall {
                ApiClient.hermesApi.getSessionMessages(
                    sessionId = session.id,
                    limit = 1,
                    // order=latest pages back from the NEWEST message; without
                    // it a legacy backend returns the oldest, which is the same
                    // thing SessionInfo.preview already holds.
                    order = "latest",
                    role = "user",
                )
            }
        // Parse BEFORE squashing: `BotDmAttribution` is line-anchored, and
        // `oneLine()` would fold a multi-line delivery into text it no longer
        // recognises. PM1's roster badge is pure derivation over the last
        // message the fan-out ALREADY fetched — no extra request.
        val rawLastMessage =
            (messagesResult as? NetworkResult.Success)
                ?.data
                ?.messages
                ?.lastOrNull()
                ?.content
                ?.flatText()
        val dm = rawLastMessage?.let { BotDmAttribution.parse(it) }
        val lastMessage =
            rawLastMessage
                ?.let { if (dm != null) BotDmAttribution.stripPrefix(it) else it }
                ?.oneLine()

        return base.copy(
            lastMessage = lastMessage?.takeIf { it.isNotBlank() },
            // Null unless the last message really is a delivery — the row's
            // badge is the only thing that reads it.
            lastMessageDmSender = dm?.displayName,
            lastActivityAt = session.started_at,
            // The session itself was readable, so recency survives; only the
            // message text is missing, and only when the call actually failed
            // (a session whose newest user message is empty is not an error).
            lastMessageUnavailable = messagesResult !is NetworkResult.Success,
        )
    }

    companion object {
        /** Concurrent per-bot lookups; keeps the fan-out off the connection pool's back. */
        private const val ROSTER_FAN_OUT_LIMIT = 12
    }
}

private fun List<BotRosterItem>.withActive(activeName: String?): List<BotRosterItem> =
    map { bot ->
        val isActive = bot.name == activeName
        bot.copy(
            isActive = isActive,
            presence =
                when {
                    isActive -> BotPresence.ACTIVE
                    // Demoting the previous active: it stays reachable, so ONLINE
                    // is the honest fallback until the next load says otherwise.
                    bot.presence == BotPresence.ACTIVE -> BotPresence.ONLINE
                    else -> bot.presence
                },
        )
    }
