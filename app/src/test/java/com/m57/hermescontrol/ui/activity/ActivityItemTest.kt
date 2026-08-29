package com.m57.hermescontrol.ui.activity

import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
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

    @Test
    fun `two bots sharing one session id still produce unique row keys`() {
        // A gateway that ignores `?profile=` hands every bot the SAME newest
        // session. The feed is then wrong, but it must degrade — the list keys
        // rows by id, and a duplicate key throws.
        val turns =
            listOf(
                ActivityTurn("Message from Hermes: ping", 10.0),
                ActivityTurn("what is up", 20.0),
            )

        val ids =
            (botActivity("research", "shared", null, turns) + botActivity("coder", "shared", null, turns))
                .map { it.id }

        assertEquals("no id repeats across bots", ids.size, ids.distinct().size)
    }

    @Test
    fun `a delivery keeps its key as the scan window slides`() {
        val first = ActivityTurn("Message from Hermes: ping", 10.0, messageId = 41)
        val second = ActivityTurn("Message from Hermes: pong", 20.0, messageId = 42)

        val before = botActivity("research", "s8", null, listOf(first))
        val after = botActivity("research", "s8", null, listOf(first, second))

        // The window index would renumber `first` on every new turn, churning
        // the list's keys for a message that has not changed.
        assertEquals(before.single().id, after.first().id)
    }

    @Test
    fun `a backend without message ids still keys deliveries by their stamp`() {
        // Same stamp on two deliveries: the counter is what keeps them apart.
        val turns =
            listOf(
                ActivityTurn("Message from Hermes: one", 10.0),
                ActivityTurn("Message from Hermes: two", 10.0),
            )

        val ids = botActivity("research", "s9", null, turns).map { it.id }

        assertEquals(2, ids.distinct().size)
        assertEquals(ids, botActivity("research", "s9", null, turns).map { it.id })
    }

    // ── activityTurns ─────────────────────────────────────────────────────

    @Test
    fun `timeline markers are not user turns`() {
        // model_switch and friends ride on role=user (issue #904), so the
        // scanned page contains them even though the request asked for the
        // human's turns.
        val turns =
            activityTurns(
                listOf(
                    userMessage(id = 1, text = "deploy the thing"),
                    userMessage(id = 2, text = "Switched model to opus", displayKind = "model_switch"),
                ),
            )

        assertEquals(listOf("deploy the thing"), turns.map { it.text })
    }

    @Test
    fun `switching a bot's model does not evict the real prompt`() {
        val items =
            botActivity(
                botName = "coder",
                sessionId = "s10",
                sessionStartedAt = null,
                turns =
                    activityTurns(
                        listOf(
                            userMessage(id = 1, text = "deploy the thing", stamp = "10"),
                            userMessage(
                                id = 2,
                                text = "Switched model to opus",
                                stamp = "20",
                                displayKind = "model_switch",
                            ),
                        ),
                    ),
            )

        // Unfiltered, the marker became a "You messaged coder" row AND pushed
        // the actual prompt out, since only the newest non-delivery survives.
        assertEquals(1, items.size)
        assertEquals("deploy the thing", items.single().body)
    }

    @Test
    fun `scanned turns carry the gateway's message id and stamp`() {
        val turn = activityTurns(listOf(userMessage(id = 7, text = "hello", stamp = "1700000000"))).single()

        assertEquals(7, turn.messageId)
        assertEquals(1700000000.0, turn.timestamp!!, 0.001)
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
        assertNull(routineActivity("job3b", "unused", "   ", null, null))
    }

    @Test
    fun `the live gateway's offset stamp becomes a routine row`() {
        // Verbatim from GET /api/cron/jobs: ISO-8601 with a numeric offset,
        // which is NOT what Instant.parse accepts on a Java 8-era java.time.
        val item = routineActivity("job6", "nightly-test", "2026-08-27T08:01:07.850223-03:00", "ok", "Runs at 08:00")!!

        assertEquals(
            Instant.parse("2026-08-27T11:01:07Z").epochSecond.toDouble(),
            item.timestamp!!,
            0.001,
        )
        assertFalse(item.failed)
    }

    @Test
    fun `a run with an unreadable stamp is undated, not dropped`() {
        val item = routineActivity("job4", "mystery", "whenever", "ok", "Runs at 09:00")!!

        // The run happened — only its clock is unreadable, so the row files
        // under "undated" at the bottom instead of disappearing silently.
        assertNull(item.timestamp)
        assertEquals(ActivityBucket.UNDATED, bucketOf(item.timestamp))
        assertEquals("Runs at 09:00", item.body)
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
    fun `an ISO stamp with a numeric offset parses to the same instant`() {
        // The exact shape GET /api/cron/jobs returns for last_run_at. This
        // suite runs on a modern JDK, where Instant.parse would have taken it
        // too; API 26's java.time (Java 8 semantics) would not, which is why
        // the parser leads with OffsetDateTime instead.
        assertEquals(
            OffsetDateTime.parse("2026-08-27T08:01:07.850223-03:00").toEpochSecond().toDouble(),
            parseTimestamp("2026-08-27T08:01:07.850223-03:00")!!,
            0.001,
        )
        assertEquals(
            Instant.parse("2026-08-27T11:01:07Z").epochSecond.toDouble(),
            parseTimestamp("2026-08-27T08:01:07.850223-03:00")!!,
            0.001,
        )
        assertEquals(
            Instant.parse("2026-08-27T11:01:00Z").epochSecond.toDouble(),
            parseTimestamp("2026-08-27T08:01-03:00")!!,
            0.001,
        )
    }

    @Test
    fun `an ISO stamp with no zone is read in the device's own zone`() {
        // `datetime.isoformat()` on a naive datetime — the gateway's clock is
        // the only one on offer, and it is the zone the day buckets use.
        val local = LocalDateTime.of(2026, 8, 27, 8, 1, 7)

        assertEquals(
            local.atZone(ZoneId.systemDefault()).toEpochSecond().toDouble(),
            parseTimestamp("2026-08-27T08:01:07")!!,
            0.001,
        )
        assertEquals(
            local.atZone(ZoneId.systemDefault()).toEpochSecond().toDouble(),
            parseTimestamp("2026-08-27T08:01:07.850223")!!,
            0.001,
        )
    }

    @Test
    fun `unusable timestamps are null, never an exception`() {
        assertNull(parseTimestamp(null))
        assertNull(parseTimestamp(""))
        assertNull(parseTimestamp("   "))
        assertNull(parseTimestamp("last tuesday"))
        assertNull(parseTimestamp("2026-13-45T99:99:99Z"))
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

    @Test
    fun `merge cannot emit a duplicate key`() {
        // Belt and braces for the list's `key = { it.id }`: whatever a source
        // contributes, the feed handed to the UI has distinct ids.
        val merged = mergeActivity(listOf(row("dup", 100.0), row("dup", 300.0)))

        assertEquals(1, merged.size)
        assertEquals("the survivor is the newest", 300.0, merged.single().timestamp!!, 0.001)
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

    private fun userMessage(
        id: Int,
        text: String,
        stamp: String? = null,
        displayKind: String? = null,
    ) = SessionMessage(
        id = id,
        role = "user",
        content = JsonPrimitive(text),
        timestamp = stamp?.let { JsonPrimitive(it) },
        display_kind = displayKind,
    )

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
