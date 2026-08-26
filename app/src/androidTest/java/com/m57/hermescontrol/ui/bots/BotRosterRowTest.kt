package com.m57.hermescontrol.ui.bots

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.theme.HermesControlTheme
import com.m57.hermescontrol.ui.bots.components.BotAvatar
import com.m57.hermescontrol.ui.bots.components.BotRosterRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the Bot Mode roster row (Fase 1).
 *
 * The row is tested directly rather than through `BotsScreen` so the assertions
 * cover rendering, not a ViewModel's network fan-out.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class BotRosterRowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setRow(
        bot: BotRosterItem,
        onClick: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            HermesControlTheme(useDynamicColors = false) {
                BotRosterRow(bot = bot, onClick = onClick)
            }
        }
    }

    @Test
    fun row_showsNameAndLastMessage() {
        setRow(
            BotRosterItem(
                name = "research",
                presence = BotPresence.ONLINE,
                lastMessage = "what is the deploy status?",
            ),
        )

        composeTestRule.onNodeWithText("research").assertIsDisplayed()
        composeTestRule.onNodeWithText("what is the deploy status?").assertIsDisplayed()
    }

    @Test
    fun row_withoutLastMessage_showsPlaceholder() {
        setRow(BotRosterItem(name = "fresh", presence = BotPresence.OFFLINE))

        // A bot never talked to still renders a full-height row.
        composeTestRule.onNodeWithText("No messages yet").assertIsDisplayed()
    }

    @Test
    fun presenceDot_isAnnouncedToScreenReaders() {
        setRow(BotRosterItem(name = "research", presence = BotPresence.OFFLINE))

        // Unmerged: the row merges its children's semantics, so the dot's own
        // description is only addressable in the unmerged tree. The bot's name
        // is part of it — an 8dp dot announcing a bare "Offline" is a state
        // with no subject.
        composeTestRule.onNodeWithContentDescription("research: Offline", useUnmergedTree = true).assertExists()
    }

    @Test
    fun avatar_isDecorativeAndNeverAnnouncesTheMonogram() {
        setRow(BotRosterItem(name = "research bot", presence = BotPresence.ONLINE))

        // The name is announced by the Text beside it; "RB" must not be
        // ANNOUNCED. clearAndSetSemantics hides the monogram from screen
        // readers (merged tree) but the Text node still exists in the raw
        // composition — so assert on the merged tree, which is what TalkBack
        // actually reads, and require the monogram to be absent there.
        composeTestRule.onNodeWithText("RB").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("RB").assertDoesNotExist()
        // The row's real identity IS announced, via the name text.
        composeTestRule.onNodeWithText("research bot").assertExists()
    }

    @Test
    fun avatar_announcesAnExplicitDescriptionWhenGivenOne() {
        composeTestRule.setContent {
            HermesControlTheme(useDynamicColors = false) {
                BotAvatar(name = "research", contentDescription = "Avatar for research")
            }
        }

        composeTestRule.onNodeWithContentDescription("Avatar for research").assertExists()
    }

    @Test
    fun row_withUnavailableLastMessage_saysSoInsteadOfLookingEmpty() {
        setRow(
            BotRosterItem(
                name = "broken",
                presence = BotPresence.ONLINE,
                lastMessageUnavailable = true,
            ),
        )

        // A failed per-bot lookup must not read as "this bot has no messages".
        composeTestRule.onNodeWithText("Last message unavailable").assertIsDisplayed()
        composeTestRule.onNodeWithText("No messages yet").assertDoesNotExist()
    }

    @Test
    fun activeBot_showsCheckMarker() {
        setRow(BotRosterItem(name = "default", isActive = true, presence = BotPresence.ACTIVE))

        composeTestRule.onNodeWithTag("bot_active_check", useUnmergedTree = true).assertExists()
    }

    @Test
    fun inactiveBot_hasNoCheckMarker() {
        setRow(BotRosterItem(name = "research", presence = BotPresence.ONLINE))

        composeTestRule.onNodeWithTag("bot_active_check", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun row_isClickable() {
        var clicked = 0
        setRow(BotRosterItem(name = "research"), onClick = { clicked++ })

        composeTestRule.onNodeWithText("research").performClick()

        assertEquals(1, clicked)
    }
}
