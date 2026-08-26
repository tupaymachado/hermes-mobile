package com.m57.hermescontrol.ui.bots.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.bots.BotPresence
import com.m57.hermescontrol.ui.bots.BotRosterItem

/**
 * One bot in the roster: avatar, name, last user message, presence dot, and a
 * check on the bot the app is currently homed on.
 */
@Composable
fun BotRosterRow(
    bot: BotRosterItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors =
            if (bot.isActive) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                CardDefaults.cardColors()
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // No contentDescription: the name is announced by the Text right
            // next to it, so describing the avatar would double the
            // announcement (see BotAvatar's accessibility note).
            BotAvatar(name = bot.name)

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PresenceDot(botName = bot.name, presence = bot.presence)
                    Text(
                        text = bot.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    // Three distinct states, never collapsed into one: a real
                    // last message; "no messages yet" for a bot never talked
                    // to; and the degraded case, where the lookup for THIS bot
                    // failed. The row always renders — a broken last-message
                    // fetch is not a broken roster, and the bot is still
                    // selectable.
                    text =
                        when {
                            bot.lastMessage != null -> bot.lastMessage
                            bot.lastMessageUnavailable ->
                                stringResource(R.string.bots_last_message_unavailable)

                            else -> stringResource(R.string.bots_last_message_none)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (bot.lastMessage == null && bot.lastMessageUnavailable) {
                            // Muted error, not the loud one: the row is degraded,
                            // not failed.
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("bot_last_message"),
                )
            }

            if (bot.isActive) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringResource(R.string.bots_presence_active),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp).testTag("bot_active_check"),
                )
            }
        }
    }
}

/**
 * Small semantic presence indicator; colours come from the status palette.
 *
 * The description carries the bot's NAME as well as the state: the dot is an
 * 8dp box with no text of its own, so a bare "Offline" in the a11y tree is a
 * state with no subject once the row's children are read in sequence.
 */
@Composable
private fun PresenceDot(
    botName: String,
    presence: BotPresence,
) {
    val statusColors = LocalHermesStatusColors.current
    val color =
        when (presence) {
            BotPresence.ACTIVE -> statusColors.success
            BotPresence.ONLINE -> statusColors.success
            BotPresence.OFFLINE -> statusColors.neutral
            BotPresence.UNKNOWN -> statusColors.neutral
        }
    val state =
        when (presence) {
            BotPresence.ACTIVE -> stringResource(R.string.bots_presence_active)
            BotPresence.ONLINE -> stringResource(R.string.bots_presence_online)
            BotPresence.OFFLINE -> stringResource(R.string.bots_presence_offline)
            BotPresence.UNKNOWN -> stringResource(R.string.bots_presence_unknown)
        }
    val label = stringResource(R.string.bots_presence_desc, botName, state)
    Box(
        modifier =
            Modifier
                .size(8.dp)
                .background(color = color, shape = CircleShape)
                .semantics { contentDescription = label },
    )
}
