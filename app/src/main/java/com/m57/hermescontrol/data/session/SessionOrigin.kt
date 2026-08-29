package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.model.SessionInfo

/**
 * Whether a session is a CONVERSATION or something a machine produced.
 *
 * Every per-bot fan-out (roster last-message, Bot DMs, Activity feed) starts by
 * asking for the bot's newest session. On a live gateway that answer is often a
 * **cron run**: each scheduled job opens its own session (`cron_<job>_<stamp>`,
 * `source = "cron"`) whose first user turn is the injected
 * `[IMPORTANT: You are running as a scheduled task…]` preamble.
 *
 * Taken at face value that turn reads as something the human typed — the feed
 * renders "You messaged default" over text the user never wrote, the roster
 * shows it as the bot's last message, and the Activity feed lists the same cron
 * run twice: once truthfully as a `ROUTINE_RUN`, once as a fake prompt. Same
 * failure class as the timeline markers riding on `role=user`: a row that is
 * *technically* a user turn but is not the user talking.
 *
 * Found by exercising the live gateway (100.101.230.70:9119, 29/ago/2026),
 * where two cron jobs run daily and their sessions sat at the top of `recent`.
 */
private val MACHINE_SOURCES = setOf("cron")

/** True when the gateway itself opened this session, not a person. */
fun SessionInfo.isMachineOrigin(): Boolean = source?.trim()?.lowercase() in MACHINE_SOURCES

/**
 * The newest session that is actually a conversation, or null when the bot has
 * none.
 *
 * A bot whose whole recent history is cron runs legitimately lands on null —
 * "no messages yet" is the truth there, and it is a different fact from the
 * scan having failed (which the callers report separately).
 *
 * `order=recent` already sorts newest-first; `started_at` breaks ties for a
 * backend that ignores the param, and null stamps sort last — the same
 * tie-break [BotChatRegistry] uses when it looks for the pinned canonical.
 */
fun List<SessionInfo>?.newestConversation(): SessionInfo? =
    this
        ?.filter { it.id.isNotBlank() && !it.isMachineOrigin() }
        ?.maxByOrNull { it.started_at ?: Double.NEGATIVE_INFINITY }

/**
 * Sessions to ask for when the caller wants ONE conversation.
 *
 * `limit=1` was the old shape and is exactly what broke: the single row it
 * returns is whatever ran last, cron included. Ten is still one round trip and
 * survives a day of a chatty scheduler before a bot's real chat falls off the
 * page.
 */
const val CONVERSATION_PROBE_LIMIT = 10
