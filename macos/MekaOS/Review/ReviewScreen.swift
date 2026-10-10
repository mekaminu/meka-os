@preconcurrency import MekaKit
import SwiftUI

/// REVIEW on the Mac (build plan M1, ADR-013): the weekly review, synced with the Fold. One Monday–Sunday week looked
/// back on (numbers, done, habits, goals, fasts, lists, still open), the week ahead and the north-star numbers.
/// ‹ › (⌘[ and ⌘]) step between weeks. "Done reviewing" syncs; nothing else here changes anything.
///
/// Motion: numbers count up, tiles and sections stagger in, goal bars fill on appear; stepping weeks pushes the week
/// across the way you moved; Done reviewing pops a check (spring). Reduce Motion: cross-fades, no counting.
struct ReviewScreen: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.mekaReduceMotion) private var reduceMotion
    private var palette: MekaPalette { scheme == .dark ? .dark : .light }
    @State private var direction = 0

    var body: some View {
        ScrollView {
            if let v = model.review {
                week(v)
                    .id(v.weekStart)
                    .transition(transition)
            }
        }
        .animation(MekaMotion.replan(reduced: reduceMotion), value: model.review?.weekStart)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(palette.background)
    }

    private var transition: AnyTransition {
        if reduceMotion || direction == 0 { return .opacity }
        return .push(from: direction > 0 ? .trailing : .leading).combined(with: .opacity)
    }

    private func step(_ v: WeeklyReviewView, _ delta: Int) {
        direction = delta
        model.showReviewWeek(Int(v.offset) + delta)
    }

    @ViewBuilder
    private func week(_ v: WeeklyReviewView) -> some View {
        LazyVStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Review")
                .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                .foregroundStyle(palette.textPrimary)
                .staggeredAppear(0)
            HStack {
                Button { step(v, -1) } label: { Image(systemName: "chevron.left") }
                    .disabled(!v.canGoBack)
                    .keyboardShortcut("[", modifiers: .command)
                    .help("Previous week")
                VStack(spacing: 2) {
                    Text(v.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                    Text(v.rangeLabel).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
                .frame(maxWidth: .infinity)
                Button { step(v, 1) } label: { Image(systemName: "chevron.right") }
                    .disabled(!v.canGoForward)
                    .keyboardShortcut("]", modifiers: .command)
                    .help("Next week")
            }
            .buttonStyle(.borderless)
            .padding(.bottom, MekaSpace.m)
            .staggeredAppear(0)

            HStack(spacing: MekaSpace.s) {
                ForEach(Array(v.tiles.enumerated()), id: \.offset) { i, t in
                    TileView(tile: t, palette: palette).staggeredAppear(1 + i)
                }
            }
            if let compared = v.comparedLine {
                Text(compared).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).staggeredAppear(3)
            }

            section("Done", 4)
            if v.done.isEmpty {
                note(v.isCurrent ? "Nothing ticked off yet this week." : "Nothing was ticked off that week.", 4)
            }
            ForEach(v.done, id: \.id) { d in line(d.title, d.dayLabel, 4) }
            if v.doneMore > 0 { note("and \(v.doneMore) more", 4) }

            if !v.habits.isEmpty {
                section("Habits", 5, v.habitsLine)
                ForEach(v.habits, id: \.id) { h in HabitWeekRow(habit: h, palette: palette).staggeredAppear(5) }
            }

            if !v.goals.isEmpty {
                section("Goals", 6)
                ForEach(v.goals, id: \.id) { g in GoalWeekRow(goal: g, palette: palette).staggeredAppear(6) }
            }

            let more = [v.fastingLine].compactMap { $0 } + v.listsLines
            if !more.isEmpty {
                section("Fasting and lists", 7)
                ForEach(more, id: \.self) { l in
                    Text(l).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).padding(.vertical, 2).staggeredAppear(7)
                }
            }

            if !v.stillOpen.isEmpty {
                section("Still open", 8)
                ForEach(v.stillOpen, id: \.id) { o in line(o.title, o.detail, 8, lit: true) }
                if v.stillOpenMore > 0 { note("and \(v.stillOpenMore) more", 8) }
            }

            if let ahead = v.aheadTitle {
                section(ahead, 9)
                ForEach(v.aheadLines, id: \.self) { l in
                    Text(l).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).padding(.vertical, 2).staggeredAppear(9)
                }
            }

            section("North star", 10)
            ForEach(v.northStar, id: \.key) { m in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(m.label).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Spacer()
                        Group {
                            if let n = m.value.flatMap({ Int($0) }) { CountUpText(n) } // e.g. Interruptions
                            else { Text(m.value ?? "—") }
                        }
                        .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    }
                    Text(m.line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                .padding(.vertical, 2)
                .accessibilityElement(children: .combine)
                .staggeredAppear(10)
            }

            VStack(spacing: MekaSpace.s) {
                if v.reviewed {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 34))
                        .foregroundStyle(palette.accent)
                        .transition(reduceMotion ? .opacity : .scale(scale: 0.6).combined(with: .opacity))
                    if let l = v.reviewedLine { Text(l).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary) }
                } else {
                    Button("Done reviewing") { model.reviewDone() }
                        .buttonStyle(.borderedProminent)
                        .tint(palette.accent)
                        .controlSize(.large)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, MekaSpace.xl)
            .animation(reduceMotion ? MekaMotion.replan(reduced: true) : .spring(response: 0.35, dampingFraction: 0.55), value: v.reviewed)
            .staggeredAppear(11)
        }
        .frame(maxWidth: 720, alignment: .leading)
        .padding(.horizontal, MekaSpace.gutterWide)
        .padding(.vertical, MekaSpace.xl)
    }

    private func section(_ label: String, _ index: Int, _ sub: String? = nil) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            SectionLabel(label, palette)
            if let sub { Text(sub).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).padding(.bottom, MekaSpace.xs) }
        }
        .padding(.top, MekaSpace.l)
        .staggeredAppear(index)
    }

    private func note(_ text: String, _ index: Int) -> some View {
        Text(text).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).padding(.vertical, 2).staggeredAppear(index)
    }

    private func line(_ title: String, _ detail: String, _ index: Int, lit: Bool = false) -> some View {
        HStack {
            Text(title).font(MekaType.body).foregroundStyle(palette.textPrimary)
            Spacer()
            Text(detail).font(MekaType.caption).foregroundStyle(lit ? palette.accent : palette.textTertiary)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .staggeredAppear(index)
    }
}

/// A number at the top of the review; it counts up on appear.
private struct TileView: View {
    let tile: ReviewTile
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(tile.label).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            CountUpText(Int(tile.value))
                .font(MekaType.greeting)
                .foregroundStyle(palette.textPrimary)
            if let d = tile.detail { Text(d).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(MekaSpace.m)
        .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.surfaceRaised))
        .accessibilityElement(children: .combine)
    }
}

/// A habit: name, "5 of 7 · met", and the week's days as dots.
private struct HabitWeekRow: View {
    let habit: ReviewHabit
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack {
                Text(habit.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Spacer()
                Text(habit.line).font(MekaType.itemMeta).foregroundStyle(habit.met ? palette.accent : palette.textSecondary)
            }
            HStack(spacing: 4) {
                ForEach(Array(habit.days.enumerated()), id: \.offset) { _, on in
                    Circle().fill(on.boolValue ? palette.accent : palette.surfaceRaised).frame(width: 8, height: 8)
                }
            }
            .accessibilityHidden(true)
        }
        .padding(.vertical, MekaSpace.xxs)
        .accessibilityElement(children: .combine)
    }
}

/// A goal with a bar that fills on appear.
private struct GoalWeekRow: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let goal: ReviewGoal
    let palette: MekaPalette
    @State private var shown: Double = 0

    private var fraction: Double { min(1, max(0, Double(goal.progressPct) / 100)) }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(goal.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.surfaceRaised)
                    Capsule().fill(palette.accent).frame(width: geo.size.width * shown)
                }
            }
            .frame(height: 6)
            .padding(.vertical, MekaSpace.xxs)
            Text(goal.line).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
        }
        .padding(.vertical, MekaSpace.xxs)
        .accessibilityElement(children: .combine)
        .onAppear {
            if reduceMotion { shown = fraction } else { withAnimation(MekaMotion.replan(reduced: false)) { shown = fraction } }
        }
        .onChange(of: goal.progressPct) { withAnimation(MekaMotion.replan(reduced: reduceMotion)) { shown = fraction } }
    }
}

/// The weekly review's card in Today: from 18:00 on Sunday through Monday until that week is reviewed on either
/// device. It rises in like the other cards; tapping it opens Review on that week.
struct ReviewCardView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let card: ReviewCard
    let palette: MekaPalette

    var body: some View {
        Button { model.openReviewCard(reduced: reduceMotion) } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(card.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Text(card.line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(MekaSpace.l)
            .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.surfaceRaised))
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .mekaHoverLift()
    }
}
