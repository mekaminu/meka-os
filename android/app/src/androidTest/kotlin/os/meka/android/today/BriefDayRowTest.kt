package os.meka.android.today

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.BriefRules
import os.meka.core.domain.TomorrowRow

/**
 * Fold review 2026-10-09 07:26, item 7: the brief's task rows sat indented in an empty time column, greyed. On the
 * closed Fold's width (412 dp) every row's title starts on the same left edge, and a task's tick circle completes it.
 * Runs on the Fold or an emulator; CI compiles it.
 */
class BriefDayRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun titlesShareOneEdgeAndTheTickCompletesTheTask() {
        val event = BriefRules.dayLine(TomorrowRow("e-1", "09:30", "Standup", true, "Room 4"))
        val task = BriefRules.dayLine(TomorrowRow("t-milk", null, "Buy milk", false, "Due 17:00"))
        var completed: String? = null
        compose.setContent {
            MekaTheme {
                Column(Modifier.width(412.dp)) {
                    BriefDayRow(event) { completed = it }
                    BriefDayRow(task) { completed = it }
                }
            }
        }
        val standup = compose.onNodeWithText("Standup").assertIsDisplayed().getUnclippedBoundsInRoot()
        val milk = compose.onNodeWithText("Buy milk").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals("one left edge", standup.left, milk.left)
        assertTrue("no time column: the title starts within 48 dp", milk.left <= 48.dp)
        compose.onNodeWithText("09:30 · Room 4").assertIsDisplayed()

        compose.onNodeWithContentDescription("Complete Buy milk").performClick()
        compose.waitUntil(2_000) { completed != null }
        assertEquals("milk", completed)
    }
}
