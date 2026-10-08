@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// Appearance → Motion on this Mac (build plan M1, motion pass 2): Expressive · Subtle · Off, kept like the theme.
/// MEKA follows this, not macOS's Reduce Motion; with nothing chosen it plays Expressive (Meka, 2026-10-08), and only
/// an explicit Off keeps it still (`MotionRules` in core decides). Views read the result from the environment
/// (`mekaReduceMotion`, `mekaExpressiveMotion`), set once at each root with `.mekaMotion()`.
enum MotionSetting {
    static let key = "meka.motion"
    /// The three choices in their order (Expressive · Subtle · Off).
    static var all: [MotionChoice] { [.expressive, .subtle, .off] }

    /// The stored choice; nil until Meka chooses on this Mac.
    static func stored(_ id: String) -> MotionChoice? { MotionRules.shared.choice(id: id) }

    static func effective(_ id: String, systemReduce: Bool) -> MotionChoice {
        MotionRules.shared.effective(stored: stored(id), systemOff: systemReduce)
    }

    /// For code outside a view (menus, panels): the choice in effect right now.
    static var current: MotionChoice {
        effective(UserDefaults.standard.string(forKey: key) ?? "",
                  systemReduce: NSWorkspace.shared.accessibilityDisplayShouldReduceMotion)
    }

    /// Cross-fades only (Motion → Off, or Reduce Motion with nothing chosen).
    static var reduced: Bool { current == .off }
}

private struct MekaReduceMotionKey: EnvironmentKey {
    static let defaultValue = false
}

private struct MekaExpressiveMotionKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// MEKA's reduced motion: cross-fades only, no movement. Use this, not `accessibilityReduceMotion`.
    var mekaReduceMotion: Bool {
        get { self[MekaReduceMotionKey.self] }
        set { self[MekaReduceMotionKey.self] = newValue }
    }

    /// Appearance → Motion → Expressive: bigger entrances (rise 28 pt, 60 ms apart, growing from 0.96), bouncier springs.
    var mekaExpressiveMotion: Bool {
        get { self[MekaExpressiveMotionKey.self] }
        set { self[MekaExpressiveMotionKey.self] = newValue }
    }
}

/// Puts the Motion setting into the environment of everything below (a window, the menu-bar card, the floating ticker).
private struct MekaMotionRoot: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var systemReduce
    @AppStorage(MotionSetting.key) private var stored = ""

    func body(content: Content) -> some View {
        let choice = MotionSetting.effective(stored, systemReduce: systemReduce)
        let expressive = choice == .expressive
        content
            .environment(\.mekaReduceMotion, choice == .off)
            .environment(\.mekaExpressiveMotion, expressive)
            .onAppear { MotionStyle.expressive = expressive }
            .onChange(of: expressive) { _, now in MotionStyle.expressive = now }
    }
}

extension View {
    /// MEKA's own Motion setting for this root (Appearance → Motion).
    func mekaMotion() -> some View { modifier(MekaMotionRoot()) }
}

/// Motion pass 2: the one-time card on Today when Reduce Motion is on and nothing is chosen in Appearance → Motion.
/// "Turn on motion" chooses Expressive, "Keep it still" chooses Off; either is a choice, so the card goes for good.
struct MotionSystemCardView: View {
    let palette: MekaPalette
    @AppStorage(MotionSetting.key) private var stored = ""
    @Environment(\.accessibilityReduceMotion) private var systemReduce

    var body: some View {
        if let card = MotionRules.shared.systemCard(stored: MotionSetting.stored(stored), systemOff: systemReduce, mac: true) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(card.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Text(card.line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                HStack(spacing: MekaSpace.xs) {
                    Button(card.turnOn) {
                        MekaHaptics.light()
                        stored = MotionRules.shared.CARD_TURN_ON.id
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(palette.accent)
                    Button(card.keepStill) {
                        MekaHaptics.tick()
                        stored = MotionRules.shared.CARD_KEEP_STILL.id
                    }
                    .buttonStyle(.bordered)
                }
                .padding(.top, MekaSpace.xs)
            }
            .padding(MekaSpace.l)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.l))
            .accessibilityElement(children: .contain)
            .padding(.bottom, MekaSpace.l)
        }
    }
}
