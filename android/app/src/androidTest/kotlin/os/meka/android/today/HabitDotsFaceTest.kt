package os.meka.android.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.DayRingHeader
import os.meka.core.domain.DayRingPlay
import os.meka.core.domain.HabitChip
import os.meka.core.domain.HabitDot
import os.meka.core.domain.HabitDotRules
import os.meka.core.domain.WatchFace

/**
 * Calm Today, slice 2: today's habits sit on the watch face as dots on the closed Fold's compact face; a tap on a dot
 * ticks that habit, a tap anywhere else opens the whole day, and a screen reader gets one action per habit. Runs on
 * the Fold or an emulator; CI compiles it.
 */
class HabitDotsFaceTest {
    @get:Rule
    val compose = createComposeRule()

    private val dots = HabitDotRules.dots(listOf(
        HabitChip("s", "Stretch", done = false, behind = false, tickLabel = "Tick Stretch for today"),
        HabitChip("r", "Read", done = true, behind = false, tickLabel = "Untick Read for today"),
    ))
    private val ticked = mutableListOf<String>()
    private var opened = 0

    private fun face(list: List<HabitDot> = dots) {
        // The living face draws every frame: drive the clock by hand (see TodayHeaderRingTest).
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(412.dp)) {
                    TodayHeaderRow(WatchFace.EMPTY, compact = true, DayRingPlay.STILL, played = {}, onOpenFace = { opened++ },
                        dots = list, onTick = { ticked += it.id }) { Text("Good morning, Meka") }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
    }

    @Test
    fun aTapOnADotTicksItsHabit() {
        face()
        val place = HabitDotRules.place(dots.first(), DayRingHeader.COMPACT_DP)
        compose.onNodeWithTag(WATCH_FACE_TAG, useUnmergedTree = true).performTouchInput {
            val px = density
            click(Offset(center.x + place.xDp * px, center.y + place.yDp * px))
        }
        compose.mainClock.advanceTimeBy(1_000L)
        assertEquals(listOf("s"), ticked)
        assertEquals(0, opened)
    }

    @Test
    fun aTapAwayFromTheDotsOpensTheDay() {
        face()
        compose.onNodeWithTag(WATCH_FACE_TAG, useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(1_000L)
        assertEquals(1, opened)
        assertTrue(ticked.isEmpty())
    }

    @Test
    fun aScreenReaderGetsOneActionPerHabit() {
        face()
        val node = compose.onNodeWithTag(WATCH_FACE_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val actions = node.config[SemanticsActions.CustomActions]
        assertEquals(listOf("Tick Stretch for today", "Untick Read for today"), actions.map { it.label })
        compose.runOnUiThread { actions.first().action() }
        assertEquals(listOf("s"), ticked)
    }
}
