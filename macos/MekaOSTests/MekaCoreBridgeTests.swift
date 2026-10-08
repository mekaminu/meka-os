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
        try await core.briefSeen()
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
        // Slice 2: no fixture today, so no matchday line; nothing to show under Coming up either.
        XCTAssertNil(place.matchday)
        XCTAssertNil(CommandCentreRules.shared.newsGlance(place: place, maxItems: CommandCentreRules.shared.NEWS_ALONE))
        // Pictures (images slice): with no server there is none, and only server keys are ever asked for.
        let none = try await core.newsImageBase64(key: "0123456789abcdef0123456789abcdef")
        XCTAssertNil(none)
        let notAKey = try await core.newsImageBase64(key: "https://img.example/a.jpg")
        XCTAssertNil(notAKey)
        XCTAssertEqual(NewsRules.shared.tileInitial(source: "Mundo Deportivo"), "M")
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

    /// The opening moment's Day ring (motion pass 2, slice 7): the core's ring reaches Swift with its words and play rule.
    func testDayRingReachesSwift() {
        XCTAssertEqual(DayRingRules.shared.line(freeMinutes: 225, toDo: 4, nowMinute: 600), "3 h 45 free · 4 to do")
        XCTAssertEqual(DayRingRules.shared.freeLine(freeMinutes: 0, nowMinute: 1330), "Evening")
        XCTAssertEqual(DayRingRules.shared.toDoLine(toDo: 0), "Nothing to do")
        XCTAssertEqual(DayRingRules.shared.play(lastFullEpochDay: nil, todayEpochDay: 100, reduced: false), DayRingPlay.full)
        XCTAssertEqual(DayRingRules.shared.play(lastFullEpochDay: KotlinLong(longLong: 100), todayEpochDay: 100, reduced: false), DayRingPlay.quick)
        XCTAssertEqual(DayRingRules.shared.play(lastFullEpochDay: nil, todayEpochDay: 100, reduced: true), DayRingPlay.still)
        let arc = DayArc(id: "e-x", kind: .event, startMinute: 600, endMinute: 660, past: false)
        XCTAssertEqual(arc.startDegrees, 150, accuracy: 1e-4)
        XCTAssertEqual(arc.sweepDegrees, 15, accuracy: 1e-4)
        let ring = DayRing(arcs: [arc], nowMinute: 630, freeMinutes: 225, toDo: 4)
        XCTAssertEqual(ring.line, "3 h 45 free · 4 to do")
        XCTAssertEqual(ring.spokenLine, "Your day: 1 thing booked. Now 10:30. 3 h 45 free · 4 to do.")
    }
}
