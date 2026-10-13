import XCTest
@testable import MekaOS

/// MEKA's icon, the Hands M (Meka chose option B, 2026-10-12): the asset catalog's AppIcon is the app's icon.
final class AppIconTests: XCTestCase {
    func testTheAppNamesItsIcon() {
        let name = Bundle.main.object(forInfoDictionaryKey: "CFBundleIconName") as? String
        XCTAssertEqual(name, "AppIcon")
    }
}
