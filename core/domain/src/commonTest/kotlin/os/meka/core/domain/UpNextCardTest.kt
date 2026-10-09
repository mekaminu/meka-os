package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Fold review 2026-10-09, item 3: Up next is the same card on every screen. */
class UpNextCardTest {
    private val cal = LocalCalendar.UTC
    private val hour = 3_600_000L
    private val min = 60_000L
    private val wed = CivilDate.toEpochDay(2026, 10, 7) * CivilDate.DAY_MS
    private val day = DayWindow(wed, wed + 24 * hour, 0)
    private fun at(h: Int, m: Int = 0) = wed + h * hour + m * min

    private fun task(id: String, title: String, scheduled: Long? = null, estimate: Int? = null) =
        Task(id, title, null, Lifecycle.ACTIVE, null, scheduled, estimate, 0, null, null, 0, null, false)

    private fun ev(id: String, title: String, from: Long, to: Long) =
        CalendarEvent(id, title, from, to, false, null, "google", null, null)

    private fun today(tasks: List<Task>, events: List<CalendarEvent>, nowMs: Long) =
        TodayProjection.project(tasks, nowMs, day, events, cal)

    @Test
    fun anAnytimeTaskIsTheClosedFoldsCardWithItsThreeActions() {
        val v = CoverNowRules.upNext(today(listOf(task("inv", "Send the invoice")), emptyList(), at(10)), at(10), cal)!!
        assertEquals(NowKind.TASK, v.kind)
        assertEquals("Up next", v.label)
        assertFalse(v.lit)
        assertEquals("Send the invoice", v.title)
        assertEquals("Anytime today", v.line)
        assertEquals(listOf(NowAction.DONE, NowAction.TOMORROW, NowAction.OPEN_TASK), v.actions)
        assertEquals("inv", v.task?.id)
    }

    @Test
    fun aTimedTaskReadsItsTimeAndNeverCarriesThenOrNeedsYou() {
        val t = today(
            listOf(task("inv", "Send the invoice", scheduled = at(14), estimate = 20)),
            listOf(ev("d", "Dentist", at(14, 30), at(15))), at(13, 50),
        )
        val v = CoverNowRules.upNext(t, at(13, 50), cal)!!
        assertEquals("At 14:00 · 20 min", v.line)
        // The open Fold and the Mac list the next event and Needs you beside the card.
        assertNull(v.thenLine)
        assertNull(v.thenEvent)
        assertNull(v.needsYouLine)
    }

    @Test
    fun itMatchesTheCoverScreensCardWhenTheTaskLeadsThere() {
        val t = today(listOf(task("inv", "Send the invoice", scheduled = at(14), estimate = 20)), emptyList(), at(13, 50))
        val card = CoverNowRules.upNext(t, at(13, 50), cal)!!
        val cover = CoverNowRules.now(t, at(13, 50), cal)
        assertEquals(cover.copy(thenLine = null, thenEvent = null, needsYouLine = null), card)
    }

    @Test
    fun nothingUpNextIsNoCard() {
        assertNull(CoverNowRules.upNext(today(emptyList(), listOf(ev("d", "Dentist", at(14), at(15))), at(10)), at(10), cal))
    }
}
