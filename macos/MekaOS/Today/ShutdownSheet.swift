@preconcurrency import MekaKit
import SwiftUI

/// Evening shutdown on the Mac (build plan M1): what got done, what's left from today (tick it, carry it to tomorrow,
/// skip a repeating one, or send a one-off to Someday) and tomorrow at a glance, then Shut down. Synced with the Fold.
/// Motion: sections stagger in; ticks complete like a task; carried rows leave and land in Tomorrow; Shut down pops a
/// check and the sheet goes. Reduce Motion: cross-fades.
struct ShutdownSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var closing = false

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            Text("Shut down the day").font(MekaType.upNextTitle).staggeredAppear(0)
            if let v = model.shutdown {
                doneCount(v).staggeredAppear(0)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        SectionLabel("Left from today", palette).staggeredAppear(1)
                        if v.left.isEmpty {
                            Text("Nothing left from today.")
                                .font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                                .transition(.opacity)
                        }
                        ForEach(v.left, id: \.task.id) { item in
                            LeftRow(item: item, palette: palette)
                                .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .bottom)))
                                .staggeredAppear(1)
                        }
                        if v.left.count > 1 {
                            Button("Move the rest to tomorrow") { model.carryAllToTomorrow() }
                                .buttonStyle(.plain).font(MekaType.itemTitle).foregroundStyle(palette.accent)
                                .padding(.vertical, MekaSpace.xs)
                                .staggeredAppear(1)
                        }

                        SectionLabel(v.tomorrow.label, palette).padding(.top, MekaSpace.l).staggeredAppear(2)
                        if let work = v.tomorrow.workLine {
                            Text(work).font(MekaType.caption).foregroundStyle(palette.textTertiary).staggeredAppear(2)
                        }
                        Text(v.tomorrow.summary)
                            .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            .contentTransition(.opacity)
                            .staggeredAppear(2)
                        ForEach(v.tomorrow.rows, id: \.id) { r in
                            TomorrowLine(row: r, palette: palette)
                                .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .top)))
                                .staggeredAppear(2)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .animation(MekaMotion.replan(reduced: reduceMotion), value: v.left.map { $0.task.id })
                    .animation(MekaMotion.replan(reduced: reduceMotion), value: v.tomorrow.rows.map(\.id))
                }
                .frame(maxHeight: 440)

                if closing || v.doneToday {
                    HStack(spacing: MekaSpace.s) {
                        Image(systemName: "checkmark.circle.fill").font(.system(size: 22)).foregroundStyle(palette.accent)
                        Text(v.doneLine ?? "Day shut down").font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    }
                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.scale(scale: 0.6).combined(with: .opacity))
                }
            } else {
                SkeletonRows(count: 4, palette: palette)
            }

            HStack {
                Spacer()
                if model.shutdown?.doneToday == true || closing {
                    Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
                } else {
                    Button("Close") { dismiss() }.keyboardShortcut(.cancelAction)
                    Button("Shut down") { shutDown() }.keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
    }

    @ViewBuilder
    private func doneCount(_ v: ShutdownView) -> some View {
        if v.doneCount > 0 {
            CountUpText(Int(v.doneCount)) { "\($0) done today" }
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
        } else {
            Text(v.doneCountLine).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
        }
    }

    /// The check pops (a spring) with a light haptic, holds a moment, then the sheet goes.
    private func shutDown() {
        withAnimation(reduceMotion ? MekaMotion.appear(reduced: true) : .spring(response: 0.35, dampingFraction: 0.6)) { closing = true }
        MekaHaptics.light()
        Task { @MainActor in
            await model.shutDown()
            try? await Task.sleep(for: .milliseconds(700))
            dismiss()
        }
    }
}

/// One thing left from today: tick it, or carry it over.
private struct LeftRow: View {
    @Environment(CoreModel.self) private var model
    let item: ShutdownItem
    let palette: MekaPalette

    var body: some View {
        HStack(alignment: .top, spacing: MekaSpace.m) {
            CompleteButton(task: item.task, palette: palette)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.task.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                if let line = item.line {
                    Text(line).font(MekaType.itemMeta).foregroundStyle(item.overdue ? palette.critical : palette.textSecondary)
                }
                HStack(spacing: MekaSpace.m) {
                    Button("Tomorrow") { model.carryOver(item.task.id) }
                    if item.canSkip { Button("Skip") { model.skip(item.task.id) } }
                    if item.canSomeday { Button("Someday") { model.moveToSomeday(item.task.id) } }
                }
                .buttonStyle(.plain).font(MekaType.caption).foregroundStyle(palette.accent)
                .padding(.top, 2)
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.s)
        .padding(.horizontal, MekaSpace.xs)
    }
}

/// One line of tomorrow: time on the left (empty for a task with no time), title and detail on the right.
struct TomorrowLine: View {
    let row: TomorrowRow
    let palette: MekaPalette

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.m) {
            Text(row.time ?? "").font(MekaType.itemMeta).monospacedDigit()
                .foregroundStyle(palette.textSecondary)
                .frame(width: 96, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).font(MekaType.itemTitle).foregroundStyle(row.isEvent ? palette.textPrimary : palette.textSecondary)
                if let d = row.detail {
                    Text(d).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
    }
}

/// The evening card in Today: "Shut down the day" with what's left and tomorrow in one line.
struct ShutdownCard: View {
    @Environment(CoreModel.self) private var model
    let shutdown: ShutdownView
    let palette: MekaPalette

    var body: some View {
        Button { model.showShutdown = true } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text("Shut down the day").font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Text(shutdown.cardLine).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(MekaSpace.l)
            .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.surfaceRaised))
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
    }
}

/// Tomorrow at a glance in Today once the evening starts and the shutdown card isn't showing: the quiet
/// "Day shut down at 18:42" line (if done) over "Tomorrow: first thing 09:00 Standup · 3 events". The glance
/// cross-fades as tomorrow changes (only ever a fade, so reduced motion is the same). Clicking opens the shutdown sheet.
struct TomorrowGlanceView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let doneLine: String?
    let glance: String?
    let palette: MekaPalette

    var body: some View {
        Button { model.showShutdown = true } label: {
            VStack(alignment: .leading, spacing: 2) {
                if let doneLine {
                    Text(doneLine).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                if let glance {
                    Text(glance).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                        .lineLimit(2)
                        .id(glance)
                        .transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .animation(MekaMotion.appear(reduced: reduceMotion), value: glance)
        }
        .buttonStyle(.plain)
    }
}
