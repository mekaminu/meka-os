@preconcurrency import MekaKit
import SwiftUI

/// The Tomorrow peek on Today (Meka 2026-10-26: "how do I see tomorrow's stuff?"): from 17:00 or once the day is shut
/// down, "Tomorrow · Tue 27 Oct · 2 tasks · first 07:40 Gym ›". Clicking opens the Tomorrow sheet. The line
/// cross-fades as tomorrow changes (only ever a fade, so Reduce Motion is the same).
struct TomorrowPeekView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let peek: TomorrowPeek
    let palette: MekaPalette

    var body: some View {
        Button { model.openTomorrow() } label: {
            HStack(spacing: MekaSpace.xs) {
                Text(LoneWordGlue.shared.kept(line: peek.line)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .lineLimit(2)
                    .id(peek.line)
                    .transition(.opacity)
                Spacer(minLength: MekaSpace.xs)
                Text("›").font(MekaType.itemMeta).foregroundStyle(palette.accent)
            }
            .padding(.horizontal, MekaSpace.l)
            .padding(.vertical, MekaSpace.xs)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
            .contentShape(Rectangle())
            .animation(MekaMotion.appear(reduced: reduceMotion), value: peek.line)
        }
        .buttonStyle(MekaPressStyle())
        .mekaHoverLift()
        .accessibilityLabel(peek.spoken)
    }
}

/// The Tomorrow sheet on the Mac, from the peek or the date under the greeting: tomorrow's work line, train, weather and
/// school over its events and tasks in time order. A task ticks off, or moves to Today (its time kept while still
/// ahead) or the Day after. Synced with the Fold.
/// Motion: sections stagger in; a ticked or moved row leaves at once and the rest glide (replan); the line saying what
/// moved fades in. Reduce Motion: cross-fades.
struct TomorrowSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// Tomorrow on Today, slice 3: the task open for editing (its full detail in place of the list) while it's still
    /// tomorrow's; moved to another day, done or deleted, the list comes back.
    @State private var editingID: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let v = model.shutdown, let task = ShutdownRules.shared.taskOf(p: v.tomorrow, taskId: editingID) {
                Button("‹ Tomorrow") { editingID = nil }
                    .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                    .keyboardShortcut(.cancelAction)
                DetailView(task: task, palette: palette)
                    .frame(height: 480)
                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.push(from: .trailing).combined(with: .opacity))
            } else {
                list
                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.push(from: .leading).combined(with: .opacity))
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: editingID)
        .padding(MekaSpace.l)
        .frame(width: 480)
    }

    @ViewBuilder private var list: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Tomorrow").font(MekaType.upNextTitle).staggeredAppear(0)
            if let v = model.shutdown {
                let p = v.tomorrow
                let rows = model.tomorrowRows(p.rows)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        SectionLabel(p.label, palette).staggeredAppear(1)
                        if let first = v.firstLine {
                            Text(LoneWordGlue.shared.kept(line: first)).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary).staggeredAppear(1)
                        }
                        if let work = p.workLine {
                            Text(LoneWordGlue.shared.kept(line: work)).font(MekaType.caption).foregroundStyle(palette.textTertiary).staggeredAppear(1)
                        }
                        if let train = p.train {
                            Text(LoneWordGlue.shared.kept(line: train.text)).font(MekaType.caption)
                                .foregroundStyle(train.lit ? palette.accent : palette.textSecondary)
                                .accessibilityLabel(train.spoken).staggeredAppear(1)
                        }
                        if let weather = p.weatherLine {
                            Text(LoneWordGlue.shared.kept(line: weather)).font(MekaType.caption).foregroundStyle(palette.textSecondary).staggeredAppear(1)
                        }
                        if let school = p.school {
                            Text(LoneWordGlue.shared.kept(line: school.text)).font(MekaType.caption).foregroundStyle(palette.accent)
                                .accessibilityLabel(school.spoken).staggeredAppear(1)
                        }
                        // Tomorrow on Today, slice 2: bills and renewals due tomorrow, and a last day to cancel (in the accent).
                        if let bills = p.bills {
                            VStack(alignment: .leading, spacing: 0) {
                                if let due = bills.due {
                                    Text(LoneWordGlue.shared.kept(line: due)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                                }
                                if let cancel = bills.cancel {
                                    Text(LoneWordGlue.shared.kept(line: cancel)).font(MekaType.caption).foregroundStyle(palette.accent)
                                }
                            }
                            .accessibilityElement(children: .ignore).accessibilityLabel(bills.spoken).staggeredAppear(1)
                        }
                        // Plan a lesson for a day: "Study · 2 lessons · about 1 h · Pivot tables, Dashboards".
                        if let study = p.study {
                            Text(LoneWordGlue.shared.kept(line: study.text)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                                .accessibilityLabel(study.spoken).staggeredAppear(1)
                        }
                        if let said = model.tomorrowSaid {
                            Text(LoneWordGlue.shared.kept(line: said)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                                .transition(.opacity)
                        }
                        if rows.isEmpty && p.bills == nil && p.study == nil {
                            Text("Nothing planned for tomorrow yet.")
                                .font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                                .transition(.opacity)
                        }
                        ForEach(rows, id: \.id) { r in
                            Group {
                                if let id = ShutdownRules.shared.taskIdOf(r: r) {
                                    TomorrowTaskRow(row: r, taskId: id, palette: palette, edit: { editingID = id })
                                } else {
                                    TomorrowLine(row: r, palette: palette)
                                }
                            }
                            .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .leading)))
                            .staggeredAppear(2)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .animation(MekaMotion.replan(reduced: reduceMotion), value: rows.map(\.id))
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: model.tomorrowSaid)
                }
                .frame(maxHeight: 440)
            } else {
                SkeletonRows(count: 4, palette: palette)
            }
        }
    }
}

/// One of tomorrow's tasks: a tick, its time and title (click to edit), and Today · Day after · Edit.
private struct TomorrowTaskRow: View {
    @Environment(CoreModel.self) private var model
    let row: TomorrowRow
    let taskId: String
    let palette: MekaPalette
    let edit: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: MekaSpace.m) {
            Button { model.complete(taskId) } label: {
                Image(systemName: "circle").font(MekaType.itemTitle).foregroundStyle(palette.textSecondary)
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel("Complete \(row.title)")
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Button(action: edit) {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(row.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        let meta = [row.time, row.detail].compactMap { $0 }.joined(separator: " · ")
                        if !meta.isEmpty {
                            Text(LoneWordGlue.shared.kept(line: meta)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityHint("Edit")
                HStack(spacing: MekaSpace.m) {
                    Button("Today") { model.moveTomorrowTask(taskId, toToday: true) }
                    Button("Day after") { model.moveTomorrowTask(taskId, toToday: false) }
                    Button("Edit", action: edit)
                }
                .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                .padding(.top, MekaSpace.xxs)
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
    }
}
