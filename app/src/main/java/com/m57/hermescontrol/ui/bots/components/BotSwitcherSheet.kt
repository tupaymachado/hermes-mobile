package com.m57.hermescontrol.ui.bots.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.bots.BotsScreen
import com.m57.hermescontrol.ui.bots.BotsViewModel
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.SkeletonListState

/**
 * Quick bot switcher (Fase 3): change the active bot without leaving the chat.
 *
 * Owns a SHEET-LOCAL [BotsViewModel] — deliberately not activity-scoped, so a
 * switcher opened from the chat never drags roster state (loading, toasts,
 * optimistic selection) into the drawer's BotsScreen and vice versa.
 *
 * Selection goes through the same [BotsViewModel.selectBot] path as the
 * roster, so canonical-chat handoff, navigation and failure rollback are all
 * inherited rather than reimplemented. The sheet dismisses on selection; the
 * coordinator's re-dial lands back on this same ChatScreen with the bot's
 * thread already resumed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotSwitcherSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onViewAll: () -> Unit = { NavigationController.navigateTo(BotsScreen) },
    viewModel: BotsViewModel = viewModel { BotsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadRoster() }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.screen_bots),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
            )

            when {
                state.isLoading && state.bots.isEmpty() -> {
                    SkeletonListState()
                }

                state.errorMessage != null && state.bots.isEmpty() -> {
                    ErrorState(
                        message = state.errorMessage ?: "",
                        onRetry = { viewModel.loadRoster() },
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.bots, key = { it.name }) { bot ->
                            BotRosterRow(
                                bot = bot,
                                onClick = {
                                    onDismiss()
                                    viewModel.selectBot(bot.name)
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(
                    onClick = {
                        onDismiss()
                        onViewAll()
                    },
                ) {
                    Text(stringResource(R.string.bots_switcher_view_all))
                }
            }
        }
    }
}
