package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsTickerTest {
    private fun item(id: String, topic: String, title: String = id) = NewsItem(id, title, null, "Sport", topic, "Sport · 2 h ago", null, 0L)
    private fun lane(topic: String, vararg ids: String) = NewsLane(topic, NewsTopics.byId(topic)!!.label, "From Sport", ids.map { item(it, topic) })

    private val match = NewsMatchday(
        "espn-1", "Barça v Real Madrid", "Barça v Real Madrid · 21:00 · in 3 h", false,
        CalendarEvent("espn-1", "Barça v Real Madrid", 0L, 7_200_000L, false, null, "fixtures", null, "Fixtures"),
    )

    @Test
    fun theStripTakesOneStoryFromEachLaneInTurnBarcaFirst() {
        val place = NewsPlace(listOf(lane("barca", "b1", "b2", "b3"), lane("ai", "a1"), lane("top", "t1", "t2")), emptyList(), null)
        val t = TickerRules.ticker(place)
        assertEquals(listOf("b1", "a1", "t1", "b2", "t2", "b3"), t.items.map { it.id })
        assertNull(t.matchday)
        assertFalse(t.isEmpty)
    }

    @Test
    fun theStripHoldsAtMostTwelveStoriesAndLeadsWithTheMatch() {
        val many = (1..10).map { "b$it" }.toTypedArray()
        val more = (1..10).map { "a$it" }.toTypedArray()
        val t = TickerRules.ticker(NewsPlace(listOf(lane("barca", *many), lane("ai", *more)), emptyList(), null, match))
        assertEquals(TickerRules.MAX_ITEMS, t.items.size)
        assertEquals(listOf("b1", "a1", "b2", "a2"), t.items.take(4).map { it.id })
        assertEquals(match, t.matchday)
        // A matchday with no stories still makes a strip.
        assertFalse(TickerRules.ticker(NewsPlace(emptyList(), emptyList(), null, match)).isEmpty)
    }

    @Test
    fun nothingToShowMeansNoStrip() {
        val t = TickerRules.ticker(NewsPlace.EMPTY)
        assertTrue(t.isEmpty)
        assertFalse(TickerRules.shown(TickerMode.CALM, t))
        val some = TickerRules.ticker(NewsPlace(listOf(lane("ai", "a1")), emptyList(), null))
        assertTrue(TickerRules.shown(TickerMode.CALM, some))
        assertTrue(TickerRules.shown(TickerMode.MOVING, some))
        assertFalse(TickerRules.shown(TickerMode.OFF, some))
    }

    @Test
    fun theChoiceDefaultsToCalm() {
        assertEquals(TickerMode.CALM, TickerRules.mode(null))
        assertEquals(TickerMode.CALM, TickerRules.mode("sideways"))
        assertEquals(TickerMode.MOVING, TickerRules.mode("moving"))
        assertEquals(TickerMode.OFF, TickerRules.mode("off"))
        assertEquals(listOf("Calm", "Always moving", "Off"), TickerMode.entries.map { it.label })
    }

    @Test
    fun calmDriftsTwiceThenRestsAndPlaySetsItGoingAgain() {
        val m = TickerMode.CALM
        assertTrue(TickerRules.moving(m, reducedMotion = false, onScreen = true, held = false, loopsDone = 0, hasItems = true))
        assertTrue(TickerRules.moving(m, false, true, false, 1, true))
        assertFalse(TickerRules.moving(m, false, true, false, 2, true))
        assertTrue(TickerRules.offersPlay(m, false, 2, true))
        assertFalse(TickerRules.offersPlay(m, false, 1, true))
        // Always moving never rests and never needs ▸.
        assertTrue(TickerRules.moving(TickerMode.MOVING, false, true, false, 40, true))
        assertFalse(TickerRules.offersPlay(TickerMode.MOVING, false, 40, true))
    }

    @Test
    fun theStripStandsStillWhenTouchedOffScreenEmptyOrWithReducedMotion() {
        val m = TickerMode.MOVING
        assertFalse(TickerRules.moving(m, reducedMotion = true, onScreen = true, held = false, loopsDone = 0, hasItems = true))
        assertFalse(TickerRules.moving(m, false, onScreen = false, held = false, loopsDone = 0, hasItems = true))
        assertFalse(TickerRules.moving(m, false, true, held = true, loopsDone = 0, hasItems = true))
        assertFalse(TickerRules.moving(m, false, true, false, 0, hasItems = false))
        assertFalse(TickerRules.moving(TickerMode.OFF, false, true, false, 0, true))
        // Reduced motion pages instead: no ▸.
        assertFalse(TickerRules.offersPlay(TickerMode.CALM, true, 2, true))
    }

    @Test
    fun theStripDriftsAtFortyDpASecondAndCountsLoops() {
        var d = TickerDrift.START
        d = TickerRules.step(d, 50, 100f)
        assertEquals(2f, d.offsetDp)
        // A long gap (a pause, a dropped frame) moves no further than 100 ms.
        d = TickerRules.step(d, 5_000, 100f)
        assertEquals(6f, d.offsetDp)
        // Wrapping at the loop's width counts a loop and keeps the remainder.
        d = TickerRules.step(TickerDrift(99f, 0), 50, 100f)
        assertEquals(1, d.loops)
        assertTrue(kotlin.math.abs(d.offsetDp - 1f) < 0.001f)
        // Nothing moves before the width is known, or with no time passed.
        assertEquals(TickerDrift.START, TickerRules.step(TickerDrift.START, 50, 0f))
        assertEquals(TickerDrift(3f, 1), TickerRules.step(TickerDrift(3f, 1), 0, 100f))
    }

    @Test
    fun scrubbingKeepsThePlaceWithinOneLoopAndIsNeverALoop() {
        val d = TickerDrift(10f, 1)
        assertEquals(TickerDrift(30f, 1), TickerRules.scrubbed(d, 130f, 100f))
        assertEquals(TickerDrift(80f, 1), TickerRules.scrubbed(d, -20f, 100f))
        assertEquals(d, TickerRules.scrubbed(d, 50f, 0f))
    }

    @Test
    fun reducedMotionPagesWrapBothWays() {
        assertEquals(1, TickerRules.page(0, 1, 3))
        assertEquals(0, TickerRules.page(2, 1, 3))
        assertEquals(2, TickerRules.page(0, -1, 3))
        assertEquals(0, TickerRules.page(0, 1, 0))
        assertEquals("2 of 12", TickerRules.pageLabel(1, 12))
        assertEquals("", TickerRules.pageLabel(0, 0))
    }

    @Test
    fun screenReadersHearTheLaneSourceAndTitle() {
        assertEquals("Barça · Sport · 2 h ago: Pedri returns", TickerRules.spoken(item("b1", "barca", "Pedri returns")))
    }
}
