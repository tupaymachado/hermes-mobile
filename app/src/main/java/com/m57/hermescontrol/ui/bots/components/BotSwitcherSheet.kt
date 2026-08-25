package com.m57.hermescontrol.ui.bots.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.BotsScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.bots.BotRosterItem
import com.m57.hermescontrol.ui.bots.BotsViewModel
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect

/**
 * Quick bot switcher (Fase 3): change the active bot without leaving the chat.
 *
 * **ViewModel scope.** `viewModel { BotsViewModel() }` resolves against the
 * Activity's ViewModelStoreOwner with the class-name key — the SAME instance
 * the drawer's `BotsScreen` uses. That is deliberate: selection must survive
 * the sheet's dismissal, because the switch continues past it (REST flip →
 * AuthManager → `_switched` → WS re-dial); a sheet-scoped VM would be
 * cancelled mid-switch and leave the server flipped but the app not re-homed —
 * exactly the split-brain `ProfileSwitchCoordinator` exists to prevent.
 *
 * Selection goes through [BotsViewModel.selectBot] BEFORE dismissing, so a
 * failure keeps the sheet open and its toast visible; dismissal happens only
 * on success or on explicit user cancel.
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

    // Failure feedback lives OUTSIDE the sheet: selectBot reports via
    // toastMessage, and the sheet only dismisses on success — so a failed
    // switch keeps the sheet open with the toast visible (and a stale toast
    // never leaks into BotsScreen later).
    LaunchedEffect(Unit) { viewModel.loadRoster() }
    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        BotSwitcherSheetContent(
            bots = state.bots,
            isLoading = state.isLoading,
            errorMessage = state.errorMessage,
            onSelectBot = { name ->
                viewModel.selectBot(name)
                if (viewModel.uiState.value.errorMessage == null) onDismiss()
            },
            onViewAll = {
                onDismiss()
                onViewAll()
            },
        )
    }
}

/**
 * Stateless content of the switcher, extracted so instrumented tests can drive
 * it with controlled inputs instead of the Activity-scoped [BotsViewModel]'s
 * real network fan-out.
 *
 * The elastic content region is capped at [SheetContentMaxHeight] so the
 * divider + "view all" footer stay visible regardless of roster size or
 * transient state (Skeleton/Error use fillMaxSize internally and would
 * otherwise push the footer off screen).
 */
@Composable
fun BotSwitcherSheetContent(
    bots: List<BotRosterItem>,
    isLoading: Boolean,
    errorMessage: String?,
    onSelectBot: (String) -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.screen_bots),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
        )

        when {
            isLoading && bots.isEmpty() ->
                SkeletonListState(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = SheetContentMaxHeight),
                )

            errorMessage != null && bots.isEmpty() ->
                ErrorState(
                    message = errorMessage,
                    onRetry = { /* caller-owned retry; sheet-level is reload */ },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = SheetContentMaxHeight),
                )

            bots.isEmpty() ->
                EmptyState(
                    title = stringResource(R.string.bots_empty_title),
                    subtitle = stringResource(R.string.bots_empty_desc),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = SheetContentMaxHeight),
                )

            else ->
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = SheetContentMaxHeight),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(bots, key = { it.name }) { bot ->
                        BotRosterRow(
                            bot = bot,
                            onClick = { onSelectBot(bot.name) },
                        )
                    }
                }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(onClick = onViewAll) {
                Text(stringResource(R.string.bots_switcher_view_all))
            }
        }
    }
}

/** Cap the elastic content region so the divider + footer stay on screen. */
private val SheetContentMaxHeight: Dp = 320.dp
