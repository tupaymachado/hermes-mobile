package com.m57.hermescontrol.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.CronJobsScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.ui.bots.flatText
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

data class ActivityUiState(
    val items: List<ActivityItem> = emptyList(),
    val activeBot: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** Bots whose thread could not be scanned — surfaced, never swallowed. */
    val unscannedBots: List<String> = emptyList(),
    /** The cron list failed: the feed stands, minus its routine rows. */
    val routinesUnavailable: Boolean = false,
    val toastMessage: String? = null,
)

/**
 * The Activity feed: one chronological view of what moved across every bot and
 * routine, and the middle tab of the bottom-nav shell.
 *
 * **Load shape.** The same 2N+1 fan-out the roster and the Bot DMs screen use
 * — profiles + active in parallel, then per bot its newest session and the last
 * [SCAN_WINDOW] user turns of that thread — plus ONE cron list. So 2N+3
 * requests, capped at [FAN_OUT_LIMIT] concurrent bots. Scanning the canonical
 * Bot Chat (Fase 2) rather than sweeping history is what makes the screen
 * affordable: O(bots), not O(sessions).
 *
 * **Consequence, stated rather than hidden:** activity older than the scanned
 * window, or living in a bot's non-canonical threads, does not list. This is a
 * "what is going on" view, not an audit log.
 *
 * **Failure policy.** Only the profiles call is fatal. A per-bot scan that
 * fails drops that bot into [ActivityUiState.unscannedBots]; a cron list that
 * fails sets [ActivityUiState.routinesUnavailable]. Both leave the feed
 * standing — a partial feed that says so beats an error page.
 */
class ActivityViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(),
    ToastHost {
    private val _uiState = MutableStateFlow(ActivityUiState())
    val uiState: StateFlow<ActivityUiState> = _uiState.asStateFlow()

    init {
        // Two signatures move this feed: any finished turn (`sessions.changed`)
        // and any routine run (`cron.changed`). One collector over both, so a
        // burst on the pair cannot start two concurrent fan-outs.
        refreshOnChange(
            eventTypes = setOf(ChangeEvents.SESSIONS, ChangeEvents.CRON),
            apiCall = { fetchFeed() },
            onSuccess = { snapshot -> _uiState.update { it.applied(snapshot) } },
        )
    }

    fun loadFeed() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = fetchFeed()) {
                is NetworkResult.Success ->
                    _uiState.update { it.copy(isLoading = false).applied(result.data) }

                is NetworkResult.Failure ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Failed to load activity: ${result.error.message}",
                        )
                    }
            }
        }
    }

    /**
     * Opens what [item] is about.
     *
     * A bot row belongs to its bot's profile scope, so reaching its thread
     * means switching the active profile first — with the thread as the
     * switch's target session, exactly as the roster and the Bot DMs screen do,
     * so `ChatViewModel` resumes it on `gateway.ready` instead of creating a
     * new session. A routine has no chat: it opens the cron screen.
     */
    fun openItem(item: ActivityItem) {
        val sessionId = item.sessionId
        val botName = item.botName
        if (sessionId == null || botName == null) {
            NavigationController.navigateTo(CronJobsScreen)
            return
        }
        if (botName == _uiState.value.activeBot) {
            NavigationController.openChatSession(sessionId)
            return
        }
        viewModelScope.launch {
            val result =
                ProfileSwitchCoordinator.switchProfile(botName, sessionId, isBotContext = true)
            when (result) {
                is NetworkResult.Success -> {
                    _uiState.update { it.copy(activeBot = botName) }
                    NavigationController.navigateTo(ChatScreen)
                }

                is NetworkResult.Failure ->
                    _uiState.update {
                        it.copy(toastMessage = "Failed to open $botName: ${result.error.message}")
                    }
            }
        }
    }

    override fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    // ── Loading ──────────────────────────────────────────────────────────

    private data class FeedSnapshot(
        val items: List<ActivityItem>,
        val activeBot: String?,
        val unscannedBots: List<String>,
        val routinesUnavailable: Boolean,
    )

    private fun ActivityUiState.applied(snapshot: FeedSnapshot) =
        copy(
            items = snapshot.items,
            activeBot = snapshot.activeBot,
            unscannedBots = snapshot.unscannedBots,
            routinesUnavailable = snapshot.routinesUnavailable,
        )

    /** One bot's scan: what it contributed, or the fact that it failed. */
    private data class BotScan(
        val name: String,
        val items: List<ActivityItem>,
        val failed: Boolean,
    )

    private suspend fun fetchFeed(): NetworkResult<FeedSnapshot> =
        coroutineScope {
            val profilesDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getProfiles() } }
            val activeDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getActiveProfile() } }
            val cronDeferred = async(ioDispatcher) { safeApiCall { ApiClient.hermesApi.getCronJobs() } }

            val profilesResult = profilesDeferred.await()
            val activeResult = activeDeferred.await()
            val cronResult = cronDeferred.await()

            if (profilesResult !is NetworkResult.Success) {
                return@coroutineScope profilesResult as NetworkResult.Failure
            }
            // Missing /active only costs the "no switch needed" shortcut in
            // [openItem]; the feed itself is still correct without it.
            val activeName = (activeResult as? NetworkResult.Success)?.data?.active

            val profiles = profilesResult.data.profiles.orEmpty()
            val gate = Semaphore(FAN_OUT_LIMIT)
            val scans =
                profiles
                    .map { profile -> async(ioDispatcher) { gate.withPermit { profile.scan() } } }
                    .awaitAll()

            val routineItems =
                (cronResult as? NetworkResult.Success)?.data.orEmpty().mapNotNull { job ->
                    routineActivity(
                        jobId = job.id,
                        name = job.name,
                        // Two spellings of the same field across gateway
                        // versions; neither is guaranteed, so both are tried.
                        lastRunAt = job.last_run_at,
                        lastRunStatus = job.last_run_status ?: job.last_status,
                        scheduleDisplay = job.schedule_display,
                    )
                }

            NetworkResult.Success(
                FeedSnapshot(
                    items = mergeActivity(scans.flatMap { it.items } + routineItems),
                    activeBot = activeName,
                    unscannedBots = scans.filter { it.failed }.map { it.name },
                    routinesUnavailable = cronResult !is NetworkResult.Success,
                ),
            )
        }

    /**
     * Scans one bot's canonical thread. An explicit `profile=` reads it WITHOUT
     * switching the active profile (`ProfileScopeInterceptor` honours the
     * explicit param).
     */
    private suspend fun ProfileInfo.scan(): BotScan {
        val sessionsResult =
            safeApiCall {
                ApiClient.hermesApi.getSessions(limit = 1, offset = 0, order = "recent", profile = name)
            }
        if (sessionsResult !is NetworkResult.Success) {
            return BotScan(name = name, items = emptyList(), failed = true)
        }
        // No session is not a failure — the bot simply has nothing yet.
        val session =
            sessionsResult.data.sessions?.firstOrNull()
                ?: return BotScan(name = name, items = emptyList(), failed = false)

        val messagesResult =
            safeApiCall {
                ApiClient.hermesApi.getSessionMessages(
                    sessionId = session.id,
                    limit = SCAN_WINDOW,
                    // Newest-anchored page, returned chronologically.
                    order = "latest",
                    role = "user",
                )
            }
        if (messagesResult !is NetworkResult.Success) {
            return BotScan(name = name, items = emptyList(), failed = true)
        }

        val turns =
            messagesResult.data.messages.mapNotNull { message ->
                message.content?.flatText()?.let { text ->
                    ActivityTurn(text = text, timestamp = parseTimestamp(message.timestampText))
                }
            }
        return BotScan(
            name = name,
            items =
                botActivity(
                    botName = name,
                    sessionId = session.id,
                    // Fallback only: a backend that omits per-message stamps
                    // still dates the row, at thread granularity.
                    sessionStartedAt = session.started_at,
                    turns = turns,
                ),
            failed = false,
        )
    }

    companion object {
        /** Concurrent per-bot scans — same cap the roster fan-out uses. */
        private const val FAN_OUT_LIMIT = 12

        /** User turns scanned per thread — one cheap page per bot. */
        private const val SCAN_WINDOW = 20
    }
}
