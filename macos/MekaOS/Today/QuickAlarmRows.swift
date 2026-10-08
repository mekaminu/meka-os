@preconcurrency import MekaKit
import SwiftUI

/// Quick alarms and timers typed into capture (Alarms, slice 2): one slim row each under Up next while they're still to
/// ring ("Pasta" · "20 min · ends 14:52 · 18 min left") with a ✕ that cancels it on every device (tick haptic). Rows
/// come and go with the expand spring (Motion Off: a cross-fade). Nothing shows when none are set.
struct QuickAlarmRowsView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        let items = model.quickAlarms
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                ForEach(items, id: \.id) { item in
                    QuickAlarmRowView(item: item, palette: palette)
                        .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .top)))
                }
            }
            .animation(MekaMotion.expand(reduced: reduceMotion), value: items.map(\.id))
        }
    }
}

private struct QuickAlarmRowView: View {
    @Environment(CoreModel.self) private var model
    let item: QuickAlarmItem
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: MekaSpace.s) {
            // A soft brass dot for a timer, a full one for an alarm: quiet, like the calendar's dots.
            Circle()
                .fill(item.kind == .timer ? palette.accent.opacity(0.55) : palette.accent)
                .frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                Text(item.detail).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(1)
                    .contentTransition(.opacity)
            }
            Spacer(minLength: MekaSpace.s)
            Button { model.cancelAlarm(item.id) } label: {
                Image(systemName: "xmark").font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .padding(MekaSpace.xs)
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(item.cancelLabel)
            .help(item.cancelLabel)
        }
        .padding(.vertical, MekaSpace.xxs)
    }
}
