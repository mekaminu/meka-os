@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// Health on the Mac (Reliability first, item 3): which of a row's fixes the Mac can do itself.
final class HealthSheetTests: XCTestCase {
    @MainActor
    func testTheMacDoesItsOwnFixesAndLeavesThePhonesToThePhone() {
        func row(_ fix: HealthFix) -> HealthRow {
            HealthRow(key: "k", title: "t", line: "l", state: .warn, fix: fix, fixLabel: "Fix", provider: nil, account: nil, editing: false)
        }
        XCTAssertTrue(HealthSheet.fixHere(row(.reconnect)))
        XCTAssertTrue(HealthSheet.fixHere(row(.syncNow)))
        XCTAssertTrue(HealthSheet.fixHere(row(.calendars)))
        XCTAssertFalse(HealthSheet.fixHere(row(.battery)))
        XCTAssertFalse(HealthSheet.fixHere(row(.notificationAccess)))
        XCTAssertFalse(HealthSheet.fixHere(row(.callRole)))
        XCTAssertEqual(MoreItem.health.label, "Health")
    }
}
