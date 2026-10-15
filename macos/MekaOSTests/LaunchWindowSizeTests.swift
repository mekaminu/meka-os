@testable import MekaOS
import XCTest

/// `-MekaWindowSize` (the UI-test target's window sizes): only a sane "<width>x<height>" is used.
final class LaunchWindowSizeTests: XCTestCase {
    @MainActor
    func testParsesWidthByHeight() {
        XCTAssertEqual(LaunchWindowSize.parse("1440x900"), CGSize(width: 1440, height: 900))
        XCTAssertEqual(LaunchWindowSize.parse("1000X1100"), CGSize(width: 1000, height: 1100))
    }

    @MainActor
    func testIgnoresAnythingElse() {
        XCTAssertNil(LaunchWindowSize.parse(nil))
        XCTAssertNil(LaunchWindowSize.parse(""))
        XCTAssertNil(LaunchWindowSize.parse("1440"))
        XCTAssertNil(LaunchWindowSize.parse("big"))
        XCTAssertNil(LaunchWindowSize.parse("500x400"))   // under the window's 760×560 minimum
        XCTAssertNil(LaunchWindowSize.parse("1440x900x2"))
    }

    @MainActor
    func testAnOrdinaryLaunchAsksForNothing() {
        let defaults = UserDefaults(suiteName: "LaunchWindowSizeTests")!
        defaults.removePersistentDomain(forName: "LaunchWindowSizeTests")
        XCTAssertNil(LaunchWindowSize.requested(defaults))
    }

    @MainActor
    func testIdentifiersTheUITestsUse() {
        XCTAssertEqual(ShellView.sidebarID(.needsYou), "sidebar-needsYou")
        XCTAssertEqual(ShellView.screenID(.calendar), "screen-calendar")
        XCTAssertEqual(TodayView.columnID(.needsYou), "column-needs_you")
        XCTAssertEqual(TodayView.columnID(.comingUp), "column-coming_up")
    }
}
