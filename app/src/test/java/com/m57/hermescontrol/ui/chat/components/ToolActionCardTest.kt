package com.m57.hermescontrol.ui.chat.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Schedule
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for the PM2-A summary cards: the kind → icon/label mapping (the
 * table the renderer paints from) and the best-effort payload detection that
 * decides when a card is emitted at all.
 *
 * Both halves are deliberately Compose-free and Context-free, so they run on
 * the JVM — the composable that joins title + detail is the only piece that
 * needs resources, and it has nothing to decide.
 */
class ToolActionCardTest {
    // ── kind → icon / label ──────────────────────────────────────────────

    @Test
    fun everyKind_mapsToAnIconAndALabel() {
        // A kind added without a mapping is a `when` that no longer compiles;
        // this asserts the mappings are also DISTINCT, so a copy-pasted branch
        // that reuses the previous kind's icon or string can't slip through.
        val icons = ToolActionKind.entries.map { toolActionIcon(it) }
        val labels = ToolActionKind.entries.map { toolActionLabelRes(it) }

        icons.forEach { assertNotNull(it) }
        assertEquals(ToolActionKind.entries.size, icons.distinct().size)
        assertEquals(ToolActionKind.entries.size, labels.distinct().size)
    }

    @Test
    fun cronCreated_mapsToRoutineIconAndCreatedRoutineLabel() {
        assertSame(Icons.Filled.Autorenew, toolActionIcon(ToolActionKind.CRON_CREATED))
        assertEquals(R.string.chat_tool_action_cron_created, toolActionLabelRes(ToolActionKind.CRON_CREATED))
    }

    @Test
    fun messageSent_mapsToSendIconAndMessageSentLabel() {
        assertSame(Icons.AutoMirrored.Filled.Send, toolActionIcon(ToolActionKind.MESSAGE_SENT))
        assertEquals(R.string.chat_tool_action_message_sent, toolActionLabelRes(ToolActionKind.MESSAGE_SENT))
    }

    @Test
    fun scheduled_mapsToScheduleIconAndScheduledLabel() {
        assertSame(Icons.Filled.Schedule, toolActionIcon(ToolActionKind.SCHEDULED))
        assertEquals(R.string.chat_tool_action_scheduled, toolActionLabelRes(ToolActionKind.SCHEDULED))
    }

    // ── detection ────────────────────────────────────────────────────────

    @Test
    fun cronjobCreate_detectsCronCreatedWithResultName() {
        val detected =
            detectToolAction(
                toolMessage(
                    name = "cronjob",
                    content = """{"args":{"action":"create","name":"arg-name"},"result":{"name":"standup"}}""",
                    timestamp = 1_700_000_000_000L,
                ),
            )

        assertEquals(ToolActionKind.CRON_CREATED, detected?.action)
        // The result wins over the args: the gateway may normalise the name.
        assertEquals("standup", detected?.detail)
        assertEquals(1_700_000_000_000L, detected?.timestampMs)
    }

    @Test
    fun cronjobCreate_withoutName_stillDetectsWithNoDetail() {
        val detected =
            detectToolAction(
                toolMessage(name = "cronjob", content = """{"args":{"action":"add"},"result":{"ok":true}}"""),
            )

        assertEquals(ToolActionKind.CRON_CREATED, detected?.action)
        assertNull(detected?.detail)
    }

    @Test
    fun cronjobList_isNotAnAction() {
        assertNull(
            detectToolAction(
                toolMessage(name = "cronjob", content = """{"args":{"action":"list"},"result":{"jobs":[]}}"""),
            ),
        )
    }

    @Test
    fun sendMessage_detectsMessageSentWithRecipient() {
        val detected =
            detectToolAction(
                toolMessage(name = "send_message", content = """{"args":{"to":"hermes"},"result":{"sent":true}}"""),
            )

        assertEquals(ToolActionKind.MESSAGE_SENT, detected?.action)
        assertEquals("hermes", detected?.detail)
    }

    @Test
    fun failedResult_producesNoCard() {
        // A card asserts the effect HAPPENED — an errored payload must not
        // claim a routine exists.
        assertNull(
            detectToolAction(
                toolMessage(
                    name = "cronjob",
                    content = """{"args":{"action":"create","name":"x"},"result":{"error":"invalid schedule"}}""",
                ),
            ),
        )
    }

    @Test
    fun runningCall_producesNoCard() {
        assertNull(
            detectToolAction(
                toolMessage(
                    name = "cronjob",
                    content = """{"args":{"action":"create","name":"x"}}""",
                    status = ToolStatus.RUNNING,
                ),
            ),
        )
    }

    @Test
    fun unnamedToolRow_producesNoCard() {
        // REST-hydrated rows carry no tool name — the documented gap. They must
        // degrade to "no card", never to a guess.
        assertNull(
            detectToolAction(
                ChatMessage(
                    role = MessageRole.TOOL,
                    content = """{"args":{"action":"create","name":"x"}}""",
                    toolName = null,
                    toolStatus = ToolStatus.COMPLETED,
                ),
            ),
        )
    }

    @Test
    fun nonJsonPayload_producesNoCardForCronButStillDetectsSendMessage() {
        // cronjob needs `action` from the payload, so unparseable content
        // cannot be classified as a create.
        assertNull(detectToolAction(toolMessage(name = "cronjob", content = "created job standup")))
        // send_message needs nothing beyond the tool name, so the card still
        // renders — without a recipient detail.
        val detected = detectToolAction(toolMessage(name = "send_message", content = "ok"))
        assertEquals(ToolActionKind.MESSAGE_SENT, detected?.action)
        assertNull(detected?.detail)
    }

    private fun toolMessage(
        name: String,
        content: String,
        status: ToolStatus = ToolStatus.COMPLETED,
        timestamp: Long = 0L,
    ) = ChatMessage(
        role = MessageRole.TOOL,
        content = content,
        toolName = name,
        toolStatus = status,
        timestamp = timestamp,
    )
}
