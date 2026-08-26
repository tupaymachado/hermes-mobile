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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

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
 * Selection goes through [BotsViewModel.selectBot] BEFORE dismissing, and the
 * dismissal itself is the ViewModel's `onSwitched` callback — it fires only
 * once the server has accepted the flip. A failed switch therefore keeps the
 * sheet open with its toast visible, and tapping the bot that is already
 * active reports that instead of closing on a no-op.
 *
 * **Dismissal is animated.** Every path that closes the sheet from inside goes
 * through `sheetState.hide()` and only then drops the composable, per the M3
 * pattern; flipping the caller's flag directly yanks the sheet off screen with
 * no exit animation. `onDismissRequest` is the exception — Material has
 * already settled the sheet by the time it fires.
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
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // Settle the sheet out, THEN run [after] and drop the composable. hide()
    // suspends until the animation finishes, so anything that would remove the
    // sheet from composition has to wait on it.
    val dismissAnimated: (() -> Unit) -> Unit = { after ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            after()
        }
    }

    // Failure feedback lives OUTSIDE the sheet: selectBot reports via
    // toastMessage and only calls back on success, so a failed switch keeps
    // the sheet open with the toast visible (and a stale toast never leaks
    // into BotsScreen later).
    LaunchedEffect(Unit) { viewModel.loadRoster() }
    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    ModalBottomSheet(
        // Swipe-down and scrim taps arrive here already animated by Material —
        // re-running hide() would be a second animation on a settled sheet.
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        BotSwitcherSheetContent(
            bots = state.bots,
            isLoading = state.isLoading,
            errorMessage = state.errorMessage,
            onSelectBot = { name -> viewModel.selectBot(name, onSwitched = { dismissAnimated {} }) },
            // Navigation waits for the exit animation: navigating first tears
            // down the host screen and takes the sheet with it, mid-slide.
            onViewAll = { dismissAnimated(onViewAll) },
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
