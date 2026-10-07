@preconcurrency import MekaKit
import SwiftUI

/// What MEKA did and why (V1 activity log): every reminder and digest that reached you, and later every change MEKA
/// makes for you, newest first by day, each with the rule or setting behind it. A change can be undone here (only what
/// is still as MEKA left it). Synced with the Fold. Opened from Today's header or File → Activity… (⇧⌘A).
/// Motion: the sheet scale-fades; day sections stagger in; Undo gives a light haptic and the row's line cross-fades
/// to "Undone at 09:12". Reduce Motion: cross-fades.
struct ActivitySheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text("Activity").font(MekaType.upNextTitle).staggeredAppear(0)
            if let view = model.activity, !view.isEmpty {
                Text("What MEKA did and why. \(view.weekLine).")
                    .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .staggeredAppear(0)
                if let note = model.activityNote {
                    Text(note).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).transition(.opacity)
                }
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        ForEach(Array(view.days.enumerated()), id: \.element.day) { i, day in
                            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                                Text(day.label.uppercased())
                                    .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                                    .foregroundStyle(palette.textTertiary)
                                ForEach(day.rows, id: \.id) { row in rowView(row) }
                            }
                            .staggeredAppear(i + 1)
                        }
                    }
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
            .padding(.top, MekaSpace.s)
        }
        .padding(MekaSpace.l)
        .frame(width: 500)
        .background(palette.surface)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.activityNote)
    }

    private func rowView(_ row: ActivityRow) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.s) {
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
                            .buttonStyle(.plain).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                    }
                }
                .transition(.opacity)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.s)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: row.undoneLine)
    }
}
