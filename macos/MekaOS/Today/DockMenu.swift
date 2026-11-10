import AppKit
@preconcurrency import MekaKit

/// The Day list, slice 2 (`docs/design/day-list/Today.dc.html`: "hold the app icon → Add tasks"), on the Mac: the Dock
/// icon's menu carries **Add Tasks…**, which brings Navilife forward with today's Day list open and its field ready, as
/// ⌘N does. A click that comes before the core has started waits here until the model is attached.
@MainActor
final class DockMenu {
    static let shared = DockMenu()

    init() {}

    /// The Dock menu's title, from the core so the Fold's icon menu and the Mac's say the same.
    static var addTasksTitle: String { DayListRules.shared.SHORTCUT + "…" }

    private var waiting = false
    private var sink: (() -> Void)?

    /// Add Tasks… was chosen in the Dock menu.
    func addTasks() {
        if let sink { sink() } else { waiting = true }
    }

    /// Called once the core is running; a click that came earlier opens the Day list now.
    func attach(_ sink: @escaping () -> Void) {
        self.sink = sink
        if waiting { waiting = false; sink() }
    }

    var isWaiting: Bool { waiting }

    /// The menu macOS shows on a right-click (or a long press) of Navilife's Dock icon.
    static func menu(target: AnyObject, action: Selector) -> NSMenu {
        let menu = NSMenu()
        let item = NSMenuItem(title: Self.addTasksTitle, action: action, keyEquivalent: "")
        item.target = target
        menu.addItem(item)
        return menu
    }
}
