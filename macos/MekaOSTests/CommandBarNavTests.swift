@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// The command bar's Go commands open the same places as the Go menu, and show its shortcuts (⌘1–⌘8).
final class CommandBarNavTests: XCTestCase {
    @MainActor
    func testGoCommandsMatchTheGoMenu() {
        let go: [CommandBarAction] = [.goToday, .goNeedsYou, .goCalendar, .goAsk, .goLists, .goGoals, .goReview, .goVault]
        XCTAssertEqual(go.compactMap { CommandBarNav.destination($0) }, ShellDestination.allCases)
        for a in go {
            let d = CommandBarNav.destination(a)!
            XCTAssertEqual(CommandBarNav.shortcut(a), "⌘\(d.rawValue + 1)")
            XCTAssertEqual(CommandBarNav.symbol(a), d.symbol)
        }
        XCTAssertNil(CommandBarNav.destination(.addTask))
        XCTAssertEqual(CommandBarNav.appearance(.appearanceAuto), .system)
        XCTAssertNil(CommandBarNav.appearance(.goToday))
    }

    @MainActor
    func testTheCoreCatalogueIsReachableFromSwift() {
        let ctx = CommandBarContext(atWork: false, fasting: false, fastGoalHours: 16, connected: true, appearance: "dark")
        let rows = CommandBarRules.shared.run(query: "rev", context: ctx).rows
        XCTAssertEqual(rows.first?.action, .goReview)
        XCTAssertEqual(rows.last?.action, .addTask)
        XCTAssertEqual(rows.last?.text, "rev")
    }

    @MainActor
    func testLongerFastRowsCarryTheirLengthToSwift() {
        let ctx = CommandBarContext(atWork: false, fasting: false, fastGoalHours: 16, connected: true, appearance: "dark")
        let row = CommandBarRules.shared.run(query: "5 day", context: ctx).rows.first
        XCTAssertEqual(row?.action, .fastLonger)
        XCTAssertEqual(row?.title, "Start a 5-day fast")
        XCTAssertEqual(row?.hours?.int32Value, 120)
        XCTAssertEqual(CommandBarNav.symbol(.fastLonger), CommandBarNav.symbol(.fastStart))
        XCTAssertNil(CommandBarNav.destination(.fastLonger))
        // The custom "until" check the Mac's popover shows.
        let now: Int64 = 1_760_000_000_000
        XCTAssertFalse(FastingRules.shared.untilPick(nowMs: now, untilMs: now + 3_600_000).ok)
        XCTAssertEqual(FastingRules.shared.untilPick(nowMs: now, untilMs: now + 36 * 3_600_000).line, "Goal 36 h 00 m · starts now")
        XCTAssertEqual(FastingRules.shared.untilLatest(nowMs: now) - FastingRules.shared.untilEarliest(nowMs: now), 228 * 3_600_000)
    }
}

