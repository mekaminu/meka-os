@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// View → Text Size (Mac text is too big, Meka 2026-10-15): the Mac's own type scale and the multiplier on it.
final class TextSizeSettingTests: XCTestCase {
    @MainActor
    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: TextSizeSetting.key)
        TextSizeSetting.apply("")
        super.tearDown()
    }

    @MainActor
    func testDefaultIsTheMacsOwnBodySize() {
        UserDefaults.standard.removeObject(forKey: TextSizeSetting.key)
        TextSizeSetting.apply()
        XCTAssertEqual(MekaTextScale.shared.factor, 1)
        XCTAssertEqual(MekaTextScale.size(13), 13)
        XCTAssertEqual(TextSizeSetting.current.id, "default")
    }

    @MainActor
    func testBiggerSmallerAndResetStepTheMultiplier() {
        TextSizeSetting.choose("default")
        TextSizeSetting.bigger()
        XCTAssertEqual(TextSizeSetting.stored, "larger")
        XCTAssertEqual(MekaTextScale.shared.factor, 1.15, accuracy: 0.0001)
        XCTAssertEqual(MekaTextScale.size(13), 15)
        TextSizeSetting.bigger(); TextSizeSetting.bigger()
        XCTAssertEqual(TextSizeSetting.stored, "largest")
        TextSizeSetting.reset()
        XCTAssertEqual(TextSizeSetting.stored, "default")
        TextSizeSetting.smaller()
        XCTAssertEqual(MekaTextScale.shared.factor, 0.9, accuracy: 0.0001)
        XCTAssertEqual(MekaTextScale.size(11), 10)
    }

    @MainActor
    func testTheClockKeepsItsSize() {
        TextSizeSetting.choose("largest")
        XCTAssertEqual(MekaType.clockTracking, -2.88, accuracy: 0.001)
        XCTAssertEqual(MekaType.bodyTracking, 0, accuracy: 0.001)
    }
}
