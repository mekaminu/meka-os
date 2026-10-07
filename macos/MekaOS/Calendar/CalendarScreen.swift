@preconcurrency import MekaKit
import SwiftUI

/// CALENDAR on the Mac (calendar redesign, slice 2), matching android/.../calendar/CalendarRoute.kt: a week strip
/// (seven day pills with busy dots; ‹ › or ⌘[ ⌘] for other weeks) above the next 30 days grouped by day. All-day
/// events are chips, fixtures are marked in the accent colour, planned tasks sit among the events, empty stretches
/// fold into one "Nothing planned" line. Click a day to jump to it; scrolling keeps the strip on the week in view.
/// Shows only: nothing here changes anything; clicking an event opens its detail sheet (slice 3).
///
/// Motion: the strip pushes across between weeks the way you moved; the lit pill blends across with a tick haptic;
/// sections stagger in; rows glide as the day moves on; the now line's dot breathes. Reduce Motion: cross-fades, jumps
/// happen at once, a steady dot.
struct CalendarScreen: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private var palette: MekaPalette { scheme == .dark ? .dark : .light }
    /// The week strip on screen, and which way it last moved (for the push).
    @State private var week = 0
    @State private var direction = 0
    /// The section at the top of the agenda (two-way: clicking a day scrolls to it).
    @State private var topSection: String?
    /// A clicked day inside a free stretch stays lit while that stretch is at the top.
    @State private var clicked: Int64?

    var body: some View {
        Group {
            if let v = model.calendar, !v.sections.isEmpty {
                content(v)
            } else {
                Color.clear
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(palette.background)
    }

    @ViewBuilder
    private func content(_ v: CalendarView) -> some View {
        let top = v.sections.first { $0.id == topSection } ?? v.sections[0]
        let lit: Int64 = {
            if let c = clicked, c >= top.firstDay, c <= top.lastDay { return c }
            return top.firstDay
        }()
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text("Calendar")
                    .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                    .foregroundStyle(palette.textPrimary)
                    .staggeredAppear(0)
                Text(v.summary).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .staggeredAppear(1)
            }
            .padding(.bottom, MekaSpace.m)

            strip(v, lit: lit).staggeredAppear(2)

            Rectangle().fill(palette.hairline).frame(height: 1).padding(.top, MekaSpace.s)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(v.sections.enumerated()), id: \.element.id) { i, s in
                        AgendaSectionView(section: s, palette: palette)
                            .staggeredAppear(3 + min(i, 8))
                    }
                }
                .scrollTargetLayout()
                .padding(.top, MekaSpace.s)
                .padding(.bottom, MekaSpace.xl)
                .animation(MekaMotion.replan(reduced: reduceMotion), value: v.sections.map(\.id))
            }
            .scrollPosition(id: $topSection, anchor: .top)
        }
        .padding(.horizontal, MekaSpace.gutter)
        .padding(.top, MekaSpace.xl)
        .onChange(of: topSection) { _, _ in
            // Scrolling the agenda keeps the strip on the week in view.
            let section = v.sections.first { $0.id == topSection } ?? v.sections[0]
            let w = Int(v.weekIndexOf(epochDay: section.firstDay))
            if w != week { move(to: w) }
        }
    }

    private func move(to w: Int) {
        direction = w > week ? 1 : -1
        withAnimation(MekaMotion.replan(reduced: reduceMotion)) { week = w }
    }

    @ViewBuilder
    private func strip(_ v: CalendarView, lit: Int64) -> some View {
        let w = v.weeks[min(week, v.weeks.count - 1)]
        VStack(spacing: MekaSpace.xs) {
            HStack {
                Button { MekaHaptics.tick(); move(to: week - 1) } label: { Image(systemName: "chevron.left") }
                    .disabled(week == 0)
                    .keyboardShortcut("[", modifiers: .command)
                    .help("Previous week")
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.s) {
                    Text(w.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                    if w.title != w.range {
                        Text(w.range).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                }
                .frame(maxWidth: .infinity)
                .id("title-\(w.startEpochDay)")
                .transition(.opacity)
                Button { MekaHaptics.tick(); move(to: week + 1) } label: { Image(systemName: "chevron.right") }
                    .disabled(week >= v.weeks.count - 1)
                    .keyboardShortcut("]", modifiers: .command)
                    .help("Next week")
            }
            .buttonStyle(.borderless)

            HStack(spacing: MekaSpace.xxs) {
                ForEach(w.days, id: \.epochDay) { d in
                    DayPillView(day: d, lit: d.epochDay == lit, palette: palette) {
                        guard let id = d.sectionId else { return }
                        MekaHaptics.tick()
                        clicked = d.epochDay
                        withAnimation(reduceMotion ? nil : MekaMotion.replan(reduced: false)) { topSection = id }
                    }
                }
            }
            .id(w.startEpochDay)
            .transition(stripTransition)
        }
        .clipped()
    }

    private var stripTransition: AnyTransition {
        if reduceMotion || direction == 0 { return .opacity }
        return .push(from: direction > 0 ? .trailing : .leading).combined(with: .opacity)
    }
}

/// A day pill: the letter, the date (accent for today), up to three busy dots. Lit with a soft fill.
private struct DayPillView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let day: DayPill
    let lit: Bool
    let palette: MekaPalette
    let tap: () -> Void

    var body: some View {
        Button(action: tap) {
            VStack(spacing: 2) {
                Text(day.letter).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                Text(day.number).font(MekaType.itemTitle).monospacedDigit()
                    .foregroundStyle(day.isToday ? palette.accent : (day.inRange ? palette.textPrimary : palette.textTertiary))
                HStack(spacing: 2) {
                    ForEach(0..<Int(day.dots), id: \.self) { _ in
                        Circle().fill(day.isToday ? palette.accent : palette.textSecondary).frame(width: 4, height: 4)
                    }
                }
                .frame(height: 6)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, MekaSpace.xs)
            .background(Capsule().fill(lit ? palette.surfaceRaised : Color.clear))
            .contentShape(Capsule())
            .opacity(day.inRange ? 1 : 0.5)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
        }
        .buttonStyle(.plain)
        .disabled(day.sectionId == nil)
        .accessibilityLabel(day.accessibilityLabel)
        .accessibilityAddTraits(lit ? .isSelected : [])
    }
}

/// One section: "Today" with its date, all-day chips, then events, planned tasks and (today) the now line.
private struct AgendaSectionView: View {
    @Environment(CoreModel.self) private var model
    let section: AgendaSection
    let palette: MekaPalette

    var body: some View {
        let free = section.kind == .free
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.s) {
                Text(section.title)
                    .font(free ? MekaType.body : MekaType.itemTitle)
                    .foregroundStyle(free ? palette.textTertiary : palette.textPrimary)
                if let sub = section.subtitle {
                    Text(sub).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
            }
            .padding(.top, free ? MekaSpace.s : MekaSpace.l)
            .padding(.bottom, free ? MekaSpace.s : MekaSpace.xs)

            if !section.allDay.isEmpty {
                AllDayChips(events: section.allDay, palette: palette)
                    .padding(.leading, TimelineMetrics.timeColumn + MekaSpace.xs)
            }
            ForEach(section.ended, id: \.id) { r in AgendaEventRow(row: r, past: true, palette: palette) }
            ForEach(section.rows, id: \.id) { r in
                switch r.kind {
                case .event: AgendaEventRow(row: r, past: false, palette: palette).eventActions(r.event, palette: palette)
                case .task: AgendaTaskRow(row: r, palette: palette)
                case .now: NowLine(row: r, palette: palette)
                default: EmptyView() // the agenda has no gaps; Today shows free time
                }
            }
            if let empty = section.emptyLine {
                Text(empty).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.leading, TimelineMetrics.timeColumn + MekaSpace.xs)
                    .padding(.bottom, MekaSpace.xs)
            }
            // Hidden from my day (calendar actions): listed quietly at the day's foot with Show.
            if let label = section.hiddenLabel {
                Text(label).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.leading, TimelineMetrics.timeColumn + MekaSpace.xs)
                    .padding(.top, MekaSpace.xs)
                ForEach(section.hidden, id: \.id) { e in
                    HStack(spacing: MekaSpace.m) {
                        Text(e.title).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .opensEvent(e)
                        Spacer()
                        Button("Show") { model.showEvent(e.id) }
                            .buttonStyle(.borderless)
                            .foregroundStyle(palette.accent)
                    }
                    .padding(.leading, TimelineMetrics.timeColumn + MekaSpace.xs)
                    .padding(.trailing, MekaSpace.xs)
                    .transition(.opacity)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// An event: context, so the regular body weight. Fixtures are marked in the accent colour.
private struct AgendaEventRow: View {
    let row: TimelineRow
    let past: Bool
    let palette: MekaPalette

    var body: some View {
        let fixture = row.event?.isFixture ?? false
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            Text(row.time).font(MekaType.itemMeta).monospacedDigit()
                .foregroundStyle(past ? palette.textTertiary : palette.textSecondary)
                .frame(width: TimelineMetrics.timeColumn, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).font(MekaType.body).foregroundStyle(past ? palette.textTertiary : palette.textPrimary)
                let line = [row.running ? "Now" : nil, row.detail].compactMap { $0 }.joined(separator: " · ")
                if !line.isEmpty {
                    Text(line).font(MekaType.caption)
                        .foregroundStyle(!past && (row.running || fixture) ? palette.accent : palette.textTertiary)
                }
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
        .opensEvent(row.event)
    }
}

/// A planned task: something to act on, so the item title weight. Shown only; it's ticked in Today.
private struct AgendaTaskRow: View {
    let row: TimelineRow
    let palette: MekaPalette

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            Text(row.time).font(MekaType.itemMeta).monospacedDigit().foregroundStyle(palette.textSecondary)
                .frame(width: TimelineMetrics.timeColumn, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                if let d = row.detail { Text(d).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
    }
}
