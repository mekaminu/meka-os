@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// The Today rebuild, slice 2d-2d: the Mac's Schedule is one compact card (core ScheduleCardRules) and gold is only for
/// clicks and the now mark (core TodayInkRules).
final class MacScheduleCardTests: XCTestCase {
    @MainActor
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

    @MainActor
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

    /// Today regression 3 (Meka 2026-11-11): every row wears one 2 pt edge on the card's surface; work and the commute
    /// are one dim line, never the outlined band.
    @MainActor
    func testEveryRowWearsOneEdgeAndWorkIsOneDimLine() {
        let p = MekaPalette.dark
        let rules = ScheduleCardRules.shared
        XCTAssertEqual(rules.edgeInk(kind: .event, running: false, fixture: false)?.color(p), p.calendarTone(1))
        XCTAssertEqual(rules.edgeInk(kind: .event, running: false, fixture: true)?.color(p), p.barca)
        XCTAssertEqual(rules.edgeInk(kind: .work, running: false, fixture: false)?.color(p), p.hairline)
        XCTAssertEqual(rules.edgeInk(kind: .work, running: true, fixture: false)?.color(p), p.accent)
        XCTAssertNil(rules.edgeInk(kind: .now, running: true, fixture: false))
        XCTAssertTrue(rules.isBackground(kind: .commute))
        XCTAssertEqual(rules.backgroundLine(title: "Work", detail: "Now · until 17:30"), "Work · Now · until 17:30")
    }
}
