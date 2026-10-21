@preconcurrency import MekaKit
import XCTest

/// Proves the Kotlin facade is usable from Swift: commands are async, flows are AsyncSequences.
final class MekaCoreBridgeTests: XCTestCase {
    /// Spike S6 (ADR-002): the app's SQLite is SQLCipher, and a keyed database file is really encrypted.
    func testDatabaseEngineIsSQLCipher() {
        let dir = (NSTemporaryDirectory() as NSString).appendingPathComponent("s6-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(atPath: dir) }
        let probe = MacCoreFactory.shared.databaseEncryptionProbe(directory: dir)
        let version = probe.cipherVersion
        XCTAssertNotNil(version, "MekaKit's sqlite3 calls bound to the system SQLite, not SQLCipher")
        XCTAssertTrue(version?.hasPrefix("4.") ?? false, "cipher_version: \(version ?? "nil")")
        XCTAssertTrue(probe.opensWithKey, "an encrypted database didn't reopen with its key")
        XCTAssertTrue(probe.fileUnreadable, "the keyed database file still has a plain SQLite header")
        XCTAssertTrue(probe.refusedWithoutKey, "the keyed database opened without its key or with the wrong one")
    }

    func testAddAndCompleteThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "bridge-\(UUID().uuidString).db", deviceKey: nil
        )
        let id = try await core.addTask(title: "Bridge works")
        var iterator = core.today.makeAsyncIterator()
        let today = await iterator.next()
        XCTAssertEqual(today?.upNext?.id, id)
        try await core.complete(taskId: id)
        XCTAssertTrue(core.today.value.isClear)
        XCTAssertTrue(core.today.value.isAllClear)
    }

    /// Alarms, slice 1: the wake alarm and the next alarm (a nullable flow) through the bridge.
    func testWakeAlarmThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "alarm-\(UUID().uuidString).db", deviceKey: nil
        )
        XCTAssertNil(core.nextAlarm.value)
        XCTAssertFalse(core.wakeView.value.isSet)
        // 23:55 is always still ahead on the wake day (tomorrow, or today before 04:00).
        let ok = try await core.setWake(minute: 23 * 60 + 55)
        XCTAssertTrue(ok.boolValue)
        XCTAssertTrue(core.wakeView.value.isSet)
        XCTAssertEqual(core.wakeView.value.timeLabel, "23:55")
        let ring = try XCTUnwrap(core.nextAlarm.value)
        XCTAssertEqual(ring.timeLabel, "23:55")
        XCTAssertNil(core.ringingAlarm())
        XCTAssertEqual(AlarmRules.shared.bufferLine(min: 75), "1 h 15 to get ready")
        try await core.wakeOff()
        XCTAssertNil(core.nextAlarm.value)
    }

    func testQuickTimerFromCaptureThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "timer-\(UUID().uuidString).db", deviceKey: nil
        )
        let outcome = try await core.captureTyped(text: "timer 20 min pasta")
        guard case .alarmSet(let set) = onEnum(of: outcome) else { return XCTFail("expected a timer") }
        let alarmID = set.alarmId
        XCTAssertTrue(set.line.hasPrefix("Timer set · 20 min · ends "))
        XCTAssertEqual(core.quickAlarms.value.map(\.title), ["pasta"])
        XCTAssertEqual(core.quickAlarms.value.first?.kind, .timer)
        XCTAssertEqual(core.nextAlarm.value?.timeLabel, "20 min")
        XCTAssertEqual(core.nextAlarm.value?.opensBrief, false)
        XCTAssertTrue(alarmID.hasPrefix("timer."))
        let cancelled = try await core.cancelAlarm(id: alarmID)
        XCTAssertTrue(cancelled.boolValue)
        XCTAssertTrue(core.quickAlarms.value.isEmpty)
        // Anything else is a task.
        let task = try await core.captureTyped(text: "Book dentist")
        if case .taskAdded = onEnum(of: task) {} else { XCTFail("expected a task") }
    }

    func testLeaveByRingsAsAnAlarmThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "leave-\(UUID().uuidString).db", deviceKey: nil
        )
        try await core.setEventLeaveBy(eventId: "ev2", travelMinutes: 30)
        try await core.setEventLeaveAlarm(eventId: "ev2", on: true)
        XCTAssertTrue(core.eventMarks.value.leaveRingsOf(eventId: "ev2"))
        XCTAssertEqual(core.eventMarks.value.travelOf(eventId: "ev2"), 30)
        XCTAssertEqual(LeaveAlarmRules.shared.SWITCH_LABEL, "Ring as an alarm")
        XCTAssertEqual(LeaveAlarmRules.shared.id(eventId: "ev2", startAtMs: 1_000, travelMin: 30), "leave.eev2.s1000.t30")
        try await core.setEventLeaveAlarm(eventId: "ev2", on: false)
        XCTAssertFalse(core.eventMarks.value.leaveRingsOf(eventId: "ev2"))
    }

    /// Inbox fixes, slice 7: Today's unread mail line reaches Swift (nothing counted yet: no line).
    func testMailUnreadLineReachesSwift() {
        XCTAssertNil(MailUnreadRules.shared.todayLine(backlogs: [], nowMs: 0))
        XCTAssertEqual(MailUnreadRules.shared.NONE_UNREAD, "Mail · nothing unread ✓")
    }

    func testRepeatingTaskThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "repeat-\(UUID().uuidString).db", deviceKey: nil
        )
        let id = try await core.addTask(title: "Vitamins")
        let choices = try await core.repeatChoices(taskId: id)
        XCTAssertEqual(choices.first?.label, "Doesn't repeat")
        let daily = try XCTUnwrap(choices.first { $0.label == "Every day" })
        try await core.setRepeat(taskId: id, rule: daily.rule)
        _ = try await core.addStep(taskId: id, text: "With water")
        try await core.complete(taskId: id)
        // Tomorrow's occurrence waits for its day, so today is clear.
        XCTAssertTrue(core.today.value.isClear)
    }

    func testListsThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "lists-\(UUID().uuidString).db", deviceKey: nil
        )
        _ = try await core.addWaiting(title: "Refund", who: "Shop", chaseInDays: KotlinInt(int: 0))
        _ = try await core.addSomeday(title: "Lisbon", kind: .trip)
        _ = try await core.recordDecision(statement: "No new car", rationale: nil, reviewInDays: nil)
        let lists = core.listsView.value
        XCTAssertEqual(lists.waiting.first?.state, .due)
        XCTAssertEqual(lists.dueLine, "1 to chase")
        XCTAssertEqual(lists.someday.first?.label, ListRules.shared.kindLabel(k: .trip))
        XCTAssertEqual(lists.decisions.first?.statement, "No new car")
        XCTAssertEqual(ListRules.shared.CHASE_CHOICES.last?.days, nil)
    }

    func testPromisesReachSwift() async throws {
        // People, slice 1: a promise made through the facade (only Strings and an Int cross), due today, shows in the
        // Promises tab beside Waiting for; Kept clears it.
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "promises-\(UUID().uuidString).db", deviceKey: nil
        )
        let id = try await core.addPromise(title: "Fix the bike", to: "Theo", byInDays: KotlinInt(int: 0))
        _ = try await core.addWaiting(title: "Refund", who: "Shop", chaseInDays: nil)
        let lists = core.listsView.value
        XCTAssertEqual(lists.promised.map(\.meta), ["Theo · due today"])
        XCTAssertEqual(lists.promised.first?.state, .due)
        XCTAssertEqual(lists.promisesBothWays, 2)
        XCTAssertEqual(lists.dueLine, "1 promise due")
        XCTAssertEqual(ListRules.shared.PROMISE_CHOICES.first?.label, "By today")
        XCTAssertEqual(NoticeSource.promise.label, "Promises you made")
        try await core.keptPromise(id: id)
        XCTAssertTrue(core.listsView.value.promised.isEmpty)
        XCTAssertEqual(core.listsView.value.waiting.count, 1)
    }

    func testPeopleReachSwift() async throws {
        // People, slice 2: a promise each way to the same person makes one People row on the Mac with what's owed both
        // ways; Kept on the row's promise (only the id String crosses) leaves only the waiting-for.
        XCTAssertTrue(PeopleView.companion.EMPTY.isEmpty)
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "people-\(UUID().uuidString).db", deviceKey: nil
        )
        let id = try await core.addPromise(title: "Fix the bike", to: "Theo", byInDays: KotlinInt(int: 0))
        _ = try await core.addWaiting(title: "Football photos", who: "theo", chaseInDays: nil)
        let row = try XCTUnwrap(core.peopleView.value.rows.first)
        XCTAssertEqual(core.peopleView.value.rows.count, 1)
        XCTAssertEqual(row.name, "Theo")
        XCTAssertEqual(row.owedLine, "You promised 1 · Waiting for 1")
        XCTAssertTrue(row.lit)
        XCTAssertEqual(row.promised.map(\.id), [id])
        XCTAssertEqual(core.peopleView.value.summary, "1 person · 1 promise due")
        try await core.keptPromise(id: id)
        XCTAssertEqual(core.peopleView.value.rows.first?.owedLine, "Waiting for 1")
    }

    func testRenewalsThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "renewals-\(UUID().uuidString).db", deviceKey: nil
        )
        let today = core.todayEpochDay()
        let monthly = RenewalRules.shared.repeatAt(index: 1)
        XCTAssertEqual(RenewalRules.shared.repeatLabel(r: monthly), "Every month")
        XCTAssertNil(RenewalRules.shared.costError(text: "10.99"))
        XCTAssertNotNil(RenewalRules.shared.costError(text: "ten"))
        let id = try await core.addRenewal(title: "Netflix", kind: RenewalRules.shared.kindAt(index: 4), dueDay: today + 2, repeats: monthly,
                                           cost: "10.99", cancelByDaysBefore: nil)
        let r = core.listsView.value.renewals
        XCTAssertEqual(r.attention.first?.id, id)
        XCTAssertEqual(r.attention.first?.repeats, monthly)
        XCTAssertEqual(core.listsView.value.dueLine, "1 renewal due")
        try await core.renewalDone(id: id)
        XCTAssertNil(core.listsView.value.dueLine)
    }

    func testGoalsAndHabitsThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "goals-\(UUID().uuidString).db", deviceKey: nil
        )
        let goal = try await core.addGoal(title: "Fitter by spring", target: nil, horizon: GoalRules.shared.horizonAt(index: 1))
        let habit = try await core.addHabit(title: "Stretch", perWeek: 7, timing: .morning, minutes: 15, goalId: goal)
        XCTAssertEqual(core.goalsView.value.habits.first?.pace, .due)
        try await core.setHabitDone(id: habit, done: true)
        let view = core.goalsView.value
        XCTAssertEqual(view.habits.first?.doneToday, true)
        XCTAssertEqual(view.habits.first?.week.filter { $0.boolValue }.count, 1)
        XCTAssertEqual(view.goals.first?.counted, true)
        XCTAssertEqual(GoalRules.shared.timingLabel(t: .morning), "Morning")
        let plan = try await core.planDay()
        XCTAssertTrue(plan.habits.isEmpty)
    }

    /// Goals, properly, slice 1: a month goal under a year one, a step that becomes the next step, a due day.
    func testGoalsNestAndStepsReachSwift() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "goals2-\(UUID().uuidString).db", deviceKey: nil
        )
        XCTAssertEqual(GoalRules.shared.HORIZONS.count, 5)
        XCTAssertEqual(GoalRules.shared.horizonLabel(h: GoalRules.shared.horizonAt(index: 3)), "This year")
        let year = try await core.addGoal(title: "Run a marathon", target: nil, horizon: GoalRules.shared.horizonAt(index: 3))
        let month = try await core.addGoalUnder(parentId: year, title: "Run 5k", horizon: GoalRules.shared.horizonAt(index: 1))
        _ = try await core.addGoalStep(goalId: month, title: "Buy running shoes")
        let day = GoalRules.shared.dueChoices(today: core.todayEpochDay())[2].day
        try await core.setGoalDue(id: month, dueDay: day)
        try await core.setGoalWhy(id: year, why: "Feel fit at 40")
        let goals = core.goalsView.value.goals
        XCTAssertEqual(goals.map(\.id), [year, month])
        XCTAssertEqual(goals[1].depth, 1)
        XCTAssertEqual(goals[1].parentTitle, "Run a marathon")
        XCTAssertEqual(goals[1].nextStep, "Buy running shoes")
        XCTAssertEqual(goals[1].dueDay, day)
        XCTAssertEqual(goals[0].why, "Feel fit at 40")
        XCTAssertEqual(goals[0].childCount, 1)
        let found = try await core.goalLinkCandidates(goalId: year, query: "shoes")
        XCTAssertEqual(found.map(\.title), ["Buy running shoes"])
    }

    func testGoalMilestonesReachSwift() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "goals3-\(UUID().uuidString).db", deviceKey: nil
        )
        let goal = try await core.addGoal(title: "Run a half marathon", target: "Under 2 hours", horizon: GoalRules.shared.horizonAt(index: 2))
        let five = try await core.addGoalMilestone(goalId: goal, title: "Run 5k")
        _ = try await core.addGoalMilestone(goalId: goal, title: "Run 10k")
        try await core.setGoalMilestoneDone(id: five, done: true)
        let g = core.goalsView.value.goals[0]
        XCTAssertEqual(g.milestones.map(\.title), ["Run 5k", "Run 10k"])
        XCTAssertEqual(g.milestonesDone, 1)
        XCTAssertEqual(g.progressPct, 50)
        XCTAssertEqual(g.nextLine, "Next milestone · Run 10k")
        XCTAssertEqual(GoalRules.shared.ringSweep(pct: 50), 180)
        let removed = try await core.removeGoalMilestone(id: five)
        XCTAssertEqual(removed, "Run 5k")
    }

    func testGoalNumbersReachSwift() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "goals4-\(UUID().uuidString).db", deviceKey: nil
        )
        let goal = try await core.addGoal(title: "Save £2,000", target: nil, horizon: GoalRules.shared.horizonAt(index: 3))
        let bad = try await core.setGoalNumber(goalId: goal, text: "lots")
        XCTAssertFalse(bad.boolValue)
        let ok = try await core.setGoalNumber(goalId: goal, text: "£2,000")
        XCTAssertTrue(ok.boolValue)
        let first = try await core.addGoalUpdate(goalId: goal, text: "+500")
        XCTAssertNotNil(first)
        _ = try await core.addGoalUpdate(goalId: goal, text: "+300")
        let g = core.goalsView.value.goals[0]
        XCTAssertEqual(g.metric?.line, "£800 of £2,000")
        XCTAssertEqual(g.progressPct, 40)
        XCTAssertEqual(g.metric?.recent.count, 2)
        let plot = GoalMetricRules.shared.plot(m: g.metric!, today: core.todayEpochDay())
        XCTAssertEqual(plot.line.last?.y, 0.4)
        XCTAssertEqual(GoalMetricRules.shared.suggestFromTitle(title: "Read 12 books"), "12 books")
        let removed = try await core.removeGoalUpdate(id: first!)
        XCTAssertTrue(removed.boolValue)
        XCTAssertEqual(core.goalsView.value.goals[0].metric?.line, "£300 of £2,000")
    }

    func testWeekGoalsReachSwift() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "goals7-\(UUID().uuidString).db", deviceKey: nil
        )
        XCTAssertNil(core.goalsView.value.week.line)
        let goal = try await core.addGoal(title: "Put £50 away", target: nil, horizon: GoalRules.shared.horizonAt(index: 0))
        XCTAssertEqual(core.goalsView.value.week.line, "This week: “Put £50 away” not moving yet")
        _ = try await core.setGoalNumber(goalId: goal, text: "£50")
        _ = try await core.addGoalUpdate(goalId: goal, text: "+20")
        let week = core.goalsView.value.week
        XCTAssertEqual(week.line, "This week: “Put £50 away” is moving")
        XCTAssertEqual(week.arcs.count, 1)
        XCTAssertEqual(week.arcs[0].pct, 40)
        XCTAssertEqual(GoalWeekRules.shared.fillAlpha(arc: week.arcs[0]), GoalWeekRules.shared.MOVING_ALPHA)
    }

    func testGoalRhythmReachesSwift() async throws {
        // Goals, properly, item 6: the quiet label, the month's review (none for a fresh core) and the notices' source.
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "goals6-\(UUID().uuidString).db", deviceKey: nil
        )
        _ = try await core.addGoal(title: "Run 5k", target: nil, horizon: GoalRules.shared.horizonAt(index: 1))
        XCTAssertNil(core.goalsView.value.month)
        let rules = GoalRhythmRules.shared
        XCTAssertNil(rules.quietLabel(days: KotlinLong(value: 13)))
        XCTAssertEqual(rules.quietLabel(days: KotlinLong(value: 14)), "quiet for 2 weeks")
        XCTAssertEqual(
            rules.previousMonthStart(day: CivilDate.shared.toEpochDay(year: 2026, month: 11, day: 1)),
            CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 1)
        )
        XCTAssertEqual(NoticeSource.goals.label, "Goals: quiet goals and the month's review")
    }

    func testLeavingNowReachesSwift() async throws {
        // "Leaving now": the synced setting, its line, and the Mac's buttons (Copy only; Send and Edit are the Fold's).
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "leaving-\(UUID().uuidString).db", deviceKey: nil
        )
        // Rule 13: no partner in About you, no one to send it to (the name lives only in his data).
        XCTAssertEqual(core.leavingView.value.to, "")
        XCTAssertEqual(core.leavingView.value.summary, LeavingRules.shared.NO_ONE)
        let line = try await core.setLeavingNow(on: true, to: "Wife", journeyMin: 80)
        XCTAssertEqual(line, "On · a draft to Wife when work ends on office days · about 1 h 20 home")
        XCTAssertEqual(core.leavingView.value.journeyMin, 80)
        XCTAssertEqual(NoticeSource.leaving.defaultTier, NoticeTier.headsUp)
        XCTAssertEqual(NotifyRules.shared.actionName(a: NoticeAction.copyMessage), "COPY_MESSAGE") // MacNotifier.copyMessageAction
        XCTAssertTrue(NoticeAction.copyMessage.shownOn(send: false))
        XCTAssertFalse(NoticeAction.sendMessage.shownOn(send: false))
        XCTAssertEqual(LeavingRules.shared.journeyLabel(min: 90), "1 h 30")
        // The commute counts everywhere, slice 4: the journey home is the commute home.
        XCTAssertEqual(LeavingRules.shared.journeyNote(journeyMin: 80), "Journey home: 1 h 20 · your commute home, set under Commute")
    }

    func testCommuteReachesSwift() async throws {
        // The commute counts everywhere, slice 1: the synced setting on the work state, its line and the chips.
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "commute-\(UUID().uuidString).db", deviceKey: nil
        )
        let rules = CommuteRules.shared
        XCTAssertEqual(rules.summary(s: CommuteSetting.companion.DEFAULT), "About 1 h 20 each way · change it below")
        let changed = try await core.setCommute(toWorkMin: 90, homeMin: 70)
        XCTAssertTrue(changed.boolValue)
        XCTAssertEqual(core.workMode.value.commute.toWorkMin, 90)
        XCTAssertTrue(core.workMode.value.commute.chosen)
        XCTAssertEqual(rules.summary(s: core.workMode.value.commute), "1 h 30 there · 1 h 10 home")
        XCTAssertEqual(rules.choices(current: 75).map { Int($0.int32Value) }, [45, 60, 70, 75, 80, 90, 100, 120])
        XCTAssertEqual(rules.label(min: 80), "1 h 20")
        // Slice 2: Today's commute rows and the Calendar's line.
        XCTAssertEqual(TimelineKind.commute.name, "COMMUTE")
        XCTAssertEqual(rules.homeLine(minute: 18 * 60 + 50), "Home about 18:50")
        XCTAssertEqual(rules.HOME_TITLE, "Commute home")
        // Slice 6: one day's own times ("Just one day") on the work state's rows.
        let rows = core.workMode.value.commuteDays
        XCTAssertFalse(rows.isEmpty)
        let day = rows[0].epochDay
        let set = try await core.setCommuteDay(epochDay: day, toWorkMin: 105, homeMin: 70)
        XCTAssertTrue(set.boolValue)
        let row = core.workMode.value.commuteDays.first { $0.epochDay == day }
        XCTAssertEqual(row?.changed, true)
        XCTAssertEqual(row?.toWorkMin, 105)
        XCTAssertTrue(row?.line.hasSuffix(rules.JUST_THIS_DAY) ?? false)
        let cleared = try await core.clearCommuteDay(epochDay: day)
        XCTAssertTrue(cleared.boolValue)
        XCTAssertEqual(core.workMode.value.commuteDays.first { $0.epochDay == day }?.changed, false)
    }

    func testWeekAheadReachesSwift() async throws {
        // The Sunday week ahead: the view, its days and lines, and Sunday's heads-up source.
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "weekahead-\(UUID().uuidString).db", deviceKey: nil
        )
        let v = core.weekAheadView.value
        XCTAssertFalse(v.summary.isEmpty)
        XCTAssertTrue(v.title == "This week" || v.title == "Next week")
        XCTAssertFalse(v.days.isEmpty)
        for d in v.days { for l in d.lines { XCTAssertFalse(l.text.isEmpty) } }
        XCTAssertEqual(NoticeSource.weekAhead.defaultTier, NoticeTier.headsUp)
        XCTAssertEqual(NotifyRules.shared.targetName(t: NoticeTarget.weekAhead), "WEEK_AHEAD")
        // Sunday 11 Oct 2026 looks at the week from Monday 12 Oct.
        XCTAssertEqual(WeekAheadRules.shared.weekStart(today: 20737), 20738)
    }

    func testWeekAheadSettingReachesSwift() async throws {
        // Sunday week ahead, slice 2: the heads-up's time setting and the Listen button's name.
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "weekahead2-\(UUID().uuidString).db", deviceKey: nil
        )
        XCTAssertEqual(core.weekAheadSettingView.value.timeLabel, "18:00")
        let line = try await core.setWeekAhead(on: true, minute: 1140)
        XCTAssertEqual(line, "On · Sundays at 19:00 · Listen reads it out")
        XCTAssertEqual(core.weekAheadSettingView.value.minute, 1140)
        XCTAssertTrue(core.weekAheadSettingView.value.choices.map { $0.int32Value }.contains(1140))
        XCTAssertEqual(NotifyRules.shared.actionName(a: NoticeAction.listen), "LISTEN")
        XCTAssertEqual(NotifyRules.shared.actionLabel(a: NoticeAction.listen), "Listen")
    }

    func testWeekGoalsPickReachesSwift() async throws {
        // Goals, properly, item 6: the week ahead's pick of up to three goals.
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "weekgoals-\(UUID().uuidString).db", deviceKey: nil
        )
        let goal = try await core.addGoal(title: "Run 5k", target: nil, horizon: GoalRules.shared.horizonAt(index: 1))
        XCTAssertEqual(core.weekAheadView.value.goals.goals.map(\.goalId), [goal])
        XCTAssertEqual(core.weekAheadView.value.goals.line, "Pick up to 3 for the week")
        let line = try await core.pickWeekGoal(goalId: goal, on: true)
        XCTAssertNotNil(line)
        XCTAssertTrue(core.weekAheadView.value.goals.goals[0].picked)
        XCTAssertEqual(core.weekAheadView.value.goals.picked, 1)
        XCTAssertFalse(core.weekAheadView.value.goals.full)
        XCTAssertEqual(GoalWeekRules.shared.MAX_PICKS, 3)
    }

    func testGoalBreakdownReachesSwift() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "goals5-\(UUID().uuidString).db", deviceKey: nil
        )
        let goal = try await core.addGoal(title: "Run a half marathon", target: nil, horizon: GoalRules.shared.horizonAt(index: 3))
        // Not connected: nothing goes out, the line says why.
        let out = try await core.breakDownGoal(goalId: goal)
        switch onEnum(of: out) {
        case .unavailable(let u): XCTAssertEqual(u.line, GoalBreakdownRules.shared.NOT_CONNECTED_LINE)
        default: XCTFail("expected unavailable")
        }
        // What Meka ticked is kept; Undo takes it back.
        let kept = try await core.keepBreakdown(goalId: goal, milestones: ["Run 10k", "Run 15k"], steps: ["Book a gait analysis"])
        XCTAssertEqual(kept.line, "Added 2 milestones and 1 step")
        XCTAssertFalse(kept.isEmpty)
        XCTAssertEqual(core.goalsView.value.goals[0].milestones.map(\.title), ["Run 10k", "Run 15k"])
        XCTAssertEqual(core.goalsView.value.goals[0].nextStep, "Book a gait analysis")
        try await core.undoBreakdown(kept: kept)
        XCTAssertTrue(core.goalsView.value.goals[0].milestones.isEmpty)
        XCTAssertEqual(GoalBreakdownRules.shared.keepLabel(ticked: 2), "Keep 2")
    }

    func testFastingThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "fasting-\(UUID().uuidString).db", deviceKey: nil
        )
        XCTAssertNil(core.fastingView.value.current)
        try await core.chooseFastingPlan(index: 1)
        _ = try await core.startFast(startedMinutesAgo: 60)
        let cur = try XCTUnwrap(core.fastingView.value.current)
        XCTAssertEqual(cur.targetHours, 16)
        XCTAssertFalse(cur.reachedGoal)
        XCTAssertEqual(FastingRules.shared.clock(elapsedMs: 3_600_000), "1:00:00")
        XCTAssertEqual(FastingRules.shared.planLabel(p: core.fastingView.value.plan), "16:8")
        // Outside the app: the running fast is what the menu bar shows beside the mark.
        let ongoing = core.ongoing()
        XCTAssertEqual(ongoing.items.last?.kind, .fast)
        XCTAssertEqual(ongoing.items.last?.progressPercent?.intValue, 6)
        XCTAssertNotNil(ongoing.menuBar)
        try await core.endFast()
        XCTAssertTrue(core.ongoing().items.filter { $0.kind == .fast }.isEmpty)
        XCTAssertNil(core.fastingView.value.current)
        XCTAssertEqual(core.fastingView.value.last?.canResume, true)
        XCTAssertEqual(core.fastingView.value.week.count, 7)
    }

    func testShutdownThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "shutdown-\(UUID().uuidString).db", deviceKey: nil
        )
        _ = try await core.addTask(title: "Post the letter")
        let id = try await core.addTask(title: "Call the garage")
        try await core.complete(taskId: id)
        var v = core.shutdownView.value
        XCTAssertEqual(v.left.map { $0.task.title }, ["Post the letter"])
        XCTAssertEqual(v.doneCount, 1)
        // Done for the day: what got done reaches the Mac with its time.
        XCTAssertEqual(v.done.map(\.title), ["Call the garage"])
        XCTAssertNil(v.doneMore)
        XCTAssertTrue(v.left.first?.canSomeday == true)
        try await core.carryAllToTomorrow()
        v = core.shutdownView.value
        XCTAssertTrue(v.left.isEmpty)
        XCTAssertEqual(v.tomorrow.rows.map(\.title), ["Post the letter"])
        XCTAssertEqual(v.tomorrow.glance, "Tomorrow: 1 task")
        try await core.shutDown()
        XCTAssertTrue(core.shutdownView.value.doneToday)
        XCTAssertFalse(core.shutdownView.value.offered)
        // The watch face dims to night once the day is shut down.
        XCTAssertTrue(core.shutdownView.value.night)
        XCTAssertEqual(WatchFaceRules.shared.nightAlpha(night: true), WatchFaceRules.shared.NIGHT_ALPHA)
        XCTAssertEqual(WatchFaceRules.shared.liveMode(reduced: false, powerSave: false, bedside: false, quiet: false, night: true), .minute)
    }

    func testSearchThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "search-\(UUID().uuidString).db", deviceKey: nil
        )
        let id = try await core.addTask(title: "Renew passport")
        _ = try await core.addWaiting(title: "Passport photos", who: "Snappy Snaps", chaseInDays: KotlinInt(int: 3))
        try await core.search(query: "passp")
        let v = core.searchView.value
        XCTAssertEqual(v.groups.map(\.label), ["Tasks", "Waiting for"])
        XCTAssertEqual(v.summary, "2 matches")
        let task = try XCTUnwrap(v.groups.first?.hits.first)
        XCTAssertEqual(task.target, .task)
        XCTAssertEqual(task.task?.id, id)
        XCTAssertEqual(v.groups.last?.hits.first?.target, .listsWaiting)
        // People, slice 3b: a promise is found by its title or the person, in its own group before Waiting for.
        _ = try await core.addPromise(title: "Send the passport form", to: "Jordan", byInDays: KotlinInt(int: 0))
        try await core.search(query: "passp")
        XCTAssertEqual(core.searchView.value.groups.map(\.label), ["Tasks", "You promised", "Waiting for"])
        try await core.search(query: "jord")
        let promised = try XCTUnwrap(core.searchView.value.groups.first?.hits.first)
        XCTAssertEqual(promised.kind, .promised)
        XCTAssertEqual(promised.detail, "Jordan · due today")
        XCTAssertEqual(promised.target, .listsWaiting)
        try await core.search(query: "")
        XCTAssertFalse(core.searchView.value.active)
    }

    func testMorningBriefThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "brief-\(UUID().uuidString).db", deviceKey: nil
        )
        _ = try await core.addTask(title: "Post the letter")
        _ = try await core.addWaiting(title: "Deposit back", who: "Landlord", chaseInDays: KotlinInt(int: 0))
        try await core.tick()
        let v = core.briefView.value
        XCTAssertEqual(v.day.map(\.title), ["Post the letter"])
        XCTAssertEqual(v.waitingLine, "Waiting on 1 thing · 1 to chase today")
        XCTAssertEqual(v.waiting.first?.state, .due)
        XCTAssertFalse(v.seenToday)
        // Calm Today: a glance never counts as read; open long enough, it does whenever the card is offered.
        XCTAssertEqual(BriefRules.shared.READ_AFTER_MS, 5000)
        let glanced = try await core.briefLookedAt(on: "Mac", openForMs: 1200)
        XCTAssertFalse(glanced.boolValue)
        let offered = core.briefView.value.offered
        let read = try await core.briefLookedAt(on: "Mac", openForMs: 9000)
        XCTAssertEqual(read.boolValue, offered)
        if offered { XCTAssertFalse(core.briefView.value.offered) }
        try await core.briefSeen(on: "Mac")
        XCTAssertTrue(core.briefView.value.seenToday)
        XCTAssertFalse(core.briefView.value.offered)

        // News topics: Barça, AI, Top stories and World by default; a topic toggles and stays chosen.
        XCTAssertEqual(core.briefView.value.newsTopics.filter(\.chosen).map(\.id), ["barca", "ai", "top", "world"])
        XCTAssertTrue(core.briefView.value.headlines.isEmpty)
        try await core.setNewsTopic(topicId: "technology", on: true)
        XCTAssertEqual(core.briefView.value.newsTopics.filter(\.chosen).map(\.id), ["barca", "ai", "top", "world", "technology"])
        // The News place: the same choice, no headlines yet, and a line saying when they come.
        let place = core.newsPlace.value
        XCTAssertTrue(place.lanes.isEmpty)
        XCTAssertEqual(place.emptyLine, "No headlines in the last two days · they refresh every hour")
        XCTAssertEqual(place.topics.filter(\.chosen).map(\.id), ["barca", "ai", "top", "world", "technology"])
        // English only by default (Fold review 2026-10-09 07:26); the Spanish sources switch reaches Swift and syncs.
        XCTAssertFalse(place.spanishSources)
        XCTAssertEqual(place.spanishLine, "Off · English only")
        try await core.setNewsSpanish(on: true)
        XCTAssertTrue(core.newsPlace.value.spanishSources)
        XCTAssertTrue(core.newsPlace.value.sourcesCaption.contains("Mundo Deportivo"))
        try await core.setNewsSpanish(on: false)
        XCTAssertFalse(core.newsPlace.value.spanishSources)
        // Slice 2: no fixture today, so no matchday line; nothing to show under Coming up either.
        XCTAssertNil(place.matchday)
        XCTAssertNil(CommandCentreRules.shared.newsGlance(place: place, maxItems: CommandCentreRules.shared.NEWS_ALONE))
        // Pictures (images slice): with no server there is none, and only server keys are ever asked for.
        let none = try await core.newsImageBase64(key: "0123456789abcdef0123456789abcdef")
        XCTAssertNil(none)
        let notAKey = try await core.newsImageBase64(key: "https://img.example/a.jpg")
        XCTAssertNil(notAKey)
        XCTAssertEqual(NewsRules.shared.tileInitial(source: "Mundo Deportivo"), "M")
        XCTAssertEqual(NewsRules.shared.tileMark(source: "Mundo Deportivo"), "MD")
        XCTAssertEqual(NewsRules.shared.tileMark(source: "BBC News"), "BBC")
        XCTAssertEqual(TickerRules.shared.edgeFadeFraction(widthDp: 480), 0.1, accuracy: 0.0001)
        // The news ticker (slice 2): nothing to show, so no strip; calm by default, rests after two loops.
        let ticker = TickerRules.shared.ticker(place: place)
        XCTAssertTrue(ticker.isEmpty)
        XCTAssertFalse(TickerRules.shared.shown(mode: TickerMode.calm, ticker: ticker))
        XCTAssertEqual(TickerRules.shared.mode(id: "nonsense"), TickerMode.calm)
        XCTAssertEqual([TickerMode.calm, TickerMode.moving, TickerMode.off].map(\.label), ["Calm", "Always moving", "Off"])
        XCTAssertFalse(TickerRules.shared.moving(mode: TickerMode.calm, reducedMotion: false, onScreen: true, held: false,
                                                 loopsDone: TickerRules.shared.CALM_LOOPS, hasItems: true))
        let d = TickerRules.shared.step(drift: TickerDrift(offsetDp: 99, loops: 0), dtMs: 50, loopWidthDp: 100)
        XCTAssertEqual(d.loops, 1)
        // The floating ticker (slice 2b): off by default, bottom middle, lets go onto the nearer edge.
        XCTAssertFalse(FloatingTickerRules.shared.shown(enabled: true, ticker: ticker))
        let screen = TickerRect(x: 0, y: 0, width: 1440, height: 875)
        let placed = FloatingTickerRules.shared.placement(edgeId: nil, centre: Double.nan)
        XCTAssertEqual(placed.edge, FloatingEdge.bottom)
        let f = FloatingTickerRules.shared.frame(visible: screen, placement: placed)
        XCTAssertEqual(f.x, 240)
        XCTAssertEqual(f.width, FloatingTickerRules.shared.MAX_WIDTH)
        let up = FloatingTickerRules.shared.dragged(start: f, dx: 0, dy: 600, visible: screen)
        XCTAssertEqual(FloatingTickerRules.shared.dropped(visible: screen, panel: up).edge, FloatingEdge.top)
    }

    func testWeeklyReviewThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "review-\(UUID().uuidString).db", deviceKey: nil
        )
        try await core.showReviewWeek(offset: 0)
        let id = try await core.addTask(title: "Book the MOT")
        try await core.complete(taskId: id)
        let v = core.reviewView.value
        XCTAssertEqual(v.title, "This week")
        XCTAssertEqual(v.done.map(\.title), ["Book the MOT"])
        XCTAssertEqual(v.tiles.first?.value, 1)
        XCTAssertEqual(v.northStar.count, 6)
        XCTAssertFalse(v.reviewed)
        try await core.reviewDone()
        XCTAssertTrue(core.reviewView.value.reviewed)
        try await core.showReviewWeek(offset: -1)
        XCTAssertEqual(core.reviewView.value.title, "Last week")
        XCTAssertTrue(core.reviewView.value.canGoForward)
    }

    func testNotificationGovernorThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "notify-\(UUID().uuidString).db", deviceKey: nil
        )
        try await core.setQuietHours(enabled: true, startMinute: 23 * 60, endMinute: 6 * 60)
        XCTAssertEqual(core.notificationSettings.value.quiet.summary, "23:00–06:00")
        try await core.setDigest(minute: NotifyRules.shared.MIDDAY, on: false)
        XCTAssertFalse(NotifyRules.shared.hasDigest(settings: core.notificationSettings.value, minute: NotifyRules.shared.MIDDAY))
        XCTAssertTrue(NotifyRules.shared.hasDigest(settings: core.notificationSettings.value, minute: NotifyRules.shared.EVENING))
        try await core.setNoticeTier(source: .shutdown, tier: .silent)
        XCTAssertEqual(NotifyRules.shared.tierOf(settings: core.notificationSettings.value, s: .shutdown), .silent)
        XCTAssertEqual(NotifyRules.shared.tierChoices(s: .chase).count, 2)
        let off = try await core.governNotifications(state: nil, device: .off)
        XCTAssertTrue(off.post.isEmpty)
        XCTAssertNil(off.digest)
        XCTAssertNil(off.nextWakeMs)
        // Reporting what posted (even nothing) starts the review's Interruptions count.
        try await core.notificationsPosted(posted: off.post)
        let interruptions = core.reviewView.value.northStar.first { $0.key == "interruptions" }
        XCTAssertNotEqual(interruptions?.line, "Counted once Navilife can notify you on a device")
        XCTAssertEqual(NotifyRules.shared.deviceFromName(name: "DIGESTS", fallback: .off), .digests)
        XCTAssertEqual(NotifyRules.shared.deviceFromName(name: nil, fallback: .off), .off)
        XCTAssertEqual(Int(NotifyRules.shared.deviceCount), 3)
    }

    func testGymThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "gym-\(UUID().uuidString).db", deviceKey: nil
        )
        let id = try await core.addGym()
        let habit = core.goalsView.value.habits.first { $0.id == id }
        XCTAssertEqual(habit?.booked, true)
        XCTAssertEqual(habit?.rotationLabel, "No rotation")
        try await core.setHabitRotation(id: id, index: 1)
        XCTAssertEqual(core.goalsView.value.habits.first { $0.id == id }?.rotation, ["Push", "Pull", "Legs"])
        XCTAssertEqual(SessionRules.shared.ROTATIONS.count, 4)
        XCTAssertEqual(SessionRules.shared.rotationLabel(r: ["Upper", "Lower"]), "Upper · Lower")
        // The workout app's link: cleaned, named, refused when it isn't a web address.
        let saved = try await core.setHabitAppLink(id: id, link: "hevy.com")
        XCTAssertTrue(saved.boolValue)
        XCTAssertEqual(core.goalsView.value.habits.first { $0.id == id }?.appLink, "https://hevy.com")
        XCTAssertEqual(core.goalsView.value.habits.first { $0.id == id }?.appName, "Hevy")
        let refused = try await core.setHabitAppLink(id: id, link: "javascript:alert(1)")
        XCTAssertFalse(refused.boolValue)
        XCTAssertEqual(SessionRules.shared.appName(link: "https://www.strava.com"), "Strava")
        // Went and Undo work whatever today's card says (the booking depends on the clock and calendar).
        try await core.sessionWent(id: id, note: "5 km")
        XCTAssertEqual(core.goalsView.value.habits.first { $0.id == id }?.doneToday, true)
        try await core.undoSession(id: id)
        XCTAssertEqual(core.goalsView.value.habits.first { $0.id == id }?.doneToday, false)
    }

    /// Today's habit chips (Fold review 2026-10-09 07:26, item 3): none without habits, and the strip under the
    /// header leaves its habits tile to the chips.
    func testHabitChipsReachSwift() {
        XCTAssertTrue(HabitChipRules.shared.build(goals: nil).isEmpty)
        XCTAssertEqual(HabitChipRules.shared.shortTitle(title: "Read 20 pages of a novel"), "Read 20 pages of…")
        let tiles = [DayTile(kind: .habits, value: 0, total: 1, label: "habits today"),
                     DayTile(kind: .renewals, value: 2, total: 0, label: "renewals due")]
        XCTAssertEqual(HabitChipRules.shared.stripTiles(tiles: tiles).map(\.kind), [.renewals])
    }

    /// Fold review 2026-10-09 07:26, item 9: an account's main calendar is "Personal"; Calendars renames in MEKA only.
    func testCalendarNamesReachSwift() {
        XCTAssertEqual(CalendarRules.shared.PERSONAL, "Personal")
        XCTAssertEqual(CalendarRules.shared.cleanName(typed: "  Kids   football "), "Kids football")
        XCTAssertNil(CalendarRules.shared.cleanName(typed: "   "))
        XCTAssertEqual(CalendarRules.shared.renamedLine(name: nil, defaultLabel: "Personal"), "Calendar name back to Personal")
        XCTAssertEqual(Int(CalendarRules.shared.MAX_NAME), 40)
    }

    /// Fold review 2026-10-09 07:26, item 6: Today's section label says what it holds; an empty day has no section.
    func testTodaySectionLabelReachesSwift() {
        XCTAssertEqual(DayTimeline.companion.EMPTY.sectionLabel, "Today")
        XCTAssertNil(DayTimeline.companion.EMPTY.head)
        XCTAssertNil(DayTimeline.companion.EMPTY.summary)
        XCTAssertEqual(TimelineRules.shared.SECTION, "Today")
    }

    /// The Day ring's live tiles (the opening moment, part 2): their words reach Swift.
    func testDayTilesReachSwift() {
        XCTAssertEqual(DayTileRules.shared.valueText(kind: .nextEvent, shown: 100, total: 0), "1 h 40")
        XCTAssertEqual(DayTileRules.shared.valueText(kind: .fast, shown: 790, total: 0), "13 h")
        XCTAssertEqual(DayTileRules.shared.valueText(kind: .habits, shown: 1, total: 3), "1 of 3")
        let tile = DayTile(kind: .renewals, value: 2, total: 0, label: "renewals due")
        XCTAssertEqual(tile.text(shown: 1), "1")
        XCTAssertEqual(tile.spokenLine, "2 renewals due")
    }

    /// The opening moment's Day ring (motion pass 2, slice 7): the core's ring reaches Swift with its words and play rule.
    func testDayRingReachesSwift() {
        XCTAssertEqual(DayRingRules.shared.line(freeMinutes: 225, toDo: 4, nowMinute: 600), "3 h 45 free · 4 to do")
        XCTAssertEqual(DayRingRules.shared.freeLine(freeMinutes: 0, nowMinute: 1330), "Evening")
        XCTAssertEqual(DayRingRules.shared.toDoLine(toDo: 0), "Nothing to do")
        XCTAssertEqual(DayRingRules.shared.play(lastFullEpochDay: nil, todayEpochDay: 100, reduced: false), DayRingPlay.full)
        XCTAssertEqual(DayRingRules.shared.play(lastFullEpochDay: KotlinLong(longLong: 100), todayEpochDay: 100, reduced: false), DayRingPlay.quick)
        XCTAssertEqual(DayRingRules.shared.play(lastFullEpochDay: nil, todayEpochDay: 100, reduced: true), DayRingPlay.still)
        // Living Today, slice 2: back to Today after 15 s or more draws the ring in again; a new day plays in full.
        XCTAssertEqual(DayRingRules.shared.onReturn(lastFullEpochDay: KotlinLong(longLong: 100), todayEpochDay: 100, awayMs: 15_000, reduced: false), DayRingPlay.quick)
        XCTAssertNil(DayRingRules.shared.onReturn(lastFullEpochDay: KotlinLong(longLong: 100), todayEpochDay: 100, awayMs: 14_999, reduced: false))
        XCTAssertEqual(DayRingRules.shared.onReturn(lastFullEpochDay: KotlinLong(longLong: 99), todayEpochDay: 100, awayMs: 1_000, reduced: false), DayRingPlay.full)
        XCTAssertNil(DayRingRules.shared.onReturn(lastFullEpochDay: nil, todayEpochDay: 100, awayMs: 60_000, reduced: true))
        let arc = DayArc(id: "e-x", kind: .event, startMinute: 600, endMinute: 660, past: false, current: false)
        XCTAssertEqual(arc.startDegrees, 150, accuracy: 1e-4)
        XCTAssertEqual(arc.sweepDegrees, 15, accuracy: 1e-4)
        let ring = DayRing(arcs: [arc], nowMinute: 630, freeMinutes: 225, toDo: 4, work: [], fast: nil, tomorrow: nil, rain: [])
        XCTAssertEqual(ring.line, "3 h 45 free · 4 to do")
        XCTAssertEqual(ring.spokenLine, "Your day: 1 thing booked. Now 10:30. 3 h 45 free · 4 to do.")
    }

    /// Living Today (slice 1): the living ring's numbers reach the Mac's layer.
    /// Fold review 2026-10-09, item 1: the ring in Today's header — 150 pt with its centre on the Mac.
    func testDayRingHeaderReachesSwift() {
        let h = DayRingHeader.shared
        XCTAssertEqual(h.sizeDp(compact: false), 150)
        XCTAssertEqual(h.WIDE_DP, 150)
        XCTAssertTrue(h.showsCentre(sizeDp: 150))
        XCTAssertFalse(h.showsCentre(sizeDp: 96))
        XCTAssertEqual(h.strokeDp(sizeDp: 150), 10)
        XCTAssertEqual(h.strokeDp(sizeDp: 96), 6)
    }

    func testDayRingLiveReachesSwift() {
        let live = DayRingLive.shared
        let nine: Int64 = 1_791_493_200_000 // Thu 8 Oct 2026 21:00 UTC
        XCTAssertEqual(live.handDegrees(epochMs: nine + 15_000), 90, accuracy: 0.01)
        XCTAssertEqual(live.glow(epochMs: nine), live.GLOW_LOW, accuracy: 0.001)
        XCTAssertEqual(live.glow(epochMs: nine + 2_500), live.GLOW_HIGH, accuracy: 0.001)
        XCTAssertEqual(live.shimmer(epochMs: nine, offsetMs: 3_600_000)?.floatValue, 0)
        XCTAssertNil(live.shimmer(epochMs: nine + 600_000, offsetMs: 3_600_000))
        XCTAssertGreaterThan(live.nowPop(epochMs: nine + 120), 1.3)
        XCTAssertEqual(live.mode(reduced: false, powerSave: false), DayRingLiveMode.sweep)
        XCTAssertEqual(live.mode(reduced: false, powerSave: true), DayRingLiveMode.minute)
        XCTAssertEqual(live.mode(reduced: true, powerSave: false), DayRingLiveMode.still)
        XCTAssertEqual(live.TAIL_SEGMENTS, 20)
        XCTAssertEqual(live.TAIL_DEGREES, 60)
        XCTAssertGreaterThan(live.tailAlpha(i: 0), live.tailAlpha(i: 5))
    }

    /// Fold review 2026-10-09, item 2: the brighter ring's numbers reach the Mac's dial.
    func testDayRingLookReachesSwift() {
        let look = DayRingLook.shared
        XCTAssertEqual(look.TRACK_STROKE_DP, 3)
        XCTAssertEqual(look.TRACK_ALPHA, 0.55, accuracy: 0.001)
        XCTAssertEqual(look.HOUR_MARK_ALPHA, 0.5, accuracy: 0.001)
        XCTAssertEqual(look.HAND_STROKE_DP, 2.5, accuracy: 0.001)
        XCTAssertEqual(look.edgeAlpha(glow: 0.6), 0.6, accuracy: 0.001)
        XCTAssertEqual(look.blurOffsetDp(i: look.EDGE_BLUR_LAYERS - 1), look.EDGE_BLUR_DP, accuracy: 0.001)
        XCTAssertGreaterThan(look.blurAlpha(i: 0, glow: 1), look.blurAlpha(i: 3, glow: 1))
    }

    /// Fold review 2026-10-09, item 3: Up next is the menu bar's card in Today's window too.
    func testUpNextCardReachesSwift() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "upnext-\(UUID().uuidString).db", deviceKey: nil
        )
        XCTAssertNil(core.upNextCard())
        let id = try await core.addTask(title: "Send the invoice")
        let card = try XCTUnwrap(core.upNextCard())
        XCTAssertEqual(card.kind, NowKind.task)
        XCTAssertEqual(card.label, CoverNowRules.shared.UP_NEXT_LABEL)
        XCTAssertEqual(card.title, "Send the invoice")
        XCTAssertEqual(card.task?.id, id)
        XCTAssertTrue(card.offers(action: NowAction.done))
        XCTAssertTrue(card.offers(action: NowAction.tomorrow))
        XCTAssertTrue(card.offers(action: NowAction.openTask))
        XCTAssertNil(card.thenLine)
        XCTAssertNil(card.needsYouLine)
    }

    /// Living Today, slice 3: the ring carries the day — the work band, the arc on now, a fast, clicking an arc.
    func testDayRingCarriesTheDayInSwift() {
        let call = DayArc(id: "e-call", kind: .event, startMinute: 600, endMinute: 660, past: false, current: true)
        let gym = DayArc(id: "s-gym", kind: .session, startMinute: 1065, endMinute: 1125, past: false, current: false)
        let band = DayBand(startMinute: 540, endMinute: 1050, current: true)
        let fast = DayFastArc(startDegrees: 301.25, sweepDegrees: 240, progress: 0.75, reachedGoal: false)
        let ring = DayRing(arcs: [call, gym], nowMinute: 630, freeMinutes: 120, toDo: 2, work: [band], fast: fast, tomorrow: nil, rain: [])
        XCTAssertTrue(gym.highlighted)
        XCTAssertFalse(call.highlighted)
        XCTAssertEqual(ring.work.first?.sweepDegrees ?? 0, 127.5, accuracy: 0.01)
        XCTAssertEqual(ring.fast?.filledDegrees ?? 0, 180, accuracy: 0.01)
        let rules = DayRingRules.shared
        let up = rules.tapDegrees(dx: 0, dy: -100, radius: 100)
        XCTAssertEqual(up?.floatValue ?? -1, 0, accuracy: 0.01)
        XCTAssertNil(rules.tapDegrees(dx: 0, dy: -30, radius: 100))
        XCTAssertEqual(rules.arcAt(ring: ring, degrees: 157.5)?.id, "e-call")
        XCTAssertEqual(rules.arcAt(ring: ring, degrees: 270)?.id, "s-gym")
        XCTAssertNil(rules.arcAt(ring: ring, degrees: 60))
    }

    /// Living Today, item 1: once the day is shut down the ring looks ahead to tomorrow's first thing.
    func testDayRingLooksAheadToTomorrowInSwift() {
        let tomorrow = DayRingTomorrow(minute: KotlinInt(int: 570), title: "Standup")
        let ring = DayRing(arcs: [], nowMinute: 1290, freeMinutes: 0, toDo: 0, work: [], fast: nil, tomorrow: tomorrow, rain: [])
        XCTAssertEqual(ring.tomorrow?.degrees?.floatValue ?? -1, 142.5, accuracy: 0.01)
        XCTAssertEqual(ring.centreLine(free: 0), "Tomorrow 09:30")
        XCTAssertEqual(ring.centreCaption(toDo: 0), "Standup")
        XCTAssertEqual(ring.spokenLine, "Day shut down. Tomorrow: first thing 09:30 Standup.")
        XCTAssertEqual(DayRingRules.shared.clockMinute(time: "09:30")?.intValue, 570)
        let today = DayRing(arcs: [], nowMinute: 1350, freeMinutes: 0, toDo: 0, work: [], fast: nil, tomorrow: nil, rain: [])
        XCTAssertEqual(today.centreLine(free: 0), "Evening")
    }

    /// Calendar colours (Fold review 2026-10-08, item 9): fixtures wear Barça's colour, the rest take five hues.
    func testCalendarTonesReachSwift() {
        XCTAssertEqual(CalendarTones.shared.FIXTURE, 0)
        XCTAssertEqual(CalendarTones.shared.COUNT, 5)
        let tone = CalendarTone(key: "google|meka@gmail.com|Kids", label: "Kids", tone: 2)
        XCTAssertEqual(tone.label, "Kids")
        XCTAssertEqual(AllDayRules.shared.LABEL, "All day")
    }

    func testNeedsYouEmptyStateReachesSwift() {
        XCTAssertEqual(NeedsYouStackRules.shared.EMPTY_LINE, "Nothing needs you")
        XCTAssertEqual(NeedsYouStackRules.shared.EMPTY_CAPTION, "Approvals, replies and decisions land here.")
        // Fold review 2026-10-12 17:56, item 3: messages waiting change the line; the after-work card names people.
        XCTAssertEqual(NeedsYouStackRules.shared.emptyLine(toCatchUp: 9), "Nothing to decide · 9 messages to catch up on")
        XCTAssertEqual(NeedsYouStackRules.shared.emptyLine(toCatchUp: 0), "Nothing needs you")
        XCTAssertEqual(AfterWorkSummary.companion.OPEN_LABEL, "Open")
        XCTAssertNil(AfterWorkSummary(people: []).namesLine)
        XCTAssertEqual(AfterWorkSummary(people: []).toCatchUp, 0)
    }

    /// Can't see the animations (2026-10-08): the Motion check and the build line reach Swift.
    /// Weather (slice 1): the server's forecast text decodes in Swift and the words Today's line uses come across.
    func testWeatherReachesSwift() {
        let hours = WeatherCodec.shared.decodeHours(s: "497000|14,2,10;12,61,70")
        XCTAssertEqual(hours.map(\.tempC), [14, 12])
        XCTAssertEqual(hours.last?.startMs, Int64(497_001) * 3_600_000)
        XCTAssertEqual(WeatherCodec.shared.decodeDays(s: "2026-10-09=8,15,61,70").first?.maxC, 15)
        XCTAssertEqual(WeatherRules.shared.words(code: 61), "light rain")
        XCTAssertTrue(WeatherRules.shared.isWet(h: hours[1]))
        XCTAssertFalse(WeatherRules.shared.isWet(h: hours[0]))
        // Weather slice 2: rain on the Day ring is a band of the track, two hours = 30°.
        let rain = DayBand(startMinute: 960, endMinute: 1080, current: false)
        XCTAssertEqual(rain.sweepDegrees, 30, accuracy: 0.01)
        XCTAssertEqual(rain.startDegrees, 240, accuracy: 0.01)
    }

    /// Weather place setting: what the Calendars sheet's field accepts and the line it shows reach Swift.
    func testWeatherPlaceReachesSwift() {
        // Rule 13: home is About you's (invented here), never the code's; the generic place stands in without one.
        XCTAssertEqual(WeatherPlaceRules.shared.FALLBACK, "London")
        _ = Household.shared.usePlaces(home: "Oakfield", work: "", assistantNumber: "")
        defer { _ = Household.shared.set(n: HouseholdNames.companion.EMPTY) }
        XCTAssertEqual(WeatherPlaceRules.shared.defaultName, "Oakfield")
        XCTAssertEqual(WeatherPlaceRules.shared.normalize(input: "  St   Neots "), "St Neots")
        XCTAssertNil(WeatherPlaceRules.shared.normalize(input: "123"))
        XCTAssertTrue(WeatherPlaceRules.shared.accepts(input: "Bedford"))
        XCTAssertFalse(WeatherPlaceRules.shared.accepts(input: "<b>"))
        XCTAssertTrue(WeatherPlaceRules.shared.isHome(name: "oakfield"))
        let home = WeatherForecast(place: "London", hours: [], days: [], asked: "Xyzzy", found: false)
        let lit = WeatherPlaceRules.shared.view(wanted: "Xyzzy", f: home, home: WeatherPlaceRules.shared.aboutHome)
        XCTAssertTrue(lit.lit)
        XCTAssertEqual(lit.line, "Couldn't find “Xyzzy” — showing London. Try the nearest town.")
        XCTAssertTrue(WeatherPlaceRules.shared.view(wanted: "Bedford", f: home, home: "Oakfield").pending)
        XCTAssertEqual(WeatherPlaceRules.shared.view(wanted: nil, f: WeatherForecast.companion.EMPTY, home: nil), WeatherPlaceView.companion.NO_HOME)
    }

    /// MEKA's voice: Talk cuts a line into the pieces it asks MEKA's server to say, in Swift as on the Fold.
    func testSpeechPiecesReachSwift() {
        XCTAssertEqual(SpeechRules.shared.pieces(text: "You've got three things today. Training is at 18:00. Anything else?"),
                       ["You've got three things today.", "Training is at 18:00. Anything else?"])
        XCTAssertEqual(SpeechRules.shared.pieces(text: "  "), [])
        XCTAssertEqual(SpeechRules.shared.FIRST_AUDIO_MS, 4000)
        // The spoken brief: short pieces, three asked for at once, a late one waited for in MEKA's voice.
        let read = SpeechRules.shared.pieces(text: "Good morning, Meka. Max's under-sevens play Hilltop at ten o'clock.", reading: true)
        XCTAssertEqual(read, ["Good morning, Meka.", "Max's under-sevens play Hilltop at ten o'clock."])
        XCTAssertEqual(SpeechRules.shared.ahead(reading: true), 3)
        XCTAssertEqual(SpeechRules.shared.waitMs(first: false, reading: true), 15000)
        // Rule 13, release 2: the children and clubs come from About you (invented here).
        _ = Household.shared.useTyped(children: "Max, U7\nFelix, U10", clubs: "OTFC Lindenbrook United, HRFC")
        defer { _ = Household.shared.useTyped(children: "", clubs: "") }
        XCTAssertEqual(FootballRules.shared.spokenFixture(title: "OTFC U10s v Wexley"), "Felix's under-tens play Wexley")
        XCTAssertEqual(MekaVoiceRules.shared.normalize(name: " device "), MekaVoiceRules.shared.DEVICE)
        XCTAssertEqual(SpeechRules.shared.usageLine(state: "on", month: "2026-10", usedChars: 12400, capChars: 1000000,
                                                    voice: "Amy", deviceChosen: false),
                       "Navilife's voice · Amy · 12,400 of 1,000,000 characters in October")
        XCTAssertNil(SpeechRules.shared.usageLine(state: "off", month: nil, usedChars: 0, capChars: 0, voice: nil, deviceChosen: false))
    }

    /// The voice picker (Weather and a voice, item 2): MEKA's voices first, then the Mac's own, reach Swift.
    func testVoicePickerReachesSwift() {
        let offered = [OfferedVoice(id: "Amy", gender: "Female", engine: "generative", provider: "polly"), OfferedVoice(id: "Brian", gender: "Male", engine: "neural", provider: "polly"),
                       OfferedVoice(id: "Aoede", gender: "Female", engine: "chirp3hd", provider: "google")]
        let v = VoicePickerRules.shared.view(state: "on", offered: offered, defaultVoice: "Amy", chosen: "Brian", usageLine: nil,
                                             mac: true, connected: true, loaded: true)
        XCTAssertEqual(v.choices.map(\.id), ["Aoede", "Amy", "Brian", "device"])
        XCTAssertEqual(v.choices.first?.group, "Google · Chirp 3 HD · British")
        XCTAssertEqual(VoicePickerRules.shared.googleLine(state: "on", usedChars: 84_213, freeChars: 1_000_000, fallbackAtChars: 900_000,
                                                         failedClock: nil, month: "2026-10"), "Google voices · 84k of 1M free this month")
        XCTAssertEqual(v.choices.filter(\.selected).map(\.id), ["Brian"])
        XCTAssertEqual(v.choices.last?.label, "This Mac's own voice")
        XCTAssertEqual(v.choices[1].detail, "British · female · most natural · Navilife's default")
        XCTAssertTrue(v.help.contains("Manage Voices"))
        XCTAssertTrue(VoicePickerRules.shared.SAMPLE.hasPrefix("Good morning, Meka."))
        // Natural voice, slice 2: ▶ Sample reads the start of today's brief.
        XCTAssertEqual(VoicePickerRules.shared.sampleFrom(script: "Good morning, Meka. It's Wednesday. Leave at 7:40."),
                       "Good morning, Meka. It's Wednesday.")
        XCTAssertEqual(VoicePickerRules.shared.sampleLine(words: VoicePickerRules.shared.SAMPLE), VoicePickerRules.shared.SAMPLE_STOCK)
    }

    /// The Mac's own voices one by one, with speed and pitch (Weather and a voice, slice 10): the section's rows and the
    /// one-line setting kept in UserDefaults reach Swift.
    func testDeviceVoicesReachSwift() {
        let voices = [
            DeviceVoice(name: "com.apple.voice.compact.en-GB.Daniel", displayName: "Daniel", language: "en-GB", quality: 1,
                        engine: "Apple", needsNetwork: false, installed: true),
            DeviceVoice(name: "com.apple.voice.premium.en-GB.Serena", displayName: "Serena (Premium)", language: "en-GB", quality: 3,
                        engine: "Apple", needsNetwork: false, installed: true),
        ]
        let d = DeviceVoiceRules.shared.view(voices: voices, settings: DeviceVoiceSettings.companion.DEFAULT, mac: true, expanded: false)
        XCTAssertEqual(d.title, "This Mac's voices")
        XCTAssertEqual(d.rows.map(\.label), ["Automatic", "Serena", "Daniel"])
        XCTAssertEqual(d.rows[1].detail, "Apple · British · Premium")
        XCTAssertEqual(d.rateLine, "Speed · normal")
        let s = DeviceVoiceSettings(voice: "com.apple.voice.compact.en-GB.Daniel", rate: 1.2, pitch: 0.9)
        XCTAssertEqual(DeviceVoiceRules.shared.decode(line: DeviceVoiceRules.shared.encode(s: s)), s)
        XCTAssertEqual(DeviceVoiceRules.shared.pick(voices: voices, settings: s)?.name, "com.apple.voice.compact.en-GB.Daniel")
        XCTAssertEqual(DeviceVoiceRules.shared.macRate(r: 1.2), 0.6, accuracy: 0.001)
        XCTAssertEqual(DeviceVoiceRules.shared.pitchLine(p: 0.9), "Pitch · 10% lower")
    }

    /// Talk without tapping the mic (slice 1): the Talk sheet's words reach Swift.
    func testTalkSetupReachesSwift() {
        let v = TalkStartRules.shared.setup(mac: true, assistantHeld: false, samsung: false)
        XCTAssertEqual(v.title, "Talk")
        XCTAssertEqual(v.sections.map(\.label), ["On the Mac", "Safety"])
        XCTAssertTrue(v.sections[0].status.contains("⌥Space"))
        XCTAssertNil(v.sections[0].action)
        XCTAssertEqual(TalkStartRules.shared.fromAction(action: "android.intent.action.ASSIST"), TalkStart.sideButton)
    }

    /// ⌥Space from any app: the Talk sheet's order, the switch's words and the taken line reach Swift.
    func testTalkAnywhereReachesSwift() {
        XCTAssertTrue(TalkAnywhereRules.shared.DEFAULT_ON)
        let v = TalkAnywhereRules.shared.macSetup(anywhere: true, taken: false, listenOnOpen: false, talkOver: true)
        XCTAssertEqual(v.sections.map(\.label), ["On the Mac", "From any app", "When I open Navilife", "Talk over Navilife", "Safety"])
        XCTAssertTrue(v.sections[0].status.contains("in any app"))
        XCTAssertEqual(v.sections[1].action, "Turn off")
        XCTAssertTrue(v.sections[1].lit)
        let taken = TalkAnywhereRules.shared.section(on: true, taken: true)
        XCTAssertFalse(taken.lit)
        XCTAssertTrue(taken.status.contains("Another app"))
        XCTAssertEqual(TalkAnywhereRules.shared.section(on: false, taken: false).action, "Turn on")
    }

    /// Talking over MEKA: the detector, the orb's line and the sheet's switch reach Swift.
    func testTalkOverMekaReachesSwift() {
        XCTAssertTrue(BargeInRules.shared.DEFAULT_ON)
        XCTAssertEqual(TalkOrb.shared.label(phase: .speaking, mac: true, talkOver: true), "Talk or click to interrupt")
        XCTAssertEqual(TalkOrb.shared.label(phase: .speaking, mac: true, talkOver: false), "Click to interrupt")
        // MEKA's echo at -25 dBFS for a second, then Meka at -8 dBFS: MEKA stops once it has kept up.
        var s = BargeInRules.shared.start()
        var stoppedAt: Int64?
        for i in 0..<100 {
            let now = Int64(i * 20)
            let step = BargeInRules.shared.step(s: s, levelDbfs: i < 50 ? -25 : -8, playing: true, output: .speaker, nowMs: now)
            s = step.state
            if step.interrupt { stoppedAt = now; break }
        }
        XCTAssertEqual(stoppedAt, 1_000 + BargeInRules.shared.SPEAKER.sustainMs)
        let section = BargeInRules.shared.section(on: false, mac: true)
        XCTAssertEqual(section.label, "Talk over Navilife")
        XCTAssertEqual(section.action, "Turn on")
    }

    /// The orb speed pass, slice 7: the mic open during the hello reaches Swift.
    func testHelloListenReachesSwift() {
        let hello = "Good evening, Alex. Anything you'd like?"
        XCTAssertTrue(HelloListenRules.shared.opensMic(talkOver: true, output: .headphones))
        XCTAssertFalse(HelloListenRules.shared.opensMic(talkOver: true, output: .speaker))
        XCTAssertEqual(HelloListenRules.shared.his(heard: "anything you'd like what's on", hello: hello), "what's on")
        XCTAssertTrue(HelloListenRules.shared.isHim(heard: "anything you'd like what's on", hello: hello, playing: true))
        XCTAssertTrue(HelloListenRules.shared.onlyEcho(heard: "good evening Alex", hello: hello))
        let tuning = HelloListenRules.shared.tuning(output: .speaker, hello: true)
        XCTAssertEqual(tuning.sustainMs, 240)
        let greeted = TalkFlow.shared.greet(greeting: hello)
        XCTAssertEqual(TalkFlow.shared.overHello(s: greeted.session).session.phase, .listening)
        XCTAssertTrue(TalkFlow.shared.spokeListening(s: greeted.session).effects.isEmpty)
        let step = BargeInRules.shared.stepWith(s: BargeInRules.shared.start(), levelDbfs: -8, playing: true, tuning: tuning, nowMs: 0)
        XCTAssertFalse(step.interrupt)
    }

    /// "Listen when I open MEKA" on the Mac: the sheet's section, the open and Dock rules and the room check reach Swift.
    func testTalkOnOpenReachesSwift() {
        let on = TalkOnOpenRules.shared.section(on: true, mac: true)
        XCTAssertEqual(on.label, TalkOnOpenRules.shared.LABEL)
        XCTAssertEqual(on.action, "Turn off")
        XCTAssertTrue(on.lit)
        XCTAssertTrue(on.status.contains("Dock"))
        XCTAssertEqual(TalkOnOpenRules.shared.section(on: false, mac: true).action, "Turn on")
        XCTAssertEqual(TalkOnOpenRules.shared.macOpenStart(plainOpen: true, listenOnOpen: true, micAllowed: true)?.name, "OPEN")
        XCTAssertNil(TalkOnOpenRules.shared.macOpenStart(plainOpen: true, listenOnOpen: false, micAllowed: true))
        XCTAssertNil(TalkOnOpenRules.shared.macOpenStart(plainOpen: true, listenOnOpen: true, micAllowed: false))
        XCTAssertTrue(TalkOnOpenRules.shared.reopenFromBackground(msSinceActivated: nil))
        XCTAssertTrue(TalkOnOpenRules.shared.reopenFromBackground(msSinceActivated: KotlinLong(longLong: 200)))
        XCTAssertFalse(TalkOnOpenRules.shared.reopenFromBackground(msSinceActivated: KotlinLong(longLong: 5_000)))
        XCTAssertTrue(TalkOnOpenRules.shared.tooNoisyAt(rms: 0.06))
        XCTAssertFalse(TalkOnOpenRules.shared.tooNoisyAt(rms: 0.003))
        XCTAssertTrue(TalkOnOpenRules.shared.windowLapsed(startedAtMs: 0, nowMs: 6_000, speechBegan: false))
        XCTAssertEqual(TalkProblem.tooNoisy.macLine, "Too noisy — click to talk")
    }

    /// Fold review 2026-10-09 07:26, item 2: the watch face's hands, markers and rim reach Swift.
    func testWatchFaceReachesSwift() {
        let rules = WatchFaceRules.shared
        let hands = rules.hands(minuteOfDay: 7 * 60 + 21, msIntoMinute: 0, secondEpochMs: 15_000)
        XCTAssertEqual(hands.hourDegrees, 220.5, accuracy: 0.01)
        XCTAssertEqual(hands.minuteDegrees, 126, accuracy: 0.01)
        XCTAssertEqual(hands.secondDegrees, 90, accuracy: 0.01)
        XCTAssertEqual(rules.markers().filter { $0.major }.map(\.hour), [12, 3, 6, 9])
        XCTAssertEqual(rules.rimStrokeDp(sizeDp: DayRingHeader.shared.COMPACT_DP), 4)
        XCTAssertEqual(WatchFace.companion.EMPTY.spokenLine, "Watch face. Next 12 hours: nothing booked. Tap for your whole day.")
        XCTAssertTrue(WatchFace.companion.EMPTY.arcs.isEmpty)
    }

    /// Watch face item (2026-10-12/13): the thinner bezel, its hairline and glow, and the thin glowing second hand reach Swift.
    func testHeaderCollapseReachesSwift() {
        let rules = HeaderCollapseRules.shared
        let wide = DayRingHeader.shared.WIDE_DP
        XCTAssertTrue(rules.collapses(cover: false, mac: true))
        XCTAssertEqual(rules.faceScale(shown: 0, fullDp: wide), 1, accuracy: 0.001)
        XCTAssertEqual(rules.faceDp(shown: 1, fullDp: wide), Float(rules.COMPACT_DP), accuracy: 0.001)
        XCTAssertEqual(rules.shown(progress: rules.progress(firstIndex: 0, offsetPx: 60, rangePx: 120), reduced: true), 1, accuracy: 0.001)
        XCTAssertFalse(rules.barShown(shown: 0))
        XCTAssertEqual(rules.compactLine(dateLabel: "Tue 13 Oct", weatherLine: "14° · light rain"), "Tue 13 Oct · 14°")
    }

    func testWatchFaceLookReachesSwift() {
        let look = WatchFaceLook.shared
        let wide = DayRingHeader.shared.WIDE_DP
        XCTAssertEqual(look.bezelDp(sizeDp: wide), 1.5, accuracy: 0.001)
        XCTAssertGreaterThanOrEqual(look.bezelDp(sizeDp: WatchFaceRules.shared.COVER_DP), 1.2)
        XCTAssertLessThanOrEqual(look.arcStrokeDp(sizeDp: wide), 1.5)
        XCTAssertGreaterThanOrEqual(look.bleedDp(sizeDp: WatchFaceRules.shared.COVER_DP), 6)
        XCTAssertEqual(look.NEEDLE_DARK_ARGB, 0xFFECD79E)
        XCTAssertLessThan(look.hairlineRadiusDp(sizeDp: wide), look.bezelRadiusDp(sizeDp: wide))
        XCTAssertEqual(look.bezelAlpha(glow: 0.6, shown: 0), look.BEZEL_DRAW_ALPHA, accuracy: 0.001)
        XCTAssertGreaterThan(look.blurAlpha(i: 0, glow: 1), look.blurAlpha(i: 2, glow: 1))
        XCTAssertEqual(look.needleDp(sizeDp: wide), 1.2, accuracy: 0.001)
        XCTAssertGreaterThan(look.needleReachDp(sizeDp: wide), look.needleTailDp(sizeDp: wide))
        let halo = look.halo(sizeDp: wide)
        XCTAssertEqual(halo.count, 3)
        XCTAssertEqual(halo.first?.widthDp ?? 0, 5, accuracy: 0.001)
        XCTAssertGreaterThan(halo.first?.blurDp ?? 0, 0)
        XCTAssertEqual(WatchFaceRules.shared.headerDp(cover: false), wide)
        XCTAssertLessThan(WatchFaceRules.shared.headerDp(cover: true), DayRingHeader.shared.COMPACT_DP)
    }

    /// Under "Nothing needs you" (Fold reviews 2026-10-09): the core's habits / Waiting on / renewals reach Swift.
    func testNeedsYouMeanwhileReachesSwift() {
        let m = NeedsYouMeanwhileRules.shared.build(goals: nil, lists: nil, comingUp: [])
        XCTAssertTrue(m.isEmpty)
        XCTAssertTrue(m.sections.isEmpty)
        XCTAssertNil(m.habitsLine)
        XCTAssertEqual(NeedsYouMeanwhileRules.shared.HABITS_LABEL, "Habits today")
        XCTAssertEqual(NeedsYouMeanwhileRules.shared.WAITING_LABEL, "Waiting on")
        XCTAssertEqual(NeedsYouMeanwhileRules.shared.more(total: 5), "+2 more")
        XCTAssertNil(NeedsYouMeanwhileRules.shared.more(total: 3))
        // Fold review 2026-10-12 17:56, slice 4: Coming up (the next two reminders) and Break it down.
        let line = MeanwhileLine(id: "a", title: "Renew passport", meta: "Reminder · Today · 18:00")
        let c = NeedsYouMeanwhileRules.shared.build(goals: nil, lists: nil, comingUp: [line, line, line])
        XCTAssertEqual(c.comingUp.count, 2)
        XCTAssertEqual(c.sections, [NeedsYouMeanwhileRules.shared.COMING_LABEL])
        XCTAssertEqual(NeedsYouMeanwhileRules.shared.COMING_LABEL, "Coming up")
        XCTAssertEqual(CoverNowRules.shared.BREAK_DOWN_LABEL, "Break it down")
        XCTAssertNotEqual(NowAction.breakDown, NowAction.openTask)
    }

    /// Requests slice 4: what Add on a request card says reaches Swift, and answering a card that isn't there is a no-op.
    func testRequestAnswersReachSwift() async throws {
        let thu: Int64 = 20_741 // Thu 15 Oct 2026
        let home = RequestAcceptRules.shared.doneLine(plan: RequestPlan.HomeDay(day: thu), kind: .workFromHome, today: thu - 6,
                                                      provider: nil, isWorkDay: true)
        XCTAssertEqual(home, "Thu 15 Oct: work from home")
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "requests-\(UUID().uuidString).db", deviceKey: nil
        )
        XCTAssertTrue(core.requests.value.isEmpty)
        let none = try await core.acceptRequest(cardId: "nope#0")
        XCTAssertNil(none)
    }

    /// Requests' notices (V1): the Notifications sheet lists the source, and "Straight away" (set on the Fold) syncs
    /// into the Mac's settings, so the Mac's governor posts that person's requests at once too.
    func testRequestNoticesReachSwift() async throws {
        let labels = (0..<Int(NotifyRules.shared.sourceCount)).map {
            NotifyRules.shared.sourceLabel(s: NotifyRules.shared.sourceAt(index: Int32($0)))
        }
        XCTAssertTrue(labels.contains("Requests from people you watch"))
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "request-now-\(UUID().uuidString).db", deviceKey: nil
        )
        try await core.setRequestNotifyNow(name: "Wife", on: true)
        XCTAssertTrue(core.notificationSettings.value.notifiesNow(name: "wife"))
        XCTAssertEqual(RequestWatchRules.shared.notifyLabel(on: true), "Straight away")
    }

    /// The spoken morning brief (Weather and a voice, slice 8): the script the brief sheet's Listen reads reaches Swift.
    func testBriefSpeechReachesSwift() {
        XCTAssertEqual(BriefSpeech.shared.script(v: MorningBriefView.companion.EMPTY, name: "Meka"),
                       "Good morning, Meka. Nothing's planned yet. That's your morning.")
        XCTAssertEqual(BriefSpeech.shared.spokenDate(label: "Fri 9 Oct"), "Friday 9 October")
        XCTAssertEqual(BriefSpeech.shared.clean(text: "9–15°, light rain from 15:00 — take a coat"),
                       "9 to 15 degrees, light rain from 15:00, take a coat")
    }

    /// One name everywhere (2026-10-21): Today's greeting and the brief's title read About you's name through the core.
    func testCallNameReachesSwift() {
        XCTAssertEqual(CallNameRules.shared.todayGreeting(hour: 19, shownName: "Alex Smith"), "Good evening, Alex")
        XCTAssertEqual(CallNameRules.shared.todayGreeting(hour: 2, shownName: nil), "Good night")
        XCTAssertEqual(CallNameRules.shared.opening(greeting: "Good morning", name: nil), "Good morning.")
    }

    /// A morning brief worth listening to, slice 1: the brief sheet's Listen reads the chief-of-staff script.
    func testBriefScriptReachesSwift() {
        let s = BriefScriptRules.shared.script(v: MorningBriefView.companion.EMPTY, name: "Meka")
        XCTAssertTrue(s.hasPrefix("Good morning, Meka. Nothing's planned yet. "), s)
        XCTAssertTrue(s.hasSuffix(BriefScriptRules.shared.CLOSE), s)
        XCTAssertGreaterThan(BriefScriptRules.shared.lineCount, 30)
        XCTAssertEqual(BriefScriptRules.shared.clock(min: 17 * 60 + 30), "half past 5")
    }

    /// A morning brief worth listening to, slice 2: the AI's brief is checked against the facts before the Mac reads it.
    func testBriefAiCheckReachesSwift() {
        let facts = ["Good morning, Meka.", "It's Monday 12 October.", "One thing to focus on: Renew passport, it's up next."]
        let kept = BriefAiRules.shared.check(answer: "Good morning, Meka. It's Monday 12 October. Lunch with Priya. Renew passport is up next.", facts: facts, maxChars: BriefScriptRules.shared.MAX_CHARS)
        XCTAssertEqual(kept, ["Good morning, Meka.", "It's Monday 12 October.", "Renew passport is up next."])
        let s = BriefAiRules.shared.script(kept: kept, v: MorningBriefView.companion.EMPTY)
        XCTAssertTrue(s?.hasSuffix(BriefScriptRules.shared.CLOSE) == true)
        XCTAssertNil(BriefAiRules.shared.script(kept: [], v: MorningBriefView.companion.EMPTY))
        XCTAssertEqual(BriefAiRules.shared.WAIT_MS, 6_000)
    }

    /// A morning brief worth listening to, slice 3: Short · Standard · Full and the Focus rows reach the Mac's sheet.
    func testBriefLengthAndFocusReachSwift() {
        let v = MorningBriefView.companion.EMPTY
        XCTAssertEqual(v.lengths.map(\.label), ["Short", "Standard", "Full"])
        XCTAssertEqual(v.lengths.first(where: \.chosen)?.id, "standard")
        XCTAssertTrue(v.focusLines.isEmpty)
        XCTAssertNil(v.focusMore)
        XCTAssertNil(v.needsYouLine)
        XCTAssertNil(v.focusCardLine)
        XCTAssertFalse(v.oneLiner.isEmpty)
        XCTAssertEqual(BriefLength.full.label, "Full")
        XCTAssertEqual(BriefCardRules.shared.caption(why: "it's overdue"), "Overdue")
    }

    /// Morning brief slice 4: who's waiting on you reaches Swift (the sheet's rows; the brief says them).
    func testBriefAsksReachSwift() {
        XCTAssertTrue(MorningBriefView.companion.EMPTY.asks.isEmpty)
        let note = MessageRequestRules.shared.voiceNoteProposal(personName: "Mum")
        XCTAssertEqual(BriefScriptRules.shared.askSaid(who: "Mum", p: note, today: 0), "Mum sent you a voice note to listen to.")
        let a = BriefAsk(id: "v-1", who: "Dave", line: "Urgent · Voice message from Dave · 16:10", spoken: "Dave left an urgent voice message at 4:10.", urgent: true)
        XCTAssertTrue(a.urgent)
        XCTAssertEqual(a.line, "Urgent · Voice message from Dave · 16:10")
    }

    /// Morning brief slice 5: "plan my day" said to Talk ends the conversation, then opens the planner.
    func testPlanMyDayFromTalkReachesSwift() {
        let reply = TalkRules.shared.reply(utterance: "plan my day please", pendingCards: 0)
        XCTAssertEqual((reply as? TalkReplyOpen)?.place, TalkPlace.plan)
        let said = TalkFlow.shared.heard(s: TalkFlow.shared.start().session, utterance: "plan my day")
        XCTAssertEqual((said.effects.first as? TalkEffectSpeak)?.text, TalkOpenRules.shared.OPENING_PLAN)
        let done = TalkFlow.shared.spoke(s: said.session)
        XCTAssertTrue(done.effects.first is TalkEffectEnd)
        XCTAssertEqual((done.effects.last as? TalkEffectOpen)?.place, TalkPlace.plan)
        XCTAssertNil(done.session.openAfter)
    }

    /// Inbox autopilot, slice 7b: "clear my inbox" said to Talk ends the conversation, then starts the ten-minute inbox.
    func testClearMyInboxFromTalkReachesSwift() {
        let reply = TalkRules.shared.reply(utterance: "clear my inbox", pendingCards: 0)
        XCTAssertEqual((reply as? TalkReplyOpen)?.place, TalkPlace.inbox)
        let said = TalkFlow.shared.heard(s: TalkFlow.shared.start().session, utterance: "go through my emails")
        XCTAssertEqual((said.effects.first as? TalkEffectSpeak)?.text, TalkOpenRules.shared.OPENING_INBOX)
        let done = TalkFlow.shared.spoke(s: said.session)
        XCTAssertEqual((done.effects.last as? TalkEffectOpen)?.place, TalkPlace.inbox)
        XCTAssertEqual(NoticeSource.mailMoment.label, "Ten-minute inbox")
    }

    /// Voice notes, slice 3: "take a voice note" said to Talk on the Mac opens the voice note sheet; the Mac's notes
    /// use the phone's index format and the core's rows say "this Mac".
    func testVoiceNoteOnTheMacReachesSwift() {
        let said = TalkFlow.shared.heard(s: TalkFlow.shared.start().session, utterance: "take a voice note",
                                         places: TalkOpenRules.shared.MAC)
        XCTAssertEqual((said.effects.first as? TalkEffectSpeak)?.text, VoiceNoteRules.shared.OPENING)
        let done = TalkFlow.shared.spoke(s: said.session)
        XCTAssertEqual((done.effects.last as? TalkEffectOpen)?.place, TalkPlace.voiceNote)
        let rules = VoiceNoteRules.shared
        let note = rules.made(id: "vn1", atMs: 1_791_668_400_000, durationMs: 83_000, segments: ["ring the garage"], failed: false)
        XCTAssertEqual(note.transcript, "Ring the garage.")
        XCTAssertEqual(note.words, VoiceNoteWords.heard)
        let back = rules.decode(text: rules.encodeList(notes: [note]))
        XCTAssertEqual(back.map(\.id), ["vn1"])
        XCTAssertEqual(rules.toggledKeep(note: note).kept, true)
        XCTAssertNil(rules.tidied(note: note, cards: -1, tidy: "", people: []))
        XCTAssertEqual(rules.tidied(note: note, cards: 2, tidy: "Ring the garage.", people: ["Sam"])?.tidy, "Ring the garage.")
        XCTAssertEqual(rules.noWordsLineMac(words: VoiceNoteWords.failed, noOnDevice: true), rules.MAC_NO_ON_DEVICE)
    }

    /// Morning brief, slice 7: the brief's Listen hands over to Talk (8 s) only after a finished read that ends asking.
    func testBriefFollowOnReachesSwift() {
        let script = "Good morning, Meka. " + BriefScriptRules.shared.CLOSE
        XCTAssertTrue(BriefFollowOnRules.shared.handOff(script: script, finished: true, micAllowed: true))
        XCTAssertFalse(BriefFollowOnRules.shared.handOff(script: script, finished: true, micAllowed: false))
        XCTAssertEqual(BriefFollowOnRules.shared.WINDOW_MS, 8_000)
        let start = TalkFlow.shared.afterBrief()
        XCTAssertEqual(start.session.phase, TalkPhase.listening)
        XCTAssertTrue(start.effects.first is TalkEffectListen)
        let more = TalkFlow.shared.heard(s: start.session, utterance: "more news")
        XCTAssertEqual((more.effects.first as? TalkEffectReadOut)?.read.kind, ReadOut.Kind.headlines)
    }

    /// Fold review 2026-10-09 07:26, item 7: the brief's Today rows (one left edge; a task carries its id for the tick).
    func testBriefDayLinesReachSwift() {
        let task = BriefRules.shared.dayLine(r: TomorrowRow(id: "t-milk", time: nil, title: "Buy milk", isEvent: false, detail: "Overdue"))
        XCTAssertEqual(task.taskId, "milk")
        XCTAssertEqual(task.caption, "Overdue")
        XCTAssertTrue(task.lit)
        let event = BriefRules.shared.dayLine(r: TomorrowRow(id: "e-1", time: "09:30", title: "Standup", isEvent: true, detail: nil))
        XCTAssertNil(event.taskId)
        XCTAssertEqual(event.spoken, "Event, Standup, 09:30")
        XCTAssertTrue(MorningBriefView.companion.EMPTY.dayLines.isEmpty)
    }

    /// Morning brief read aloud: every headline shown is read ("From BBC Sport: …"), and a long read keeps MEKA's voice.
    func testBriefReadsEveryHeadlineInMekasVoice() {
        let shown = [
            BriefHeadline(id: "n1", title: "Barça win again", url: nil, meta: "BBC Sport · Barça · 1 h ago"),
            BriefHeadline(id: "n2", title: "Rates held", url: nil, meta: "BBC News · UK"),
        ]
        XCTAssertEqual(BriefSpeech.shared.news(headlines: shown),
                       ["In the news.", "From BBC Sport: Barça win again.", "From BBC News: Rates held."])
        XCTAssertEqual(SpeechRules.shared.firstWaitMs(reading: true), SpeechRules.shared.READ_FIRST_AUDIO_MS)
        XCTAssertTrue(SpeechRules.shared.onMiss(reading: true, resting: false) == .pieceOnDevice)
        XCTAssertTrue(SpeechRules.shared.onMiss(reading: false, resting: false) == .restOnDevice)
    }

    /// Voice never switches mid-conversation (2026-10-09): the hold rule, its budget and Activity's timing line reach Swift.
    func testVoiceHoldReachesSwift() {
        let now: Int64 = 1_000_000
        XCTAssertTrue(SpeechRules.shared.holds(reading: false, resting: false, lastMekaVoiceMs: KotlinLong(longLong: now - 60_000), nowMs: now))
        XCTAssertFalse(SpeechRules.shared.holds(reading: false, resting: false, lastMekaVoiceMs: nil, nowMs: now))
        XCTAssertFalse(SpeechRules.shared.holds(reading: true, resting: false, lastMekaVoiceMs: KotlinLong(longLong: now), nowMs: now))
        XCTAssertEqual(SpeechRules.shared.waitMs(first: true, reading: false), 4_000)
        XCTAssertEqual(SpeechRules.shared.budgetMs(first: true, reading: false, hold: true), 10_000)
        XCTAssertEqual(SpeechRules.shared.HOLD_LINE, "One moment…")
        XCTAssertNil(SpeechRules.shared.timingLine(timings: []))
        XCTAssertEqual(SpeechRules.shared.seconds(ms: 1_834), "1.8 s")
    }

    /// Activity completeness: the kept line, Export's label and the too-late line reach Swift.
    func testActivityExportReachesSwift() {
        let rules = ActivityExportRules.shared
        XCTAssertTrue(rules.keptLine(count: 2).hasSuffix("Export saves all 2 entries"))
        XCTAssertEqual(rules.LABEL, "Export")
        XCTAssertEqual(UndoOutcome.tooLate.line, "Too late to undo · change it by hand")
    }

    /// Activity's filter (activity log, slice 3): chips for the kinds there are, and the days narrowed to one kind.
    func testActivityFilterReachesSwift() {
        func row(_ id: String, _ kind: ActivityKind) -> ActivityRow {
            ActivityRow(id: id, time: "09:00", kind: kind, summary: "Something", detail: nil, why: "Why: a rule", canUndo: false, undoneLine: nil)
        }
        let view = ActivityView(weekLine: "This week: 1 reminder", days: [
            ActivityDay(day: 2, label: "Today", rows: [row("a", .reminded), row("b", .screened)]),
            ActivityDay(day: 1, label: "Yesterday", rows: [row("c", .reminded)]),
        ], emptyLine: ActivityRules.shared.EMPTY_LINE)
        let rules = ActivityRules.shared
        XCTAssertEqual(rules.filters(view: view).map(\.label), ["All", "Reminders · 2", "Calls · 1"])
        let calls = rules.filtered(view: view, filter: rules.filterNamed(name: "CALLS"))
        XCTAssertEqual(calls.days.map(\.label), ["Today"])
        XCTAssertEqual(rules.chosen(view: view, chosen: .calendar).name, "ALL")
    }

    /// Activity's daily count of MEKA's AI (default assistant item): Talk first, then Ask, then what MEKA read, and the cost.
    func testAiTodayLineReachesSwift() {
        let rules = AiTodayRules.shared
        XCTAssertNil(rules.line(today: []))
        let today = [AiDayUse(feature: "ask", calls: 2, microUsd: 6_000), AiDayUse(feature: "ask.talk", calls: 6, microUsd: 12_000)]
        XCTAssertEqual(rules.line(today: today), "Navilife's AI today · Talk 6 · Ask 2 · 2¢")
        XCTAssertEqual(rules.cost(microUsd: 1_200_000), "$1.20")
    }

    /// N-a-week habits (Fold review 2026-10-09 13:45, item 1): never "Behind" for a day off, the week's goes as slots.
    func testWeeklyHabitPaceReachesSwift() {
        let rules = GoalRules.shared
        let monday: Int64 = 4 // 1970-01-05
        XCTAssertEqual(rules.weeklyPace(target: 2, doneThisWeek: 0, doneToday: false, today: monday + 2, createdDay: monday), .onTrack)
        XCTAssertEqual(rules.weeklyPace(target: 2, doneThisWeek: 1, doneToday: false, today: monday + 5, createdDay: monday), .due)
        XCTAssertEqual(rules.leftLine(left: 1, days: 2), "1 left — 2 days to go")
        XCTAssertEqual(rules.weeklyMeta(pace: .onTrack, done: 1, weekTarget: 2, today: monday + 2), "1 of 2 this week")
    }

    /// Calm Today, slice 2: today's habits as dots on the watch face; a click near one ticks it, the undo bar names it.
    func testHabitDotsReachSwift() {
        let rules = HabitDotRules.shared
        let chips = [
            HabitChip(id: "s", title: "Stretch", done: false, behind: false, tickLabel: "Tick Stretch for today", count: nil, name: "Stretch"),
            HabitChip(id: "r", title: "Read", done: true, behind: false, tickLabel: "Untick Read for today", count: nil, name: "Read"),
        ]
        let dots = rules.dots(chips: chips)
        XCTAssertEqual(dots.map(\.degrees), [168, 192])
        XCTAssertFalse(rules.chipsShown(faceShown: true, chips: chips))
        XCTAssertTrue(rules.chipsShown(faceShown: false, chips: chips))
        let size = Int32(DayRingHeader.shared.WIDE_DP)
        let place = rules.place(dot: dots[0], sizeDp: size)
        XCTAssertEqual(rules.hit(dots: dots, xDp: place.xDp, yDp: place.yDp, sizeDp: size)?.id, "s")
        XCTAssertNil(rules.hit(dots: dots, xDp: 0, yDp: 0, sizeDp: size))
        XCTAssertEqual(rules.tickedLine(dot: dots[0], nowDone: true), "Stretch · done today")
        XCTAssertEqual(rules.spokenLine(dots: dots), " Habits: Stretch, not yet; Read, done.")
    }

    /// Calm Today, slice 3: Up next and the timeline lead Today, the moment cards follow them, and the opening
    /// stagger arrives in the same order.
    func testTodayOrderReachesSwift() {
        let rules = TodayOrderRules.shared
        XCTAssertEqual(rules.ORDER, [TodaySlot.clear, .fromYesterday, .upNext, .timeline, .cards, .needsYou, .anytime, .done])
        XCTAssertTrue(rules.leads(slot: .timeline))
        XCTAssertFalse(rules.leads(slot: .cards))
        XCTAssertLessThan(rules.stagger(slot: .upNext), rules.stagger(slot: .cards))
        XCTAssertLessThan(rules.TIMELINE_ROWS_STEP, rules.stagger(slot: .cards))
        XCTAssertEqual(rules.SECTIONS, 7)
    }

    /// A hand-made gym habit is offered booking instead of a second "Add Gym" (Fold review 2026-10-09 13:45, item 2).
    func testGymBookOfferReachesSwift() {
        let rules = SessionRules.shared
        XCTAssertTrue(rules.isGymLike(title: "gym"))
        XCTAssertFalse(rules.isGymLike(title: "Gymnastics"))
        XCTAssertEqual(rules.bookOfferLabel(title: "gym"), "Let Navilife book “gym”")
        XCTAssertEqual(rules.bookedMinutes(minutes: 15), 60)
        XCTAssertFalse(rules.BOOK_OFFER_CAPTION.isEmpty)
    }

    /// A late planned task (Fold review 2026-10-09 13:45, item 3): "Since 09:15", Move to later and the undo line.
    func testLateTaskRulesReachSwift() {
        let rules = LateTaskRules.shared
        XCTAssertEqual(rules.sinceLabel(hhmm: "09:15"), "Since 09:15")
        XCTAssertEqual(rules.MOVE_LATER, "Move to later")
        XCTAssertEqual(rules.movedLine(title: "Send the invoice", hhmm: "15:30"), "Moved “Send the invoice” to 15:30")
        XCTAssertEqual(NowAction.later.name, "LATER")
    }

    /// The messages assistant's cards (V1, messages slice 3): the Mac copies, never sends.
    func testTriageReplyRulesReachSwift() {
        let rules = TriageReplyRules.shared
        let card = TriageCard(id: "wa-1", lane: .needsReply, personKey: "tunde", from: "Tunde · 14:02", gist: "Asks if you're coming",
                              draft: "Yes, see you then", app: .whatsapp, mentioned: false, atMs: 1_000, spoken: "Tunde", group: nil, askedBy: nil, number: nil, phoneLive: false, phoneSend: nil)
        XCTAssertEqual(rules.label(primary: rules.primary(card: card, live: false, canSend: false)), "Copy reply")
        XCTAssertEqual(rules.secondary(card: card), "Not now")
        XCTAssertEqual(rules.copiedLine(card: card), "Reply to Tunde copied")
        XCTAssertEqual(rules.notNowLine(card: card), "Not now · Tunde")
        XCTAssertEqual(card.lane.label, "Needs a reply")
        // WhatsApp replies, slice 1: with the chat's number the Mac's button opens WhatsApp with the reply typed.
        let numbered = TriageCard(id: "wa-2", lane: .needsReply, personKey: "tunde", from: "Tunde · 14:02", gist: "Asks if you're coming",
                                  draft: "Yes, see you then", app: .whatsapp, mentioned: false, atMs: 1_000, spoken: "Tunde", group: nil,
                                  askedBy: nil, number: "+447700900123", phoneLive: false, phoneSend: nil)
        let primary = rules.primary(card: numbered, live: false, canSend: false)
        XCTAssertEqual(rules.label(primary: primary), "Reply in WhatsApp")
        XCTAssertEqual(rules.replyLink(card: numbered, text: numbered.draft), "https://wa.me/447700900123?text=Yes%2C%20see%20you%20then")
        XCTAssertEqual(rules.routeLine(card: numbered, primary: primary), "Opens WhatsApp with your reply typed · you tap send")
        XCTAssertEqual(rules.routeLine(card: card, primary: rules.primary(card: card, live: false, canSend: false)), "Copies your reply · paste it in the chat")
        // WhatsApp replies, slice 2: while the Fold holds the notification the Mac's button asks the phone to send.
        let live = TriageCard(id: "wa-3", lane: .needsReply, personKey: "tunde", from: "Tunde · 14:02", gist: "Asks if you're coming",
                              draft: "Yes, see you then", app: .whatsapp, mentioned: false, atMs: 1_000, spoken: "Tunde", group: nil,
                              askedBy: nil, number: "+447700900123", phoneLive: true, phoneSend: nil)
        let viaPhone = rules.primary(card: live, live: false, canSend: false)
        XCTAssertEqual(viaPhone, .sendViaPhone)
        XCTAssertEqual(rules.label(primary: viaPhone), "Send (via your phone)")
        XCTAssertFalse(rules.waitingOnPhone(card: live))
        let waiting = TriageCard(id: "wa-3", lane: .needsReply, personKey: "tunde", from: "Tunde · 14:02", gist: "Asks if you're coming",
                                 draft: "Yes, see you then", app: .whatsapp, mentioned: false, atMs: 1_000, spoken: "Tunde", group: nil,
                                 askedBy: nil, number: "+447700900123", phoneLive: true, phoneSend: .waiting)
        XCTAssertTrue(rules.waitingOnPhone(card: waiting))
        XCTAssertEqual(rules.phoneSendLine(card: waiting), "Sending from your phone…")
    }

    /// Messages grouped per chat (slice 1): one card per conversation reaches Swift with its count and Done line.
    func testTriageChatsReachSwift() {
        let cards = (1...3).map { i in
            TriageCard(id: "g\(i)", lane: .fyi, personKey: i == 2 ? "obi" : "femi", from: "\(i == 2 ? "Obi" : "Femi") in Barça lads · 14:0\(i)",
                       gist: "Lineup talk", draft: nil, app: .whatsapp, mentioned: true, atMs: Int64(i), spoken: "x", group: "Barça lads", askedBy: nil, number: nil, phoneLive: false, phoneSend: nil)
        }
        let chat = TriageChatRules.shared.chats(cards: cards).first!
        XCTAssertEqual(chat.title, "Barça lads")
        XCTAssertEqual(chat.countLine, "3 new · 2 people")
        XCTAssertFalse(chat.single)
        XCTAssertEqual(chat.ids, ["g1", "g2", "g3"])
        XCTAssertEqual(TriageChatRules.shared.doneLine(chat: chat), "Done · Barça lads · 3 messages")
        XCTAssertEqual(TriageChatRules.shared.replyChats(cards: cards), 0)
        // Slice 3: a group carries no requests, and with none every chat is as before.
        let withRequests = TriageChatRules.shared.chatsWith(cards: cards, requests: [])
        XCTAssertEqual(withRequests.first?.countLine, "3 new · 2 people")
        XCTAssertTrue(withRequests.first?.requests.isEmpty ?? false)
        XCTAssertTrue(TriageChatRules.shared.looseRequests(chats: withRequests, requests: []).isEmpty)
        // Slice 3b: the group's digest card rides in the chat's card, and the digest keeps the rest.
        let gists = [("Barça lads", "Lineup debate"), ("Golf crew", "Tee time moved")].map { title, line in
            GroupGist(groupKey: title.lowercased(), title: title, countLine: "14 messages", count: 14, people: "Tunde",
                      gist: line, slotMs: 1_000, slotLabel: "Lunchtime digest", latestMs: 900, caughtUpAtMs: nil, spoken: "x")
        }
        let folded = TriageChatRules.shared.withGists(chats: withRequests, gists: gists)
        XCTAssertEqual(folded.first?.chatter, 14)
        XCTAssertEqual(folded.first?.chatterLine, "+ 14 more in the group · Lineup debate")
        XCTAssertEqual(folded.first?.countLine, "3 new · 2 people · +14 in the group")
        XCTAssertEqual(TriageChatRules.shared.gistsOutside(chats: folded, gists: gists).map(\.title), ["Golf crew"])
        XCTAssertEqual(TriageChatRules.shared.gistsInside(chat: folded[0], gists: gists).map(\.groupKey), ["barça lads"])
        // Slice 3c: a busy chat's synced summary leads its card; with none the card is as before.
        let busy = TriageChatRules.shared.chats(cards: cards + [
            TriageCard(id: "g4", lane: .fyi, personKey: "femi", from: "Femi in Barça lads · 14:04", gist: "Kick-off time",
                       draft: nil, app: .whatsapp, mentioned: true, atMs: 4, spoken: "x", group: "Barça lads", askedBy: nil, number: nil, phoneLive: false, phoneSend: nil),
        ])
        // Fold review 2026-10-12, bug 3: a question from someone Meka watches for lifts the group with "Jordan asked".
        let asked = TriageChatRules.shared.chats(cards: cards + [
            TriageCard(id: "j1", lane: .fyi, personKey: "jordan", from: "Jordan in WTFC U7s · 14:05", gist: "Asks if there's no match Saturday",
                       draft: nil, app: .whatsapp, mentioned: true, atMs: 1, spoken: "x", group: "WTFC U7s", askedBy: "Jordan", number: nil, phoneLive: false, phoneSend: nil),
        ])
        XCTAssertEqual(asked.map(\.title), ["WTFC U7s", "Barça lads"])
        XCTAssertEqual(asked.first?.askedBy, "Jordan")
        XCTAssertEqual(TriageChatRules.shared.askedLine(name: "Jordan"), "Jordan asked")
        XCTAssertNil(asked.last?.askedBy)
        let summary = ChatSummary(chatKey: busy[0].key, title: "Barça lads", summary: "Saturday's lineup and kick-off", upToMs: 4, count: 4, madeAtMs: 5)
        let summed = ChatSummaryRules.shared.withSummaries(chats: busy, summaries: [summary])
        XCTAssertEqual(summed.first?.summary, "Saturday's lineup and kick-off")
        XCTAssertEqual(summed.first?.top.count, 0)
        XCTAssertNil(ChatSummaryRules.shared.withSummaries(chats: busy, summaries: []).first?.summary)
    }

    /// The group digest's synced cards (V1, messages slice 4b) reach Swift: the head line and Caught up.
    func testGroupGistsReachSwift() {
        let gist = GroupGist(groupKey: "barça lads", title: "Barça lads", countLine: "47 messages", count: 47, people: "Tunde, Femi and 3 others",
                             gist: "Lineup debate for Getafe", slotMs: 1_000, slotLabel: "Lunchtime digest", latestMs: 900,
                             caughtUpAtMs: nil, spoken: "Barça lads, 47 messages.")
        XCTAssertEqual(GroupGistRules.shared.headLine(gists: [gist]), "Lunchtime digest · 1 group · 47 messages")
        XCTAssertNil(GroupGistRules.shared.headLine(gists: []))
        XCTAssertEqual(GroupDigestRules.shared.CAUGHT_UP, "Caught up")
    }

    func testMotionCheckReachesSwift() {
        // Only MEKA's own Off stills it (Reduce Motion no longer does; core MotionCheckTest.macCheck).
        let off = MotionCheckRules.shared.mac(stored: .off, reduceMotion: true, lowPower: false)
        XCTAssertEqual(off.rows.map(\.label), ["Navilife Motion", "Reduce Motion", "Low Power Mode"])
        XCTAssertEqual(off.result, "Animations off because Navilife's Motion is Off — click to turn them on")
        XCTAssertEqual(off.fix?.id, "expressive")
        XCTAssertEqual(MotionCheckRules.shared.mac(stored: nil, reduceMotion: true, lowPower: false).result,
                       "Animations on · Expressive · playing even with Reduce Motion on")
        let on = MotionCheckRules.shared.mac(stored: .expressive, reduceMotion: false, lowPower: false)
        XCTAssertEqual(on.result, "Animations on · Expressive")
        XCTAssertNil(on.fix)
        XCTAssertEqual(MotionCheckRules.shared.PLAY_OPENING, "Play the opening")
        XCTAssertEqual(AppUpdateRules.shared.versionLine(versionName: "0.1.0", versionCode: 1, latestCode: nil), "Navilife 0.1.0 · build 1")
    }

    /// The task detail's When row (Fold review 2026-10-08, item 8): labels, steps and the undo line reach Swift.
    func testTaskWhenRulesReachSwift() {
        XCTAssertEqual(TaskWhenRules.shared.label(day: 100, minute: KotlinInt(int: 870), today: 100), "Today · 14:30")
        XCTAssertEqual(TaskWhenRules.shared.label(day: 101, minute: nil, today: 100), "Tomorrow")
        XCTAssertEqual(TaskWhenRules.shared.step(minute: 540, steps: 1), 555)
        XCTAssertEqual(TaskWhenRules.shared.suggestedMinute(day: 101, today: 100, nowMinute: 600), 540)
        XCTAssertEqual(TaskWhenRules.shared.deletedLine(title: "Book dentist"), "Deleted “Book dentist”")
    }

    /// Remind me in the task detail: the chip labels reach Swift.
    func testTaskReminderRulesReachSwift() {
        XCTAssertEqual(TaskReminderRules.shared.beforeLabel(minutes: 15), "15 min before")
        XCTAssertEqual(TaskReminderRules.shared.beforeLabel(minutes: 60), "1 h before")
    }

    /// Calendar editing, slice 1: the Calendars sheet's editing line and action reach Swift.
    func testCalendarAccessRulesReachSwift() {
        XCTAssertEqual(CalendarAccessRules.shared.action(provider: "google", canEdit: false, needsReconnect: false), .allowEditing)
        XCTAssertEqual(CalendarAccessRules.shared.action(provider: "microsoft", canEdit: true, needsReconnect: false)?.label, "Stop editing")
        XCTAssertNil(CalendarAccessRules.shared.action(provider: "fixtures", canEdit: false, needsReconnect: false))
        XCTAssertEqual(CalendarAccessRules.shared.line(provider: "google", canEdit: false, needsReconnect: false), "Read-only · Navilife only reads this calendar")
        XCTAssertTrue(CalendarAccessRules.shared.reconnectAsksEditing(canEdit: true))
        let a = ConnectedAccount(provider: "google", email: "me@gmail.com", status: "ok", lastSyncAtMs: nil, canEdit: true, title: "Personal", eventsLine: nil, newsDown: [])
        XCTAssertEqual(a.editingAction, .stopEditing)
    }

    /// Meka's screenshot 2026-10-09 09:01: Calendars titles accounts like their main calendar; "news_more" reads Headlines.
    func testCalendarAccountLinesReachSwift() {
        XCTAssertEqual(CalendarAccountRules.shared.providerLabel(provider: "news_more"), "Headlines")
        XCTAssertEqual(CalendarAccountRules.shared.title(provider: "microsoft", email: "me@hotmail.co.uk", names: [:]), "Hotmail")
        let a = ConnectedAccount(provider: "google", email: "me@gmail.com", status: "ok", lastSyncAtMs: nil, canEdit: false,
                                 title: CalendarAccountRules.shared.title(provider: "google", email: "me@gmail.com", names: [:]),
                                 eventsLine: "No events in the next 30 days", newsDown: [])
        XCTAssertEqual(a.title, "Personal")
        XCTAssertEqual(a.statusLine(syncedAt: "08:29"), "Google · me@gmail.com · synced 08:29")
        XCTAssertEqual(a.eventsLine, "No events in the next 30 days")
    }

    /// Meka's 10:48 screenshots (2026-10-09): the watch face's work band and arcs, the Calendar's live work band,
    /// the now line when nothing else is planned, and whether an account's events come through.
    func testTenFortyEightPolishReachesSwift() {
        let rules = WatchFaceRules.shared
        XCTAssertEqual(rules.WORK_BAND_ALPHA, 0.7, accuracy: 0.001)
        XCTAssertEqual(rules.arcAlpha(kind: .event), 1, accuracy: 0.001)
        XCTAssertEqual(TimelineRules.shared.nothingAfterLabel(until: "17:30"), "Nothing else planned after 17:30")
        XCTAssertEqual(TimelineRules.shared.nothingAfterLabel(until: nil), "Nothing else planned today")
        XCTAssertEqual(CalendarAccountRules.shared.eventsLine(provider: "microsoft", email: "me@hotmail.co.uk", status: "ok",
                                                              synced: true, events: [], nowMs: 0), "No events in the next 30 days")
    }

    /// Fold review 2026-10-09 13:45, item 4: at work the now line counts the anytime tasks left; 2026-10-12, polish 6:
    /// the work row above already says until when, so the now line doesn't repeat it.
    func testNowLineAtWorkReachesSwift() {
        XCTAssertEqual(TimelineRules.shared.restLabel(until: "17:30", work: "Work", anytime: 4), "4 anytime tasks")
        XCTAssertEqual(TaskTitleRules.shared.display(title: "renew passport"), "Renew passport")
        XCTAssertEqual(UpNextWhyRules.shared.carriedLabel(days: 2, repeating: false), "Carried over 2 days")
        XCTAssertEqual(TimelineRules.shared.restLabel(until: "15:00", work: nil, anytime: 1), "Free after 15:00 · 1 anytime task")
        XCTAssertEqual(TimelineRules.shared.restLabel(until: nil, work: nil, anytime: 0), "Nothing else planned today")
    }

    /// Fold review 2026-10-09, item 5: "3 held for later" unfolds a preview; its words reach Swift.
    func testHeldPreviewReachesSwift() {
        let rules = HeldPreviewRules.shared
        XCTAssertEqual(rules.label(workLine: "At work until 17:30", count: 3), "At work until 17:30 · 3 held for later")
        XCTAssertEqual(rules.moreLine(more: 2), "+2 more — all of them after work")
        XCTAssertEqual(rules.firstLine(text: "Can you grab milk?\nAnd bread"), "Can you grab milk?")
        XCTAssertTrue(rules.CAPTION.contains("nothing is marked read"))
        let empty = HeldPreview(label: "At work · 0 held for later", rows: [], more: 0)
        XCTAssertTrue(empty.isEmpty)
        XCTAssertNil(empty.moreLine)
    }

    /// Messages assistant, slice 5: Work mode → Messages' lines reach Swift (the Mac shows them; the Fold keeps the list).
    func testMessagesSetupReachesSwift() {
        let rules = MessagesSetupRules.shared
        XCTAssertTrue(rules.statusLine(listening: nil, aiOn: nil).contains("Work mode → Messages"))
        XCTAssertFalse(rules.statusLit(listening: nil, aiOn: KotlinBoolean(bool: false)))
        XCTAssertTrue(rules.PRIVACY.contains { $0.contains("without your tap") })
        XCTAssertTrue(rules.COST.contains("monthly cap"))
        XCTAssertFalse(rules.LIMITS.isEmpty)
    }

    /// Calendar editing, slice 2b: the Add event sheet's form steps and view reach Swift.
    func testPlanCalendarRulesReachSwift() {
        let google = EditAccount(provider: "google", email: "me@gmail.com")
        let off = PlanCalendarRules.shared.setting(on: false, accounts: [google], lastUsedKey: nil)
        XCTAssertTrue(off.available)
        XCTAssertFalse(off.on)
        XCTAssertEqual(off.label, "Also add the blocks to Google")
        XCTAssertEqual(PlanCalendarRules.shared.setting(on: true, accounts: [google], lastUsedKey: nil).accountLabel, "Google · me@gmail.com")
        XCTAssertFalse(PlanCalendarRules.shared.setting(on: true, accounts: [], lastUsedKey: nil).available)
        XCTAssertEqual(PlanCalendarRules.shared.line(count: 3, provider: "google"), "Adding 3 blocks to Google")
    }

    func testAddEventRulesReachSwift() {
        let google = EditAccount(provider: "google", email: "me@gmail.com")
        XCTAssertEqual(google.key, "google|me@gmail.com")
        XCTAssertEqual(google.label, "Google · me@gmail.com")
        let form = AddEventRules.shared.start(today: 100, nowMinute: 600, day: nil, accounts: [google], lastUsedKey: nil)
            .withTitle(text: "Dentist").stepTime(steps: 2).withLength(minutes: 30)
        XCTAssertEqual(form.minuteOrNone, 645)
        XCTAssertEqual(form.accountKey, google.key)
        XCTAssertEqual(form.withAllDay(on: true).minuteOrNone, -1)
        XCTAssertEqual(AddEventRules.shared.lengthLabel(minutes: 90), "1 h 30")
        XCTAssertEqual(AddEventRules.shared.endLabel(endMinute: 1470), "00:30 next day")
    }

    /// Calendar editing, slice 2c: the Edit form's words and the detail's edit note reach Swift.
    func testEditEventRulesReachSwift() {
        XCTAssertTrue(EditEventRules.shared.TIME_NOTE.hasPrefix("Its time can't be changed"))
        let note = EventEditNote(editId: "e1", text: "This cancels it for 4 people", needsMeka: true, deleteAnyway: true, waiting: false, clash: nil, everyEvent: true, followingEvents: false)
        XCTAssertTrue(note.deleteAnyway)
        // Completeness slice 1: Delete anyway keeps the refused edit's scope (every event of a series).
        XCTAssertTrue(note.everyEvent)
        XCTAssertEqual(EventScopeChoice(label: "Every event", every: true, selected: true, following: false).label, "Every event")
        // Completeness slice 2: This and following reaches Swift as its own choice.
        let following = EventScopeChoice(label: "This and following", every: false, selected: true, following: true)
        XCTAssertTrue(following.following)
        XCTAssertFalse(note.followingEvents)
        XCTAssertFalse(note.waiting)
    }

    /// V1 AI layer, slice 3b: Ask's status line, the answer's lines and a card with its Undo reach Swift.
    func testAskMekaThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "ask-\(UUID().uuidString).db", deviceKey: nil
        )
        let status = try await core.aiStatus()
        XCTAssertFalse(status.canAsk)
        XCTAssertEqual(AskRules.shared.statusView(state: "on", reason: nil, spentCents: 120, budgetCents: 2000, level: "ok").line, "On · $1.20 of $20 this month")
        XCTAssertEqual(AskRules.shared.answerLines(text: "One.\n\nTwo."), ["One.", "Two."])
        let card = AskRules.shared.cardOf(p: AskProposalAddTask(title: "Milk", day: nil, minute: nil), today: 0)
        XCTAssertEqual(card.line, "Add “Milk”")
        let done = try await core.doAsk(card: card)
        XCTAssertEqual(done.line, "Added “Milk”")
        XCTAssertEqual(core.today.value.upNext?.title, "Milk")
        let undo = try XCTUnwrap(done.undo)
        let back = try await core.undoAsk(undo: undo)
        XCTAssertTrue(back.boolValue)
        XCTAssertNil(core.today.value.upNext)
    }

    /// V1 voice, slice 3: the Mac's talk rules, the flow's effects and a spoken yes with one undo reach Swift.
    func testTalkToMekaOnTheMac() async throws {
        XCTAssertEqual(TalkOrb.shared.label(phase: .speaking, mac: true), "Click to interrupt")
        XCTAssertTrue(TalkProblem.noOnDevice.macLine.contains("can't listen right now"))
        XCTAssertEqual(TalkOrb.shared.levelDbfs(db: -50), 0)
        XCTAssertEqual(TalkOrb.shared.levelDbfs(db: -3), 1)
        XCTAssertEqual(TalkEndpoint.shared.step(startedMs: 0, heardAnything: false, lastWordsMs: 0, nowMs: 9_000), .silence)
        XCTAssertEqual(TalkEndpoint.shared.step(startedMs: 0, heardAnything: true, lastWordsMs: 2_000, nowMs: 2_500), .keep)
        XCTAssertEqual(TalkEndpoint.shared.step(startedMs: 0, heardAnything: true, lastWordsMs: 2_000, nowMs: 3_600), .finish)
        // The Mac's voice qualities: default 1, enhanced 2, premium 3; British first.
        let best = TalkVoice.shared.best(voices: [
            VoiceCandidate(name: "us.premium", language: "en-US", quality: 3, needsNetwork: false, installed: true),
            VoiceCandidate(name: "gb.enhanced", language: "en-GB", quality: 2, needsNetwork: false, installed: true),
            VoiceCandidate(name: "gb.default", language: "en-GB", quality: 1, needsNetwork: false, installed: true),
        ])
        XCTAssertEqual(best?.name, "gb.enhanced")
        let start = TalkFlow.shared.start()
        XCTAssertEqual(start.session.phase, .listening)
        XCTAssertTrue(start.effects.first is TalkEffectListen)

        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "talk-\(UUID().uuidString).db", deviceKey: nil
        )
        let card = AskRules.shared.cardOf(p: AskProposalAddTask(title: "Milk", day: nil, minute: nil), today: 0)
        let did = try await core.doTalk(cards: [card])
        XCTAssertEqual(did.barLine, "Added “Milk”")
        XCTAssertEqual(core.today.value.upNext?.title, "Milk")
        let back = try await core.undoTalk(undos: did.undos)
        XCTAssertTrue(back.boolValue)
        XCTAssertNil(core.today.value.upNext)
    }

    /// Ask's one field (Fold review 2026-10-09 07:26, item 8): the matches, Return and the words reach Swift.
    func testAskFieldReachesSwift() {
        let rules = AskFieldRules.shared
        XCTAssertEqual(rules.placeholder(canAsk: true), "Ask or search…")
        XCTAssertEqual(rules.onReturn(typed: "dentist", canAsk: false), AskReturn.search)
        XCTAssertEqual(rules.onReturn(typed: "what's on", canAsk: true), AskReturn.ask)
        XCTAssertTrue(rules.showMatches(typed: "dentist", asked: nil))
        XCTAssertFalse(rules.showMatches(typed: "what's on", asked: "what's on"))
        let hit = SearchHit(id: "t1", kind: .task, title: "Dentist", detail: "Planned today 14:00", snippet: nil,
                            target: .task, task: nil, score: 5, place: nil)
        let view = SearchView(query: "den", groups: [SearchGroup(kind: .task, label: "Tasks", hits: [hit], more: 0)], total: 3)
        let m = rules.matches(view: view, typed: "den", canAsk: true)
        XCTAssertEqual(m?.rows.first?.line, "Task · Planned today 14:00")
        XCTAssertEqual(m?.seeAll, "See all 3 matches")
        XCTAssertNil(rules.matches(view: view, typed: "dentist", canAsk: true))
        XCTAssertTrue(rules.idleLine(canAsk: true, mac: true).hasSuffix("click."))
    }

    /// Places item 1: each weekday's own hours (Thursday's short day) reach Swift.
    func testWorkDayHoursReachSwift() {
        let s = WorkSchedule.companion.DEFAULT
        XCTAssertEqual(s.summary, "Mon–Fri · 09:00–17:30 · Thu 09:00–15:30")
        let rows = s.weekRows
        XCTAssertEqual(rows.map { $0.dayShort }, ["Mon", "Tue", "Wed", "Thu", "Fri"])
        XCTAssertTrue(rows[3].own)
        XCTAssertEqual(rows[3].line, "09:00–15:30 · own hours")
        XCTAssertEqual(rows[0].line, "09:00–17:30 · usual")
    }

    /// Places item 2: work is About you's (rule 13; invented here), and the work field's line reaches Swift.
    func testPlacesReachSwift() {
        _ = Household.shared.usePlaces(home: "", work: "Riverside", assistantNumber: "")
        defer { _ = Household.shared.set(n: HouseholdNames.companion.EMPTY) }
        XCTAssertEqual(PlacesRules.shared.defaultWork, "Riverside")
        XCTAssertTrue(PlacesRules.shared.isDefaultWork(name: nil))
        XCTAssertTrue(PlacesRules.shared.isDefaultWork(name: "riverside"))
        XCTAssertFalse(PlacesRules.shared.isDefaultWork(name: "Cambridge"))
        XCTAssertEqual(PlacesRules.shared.workLine(place: "Riverside"), "Work forecast for Riverside · on office days")
        XCTAssertEqual(WeatherView.companion.EMPTY.workChoice, WeatherPlaceView.companion.NO_WORK)
    }

    /** Places item 4: the commute's train line reaches Today on the Mac through the weather view. */
    func testRouteReachesSwift() {
        XCTAssertNil(WeatherView.companion.EMPTY.route)
        let line = RouteLine(text: "South Rail · Metro line · good service", lit: false, spoken: "Trains: South Rail, Metro line, good service")
        XCTAssertFalse(line.lit)
        // Rule 13, release 2 slice 6: the route is About you's Train route (an invented one here).
        _ = Household.shared.useRoute(typed: "South Rail to Oakfield Junction, then Metro line; or North Rail to Riverside")
        defer { _ = Household.shared.set(n: HouseholdNames.companion.EMPTY) }
        XCTAssertEqual(RouteRules.shared.name(id: "metro", r: RouteRules.shared.route()), "Metro line")
        XCTAssertEqual(RouteRules.shared.route()?.text, "South Rail to Oakfield Junction, then Metro line; or North Rail to Riverside")
        XCTAssertEqual(RouteRules.shared.words(severity: 9), "minor delays")
        XCTAssertEqual(CalendarAccountRules.shared.providerLabel(provider: "lines"), "Train lines")
    }

    func testBlockListReachesSwift() {
        XCTAssertEqual(BlockedCallersView.companion.EMPTY.rows.count, 0)
        XCTAssertEqual(BlockedCallersView.companion.EMPTY.line, BlockedCallerRules.shared.EMPTY_LINE)
        XCTAssertEqual(BlockedCallerRules.shared.display(number: "+441632960691"), "01632 960691")
        XCTAssertNil(BlockedCallerRules.shared.keyOf(number: "abc"))
        // The assistant's number is About you's (rule 13; invented here).
        XCTAssertEqual(CallScreeningRules.shared.testLine(number: "01632 960000"), "Test it: ring 01632 960000 from another phone")
        XCTAssertEqual(CallScreeningRules.shared.assistantName(number: nil), "Navilife's assistant")
    }

    func testCallCreditReachesSwift() {
        // The call assistant's low-balance guard: the Work sheet's paused line and the Top up card's link.
        XCTAssertEqual(
            CallScreeningRules.shared.statusLine(switchedOn: true, atWork: true, screeningAllowed: nil, paused: true),
            "Paused · Twilio credit · calls ring as usual until it's topped up"
        )
        XCTAssertEqual(CallCreditRules.shared.money(pence: 420, currency: "GBP"), "£4.20")
        XCTAssertEqual(CallCreditRules.shared.TOP_UP_URL, "https://www.twilio.com/console/billing")
        XCTAssertEqual(NoticeSource.callCredit.label, "Call assistant credit")
    }

    func testBlockFromAHeldMessageReachesSwift() {
        // Call assistant polish 8b b, e and 8: Block on a stranger's message, the 7726 text, the one-screener note.
        XCTAssertEqual(BlockedCallerRules.shared.reportText(number: "+441632960691"), "Call 01632960691")
        XCTAssertTrue(BlockedCallerRules.shared.macReportHint(number: "01632960691").contains("7726"))
        XCTAssertFalse(BlockedCallersView.companion.EMPTY.has(number: "01632 960691"))
        XCTAssertEqual(CallScreeningRules.shared.ONE_SCREENER_LINES.count, 3)
    }

    func testSpamProtectionIsAlwaysOnAndReachesSwift() {
        // Spam calls still ring, slice 1: the shared copy, and an official claim's heads-up.
        XCTAssertTrue(SpamProtectionRules.shared.HINT.hasPrefix("Spam protection is always on."))
        XCTAssertEqual(OfficialCallerRules.shared.claim(networkName: "Eastshire Police"), OfficialClaim.police)
        XCTAssertTrue(OfficialCallerRules.shared.isEmergency(number: "999"))
        XCTAssertEqual(AutoBlockRules.shared.blockedLine(number: "+441632960123"), "Blocked 01632 960123 · it won't ring again")
    }

    func testSuspectedSpamReachesSwift() {
        // Call assistant polish 8b c: the AI's flags arrive as Suspected spam rows with Block · Not spam.
        XCTAssertEqual(BlockedCallersView.companion.EMPTY.suspects.count, 0)
        XCTAssertEqual(SuspectedSpamRules.shared.TITLE, "Suspected spam")
        XCTAssertEqual(SuspectedSpamRules.shared.NOT_SPAM_LABEL, "Not spam")
        XCTAssertEqual(SuspectedSpamRules.shared.blockWhy(why: "Claims to be HMRC"), "Suspected scam · Claims to be HMRC")
        XCTAssertNil(SuspectedSpamRules.shared.cleanWhy(why: "see https://x.example"))
    }

    func testBlockedCallsReachSwift() {
        // Spam calls still ring, slice 4: Activity → Blocked calls with Unblock / Block again.
        XCTAssertEqual(BlockedCallersView.companion.EMPTY.stopped.count, 0)
        XCTAssertNil(BlockedCallersView.companion.EMPTY.stoppedLine)
        XCTAssertEqual(StoppedCallRules.shared.TITLE, "Blocked calls")
        XCTAssertEqual(StoppedCallRules.shared.UNBLOCK_LABEL, "Unblock")
        XCTAssertEqual(StoppedCallRules.shared.BLOCK_AGAIN_LABEL, "Block again")
    }

    func testRecentCallsReachSwift() {
        // Call notifications, slice 4: Activity → Calls and a call's detail.
        XCTAssertTrue(CallLogView.companion.EMPTY.isEmpty)
        XCTAssertNil(CallLogView.companion.EMPTY.line)
        XCTAssertEqual(CallLogRules.shared.TITLE, "Recent calls")
        XCTAssertEqual(CallLogRules.shared.CHIP_BLOCKED, "Blocked")
        XCTAssertEqual(CallLogRules.shared.dayLabel(day: 10, today: 11), "Yesterday")
    }

    func testMoneyRulesReachSwift() {
        // Money manager, slice 1: a statement file is read, a shop categorised, Money starts locked, the tile has no amount.
        let read = MoneyCsv.shared.read(text: "Date,Description,Amount\n02/10/2026,TESCO STORES 2041,-40.00\n")
        XCTAssertNil(read.problem)
        XCTAssertEqual(read.rows.first?.pence, -4000)
        XCTAssertEqual(MoneyCategoryRules.shared.merchantKey(text: "CARD PAYMENT TO TESCO STORES 2041"), "TESCO STORES")
        XCTAssertEqual(MoneyCategoryRules.shared.categoryOf(text: "COSTA COFFEE", pence: -320), MoneyCategory.coffee)
        XCTAssertTrue(MoneyView.companion.LOCKED.locked)
        XCTAssertEqual(MoneyRules.shared.signed(pence: -2340), "−£23.40")
        XCTAssertEqual(MoneyRules.shared.CHOICES.first?.label, "Groceries")
    }

    func testMoneyAskReachesSwift() {
        // Money manager (16): which questions are money ones, the amount read, and the unlock's words cross to Swift.
        XCTAssertTrue(MoneyAskRules.shared.isAbout(question: "can I afford £300 on Saturday?"))
        XCTAssertFalse(MoneyAskRules.shared.isAbout(question: "how do I import a statement?"))
        XCTAssertEqual(MoneyAskRules.shared.amount(q: "can I afford £300?"), KotlinLong(value: 30_000))
        XCTAssertEqual(MoneyAskRules.shared.UNLOCK, "Unlock Money to answer")
    }

    func testMoneyTipsReachSwift() {
        // Money manager, slice 2: the tip kinds and their words cross to Swift; nothing hidden says nothing.
        XCTAssertEqual(MoneyTipKind.habit.key, "habit")
        XCTAssertEqual(MoneyTips.shared.HIDE, "Hide tips like this")
        XCTAssertNil(MoneyTips.shared.hiddenLine(hidden: Set<String>()))
        XCTAssertEqual(MoneyTips.shared.hiddenLine(hidden: ["rise"]), "Hidden: tips on price rises")
        XCTAssertTrue(MoneyView.companion.LOCKED.tips.isEmpty)
    }

    func testMoneyWatchReachesSwift() {
        // Money manager, slice 3: the watchdog's words and head cross to Swift; a locked screen has no alerts.
        XCTAssertEqual(MoneyWatch.shared.FINE, "That's fine")
        XCTAssertEqual(MoneyWatch.shared.head(n: 2), "WORTH A LOOK · 2")
        XCTAssertEqual(MoneyAlertKind.duplicate.key, "double")
        XCTAssertTrue(MoneyView.companion.LOCKED.alerts.isEmpty)
    }

    func testMoneyForecastReachesSwift() {
        // Money manager, slice 4: the forecast's words and the extra's reading cross to Swift; a locked screen has none.
        XCTAssertEqual(MoneyForecast.shared.HEAD, "FORECAST AT YOUR PACE")
        XCTAssertEqual(MoneyForecast.shared.parseExtra(text: "£50 a week"), KotlinLong(value: 21_666))
        XCTAssertNil(MoneyForecast.shared.parseExtra(text: "lots"))
        XCTAssertEqual(MoneyForecast.shared.extraSaid(pence: 20_000), "What if: £200 a month more coming in")
        XCTAssertNil(MoneyView.companion.LOCKED.forecast)
    }

    func testMoneyCardsReachSwift() {
        // Money manager, slice 7: the cards block's words cross to Swift, typed values parse; a locked screen has none.
        XCTAssertEqual(MoneyCards.shared.HEAD, "CARDS")
        XCTAssertEqual(MoneyCards.shared.parseDue(text: "23rd")?.int32Value, 23)
        XCTAssertEqual(MoneyCards.shared.parseAmount(text: "£640")?.int64Value, 64000)
        XCTAssertEqual(MoneyCards.shared.ordinal(n: 2), "2nd")
        XCTAssertNil(MoneyView.companion.LOCKED.cards)
    }

    func testMoneyPaydayReachesSwift() {
        // Money manager, slice 6: the payday block's words cross to Swift, a typed balance parses; a locked screen has none.
        XCTAssertEqual(MoneyPayday.shared.HEAD, "UNTIL PAYDAY")
        XCTAssertEqual(MoneyPayday.shared.parseBalance(text: "-£50")?.int64Value, -5000)
        XCTAssertNil(MoneyView.companion.LOCKED.payday)
    }

    func testMoneyWorthReachesSwift() {
        // Money manager, slice 8: net worth's words and a typed line cross to Swift; a locked screen has none.
        XCTAssertEqual(MoneyWorth.shared.HEAD, "NET WORTH")
        let p = MoneyWorth.shared.parse(text: "Car loan £3,200")
        XCTAssertEqual(p?.name, "Car loan")
        XCTAssertEqual(p?.owes, true)
        XCTAssertEqual(p?.pence, 320_000)
        XCTAssertNil(MoneyView.companion.LOCKED.worth)
    }

    func testMoneyRefundsReachSwift() {
        // Money manager, slice 5: the refunds section's words cross to Swift; a locked screen has none.
        XCTAssertEqual(MoneyRefunds.shared.HEAD, "REFUNDS ON THE WAY")
        XCTAssertEqual(MoneyRefunds.shared.CLOSE, "Close it")
        XCTAssertEqual(MoneyRefunds.shared.BACK, "back")
        XCTAssertNil(MoneyView.companion.LOCKED.refunds)
    }

    func testKidsRulesReachSwift() {
        // Kids' admin, slice 1: a size is tidied, both apps write the same child, no children → About you first.
        let rules = KidsRules.shared
        XCTAssertEqual(rules.tidy(typed: "7-8 years"), "7–8 years")
        XCTAssertNil(rules.tidy(typed: "big"))
        XCTAssertEqual(rules.idFor(name: "Mary Jane"), "c-mary-jane")
        XCTAssertEqual(KidsView.companion.EMPTY.empty, rules.NO_CHILDREN)
        // Slice 2: a typed club fee is read (or turned away with why).
        XCTAssertEqual(rules.readFee(typed: "Football £35 monthly", today: 20_741).second as? String, rules.FEE_NO_DATE)
        XCTAssertEqual(rules.readFee(typed: "Football £35 monthly from 1 Nov", today: 20_741).first?.title, "Football")
        // Slice 3: THIS WEEK's words for a school thing ("Alex: PE kit").
        let pe = SchoolThing(id: "pe", kind: SchoolKind.weekly, title: "PE", who: "Alex")
        XCTAssertEqual(KidsWeekRules.shared.schoolText(t: pe, who: "Alex"), "Alex: PE kit")
        // Slice 5: a chore line and a weekly amount are read; nothing set shows nothing.
        let chores = KidChoreRules.shared
        XCTAssertEqual(chores.readChore(typed: "Tidy room £1").first?.title, "Tidy room")
        XCTAssertEqual(chores.readChore(typed: "Tidy room £1").first?.rewardPence?.int64Value, 100)
        XCTAssertEqual(chores.readPocket(typed: "lots").second as? String, chores.BAD_POCKET)
        XCTAssertFalse(KidMoney.companion.NONE.shown)
    }

    func testReflectionRulesReachSwift() {
        // Weekly reflection, slice 1: a suggestion adds once on its own line; the week's facts in one line.
        let rules = ReflectionRules.shared
        XCTAssertEqual(rules.withHint(field: "Shipped it", hint: "Gym"), "Shipped it\nGym")
        XCTAssertEqual(rules.withHint(field: "Shipped it\ngym", hint: "Gym"), "Shipped it\ngym")
        XCTAssertEqual(rules.facts(done: 5, habitsMet: 1, habits: 2, wins: 1, stillOpen: 0), "5 done · 1 of 2 habits met · 1 win")
        XCTAssertEqual(rules.facts(done: 0, habitsMet: 0, habits: 0, wins: 0, stillOpen: 0), rules.QUIET_WEEK)
        // Slice 2: "reflect on my week" said to Talk on the Mac opens the reflection.
        let said = TalkFlow.shared.heard(s: TalkFlow.shared.start().session, utterance: "reflect on my week",
                                         places: TalkOpenRules.shared.MAC)
        XCTAssertEqual((said.effects.first as? TalkEffectSpeak)?.text, rules.OPENING)
        let done = TalkFlow.shared.spoke(s: said.session)
        XCTAssertEqual((done.effects.last as? TalkEffectOpen)?.place, TalkPlace.reflect)
        // Done for the day, completeness 2: "shut down the day" said to Talk on the Mac opens the shutdown sheet.
        let shut = TalkFlow.shared.heard(s: TalkFlow.shared.start().session, utterance: "shut down the day",
                                         places: TalkOpenRules.shared.MAC)
        XCTAssertEqual((shut.effects.first as? TalkEffectSpeak)?.text, TalkOpenRules.shared.OPENING_SHUTDOWN)
        XCTAssertEqual((TalkFlow.shared.spoke(s: shut.session).effects.last as? TalkEffectOpen)?.place, TalkPlace.shutdown)
        // Slice 3: the Sunday time, as the picker shows it.
        let review = ReviewRules.shared
        XCTAssertEqual(review.whenLine(start: 19 * 60 + 30), "Comes on Sundays at 19:30 · through Monday")
        XCTAssertEqual(review.timeChoices(start: 18 * 60).map { Int($0.int32Value) }, [960, 1020, 1080, 1140, 1200, 1260])
        XCTAssertFalse(review.validStart(minute: 23 * 60))
        // Slice 4: Later tonight, its button and what it says, and the heads-up's button name the Mac answers.
        XCTAssertEqual(review.LATER_LABEL, "Later tonight")
        XCTAssertEqual(review.laterSaid(minute: 20 * 60 + 15), "Your weekly review comes back at 20:15")
        XCTAssertEqual(NotifyRules.shared.actionName(a: NoticeAction.laterTonight), "LATER_TONIGHT") // MacNotifier.laterTonightAction
        XCTAssertEqual(NotifyRules.shared.actionName(a: NoticeAction.allStillFit), "ALL_STILL_FIT") // MacNotifier.allStillFitAction, kids' admin, slice 6
        XCTAssertEqual(NotifyRules.shared.actionName(a: NoticeAction.shutDownLater), "SHUT_DOWN_LATER") // MacNotifier.shutdownLaterAction, Done for the day, completeness
        XCTAssertEqual(NotifyRules.shared.targetName(t: .kids), "KIDS")
        XCTAssertEqual(NoticeSource.barca.label, "Barça match day") // Barça match day, slice 1: listed in Notifications like the rest
        XCTAssertEqual(NoticeSource.barcaGoals.label, "Barça goals") // slice 2: each goal, then full time, in one place
        XCTAssertEqual(NoticeSource.barcaResult.label, "Barça full time")
        XCTAssertEqual(NoticeSource.meetingPrep.label, "Meeting prep (before meetings)")
        XCTAssertEqual(NoticeSource.balance.label, "Balance: an area short of its target (Thursday)") // Balance view, slice 4: the weekly nudge, listed like the rest
        XCTAssertEqual(NoticeSource.protectedMail.label, "Work mail that waited (protected time)") // Protected time, slice 6: the morning note, its own row
        // Balance view, slice 5: the "Count as" chips reach Swift as plain Strings.
        XCTAssertEqual(BalanceRules.shared.countAsCodes(), ["FAMILY", "HEALTH", "SELF", "WORK", "NONE"])
        XCTAssertEqual(BalanceRules.shared.countAsLabel(code: "NONE"), "Don't count")
        XCTAssertEqual(BalanceRules.shared.key(title: "  Choir   PRACTICE! "), "choir practice")
        XCTAssertEqual(MeetingPrepLead.companion.of(key: "30"), MeetingPrepLead.thirty) // Meeting prep, slice 2: the Mail sheet's chips
    }

    func testScreenshotRulesReachSwift() {
        // Screenshots and photos, slice 2: what a new screenshot looks like, and which ones are read.
        let rules = ScreenshotRules.shared
        let notice = rules.noticeFor(text: "Oakfield Dental\nYour appointment is on Thu 16 Oct at 15:00")
        XCTAssertEqual(notice?.title, "Screenshot · looks like an appointment")
        XCTAssertEqual(notice?.text, "Your appointment is on Thu 16 Oct at 15:00")
        XCTAssertNil(rules.noticeFor(text: "haha see you later then"))
        let now: Int64 = 1_800_000_000_000
        let c = [ScreenshotCandidate(id: "/a.png", takenAtMs: now - 60_000, screenshot: true)]
        XCTAssertEqual(rules.pick(candidates: c, sinceMs: now - 120_000, nowMs: now).map(\.id), ["/a.png"])
        XCTAssertTrue(rules.pick(candidates: c, sinceMs: now, nowMs: now).isEmpty)
    }

    func testVoiceWaveformReachesSwift() {
        // Call notifications, slice 7: the player's scrub bar and waveform.
        let rules = VoiceRecordingRules.shared
        XCTAssertEqual(rules.seekMs(fraction: 0.25, durationMs: 40_000), 10_000)
        XCTAssertEqual(rules.startFromMs(fraction: 1, durationMs: 40_000), 0)
        XCTAssertEqual(rules.spoken(positionMs: 12_400, durationMs: 40_000), "Voice message, 0:12 of 0:40")
        XCTAssertEqual(VoiceWaveformRules.shared.BARS, 48)
        XCTAssertTrue(VoiceWaveformRules.shared.bars(levels: [], bars: 48).isEmpty)
    }

    func testVoiceRecordingReachesSwift() {
        // Call assistant polish 8c: the player's line and bar, and only a held id is ever asked for.
        let rules = VoiceRecordingRules.shared
        XCTAssertEqual(rules.progressLine(positionMs: 12_400, durationMs: 40_000), "0:12 / 0:40")
        XCTAssertEqual(rules.fraction(positionMs: 20_000, durationMs: 40_000), 0.5)
        XCTAssertTrue(rules.isHeldId(id: "h0123456789abcdef"))
        XCTAssertFalse(rules.isHeldId(id: "../x"))
        XCTAssertEqual(rules.KEEP_MS, 30 * 24 * 60 * 60_000)
        XCTAssertTrue(rules.PRIVACY.contains("30 days"))
    }

    func testPlayMyMessagesReachesSwift() {
        // Call assistant polish 8c: "play my messages" in Talk is played on the Mac, step by step, with no AI call.
        XCTAssertTrue(VoicePlaylistRules.shared.isPlayRequest(utterance: "Play my messages"))
        XCTAssertFalse(VoicePlaylistRules.shared.isPlayRequest(utterance: "read my messages"))
        let step = TalkFlow.shared.heard(s: TalkFlow.shared.start().session, utterance: "who called?")
        XCTAssertTrue(step.effects.first is TalkEffectPlayMessages)
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "playlist-\(UUID().uuidString).db", deviceKey: nil
        )
        let playlist = core.voicePlaylist()
        XCTAssertEqual(playlist.count, 0)
        XCTAssertEqual((playlist.steps.first as? PlayStepSay)?.text, "No voice messages waiting. Anything else?")
    }

    func testReadOutsReachSwift() {
        // Talk's read-outs ("read my brief", "the headlines", "Barça news"): recognised on the Mac, no AI call, the words
        // from the core as one String.
        XCTAssertEqual(ReadOutRules.shared.request(utterance: "read my brief")?.kind, ReadOut.Kind.brief)
        XCTAssertEqual(ReadOutRules.shared.request(utterance: "Barça news")?.topicId, "barca")
        XCTAssertNil(ReadOutRules.shared.request(utterance: "read my messages"))
        let step = TalkFlow.shared.heard(s: TalkFlow.shared.start().session, utterance: "read the headlines")
        let effect = step.effects.first as? TalkEffectReadOut
        XCTAssertEqual(effect?.read.kind, ReadOut.Kind.headlines)
        XCTAssertEqual(step.session.questions, 0)
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "readout-\(UUID().uuidString).db", deviceKey: nil
        )
        XCTAssertEqual(core.talkReadOut(read: ReadOut(kind: .headlines, topicId: nil)), ReadOutRules.shared.NO_HEADLINES)
        XCTAssertEqual(ReadOutRules.shared.showing(read: ReadOut(kind: .brief, topicId: nil)), "Reading your morning brief…")
    }

    func testShoppingReachesSwift() {
        // Family sharing, slice 1: the shopping list's rules and Lists' new tab reach the Mac.
        XCTAssertEqual(ShoppingRules.shared.split(text: "milk, fish and chips;eggs"), ["milk", "fish and chips", "eggs"])
        XCTAssertEqual(ShoppingRules.shared.line(toBuy: 2, got: 1), "2 to buy · 1 got")
        XCTAssertEqual(ShoppingRules.shared.byName(by: "jordan"), "Jordan")
        XCTAssertNil(ShoppingRules.shared.byName(by: "meka"))
        XCTAssertEqual(ShoppingView.companion.EMPTY.line, "Nothing to buy")
        XCTAssertEqual(ListsView.companion.EMPTY.shopping.count, 0)
    }

    func testKitReminderReachesSwift() {
        // Weekend football, slice 1: club fixtures, the chip and the kit list reach the Mac.
        // Rule 13, release 2: the children and clubs come from About you (invented here).
        _ = Household.shared.useTyped(children: "Max, U7\nFelix, U10", clubs: "OTFC Lindenbrook United, HRFC")
        defer { _ = Household.shared.useTyped(children: "", clubs: "") }
        XCTAssertEqual(FootballRules.shared.clubIn(text: "OTFC U9s v Hilltop", clubs: FootballRules.shared.CLUBS), "OTFC")
        XCTAssertEqual(FootballRules.shared.clubIn(text: "hrfc training", clubs: FootballRules.shared.CLUBS), "HRFC")
        XCTAssertNil(FootballRules.shared.clubIn(text: "OTFCX", clubs: FootballRules.shared.CLUBS))
        XCTAssertEqual(FootballRules.shared.CHIP, "Kit reminder")
        XCTAssertEqual(FootballRules.shared.kitTaskId(eventId: "ev1"), "kev1")
        XCTAssertEqual(FootballRules.shared.kitList(tasks: []).first, "Boots")
        XCTAssertEqual(EventMarks.companion.NONE.kitTasks.count, 0)
    }

    func testLeaveOfferReachesSwift() {
        // Weekend football, slice 2: a ground's key and the remembered grounds reach the Mac.
        XCTAssertEqual(FootballRules.shared.venueKey(place: "Eastfield Town FC, Station Rd"), "eastfield town fc station rd")
        XCTAssertNil(FootballRules.shared.venueKey(place: "https://meet.google.com/abc"))
        XCTAssertEqual(FootballRules.shared.AS_LAST_TIME, "as last time")
        XCTAssertEqual(EventMarks.companion.NONE.venues.count, 0)
    }

    func testLeaveByFollowingTheTrafficReachesSwift() {
        // Weekend football, slice 2c: a Leave by taken from the drive follows later answers; the Mac reads the same marks.
        XCTAssertEqual(TravelRules.shared.EARLIER_NOTICE_MIN, 10)
        XCTAssertEqual(DriveFollow(driveMin: 40, setTravelMin: 50, checkedAtMs: 0).earlierMin, 15)
        XCTAssertEqual(EventMarks.companion.NONE.drives.count, 0)
        XCTAssertEqual(EventMarkFields.shared.TRAVEL_DRIVE_KEY, "travelDriveKey")
    }

    func testLateDraftsReachSwift() {
        // Weekend football, slice 3: the "running late" wording and choices reach the Mac.
        XCTAssertEqual(FootballRules.shared.LATE_TITLE, "Running late?")
        XCTAssertEqual(FootballRules.shared.LATE_MINUTES.count, 4)
        XCTAssertEqual(FootballRules.shared.LATE_CAPTION, "Drafts a message; you pick the chat and send it")
    }

    func testMatchResultReachesSwift() {
        // Weekend football, slice 4: "How did it go?" — the words, the line and the Swift-friendly score reach the Mac.
        XCTAssertEqual(FootballRules.shared.RESULT_TITLE, "How did it go?")
        let r = FixtureResult(scoreFor: KotlinInt(int: 3), scoreAgainst: KotlinInt(int: 1), scorers: "Leo 2, Sam", note: nil, atMs: 0)
        XCTAssertEqual(FootballRules.shared.resultLine(r: r), "Won 3–1 · Leo 2, Sam")
        XCTAssertEqual(r.forOrNone, 3)
        let noScore = FixtureResult(scoreFor: nil, scoreAgainst: nil, scorers: nil, note: "Rained off", atMs: 0)
        XCTAssertEqual(noScore.againstOrNone, -1)
        XCTAssertEqual(FootballRules.shared.savedLine(r: noScore), "Saved the note")
        XCTAssertNil(EventMarks.companion.NONE.resultOf(eventId: "x"))
    }

    func testClubChildReachesSwift() {
        // Fold review 17:56, slice 3: the children offered on a club session and the agenda's ask reach the Mac;
        // nothing is set until Meka picks one.
        // Rule 13, release 2: the children and clubs come from About you (invented here).
        _ = Household.shared.useTyped(children: "Max, U7\nFelix, U10", clubs: "OTFC Lindenbrook United, HRFC")
        defer { _ = Household.shared.useTyped(children: "", clubs: "") }
        XCTAssertEqual(ClubChildRules.shared.NAMES, ["Max", "Felix"])
        XCTAssertEqual(ClubChildRules.shared.ASK, "Max or Felix?")
        XCTAssertEqual(EntityTypes.shared.CLUB_SERIES, "club_series")
        XCTAssertTrue(EventMarks.companion.NONE.clubChildren.isEmpty)
    }

    func testShoppingFromAskReachesSwift() async throws {
        // "Add milk to shopping" in Ask and Talk: the card, its undo and Search's Shopping kind reach the Mac.
        let card = AskRules.shared.cardOf(p: AskProposalAddShopping(items: ["milk", "eggs"]), today: 0)
        XCTAssertEqual(card.line, "Add to shopping · milk and eggs")
        XCTAssertEqual(AskFieldRules.shared.kindWord(kind: .shopping), "Shopping")
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "askshop-\(UUID().uuidString).db", deviceKey: nil
        )
        let done = try await core.doAsk(card: card)
        XCTAssertEqual(done.line, "Added milk and eggs to shopping")
        XCTAssertTrue(done.undo is AskUndoTakeBackShopping)
        let undo = try XCTUnwrap(done.undo)
        let back = try await core.undoAsk(undo: undo)
        XCTAssertTrue(back.boolValue)
    }

    func testPlanDinnerFromAskReachesSwift() {
        // "Plan chilli for Friday" in Ask and Talk (meal plan, slice 2): the dinner card's words reach the Mac.
        let card = AskRules.shared.cardOf(p: AskProposalPlanDinner(day: 1, title: "Pizza", mealId: nil), today: 0)
        XCTAssertEqual(card.line, "Dinner · Pizza for tomorrow · new favourite")
        XCTAssertEqual(card.button, "Plan")
        XCTAssertEqual(TalkRules.shared.phrase(p: card.proposal, today: 0, past: true), "planned Pizza for dinner tomorrow")
    }

    func testSkipDateNightFromAskReachesSwift() {
        // "Skip date night next week" in Ask and Talk (date night, slice 3): the skip card's words reach the Mac.
        let card = AskRules.shared.cardOf(p: AskProposalSkipDateNight(day: 14), today: 0)
        XCTAssertEqual(card.line, "Skip date night · Thu 15 Jan")
        XCTAssertEqual(card.button, "Skip")
        XCTAssertEqual(TalkRules.shared.phrase(p: card.proposal, today: 13, past: true), "skipped date night tomorrow")
    }

    func testSkipLifeAdminFromAskReachesSwift() {
        // "Skip admin this week" in Ask and Talk (life admin hour, slice 3): the skip card's words reach the Mac.
        let card = AskRules.shared.cardOf(p: AskProposalSkipLifeAdmin(weekStart: -3, day: 2, whenLine: "Sat 3 Jan 10:00–11:00"), today: 0)
        XCTAssertEqual(card.line, "Skip this week's life admin hour · Sat 3 Jan 10:00–11:00")
        XCTAssertEqual(card.button, "Skip")
        XCTAssertEqual(TalkRules.shared.phrase(p: card.proposal, today: 1, past: true), "skipped this week's life admin hour tomorrow")
        XCTAssertEqual(AskItemKind.lifeAdmin.wire, "life_admin")
    }

    func testGiftIdeaFromAskReachesSwift() {
        // "Note a scarf as a gift idea for Mum" in Ask and Talk (gift planner, slice 2): the card's words reach the Mac.
        let card = AskRules.shared.cardOf(p: AskProposalAddGiftIdea(occasionId: "o1", title: "Mum's birthday", idea: "Silk scarf"), today: 0)
        XCTAssertEqual(card.line, "Gift idea for Mum's birthday · Silk scarf")
        XCTAssertEqual(card.button, "Note")
        XCTAssertEqual(TalkRules.shared.phrase(p: card.proposal, today: 0, past: true), "noted Silk scarf as a gift idea for Mum's birthday")
        XCTAssertEqual(AskItemKind.gifts.wire, "gifts")
    }

    func testPromiseFromAskReachesSwift() {
        // "I told Theo I'd fix his bike" in Ask and Talk (People, slice 3): the card's words reach the Mac.
        let card = AskRules.shared.cardOf(p: AskProposalAddPromise(to: "Theo", title: "Fix his bike", byDay: KotlinLong(value: 1)), today: 0)
        XCTAssertEqual(card.line, "Promise to Theo · Fix his bike · by tomorrow")
        XCTAssertEqual(card.button, "Add")
        XCTAssertEqual(TalkRules.shared.phrase(p: card.proposal, today: 0, past: true), "added your promise to Theo: Fix his bike, by tomorrow")
        XCTAssertEqual(AskItemKind.people.wire, "people")
    }

    func testBookingFromAskReachesSwift() {
        // "Add our flight to Barcelona" in Ask and Talk (Travel mode, slice 2a): the card's words reach the Mac.
        let entry = BookingEntry(tripId: "b", kind: .flight, title: "Flight BA478", day: 1, endDay: nil, minute: KotlinInt(value: 460), ref: "ABC123")
        let card = AskRules.shared.cardOf(p: AskProposalAddBooking(entry: entry, tripName: "Barcelona"), today: 0)
        XCTAssertEqual(card.line, "Add to Barcelona · Flight BA478 · Fri 2 Jan 07:40 · ref ABC123")
        XCTAssertEqual(card.button, "Add")
        XCTAssertEqual(TalkRules.shared.phrase(p: card.proposal, today: 0, past: true), "added Flight BA478 tomorrow at 07:40 to the Barcelona trip")
        XCTAssertEqual(AskItemKind.trips.wire, "trips")
    }

    func testHomeUpkeepReachesSwift() {
        // Home upkeep, slice 1: the suggested jobs and their lines reach the Mac (only Strings and an Int64 cross).
        let sat = CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 10)
        let view = HomeUpkeepRules.shared.view(items: [], today: sat)
        XCTAssertEqual(view.rows.count, 6)
        XCTAssertEqual(view.summary, "6 jobs a home needs · add the ones that apply")
        let alarms = view.rows[0]
        XCTAssertEqual(alarms.presetId, "alarms")
        XCTAssertEqual(alarms.line, "Every month · a nudge on the day")
        XCTAssertFalse(alarms.tracked)
        XCTAssertTrue(alarms.dateKnown)
        XCTAssertFalse(view.rows[4].dateKnown) // home insurance: Meka sets the date
        XCTAssertEqual(HomeUpkeepRules.shared.LABEL, "Home upkeep")
        XCTAssertEqual(RenewalRules.shared.repeatLabel(r: .halfYearly), "Every 6 months")
    }

    func testCarReachesSwift() {
        // Car on autopilot, slice 1: the car's rows, the registration check and display reach the Mac (only Strings cross).
        let view = CarRules.shared.view(registration: "AB12CDE", items: [])
        XCTAssertEqual(view.rows.map { $0.presetId }, ["mot", "tax", "insurance", "service"])
        XCTAssertEqual(view.registration, "AB12 CDE")
        XCTAssertEqual(view.summary, "AB12 CDE · add the dates that apply")
        XCTAssertFalse(view.rows[0].tracked)
        XCTAssertEqual(view.rows[0].line, "Every year · reminders a month and a week before · you set the date")
        XCTAssertEqual(CarRules.shared.registrationError(text: "??"), "Type the registration, like AB12 CDE")
        XCTAssertNil(CarRules.shared.registrationError(text: "ab12 cde"))
        XCTAssertEqual(CarRules.shared.LABEL, "Car")
        // Two cars: the first car's id and the Undo line reach the Mac.
        XCTAssertEqual(CarRules.shared.ENTITY_ID, "car")
        XCTAssertEqual(CarRules.shared.removedLine(title: "the Golf"), "Removed the Golf · its dates stay on the radar")
        XCTAssertEqual(view.carId, "car")
        XCTAssertEqual(RenewalRules.shared.kindLabel(k: .carService), "Car service")
        // Slice 2: without the DVLA's answer there is no DVLA line and nothing to offer.
        XCTAssertNil(view.dvlaLine)
        XCTAssertNil(view.historyLine)
        XCTAssertNil(view.rows[0].dvlaOffer)
        XCTAssertEqual(CarCheckRules.shared.ENTITY_ID, "car_check")
    }

    func testMoneyDatesReachSwift() {
        // UK money deadlines: the five rows and their lines reach the Mac (only Strings and Bools cross).
        let view = MoneyDateRules.shared.view(items: [], today: 20738) // Mon 12 Oct 2026
        XCTAssertEqual(view.rows.map { $0.presetId }, ["self_assessment", "isa", "energy", "broadband", "pension"])
        XCTAssertEqual(view.summary, "Tax, ISA, contracts and pension · dates only")
        XCTAssertEqual(view.rows[0].line, "Every year · next Sun 31 Jan · reminders 4 weeks and a week before")
        XCTAssertTrue(view.rows[0].dateKnown)
        XCTAssertFalse(view.rows[2].dateKnown)
        XCTAssertFalse(view.rows[2].tracked)
        XCTAssertEqual(MoneyDateRules.shared.LABEL, "Money dates")
    }

    func testBillsReachSwift() {
        // Bills due: the pay link's words and the Pay button's name reach the Mac (only Strings and Bools cross).
        XCTAssertEqual(BillRules.shared.site(link: "https://www.octopus.energy/dashboard"), "octopus.energy")
        XCTAssertEqual(BillRules.shared.payLabel(link: "https://anglianwater.co.uk/account"), "Pay on anglianwater.co.uk ↗")
        XCTAssertEqual(NotifyRules.shared.actionName(a: NoticeAction.pay), "PAY") // MacNotifier.payAction
        XCTAssertTrue(NoticeAction.pay.shownOn(send: false))
        XCTAssertEqual(Int(BillRules.shared.REMIND_DAYS), 3)
    }

    func testRenewalDoneFromAskReachesSwift() {
        // "I've tested the smoke alarms" in Ask and Talk (home upkeep, slice 2): the radar card's words reach the Mac.
        let p = AskProposalRenewalDone(id: "r1", title: "Test the smoke and CO alarms", doneLabel: "Done", dueDay: 0, nextDay: KotlinLong(value: 31))
        let card = AskRules.shared.cardOf(p: p, today: 0)
        XCTAssertEqual(card.line, "Done · Test the smoke and CO alarms · next Sun 1 Feb")
        XCTAssertEqual(card.button, "Done")
        XCTAssertEqual(TalkRules.shared.phrase(p: card.proposal, today: 0, past: true), "marked Test the smoke and CO alarms done, next due Sunday 1 February")
    }

    func testAddWinFromAskReachesSwift() {
        // "Add a win: …" in Ask and Talk (work wins, slice 2): the card's words reach the Mac.
        let card = AskRules.shared.cardOf(p: AskProposalAddWin(text: "Closed the Henley deal"), today: 0)
        XCTAssertEqual(card.line, "Keep in this week's wins · “Closed the Henley deal”")
        XCTAssertEqual(card.button, "Keep")
        XCTAssertEqual(TalkRules.shared.phrase(p: card.proposal, today: 0, past: true), "kept Closed the Henley deal in this week's wins")
    }

    func testMoveEventFromAskReachesSwift() {
        // "Move the dentist to 4pm" in Ask and Talk (Edit your calendars, completeness slice 4): the card's words reach the Mac.
        let card = AskRules.shared.cardOf(p: AskProposalMoveEvent(eventId: "g1", title: "Dentist", day: 1, minute: 960, provider: "google"), today: 0)
        XCTAssertEqual(card.line, "Move “Dentist” to Tomorrow · 16:00 · Google")
        XCTAssertEqual(card.button, "Move")
        XCTAssertNil(TrustRules.shared.kindOf(p: card.proposal))
    }

    func testQueueDoneFromAskReachesSwift() {
        // "I've read the BBC one" in Ask and Talk (train-time queue, slice 3): the card's words reach the Mac.
        let card = AskRules.shared.cardOf(p: AskProposalQueueDone(id: "q1", title: "Why trains run late"), today: 0)
        XCTAssertEqual(card.line, "Done · “Why trains run late” · off the train queue")
        XCTAssertEqual(card.button, "Done")
        XCTAssertEqual(TalkRules.shared.phrase(p: card.proposal, today: 0, past: true), "marked Why trains run late done in the train queue")
    }

    func testAddFactFromAskReachesSwift() {
        // "Remember that my shoe size is 10" in Ask and Talk (What MEKA knows, slice 2): the card's words reach the Mac.
        let card = AskRules.shared.cardOf(p: AskProposalAddFact(label: "Shoe size", value: "10", factId: nil), today: 0)
        XCTAssertEqual(card.line, "Keep on What Navilife knows · Shoe size: 10")
        XCTAssertEqual(card.button, "Keep")
        XCTAssertEqual(TalkRules.shared.phrase(p: card.proposal, today: 0, past: true), "remembered Shoe size as 10")
        let update = AskRules.shared.cardOf(p: AskProposalAddFact(label: "GP", value: "Dr Shah", factId: "f1"), today: 0)
        XCTAssertEqual(update.button, "Update")
    }

    func testAddGoalFromAskReachesSwift() {
        // "Add a goal to run 5k by December" in Ask and Talk (Goals, properly, slice 8): the card's words reach the Mac.
        let today: Int64 = 20740 // Wed 14 Oct 2026
        let card = AskRules.shared.cardOf(p: AskProposalAddGoal(title: "Run 5k", horizon: .quarter, dueDay: KotlinLong(longLong: 20818)), today: today)
        XCTAssertEqual(card.line, "Add goal “Run 5k” · This quarter · by Thu 31 Dec")
        XCTAssertEqual(card.button, "Add")
        XCTAssertEqual(TalkRules.shared.phrase(p: card.proposal, today: today, past: true), "added the goal Run 5k, done by Thursday 31 December")
    }

    func testAddChecklistFromAskReachesSwift() {
        // "Add eggs to the party checklist" in Ask and Talk (Checklists slice 3b): the card's words reach the Mac.
        let card = AskRules.shared.cardOf(p: AskProposalAddChecklist(taskId: "t", title: "Party", items: ["eggs", "cake"]), today: 0)
        XCTAssertEqual(card.line, "Add to “Party” · eggs and cake")
        XCTAssertEqual(card.button, "Add")
        XCTAssertEqual(TalkRules.shared.phrase(p: card.proposal, today: 0, past: true), "added eggs and cake to the Party checklist")
    }

    func testAddErrandFromAskReachesSwift() {
        // "Add batteries to Tesco" in Ask and Talk (errands by location, slice 2b): the card's words reach the Mac.
        let card = AskRules.shared.cardOf(p: AskProposalAddErrand(place: "Tesco", things: ["milk", "batteries"]), today: 0)
        XCTAssertEqual(card.line, "Errand at Tesco · milk and batteries")
        XCTAssertEqual(card.button, "Add")
        XCTAssertEqual(TalkRules.shared.phrase(p: card.proposal, today: 0, past: true), "added milk and batteries to your errands at Tesco")
        XCTAssertEqual(SearchKind.errand.label, "Errands")
    }

    func testBookLeaveFromAskReachesSwift() {
        // "Book the 2nd of November off" in Ask and Talk (annual leave, slice 2b): the card's words reach the Mac.
        let card = AskRules.shared.cardOf(p: AskProposalBookLeave(title: "Annual leave", startDay: 20759, endDay: 20759, days: 1), today: 0)
        XCTAssertEqual(card.line, "Book annual leave · Mon 2 Nov · 1 day")
        XCTAssertEqual(card.button, "Book")
        XCTAssertEqual(TalkRules.shared.phrase(p: card.proposal, today: 0, past: true), "booked annual leave for Monday 2 November")
    }

    func testEarnedTrustReachesSwift() {
        // Earned trust: the offer after five yeses in a row and its two answers reach the Mac (only the kind's key, a
        // String, and a Bool cross back into the core).
        let rules = TrustRules.shared
        XCTAssertNil(rules.offer(k: TrustKind.addTask, s: TrustState(streak: rules.STREAK - 1, trusted: false, declined: false)))
        let offer = rules.offer(k: TrustKind.addTask, s: TrustState(streak: rules.STREAK, trusted: false, declined: false))
        XCTAssertEqual(offer?.kindKey, "add_task")
        XCTAssertEqual(offer?.yes, "Just do it")
        XCTAssertEqual(offer?.no, "Keep asking")
        XCTAssertNil(rules.kindOf(p: AskProposalTimer(minutes: 20)))
        XCTAssertEqual(rules.autoLine(doneLine: "Added “Milk”"), "Added “Milk” · done for you")
    }

    func testFamilyReachesSwift() {
        // Family sharing, slice 4: Settings → Family's rules reach the Mac (only Strings cross from the sheet).
        let rules = FamilyRules.shared
        XCTAssertEqual(rules.validName(raw: "  Jordan "), "Jordan")
        XCTAssertNil(rules.validName(raw: "meka"))
        XCTAssertEqual(rules.display(name: "jordan"), "Jordan")
        XCTAssertEqual(rules.state(server: "joined"), .joined)
        let link = rules.link(id: "fam1", name: "jordan", url: "https://meka.example/family#ab")
        XCTAssertEqual(link.name, "Jordan")
        XCTAssertTrue(link.shareText.hasSuffix("https://meka.example/family#ab"))
        XCTAssertEqual(link.note, rules.NOTE)
    }

    func testWorkWinsReachSwift() {
        // Work wins, slice 1: a typed line is tidied by the core's rules, a past week's win is dated its Friday, and the
        // review's empty section reaches the Mac (only a String crosses from the field).
        let rules = WinRules.shared
        XCTAssertEqual(rules.read(text: "  - shipped   the Q3 report "), "Shipped the Q3 report")
        XCTAssertNil(rules.read(text: "   "))
        let mon5 = CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 5)
        XCTAssertEqual(rules.dayFor(weekStart: mon5, today: mon5 + 7), mon5 + 4)
        let empty = WinsView.companion.EMPTY
        XCTAssertEqual(empty.weekLine, rules.NONE_THIS_WEEK)
        XCTAssertEqual(empty.hint, rules.HINT_THIS_WEEK)
        XCTAssertFalse(empty.asking)
        XCTAssertEqual(NoticeSource.wins.defaultTier, NoticeTier.headsUp)
    }

    func testAnnualLeaveReachesSwift() {
        // Annual leave planner, slice 1: a typed stretch is read by the core's rules (leave already taken needs its
        // year), the words and the empty view reach the Mac (only Strings and Ints cross from the sheet).
        let rules = LeaveRules.shared
        let sat = CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 10)
        let jan1 = CivilDate.shared.toEpochDay(year: 2026, month: 1, day: 1)
        let e = rules.read(text: "Spain 18 Dec – 4 Jan", today: sat, earliest: jan1)
        XCTAssertEqual(e?.title, "Spain")
        XCTAssertEqual(e?.startDay, CivilDate.shared.toEpochDay(year: 2026, month: 12, day: 18))
        XCTAssertEqual(rules.read(text: "27–30 Oct", today: sat, earliest: jan1)?.title, rules.TITLE)
        XCTAssertNil(rules.read(text: "whenever", today: sat, earliest: jan1))
        XCTAssertEqual(rules.daysWord(n: 1), "1 day")
        XCTAssertEqual(rules.allowanceLine(s: LeaveSettings(allowance: 27, yearStartMonth: 4)), "27 days a year · the leave year starts 1 Apr")
        let empty = LeaveView.companion.EMPTY
        XCTAssertEqual(empty.summary, rules.EMPTY_LINE)
        XCTAssertTrue(empty.holidays.isEmpty)
        XCTAssertEqual(empty.allowance, Int32(rules.DEFAULT_ALLOWANCE))
        // Slice 2: the nudge is a heads-up of its own, listed in Notifications; it targets the Leave pane.
        XCTAssertEqual(NoticeSource.leave.defaultTier, NoticeTier.headsUp)
        XCTAssertEqual(NotifyRules.shared.targetName(t: .leave), "LEAVE")
    }

    func testTrainQueueReachesSwift() {
        // Train-time queue, slice 1: a pasted link is found and tidied by the core's rules, podcasts are told from
        // articles, and the queue's view reaches the Mac (only Strings cross from the sheet).
        let rules = QueueRules.shared
        XCTAssertEqual(rules.link(text: "Read this: https://www.bbc.co.uk/news/uk-123."), "https://www.bbc.co.uk/news/uk-123")
        XCTAssertNil(rules.link(text: "no link here"))
        XCTAssertEqual(rules.host(url: "https://www.bbc.co.uk/news/uk-123"), "bbc.co.uk")
        XCTAssertEqual(rules.kind(url: "https://overcast.fm/+abc", title: "x"), .podcast)
        XCTAssertEqual(rules.countLine(articles: 2, podcasts: 1), "2 articles · 1 podcast")
        let empty = QueueView.companion.EMPTY
        XCTAssertEqual(empty.summary, rules.EMPTY_SUMMARY)
        XCTAssertTrue(empty.waiting.isEmpty)
    }

    func testAppointmentsReachSwift() {
        // Appointment nudges, slice 1: the core reads a typed line into what, how often and the last visit, and the
        // empty view (with its six common ones) reaches the Mac; only Strings cross from the sheet and the card.
        let rules = AppointmentRules.shared
        let today = CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 11)
        let p = rules.parse(text: "Dentist every 6 months, last 12 Apr", today: today, nowMinute: 600)
        XCTAssertEqual(p?.title, "Dentist")
        XCTAssertEqual(p?.everyMonths?.intValue, 6)
        XCTAssertEqual(p?.lastDay?.int64Value, CivilDate.shared.toEpochDay(year: 2026, month: 4, day: 12))
        XCTAssertEqual(rules.intervalLabel(weeks: 6, months: 0), "Every 6 weeks")
        XCTAssertEqual(AppointmentView.companion.EMPTY.summary, rules.EMPTY_SUMMARY)
        XCTAssertEqual(AppointmentView.companion.EMPTY.suggestions.map(\.title), ["Dentist", "Hygienist", "Optician", "Haircut", "Car service", "GP check-up"])
        XCTAssertTrue(AppointmentView.companion.EMPTY.nudges.isEmpty)
    }

    func testAppointmentsInAskAndSearchReachSwift() {
        // Appointment nudges, slice 2a: Search has an Appointments group that opens the sheet; an empty list sends Ask nothing.
        XCTAssertEqual(SearchKind.appointment.label, "Appointments")
        XCTAssertTrue(AppointmentAskRules.shared.askLines(view: AppointmentView.companion.EMPTY).isEmpty)
    }

    func testAppointmentCallAndEmailReachSwift() {
        // Appointment nudges, slice 2b: Call to book's plan, the booking email and the heads-up's source reach the Mac.
        let rules = AppointmentRules.shared
        let today = CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 11)
        let dentist = AppointmentItem(
            id: "d", title: "Dentist", everyWeeks: 0, everyMonths: 6,
            lastDay: KotlinLong(value: CivilDate.shared.toEpochDay(year: 2026, month: 5, day: 1)),
            bookedDay: nil, bookedMinute: nil, laterDay: nil, addedAtMs: 0
        )
        XCTAssertEqual(rules.callPlan(item: dentist, today: today, nowMinute: 600).line, "Call to book: Dentist · planned tomorrow at 12:30")
        XCTAssertTrue(rules.bookingEmail(item: dentist, today: today).hasPrefix("Subject: Booking a dental check-up"))
        XCTAssertEqual(rules.CALL_LABEL, "Call to book")
        XCTAssertEqual(rules.EMAIL_LABEL, "Copy email")
        XCTAssertEqual(NoticeSource.appointments.label, "Appointments due")
    }

    func testChecklistReachesSwift() async throws {
        // Checklists in tasks, slice 1: a pasted list becomes its items, ticks move them under Ticked, Untick all
        // returns what it unticked (only Strings cross from the detail).
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "checklist-\(UUID().uuidString).db", deviceKey: nil
        )
        XCTAssertEqual(ChecklistRules.shared.split(text: "eggs, milk\nbread"), ["eggs", "milk", "bread"])
        let id = try await core.addTask(title: "Weekly shop")
        let ids = try await core.addSteps(taskId: id, text: "eggs, milk\nbread")
        XCTAssertEqual(ids.count, 3)
        try await core.setStepDone(stepId: ids[1], done: true)
        let task = try XCTUnwrap(core.today.value.upNext)
        let view = core.checklistView(task: task)
        XCTAssertEqual(view.label, "Checklist")
        XCTAssertEqual(view.toTick.map(\.text), ["eggs", "bread"])
        XCTAssertEqual(view.ticked.map(\.text), ["milk"])
        XCTAssertEqual(view.line, "1 of 3 ticked")
        let unticked = try await core.untickAllSteps(taskId: id)
        XCTAssertEqual(unticked, [ids[1]])
        XCTAssertEqual(ChecklistRules.shared.untickedLine(count: 1), "Unticked 1 item")
    }

    func testChecklistReorderAndShopModeReachSwift() async throws {
        // Checklists in tasks, slice 2: a row dragged within its group, and Shop mode's view (only Strings cross).
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "shopmode-\(UUID().uuidString).db", deviceKey: nil
        )
        let id = try await core.addTask(title: "Weekly shop")
        let ids = try await core.addSteps(taskId: id, text: "eggs, milk, bread")
        try await core.moveStepInGroup(taskId: id, stepId: ids[2], toGroupIndex: 0)
        try await core.setStepDone(stepId: ids[0], done: true)
        let task = try XCTUnwrap(core.today.value.upNext)
        let shop = core.shopMode(task: task)
        XCTAssertEqual(shop.toGet.map(\.text), ["bread", "milk"])
        XCTAssertEqual(shop.done.map(\.text), ["eggs"])
        XCTAssertEqual(shop.line, "2 left · 1 ticked")
        XCTAssertEqual(shop.doneLabel, "Ticked")
        XCTAssertEqual(ShopModeRules.shared.line(left: 0, done: 2, doneWord: "got"), "All 2 got")
        XCTAssertTrue(ShopModeRules.shared.offer(itemCount: 1))
    }

    func testChecklistTemplatesReachSwift() async throws {
        // Checklists in tasks, slice 3a: Save as template, Start from template and Remove (only Strings cross in;
        // the removed template goes back to the core once, for Undo).
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "templates-\(UUID().uuidString).db", deviceKey: nil
        )
        let id = try await core.addTask(title: "Weekly shop")
        _ = try await core.addSteps(taskId: id, text: "eggs, milk, bread")
        let task = try XCTUnwrap(core.today.value.upNext)
        XCTAssertEqual(core.templateName(task: task), "Weekly shop")
        let saved = try await core.saveChecklistTemplate(taskId: id, name: "Weekly shop")
        XCTAssertEqual(saved.line, "Saved “Weekly shop” as a template")
        XCTAssertFalse(saved.replaced)
        let view = try await core.checklistTemplates()
        XCTAssertEqual(view.saved.map(\.name), ["Weekly shop"])
        XCTAssertFalse(view.starters.contains { $0.name == "Weekly shop" })

        let next = try await core.addTask(title: "Shop Saturday")
        let tid = try XCTUnwrap(view.saved.first?.id)
        let applied = try await core.startFromTemplate(taskId: next, templateId: tid)
        XCTAssertEqual(applied.line, "Added 3 items from “Weekly shop”")
        XCTAssertEqual(applied.stepIds.count, 3)

        let removed = try await core.removeChecklistTemplate(id: tid)
        XCTAssertEqual(removed?.name, "Weekly shop")
        let empty = try await core.checklistTemplates()
        XCTAssertTrue(empty.saved.isEmpty)
        XCTAssertEqual(ChecklistTemplateRules.shared.removedLine(name: "Weekly shop"), "Removed the “Weekly shop” template")
    }

    func testErrandsReachSwift() {
        // Errands by location, slice 1: a typed line is read by the core's rules into a place and its things, and the
        // empty view reaches the Mac (only Strings cross from the sheet).
        let rules = ErrandRules.shared
        let p = rules.parse(text: "when I'm near Tesco: milk, batteries")
        XCTAssertEqual(p?.place, "Tesco")
        XCTAssertEqual(p?.things, ["milk", "batteries"])
        XCTAssertNil(rules.parse(text: "milk, batteries"))
        XCTAssertEqual(rules.joined(things: ["milk", "batteries"]), "milk and batteries")
        XCTAssertEqual(ErrandView.companion.EMPTY.summary, rules.EMPTY_SUMMARY)
        XCTAssertTrue(ErrandView.companion.EMPTY.places.isEmpty)
    }

    func testFactsPageReachesSwift() {
        // What MEKA knows (facts page, slice 1): a typed fact is read by the core's rules and grouped, secrets and card,
        // bank or ID numbers are turned away, and only the facts that bear on a question go with it.
        let rules = FactRules.shared
        let reg = rules.read(text: "my car reg is AB12 CDE")
        XCTAssertEqual(reg?.label, "Car reg")
        XCTAssertEqual(reg?.value, "AB12 CDE")
        XCTAssertEqual(rules.groupOf(label: "Car reg"), FactGroup.car)
        XCTAssertNil(rules.refusal(text: "GP: Dr Patel, 01632 123456"))
        XCTAssertEqual(rules.refusal(text: "Wifi password: BlueFox77"), rules.REFUSED_SECRET)
        XCTAssertEqual(rules.refusal(text: "Bank card: 4111 1111 1111 1111"), rules.REFUSED_BANK)
        XCTAssertEqual(rules.refusal(text: "Passport: 123456789"), rules.REFUSED_ID)
        let fact = LifeFact(id: "f1", label: "Car reg", value: "AB12 CDE", group: .car, aiOff: false, addedAtMs: 0)
        XCTAssertEqual(FactAskRules.shared.askLines(facts: [fact], question: "what's the car's reg?"),
                       ["Facts · 1 kept · all below", "Fact · Car · Car reg: AB12 CDE"])
        let view = rules.view(facts: [fact])
        XCTAssertEqual(view.summary, "1 fact · Navilife answers from them")
        XCTAssertEqual(view.groups.map(\.label), ["Car"])
        XCTAssertEqual(view.groups.first?.rows.first?.aiButton, rules.KEEP_BUTTON)
        XCTAssertEqual(FactsView.companion.EMPTY.summary, rules.EMPTY_SUMMARY)
        // Slice 3: Copy puts the fact alone on the pasteboard and says what was copied.
        if let row = view.groups.first?.rows.first {
            XCTAssertEqual(row.copyText, "AB12 CDE")
            XCTAssertEqual(row.copySpoken, "Copy Car reg")
            XCTAssertEqual(rules.copiedLine(row: row), "Copied Car reg · AB12 CDE")
        } else {
            XCTFail("the Car group has its row")
        }
        XCTAssertEqual(rules.COPY_BUTTON, "Copy")
    }

    func testEmergencyCardReachesSwift() {
        // Emergency card, slice 1: a typed line is read by the core's rules, secrets and numbers are turned away, and the
        // card's parts reach the Mac (only Strings cross from the sheet).
        let rules = EmergencyRules.shared
        let gp = rules.read(text: "GP: Dr Patel, 01632 123456")
        XCTAssertEqual(gp?.title, "GP")
        XCTAssertEqual(gp?.detail, "Dr Patel, 01632 123456")
        XCTAssertNil(rules.refusal(section: .call, text: "GP: Dr Patel, 01632 123456"))
        XCTAssertEqual(rules.refusal(section: .accounts, text: "Bank PIN: 4821"), rules.REFUSED_SECRET)
        XCTAssertEqual(rules.refusal(section: .documents, text: "Passport: 123456789"), rules.REFUSED_NUMBER)
        XCTAssertEqual(rules.phone(detail: "Dr Patel, 01632 123456"), "01632123456")
        let empty = EmergencyView.companion.EMPTY
        XCTAssertEqual(empty.groups.map(\.label), ["Who to call", "Where things are", "Key accounts"])
        XCTAssertEqual(empty.groups.map(\.section), ["call", "documents", "accounts"])
        XCTAssertEqual(empty.summary, rules.EMPTY_SUMMARY)
    }

    func testMealPlanReachesSwift() {
        // Meal plan → shopping, slice 1: a favourite typed once is read by the core's rules, and the week's lines reach
        // the Mac (only Strings and an Int64 cross from the sheet).
        let rules = MealRules.shared
        let chilli = rules.read(text: "Chilli: mince, kidney beans, rice")
        XCTAssertEqual(chilli?.title, "Chilli")
        XCTAssertEqual(chilli?.ingredients, ["mince", "kidney beans", "rice"])
        XCTAssertNil(rules.read(text: "  "))
        let sat = CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 10)
        XCTAssertEqual(rules.dayLabel(day: sat, today: sat), "Tonight")
        XCTAssertEqual(rules.dayLabel(day: sat + 2, today: sat), "Mon 12 Oct")
        XCTAssertEqual(rules.shoppedLine(added: 5, already: 1), "Added 5 to shopping · 1 was already on it")
        XCTAssertEqual(rules.plannedLine(day: sat, today: sat, title: "Chilli"), "Chilli for tonight")
        XCTAssertEqual(MealPlanView.companion.EMPTY.summary, rules.NO_FAVOURITES)
        XCTAssertNil(MealPlanView.companion.EMPTY.shoppingLabel)
    }

    func testDateNightReachesSwift() {
        // Date night, slice 1: the sheet's chips and lines come from the core's rules (only Ints, an Int64 and a Bool
        // cross from the sheet).
        let rules = DateNightRules.shared
        let sat = CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 10)
        let fri = rules.firstNight(weekday: 5, today: sat, nextWeek: false)
        XCTAssertEqual(fri, CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 16))
        XCTAssertEqual(rules.WEEKDAY_CHOICES.count, 7)
        XCTAssertEqual(rules.START_CHOICES.map { $0.int32Value }.first, 18 * 60)
        XCTAssertEqual(LocalClock.companion.formatMinute(m: 19 * 60), "19:00")
        let setting = DateNightSetting(weekday: 5, startMin: 19 * 60, anchorDay: fri, skipped: [], booked: [])
        let v = rules.view(s: setting, today: sat, minuteOfDay: 10 * 60)
        XCTAssertEqual(v.summary, "Every other Friday from 19:00 · next Fri 16 Oct")
        XCTAssertEqual(v.nights.first?.day, fri)
        XCTAssertEqual(v.starts.map(\.chosen), [true, false])
        XCTAssertEqual(rules.todayLine(s: setting, today: fri, minuteOfDay: 13 * 60)?.text, "Date night tonight from 19:00")
        XCTAssertEqual(rules.skipLine(day: fri, today: sat, skip: true), "Skipped Fri 16 Oct · the evening is free to plan")
        XCTAssertEqual(DateNightView.companion.EMPTY.summary, rules.OFF_LINE)
    }

    func testLifeAdminReachesSwift() {
        // Life admin hour, slice 1: the sheet's chips, lines and jobs come from the core's rules (only Ints and a Bool
        // cross from the sheet).
        let rules = LifeAdminRules.shared
        XCTAssertEqual(rules.WEEKDAY_CHOICES.count, 7)
        XCTAssertEqual(rules.START_CHOICES.map { $0.int32Value }.first, 9 * 60)
        XCTAssertTrue(rules.isAdmin(title: "Call the GP"))
        XCTAssertFalse(rules.isAdmin(title: "Gym"))
        let empty = LifeAdminView.companion.EMPTY
        XCTAssertEqual(empty.summary, rules.OFF_LINE)
        XCTAssertFalse(empty.on)
        XCTAssertEqual(empty.weekday, 6)
        XCTAssertEqual(NoticeSource.lifeAdmin.label, "Life admin hour")
        let job = LifeAdminJob(id: "task:a", kind: .task, title: "Call the GP", line: nil, spoken: "Call the GP", overdue: false)
        XCTAssertEqual(job.kind, LifeAdminJobKind.task)
    }

    func testAutonomyReachesSwift() {
        // What MEKA may do (policy engine UI, slice 1): the sheet's rows and lines come from the core's rules (only a
        // Bool or a kind's key crosses from the sheet).
        let rules = AutonomyRules.shared
        let empty = AutonomyView.companion.EMPTY
        XCTAssertFalse(empty.paused)
        XCTAssertEqual(empty.summary, rules.NONE_LINE)
        XCTAssertEqual(empty.pauseButton, rules.PAUSE)
        XCTAssertEqual(empty.sections.map(\.label), [rules.LISTS_LABEL, rules.LOCKED_LABEL])
        XCTAssertEqual(empty.sections[0].rows.map(\.key), ["add_task", "tick_off", "shopping", "win"])
        XCTAssertTrue(empty.sections[1].rows.allSatisfy { $0.locked && $0.action == nil })
        let trusted = rules.trustRow(k: TrustKind.addTask, s: TrustState(streak: TrustRules.shared.STREAK, trusted: true, declined: false), paused: false)
        XCTAssertEqual(trusted.action, rules.ASK_FIRST)
        XCTAssertTrue(trusted.onItsOwn)
        // "What Navilife may do" completeness: Today's paused line, nil while not paused.
        XCTAssertNil(empty.todayLine)
        XCTAssertEqual(AutonomyAskRules.shared.request(q: "pause Navilife"), AutonomyAsk.pause)
    }

    func testApprovalsReachSwift() async throws {
        // The approvals inbox (slice 1): the flow starts empty, an answer to a request that isn't there is refused with
        // the core's line, and the Mac's Touch ID lines come from the core's rules.
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "approvals-\(UUID().uuidString).db", deviceKey: nil
        )
        XCTAssertTrue(core.approvals.value.isEmpty)
        let answer = try await core.declineApproval(approvalId: "nope")
        XCTAssertFalse(answer.done)
        XCTAssertEqual(answer.line, ApprovalRefusal.gone.line)
        // Trading approvals, slice 2: what to sign comes first (refused here, so no Touch ID is asked), and a yes
        // without the approval key's signature is refused.
        let prep = try await core.prepareApproval(approvalId: "nope")
        XCTAssertEqual(prep.refusal, ApprovalRefusal.gone.line)
        XCTAssertNil(prep.message)
        let refused = try await core.approve(
            approvalId: "nope", decisionKey: prep.decisionKey, decidedAtMs: prep.decidedAtMs,
            publicKeyBase64: "", signatureBase64: "", keyLabel: "Mac"
        )
        XCTAssertFalse(refused.done)
        let registered = try await core.registerApprovalKey(publicKeyBase64: "MFkw-test", label: "Mac")
        XCTAssertTrue(registered.boolValue)
        XCTAssertEqual(ApprovalRules.shared.KEY_RENEWED_MAC, "Touch ID changed on this Mac · approval key renewed · approve again")
        XCTAssertEqual(ApprovalRules.shared.LOCK_MAC, "Approving needs Touch ID")
    }

    func testApprovalNoticesReachSwift() async throws {
        // Approvals inbox, slice 2: both of a request's notices post in one place (so the Critical one replaces the
        // first), the governor's result says what to take down, and the Notifications sheet doesn't list approvals.
        XCTAssertEqual(ApprovalRules.shared.noticeSlot(key: "approval:ab12:ending"), "approval:ab12")
        let labels = (0..<Int(NotifyRules.shared.sourceCount)).map {
            NotifyRules.shared.sourceLabel(s: NotifyRules.shared.sourceAt(index: Int32($0)))
        }
        XCTAssertFalse(labels.contains("Approvals (trades, payments, documents)"))
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "approval-notices-\(UUID().uuidString).db", deviceKey: nil
        )
        let result = try await core.governNotifications(state: nil, device: .all)
        let withdraw: [String] = result.withdraw
        XCTAssertTrue(withdraw.isEmpty)
    }

    func testHandleThisReachesSwift() async throws {
        // "Handle this", slice 1: Services hands the core a String; without a connection it says so and keeps nothing,
        // and a bare link never goes anywhere.
        let rules = HandleThisRules.shared
        XCTAssertFalse(rules.worthAsking(text: "https://example.com/article", subject: nil))
        XCTAssertTrue(rules.worthAsking(text: "Please return the reply slip by Friday.", subject: nil))
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "handle-this-\(UUID().uuidString).db", deviceKey: nil
        )
        let outcome = try await core.handleThis(text: "Please return the reply slip by Friday.", subject: nil)
        guard case .unavailable(let u) = onEnum(of: outcome) else { return XCTFail("expected Unavailable") }
        XCTAssertEqual(u.line, AskRules.shared.NOT_CONNECTED_LINE)
        let link = try await core.handleThis(text: "https://example.com/article", subject: nil)
        guard case .unavailable(let l) = onEnum(of: link) else { return XCTFail("expected Unavailable") }
        XCTAssertEqual(l.line, rules.NOTHING_LINE)
        // Slice 2: a renewal card's line under it comes from the core (Swift shows `card.detail` as it is).
        let details = RenewalDetails(kind: .insurance, repeat: .yearly, costPence: KotlinLong(value: 41_200))
        XCTAssertEqual(MessageRequestRules.shared.renewalDetailLine(r: details, day: nil), "Insurance · every year · £412")
        // Slice 3: a reply's draft is checked in the core; Swift only shows `card.draft` and copies `done.copyText`.
        XCTAssertEqual(rules.cleanReply(s: "Yes, Theo will be there."), "Yes, Theo will be there.")
        XCTAssertNil(rules.cleanReply(s: "Pay at www.example.com"))
        XCTAssertEqual(rules.COPY_REPLY, "Copy reply")
        // What MEKA knows, slice 4: a shared form answered from the facts page in the core, with no connection at all.
        _ = try await core.addFact(text: "Car reg: AB12 CDE")
        let answers = try await core.formAnswers(text: "Vehicle registration: ____\nSignature: ____", subject: nil)
        XCTAssertEqual(answers.answers.map(\.field), ["Vehicle registration"])
        XCTAssertEqual(answers.answers.first?.copyText, "AB12 CDE")
        XCTAssertEqual(answers.copyAllText, "Vehicle registration: AB12 CDE")
        if let a = answers.answers.first { XCTAssertEqual(FormFillRules.shared.copiedLine(answer: a), "Copied Car reg · AB12 CDE") }
        XCTAssertTrue(FormFillRules.shared.EMPTY.answers.isEmpty)
    }

    func testProtectedTimeReachesSwift() {
        // Protected time, slice 1: the sheet's chips and lines come from the core's rules (only two Bools and an Int
        // cross from the sheet).
        let rules = ProtectedRules.shared
        let fromChoices: [Int32] = rules.FROM_CHOICES.map { $0.int32Value }
        let expectedFrom: [Int32] = [1080, 1110, 1140, 1170, 1200] // 18:00, 18:30, 19:00, 19:30, 20:00
        XCTAssertEqual(fromChoices, expectedFrom)
        XCTAssertEqual(ProtectedView.companion.EMPTY.summary, rules.OFF_LINE)
        XCTAssertNil(ProtectedView.companion.EMPTY.todayLine)
        let setting = ProtectedSetting(evenings: true, fromMin: 19 * 60, weekends: true, holdMail: true, openDay: nil)
        XCTAssertTrue(setting.on)
        // Slice 3: work mail waits until morning; the sheet's row and the card's line come from the core.
        XCTAssertTrue(setting.holdMail)
        XCTAssertTrue(ProtectedView.companion.EMPTY.holdMail)
        XCTAssertNil(ProtectedView.companion.EMPTY.mailLine)
        XCTAssertEqual(ProtectedMailRules.shared.LABEL, "Work mail")
        XCTAssertEqual(ProtectedMailRules.shared.MORNING_MIN, 7 * 60)
        XCTAssertEqual(rules.summary(s: setting), "Evenings from 19:00, weekends and bank holidays · nothing is planned into them")
        XCTAssertEqual(rules.summary(s: nil), rules.OFF_LINE)
        // Slice 4: "not protected tonight" — the sheet's button comes from the view.
        XCTAssertNil(ProtectedView.companion.EMPTY.openAction)
        XCTAssertFalse(ProtectedView.companion.EMPTY.openToday)
        XCTAssertNil(setting.openDay)
        // Slice 5: the brief's and the week ahead's lines are plain optional Strings on their views.
        XCTAssertNil(MorningBriefView.companion.EMPTY.protectedLine)
        XCTAssertNil(WeekAheadView.companion.EMPTY.protectedLine)
    }

    func testDateNightNudgeReachesSwift() {
        // Date night, slice 2: Needs you's week-before card and its heads-up's row in Notifications reach the Mac; the
        // card's buttons send only an Int64 and a Bool (the undo bar gets a String back).
        let rules = DateNightRules.shared
        let fri16 = CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 16)
        let setting = DateNightSetting(weekday: 5, startMin: 19 * 60, anchorDay: fri16 + 7, skipped: [], booked: [])
        let card = rules.nudge(s: setting, today: fri16, minuteOfDay: 9 * 60)
        XCTAssertEqual(card?.day, fri16 + 7)
        XCTAssertEqual(card?.title, "Date night · Fri 23 Oct")
        XCTAssertEqual(card?.line, "In 7 days · from 19:00")
        XCTAssertEqual(card?.bookedLabel, "Booked")
        XCTAssertEqual(card?.skipLabel, "Skip this one")
        XCTAssertEqual(rules.view(s: setting, today: fri16, minuteOfDay: 9 * 60).nudge?.day, fri16 + 7)
        XCTAssertEqual(rules.bookedLine(day: fri16 + 7, today: fri16, booked: true), "Date night Fri 23 Oct · booked")
        let booked = DateNightSetting(weekday: 5, startMin: 19 * 60, anchorDay: fri16 + 7, skipped: [], booked: [KotlinLong(longLong: fri16 + 7)])
        XCTAssertNil(rules.nudge(s: booked, today: fri16, minuteOfDay: 9 * 60))
        XCTAssertEqual(NoticeSource.dateNight.label, "Date night")
    }

    func testGiftsReachSwift() async throws {
        // Gift planner, slice 1: a typed birthday is read by the core's rules, added through the facade (only Strings
        // and Bools cross) and the sheet's rows, ideas and sorted state reach the Mac.
        let rules = GiftRules.shared
        let mum = rules.read(text: "Mum's birthday 3 Nov 1966", thisYear: 2026)
        XCTAssertEqual(mum?.person, "Mum")
        XCTAssertEqual(mum?.kind, .birthday)
        XCTAssertEqual(mum?.month, 11)
        XCTAssertEqual(mum?.year, 1966)
        XCTAssertNil(rules.read(text: "birthday 3 Nov", thisYear: 2026))
        XCTAssertEqual(NoticeSource.gifts.label, "Gifts: birthdays and anniversaries")
        XCTAssertEqual(GiftsView.companion.EMPTY.summary, rules.EMPTY_LINE)
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "gifts-\(UUID().uuidString).db", deviceKey: nil
        )
        let added = try await core.addOccasion(text: "Wedding anniversary 20 Aug 2015")
        XCTAssertFalse(added.refused)
        XCTAssertTrue(added.line.hasPrefix("Added Wedding anniversary · "))
        let refused = try await core.addOccasion(text: "soon")
        XCTAssertTrue(refused.refused)
        let id = core.giftsView.value.rows[0].id
        let noted = try await core.addGiftIdea(occasionId: id, text: "Dinner at the Ivy")
        XCTAssertTrue(noted.boolValue)
        let sorted = try await core.setGiftSorted(id: id, sorted: true)
        XCTAssertTrue(sorted.boolValue)
        let row = core.giftsView.value.rows[0]
        XCTAssertEqual(row.title, "Wedding anniversary")
        XCTAssertEqual(row.ideas.map(\.text), ["Dinner at the Ivy"])
        XCTAssertTrue(row.sorted)
        XCTAssertEqual(row.note, "✓ Gift sorted")
        let removed = try await core.removeOccasion(id: id)
        XCTAssertTrue(removed.boolValue)
        XCTAssertTrue(core.giftsView.value.rows.isEmpty)
    }

    func testTripsReachSwift() async throws {
        // Travel mode, slice 1: a typed trip and booking go through the facade (only Strings cross) and the sheet's
        // rows, the timeline and the week ahead's kind reach the Mac.
        let rules = TripRules.shared
        XCTAssertEqual(rules.kindOf(text: "Flight BA478 Sat 14 Nov"), .flight)
        XCTAssertEqual(rules.kindOf(text: "Check out 11:00"), .stay)
        XCTAssertNil(rules.kindOf(text: "Barcelona 14–18 Nov"))
        XCTAssertEqual(WeekAheadKind.trip.name, "TRIP")
        XCTAssertEqual(TripsView.companion.EMPTY.summary, rules.EMPTY_LINE)
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "trips-\(UUID().uuidString).db", deviceKey: nil
        )
        let y = Calendar.current.component(.year, from: Date()) + 1
        let none = try await core.addTrip(text: "Flight TP1361 3 Mar \(y) 09:15")
        XCTAssertTrue(none.refused)
        let trip = try await core.addTrip(text: "Lisbon 3–6 Mar \(y)")
        XCTAssertFalse(trip.refused)
        XCTAssertTrue(trip.line.hasPrefix("Added Lisbon · "))
        let flight = try await core.addTrip(text: "Flight TP1361 3 Mar \(y) 09:15 ref QX12AB")
        XCTAssertFalse(flight.refused)
        XCTAssertTrue(flight.line.hasSuffix("09:15 · ref QX12AB"), flight.line)
        let row = core.tripsView.value.rows[0]
        XCTAssertEqual(row.name, "Lisbon")
        XCTAssertTrue(row.line.hasSuffix("4 days · 1 booking"), row.line)
        let line = row.timeline[0].lines[0]
        XCTAssertEqual(line.lead, "09:15")
        XCTAssertEqual(line.text, "Flight TP1361 · ref QX12AB")
        // Completeness slice 4: × with Undo, then Remove trip for good.
        let undo = try await core.removeTripBookingForUndo(id: line.bookingId)
        XCTAssertEqual(undo?.line, "Removed Flight TP1361 from Lisbon")
        let back = try await core.undoTripRemoval(token: undo?.token ?? "")
        XCTAssertTrue(back.boolValue)
        XCTAssertEqual(core.tripsView.value.rows[0].timeline.count, 1)
        XCTAssertTrue(core.tripsView.value.past.isEmpty)
        XCTAssertEqual(core.tripsView.value.pastLabel, "")
        let gone = try await core.removeTripBooking(id: line.bookingId)
        XCTAssertTrue(gone.boolValue)
        let removed = try await core.removeTrip(id: row.id)
        XCTAssertTrue(removed.boolValue)
        XCTAssertTrue(core.tripsView.value.rows.isEmpty)
    }

    func testVaultReceiptReachesSwift() async throws {
        // Receipts → Vault: the preview's offer, Keep / Take out and Open the email reach the Mac (only Strings cross).
        XCTAssertEqual(VaultReceiptRules.shared.KEEP, "Keep in the Vault")
        XCTAssertEqual(DocumentRules.shared.kindOf(text: "TV receipt"), .receipt)
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "receipt-\(UUID().uuidString).db", deviceKey: nil
        )
        let kept = try await core.keepReceiptInVault(batchId: "nothing", key: "te1")
        XCTAssertNil(kept)
        let out = try await core.takeReceiptOutOfVault(key: "te1")
        XCTAssertNil(out)
        let open = try await core.vaultReceiptLink(docId: "nothing")
        XCTAssertEqual(open.problem, VaultReceiptRules.shared.GONE)
    }

    func testDocumentsReachSwift() async throws {
        // The Vault, slice 1: a typed document goes through the facade (only a String crosses) and its row, status
        // and the Vault's notice target reach the Mac.
        let rules = DocumentRules.shared
        XCTAssertEqual(rules.kindOf(text: "Theo passport"), .passport)
        XCTAssertEqual(DocumentsView.companion.EMPTY.summary, rules.EMPTY_LINE)
        XCTAssertEqual(NoticeTarget.vault.name, "VAULT")
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "docs-\(UUID().uuidString).db", deviceKey: nil
        )
        let y = Calendar.current.component(.year, from: Date()) + 5
        let bad = try await core.addDocument(text: "Passport 31 Feb \(y)")
        XCTAssertTrue(bad.refused)
        let added = try await core.addDocument(text: "Passport 123456789 expires 3 Mar \(y)")
        XCTAssertFalse(added.refused)
        XCTAssertTrue(added.line.hasPrefix("Added Passport · expires "), added.line)
        let row = core.documentsView.value.rows[0]
        XCTAssertEqual(row.title, "Passport")
        XCTAssertFalse(row.statusLit)
        XCTAssertTrue(row.status.hasPrefix("Expires in "), row.status)
        let removed = try await core.removeDocument(id: row.id)
        XCTAssertTrue(removed.boolValue)
        XCTAssertTrue(core.documentsView.value.rows.isEmpty)
    }

    func testDocumentFromSharedReachesSwift() async throws {
        // Screenshots and photos, slice 3: a picture's words that are a document offer a Vault row (only Strings
        // cross), Add writes its name and date, and the same words then say it's already there. Invented names only.
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "docshare-\(UUID().uuidString).db", deviceKey: nil
        )
        let y = (Calendar.current.component(.year, from: Date()) + 3) % 100
        let text = "PASSPORT\nGiven names / Prénoms (2)\nSAM\nDate of expiry / Date d'expiration (6)\n20 MAY /MAI \(y)"
        let offer = try await core.documentFromShared(text: text, subject: nil)
        XCTAssertNotNil(offer)
        XCTAssertTrue(offer?.line.hasPrefix(DocumentShareRules.shared.ACTION + " · Sam passport · expires ") ?? false, offer?.line ?? "")
        XCTAssertFalse(offer?.already ?? true)
        let added = try await core.addDocumentFromShared(text: text, subject: nil)
        XCTAssertNotNil(added?.id)
        XCTAssertFalse(added?.updated ?? true)
        let again = try await core.documentFromShared(text: text, subject: nil)
        XCTAssertTrue(again?.already ?? false)
        let none = try await core.documentFromShared(text: "Lunch with Alex at 12:30", subject: nil)
        XCTAssertNil(none)
        XCTAssertEqual(ScreenshotKind.document.label, "a document with an expiry date")
    }

    func testAboutYouReachesSwift() async throws {
        // About you (rule 13, slice 1): a line saved by its key and a child added (only Strings and an Int cross),
        // the view, Undo's text and Setup's fix reach the Mac. Invented names only.
        XCTAssertEqual(AboutYouView.companion.EMPTY.summary, AboutYouRules.shared.WELCOME)
        XCTAssertEqual(SetupFix.aboutYou.name, "ABOUT_YOU")
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "about-\(UUID().uuidString).db", deviceKey: nil
        )
        let saved = try await core.setAboutYouField(key: AboutYouFields.shared.PARTNER_NAME, text: "Sam")
        XCTAssertEqual(saved?.line, "Saved")
        let refused = try await core.setAboutYouField(key: AboutYouFields.shared.HOME, text: "<home>")
        XCTAssertEqual(refused?.refused, true)
        let added = try await core.addAboutYouChild(text: "Alex, U7, Oakfield School")
        XCTAssertEqual(added.line, "Added Alex · Under 7s · Oakfield School")
        let v = core.aboutYouView.value
        XCTAssertFalse(v.isEmpty)
        XCTAssertEqual(v.rows.first { $0.field == .partnerName }?.value, "Sam")
        XCTAssertEqual(v.children.map(\.name), ["Alex"])
        let gone = try await core.removeAboutYouChild(index: 0)
        XCTAssertEqual(gone.undo, "Alex, U7, Oakfield School")
        XCTAssertTrue(core.aboutYouView.value.children.isEmpty)
    }

    func testBinsReachSwift() async throws {
        // Bin days, slice 1: a typed bin is read by the core's rules, added through the facade (only a String crosses)
        // and the sheet's rows, Today's line and the heads-up's source reach the Mac.
        let rules = BinRules.shared
        let sat = CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 10)
        let black = rules.read(text: "Black bin Tue fortnightly from 13 Oct", today: sat)
        XCTAssertEqual(black?.name, "Black bin")
        XCTAssertEqual(black?.weekday, 2)
        XCTAssertEqual(black?.everyWeeks, 2)
        XCTAssertEqual(black?.anchorDay, CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 13))
        XCTAssertNil(rules.read(text: "Black bin", today: sat))
        XCTAssertEqual(NoticeSource.bins.label, "Bins out the night before")
        XCTAssertEqual(BinsView.companion.EMPTY.summary, rules.EMPTY_LINE)
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "bins-\(UUID().uuidString).db", deviceKey: nil
        )
        let added = try await core.addBin(text: "Food caddy every Tuesday")
        XCTAssertFalse(added.refused)
        XCTAssertTrue(added.line.hasPrefix("Added Food caddy · every Tuesday · next "))
        let refused = try await core.addBin(text: "the bins")
        XCTAssertTrue(refused.refused)
        let rows = core.binsView.value.rows
        XCTAssertEqual(rows.map(\.name), ["Food caddy"])
        XCTAssertEqual(rows.first?.line, "Every Tuesday")
        let removed = try await core.removeBin(id: rows[0].id)
        XCTAssertTrue(removed.boolValue)
        XCTAssertTrue(core.binsView.value.rows.isEmpty)
        // Slice 3: a one-off reaches the sheet's ONE-OFFS rows and the collection's note; Remove sends only the id.
        let tue13 = CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 13)
        let move = BinMove(id: "m", binId: "b", fromDay: tue13, toDay: KotlinLong(longLong: tue13 + 1))
        let caddy = BinItem(id: "b", name: "Food caddy", weekday: 2, everyWeeks: 1, anchorDay: tue13, addedAtMs: 0, moves: [move])
        XCTAssertEqual(rules.oneOffRows(bins: [caddy], today: sat).map(\.line), ["Wed 14 Oct instead of Tue 13 Oct"])
        XCTAssertEqual(rules.note(bin: caddy, day: tue13 + 1), "moved from Tue 13 Oct")
        XCTAssertTrue(core.binsView.value.oneOffs.isEmpty)
        let gone = try await core.removeBinMove(id: "nope")
        XCTAssertFalse(gone.boolValue)
        // Completeness: the reminder's time crosses as an Int32 and comes back as the sheet's line.
        let said = try await core.setBinReminder(minute: 20 * 60)
        XCTAssertEqual(said, "Reminder at 20:00 · on both devices")
        XCTAssertEqual(rules.remindLine(minute: 19 * 60), "Reminder the evening before at 19:00 · Today says it from 16:00")
    }

    func testSchoolReachesSwift() {
        // School rhythm, slice 1: a typed line is read by the core's rules and the sheet's words reach the Mac (only
        // Strings cross from the sheet; the cover answer sends an id and a Bool).
        let rules = SchoolRules.shared
        let sat = CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 10)
        let inset = rules.read(text: "INSET 27 Oct", today: sat)
        XCTAssertEqual(inset?.kind, .off)
        // Slice 2b(ii): the school's heads-ups are their own row in Notifications on the Mac too.
        XCTAssertEqual(NoticeSource.school.label, "School")
        XCTAssertTrue(rules.isDue(title: "Trip payment"))
        XCTAssertEqual(inset?.title, "INSET day")
        XCTAssertEqual(inset?.startDay, CivilDate.shared.toEpochDay(year: 2026, month: 10, day: 27))
        // Rule 13, release 2: the children and clubs come from About you (invented here).
        _ = Household.shared.useTyped(children: "Max, U7\nFelix, U10", clubs: "OTFC Lindenbrook United, HRFC")
        defer { _ = Household.shared.useTyped(children: "", clubs: "") }
        let pe = rules.read(text: "Max PE Tue", today: sat)
        XCTAssertEqual(pe?.kind, .weekly)
        XCTAssertEqual(pe.map { rules.addedLine(e: $0) }, "Added PE · every Tuesday · Max")
        XCTAssertNil(rules.read(text: "Half term", today: sat))
        XCTAssertFalse(rules.NOT_READ.isEmpty)
        XCTAssertEqual(rules.HOME_LABEL, "I'll work from home")
    }

    func testSchoolDayLinesReachSwift() {
        // School rhythm, slice 2: Today's header, the brief and the shutdown read SchoolLine's two Strings.
        let rules = SchoolRules.shared
        let pe = SchoolThing(id: "x", kind: .weekly, title: "PE", who: "Theo")
        XCTAssertEqual(rules.thingText(t: pe, kit: false), "Theo: PE")
        XCTAssertEqual(rules.thingText(t: pe, kit: true), "Theo: PE kit")
        XCTAssertEqual(rules.spokenThing(t: pe), "Theo has PE")
        let line = SchoolLine(text: "Theo: PE today", spoken: "School today: Theo has PE.")
        XCTAssertEqual(line.text, "Theo: PE today")
    }

    func testRecoveryKeyReachesSwift() async throws {
        // Recovery key, slice 3: the Mac's sheet makes a draft (six rows of four, three words to type back), saving
        // refuses words that don't match without sending anything, and Connect reads the typed words from a String.
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "recovery-\(UUID().uuidString).db", deviceKey: nil
        )
        let draft = core.makeRecoveryKey()
        XCTAssertEqual(draft.rows.count, 6)
        XCTAssertTrue(draft.rows.allSatisfy { $0.count == 4 })
        XCTAssertEqual(draft.rows.first?.first?.hasPrefix("1 "), true)
        XCTAssertEqual(draft.confirmLabels.count, 3)
        XCTAssertTrue(draft.confirmLabels.allSatisfy { $0.hasPrefix("Word ") })
        let saved = try await core.saveRecoveryKey(draft: draft, answers: ["zzzz", "zzzz", "zzzz"])
        XCTAssertFalse(saved.boolValue)
        XCTAssertEqual(core.recoveryKeyView.value?.problem, RecoveryKeyRules.shared.NOT_MATCHING)
        let offline = try await core.refreshRecoveryKey()
        XCTAssertEqual(offline.problem, RecoveryKeyRules.shared.NOT_CONNECTED)
        let rules = RecoveryKeyRules.shared
        XCTAssertEqual(rules.typedLine(text: ""), "Type the 24 words")
        XCTAssertFalse(rules.readable(text: "abandon ability"))
    }

    func testWatchLinkReachesSwift() {
        // Galaxy Watch, slice 1: Settings → Watch's rules reach the Mac (only Strings cross from the sheet).
        let rules = WatchLinkRules.shared
        XCTAssertEqual(rules.normaliseCode(raw: " 1234 5678 "), "12345678")
        XCTAssertNil(rules.normaliseCode(raw: "1234"))
        XCTAssertEqual(rules.showCode(code: "12345678"), "1234 5678")
        XCTAssertEqual(rules.refusal(reason: "wait"), rules.TOO_MANY)
        XCTAssertEqual(rules.linkedSummary(name: "Galaxy Watch"), "Linked Galaxy Watch")
        XCTAssertTrue(rules.HOW.contains("8-digit code"))
    }

    func testKeepCallersRecordingsReachesSwift() {
        // Call assistant polish 8c: the "Keep callers' recordings" choice, its line and the summary's note.
        let rules = VoiceRecordingRules.shared
        // 2026-10-20: kept for the call log's 7 days after Done; Delete recording removes one sooner.
        XCTAssertEqual(rules.DEFAULT_KEEP_DAYS, 7)
        XCTAssertEqual(rules.KEEP_CHOICES.map { $0.int32Value }, [7, 0])
        XCTAssertEqual(rules.choiceLabel(days: 0), "Don't keep")
        XCTAssertEqual(rules.choiceLabel(days: 7), "7 days")
        XCTAssertTrue(rules.settingLine(days: 0).hasPrefix("Not kept"))
        XCTAssertEqual(rules.privacy(days: 7), rules.PRIVACY)
        XCTAssertEqual(rules.DELETE, "Delete recording")
        XCTAssertEqual(rules.DELETE_UNDO_MS, 5000)
    }

    func testSignInLineReachesSwift() {
        // Reliability first, item 2: the line Today shows for a sign-in on its last day, with Reconnect.
        let line = SignInLine(text: "Google sign-in ends tomorrow at 14:05 · Reconnect", detail: SignInRules.shared.PRODUCTION_HINT,
                              critical: false, provider: "google", account: "meka@gmail.com", editing: true, spoken: "")
        XCTAssertFalse(line.critical)
        XCTAssertTrue(line.detail.contains("#17"))
        XCTAssertEqual(SignInRules.shared.entityId(key: "accAbc"), "sign_in.accabc")
        XCTAssertEqual(NoticeSource.signIn.label, "Calendar sign-ins")
    }

    func testHealthReachesSwift() {
        // Reliability first, item 3: a row as the core makes it (the Mac's fixes: HealthSheetTests).
        let reconnect = HealthRow(key: "calendar:google:meka@gmail.com", title: "Calendar · Personal", line: "Google sign-in expired",
                                  state: .bad, fix: .reconnect, fixLabel: "Reconnect", provider: "google", account: "meka@gmail.com", editing: true)
        XCTAssertTrue(reconnect.needsLook)
        XCTAssertEqual(reconnect.fix, .reconnect)
        let battery = HealthRow(key: "battery", title: "Battery", line: "Samsung may put Navilife to sleep",
                                state: .warn, fix: .battery, fixLabel: "Keep Navilife awake", provider: nil, account: nil, editing: false)
        XCTAssertTrue(battery.needsLook)
        XCTAssertEqual(HealthRules.shared.TODAY_REFRESH_MS, 15 * 60_000)
    }

    func testSetupReachesSwift() {
        // Setup checklist: the Mac's own facts and a view as the core makes them (the Mac's buttons: SetupSheetTests).
        let device = SetupDevice(mac: true, notificationsAllowed: KotlinBoolean(bool: true), batteryExempt: nil,
                                 notificationAccess: nil, callRoleHeld: nil, people: nil, watch: nil)
        let facts = SetupFacts(device: device, connected: true, server: nil, accounts: nil, signIns: [], ai: nil,
                               callAssistantOn: false, voiceMessageSeen: false, voiceChosen: nil,
                               homePlace: "Oakfield", workPlace: "Riverside", nowMs: 0)
        let view = SetupRules.shared.view(f: facts)
        let role = view.steps.first { $0.key == "calls.role" }
        XCTAssertEqual(role?.state, .elsewhere)
        XCTAssertEqual(role?.line, SetupRules.shared.ON_THE_FOLD)
        XCTAssertEqual(view.steps.first { $0.key == "calls.on" }?.fix, .work)
        XCTAssertTrue(SetupRules.shared.cardShown(view: view, hiddenOnDay: nil, today: 1))
        XCTAssertFalse(SetupRules.shared.cardShown(view: view, hiddenOnDay: KotlinLong(longLong: 1), today: 1))
    }

    func testMacNotificationsReachSwift() {
        // Notifications never seen, the Mac's half: Health's row, the sheet's off line and Send a test.
        let off = MacNotifyRules.shared.health(allowed: false, macOff: false, lastPostMs: 0)
        XCTAssertTrue(off.mac)
        XCTAssertFalse(off.allowed)
        XCTAssertTrue(MacNotifyRules.shared.health(allowed: true, macOff: true, lastPostMs: 1).phoneOff)
        XCTAssertNotNil(MacNotifyRules.shared.offLine(allowed: false, macOff: false))
        XCTAssertNil(MacNotifyRules.shared.offLine(allowed: true, macOff: false))
        XCTAssertFalse(MacNotifyRules.shared.testSending().done)
        let shown = MacNotifyRules.shared.testView(shown: true)
        XCTAssertEqual(shown.localLine, "On this Mac · arrived ✓")
        XCTAssertEqual(shown.localOk?.boolValue, true)
        XCTAssertEqual(MacNotifyRules.shared.TEST_TITLE, "Navilife test · from this Mac")
    }

    func testMailAccountsReachSwift() async throws {
        // Inbox autopilot, slice 1: the Mail sheet's rows and label chips come from the core; an unconnected core has
        // no server to ask, so Mail says it couldn't reach it (nil).
        let rules = MailAccountRules.shared
        let v = rules.view(accounts: [
            MailAccountFacts(provider: "gmail", email: "shop@example.com", status: "ok", lastSyncAtMs: nil, label: "BUSINESS", sorted: ["NEEDS_YOU": KotlinInt(int: 2)], wouldTidy: ["ARCHIVE": KotlinInt(int: 3)], why: nil, inboxTotal: nil, inboxUnread: nil),
            MailAccountFacts(provider: "gmail", email: "alex@example.com", status: "needs_reconnect", lastSyncAtMs: nil, label: nil, sorted: nil, wouldTidy: nil, why: nil, inboxTotal: nil, inboxUnread: nil),
        ], nowMs: 0)
        XCTAssertEqual(v.rows.map(\.title), ["Personal · Gmail", "Business · Gmail"])
        XCTAssertTrue(v.rows[0].needsReconnect)
        // Slice 2a: a sorted account's inbox counts.
        XCTAssertNil(v.rows[0].sortedLine)
        XCTAssertEqual(v.rows[1].sortedLine, "In the inbox · 2 need you")
        // Slice 3a: Autopilot's dry run.
        XCTAssertNil(v.rows[0].tidyLine)
        XCTAssertEqual(v.rows[1].tidyLine, "Autopilot, dry run · would tidy 3 · 3 notifications and promos archived · nothing moved yet")
        XCTAssertEqual(MailSortRules.shared.sort(f: MailSortFacts(fromAddress: "x@gov.uk", fromName: nil, subject: nil, labels: [], listUnsubscribe: nil, listId: nil)).sort, MailSort.needsYou)
        XCTAssertEqual(v.toConnect, "Still to connect: Outlook")
        XCTAssertEqual(rules.LABELS.map(\.label), ["Personal", "Business", "Outlook"])
        XCTAssertEqual(v.connects.map(\.label), ["Connect Gmail", "Connect Outlook"])
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "mail-\(UUID().uuidString).db", deviceKey: nil
        )
        let mail = try await core.mailAccounts()
        XCTAssertNil(mail)
        // Slice 1b: Disconnect offline says nothing changed; Setup's Mail step; a mail sign-in's line on Today.
        let gone = try await core.disconnectMail(provider: "gmail", email: "alex@example.com")
        XCTAssertNil(gone)
        // Completeness: Delete mail data offline changes nothing either.
        let wiped = try await core.disconnectMailAndDeleteData(provider: "gmail", email: "alex@example.com")
        XCTAssertNil(wiped)
        XCTAssertTrue(rules.deletedLine(provider: "gmail", email: "alex@example.com").contains("deleted its mail data"))
        XCTAssertEqual(rules.setupStep(accounts: [], nowMs: 0).fix, SetupFix.mail)
        XCTAssertTrue(rules.disconnectAsk(row: v.rows[1]).hasPrefix("Disconnect Business · Gmail"))
        XCTAssertTrue(MailProviders.shared.isMail(provider: "outlook_mail"))
        // Slice 3b-2: Autopilot's switch (its panel from the core) and the switch and Undo offline.
        let panel = MailAutopilotPanelRules.shared.panel(setting: MailAutopilotSetting(on: true, changedAtMs: nil, markRead: true), paused: false)
        XCTAssertEqual(panel.action, MailAutopilotPanelRules.shared.TURN_OFF)
        XCTAssertTrue(v.rows.allSatisfy { $0.tidies.isEmpty })
        let switched = try await core.setMailAutopilot(on: false)
        XCTAssertTrue(switched.line.hasPrefix("Autopilot is off"))
        XCTAssertNil(switched.view)
        let undo = try await core.undoMailTidy(id: "nothing")
        XCTAssertEqual(undo.line, "This can't be undone")
        // Slice 5a: Unsubscribe on an unknown sender answers offline.
        let unsub = try await core.unsubscribeMailSender(id: "nothing")
        XCTAssertEqual(unsub.line, MailSenderPanelRules.shared.UNKNOWN)
        // Completeness: a sender rule on an unknown sender answers offline too.
        let rule = try await core.setMailSenderRule(id: "nothing", rule: "KEEP")
        XCTAssertEqual(rule.line, MailSenderPanelRules.shared.UNKNOWN)
        // Slice 10b: Always tidy on an unknown card answers offline too.
        let tidy = try await core.alwaysTidyMailItem(id: "nothing")
        XCTAssertEqual(tidy, MailNeedsRules.shared.UNKNOWN)
        // Slice 4: "Archived for you" — nothing tidied, no card; Got it and its Undo answer offline.
        XCTAssertNil(core.archivedForYou.value)
        let seen = try await core.seenArchivedForYou()
        XCTAssertEqual(seen, ArchivedForYouRules.shared.GOT_IT_LINE)
        try await core.undoSeenArchivedForYou()
        XCTAssertNil(core.archivedForYou.value)
        let batch = MailTidyBatch(id: "t1", atMs: 1_000, account: "Personal", summary: "Tidied 5 emails · Personal",
                                  detail: "3 newsletters → Navilife/Newsletters · 2 receipts and orders → Navilife/Receipts", count: 5,
                                  undoAskedAtMs: nil, undoneAtMs: nil, undoNote: nil,
                                  kinds: [MailTidy.newsletter: KotlinInt(int: 3), MailTidy.receipt: KotlinInt(int: 2)],
                                  backlog: false, junk: false, receiptsPence: nil, receiptsPriced: 0, merchants: [], accountKey: nil, emails: [], emailAsks: [], emailDone: [])
        XCTAssertEqual(ArchivedForYouRules.shared.headline(batches: [batch]), "Tidied 5 · 3 newsletters · 2 receipts and orders")
        // Slice 5b: an unsubscribed sender's later mail goes to Trash, counted with the rest.
        let trashed = MailTidyBatch(id: "t2", atMs: 2_000, account: "Personal", summary: "Tidied 2 emails · Personal",
                                    detail: "2 from unsubscribed senders → Trash", count: 2,
                                    undoAskedAtMs: nil, undoneAtMs: nil, undoNote: nil,
                                    kinds: [MailTidy.trash: KotlinInt(int: 2)], backlog: false, junk: false,
                                    receiptsPence: nil, receiptsPriced: 0, merchants: [], accountKey: nil, emails: [], emailAsks: [], emailDone: [])
        XCTAssertEqual(ArchivedForYouRules.shared.headline(batches: [trashed]), "Tidied 2 · 2 from blocked or unsubscribed senders")
        XCTAssertEqual(MailTidy.trash.destination, "→ Trash")
        // Slice 3c: the daily Junk review's tidy reads the same way.
        let junk = MailAutopilotRules.shared.readBatch(id: "t3", f: MailJunkRules.shared.batchFields(atMs: 3_000, account: "Personal",
                                                       counts: [MailTidy.junkTrash: KotlinInt(int: 2), MailTidy.rescue: KotlinInt(int: 1)]))
        XCTAssertEqual(junk?.summary, "Junk checked · 2 to Trash · 1 rescued · Personal")
        XCTAssertEqual(junk?.junk, true)
        XCTAssertEqual(MailTidy.rescue.destination, "→ inbox, flagged")
        // Slice 5c: no not-opened senders, no offer; its answers still say something offline.
        XCTAssertNil(core.mailUnopened.value)
        let none = try await core.unsubscribeUnopened()
        XCTAssertEqual(none, MailUnopenedRules.shared.NONE_LEFT)
        let kept = try await core.keepMailSender(id: "nothing")
        XCTAssertEqual(kept, MailSenderPanelRules.shared.UNKNOWN)
        let notNow = try await core.notNowUnopened()
        XCTAssertEqual(notNow, MailUnopenedRules.shared.NOT_NOW_LINE)
        try await core.undoUnopened()
        XCTAssertNil(core.mailUnopened.value)
        XCTAssertEqual(MailUnopenedRules.shared.headline(n: 11), "Unsubscribe from 11?")
        XCTAssertEqual(MailUnopenedRules.shared.unsubscribeLabel(n: 11), "Unsubscribe from 11")
        XCTAssertEqual(MailUnopenedRules.shared.showAllLabel(n: 31), "Show all 31")
        // Slice 6a: no email needs him, no card; Done and Snooze on an unknown id say so.
        XCTAssertNil(core.mailNeeds.value)
        let unknownDone = try await core.doneMailItem(id: "nothing")
        XCTAssertEqual(unknownDone, MailNeedsRules.shared.UNKNOWN)
        let unknownSnooze = try await core.snoozeMailItem(id: "nothing")
        XCTAssertEqual(unknownSnooze, MailNeedsRules.shared.UNKNOWN)
        // Slice 6b-1: Task and Waiting for on an unknown id say so too.
        let unknownTask = try await core.taskFromMailItem(id: "nothing")
        XCTAssertEqual(unknownTask, MailNeedsRules.shared.UNKNOWN)
        let unknownWaiting = try await core.waitingFromMailItem(id: "nothing")
        XCTAssertEqual(unknownWaiting, MailNeedsRules.shared.UNKNOWN)
        // Slice 6b-2a: Reply, sent by the server only after Send.
        let unknownReply = try await core.replyToMailItem(id: "nothing", text: "Yes")
        XCTAssertEqual(unknownReply, MailNeedsRules.shared.UNKNOWN)
        XCTAssertEqual(MailNeedsRules.shared.replyProblem(text: "  "), MailNeedsRules.shared.REPLY_EMPTY)
        XCTAssertNil(MailNeedsRules.shared.replyProblem(text: "Yes, I can take them."))
        // Slice 6b-2b: Draft it on an unknown id says so and brings no text; the editor's rules reach Swift.
        let unknownDraft = try await core.draftMailReply(id: "nothing")
        XCTAssertNil(unknownDraft.text)
        XCTAssertEqual(unknownDraft.line, MailNeedsRules.shared.UNKNOWN)
        XCTAssertTrue(MailDraftRules.shared.canDraft(editor: "", lastDraft: nil))
        XCTAssertFalse(MailDraftRules.shared.canDraft(editor: "My words", lastDraft: "Draft"))
        XCTAssertEqual(MailDraftRules.shared.label(editor: ""), MailDraftRules.shared.DRAFT)
        XCTAssertNil(MailDraftRules.shared.check(raw: "See https://example.com"))
        // Email triage completeness: Send all on unknown ids writes nothing; its rules reach Swift.
        let unknownAll = try await core.replyToMailItems(ids: ["nothing", "nada"], texts: ["Yes", "No"])
        XCTAssertEqual(unknownAll, MailNeedsRules.shared.UNKNOWN)
        XCTAssertNil(MailSendAllRules.shared.draftLabel(rows: 1))
        XCTAssertEqual(MailSendAllRules.shared.draftLabel(rows: 3), "Draft replies to 3")
        XCTAssertTrue(MailSendAllRules.shared.easy(text: "Thanks, sorted."))
        XCTAssertNil(MailSendAllRules.shared.sendAll(rows: [], texts: ["a": "Yes"]))
        try await core.undoMailItem()
        XCTAssertNil(core.mailNeeds.value)
        XCTAssertEqual(MailNeedsRules.shared.headline(n: 3), "3 emails need you")
        XCTAssertEqual(MailNeedsRules.shared.cleanPreview(raw: "Tom &amp; Jerry"), "Tom & Jerry")
        // Slice 6b-3: nothing to chase, no card; Send, Got it and Not yet on an unknown id say so.
        XCTAssertNil(core.mailChases.value)
        let unknownChase = try await core.chaseMailItem(id: "nothing", text: "Any news?")
        XCTAssertEqual(unknownChase, MailChaseRules.shared.UNKNOWN)
        let unknownGot = try await core.gotMailChase(id: "nothing")
        XCTAssertEqual(unknownGot, MailChaseRules.shared.UNKNOWN)
        let unknownNotYet = try await core.notYetMailChase(id: "nothing")
        XCTAssertEqual(unknownNotYet, MailChaseRules.shared.UNKNOWN)
        // Follow-up radar, slice 5: the AI's chase on an unknown id brings no text; its rules reach Swift.
        let unknownChaseDraft = try await core.draftMailChase(id: "nothing")
        XCTAssertNil(unknownChaseDraft.text)
        XCTAssertEqual(unknownChaseDraft.line, MailChaseRules.shared.UNKNOWN)
        XCTAssertTrue(MailChaseDraftRules.shared.canDraft(editor: "Plain", plain: "Plain", lastDraft: nil))
        XCTAssertFalse(MailChaseDraftRules.shared.canDraft(editor: "My words", plain: "Plain", lastDraft: "AI"))
        XCTAssertEqual(MailChaseDraftRules.shared.label(editor: "AI", lastDraft: "AI"), MailChaseDraftRules.shared.AGAIN)
        try await core.undoMailChase()
        XCTAssertEqual(MailChaseRules.shared.headline(n: 2), "2 to chase by email")
        XCTAssertEqual(MailChaseRules.shared.draft(from: "Sam Taylor", shownName: nil),
                       "Hi Sam,\n\nJust checking in on this — is there any news?\n\nThanks")
        // Renewals from your mail: nothing found, no card; Add and Not needed on an unknown id say so.
        XCTAssertNil(core.mailFinds.value)
        let unknownAdd = try await core.addMailFind(id: "nothing")
        XCTAssertEqual(unknownAdd, MailRenewalRules.shared.UNKNOWN)
        let unknownDismiss = try await core.notNeededMailFind(id: "nothing")
        XCTAssertEqual(unknownDismiss, MailRenewalRules.shared.UNKNOWN)
        try await core.undoMailFind()
        XCTAssertEqual(MailRenewalRules.shared.headline(n: 1), "1 to add to Renewals · nothing goes on until you tap Add")
        // Slice 2: promises share the card.
        XCTAssertEqual(MailRenewalRules.shared.headline(renewals: 0, promises: 2), "2 to add to Waiting for · nothing goes on until you tap Add")
        XCTAssertEqual(MailPromiseRules.shared.TITLE, "Promises from your mail")
        // Scheduling assistant, slice 1: an email asking to meet offers Suggest times; the times are worked out on the Mac.
        XCTAssertTrue(MeetingRequestRules.shared.asks(subject: "Catch up?", preview: "Can we meet next week?"))
        XCTAssertFalse(MeetingRequestRules.shared.asks(subject: "Invitation: Weekly sync", preview: nil))
        XCTAssertEqual(MeetingRequestRules.shared.SUGGEST, "Suggest times")
        let noTimes = try await core.suggestMeetingTimes(id: "nothing")
        XCTAssertNil(noTimes.text)
        XCTAssertEqual(noTimes.line, MeetingRequestRules.shared.UNKNOWN)
        // Scheduling assistant, slice 2: their pick is booked on his tap; an unknown email books nothing.
        XCTAssertEqual(MeetingPickRules.shared.BOOK, "Book it")
        let unknownBooking = try await core.bookMeetingPick(id: "nothing")
        XCTAssertEqual(unknownBooking, MailNeedsRules.shared.UNKNOWN)
        // Slice 3: an answer turning the times down reads the same on the Mac.
        XCTAssertTrue(MeetingPickRules.shared.declines(text: "None of those work for me, sorry"))
        XCTAssertEqual(MeetingPickRules.shared.declinedLine(from: "Sam Taylor"), "Sam can't make the times you offered · Suggest times finds others")
        // Follow-up radar: his emails with no reply share the card.
        XCTAssertEqual(MailFollowUpRules.shared.TITLE, "No reply yet")
        // Follow-up radar, slice 3: per-person waits.
        XCTAssertEqual(MailFollowUpRules.shared.waitPill(days: 7), "Wait 1 week ▾")
        XCTAssertEqual(MailFollowUpRules.shared.waitChoices(who: "Sam", current: 3).filter { $0.chosen }.map { $0.days }, [3])
        XCTAssertEqual(MailRenewalRules.shared.headline(renewals: 0, promises: 0, followUps: 1), "1 email with no reply · nothing goes on Waiting for until you tap Add")
        // Slice 4: Mail → Chase waits — nobody set yet, and an unknown person changes nothing.
        let waits = try await core.followUpWaitsPanel()
        XCTAssertEqual(waits.title, FollowUpWaitsRules.shared.TITLE)
        XCTAssertTrue(waits.rows.isEmpty)
        let unknownWait = try await core.changeFollowUpWait(waitId: "fwnobody", days: 7)
        XCTAssertEqual(unknownWait, MailRenewalRules.shared.UNKNOWN)
        let noWaitUndo = try await core.undoFollowUpWaitChange()
        XCTAssertNil(noWaitUndo)
        // Life audit, slice 2: the review reads (nil until mail is connected); the closed card's words.
        let audit = try await core.lifeAuditReview()
        XCTAssertEqual(audit?.lines.count ?? 5, 5)
        XCTAssertEqual(LifeAuditReviewRules.shared.OPEN, "Review")
        XCTAssertEqual(LifeAuditReviewRules.shared.summary(subs: 0, onYou: 0, bills: 0, toAdd: 0), "Nothing needs you from 60 days of mail ✓")
        // Slice 7: the ten-minute inbox — no email, no card; a session over nothing ends "Inbox clear"; the time's key.
        XCTAssertNil(core.mailMoment.value)
        try await core.startMailMoment()
        XCTAssertEqual(core.mailMomentSession.value?.line, MailMomentRules.shared.CLEAR_LINE)
        XCTAssertNil(core.mailMomentSession.value?.row)
        try await core.skipMailMoment(id: "nothing")
        try await core.endMailMoment()
        XCTAssertNil(core.mailMomentSession.value)
        let morning = try await core.mailMomentTimeKey()
        XCTAssertEqual(morning, "morning")
        let evening = try await core.setMailMomentTimeKey(key: "evening")
        XCTAssertEqual(evening, "Ten-minute inbox · evenings from 20:00")
        let notToday = try await core.notTodayMailMoment()
        XCTAssertEqual(notToday, MailMomentRules.shared.PUT_OFF_LINE)
        try await core.undoMailMoment()
        XCTAssertEqual(MailMomentRules.shared.headline(n: 6), "6 need you · 2 min each")
        XCTAssertEqual(MailMomentTime.companion.of(key: "off"), MailMomentTime.off)
        XCTAssertEqual(MailMomentRules.shared.settingLine(time: MailMomentTime.morning),
                       "Mornings from 08:00 · Needs you offers a few minutes to clear what needs you")
    }

    /// The Done pill on Needs you cards (Meka, 2026-10-20 17:33): the Mac draws it from the core's numbers.
    func testDonePillSpecReachesSwift() {
        let spec = DonePillSpec.shared
        XCTAssertLessThanOrEqual(spec.HEIGHT_DP, 36)
        XCTAssertGreaterThanOrEqual(spec.TOUCH_DP, 48)
        XCTAssertEqual(spec.scale(pressed: true, reduced: false), 0.96, accuracy: 0.001)
        XCTAssertEqual(spec.scale(pressed: true, reduced: true), 1, accuracy: 0.001)
        XCTAssertEqual(spec.flashMs(reduced: true), 0)
    }

    /// The orb speed pass (Meka, 2026-10-20 19:57): the hello starts during the entrance and a click skips the rest.
    func testHelloStartsDuringTheEntranceAndAClickSkipsIt() {
        let e = HelloEntrance.shared
        XCTAssertLessThanOrEqual(e.GREET_AT, e.GREET_MAX_MS)
        XCTAssertLessThan(e.GREET_AT, e.REFORM_AT)
        XCTAssertEqual(e.tapThrough(kind: .big, t: 500, reduced: false)?.int64Value, e.BIG_MS)
        XCTAssertNil(e.tapThrough(kind: .big, t: e.BIG_MS, reduced: false))
        XCTAssertEqual(TalkEndpoint.shared.PAUSE_MS, 600)
    }

    /// The orb speed pass, slice 2: a turn's trace from Swift, times only, and the store reads back what it wrote.
    func testATurnsTraceSplitsTheWaitAndKeepsNumbersOnly() {
        let w = TurnStopwatch(kind: .talk, startMs: 0)
        w.sent(now: 100)
        w.firstPiece(now: 1_300)
        w.clip(now: 1_300, tookMs: 0, audio: true)
        guard let t = w.finish(wallStartMs: 0) else { return XCTFail("no trace") }
        XCTAssertEqual(t.toVoiceMs?.int64Value, 1_300)
        XCTAssertEqual(t.toFirstPieceMs?.int64Value, 1_200)
        let rules = TurnSpeedRules.shared
        XCTAssertEqual(rules.decode(text: rules.encode(traces: [t])), [t])
        XCTAssertEqual(rules.ANSWER_TARGET_MS, 1_500)
        XCTAssertEqual(rules.HELLO_TARGET_MS, 600)
    }

    /// The orb speed pass, slice 3: the hello's openers made ahead come first, are Navilife's own words, and include the
    /// Mac's welcome back.
    func testTheHellosOpenersAreMadeAhead() {
        let lines = HelloRules.shared.warmLines(minuteOfDay: 19 * 60, name: "Alex", welcome: true)
        XCTAssertEqual(lines.first, "Good evening, Alex.")
        XCTAssertTrue(lines.contains("Welcome back, Alex."))
        XCTAssertEqual(HelloRules.shared.WARM_EVERY_MS, 900_000)
        XCTAssertEqual(SpeechRules.shared.AHEAD_AUDIO_MS, 10_000)
    }
}

