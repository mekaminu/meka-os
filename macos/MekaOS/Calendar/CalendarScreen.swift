@preconcurrency import MekaKit
import SwiftUI

/// CALENDAR on the Mac (calendar redesign, slice 2), matching android/.../calendar/CalendarRoute.kt: a week strip
/// (seven day pills with busy dots; ‹ › or ⌘[ ⌘] for other weeks) above the next 30 days grouped by day. All-day
/// events are an "All day" group like Today's (one row each, at most 3 then "+2 more"; Fold review 2026-10-08, item
/// 9), each event carries its calendar's colour dot (a key under the summary names them), fixtures are marked in
/// Barça's colour, planned tasks sit among the events, empty stretches fold into one "Nothing planned" line. Click a
/// day to spring the agenda to it; scrolling keeps the strip on the week in view.
/// Clicking an event opens its detail sheet (slice 3); "Add event" (calendar editing, slice 2b) adds a real event
/// where an account allows editing, and the lines under the summary say how it's going ("Added “Dentist” to Google").
///
/// Motion: the strip pushes across between weeks the way you moved; the lit pill blends across with a tick haptic;
/// sections stagger in; rows glide as the day moves on; the now line's dot breathes. Reduce Motion: cross-fades, jumps
/// happen at once, a steady dot.
struct CalendarScreen: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.mekaReduceMotion) private var reduceMotion
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
        .task { await model.refreshEditAccounts() }
        .sheet(isPresented: Binding(get: { model.addEventDay != nil }, set: { if !$0 { model.addEventDay = nil } })) {
            AddEventSheet(day: model.addEventDay ?? -1, palette: palette)
        }
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
                HStack(alignment: .firstTextBaseline) {
                    Text("Calendar")
                        .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                        .foregroundStyle(palette.textPrimary)
                    Spacer()
                    // Calendar editing (slice 2b): only while an account allows editing; opens on the lit day.
                    if !model.editAccounts.isEmpty {
                        Button {
                            MekaHaptics.tick()
                            model.addEventDay = lit
                        } label: {
                            Label("Add event", systemImage: "plus")
                                .font(MekaType.caption)
                                .foregroundStyle(palette.textPrimary)
                                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                                .background(Capsule().fill(palette.surfaceRaised))
                        }
                        .buttonStyle(MekaPressStyle())
                        .help("Add an event to your calendar")
                    }
                }
                .staggeredAppear(0)
                Text(v.summary).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .staggeredAppear(1)
                EditLinesView(lines: model.editLines, palette: palette)
                    .staggeredAppear(1)
                if !v.legend.isEmpty {
                    CalendarKey(legend: v.legend, palette: palette)
                        .padding(.top, MekaSpace.xxs)
                        .staggeredAppear(1)
                }
            }
            .padding(.bottom, MekaSpace.m)

            strip(v, lit: lit).staggeredAppear(2)

            Rectangle().fill(palette.hairline).frame(height: 1).padding(.top, MekaSpace.xs)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(v.sections.enumerated()), id: \.element.id) { i, s in
                        AgendaSectionView(view: v, section: s, palette: palette)
                            .staggeredAppear(3 + min(i, 8))
                    }
                }
                .scrollTargetLayout()
                .padding(.top, MekaSpace.xs)
                .padding(.bottom, MekaSpace.xl)
                .animation(MekaMotion.replan(reduced: reduceMotion), value: v.sections.map(\.id))
            }
            .scrollPosition(id: $topSection, anchor: .top)
        }
        .padding(.horizontal, MekaSpace.gutterWide)
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
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
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
                        // The agenda springs to the day (the expand spring); Reduce Motion jumps.
                        withAnimation(reduceMotion ? nil : MekaMotion.expand(reduced: false)) { topSection = id }
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
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let day: DayPill
    let lit: Bool
    let palette: MekaPalette
    let tap: () -> Void

    var body: some View {
        Button(action: tap) {
            VStack(spacing: MekaSpace.xxs) {
                Text(day.letter).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                Text(day.number).font(MekaType.itemTitle).monospacedDigit()
                    .foregroundStyle(day.isToday ? palette.accent : (day.inRange ? palette.textPrimary : palette.textTertiary))
                HStack(spacing: 2) { // rhythm: ok (the dot pips under a day number)
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
        .buttonStyle(MekaPressStyle())
        .disabled(day.sectionId == nil)
        .accessibilityLabel(day.accessibilityLabel)
        .accessibilityAddTraits(lit ? .isSelected : [])
    }
}

extension MekaPalette {
    /// A calendar's dot colour for a `CalendarTones` value: fixtures (`CalendarTones.FIXTURE`, 0) in Barça's colour,
    /// the other calendars 1–5.
    func calendarTone(_ tone: Int32) -> Color {
        switch tone {
        case 0: return barca
        case 1: return calendar1
        case 2: return calendar2
        case 3: return calendar3
        case 4: return calendar4
        default: return calendar5
        }
    }
}

/// A calendar's small colour dot.
struct CalendarDot: View {
    let tone: Int32
    let palette: MekaPalette
    var past = false

    var body: some View {
        Circle().fill(palette.calendarTone(tone)).frame(width: 7, height: 7).opacity(past ? 0.5 : 1)
    }
}

/// The key under the summary: each calendar's dot and name ("● Kids  ● Personal  ● Fixtures").
private struct CalendarKey: View {
    let legend: [CalendarTone]
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            ForEach(legend, id: \.key) { c in
                HStack(spacing: MekaSpace.xxs) {
                    CalendarDot(tone: c.tone, palette: palette)
                    Text(c.label).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Calendars: " + legend.map(\.label).joined(separator: ", "))
    }
}

/// The dot's column beside a title, so events, all-day rows and tasks keep one title column.
private let dotColumn: CGFloat = MekaSpace.m

/// One section: "Today" with its date, the "All day" group, then events, planned tasks and (today) the now line.
private struct AgendaSectionView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let view: CalendarView
    let section: AgendaSection
    let palette: MekaPalette
    /// "+2 more" unfolds the rest of the day's all-day rows with the expand spring.
    @State private var allDayOpen = false

    var body: some View {
        let free = section.kind == .free
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                Text(section.title)
                    .font(free ? MekaType.body : MekaType.itemTitle)
                    .foregroundStyle(free ? palette.textTertiary : palette.textPrimary)
                if let sub = section.subtitle {
                    Text(sub).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
            }
            .padding(.top, free ? MekaSpace.xs : MekaSpace.l)
            .padding(.bottom, MekaSpace.xs)

            if !section.allDayItems.isEmpty {
                // The "All day" group as Today shows it: the label once, one row each, "+2 more" unfolds the rest.
                AllDayLabel(label: section.allDayLabel, palette: palette)
                    .padding(.leading, TimelineMetrics.timeColumn + MekaSpace.xs)
                ForEach(AllDayRules.shared.shown(items: section.allDayItems, open: allDayOpen), id: \.event.id) { item in
                    AgendaAllDayRow(item: item, tone: view.toneOf(event: item.event), palette: palette)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
                if let more = AllDayRules.shared.moreLabel(items: section.allDayItems, open: allDayOpen) {
                    AllDayMore(label: more, open: Binding(
                        get: { allDayOpen },
                        set: { v in withAnimation(MekaMotion.expand(reduced: reduceMotion)) { allDayOpen = v } }
                    ), palette: palette)
                    .padding(.leading, MekaSpace.xs)
                }
            }
            // Work hours (Fold review 2026-10-08): "Work 09:00–17:30" as the quiet band Today uses, full width, and
            // "Work · Now · until 17:30" with the bar lit while at work (Meka's 10:48 screenshots, 2026-10-09).
            if let work = section.workTitle {
                WorkBand(title: work, detail: section.workDetail, running: section.workRunning, palette: palette)
                    .padding(.leading, TimelineMetrics.timeColumn + MekaSpace.xs)
                    .padding(.bottom, MekaSpace.xxs)
            }
            ForEach(section.ended, id: \.id) { r in AgendaEventRow(row: r, past: true, tone: tone(r), palette: palette) }
            ForEach(section.rows, id: \.id) { r in
                switch r.kind {
                case .event: AgendaEventRow(row: r, past: false, tone: tone(r), palette: palette).eventActions(r.event, palette: palette)
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

    private func tone(_ r: TimelineRow) -> Int32? { r.event.map { view.toneOf(event: $0) } }
}

/// One all-day entry in the agenda, as Today's "All day" row (the title in the event weight in the title column, its
/// calendar under it only when calendars are mixed), with its calendar's dot. Clicking opens the detail; the agenda
/// only shows, so "Make it a task" and the right-click menu stay on Today.
private struct AgendaAllDayRow: View {
    let item: AllDayItem
    let tone: Int32
    let palette: MekaPalette

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            Spacer().frame(width: TimelineMetrics.timeColumn)
            CalendarDot(tone: tone, palette: palette).frame(width: dotColumn, alignment: .leading)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(item.event.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                if let line = item.line {
                    Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
        .contentShape(Rectangle())
        .opensEvent(item.event)
    }
}

/// An event: context, so the regular body weight, after its calendar's colour dot (dimmed once it has ended).
/// Fixtures are marked in the accent colour.
private struct AgendaEventRow: View {
    let row: TimelineRow
    let past: Bool
    let tone: Int32?
    let palette: MekaPalette

    var body: some View {
        let fixture = row.event?.isFixture ?? false
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            Text(row.time).font(MekaType.itemMeta).monospacedDigit()
                .foregroundStyle(past ? palette.textTertiary : palette.textSecondary)
                .frame(width: TimelineMetrics.timeColumn, alignment: .leading)
            Group {
                if let tone { CalendarDot(tone: tone, palette: palette, past: past) } else { Color.clear.frame(width: 7, height: 7) }
            }
            .frame(width: dotColumn, alignment: .leading)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
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
            Spacer().frame(width: dotColumn)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                if let d = row.detail { Text(d).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
    }
}
