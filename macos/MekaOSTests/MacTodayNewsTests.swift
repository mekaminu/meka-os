@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// The Today rebuild, slice 2d-2e: the Mac's NEWS picture cards read core TodayNewsRules, as the Fold's do.
final class MacTodayNewsTests: XCTestCase {
    func testTheRowDriftsOnAsTheFoldsDoes() {
        let rules = TodayNewsRules.shared
        XCTAssertEqual(rules.LABEL, "NEWS")
        XCTAssertEqual(rules.CARD_WIDTH_DP, 250)
        XCTAssertEqual(rules.THUMB_DP, 56)
        XCTAssertEqual(rules.START_INDEX, 0)
        // Calm goes round twice, Always moving keeps going, Reduce Motion, Off and a single card stay still.
        XCTAssertEqual(rules.driftSteps(mode: TickerMode.calm, count: 4, reduced: false), 8)
        XCTAssertEqual(rules.driftSteps(mode: TickerMode.moving, count: 4, reduced: false), Int32.max)
        XCTAssertEqual(rules.driftSteps(mode: TickerMode.calm, count: 4, reduced: true), 0)
        XCTAssertEqual(rules.driftSteps(mode: TickerMode.off, count: 4, reduced: false), 0)
        XCTAssertEqual(rules.driftSteps(mode: TickerMode.calm, count: 1, reduced: false), 0)
        // Back to the first after the last.
        XCTAssertEqual(rules.nextIndex(index: 3, count: 4), 0)
        XCTAssertEqual(rules.nextIndex(index: 1, count: 4), 2)
        // His own hover or scroll pauses it for a few seconds.
        XCTAssertTrue(rules.mayDrift(touchedAtMs: nil, nowMs: 10_000))
        XCTAssertFalse(rules.mayDrift(touchedAtMs: KotlinLong(value: 9_000), nowMs: 10_000))
        XCTAssertTrue(rules.mayDrift(touchedAtMs: KotlinLong(value: 1_000), nowMs: 1_000 + rules.RESUME_AFTER_MS))
        XCTAssertEqual(rules.hint(count: 1), "")
        XCTAssertEqual(rules.hint(count: 3), "swipe ›")
        XCTAssertEqual(rules.spokenLabel(count: 3), "News, 3 stories")
    }
}
