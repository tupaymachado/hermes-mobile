package com.m57.hermescontrol.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for group-room orchestration (§P3, slice a).
 *
 * The room's failure modes are all schedule-shaped — a round that never ends,
 * two bots answering each other forever, a mention that scopes to nobody — so
 * they are pinned here, without a gateway.
 */
class GroupRoundPolicyTest {
    private val room = listOf("coder", "writer", "researcher")

    // ── mentionedMembers ──────────────────────────────────────────────────

    @Test
    fun `a mention scopes to the named member`() {
        assertEquals(listOf("coder"), GroupRoundPolicy.mentionedMembers("@coder take this", room))
    }

    @Test
    fun `several mentions keep their order and dedupe`() {
        val text = "@writer and @coder and @writer again"

        assertEquals(listOf("writer", "coder"), GroupRoundPolicy.mentionedMembers(text, room))
    }

    @Test
    fun `an email address is not a mention`() {
        assertEquals(emptyList<String>(), GroupRoundPolicy.mentionedMembers("mail tupay@coder.com", room))
    }

    @Test
    fun `an unknown handle passes through`() {
        // Matched against the room, not a charset ∴ a bot that left, or a
        // literal '@' in prose, scopes nothing instead of scoping to nobody.
        assertEquals(emptyList<String>(), GroupRoundPolicy.mentionedMembers("@nobody hello", room))
    }

    @Test
    fun `trailing punctuation does not break a mention`() {
        val text = "ask @coder, then @writer."

        assertEquals(listOf("coder", "writer"), GroupRoundPolicy.mentionedMembers(text, room))
    }

    @Test
    fun `a longer member name wins over its own prefix`() {
        // With `code` and `coder` both in the room, "@coder" must not resolve
        // to `code`.
        val overlapping = listOf("code", "coder")

        assertEquals(listOf("coder"), GroupRoundPolicy.mentionedMembers("@coder ping", overlapping))
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(listOf("coder"), GroupRoundPolicy.mentionedMembers("@CODER ping", room))
    }

    // ── escalatesToUser ───────────────────────────────────────────────────

    @Test
    fun `at-user is an escalation`() {
        assertTrue(GroupRoundPolicy.escalatesToUser("@user can you confirm?"))
    }

    @Test
    fun `ordinary chatter is not an escalation`() {
        assertFalse(GroupRoundPolicy.escalatesToUser("I will ask @coder about it"))
        assertFalse(GroupRoundPolicy.escalatesToUser("the user asked for a summary"))
    }

    // ── respondersFor ─────────────────────────────────────────────────────

    @Test
    fun `an unaddressed message goes to the whole room`() {
        assertEquals(room, GroupRoundPolicy.respondersFor("what is the deploy status?", room))
    }

    @Test
    fun `a mention narrows the round`() {
        assertEquals(listOf("writer"), GroupRoundPolicy.respondersFor("@writer draft it", room))
    }

    @Test
    fun `a bot never answers itself`() {
        val responders = GroupRoundPolicy.respondersFor("here is what I found", room, speaker = "coder")

        assertEquals(listOf("writer", "researcher"), responders)
    }

    @Test
    fun `a bot mentioning itself still does not answer itself`() {
        val responders = GroupRoundPolicy.respondersFor("@coder was wrong, @writer take over", room, speaker = "coder")

        assertEquals(listOf("writer"), responders)
    }

    @Test
    fun `escalating to the human stops the room`() {
        // Answering here would talk over the person the message asked for.
        val responders = GroupRoundPolicy.respondersFor("@user I need a decision", room, speaker = "coder")

        assertEquals(emptyList<String>(), responders)
    }

    @Test
    fun `escalating while also naming a bot keeps that bot working`() {
        val responders = GroupRoundPolicy.respondersFor("@user decide, and @writer draft meanwhile", room, "coder")

        assertEquals(listOf("writer"), responders)
    }

    // ── caps ──────────────────────────────────────────────────────────────

    @Test
    fun `rounds are capped`() {
        assertTrue(GroupRoundPolicy.canContinue(round = 0, messagesSoFar = 0))
        assertTrue(GroupRoundPolicy.canContinue(round = GroupRoundPolicy.MAX_ROUNDS - 1, messagesSoFar = 0))
        assertFalse(GroupRoundPolicy.canContinue(round = GroupRoundPolicy.MAX_ROUNDS, messagesSoFar = 0))
    }

    @Test
    fun `volume is capped independently of depth`() {
        // Six members answering twice is past the message cap before the round
        // cap — which is the point: the cap is on volume, not depth.
        assertFalse(GroupRoundPolicy.canContinue(round = 1, messagesSoFar = GroupRoundPolicy.MAX_MESSAGES_PER_SEND))
    }

    @Test
    fun `admit trims the round to the remaining budget`() {
        val six = listOf("a", "b", "c", "d", "e", "f")

        val admitted = GroupRoundPolicy.admit(six, messagesSoFar = GroupRoundPolicy.MAX_MESSAGES_PER_SEND - 2)

        assertEquals(listOf("a", "b"), admitted)
    }

    @Test
    fun `a spent budget admits nobody`() {
        assertEquals(
            emptyList<String>(),
            GroupRoundPolicy.admit(room, messagesSoFar = GroupRoundPolicy.MAX_MESSAGES_PER_SEND),
        )
    }

    @Test
    fun `a full round fits when the budget allows`() {
        assertEquals(room, GroupRoundPolicy.admit(room, messagesSoFar = 0))
    }
}
