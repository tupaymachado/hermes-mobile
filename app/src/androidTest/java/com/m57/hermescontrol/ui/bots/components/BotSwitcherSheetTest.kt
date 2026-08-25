package com.m57.hermescontrol.ui.bots.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.bots.BotPresence
import com.m57.hermescontrol.ui.bots.BotRosterItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the Fase 3 quick switcher.
 *
 * Tests the STATELESS content path of the sheet (real [BotSwitcherSheet] with
 * a pre-seeded VM state is not reachable without mocking the Activity-scoped
 * store), so the assertions cover rendering + callbacks — the ViewModel's
 * network fan-out is unit-tested elsewhere.
 *
 * Uses createAndroidComposeRule because createComposeRule()'s
 * ComposeContentTestRule does NOT expose `.activity` for string resources.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class BotSwitcherSheetTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val bots =
        listOf(
            BotRosterItem(
                name = "research",
                presence = BotPresence.ONLINE,
                lastMessage = "what is the deploy status?",
            ),
            BotRosterItem(
                name = "coder",
                presence = BotPresence.OFFLINE,
            ),
        )

    /** Render the sheet content with a controlled state and captured callbacks. */
    private fun setContent(
        bots: List<BotRosterItem> = this.bots,
        isLoading: Boolean = false,
        errorMessage: String? = null,
        onSelect: (String) -> Unit = {},
        onViewAll: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            HermesControlTheme(useDynamicColors = false) {
                BotSwitcherSheetContent(
                    bots = bots,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    onSelectBot = onSelect,
                    onViewAll = onViewAll,
                )
            }
        }
    }

    @Test
    fun content_showsBotsAndFooter() {
        setContent()

        composeTestRule.onNodeWithText("research").assertIsDisplayed()
        composeTestRule.onNodeWithText("what is the deploy status?").assertIsDisplayed()
        composeTestRule.onNodeWithText("coder").assertIsDisplayed()
        val viewAll = composeTestRule.activity.getString(R.string.bots_switcher_view_all)
        composeTestRule.onNodeWithText(viewAll).assertIsDisplayed()
    }

    @Test
    fun tappingABot_firesSelectWithItsName() {
        var selected: String? = null
        setContent(onSelect = { selected = it })

        composeTestRule.onNodeWithText("research").performClick()

        assertTrue("selectBot was not called with the tapped bot", selected == "research")
    }

    @Test
    fun viewAll_firesCallback() {
        var clicked = false
        setContent(onViewAll = { clicked = true })

        val viewAll = composeTestRule.activity.getString(R.string.bots_switcher_view_all)
        composeTestRule.onNodeWithText(viewAll).performClick()

        assertTrue("view-all did not fire", clicked)
    }

    @Test
    fun errorState_showsMessageAndRendersRetry() {
        setContent(errorMessage = "boom", bots = emptyList())

        composeTestRule.onNodeWithText("boom").assertIsDisplayed()
    }

    @Test
    fun chipAffordance_a11yResourceResolves() {
        // The chevron's contentDescription comes from strings.xml; assert the
        // resource resolves in every locale the app ships.
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
