@preconcurrency import MekaCore
import XCTest

/// Proves the Kotlin facade is usable from Swift: commands are async, flows are AsyncSequences.
final class MekaCoreBridgeTests: XCTestCase {
    func testAddAndCompleteThroughTheBridge() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false,
            databaseDirectory: NSTemporaryDirectory(), databaseName: "bridge-\(UUID().uuidString).db"
        )
        let id = try await core.addTask(title: "Bridge works")
        var iterator = core.today.makeAsyncIterator()
        let today = await iterator.next()
        XCTAssertEqual(today?.upNext?.id, id)
        try await core.complete(taskId: id)
        XCTAssertTrue(core.today.value.isClear)
    }
}
