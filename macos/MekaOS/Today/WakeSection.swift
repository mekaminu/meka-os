@preconcurrency import MekaKit
import SwiftUI

/// The smart wake alarm in the evening shutdown (Alarms, slice 1): the time MEKA suggests from tomorrow's first
/// commitment less the get-ready buffer, ‹ › to move it five minutes, Set alarm / Turn off, "Use 06:30" when something
/// earlier came in, and the buffer as a row with a menu. Synced with the Fold, which rings it; this Mac shows it as a notification.
/// Motion: the digits roll as the time steps (tick haptic), the line cross-fades; Reduce Motion: cross-fades.
struct WakeSection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let wake: WakeView
    let palette: MekaPalette
    /// Shut down's tomorrow block above, which already says the day (A15): only a different day's label shows.
    var under: TomorrowPreview? = nil
    /// The time picked before the alarm is set (nothing is written until Set alarm).
    @State private var draft: Int32?

    private var dayShown: String? {
        guard let under else { return wake.dayLabel }
        return ShutdownRules.shared.wakeDayShown(wakeDayLabel: wake.dayLabel, p: under)
    }

    private var minute: Int32 { wake.setMinute?.int32Value ?? draft ?? wake.minute }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel("Wake alarm", palette)
            if let day = dayShown {
                Text(day).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
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
            Text(LoneWordGlue.shared.kept(line: DurationGlue.shared.kept(line: wake.line)))
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
            if let use = wake.useSuggestionLabel {
                Button(use) { model.useSuggestedWake() }
                    .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                    .transition(.opacity)
            }
            // Tomorrow morning's trains in trouble (the commute counts everywhere, slice 7): only a click moves the alarm.
            if let trains = wake.trains {
                Text(trains.line)
                    .font(MekaType.caption).foregroundStyle(palette.accent)
                    .contentTransition(.opacity)
                    .transition(.opacity)
                if let label = trains.actionLabel {
                    Button(label) { draft = nil; model.wakeEarlierForTrains() }
                        .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                        .transition(.opacity)
                }
            }
            // Walk-through week 5, A17: the get-ready buffer is a row with a menu of the same choices the Fold's sheet
            // has, each with the time it would suggest; a pick moves the suggestion only, never an alarm already set.
            bufferRow
        }
        .animation(MekaMotion.replan(reduced: reduceMotion), value: minute)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: wake.line)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: wake.useSuggestionLabel)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: wake.trains?.line)
    }

    private var bufferRow: some View {
        let row = WakeBufferChoiceRules.shared.row(v: wake)
        let sheet = WakeBufferChoiceRules.shared.sheet(v: wake)
        let lit = sheet.choices.first(where: { $0.lit })?.name ?? ""
        return HStack {
            Text(row.title).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
            Spacer()
            Picker(sheet.title, selection: Binding(get: { lit }, set: { name in
                if let b = WakeBufferChoiceRules.shared.named(v: wake, name: name) { model.setWakeBuffer(b.int32Value) }
            })) {
                ForEach(sheet.choices, id: \.name) { c in Text("\(c.label) · \(c.line)").tag(c.name) }
            }
            .pickerStyle(.menu)
            .labelsHidden()
            .fixedSize()
            .help(LoneWordGlue.shared.kept(line: sheet.note))
            .accessibilityLabel(row.spoken)
        }
        .font(MekaType.itemMeta)
        .padding(.top, MekaSpace.xs)
    }

    private func step(_ by: Int32) {
        let m = AlarmRules.shared.step(minute: minute, steps: by)
        MekaHaptics.tick()
        if wake.isSet { model.setWake(m) } else { draft = m }
    }

    /// "06:45".
    static func clock(_ m: Int32) -> String { String(format: "%02d:%02d", Int(m) / 60, Int(m) % 60) }
}
