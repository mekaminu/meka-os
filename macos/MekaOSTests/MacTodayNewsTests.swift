@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// The Today rebuild, slice 2d-2e: the Mac's NEWS picture cards read core TodayNewsRules, as the Fold's do.
final class MacTodayNewsTests: XCTestCase {
    func testTheRowDriftsOnContinuouslyAsTheFoldsDoes() {
        let rules = TodayNewsRules.shared
        XCTAssertEqual(rules.LABEL, "NEWS")
        XCTAssertEqual(rules.CARD_WIDTH_DP, 250)
        XCTAssertEqual(rules.THUMB_DP, 56)
        XCTAssertEqual(rules.START_INDEX, 0)
        // Meka 2026-11-10: it drifts on continuously, Calm or Always moving; Reduce Motion, Off and one card stay still.
        XCTAssertTrue(rules.drifts(mode: TickerMode.calm, count: 4, reduced: false))
        XCTAssertTrue(rules.drifts(mode: TickerMode.moving, count: 4, reduced: false))
        XCTAssertFalse(rules.drifts(mode: TickerMode.calm, count: 4, reduced: true))
        XCTAssertFalse(rules.drifts(mode: TickerMode.off, count: 4, reduced: false))
        XCTAssertFalse(rules.drifts(mode: TickerMode.moving, count: 1, reduced: false))
        // A slow constant glide (40 pt a second), wrapping within one set of cards; his hover or drag rests it 4 s.
        XCTAssertEqual(rules.drift(offsetDp: 0, dtMs: 100, loopWidthDp: 1000), 4)
        XCTAssertEqual(rules.drift(offsetDp: 998, dtMs: 100, loopWidthDp: 1000), 2)
        XCTAssertEqual(rules.RESUME_AFTER_MS, 4_000)
        XCTAssertEqual(rules.hint(count: 1), "")
        XCTAssertEqual(rules.hint(count: 3), "swipe ›")
        XCTAssertEqual(rules.spokenLabel(count: 3), "News, 3 stories")
    }
}
