import AppKit

/// The window size a launch asks for (Mac presentation pass: the UI-test target's 1440×900 and 1000×1100 checks).
/// `-MekaWindowSize 1440x900` on the command line lands in UserDefaults' argument domain for that launch only, so
/// nothing is stored and an ordinary launch never sees it. Too small (under the window's 760×560 minimum) or not
/// "<width>x<height>": ignored.
enum LaunchWindowSize {
    static let key = "MekaWindowSize"

    static func parse(_ text: String?) -> CGSize? {
        guard let text else { return nil }
        let parts = text.lowercased().split(separator: "x").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        guard parts.count == 2, parts[0] >= 760, parts[1] >= 560, parts[0] <= 10_000, parts[1] <= 10_000 else { return nil }
        return CGSize(width: parts[0], height: parts[1])
    }

    static func requested(_ defaults: UserDefaults = .standard) -> CGSize? { parse(defaults.string(forKey: key)) }

    /// Sizes the app's main window once it is on screen (macOS keeps it inside the screen's visible frame).
    @MainActor
    static func apply() {
        guard let size = requested() else { return }
        Task { @MainActor in
            for window in NSApp.windows where window.isVisible && window.canBecomeMain {
                window.setContentSize(size)
                window.center()
                // Wider than the screen (ci's runner screen is narrower than 1440 pt): keep the left edge, and with it
                // the sidebar, on screen; the rest runs off the right.
                if let visible = window.screen?.visibleFrame, window.frame.minX < visible.minX {
                    window.setFrameOrigin(NSPoint(x: visible.minX, y: window.frame.minY))
                }
            }
        }
    }
}
