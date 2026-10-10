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

    var body: some View {
        let m = NeedsYouMeanwhileRules.shared.build(goals: model.goals, lists: model.lists)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if !m.habits.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    label(NeedsYouMeanwhileRules.shared.HABITS_LABEL, m.habitsLine)
                    ForEach(m.habits, id: \.id) { h in habitRow(h) }
                }
                .staggeredAppear(firstIndex)
            }
            if !m.waiting.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    label(NeedsYouMeanwhileRules.shared.WAITING_LABEL, nil)
                    ForEach(m.waiting, id: \.id) { l in lineRow(l) }
                    if let more = m.waitingMore { moreRow(more) }
                }
                .staggeredAppear(firstIndex + (m.habits.isEmpty ? 0 : 1))
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

    private func lineRow(_ l: MeanwhileLine) -> some View {
        Button { model.go(to: .lists, reduced: reduceMotion) } label: {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(l.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(2)
                Text(l.meta).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
            }
            .padding(.vertical, MekaSpace.xxs)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityHint("Opens Lists")
    }

    private func moreRow(_ text: String) -> some View {
        Button { model.go(to: .lists, reduced: reduceMotion) } label: {
            Text(text).font(MekaType.caption).foregroundStyle(palette.accent)
        }
        .buttonStyle(MekaPressStyle())
    }
}
