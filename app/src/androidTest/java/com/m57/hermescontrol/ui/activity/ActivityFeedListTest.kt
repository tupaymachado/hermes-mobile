package com.m57.hermescontrol.ui.activity

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesControlTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Instrumented tests for the Activity feed's rendering.
 *
 * Drives the stateless [ActivityFeedList] with a FIXED `now`, so day headers
 * and relative times are deterministic instead of depending on when the suite
 * runs. The feed's shape (what becomes a row, how sources merge) is unit-tested
 * in `ActivityItemTest`; this covers what the user actually sees and taps.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ActivityFeedListTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val now = Instant.parse("2026-08-27T12:00:00Z")

    private val dm =
        ActivityItem(
            id = "dm:s1:0",
            kind = ActivityKind.BOT_DM,
            actor = "research",
            counterpart = "Hermes",
            body = "deploy is green",
            timestamp = now.epochSecond - 600.0,
            botName = "research",
            sessionId = "s1",
        )

    private val routine =
        ActivityItem(
            id = "cron:job1:1",
            kind = ActivityKind.ROUTINE_RUN,
            actor = "nightly-test",
            body = "Runs at 03:00",
            timestamp = now.epochSecond - 2 * 86_400.0,
            failed = true,
        )

    private fun setContent(
        items: List<ActivityItem> = listOf(dm, routine),
        onOpenItem: (ActivityItem) -> Unit = {},
    ) {
        composeTestRule.setContent {
            HermesControlTheme(useDynamicColors = false) {
                ActivityFeedList(items = items, onOpenItem = onOpenItem, now = now)
            }
        }
    }

    @Test
    fun botDeliveryRendersSenderAndBody() {
        setContent()

        val headline =
            composeTestRule.activity.getString(R.string.activity_dm_line, "Hermes", "research")
        composeTestRule.onNodeWithText(headline).assertIsDisplayed()
        composeTestRule.onNodeWithText("deploy is green").assertIsDisplayed()
    }

    @Test
    fun failedRoutineRendersAsFailed() {
        setContent()

        val headline =
            composeTestRule.activity.getString(R.string.activity_routine_failed, "nightly-test")
        composeTestRule.onNodeWithText(headline).assertIsDisplayed()
    }

    @Test
    fun rowsAreFiledUnderDayHeaders() {
        setContent()

        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.activity_section_today))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(composeTestRule.activity.getString(R.string.activity_section_earlier))
            .assertIsDisplayed()
    }

    @Test
    fun tappingARowOpensIt() {
        var opened: ActivityItem? = null
        setContent(onOpenItem = { opened = it })

        composeTestRule.onNodeWithText("deploy is green").performClick()

        assertEquals(dm, opened)
    }
}
