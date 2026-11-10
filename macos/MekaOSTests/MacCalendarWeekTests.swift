@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// Calendar → Week on the Mac (Option A, approved 2026-11-10): the view, its days and its steps come from core
/// CalendarWeekRules, shared with the Fold.
final class MacCalendarWeekTests: XCTestCase {
    private let rules = CalendarWeekRules.shared
    /// Monday 9 November 2026.
    private let monday: Int64 = 20766

    func testCalendarOpensOnWeekAndKnowsBoardAndAgenda() {
        XCTAssertEqual(CalendarMode.companion.of(id: nil), CalendarMode.week)
        XCTAssertEqual(CalendarMode.companion.of(id: "board"), CalendarMode.board)
        XCTAssertEqual(CalendarMode.companion.of(id: "agenda"), CalendarMode.agenda)
        XCTAssertEqual([CalendarMode.week, CalendarMode.board, CalendarMode.agenda].map(\.label), ["Week", "Board", "Agenda"])
    }

    func testTheMacShowsFiveDaysUnlessSevenWereChosen() {
        XCTAssertEqual(rules.openDays(stored: nil), 5)
        XCTAssertEqual(rules.openDays(stored: KotlinInt(value: 7)), 7)
        XCTAssertEqual(rules.openDays(stored: KotlinInt(value: 3)), 5)
        XCTAssertEqual(rules.OPEN_DAYS.map(\.int32Value), [5, 7])
        XCTAssertEqual(rules.daysLabel(n: 7), "7 days")
    }

    func testAWeekStartsOnMondayAndStepsAWeekAtATime() {
        XCTAssertEqual(rules.start(anchor: monday + 3, count: 5), monday)
        XCTAssertEqual(rules.start(anchor: monday + 6, count: 7), monday)
        XCTAssertEqual(rules.step(start: monday, count: 5, delta: 1), monday + 7)
        XCTAssertEqual(rules.step(start: monday, count: 7, delta: -1), monday - 7)
    }

    func testTheGridRunsFromEightToNineAtFortyPointsAnHour() {
        XCTAssertEqual(rules.hours.first, "08:00")
        XCTAssertEqual(rules.hours.last, "20:00")
        XCTAssertEqual(rules.heightDp(hourDp: rules.HOUR_DP), 13 * 40)
        XCTAssertEqual(rules.minuteDp(minute: 9 * 60 + 30, hourDp: rules.HOUR_DP), 60)
        XCTAssertEqual(rules.hoursLabel(minutes: 348), "5.8 h")
    }

    func testHourLabelsSitOnTheirLinesAndShortBlocksAreOneLine() {
        // Centred on the line (7 pt up), the first kept inside the grid; under 33 pt a block is one line (a 45 min call).
        XCTAssertEqual(rules.hourLabelTopDp(index: 0, hourDp: rules.HOUR_DP), 0)
        XCTAssertEqual(rules.hourLabelTopDp(index: 2, hourDp: rules.HOUR_DP), 73)
        XCTAssertEqual(rules.TWO_LINE_DP, 33)
        XCTAssertEqual(rules.allDayLabel(folded: false), "all day")
    }

    func testADraggedEventSnapsToQuarterHoursAndDaysInView() {
        // A 10:00 event: 130 pt across 100 pt columns is a day; 25 pt down at 40 pt an hour is 45 minutes (to the quarter).
        let d = rules.dropAt(startMinute: 600, dayIndex: 0, startEpochDay: monday, count: 5, dxDp: 130, dyDp: 25, columnDp: 100, hourDp: 40, today: monday)
        XCTAssertEqual(d?.days, 1)
        XCTAssertEqual(d?.minutes, 45)
        XCTAssertEqual(d?.hint, "Move to Tomorrow · 10:45")
        // Let go where it was: nothing moves; far right from Friday: it stays on Friday.
        XCTAssertNil(rules.dropAt(startMinute: 600, dayIndex: 2, startEpochDay: monday, count: 5, dxDp: 20, dyDp: 4, columnDp: 100, hourDp: 40, today: monday))
        XCTAssertEqual(rules.dropAt(startMinute: 600, dayIndex: 4, startEpochDay: monday, count: 5, dxDp: 900, dyDp: 40, columnDp: 100, hourDp: 40, today: monday)?.days, 0)
    }
}
