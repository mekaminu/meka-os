package os.meka.android.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import os.meka.android.designsystem.MekaTheme
import os.meka.core.domain.DayArcKind
import os.meka.core.domain.DayRingPlay
import os.meka.core.domain.HeaderCollapseRules
import os.meka.core.domain.WatchArc
import os.meka.core.domain.WatchFace
import os.meka.core.domain.WatchFaceRules

/**
 * Watch face item, part 2: on the cover screen Today's header collapses as it scrolls — the face shrinks from its
 * [WatchFaceRules.COVER_DP] size into a [HeaderCollapseRules.COMPACT_DP] clock with a slim bar behind it, and grows back
 * at the top. Runs on the Fold or an emulator (motion workflow); CI compiles it.
 */
class CollapsingHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    private val face = WatchFace(
        listOf(WatchArc("e-1", DayArcKind.EVENT, 270f, 30f, current = false, highlighted = false)),
        work = emptyList(), upcoming = 1, next = "Standup 09:00",
    )

    private companion object {
        const val SETTLE_MS = 2_000L
        const val LIST_TAG = "collapsing-list"
    }

    private fun today() {
        // The face's second hand runs every frame: drive the clock by hand (see TodayHeaderRingTest).
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(412.dp).height(700.dp)) {
                    val list = rememberLazyListState()
                    var headerPx by remember { mutableIntStateOf(0) }
                    val barPx = with(LocalDensity.current) { COMPACT_BAR_HEIGHT.toPx() }
                    val shown by remember {
                        derivedStateOf {
                            HeaderCollapseRules.shown(HeaderCollapseRules.progress(list.firstVisibleItemIndex,
                                list.firstVisibleItemScrollOffset.toFloat(), HeaderCollapseRules.rangePx(headerPx.toFloat(), barPx)), false)
                        }
                    }
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag(LIST_TAG)) {
                        item {
                            Box(Modifier.onSizeChanged { headerPx = it.height }) {
                                TodayHeaderRow(face, compact = true, play = DayRingPlay.STILL, played = {}, slotOnly = true) {
                                    Text("Good morning, Alex")
                                    Text("Tue 13 Oct")
                                    Text("14° · light rain from 16:00")
                                }
                            }
                        }
                        items(40) { Text("Row $it", Modifier.height(56.dp)) }
                    }
                    CollapsingHeader(shown, face, WatchFaceRules.COVER_DP, "Good morning, Alex", "Tue 13 Oct · 14°",
                        DayRingPlay.STILL, played = {}, onOpenFace = {}, night = false, week = os.meka.core.domain.WeekGoals.EMPTY,
                        dots = emptyList(), onTick = null, onTop = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
    }

    /** The face as drawn (bounds through its scale layer). */
    private fun faceWidthDp(): Float =
        compose.onNodeWithTag(WATCH_FACE_TAG, useUnmergedTree = true).getBoundsInRoot().let { (it.right - it.left).value }

    @Test
    fun atRestTheFaceIsFullSizeAndThereIsNoBar() {
        today()
        compose.onNodeWithTag(WATCH_FACE_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(WatchFaceRules.COVER_DP.toFloat(), faceWidthDp(), 1f)
        assertTrue(compose.onAllNodesWithTagCount(COMPACT_HEADER_TAG) == 0)
    }

    @Test
    fun scrolledTheFaceIsACompactClockInASlimBarAndStillThere() {
        today()
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(10)
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        val bar = compose.onNodeWithTag(COMPACT_HEADER_TAG, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
        // The header at rest is taller than the compact bar.
        val barHeight = (bar.bottom - bar.top).value
        assertTrue("bar $barHeight is slim", barHeight < WatchFaceRules.COVER_DP + 24f)
        assertEquals(HeaderCollapseRules.COMPACT_DP.toFloat(), faceWidthDp(), 1.5f)
        // The clock is still drawn (and live) a second later.
        compose.mainClock.advanceTimeBy(1_000L)
        compose.onNodeWithTag(WATCH_FACE_TAG, useUnmergedTree = true).assertIsDisplayed()
        // Back to the top: full size again, no bar.
        compose.onNodeWithTag(LIST_TAG).performScrollToIndex(0)
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals(WatchFaceRules.COVER_DP.toFloat(), faceWidthDp(), 1f)
        assertTrue(compose.onAllNodesWithTagCount(COMPACT_HEADER_TAG) == 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size
}
