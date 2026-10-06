@testable import MekaOS
import XCTest

/// The Mac shell must agree with the Fold's rule for rule (android ShellNavTest).
final class ShellNavTests: XCTestCase {
    @MainActor
    func testClosedFoldGetsTheBarOpenFoldGetsTheRail() {
        XCTAssertEqual(ShellNav.layout(forWidth: 360), .bottomBar)
        XCTAssertEqual(ShellNav.layout(forWidth: 599.9), .bottomBar)
        XCTAssertEqual(ShellNav.layout(forWidth: 600), .rail)
    }

    @MainActor
    func testBarHasFiveSidebarHasAllSixInOrder() {
        XCTAssertEqual(ShellNav.destinations(.bottomBar).map(\.label), ["Today", "Needs you", "Lists", "Goals", "Review"])
        XCTAssertEqual(ShellNav.destinations(.rail).map(\.label), ["Today", "Needs you", "Lists", "Goals", "Review", "Vault"])
        XCTAssertNil(ShellNav.barSelection(.vault, .bottomBar))
        XCTAssertEqual(ShellNav.barSelection(.vault, .rail), .vault)
    }

    @MainActor
    func testContentMovesTheWayYouMoved() {
        XCTAssertEqual(ShellNav.direction(from: .today, to: .review), 1)
        XCTAssertEqual(ShellNav.direction(from: .review, to: .needsYou), -1)
        XCTAssertEqual(ShellNav.direction(from: .lists, to: .lists), 0)
    }

    @MainActor
    func testBadgeAndScreenReaderLabel() {
        XCTAssertNil(ShellNav.badge(0))
        XCTAssertEqual(ShellNav.badge(3), "3")
        XCTAssertEqual(ShellNav.badge(10), "9+")
        XCTAssertEqual(ShellNav.accessibilityLabel(.needsYou, needsYouCount: 12), "Needs you, 12 waiting")
        XCTAssertEqual(ShellNav.accessibilityLabel(.today, needsYouCount: 12), "Today")
    }

    @MainActor
    func testEveryUpcomingDestinationSaysWhatIsComing() {
        for d in [ShellDestination.review, .vault] { XCTAssertNotNil(ShellNav.upcomingLine(d)) }
        for d in [ShellDestination.today, .needsYou, .lists, .goals] { XCTAssertNil(ShellNav.upcomingLine(d)) }
    }
}
