@preconcurrency import MekaKit
import SwiftUI

/// The smart wake alarm in the evening shutdown (Alarms, slice 1): the time MEKA suggests from tomorrow's first
/// commitment less the get-ready buffer, ‹ › to move it five minutes, Set alarm / Turn off, "Use 06:30" when something
/// earlier came in, and the buffer as a menu. Synced with the Fold, which rings it; this Mac shows it as a notification.
/// Motion: the digits roll as the time steps (tick haptic), the line cross-fades; Reduce Motion: cross-fades.
struct WakeSection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let wake: WakeView
    let palette: MekaPalette
    /// The time picked before the alarm is set (nothing is written until Set alarm).
    @State private var draft: Int32?

    private var minute: Int32 { wake.setMinute?.int32Value ?? draft ?? wake.minute }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel("Wake alarm", palette)
            Text(wake.dayLabel).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            HStack(spacing: MekaSpace.xs) {
                Button { step(-1) } label: { Image(systemName: "chevron.left") }
                    .buttonStyle(MekaPressStyle()).foregroundStyle(palette.textSecondary)
                    .accessibilityLabel("Five minutes earlier")
                Text(Self.clock(minute))
                    .font(MekaType.upNextTitle).monospacedDigit()
                    .foregroundStyle(wake.isSet ? palette.accent : palette.textPrimary)
                    .contentTransition(reduceMotion ? ContentTransition.opacity : ContentTransition.numericText())
                Button { step(1) } label: { Image(systemName: "chevron.right") }
                    .buttonStyle(MekaPressStyle()).foregroundStyle(palette.textSecondary)
                    .accessibilityLabel("Five minutes later")
                Spacer()
                if wake.isSet {
                    Button("Turn off") { draft = nil; model.wakeOff() }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                } else {
                    Button("Set alarm") { model.setWake(minute) }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                }
            }
            Text(wake.line)
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
            if let use = wake.useSuggestionLabel {
                Button(use) { model.useSuggestedWake() }
                    .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                    .transition(.opacity)
            }
            Menu(wake.bufferLine) {
                ForEach(wake.bufferChoices.map { $0.int32Value }, id: \.self) { m in
                    Button(AlarmRules.shared.bufferLine(min: m)) { model.setWakeBuffer(m) }
                }
            }
            .menuStyle(.button).fixedSize()
            .font(MekaType.caption)
        }
        .animation(MekaMotion.replan(reduced: reduceMotion), value: minute)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: wake.line)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: wake.useSuggestionLabel)
    }

    private func step(_ by: Int32) {
        let m = AlarmRules.shared.step(minute: minute, steps: by)
        MekaHaptics.tick()
        if wake.isSet { model.setWake(m) } else { draft = m }
    }

    /// "06:45".
    static func clock(_ m: Int32) -> String { String(format: "%02d:%02d", Int(m) / 60, Int(m) % 60) }
}
