package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodayNewsTest {
    private fun item(id: String, topic: String = "top") = NewsItem(id, "Headline $id", null, "BBC News", topic, "BBC News · 49 min ago", null, 0L)

    private val match = NewsMatchday(
        "espn-1", "Barça v Real Madrid", "Barça v Real Madrid · 21:00 · in 3 h", false,
        CalendarEvent("espn-1", "Barça v Real Madrid", 0L, 7_200_000L, false, null, "fixtures", null, "Fixtures"),
    )

    @Test
    fun theMatchLeadsThenTheStoriesInOrder() {
        val cards = TodayNewsRules.cards(NewsTicker(match, listOf(item("a", "barca"), item("b"))))
        assertEquals(listOf("match:espn-1", "a", "b"), cards.map { it.key })
        assertNull(cards[0].storyId)
        assertEquals(match, cards[0].match)
        assertTrue(cards[0].barca)
        assertEquals("Barça v Real Madrid", cards[0].title)
        assertEquals("21:00 · in 3 h", cards[0].meta)
        assertEquals(match.line, cards[0].spoken)
        assertEquals("a", cards[1].storyId)
        assertTrue(cards[1].barca)
        assertFalse(cards[2].barca)
        assertEquals("BBC News · 49 min ago", cards[2].meta)
    }

    @Test
    fun atMostEightCardsAndNoRepeats() {
        val many = (1..12).map { item("s$it") } + item("s1")
        val cards = TodayNewsRules.cards(NewsTicker(null, many))
        assertEquals(TodayNewsRules.MAX_CARDS, cards.size)
        assertEquals(cards.map { it.key }.distinct(), cards.map { it.key })
    }

    @Test
    fun theRowStartsAtTheFirstCardAndOnlyHintsWhenThereIsMore() {
        assertEquals(0, TodayNewsRules.START_INDEX)
        assertEquals("", TodayNewsRules.hint(1))
        assertEquals("swipe ›", TodayNewsRules.hint(2))
        assertEquals("News, 1 story", TodayNewsRules.spokenLabel(1))
        assertEquals("News, 4 stories", TodayNewsRules.spokenLabel(4))
    }

    @Test
    fun offOrNothingHidesIt() {
        assertFalse(TodayNewsRules.shown(TickerMode.OFF, NewsTicker(null, listOf(item("a")))))
        assertFalse(TodayNewsRules.shown(TickerMode.CALM, NewsTicker.EMPTY))
        assertTrue(TodayNewsRules.shown(TickerMode.CALM, NewsTicker(null, listOf(item("a")))))
        assertTrue(TodayNewsRules.cards(NewsTicker.EMPTY).isEmpty())
    }

    @Test
    fun aStoryCardSpeaksItsLaneSourceAndHeadline() {
        val c = TodayNewsRules.cards(NewsTicker(null, listOf(item("a", "barca")))).single()
        assertTrue(c.spoken.endsWith(": Headline a"))
    }

    @Test
    fun theRowDriftsContinuouslyInCalmAndMovingAndOnlyTheAppsMotionOffStillsIt() {
        assertTrue(TodayNewsRules.drifts(TickerMode.CALM, 4, reduced = false))
        assertTrue(TodayNewsRules.drifts(TickerMode.MOVING, 4, reduced = false))
        kotlin.test.assertFalse(TodayNewsRules.drifts(TickerMode.CALM, 4, reduced = true))
        kotlin.test.assertFalse(TodayNewsRules.drifts(TickerMode.MOVING, 1, reduced = false))
        kotlin.test.assertFalse(TodayNewsRules.drifts(TickerMode.OFF, 4, reduced = false))
    }

    @Test
    fun theDriftIsASlowConstantGlideThatWrapsWithoutASeam() {
        // 40 dp a second: a tenth of a second moves 4 dp; a long gap counts as one short step, never a jump.
        assertEquals(4f, TodayNewsRules.drift(0f, 100L, 1000f))
        assertEquals(2f, TodayNewsRules.drift(998f, 100L, 1000f))
        assertEquals(4f, TodayNewsRules.drift(0f, 60_000L, 1000f))
        assertEquals(5f, TodayNewsRules.drift(5f, 100L, 0f))
        // Laid out three times: past the first set it jumps back to the same card in the first set.
        assertEquals(null, TodayNewsRules.wrapIndex(3, 4))
        assertEquals(0, TodayNewsRules.wrapIndex(4, 4))
        assertEquals(1, TodayNewsRules.wrapIndex(9, 4))
    }

    @Test
    fun nothingChosenIsAlwaysMoving() {
        assertEquals(TickerMode.MOVING, TickerRules.mode(null))
    }
}
