package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BedsideTickerTest {
    private fun item(id: String, topic: String) = NewsItem(id, id, null, "Sport", topic, "Sport · 2 h ago", null, 0L)
    private fun lane(topic: String, vararg ids: String) = NewsLane(topic, NewsTopics.byId(topic)!!.label, "From Sport", ids.map { item(it, topic) })

    private val match = NewsMatchday(
        "espn-1", "Barça v Real Madrid", "Barça v Real Madrid · 21:00 · in 3 h", false,
        CalendarEvent("espn-1", "Barça v Real Madrid", 0L, 7_200_000L, false, null, "fixtures", null, "Fixtures"),
    )

    private fun todayTicker(): NewsTicker {
        val b = (1..8).map { "b$it" }.toTypedArray()
        val a = (1..8).map { "a$it" }.toTypedArray()
        return TickerRules.ticker(NewsPlace(listOf(lane("barca", *b), lane("ai", *a)), emptyList(), null, match))
    }

    @Test
    fun theBedsideStripIsTodaysTickerCutShortMatchFirst() {
        val t = BedsideTickerRules.ticker(todayTicker())
        assertEquals(BedsideTickerRules.MAX_ITEMS, t.items.size)
        assertEquals(listOf("b1", "a1", "b2", "a2", "b3", "a3"), t.items.map { it.id })
        assertEquals(match, t.matchday)
        // Fewer stories than the cap: all of them.
        val few = TickerRules.ticker(NewsPlace(listOf(lane("ai", "a1", "a2")), emptyList(), null))
        assertEquals(listOf("a1", "a2"), BedsideTickerRules.ticker(few).items.map { it.id })
    }

    @Test
    fun goneInQuietHoursWhenTodaysTickerIsOffOrThereIsNothing() {
        val t = BedsideTickerRules.ticker(todayTicker())
        assertTrue(BedsideTickerRules.shown(TickerMode.CALM, quiet = false, ticker = t))
        assertTrue(BedsideTickerRules.shown(TickerMode.MOVING, quiet = false, ticker = t))
        assertFalse(BedsideTickerRules.shown(TickerMode.CALM, quiet = true, ticker = t))
        assertFalse(BedsideTickerRules.shown(TickerMode.OFF, quiet = false, ticker = t))
        assertFalse(BedsideTickerRules.shown(TickerMode.MOVING, quiet = false, ticker = NewsTicker.EMPTY))
        // The match alone is something to show.
        assertTrue(BedsideTickerRules.shown(TickerMode.CALM, quiet = false, ticker = NewsTicker(match, emptyList())))
    }

    @Test
    fun keepsDriftingOnTheChargerAndIsCalmOnBattery() {
        assertEquals(TickerMode.MOVING, BedsideTickerRules.mode(TickerMode.CALM, charging = true))
        assertEquals(TickerMode.MOVING, BedsideTickerRules.mode(TickerMode.MOVING, charging = true))
        assertEquals(TickerMode.CALM, BedsideTickerRules.mode(TickerMode.MOVING, charging = false))
        assertEquals(TickerMode.CALM, BedsideTickerRules.mode(TickerMode.CALM, charging = false))
        assertEquals(TickerMode.OFF, BedsideTickerRules.mode(TickerMode.OFF, charging = true))
        // On battery it rests after the calm loops, like Today.
        val m = BedsideTickerRules.mode(TickerMode.MOVING, charging = false)
        assertTrue(TickerRules.moving(m, reducedMotion = false, onScreen = true, held = false, loopsDone = 1, hasItems = true))
        assertFalse(TickerRules.moving(m, reducedMotion = false, onScreen = true, held = false, loopsDone = TickerRules.CALM_LOOPS, hasItems = true))
        // Reduced motion never drifts, even on the charger.
        assertFalse(TickerRules.moving(BedsideTickerRules.mode(TickerMode.CALM, true), reducedMotion = true, onScreen = true, held = false, loopsDone = 0, hasItems = true))
    }

    @Test
    fun itDriftsAtHalfTodaysSpeedAndIsDimmer() {
        assertEquals(TickerRules.SPEED_DP_PER_S / 2, BedsideTickerRules.SPEED_DP_PER_S)
        assertTrue(BedsideTickerRules.ALPHA < 1f && BedsideTickerRules.ALPHA > 0.3f)
        // One second at the bedside goes half as far as on Today.
        val today = TickerRules.step(TickerDrift.START, 100, 1000f)
        val bedside = TickerRules.stepAt(TickerDrift.START, 100, 1000f, BedsideTickerRules.SPEED_DP_PER_S)
        assertEquals(today.offsetDp / 2, bedside.offsetDp)
        // It wraps and counts loops the same way.
        val wrapped = TickerRules.stepAt(TickerDrift(999f, 1), 100, 1000f, BedsideTickerRules.SPEED_DP_PER_S)
        assertEquals(1f, wrapped.offsetDp)
        assertEquals(2, wrapped.loops)
        // No speed, no movement.
        assertEquals(TickerDrift.START, TickerRules.stepAt(TickerDrift.START, 100, 1000f, 0f))
    }
}
