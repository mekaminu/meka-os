import XCTest
@testable import MekaOS

/// MEKA's icon, the Bezel M (Fold review 2026-10-12, polish 8): the asset catalog's AppIcon is the app's icon.
final class AppIconTests: XCTestCase {
    func testTheAppNamesItsIcon() {
        let name = Bundle.main.object(forInfoDictionaryKey: "CFBundleIconName") as? String
        XCTAssertEqual(name, "AppIcon")
    }
}
