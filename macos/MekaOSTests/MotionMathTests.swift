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
}
