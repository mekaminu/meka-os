import AppKit
import SwiftUI

@main
struct MekaOSApp: App {
    /// Registers the "Add to MEKA" service (capture from any app's selected text).
    @NSApplicationDelegateAdaptor(MekaAppDelegate.self) private var appDelegate
    @State private var model = CoreModel()
    @AppStorage(MekaAppearance.key) private var appearance = MekaAppearance.dark.rawValue

    var body: some Scene {
        WindowGroup("Meka", id: "today") {
            ShellView()
                .environment(model)
                .frame(minWidth: 760, minHeight: 560)
                .task {
                    await model.start()
                    // Services captures that arrived while the core was starting go in now.
                    let model = model
                    CaptureInbox.shared.attach { text, subject in model.capture(text, subject: subject) }
                }
                .preferredColorScheme((MekaAppearance(rawValue: appearance) ?? .dark).scheme)
                .animation(MekaMotion.themeBlend(reduced: NSWorkspace.shared.accessibilityDisplayShouldReduceMotion), value: appearance)
        }
        .windowToolbarStyle(.unifiedCompact(showsTitle: false))
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("Capture") { model.focusCapture.toggle() }
                    .keyboardShortcut("n", modifiers: .command)
            }
            CommandGroup(after: .toolbar) {
                Picker("Appearance", selection: $appearance) {
                    ForEach(MekaAppearance.allCases) { Text($0.label).tag($0.rawValue) }
                }
            }
            CommandGroup(after: .textEditing) {
                Button("Search Everything…") { model.showSearch = true }
                    .keyboardShortcut("f", modifiers: .command)
            }
            CommandMenu("Go") {
                ForEach(ShellDestination.allCases) { d in
                    Button(d.label) { model.go(to: d, reduced: NSWorkspace.shared.accessibilityDisplayShouldReduceMotion) }
                        .keyboardShortcut(KeyEquivalent(Character("\(d.rawValue + 1)")), modifiers: .command)
                }
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
                .preferredColorScheme((MekaAppearance(rawValue: appearance) ?? .dark).scheme)
        }
        .menuBarExtraStyle(.window)
    }
}

/// The owner's appearance choice. Dark is the default: it is the look MEKA OS is designed around.
enum MekaAppearance: String, CaseIterable, Identifiable {
    case dark, light, system
    static let key = "meka.appearance"
    var id: String { rawValue }
    var label: String { switch self { case .dark: "Dark"; case .light: "Light"; case .system: "Auto" } }
    /// nil follows macOS.
    var scheme: ColorScheme? { switch self { case .dark: .dark; case .light: .light; case .system: nil } }
    var next: MekaAppearance { let all = Self.allCases; return all[(all.firstIndex(of: self)! + 1) % all.count] }
}
