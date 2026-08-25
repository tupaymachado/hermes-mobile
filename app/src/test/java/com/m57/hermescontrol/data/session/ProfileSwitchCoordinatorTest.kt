package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SetActiveProfileRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.ws.HermesWsClient
import io.mockk.Ordering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The switch coordinator is the single atomic profile-switch flow — every
 * surface (Profiles screen, future quick-switch) routes through it. These
 * tests pin the ORDER of operations, because chat's fresh-session behavior
 * depends on the switch broadcast landing BEFORE the socket re-dial.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSwitchCoordinatorTest {
    private lateinit var mockApi: HermesApiService

    @Before
    fun setUp() {
        // NOTE: no mockkStatic(Dispatchers) here — a static Dispatchers mock
        // bleeds into later test classes in the same JVM (it hijacks
        // Dispatchers.IO for HermesWsClient's reconnect coroutines), which
        // deterministically broke HermesWsClientTest.testAutoReconnect in
        // full-suite runs. Real Dispatchers.IO is fine: the network layer is
        // mocked, so withContext(Dispatchers.IO) hops are instant.

        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi

        mockkObject(AuthManager)
        every { AuthManager.setActiveProfileId(any()) } returns Unit

        mockkObject(HermesWsClient)
        every { HermesWsClient.disconnect() } returns Unit
        every { HermesWsClient.connect() } returns Unit

        // The coordinator is an object: a handoff armed by a previous test
        // would leak into this one. Drain it.
        ProfileSwitchCoordinator.consumePendingBotSession()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `success flips server then persists selection then re-dials socket`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            val result = ProfileSwitchCoordinator.switchProfile("meow")

            assertTrue(result is NetworkResult.Success)
            coVerify { mockApi.setActiveProfile(SetActiveProfileRequest("meow")) }
            verify(ordering = Ordering.SEQUENCE) {
                AuthManager.setActiveProfileId("meow")
                HermesWsClient.disconnect()
                HermesWsClient.connect()
            }
        }

    @Test
    fun `success broadcasts the switch so chat wipes before the re-dial`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)
            // Subscribe BEFORE the switch — exactly how ChatViewModel does it.
            val received = Channel<String>(Channel.UNLIMITED)
            backgroundScope.launch {
                ProfileSwitchCoordinator.switched.collect { received.send(it) }
            }
            runCurrent()

            ProfileSwitchCoordinator.switchProfile("meow")
            runCurrent()

            // The broadcast is buffered with capacity 1, so chat observes the
            // wipe BEFORE gateway.ready arrives on the re-dialed socket and
            // auto-creates the fresh session (desktop requestFreshSession).
            assertEquals("meow", received.tryReceive().getOrNull())
        }

    @Test
    fun `failure touches nothing`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns errorResponse(500)

            val result = ProfileSwitchCoordinator.switchProfile("meow")

            assertTrue(result is NetworkResult.Failure)
            verify(exactly = 0) { AuthManager.setSelectedProfileId(any()) }
            verify(exactly = 0) { AuthManager.setActiveProfileId(any()) }
            verify(exactly = 0) { HermesWsClient.disconnect() }
            verify(exactly = 0) { HermesWsClient.connect() }
        }

    @Test
    fun `connection switch re-homes selection retrofit and socket in order`() =
        runTest {
            every { AuthManager.setSelectedProfileId(any()) } returns Unit
            every { ApiClient.rebuild() } returns Unit
            coEvery { AuthManager.syncCookieStoreForProfile(any()) } returns Unit

            ProfileSwitchCoordinator.switchConnectionProfile("prof-2")

            // Load-bearing order: selection persists FIRST (token cache +
            // cookie scope follow), Retrofit re-points, the cookie store swap
            // is AWAITED (a racing dial mints the ticket with the previous
            // server's cookie → 401 → dead socket), then the socket re-dials
            // — so chat's wipe (via the broadcast) lands before the new
            // gateway's gateway.ready auto-creates the fresh session.
            coVerify(ordering = Ordering.SEQUENCE) {
                AuthManager.setSelectedProfileId("prof-2")
                ApiClient.rebuild()
                AuthManager.syncCookieStoreForProfile("prof-2")
                HermesWsClient.disconnect()
                HermesWsClient.connect()
            }
        }

    @Test
    fun `connection switch broadcasts so chat wipes before the re-dial`() =
        runTest {
            every { AuthManager.setSelectedProfileId(any()) } returns Unit
            every { ApiClient.rebuild() } returns Unit
            coEvery { AuthManager.syncCookieStoreForProfile(any()) } returns Unit
            // Subscribe BEFORE the switch — exactly how ChatViewModel does it.
            val received = Channel<String>(Channel.UNLIMITED)
            backgroundScope.launch {
                ProfileSwitchCoordinator.connectionSwitched.collect { received.send(it) }
            }
            runCurrent()

            ProfileSwitchCoordinator.switchConnectionProfile("prof-2")
            runCurrent()

            assertEquals("prof-2", received.tryReceive().getOrNull())
        }

    // ── Bot Mode (Fase 2): canonical-chat handoff ────────────────────────

    @Test
    fun `bot switch hands the canonical chat over before the socket re-dials`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)
            val order = mutableListOf<String>()
            every { HermesWsClient.disconnect() } answers { order.add("re-dial") }
            // Unconfined: the collector runs INSIDE emit(), so what it sees is
            // exactly what ChatViewModel's collector sees at broadcast time.
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                ProfileSwitchCoordinator.switched.collect {
                    val pending = ProfileSwitchCoordinator.consumePendingBotSession()
                    order.add("handoff=${pending?.profile}/${pending?.sessionId}")
                }
            }
            runCurrent()

            ProfileSwitchCoordinator.switchProfile("meow", "sess-canon")
            runCurrent()

            // Load-bearing: the target is armed BEFORE the broadcast, and the
            // broadcast lands BEFORE the re-dial — so handleGatewayReady on the
            // re-dialed socket already knows which thread to resume.
            assertEquals(listOf("handoff=meow/sess-canon", "re-dial"), order)
        }

    @Test
    fun `the bot handoff is consumed exactly once`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            ProfileSwitchCoordinator.switchProfile("meow", "sess-canon")

            assertEquals("sess-canon", ProfileSwitchCoordinator.consumePendingBotSession()?.sessionId)
            // Single-use: a later reconnect (or a second collector) must not
            // reopen the same thread on top of whatever the user moved to.
            assertNull(ProfileSwitchCoordinator.consumePendingBotSession())
        }

    @Test
    fun `a bot with no canonical chat still arms the handoff`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            ProfileSwitchCoordinator.switchProfile("meow", targetSessionId = null, isBotContext = true)

            // The chat needs the PROFILE even without a target: the session it
            // creates is what gets adopted as that bot's canonical chat.
            val pending = ProfileSwitchCoordinator.consumePendingBotSession()
            assertEquals("meow", pending?.profile)
            assertNull(pending?.sessionId)
        }

    @Test
    fun `a blank target is normalized to no target`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            ProfileSwitchCoordinator.switchProfile("meow", "   ")

            assertNull(ProfileSwitchCoordinator.consumePendingBotSession()?.sessionId)
        }

    @Test
    fun `a plain profile switch arms nothing and clears a stale handoff`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)
            // A bot switch whose handoff nobody consumed (chat not listening).
            ProfileSwitchCoordinator.switchProfile("meow", "sess-canon")

            ProfileSwitchCoordinator.switchProfile("plain")

            // Stale targets must never leak into a switch made from elsewhere
            // (Profiles screen) — that would reopen a bot thread unasked.
            assertNull(ProfileSwitchCoordinator.consumePendingBotSession())
        }

    @Test
    fun `a failed bot switch arms no handoff`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns errorResponse(500)

            val result = ProfileSwitchCoordinator.switchProfile("meow", "sess-canon")

            assertTrue(result is NetworkResult.Failure)
            assertNull(ProfileSwitchCoordinator.consumePendingBotSession())
        }

    @Test
    fun `the plain switch still works untouched`() =
        runTest {
            coEvery { mockApi.setActiveProfile(any()) } returns Response.success(Unit)

            val result = ProfileSwitchCoordinator.switchProfile("meow")

            assertTrue(result is NetworkResult.Success)
            verify(ordering = Ordering.SEQUENCE) {
                AuthManager.setActiveProfileId("meow")
                HermesWsClient.disconnect()
                HermesWsClient.connect()
            }
        }

    private fun <T> errorResponse(code: Int): Response<T> = Response.error(code, "{}".toResponseBody(null))
}
