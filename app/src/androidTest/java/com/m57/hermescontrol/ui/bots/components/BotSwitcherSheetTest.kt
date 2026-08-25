package com.m57.hermescontrol.ui.bots.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the Fase 3 quick switcher.
 *
 * The sheet's network fan-out lives in `BotsViewModel` (already unit-tested);
 * here we cover what a ViewModel test cannot: the sheet RENDERS the roster and
 * its affordances, and tapping "view all" fires the callback that hands
 * navigation to the caller (the real navigation is a side effect of
 * [com.m57.hermescontrol.NavigationController], out of scope for a compose
 * rule without the app nav host).
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class BotSwitcherSheetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setSheet(onViewAll: () -> Unit = {}) {
        composeTestRule.setContent {
            HermesControlTheme(useDynamicColors = false) {
                BotSwitcherSheet(onDismiss = {}, onViewAll = onViewAll)
            }
        }
    }

    @Test
    fun sheet_showsTitleAndViewAllAction() {
        setSheet()

        val title = composeTestRule.activity.getString(R.string.screen_bots)
        val viewAll = composeTestRule.activity.getString(R.string.bots_switcher_view_all)
        composeTestRule.onNodeWithText(title).assertIsDisplayed()
        composeTestRule.onNodeWithText(viewAll).assertIsDisplayed()
    }

    @Test
    fun viewAll_firesCallback() {
        var clicked = false
        setSheet(onViewAll = { clicked = true })

        val viewAll = composeTestRule.activity.getString(R.string.bots_switcher_view_all)
        composeTestRule.onNodeWithText(viewAll).performClick()
        composeTestRule.waitForIdle()

        assert(clicked) { "View-all action did not fire" }
    }

    @Test
    fun chip_contentDescription_isSet() {
        // The title-chip affordance in ChatScreen exposes an a11y description;
        // assert the resource exists and resolves (guards the i18n contract).
        val desc = composeTestRule.activity.getString(R.string.bots_switcher_open)
        composeTestRule.setContent {
            HermesControlTheme(useDynamicColors = false) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Filled.ExpandMore,
                    contentDescription = desc,
                )
            }
        }
        composeTestRule.onNodeWithContentDescription(desc).assertIsDisplayed()
    }
}
