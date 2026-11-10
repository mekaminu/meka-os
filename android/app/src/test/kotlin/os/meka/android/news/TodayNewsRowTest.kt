package os.meka.android.news

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import os.meka.android.designsystem.MekaTheme
import os.meka.android.screens.ShotApplication
import os.meka.core.domain.NewsItem
import os.meka.core.domain.NewsTicker
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random

/**
 * The Today rebuild, slice 2b-2b-2b-3 (Meka 2026-11-09 08:09 (d)): Today's news is a row of picture cards you swipe,
 * starting at the first card whole (the ticker showed half a card at the left edge). Invented headlines only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ShotApplication::class)
class TodayNewsRowTest {
    @get:Rule
    val compose = createComposeRule()

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(3), timeZone = { TimeZone.of("Europe/London") }, nowMs = { 0L },
    )

    private fun story(id: String, title: String) = NewsItem(id, title, null, "BBC News", "top", "BBC News · 49 min ago", null, 0L)

    @Test
    fun theRowStartsAtTheFirstCardWholeAndATapOpensItsStory() {
        val ticker = NewsTicker(null, listOf(story("s1", "Markets steady ahead of a rate decision"), story("s2", "Weekend weather: cold and clear"), story("s3", "A town opens a new library")))
        var opened: String? = null
        val core = core()
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(360.dp).padding(horizontal = 16.dp)) {
                    TodayNewsRow(core, ticker, openStory = { opened = it }, openMatch = {})
                }
            }
        }
        compose.onNodeWithText("NEWS").assertExists()
        compose.onNodeWithText("swipe ›").assertExists()
        val row = compose.onNodeWithTag(TODAY_NEWS_TAG).fetchSemanticsNode().boundsInRoot
        val first = compose.onAllNodesWithTag("today-news-card").fetchSemanticsNodes().first().boundsInRoot
        assertEquals("the first card starts at the row's left edge", row.left, first.left, 0.5f)
        assertTrue("the first card is whole", first.right <= row.right)
        compose.onAllNodesWithTag("today-news-card")[0].performClick()
        compose.waitForIdle()
        assertEquals("s1", opened)
    }

    @Test
    fun oneCardHasNoSwipeHint() {
        val core = core()
        compose.setContent {
            MekaTheme { TodayNewsRow(core, NewsTicker(null, listOf(story("s1", "A town opens a new library"))), openStory = {}, openMatch = {}) }
        }
        compose.onNodeWithText("NEWS").assertExists()
        compose.onNodeWithText("swipe ›").assertDoesNotExist()
    }

    /** Where the first story's own card starts, or null once it has drifted out of the row (disposed). */
    private fun firstStoryLeft(): Float? = compose.onAllNodes(hasContentDescription("Markets steady", substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.left

    private fun setRow(ticker: NewsTicker) {
        val core = core()
        compose.setContent {
            MekaTheme {
                Box(Modifier.width(360.dp).padding(horizontal = 16.dp)) {
                    TodayNewsRow(core, ticker, openStory = {}, openMatch = {})
                }
            }
        }
    }

    private val three = NewsTicker(null, listOf(story("s1", "Markets steady ahead of a rate decision"), story("s2", "Weekend weather: cold and clear"), story("s3", "A town opens a new library")))

    @Test
    fun theRowDriftsAcrossOnItsOwnWithNoTouch() {
        // Meka 2026-11-10 07:38: "my news are not scrolling as before". Over ten seconds, untouched, it glides on.
        compose.mainClock.autoAdvance = false
        setRow(three)
        compose.mainClock.advanceTimeByFrame()
        val row = compose.onNodeWithTag(TODAY_NEWS_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(row.left, firstStoryLeft()!!, 12f)
        compose.mainClock.advanceTimeBy(10_000)
        val left = firstStoryLeft()
        assertTrue("the first card has drifted away to the left (was at ${row.left}, now $left)", left == null || left < row.left - 100f)
    }

    @Test
    fun hisFingerOnTheRowPausesItAndItPicksUpAfterHeLetsGo() {
        compose.mainClock.autoAdvance = false
        setRow(three)
        compose.mainClock.advanceTimeByFrame()
        val row = compose.onNodeWithTag(TODAY_NEWS_TAG).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(TODAY_NEWS_ROW_TAG).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(10_000)
        assertEquals("held: it stays put", row.left, firstStoryLeft()!!, 12f)
        compose.onNodeWithTag(TODAY_NEWS_ROW_TAG).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals("just let go: it waits a few seconds", row.left, firstStoryLeft()!!, 12f)
        compose.mainClock.advanceTimeBy(10_000)
        val left = firstStoryLeft()
        assertTrue("then it drifts again (now $left)", left == null || left < row.left - 100f)
    }
}
