package com.m57.hermescontrol.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the group-room model and its creation rules (§P3, slice a).
 */
class GroupRoomTest {
    private val existing =
        listOf(
            GroupRoom(id = "r1", name = "Deploy squad", members = listOf("coder", "writer")),
        )

    @Test
    fun `a room titles every member thread the same way`() {
        val room = GroupRoom(id = "r2", name = "Deploy squad", members = listOf("coder", "writer"))

        // The title is what makes a room recoverable from the server after a
        // reinstall — the role `pinned` plays for the 1:1 canonical (§V3).
        assertEquals("Group: Deploy squad", room.sessionTitle)
    }

    @Test
    fun `a valid room passes`() {
        assertNull(validateGroupRoom("Research", listOf("coder", "researcher"), existing))
    }

    @Test
    fun `a room needs a name`() {
        assertEquals(GroupRoomError.NAME_BLANK, validateGroupRoom("   ", listOf("coder", "writer"), existing))
    }

    @Test
    fun `one bot is not a room`() {
        // One bot is the 1:1 chat that already exists.
        assertEquals(GroupRoomError.TOO_FEW_MEMBERS, validateGroupRoom("Solo", listOf("coder"), existing))
    }

    @Test
    fun `blank members do not count towards the minimum`() {
        assertEquals(
            GroupRoomError.TOO_FEW_MEMBERS,
            validateGroupRoom("Padded", listOf("coder", "  ", ""), existing),
        )
    }

    @Test
    fun `seven bots is too many`() {
        val seven = (1..7).map { "bot$it" }

        assertEquals(GroupRoomError.TOO_MANY_MEMBERS, validateGroupRoom("Crowd", seven, existing))
    }

    @Test
    fun `six bots is the ceiling and it fits`() {
        val six = (1..6).map { "bot$it" }

        assertNull(validateGroupRoom("Full", six, existing))
    }

    @Test
    fun `the same bot twice is rejected, case included`() {
        // Coder and coder are one bot; letting both in makes the room answer
        // twice every round.
        assertEquals(
            GroupRoomError.DUPLICATE_MEMBERS,
            validateGroupRoom("Dupes", listOf("coder", "Coder"), existing),
        )
    }

    @Test
    fun `a taken name is rejected`() {
        assertEquals(
            GroupRoomError.NAME_TAKEN,
            validateGroupRoom("deploy squad", listOf("coder", "researcher"), existing),
        )
    }

    @Test
    fun `a room may keep its own name while being edited`() {
        assertNull(
            validateGroupRoom("Deploy squad", listOf("coder", "researcher"), existing, editingRoomId = "r1"),
        )
    }
}
