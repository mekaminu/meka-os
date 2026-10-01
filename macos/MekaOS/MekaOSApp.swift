import SwiftUI

@main
struct MekaOSApp: App {
    @State private var model = CoreModel()

    var body: some Scene {
        WindowGroup("Meka", id: "today") {
            TodayView()
                .environment(model)
                .frame(minWidth: 520, minHeight: 560)
                .task { await model.start() }
        }
        .windowToolbarStyle(.unifiedCompact(showsTitle: false))
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("Capture") { model.focusCapture.toggle() }
                    .keyboardShortcut("n", modifiers: .command)
            }
            CommandMenu("Today") {
                Button("Complete Selected") { model.completeSelected() }
                    .keyboardShortcut(.return, modifiers: .command)
                    .disabled(model.selectedID == nil)
                Button("Delete Selected") { model.deleteSelected() }
                    .keyboardShortcut(.delete, modifiers: .command)
                    .disabled(model.selectedID == nil)
                Divider()
                Button("Sync Now") { Task { await model.syncNow() } }
                    .keyboardShortcut("r", modifiers: .command)
            }
        }

        // Menu-bar quick capture (brief §45).
        MenuBarExtra("Meka", systemImage: "circle.dotted") {
            QuickCaptureMenu().environment(model)
        }
        .menuBarExtraStyle(.window)
    }
}
