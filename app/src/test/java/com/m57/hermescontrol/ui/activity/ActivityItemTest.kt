package com.m57.hermescontrol.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Unit tests for the Activity feed's pure core: what a bot's thread
 * contributes, what a routine run contributes, how the sources merge, and how
 * rows are filed under day buckets.
 *
 * The ViewModel above these does fan-out and failure policy only, so this is
 * where the shape of the feed is actually pinned down.
 */
class ActivityItemTest {
    // ── botActivity ───────────────────────────────────────────────────────

    @Test
    fun `bot-to-bot deliveries become DM rows with the sender attributed`() {
        val items =
            botActivity(
                botName = "research",
                sessionId = "s1",
                sessionStartedAt = 100.0,
                turns =
                    listOf(
                        ActivityTurn("Message from 🤖 Hermes (@hermes): deploy is green", 200.0),
                    ),
            )

        assertEquals(1, items.size)
        val dm = items.first()
        assertEquals(ActivityKind.BOT_DM, dm.kind)
        assertEquals("research", dm.actor)
        assertEquals("Hermes", dm.counterpart)
        assertEquals("deploy is green", dm.body)
        assertEquals(200.0, dm.timestamp!!, 0.001)
        // Openable: the row carries the profile to switch to AND the thread.
        assertEquals("research", dm.botName)
        assertEquals("s1", dm.sessionId)
    }

    @Test
    fun `only the newest human prompt survives`() {
        val items =
            botActivity(
                botName = "coder",
                sessionId = "s2",
                sessionStartedAt = null,
                turns =
                    listOf(
                        ActivityTurn("first thing", 10.0),
                        ActivityTurn("second thing", 20.0),
                        ActivityTurn("third thing", 30.0),
                    ),
            )

        assertEquals(1, items.size)
        assertEquals(ActivityKind.USER_PROMPT, items[0].kind)
        assertEquals("third thing", items[0].body)
    }

    @Test
    fun `a chatty pair of bots cannot own the feed`() {
        val turns = (1..12).map { ActivityTurn("Message from Hermes: ping $it", it.toDouble()) }

        val items = botActivity("research", "s3", null, turns)

        assertEquals("deliveries are capped per bot", 5, items.size)
        // The cap keeps the NEWEST, not the first five scanned.
        assertEquals("ping 12", items.last().body)
    }

    @Test
    fun `deliveries and the newest prompt coexist`() {
        val items =
            botActivity(
                botName = "research",
                sessionId = "s4",
                sessionStartedAt = null,
                turns =
                    listOf(
                        ActivityTurn("Message from Hermes: deploy is green", 10.0),
                        ActivityTurn("show me the log", 20.0),
                    ),
            )

        assertEquals(2, items.size)
        assertEquals(ActivityKind.BOT_DM, items[0].kind)
        assertEquals(ActivityKind.USER_PROMPT, items[1].kind)
    }

    @Test
    fun `blank turns produce no row`() {
        val items = botActivity("data", "s5", null, listOf(ActivityTurn("   \n  ", 10.0)))

        assertTrue("an empty turn is not activity", items.isEmpty())
    }

    @Test
    fun `a turn without its own stamp falls back to the thread's`() {
        val items = botActivity("writer", "s6", sessionStartedAt = 42.0, turns = listOf(ActivityTurn("hello")))

        assertEquals(42.0, items.single().timestamp!!, 0.001)
    }

    @Test
    fun `multi-line bodies squash to one line`() {
        val items = botActivity("writer", "s7", null, listOf(ActivityTurn("draft\n\nsaved   to /tmp", 5.0)))

        assertEquals("draft saved to /tmp", items.single().body)
    }

    // ── routineActivity ───────────────────────────────────────────────────

    @Test
    fun `a routine that ran becomes a row`() {
        val item =
            routineActivity(
                jobId = "job1",
                name = "nightly-test",
                lastRunAt = "1700000000",
                lastRunStatus = "ok",
                scheduleDisplay = "Runs at 03:00",
            )!!

        assertEquals(ActivityKind.ROUTINE_RUN, item.kind)
        assertEquals("nightly-test", item.actor)
        assertEquals("Runs at 03:00", item.body)
        assertFalse(item.failed)
        // Routines have no thread: tapping one opens the cron screen instead.
        assertNull(item.sessionId)
        assertNull(item.botName)
    }

    @Test
    fun `a non-success status marks the row failed`() {
        val item = routineActivity("job2", "deploy", "1700000000", "error", null)!!

        assertTrue(item.failed)
    }

    @Test
    fun `a routine that never ran produces nothing`() {
        assertNull(routineActivity("job3", "unused", null, null, "Runs at 09:00"))
        // An unparseable stamp is the same case: an undated "it ran, sometime"
        // row would sit at the bottom of the feed forever.
        assertNull(routineActivity("job4", "unused", "whenever", "ok", null))
    }

    @Test
    fun `an unknown status is treated as a failure, not silently as success`() {
        val item = routineActivity("job5", "weird", "1700000000", "partially-eaten", null)!!

        assertTrue(item.failed)
    }

    // ── parseTimestamp ────────────────────────────────────────────────────

    @Test
    fun `timestamps parse from epoch numbers and ISO instants`() {
        assertEquals(1700000000.0, parseTimestamp("1700000000")!!, 0.001)
        assertEquals(1700000000.5, parseTimestamp(" 1700000000.5 ")!!, 0.001)
        assertEquals(
            Instant.parse("2026-08-27T12:00:00Z").epochSecond.toDouble(),
            parseTimestamp("2026-08-27T12:00:00Z")!!,
            0.001,
        )
    }

    @Test
    fun `unusable timestamps are null, never an exception`() {
        assertNull(parseTimestamp(null))
        assertNull(parseTimestamp(""))
        assertNull(parseTimestamp("last tuesday"))
        assertNull(parseTimestamp("0"))
    }

    // ── mergeActivity ─────────────────────────────────────────────────────

    @Test
    fun `merge sorts newest first and files undated rows last`() {
        val merged =
            mergeActivity(
                listOf(
                    row("old", 100.0),
                    row("undated", null),
                    row("new", 300.0),
                    row("middle", 200.0),
                ),
            )

        assertEquals(listOf("new", "middle", "old", "undated"), merged.map { it.id })
    }

    @Test
    fun `merge caps the feed`() {
        val merged = mergeActivity((1..100).map { row("r$it", it.toDouble()) }, limit = 10)

        assertEquals(10, merged.size)
        assertEquals("the cap keeps the newest", "r100", merged.first().id)
    }

    // ── bucketOf ──────────────────────────────────────────────────────────

    @Test
    fun `rows are filed by the viewer's local calendar day`() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val now = Instant.parse("2026-08-27T12:00:00Z")
        val oneHourAgo = now.epochSecond - 3600.0
        val yesterday = now.epochSecond - 86_400.0
        val lastWeek = now.epochSecond - 7 * 86_400.0

        assertEquals(ActivityBucket.TODAY, bucketOf(oneHourAgo, now, zone))
        assertEquals(ActivityBucket.YESTERDAY, bucketOf(yesterday, now, zone))
        assertEquals(ActivityBucket.EARLIER, bucketOf(lastWeek, now, zone))
    }

    @Test
    fun `a missing stamp is undated, not epoch zero`() {
        val now = Instant.parse("2026-08-27T12:00:00Z")

        assertEquals(ActivityBucket.UNDATED, bucketOf(null, now, ZoneId.of("UTC")))
        assertEquals(ActivityBucket.UNDATED, bucketOf(0.0, now, ZoneId.of("UTC")))
    }

    @Test
    fun `a clock-skewed future stamp reads as today`() {
        val now = Instant.parse("2026-08-27T12:00:00Z")
        val tomorrow = now.epochSecond + 86_400.0

        // Newest-first puts it at the top of the list; "Earlier" would be a
        // header contradicting the row right under it.
        assertEquals(ActivityBucket.TODAY, bucketOf(tomorrow, now, ZoneId.of("UTC")))
    }

    private fun row(
        id: String,
        at: Double?,
    ) = ActivityItem(
        id = id,
        kind = ActivityKind.USER_PROMPT,
        actor = "bot",
        body = "body",
        timestamp = at,
    )
}
