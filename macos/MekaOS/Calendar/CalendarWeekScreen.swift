@preconcurrency import MekaKit
import SwiftUI

/// Calendar → **Week** on the Mac, Option A (approved by Meka 2026-11-10 07:38, square corners; mockup
/// `docs/design/calendar-week/WeekOpen.dc.html`), matching android/.../calendar/CalendarWeekView.kt's open layout and
/// built from the same core grid ([CalendarWeekRules], `MekaCore.calendarWeek`): Calendar's first view, beside Board and
/// Agenda.
///
/// "November 2026" over "Week 46", the Week · Board · Agenda switch, 5 days · 7 days (5 by default, ⌥⌘5 / ⌥⌘7, this Mac
/// remembers it), Today and ‹ › (⌘[ ⌘]) a week at a time; the calendars' key and "Booked this week 5.8 h"; then the
/// grid in one rounded panel: the days' heads (today's date in the gold pill), the all-day strip (all-day events, events
/// outside 08:00–21:00 with their time, the day's open tasks with a teal edge; three then "+2 more"), and the hours
/// 08:00–21:00 at 40 pt, each event a square block on the raised grey with its calendar's colour on a 2 pt left edge, the
/// title regular (11 pt) over its time (10 pt), one under 30 minutes on one line, overlaps side by side, a clash with
/// work as a small amber dot and a thin amber outline; work hours a plain faint band; no commutes; the gold now line.
///
/// Click an event (or an event chip) to open it; click an empty hour to add an event on that day (where an account
/// allows editing); click a task chip to open the task (slice 3). Drag an event that may be moved (its own, no guests,
/// not started: EventMoveRules) and it follows the pointer a quarter hour and a whole day at a time (only onto a day in
/// view), its time line saying where it will land; let go and it moves there with the five-second Undo (core
/// `CalendarWeekRules.drop`, shared with the Fold).
///
/// Motion (catalogue "Calendar"): the head, the key row and the grid stagger in; the days cross-fade as a set when the
/// week changes; the lit switch blends across with a tick; the now dot breathes. Reduce Motion: cross-fades only, a
/// steady dot.
struct CalendarWeekScreen: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @AppStorage("calendar.weekDays") private var storedDays = Int(CalendarWeekRules.shared.DEFAULT_OPEN_DAYS)
    @State private var anchor: Int64?
    @State private var openTask: WeekTaskRef?

    private var rules: CalendarWeekRules { CalendarWeekRules.shared }

    var body: some View {
        // Rebuilt each minute (the now line) and whenever the board or the calendar change.
        TimelineView(.periodic(from: .now, by: 60)) { _ in
            let today = model.todayEpochDay
            let count = Int32(rules.openDays(stored: KotlinInt(value: Int32(clamping: storedDays))))
            let start = rules.start(anchor: anchor ?? today, count: count)
            if let grid = model.calendarWeek(start: start, count: count) {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    head(grid, count: count, start: start, today: today)
                        .staggeredAppear(0)
                    keyRow(grid)
                        .staggeredAppear(1)
                    WeekGridPanel(grid: grid, palette: palette, today: today, onTask: { openTask = WeekTaskRef(id: $0) })
                        .id(grid.startEpochDay * 10 + Int64(count))
                        .transition(.opacity)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                        .staggeredAppear(2)
                }
                .animation(MekaMotion.appear(reduced: reduceMotion), value: grid.startEpochDay * 10 + Int64(count))
                .sheet(item: $openTask) { t in
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        DetailView(task: model.board?.dayOf(taskId: t.id)?.cards.first(where: { $0.id == t.id })?.task, palette: palette)
                            .frame(height: 480)
                        HStack { Spacer(); Button("Done") { openTask = nil }.keyboardShortcut(.cancelAction) }
                    }
                    .padding(MekaSpace.l)
                    .frame(width: 520)
                    .background(palette.surface)
                }
            } else {
                Color.clear
            }
        }
    }

    // MARK: Head

    @ViewBuilder private func head(_ grid: WeekGrid, count: Int32, start: Int64, today: Int64) -> some View {
        HStack(alignment: .center, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: 0) {
                Text(grid.title).font(MekaType.greetingCompact).tracking(MekaType.greetingCompactTracking)
                    .foregroundStyle(palette.textPrimary).lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
                Text(grid.weekLabel).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
            }
            Spacer(minLength: MekaSpace.xs)
            CalendarViewSwitch(palette: palette)
            daysSwitch(count)
            Button {
                MekaHaptics.tick()
                anchor = today
            } label: {
                Text("Today").font(MekaType.caption).foregroundStyle(palette.textPrimary)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(Capsule().fill(palette.surface))
                    .overlay(Capsule().strokeBorder(palette.hairline, lineWidth: 1))
                    .contentShape(Capsule())
            }
            .buttonStyle(MekaPressStyle())
            .help("Back to this week")
            .accessibilityLabel("Back to this week")
            Button { step(start, count, -1) } label: { RoundStep(systemName: "chevron.left", palette: palette) }
                .buttonStyle(.plain)
                .keyboardShortcut("[", modifiers: .command)
                .help("Last week (⌘[)")
                .accessibilityLabel("Last week")
            Button { step(start, count, 1) } label: { RoundStep(systemName: "chevron.right", palette: palette) }
                .buttonStyle(.plain)
                .keyboardShortcut("]", modifiers: .command)
                .help("Next week (⌘])")
                .accessibilityLabel("Next week")
            if !model.editAccounts.isEmpty {
                Button {
                    MekaHaptics.tick()
                    let first = grid.days.first?.epochDay ?? today
                    model.addEventDay = (first...(first + Int64(count) - 1)).contains(today) ? today : first
                } label: { RoundStep(systemName: "plus", palette: palette) }
                    .buttonStyle(.plain)
                    .keyboardShortcut("n", modifiers: [.option, .command])
                    .help("Add an event to your calendar (⌥⌘N)")
                    .accessibilityLabel("Add event")
            }
        }
    }

    private func step(_ start: Int64, _ count: Int32, _ delta: Int32) {
        MekaHaptics.tick()
        anchor = rules.step(start: start, count: count, delta: delta)
    }

    @ViewBuilder private func daysSwitch(_ current: Int32) -> some View {
        HStack(spacing: MekaSpace.xxs) {
            ForEach(rules.OPEN_DAYS.map(\.int32Value), id: \.self) { n in
                let on = n == current
                Button {
                    guard !on else { return }
                    MekaHaptics.tick()
                    storedDays = Int(n)
                } label: {
                    Text(rules.daysLabel(n: n)).font(on ? MekaType.captionStrong : MekaType.caption).lineLimit(1)
                        .foregroundStyle(on ? palette.textPrimary : palette.textSecondary)
                        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                        .background(Capsule().fill(on ? palette.surfaceRaised : Color.clear))
                        .contentShape(Capsule())
                }
                .buttonStyle(MekaPressStyle())
                .keyboardShortcut(KeyEquivalent(Character(String(n))), modifiers: [.command, .option])
                .accessibilityAddTraits(on ? [.isSelected] : [])
                .help("Show \(rules.daysLabel(n: n)) (⌥⌘\(n))")
            }
        }
        .background(Capsule().fill(palette.surface))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: current)
    }

    @ViewBuilder private func keyRow(_ grid: WeekGrid) -> some View {
        HStack(spacing: MekaSpace.xs) {
            if let legend = model.calendar?.legend, !legend.isEmpty {
                CalendarKey(legend: legend, palette: palette)
            }
            Spacer(minLength: MekaSpace.xs)
            Text(CalendarWeekRules.shared.BOOKED).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            Text(grid.booked).font(MekaType.metaStrong).monospacedDigit().foregroundStyle(palette.textPrimary)
                .contentTransition(.numericText())
        }
        .accessibilityElement(children: .combine)
    }
}

/// The grid in one rounded panel: day heads, the all-day strip, then the hours (scrolling, opening at the morning or an
/// hour before now).
/// A task chip's task, opened in a sheet.
private struct WeekTaskRef: Identifiable {
    let id: String
}

private struct WeekGridPanel: View {
    @Environment(CoreModel.self) private var model
    let grid: WeekGrid
    let palette: MekaPalette
    let today: Int64
    let onTask: (String) -> Void

    private var rules: CalendarWeekRules { CalendarWeekRules.shared }
    private var hourPt: Float { rules.HOUR_DP }
    private var labelWidth: CGFloat { CGFloat(rules.LABEL_DP) }

    var body: some View {
        VStack(spacing: 0) {
            heads
            Rectangle().fill(palette.hairline).frame(height: 1)
            strip
            Rectangle().fill(palette.hairline).frame(height: 1)
            hours
        }
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
        .clipShape(RoundedRectangle(cornerRadius: MekaRadius.m))
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).strokeBorder(palette.hairline, lineWidth: 1))
    }

    private var heads: some View {
        HStack(spacing: 0) {
            Color.clear.frame(width: labelWidth, height: 1)
            ForEach(grid.days, id: \.epochDay) { d in
                VStack(spacing: MekaSpace.xxs) {
                    Text(d.weekday).font(MekaType.micro).foregroundStyle(palette.textTertiary)
                    Text(d.number).font(MekaType.metaStrong).monospacedDigit()
                        .foregroundStyle(d.isToday ? palette.onAccent : palette.textPrimary)
                        .frame(minWidth: 30, minHeight: 26)
                        .background(RoundedRectangle(cornerRadius: MekaRadius.s).fill(d.isToday ? palette.accent : Color.clear))
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, MekaSpace.xs)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(d.spoken)
                .accessibilityAddTraits(.isHeader)
            }
        }
    }

    private var strip: some View {
        HStack(alignment: .top, spacing: 0) {
            Text("all day").font(MekaType.micro).foregroundStyle(palette.textTertiary).lineLimit(1)
                .frame(width: labelWidth - MekaSpace.xs, alignment: .trailing)
                .padding(.trailing, MekaSpace.xs)
                .padding(.top, MekaSpace.xs)
                .accessibilityHidden(true)
            ForEach(grid.days, id: \.epochDay) { d in
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    ForEach(rules.shownChips(chips: d.chips), id: \.key) { c in
                        WeekChipView(chip: c, palette: palette, onTask: onTask)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 22, alignment: .topLeading)
                .padding(MekaSpace.xxs)
            }
        }
    }

    private var hours: some View {
        let height = CGFloat(rules.heightDp(hourDp: hourPt))
        return ScrollViewReader { proxy in
            ScrollView(.vertical) {
                HStack(alignment: .top, spacing: 0) {
                    // One row per hour (laid out, not offset, so the scroll can find each hour).
                    VStack(spacing: 0) {
                        ForEach(Array(rules.hours.enumerated()), id: \.offset) { i, h in
                            Text(h).font(MekaType.micro).monospacedDigit().foregroundStyle(palette.textTertiary).lineLimit(1)
                                .padding(.trailing, MekaSpace.xs)
                                .frame(width: labelWidth, height: CGFloat(hourPt), alignment: .topTrailing)
                                .id("hour-\(i)")
                        }
                    }
                    .frame(width: labelWidth, height: height, alignment: .top)
                    ForEach(Array(grid.days.enumerated()), id: \.element.epochDay) { i, d in
                        WeekDayColumn(day: d, hourPt: hourPt, palette: palette,
                                      place: WeekHeldPlace(index: Int32(i), start: grid.startEpochDay, count: Int32(grid.days.count), today: today))
                            .frame(maxWidth: .infinity)
                            .frame(height: height)
                    }
                }
                .padding(.bottom, MekaSpace.xs)
            }
            .onAppear {
                // Opens at the morning (08:00 at the top), or two hours before now when now is further down.
                guard let now = grid.days.compactMap({ $0.nowMinute?.int32Value }).first else { return }
                let hour = Int(now) / 60 - 2 - Int(rules.FIRST_HOUR)
                if hour > 0 { proxy.scrollTo("hour-\(hour)", anchor: .top) }
            }
        }
    }
}

/// One day's column: hour lines (a click on an empty hour adds an event that day), the faint work band, the blocks
/// side by side where they overlap, and today's gold now line.
/// Where a column sits among the days in view, for a dragged block's drop.
private struct WeekHeldPlace: Equatable {
    let index: Int32
    let start: Int64
    let count: Int32
    let today: Int64
}

private struct WeekDayColumn: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let day: WeekGridDay
    let hourPt: Float
    let palette: MekaPalette
    let place: WeekHeldPlace
    @State private var dim = false
    /// The block being dragged and how far (points).
    @State private var heldKey: String?
    @State private var drag: CGSize = .zero

    private var rules: CalendarWeekRules { CalendarWeekRules.shared }

    var body: some View {
        GeometryReader { geo in
            let colW = geo.size.width
            ZStack(alignment: .topLeading) {
                // Each hour's row: a hairline; a click adds an event on this day.
                ForEach(0..<Int(rules.END_HOUR - rules.FIRST_HOUR), id: \.self) { i in
                    Rectangle().fill(palette.hairline.opacity(0.5)).frame(width: colW, height: 1)
                        .frame(width: colW, height: CGFloat(hourPt), alignment: .top)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            guard !model.editAccounts.isEmpty else { return }
                            MekaHaptics.tick()
                            model.addEventDay = day.epochDay
                        }
                        .offset(y: CGFloat(i) * CGFloat(hourPt))
                        .accessibilityHidden(true)
                }
                // Work hours: a plain faint band behind the events.
                if let s = day.workStartMinute?.int32Value, let e = day.workEndMinute?.int32Value {
                    let top = CGFloat(rules.minuteDp(minute: s, hourDp: hourPt))
                    let bottom = CGFloat(rules.minuteDp(minute: e, hourDp: hourPt))
                    Rectangle().fill(palette.textPrimary.opacity(0.035))
                        .frame(width: colW, height: max(0, bottom - top))
                        .offset(y: top)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
                ForEach(day.blocks, id: \.key) { b in
                    let w = (colW - 6) / CGFloat(max(1, b.columns))
                    let held = heldKey == b.key
                    let landing = held ? drop(b, colW) : nil
                    let ox = CGFloat(landing?.days ?? 0) * colW
                    let oy = CGFloat(landing?.minutes ?? 0) * CGFloat(hourPt) / 60
                    WeekBlockView(block: b, palette: palette, hint: landing?.hint)
                        .frame(width: max(0, w - 1), height: CGFloat(b.heightDp(hourDp: hourPt)))
                        .shadow(color: .black.opacity(held ? 0.35 : 0), radius: held ? 6 : 0, y: held ? 2 : 0)
                        .offset(x: 3 + w * CGFloat(b.column) + ox, y: CGFloat(b.topDp(hourDp: hourPt)) + 1 + oy)
                        .zIndex(held ? 1 : 0)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: landing?.startMinute)
                        .gesture(moveGesture(b, colW), including: model.canMoveEvent(b.event) ? .all : .subviews)
                        .help(model.moveEventNote(b.event) ?? b.spoken + " · drag to move it")
                        .transition(.opacity)
                }
                // The gold now line, its dot breathing.
                if let now = day.nowMinute?.int32Value {
                    let y = CGFloat(rules.minuteDp(minute: now, hourDp: hourPt))
                    Rectangle().fill(palette.accent).frame(width: colW, height: 2)
                        .offset(y: y)
                        .allowsHitTesting(false)
                    Circle().fill(palette.accent).frame(width: 10, height: 10)
                        .opacity(reduceMotion ? 1 : (dim ? 0.45 : 1))
                        .offset(x: -5, y: y - 4)
                        .allowsHitTesting(false)
                        .accessibilityLabel("Now")
                        .onAppear {
                            guard !reduceMotion else { return }
                            withAnimation(.easeInOut(duration: 1.4).repeatForever(autoreverses: true)) { dim = true }
                        }
                }
            }
            .frame(width: colW, height: geo.size.height, alignment: .topLeading)
        }
        .overlay(Rectangle().strokeBorder(palette.hairline, lineWidth: 0.5).allowsHitTesting(false))
        .zIndex(heldKey != nil ? 1 : 0)
    }

    /// Where the dragged block would land now (core CalendarWeekRules.drop), or nil where it was.
    private func drop(_ b: WeekBlock, _ colW: CGFloat) -> WeekDrop? {
        CalendarWeekRules.shared.drop(
            block: b, dayIndex: place.index, startEpochDay: place.start, count: place.count,
            dxDp: Float(drag.width), dyDp: Float(drag.height), columnDp: Float(colW), hourDp: hourPt, today: place.today)
    }

    /// Drag an event: it follows a quarter hour and a day at a time with a tick; let go, it moves (only the id and two
    /// numbers cross into the core) with the five-second Undo.
    private func moveGesture(_ b: WeekBlock, _ colW: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 4)
            .onChanged { v in
                let before = heldKey == b.key ? drop(b, colW)?.startMinute : nil
                let beforeDays = heldKey == b.key ? drop(b, colW)?.days : nil
                if heldKey != b.key { heldKey = b.key; MekaHaptics.light() }
                drag = v.translation
                let after = drop(b, colW)
                if after?.startMinute != before || after?.days != beforeDays { MekaHaptics.tick() }
            }
            .onEnded { v in
                drag = v.translation
                let landed = drop(b, colW)
                heldKey = nil
                drag = .zero
                guard let landed else { return }
                let id = b.event.id
                let days = landed.days
                let minutes = landed.minutes
                Task { await model.moveEventBy(id, days: days, minutes: minutes) }
            }
    }
}

/// One event: square corners, its calendar's colour on a 2 pt left edge, the title regular over its time; a clash with
/// work adds a small amber dot after the title and a thin amber outline.
private struct WeekBlockView: View {
    let block: WeekBlock
    let palette: MekaPalette
    /// While dragged: "Move to Thu 12 Nov · 15:30" in the time's place.
    var hint: String?

    var body: some View {
        let amber = palette.calendar3
        HStack(spacing: 0) {
            Rectangle().fill(palette.calendarTone(Int32(block.tone))).frame(width: CGFloat(CalendarWeekRules.shared.EDGE_DP))
            Group {
                if block.oneLine {
                    HStack(spacing: MekaSpace.xxs) {
                        title
                        if block.clash { Circle().fill(amber).frame(width: 6, height: 6) }
                        Spacer(minLength: 0)
                        Text(hint ?? (block.time.components(separatedBy: "–").first ?? block.time))
                            .font(MekaType.micro).monospacedDigit().foregroundStyle(hint != nil ? palette.accent : palette.textTertiary).lineLimit(1)
                    }
                    .frame(maxHeight: .infinity)
                } else {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: MekaSpace.xxs) {
                            title
                            if block.clash { Circle().fill(amber).frame(width: 6, height: 6) }
                        }
                        Text(hint ?? block.time).font(MekaType.micro).monospacedDigit()
                            .foregroundStyle(hint != nil ? palette.accent : palette.textTertiary).lineLimit(1)
                        Spacer(minLength: 0)
                    }
                    .padding(.vertical, MekaSpace.xxs)
                }
            }
            .padding(.horizontal, 7) // rhythm: ok (the mockup's 7 px inset)
            Spacer(minLength: 0)
        }
        .background(Rectangle().fill(palette.surfaceRaised))
        .overlay(Rectangle().strokeBorder(block.clash ? amber.opacity(0.6) : Color.clear, lineWidth: 1))
        .clipped()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(block.spoken)
        .opensEvent(block.event)
    }

    private var title: some View {
        Text(block.title).font(MekaType.caption).foregroundStyle(palette.textPrimary).lineLimit(1).truncationMode(.tail)
    }
}

/// A strip chip: square corners, a 2 pt edge (teal for a task, the calendar's colour for an event); an event chip opens
/// the event.
private struct WeekChipView: View {
    let chip: WeekChip
    let palette: MekaPalette
    let onTask: (String) -> Void

    var body: some View {
        let task = chip.kind == WeekChipKind.task
        let edge = task ? palette.calendar1 : palette.calendarTone(Int32(chip.tone))
        HStack(spacing: 0) {
            Rectangle().fill(edge).frame(width: CGFloat(CalendarWeekRules.shared.EDGE_DP), height: 18)
            Text(chip.text).font(MekaType.caption).foregroundStyle(task ? palette.calendar1 : palette.textPrimary)
                .lineLimit(1).truncationMode(.tail)
                .padding(.horizontal, MekaSpace.xxs)
            Spacer(minLength: 0)
        }
        .background(Rectangle().fill(edge.opacity(0.10)))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(chip.spoken)
        .opensEvent(chip.event)
        .modifier(OpensTask(id: chip.event == nil ? chip.taskId : nil, onTask: onTask))
    }
}

/// A task chip opens its task (slice 3).
private struct OpensTask: ViewModifier {
    let id: String?
    let onTask: (String) -> Void

    @ViewBuilder
    func body(content: Content) -> some View {
        if let id {
            content
                .contentShape(Rectangle())
                .onTapGesture { MekaHaptics.tick(); onTask(id) }
                .accessibilityAddTraits(.isButton)
                .accessibilityHint("Opens the task")
                .help("Open the task")
        } else {
            content
        }
    }
}
