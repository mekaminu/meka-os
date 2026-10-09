package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Requests from people Meka watches, slice 4: what Add and Change on a card do, and the work-from-home day. */
class RequestAcceptTest {
    private val cal = LocalCalendar.UTC
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private val thu15 = CivilDate.toEpochDay(2026, 10, 15)
    private val tue20 = CivilDate.toEpochDay(2026, 10, 20)

    private val pickUp = RequestProposal(RequestKind.TASK, "Pick up dry cleaning", fri + 1, null)
    private val callMum = RequestProposal(RequestKind.REMINDER, "Call your mum", null, 19 * 60)
    private val evening = RequestProposal(RequestKind.EVENT, "Parents' evening", tue20, 18 * 60)
    private val wfh = RequestProposal(RequestKind.WORK_FROM_HOME, "Work from home", thu15, null)

    @Test
    fun eachKindHasItsPlan() {
        assertEquals(RequestPlan.AddTask("Pick up dry cleaning", fri + 1, null, null), RequestAcceptRules.plan(pickUp, canAddEvent = true))
        assertEquals(RequestPlan.AddTask("Call your mum", null, 19 * 60, 19 * 60), RequestAcceptRules.plan(callMum, canAddEvent = true))
        assertEquals(RequestPlan.AddEvent("Parents' evening", tue20, 18 * 60, 60), RequestAcceptRules.plan(evening, canAddEvent = true))
        assertEquals(RequestPlan.HomeDay(thu15), RequestAcceptRules.plan(wfh, canAddEvent = false))
    }

    @Test
    fun anEventNoCalendarCanTakeOrOneBeingChangedBecomesAPlannedTask() {
        val asTask = RequestPlan.AddTask("Parents' evening", tue20, 18 * 60, null)
        assertEquals(asTask, RequestAcceptRules.plan(evening, canAddEvent = false))
        assertEquals(asTask, RequestAcceptRules.plan(evening, canAddEvent = true, change = true))
        assertEquals(
            "Added “Parents' evening” as a task · Tue 20 Oct · 18:00",
            RequestAcceptRules.doneLine(asTask, RequestKind.EVENT, fri),
        )
    }

    @Test
    fun workFromHomeHasNothingToChange() {
        assertNull(RequestAcceptRules.plan(wfh, canAddEvent = true, change = true))
        val card = MessageRequestRules.card(RequestMessage("wa-1", "Wife", "can you work from home on the 15th?", 0L), 0, wfh, fri, cal)
        assertNull(card.changeLabel)
        assertEquals("Change", MessageRequestRules.card(RequestMessage("wa-2", "Wife", "dry cleaning tomorrow?", 0L), 0, pickUp, fri, cal).changeLabel)
    }

    @Test
    fun theUndoBarSaysWhatWasDone() {
        assertEquals("Added “Pick up dry cleaning” · Tomorrow", RequestAcceptRules.doneLine(RequestAcceptRules.plan(pickUp, true)!!, RequestKind.TASK, fri))
        assertEquals("Added “Call your mum” · reminding Today · 19:00", RequestAcceptRules.doneLine(RequestAcceptRules.plan(callMum, true)!!, RequestKind.REMINDER, fri))
        assertEquals(
            "Adding “Parents' evening” to Google · Tue 20 Oct · 18:00",
            RequestAcceptRules.doneLine(RequestAcceptRules.plan(evening, true)!!, RequestKind.EVENT, fri, provider = "google"),
        )
        assertEquals("Thu 15 Oct: work from home", RequestAcceptRules.doneLine(RequestPlan.HomeDay(thu15), RequestKind.WORK_FROM_HOME, fri))
        assertEquals(
            "Tomorrow: work from home (not a work day)",
            RequestAcceptRules.doneLine(RequestPlan.HomeDay(fri + 1), RequestKind.WORK_FROM_HOME, fri, isWorkDay = false),
        )
    }

    @Test
    fun aReminderOnlyRingsWhileItsTimeIsAhead() {
        val plan = RequestAcceptRules.plan(callMum, true) as RequestPlan.AddTask
        assertEquals(fri, RequestAcceptRules.taskDay(plan, fri))
        assertEquals(cal.toEpochMs(fri, 19 * 60), RequestAcceptRules.remindAtMs(plan, fri, cal.toEpochMs(fri, 14 * 60), cal))
        assertNull(RequestAcceptRules.remindAtMs(plan, fri, cal.toEpochMs(fri, 19 * 60 + 5), cal))
        val anytime = RequestAcceptRules.plan(pickUp.copy(day = null), true) as RequestPlan.AddTask
        assertNull(RequestAcceptRules.taskDay(anytime, fri))
        assertNull(RequestAcceptRules.remindAtMs(anytime, fri, 0L, cal))
    }

    @Test
    fun aHomeDaySyncsAndTheWorkBandSaysSo() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        world.clock.nowMs = cal.toEpochMs(fri, 14 * 60)
        val foldWork = WorkMode(fold.replica) { world.clock.nowMs }
        val macWork = WorkMode(mac.replica) { world.clock.nowMs }
        assertTrue(foldWork.setHomeDay(thu15, true, today = fri))
        assertFalse(foldWork.setHomeDay(thu15, true, today = fri))
        fold.sync(); mac.sync()
        assertEquals(setOf(thu15), macWork.homeDays(fri))

        val hours = WorkHours(WorkSchedule.WEEKDAYS, homeDays = macWork.homeDays(fri))
        assertEquals("Work from home 09:00–17:30", hours.line(thu15))
        assertEquals("Work 09:00–17:30", hours.line(thu15 + 1))
        assertEquals(listOf("Work from home"), hours.blocks(thu15, cal).map { it.title })

        // Undo on the Mac takes it back; a day gone by is dropped on the next write.
        assertTrue(macWork.setHomeDay(thu15, false, today = fri))
        assertTrue(macWork.homeDays(fri).isEmpty())
        macWork.setHomeDay(fri, true, today = fri)
        macWork.setHomeDay(tue20, true, today = fri + 1)
        assertEquals(setOf(tue20), macWork.homeDays())
    }

    @Test
    fun todaysSummarySaysWorkingFromHome() {
        val now = cal.toEpochMs(thu15, 10 * 60)
        val hours = WorkHours(WorkSchedule.WEEKDAYS, homeDays = setOf(thu15))
        val t = TodayProjection.project(emptyList(), now, CalendarAgenda.window(thu15, cal), emptyList(), cal, work = hours)
        assertEquals("Work from home", t.timeline.rows.first { it.kind == TimelineKind.WORK }.title)
        assertEquals("Working from home until 17:30", TimelineRules.summary(t.timeline))
    }
}
