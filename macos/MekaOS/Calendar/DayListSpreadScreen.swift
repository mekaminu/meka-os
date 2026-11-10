@preconcurrency import MekaKit
import SwiftUI

/// Calendar's Board on the Mac as Day lists side by side (the approved Day list item's slice 5, 2026-11-08: "the Mac
/// offers 3 · 5 · 7 days, default 5"; the open Fold's `docs/design/day-list/OpenSpread.dc.html` at the Mac's width;
/// core `DayListBoardRules` and `DayListSpreadRules`). One header row: the month, "Week 46 · 6h 15m planned", ‹ › (⌘[ ⌘])
/// and ⋯ (Agenda view, New event, Plan your week); then the 3 days · 5 days · 7 days switch (this Mac remembers its
/// choice) beside the week's seven pills, each with its planned length and the days in view lit; then that many Day
/// lists across, each with its heading ("Wed 11 · today"), total, the gold done bar, its rows (time · title · area
/// stacked, the length on the right), and its own add field (Return adds the line and keeps focus for the next).
///
/// Click a pill to bring that day into view (the others slide along, held inside the week). Drag a task by its row onto
/// another day's list or pill (today or later): the day it would land on wears a gold ring, and the task lands at the
/// end of that day with Undo on the bar. Click a row for its options in place (Length, Area, Move to, Edit…, Done).
///
/// Motion: the header and the switch row stagger in; the lit pills' fill cross-fades; the columns cross-fade as a set
/// to the next days (each list's own row motion inside: a new row arrives with the item motion, the rest glide); a
/// targeted column's ring blends in on the appear spring. Reduce Motion: cross-fades only.
struct DayListSpreadScreen: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let board: WeekBoard
    let palette: MekaPalette
    @AppStorage("board.spreadDays") private var count = Int(DayListSpreadRules.shared.MAC_DEFAULT_COUNT)
    @State private var chosen: Int64?
    @State private var target: Int64?
    @State private var editing: SpreadEditing?

    private var rules: DayListBoardRules { DayListBoardRules.shared }
    private var spread: DayListSpreadRules { DayListSpreadRules.shared }

    var body: some View {
        if let shown = rules.shownDay(board: board, chosen: chosen.map { KotlinLong(value: $0) })?.int64Value,
           let week = rules.weekOf(board: board, day: shown) {
            let k = Int32(spread.macCountOrDefault(count: KotlinInt(value: Int32(clamping: count))))
            let columns = spread.macColumns(week: week, chosen: shown, count: k)
            VStack(alignment: .leading, spacing: MekaSpace.m) {
                header(week, day: shown)
                    .staggeredAppear(0)
                HStack(spacing: MekaSpace.m) {
                    countSwitch(k)
                    Spacer(minLength: MekaSpace.xs)
                    pills(week, lit: Set(columns.map(\.epochDay)))
                }
                .staggeredAppear(1)
                HStack(alignment: .top, spacing: MekaSpace.xs) {
                    ForEach(columns, id: \.epochDay) { c in
                        SpreadColumn(column: c, palette: palette, ringed: target == c.epochDay,
                                     onEdit: { editing = SpreadEditing(id: $0) },
                                     onAcross: { id in drop(id, on: c.epochDay) })
                            .dropDestination(for: String.self) { ids, _ in
                                guard let id = ids.first else { return false }
                                return drop(id, on: c.epochDay)
                            } isTargeted: { on in targetChanged(c.epochDay, on: on) }
                    }
                }
                .id(columns.map(\.epochDay))
                .transition(.opacity)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .staggeredAppear(2)
            }
            .animation(MekaMotion.appear(reduced: reduceMotion), value: columns.map(\.epochDay))
            .animation(MekaMotion.appear(reduced: reduceMotion), value: target)
            .sheet(item: $editing) { e in
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    DetailView(task: board.dayOf(taskId: e.id)?.cards.first(where: { $0.id == e.id })?.task, palette: palette)
                        .frame(height: 480)
                    HStack { Spacer(); Button("Done") { editing = nil }.keyboardShortcut(.cancelAction) }
                }
                .padding(MekaSpace.l)
                .frame(width: 520)
                .background(palette.surface)
            }
        }
    }

    // MARK: Header

    @ViewBuilder private func header(_ week: BoardWeek, day: Int64) -> some View {
        let head = rules.head(week: week)
        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: 0) {
                Text(head.month).font(BoardFont.month).foregroundStyle(palette.textPrimary).lineLimit(1)
                Text(head.weekLine).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).monospacedDigit().lineLimit(1)
                    .contentTransition(.opacity)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(head.spoken)
            Spacer(minLength: MekaSpace.xs)
            let back = rules.canStep(board: board, day: day, delta: -1)
            let forward = rules.canStep(board: board, day: day, delta: 1)
            Button { stepWeek(day, -1) } label: { RoundStep(systemName: "chevron.left", palette: palette) }
                .buttonStyle(.plain)
                .opacity(back ? 1 : 0.3)
                .disabled(!back)
                .keyboardShortcut("[", modifiers: .command)
                .help("Previous week")
                .accessibilityLabel("Previous week")
            Button { stepWeek(day, 1) } label: { RoundStep(systemName: "chevron.right", palette: palette) }
                .buttonStyle(.plain)
                .opacity(forward ? 1 : 0.3)
                .disabled(!forward)
                .keyboardShortcut("]", modifiers: .command)
                .help("Next week")
                .accessibilityLabel("Next week")
            Menu {
                Button("Agenda view") {
                    MekaHaptics.tick()
                    withAnimation(MekaMotion.appear(reduced: reduceMotion)) { model.calendarShowsBoard = false }
                }
                if !model.editAccounts.isEmpty {
                    Button("New event") {
                        MekaHaptics.tick()
                        model.addEventDay = day < board.todayEpochDay ? board.todayEpochDay : day
                    }
                }
                Button(PlanWeekRules.shared.OPEN + " · Obi helps") { MekaHaptics.tick(); model.draftWeek() }
            } label: {
                RoundStep(systemName: "ellipsis", palette: palette)
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .menuIndicator(.hidden)
            .fixedSize()
            .accessibilityLabel(BoardOpenFoldRules.shared.MENU_LABEL)
            .help("Agenda view, new event, plan your week")
        }
    }

    private func stepWeek(_ day: Int64, _ delta: Int32) {
        guard let next = rules.stepWeek(board: board, day: day, delta: delta)?.int64Value else { return }
        MekaHaptics.tick()
        chosen = next
    }

    // MARK: The switch and the pills

    @ViewBuilder private func countSwitch(_ current: Int32) -> some View {
        HStack(spacing: MekaSpace.xxs) {
            ForEach(spread.MAC_COUNTS.map(\.int32Value), id: \.self) { n in
                let on = n == current
                Button {
                    MekaHaptics.tick()
                    count = Int(n)
                } label: {
                    Text(spread.countLabel(count: n)).font(on ? MekaType.metaStrong : MekaType.itemMeta).lineLimit(1)
                        .foregroundStyle(on ? palette.textPrimary : palette.textSecondary)
                        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                        .background(Capsule().fill(on ? palette.surfaceRaised : palette.surface))
                        .contentShape(Capsule())
                }
                .buttonStyle(MekaPressStyle())
                .keyboardShortcut(KeyEquivalent(Character(String(n))), modifiers: [.command, .option])
                .accessibilityAddTraits(on ? [.isSelected] : [])
                .help("Show \(spread.countLabel(count: n)) (⌥⌘\(n))")
            }
        }
        .padding(MekaSpace.xxs)
        .background(Capsule().fill(palette.surface))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: current)
    }

    @ViewBuilder private func pills(_ week: BoardWeek, lit: Set<Int64>) -> some View {
        HStack(spacing: MekaSpace.xxs) {
            ForEach(rules.strip(week: week), id: \.epochDay) { p in
                let on = lit.contains(p.epochDay)
                let ring = target == p.epochDay
                Button {
                    MekaHaptics.tick()
                    chosen = p.epochDay
                } label: {
                    VStack(spacing: 0) {
                        Text(p.letter).font(MekaType.micro)
                            .foregroundStyle(on ? palette.textPrimary : palette.textTertiary)
                        Text(p.number).font(MekaType.metaStrong).monospacedDigit()
                            .foregroundStyle(p.isToday ? palette.accent : (on ? palette.textPrimary : palette.textSecondary))
                        Text(p.length).font(MekaType.micro).monospacedDigit().lineLimit(1)
                            .foregroundStyle(palette.textTertiary)
                    }
                    .frame(width: 54, height: CGFloat(DayListBoardRules.shared.PILL_HEIGHT) - MekaSpace.xs)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(on ? palette.surfaceRaised : palette.surface))
                    .overlay(RoundedRectangle(cornerRadius: MekaRadius.m)
                        .strokeBorder(ring ? palette.accent : (p.isToday ? palette.accent.opacity(0.5) : Color.clear), lineWidth: ring ? 2 : 1))
                    .opacity(p.isPast && !on ? 0.6 : 1)
                    .contentShape(RoundedRectangle(cornerRadius: MekaRadius.m))
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel(p.spoken)
                .accessibilityAddTraits(on ? [.isSelected] : [])
                .dropDestination(for: String.self) { ids, _ in
                    guard let id = ids.first else { return false }
                    return drop(id, on: p.epochDay)
                } isTargeted: { on in targetChanged(p.epochDay, on: on) }
            }
        }
    }

    // MARK: Drag across days

    /// A task dragged from another day's list lands at the end of [day] (today or later, as on the open Fold), with Undo.
    private func drop(_ id: String, on day: Int64) -> Bool {
        target = nil
        guard let from = board.dayOf(taskId: id)?.epochDay, spread.canDropOn(board: board, from: from, to: day) else { return false }
        model.moveOnBoard(id, day: day, index: Int.max / 2)
        return true
    }

    private func targetChanged(_ day: Int64, on: Bool) {
        if on {
            if target != day { MekaHaptics.tick() }
            target = day
        } else if target == day {
            target = nil
        }
    }
}

/// The task whose detail is open from a column's Edit…
private struct SpreadEditing: Identifiable {
    let id: String
}

/// One day's Day list in the spread: its heading and total, the done bar, the rows (each clicks open to its options),
/// the hint, the line answering its add field, and the add field itself.
private struct SpreadColumn: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let column: DayListColumn
    let palette: MekaPalette
    let ringed: Bool
    let onEdit: (String) -> Void
    let onAcross: (String) -> Bool
    @State private var draft = ""
    @State private var openID: String?
    @State private var dropOver: String?
    @FocusState private var fieldFocused: Bool

    var body: some View {
        let day = column.epochDay
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let v = model.dayList(timesOn: true, day: day) {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(column.title).font(MekaType.itemTitle).lineLimit(1)
                        .foregroundStyle(column.isToday ? palette.accent : palette.textPrimary)
                    Spacer(minLength: MekaSpace.xxs)
                    Text(v.total).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary).monospacedDigit().lineLimit(1)
                }
                DayListBar(progress: Double(v.progress), line: [v.doneLine, v.endsLine].compactMap { $0 }.joined(separator: " · "), palette: palette)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        if v.isEmpty {
                            Text(DayListRules.shared.EMPTY).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                                .padding(.vertical, MekaSpace.m)
                        }
                        ForEach(Array(v.rows.enumerated()), id: \.element.id) { index, r in
                            VStack(alignment: .leading, spacing: 0) {
                                DayListRowView(row: r, timesOn: v.timesOn, open: openID == r.id, palette: palette, stacked: true) {
                                    MekaHaptics.tick()
                                    openID = openID == r.id ? nil : r.id
                                }
                                .modifier(DayListDrag(row: r, index: index, view: v, palette: palette, over: $dropOver, day: day, onAcross: onAcross))
                                if openID == r.id {
                                    DayListOptions(row: r, day: day, palette: palette,
                                                   onEdit: { openID = nil; onEdit(r.id) },
                                                   onDone: { openID = nil; model.complete(r.id) },
                                                   onLeave: { openID = nil })
                                        .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                                }
                            }
                            .transition(reduceMotion ? .opacity : .asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity), removal: .opacity))
                        }
                        Text(DayListSpreadRules.shared.HINT).font(MekaType.micro).foregroundStyle(palette.textTertiary)
                            .padding(.top, MekaSpace.xs)
                    }
                    .animation(MekaMotion.replan(reduced: reduceMotion), value: v.rows.map(\.id))
                    .animation(MekaMotion.expand(reduced: reduceMotion), value: openID)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(minHeight: 200, maxHeight: .infinity)
                if model.dayListSaidDay == day, let said = model.dayListSaid {
                    Text(LoneWordGlue.shared.kept(line: said)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .lineLimit(2)
                        .id(said)
                        .transition(.opacity)
                }
                addField
            }
        }
        .padding(MekaSpace.xs)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).strokeBorder(palette.accent.opacity(ringed ? 1 : 0), lineWidth: 2))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.dayListSaid)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(column.title)
    }

    private var addField: some View {
        HStack(spacing: MekaSpace.xxs) {
            TextField(column.placeholder, text: $draft)
                .textFieldStyle(.plain)
                .font(MekaType.body)
                .focused($fieldFocused)
                .onSubmit(add)
                .onChange(of: draft) { _, next in
                    // A pasted list arrives with its new lines: add it at once.
                    if next.contains("\n") { add() }
                }
                .accessibilityLabel(column.placeholder)
            Button(action: add) {
                Text("+").font(MekaType.itemTitle).foregroundStyle(palette.onAccent)
                    .frame(width: 24, height: 24)
                    .background(Circle().fill(palette.accent))
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel("Add to \(column.title)")
        }
        .padding(.leading, MekaSpace.m)
        .padding(.trailing, MekaSpace.xxs)
        .padding(.vertical, MekaSpace.xxs)
        .background(Capsule().fill(palette.surfaceRaised))
    }

    private func add() {
        let text = draft
        draft = ""
        model.addToDayList(text, day: column.epochDay)
        fieldFocused = true
    }
}
