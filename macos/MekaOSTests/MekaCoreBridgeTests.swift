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
        XCTAssertTrue(v.left.first?.canSomeday == true)
        try await core.carryAllToTomorrow()
        v = core.shutdownView.value
        XCTAssertTrue(v.left.isEmpty)
        XCTAssertEqual(v.tomorrow.rows.map(\.title), ["Post the letter"])
        XCTAssertEqual(v.tomorrow.glance, "Tomorrow: 1 task")
        try await core.shutDown()
        XCTAssertTrue(core.shutdownView.value.doneToday)
        XCTAssertFalse(core.shutdownView.value.offered)
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
        XCTAssertNotEqual(interruptions?.line, "Counted once MEKA can notify you on a device")
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
        XCTAssertEqual(WeatherPlaceRules.shared.HOME, "Biggleswade")
        XCTAssertEqual(WeatherPlaceRules.shared.normalize(input: "  St   Neots "), "St Neots")
        XCTAssertNil(WeatherPlaceRules.shared.normalize(input: "123"))
        XCTAssertTrue(WeatherPlaceRules.shared.accepts(input: "Bedford"))
        XCTAssertFalse(WeatherPlaceRules.shared.accepts(input: "<b>"))
        XCTAssertTrue(WeatherPlaceRules.shared.isHome(name: "biggleswade"))
        let home = WeatherForecast(place: "Biggleswade", hours: [], days: [], asked: "Xyzzy", found: false)
        let lit = WeatherPlaceRules.shared.view(wanted: "Xyzzy", f: home)
        XCTAssertTrue(lit.lit)
        XCTAssertEqual(lit.line, "Couldn't find “Xyzzy” — showing Biggleswade. Try the nearest town.")
        XCTAssertTrue(WeatherPlaceRules.shared.view(wanted: "Bedford", f: home).pending)
    }

    /// MEKA's voice: Talk cuts a line into the pieces it asks MEKA's server to say, in Swift as on the Fold.
    func testSpeechPiecesReachSwift() {
        XCTAssertEqual(SpeechRules.shared.pieces(text: "You've got three things today. Training is at 18:00. Anything else?"),
                       ["You've got three things today.", "Training is at 18:00. Anything else?"])
        XCTAssertEqual(SpeechRules.shared.pieces(text: "  "), [])
        XCTAssertEqual(SpeechRules.shared.FIRST_AUDIO_MS, 1200)
        XCTAssertEqual(MekaVoiceRules.shared.normalize(name: " device "), MekaVoiceRules.shared.DEVICE)
        XCTAssertEqual(SpeechRules.shared.usageLine(state: "on", month: "2026-10", usedChars: 12400, capChars: 1000000,
                                                    voice: "Amy", deviceChosen: false),
                       "MEKA's voice · Amy · 12,400 of 1,000,000 characters in October")
        XCTAssertNil(SpeechRules.shared.usageLine(state: "off", month: nil, usedChars: 0, capChars: 0, voice: nil, deviceChosen: false))
    }

    /// The voice picker (Weather and a voice, item 2): MEKA's voices first, then the Mac's own, reach Swift.
    func testVoicePickerReachesSwift() {
        let offered = [OfferedVoice(id: "Amy", gender: "Female", engine: "generative"), OfferedVoice(id: "Brian", gender: "Male", engine: "neural")]
        let v = VoicePickerRules.shared.view(state: "on", offered: offered, defaultVoice: "Amy", chosen: "Brian", usageLine: nil,
                                             mac: true, connected: true, loaded: true)
        XCTAssertEqual(v.choices.map(\.id), ["Amy", "Brian", "device"])
        XCTAssertEqual(v.choices.filter(\.selected).map(\.id), ["Brian"])
        XCTAssertEqual(v.choices.last?.label, "This Mac's own voice")
        XCTAssertEqual(v.choices.first?.detail, "British · female · most natural · MEKA's default")
        XCTAssertTrue(v.help.contains("Manage Voices"))
        XCTAssertTrue(VoicePickerRules.shared.SAMPLE.hasPrefix("Good morning, Meka."))
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

    /// Under "Nothing needs you" (Fold reviews 2026-10-09): the core's habits / Waiting on / renewals reach Swift.
    func testNeedsYouMeanwhileReachesSwift() {
        let m = NeedsYouMeanwhileRules.shared.build(goals: nil, lists: nil)
        XCTAssertTrue(m.isEmpty)
        XCTAssertTrue(m.sections.isEmpty)
        XCTAssertNil(m.habitsLine)
        XCTAssertEqual(NeedsYouMeanwhileRules.shared.HABITS_LABEL, "Habits today")
        XCTAssertEqual(NeedsYouMeanwhileRules.shared.WAITING_LABEL, "Waiting on")
        XCTAssertEqual(NeedsYouMeanwhileRules.shared.more(total: 5), "+2 more")
        XCTAssertNil(NeedsYouMeanwhileRules.shared.more(total: 3))
    }

    /// The spoken morning brief (Weather and a voice, slice 8): the script the brief sheet's Listen reads reaches Swift.
    func testBriefSpeechReachesSwift() {
        XCTAssertEqual(BriefSpeech.shared.script(v: MorningBriefView.companion.EMPTY, name: "Meka"),
                       "Good morning, Meka. Nothing's planned yet. That's your morning.")
        XCTAssertEqual(BriefSpeech.shared.spokenDate(label: "Fri 9 Oct"), "Friday 9 October")
        XCTAssertEqual(BriefSpeech.shared.clean(text: "9–15°, light rain from 15:00 — take a coat"),
                       "9 to 15 degrees, light rain from 15:00, take a coat")
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

    func testMotionCheckReachesSwift() {
        let off = MotionCheckRules.shared.mac(stored: nil, reduceMotion: true, lowPower: false)
        XCTAssertEqual(off.rows.map(\.label), ["MEKA Motion", "Reduce Motion", "Low Power Mode"])
        XCTAssertEqual(off.result, "Animations off because the Mac's Reduce Motion is on — click to use MEKA's own setting")
        XCTAssertEqual(off.fix?.id, "expressive")
        let on = MotionCheckRules.shared.mac(stored: .expressive, reduceMotion: false, lowPower: false)
        XCTAssertEqual(on.result, "Animations on · Expressive")
        XCTAssertNil(on.fix)
        XCTAssertEqual(MotionCheckRules.shared.PLAY_OPENING, "Play the opening")
        XCTAssertEqual(AppUpdateRules.shared.versionLine(versionName: "0.1.0", versionCode: 1, latestCode: nil), "MEKA 0.1.0 · build 1")
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
        XCTAssertEqual(CalendarAccessRules.shared.line(provider: "google", canEdit: false, needsReconnect: false), "Read-only · MEKA only reads this calendar")
        XCTAssertTrue(CalendarAccessRules.shared.reconnectAsksEditing(canEdit: true))
        let a = ConnectedAccount(provider: "google", email: "me@gmail.com", status: "ok", lastSyncAtMs: nil, canEdit: true, title: "Personal")
        XCTAssertEqual(a.editingAction, .stopEditing)
    }

    /// Meka's screenshot 2026-10-09 09:01: Calendars titles accounts like their main calendar; "news_more" reads Headlines.
    func testCalendarAccountLinesReachSwift() {
        XCTAssertEqual(CalendarAccountRules.shared.providerLabel(provider: "news_more"), "Headlines")
        XCTAssertEqual(CalendarAccountRules.shared.title(provider: "microsoft", email: "me@hotmail.co.uk", names: [:]), "Hotmail")
        let a = ConnectedAccount(provider: "google", email: "me@gmail.com", status: "ok", lastSyncAtMs: nil, canEdit: false,
                                 title: CalendarAccountRules.shared.title(provider: "google", email: "me@gmail.com", names: [:]))
        XCTAssertEqual(a.title, "Personal")
        XCTAssertEqual(a.statusLine(syncedAt: "08:29"), "Google · me@gmail.com · synced 08:29")
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
        let note = EventEditNote(editId: "e1", text: "This cancels it for 4 people", needsMeka: true, deleteAnyway: true, waiting: false)
        XCTAssertTrue(note.deleteAnyway)
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
        XCTAssertTrue(TalkProblem.noOnDevice.macLine.contains("nothing is sent away"))
        XCTAssertEqual(TalkOrb.shared.levelDbfs(db: -50), 0)
        XCTAssertEqual(TalkOrb.shared.levelDbfs(db: -3), 1)
        XCTAssertEqual(TalkEndpoint.shared.step(startedMs: 0, heardAnything: false, lastWordsMs: 0, nowMs: 9_000), .silence)
        XCTAssertEqual(TalkEndpoint.shared.step(startedMs: 0, heardAnything: true, lastWordsMs: 2_000, nowMs: 3_000), .keep)
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
                            target: .task, task: nil, score: 5)
        let view = SearchView(query: "den", groups: [SearchGroup(kind: .task, label: "Tasks", hits: [hit], more: 0)], total: 3)
        let m = rules.matches(view: view, typed: "den", canAsk: true)
        XCTAssertEqual(m?.rows.first?.line, "Task · Planned today 14:00")
        XCTAssertEqual(m?.seeAll, "See all 3 matches")
        XCTAssertNil(rules.matches(view: view, typed: "dentist", canAsk: true))
        XCTAssertTrue(rules.idleLine(canAsk: true, mac: true).hasSuffix("click."))
    }
}
