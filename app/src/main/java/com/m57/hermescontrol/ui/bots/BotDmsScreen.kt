package com.m57.hermescontrol.ui.bots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.bots.components.BotAvatar
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect

/**
 * Aggregated "Bot DMs" screen (PM1): every canonical Bot Chat currently holding
 * bot-to-bot traffic, newest first. Tapping a row switches to that bot and
 * resumes the thread — the same canonical path the roster uses.
 */
@Composable
fun BotDmsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: BotDmsViewModel = viewModel { BotDmsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadThreads() }
    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        modifier = modifier,
        title = { Text(stringResource(R.string.screen_bot_dms)) },
        navigationIcon = onOpenDrawer?.let { com.m57.hermescontrol.ui.common.NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadThreads() },
    ) { paddingValues ->
        when {
            state.isLoading && state.threads.isEmpty() ->
                SkeletonListState(modifier = Modifier.padding(paddingValues))

            state.errorMessage != null && state.threads.isEmpty() ->
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadThreads() },
                    modifier = Modifier.padding(paddingValues),
                )

            state.threads.isEmpty() ->
                EmptyState(
                    title = stringResource(R.string.bot_dms_empty_title),
                    subtitle = stringResource(R.string.bot_dms_empty_desc),
                    icon = Icons.Filled.Forum,
                    modifier = Modifier.padding(paddingValues),
                )

            else ->
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(paddingValues),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.threads, key = { "${it.botName}:${it.sessionId}" }) { thread ->
                        BotDmThreadRow(
                            thread = thread,
                            onClick = { viewModel.openThread(thread) },
                        )
                    }
                }
        }
        if (state.unscannedBots.isNotEmpty()) {
            Text(
                text =
                    stringResource(
                        R.string.bot_dms_partial,
                        state.unscannedBots.joinToString(", "),
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun BotDmThreadRow(
    thread: BotDmThreadItem,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BotAvatar(name = thread.botName, size = 20.dp)
                Text(
                    text = thread.title ?: thread.botName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text =
                    androidx.compose.ui.res.pluralStringResource(
                        R.plurals.bot_dms_count,
                        thread.dmCount,
                        thread.dmCount,
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.bot_dms_last_from, thread.lastDmSender),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = thread.lastDmBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
