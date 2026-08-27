package com.m57.hermescontrol

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * Central navigation controller with deduplication guard.
 *
 * Top-level primary screens (drawer items) clear the back stack and become the new
 * root — this matches navigation drawer patterns where switching top-level sections
 * resets the stack.
 *
 * B7 (Jun 18 2026): Never call `backStack.add()` directly from UI callbacks.
 * Always route through [navigateTo] to prevent stacking duplicate screen
 * entries that compete for touch events.
 */
object NavigationController {
    var backStack: NavBackStack<NavKey>? = null
    var pendingSessionId: String? by mutableStateOf(null)
        private set

    /**
     * Top-level screens: reaching one clears the stack and becomes the new root.
     *
     * [ChatScreen] is deliberately NOT here since the bottom-nav shell landed.
     * A chat is reached by picking a bot, so it has to stack ON TOP of the tab
     * you came from — as a primary screen it cleared that tab away and left
     * back with nowhere to return to but the fallback.
     */
    private val primaryScreens: MutableSet<NavKey> =
        mutableSetOf(
            BotsScreen,
            ActivityScreen,
            SkillsScreen,
            CronJobsScreen,
            SystemScreen,
            SettingsScreen,
        )

    /** Returns whether the given key is a primary top-level screen. */
    fun isPrimaryScreen(key: NavKey): Boolean = key in primaryScreens

    fun navigateTo(key: NavKey) {
        val stack = backStack ?: return
        if (stack.lastOrNull() == key) return

        if (isPrimaryScreen(key)) {
            stack.clear()
        }
        // Drawer dismissal is handled by DrawerGestureController (issue #619):
        // when a non-gesture sub-page composes, its HermesScaffold reconciles
        // drawerGesturesEnabled=false and the controller closes the drawer
        // itself via SideEffect. No synchronous closeDrawer callback here.
        stack.add(key)
    }

    fun openChatSession(sessionId: String) {
        if (sessionId.isBlank()) return
        pendingSessionId = sessionId
        navigateTo(ChatScreen)
    }

    fun consumePendingSessionId(): String? = pendingSessionId.also { pendingSessionId = null }

    /** Clear the stack and navigate to the given screen atomically. */
    fun resetTo(screen: NavKey) {
        val stack = backStack ?: return
        stack.clear()
        stack.add(screen)
    }

    /**
     * Navigate back one step, or fall back to [fallback] when the stack has only one item.
     * Never leaves the stack empty.
     *
     * The old "back from Chat always opens History" special case is gone with
     * the bottom-nav shell: Chat now stacks on the tab that opened it, so a
     * plain pop returns to that tab. Chat is only ever the ROOT on a cold start
     * from a notification, and there [fallback] — the Bots home — is the right
     * place to land.
     */
    fun goBack(fallback: NavKey = BotsScreen) {
        val stack = backStack ?: return
        if (stack.size > 1) {
            stack.removeLastOrNull()
        } else if (stack.size == 1) {
            stack.clear()
            stack.add(fallback)
        }
    }
}
