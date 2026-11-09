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

    // The Today rebuild, slice 2d: Today is two columns from 600 pt; an open task keeps about a third beside it.
    @MainActor
    func testTodaySplitsAndAnOpenTaskKeepsAColumnBeside() {
        let cols = TodayColumnsRules.shared
        XCTAssertFalse(cols.macSplits(contentWidthPt: 599))
        XCTAssertTrue(cols.macSplits(contentWidthPt: 1000))
        XCTAssertEqual(cols.macWidths(contentWidthPt: 1000, taskOpen: false).map { CGFloat($0.floatValue) }, [1000])
        let open = cols.macWidths(contentWidthPt: 1440, taskOpen: true).map { CGFloat($0.floatValue) }
        XCTAssertEqual(open, [922, 518])
        XCTAssertTrue(cols.macTodaySplits(todayWidthPt: Float(open[0])))
        XCTAssertFalse(cols.macTodaySplits(todayWidthPt: 480))
        XCTAssertEqual(TodayView.leftColumnID, "column-today-left")
        XCTAssertEqual(TodayView.rightColumnID, "column-today-right")
    }

    func testFadesOnlyWhereThereIsMore() {
        let top = rules.edgeFades(offsetPt: 0, contentHeightPt: 1200, viewportHeightPt: 600)
        XCTAssertFalse(top.top)
        XCTAssertTrue(top.bottom)
        let fits = rules.edgeFades(offsetPt: 0, contentHeightPt: 300, viewportHeightPt: 600)
        XCTAssertFalse(fits.top)
        XCTAssertFalse(fits.bottom)
    }

    // Mac presentation pass, slice 2: the one setup line, no ticker beside the News list, the sidebar when wide.
    func testSetupIsOneQuietLineThatOpensSetup() {
        let line = MacHeaderRules.shared.setupLine(setupShown: true, stepsLeft: 4, health: nil)
        XCTAssertEqual(line?.text, "Finish setting up · 4 steps")
        XCTAssertEqual(line?.opens, MacHeaderOpens.setup)
        XCTAssertEqual(line?.critical, false)
        XCTAssertNil(MacHeaderRules.shared.setupLine(setupShown: false, stepsLeft: 4, health: nil))
    }

    func testTickerOnlyWhenTodayStandsAloneAndSidebarWhenWide() {
        XCTAssertFalse(MacHeaderRules.shared.tickerInToday(layout: rules.layout(contentWidthPt: 1000)))
        XCTAssertTrue(MacHeaderRules.shared.tickerInToday(layout: rules.layout(contentWidthPt: 500)))
        XCTAssertFalse(MacHeaderRules.shared.sidebarOnOpen(windowWidthPt: 1000))
        XCTAssertTrue(MacHeaderRules.shared.sidebarOnOpen(windowWidthPt: 1440))
    }

    // Mac presentation pass, item 9: the side columns ease in with Today's opening, once.
    func testSideColumnsFollowTodayAndStaggerOnlyWithTheOpening() {
        let cols = rules.columns(layout: rules.layout(contentWidthPt: 1440), taskOpen: false)
        let needs = rules.entrance(columns: cols, column: CommandColumn.needsYou, opening: true, reduced: false)
        let coming = rules.entrance(columns: cols, column: CommandColumn.comingUp, opening: true, reduced: false)
        XCTAssertEqual(Int(needs.step), 1)
        XCTAssertEqual(Int(coming.step), 2)
        XCTAssertEqual(CGFloat(needs.slidePt), 24)
        XCTAssertTrue(needs.staggers)
        XCTAssertFalse(rules.entrance(columns: cols, column: CommandColumn.needsYou, opening: false, reduced: false).staggers)
        XCTAssertEqual(CGFloat(rules.entrance(columns: cols, column: CommandColumn.comingUp, opening: true, reduced: true).slidePt), 0)
    }
}
