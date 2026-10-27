import AppKit
import XCTest
@testable import MekaOS

/// Menus, popovers and alerts follow Navilife's Appearance, not only the window (Meka 2026-10-26 23:18, (5): a white
/// menu with light text).
@MainActor
final class MekaAppearanceTests: XCTestCase {
    func testEachChoiceNamesItsAppKitAppearance() {
        XCTAssertEqual(MekaAppearance.dark.nsAppearance?.name, .darkAqua)
        XCTAssertEqual(MekaAppearance.light.nsAppearance?.name, .aqua)
        XCTAssertNil(MekaAppearance.system.nsAppearance)
    }

    func testTheAppTakesTheChoiceAndUnknownMeansDark() {
        let before = NSApp.appearance
        defer { NSApp.appearance = before }
        MekaAppearance.applyToApp("light")
        XCTAssertEqual(NSApp.appearance?.name, .aqua)
        MekaAppearance.applyToApp("nonsense")
        XCTAssertEqual(NSApp.appearance?.name, .darkAqua)
        MekaAppearance.applyToApp("system")
        XCTAssertNil(NSApp.appearance)
    }
}
