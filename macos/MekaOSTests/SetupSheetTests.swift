@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// Setup on the Mac (Setup checklist): which steps' buttons the Mac can do itself, and the day "Not today" counts.
final class SetupSheetTests: XCTestCase {
    @MainActor
    func testTheMacDoesItsOwnStepsAndLeavesThePhonesToThePhone() {
        func step(_ fix: SetupFix, _ state: SetupState = .todo) -> SetupStep {
            SetupStep(key: "k", title: "t", line: "l", state: state, fix: fix, fixLabel: "Fix")
        }
        XCTAssertTrue(SetupSheet.fixHere(step(.calendars)))
        XCTAssertTrue(SetupSheet.fixHere(step(.voice)))
        XCTAssertTrue(SetupSheet.fixHere(step(.notifications)))
        XCTAssertFalse(SetupSheet.fixHere(step(.callRole)))
        XCTAssertFalse(SetupSheet.fixHere(step(.battery)))
        XCTAssertFalse(SetupSheet.fixHere(step(.notificationAccess)))
        // A done step has no button.
        XCTAssertFalse(SetupSheet.fixHere(step(.calendars, .done)))
        XCTAssertEqual(MoreItem.setup.label, "Setup")
    }

    @MainActor
    func testTodayIsTheLocalDayAsTheCoreCountsIt() {
        let london = TimeZone(identifier: "Europe/London")!
        // Fri 9 Oct 2026 23:30 UTC is already Saturday 10 Oct in London (BST).
        let late = Date(timeIntervalSince1970: 1_791_588_600)
        XCTAssertEqual(SetupSheet.today(late, zone: london), SetupSheet.today(late, zone: TimeZone(identifier: "UTC")!) + 1)
        XCTAssertEqual(SetupSheet.today(Date(timeIntervalSince1970: 0), zone: TimeZone(identifier: "UTC")!), 0)
    }
}
