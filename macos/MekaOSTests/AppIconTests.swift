import XCTest
@testable import MekaOS

/// Navilife's icon, logo N3·2 (Meka picked it 2026-10-13 with the rename): the asset catalog's AppIcon is the app's
/// icon, and the menu bar and Dock say Navilife (the code name stays MekaOS).
final class AppIconTests: XCTestCase {
    func testTheAppNamesItsIcon() {
        let name = Bundle.main.object(forInfoDictionaryKey: "CFBundleIconName") as? String
        XCTAssertEqual(name, "AppIcon")
    }

    func testTheAppIsCalledNavilife() {
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "CFBundleName") as? String, "Navilife")
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String, "Navilife")
    }
}
