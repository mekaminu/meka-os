import XCTest

/// The Mac's UI checks (Mac presentation pass and "the Mac sidebar's items can't be clicked", Meka 2026-10-15): the app
/// is launched for real, the sidebar's rows are clicked one by one and Today is photographed at two window sizes. Run
/// by ci's nightly macOS job (its own scheme, MekaOSUI, signed to run locally); the screenshots are kept in the
/// result bundle and uploaded as the `mac-screenshots` artifact. Nothing here types or sends anything.
final class SidebarUITests: XCTestCase {
    /// Every sidebar row, in the sidebar's order (ShellDestination's case names).
    private static let rows = ["today", "needsYou", "calendar", "ask", "lists", "goals", "review", "vault"]

    @MainActor
    private func launch(size: String) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        // LaunchWindowSize: this launch's window size; no restored windows from an earlier launch.
        app.launchArguments += ["-MekaWindowSize", size, "-ApplePersistenceIgnoreState", "YES"]
        app.launch()
        XCTAssertTrue(app.windows.firstMatch.waitForExistence(timeout: 30), "Navilife's window didn't open")
        return app
    }

    /// The sidebar, shown if the window opened without it (⌃⌘S is View → Show Sidebar).
    @MainActor
    private func showSidebar(_ app: XCUIApplication) {
        let first = app.descendants(matching: .any)["sidebar-today"]
        if first.waitForExistence(timeout: 5), first.isHittable { return }
        app.typeKey("s", modifierFlags: [.command, .control])
        // A collapsed sidebar's rows still exist off-window, so wait until the first one can be clicked.
        let hittable = expectation(for: NSPredicate(format: "isHittable == true"), evaluatedWith: first)
        wait(for: [hittable], timeout: 5)
    }

    @MainActor
    func testEachSidebarRowSwitchesTheScreen() {
        let app = launch(size: "1440x900")
        showSidebar(app)
        // Away from Today first, so the last click (Today) is a real switch too.
        for name in Array(Self.rows.dropFirst()) + ["today"] {
            let row = app.descendants(matching: .any)["sidebar-" + name]
            XCTAssertTrue(row.waitForExistence(timeout: 5), "No sidebar row \(name)")
            row.click()
            XCTAssertTrue(app.descendants(matching: .any)["screen-" + name].waitForExistence(timeout: 5),
                          "Clicking \(name) in the sidebar didn't switch to it")
        }
        // ⌘3 (Go → Calendar) still switches, and a sidebar click after it still works.
        app.typeKey("3", modifierFlags: .command)
        XCTAssertTrue(app.descendants(matching: .any)["screen-calendar"].waitForExistence(timeout: 5))
        app.descendants(matching: .any)["sidebar-needsYou"].click()
        XCTAssertTrue(app.descendants(matching: .any)["screen-needsYou"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testTodayAt1440x900ShowsThreeColumnsInsideTheWindow() {
        let app = launch(size: "1440x900")
        let window = app.windows.firstMatch
        let today = app.descendants(matching: .any)["column-today"]
        XCTAssertTrue(today.waitForExistence(timeout: 30), "Today's column didn't appear")
        // Let the opening stagger and the columns' entrance finish before measuring and photographing.
        sleep(3)
        for id in ["column-today", "column-needs_you", "column-coming_up"] {
            let column = app.descendants(matching: .any)[id]
            XCTAssertTrue(column.waitForExistence(timeout: 10), "\(id) isn't showing at 1440×900")
            let f = column.frame, w = window.frame
            XCTAssertGreaterThan(f.width, 200, "\(id) is too narrow: \(f.width)")
            XCTAssertGreaterThanOrEqual(f.minX, w.minX - 1, "\(id) starts left of the window")
            XCTAssertLessThanOrEqual(f.maxX, w.maxX + 1, "\(id) runs past the window's right edge")
        }
        keep(window.screenshot(), named: "Today 1440x900")
    }

    @MainActor
    func testTodayAt1000WideIsTwoColumns() {
        // The runner's screen may be shorter than 1100 pt: macOS keeps the window inside it; the width is what counts.
        let app = launch(size: "1000x1100")
        let window = app.windows.firstMatch
        XCTAssertTrue(app.descendants(matching: .any)["column-today"].waitForExistence(timeout: 30))
        sleep(3)
        XCTAssertTrue(app.descendants(matching: .any)["column-needs_you"].waitForExistence(timeout: 10),
                      "Needs you isn't beside Today at 1000 pt")
        XCTAssertFalse(app.descendants(matching: .any)["column-coming_up"].exists, "Coming up has its own column at 1000 pt")
        keep(window.screenshot(), named: "Today 1000 wide")
    }

    @MainActor
    private func keep(_ shot: XCUIScreenshot, named name: String) {
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
