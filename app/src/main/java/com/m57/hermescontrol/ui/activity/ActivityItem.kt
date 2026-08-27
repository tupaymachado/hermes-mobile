package com.m57.hermescontrol.ui.activity

import com.m57.hermescontrol.data.session.BotDmAttribution
import com.m57.hermescontrol.ui.bots.oneLine
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What kind of thing happened. Deliberately narrow: every kind here is
 * something the gateway ACTUALLY reports, not something inferred from prose.
 *
 * There is no "bot finished a task" or "bot flagged an anomaly" kind, however
 * well those read in a mockup — the API exposes messages and cron runs, and a
 * feed that guesses intent from message text would be confidently wrong on the
 * first sarcastic bot.
 */
enum class ActivityKind {
    /** A bot-to-bot delivery landed in a bot's canonical chat (PM1). */
    BOT_DM,

    /** The human prompted a bot — the newest such turn per bot. */
    USER_PROMPT,

    /** A cron routine ran. [ActivityItem.failed] carries how it went. */
    ROUTINE_RUN,
}

/**
 * One row of the Activity feed: a merged, chronological view of what moved
 * across every bot and routine.
 *
 * [timestamp] is epoch SECONDS and nullable — a backend that omits message
 * timestamps still produces a usable feed, its items simply sort last instead
 * of vanishing (the same "degrade the row, never drop it" rule the roster
 * follows for a missing last message).
 */
data class ActivityItem(
    /** Stable list key. Unique per (source, session/job, position). */
    val id: String,
    val kind: ActivityKind,
    /** The bot or routine the row is ABOUT. */
    val actor: String,
    /** For [ActivityKind.BOT_DM], the bot that sent the delivery. */
    val counterpart: String? = null,
    /** One-line detail: the delivered body, the prompt, or the run's status. */
    val body: String,
    val timestamp: Double? = null,
    /**
     * Profile to switch to when the row is opened, and the thread to resume in
     * it. Both null on [ActivityKind.ROUTINE_RUN], which opens the cron screen
     * instead — a routine has no chat to resume.
     */
    val botName: String? = null,
    val sessionId: String? = null,
    /** [ActivityKind.ROUTINE_RUN] only: the last run reported a failure. */
    val failed: Boolean = false,
)

/** A scanned user turn: its flattened text and, when the backend sent one, its time. */
data class ActivityTurn(
    val text: String,
    val timestamp: Double? = null,
)

/** Day bucket a row is filed under. */
enum class ActivityBucket {
    TODAY,
    YESTERDAY,
    EARLIER,

    /** No timestamp from the backend — filed last, never dropped. */
    UNDATED,
}

/**
 * Builds one bot's contribution to the feed from the user turns of its
 * canonical chat.
 *
 * Two rules, both about signal density rather than completeness:
 *  - every bot-to-bot delivery in the scanned window is an item, capped at
 *    [MAX_DELIVERIES_PER_BOT] newest — a chatty pair of bots must not push
 *    every other bot off the feed;
 *  - the human's own prompts collapse to the NEWEST one. "You messaged coder"
 *    is context for the bot's row, not a log of your typing.
 *
 * Pure: the whole shape of the feed is testable without a network stub, which
 * is the same split [com.m57.hermescontrol.ui.bots.botDmThread] uses.
 *
 * @param turns user-role turns of the thread, OLDEST first — the order
 * `GET /api/sessions/{id}/messages?order=latest` returns a page in.
 */
internal fun botActivity(
    botName: String,
    sessionId: String,
    sessionStartedAt: Double?,
    turns: List<ActivityTurn>,
): List<ActivityItem> {
    val deliveries = mutableListOf<ActivityItem>()
    var newestPrompt: ActivityItem? = null

    turns.forEachIndexed { index, turn ->
        val attribution = BotDmAttribution.parse(turn.text)
        val at = turn.timestamp ?: sessionStartedAt
        if (attribution != null) {
            deliveries +=
                ActivityItem(
                    id = "dm:$sessionId:$index",
                    kind = ActivityKind.BOT_DM,
                    actor = botName,
                    counterpart = attribution.displayName,
                    body = BotDmAttribution.stripPrefix(turn.text).oneLine(),
                    timestamp = at,
                    botName = botName,
                    sessionId = sessionId,
                )
        } else {
            val body = turn.text.oneLine()
            // Blank turns exist (tool-only rows, markers): they carry no
            // information for a feed and would render as an empty row.
            if (body.isNotEmpty()) {
                newestPrompt =
                    ActivityItem(
                        id = "prompt:$sessionId",
                        kind = ActivityKind.USER_PROMPT,
                        actor = botName,
                        body = body,
                        timestamp = at,
                        botName = botName,
                        sessionId = sessionId,
                    )
            }
        }
    }

    return deliveries.takeLast(MAX_DELIVERIES_PER_BOT) + listOfNotNull(newestPrompt)
}

/**
 * Builds the row for a routine's last run, or null when it never ran (or the
 * backend's stamp is unparseable — an undated "it ran, sometime" row would
 * float to the bottom of the feed forever).
 */
internal fun routineActivity(
    jobId: String,
    name: String,
    lastRunAt: String?,
    lastRunStatus: String?,
    scheduleDisplay: String?,
): ActivityItem? {
    val at = parseTimestamp(lastRunAt) ?: return null
    val status = lastRunStatus?.trim()?.lowercase()
    return ActivityItem(
        id = "cron:$jobId:${at.toLong()}",
        kind = ActivityKind.ROUTINE_RUN,
        actor = name,
        body = scheduleDisplay?.oneLine().orEmpty(),
        timestamp = at,
        // A failed run is the one thing in this feed worth a colour, so the
        // fact is carried rather than re-derived from the body string.
        failed = status != null && status !in SUCCESS_STATUSES,
    )
}

/**
 * Merges every source into the feed: newest first, undated last, capped.
 *
 * The cap is what keeps a chatty gateway from turning the feed into an
 * unbounded scroll — this is a "what is going on" view, not an archive, the
 * same contract the Bot DMs screen states.
 */
internal fun mergeActivity(
    items: List<ActivityItem>,
    limit: Int = FEED_LIMIT,
): List<ActivityItem> =
    items
        .sortedWith(
            compareByDescending<ActivityItem> { it.timestamp != null }
                .thenByDescending { it.timestamp ?: 0.0 },
        ).take(limit)

/**
 * Files a row under a day bucket, in the VIEWER's timezone — "today" is a
 * local-calendar question, not a UTC one.
 */
internal fun bucketOf(
    timestamp: Double?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): ActivityBucket {
    if (timestamp == null || timestamp <= 0.0) return ActivityBucket.UNDATED
    val day =
        try {
            Instant.ofEpochSecond(timestamp.toLong()).atZone(zone).toLocalDate()
        } catch (_: Exception) {
            return ActivityBucket.UNDATED
        }
    val today = now.atZone(zone).toLocalDate()
    return when (ChronoUnit.DAYS.between(day, today)) {
        0L -> ActivityBucket.TODAY
        1L -> ActivityBucket.YESTERDAY
        // A negative diff means a clock-skewed stamp in the future. It reads as
        // "today" rather than "earlier", which is where the user expects the
        // newest thing in a newest-first list to be.
        else -> if (day.isAfter(today)) ActivityBucket.TODAY else ActivityBucket.EARLIER
    }
}

/**
 * Parses the assorted stamps the gateway sends: epoch seconds as a number or
 * a string, or an ISO-8601 instant. Anything else is null, never an exception.
 */
internal fun parseTimestamp(raw: String?): Double? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toDoubleOrNull()?.let { return it.takeIf { seconds -> seconds > 0.0 } }
    return try {
        Instant.parse(text).epochSecond.toDouble()
    } catch (_: Exception) {
        null
    }
}

/** Cron `last_run_status` values that mean the run was fine. */
private val SUCCESS_STATUSES = setOf("ok", "success", "succeeded", "done", "completed")

/** Newest deliveries kept per bot, so one loud pair cannot own the feed. */
private const val MAX_DELIVERIES_PER_BOT = 5

/** Rows the feed holds at most. */
private const val FEED_LIMIT = 60
