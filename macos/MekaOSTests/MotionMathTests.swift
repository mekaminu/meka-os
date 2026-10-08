@testable import MekaOS
import XCTest

/// The Mac choreography must match the Fold's (android MotionMathTest) number for number.
final class MotionMathTests: XCTestCase {
    @MainActor
    func testSectionsStagger40msApartAndCap() {
        XCTAssertEqual(MotionMath.staggerDelay(index: 0, reduced: false), 0)
        XCTAssertEqual(MotionMath.staggerDelay(index: 1, reduced: false), 0.040, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.staggerDelay(index: 3, reduced: false), 0.120, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.staggerDelay(index: 50, reduced: false), MotionMath.staggerDelay(index: 8, reduced: false))
        XCTAssertEqual(MotionMath.staggerSpan(count: 30, reduced: false), 0.320, accuracy: 1e-9)
    }

    @MainActor
    func testExpressiveSpacesWiderRisesFurtherAndGrowsFromNinetySixPercent() {
        XCTAssertEqual(MotionMath.staggerDelay(index: 1, reduced: false, expressive: true), 0.060, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.staggerDelay(index: 3, reduced: false, expressive: true), 0.180, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.staggerSpan(count: 30, reduced: false, expressive: true), 0.480, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.staggerDelay(index: 3, reduced: true, expressive: true), 0)
        XCTAssertEqual(MotionMath.riseDistance(expressive: true), 28)
        XCTAssertEqual(MotionMath.riseDistance(expressive: false), 12)
        XCTAssertEqual(MotionMath.countUpDuration(expressive: true), 0.900, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.countUpDuration(expressive: false), 0.700, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.entryScale(expressive: true, reduced: false), 0.96, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.entryScale(expressive: false, reduced: false), 1)
        XCTAssertEqual(MotionMath.entryScale(expressive: true, reduced: true), 1)
    }

    @MainActor
    func testTheMotionSettingFollowsMekasChoiceThenReduceMotion() {
        XCTAssertEqual(MotionSetting.effective("", systemReduce: false), .expressive)
        XCTAssertEqual(MotionSetting.effective("", systemReduce: true), .off)
        XCTAssertEqual(MotionSetting.effective("subtle", systemReduce: true), .subtle)
        XCTAssertEqual(MotionSetting.effective("expressive", systemReduce: true), .expressive)
        XCTAssertNil(MotionSetting.stored(""))
        XCTAssertEqual(MotionSetting.all.map(\.label), ["Expressive", "Subtle", "Off"])
    }

    @MainActor
    func testReduceMotionHasNoStagger() {
        XCTAssertEqual(MotionMath.staggerDelay(index: 5, reduced: true), 0)
    }

    @MainActor
    func testCountUpLandsOnTargetAndNeverGoesBackwards() {
        XCTAssertEqual(MotionMath.countUpValue(from: 0, to: 135, fraction: 0), 0)
        XCTAssertEqual(MotionMath.countUpValue(from: 0, to: 135, fraction: 1), 135)
        var last = -1
        for i in 0...100 {
            let v = MotionMath.countUpValue(from: 0, to: 135, fraction: Double(i) / 100)
            XCTAssertGreaterThanOrEqual(v, last)
            last = v
        }
        XCTAssertEqual(MotionMath.countUpValue(from: 20, to: 10, fraction: 1), 10)
        XCTAssertGreaterThan(MotionMath.easeOutCubic(0.5), 0.5)
    }

    @MainActor
    func testClicksPressInToNinetySevenPercentAndCardsLiftTwoPoints() {
        XCTAssertEqual(MotionMath.pressScale(pressed: true, reduced: false), 0.97, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.pressScale(pressed: false, reduced: false), 1)
        XCTAssertEqual(MotionMath.pressScale(pressed: true, reduced: true), 1)
        XCTAssertEqual(MotionMath.pressOpacity(pressed: true, reduced: true), 0.85, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.pressOpacity(pressed: true, reduced: false), 1)
        XCTAssertEqual(MotionMath.hoverLift(hovering: true, reduced: false), 2)
        XCTAssertEqual(MotionMath.hoverLift(hovering: false, reduced: false), 0)
        XCTAssertEqual(MotionMath.hoverLift(hovering: true, reduced: true), 0)
    }

    @MainActor
    func testTheBrassSyncRingTurnsOncePerPeriodAndStaysLongEnoughToSee() {
        let period = MekaChoreography.syncSpinPeriod
        XCTAssertEqual(MotionMath.ringSweepDegrees(progress: 0), 0)
        XCTAssertEqual(MotionMath.ringSweepDegrees(progress: 2), MotionMath.ringArcDegrees)
        XCTAssertLessThan(MotionMath.ringArcDegrees, 360) // the gap shows the turn
        XCTAssertEqual(MotionMath.ringSpinDegrees(elapsed: 0, reduced: false), 0)
        XCTAssertEqual(MotionMath.ringSpinDegrees(elapsed: period / 2, reduced: false), 180, accuracy: 1e-6)
        XCTAssertEqual(MotionMath.ringSpinDegrees(elapsed: period * 1.25, reduced: false), 90, accuracy: 1e-6)
        XCTAssertEqual(MotionMath.ringSpinDegrees(elapsed: period / 2, reduced: true), 0) // Off: a still ring
        XCTAssertEqual(MotionMath.ringHold(elapsed: 0), period, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.ringHold(elapsed: 0.3), period - 0.3, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.ringHold(elapsed: 5), 0)
    }

    @MainActor
    func testCompletingATaskSweepsTheRingThenFillsThenStrokesTheCheck() {
        XCTAssertEqual(MotionMath.checkDraw(reduced: false), 0.42, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.checkDraw(reduced: true), 0)
        XCTAssertEqual(MotionMath.checkRingDegrees(0), 0)
        XCTAssertEqual(MotionMath.checkFill(0), 0)
        XCTAssertEqual(MotionMath.checkStroke(0), 0)
        XCTAssertEqual(MotionMath.checkRingDegrees(0.5), 360, accuracy: 1e-9)
        XCTAssertGreaterThan(MotionMath.checkRingDegrees(0.25), 180) // eased out: fast start
        XCTAssertEqual(MotionMath.checkFill(0.3), 0)
        XCTAssertEqual(MotionMath.checkFill(0.5), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.checkStroke(0.5), 0)
        XCTAssertGreaterThan(MotionMath.checkStroke(0.75), 0.5)
        XCTAssertEqual(MotionMath.checkStroke(1), 1)
        XCTAssertEqual(MotionMath.checkStroke(1.2), 1) // never past done
        XCTAssertEqual(MotionMath.checkRingDegrees(1.2), 360, accuracy: 1e-9)
    }

    @MainActor
    func testAnEmptyStateRingBreathesSlowlyInAndOutAndHoldsStillWhenOff() {
        let period = MekaChoreography.emptyBreathPeriod
        XCTAssertEqual(period, 4.2, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.breath(elapsed: 0, reduced: false), 0, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.breath(elapsed: period / 2, reduced: false), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.breath(elapsed: period, reduced: false), 0, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.breath(elapsed: period / 4, reduced: false), 0.5, accuracy: 1e-6)
        XCTAssertEqual(MotionMath.breath(elapsed: period * 1.25, reduced: false),
                       MotionMath.breath(elapsed: period / 4, reduced: false), accuracy: 1e-9)
        XCTAssertEqual(MotionMath.breath(elapsed: -1, reduced: false), 0, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.breath(elapsed: 1.3, reduced: true), 1)
        XCTAssertEqual(MotionMath.breathScale(0), MotionMath.breathMinScale, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.breathScale(1), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.breathScale(1.3), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.breathGlow(0), MotionMath.breathMinGlow, accuracy: 1e-9)
        XCTAssertGreaterThan(MotionMath.breathGlow(0), 0)
    }

    @MainActor
    func testAStayingTickDrawsOnlyWhenItTurnsDoneHere() {
        // Ticked on this screen: the check draws.
        XCTAssertEqual(MotionMath.tickDraw(wasDone: false, done: true, reduced: false), .draw)
        // Already done when it appears (or ticked elsewhere before the screen opened): done at once, no replay.
        XCTAssertEqual(MotionMath.tickDraw(wasDone: nil, done: true, reduced: false), .done)
        XCTAssertEqual(MotionMath.tickDraw(wasDone: true, done: true, reduced: false), .done)
        // Unticked, or never ticked: back to the outline at once.
        XCTAssertEqual(MotionMath.tickDraw(wasDone: true, done: false, reduced: false), .rest)
        XCTAssertEqual(MotionMath.tickDraw(wasDone: nil, done: false, reduced: false), .rest)
        // Off: never draws.
        XCTAssertEqual(MotionMath.tickDraw(wasDone: false, done: true, reduced: true), .done)
        XCTAssertEqual(MotionMath.tickDraw(wasDone: true, done: false, reduced: true), .rest)
    }

    @MainActor
    func testTheDayRingDrawsItsMarkThenTheArcsAndNeedleThenCountsUp() {
        let full = DayRingPlayback.full
        XCTAssertEqual(MotionMath.dayRingMark(elapsed: 0, play: full), 0, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingMark(elapsed: 0.3, play: full), 0.875, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingMark(elapsed: 0.6, play: full), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingArc(elapsed: 0.3, index: 0, play: full, expressive: true), 0, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingArc(elapsed: 0.66, index: 0, play: full, expressive: true), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingArc(elapsed: 0.36, index: 1, play: full, expressive: true), 0, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingArc(elapsed: 0.72, index: 1, play: full, expressive: true), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingArc(elapsed: 0.34, index: 1, play: full, expressive: false), 0, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingNeedle(elapsed: 0.3, play: full), 0, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingNeedle(elapsed: 1.02, play: full), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingCount(elapsed: 0.6, play: full, expressive: true), 0, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingCount(elapsed: 1.5, play: full, expressive: true), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingCount(elapsed: 1.3, play: full, expressive: false), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingTotal(arcs: 3, play: full, expressive: true), 1.5, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingTotal(arcs: 3, play: full, expressive: false), 1.3, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingTotal(arcs: 20, play: full, expressive: true), 1.5, accuracy: 1e-9)
    }

    @MainActor
    func testLaterOpensPlayTheDayRingQuicklyAndOffDrawsItAtOnce() {
        XCTAssertEqual(MotionMath.dayRingMark(elapsed: 0, play: .quick), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingArc(elapsed: 0.15, index: 5, play: .quick, expressive: true), 0.875, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingNeedle(elapsed: 0.15, play: .quick), 0.875, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingCount(elapsed: 0.15, play: .quick, expressive: true), 0.5, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingTotal(arcs: 9, play: .quick, expressive: true), 0.3, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingMark(elapsed: 0, play: .still), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingArc(elapsed: 0, index: 3, play: .still, expressive: true), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingNeedle(elapsed: 0, play: .still), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingCount(elapsed: 0, play: .still, expressive: true), 1, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.dayRingTotal(arcs: 3, play: .still, expressive: true), 0, accuracy: 1e-9)
    }

    @MainActor
    func testListFootFadesIntoTheBarAndTheLastRowStillScrollsClear() {
        XCTAssertEqual(MotionMath.footFade, 24)
        XCTAssertEqual(MotionMath.footAlpha(fromFoot: 0), 0)
        XCTAssertEqual(MotionMath.footAlpha(fromFoot: 12), 0.5, accuracy: 1e-9)
        XCTAssertEqual(MotionMath.footAlpha(fromFoot: 24), 1)
        XCTAssertEqual(MotionMath.footAlpha(fromFoot: 400), 1)
        XCTAssertEqual(MotionMath.footAlpha(fromFoot: -3), 0)
        XCTAssertTrue(MotionMath.footClear(bottomPadding: MekaSpace.xl))
        XCTAssertFalse(MotionMath.footClear(bottomPadding: MekaSpace.s))
    }
}
