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
}
