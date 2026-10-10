import XCTest
@testable import MekaOS

/// MEKA's watch-face icon (call assistant polish item 9): the asset catalog's AppIcon is the app's icon.
final class AppIconTests: XCTestCase {
    func testTheAppNamesItsWatchFaceIcon() {
        let name = Bundle.main.object(forInfoDictionaryKey: "CFBundleIconName") as? String
        XCTAssertEqual(name, "AppIcon")
    }
}
