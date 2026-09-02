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
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The 1:N sibling of `BotChatRegistryTest`, pinning the same three behaviours
 * per member: fallback ORDER (local map beats the title scan beats null), the
 * DEFERRED rename (§V3 — a fresh session has no server row, so an eager PATCH
 * 404s), and the self-heal on a dead session.
 *
 * Plus the one that is specific to rooms: the durable marker is the TITLE and
 * never `pinned`, because a bot's pinned slot already means "my 1:1 canonical".
 */
class GroupRoomCoordinatorTest {
    private lateinit var mockApi: HermesApiService

    /** In-memory stand-in for the persisted room map: (roomId, member) → session. */
    private val stored = mutableMapOf<Pair<String, String>, String>()

    private val room =
        GroupRoom(id = "r1", name = "Deploy squad", members = listOf("coder", "writer"))

    @Before
    fun setUp() {
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi

        stored.clear()
        mockkObject(AuthManager)
        every { AuthManager.getGroupSessionId(any(), any()) } answers {
            stored[firstArg<String>() to secondArg<String>()]
        }
        every { AuthManager.setGroupSessionId(any(), any(), any()) } answers {
            stored[firstArg<String>() to secondArg<String>()] = thirdArg()
            Unit
        }
        every { AuthManager.clearGroupSession(any(), any()) } answers {
            stored.remove(firstArg<String>() to secondArg<String>())
            Unit
        }

        GroupRoomCoordinator.clearPendingTitlesForTest()
    }

    @After
    fun tearDown() {
        GroupRoomCoordinator.clearPendingTitlesForTest()
        unmockkAll()
    }

    // ── resolve: fallback order ───────────────────────────────────────────

    @Test
    fun `the local map wins and costs no request`() =
        runTest {
            stored["r1" to "coder"] = "sess-local"

            assertEquals("sess-local", GroupRoomCoordinator.resolveMemberSessionId(room, "coder"))

            coVerify(exactly = 0) { mockApi.getSessions(any(), any(), any(), any()) }
        }

    @Test
    fun `an empty map recovers the member thread by title`() =
        runTest {
            stubSessions(
                session("other", title = "Some other chat", startedAt = 900.0),
                session("sess-room", title = "Group: Deploy squad", startedAt = 100.0),
            )

            val resolved = GroupRoomCoordinator.resolveMemberSessionId(room, "coder")

            assertEquals("sess-room", resolved)
            // Written back, so the next resolve is offline-cheap.
            assertEquals("sess-room", stored["r1" to "coder"])
        }

    @Test
    fun `the newest matching thread wins`() =
        runTest {
            stubSessions(
                session("old", title = "Group: Deploy squad", startedAt = 100.0),
                session("new", title = "Group: Deploy squad", startedAt = 500.0),
            )

            assertEquals("new", GroupRoomCoordinator.resolveMemberSessionId(room, "coder"))
        }

    @Test
    fun `a room with no thread anywhere resolves to null`() =
        runTest {
            stubSessions(session("unrelated", title = "Daily standup", startedAt = 100.0))

            assertNull(GroupRoomCoordinator.resolveMemberSessionId(room, "coder"))
        }

    @Test
    fun `a cron session never becomes a room thread`() =
        runTest {
            // §V12: a scheduled run can never be a room thread, and matching one
            // would hand the room a thread nobody spoke in.
            stubSessions(
                session("cron_x", title = "Group: Deploy squad", startedAt = 900.0, source = "cron"),
                session("real", title = "Group: Deploy squad", startedAt = 100.0),
            )

            assertEquals("real", GroupRoomCoordinator.resolveMemberSessionId(room, "coder"))
        }

    @Test
    fun `the scan is scoped with an explicit profile param`() =
        runTest {
            stubSessions()

            GroupRoomCoordinator.resolveMemberSessionId(room, "writer")

            // §V2: reads the member's sessions WITHOUT flipping the active profile.
            coVerify { mockApi.getSessions(any(), 0, "recent", "writer") }
        }

    @Test
    fun `a member who left the room resolves to nothing`() =
        runTest {
            stored["r1" to "researcher"] = "sess-stale"

            // Removing a bot must stop routing to it, even while the map
            // remembers its thread.
            assertNull(GroupRoomCoordinator.resolveMemberSessionId(room, "researcher"))
        }

    // ── deferred rename (§V3) ─────────────────────────────────────────────

    @Test
    fun `adopt records the thread without touching the server`() =
        runTest {
            GroupRoomCoordinator.adopt(room, "coder", "sess-new")

            assertEquals("sess-new", stored["r1" to "coder"])
            // The PATCH would 404: no server row until the first prompt.
            coVerify(exactly = 0) { mockApi.setSessionPinned(any(), any()) }
        }

    @Test
    fun `flush titles the session and never pins it`() =
        runTest {
            coEvery { mockApi.setSessionPinned(any(), any()) } returns Response.success(Unit)
            GroupRoomCoordinator.adopt(room, "coder", "sess-new")

            GroupRoomCoordinator.flushPendingTitle("sess-new")

            // pinned = null: a bot's pinned slot means "my 1:1 canonical", and
            // pinning a room thread would surface the group as that private chat.
            coVerify {
                mockApi.setSessionPinned("sess-new", SessionRenameRequest(title = "Group: Deploy squad"))
            }
        }

    @Test
    fun `flushing a session nobody adopted does nothing`() =
        runTest {
            GroupRoomCoordinator.flushPendingTitle("sess-unknown")

            coVerify(exactly = 0) { mockApi.setSessionPinned(any(), any()) }
        }

    @Test
    fun `a failed rename stays pending for a later retry`() =
        runTest {
            coEvery { mockApi.setSessionPinned(any(), any()) } returns
                Response.error(404, "".toResponseBody(null))
            GroupRoomCoordinator.adopt(room, "coder", "sess-new")

            GroupRoomCoordinator.flushPendingTitle("sess-new")
            coEvery { mockApi.setSessionPinned(any(), any()) } returns Response.success(Unit)
            GroupRoomCoordinator.flushPendingTitle("sess-new")

            coVerify(exactly = 2) { mockApi.setSessionPinned("sess-new", any()) }
        }

    @Test
    fun `a flushed title is not re-sent`() =
        runTest {
            coEvery { mockApi.setSessionPinned(any(), any()) } returns Response.success(Unit)
            GroupRoomCoordinator.adopt(room, "coder", "sess-new")

            GroupRoomCoordinator.flushPendingTitle("sess-new")
            GroupRoomCoordinator.flushPendingTitle("sess-new")

            coVerify(exactly = 1) { mockApi.setSessionPinned("sess-new", any()) }
        }

    // ── self-heal ─────────────────────────────────────────────────────────

    @Test
    fun `invalidate forgets a dead thread`() =
        runTest {
            stored["r1" to "coder"] = "sess-dead"

            GroupRoomCoordinator.invalidate("r1", "coder", "sess-dead")

            assertNull(stored["r1" to "coder"])
        }

    @Test
    fun `a stale invalidation never wipes a newer adoption`() =
        runTest {
            stored["r1" to "coder"] = "sess-new"

            GroupRoomCoordinator.invalidate("r1", "coder", "sess-old")

            assertEquals("sess-new", stored["r1" to "coder"])
        }

    @Test
    fun `invalidating one member leaves the rest of the room alone`() =
        runTest {
            stored["r1" to "coder"] = "sess-a"
            stored["r1" to "writer"] = "sess-b"

            GroupRoomCoordinator.invalidate("r1", "coder", "sess-a")

            assertNull(stored["r1" to "coder"])
            assertEquals("sess-b", stored["r1" to "writer"])
        }

    @Test
    fun `the same bot in two rooms keeps two independent threads`() =
        runTest {
            val other = GroupRoom(id = "r2", name = "Research", members = listOf("coder", "writer"))
            GroupRoomCoordinator.adopt(room, "coder", "sess-r1")
            GroupRoomCoordinator.adopt(other, "coder", "sess-r2")

            assertEquals("sess-r1", GroupRoomCoordinator.resolveMemberSessionId(room, "coder"))
            assertEquals("sess-r2", GroupRoomCoordinator.resolveMemberSessionId(other, "coder"))
        }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun stubSessions(vararg sessions: SessionInfo) {
        coEvery { mockApi.getSessions(any(), any(), any(), any()) } returns
            Response.success(SessionListResponse(sessions = sessions.toList()))
    }

    private fun session(
        id: String,
        title: String?,
        startedAt: Double?,
        source: String? = null,
    ) = SessionInfo(id = id, title = title, started_at = startedAt, source = source)
}
