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
            BotAvatar(name = bot.name)

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PresenceDot(presence = bot.presence)
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
                    // No last message is a normal state (a bot never talked to
                    // yet, or a lookup that degraded) — say so instead of
                    // collapsing the row and making the list jump.
                    text = bot.lastMessage ?: stringResource(R.string.bots_last_message_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

/** Small semantic presence indicator; colours come from the status palette. */
@Composable
private fun PresenceDot(presence: BotPresence) {
    val statusColors = LocalHermesStatusColors.current
    val color =
        when (presence) {
            BotPresence.ACTIVE -> statusColors.success
            BotPresence.ONLINE -> statusColors.success
            BotPresence.OFFLINE -> statusColors.neutral
            BotPresence.UNKNOWN -> statusColors.neutral
        }
    val label =
        when (presence) {
            BotPresence.ACTIVE -> stringResource(R.string.bots_presence_active)
            BotPresence.ONLINE -> stringResource(R.string.bots_presence_online)
            BotPresence.OFFLINE -> stringResource(R.string.bots_presence_offline)
            BotPresence.UNKNOWN -> stringResource(R.string.bots_presence_unknown)
        }
    Box(
        modifier =
            Modifier
                .size(8.dp)
                .background(color = color, shape = CircleShape)
                .semantics { contentDescription = label },
    )
}
