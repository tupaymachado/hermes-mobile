package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionListResponse
import com.m57.hermescontrol.data.model.SessionRenameRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The registry is Bot Mode's single source of truth for "which session IS the
 * conversation with bot X". These tests pin the three behaviors the rest of
 * the feature leans on: the fallback ORDER (local map beats the pinned scan
 * beats null), the DEFERRED pin (a fresh session has no server row yet, so an
 * eager PATCH would 404), and the self-heal on a dead session.
 */
class BotChatRegistryTest {
    private lateinit var mockApi: HermesApiService
    private val stored = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi

        // A tiny in-memory stand-in for the DataStore-backed map, so the tests
        // assert on observable state instead of only on call counts.
        stored.clear()
        mockkObject(AuthManager)
        every { AuthManager.getBotChatSessionId(any()) } answers { stored[firstArg()] }
        every { AuthManager.setBotChatSessionId(any(), any()) } answers {
            stored[firstArg()] = secondArg()
        }
        every { AuthManager.clearBotChatSession(any()) } answers {
            stored.remove(firstArg<String>())
            Unit
        }

        BotChatRegistry.clearPendingPinsForTest()
    }

    @After
    fun tearDown() {
        BotChatRegistry.clearPendingPinsForTest()
        unmockkAll()
    }

    // ── Fallback order ───────────────────────────────────────────────────

    @Test
    fun `local map wins without touching the network`() =
        runTest {
            stored["scribe"] = "sess-local"

            assertEquals("sess-local", BotChatRegistry.resolveCanonicalSessionId("scribe"))

            coVerify(exactly = 0) { mockApi.getSessions(any(), any(), any(), any()) }
        }

    @Test
    fun `no local entry falls back to the most recent pinned session`() =
        runTest {
            coEvery { mockApi.getSessions(any(), any(), any(), any()) } returns
                Response.success(
                    SessionListResponse(
                        sessions =
                            listOf(
                                session("sess-loud", pinned = false, startedAt = 300.0),
                                session("sess-old-pin", pinned = true, startedAt = 100.0),
                                session("sess-new-pin", pinned = true, startedAt = 200.0),
                            ),
                    ),
                )

            assertEquals("sess-new-pin", BotChatRegistry.resolveCanonicalSessionId("scribe"))
        }

    @Test
    fun `pinned scan is profile-scoped explicitly so no profile switch is needed`() =
        runTest {
            coEvery { mockApi.getSessions(any(), any(), any(), any()) } returns
                Response.success(SessionListResponse(sessions = listOf(session("sess-pin", pinned = true))))

            BotChatRegistry.resolveCanonicalSessionId("scribe")

            coVerify { mockApi.getSessions(50, 0, "recent", "scribe") }
        }

    @Test
    fun `recovered session is written back to the local map`() =
        runTest {
            coEvery { mockApi.getSessions(any(), any(), any(), any()) } returns
                Response.success(SessionListResponse(sessions = listOf(session("sess-pin", pinned = true))))

            BotChatRegistry.resolveCanonicalSessionId("scribe")

            assertEquals("sess-pin", stored["scribe"])
        }

    @Test
    fun `profile with no pinned session resolves to null`() =
        runTest {
            coEvery { mockApi.getSessions(any(), any(), any(), any()) } returns
                Response.success(SessionListResponse(sessions = listOf(session("sess-a", pinned = false))))

            assertNull(BotChatRegistry.resolveCanonicalSessionId("scribe"))
            verify(exactly = 0) { AuthManager.setBotChatSessionId(any(), any()) }
        }

    @Test
    fun `profile with no sessions at all resolves to null`() =
        runTest {
            coEvery { mockApi.getSessions(any(), any(), any(), any()) } returns
                Response.success(SessionListResponse(sessions = emptyList()))

            assertNull(BotChatRegistry.resolveCanonicalSessionId("fresh-bot"))
        }

    @Test
    fun `failed scan degrades to null instead of throwing`() =
        runTest {
            coEvery { mockApi.getSessions(any(), any(), any(), any()) } returns
                Response.error(500, "{}".toResponseBody(null))

            assertNull(BotChatRegistry.resolveCanonicalSessionId("scribe"))
        }

    // ── Deferred pin ─────────────────────────────────────────────────────

    @Test
    fun `adopt persists the map but does NOT pin yet`() =
        runTest {
            BotChatRegistry.adopt("scribe", "sess-new")

            assertEquals("sess-new", stored["scribe"])
            // session.create writes no server row until the first prompt — an
            // eager PATCH /api/sessions/{id} would 404.
            coVerify(exactly = 0) { mockApi.setSessionPinned(any(), any()) }
        }

    @Test
    fun `flush pins the adopted session once server presence is confirmed`() =
        runTest {
            coEvery { mockApi.setSessionPinned(any(), any()) } returns Response.success(Unit)
            BotChatRegistry.adopt("scribe", "sess-new")

            BotChatRegistry.flushPendingPin("sess-new")

            coVerify(exactly = 1) { mockApi.setSessionPinned("sess-new", SessionRenameRequest(pinned = true)) }
        }

    @Test
    fun `flush is a no-op for a session that was never adopted`() =
        runTest {
            BotChatRegistry.flushPendingPin("sess-unknown")

            coVerify(exactly = 0) { mockApi.setSessionPinned(any(), any()) }
        }

    @Test
    fun `a flushed pin is not sent again on later presence signals`() =
        runTest {
            coEvery { mockApi.setSessionPinned(any(), any()) } returns Response.success(Unit)
            BotChatRegistry.adopt("scribe", "sess-new")

            BotChatRegistry.flushPendingPin("sess-new")
            BotChatRegistry.flushPendingPin("sess-new")

            coVerify(exactly = 1) { mockApi.setSessionPinned(any(), any()) }
        }

    @Test
    fun `a failed pin stays pending and retries on the next presence signal`() =
        runTest {
            coEvery { mockApi.setSessionPinned(any(), any()) } returns Response.error(404, "{}".toResponseBody(null))
            BotChatRegistry.adopt("scribe", "sess-new")

            BotChatRegistry.flushPendingPin("sess-new")
            coEvery { mockApi.setSessionPinned(any(), any()) } returns Response.success(Unit)
            BotChatRegistry.flushPendingPin("sess-new")

            coVerify(exactly = 2) { mockApi.setSessionPinned("sess-new", SessionRenameRequest(pinned = true)) }
        }

    // ── Invalidation (self-heal) ─────────────────────────────────────────

    @Test
    fun `invalidate forgets a dead canonical session`() =
        runTest {
            stored["scribe"] = "sess-gone"
            coEvery { mockApi.getSessions(any(), any(), any(), any()) } returns
                Response.success(SessionListResponse(sessions = emptyList()))

            BotChatRegistry.invalidate("scribe", "sess-gone")

            assertNull(stored["scribe"])
            assertNull(BotChatRegistry.resolveCanonicalSessionId("scribe"))
        }

    @Test
    fun `invalidate leaves a newer adoption alone`() =
        runTest {
            stored["scribe"] = "sess-new"

            BotChatRegistry.invalidate("scribe", "sess-old")

            assertEquals("sess-new", stored["scribe"])
            verify(exactly = 0) { AuthManager.clearBotChatSession(any()) }
        }

    @Test
    fun `invalidate drops the pending pin so a dead session is never pinned`() =
        runTest {
            BotChatRegistry.adopt("scribe", "sess-gone")

            BotChatRegistry.invalidate("scribe", "sess-gone")
            BotChatRegistry.flushPendingPin("sess-gone")

            coVerify(exactly = 0) { mockApi.setSessionPinned(any(), any()) }
        }

    private fun session(
        id: String,
        pinned: Boolean,
        startedAt: Double? = null,
    ): SessionInfo = SessionInfo(id = id, pinned = pinned, started_at = startedAt)
}
