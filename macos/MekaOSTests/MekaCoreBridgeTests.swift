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
}

