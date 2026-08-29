package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.model.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the conversation/machine split every per-bot fan-out depends on.
 *
 * Written against what the live gateway actually returns (100.101.230.70:9119,
 * 29/ago/2026): two daily cron jobs whose sessions — `source = "cron"`, ids like
 * `cron_93247841690b_20260829_090028` — sat at the top of `order=recent`, ahead
 * of the newest real chat.
 */
class SessionOriginTest {
    @Test
    fun `a cron session is machine origin`() {
        assertTrue(session("cron_93247841690b_20260829_090028", source = "cron").isMachineOrigin())
    }

    @Test
    fun `source matching ignores case and padding`() {
        assertTrue(session("s1", source = " CRON ").isMachineOrigin())
    }

    @Test
    fun `a human session is not machine origin`() {
        assertFalse(session("s1", source = "desktop").isMachineOrigin())
        assertFalse(session("s2", source = "cli").isMachineOrigin())
        // An older gateway omits the field entirely. Unknown ! machine —
        // guessing the other way would hide real chats.
        assertFalse(session("s3", source = null).isMachineOrigin())
    }

    @Test
    fun `the newest conversation skips cron runs sitting on top`() {
        // Exactly the live ordering: two cron runs newer than the real chat.
        val sessions =
            listOf(
                session("cron_a_20260829_090028", source = "cron", startedAt = 300.0),
                session("cron_b_20260829_080028", source = "cron", startedAt = 200.0),
                session("20260829_101658_72f62ad3", source = "cli", startedAt = 100.0),
            )

        assertEquals("20260829_101658_72f62ad3", sessions.newestConversation()?.id)
    }

    @Test
    fun `recency wins among conversations`() {
        val sessions =
            listOf(
                session("older", source = "cli", startedAt = 100.0),
                session("newer", source = "desktop", startedAt = 500.0),
            )

        assertEquals("newer", sessions.newestConversation()?.id)
    }

    @Test
    fun `a bot with only cron runs has no conversation`() {
        val sessions = listOf(session("cron_a", source = "cron", startedAt = 300.0))

        // Null here means "no messages yet", which is the truth — and the
        // callers keep it distinct from a scan that FAILED.
        assertNull(sessions.newestConversation())
    }

    @Test
    fun `blank ids are not conversations`() {
        assertNull(listOf(session("   ", source = "cli", startedAt = 1.0)).newestConversation())
    }

    @Test
    fun `an absent list is not a crash`() {
        assertNull(null.newestConversation())
        assertNull(emptyList<SessionInfo>().newestConversation())
    }

    @Test
    fun `sessions without stamps still resolve`() {
        // maxByOrNull over an all-null selector keeps the first, which under
        // order=recent is the newest the backend knows about.
        val sessions = listOf(session("first", source = "cli"), session("second", source = "cli"))

        assertEquals("first", sessions.newestConversation()?.id)
    }

    @Test
    fun `the probe asks for more than one row`() {
        // The whole bug was limit=1: whatever ran last, cron included.
        assertTrue(CONVERSATION_PROBE_LIMIT > 1)
    }

    private fun session(
        id: String,
        source: String? = null,
        startedAt: Double? = null,
    ) = SessionInfo(id = id, source = source, started_at = startedAt)
}
