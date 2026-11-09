@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// The Today rebuild, slice 2d-2d: the Mac's Schedule is one compact card (core ScheduleCardRules) and gold is only for
/// clicks and the now mark (core TodayInkRules).
final class MacScheduleCardTests: XCTestCase {
    func testTheCardShowsTheStartAndPutsTheEndOnTheLine() {
        XCTAssertEqual(TimelineMetrics.shown("09:30–10:00", card: true), "09:30")
        XCTAssertEqual(TimelineMetrics.shown("09:30–10:00", card: false), "09:30–10:00")
        XCTAssertEqual(TimelineMetrics.shown("Until 10:00", card: true), "–10:00")
        XCTAssertEqual(ScheduleCardRules.shared.until(t: "09:30–10:00"), "until 10:00")
        XCTAssertNil(ScheduleCardRules.shared.until(t: "09:30"))
        XCTAssertEqual(ScheduleCardRules.shared.allDayTime(index: 0), "All day")
        XCTAssertNil(ScheduleCardRules.shared.allDayTime(index: 1))
        XCTAssertEqual(TimelineMetrics.column(card: true), 60)
        XCTAssertEqual(TimelineMetrics.column(card: false), TimelineMetrics.timeColumn)
    }

    func testGoldIsOnlyForClicksAndTheNowMark() {
        let p = MekaPalette.dark
        XCTAssertEqual(TodayInk.tap.color(p), p.accent)
        XCTAssertEqual(TodayInk.nowMark.color(p), p.accent)
        XCTAssertEqual(TodayInkRules.shared.detail(running: true).color(p), p.textSecondary)
        XCTAssertEqual(TodayInkRules.shared.clash(past: false).color(p), p.calendar3)
        XCTAssertEqual(TodayInkRules.shared.nowWords.color(p), p.textSecondary)
        XCTAssertEqual(TodayInkRules.shared.taskLine(late: true, carried: false, flagged: false)?.color(p), p.calendar3)
        XCTAssertNil(TodayInkRules.shared.taskLine(late: false, carried: false, flagged: true))
    }
}
