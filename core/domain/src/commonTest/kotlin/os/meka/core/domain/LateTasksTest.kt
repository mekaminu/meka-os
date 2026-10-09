package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fold review 2026-10-09 13:45, item 3: a planned task whose time has gone by. */
class LateTasksTest {
    private val cal = LocalCalendar.UTC
    private val hour = 3_600_000L
    private val min = 60_000L
    private val fri = CivilDate.toEpochDay(2026, 10, 9) * CivilDate.DAY_MS
    private val day = DayWindow(fri, fri + 24 * hour, 0)
    private fun at(h: Int, m: Int = 0) = fri + h * hour + m * min

    private fun task(id: String, title: String, scheduled: Long? = null, estimate: Int? = null, createdAt: Long = 0) =
        Task(id, title, null, Lifecycle.ACTIVE, null, scheduled, estimate, 0, null, null, createdAt, null, false)

    private fun ev(id: String, title: String, from: Long, to: Long) =
        CalendarEvent(id, title, from, to, false, null, "google", null, null)

    private fun today(tasks: List<Task>, nowMs: Long, events: List<CalendarEvent> = emptyList()) =
        TodayProjection.project(tasks, nowMs, day, events, cal)

    private val mutation = task("m", "add mutation to PM Autopilot", scheduled = at(9, 15))
    private val anytime = task("a", "Read the RFC", createdAt = 1)

    @Test
    fun aPlannedTaskIsLateOnlyOnceItsStretchIsOver() {
        assertFalse(LateTaskRules.isLate(mutation, at(9, 30)))
        assertTrue(LateTaskRules.isLate(mutation, at(9, 45)))
        assertFalse(LateTaskRules.isLate(mutation.copy(lifecycle = Lifecycle.DONE), at(13, 44)))
        assertFalse(LateTaskRules.isLate(anytime, at(13, 44)))
        assertFalse(LateTaskRules.isLate(task("x", "Long one", scheduled = at(9), estimate = 120), at(10, 30)))
    }

    @Test
    fun theTimelineRowSaysSinceWhenAndIsMarkedLate() {
        val t = today(listOf(mutation, anytime), at(13, 44))
        val row = t.timeline.rows.first { it.kind == TimelineKind.TASK }
        assertTrue(row.late)
        assertEquals("09:15", row.time)
        assertTrue(row.detail!!.startsWith("Since 09:15"), row.detail)
        val onTime = today(listOf(mutation), at(9, 20)).timeline.rows.first { it.kind == TimelineKind.TASK }
        assertFalse(onTime.late)
        assertFalse(onTime.detail.orEmpty().contains("Since"))
    }

    @Test
    fun aLatePlannedTaskLeadsUpNextAheadOfAnAnytimeTask() {
        val t = today(listOf(anytime, mutation), at(13, 44))
        assertEquals("m", t.upNext?.id)
        assertTrue(t.upNextLate(at(13, 44)))
        // Still on the timeline (so the free time around it is right), and the anytime task waits below.
        assertTrue(t.timeline.rows.any { it.task?.id == "m" })
        assertEquals(listOf("a"), t.timeline.anytime.map { it.id })
    }

    @Test
    fun theEarliestPlannedTaskWhoseTimeHasComeLeadsBeforeALaterOne() {
        val later = task("l", "Call the bank", scheduled = at(15))
        val t = today(listOf(later, mutation), at(13, 44))
        assertEquals("m", t.upNext?.id)
        // Nothing late: the next planned task leads as before.
        assertEquals("l", today(listOf(later, anytime), at(13, 44)).upNext?.id)
    }

    @Test
    fun upNextIsLitSaysSinceAndOffersMoveToLater() {
        val t = today(listOf(mutation, anytime), at(13, 44))
        val v = CoverNowRules.upNext(t, at(13, 44), cal)!!
        assertTrue(v.lit)
        assertTrue(v.late)
        assertEquals("Up next", v.label)
        assertTrue(v.line!!.startsWith("Since 09:15"), v.line)
        assertEquals(listOf(NowAction.DONE, NowAction.LATER, NowAction.TOMORROW), v.actions)
        assertEquals(at(13, 45), v.laterAtMs)
        // The cover screen's card says the same.
        val cover = CoverNowRules.now(t, at(13, 44), cal)
        assertEquals(NowKind.TASK, cover.kind)
        assertEquals(v.actions, cover.actions)
        assertTrue(cover.lit)
    }

    @Test
    fun anOnTimeTaskKeepsItsUsualCard() {
        val v = CoverNowRules.upNext(today(listOf(mutation), at(9, 20)), at(9, 20), cal)!!
        assertFalse(v.lit)
        assertFalse(v.late)
        assertNull(v.laterAtMs)
        assertEquals("At 09:15", v.line)
        assertEquals(listOf(NowAction.DONE, NowAction.TOMORROW, NowAction.OPEN_TASK), v.actions)
    }

    @Test
    fun laterFindsTheFirstFreeQuarterHourAroundEventsAndOtherPlans() {
        val events = listOf(ev("s", "Standup", at(14), at(14, 30)))
        val other = task("o", "Review PR", scheduled = at(14, 45), estimate = 30)
        // 13:44 → 14:00 would run into Standup's buffer (13:50); after it (14:40) the next quarter is 14:45, taken
        // by the PR review until 15:15 → 15:15.
        assertEquals(at(15, 15), LateTaskRules.laterSlot(mutation, listOf(mutation, other), events, emptyList(), at(13, 44), day))
        // A booked session is in the way too.
        val gym = BookedSession("g", "Gym", null, CivilDate.toEpochDay(2026, 10, 9), at(14), at(15), "Today 14:00")
        assertEquals(at(15), LateTaskRules.laterSlot(mutation, listOf(mutation), emptyList(), listOf(gym), at(13, 44), day))
    }

    @Test
    fun noRoomLeftTodayMeansNoMoveToLater() {
        assertNull(LateTaskRules.laterSlot(mutation, listOf(mutation), emptyList(), emptyList(), at(20, 50), day))
        val t = today(listOf(mutation), at(20, 50))
        val v = CoverNowRules.upNext(t, at(20, 50), cal)!!
        assertTrue(v.late)
        assertNull(v.laterAtMs)
        assertEquals(listOf(NowAction.DONE, NowAction.TOMORROW, NowAction.OPEN_TASK), v.actions)
    }

    @Test
    fun theUndoBarSaysWhereItWent() {
        assertEquals("Moved “Send the invoice” to 15:30", LateTaskRules.movedLine("Send the invoice", "15:30"))
    }
}
