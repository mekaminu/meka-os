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
}
