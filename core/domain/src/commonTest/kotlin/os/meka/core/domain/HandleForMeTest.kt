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
    fun theMorningBriefSaysWhatWasDoneAndTheStepsStillOpen() {
        val since = LocalCalendar.UTC.toEpochMs(wed - 1, 0)
        val last = LocalCalendar.UTC.toEpochMs(wed - 1, 22 * 60 + 15)
        val wfh = DoneForYouBrief("Alex", last, thu, null, null, HandleForMeRules.WORK_FROM_HOME_STEPS)
        assertEquals(
            listOf(
                "Done for you: Alex asked you to work from home tomorrow, and it's marked as a home day.",
                "Still to do today: Tell your manager, get approval and put the request in at work.",
            ),
            HandleForMeRules.briefSentences(listOf(wfh), wed, since),
        )
        // All the steps done: only what was done is said.
        assertEquals(
            listOf("Done for you: Alex asked you to work from home tomorrow, and it's marked as a home day."),
            HandleForMeRules.briefSentences(listOf(wfh.copy(openToday = emptyList())), wed, since),
        )
        val milk = DoneForYouBrief("Alex", last + 60_000, null, "Pick up the dry cleaning", mon, emptyList())
        val today = DoneForYouBrief("Alex", last + 120_000, null, "Get milk", wed, emptyList())
        val more = DoneForYouBrief("Alex", last + 180_000, null, "Call the vet", thu, emptyList())
        assertEquals(
            listOf(
                "Done for you: Alex asked you to work from home tomorrow, and it's marked as a home day.",
                "Alex asked for “Pick up the dry cleaning”, and it's on your list for Monday.",
                "And 2 more requests done for you, in Needs you.",
            ),
            HandleForMeRules.briefSentences(listOf(more, today, milk, wfh.copy(openToday = emptyList())), wed, since),
        )
        assertEquals(
            listOf("Done for you: Alex asked for “Get milk”, and it's on today's list."),
            HandleForMeRules.briefSentences(listOf(today), wed, since),
        )
        // Before yesterday, a day gone by, or nothing left of it: not said.
        assertTrue(HandleForMeRules.briefSentences(listOf(wfh.copy(atMs = since - 1)), wed, since).isEmpty())
        assertTrue(HandleForMeRules.briefSentences(listOf(wfh.copy(homeDay = wed - 1, openToday = emptyList())), wed, since).isEmpty())
        assertTrue(HandleForMeRules.briefSentences(listOf(milk.copy(title = null)), wed, since).isEmpty())
        assertTrue(HandleForMeRules.briefSentences(emptyList(), wed, since).isEmpty())
    }

    @Test
    fun theBriefSaysDoneForYouAfterTheRequestsWaiting() {
        val story = BriefStory(epochDay = wed, doneForYou = listOf("Done for you: Alex asked you to work from home tomorrow, and it's marked as a home day."))
        val script = BriefScriptRules.script(MorningBriefView.EMPTY.copy(story = story))
        assertTrue(script.contains("Done for you: Alex asked you to work from home tomorrow"), script)
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

    @Test
    fun theRepliesAreDraftedForHimToSendNeverSent() {
        // Slice 3: a reply to her and, for a work-from-home day, a note to his manager; dates as dates, never "tomorrow".
        val wfh = HandleForMeRules.drafts(RequestKind.WORK_FROM_HOME, "Work from home", thu, null, "Alex Smith")
        assertEquals(listOf("Copy reply to Alex", HandleForMeRules.MANAGER_LABEL), wfh.map { it.label })
        assertEquals("Okay, I'll ask work about working from home on Thu 12 Nov.", wfh[0].text)
        assertEquals("Hi, would it be okay if I work from home on Thu 12 Nov? I'll be online and reachable as usual.", wfh[1].text)
        assertTrue(wfh[1].spoken.endsWith(wfh[1].text))
        val task = HandleForMeRules.drafts(RequestKind.TASK, "  Pick up   the dry cleaning. ", thu, null, "Alex")
        assertEquals(listOf("Okay, I've put “Pick up the dry cleaning” on my list for Thu 12 Nov."), task.map { it.text })
        val event = HandleForMeRules.drafts(RequestKind.EVENT, "Dinner with Sam", thu, 18 * 60 + 30, "Alex")
        assertEquals("Okay, “Dinner with Sam” is in my diary for Thu 12 Nov at 18:30.", event.single().text)
        assertTrue(HandleForMeRules.drafts(RequestKind.TASK, "Sort the garden", null, null, "Alex").isEmpty(), "no day: no draft")
        assertTrue(HandleForMeRules.drafts(RequestKind.REPLY, "Reply", thu, null, "Alex").isEmpty())
        val long = HandleForMeRules.drafts(RequestKind.TASK, "x".repeat(300), thu, null, "Alex").single().text
        assertEquals(HandleForMeRules.DRAFT_TITLE_MAX, long.substringAfter("“").substringBefore("”").length)
    }

    @Test
    fun theRowCarriesItsDraftsOnEveryDevice() {
        val world = SyncWorld()
        val fold = world.device("fold")
        val mac = world.device("mac")
        world.clock.nowMs = LocalCalendar.UTC.toEpochMs(wed, 22 * 60 + 15)
        val foldCards = RequestCards(fold.replica, { world.clock.nowMs }, LocalCalendar.UTC)
        val macCards = RequestCards(mac.replica, { world.clock.nowMs }, LocalCalendar.UTC)
        val made = foldCards.save(RequestMessage("m1", "Alex", "Can you work from home tomorrow?", world.clock.nowMs), listOf(wfhThu))
        foldCards.handled(made[0].id, listOf("t1"), thu, "Work from home Thu 12 Nov · added")
        fold.sync(); mac.sync()
        val row = macCards.doneForYou().single()
        assertEquals(HandleForMeRules.drafts(RequestKind.WORK_FROM_HOME, "Work from home", thu, null, "Alex"), row.drafts)
        assertFalse(row.drafts.any { "Can you" in it.text }, "never the message's own words")
    }

    private val cal = LocalCalendar.UTC
    private val nine2five = WorkSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60)
    private fun step(id: String, title: String, remindAt: Long?, done: Boolean = false) = Task(
        id, title, null, if (done) Lifecycle.DONE else Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false,
        remindAtMs = remindAt,
    )
    private fun wfhRow(atMs: Long, home: Long = thu) = DoneForYou(
        "m1#0", "From Alex · 10:00", "Work from home Thu 12 Nov · added", "x", atMs, listOf("s1", "s2", "s3"), home, person = "Alex",
    )
    private val remind16 = cal.toEpochMs(wed, 16 * 60)
    private val steps = listOf(
        step("s1", "Tell your manager", remind16), step("s2", "Get approval", remind16),
        step("s3", "Put the request in at work", remind16),
    )

    @Test
    fun theStepsStillOpenRemindAgainAtTheEndOfWork() {
        val now = cal.toEpochMs(wed, 12 * 60)
        val n = HandleForMeRules.endOfWorkNotices(listOf(wfhRow(cal.toEpochMs(wed, 10 * 60))), steps, nine2five, HolidayCalendar.NONE, now, cal).single()
        assertEquals(cal.toEpochMs(wed, 17 * 60), n.atMs, "his hours end at 17:00")
        assertEquals(HandleForMeRules.END_OF_WORK_TITLE, n.title)
        assertEquals("Still open for working from home Thu 12 Nov: tell your manager, get approval and put the request in at work.", n.text)
        assertEquals(NoticeTarget.NEEDS_YOU, n.target)
        // Two done: only the one left is named.
        val one = steps.mapIndexed { i, t -> if (i < 2) t.copy(lifecycle = Lifecycle.DONE) else t }
        assertEquals(
            "Still open for working from home Thu 12 Nov: put the request in at work.",
            HandleForMeRules.endOfWorkNotices(listOf(wfhRow(cal.toEpochMs(wed, 10 * 60))), one, nine2five, HolidayCalendar.NONE, now, cal).single().text,
        )
        // No hours known: 17:30.
        assertEquals(cal.toEpochMs(wed, 17 * 60 + 30), HandleForMeRules.endOfWorkNotices(listOf(wfhRow(cal.toEpochMs(wed, 10 * 60))), steps, null, HolidayCalendar.NONE, now, cal).single().atMs)
    }

    @Test
    fun noEndOfWorkReminderWhenNothingIsLeftOrItWouldNagTwice() {
        val row = wfhRow(cal.toEpochMs(wed, 10 * 60))
        val now = cal.toEpochMs(wed, 12 * 60)
        fun none(rows: List<DoneForYou>, ts: List<Task>, at: Long = now, sched: WorkSchedule? = nine2five) =
            assertTrue(HandleForMeRules.endOfWorkNotices(rows, ts, sched, HolidayCalendar.NONE, at, cal).isEmpty())
        none(listOf(row), steps.map { it.copy(lifecycle = Lifecycle.DONE) })
        none(listOf(row), emptyList())
        // Handled at 17:10: its steps already remind at 18:00, so no second one this evening.
        none(listOf(wfhRow(cal.toEpochMs(wed, 17 * 60 + 10))), steps.map { it.copy(remindAtMs = cal.toEpochMs(wed, 18 * 60)) }, cal.toEpochMs(wed, 17 * 60 + 15))
        // The home day itself, or a day off from work.
        none(listOf(row), steps, cal.toEpochMs(thu, 12 * 60))
        none(listOf(wfhRow(cal.toEpochMs(mon - 2, 10 * 60), home = mon)), steps.map { it.copy(remindAtMs = null) }, cal.toEpochMs(mon - 2, 12 * 60))
        // A task or event request has no steps and no home day: nothing.
        none(listOf(row.copy(homeDay = null)), steps)
    }
}
