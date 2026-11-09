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
    fun theRowDriftsAcrossOnItsOwnAndAwipePausesIt() {
        assertEquals(0, TodayNewsRules.driftSteps(TickerMode.CALM, 4, reduced = true))
        assertEquals(0, TodayNewsRules.driftSteps(TickerMode.CALM, 1, reduced = false))
        assertEquals(8, TodayNewsRules.driftSteps(TickerMode.CALM, 4, reduced = false))
        assertEquals(Int.MAX_VALUE, TodayNewsRules.driftSteps(TickerMode.MOVING, 4, reduced = false))
        assertEquals(1, TodayNewsRules.nextIndex(0, 4))
        assertEquals(0, TodayNewsRules.nextIndex(3, 4))
        kotlin.test.assertTrue(TodayNewsRules.mayDrift(null, 0L))
        kotlin.test.assertFalse(TodayNewsRules.mayDrift(10_000L, 12_000L))
        kotlin.test.assertTrue(TodayNewsRules.mayDrift(10_000L, 10_000L + TodayNewsRules.RESUME_AFTER_MS))
    }
}
