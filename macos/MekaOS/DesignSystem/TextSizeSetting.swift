@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// View → Text Size on this Mac (Fix first, "Mac text is too big", Meka 2026-10-15): Smaller · Default · Larger ·
/// Largest, also ⌘− · ⌘0 · ⌘+ and Settings → Appearance. One multiplier on every MekaType token (the Mac's own type
/// scale, body 13), kept on this Mac like the theme. `TextSizeRules` in core decides the steps; `MekaTextScale` carries
/// the multiplier to every view, which redraws when it changes. The Fold keeps its own sizes.
enum TextSizeSetting {
    static let key = "meka.textSize"

    /// The stored id ("" until Meka chooses: Default).
    static var stored: String { UserDefaults.standard.string(forKey: key) ?? "" }

    static var current: TextSizeChoice { TextSizeRules.shared.choice(id: stored) }

    static var all: [TextSizeChoice] { TextSizeRules.shared.all }

    /// Chooses a size: stored, and every view redraws at it (with the theme's blend unless motion is off).
    static func choose(_ id: String) {
        UserDefaults.standard.set(id, forKey: key)
        apply(id, animated: true)
    }

    static func bigger() { choose(TextSizeRules.shared.bigger(id: stored).id) }
    static func smaller() { choose(TextSizeRules.shared.smaller(id: stored).id) }
    static func reset() { choose(TextSizeRules.shared.choice(id: nil).id) }

    /// Puts the stored size into effect (at launch, and whenever it's chosen).
    static func apply(_ id: String? = nil, animated: Bool = false) {
        let factor = CGFloat(TextSizeRules.shared.multiplier(id: id ?? stored))
        guard MekaTextScale.shared.factor != factor else { return }
        if animated {
            withAnimation(MekaMotion.themeBlend(reduced: MotionSetting.reduced)) { MekaTextScale.shared.factor = factor }
        } else {
            MekaTextScale.shared.factor = factor
        }
    }

    /// ⌘= (the + key without Shift) also makes the text bigger, as in Safari and Mail; the menu's ⌘+ needs Shift on
    /// most keyboards. Installed once at launch; only while Navilife is the active app.
    private static var monitor: Any?
    static func installKeyMonitor() {
        guard monitor == nil else { return }
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            let mods = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
            guard mods == .command, event.charactersIgnoringModifiers == "=" else { return event }
            MainActor.assumeIsolated { bigger() } // AppKit calls local monitors on the main thread
            return nil
        }
    }
}

/// View → Text Size: the four sizes, then Bigger / Smaller / Default Size with their shortcuts.
struct TextSizeCommands: View {
    @AppStorage(TextSizeSetting.key) private var stored = ""

    var body: some View {
        Menu("Text Size") {
            Picker("Text Size", selection: Binding(get: { TextSizeRules.shared.choice(id: stored).id },
                                                   set: { TextSizeSetting.choose($0) })) {
                ForEach(TextSizeSetting.all, id: \.id) { c in Text(c.label).tag(c.id) }
            }
            .pickerStyle(.inline)
            .labelsHidden()
            Divider()
            Button("Bigger") { TextSizeSetting.bigger() }
                .keyboardShortcut("+", modifiers: .command)
                .disabled(!TextSizeRules.shared.canGrow(id: stored))
            Button("Smaller") { TextSizeSetting.smaller() }
                .keyboardShortcut("-", modifiers: .command)
                .disabled(!TextSizeRules.shared.canShrink(id: stored))
            Button("Default Size") { TextSizeSetting.reset() }
                .keyboardShortcut("0", modifiers: .command)
        }
    }
}

/// Settings → Appearance → Text size: the four sizes in a segmented control and the line saying how to change it.
struct TextSizeRow: View {
    let palette: MekaPalette
    @AppStorage(TextSizeSetting.key) private var stored = ""
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text("Text size").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Text(TextSizeRules.shared.line(id: stored))
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: stored)
            }
            Spacer()
            Picker("Text size", selection: Binding(get: { TextSizeRules.shared.choice(id: stored).id },
                                                   set: { TextSizeSetting.choose($0) })) {
                ForEach(TextSizeSetting.all, id: \.id) { c in Text(c.label).tag(c.id) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .fixedSize()
        }
        .accessibilityElement(children: .contain)
    }
}
