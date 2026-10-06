@testable import MekaOS
import XCTest

/// Must agree with the Fold's rules (android SharedMotionTest) for the parts both apps use.
final class SharedMotionTests: XCTestCase {
    @MainActor
    func testOneKeyPerTaskAndTheLandedLightLastsAsOnTheFold() {
        XCTAssertEqual(SharedMotion.taskKey("abc"), "task-abc")
        XCTAssertEqual(SharedMotion.landedSeconds, 1.2, accuracy: 0.0001)
    }

    @MainActor
    func testLandedTasksAreLitOnlyOnceThePlanHasGone() {
        XCTAssertFalse(SharedMotion.highlightLanded("a", planOpen: true, landing: ["a"]))
        XCTAssertTrue(SharedMotion.highlightLanded("a", planOpen: false, landing: ["a"]))
        XCTAssertFalse(SharedMotion.highlightLanded("b", planOpen: false, landing: ["a"]))
    }
}
