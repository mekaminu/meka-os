package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Handle her requests for me" (Take first, 2026-11-11), slice 1: who, what's certain, the steps and their dates. */
class HandleForMeTest {
    private val wed = CivilDate.toEpochDay(2026, 11, 11)
    private val thu = wed + 1
    private val mon = CivilDate.toEpochDay(2026, 11, 16)
    private val weekdays: (Long) -> Boolean = { CivilDate.isoDayOfWeek(it) in 1..5 }
    private val about = AboutYou(partnerName = "Alex Smith")

    private val wfhThu = RequestProposal(RequestKind.WORK_FROM_HOME, "Work from home", thu, null)

    @Test
    fun onlyHisPartnerWhileWatchedAndOn() {
        assertTrue(HandleForMeRules.handles("Alex Smith", about))
        assertTrue(HandleForMeRules.handles("alex", about))
        assertTrue(HandleForMeRules.handles("Alex 💛", about))
        assertFalse(HandleForMeRules.handles("Alexandra", about))
        assertFalse(HandleForMeRules.handles("Sam", about))
        assertFalse(HandleForMeRules.handles("07700 900123", about))
        assertFalse(HandleForMeRules.handles("Alex", about.copy(partnerHandle = false)), "switch off = today's cards")
        assertFalse(HandleForMeRules.handles("Alex", about.copy(partnerWatch = false)))
        assertFalse(HandleForMeRules.handles("Alex", AboutYou()), "no partner named")
    }

    @Test
    fun uncertainRequestsStayCards() {
        assertTrue(HandleForMeRules.certain(wfhThu, wed))
        assertFalse(HandleForMeRules.certain(RequestProposal(RequestKind.TASK, "Sort the garden", null, null), wed), "no clear day")
        assertFalse(HandleForMeRules.certain(wfhThu, thu + 1), "the day has gone")
        assertFalse(HandleForMeRules.certain(RequestProposal(RequestKind.REPLY, "Reply", wed, null, reply = "Yes"), wed))
        assertFalse(HandleForMeRules.certain(RequestProposal(RequestKind.RENEWAL, "Insurance", thu, null), wed))
    }

    @Test
    fun workFromHomeTomorrowGetsThreeStepsForTodayRemindingAt16() {
        val plan = HandleForMeRules.plan(wfhThu, wed, 10 * 60, weekdays)
        assertNull(plan.task)
        assertEquals(thu, plan.homeDay)
        assertEquals(listOf("Tell your manager", "Get approval", "Put the request in at work"), plan.steps.map { it.title })
        assertTrue(plan.steps.all { it.day == wed && it.remindDay == wed && it.remindMinute == 16 * 60 })
        assertEquals(
            "Work from home Thu 12 Nov · added to Tomorrow, 3 steps for today, reminder 16:00",
            HandleForMeRules.line(wfhThu, plan, wed),
        )
    }

    @Test
    fun aLateMessageRemindsAtTheNextHalfHourOrTheNextMorning() {
        // 17:10: past 16:00, so the next half hour at least 30 minutes ahead.
        assertEquals(wed to 18 * 60, HandleForMeRules.plan(wfhThu, wed, 17 * 60 + 10, weekdays).steps.first().let { it.remindDay!! to it.remindMinute!! })
        // 22:15 (the message that started this): too late tonight, so 08:00 on the day itself, the steps with it.
        val late = HandleForMeRules.plan(wfhThu, wed, 22 * 60 + 15, weekdays)
        assertTrue(late.steps.all { it.day == thu && it.remindDay == thu && it.remindMinute == 8 * 60 })
        assertEquals(
            "Work from home Thu 12 Nov · added to Tomorrow, 3 steps for tomorrow, reminder Tomorrow 08:00",
            HandleForMeRules.line(wfhThu, late, wed),
        )
    }

    @Test
    fun stepsForALaterDayLandOnTheWorkingDayBefore() {
        // Monday's home day: the steps on Friday, not over the weekend.
        val fri = CivilDate.toEpochDay(2026, 11, 13)
        assertEquals(fri, HandleForMeRules.stepDay(mon, wed, weekdays))
        val plan = HandleForMeRules.plan(wfhThu.copy(day = mon), wed, 9 * 60, weekdays)
        assertTrue(plan.steps.all { it.day == fri && it.remindDay == fri && it.remindMinute == 16 * 60 })
        assertEquals("Work from home Mon 16 Nov · added to Monday, 3 steps for Friday, reminder Friday 16:00", HandleForMeRules.line(wfhThu.copy(day = mon), plan, wed))
        // No working day in between: today.
        assertEquals(wed, HandleForMeRules.stepDay(thu + 2, wed) { false })
    }

    @Test
    fun aTaskOrEventIsATaskWithAReminder() {
        val pick = RequestProposal(RequestKind.TASK, "Pick up the dry cleaning", thu, null)
        val p = HandleForMeRules.plan(pick, wed, 20 * 60, weekdays)
        assertEquals(HandleTask("Pick up the dry cleaning", thu, null, thu, 9 * 60), p.task)
        assertTrue(p.steps.isEmpty())
        assertEquals("“Pick up the dry cleaning” · Tomorrow · added to Tomorrow, reminder Tomorrow 09:00", HandleForMeRules.line(pick, p, wed))
        val evening = RequestProposal(RequestKind.EVENT, "Parents' evening", wed, 18 * 60)
        assertEquals(HandleTask("Parents' evening", wed, 18 * 60, wed, 17 * 60), HandleForMeRules.plan(evening, wed, 12 * 60, weekdays).task)
        // 17:50: an hour before has gone and the next half hour is after it starts, so no reminder.
        assertEquals(HandleTask("Parents' evening", wed, 18 * 60, null, null), HandleForMeRules.plan(evening, wed, 17 * 60 + 50, weekdays).task)
        val call = RequestProposal(RequestKind.REMINDER, "Call your mum", wed, 19 * 60)
        assertEquals(wed to 19 * 60, HandleForMeRules.plan(call, wed, 9 * 60, weekdays).task!!.let { it.remindDay!! to it.remindMinute!! })
    }

    @Test
    fun theRowsSyncAndUndoOrClearTakesThemAway() {
        val world = SyncWorld()
        val fold = world.device("fold")
        val mac = world.device("mac")
        world.clock.nowMs = LocalCalendar.UTC.toEpochMs(wed, 22 * 60 + 15)
        val now = world.clock.nowMs
        val foldCards = RequestCards(fold.replica, { world.clock.nowMs }, LocalCalendar.UTC)
        val macCards = RequestCards(mac.replica, { world.clock.nowMs }, LocalCalendar.UTC)
        val made = foldCards.save(RequestMessage("m1", "Alex", "Can you work from home tomorrow?", now - 60_000), listOf(wfhThu))
        assertEquals(1, made.size)
        assertTrue(foldCards.handled(made[0].id, listOf("t1", "t2"), thu, "Work from home Thu 12 Nov · added"))
        assertFalse(foldCards.handled(made[0].id, emptyList(), null, "again"), "only once")
        assertTrue(foldCards.open().isEmpty(), "no card to tap")
        fold.sync(); mac.sync(); fold.sync()
        val row = macCards.doneForYou().single()
        assertEquals("From Alex · 22:14", row.from)
        assertEquals(listOf("t1", "t2"), row.taskIds)
        assertEquals(thu, row.homeDay)
        assertEquals("t1", row.changeTaskId)
        assertTrue(macCards.open().isEmpty())
        assertTrue(macCards.dropHandled(row.id, undone = true))
        fold.sync(); mac.sync(); fold.sync()
        assertTrue(foldCards.doneForYou().isEmpty())
        // The same request read again (a re-post) makes no new card.
        assertTrue(foldCards.save(RequestMessage("m1", "Alex", "Can you work from home tomorrow?", now - 60_000), listOf(wfhThu)).isEmpty())
        // A row stays a day and a half at most.
        val m2 = foldCards.save(RequestMessage("m2", "Alex", "Can you get milk on Friday?", now), listOf(RequestProposal(RequestKind.TASK, "Get milk", thu + 1, null)))
        foldCards.handled(m2[0].id, listOf("t3"), null, "“Get milk” · Fri 13 Nov · added")
        assertEquals(1, foldCards.doneForYou().size)
        world.clock.advance(HandleForMeRules.SHOWN_MS + 1)
        assertTrue(foldCards.doneForYou().isEmpty())
    }

    @Test
    fun aHeadsUpNeverCarriesTheMessage() {
        val row = DoneForYou("m1#0", "From Alex · 22:14", "Work from home Thu 12 Nov · added to Tomorrow", "x", 1000L, listOf("t1"), thu)
        val n = HandleForMeRules.notices(listOf(row)).single()
        assertEquals("Done for you · from Alex", n.title)
        assertEquals("Work from home Thu 12 Nov · added to Tomorrow", n.text)
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals(NoticeTarget.NEEDS_YOU, n.target)
    }

    @Test
    fun aboutYouKeepsTheSwitchOnByDefault() {
        val world = SyncWorld()
        val fold = world.device("fold")
        val store = AboutYouStore(fold.replica) { 0L }
        assertTrue(store.profile().partnerHandle)
        assertTrue(store.setPartnerHandle(false))
        assertFalse(store.profile().partnerHandle)
        val v = AboutYouRules.view(store.profile().copy(partnerName = "Alex"), LocalCalendar.UTC, 0L)
        assertEquals(HandleForMeRules.OFF, v.handleLine)
        assertNull(AboutYouRules.view(AboutYou(partnerName = "Alex", partnerWatch = false), LocalCalendar.UTC, 0L).handleLine)
        assertEquals(HandleForMeRules.ON, AboutYouRules.view(AboutYou(partnerName = "Alex"), LocalCalendar.UTC, 0L).handleLine)
    }

    @Test
    fun theWorkFromHomeMessageShapeReads() {
        // Item (5): "can you work from home tomorrow" as the model answers it reads as a dated work-from-home day,
        // so it is certain and handled (it had only waited as a card before).
        val raw = listOf(RawRequestProposal("work_from_home", "Work from home", words = "tomorrow"))
        val checked = MessageRequestRules.check(raw, wed, 21 * 60)
        assertEquals(listOf(wfhThu), checked)
        assertTrue(HandleForMeRules.certain(checked.single(), wed))
    }
}
