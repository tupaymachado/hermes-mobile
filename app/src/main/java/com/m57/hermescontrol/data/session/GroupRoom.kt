package com.m57.hermescontrol.data.session

import kotlinx.serialization.Serializable

/**
 * A shared room where several bots and the human talk in one thread (§P3).
 *
 * **The 1:N generalization of the canonical Bot Chat.** [sessions] is the same
 * idea as `ServerStoreState.botChatSessions` — a durable map from bot to the
 * thread it keeps — except keyed per room, so a bot in three rooms holds three
 * threads plus its own 1:1 chat, all independent. That is the widening §D1
 * promised the registry could take without a destructive migration: the 1:1 map
 * stays exactly where it is and this arrives beside it.
 *
 * Each member's thread is titled `Group: <name>` on the gateway, which is what
 * makes a room recoverable from the server after a reinstall — the same role
 * `pinned` plays for the 1:1 canonical (§V3).
 */
@Serializable
data class GroupRoom(
    /** Stable local id. Never reused, so [sessions] can never be misattributed. */
    val id: String,
    /** Display name. Also the gateway-side session title, as `Group: <name>`. */
    val name: String,
    /** Bot (profile) names in the room. */
    val members: List<String> = emptyList(),
    /** member → session id. Empty until a member's thread is adopted (§V3). */
    val sessions: Map<String, String> = emptyMap(),
) {
    /** The gateway-side title every member's thread carries. */
    val sessionTitle: String get() = "$TITLE_PREFIX$name"

    companion object {
        /**
         * A room needs two bots to be a room; one is the 1:1 chat that already
         * exists, and past six the serial rounds (§P3) stop being readable
         * long before they stop being affordable.
         */
        const val MIN_MEMBERS = 2
        const val MAX_MEMBERS = 6

        const val TITLE_PREFIX = "Group: "
    }
}

/**
 * Why a room cannot be created as asked, or null when it can.
 *
 * Returned rather than thrown: this answers a form as the user types, and every
 * case here is something they can fix by editing the field.
 */
enum class GroupRoomError {
    NAME_BLANK,
    TOO_FEW_MEMBERS,
    TOO_MANY_MEMBERS,
    DUPLICATE_MEMBERS,
    NAME_TAKEN,
}

/**
 * Validates a room against the existing [rooms].
 *
 * Membership is compared case-insensitively because profile names are
 * free-form on the gateway: `Coder` and `coder` are one bot, and letting both
 * into a room would make it answer twice every round.
 */
fun validateGroupRoom(
    name: String,
    members: List<String>,
    rooms: List<GroupRoom> = emptyList(),
    editingRoomId: String? = null,
): GroupRoomError? {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) return GroupRoomError.NAME_BLANK

    val cleanMembers = members.map { it.trim() }.filter { it.isNotEmpty() }
    if (cleanMembers.size != cleanMembers.distinctBy { it.lowercase() }.size) {
        return GroupRoomError.DUPLICATE_MEMBERS
    }
    if (cleanMembers.size < GroupRoom.MIN_MEMBERS) return GroupRoomError.TOO_FEW_MEMBERS
    if (cleanMembers.size > GroupRoom.MAX_MEMBERS) return GroupRoomError.TOO_MANY_MEMBERS

    // Names are how a room is recognised in a list and in its session title, so
    // two rooms sharing one is a durable confusion, not a cosmetic one.
    val clash =
        rooms.any { it.id != editingRoomId && it.name.trim().equals(trimmedName, ignoreCase = true) }
    return if (clash) GroupRoomError.NAME_TAKEN else null
}
