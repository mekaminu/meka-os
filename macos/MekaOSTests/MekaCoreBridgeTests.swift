@preconcurrency import MekaKit
import XCTest

/// Proves the Kotlin facade is usable from Swift: commands are async, flows are AsyncSequences.
final class MekaCoreBridgeTests: XCTestCase {
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
        try await core.endFast()
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
        try await core.shutDown()
        XCTAssertTrue(core.shutdownView.value.doneToday)
        XCTAssertFalse(core.shutdownView.value.offered)
    }
}
