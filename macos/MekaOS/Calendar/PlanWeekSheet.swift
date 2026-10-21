@preconcurrency import MekaKit
import SwiftUI

/// Plan your week on the Mac (Week board item (6); core `PlanWeekRules`): from the Board's "Plan your week", a sheet in
/// four steps — Left from last week, Fixed this week, Goals, Suggested spread. Next and Back push the step the way he
/// moved (Reduce Motion: cross-fade) and the step dots blend to the accent. On the last step each task has its day chips
/// with Navilife's pick lit, and Leave. Nothing moves until Accept plan (light haptic): the sheet closes and the undo bar
/// says "Week planned · …". The plan is read once from the core, changed on the main actor, and handed to the core once.
struct PlanWeekSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var plan: WeekPlan?
    @State private var step = 0
    @State private var forward = true

    private var steps: [String] { PlanWeekRules.shared.STEPS }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(plan?.title ?? PlanWeekRules.shared.OPEN).font(MekaType.upNextTitle).staggeredAppear(0)
            if let p = plan {
                Text(p.range).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).staggeredAppear(0)
                dots.padding(.top, MekaSpace.xs).staggeredAppear(1)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        switch step {
                        case 0: left(p)
                        case 1: fixed(p)
                        case 2: goals(p)
                        default: spread(p)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .id(step)
                    .transition(stepTransition)
                }
                .frame(minHeight: 280)
                .animation(MekaMotion.replan(reduced: reduceMotion), value: step)
            } else {
                SkeletonRows(count: 4, palette: palette)
            }

            HStack {
                Button(step == 0 ? PlanWeekRules.shared.NOT_NOW : PlanWeekRules.shared.BACK) {
                    MekaHaptics.tick()
                    if step == 0 { dismiss() } else { forward = false; step -= 1 }
                }
                .keyboardShortcut(.cancelAction)
                Spacer()
                if step < steps.count - 1 {
                    Button(PlanWeekRules.shared.NEXT) { MekaHaptics.tick(); forward = true; step += 1 }
                        .keyboardShortcut(.defaultAction)
                        .disabled(plan == nil)
                } else {
                    Button(PlanWeekRules.shared.ACCEPT) { accept() }
                        .keyboardShortcut(.defaultAction)
                        .disabled(plan?.canAccept != true)
                }
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 520)
        .task { plan = await model.planWeek() }
    }

    private var stepTransition: AnyTransition {
        if reduceMotion { return .opacity }
        return .asymmetric(
            insertion: .opacity.combined(with: .move(edge: forward ? .trailing : .leading)),
            removal: .opacity.combined(with: .move(edge: forward ? .leading : .trailing))
        )
    }

    private var dots: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(spacing: MekaSpace.xxs) {
                ForEach(0..<steps.count, id: \.self) { i in
                    Capsule().fill(i <= step ? palette.accent : palette.hairline).frame(width: MekaSpace.m, height: 4)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: step)
                }
            }
            Text("\(step + 1) of \(steps.count) · \(steps[step])".uppercased())
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Step \(step + 1) of \(steps.count), \(steps[step])")
    }

    @ViewBuilder
    private func left(_ p: WeekPlan) -> some View {
        Text(p.leftLine).font(MekaType.body).foregroundStyle(palette.textPrimary)
        ForEach(p.rows, id: \.taskId) { r in
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(r.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(r.why).font(MekaType.itemMeta)
                    .foregroundStyle(r.why.hasPrefix("Carried") ? palette.accent : palette.textSecondary)
            }
            .padding(.top, MekaSpace.xs)
            .accessibilityElement(children: .combine)
        }
    }

    @ViewBuilder
    private func fixed(_ p: WeekPlan) -> some View {
        ForEach(p.days, id: \.day) { d in
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(d.label).font(MekaType.captionStrong).foregroundStyle(palette.textPrimary)
                    if let place = d.placeLabel {
                        Text(place).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    Spacer()
                    if let free = d.freeLine {
                        Text(free).font(MekaType.caption).foregroundStyle(d.stacking ? palette.calendar3 : palette.textSecondary)
                    }
                }
                if d.fixed.isEmpty {
                    Text(PlanWeekRules.shared.NOTHING_FIXED).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                } else {
                    ForEach(Array(d.fixed.enumerated()), id: \.offset) { _, line in
                        Text(line).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    }
                }
            }
            .padding(.bottom, MekaSpace.xs)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(d.spoken)
        }
    }

    @ViewBuilder
    private func goals(_ p: WeekPlan) -> some View {
        if p.goals.isEmpty {
            Text(PlanWeekRules.shared.NO_GOALS).font(MekaType.body).foregroundStyle(palette.textSecondary)
        }
        ForEach(p.goals, id: \.goalId) { g in
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text("◎ \(g.title)").font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(g.line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            }
            .padding(.bottom, MekaSpace.xs)
            .accessibilityElement(children: .combine)
        }
    }

    @ViewBuilder
    private func spread(_ p: WeekPlan) -> some View {
        Text(p.spreadLine).font(MekaType.body).foregroundStyle(palette.textPrimary)
        ForEach(p.rows, id: \.taskId) { r in
            let chosen = r.chosen?.int64Value
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(r.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(PlanWeekRules.shared.rowLine(plan: p, row: r)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: chosen)
                HStack(spacing: MekaSpace.xs) {
                    ForEach(r.days.map(\.int64Value), id: \.self) { day in
                        let label = p.dayLabel(d: day)
                        MailNeedsPill(
                            label: chosen == day ? "✓ \(label)" : label, palette: palette, quiet: chosen != day,
                            spoken: "\(r.title) on \(label)", action: { choose(r.taskId, day) }
                        )
                    }
                    MailNeedsPill(
                        label: PlanWeekRules.shared.LEAVE, palette: palette, quiet: chosen != nil,
                        spoken: "Leave \(r.title) where it is", action: { choose(r.taskId, -1) }
                    )
                }
            }
            .padding(.top, MekaSpace.m)
        }
    }

    private func choose(_ taskId: String, _ day: Int64) {
        guard let p = plan else { return }
        MekaHaptics.tick()
        plan = p.chooseDay(taskId: taskId, day: day)
    }

    private func accept() {
        guard let p = plan, p.canAccept else { return }
        plan = nil
        dismiss()
        Task { await model.acceptWeekPlan(p) }
    }
}
