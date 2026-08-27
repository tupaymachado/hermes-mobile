package com.m57.hermescontrol.ui.common

import com.m57.hermescontrol.ActivityScreen
import com.m57.hermescontrol.BotDmsScreen
import com.m57.hermescontrol.BotsScreen
import com.m57.hermescontrol.ChatScreen
import com.m57.hermescontrol.CronJobsScreen
import com.m57.hermescontrol.LandingScreen
import com.m57.hermescontrol.SettingsAppearance
import com.m57.hermescontrol.SettingsScreen
import com.m57.hermescontrol.ToolsetDetailKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the bottom-nav shell's two rules: where the bar shows and
 * which tab it highlights.
 *
 * These are the whole contract of the navigation shell and they are pure, so
 * they belong here rather than in an instrumented test that needs an emulator
 * to answer "is the bar visible on the settings sub-page".
 */
class BottomNavTest {
    // ── isVisibleOn ───────────────────────────────────────────────────────

    @Test
    fun `bar shows on the tab destinations`() {
        assertTrue(BottomNav.isVisibleOn(BotsScreen))
        assertTrue(BottomNav.isVisibleOn(ActivityScreen))
    }

    @Test
    fun `bar shows on drawer screens so the tabs stay reachable`() {
        assertTrue(BottomNav.isVisibleOn(CronJobsScreen))
        assertTrue(BottomNav.isVisibleOn(SettingsScreen))
        // Bot DMs is the passive archive behind "More" now, not a tab.
        assertTrue(BottomNav.isVisibleOn(BotDmsScreen))
    }

    @Test
    fun `bar hides on chat`() {
        // Chat is a destination, not a tab: the composer owns that space, and
        // back returns to the tab that opened it.
        assertFalse(BottomNav.isVisibleOn(ChatScreen))
    }

    @Test
    fun `bar hides on drill-down pages`() {
        // Not registered in ScreenRegistry.ALL_SCREENS — which is what keeps
        // this rule from rotting when the next sub-page is added.
        assertFalse(BottomNav.isVisibleOn(SettingsAppearance))
        assertFalse(BottomNav.isVisibleOn(ToolsetDetailKey(name = "git", label = "Git")))
    }

    @Test
    fun `bar hides on entry screens`() {
        assertFalse(BottomNav.isVisibleOn(LandingScreen))
    }

    // ── selectedOn ────────────────────────────────────────────────────────

    @Test
    fun `tab destinations select themselves`() {
        assertEquals(BottomNavTab.BOTS, BottomNav.selectedOn(BotsScreen))
        assertEquals(BottomNavTab.ACTIVITY, BottomNav.selectedOn(ActivityScreen))
    }

    @Test
    fun `everything else reads as More`() {
        assertEquals(BottomNavTab.MORE, BottomNav.selectedOn(CronJobsScreen))
        assertEquals(BottomNavTab.MORE, BottomNav.selectedOn(SettingsScreen))
        assertEquals(BottomNavTab.MORE, BottomNav.selectedOn(BotDmsScreen))
    }

    // ── tab wiring ────────────────────────────────────────────────────────

    @Test
    fun `More has no destination — it opens the drawer`() {
        assertEquals(null, BottomNavTab.MORE.key)
        assertEquals(BotsScreen, BottomNavTab.BOTS.key)
        assertEquals(ActivityScreen, BottomNavTab.ACTIVITY.key)
    }
}
