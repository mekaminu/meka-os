package os.meka.android.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.HabitChip

/**
 * Fold review 2026-10-09 07:26, item 3: Today's habits are compact chips on the closed Fold's width (412 dp), a tap
 * ticks the habit, and with no habits there is no row. Runs on the Fold or an emulator; CI compiles it.
 */
class HabitChipsRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val ticked = mutableListOf<String>()

    private fun row(chips: List<HabitChip>) {
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(412.dp)) { HabitChipsRow(chips, play = false, tick = { ticked += it.id }) }
            }
        }
    }

    @Test
    fun chipsShowOnTheClosedFoldAndATapTicks() {
        row(listOf(
            HabitChip("s", "Stretch", done = false, behind = false, tickLabel = "Tick Stretch for today"),
            HabitChip("r", "Read", done = true, behind = false, tickLabel = "Untick Read for today"),
        ))
        compose.onNodeWithTag(HABIT_CHIPS_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Tick Stretch for today").assertIsDisplayed().performClick()
        assertEquals(listOf("s"), ticked)
    }

    @Test
    fun noHabitsNoRow() {
        row(emptyList())
        compose.onNodeWithTag(HABIT_CHIPS_TAG).assertDoesNotExist()
    }
}
