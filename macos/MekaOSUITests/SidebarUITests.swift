import XCTest

/// The Mac's UI checks (Mac presentation pass and "the Mac sidebar's items can't be clicked", Meka 2026-10-15): the app
/// is launched for real, the sidebar's rows are clicked one by one and Today is photographed at two window sizes. Run
/// by ci's nightly macOS job (its own scheme, MekaOSUI, signed to run locally); the screenshots are kept in the
/// result bundle and uploaded as the `mac-screenshots` artifact. Nothing here types or sends anything.
final class SidebarUITests: XCTestCase {
    /// Every sidebar row, in the sidebar's order (ShellDestination's case names).
    private static let rows = ["today", "needsYou", "calendar", "ask", "lists", "goals", "review", "vault"]

    @MainActor
    private func launch(size: String, still: Bool = false) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        // LaunchWindowSize: this launch's window size; no restored windows from an earlier launch.
        app.launchArguments += ["-MekaWindowSize", size, "-ApplePersistenceIgnoreState", "YES"]
        // Nightly run 2026-11-10: Today's news now drifts for as long as Today shows, so the column checks launch with
        // Appearance → Motion → Off for this launch only (the argument domain; the stored choice is untouched): the
        // page stands still to be measured and photographed.
        if still { app.launchArguments += ["-meka.motion", "off"] }
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
            if !app.descendants(matching: .any)["screen-" + name].waitForExistence(timeout: 10) {
                // Nightly run 2026-10-25 (Review): say what the window holds instead, on one line for the annotation.
                if app.state == .notRunning {
                    XCTFail("Navilife quit when \(name) was clicked in the sidebar (the next step shows its crash report)")
                    return
                }
                let shot = XCTAttachment(screenshot: app.screenshot())
                shot.name = "no-screen-\(name)"
                shot.lifetime = .keepAlways
                add(shot)
                let screens = app.descendants(matching: .any)
                    .matching(NSPredicate(format: "identifier BEGINSWITH 'screen-'")).allElementsBoundByIndex.map(\.identifier)
                let texts = app.windows.firstMatch.staticTexts.allElementsBoundByIndex.prefix(30)
                    .map { $0.label.isEmpty ? $0.identifier : $0.label }.filter { !$0.isEmpty }
                XCTFail("Clicking \(name) in the sidebar didn't switch to it; screens: \(screens); texts: \(texts.joined(separator: " | "))")
            }
        }
        // ⌘3 (Go → Calendar) still switches, and a sidebar click after it still works.
        app.typeKey("3", modifierFlags: .command)
        XCTAssertTrue(app.descendants(matching: .any)["screen-calendar"].waitForExistence(timeout: 5))
        app.descendants(matching: .any)["sidebar-needsYou"].click()
        XCTAssertTrue(app.descendants(matching: .any)["screen-needsYou"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testTodayAt1440x900IsTwoColumnsInsideTheWindow() {
        // The Today rebuild, slice 2d (core TodayColumnsRules): Today is two columns across the window, under the header.
        let app = launch(size: "1440x900", still: true)
        let window = app.windows.firstMatch
        let today = app.descendants(matching: .any)["column-today"]
        if !today.waitForExistence(timeout: 30) {
            let shows = whatShows(app)
            XCTFail("Today's column didn't appear; " + shows)
            return
        }
        // Let the opening stagger finish before measuring and photographing.
        sleep(3)
        var frames: [CGRect] = []
        for id in ["column-today", "column-today-left", "column-today-right"] {
            let column = app.descendants(matching: .any)[id]
            XCTAssertTrue(column.waitForExistence(timeout: 10), "\(id) isn't showing at 1440×900")
            let f = column.frame, w = window.frame
            XCTAssertGreaterThan(f.width, 300, "\(id) is too narrow: \(f.width)")
            XCTAssertGreaterThanOrEqual(f.minX, w.minX - 1, "\(id) starts left of the window")
            XCTAssertLessThanOrEqual(f.maxX, w.maxX + 1, "\(id) runs past the window's right edge")
            frames.append(f)
        }
        XCTAssertLessThanOrEqual(frames[1].maxX, frames[2].minX + 1, "Today's two columns overlap")
        XCTAssertFalse(app.descendants(matching: .any)["column-needs_you"].exists, "Needs you still has a column beside Today")
        XCTAssertFalse(app.descendants(matching: .any)["column-coming_up"].exists, "Coming up still has a column beside Today")
        keep(window.screenshot(), named: "Today 1440x900")
    }

    @MainActor
    func testTodayAt1000WideIsTwoColumns() {
        // The runner's screen may be shorter than 1100 pt: macOS keeps the window inside it; the width is what counts.
        let app = launch(size: "1000x1100", still: true)
        let window = app.windows.firstMatch
        if !app.descendants(matching: .any)["column-today"].waitForExistence(timeout: 30) {
            let shows = whatShows(app)
            XCTFail("Today's column didn't appear at 1000 pt; " + shows)
            return
        }
        sleep(3)
        XCTAssertTrue(app.descendants(matching: .any)["column-today-left"].waitForExistence(timeout: 10),
                      "Today isn't two columns at 1000 pt")
        XCTAssertTrue(app.descendants(matching: .any)["column-today-right"].exists, "Today's right-hand column is missing at 1000 pt")
        XCTAssertFalse(app.descendants(matching: .any)["column-needs_you"].exists, "Needs you still has a column beside Today")
        keep(window.screenshot(), named: "Today 1000 wide")
    }

    /// What the window holds when a column is missing, on one line for the annotation: the screen and column ids
    /// showing and the first texts, with a screenshot kept beside it (nightly run 2026-11-10).
    @MainActor
    private func whatShows(_ app: XCUIApplication) -> String {
        if app.state == .notRunning { return "Navilife quit" }
        keep(app.screenshot(), named: "no-column-today")
        let ids = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'screen-' OR identifier BEGINSWITH 'column-'"))
            .allElementsBoundByIndex.map(\.identifier)
        let texts = app.windows.firstMatch.staticTexts.allElementsBoundByIndex.prefix(30)
            .map { $0.label.isEmpty ? $0.identifier : $0.label }.filter { !$0.isEmpty }
        return "ids: \(ids); texts: \(texts.joined(separator: " | "))"
    }

    @MainActor
    private func keep(_ shot: XCUIScreenshot, named name: String) {
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
