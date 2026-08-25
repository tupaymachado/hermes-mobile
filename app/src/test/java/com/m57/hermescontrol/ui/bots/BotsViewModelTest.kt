package com.m57.hermescontrol.ui.bots

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ActiveProfileResponse
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.model.ProfilesResponse
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionListResponse
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.model.SessionMessagesResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkError
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import com.m57.hermescontrol.data.ws.HermesWsClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Bot Mode Fase 1 — the roster.
 *
 * The contracts that matter here are the ones the plan validated against a
 * live gateway (24/ago/2026):
 *  - last message comes from `GET /api/sessions/{id}/messages`, NOT from
 *    `SessionInfo.preview` (which holds the FIRST user prompt);
 *  - the per-bot fan-out is scoped with an EXPLICIT `?profile=`, so reading
 *    another bot never switches the active profile;
 *  - a per-bot lookup that fails degrades that ROW only — the roster itself
 *    must still render.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    @Before
    fun setUp() {
        // setMain only — no mockkStatic(Dispatchers), which bleeds across test
        // classes in the same JVM (see ProfilesViewModelTest).
        Dispatchers.setMain(testDispatcher)

        mockkObject(AuthManager)
        every { AuthManager.getToken() } returns "tok-abc"

        mockkObject(HermesWsClient)
        every { HermesWsClient.disconnect() } returns Unit
        every { HermesWsClient.connect() } returns Unit

        mockkObject(ProfileSwitchCoordinator)
        coEvery { ProfileSwitchCoordinator.switchProfile(any()) } returns NetworkResult.Success(Unit)

        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi

        stubRoster(ProfileInfo(name = "default", gateway_running = true))
        stubNoSessions()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private fun stubRoster(
        vararg profiles: ProfileInfo,
        active: String = "default",
    ) {
        coEvery { mockApi.getProfiles() } returns Response.success(ProfilesResponse(profiles.toList()))
        coEvery { mockApi.getActiveProfile() } returns Response.success(ActiveProfileResponse(active = active))
    }

    private fun stubNoSessions() {
        coEvery { mockApi.getSessions(any(), any(), any(), any()) } returns
            Response.success(SessionListResponse(sessions = emptyList()))
    }

    /** Wires one bot's session + its last user message. */
    private fun stubBotChat(
        profile: String,
        sessionId: String,
        lastUserMessage: String?,
        startedAt: Double? = null,
        preview: String? = null,
    ) {
        coEvery { mockApi.getSessions(any(), any(), any(), profile) } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo(id = sessionId, preview = preview, started_at = startedAt)),
                ),
            )
        coEvery { mockApi.getSessionMessages(sessionId, any(), any(), any(), any(), any()) } returns
            if (lastUserMessage == null) {
                Response.error(500, "{}".toResponseBody(null))
            } else {
                Response.success(
                    SessionMessagesResponse(
                        messages = listOf(SessionMessage(role = "user", content = JsonPrimitive(lastUserMessage))),
                    ),
                )
            }
    }

    private fun <T> errorResponse(code: Int): Response<T> = Response.error(code, "{}".toResponseBody(null))

    private fun loadedViewModel(): BotsViewModel {
        val vm = BotsViewModel(ioDispatcher = testDispatcher)
        vm.loadRoster()
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    // ── Roster shape ─────────────────────────────────────────────────────

    @Test
    fun `roster maps profiles with presence and marks the active bot`() {
        stubRoster(
            ProfileInfo(name = "default", description = "Main", gateway_running = true),
            ProfileInfo(name = "research", gateway_running = false),
            ProfileInfo(name = "legacy"),
            active = "default",
        )

        val state = loadedViewModel().uiState.value

        assertEquals("default", state.activeBot)
        assertNull(state.errorMessage)
        val byName = state.bots.associateBy { it.name }
        // ACTIVE outranks ONLINE for the bot the app is homed on.
        assertEquals(BotPresence.ACTIVE, byName.getValue("default").presence)
        assertTrue(byName.getValue("default").isActive)
        assertEquals("Main", byName.getValue("default").description)
        assertEquals(BotPresence.OFFLINE, byName.getValue("research").presence)
        // gateway_running absent (older gateway) must not read as OFFLINE.
        assertEquals(BotPresence.UNKNOWN, byName.getValue("legacy").presence)
        assertFalse(byName.getValue("legacy").isActive)
    }

    @Test
    fun `last message comes from the messages endpoint, not from preview`() {
        stubRoster(ProfileInfo(name = "default"))
        stubBotChat(
            profile = "default",
            sessionId = "sess-1",
            lastUserMessage = "what is the deploy status?",
            // preview holds the FIRST prompt of the session — showing it would
            // be the exact bug this test guards against.
            preview = "hello, who are you?",
        )

        val bot = loadedViewModel().uiState.value.bots.single()

        assertEquals("what is the deploy status?", bot.lastMessage)
    }

    @Test
    fun `the per-bot fan-out is scoped with an explicit profile param`() {
        stubRoster(ProfileInfo(name = "default"), ProfileInfo(name = "research"))

        loadedViewModel()

        // Explicit profile= is what lets the roster read another bot WITHOUT
        // flipping the active profile (ProfileScopeInterceptor honours it).
        coVerify { mockApi.getSessions(1, 0, "recent", "default") }
        coVerify { mockApi.getSessions(1, 0, "recent", "research") }
        coVerify(exactly = 0) { ProfileSwitchCoordinator.switchProfile(any()) }
    }

    @Test
    fun `last user message is requested newest-first and role-filtered`() {
        stubRoster(ProfileInfo(name = "default"))
        stubBotChat("default", "sess-1", "hi")

        loadedViewModel()

        coVerify {
            mockApi.getSessionMessages(
                sessionId = "sess-1",
                limit = 1,
                offset = any(),
                order = "latest",
                includeCompacted = any(),
                role = "user",
            )
        }
    }

    @Test
    fun `structured content blocks flatten to one line`() {
        stubRoster(ProfileInfo(name = "default"))
        coEvery { mockApi.getSessions(any(), any(), any(), "default") } returns
            Response.success(SessionListResponse(sessions = listOf(SessionInfo(id = "sess-1"))))
        coEvery { mockApi.getSessionMessages("sess-1", any(), any(), any(), any(), any()) } returns
            Response.success(
                SessionMessagesResponse(
                    messages =
                        listOf(
                            SessionMessage(
                                role = "user",
                                content =
                                    JsonArray(
                                        listOf(
                                            JsonObject(
                                                mapOf(
                                                    "type" to JsonPrimitive("text"),
                                                    "text" to JsonPrimitive("ship  it\nplease"),
                                                ),
                                            ),
                                        ),
                                    ),
                            ),
                        ),
                ),
            )

        val bot = loadedViewModel().uiState.value.bots.single()

        assertEquals("ship it please", bot.lastMessage)
    }

    @Test
    fun `bots are ordered by most recent activity`() {
        stubRoster(ProfileInfo(name = "quiet"), ProfileInfo(name = "busy"), ProfileInfo(name = "never-used"))
        stubBotChat("quiet", "sess-q", "old news", startedAt = 100.0)
        stubBotChat("busy", "sess-b", "fresh news", startedAt = 900.0)

        val names = loadedViewModel().uiState.value.bots.map { it.name }

        // never-used has no session at all, so it sorts last rather than first.
        assertEquals(listOf("busy", "quiet", "never-used"), names)
    }

    // ── Degradation ──────────────────────────────────────────────────────

    @Test
    fun `a bot with no sessions still renders, with no last message`() {
        stubRoster(ProfileInfo(name = "fresh"))

        val state = loadedViewModel().uiState.value

        assertEquals(1, state.bots.size)
        assertNull(state.bots.single().lastMessage)
        assertNull(state.errorMessage)
    }

    @Test
    fun `a failed per-bot lookup degrades only that row`() {
        stubRoster(ProfileInfo(name = "healthy"), ProfileInfo(name = "broken"))
        stubBotChat("healthy", "sess-h", "still here", startedAt = 10.0)
        // Both of this bot's lookups fail — the roster must survive it.
        coEvery { mockApi.getSessions(any(), any(), any(), "broken") } returns errorResponse(500)

        val state = loadedViewModel().uiState.value

        assertNull(state.errorMessage)
        assertEquals(2, state.bots.size)
        assertEquals("still here", state.bots.first { it.name == "healthy" }.lastMessage)
        assertNull(state.bots.first { it.name == "broken" }.lastMessage)
    }

    @Test
    fun `a failed messages lookup degrades only that row`() {
        stubRoster(ProfileInfo(name = "default"))
        stubBotChat("default", "sess-1", lastUserMessage = null, startedAt = 42.0)

        val bot = loadedViewModel().uiState.value.bots.single()

        assertNull(bot.lastMessage)
        // The session itself was readable, so recency survives the failure.
        assertEquals(42.0, bot.lastActivityAt!!, 0.0)
    }

    @Test
    fun `a failed profiles load surfaces an error`() {
        coEvery { mockApi.getProfiles() } returns errorResponse(500)

        val state = loadedViewModel().uiState.value

        assertTrue(state.bots.isEmpty())
        assertTrue(state.errorMessage!!.contains("Failed to load bots"))
        assertFalse(state.isLoading)
    }

    @Test
    fun `a failed active-profile lookup still yields a roster`() {
        stubRoster(ProfileInfo(name = "default", gateway_running = true))
        coEvery { mockApi.getActiveProfile() } returns errorResponse(500)

        val state = loadedViewModel().uiState.value

        // Knowing WHICH bot is active is a nicety; the list itself is the value.
        assertNull(state.errorMessage)
        assertNull(state.activeBot)
        assertEquals(BotPresence.ONLINE, state.bots.single().presence)
    }

    // ── Selection ────────────────────────────────────────────────────────

    @Test
    fun `selecting a bot switches through the coordinator and reloads`() {
        stubRoster(ProfileInfo(name = "default"), ProfileInfo(name = "research"), active = "default")

        val vm = loadedViewModel()
        vm.selectBot("research")
        testDispatcher.scheduler.advanceUntilIdle()

        // The coordinator is the ONLY legitimate switch path (REST flip →
        // persist → broadcast → socket re-dial).
        coVerify(exactly = 1) { ProfileSwitchCoordinator.switchProfile("research") }
        assertTrue(vm.uiState.value.toastMessage!!.contains("research"))
    }

    @Test
    fun `selecting the already-active bot is a no-op`() {
        stubRoster(ProfileInfo(name = "default"), active = "default")

        val vm = loadedViewModel()
        vm.selectBot("default")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { ProfileSwitchCoordinator.switchProfile(any()) }
        assertNull(vm.uiState.value.toastMessage)
    }

    @Test
    fun `a failed switch rolls the optimistic selection back`() {
        stubRoster(ProfileInfo(name = "default"), ProfileInfo(name = "research"), active = "default")
        coEvery { ProfileSwitchCoordinator.switchProfile("research") } returns
            NetworkResult.Failure(NetworkError.Http(500, "boom"))

        val vm = loadedViewModel()
        vm.selectBot("research")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("default", state.activeBot)
        assertTrue(state.bots.first { it.name == "default" }.isActive)
        assertFalse(state.bots.first { it.name == "research" }.isActive)
        assertTrue(state.toastMessage!!.contains("Failed to switch"))
    }
}
