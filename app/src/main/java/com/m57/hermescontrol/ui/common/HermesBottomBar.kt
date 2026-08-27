package com.m57.hermescontrol.ui.common

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import com.m57.hermescontrol.ActivityScreen
import com.m57.hermescontrol.BotsScreen
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.NavigationController
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ScreenRegistry

/**
 * The app's three bottom-nav tabs.
 *
 * [key] is the destination a tab navigates to; the `null` on [MORE] is the
 * whole point of the sealed-ish shape — "More" is not a destination, it opens
 * the root navigation drawer where the other ~24 screens still live. Making it
 * a NavKey would mean either a fake screen or a second copy of the drawer.
 */
enum class BottomNavTab(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val key: NavKey?,
    val testTag: String,
) {
    BOTS(R.string.nav_tab_bots, Icons.Filled.SmartToy, BotsScreen, "bottom_nav_bots"),
    ACTIVITY(R.string.nav_tab_activity, Icons.Filled.Bolt, ActivityScreen, "bottom_nav_activity"),
    MORE(R.string.nav_tab_more, Icons.Filled.MoreHoriz, null, "bottom_nav_more"),
}

/**
 * Where the bottom bar shows and which tab it highlights.
 *
 * Pure and free of Compose on purpose: these two rules are the whole contract
 * of the new navigation shell, and they are worth testing without an emulator.
 */
object BottomNav {
    /**
     * The bar rides on top-level screens only.
     *
     * - [ChatScreen] is a *destination* now (you get there by picking a bot),
     *   not a tab: it owns the full height for its composer, and its back
     *   gesture returns to the tab you came from.
     * - Drill-down pages (settings sub-pages, toolset/memory detail) are not in
     *   [ScreenRegistry.ALL_SCREENS], so they fall out here without an
     *   exclusion list that would rot the next time one is added.
     * - Entry screens (Landing, AuthLogin) are likewise absent from the
     *   registry — the user has no session to navigate yet.
     */
    fun isVisibleOn(screen: NavKey): Boolean =
        when (screen) {
            ChatScreen -> false
            else -> ScreenRegistry.ALL_SCREENS.any { it.key == screen }
        }

    /**
     * The highlighted tab for [screen].
     *
     * Everything that is not a tab destination reads as [BottomNavTab.MORE] —
     * that is exactly what the tab means ("the rest of the app"), and it keeps
     * the bar from rendering with nothing selected on the ~24 drawer screens.
     */
    fun selectedOn(screen: NavKey): BottomNavTab =
        when (screen) {
            BotsScreen -> BottomNavTab.BOTS
            ActivityScreen -> BottomNavTab.ACTIVITY
            else -> BottomNavTab.MORE
        }
}

/**
 * Bottom navigation bar for the app shell.
 *
 * Hosted once by `MainNavigation`'s Scaffold rather than by each screen: the
 * screens keep using [HermesScaffold] untouched, and the bar cannot go missing
 * on a new screen or double up on an old one.
 *
 * @param onOpenMore opens the root drawer — the "More" tab's action.
 */
@Composable
fun HermesBottomBar(
    currentScreen: NavKey,
    onOpenMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = BottomNav.selectedOn(currentScreen)
    NavigationBar(modifier = modifier) {
        BottomNavTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = {
                    val key = tab.key
                    if (key == null) onOpenMore() else NavigationController.navigateTo(key)
                },
                // contentDescription = null: the label below carries the name,
                // so describing the icon doubles every announcement — the same
                // rule the drawer items and BotAvatar follow.
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
                modifier = Modifier.testTag(tab.testTag),
            )
        }
    }
}
