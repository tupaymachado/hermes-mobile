package com.m57.hermescontrol.ui.bots

/**
 * How available a bot is, derived from what the backend actually exposes —
 * there is no presence endpoint, so this is inferred:
 *  - [ACTIVE]  the bot the app is currently homed on (`GET /api/profiles/active`);
 *  - [ONLINE]  `ProfileInfo.gateway_running == true`;
 *  - [OFFLINE] `gateway_running == false`;
 *  - [UNKNOWN] the backend omitted the field (older gateways).
 *
 * [ACTIVE] deliberately outranks [ONLINE]: a user looking at the roster cares
 * first about which bot they are talking to.
 */
enum class BotPresence {
    ACTIVE,
    ONLINE,
    OFFLINE,
    UNKNOWN,
}

/**
 * One row of the Bot Mode roster. A *bot* is a SERVER-side Hermes profile
 * (`GET /api/profiles`), not a local connection profile.
 *
 * [lastMessage] comes from `GET /api/sessions/{id}/messages` — NOT from
 * `SessionInfo.preview`, which the gateway fills with the FIRST user prompt of
 * the session (validated 24/ago/2026). It is null when the bot has no session
 * yet, or when that per-bot lookup failed: a roster row must still render when
 * its last message is unavailable.
 */
data class BotRosterItem(
    val name: String,
    val description: String? = null,
    val isActive: Boolean = false,
    val presence: BotPresence = BotPresence.UNKNOWN,
    val lastMessage: String? = null,
    /** Epoch seconds of the bot's most recent session, for recency sorting. */
    val lastActivityAt: Double? = null,
    /**
     * True when this bot's per-bot lookup FAILED, as opposed to the bot simply
     * having nothing to show. Both end with a null [lastMessage], but they are
     * different facts and the row says so: "no messages yet" is a bot you never
     * talked to, "last message unavailable" is a fetch that broke. Collapsing
     * the two would quietly report an outage as an empty inbox.
     *
     * A degraded row is still a usable row — tapping it switches to the bot as
     * normal. This flag never blocks selection.
     */
    val lastMessageUnavailable: Boolean = false,
)
