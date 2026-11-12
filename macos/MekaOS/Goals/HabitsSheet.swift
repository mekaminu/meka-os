@preconcurrency import MekaKit
import SwiftUI

/// HABITS, its own sheet on the Mac (Habits screen slice 2, the approved design `docs/design/habits/Habits.dc.html`,
/// like the Fold's HabitsPage): seven days ending today (click one to mark an earlier day), the day's "3 of 8 done"
/// with a bar and the best streak, list chips (All · Morning · All day · Night time · his own · + New list), the
/// habits boxed by list, each Do (plus) or Avoid (barred circle) with its streak and the strip's seven dots, and one
/// 44 pt circle cycling done (green tick) → missed (red cross) → clear. Clicking a habit unfolds Do or Avoid, its list
/// and Remove (click twice). + Add unfolds a form: name, Do or Avoid, list, days a week. Every word comes from core
/// HabitBoardRules, so both apps say the same.
///
/// Motion: the sheet scale-fades (system); the parts appear in the stagger; the day pill blends to brass; the bars
/// grow on the replan spring; the circle pops (spring) as its tick or cross cross-fades in, a light haptic for done
/// and a tick for missed and clear; a row's settings and the add form unfold on the expand spring; the toast rises.
/// Clicks show at once (drawn over the core's goals until the call returns). Reduce Motion: cross-fades, no pop.
struct HabitsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// 0–6 along the strip; 6 is today (HabitBoardRules.DAYS - 1).
    @State private var day = 6
    /// The chip he's on: HabitBoardRules.ALL to start.
    @State private var list = "All"
    @State private var open: String?
    @State private var removing: String?
    /// The add form: nil when folded, else whether it names a new list.
    @State private var adding: Bool?
    @State private var toast: String?
    @State private var toastSeq = 0

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let b = model.habitBoard(selected: day, list: list) {
                ScrollView {
                    content(b).padding(MekaSpace.l)
                }
                .frame(minHeight: 420, maxHeight: 680)
                .overlay(alignment: .bottom) { toastView }
            } else {
                Text("Navilife is still starting.").font(MekaType.body).foregroundStyle(palette.textSecondary)
                    .padding(MekaSpace.l)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding([.horizontal, .bottom], MekaSpace.l)
        }
        .frame(width: 560)
        .background(palette.background)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: adding)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: removing)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: toast)
    }

    @ViewBuilder
    private func content(_ b: HabitBoard) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            HStack {
                Text("Habits").font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Button("+ Add") { adding = adding == false ? nil : false }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.metaStrong)
                    .foregroundStyle(palette.onAccent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.accent, in: Capsule())
                    .accessibilityLabel("Add a habit")
            }
            .staggeredAppear(0)
            if let newList = adding {
                HabitAddForm(newList: newList, lists: b.lists, start: defaultList, palette: palette) { added, made in
                    adding = nil
                    if let made { list = made }
                    show("Added \(added)")
                }
                .id(newList)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            days(b).staggeredAppear(1)
            summary(b).staggeredAppear(2)
            chips(b).staggeredAppear(3)
            if b.empty {
                Text(HabitBoardRules.shared.EMPTY_LINE).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .staggeredAppear(4)
            }
            ForEach(b.groups, id: \.title) { g in
                group(g, lists: b.lists, day: b.selectedDay).staggeredAppear(4)
            }
            if !b.empty {
                Text(HabitBoardRules.shared.HINT + " · click a habit to change it")
                    .font(MekaType.micro).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    /// The list a new habit goes in: the chip he's on, else All day.
    private var defaultList: String { list == HabitBoardRules.shared.ALL ? HabitBoardRules.shared.ALL_DAY : list }

    // MARK: The day strip

    private func days(_ b: HabitBoard) -> some View {
        HStack(spacing: MekaSpace.xxs) {
            ForEach(Array(b.days.enumerated()), id: \.offset) { i, d in
                let ink = d.selected ? palette.onAccent : palette.textPrimary
                Button {
                    MekaHaptics.tick()
                    day = i
                    open = nil
                } label: {
                    VStack(spacing: MekaSpace.xxs) {
                        Text(d.weekday).font(MekaType.micro).foregroundStyle(ink)
                        Text("\(d.dayOfMonth)").font(MekaType.metaStrong).foregroundStyle(ink)
                        HabitBar(share: Double(d.pct) / 100, track: ink.opacity(0.2), fill: d.selected ? ink : palette.accent)
                            .frame(width: 22, height: 3)
                    }
                    .frame(maxWidth: .infinity, minHeight: 62)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.s).fill(d.selected ? palette.accent : palette.surface))
                    .contentShape(RoundedRectangle(cornerRadius: MekaRadius.s))
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: d.selected)
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel(d.label)
                .accessibilityAddTraits(d.selected ? .isSelected : [])
            }
        }
    }

    private func summary(_ b: HabitBoard) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack {
                Text(b.doneLine).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
                    .contentTransition(.opacity)
                Spacer()
                if let streak = b.streakLine {
                    Text(streak).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
            }
            HabitBar(share: Double(b.pct) / 100, track: palette.surfaceRaised, fill: palette.accent)
                .frame(height: 5)
                .animation(MekaMotion.replan(reduced: reduceMotion), value: b.pct)
        }
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
        .accessibilityElement(children: .combine)
    }

    private func chips(_ b: HabitBoard) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: MekaSpace.xxs) {
                ForEach(b.chips, id: \.name) { c in
                    HabitChip(label: c.name, lit: c.selected, palette: palette) {
                        list = c.name
                        open = nil
                    }
                }
                HabitChip(label: "+ New list", lit: false, palette: palette) { adding = adding == true ? nil : true }
            }
        }
    }

    // MARK: A list's box

    private func group(_ g: HabitBoardGroup, lists: [String], day: Int64) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: MekaSpace.xs) {
                Text(g.title).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                if !g.count.isEmpty {
                    Text(g.count).font(MekaType.micro).foregroundStyle(palette.textSecondary)
                        .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                        .background(palette.surfaceRaised, in: Capsule())
                }
                Spacer()
                if !g.whenLine.isEmpty {
                    Text(g.whenLine).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
            }
            .padding(.vertical, MekaSpace.xxs)
            ForEach(Array(g.rows.enumerated()), id: \.element.id) { i, r in
                if i > 0 { Rectangle().fill(palette.hairline).frame(height: 1) }
                HabitBoardRowView(row: r, palette: palette, onOpen: {
                    MekaHaptics.tick()
                    removing = nil
                    open = open == r.id ? nil : r.id
                }, onMark: {
                    if let said = model.markHabit(r.id, day: day, from: r.mark) { show(said) }
                })
                if open == r.id {
                    settings(r, lists: lists)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }
            }
        }
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
    }

    /// A habit's settings: its four weeks, the reminder, Do or Avoid, its list, and Remove (a second click removes it
    /// and its days).
    private func settings(_ r: HabitBoardRow, lists: [String]) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HabitHistoryGrid(history: r.history, palette: palette)
            Text("Reminder").font(MekaType.caption).foregroundStyle(palette.textSecondary)
            HStack(spacing: MekaSpace.xxs) {
                HabitChip(label: "Off", lit: r.remindAt == nil, palette: palette) {
                    if r.remindAt != nil { model.setHabitReminder(r.id, nil); show(HabitBoardRules.shared.NO_REMINDER) }
                }
                HabitChip(label: "Every day", lit: r.remindAt != nil, palette: palette) {
                    if r.remindAt == nil {
                        let m = Int(HabitBoardRules.shared.defaultReminder(list: r.list))
                        model.setHabitReminder(r.id, m)
                        show(HabitTime.line(m))
                    }
                }
                if let at = r.remindAt?.intValue {
                    HabitTimeStepper(minute: at, palette: palette) { m in
                        model.setHabitReminder(r.id, m)
                        show(HabitTime.line(m))
                    }
                }
            }
            HStack(spacing: MekaSpace.xxs) {
                HabitChip(label: "Do", lit: !r.avoid, palette: palette) { if r.avoid { model.setHabitAvoid(r.id, false) } }
                HabitChip(label: "Avoid", lit: r.avoid, palette: palette) { if !r.avoid { model.setHabitAvoid(r.id, true) } }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: MekaSpace.xxs) {
                    ForEach(lists, id: \.self) { l in
                        HabitChip(label: l, lit: l == r.list, palette: palette) { if l != r.list { model.setHabitList(r.id, l) } }
                    }
                }
            }
            let asking = removing == r.id
            Button(asking ? "Click again to remove \(r.name) and its days" : "Remove habit") {
                MekaHaptics.tick()
                if asking {
                    removing = nil
                    open = nil
                    model.deleteHabit(r.id)
                } else {
                    removing = r.id
                }
            }
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta)
            .foregroundStyle(asking ? palette.accent : palette.textSecondary)
            .contentTransition(.opacity)
        }
        .padding(.bottom, MekaSpace.xs)
    }

    // MARK: The toast

    @ViewBuilder
    private var toastView: some View {
        if let toast {
            Text(toast).font(MekaType.caption).foregroundStyle(palette.textPrimary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(RoundedRectangle(cornerRadius: MekaRadius.s).fill(palette.surfaceRaised))
                .padding(.bottom, MekaSpace.m)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .bottom)))
        }
    }

    /// "Done · nice", "Marked missed", "Added …": shown for 2.2 s.
    private func show(_ line: String) {
        toastSeq += 1
        let seq = toastSeq
        toast = line
        Task {
            try? await Task.sleep(for: .milliseconds(2200))
            if toastSeq == seq { toast = nil }
        }
    }
}

/// "Reminder 07:30" for a minute of the day.
private enum HabitTime {
    static func hhmm(_ m: Int) -> String { String(format: "%02d:%02d", m / 60, m % 60) }
    static func line(_ m: Int) -> String { "Reminder \(hhmm(m))" }
    /// 15 minutes either way, round the clock.
    static func step(_ m: Int, by: Int) -> Int { ((m + by) % 1440 + 1440) % 1440 }
}

/// "−  07:30  +": the reminder time, 15 minutes a click.
private struct HabitTimeStepper: View {
    let minute: Int
    let palette: MekaPalette
    let change: (Int) -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            Button("−") { MekaHaptics.tick(); change(HabitTime.step(minute, by: -15)) }
                .buttonStyle(MekaPressStyle()).foregroundStyle(palette.accent)
                .accessibilityLabel("Remind me 15 minutes earlier")
            Text(HabitTime.hhmm(minute)).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
                .monospacedDigit().contentTransition(.numericText())
            Button("+") { MekaHaptics.tick(); change(HabitTime.step(minute, by: 15)) }
                .buttonStyle(MekaPressStyle()).foregroundStyle(palette.accent)
                .accessibilityLabel("Remind me 15 minutes later")
        }
        .font(MekaType.metaStrong)
    }
}

/// Slice 3: a habit's four weeks, Monday to Sunday: green done (kept off, for Avoid), red missed, grey not marked,
/// faint before it was added or still to come; today outlined. Then the done line and the best run.
private struct HabitHistoryGrid: View {
    let history: HabitHistory
    let palette: MekaPalette

    var body: some View {
        if !history.weeks.isEmpty {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                HStack {
                    Text(history.doneLine).font(MekaType.caption).foregroundStyle(palette.textPrimary)
                    Spacer()
                    if let best = history.bestLine { Text(best).font(MekaType.caption).foregroundStyle(palette.textSecondary) }
                }
                HStack(spacing: MekaSpace.xxs) {
                    ForEach(Array(HabitBoardRules.shared.HISTORY_HEADS.enumerated()), id: \.offset) { _, d in
                        Text(d).font(MekaType.micro).foregroundStyle(palette.textTertiary).frame(width: 18)
                    }
                }
                ForEach(Array(history.weeks.enumerated()), id: \.offset) { _, week in
                    HStack(spacing: MekaSpace.xxs) {
                        ForEach(week, id: \.day) { c in
                            RoundedRectangle(cornerRadius: 4)
                                .fill(fill(c.mark))
                                .frame(width: 18, height: 18)
                                .overlay {
                                    if c.isToday { RoundedRectangle(cornerRadius: 4).stroke(palette.accent, lineWidth: 1) }
                                }
                                .accessibilityLabel(c.label)
                        }
                    }
                }
            }
            .accessibilityElement(children: .contain)
        }
    }

    private func fill(_ m: HabitMark?) -> Color {
        guard let m else { return palette.surfaceRaised.opacity(0.35) }
        switch m.name {
        case HabitMark.done.name: return palette.success.opacity(0.7)
        case HabitMark.missed.name: return palette.critical.opacity(0.7)
        default: return palette.surfaceRaised
        }
    }
}

/// A chip: brass when lit.
private struct HabitChip: View {
    let label: String
    let lit: Bool
    let palette: MekaPalette
    let action: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        Button {
            MekaHaptics.tick()
            action()
        } label: {
            Text(label).font(MekaType.caption).lineLimit(1)
                .foregroundStyle(lit ? palette.onAccent : palette.textPrimary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(Capsule().fill(lit ? palette.accent : palette.surfaceRaised))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityAddTraits(lit ? .isSelected : [])
    }
}

/// A thin rounded bar filled to [share].
private struct HabitBar: View {
    let share: Double
    let track: Color
    let fill: Color

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(track)
                Capsule().fill(fill).frame(width: geo.size.width * min(max(share, 0), 1))
            }
        }
    }
}

/// One habit: Do or Avoid icon, name, "Do · 9 day streak" with the week's dots, and the circle.
private struct HabitBoardRowView: View {
    let row: HabitBoardRow
    let palette: MekaPalette
    let onOpen: () -> Void
    let onMark: () -> Void

    var body: some View {
        HStack(spacing: MekaSpace.m) {
            HabitKindIcon(avoid: row.avoid, palette: palette)
            Button(action: onOpen) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(row.name).font(MekaType.taskTitle).foregroundStyle(palette.textPrimary)
                    HStack(spacing: MekaSpace.xs) {
                        Text(row.meta).font(MekaType.micro).foregroundStyle(palette.textSecondary)
                        HStack(spacing: 3) { // rhythm: ok, the mockup's 3 px gap between the week's dots
                            ForEach(Array(row.week.enumerated()), id: \.offset) { i, m in
                                Circle().fill(dot(m)).frame(width: 6, height: 6)
                                    .overlay(Circle().stroke(i == Int(row.selectedIndex) ? palette.textTertiary : .clear, lineWidth: 1))
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, MekaSpace.xs)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel("\(row.name), \(row.meta)")
            .accessibilityHint("Shows its four weeks, the reminder, Do or Avoid, its list and Remove")
            HabitMarkCircle(mark: row.mark, label: row.markLabel, palette: palette, action: onMark)
        }
        .frame(minHeight: 60)
    }

    private func dot(_ m: HabitMark) -> Color {
        switch m.name {
        case HabitMark.done.name: palette.success
        case HabitMark.missed.name: palette.critical
        default: palette.surfaceRaised
        }
    }
}

/// Do: a plus on green; Avoid: a barred circle on red. 36 pt, rounded.
private struct HabitKindIcon: View {
    let avoid: Bool
    let palette: MekaPalette

    var body: some View {
        let ink = avoid ? palette.critical : palette.success
        ZStack {
            RoundedRectangle(cornerRadius: MekaRadius.s).fill(ink.opacity(0.16))
            Canvas { ctx, size in
                let s = min(size.width, size.height)
                var p = Path()
                if avoid {
                    p.addEllipse(in: CGRect(x: s * 0.1, y: s * 0.1, width: s * 0.8, height: s * 0.8))
                    p.move(to: CGPoint(x: s * 0.22, y: s * 0.22)); p.addLine(to: CGPoint(x: s * 0.78, y: s * 0.78))
                } else {
                    p.move(to: CGPoint(x: s / 2, y: s * 0.15)); p.addLine(to: CGPoint(x: s / 2, y: s * 0.85))
                    p.move(to: CGPoint(x: s * 0.15, y: s / 2)); p.addLine(to: CGPoint(x: s * 0.85, y: s / 2))
                }
                ctx.stroke(p, with: .color(ink), style: StrokeStyle(lineWidth: 2, lineCap: .round))
            }
            .frame(width: 16, height: 16)
        }
        .frame(width: 36, height: 36)
        .accessibilityHidden(true)
    }
}

/// The one 44 pt circle: a brass outline when clear, a green tick when done, a red cross when missed. It pops on a
/// spring when marked (not under Reduce Motion).
private struct HabitMarkCircle: View {
    let mark: HabitMark
    let label: String
    let palette: MekaPalette
    let action: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var pop: CGFloat = 1

    var body: some View {
        let name = mark.name
        let done = name == HabitMark.done.name
        let missed = name == HabitMark.missed.name
        Button(action: action) {
            ZStack {
                Circle().fill(done ? palette.success.opacity(0.2) : missed ? palette.critical.opacity(0.2) : .clear)
                Circle().stroke(done || missed ? .clear : palette.accent, lineWidth: 1.5)
                Canvas { ctx, size in
                    let s = min(size.width, size.height)
                    var p = Path()
                    if done {
                        p.move(to: CGPoint(x: s * 0.2, y: s * 0.52)); p.addLine(to: CGPoint(x: s * 0.42, y: s * 0.72))
                        p.addLine(to: CGPoint(x: s * 0.8, y: s * 0.3))
                    } else if missed {
                        p.move(to: CGPoint(x: s * 0.25, y: s * 0.25)); p.addLine(to: CGPoint(x: s * 0.75, y: s * 0.75))
                        p.move(to: CGPoint(x: s * 0.75, y: s * 0.25)); p.addLine(to: CGPoint(x: s * 0.25, y: s * 0.75))
                    }
                    ctx.stroke(p, with: .color(done ? palette.success : palette.critical),
                               style: StrokeStyle(lineWidth: 2.6, lineCap: .round, lineJoin: .round))
                }
                .frame(width: 18, height: 18)
                .id(name)
                .transition(.opacity)
            }
            .frame(width: 44, height: 44)
            .scaleEffect(pop)
            .frame(width: MekaSpace.touch, height: MekaSpace.touch)
            .contentShape(Circle())
            .animation(MekaMotion.appear(reduced: reduceMotion), value: name)
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(label)
        .onChange(of: name) { _, now in
            guard !reduceMotion, now != HabitMark.clear.name else { return }
            pop = 0.7
            withAnimation(MekaMotion.complete(reduced: false)) { pop = 1 }
        }
    }
}

/// + Add (or + New list): a name, Do or Avoid, the list (or the new list's name) and days a week, then Add habit.
private struct HabitAddForm: View {
    @Environment(CoreModel.self) private var model
    let newList: Bool
    let lists: [String]
    let palette: MekaPalette
    /// The habit's name and, for a new list, the list to show.
    let added: (String, String?) -> Void
    @State private var name = ""
    @State private var avoid = false
    @State private var into: String
    @State private var own = ""
    @State private var perWeek: Int32 = 7
    @State private var remind: Int?
    @State private var problem: String?
    @State private var saving = false

    init(newList: Bool, lists: [String], start: String, palette: MekaPalette, added: @escaping (String, String?) -> Void) {
        self.newList = newList
        self.lists = lists
        self.palette = palette
        self.added = added
        _into = State(initialValue: start)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(newList ? "New list" : "New habit").font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
            if newList {
                TextField("Name the list, like Weekend or Gym days", text: $own)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityLabel("List name")
            }
            TextField(avoid ? "Like No biscuits" : "Like Drink water", text: $name)
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel("Habit name")
                .onSubmit { save() }
            HStack(spacing: MekaSpace.xxs) {
                HabitChip(label: "Do", lit: !avoid, palette: palette) { avoid = false }
                HabitChip(label: "Avoid", lit: avoid, palette: palette) { avoid = true }
            }
            if !newList {
                Text("List").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: MekaSpace.xxs) {
                        ForEach(lists, id: \.self) { l in HabitChip(label: l, lit: l == into, palette: palette) { into = l } }
                    }
                }
            }
            Text("Days").font(MekaType.caption).foregroundStyle(palette.textSecondary)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: MekaSpace.xxs) {
                    ForEach(GoalRules.shared.TARGET_CHOICES, id: \.perWeek) { c in
                        HabitChip(label: c.label, lit: c.perWeek == perWeek, palette: palette) { perWeek = c.perWeek }
                    }
                }
            }
            Text("Reminder").font(MekaType.caption).foregroundStyle(palette.textSecondary)
            HStack(spacing: MekaSpace.xxs) {
                HabitChip(label: "Off", lit: remind == nil, palette: palette) { remind = nil }
                HabitChip(label: "Every day", lit: remind != nil, palette: palette) {
                    if remind == nil { remind = Int(HabitBoardRules.shared.defaultReminder(list: newList ? own : into)) }
                }
                if let m = remind { HabitTimeStepper(minute: m, palette: palette) { remind = $0 } }
            }
            Button("Add habit") { save() }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.metaStrong)
                .foregroundStyle(palette.onAccent)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(palette.accent, in: Capsule())
                .disabled(saving)
            if let problem {
                Text(problem).font(MekaType.caption).foregroundStyle(palette.accent).transition(.opacity)
            }
        }
        .padding(MekaSpace.m)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
    }

    private func save() {
        guard !saving else { return }
        let target = newList ? HabitBoardRules.shared.cleanList(raw: own) : into
        let title = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let target else { problem = "Name the list"; return }
        guard !title.isEmpty else { problem = "Name the habit"; return }
        saving = true
        let made = newList ? target : nil
        Task {
            let why = await model.addHabitToList(title, avoid: avoid, list: target, perWeek: perWeek, remindAt: remind)
            saving = false
            if let why { problem = why } else { added(title, made) }
        }
    }
}
