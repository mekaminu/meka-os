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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        assertTrue(c.today.value.isAllClear)
        val id = c.addTask("Call James about football")
        assertEquals(id, c.today.value.upNext?.id)
        c.complete(id)
        assertTrue(c.today.value.isClear)
        assertEquals(listOf(id), c.today.value.doneToday.map { it.id })
    }

    @Test
    fun theDayRingsLiveTilesFollowTheFastAndTheClock() = runTest {
        val c = core("android")
        assertEquals(emptyList(), c.today.value.dayTiles)
        c.startFast(startedMinutesAgo = 125)
        val fast = c.today.value.dayTiles.single()
        assertEquals(os.meka.core.domain.DayTileKind.FAST, fast.kind)
        assertEquals("2 h", fast.valueText)
        now += 60 * 60_000L
        c.tick()
        assertEquals("3 h", c.today.value.dayTiles.single().valueText)
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
    fun afterWorkSummaryHeldOnTheFoldShowsOnTheMacAndDoneOnTheMacClearsBoth() = runTest {
        val a = core("android"); val m = core("mac")
        val at = now - 3_600_000L
        val item = os.meka.core.domain.CapturedItem(
            os.meka.core.domain.Capture.itemId(os.meka.core.domain.CaptureApp.WHATSAPP, os.meka.core.domain.CaptureKind.MESSAGE, "Mum", null, at, "call me"),
            os.meka.core.domain.CaptureApp.WHATSAPP, os.meka.core.domain.CaptureKind.MESSAGE, "Mum", "call me", null, at,
        )
        assertEquals(1, a.holdCaptured(listOf(item), os.meka.core.domain.PeopleLists(family = setOf("Mum"))))
        assertEquals(0, a.holdCaptured(listOf(item), os.meka.core.domain.PeopleLists()))
        a.syncNow(); m.syncNow()
        assertEquals("1 person · 1 message", m.afterWork.value.headline)
        assertTrue(m.afterWork.value.people.single().isFamily)
        // Fold review 2026-10-09, item 5: the "held for later" line unfolds a preview; looking clears nothing.
        val preview = m.heldPreview(m.afterWork.value)
        assertTrue(preview.label.endsWith(" · 1 held for later"))
        assertEquals("Mum", preview.rows.single().who)
        assertEquals("call me", preview.rows.single().line)
        assertEquals("1 person · 1 message", m.afterWork.value.headline)
        assertEquals(1, m.clearAfterWork())
        m.syncNow(); a.syncNow()
        assertTrue(a.afterWork.value.isEmpty)
        assertTrue(m.afterWork.value.isEmpty)
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
    fun aDaysOwnHoursSyncAndTheUsualHoursKeepThem() = runTest {
        // Monday 21 Sept 2026, 15:13 in London. Thursday is the short day by default (Meka, 2026-10-09).
        val fold = core("fold"); val mac = core("mac")
        assertEquals("Mon–Fri · 09:00–17:30 · Thu 09:00–15:30", fold.workMode.value.schedule.summary)
        mac.setWorkDayHours(1, 9 * 60, 16 * 60) // Monday short too
        mac.syncNow(); fold.syncNow()
        assertEquals("At work until 16:00", fold.currentWorkMode().line)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri"), fold.workMode.value.schedule.weekRows.map { it.dayShort })
        assertTrue(fold.workMode.value.schedule.weekRows.first().own)
        fold.setWorkSchedule(listOf(1, 2, 3, 4, 5), 8 * 60, 17 * 60, true) // the usual hours move; Mon and Thu keep theirs
        assertEquals("Mon–Fri · 08:00–17:00 · Mon 09:00–16:00 · Thu 09:00–15:30", fold.workMode.value.schedule.summary)
        fold.clearWorkDayHours(1)
        assertEquals("At work until 17:00", fold.currentWorkMode().line)
    }

    @Test
    fun aBankHolidayFromTheServerKeepsWorkModeOffOnBothDevices() = runTest {
        // 1_790_000_000_000 ms is Monday 21 Sept 2026, 15:13 in London: a work day, unless the server says it's a holiday.
        val fold = core("fold"); val mac = core("mac")
        assertTrue(fold.workMode.value.atWork)
        val clock = os.meka.core.sync.HlcClock("server", { now })
        serverOps.append(
            os.meka.core.sync.Op(
                "srvbh1", "hh", os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.BankHolidayStore.ENTITY_ID,
                os.meka.core.domain.BankHolidayFields.DATES, os.meka.core.sync.FieldValue.Text("2026-09-21=Test holiday;2026-12-25=Christmas Day"),
                clock.now(), emptyList(), "server",
            ),
        )
        fold.syncNow(); mac.syncNow()
        for (c in listOf(fold, mac)) {
            assertEquals(false, c.currentWorkMode().atWork)
            assertEquals("Off work · Test holiday · next shift tomorrow 09:00", c.workMode.value.line)
            assertEquals("Test holiday · no work", c.briefView.value.workLine)
        }
        // "Work on" on the holiday is a real override, and it syncs.
        mac.setWorkSwitch(true)
        mac.syncNow(); fold.syncNow()
        assertTrue(fold.currentWorkMode().let { it.atWork && it.switchedManually })
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

        assertEquals(null, a.today.value.dayRing.tomorrow)
        a.shutDown()
        assertTrue(a.shutdownView.value.doneToday)
        assertTrue(!a.shutdownView.value.offered)
        // Living Today, item 1: once shut down the Day ring looks ahead to tomorrow (nothing timed yet), on both devices.
        assertEquals("Nothing booked yet", a.today.value.dayRing.tomorrow?.caption)
        a.syncNow(); m.syncNow()
        assertTrue(m.shutdownView.value.doneToday)
        assertEquals("Tomorrow", m.today.value.dayRing.tomorrow?.headline)
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

        a.briefSeen("Fold")
        assertTrue(a.briefView.value.seenToday)
        assertTrue(!a.briefView.value.offered)
        a.syncNow(); m.syncNow()
        assertTrue(m.briefView.value.seenToday)
        assertTrue(!m.briefView.value.offered)
    }

    @Test
    fun theWakeAlarmSetOnTheFoldRingsOnBothAndDismissOnTheMacStopsTheFold() = runTest {
        // Alarms, slice 1: Thursday evening (BST); Friday is a work day, so work at 09:00 less an hour.
        val london = TimeZone.of("Europe/London")
        fun at(d: Int, h: Int, m: Int) = kotlinx.datetime.LocalDateTime(2026, 10, d, h, m).toInstant(london).toEpochMilliseconds()
        now = at(8, 21, 30)
        val a = core("android"); val m = core("mac")
        a.tick()
        val v = a.wakeView.value
        assertEquals("Tomorrow · Fri 9 Oct", v.dayLabel)
        assertEquals("08:00", v.timeLabel)
        assertEquals("Suggested from Work at 09:00 · 1 h to get ready", v.line)
        assertEquals(null, a.nextAlarm.value)

        assertTrue(a.useSuggestedWake())
        assertEquals(at(9, 8, 0), a.nextAlarm.value?.ringAtMs)
        assertEquals("Work at 09:00", a.nextAlarm.value?.line)
        assertTrue(a.setWake(7 * 60 + 45))
        assertEquals("07:45", a.wakeView.value.timeLabel)
        a.syncNow(); m.syncNow()
        assertEquals(at(9, 7, 45), m.nextAlarm.value?.ringAtMs)
        assertEquals(null, a.ringingAlarm())

        now = at(9, 7, 45); a.tick(); m.tick()
        val ringing = a.ringingAlarm()!!
        val snoozed = a.snoozeAlarm(ringing.id)!!
        assertEquals("Snoozed until 07:54", snoozed.snoozeLine)
        assertEquals(null, a.ringingAlarm())
        now = at(9, 7, 54); a.tick()
        assertEquals(ringing.id, a.ringingAlarm()?.id)
        a.syncNow(); m.syncNow()
        assertTrue(m.dismissAlarm(ringing.id))
        m.syncNow(); a.syncNow()
        assertEquals(null, a.nextAlarm.value)
        assertEquals(null, a.ringingAlarm())
    }

    @Test
    fun typedAlarmsAndTimersAreSetFromCaptureAndSharedTextStaysATask() = runTest {
        // Alarms, slice 2: Thursday afternoon, BST.
        val london = TimeZone.of("Europe/London")
        fun at(d: Int, h: Int, m: Int) = kotlinx.datetime.LocalDateTime(2026, 10, d, h, m).toInstant(london).toEpochMilliseconds()
        now = at(8, 14, 32)
        val a = core("android"); val m = core("mac")

        val timer = a.captureTyped("timer 20 min pasta") as os.meka.core.domain.CaptureOutcome.AlarmSet
        assertEquals("Timer set · 20 min · ends 14:52 · pasta", timer.line)
        assertEquals(at(8, 14, 52), a.nextAlarm.value?.ringAtMs)
        assertEquals("20 min · ends 14:52 · 20 min left", a.quickAlarms.value.single().detail)
        val alarm = a.captureTyped("alarm 6:30") as os.meka.core.domain.CaptureOutcome.AlarmSet
        assertEquals("Alarm set for 06:30 tomorrow", alarm.line)
        // Not tasks.
        assertTrue(a.today.value.yourDay.isEmpty() && a.today.value.upNext == null)

        a.syncNow(); m.syncNow()
        assertEquals(listOf("pasta", "Alarm"), m.quickAlarms.value.map { it.title })
        assertEquals(at(9, 6, 30), m.quickAlarms.value[1].ringAtMs)
        assertTrue(m.cancelAlarm(alarm.alarmId))
        m.syncNow(); a.syncNow()
        assertEquals(listOf("pasta"), a.quickAlarms.value.map { it.title })

        // Anything else is a task; shared text never sets an alarm.
        assertTrue(a.captureTyped("Book dentist") is os.meka.core.domain.CaptureOutcome.TaskAdded)
        assertTrue(a.capture("timer 5 min", null) != null)
        assertEquals(1, a.quickAlarms.value.size)
        assertTrue(a.captureTyped("  ") is os.meka.core.domain.CaptureOutcome.Empty)
    }

    @Test
    fun calmTodayReadingTheBriefOnTheFoldFoldsTheCardAwayOnBothWithoutGotIt() = runTest {
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 10, 13, 7, 40).toInstant(london).toEpochMilliseconds() // a Tuesday
        val a = core("android"); val m = core("mac")
        a.addTask("Post the letter")
        a.tick()
        assertTrue(a.briefView.value.offered)
        // A glance (opened and closed at once) leaves the card.
        assertTrue(!a.briefLookedAt("Fold", 1_200))
        assertTrue(a.briefView.value.offered)
        // Open long enough to read: the card goes here and on the Mac, which says where it was read.
        assertTrue(a.briefLookedAt("Fold", 9_000))
        assertTrue(a.briefView.value.seenToday && !a.briefView.value.offered)
        a.syncNow(); m.syncNow(); m.tick()
        assertTrue(!m.briefView.value.offered)
        assertEquals("Brief read on your Fold", m.briefView.value.readElsewhereLine)
        // Reading it again changes nothing.
        assertTrue(!a.briefLookedAt("Fold", 9_000))
    }

    @Test
    fun readAfterMidnightTheBriefStillComesInTheMorningAndAReadOnTheMacIsALineOnTheFold() = runTest {
        // Fold review 2026-10-08: no card at 07:14–07:58 BST. A read after midnight was last night's brief.
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 10, 8, 0, 30).toInstant(london).toEpochMilliseconds() // Thu, BST
        val a = core("android"); val m = core("mac")
        a.briefSeen("Fold")
        now = kotlinx.datetime.LocalDateTime(2026, 10, 8, 7, 14).toInstant(london).toEpochMilliseconds()
        a.tick()
        assertTrue(a.briefView.value.offered)
        m.briefSeen("Mac")
        m.syncNow(); a.syncNow(); a.tick()
        assertTrue(!a.briefView.value.offered)
        assertEquals("Brief read on your Mac", a.briefView.value.readElsewhereLine)
        assertEquals(null, m.briefView.value.readElsewhereLine)
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
        a.briefSeen("Fold")
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
    fun theWatchShowsUpNextTakesDoneAndTomorrowAndStartsAndEndsAFastForTheFold() = runTest {
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 10, 10, 16, 30).toInstant(london).toEpochMilliseconds() // Sat, BST
        val fold = core("android"); val watch = core("watch0123456789abcdef")
        val letter = fold.addTask("Post the letter")
        fold.schedule(letter, kotlinx.datetime.LocalDateTime(2026, 10, 10, 17, 0).toInstant(london).toEpochMilliseconds())
        val bins = fold.addTask("Put the bins out")
        fold.syncNow(); watch.syncNow()

        val v = watch.watchHome()
        assertEquals("UP NEXT", v.label)
        assertEquals("Post the letter", v.title)
        assertEquals("At 17:00", v.line)
        assertEquals(listOf("Done", "Tomorrow"), v.buttons.map { it.label })
        watch.watchPress(v.buttons.first())
        watch.syncNow(); fold.syncNow()
        assertEquals(listOf(letter), fold.today.value.doneToday.map { it.id })

        val next = watch.watchHome()
        assertEquals("Put the bins out", next.title)
        watch.watchPress(next.buttons.single { it.label == "Tomorrow" })
        assertNotEquals("Put the bins out", watch.watchHome().title)
        watch.syncNow(); fold.syncNow()
        assertTrue(fold.today.value.let { listOfNotNull(it.upNext) + it.yourDay }.none { it.id == bins })

        assertEquals(os.meka.core.domain.WatchHomeRules.START_FAST, watch.watchHome().fast.button)
        watch.watchFastButton()
        assertEquals(os.meka.core.domain.WatchHomeRules.END_FAST, watch.watchHome().fast.button)
        watch.syncNow(); fold.syncNow()
        assertTrue(fold.fastingView.value.isFasting)
        watch.watchFastButton()
        watch.syncNow(); fold.syncNow()
        assertFalse(fold.fastingView.value.isFasting)
    }

    @Test
    fun theWatchTilesDoneReachesTheFoldAndAStaleTileDoesNothing() = runTest {
        val london = TimeZone.of("Europe/London")
        now = kotlinx.datetime.LocalDateTime(2026, 10, 10, 16, 30).toInstant(london).toEpochMilliseconds() // Sat, BST
        val fold = core("android"); val watch = core("watch0123456789abcdef")
        fold.addTask("Post the letter")
        fold.addTask("Put the bins out")
        fold.syncNow(); watch.syncNow()

        assertEquals("0/2", watch.watchComplication().text)
        val tile = watch.watchTile()
        assertEquals(watch.watchHome().title, tile.title)
        assertEquals("Done", tile.button?.label)
        val id = tile.buttonId

        // Done on the Fold first: the watch's old tile is stale and its tap does nothing.
        fold.complete(tile.button!!.targetId); fold.syncNow(); watch.syncNow()
        assertFalse(watch.watchTilePress(id))
        assertEquals("1/2", watch.watchComplication().text)

        val next = watch.watchTile()
        assertTrue(watch.watchTilePress(next.buttonId))
        watch.syncNow(); fold.syncNow()
        assertEquals(2, fold.today.value.doneToday.size)
        assertEquals("2/2", watch.watchComplication().text)
        assertEquals(os.meka.core.domain.WatchTileRules.CALM_REFRESH_MS, watch.watchRefreshAfterMs())

        watch.watchFastButton()
        assertTrue(watch.watchComplication().fasting)
        assertEquals(60_000L - now % 60_000L, watch.watchRefreshAfterMs())
    }

    @Test
    fun aWatchCaptureByVoiceReachesTheFoldAndUndoTakesItBack() = runTest {
        val fold = core("android"); val watch = core("watch0123456789abcdef")
        assertNull(watch.watchCapture("  "))
        val c = watch.watchCapture(" buy milk and eggs. ")!!
        assertEquals("Added “Buy milk and eggs”", c.line)
        watch.syncNow(); fold.syncNow()
        val titles = { fold.today.value.let { listOfNotNull(it.upNext) + it.yourDay }.map { it.title } }
        assertEquals(listOf("Buy milk and eggs"), titles())

        watch.watchCaptureUndo(c)
        watch.syncNow(); fold.syncNow()
        assertTrue(titles().isEmpty())

        // "timer 20 min" said to the watch is a timer, as typed in Today's capture bar; Undo cancels it everywhere.
        val t = watch.watchCapture("timer 20 min")!!
        assertTrue(t.line.startsWith("Timer set · 20 min"), t.line)
        watch.syncNow(); fold.syncNow()
        assertEquals(1, fold.quickAlarms.value.size)
        watch.watchCaptureUndo(t)
        watch.syncNow(); fold.syncNow()
        assertTrue(fold.quickAlarms.value.isEmpty())
        assertTrue(titles().isEmpty())
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
    fun theNewsWidgetSaysWhereToChooseTopicsUntilNewsArrives() = runTest {
        val a = core("android")
        val w = a.newsWidget()
        assertEquals(true, w.isEmpty)
        assertEquals("No news yet", w.emptyTitle)
        assertEquals(Long.MAX_VALUE, w.nextChangeMs)
    }

    @Test
    fun theMacsDesktopNewsWidgetSaysWhereToChooseTopicsUntilNewsArrives() = runTest {
        val m = core("mac")
        val w = m.deskNewsWidget()
        assertEquals(true, w.isEmpty)
        assertEquals("No news yet", w.emptyTitle)
        assertEquals("Choose topics in MEKA · Ask › More › News", w.emptyLine)
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
    fun kitReminderOnAClubFixtureIsATaskTheEveningBeforeOnBothDevices() = runTest {
        val london = TimeZone.of("Europe/London")
        fun at(day: Int, h: Int, min: Int = 0) = kotlinx.datetime.LocalDateTime(2026, 10, day, h, min).toInstant(london).toEpochMilliseconds()
        now = at(8, 12) // Thu 8 Oct
        val a = core("android"); val m = core("mac")
        val match = os.meka.core.domain.CalendarEvent("evfc", "BUFC U9s v Arlesey", at(10, 10), at(10, 11), false, "Bury Field", "google", "meka@gmail.com", "Personal")
        assertTrue(a.eventDetail(match).canKit)
        val added = assertNotNull(a.addKitReminder(match))
        assertEquals("Kit reminder tomorrow 19:00", added.line)
        assertEquals(null, a.addKitReminder(match))
        val t = assertNotNull(a.eventMarks.value.kitTasks["evfc"])
        assertEquals(added.taskId, t.id)
        assertEquals(at(9, 19), t.remindAtMs)
        assertEquals(5, t.checklist.size)
        assertEquals("Kit reminder tomorrow 19:00 · 0 of 5 packed", a.eventDetail(match).kitLine)
        a.syncNow(); m.syncNow()
        assertEquals("Kit reminder tomorrow 19:00 · 0 of 5 packed", m.eventDetail(match).kitLine)
        assertTrue(!m.eventDetail(match).canKit)
        // Not a club fixture: nothing.
        assertEquals(null, a.addKitReminder(match.copy(id = "evx", title = "Dentist")))
    }

    @Test
    fun leaveByOnAFixtureIsRememberedForTheGroundAndOfferedOnTheNextFixtureThereOnBothDevices() = runTest {
        val london = TimeZone.of("Europe/London")
        fun at(day: Int, h: Int, min: Int = 0) = kotlinx.datetime.LocalDateTime(2026, 10, day, h, min).toInstant(london).toEpochMilliseconds()
        now = at(8, 12) // Thu 8 Oct
        val a = core("android"); val m = core("mac")
        val clock = os.meka.core.sync.HlcClock("server", { now })
        var k = 0
        fun fixture(id: String, title: String, start: Long) = mapOf(
            os.meka.core.domain.EventFields.TITLE to os.meka.core.sync.FieldValue.Text(title),
            os.meka.core.domain.EventFields.START_AT to os.meka.core.sync.FieldValue.Int64(start),
            os.meka.core.domain.EventFields.END_AT to os.meka.core.sync.FieldValue.Int64(start + 3_600_000L),
            os.meka.core.domain.EventFields.ALL_DAY to os.meka.core.sync.FieldValue.Bool(false),
            os.meka.core.domain.EventFields.LOCATION to os.meka.core.sync.FieldValue.Text("Arlesey Town FC, Hitchin Rd"),
            os.meka.core.domain.EventFields.PROVIDER to os.meka.core.sync.FieldValue.Text("google"),
            os.meka.core.domain.EventFields.ACCOUNT to os.meka.core.sync.FieldValue.Text("meka@gmail.com"),
            os.meka.core.domain.EventFields.CALENDAR to os.meka.core.sync.FieldValue.Text("Personal"),
            os.meka.core.domain.EventFields.REMOVED to os.meka.core.sync.FieldValue.Bool(false),
        ).forEach { (f, v) ->
            serverOps.append(os.meka.core.sync.Op("srvfc${k++}", "hh", os.meka.core.domain.EntityTypes.EVENT, id, f, v, clock.now(), emptyList(), "server"))
        }
        fixture("fc1", "BUFC U9s v Arlesey", at(10, 10))
        fixture("fc2", "BUFC U9s v Arlesey (cup)", at(17, 10))
        assertTrue(a.syncNow()); assertTrue(m.syncNow())
        val first = os.meka.core.domain.CalendarEvent("fc1", "BUFC U9s v Arlesey", at(10, 10), at(10, 11), false, "Arlesey Town FC, Hitchin Rd", "google", "meka@gmail.com", "Personal")
        val second = first.copy(id = "fc2", title = "BUFC U9s v Arlesey (cup)", startAtMs = at(17, 10), endAtMs = at(17, 11))
        assertNull(a.eventDetail(second).leaveOfferLabel)

        a.setEventLeaveBy("fc1", 40)
        a.setEventLeaveAlarm("fc1", true)
        assertEquals("Leave by 09:20 · as last time", a.eventDetail(second).leaveOfferLabel)
        assertNull(a.eventDetail(first).leaveOfferLabel)
        assertTrue(a.syncNow()); assertTrue(m.syncNow())
        assertEquals("Leave by 09:20 · as last time", m.eventDetail(second).leaveOfferLabel)

        // One tap on the Mac sets the same travel time and the alarm; Undo takes both off and the offer comes back.
        val used = assertNotNull(m.useLastLeaveBy(second))
        assertEquals("Leave by 09:20 · 40 min away · alarm", used.line)
        assertEquals(40, m.eventMarks.value.travel["fc2"])
        assertTrue("fc2" in m.eventMarks.value.leaveAlarms)
        assertNull(m.eventDetail(second).leaveOfferLabel)
        assertNull(m.useLastLeaveBy(second))
        m.undoLastLeaveBy("fc2")
        assertNull(m.eventMarks.value.travel["fc2"])
        assertFalse("fc2" in m.eventMarks.value.leaveAlarms)
        assertEquals(40, m.eventDetail(second).leaveOfferMin)
    }

    @Test
    fun aLeaveByTakenFromTheDriveFollowsTheServersLaterTrafficAnswerOnBothDevices() = runTest {
        val london = TimeZone.of("Europe/London")
        fun at(day: Int, h: Int, min: Int = 0) = kotlinx.datetime.LocalDateTime(2026, 10, day, h, min).toInstant(london).toEpochMilliseconds()
        now = at(8, 12) // Thu 8 Oct
        val a = core("android"); val m = core("mac")
        val clock = os.meka.core.sync.HlcClock("server", { now })
        var k = 0
        val place = "Bury Field, Biggleswade"
        mapOf(
            os.meka.core.domain.EventFields.TITLE to os.meka.core.sync.FieldValue.Text("BUFC U7s v Arlesey"),
            os.meka.core.domain.EventFields.START_AT to os.meka.core.sync.FieldValue.Int64(at(10, 10)),
            os.meka.core.domain.EventFields.END_AT to os.meka.core.sync.FieldValue.Int64(at(10, 11)),
            os.meka.core.domain.EventFields.ALL_DAY to os.meka.core.sync.FieldValue.Bool(false),
            os.meka.core.domain.EventFields.LOCATION to os.meka.core.sync.FieldValue.Text(place),
            os.meka.core.domain.EventFields.PROVIDER to os.meka.core.sync.FieldValue.Text("google"),
            os.meka.core.domain.EventFields.ACCOUNT to os.meka.core.sync.FieldValue.Text("meka@gmail.com"),
            os.meka.core.domain.EventFields.CALENDAR to os.meka.core.sync.FieldValue.Text("Personal"),
            os.meka.core.domain.EventFields.REMOVED to os.meka.core.sync.FieldValue.Bool(false),
        ).forEach { (f, v) ->
            serverOps.append(os.meka.core.sync.Op("srvtt${k++}", "hh", os.meka.core.domain.EntityTypes.EVENT, "tt1", f, v, clock.now(), emptyList(), "server"))
        }
        val e = os.meka.core.domain.CalendarEvent("tt1", "BUFC U7s v Arlesey", at(10, 10), at(10, 11), false, place, "google", "meka@gmail.com", "Personal")
        // What the server's travel feed writes ([os.meka.core.domain.TravelRules.fields]).
        fun serverDrive(drive: Int) {
            val l = os.meka.core.domain.TravelRules.Lookup("tt1", place, "bury field biggleswade", e.startAtMs - drive * 60_000L, e.startAtMs)
            os.meka.core.domain.TravelRules.fields(l, drive, now).forEach { (f, v) ->
                serverOps.append(os.meka.core.sync.Op("srvtt${k++}", "hh", os.meka.core.domain.EntityTypes.TRAVEL_TIME, os.meka.core.domain.TravelRules.entityId("tt1"), f, v, clock.now(), emptyList(), "server"))
            }
        }
        serverDrive(25)
        assertTrue(a.syncNow()); assertTrue(m.syncNow())
        assertEquals("Leave by 09:10 · 25 min drive", a.eventDetail(e).leaveOfferLabel)

        // Taken on the Fold; the Friday-evening answer says 40 min and both devices move Leave by to 08:55.
        val used = assertNotNull(a.useLastLeaveBy(e))
        assertEquals("bury field biggleswade", used.driveKey)
        assertEquals(50, a.eventMarks.value.travel["tt1"])
        assertTrue(a.syncNow())
        now = at(9, 18, 5)
        serverDrive(40)
        assertTrue(a.syncNow()); assertTrue(m.syncNow())
        assertEquals(65, a.eventMarks.value.travel["tt1"])
        assertEquals(65, m.eventMarks.value.travel["tt1"])
        assertEquals("Leave by 08:55 · 40 min drive · alarm", m.eventDetail(e).reminderLine)

        // Meka picks his own time on the Mac: it stays, whatever the next answer says.
        m.setEventLeaveBy("tt1", 45)
        assertTrue(m.syncNow())
        serverDrive(30)
        assertTrue(a.syncNow()); assertTrue(m.syncNow())
        assertEquals(45, a.eventMarks.value.travel["tt1"])
        assertEquals(45, m.eventMarks.value.travel["tt1"])
    }

    @Test
    fun theResultAfterAFixtureIsKeptOnTheFoldSeenOnTheMacAndUndone() = runTest {
        val london = TimeZone.of("Europe/London")
        fun at(day: Int, h: Int, min: Int = 0) = kotlinx.datetime.LocalDateTime(2026, 10, day, h, min).toInstant(london).toEpochMilliseconds()
        now = at(10, 9) // Sat 10 Oct, before kick-off
        val a = core("android"); val m = core("mac")
        val clock = os.meka.core.sync.HlcClock("server", { now })
        var k = 0
        mapOf(
            os.meka.core.domain.EventFields.TITLE to os.meka.core.sync.FieldValue.Text("BUFC U9s v Arlesey"),
            os.meka.core.domain.EventFields.START_AT to os.meka.core.sync.FieldValue.Int64(at(10, 10)),
            os.meka.core.domain.EventFields.END_AT to os.meka.core.sync.FieldValue.Int64(at(10, 11)),
            os.meka.core.domain.EventFields.ALL_DAY to os.meka.core.sync.FieldValue.Bool(false),
            os.meka.core.domain.EventFields.LOCATION to os.meka.core.sync.FieldValue.Text("Arlesey Town FC"),
            os.meka.core.domain.EventFields.PROVIDER to os.meka.core.sync.FieldValue.Text("google"),
            os.meka.core.domain.EventFields.ACCOUNT to os.meka.core.sync.FieldValue.Text("meka@gmail.com"),
            os.meka.core.domain.EventFields.CALENDAR to os.meka.core.sync.FieldValue.Text("Personal"),
            os.meka.core.domain.EventFields.REMOVED to os.meka.core.sync.FieldValue.Bool(false),
        ).forEach { (f, v) ->
            serverOps.append(os.meka.core.sync.Op("srvres${k++}", "hh", os.meka.core.domain.EntityTypes.EVENT, "fx1", f, v, clock.now(), emptyList(), "server"))
        }
        assertTrue(a.syncNow()); assertTrue(m.syncNow())
        val match = os.meka.core.domain.CalendarEvent("fx1", "BUFC U9s v Arlesey", at(10, 10), at(10, 11), false, "Arlesey Town FC", "google", "meka@gmail.com", "Personal")
        // Not yet over: nothing to keep.
        assertFalse(a.eventDetail(match).canResult)
        assertNull(a.saveMatchResult(match, 3, 1, "Leo 2, Sam", ""))

        now = at(10, 11, 20)
        assertTrue(a.eventDetail(match).canResult)
        val saved = assertNotNull(a.saveMatchResult(match, 3, 1, "Leo 2, Sam", "Played in goal second half"))
        assertEquals("Saved · Won 3–1 · Leo 2, Sam", saved.line)
        assertNull(saved.previous)
        assertEquals("Won 3–1 · Leo 2, Sam", a.eventDetail(match).resultLine)
        assertTrue(a.syncNow()); assertTrue(m.syncNow())
        assertEquals("Played in goal second half", m.eventDetail(match).resultNote)

        // Changed on the Mac, undone there: the Fold's result is back.
        val changed = assertNotNull(m.saveMatchResult(match, 3, 2, "Leo 2, Sam", "Played in goal second half"))
        assertEquals("Won 3–2 · Leo 2, Sam", m.eventDetail(match).resultLine)
        m.undoMatchResult(changed)
        assertEquals("Won 3–1 · Leo 2, Sam", m.eventDetail(match).resultLine)
        // Emptied: cleared, and its undo brings it back.
        val cleared = assertNotNull(m.saveMatchResult(match, -1, -1, "", ""))
        assertEquals("Result cleared", cleared.line)
        assertNull(m.eventDetail(match).resultLine)
        m.undoMatchResult(cleared)
        assertEquals("Won 3–1 · Leo 2, Sam", m.eventDetail(match).resultLine)
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
        // The News place follows the same choice: lanes for the chosen topics that have headlines.
        assertEquals(listOf("world", "technology"), m.newsPlace.value.lanes.map { it.topicId })
        val place = m.newsPlace.value
        assertEquals(listOf("Summit opens", "New phone launched"), place.items.map { it.title })
        assertEquals("2 of 2", place.detail(place.items[1].id)!!.position)
        // Talk's read-outs read the same brief and News place ("the headlines", "technology news", "Barça news").
        val ro = os.meka.core.domain.ReadOut.Kind.entries.associateWith { os.meka.core.domain.ReadOut(it) }
        assertEquals(
            "In the news. From BBC News: New phone launched. From BBC News: Summit opens. Anything else?",
            m.talkReadOut(ro.getValue(os.meka.core.domain.ReadOut.Kind.HEADLINES)),
        )
        assertEquals(
            "The latest technology news. From BBC News: New phone launched. Anything else?",
            m.talkReadOut(os.meka.core.domain.ReadOut(os.meka.core.domain.ReadOut.Kind.TOPIC, "technology")),
        )
        assertTrue(m.talkReadOut(ro.getValue(os.meka.core.domain.ReadOut.Kind.BRIEF)).startsWith("Good morning, Meka."))
        assertTrue(m.talkReadOut(os.meka.core.domain.ReadOut(os.meka.core.domain.ReadOut.Kind.TOPIC, "health")).startsWith("Health isn't one of your news topics."))
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

    @Test
    fun theGymIsBookedAfterWorkAsksDidYouGoAndRebooksOnTheOtherDevice() = runTest {
        // Monday 21 Sep 2026, 15:13 in London; work Mon–Fri 09:00–17:30 by default.
        val a = core("android"); val m = core("mac")
        val id = a.addGym()
        val card = a.sessionsView.value.cards.single()
        assertEquals("Gym", card.heading)
        assertEquals("Today 17:45–18:45", card.line)
        assertEquals("Booked Today 17:45 · Wed 17:45 · Fri 17:45", a.goalsView.value.habits.single().sessionLine)
        // The planner keeps the session free and shows it; the Gym isn't placed again as a flexible habit.
        assertEquals(listOf("Gym"), a.planDay().habits.map { it.title })
        a.setHabitRotation(id, 1)
        assertEquals("Gym · Push", a.sessionsView.value.cards.single().heading)
        // The workout app opens from the card (both devices); a link that isn't a web address is refused.
        assertTrue(a.setHabitAppLink(id, "hevy.com"))
        assertTrue(!a.setHabitAppLink(id, "intent://x"))
        assertEquals("Open Hevy", a.sessionsView.value.cards.single().openLabel)
        assertEquals("https://hevy.com", a.goalsView.value.habits.single().appLink)

        a.syncNow(); m.syncNow()
        assertEquals("https://hevy.com", m.sessionsView.value.cards.single().appLink)
        now += 4 * 3_600_000L // 19:13: the slot is over
        m.tick()
        assertEquals("Did you go? · 17:45–18:45", m.sessionsView.value.cards.single().line)
        m.sessionMissed(id)
        assertEquals("Rebooked for Tue 17:45 · Push", m.sessionsView.value.cards.single().next)
        m.syncNow(); a.syncNow()
        assertEquals("Not today · no worries", a.sessionsView.value.cards.single().line)
        a.undoSession(id)
        a.sessionWent(id, "felt strong")
        val went = a.sessionsView.value.cards.single()
        assertEquals("Went · 1 of 3 this week", went.line)
        assertEquals("felt strong", went.note)
        assertEquals("Next: Wed 17:45 · Pull", went.next)
        assertTrue(a.goalsView.value.habits.single().doneToday)
    }

    @Test
    fun aHandMadeGymHabitIsBookedInPlaceKeepingItsTicks() = runTest {
        // Monday 21 Sep 2026, 15:13 in London (Fold review 2026-10-09 13:45, item 2).
        val a = core("android"); val m = core("mac")
        val id = a.addHabit("gym", 2, os.meka.core.domain.HabitTiming.ANYTIME, 15, null)
        a.setHabitDone(id, true)
        assertTrue(!a.goalsView.value.offersAddGym, "no separate Add Gym beside a habit called gym")
        assertEquals(id, a.goalsView.value.bookOffer?.id)
        assertEquals("Let MEKA book “gym”", os.meka.core.domain.SessionRules.bookOfferLabel(a.goalsView.value.bookOffer!!.title))
        a.letMekaBook(id)
        val h = a.goalsView.value.habits.single()
        assertEquals(id, h.id)
        assertTrue(h.booked)
        assertEquals(60, h.minutes)
        assertEquals(os.meka.core.domain.HabitTiming.EVENING, h.timing)
        assertEquals(2, h.targetPerWeek)
        assertTrue(h.doneToday, "its tick today is kept")
        assertEquals(1, h.doneThisWeek)
        assertTrue(h.sessionLine != null)
        assertEquals(null, a.goalsView.value.bookOffer)
        assertTrue(!a.goalsView.value.offersAddGym)
        a.syncNow(); m.syncNow()
        assertTrue(m.goalsView.value.habits.single().booked)
        assertEquals(null, m.goalsView.value.bookOffer)
    }

    @Test
    fun aLatePlannedTaskLeadsUpNextAndMovesToLaterWithUndo() = runTest {
        // Monday 21 Sep 2026, 15:13 in London (Fold review 2026-10-09 13:45, item 3).
        val a = core("android"); val m = core("mac")
        a.addTask("Read the RFC")
        val id = a.addTask("add mutation to PM Autopilot")
        val planned = now - 2 * 3_600_000L // 13:13
        a.schedule(id, planned)
        assertEquals(id, a.today.value.upNext?.id, "the late planned task leads Up next, not the anytime one")
        val card = a.upNextCard()!!
        assertTrue(card.late && card.lit)
        assertEquals("Since 13:13", card.line)
        assertEquals(
            listOf(os.meka.core.domain.NowAction.DONE, os.meka.core.domain.NowAction.LATER, os.meka.core.domain.NowAction.TOMORROW),
            card.actions,
        )
        val move = a.moveLater(id)!!
        assertEquals("Moved “add mutation to PM Autopilot” to 15:15", move.line)
        assertEquals(planned, move.fromMs)
        assertEquals(move.toMs, card.laterAtMs)
        assertEquals(move.toMs, a.today.value.upNext?.scheduledAtMs)
        assertTrue(!a.upNextCard()!!.late)
        a.syncNow(); m.syncNow()
        assertEquals(move.toMs, m.today.value.upNext?.scheduledAtMs)
        a.undoMoveLater(move)
        assertEquals(planned, a.today.value.upNext?.scheduledAtMs)
        // An anytime task has no time to move.
        assertEquals(null, a.moveLater(a.today.value.yourDay.single().id))
    }

    @Test
    fun sundayEveningBooksNextWeekIntoGoals() = runTest {
        // Monday 21 Sep 2026, 15:13 in London; on to Sunday 27 Sep at 17:13, then 18:13.
        val a = core("android")
        a.addGym()
        now += 6 * 86_400_000L + 2 * 3_600_000L
        a.tick()
        assertTrue("Next week" !in a.goalsView.value.habits.single().sessionLine.orEmpty(), "not before the evening")
        now += 3_600_000L
        a.tick()
        // Sunday's 17:00 session is still to answer, so next week rests on Monday.
        // Thursday is the short day (work until 15:30), so its session can start at the evening window, 17:00.
        assertEquals("Booked Today 17:00 · no room for 2 more · Next week: Tue 17:45 · Thu 17:00 · Sat 17:00",
            a.goalsView.value.habits.single().sessionLine)
        assertEquals("Next: Tue 17:45", a.sessionsView.value.cards.single().next)
        // Today's timeline only ever holds today's sessions.
        assertTrue(a.today.value.timeline.rows.none { it.kind == os.meka.core.domain.TimelineKind.SESSION })
    }

    @Test
    fun didYouGoIsAnsweredFromTheNotificationOnceAndNotTheNextDay() = runTest {
        // Monday 21 Sep 2026, 15:13 in London.
        val a = core("android"); val m = core("mac")
        val id = a.addGym()
        a.setHabitRotation(id, 1)
        a.syncNow(); m.syncNow()
        now += 4 * 3_600_000L // 19:13: the slot is over
        a.tick(); m.tick()
        // The notice's key (at 19:13 the governor folds it into the evening digest, so it's built here as the core does).
        val ask = os.meka.core.domain.SessionRules.notices(a.sessionsView.value, ZoneCalendar { TimeZone.of("Europe/London") })
            .single { it.source == os.meka.core.domain.NoticeSource.SESSION_ASK }
        assertEquals("session:$id:${a.todayEpochDay()}:ask", ask.key)
        assertEquals(listOf(os.meka.core.domain.NoticeAction.WENT, os.meka.core.domain.NoticeAction.DIDNT_GO), ask.actions)
        val went = a.answerSessionNotice(ask.key, os.meka.core.domain.NoticeAction.WENT)
        assertEquals("Went · 1 of 3 this week", went?.line)
        assertEquals("Gym · Push", went?.heading) // the rotation's turn is recorded, as from the card
        assertEquals(null, a.answerSessionNotice(ask.key, os.meka.core.domain.NoticeAction.DIDNT_GO), "answered already")
        a.syncNow(); m.syncNow()
        assertEquals(null, m.answerSessionNotice(ask.key, os.meka.core.domain.NoticeAction.DIDNT_GO), "answered on the other device")
        assertEquals("Went · 1 of 3 this week", m.sessionsView.value.cards.single().line)
        // Undo works as from the card; the next day the old notification answers nothing.
        a.undoSession(id)
        now += 6 * 3_600_000L // past midnight
        a.tick()
        assertEquals(null, a.answerSessionNotice(ask.key, os.meka.core.domain.NoticeAction.WENT))
    }

    @Test
    fun theSessionSitsInTodaysTimelineAndTheNowCardAsksDidYouGo() = runTest {
        // Monday 21 Sep 2026, 15:13 in London; work until 17:30.
        val a = core("android")
        val id = a.addGym()
        val row = a.today.value.timeline.rows.single { it.kind == os.meka.core.domain.TimelineKind.SESSION }
        assertEquals("s-$id", row.id)
        assertEquals("17:45–18:45", row.time)
        assertEquals("Gym", row.title)
        assertEquals("Leave by 17:30", row.detail)
        assertEquals(null, a.today.value.clearLine, "a session ahead isn't \"You're clear.\"")
        now += 2 * 3_600_000L + 25 * 60_000L // 17:38: seven minutes to go
        a.tick()
        val soon = a.coverNow()
        assertEquals(os.meka.core.domain.NowKind.SESSION, soon.kind)
        assertEquals("In 7 min", soon.label)
        assertEquals("17:45–18:45 · Leave by 17:30", soon.line)
        assertEquals(os.meka.core.domain.HomeWidgetRules.STARTS_IN, a.homeWidgets().next.label)
        now += 95 * 60_000L // 19:13: over, so it leaves the timeline and the card asks
        a.tick()
        assertTrue(a.today.value.timeline.rows.none { it.kind == os.meka.core.domain.TimelineKind.SESSION })
        val ask = a.coverNow()
        assertEquals("Did you go?", ask.label)
        assertEquals(listOf(os.meka.core.domain.NowAction.WENT, os.meka.core.domain.NowAction.DIDNT_GO), ask.actions)
        a.sessionWent(ask.session!!.habitId, null)
        assertEquals(os.meka.core.domain.NowKind.CLEAR, a.coverNow().kind)
    }

    // ---- Calendar editing: Add event (slice 2b) ----

    /** The server's account list (calendar editing) on top of the in-memory sync service. */
    private inner class EditingTransport(
        var list: List<ConnectedAccount>,
        private val inner: FaultyTransport = FaultyTransport(service),
    ) : SyncTransport by inner, AccountsApi {
        var offline = false
        override suspend fun startConnect(provider: String, editing: Boolean): ConnectStart = ConnectStart.NotSetUp
        override suspend fun accounts(): List<ConnectedAccount> { if (offline) error("offline"); return list }
        override suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount> {
            list = list.map { if (it.provider == provider && it.email == email) it.copy(canEdit = false) else it }
            return list
        }
    }

    @Test
    fun calendarsTitlesEachAccountLikeItsMainCalendar() = runTest {
        // Meka's screenshot 2026-10-09 09:01: the rows showed the address and "news_more".
        val server = EditingTransport(
            listOf(
                ConnectedAccount("google", "meka@gmail.com", "ok", null),
                ConnectedAccount("microsoft", "meka@hotmail.co.uk", "ok", null),
                ConnectedAccount("news_more", "AI, tech and Barça news", "ok", null),
            ),
        )
        val fold = core("android", server)
        assertEquals(listOf("Personal", "Hotmail", "AI, tech and Barça news"), fold.connectedAccounts().map { it.title })
        fold.renameCalendar("google|meka@gmail.com|meka@gmail.com", "Home")
        val accounts = fold.connectedAccounts()
        assertEquals("Home", accounts[0].title)
        assertEquals("Google · meka@gmail.com · synced 08:29", accounts[0].statusLine("08:29"))
        assertEquals("Headlines · first sync in progress", accounts[2].statusLine(null))
        // Not synced yet: no events line (the status line says "first sync in progress").
        assertNull(accounts[1].eventsLine)
    }

    @Test
    fun calendarsSaysWhenAnAccountHasNoEventsComingThrough() = runTest {
        // Meka's 10:48 screenshots, 2026-10-09: Hotmail connected, nothing of it showing.
        val server = EditingTransport(
            listOf(
                ConnectedAccount("microsoft", "meka@hotmail.co.uk", "ok", 1L),
                ConnectedAccount("news_more", "AI, tech and Barça news", "ok", 1L),
            ),
        )
        val fold = core("android", server)
        val accounts = fold.connectedAccounts()
        assertEquals("No events in the next 30 days", accounts[0].eventsLine)
        assertNull(accounts[1].eventsLine)
    }

    @Test
    fun addEventWaitsForAnAccountThatCanEditThenBecomesAnEditBothDevicesSee() = runTest {
        val server = EditingTransport(
            listOf(
                ConnectedAccount("google", "meka@gmail.com", "ok", null, canEdit = true),
                ConnectedAccount("microsoft", "meka@outlook.com", "ok", null, canEdit = false),
                ConnectedAccount("fixtures", "FC Barcelona", "ok", null, canEdit = true),
            ),
        )
        val fold = core("android", server)
        val mac = core("mac")
        // Not read yet: nothing to add to.
        assertTrue(fold.calendarEditAccounts.value.isEmpty())
        val early = fold.addEventForm(-1).withTitle("Dentist")
        assertEquals("Allow editing on an account in Calendars first", fold.addEventView(early).problem)
        assertIs<os.meka.core.domain.EventEditResult.Refused>(fold.addEvent(early))

        assertEquals(listOf("google|meka@gmail.com"), fold.refreshCalendarAccounts().map { it.key })
        val form = fold.addEventForm(-1).withTitle("Dentist")
        assertEquals("google|meka@gmail.com", form.accountKey)
        assertEquals(15 * 60 + 30, form.minute) // 15:13 in London → 15:30
        val v = fold.addEventView(form)
        assertTrue(v.canAdd)
        assertEquals("Add to Google", v.addLabel)
        val id = assertIs<os.meka.core.domain.EventEditResult.Made>(fold.addEvent(form)).id
        assertEquals("Adding “Dentist” to Google", fold.eventEditLine(id))
        assertEquals(listOf(id), fold.calendarEditLines.value.map { it.id })

        // Undo inside the five seconds: the line goes.
        assertTrue(fold.undoEventEdit(id))
        assertTrue(fold.calendarEditLines.value.isEmpty())

        val again = assertIs<os.meka.core.domain.EventEditResult.Made>(fold.addEvent(fold.addEventForm(-1).withTitle("Gym"))).id
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(listOf("Adding “Gym” to Google"), mac.calendarEditLines.value.map { it.text })
        now += 10_000
        assertFalse(fold.undoEventEdit(again))

        // Offline keeps what was known; Stop editing takes the account away at once.
        server.offline = true
        assertTrue(fold.connectedAccounts().isEmpty())
        assertEquals(1, fold.calendarEditAccounts.value.size)
        server.offline = false
        fold.stopCalendarEditing("google", "meka@gmail.com")
        assertTrue(fold.calendarEditAccounts.value.isEmpty())
        assertTrue(fold.addEventForm(-1).accountKey == null)
    }

    @Test
    fun planMyDayCanAlsoPutItsBlocksInGoogleOnlyWhenTurnedOnAndShowsEachTaskOnce() = runTest {
        val server = EditingTransport(listOf(ConnectedAccount("google", "meka@gmail.com", "ok", null, canEdit = true)))
        val fold = core("android", server)
        val mac = core("mac")
        val taskId = fold.addTask("Write report")

        // Off by default: Apply plans the task in MEKA only, nothing goes to a calendar.
        fold.refreshCalendarAccounts()
        val off = fold.planCalendarSetting()
        assertTrue(off.available)
        assertFalse(off.on)
        assertEquals("Also add the blocks to Google", off.label)
        assertEquals("Apply plans the tasks in MEKA only", off.line)
        val first = fold.applyPlan(fold.planDay())
        assertTrue(first.editIds.isEmpty())
        assertNull(first.line)
        assertTrue(fold.calendarEditLines.value.isEmpty())

        // On (synced): the next Apply adds one block per task, with Undo.
        fold.schedule(taskId, null)
        val on = fold.setPlanToCalendar(true)
        assertTrue(on.on)
        assertEquals("Google · meka@gmail.com", on.accountLabel)
        val plan = fold.planDay()
        val applied = fold.applyPlan(plan)
        assertEquals(1, applied.editIds.size)
        assertEquals("Adding 1 block to Google", applied.line)
        assertEquals(listOf("Adding “Write report” to Google"), fold.calendarEditLines.value.map { it.text })
        // The task stands for that time: its block isn't a second row on Today or in the Calendar tab.
        assertTrue(fold.today.value.events.none { it.title == "Write report" })
        assertEquals(1, fold.calendarView.value.sections.flatMap { it.rows + it.ended }.count { it.title == "Write report" })
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertTrue(mac.today.value.events.none { it.title == "Write report" })
        assertTrue(mac.planCalendarSetting().on)
        // Undo takes the blocks back; the task stays planned.
        assertTrue(fold.undoPlanBlocks(applied.editIds))
        assertTrue(fold.calendarEditLines.value.isEmpty())
        assertTrue(fold.planDay().placements.none { it.task.id == taskId })
        assertEquals(1, plan.placements.size)
    }

    @Test
    fun aTasksBlockFollowsItWhileStillInItsFiveSecondsAndUndoAfterDeleteBringsItBack() = runTest {
        val server = EditingTransport(listOf(ConnectedAccount("google", "meka@gmail.com", "ok", null, canEdit = true)))
        val fold = core("android", server)
        val taskId = fold.addTask("Write report")
        fold.refreshCalendarAccounts()
        fold.setPlanToCalendar(true)
        val plan = fold.planDay()
        val applied = fold.applyPlan(plan)
        assertEquals(1, applied.editIds.size)
        val start = plan.placements.single().startMs

        // Moved inside the five seconds: the first add is taken back and one add goes where the task is now.
        fold.schedule(taskId, start + 3_600_000L)
        assertFalse(fold.undoPlanBlocks(applied.editIds))
        assertEquals(listOf("Adding “Write report” to Google"), fold.calendarEditLines.value.map { it.text })
        val calendarRows = { fold.activityView.value.days.flatMap { it.rows }.filter { it.kind == os.meka.core.domain.ActivityKind.CALENDAR } }
        assertEquals(1, calendarRows().size)
        assertTrue(fold.today.value.events.none { it.title == "Write report" })

        // Plan again: the block moves with it rather than a second one being added.
        val again = fold.applyPlan(fold.planDay())
        assertTrue(again.editIds.isEmpty())
        assertEquals(1, fold.calendarEditLines.value.size)

        // Deleted: nothing will be sent; Undo (restore) brings the block back.
        fold.delete(taskId)
        assertTrue(fold.calendarEditLines.value.isEmpty())
        fold.restore(taskId)
        assertEquals(listOf("Adding “Write report” to Google"), fold.calendarEditLines.value.map { it.text })

        // Done leaves it where it is.
        fold.complete(taskId)
        assertEquals(1, fold.calendarEditLines.value.size)
    }

    @Test
    fun theEventDetailOffersEditAndDeleteOnlyWhereEditingIsAllowed() = runTest {
        val server = EditingTransport(listOf(ConnectedAccount("google", "meka@gmail.com", "ok", null, canEdit = true)))
        val fold = core("android", server)
        val mac = core("mac")
        val start = now + 2 * 3_600_000L
        val dentist = os.meka.core.domain.CalendarEvent(
            "ev1", "Dentist", start, start + 3_600_000L, false, "High St", "google", "meka@gmail.com", "Personal",
        )
        val outlook = dentist.copy(id = "ev2", provider = "microsoft", account = "meka@outlook.com")
        assertFalse(fold.eventDetail(dentist).editable) // accounts not read yet
        fold.refreshCalendarAccounts()
        assertTrue(fold.eventDetail(dentist).editable)
        assertFalse(fold.eventDetail(outlook).editable)
        assertFalse(fold.eventDetail(dentist.copy(provider = "fixtures")).editable)

        val form = fold.editEventForm(dentist).stepTime(4)
        val v = fold.editEventView(dentist, form)
        assertEquals("Save to Google", v.addLabel)
        assertTrue(v.canAdd)
        val id = assertIs<os.meka.core.domain.EventEditResult.Made>(fold.saveEventEdit(dentist, form)).id
        assertEquals("Moving “Dentist” in Google", fold.eventEditLine(id))
        assertEquals("Moving “Dentist” in Google", fold.eventDetail(dentist).edit?.text)
        assertTrue(fold.undoEventEdit(id))
        assertNull(fold.eventDetail(dentist).edit)
        assertIs<os.meka.core.domain.EventEditResult.Refused>(fold.saveEventEdit(dentist, fold.editEventForm(dentist)))

        val del = assertIs<os.meka.core.domain.EventEditResult.Made>(fold.deleteEvent(dentist, guestsOk = false)).id
        assertEquals("Deleting “Dentist” from Google", fold.deletingLine(dentist))
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(del, mac.eventDetail(dentist).edit?.editId)
        assertIs<os.meka.core.domain.EventEditResult.Refused>(mac.deleteEvent(dentist, guestsOk = false)) // the Mac hasn't read its accounts
    }

    @Test
    fun anAddedEventShowsInTodayAndTheCalendarOnBothDevicesBeforeGoogleAnswers() = runTest {
        val server = EditingTransport(listOf(ConnectedAccount("google", "meka@gmail.com", "ok", null, canEdit = true)))
        val fold = core("android", server)
        val mac = core("mac")
        fold.refreshCalendarAccounts()
        val id = assertIs<os.meka.core.domain.EventEditResult.Made>(fold.addEvent(fold.addEventForm(-1).withTitle("Dentist"))).id
        fun shown(c: MekaCore) = c.calendarView.value.sections.flatMap { it.rows }.mapNotNull { it.event }.filter { it.isProvisional }
        fun onToday(c: MekaCore) = c.today.value.timeline.rows.mapNotNull { it.event }.filter { it.isProvisional }
        assertEquals(listOf("Dentist"), shown(fold).map { it.title })
        assertEquals(listOf(id), onToday(fold).map { it.pendingEditId })
        val detail = fold.eventDetail(shown(fold).single())
        assertTrue(detail.provisional)
        assertFalse(detail.editable)
        assertEquals("Adding “Dentist” to Google", detail.edit?.text)

        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(listOf("Dentist"), shown(mac).map { it.title })

        // Undone inside the five seconds: gone from both.
        assertTrue(fold.undoEventEdit(id))
        assertTrue(shown(fold).isEmpty() && onToday(fold).isEmpty())
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertTrue(shown(mac).isEmpty())
    }

    @Test
    fun aRenamedTaskRenamesItsBlockAndABlockStillOnItsWayFollowsAfterTheNextSyncOnThatDeviceOnly() = runTest {
        val server = EditingTransport(listOf(ConnectedAccount("google", "meka@gmail.com", "ok", null, canEdit = true)))
        val fold = core("android", server)
        val mac = core("mac")
        val taskId = fold.addTask("Write report")
        fold.refreshCalendarAccounts()
        fold.setPlanToCalendar(true)
        val plan = fold.planDay()
        val addId = fold.applyPlan(plan).editIds.single()
        val p = plan.placements.single()
        now += 10_000
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())

        // Google took it, but the mirror hasn't caught up: the rename can't be sent yet.
        val clock = os.meka.core.sync.HlcClock("server", { now })
        var k = 0
        fun serverWrite(type: String, id: String, fields: Map<String, os.meka.core.sync.FieldValue>) = fields.forEach { (f, v) ->
            serverOps.append(os.meka.core.sync.Op("srvren${k++}", "hh", type, id, f, v, clock.now(), emptyList(), "server"))
        }
        serverWrite(os.meka.core.domain.EntityTypes.EVENT_EDIT, addId, mapOf(
            os.meka.core.domain.EventEditFields.STATUS to os.meka.core.sync.FieldValue.Text("DONE"),
            os.meka.core.domain.EventEditFields.STATUS_AT to os.meka.core.sync.FieldValue.Int64(now),
        ))
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        now += 3 * 60_000L // past the two minutes "Added … to Google" stays
        fold.rename(taskId, "Write the Q3 report")
        assertTrue(fold.calendarEditLines.value.isEmpty())

        // The mirror has it now: after the next sync the Fold (where Meka renamed it) renames the block; the Mac doesn't.
        serverWrite(os.meka.core.domain.EntityTypes.EVENT, "g1", mapOf(
            os.meka.core.domain.EventFields.TITLE to os.meka.core.sync.FieldValue.Text("Write report"),
            os.meka.core.domain.EventFields.START_AT to os.meka.core.sync.FieldValue.Int64(p.startMs),
            os.meka.core.domain.EventFields.END_AT to os.meka.core.sync.FieldValue.Int64(p.endMs),
            os.meka.core.domain.EventFields.ALL_DAY to os.meka.core.sync.FieldValue.Bool(false),
            os.meka.core.domain.EventFields.PROVIDER to os.meka.core.sync.FieldValue.Text("google"),
            os.meka.core.domain.EventFields.ACCOUNT to os.meka.core.sync.FieldValue.Text("meka@gmail.com"),
            os.meka.core.domain.EventFields.CALENDAR to os.meka.core.sync.FieldValue.Text("meka@gmail.com"),
            os.meka.core.domain.EventFields.REMOVED to os.meka.core.sync.FieldValue.Bool(false),
        ))
        assertTrue(mac.syncNow())
        assertTrue(mac.calendarEditLines.value.isEmpty())
        assertTrue(fold.syncNow())
        assertEquals(listOf("Changing “Write the Q3 report” in Google"), fold.calendarEditLines.value.map { it.text })
        assertTrue(fold.today.value.events.none { it.title.startsWith("Write") })
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        assertEquals(1, mac.calendarEditLines.value.size) // the Fold's one change, nothing of the Mac's own

        // Renamed again while that change is in its five seconds: it is taken back and one rename goes.
        fold.rename(taskId, "Write the report")
        assertEquals(listOf("Changing “Write the report” in Google"), fold.calendarEditLines.value.map { it.text })
    }

    @Test
    fun aBlockStillOnItsWayFollowsAfterTheAppIsClosedAndOpenedAgain() = runTest {
        val server = EditingTransport(listOf(ConnectedAccount("google", "meka@gmail.com", "ok", null, canEdit = true)))
        val store = InMemoryReplicaStore()
        fun openMeka(seed: Int) = MekaCore(
            householdId = "hh", deviceId = "android", store = store, transport = server,
            secureRandom = Random(seed), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        val fold = openMeka(1)
        val mac = core("mac")
        val taskId = fold.addTask("Write report")
        fold.refreshCalendarAccounts()
        fold.setPlanToCalendar(true)
        val plan = fold.planDay()
        val addId = fold.applyPlan(plan).editIds.single()
        val p = plan.placements.single()
        now += 10_000
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())

        val clock = os.meka.core.sync.HlcClock("server", { now })
        var k = 0
        fun serverWrite(type: String, id: String, fields: Map<String, os.meka.core.sync.FieldValue>) = fields.forEach { (f, v) ->
            serverOps.append(os.meka.core.sync.Op("srvrst${k++}", "hh", type, id, f, v, clock.now(), emptyList(), "server"))
        }
        serverWrite(os.meka.core.domain.EntityTypes.EVENT_EDIT, addId, mapOf(
            os.meka.core.domain.EventEditFields.STATUS to os.meka.core.sync.FieldValue.Text("DONE"),
            os.meka.core.domain.EventEditFields.STATUS_AT to os.meka.core.sync.FieldValue.Int64(now),
        ))
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        now += 3 * 60_000L
        fold.rename(taskId, "Write the Q3 report")
        assertTrue(fold.calendarEditLines.value.isEmpty())
        assertTrue(fold.syncNow())

        // Meka closes MEKA before the mirror has the block; the wait was kept on the Fold only.
        fold.close()
        assertTrue(store.localValue("plan.waitingFollows")!!.contains(taskId))
        now += 60_000L
        serverWrite(os.meka.core.domain.EntityTypes.EVENT, "g1", mapOf(
            os.meka.core.domain.EventFields.TITLE to os.meka.core.sync.FieldValue.Text("Write report"),
            os.meka.core.domain.EventFields.START_AT to os.meka.core.sync.FieldValue.Int64(p.startMs),
            os.meka.core.domain.EventFields.END_AT to os.meka.core.sync.FieldValue.Int64(p.endMs),
            os.meka.core.domain.EventFields.ALL_DAY to os.meka.core.sync.FieldValue.Bool(false),
            os.meka.core.domain.EventFields.PROVIDER to os.meka.core.sync.FieldValue.Text("google"),
            os.meka.core.domain.EventFields.ACCOUNT to os.meka.core.sync.FieldValue.Text("meka@gmail.com"),
            os.meka.core.domain.EventFields.CALENDAR to os.meka.core.sync.FieldValue.Text("meka@gmail.com"),
            os.meka.core.domain.EventFields.REMOVED to os.meka.core.sync.FieldValue.Bool(false),
        ))
        assertTrue(mac.syncNow())
        assertTrue(mac.calendarEditLines.value.isEmpty())

        // Opened again: the first sync that brings the block renames it, and the wait is gone.
        val reopened = openMeka(2)
        assertTrue(reopened.syncNow())
        assertEquals(listOf("Changing “Write the Q3 report” in Google"), reopened.calendarEditLines.value.map { it.text })
        assertEquals(null, store.localValue("plan.waitingFollows"))
        reopened.close()
    }

    @Test
    fun aWaitThatOutlivedItsHalfHourIsDroppedAfterOpeningAgain() = runTest {
        val store = InMemoryReplicaStore()
        val w = os.meka.core.domain.WaitingFollow("t1", true, now, "Write report", os.meka.core.domain.Lifecycle.ACTIVE, now)
        store.transaction { store.setLocalValue("plan.waitingFollows", WaitingFollowCodec.encode(listOf(w))) }
        val c = MekaCore("hh", "android", store, FaultyTransport(service), Random(3), { TimeZone.of("Europe/London") }, { now })
        now += os.meka.core.domain.PlanCalendarRules.WAIT_FOLLOW_MS + 1
        assertTrue(c.syncNow())
        assertEquals(null, store.localValue("plan.waitingFollows"))
        c.close()
    }
}
