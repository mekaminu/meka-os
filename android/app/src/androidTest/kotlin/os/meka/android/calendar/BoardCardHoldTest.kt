package os.meka.android.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.BoardHandRules

/**
 * Week board by hand (Meka 2026-10-21 19:00: "can't move this around or up and down"): on the board a card held still
 * for a moment lifts and follows the finger, even inside the board's sideways scroll; a quick swipe still scrolls the
 * board and lifts nothing; a tap is a tap. Runs on the Fold or an emulator
 * (`./gradlew :android:app:connectedDebugAndroidTest`); CI compiles it.
 */
class BoardCardHoldTest {
    @get:Rule
    val compose = createComposeRule()

    private var taps = 0
    private val lifts = mutableListOf<Offset>()
    private val moves = mutableListOf<Offset>()
    private var drops = 0
    private var cancels = 0
    private lateinit var scroll: ScrollState

    private fun show() {
        compose.setContent {
            MekaTheme {
                scroll = rememberScrollState()
                Row(Modifier.horizontalScroll(scroll).testTag("board")) {
                    Box(
                        Modifier.size(240.dp, 96.dp).testTag("card").boardCardHold(
                            "c1",
                            onTap = { taps++ },
                            onLift = { lifts += it },
                            onMove = { moves += it },
                            onDrop = { drops++ },
                            onCancel = { cancels++ },
                        ),
                    )
                    Spacer(Modifier.width(3000.dp)) // the rest of the week, so the board can scroll
                }
            }
        }
        compose.waitForIdle()
    }

    private fun moved(): Offset = moves.fold(Offset.Zero) { a, b -> a + b }

    @Test
    fun heldStillTheCardLiftsAndTheBoardDoesNotScroll() {
        show()
        compose.onNodeWithTag("card").performTouchInput {
            down(center)
            advanceEventTime(BoardHandRules.HOLD_MS + 100)
            moveBy(Offset(-200f, 0f)) // towards the next day: the board would scroll this way
            moveBy(Offset(-200f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals(1, lifts.size)
        assertEquals(1, drops)
        assertEquals(0, cancels)
        assertEquals(0, taps)
        assertTrue("the held card follows the finger", moved().x <= -399f)
        assertEquals("the board keeps still under a held card", 0, scroll.value)
    }

    @Test
    fun heldStillTheCardMovesUpAndDownWithinTheDay() {
        show()
        compose.onNodeWithTag("card").performTouchInput {
            down(center)
            advanceEventTime(BoardHandRules.HOLD_MS + 100)
            moveBy(Offset(0f, 120f))
            moveBy(Offset(0f, -300f))
            up()
        }
        compose.waitForIdle()
        assertEquals(1, lifts.size)
        assertEquals(1, drops)
        assertEquals(-180f, moved().y, 1f)
        assertEquals(0, scroll.value)
    }

    @Test
    fun aQuickSwipeScrollsTheBoardAndLiftsNothing() {
        show()
        compose.onNodeWithTag("card").performTouchInput { swipeLeft(durationMillis = 120) }
        compose.waitForIdle()
        assertTrue("the board scrolled", scroll.value > 0)
        assertEquals(0, lifts.size)
        assertEquals(0, drops)
        assertEquals(0, taps)
    }

    @Test
    fun aTapIsATapNotALift() {
        show()
        compose.onNodeWithTag("card").performTouchInput {
            down(center)
            advanceEventTime(60)
            up()
        }
        compose.waitForIdle()
        assertEquals(1, taps)
        assertEquals(0, lifts.size)
        assertEquals(0, scroll.value)
    }
}
