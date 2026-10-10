package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTileTest {
    private val cal = LocalCalendar.UTC
    private val hour = 3_600_000L
    private val min = 60_000L

    /** Saturday 10 October 2026, UTC. */
    private val sat = CivilDate.toEpochDay(2026, 10, 10) * CivilDate.DAY_MS
    private val day = DayWindow(sat, sat + 24 * hour, 0)
    private fun at(h: Int, m: Int = 0) = sat + h * hour + m * min

    private fun ev(id: String, title: String, from: Long, to: Long) =
        CalendarEvent(id, title, from, to, false, null, "google", null, null)

    private fun task(id: String, title: String, scheduled: Long? = null, estimate: Int? = null) =
        Task(id, title, null, Lifecycle.ACTIVE, null, scheduled, estimate, 0, null, null, 0, null, false)

    private fun home(tasks: List<Task>, events: List<CalendarEvent>, at: Long, fasting: FastingView = FastingView.EMPTY) =
        WatchHomeRules.view(CoverNowRules.now(TodayProjection.project(tasks, at, day, events, cal), at, cal), fasting, at)

    private fun fastAt(now: Long, hours: Int, goal: Int = 16): FastingView {
        val started = now - hours * hour - 5 * min
        return FastingView.EMPTY.copy(current = FastNow("f", started, goal, started + goal * hour, false, "Started", "Goal $goal h"))
    }

    @Test
    fun theTileShowsUpNextWithOnlyThePrimaryButtonAndItsPressComesBack() {
        val h = home(listOf(task("inv", "Send the invoice", scheduled = at(14), estimate = 20)), emptyList(), at(13, 50))
        val t = WatchTileRules.tile(h)
        assertEquals("UP NEXT", t.label)
        assertEquals("Send the invoice", t.title)
        assertEquals("At 14:00 · 20 min", t.line)
        assertEquals(NowAction.DONE, t.button?.action)
        assertEquals("press:DONE:inv", t.buttonId)
        assertNull(t.fastLine)
        assertEquals(t.button, WatchTileRules.pressed(t.buttonId, h))
        assertTrue(t.spoken.startsWith("Up next. Send the invoice."))
        assertTrue(t.spoken.endsWith("Double tap Done to done."))
    }

    @Test
    fun aStaleTilePressDoesNothingOnceTheTaskHasMovedOn() {
        val before = home(listOf(task("inv", "Send the invoice")), emptyList(), at(10))
        val id = WatchTileRules.tile(before).buttonId
        val after = home(listOf(task("bins", "Put the bins out")), emptyList(), at(10))
        assertNull(WatchTileRules.pressed(id, after))
        assertNull(WatchTileRules.pressed(WatchTileRules.OPEN_ID, after))
        assertNull(WatchTileRules.pressed(null, after))
        assertNull(WatchTileRules.pressed("press:DONE:inv", null))
        assertNull(WatchTileRules.pressed("press:OPEN:inv", before)) // only a button the watch offers
    }

    @Test
    fun anEventStartingSoonHasNoButtonAndCountsDownEachMinute() {
        val h = home(emptyList(), listOf(ev("c", "Call with Tunde", at(14), at(15))), at(13, 48) + 20_000)
        val t = WatchTileRules.tile(h)
        assertEquals("IN 12 MIN", t.label)
        assertTrue(t.lit)
        assertNull(t.button)
        assertNull(t.buttonId)
        assertEquals(40_000L, WatchTileRules.refreshAfterMs(h, at(13, 48) + 20_000))
    }

    @Test
    fun aCalmDayLooksAgainInAQuarterHourAndAFastEveryMinute() {
        val calm = home(emptyList(), emptyList(), at(16))
        assertEquals(WatchTileRules.CALM_REFRESH_MS, WatchTileRules.refreshAfterMs(calm, at(16)))
        assertEquals(WatchTileRules.CALM_REFRESH_MS, WatchTileRules.refreshAfterMs(null, at(16)))
        val fasting = home(emptyList(), emptyList(), at(16), fastAt(at(16), 14))
        assertEquals(min, WatchTileRules.refreshAfterMs(fasting, at(16)))
        val t = WatchTileRules.tile(fasting)
        assertEquals("Fasting · goal 16 h · 14:05", t.fastLine)
        assertFalse(t.fastReached)
        assertTrue(t.spoken.contains("Fasting · goal 16 h, 14 hours 5 minutes"))
    }

    @Test
    fun anUnlinkedWatchsTileSaysToOpenMeka() {
        val t = WatchTileRules.tile(null)
        assertEquals(WatchTileRules.UNLINKED_LABEL, t.label)
        assertEquals(WatchTileRules.UNLINKED_TITLE, t.title)
        assertNull(t.button)
        val c = WatchTileRules.complication(null, 3, 2)
        assertEquals("Link", c.text)
        assertEquals(0f, c.value)
    }

    @Test
    fun longTitlesAreCutAtAWord() {
        assertEquals("Short", WatchTileRules.shorten("  Short "))
        val long = "Ring the school about the trip form and the payment for Friday"
        val cut = WatchTileRules.shorten(long)
        assertTrue(cut.length <= WatchTileRules.TITLE_MAX)
        assertEquals("Ring the school about the trip form and…", cut)
        assertEquals("Aaaaaaaaa…", WatchTileRules.shorten("Aaaaaaaaaaaaaaaaaaaa", 10))
    }

    @Test
    fun theComplicationIsTheFastsRingElseTodaysListDone() {
        val f = home(emptyList(), emptyList(), at(16), fastAt(at(16), 14))
        val c = WatchTileRules.complication(f, 1, 4)
        assertTrue(c.fasting)
        assertEquals("14:05", c.text)
        assertEquals("Fast", c.title)
        assertTrue(c.value > 0.87f && c.value < 0.89f)
        assertTrue(c.spoken.endsWith("88 % of the goal"))

        val reached = WatchTileRules.complication(home(emptyList(), emptyList(), at(16), fastAt(at(16), 17)), 0, 0)
        assertEquals("Goal", reached.title)
        assertEquals(1f, reached.value)

        val calm = home(emptyList(), emptyList(), at(16))
        val d = WatchTileRules.complication(calm, 2, 3)
        assertFalse(d.fasting)
        assertEquals("2/5", d.text)
        assertEquals("Done", d.title)
        assertEquals(0.4f, d.value)
        assertEquals("2 of 5 done today", d.spoken)
        assertEquals("All 3 done today", WatchTileRules.complication(calm, 3, 0).spoken)
        assertEquals(1f, WatchTileRules.complication(calm, 3, 0).value)
        val clear = WatchTileRules.complication(calm, 0, 0)
        assertEquals("Clear", clear.text)
        assertEquals(0f, clear.value)
        assertTrue(WatchTileRules.complication(calm, 1234, 5678).text.length <= 7)
    }

    @Test
    fun clocksAreSpokenInWords() {
        assertEquals("14 hours 5 minutes", WatchTileRules.spokenClock("14:05"))
        assertEquals("1 hour", WatchTileRules.spokenClock("1:00"))
        assertEquals("1 minute", WatchTileRules.spokenClock("0:01"))
        assertEquals("abc", WatchTileRules.spokenClock("abc"))
    }
}
