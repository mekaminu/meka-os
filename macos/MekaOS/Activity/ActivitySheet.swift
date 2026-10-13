@preconcurrency import MekaKit
import SwiftUI

/// What MEKA did and why (V1 activity log): every reminder and digest that reached you, and later every change MEKA
/// makes for you, newest first by day, each with the rule or setting behind it. A change can be undone here (only what
/// is still as MEKA left it). Synced with the Fold. Opened from Today's header or File → Activity… (⇧⌘A).
/// Motion: the sheet scale-fades; day sections stagger in; Undo gives a light haptic and the row's line cross-fades
/// to "Undone at 09:12"; the filter (All · Reminders · Done for you · Calendar · Calls · Other, only the kinds there are)
/// is a segmented control (tick haptic) and the days cross-fade; MEKA's AI today ("Talk 6 · Ask 2 · 3¢") and MEKA's voice lines (how quickly it answered on this Mac lately, the month's characters) fade in once known. Reduce Motion:
/// cross-fades.
struct ActivitySheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// The filter (slice 3), by name: this Mac's choice while the sheet is open, never synced.
    @State private var filterName = ActivityRules.shared.filterNamed(name: nil).name

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text("Activity").font(MekaType.upNextTitle).staggeredAppear(0)
            if let line = model.aiTodayLine {
                Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            if let line = model.voiceTimingLine {
                Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            if let line = model.voiceUsageLine {
                Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            if let view = model.activity, !view.isEmpty {
                Text("What Navilife did and why. \(view.weekLine).")
                    .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .staggeredAppear(0)
                if let note = model.activityNote {
                    Text(note).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).transition(.opacity)
                }
                let chips = ActivityRules.shared.filters(view: view)
                let filter = ActivityRules.shared.chosen(view: view, chosen: ActivityRules.shared.filterNamed(name: filterName))
                if !chips.isEmpty {
                    Picker("Show", selection: Binding(
                        get: { filter.name },
                        set: { name in
                            guard name != filter.name else { return }
                            MekaHaptics.tick()
                            withAnimation(MekaMotion.appear(reduced: reduceMotion)) { filterName = name }
                        }
                    )) {
                        ForEach(chips, id: \.filter.name) { c in
                            Text(c.label).tag(c.filter.name).accessibilityLabel(c.spoken)
                        }
                    }
                    .pickerStyle(.segmented).labelsHidden()
                    .staggeredAppear(1)
                }
                let shown = ActivityRules.shared.filtered(view: view, filter: filter)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        ForEach(Array(shown.days.enumerated()), id: \.element.day) { i, day in
                            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                                Text(day.label.uppercased())
                                    .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                                    .foregroundStyle(palette.textTertiary)
                                ForEach(day.rows, id: \.id) { row in rowView(row) }
                            }
                            .staggeredAppear(i + 2)
                        }
                    }
                    .id(filter.name)
                    .transition(.opacity)
                }
                .frame(maxHeight: 460)
            } else {
                Text(model.activity?.emptyLine ?? ActivityRules.shared.EMPTY_LINE)
                    .font(MekaType.body).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .staggeredAppear(1)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 500)
        .background(palette.surface)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.activityNote)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.voiceUsageLine)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.voiceTimingLine)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.aiTodayLine)
        .task { await model.refreshVoiceUsage() }
    }

    private func rowView(_ row: ActivityRow) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
            Text(row.time).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).monospacedDigit()
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                // Context, not something to act on: the regular body weight.
                Text(row.summary).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                if let detail = row.detail {
                    Text(detail).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                }
                Text(row.why).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                Group {
                    if let undone = row.undoneLine {
                        Text(undone).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    } else if row.canUndo {
                        Button("Undo") { model.undoActivity(row.id) }
                            .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                    }
                }
                .transition(.opacity)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: row.undoneLine)
    }
}
