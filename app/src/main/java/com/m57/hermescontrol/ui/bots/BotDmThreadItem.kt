package com.m57.hermescontrol.ui.bots

import com.m57.hermescontrol.data.session.BotDmAttribution

/**
 * One row of the aggregated "Bot DMs" screen: a canonical Bot Chat thread that
 * contains at least one bot-to-bot delivery.
 *
 * A thread belongs to exactly one bot ([botName] is the server-side Hermes
 * profile that OWNS the thread and receives the DMs), so opening it means
 * switching to that bot and resuming [sessionId] — the same path the roster
 * uses, not a second way into the chat.
 */
data class BotDmThreadItem(
    val botName: String,
    val sessionId: String,
    /** Thread title from the gateway; null when it has not been generated yet. */
    val title: String? = null,
    /** Display name of the bot that sent the most recent delivery. */
    val lastDmSender: String,
    /** The most recent delivery's body, prefix stripped and squashed to one line. */
    val lastDmBody: String,
    /** How many deliveries were seen in the scanned window — not the all-time total. */
    val dmCount: Int,
    /** Epoch seconds of the thread's session, for recency sorting. */
    val lastActivityAt: Double? = null,
)

/**
 * Builds the row for one thread, or null when the scanned window holds no
 * bot-to-bot delivery — which is what keeps ordinary human threads out of the
 * Bot DMs list.
 *
 * Pure on purpose: the whole "is this a DM thread" rule is testable without a
 * network stub, and the ViewModel above it only does fan-out and failure
 * policy.
 *
 * @param userTurns raw user-role message bodies of the thread, OLDEST first
 * (the order `GET /api/sessions/{id}/messages?order=latest` returns a page in).
 */
internal fun botDmThread(
    botName: String,
    sessionId: String,
    title: String?,
    lastActivityAt: Double?,
    userTurns: List<String>,
): BotDmThreadItem? {
    val deliveries =
        userTurns.mapNotNull { raw ->
            BotDmAttribution.parse(raw)?.let { it to BotDmAttribution.stripPrefix(raw) }
        }
    val (attribution, body) = deliveries.lastOrNull() ?: return null
    return BotDmThreadItem(
        botName = botName,
        sessionId = sessionId,
        title = title?.takeIf { it.isNotBlank() },
        lastDmSender = attribution.displayName,
        lastDmBody = body.oneLine(),
        dmCount = deliveries.size,
        lastActivityAt = lastActivityAt,
    )
}
