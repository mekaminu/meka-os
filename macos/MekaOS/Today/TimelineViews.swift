@preconcurrency import MekaKit
import SwiftUI

/// Today's timeline (calendar redesign, slice 1), matching android/.../today/TimelineViews.kt: events and planned tasks
/// in one list with a now line and free gaps. Events are context, so their titles use the regular body weight.
/// Motion: rows glide as the day moves on; the now line's dot breathes; "3 earlier" unfolds in place.
/// Reduce Motion: cross-fades only and a steady dot.
enum TimelineMetrics {
    static let timeColumn: CGFloat = 96
}

/// Clicking an event (a row, an all-day chip, Up next's event line) opens its detail sheet (calendar redesign, slice 3).
struct OpensEvent: ViewModifier {
    @Environment(CoreModel.self) private var model
    let event: CalendarEvent?

    @ViewBuilder
    func body(content: Content) -> some View {
        if let e = event {
            content
                .contentShape(Rectangle())
                .onTapGesture { model.openEvent = e }
                .accessibilityAddTraits(.isButton)
                .accessibilityHint("Shows the event's details")
        } else {
            content
        }
    }
}

extension View {
    func opensEvent(_ event: CalendarEvent?) -> some View { modifier(OpensEvent(event: event)) }
}

/// Calendar actions on an event row (matching the Fold's swipes): Prep task and Hide from my day, in the row's
/// context menu and as small buttons while the pointer is over it (the row lifts 2 pt). MEKA-only: the real calendar is
/// untouched. Reduce Motion: no lift, the buttons fade.
struct EventActionsModifier: ViewModifier {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.openURL) private var openURL
    let event: CalendarEvent?
    let palette: MekaPalette
    @State private var hovering = false

    @ViewBuilder
    func body(content: Content) -> some View {
        if let e = event {
            content
                .overlay(alignment: .trailing) {
                    if hovering {
                        HStack(spacing: MekaSpace.xs) {
                            Button("Prep task") { model.addPrepTask(e) }
                            Button("Hide") { model.hideEvent(e.id) }
                                .help("Hide from my day (MEKA only; your calendar is unchanged)")
                        }
                        .buttonStyle(.borderless)
                        .font(MekaType.caption)
                        .foregroundStyle(palette.accent)
                        .padding(.horizontal, MekaSpace.s)
                        .padding(.vertical, MekaSpace.xxs)
                        .background(palette.surfaceRaised, in: Capsule())
                        .padding(.trailing, MekaSpace.xs)
                        .transition(.opacity)
                    }
                }
                .offset(y: hovering && !reduceMotion ? -2 : 0)
                .onHover { h in
                    withAnimation(MekaMotion.appear(reduced: reduceMotion)) { hovering = h }
                }
                .contextMenu {
                    reminderMenus(e)
                    Button("Prep task") { model.addPrepTask(e) }
                    Button("Hide from my day") { model.hideEvent(e.id) }
                    Divider()
                    Button("Details…") { model.openEvent = e }
                    // The real event in Google Calendar / Outlook on the web, to change it there (MEKA stays read-only).
                    if let link = EventDetails.shared.openLink(e: e), let url = URL(string: link.url), url.scheme == "https" {
                        Button(link.label) { openURL(url) }
                    }
                }
                .accessibilityAction(named: "Prep task") { model.addPrepTask(e) }
                .accessibilityAction(named: "Hide from my day") { model.hideEvent(e.id) }
        } else {
            content
        }
    }

    /// Remind me and Leave by (when the event has a place): only times still ahead; the one set is ticked, with Off.
    @ViewBuilder
    private func reminderMenus(_ e: CalendarEvent) -> some View {
        let remind = model.eventReminder(e.id)
        let travel = model.eventTravel(e.id)
        let remindChoices = model.remindChoices(e)
        let travelChoices = model.travelChoices(e)
        if !remindChoices.isEmpty || remind != 0 {
            Menu("Remind me") {
                ForEach(remindChoices, id: \.self) { m in
                    Toggle(ReminderRules.shared.choiceLabel(minutes: m), isOn: Binding(
                        get: { m == remind },
                        set: { on in model.setEventReminder(e.id, on ? m : 0) }
                    ))
                }
                if remind != 0 {
                    Divider()
                    Button("Off") { model.setEventReminder(e.id, 0) }
                }
            }
        }
        if !travelChoices.isEmpty || travel != 0 {
            Menu("Leave by") {
                ForEach(travelChoices, id: \.self) { m in
                    Toggle(ReminderRules.shared.travelLabel(minutes: m), isOn: Binding(
                        get: { m == travel },
                        set: { on in model.setEventLeaveBy(e.id, on ? m : 0) }
                    ))
                }
                if travel != 0 {
                    Divider()
                    Button("Off") { model.setEventLeaveBy(e.id, 0) }
                }
            }
        }
        if !remindChoices.isEmpty || remind != 0 || !travelChoices.isEmpty || travel != 0 { Divider() }
    }
}

extension View {
    func eventActions(_ event: CalendarEvent?, palette: MekaPalette) -> some View {
        modifier(EventActionsModifier(event: event, palette: palette))
    }
}

/// The calendar-action undo bar: rises from the bottom with the message and Undo; goes after 5 seconds.
struct EventUndoBar: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        ZStack {
            if let offer = model.eventUndo {
                HStack(spacing: MekaSpace.m) {
                    Text(offer.message).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                    if offer.action != nil {
                        Button("Undo") { model.undoEventAction() }
                            .buttonStyle(.borderless)
                            .foregroundStyle(palette.accent)
                    }
                }
                .padding(.horizontal, MekaSpace.l)
                .padding(.vertical, MekaSpace.s)
                .background(palette.surfaceRaised, in: Capsule())
                .shadow(color: .black.opacity(0.15), radius: 8, y: 2)
                .padding(.bottom, MekaSpace.l)
                .id(offer.id)
                .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.eventUndo)
    }
}

/// An event: time on the left, title and where/which calendar under it. A running one is marked "Now".
struct TimelineEventRow: View {
    let row: TimelineRow
    let past: Bool
    let palette: MekaPalette

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            Text(row.time).font(MekaType.itemMeta).monospacedDigit()
                .foregroundStyle(past ? palette.textTertiary : palette.textSecondary)
                .frame(width: TimelineMetrics.timeColumn, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).font(MekaType.body).foregroundStyle(past ? palette.textTertiary : palette.textPrimary)
                let line = [row.running ? "Now" : nil, row.detail].compactMap { $0 }.joined(separator: " · ")
                if !line.isEmpty {
                    Text(line).font(MekaType.caption).foregroundStyle(row.running ? palette.accent : palette.textTertiary)
                }
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
        .opensEvent(row.event)
    }
}

/// A booked session (the Gym): time on the left, "Gym · Push" in the heavier item weight (a habit is something you act
/// on), "Leave by 17:30" under it, or "Now · until 18:45" in the accent colour while it's on. Today's session card above
/// answers it, so the row has no clicks of its own.
struct SessionTimelineRow: View {
    let row: TimelineRow
    let palette: MekaPalette

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            Text(row.time).font(MekaType.itemMeta).monospacedDigit()
                .foregroundStyle(palette.textSecondary)
                .frame(width: TimelineMetrics.timeColumn, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).font(MekaType.itemTitle).tracking(MekaType.itemTitleTracking).foregroundStyle(palette.textPrimary)
                if let line = row.detail {
                    Text(line).font(MekaType.caption).foregroundStyle(row.running ? palette.accent : palette.textTertiary)
                }
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
        .accessibilityElement(children: .combine)
    }
}

/// A free stretch: "1 h 30 free", quiet.
struct GapRow: View {
    let row: TimelineRow
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: 0) {
            Text(row.time).font(MekaType.itemMeta).monospacedDigit().foregroundStyle(palette.textTertiary)
                .frame(width: TimelineMetrics.timeColumn, alignment: .leading)
            Text(row.title).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            Spacer()
        }
        .padding(.vertical, MekaSpace.xxs)
        .padding(.horizontal, MekaSpace.xs)
    }
}

/// Work hours (Fold review 2026-10-08): a quiet band, not an event. Time on the left, then a hairline-bordered band with a
/// thin bar and "Work"; while at work the bar is lit and "Now · until 17:30" sits beside it in the accent colour. No
/// clicks. It glides with the other rows and leaves once work is over; reduced motion cross-fades.
struct WorkTimelineRow: View {
    let row: TimelineRow
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: 0) {
            Text(row.time).font(MekaType.itemMeta).monospacedDigit().foregroundStyle(palette.textTertiary)
                .frame(width: TimelineMetrics.timeColumn, alignment: .leading)
            WorkBand(title: row.title, detail: row.detail, running: row.running, palette: palette)
            Spacer(minLength: 0)
        }
        .padding(.vertical, MekaSpace.xxs)
        .padding(.horizontal, MekaSpace.xs)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([row.title, row.time, row.detail].compactMap { $0 }.joined(separator: ", "))
    }
}

/// The work band itself; the Calendar tab uses it for "Work 09:00–17:30" on each work day.
struct WorkBand: View {
    let title: String
    let detail: String?
    let running: Bool
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: MekaSpace.s) {
            RoundedRectangle(cornerRadius: 1).fill(running ? palette.accent : palette.textTertiary).frame(width: 2, height: 12)
            HStack(spacing: 0) {
                Text(title).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                if let detail {
                    Text(" · \(detail)").font(MekaType.caption).foregroundStyle(running ? palette.accent : palette.textTertiary)
                }
            }
        }
        .padding(.horizontal, MekaSpace.s)
        .padding(.vertical, MekaSpace.xxs)
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.s).strokeBorder(palette.hairline, lineWidth: 1))
    }
}

/// The now line: a breathing accent dot, the time, and a hairline across.
struct NowLine: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let row: TimelineRow
    let palette: MekaPalette
    @State private var dim = false

    var body: some View {
        HStack(spacing: 0) {
            Text(row.time).font(MekaType.caption).monospacedDigit().foregroundStyle(palette.accent)
                .frame(width: TimelineMetrics.timeColumn, alignment: .leading)
            Circle().fill(palette.accent).frame(width: 8, height: 8)
                .opacity(reduceMotion ? 1 : (dim ? 0.45 : 1))
            Rectangle().fill(palette.accent.opacity(0.5)).frame(height: 1)
        }
        .padding(.vertical, MekaSpace.xxs)
        .padding(.horizontal, MekaSpace.xs)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Now, \(row.time)")
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 1.4).repeatForever(autoreverses: true)) { dim = true }
        }
    }
}

/// All-day events as chips above the timeline.
struct AllDayChips: View {
    let events: [CalendarEvent]
    let palette: MekaPalette

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: MekaSpace.xs) {
                ForEach(events, id: \.id) { e in
                    Text(e.title).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .padding(.horizontal, MekaSpace.s).padding(.vertical, MekaSpace.xxs)
                        .background(Capsule().fill(palette.surfaceRaised))
                        .opensEvent(e)
                }
            }
        }
        .padding(.bottom, MekaSpace.xs)
    }
}

/// The "All day" group's label, once above its rows (all-day polish, Meka 2026-10-07 22:37): "All day", or
/// "All day · Timestripe" when every entry shares a calendar.
struct AllDayLabel: View {
    let label: String
    let palette: MekaPalette

    var body: some View {
        Text(label).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
            .padding(.top, MekaSpace.xxs)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// One row of Today's "All day" group (matching the Fold's AllDayRow): the title in the regular event weight, aligned
/// under the group's label in the timeline's title column, with its calendar under it only when calendars are mixed;
/// clicking opens the detail. Right-click: Make it a task · Hide <calendar> from Today · Details. An entry that reads
/// like a to-do also shows a small, quiet "Make it a task".
struct AllDayRow: View {
    @Environment(CoreModel.self) private var model
    let item: AllDayItem
    let palette: MekaPalette

    var body: some View {
        let hideLabel = "Hide \(item.calendarLabel) from Today"
        HStack(alignment: .center, spacing: 0) {
            Spacer().frame(width: TimelineMetrics.timeColumn)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.event.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                if let line = item.line {
                    Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .opensEvent(item.event)
            if item.todo {
                Button("Make it a task") { model.makeAllDayTask(item.event) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .padding(.horizontal, MekaSpace.xs).padding(.vertical, 2)
                    .overlay(Capsule().strokeBorder(palette.hairline, lineWidth: 1))
                    .help("Adds it to Today as a task and takes the entry off your day (your calendar is unchanged)")
            }
        }
        .padding(.vertical, MekaSpace.xs)
        .contextMenu {
            Button("Make it a task") { model.makeAllDayTask(item.event) }
            Button(hideLabel) { model.hideCalendarFromToday(key: item.calendarKey, label: item.calendarLabel) }
            Divider()
            Button("Details…") { model.openEvent = item.event }
        }
        .accessibilityAction(named: "Make it a task") { model.makeAllDayTask(item.event) }
        .accessibilityAction(named: hideLabel) { model.hideCalendarFromToday(key: item.calendarKey, label: item.calendarLabel) }
    }
}

/// "+2 more" under the all-day rows: unfolds the rest with a spring.
struct AllDayMore: View {
    let label: String
    @Binding var open: Bool
    let palette: MekaPalette

    var body: some View {
        Button { open = true } label: {
            Text(label).font(MekaType.caption).foregroundStyle(palette.accent)
                .padding(.leading, TimelineMetrics.timeColumn)
                .padding(.vertical, MekaSpace.xxs)
                .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
    }
}

/// "3 earlier ›": unfolds the events that have finished.
struct EarlierToggle: View {
    let label: String
    @Binding var open: Bool
    let palette: MekaPalette

    var body: some View {
        Button { open.toggle() } label: {
            HStack(spacing: MekaSpace.xxs) {
                Text(label)
                Image(systemName: "chevron.right").font(.system(size: 9, weight: .semibold))
                    .rotationEffect(.degrees(open ? 90 : 0))
            }
            .font(MekaType.caption).foregroundStyle(palette.textTertiary)
            .padding(.leading, TimelineMetrics.timeColumn + MekaSpace.xs)
            .padding(.vertical, MekaSpace.xxs)
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
    }
}

/// Up next's event line: "Call with Tunde in 25 min" with its time and place.
struct NextEventCard: View {
    let next: UpNextEvent
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: MekaSpace.m) {
            Circle().fill(palette.accent).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 2) {
                Text(next.line).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(next.detail).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            Spacer()
        }
        .padding(MekaSpace.l)
        .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.surfaceRaised))
        .opensEvent(next.event)
    }
}
