package com.m57.hermescontrol.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.bots.components.BotAvatar
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import java.time.Instant

/**
 * The Activity feed — middle tab of the bottom-nav shell.
 *
 * One chronological view of what moved: bot-to-bot deliveries, your newest
 * prompt per bot, and routine runs. Tapping a bot row switches to that bot and
 * resumes the thread (the canonical path, not a second way into the chat);
 * tapping a routine opens the cron screen.
 */
@Composable
fun ActivityScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: ActivityViewModel = viewModel { ActivityViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadFeed() }
    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        modifier = modifier,
        title = { Text(stringResource(R.string.screen_activity)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadFeed() },
    ) { paddingValues ->
        // NO .padding(paddingValues) on this Column — HermesScaffold already
        // pre-applied it in its own Box (the padding foot-gun in AGENTS.md).
        // The transient states below DO take it, per that same rule.
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading && state.items.isEmpty() ->
                    SkeletonListState(modifier = Modifier.padding(paddingValues))

                state.errorMessage != null && state.items.isEmpty() ->
                    ErrorState(
                        message = state.errorMessage ?: "",
                        onRetry = { viewModel.loadFeed() },
                        modifier = Modifier.padding(paddingValues),
                    )

                state.items.isEmpty() ->
                    EmptyState(
                        title = stringResource(R.string.activity_empty_title),
                        subtitle = stringResource(R.string.activity_empty_desc),
                        icon = Icons.Filled.Bolt,
                        modifier = Modifier.padding(paddingValues),
                    )

                else ->
                    ActivityFeedList(
                        items = state.items,
                        onOpenItem = viewModel::openItem,
                        modifier = Modifier.weight(1f),
                    )
            }

            // Degraded coverage is stated, not swallowed: a feed missing a bot
            // or the whole cron list must not read as a quiet day.
            val gaps =
                listOfNotNull(
                    state.unscannedBots
                        .takeIf { it.isNotEmpty() }
                        ?.let { stringResource(R.string.activity_partial, it.joinToString(", ")) },
                    stringResource(R.string.activity_routines_unavailable)
                        .takeIf { state.routinesUnavailable },
                )
            gaps.forEach { gap ->
                Text(
                    text = gap,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("activity_gap"),
                )
            }
        }
    }
}

/**
 * Stateless feed, extracted so instrumented tests can drive it with controlled
 * rows instead of the ViewModel's real fan-out.
 *
 * Day headers come from [bucketOf] against a single [now] captured for the
 * whole list — grouping each row against its own `Instant.now()` could file two
 * rows a millisecond apart under different days at midnight.
 */
@Composable
fun ActivityFeedList(
    items: List<ActivityItem>,
    onOpenItem: (ActivityItem) -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    val grouped = items.groupBy { bucketOf(it.timestamp, now) }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Iterate the ENUM, not the map: bucket order is the reading order
        // (Today → Yesterday → Earlier → Undated), and a map's key order is
        // whatever the data happened to arrive in.
        ActivityBucket.entries.forEach { bucket ->
            val rows = grouped[bucket].orEmpty()
            if (rows.isEmpty()) return@forEach
            item(key = "header:${bucket.name}") {
                Text(
                    text = stringResource(bucket.labelRes()),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(rows, key = { it.id }) { row ->
                ActivityRow(item = row, now = now, onClick = { onOpenItem(row) })
            }
        }
    }
}

@Composable
private fun ActivityRow(
    item: ActivityItem,
    now: Instant,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp)
                .testTag("activity_row"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ActivityLeading(item)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.headline(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.body.isNotEmpty()) {
                Text(
                    text = item.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("activity_body"),
                )
            }
        }
        val time = relativeTimeLabel(item.timestamp, now)
        if (time != null) {
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Leading glyph: the bot's monogram for bot rows, a schedule icon for routines.
 *
 * No contentDescription on the avatar — the headline right next to it names the
 * bot, so describing the monogram doubles the announcement (the invariant from
 * Fase 4). The routine icon carries one because nothing else says "routine".
 */
@Composable
private fun ActivityLeading(item: ActivityItem) {
    when (item.kind) {
        ActivityKind.ROUTINE_RUN ->
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .background(
                            color =
                                if (item.failed) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                            shape = CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = stringResource(R.string.activity_routine_desc),
                    tint =
                        if (item.failed) {
                            LocalHermesStatusColors.current.error
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    modifier = Modifier.size(18.dp),
                )
            }

        // A delivery is ABOUT the receiving bot's thread, so the monogram stays
        // the receiver's — the sender is named in the headline.
        ActivityKind.BOT_DM, ActivityKind.USER_PROMPT -> BotAvatar(name = item.actor, size = 36.dp)
    }
}

/** The row's one-line "what happened", built from the kind's own phrasing. */
@Composable
private fun ActivityItem.headline(): String =
    when (kind) {
        ActivityKind.BOT_DM ->
            stringResource(R.string.activity_dm_line, counterpart.orEmpty(), actor)

        ActivityKind.USER_PROMPT -> stringResource(R.string.activity_prompt_line, actor)

        ActivityKind.ROUTINE_RUN ->
            if (failed) {
                stringResource(R.string.activity_routine_failed, actor)
            } else {
                stringResource(R.string.activity_routine_ran, actor)
            }
    }

private fun ActivityBucket.labelRes(): Int =
    when (this) {
        ActivityBucket.TODAY -> R.string.activity_section_today
        ActivityBucket.YESTERDAY -> R.string.activity_section_yesterday
        ActivityBucket.EARLIER -> R.string.activity_section_earlier
        ActivityBucket.UNDATED -> R.string.activity_section_undated
    }

/**
 * Compact age of a row ("now", "14m", "2h", "3d"). Null when the backend sent
 * no usable stamp — the row still renders, it just carries no time.
 */
@Composable
private fun relativeTimeLabel(
    timestamp: Double?,
    now: Instant,
): String? {
    if (timestamp == null || timestamp <= 0.0) return null
    val seconds = now.epochSecond - timestamp.toLong()
    if (seconds < 0L) return stringResource(R.string.activity_time_now)
    return when {
        seconds < 60L -> stringResource(R.string.activity_time_now)
        seconds < 3600L -> stringResource(R.string.activity_time_minutes, seconds / 60L)
        seconds < 86_400L -> stringResource(R.string.activity_time_hours, seconds / 3600L)
        else -> stringResource(R.string.activity_time_days, seconds / 86_400L)
    }
}
