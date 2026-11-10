import AppKit
import XCTest
@testable import MekaOS

/// The Day list, slice 2: the Dock icon's menu carries Add Tasks…, which opens today's Day list (as ⌘N); a click before
/// the core has started waits and opens it once the model is attached, and only once.
final class MacDockMenuTests: XCTestCase {
    private final class Target: NSObject { @objc func chosen() {} }

    @MainActor
    func testTheDockMenuOffersAddTasks() {
        let target = Target()
        let menu = DockMenu.menu(target: target, action: #selector(Target.chosen))
        XCTAssertEqual(menu.items.map(\.title), ["Add tasks…"])
    }

    @MainActor
    func testAClickBeforeTheCoreStartsOpensTheDayListOnceItIsReady() {
        let dock = DockMenu()
        dock.addTasks()
        XCTAssertTrue(dock.isWaiting)
        var opened = 0
        dock.attach { opened += 1 }
        XCTAssertEqual(opened, 1)
        XCTAssertFalse(dock.isWaiting)
        dock.addTasks()
        XCTAssertEqual(opened, 2)
    }
}
