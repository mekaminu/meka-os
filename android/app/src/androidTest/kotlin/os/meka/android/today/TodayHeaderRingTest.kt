package os.meka.android.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.DayArcKind
import os.meka.core.domain.WatchFace
import os.meka.core.domain.WatchArc
import os.meka.core.domain.DayRingHeader
import os.meka.core.domain.DayRingPlay

/**
 * Day ring hidden on the closed Fold (Meka, 2026-10-09: "I don't see anything") and the Fold review's header placement:
 * Today on a 412 dp-wide screen (the closed Fold) shows the Day ring, compact, to the right of the greeting; the open
 * Fold's width shows the wide dial. Runs on the Fold or an emulator (`./gradlew :android:app:connectedDebugAndroidTest`);
 * CI compiles it.
 */
class TodayHeaderRingTest {
    @get:Rule
    val compose = createComposeRule()

    private val face = WatchFace(
        listOf(WatchArc("e-1", DayArcKind.EVENT, 270f, 30f, current = false, highlighted = false)),
        work = emptyList(), upcoming = 1, next = "Standup 09:00",
    )

    private var opened = 0

    private companion object { const val SETTLE_MS = 2_000L }

    private fun header(widthDp: Int, compact: Boolean) {
        // The living face's second hand and breath run every frame, so Compose never goes idle on its own: drive the
        // clock by hand (motion workflow runs 17–27 timed out waiting for idle), and step past the draw-in.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(widthDp.dp)) {
                    TodayHeaderRow(face, compact, DayRingPlay.STILL, played = {}, onOpenFace = { opened++ }) { Text("Good morning, Meka") }
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
    }

    @Test
    fun todayOnA412DpWideScreenShowsTheDayRing() {
        header(412, compact = true)
        val dial = compose.onNodeWithTag(WATCH_FACE_TAG, useUnmergedTree = true)
        dial.assertIsDisplayed().assertWidthIsEqualTo(DayRingHeader.COMPACT_DP.dp)
        val greeting = compose.onNodeWithText("Good morning, Meka").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("the ring sits to the right of the greeting", dial.getUnclippedBoundsInRoot().left >= greeting.right)
    }

    @Test
    fun theOpenFoldShowsTheWideDialBesideTheGreeting() {
        header(840, compact = false)
        compose.onNodeWithTag(WATCH_FACE_TAG, useUnmergedTree = true).assertIsDisplayed()
            .assertWidthIsEqualTo(DayRingHeader.WIDE_DP.dp)
        compose.onNodeWithText("Good morning, Meka").assertIsDisplayed()
    }

    @Test
    fun tappingTheWatchFaceOpensTheWholeDay() {
        header(412, compact = true)
        compose.onNodeWithTag(WATCH_FACE_TAG, useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals(1, opened)
    }
}
