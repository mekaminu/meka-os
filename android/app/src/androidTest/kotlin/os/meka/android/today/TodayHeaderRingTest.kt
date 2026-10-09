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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.DayArc
import os.meka.core.domain.DayArcKind
import os.meka.core.domain.DayRing
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

    private val ring = DayRing(
        listOf(DayArc("e-1", DayArcKind.EVENT, 9 * 60, 10 * 60, past = false)),
        nowMinute = 8 * 60, freeMinutes = 300, toDo = 3,
    )

    private fun header(widthDp: Int, compact: Boolean) {
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(widthDp.dp)) {
                    TodayHeaderRow(ring, compact, DayRingPlay.STILL, played = {}) { Text("Good morning, Meka") }
                }
            }
        }
    }

    @Test
    fun todayOnA412DpWideScreenShowsTheDayRing() {
        header(412, compact = true)
        val dial = compose.onNodeWithTag(DAY_RING_TAG, useUnmergedTree = true)
        dial.assertIsDisplayed().assertWidthIsEqualTo(DayRingHeader.COMPACT_DP.dp)
        val greeting = compose.onNodeWithText("Good morning, Meka").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("the ring sits to the right of the greeting", dial.getUnclippedBoundsInRoot().left >= greeting.right)
    }

    @Test
    fun theOpenFoldShowsTheWideDialBesideTheGreeting() {
        header(840, compact = false)
        compose.onNodeWithTag(DAY_RING_TAG, useUnmergedTree = true).assertIsDisplayed()
            .assertWidthIsEqualTo(DayRingHeader.WIDE_DP.dp)
        compose.onNodeWithText("Good morning, Meka").assertIsDisplayed()
    }
}
