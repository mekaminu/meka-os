package os.meka.core.facade

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.meka.core.sync.SyncTransport
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncStatus
import os.meka.core.testing.FaultyTransport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MekaCoreTest {
    private val serverOps = InMemoryServerOpStore()
    private val service = SyncService(serverOps)
    private var now = 1_790_000_000_000L

    private fun core(name: String, transport: SyncTransport = FaultyTransport(service)) = MekaCore(
        householdId = "hh", deviceId = name, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(name.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun todayUpdatesAfterCommands() = runTest {
        val c = core("android")
        assertTrue(c.today.value.isClear)
        val id = c.addTask("Call James about football")
        assertEquals(id, c.today.value.upNext?.id)
        c.complete(id)
        assertTrue(c.today.value.isClear)
        assertEquals(listOf(id), c.today.value.doneToday.map { it.id })
    }

    @Test
    fun captureMakesOneTaskWithTitleAndNotesAndSyncsIt() = runTest {
        val a = core("android"); val m = core("mac")
        assertEquals(null, a.capture("  \n ", null))
        val id = a.capture("Buy boots\nsize 9", null)
        val task = a.today.value.upNext
        assertEquals(id, task?.id)
        assertEquals("Buy boots", task?.title)
        assertEquals("size 9", task?.notes)
        a.capture("https://example.com/a", "Read this article")
        a.syncNow(); m.syncNow()
        val titles = m.today.value.let { (listOfNotNull(it.upNext) + it.yourDay) }.map { it.title }.toSet()
        assertEquals(setOf("Buy boots", "Read this article"), titles)
    }

    @Test
    fun offlineSyncReportsPendingThenRecovers() = runTest {
        val t = FaultyTransport(service).apply { online = false }
        val c = core("android", t)
        c.addTask("Buy boots")
        assertEquals(false, c.syncNow())
        val s = assertIs<SyncStatus.Offline>(c.syncStatus.value)
        assertEquals(7, s.pending) // title, lifecycle, createdAt, priority, visibility, provenance source + trust

        t.online = true
        assertEquals(true, c.syncNow())
        assertIs<SyncStatus.Synced>(c.syncStatus.value)
    }

    @Test
    fun connectAttachesSyncAndPushesLocalWorkMadeBeforeEnrolment() = runTest {
        val c = MekaCore("hh", "android", InMemoryReplicaStore(), null, Random(1), { TimeZone.of("Europe/London") }, { now })
        c.addTask("Made before enrolment")
        assertEquals(false, c.isConnected)
        c.connect(FaultyTransport(service))
        assertEquals(true, c.syncNow())
        c.stopSync()
        val other = core("mac"); other.syncNow()
        assertEquals("Made before enrolment", other.today.value.upNext?.title)
    }

    @Test
    fun conflictsAreExposedAsChoicesAndResolve() = runTest {
        val a = core("android"); val m = core("mac")
        val id = a.addTask("Call school"); a.syncNow(); m.syncNow()
        a.rename(id, "Call school re trip"); m.rename(id, "Email school re trip")
        a.syncNow(); m.syncNow(); a.syncNow()
        val choice = a.conflicts.value.single()
        assertEquals(setOf("Call school re trip", "Email school re trip"), choice.options.toSet())
        a.resolve(choice, "Email school re trip")
        a.syncNow(); m.syncNow()
        assertTrue(m.conflicts.value.isEmpty())
        assertEquals("Email school re trip", m.today.value.upNext?.title)
    }

    /** One lock for the (non-thread-safe) in-memory server, since devices run on their own dispatchers here. */
    private val serverLock = Mutex()

    /** Server-side long-poll stand-in: answers once the household has ops after the cursor, else empty after 2 s. */
    private inner class LongPollTransport(private val inner: FaultyTransport = FaultyTransport(service)) : SyncTransport {
        override suspend fun push(request: PushRequest) = serverLock.withLock { inner.push(request) }
        override suspend fun pull(request: PullRequest) = serverLock.withLock { inner.pull(request) }
        override suspend fun awaitChanges(request: PullRequest): Boolean {
            repeat(200) {
                if (serverLock.withLock { service.pull(request).ops.isNotEmpty() }) return true
                delay(10)
            }
            return false
        }
    }

    @Test
    fun anOpenAppSeesTheOtherDevicesEditWithinASecondViaLongPoll() = runTest {
        val mac = core("mac", LongPollTransport())
        // Fallback period far beyond the test: only the long-poll can deliver the change.
        mac.startSync(periodMs = 10 * 60_000)
        val phone = core("android", LongPollTransport())
        phone.addTask("From phone")
        assertTrue(phone.syncNow())
        val seen = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000) { mac.today.first { it.upNext?.title == "From phone" } }
        }
        mac.stopSync()
        assertTrue(seen != null, "the Mac did not pick up the phone's edit via long-poll")
    }

    @Test
    fun workModeFollowsTheClockAndTheSwitchSyncs() = runTest {
        // 1_790_000_000_000 ms is Monday 21 Sept 2026, 15:13 in London (BST): inside default work hours.
        val fold = core("fold"); val mac = core("mac")
        assertTrue(fold.workMode.value.atWork)
        assertEquals("At work until 17:30", fold.workMode.value.line)

        now += 3 * 3_600_000L // 18:13
        fold.tick()
        assertEquals(false, fold.workMode.value.atWork)

        mac.setWorkSwitch(true) // evening work on the Mac
        mac.syncNow(); fold.syncNow()
        assertTrue(fold.currentWorkMode().atWork)
        assertTrue(fold.workMode.value.switchedManually)

        fold.workBackToSchedule()
        assertEquals(false, fold.workMode.value.atWork)

        fold.setWorkSchedule(listOf(1, 2, 3, 4, 5), 9 * 60, 20 * 60, true)
        assertTrue(fold.workMode.value.atWork)
        assertEquals("At work until 20:00", fold.workMode.value.line)
    }

    @Test
    fun repeatingTaskComesBackTomorrowAtTheSameLocalTimeAcrossTheClockChange() = runTest {
        // Saturday 24 Oct 2026, 10:00 BST; the clocks go back overnight (Sunday 25 Oct).
        now = 1_792_832_400_000L
        val c = core("android")
        val id = c.addTask("Morning run")
        val seven = 1_792_821_600_000L // Sat 07:00 BST = 06:00 UTC
        c.schedule(id, seven)
        val daily = c.repeatChoices(id).first { it.label == "Every day" }
        c.setRepeat(id, daily.rule)
        c.addStep(id, "Stretch")
        c.complete(id)
        assertTrue(c.today.value.yourDay.none { it.title == "Morning run" } && c.today.value.upNext?.title != "Morning run")

        now += 24 * 3_600_000L
        c.tick()
        val next = (listOfNotNull(c.today.value.upNext) + c.today.value.yourDay + c.today.value.needsYou.map { it.task })
            .single { it.title == "Morning run" }
        assertEquals(seven + 25 * 3_600_000L, next.scheduledAtMs) // 07:00 GMT: a 25-hour day
        assertEquals("Every day", next.repeatMeta(c.todayEpochDay()))
        assertEquals(listOf("Stretch" to false), next.checklist.map { it.text to it.checked })
    }

    @Test
    fun listsFlowShowsDueChasesAndReviewsAsTheClockMovesAndSyncs() = runTest {
        val a = core("android"); val m = core("mac")
        val w = a.addWaiting("Refund", "Sports Direct", 1)
        a.recordDecision("No new car this year", "Saving for the extension", 0)
        val idea = a.addSomeday("Lisbon long weekend", os.meka.core.domain.SomedayKind.TRIP)
        assertEquals("1 decision to review", a.listsView.value.dueLine)
        assertEquals(listOf("Trips"), a.listsView.value.someday.map { it.label })
        now += 86_400_000L
        a.tick()
        assertEquals("1 to chase · 1 decision to review", a.listsView.value.dueLine)
        a.chased(w, 7)
        assertEquals("1 decision to review", a.listsView.value.dueLine)
        a.promoteSomeday(idea)
        assertEquals(idea, a.today.value.upNext?.id)
        a.syncNow(); m.syncNow()
        assertEquals(listOf("Refund"), m.listsView.value.waiting.map { it.title })
        assertEquals(1, m.listsView.value.reviewsDue)
    }

    @Test
    fun renewalsComeDueInListsAndRollOnWhenRenewedOnEitherDevice() = runTest {
        val a = core("android"); val m = core("mac")
        val today = a.todayEpochDay()
        val id = a.addRenewal("Netflix", os.meka.core.domain.ObligationKind.SUBSCRIPTION, today + 4,
            os.meka.core.domain.RenewalRepeat.MONTHLY, "10.99", null)
        assertEquals(null, a.listsView.value.dueLine)
        assertEquals("£10.99 a month · £131.88 a year in repeating costs", a.listsView.value.renewals.costLine)
        now += 86_400_000L
        a.tick()
        assertEquals("1 renewal due", a.listsView.value.dueLine)
        assertEquals(1, a.listsView.value.dueCount)
        a.syncNow(); m.syncNow()
        assertEquals(listOf("Netflix"), m.listsView.value.renewals.attention.map { it.title })
        m.renewalDone(id)
        m.syncNow(); a.syncNow()
        assertEquals(null, a.listsView.value.dueLine)
        assertTrue(a.listsView.value.renewals.all.single().dueDay > today + 27)
    }

    @Test
    fun needsYouStackFollowsListsAndDecideUndoesOnBothDevices() = runTest {
        val a = core("android"); val m = core("mac")
        val today = a.todayEpochDay()
        assertTrue(a.needsYouStack.value.isEmpty)
        a.addRenewal("Netflix", os.meka.core.domain.ObligationKind.SUBSCRIPTION, today + 2,
            os.meka.core.domain.RenewalRepeat.MONTHLY, "10.99", null)
        val card = a.needsYouStack.value.cards.single()
        assertEquals(os.meka.core.domain.NeedsYouStackRules.LISTS_ID, card.id)
        assertEquals("1 renewal due", card.why)

        val id = a.addTask("Send invoice")
        val undo = a.decide(id, os.meka.core.domain.DecisionEffect.COMPLETE_TASK)!!
        a.syncNow(); m.syncNow()
        assertEquals(listOf(id), m.today.value.doneToday.map { it.id })
        assertTrue(a.undoDecision(undo))
        a.syncNow(); m.syncNow()
        assertEquals(id, m.today.value.upNext?.id)
        assertEquals(null, a.decide(id, os.meka.core.domain.DecisionEffect.OPEN_TASK))
    }

    @Test
    fun habitsTickSyncAndThePlanMakesRoomForOnesThatAreDue() = runTest {
        val a = core("android"); val m = core("mac")
        val goal = a.addGoal("Fitter by spring", null, os.meka.core.domain.GoalHorizon.MEDIUM)
        val h = a.addHabit("Stretch", 7, os.meka.core.domain.HabitTiming.ANYTIME, 15, goal)
        assertEquals(os.meka.core.domain.HabitPace.DUE, a.goalsView.value.habits.single().pace)
        val plan = a.planDay()
        // Room is made for the habit today unless the day is already over (the test clock's local time decides).
        assertEquals(plan.habits.map { it.habitId } + plan.habitsUnplaced.map { it.id }, listOf(h))
        a.setHabitDone(h, true)
        assertTrue(a.goalsView.value.habits.single().doneToday)
        assertTrue(a.planDay().habits.isEmpty())
        a.syncNow(); m.syncNow()
        val mine = m.goalsView.value
        assertTrue(mine.habits.single().doneToday)
        assertEquals(listOf("Fitter by spring"), mine.goals.map { it.title })
        assertTrue(mine.goals.single().counted)
    }

    @Test
    fun aFastSyncsAndEndsOnTheOtherDevice() = runTest {
        val a = core("android"); val m = core("mac")
        a.chooseFastingPlan(2)
        a.startFast(60)
        assertEquals(18, a.fastingView.value.current?.targetHours)
        a.syncNow(); m.syncNow()
        assertTrue(m.fastingView.value.isFasting)
        assertEquals(18, m.fastingView.value.plan.targetHours)
        m.endFast()
        m.syncNow(); a.syncNow()
        assertTrue(!a.fastingView.value.isFasting)
        assertTrue(a.fastingView.value.last != null)
        assertTrue(a.fastingView.value.weekLine != null)
    }

    @Test
    fun anExtendedFastSyncsAndEndsIntoTheHistory() = runTest {
        val a = core("android"); val m = core("mac")
        a.startExtendedFast(120, 0)
        assertEquals("5-day fast", a.fastingView.value.current?.title)
        a.syncNow(); m.syncNow()
        assertEquals("Day 1 of 5 · 0 h", m.fastingView.value.current?.dayLine)
        m.endFast()
        m.syncNow(); a.syncNow()
        val h = a.fastingView.value.history
        assertEquals(listOf("5-day fast"), h.fasts.map { it.title })
        assertTrue(h.fasts.single().resultLine.endsWith("ended early"))
        assertEquals(12, h.heat.size)
        val until = a.fastingView.value.untilChoices.last()
        a.startFastUntil(until.untilMs, 0)
        assertEquals(until.label.replace("Until", "Fast until"), a.fastingView.value.current?.title)
    }

    @Test
    fun theShutdownCarriesWhatsLeftToTomorrowAndIsPutAwayOnTheOtherDevice() = runTest {
        val a = core("android"); val m = core("mac")
        a.addTask("Post the letter")
        val done = a.addTask("Call the garage")
        a.complete(done)
        val v = a.shutdownView.value
        assertEquals(listOf("Post the letter"), v.left.map { it.task.title })
        assertEquals(1, v.doneCount)
        assertTrue(v.tomorrow.label.startsWith("Tomorrow · "))

        a.carryAllToTomorrow()
        assertTrue(a.shutdownView.value.left.isEmpty())
        assertEquals(listOf("Post the letter"), a.shutdownView.value.tomorrow.rows.map { it.title })
        assertTrue(a.today.value.isClear)

        a.shutDown()
        assertTrue(a.shutdownView.value.doneToday)
        assertTrue(!a.shutdownView.value.offered)
        a.syncNow(); m.syncNow()
        assertTrue(m.shutdownView.value.doneToday)
        assertEquals(listOf("Post the letter"), m.shutdownView.value.tomorrow.rows.map { it.title })
    }

    @Test
    fun theMorningBriefShowsTheDayAndGotItPutsItAwayOnTheOtherDevice() = runTest {
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 9, 22, 7, 45).toInstant(london).toEpochMilliseconds() // a Tuesday
        val a = core("android"); val m = core("mac")
        a.addTask("Post the letter")
        a.addWaiting("Deposit back", "Landlord", 0)
        a.tick()
        val v = a.briefView.value
        assertTrue(v.offered)
        assertEquals("Good morning", v.greeting)
        assertEquals("Tue 22 Sep", v.dateLabel)
        assertEquals("Work 09:00–17:30", v.workLine)
        assertEquals(listOf("Post the letter"), v.day.map { it.title })
        assertEquals("Waiting on 1 thing · 1 to chase today", v.waitingLine)
        assertEquals("1 task · 1 to chase", v.cardLine)
        val posted = a.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        assertEquals(listOf("Morning brief"), posted.post.map { it.title })

        a.briefSeen()
        assertTrue(a.briefView.value.seenToday)
        assertTrue(!a.briefView.value.offered)
        a.syncNow(); m.syncNow()
        assertTrue(m.briefView.value.seenToday)
        assertTrue(!m.briefView.value.offered)
    }

    @Test
    fun theBedsideClockShowsTheBriefInTheMorningAndWhatIsNextOnceItIsRead() = runTest {
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 9, 22, 7, 45).toInstant(london).toEpochMilliseconds() // Tue, BST
        val a = core("android")
        a.addTask("Post the letter")
        a.tick()
        val alarm = kotlinx.datetime.LocalDateTime(2026, 9, 23, 6, 30).toInstant(london).toEpochMilliseconds()
        val v = a.bedside(alarm)
        assertEquals("07:45", v.time) // local time, not UTC
        assertEquals("Tuesday 22 September", v.dateLabel)
        assertEquals("Alarm 06:30 · in 22 h 45", v.alarmLine)
        assertEquals(os.meka.core.domain.BedsideSection.MORNING, v.section)
        assertEquals(os.meka.core.domain.BedsideOpens.BRIEF, v.opens)
        a.briefSeen()
        val day = a.bedside(null)
        assertEquals(os.meka.core.domain.BedsideSection.DAY, day.section)
        assertEquals(listOf("Next: Post the letter"), day.lines)
        assertEquals("No alarm set", day.alarmLine)
    }

    @Test
    fun theNowCardFollowsUpNextAndClearsWhenItIsDoneOnTheOtherDevice() = runTest {
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 10, 7, 16, 30).toInstant(london).toEpochMilliseconds() // Wed, BST
        val a = core("android"); val m = core("mac")
        val id = a.addTask("Post the letter")
        a.schedule(id, kotlinx.datetime.LocalDateTime(2026, 10, 7, 17, 0).toInstant(london).toEpochMilliseconds())
        val v = a.coverNow()
        assertEquals(os.meka.core.domain.NowKind.TASK, v.kind)
        assertEquals("Post the letter", v.title)
        assertEquals("At 17:00", v.line) // local time, not UTC
        a.syncNow(); m.syncNow()
        assertEquals("Post the letter", m.coverNow().title)
        m.complete(id)
        m.syncNow(); a.syncNow()
        assertEquals(os.meka.core.domain.NowKind.CLEAR, a.coverNow().kind)
    }

    @Test
    fun homeWidgetsFollowUpNextAndAFastStartedOnTheOtherDevice() = runTest {
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 10, 7, 16, 30).toInstant(london).toEpochMilliseconds() // Wed, BST
        val a = core("android"); val m = core("mac")
        val id = a.addTask("Post the letter")
        a.schedule(id, kotlinx.datetime.LocalDateTime(2026, 10, 7, 17, 0).toInstant(london).toEpochMilliseconds())
        var w = a.homeWidgets()
        assertEquals("Up next", w.next.label)
        assertEquals("Post the letter", w.next.title)
        assertEquals("At 17:00", w.next.line) // local time, not UTC
        assertEquals("Nothing needs you", w.needsYou.label)
        assertEquals(false, w.fast.running)
        assertEquals(now + 30 * 60_000L, w.nextChangeMs)

        m.startFast(60)
        m.syncNow(); a.syncNow()
        w = a.homeWidgets()
        assertEquals(true, w.fast.running)
        assertEquals(now - 60 * 60_000L, w.fast.startedAtMs)
        assertEquals("Fasting · goal 16 h", w.fast.title)
    }

    @Test
    fun theCalendarTabShowsAPlannedTaskOnItsDayOnBothDevices() = runTest {
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 10, 6, 10, 0).toInstant(london).toEpochMilliseconds() // Tue 6 Oct
        val a = core("android"); val m = core("mac")
        val id = a.addTask("Pay the plumber")
        a.schedule(id, kotlinx.datetime.LocalDateTime(2026, 10, 9, 14, 0).toInstant(london).toEpochMilliseconds())
        val v = a.calendarView.value
        assertEquals("Tuesday 6 October", v.todayLabel)
        assertEquals(listOf("Today", "Tomorrow", "Thu 8 Oct", "Fri 9 Oct"), v.sections.take(4).map { it.title })
        val fri = v.sections[3]
        assertEquals("1 task", fri.subtitle)
        assertEquals(listOf("14:00" to "Pay the plumber"), fri.rows.map { it.time to it.title })
        assertEquals("This week", v.weeks.first().title)
        a.syncNow(); m.syncNow()
        assertEquals("Fri 9 Oct", m.calendarView.value.sections[3].title)
    }

    @Test
    fun calendarActionsAddAPrepTaskAndHideAnEventOnBothDevices() = runTest {
        val london = TimeZone.of("Europe/London")
        fun at(h: Int, min: Int = 0) = kotlinx.datetime.LocalDateTime(2026, 10, 6, h, min).toInstant(london).toEpochMilliseconds()
        now = at(10) // Tue 6 Oct
        val a = core("android"); val m = core("mac")
        val call = os.meka.core.domain.CalendarEvent("evabc", "Call with Tunde", at(14), at(15), false, null, "google", "meka@gmail.com", "Personal")
        val id = a.addPrepTask(call)
        assertEquals(id, a.addPrepTask(call))
        val prep = a.today.value.timeline.rows.single { it.task?.id == id }
        assertEquals("13:30" to "Prepare for Call with Tunde", prep.time to prep.title)
        assertEquals("Prep task at 13:30", a.eventDetail(call).prepLine)

        a.hideEvent(call.id)
        assertTrue(a.eventMarks.value.isHidden(call.id))
        assertTrue(a.eventDetail(call).hidden)
        a.syncNow(); m.syncNow()
        assertTrue(m.eventMarks.value.isHidden(call.id))
        assertEquals("Prep task at 13:30", m.eventDetail(call).prepLine)
        m.showEvent(call.id)
        m.syncNow(); a.syncNow()
        assertTrue(!a.eventMarks.value.isHidden(call.id))

        // Remind me (slice 2): set on one device, shown on both; 0 turns it off.
        a.setEventReminder(call.id, 10)
        assertEquals("Reminder 10 min before", a.eventDetail(call).reminderLine)
        a.syncNow(); m.syncNow()
        assertEquals(10, m.eventMarks.value.reminderOf(call.id))
        m.setEventReminder(call.id, 0)
        m.syncNow(); a.syncNow()
        assertEquals(null, a.eventDetail(call).reminderLine)
    }

    @Test
    fun theWeeklyReviewCountsTheWeekStepsBackAndDoneReviewingSyncs() = runTest {
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 10, 1, 18, 30).toInstant(london).toEpochMilliseconds() // a Thursday
        val a = core("android"); val m = core("mac")
        val id = a.addTask("Book the MOT")
        a.complete(id)
        a.tick()
        val v = a.reviewView.value
        assertEquals("This week", v.title)
        assertEquals("Mon 28 Sep – Sun 4 Oct", v.rangeLabel)
        assertEquals(listOf("Book the MOT"), v.done.map { it.title })
        assertEquals("Next week", v.aheadTitle)

        a.showReviewWeek(-1)
        assertEquals("Last week", a.reviewView.value.title)
        assertTrue(a.reviewView.value.done.isEmpty())
        a.showReviewWeek(0)

        a.reviewDone()
        assertTrue(a.reviewView.value.reviewed)
        a.syncNow(); m.syncNow()
        assertTrue(m.reviewView.value.reviewed)
        assertEquals("Reviewed Thu 1 Oct at 18:30", m.reviewView.value.reviewedLine)
    }

    @Test
    fun theReviewCardRisesOnSundayEveningOpensItsWeekAndPostsOneHeadsUp() = runTest {
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 10, 4, 20, 30).toInstant(london).toEpochMilliseconds() // a Sunday, after the 18:00 digest
        val a = core("android"); val m = core("mac")
        a.complete(a.addTask("Book the MOT"))
        a.tick()
        val card = a.reviewView.value.card
        assertTrue(card.offered)
        assertEquals("Review your week", card.title)
        assertEquals("1 done", card.line)

        a.showReviewWeek(-3)
        a.showReviewCardWeek()
        assertEquals("This week", a.reviewView.value.title)

        val posted = a.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        assertEquals(listOf("Review your week"), posted.post.filter { it.source == os.meka.core.domain.NoticeSource.WEEKLY_REVIEW }.map { it.title })

        a.reviewDone()
        a.syncNow(); m.syncNow()
        m.tick()
        assertTrue(!m.reviewView.value.card.offered)
    }

    @Test
    fun whatTheFoldPostsCountsAsInterruptionsInTheReviewOnBothDevices() = runTest {
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 10, 4, 20, 30).toInstant(london).toEpochMilliseconds() // Sunday evening
        val a = core("android"); val m = core("mac")
        a.tick()
        val pending = a.reviewView.value.northStar.single { it.key == "interruptions" }
        assertEquals(null, pending.value)

        val result = a.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        assertTrue(result.post.isNotEmpty())
        a.notificationsPosted(result.post)
        a.syncNow(); m.syncNow()
        m.showReviewWeek(0)
        val metric = m.reviewView.value.northStar.single { it.key == "interruptions" }
        assertEquals(result.post.count { it.tier.interrupts }.toString(), metric.value)
        assertTrue(metric.line.contains("heads-up"), metric.line)
    }

    @Test
    fun whatReachedYouShowsInTheActivityLogOnBothDevices() = runTest {
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 10, 4, 20, 30).toInstant(london).toEpochMilliseconds() // Sunday evening
        val a = core("android"); val m = core("mac")
        a.tick()
        assertTrue(a.activityView.value.isEmpty)

        val result = a.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        val keys = result.post.map { it.key }
        assertTrue(keys.isNotEmpty())
        // The Mac path: keys as plain strings, the digest flagged by its key.
        a.notificationsPostedKeys(result, keys + MekaCore.DIGEST_KEY)
        a.digestPosted(os.meka.core.domain.Digest("Evening digest · 2 things", "2 to chase", listOf("a", "b"), 2, "Evening digest",
            os.meka.core.domain.NoticeTarget.LISTS))
        a.syncNow(); m.syncNow()
        m.tick()
        val rows = m.activityView.value.days.single().rows
        assertEquals(keys.size + 1, rows.size)
        assertTrue(rows.any { it.summary == "Reminded you: Review your week" }, rows.toString())
        assertTrue(rows.any { it.summary == "Sent a digest: Evening digest · 2 things" })
        assertTrue(rows.none { it.canUndo })
        assertEquals("This can't be undone", m.undoActivity(rows.first().id))
    }

    @Test
    fun headlinesFromTheServerShowInTheBriefForTheChosenTopicsOnBothDevices() = runTest {
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 9, 22, 7, 45).toInstant(london).toEpochMilliseconds()
        val a = core("android"); val m = core("mac")
        // The server's news ingestion writes headline entities as ordinary server-authored ops.
        val clock = os.meka.core.sync.HlcClock("server", { now })
        var n = 0
        fun headline(id: String, title: String, topic: String, agoMin: Long) = mapOf(
            "title" to os.meka.core.sync.FieldValue.Text(title),
            "url" to os.meka.core.sync.FieldValue.Text("https://www.bbc.com/news/articles/$id"),
            "source" to os.meka.core.sync.FieldValue.Text("BBC News"),
            "topic" to os.meka.core.sync.FieldValue.Text(topic),
            "publishedAtMs" to os.meka.core.sync.FieldValue.Int64(now - agoMin * 60_000L),
            "removed" to os.meka.core.sync.FieldValue.Bool(false),
        ).forEach { (field, value) ->
            serverOps.append(os.meka.core.sync.Op("srv${n++}", "hh", os.meka.core.domain.EntityTypes.HEADLINE, id, field, value, clock.now(), emptyList(), "server"))
        }
        headline("hl1", "Summit opens", "world", 20)
        headline("hl2", "New phone launched", "technology", 5)
        a.syncNow(); m.syncNow()
        assertEquals(listOf("Summit opens"), a.briefView.value.headlines.map { it.title })
        assertEquals("BBC News · World · 20 min ago", a.briefView.value.headlines.single().meta)
        a.setNewsTopic("technology", true)
        assertEquals(listOf("New phone launched", "Summit opens"), a.briefView.value.headlines.map { it.title })
        a.syncNow(); m.syncNow()
        assertEquals(listOf("New phone launched", "Summit opens"), m.briefView.value.headlines.map { it.title })
        assertTrue(m.briefView.value.newsTopics.single { it.id == "technology" }.chosen)
    }

    @Test
    fun searchFindsAcrossKindsFollowsEditsAndSyncAndClears() = runTest {
        val a = core("android"); val m = core("mac")
        val task = a.addTask("Renew passport")
        a.addWaiting("Passport photos", "Snappy Snaps", 3)
        a.recordDecision("Fast-track the passport", "Trip in November", null)
        a.search("passp")
        val v = a.searchView.value
        assertEquals(listOf("Tasks", "Waiting for", "Decisions"), v.groups.map { it.label })
        assertEquals("3 matches", v.summary)
        assertEquals(task, v.groups[0].hits.single().task?.id)

        a.complete(task) // the results follow edits while the query is set
        assertEquals(listOf("Waiting for", "Decisions", "Done"), a.searchView.value.groups.map { it.label })

        a.syncNow(); m.syncNow()
        m.search("november")
        assertEquals("Trip in November", m.searchView.value.hits.single().snippet)

        a.search("")
        assertEquals(false, a.searchView.value.active)
        assertTrue(a.searchView.value.groups.isEmpty())
        a.addTask("Passport form")
        assertTrue(a.searchView.value.groups.isEmpty(), "nothing is searched once cleared")
    }

    @Test
    fun theMiddayDigestSumsUpWhatsDueOnceAndSettingsSync() = runTest {
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 9, 22, 11, 0).toInstant(london).toEpochMilliseconds()
        val a = core("android"); val m = core("mac")
        a.addWaiting("Deposit back", "Landlord", 0)
        val early = a.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        assertEquals(null, early.digest)
        assertEquals(kotlinx.datetime.LocalDateTime(2026, 9, 22, 12, 30).toInstant(london).toEpochMilliseconds(), early.nextWakeMs)
        assertEquals("Next digest 12:30 · 1 thing so far", a.notificationPreview.value.digestLine)
        now = kotlinx.datetime.LocalDateTime(2026, 9, 22, 12, 31).toInstant(london).toEpochMilliseconds()
        val r = a.governNotifications(early.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL)
        assertEquals("Midday digest · 1 thing", r.digest?.title)
        assertEquals("1 to chase", r.digest?.summary)
        assertEquals(null, a.governNotifications(r.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL).digest)
        a.setQuietHours(true, 23 * 60, 6 * 60)
        a.setNoticeTier(os.meka.core.domain.NoticeSource.CHASE, os.meka.core.domain.NoticeTier.SILENT)
        a.setNoticeTier(os.meka.core.domain.NoticeSource.SHUTDOWN, os.meka.core.domain.NoticeTier.SILENT)
        a.syncNow(); m.syncNow()
        assertEquals("23:00–06:00", m.notificationSettings.value.quiet.summary)
        assertEquals(os.meka.core.domain.NoticeTier.SILENT, m.notificationSettings.value.tierFor(os.meka.core.domain.NoticeSource.CHASE))
        now = kotlinx.datetime.LocalDateTime(2026, 9, 22, 18, 1).toInstant(london).toEpochMilliseconds()
        assertEquals(null, m.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL).digest, "chases and the shutdown nudge are app-only now")
    }
}
