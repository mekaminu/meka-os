import SwiftUI
@preconcurrency import MekaKit

/// Under "Nothing needs you" (Needs you's page and the wide window's column): today's habits ticked inline (the tick
/// ring draws its check, light haptic), Waiting on and the next renewals (a click opens Lists), from the core's
/// `NeedsYouMeanwhileRules` (Fold reviews 2026-10-09, 00:10 item 6 and 07:26 item 10). Sections stagger in after the
/// empty line; rows glide as they change. Reduced motion: cross-fades.
struct NeedsYouMeanwhileView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// The stagger index of the first section (the empty line takes the one before).
    var firstIndex: Int = 2
    /// Under a stack of cards only Coming up shows (core `NeedsYouMeanwhileRules.underStack`).
    var underStack: Bool = false

    var body: some View {
        let all = NeedsYouMeanwhileRules.shared.build(goals: model.goals, lists: model.lists, comingUp: model.today?.comingUp ?? [])
        let m = underStack ? NeedsYouMeanwhileRules.shared.underStack(m: all) : all
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if !m.habits.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    label(NeedsYouMeanwhileRules.shared.HABITS_LABEL, m.habitsLine)
                    ForEach(m.habits, id: \.id) { h in habitRow(h) }
                }
                .staggeredAppear(firstIndex)
            }
            if !m.comingUp.isEmpty {
                // Coming up (Fold review 2026-10-12 17:56, item 3): the next two reminders; a click opens the task.
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    label(NeedsYouMeanwhileRules.shared.COMING_LABEL, nil)
                    ForEach(m.comingUp, id: \.id) { l in lineRow(l, task: true) }
                }
                .staggeredAppear(firstIndex + (m.habits.isEmpty ? 0 : 1))
            }
            if !m.waiting.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    label(NeedsYouMeanwhileRules.shared.WAITING_LABEL, nil)
                    ForEach(m.waiting, id: \.id) { l in lineRow(l) }
                    if let more = m.waitingMore { moreRow(more) }
                }
                .staggeredAppear(firstIndex + (m.habits.isEmpty ? 0 : 1) + (m.comingUp.isEmpty ? 0 : 1))
            }
            if !m.renewals.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    label(NeedsYouMeanwhileRules.shared.RENEWALS_LABEL, nil)
                    ForEach(m.renewals, id: \.id) { l in lineRow(l) }
                    if let more = m.renewalsMore { moreRow(more) }
                }
                .staggeredAppear(firstIndex + m.sections.count - 1)
            }
        }
        .animation(MekaMotion.replan(reduced: reduceMotion), value: m.sections.count)
    }

    private func label(_ text: String, _ line: String?) -> some View {
        HStack {
            SectionLabel(text, palette)
            Spacer()
            if let line {
                Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary).contentTransition(.opacity)
            }
        }
        .padding(.top, MekaSpace.l)
    }

    private func habitRow(_ h: HabitItem) -> some View {
        Button { model.setHabitDone(h.id, !h.doneToday) } label: {
            HStack(spacing: MekaSpace.m) {
                TickRingView(done: h.doneToday, palette: palette).frame(width: 22, height: 22)
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(h.title).font(MekaType.itemTitle)
                        .foregroundStyle(h.doneToday ? palette.textSecondary : palette.textPrimary).lineLimit(1)
                    if !h.meta.isEmpty {
                        Text(h.meta).font(MekaType.caption)
                            .foregroundStyle(h.pace == .behind ? palette.accent : palette.textTertiary).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, MekaSpace.xxs)
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(NeedsYouMeanwhileRules.shared.tickLabel(h: h))
    }

    private func lineRow(_ l: MeanwhileLine, task: Bool = false) -> some View {
        Button {
            if task {
                model.go(to: .today, reduced: reduceMotion)
                model.select(l.id, reduced: reduceMotion)
            } else {
                model.go(to: .lists, reduced: reduceMotion)
            }
        } label: {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(l.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(2)
                Text(l.meta).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
            }
            .padding(.vertical, MekaSpace.xxs)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityHint(task ? "Opens the task" : "Opens Lists")
    }

    private func moreRow(_ text: String) -> some View {
        Button { model.go(to: .lists, reduced: reduceMotion) } label: {
            Text(text).font(MekaType.caption).foregroundStyle(palette.accent)
        }
        .buttonStyle(MekaPressStyle())
    }
}

/// Approve must land: "Working on it", what Navilife is doing with his mail since his Approve ("Marking read · Personal
/// Gmail" over "500 so far · …"), from the core's `MailWorkingRules` on the Needs you stack. Context in the body
/// weight; a click opens Mail. Staggers in after the stack or the empty line; rows glide as their counts change.
struct MailWorkingView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 2

    var body: some View {
        let lines = model.needsYouStack?.working ?? []
        if !lines.isEmpty {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                SectionLabel(MailWorkingRules.shared.LABEL, palette).padding(.top, MekaSpace.l)
                ForEach(lines, id: \.id) { l in
                    Button { model.showMail = true } label: {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text(l.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(2)
                            Text(l.meta).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                                .contentTransition(.opacity)
                        }
                        .padding(.vertical, MekaSpace.xxs)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityHint(MailWorkingRules.shared.OPEN_LABEL)
                }
            }
            .staggeredAppear(firstIndex)
            .animation(MekaMotion.replan(reduced: reduceMotion), value: lines.map(\.meta))
        }
    }
}
