package com.m57.hermescontrol.ui.bots

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.bots.components.BotRosterRow
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.ErrorState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.ui.common.SkeletonListState
import com.m57.hermescontrol.ui.common.ToastEffect
import com.m57.hermescontrol.ui.common.listContentPadding
import com.m57.hermescontrol.ui.common.listItemSpacing

/**
 * Bot Mode roster — the conversational surface over server-side Hermes
 * profiles. `ProfilesScreen` stays the administration surface (create, clone,
 * soul, model, rename); the two are deliberately NOT merged.
 */
@Composable
fun BotsScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: (() -> Unit)? = null,
    viewModel: BotsViewModel = viewModel { BotsViewModel() },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadRoster() }

    ToastEffect(toastMessage = state.toastMessage, onClearToast = viewModel::clearToast)

    HermesScaffold(
        modifier = modifier,
        title = { Text(stringResource(R.string.screen_bots)) },
        navigationIcon = onOpenDrawer?.let { NavIcon.Menu(it) },
        isRefreshing = state.isLoading,
        onRefresh = { viewModel.loadRoster() },
    ) { paddingValues ->
        when {
            state.isLoading && state.bots.isEmpty() ->
                SkeletonListState(modifier = Modifier.padding(paddingValues))

            state.errorMessage != null && state.bots.isEmpty() ->
                ErrorState(
                    message = state.errorMessage ?: "",
                    onRetry = { viewModel.loadRoster() },
                    modifier = Modifier.padding(paddingValues),
                )

            state.bots.isEmpty() ->
                EmptyState(
                    title = stringResource(R.string.bots_empty_title),
                    subtitle = stringResource(R.string.bots_empty_desc),
                    icon = Icons.Filled.SmartToy,
                    modifier = Modifier.padding(paddingValues),
                )

            else ->
                LazyColumn(
                    // The scaffold's padding goes on the LazyColumn itself, not
                    // on the item content — putting it on the content double-stacks
                    // the top gap (see AGENTS.md's padding note).
                    modifier =
                        Modifier
                            .padding(paddingValues)
                            .testTag("bots_roster"),
                    contentPadding = listContentPadding,
                    verticalArrangement = listItemSpacing,
                ) {
                    items(state.bots, key = { it.name }) { bot ->
                        BotRosterRow(
                            bot = bot,
                            onClick = { viewModel.selectBot(bot.name) },
                        )
                    }
                }
        }
    }
}
