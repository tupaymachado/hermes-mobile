package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.session.BotDmAttribution
import com.m57.hermescontrol.ui.bots.components.BotAvatar

/**
 * Bot Mode PM1, render side: a chat message that is actually a bot-to-bot
 * delivery, split into WHO sent it and WHAT they said.
 *
 * This is a pure UI derivation — [ChatMessage], the Room entity and the
 * gateway payload all keep the raw prefixed text. Nothing here is persisted,
 * so history recorded before PM1 renders with the badge too, and a parser
 * change never needs a database migration.
 */
data class BotDmMessage(
    /** The sending bot. */
    val attribution: BotDmAttribution,
    /** The same message with the attribution prefix removed from its content. */
    val message: ChatMessage,
)

/**
 * Returns this message as a bot-to-bot delivery, or null when it is ordinary
 * chat.
 *
 * Deliveries arrive on the USER role (the recipient's turn runs on it, so the
 * gateway has no other role to put them on), and a bot answering another bot
 * can echo the prefix back on the ASSISTANT role. Tool rows and system events
 * are never deliveries; neither are timeline markers, which are already
 * rendered as chips and whose text is not conversation.
 */
fun ChatMessage.asBotDm(): BotDmMessage? {
    if (role != MessageRole.USER && role != MessageRole.ASSISTANT) return null
    if (displayKind != null) return null
    val attribution = BotDmAttribution.parse(content) ?: return null
    return BotDmMessage(
        attribution = attribution,
        message = copy(content = BotDmAttribution.stripPrefix(content)),
    )
}

/**
 * Author badge for a bot-to-bot delivery: mini avatar, "DM from <bot>", and
 * the sender's handle when it adds anything over the display name.
 *
 * It sits ABOVE the bubble rather than inside it so the body keeps the normal
 * bubble/full-bleed treatment — the delivery is attributed, not redesigned.
 *
 * @param alignEnd true for user-role bubbles, which are right-aligned; agent
 * turns render full-bleed from the left.
 */
@Composable
fun BotDmAuthorBadge(
    attribution: BotDmAttribution,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .testTag("bot_dm_badge"),
        horizontalArrangement =
            if (alignEnd) {
                Arrangement.spacedBy(6.dp, Alignment.End)
            } else {
                Arrangement.spacedBy(6.dp, Alignment.Start)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.SwapHoriz,
            // Decorative: the label right next to it says "DM from X", so a
            // description here would double the announcement.
            contentDescription = null,
            tint = muted,
            modifier = Modifier.size(14.dp),
        )
        // The avatar keys off the HANDLE so the same bot gets the same colour
        // everywhere it appears (roster rows key off the profile name, which
        // is the handle).
        BotAvatar(name = attribution.handle, size = 16.dp)
        Text(
            text = stringResource(R.string.chat_bot_dm_from, attribution.displayName),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!attribution.handle.equals(attribution.displayName, ignoreCase = true)) {
            Text(
                text = stringResource(R.string.chat_bot_dm_handle, attribution.handle),
                style = MaterialTheme.typography.labelSmall,
                color = muted.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
