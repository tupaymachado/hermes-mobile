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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
        // Silent backstop: a finished turn anywhere refreshes the last-message
        // column without a spinner. No-op on backends without change_events.
        refreshOnChange(
            eventType = ChangeEvents.SESSIONS,
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
     */
    fun selectBot(name: String) {
        val previousActive = _uiState.value.activeBot
        if (name == previousActive) return
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
        val session =
            (sessionsResult as? NetworkResult.Success)?.data?.sessions?.firstOrNull()
                ?: return base

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
        val lastMessage =
            (messagesResult as? NetworkResult.Success)
                ?.data
                ?.messages
                ?.lastOrNull()
                ?.content
                ?.plainText()

        return base.copy(
            lastMessage = lastMessage?.takeIf { it.isNotBlank() },
            lastActivityAt = session.started_at,
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

/**
 * Flattens a message `content` payload to one line of prose. The gateway stores
 * it as a bare string on some turns and as structured content blocks on others
 * (`[{type:"text", text:"…"}]`), so both shapes have to collapse to something a
 * roster row can show.
 */
private fun JsonElement.plainText(): String? =
    when (this) {
        is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }
        is JsonArray -> mapNotNull { it.plainText() }.joinToString(" ").takeIf { it.isNotBlank() }
        is JsonObject ->
            listOf("text", "content", "message", "body")
                .firstNotNullOfOrNull { key -> this[key]?.plainText() }
    }?.replace(WHITESPACE_RUN, " ")?.trim()?.takeIf { it.isNotEmpty() }

private val WHITESPACE_RUN = Regex("\\s+")
