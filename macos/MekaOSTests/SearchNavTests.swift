@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// Search results must go where they go on the Fold, rule for rule (android SearchNavTest).
final class SearchNavTests: XCTestCase {
    @MainActor
    func testListResultsOpenTheirTabGoalsAndHabitsOpenGoals() {
        XCTAssertEqual(SearchNav.destination(.listsWaiting), .lists)
        XCTAssertEqual(SearchNav.destination(.listsRenewals), .lists)
        XCTAssertEqual(SearchNav.destination(.goals), .goals)
        XCTAssertEqual(SearchNav.listTab(.listsWaiting), .waiting)
        XCTAssertEqual(SearchNav.listTab(.listsSomeday), .someday)
        XCTAssertEqual(SearchNav.listTab(.listsDecisions), .decisions)
        XCTAssertEqual(SearchNav.listTab(.listsRenewals), .renewals)
        XCTAssertEqual(SearchNav.destination(.listsShopping), .lists)
        XCTAssertEqual(SearchNav.listTab(.listsShopping), .shopping)
        XCTAssertEqual(SearchNav.hint(.listsShopping), "Opens Shopping")
        XCTAssertNil(SearchNav.listTab(.goals))
    }

    @MainActor
    func testTasksOpenInPlaceAndEventsGoNowhere() {
        XCTAssertNil(SearchNav.destination(.task))
        XCTAssertNil(SearchNav.destination(.info))
        XCTAssertEqual(SearchNav.hint(.task), "Opens the task")
        XCTAssertNil(SearchNav.hint(.info))
    }
}
