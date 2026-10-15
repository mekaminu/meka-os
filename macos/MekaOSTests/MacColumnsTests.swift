@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// Mac presentation pass, slice 1: Today's columns and the scroll edge fades come from core MacCommandCentreRules.
final class MacColumnsTests: XCTestCase {
    private let rules = MacCommandCentreRules.shared

    func testHisThousandPointWindowIsTwoColumnsWithComingUpUnderToday() {
        let layout = rules.layout(contentWidthPt: 1000)
        XCTAssertEqual(layout, CommandLayout.two)
        XCTAssertEqual(rules.columns(layout: layout, taskOpen: false), [CommandColumn.today, CommandColumn.needsYou])
        XCTAssertTrue(rules.comingUpUnderToday(layout: layout))
        XCTAssertFalse(rules.todayListsNeedsYou(layout: layout))
    }

    func testWideWindowGivesNeedsYouTheMostRoomAndTheWidthsAddUp() {
        let layout = rules.layout(contentWidthPt: 1440)
        XCTAssertEqual(layout, CommandLayout.three)
        let w = rules.widths(contentWidthPt: 1440, layout: layout).map { CGFloat($0.floatValue) }
        XCTAssertEqual(w.count, 3)
        XCTAssertEqual(w.reduce(0, +), 1440)
        XCTAssertGreaterThan(w[1], w[0])
        XCTAssertGreaterThan(w[0], w[2])
    }

    func testFadesOnlyWhereThereIsMore() {
        let top = rules.edgeFades(offsetPt: 0, contentHeightPt: 1200, viewportHeightPt: 600)
        XCTAssertFalse(top.top)
        XCTAssertTrue(top.bottom)
        let fits = rules.edgeFades(offsetPt: 0, contentHeightPt: 300, viewportHeightPt: 600)
        XCTAssertFalse(fits.top)
        XCTAssertFalse(fits.bottom)
    }
}
