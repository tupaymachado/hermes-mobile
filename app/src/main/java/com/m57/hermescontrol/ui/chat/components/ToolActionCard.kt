package com.m57.hermescontrol.ui.chat.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.ToolStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Side effect worth surfacing as a summary card of its own (PM2-A).
 *
 * Deliberately open-ended: the renderer only needs an icon and a label per
 * kind, so adding a case is one entry here plus one in [toolActionIcon] /
 * [toolActionLabelRes] plus one detection rule in [detectToolAction].
 */
enum class ToolActionKind {
    CRON_CREATED,
    MESSAGE_SENT,

    /**
     * Reserved for a one-shot "runs at <time>" scheduling action. No detection
     * rule maps to it yet — see the gap note on [detectToolAction]: the gateway
     * exposes no distinct scheduling tool today, and inventing a heuristic that
     * splits `cronjob create` into recurring-vs-one-shot would mislabel rows.
     */
    SCHEDULED,
}

/**
 * Render-ready summary of one tool side effect — "Created routine · standup".
 *
 * [label] is already resolved (title + optional detail, joined by the
 * translatable [R.string.chat_tool_action_detail] separator), so the composable
 * that paints it does no string assembly. Build one with [toolActionCardOf],
 * never by hand: it is the only place that knows the title/detail join.
 *
 * [timestampMs] is the tool row's own timestamp; it orders the cards of a turn
 * so several side effects read in the sequence they actually happened.
 */
data class ToolActionCard(
    val action: ToolActionKind,
    val label: String,
    val icon: ImageVector?,
    val timestampMs: Long,
)

/**
 * A detected side effect, BEFORE its label is resolved — the pure, testable
 * half of the pipeline. [detail] is the human-meaningful name the payload
 * carried (routine name, message recipient), or null when the payload did not
 * name one; the card then shows the bare title.
 */
internal data class DetectedToolAction(
    val action: ToolActionKind,
    val detail: String?,
    val timestampMs: Long,
)

/** Icon for [action]; null means "render the label alone". */
internal fun toolActionIcon(action: ToolActionKind): ImageVector? =
    when (action) {
        ToolActionKind.CRON_CREATED -> Icons.Filled.Autorenew
        ToolActionKind.MESSAGE_SENT -> Icons.AutoMirrored.Filled.Send
        ToolActionKind.SCHEDULED -> Icons.Filled.Schedule
    }

/** Title resource for [action] — the detail, when present, is appended by [toolActionCardOf]. */
@StringRes
internal fun toolActionLabelRes(action: ToolActionKind): Int =
    when (action) {
        ToolActionKind.CRON_CREATED -> R.string.chat_tool_action_cron_created
        ToolActionKind.MESSAGE_SENT -> R.string.chat_tool_action_message_sent
        ToolActionKind.SCHEDULED -> R.string.chat_tool_action_scheduled
    }

/** Resolve a [DetectedToolAction] into the render-ready [ToolActionCard]. */
@Composable
internal fun toolActionCardOf(detected: DetectedToolAction): ToolActionCard {
    val title = stringResource(toolActionLabelRes(detected.action))
    val detail = detected.detail?.takeIf { it.isNotBlank() }
    return ToolActionCard(
        action = detected.action,
        label = if (detail == null) title else stringResource(R.string.chat_tool_action_detail, title, detail),
        icon = toolActionIcon(detected.action),
        timestampMs = detected.timestampMs,
    )
}

// ── detection ────────────────────────────────────────────────────────────

private const val TOOL_CRONJOB = "cronjob"
private const val TOOL_SEND_MESSAGE = "send_message"

/** `cronjob` actions that CREATE something. Edits/deletes/lists are not milestones. */
private val CRON_CREATE_ACTIONS = setOf("create", "add", "new")

private val CRON_NAME_KEYS = listOf("name", "job_name", "job_id", "id")
private val RECIPIENT_KEYS = listOf("to", "recipient", "target", "profile", "bot", "name")

private val ACTION_JSON = Json { ignoreUnknownKeys = true }

/**
 * Best-effort detection of a tool row that produced a card-worthy side effect.
 *
 * **Known gap (PM2-A).** The plan called for `cron.created` / `message.sent`
 * hooks; the gateway emits no such events. What actually reaches the client is
 * a `tool.complete` payload, so detection reads [ChatMessage.toolName] +
 * [ChatMessage.toolStatus] + the payload's `args`/`result` — no new pipeline,
 * no ChatViewModel change. Two consequences to keep in mind:
 *  - REST-hydrated transcript rows carry NO tool name or status
 *    (`ChatServerMessageMapper` does not set either), so a reloaded session
 *    shows cards only for the tool rows that were also seen live over WS or
 *    restored from Room. A missing card is never a wrong card.
 *  - Argument key names are matched from a candidate list rather than a
 *    schema; an unrecognised key costs the detail, not the card.
 *
 * Returns null for anything that is not a COMPLETED call of a known tool, and
 * for calls whose result reports a failure — a card claims the effect
 * HAPPENED, so a failed call must not produce one.
 */
internal fun detectToolAction(message: ChatMessage): DetectedToolAction? {
    if (message.role != MessageRole.TOOL || message.toolStatus != ToolStatus.COMPLETED) return null
    val tool = message.toolName?.trim()?.lowercase() ?: return null
    if (tool != TOOL_CRONJOB && tool != TOOL_SEND_MESSAGE) return null

    val payload = parseActionPayload(message.content)
    val args = payload?.get("args") as? JsonObject
    val result = payload?.get("result") as? JsonObject ?: payload
    if (reportsFailure(result)) return null

    return when (tool) {
        TOOL_CRONJOB -> {
            val action = stringField(args, listOf("action"))?.lowercase()
            if (action !in CRON_CREATE_ACTIONS) {
                null
            } else {
                DetectedToolAction(
                    action = ToolActionKind.CRON_CREATED,
                    detail = stringField(result, CRON_NAME_KEYS) ?: stringField(args, CRON_NAME_KEYS),
                    timestampMs = message.timestamp,
                )
            }
        }

        else ->
            DetectedToolAction(
                action = ToolActionKind.MESSAGE_SENT,
                detail = stringField(args, RECIPIENT_KEYS) ?: stringField(result, RECIPIENT_KEYS),
                timestampMs = message.timestamp,
            )
    }
}

private fun parseActionPayload(content: String): JsonObject? {
    val trimmed = content.trim()
    if (!trimmed.startsWith("{")) return null
    return try {
        ACTION_JSON.parseToJsonElement(trimmed) as? JsonObject
    } catch (_: Exception) {
        null
    }
}

/** True when the result object says the call did not succeed. */
private fun reportsFailure(result: JsonObject?): Boolean {
    val r = result ?: return false
    if (!stringField(r, listOf("error")).isNullOrBlank()) return true
    val success = r["success"] as? JsonPrimitive
    return success != null && !success.isString && success.content == "false"
}

/** First non-blank string among [keys], or null. */
private fun stringField(
    record: JsonObject?,
    keys: List<String>,
): String? {
    val r = record ?: return null
    for (key in keys) {
        val value = r[key] as? JsonPrimitive ?: continue
        if (!value.isString) continue
        val text = value.content.trim()
        if (text.isNotEmpty()) return text
    }
    return null
}

// ── rendering ────────────────────────────────────────────────────────────

/**
 * The turn's side-effect cards, stacked in the order the tools ran.
 *
 * Called from the LAST item of an agent turn in `FullBleedChatList`, which
 * puts the cards between the tool bubbles and the next user turn — the Spacek
 * position — WITHOUT adding LazyColumn items. That matters: `messageIdToLazyIndex`
 * mirrors the list's item emission order one-for-one to drive search-match
 * scrolling, so an extra item here would silently shift every following match
 * by one.
 */
@Composable
internal fun ToolActionCards(
    actions: List<DetectedToolAction>,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        actions.forEach { detected -> ToolActionCardRow(detected) }
    }
}

/** One compact "Created routine · standup" card. */
@Composable
internal fun ToolActionCardRow(
    detected: DetectedToolAction,
    modifier: Modifier = Modifier,
) {
    val card = toolActionCardOf(detected)
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("tool_action_card"),
        shape = MaterialTheme.shapes.medium,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            card.icon?.let { icon ->
                Icon(
                    imageVector = icon,
                    // Decoration: the label right next to it already names the
                    // action, so describing the icon doubles the announcement.
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = card.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
