package com.m57.hermescontrol.ui.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
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

data class BotDmsUiState(
    val threads: List<BotDmThreadItem> = emptyList(),
    val activeBot: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /**
     * Bots whose thread could not be scanned. Surfaced, never swallowed: an
     * aggregated list that silently drops an unreachable bot reads as "no DMs"
     * when the truth is "we could not look".
     */
    val unscannedBots: List<String> = emptyList(),
    val toastMessage: String? = null,
)

/**
 * Bot Mode PM1 — the aggregated "Bot DMs" view: every canonical Bot Chat that
 * currently holds bot-to-bot traffic, newest first.
 *
 * **Load shape.** Deliberately the SAME fan-out as the roster
 * (`BotsViewModel`): profiles + active in parallel, then per bot its most
 * recent session and the last [DM_SCAN_WINDOW] user turns of that thread. 2N+1
 * requests, capped at [DM_FAN_OUT_LIMIT] concurrent bots. The DM protocol
 * delivers into the bot's canonical Bot Chat (Fase 2), so scanning that one
 * thread per bot is what makes the screen affordable — a full history sweep
 * would be O(sessions), not O(bots).
 *
 * **Consequence, stated rather than hidden:** a DM that scrolled out of the
 * last [DM_SCAN_WINDOW] user turns, or that landed in an older thread, will not
 * list. This is a "what is going on between the bots right now" view, not an
 * archive.
 *
 * **Failure policy.** Only the profiles call is fatal; a per-bot scan that
 * fails drops that bot into [BotDmsUiState.unscannedBots] and leaves the list
 * standing.
 */
class BotDmsViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(BotDmsUiState())
    val uiState: StateFlow<BotDmsUiState> = _uiState.asStateFlow()

    init {
        // A finished turn anywhere can add a delivery to some bot's thread, so
        // the same silent-refresh backstop the roster uses applies here.
        refreshOnChange(
            eventType = ChangeEvents.SESSIONS,
            apiCall = { fetchThreads() },
            onSuccess = { snapshot ->
                _uiState.update {
                    it.copy(
                        threads = snapshot.threads,
                        activeBot = snapshot.activeBot,
                        unscannedBots = snapshot.unscannedBots,
                    )
                }
            },
        )
    }

    fun loadThreads() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = fetchThreads()) {
                is NetworkResult.Success ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            threads = result.data.threads,
                            activeBot = result.data.activeBot,
                            unscannedBots = result.data.unscannedBots,
                        )
                    }

                is NetworkResult.Failure ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load bot DMs: ${result.error.message}",
                        )
                    }
            }
        }
    }

    /**
     * Opens [thread] in the chat.
     *
     * A thread belongs to its bot's profile scope, so reaching it means
     * switching the active profile first — with the thread as the switch's
     * target session, exactly like the roster does, so `ChatViewModel` resumes
     * it on `gateway.ready` instead of creating a new session. The bot that is
     * ALREADY active needs no flip, and takes the plain session route.
     */
    fun openThread(thread: BotDmThreadItem) {
        if (thread.botName == _uiState.value.activeBot) {
            NavigationController.openChatSession(thread.sessionId)
            return
        }
        viewModelScope.launch {
            val result =
                ProfileSwitchCoordinator.switchProfile(
                    thread.botName,
                    thread.sessionId,
                    isBotContext = true,
                )
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(activeBot = thread.botName) }
                    NavigationController.navigateTo(ChatScreen)
                }

                is NetworkResult.Failure ->
                    _uiState.update {
                        it.copy(
                            toastMessage =
                                "Failed to open ${thread.botName}: ${result.error.message}",
                        )
                    }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    // ── Loading ──────────────────────────────────────────────────────────

    private data class DmSnapshot(
        val threads: List<BotDmThreadItem>,
        val activeBot: String?,
        val unscannedBots: List<String>,
    )

    /** A bot's scan result: its thread (if it has DMs) or the fact that it failed. */
    private data class BotScan(
        val name: String,
        val thread: BotDmThreadItem?,
        val failed: Boolean,
    )

    private suspend fun fetchThreads(): NetworkResult<DmSnapshot> =
        coroutineScope {
            val profilesDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getProfiles() } }
            val activeDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getActiveProfile() } }

            val profilesResult = profilesDeferred.await()
            val activeResult = activeDeferred.await()

            if (profilesResult !is NetworkResult.Success) {
                return@coroutineScope profilesResult as NetworkResult.Failure
            }
            // Missing /active only costs the "no switch needed" shortcut in
            // [openThread]; the list itself is still correct without it.
            val activeName = (activeResult as? NetworkResult.Success)?.data?.active

            val profiles = profilesResult.data.profiles.orEmpty()
            val gate = Semaphore(DM_FAN_OUT_LIMIT)
            val scans =
                profiles
                    .map { profile -> async(ioDispatcher) { gate.withPermit { profile.scan() } } }
                    .awaitAll()

            NetworkResult.Success(
                DmSnapshot(
                    threads =
                        scans
                            .mapNotNull { it.thread }
                            .sortedByDescending { it.lastActivityAt ?: Double.NEGATIVE_INFINITY },
                    activeBot = activeName,
                    unscannedBots = scans.filter { it.failed }.map { it.name },
                ),
            )
        }

    /**
     * Scans one bot's canonical thread for deliveries. An explicit `profile=`
     * reads it WITHOUT switching the active profile
     * (`ProfileScopeInterceptor` honours the explicit param).
     */
    private suspend fun ProfileInfo.scan(): BotScan {
        val sessionsResult =
            safeApiCall {
                ApiClient.hermesApi.getSessions(limit = 1, offset = 0, order = "recent", profile = name)
            }
        if (sessionsResult !is NetworkResult.Success) {
            return BotScan(name = name, thread = null, failed = true)
        }
        // No session is not a failure — the bot simply has no thread yet.
        val session =
            sessionsResult.data.sessions?.firstOrNull()
                ?: return BotScan(name = name, thread = null, failed = false)

        val messagesResult =
            safeApiCall {
                ApiClient.hermesApi.getSessionMessages(
                    sessionId = session.id,
                    limit = DM_SCAN_WINDOW,
                    // Newest-anchored page, returned chronologically — so the
                    // LAST element of the window is the newest turn.
                    order = "latest",
                    role = "user",
                )
            }
        if (messagesResult !is NetworkResult.Success) {
            return BotScan(name = name, thread = null, failed = true)
        }

        val userTurns = messagesResult.data.messages.mapNotNull { it.content?.flatText() }
        return BotScan(
            name = name,
            thread =
                botDmThread(
                    botName = name,
                    sessionId = session.id,
                    title = session.title,
                    lastActivityAt = session.started_at,
                    userTurns = userTurns,
                ),
            failed = false,
        )
    }

    companion object {
        /** Concurrent per-bot scans — same cap the roster fan-out uses. */
        private const val DM_FAN_OUT_LIMIT = 12

        /**
         * User turns scanned per thread. Wide enough that a bot's own prompts
         * between two deliveries do not hide them, small enough to stay one
         * cheap page per bot.
         */
        private const val DM_SCAN_WINDOW = 20
    }
}
