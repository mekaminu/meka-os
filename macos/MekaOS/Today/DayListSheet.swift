@preconcurrency import MekaKit
import SwiftUI

/// The Day list on the Mac (approved 2026-11-08, `docs/design/day-list/Main.dc.html`; slice 1b): one sheet for one day
/// where a long list of tasks goes in fast. From Today's "Day list ›" and ⌘N. The day and date, Times on/off and the
/// day's total; the gold bar with "2 of 5 done · ends about 11:15"; slim rows (tick · time · title · area · length); an
/// add field that keeps focus — Return adds the line and clears it for the next ("30m", "9:00", "#work" read from the
/// text) — with Paste a list (⇧⌘V) and Fit times under it. Clicking a row opens its options in place: Length, Area,
/// Move to, Edit… (the task's full detail) and Done. The same tasks as the Week board and the Fold.
///
/// Motion: sections stagger in; a new row arrives with the list's item motion and the rest glide; the bar fills on the
/// appear spring; the line under the field cross-fades; the options and the detail push in. Reduce Motion: cross-fades.
struct DayListSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @AppStorage("dayList.timesOn") private var timesOn = true
    @State private var draft = ""
    @State private var openID: String?
    @State private var editingID: String?
    @FocusState private var fieldFocused: Bool

    var body: some View {
        let v = model.dayList(timesOn: timesOn)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if let id = editingID, let task = model.board?.dayOf(taskId: id)?.cards.first(where: { $0.id == id })?.task {
                Button("‹ Day list") { editingID = nil }
                    .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                    .keyboardShortcut(.cancelAction)
                DetailView(task: task, palette: palette)
                    .frame(height: 480)
                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.push(from: .trailing).combined(with: .opacity))
            } else if let v {
                list(v)
                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.push(from: .leading).combined(with: .opacity))
            } else {
                Text("This day isn't on the board any more.").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
            }
            HStack {
                Spacer()
                // Return belongs to the add field (one task after another), so Done is Esc, not the default button.
                if editingID == nil {
                    Button("Done") { dismiss() }.keyboardShortcut(.cancelAction)
                } else {
                    Button("Done") { dismiss() }
                }
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: editingID)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: openID)
        .padding(MekaSpace.l)
        .frame(width: 520)
        .background(palette.surface)
        .onAppear { fieldFocused = true }
    }

    @ViewBuilder private func list(_ v: DayListView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(dayPart(v.title, first: true)).font(MekaType.upNextTitle).tracking(MekaType.upNextTitleTracking)
                        .foregroundStyle(palette.textPrimary)
                    Spacer(minLength: MekaSpace.xs)
                    DayListChip(label: timesOn ? "Times on" : "Times off", selected: timesOn, palette: palette) {
                        MekaHaptics.tick()
                        timesOn.toggle()
                    }
                    Text(v.total).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary).monospacedDigit()
                }
                if v.isToday {
                    Text(dayPart(v.title, first: false)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                }
            }
            .staggeredAppear(0)
            DayListBar(progress: Double(v.progress), line: [v.doneLine, v.endsLine].compactMap { $0 }.joined(separator: " · "), palette: palette)
                .staggeredAppear(1)
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    if v.isEmpty {
                        Text(DayListRules.shared.EMPTY).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                            .padding(.vertical, MekaSpace.m)
                    }
                    ForEach(v.rows, id: \.id) { r in
                        VStack(alignment: .leading, spacing: 0) {
                            DayListRowView(row: r, timesOn: v.timesOn, open: openID == r.id, palette: palette) {
                                MekaHaptics.tick()
                                openID = openID == r.id ? nil : r.id
                            }
                            if openID == r.id {
                                DayListOptions(row: r, day: v.epochDay, palette: palette,
                                               onEdit: { openID = nil; editingID = r.id },
                                               onDone: { openID = nil; model.complete(r.id) },
                                               onLeave: { openID = nil })
                                    .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                            }
                        }
                        .transition(reduceMotion ? .opacity : .asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity), removal: .opacity))
                    }
                    Text(DayListRules.shared.HINT).font(MekaType.micro).foregroundStyle(palette.textTertiary)
                        .padding(.top, MekaSpace.xs)
                }
                .animation(MekaMotion.replan(reduced: reduceMotion), value: v.rows.map(\.id))
                .frame(maxWidth: .infinity, alignment: .leading)
                .staggeredAppear(2)
            }
            .frame(minHeight: 200, maxHeight: 420)
            if let said = model.dayListSaid {
                Text(LoneWordGlue.shared.kept(line: said)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .id(said)
                    .transition(.opacity)
            }
            addField
            HStack(spacing: MekaSpace.xs) {
                DayListChip(label: DayListRules.shared.PASTE, palette: palette) { model.pasteIntoDayList() }
                    .keyboardShortcut("v", modifiers: [.command, .shift])
                if !v.openIds.isEmpty {
                    let from = model.dayListFitFrom(isToday: v.isToday)
                    DayListChip(label: DayListRules.shared.fitLabel(fromMinute: from), palette: palette) { model.fitDayList(from: from) }
                }
            }
            if model.eventUndo != nil { EventUndoBar(palette: palette) }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.dayListSaid)
    }

    private var addField: some View {
        HStack(spacing: MekaSpace.xs) {
            TextField(DayListRules.shared.PLACEHOLDER, text: $draft)
                .textFieldStyle(.plain)
                .font(MekaType.body)
                .focused($fieldFocused)
                .onSubmit(add)
                .onChange(of: draft) { _, next in
                    // A pasted list arrives with its new lines: add it at once.
                    if next.contains("\n") { add() }
                }
                .accessibilityLabel(DayListRules.shared.PLACEHOLDER)
            Button(action: add) {
                Text("+").font(MekaType.itemTitle).foregroundStyle(palette.onAccent)
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(palette.accent))
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel("Add")
        }
        .padding(.leading, MekaSpace.m)
        .padding(.trailing, MekaSpace.xxs)
        .padding(.vertical, MekaSpace.xxs)
        .background(Capsule().fill(palette.surfaceRaised))
    }

    private func add() {
        let text = draft
        draft = ""
        model.addToDayList(text)
        fieldFocused = true
    }

    /// "Mon 9 Nov · Today" → "Mon 9 Nov" / "Today".
    private func dayPart(_ title: String, first: Bool) -> String {
        guard let r = title.range(of: " · ") else { return first ? title : "" }
        return first ? String(title[..<r.lowerBound]) : String(title[r.upperBound...])
    }
}

/// The gold bar that fills as the day's planned minutes are done, with its line under it.
private struct DayListBar: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let progress: Double
    let line: String
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.surfaceRaised)
                    Capsule().fill(palette.accent).frame(width: g.size.width * min(max(progress, 0), 1))
                }
            }
            .frame(height: 6)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: progress)
            Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary).monospacedDigit()
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(line)
    }
}

/// A slim row: tick · time · title · area dot and name · length. Clicking it opens its options under it.
private struct DayListRowView: View {
    let row: DayListRow
    let timesOn: Bool
    let open: Bool
    let palette: MekaPalette
    let onOpen: () -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            if row.done {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.accent).frame(width: 22, height: 22)
            } else {
                CompleteButton(id: row.id, title: row.title, palette: palette, settle: true)
            }
            Button(action: onOpen) {
                HStack(spacing: MekaSpace.xs) {
                    if timesOn {
                        Text(row.time ?? "").font(MekaType.caption).monospacedDigit()
                            .foregroundStyle(row.timeIsSet ? palette.textSecondary : palette.textTertiary)
                            .frame(width: 40, alignment: .leading)
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        Text(row.title).font(MekaType.taskTitle).lineLimit(2)
                            .foregroundStyle(row.done ? palette.textTertiary : palette.textPrimary)
                            .strikethrough(row.done)
                        if !timesOn, let t = row.time {
                            Text(t).font(MekaType.micro).foregroundStyle(palette.textSecondary)
                        }
                    }
                    Spacer(minLength: MekaSpace.xs)
                    if let a = row.area {
                        HStack(spacing: MekaSpace.xxs) {
                            Circle().fill(palette.areaTone(a)).frame(width: 7, height: 7)
                            Text(row.areaLabel ?? "").font(MekaType.micro).foregroundStyle(palette.textSecondary).lineLimit(1)
                        }
                    }
                    if let len = row.length {
                        Text(len).font(MekaType.micro).monospacedDigit()
                            .foregroundStyle(row.lengthGuessed ? palette.textTertiary : palette.textPrimary)
                            .padding(.horizontal, MekaSpace.xs).padding(.vertical, 2)
                            .background(Capsule().fill(palette.surfaceRaised))
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(row.spoken)
            .accessibilityHint(open ? "Close options" : "Options")
        }
        .padding(.vertical, MekaSpace.xxs)
        .padding(.horizontal, MekaSpace.xxs)
        .background(RoundedRectangle(cornerRadius: MekaRadius.s).fill(open ? palette.surfaceRaised : Color.clear))
        .mekaHoverLift()
    }
}

/// A row's options (the approved `Sheet.dc.html`): LENGTH, AREA, MOVE TO (the week's days · Next week · Someday), then
/// Edit… and Done. Every change offers Undo on the bar. Only ids, minutes, area names and days cross to the core.
private struct DayListOptions: View {
    @Environment(CoreModel.self) private var model
    let row: DayListRow
    let day: Int64
    let palette: MekaPalette
    let onEdit: () -> Void
    let onDone: () -> Void
    let onLeave: () -> Void

    var body: some View {
        let card = row.card
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if !row.done {
                label("LENGTH")
                chips {
                    ForEach(card.estimateChoices, id: \.minutes) { c in
                        DayListChip(label: TaskEstimateRules.shared.label(minutes: c.minutes), selected: c.selected, palette: palette) {
                            if !c.selected { model.setEstimateOnBoard(row.id, minutes: Int(c.minutes)) }
                        }
                    }
                    if card.task.estimateMinutes != nil {
                        DayListChip(label: TaskEstimateRules.shared.CLEAR, palette: palette) { model.setEstimateOnBoard(row.id, minutes: nil) }
                    }
                }
                label(BoardAreaRules.shared.MENU_TITLE)
                chips {
                    ForEach(Array(BoardAreaRules.shared.choices(card: card).enumerated()), id: \.offset) { _, c in
                        DayListChip(label: c.label.replacingOccurrences(of: "✓ ", with: ""), selected: c.selected, palette: palette) {
                            if !c.selected { model.areaOnBoard(row.id, area: c.area?.name) }
                        }
                    }
                }
                if let board = model.board {
                    label("MOVE TO")
                    chips {
                        ForEach(moveDays(board)) { m in
                            DayListChip(label: m.name, palette: palette) { onLeave(); model.moveOnBoard(row.id, day: m.id, index: Int.max / 2) }
                        }
                        if !card.task.isRepeating {
                            if board.weeks.count > 1 {
                                let week = board.weeks[1].startEpochDay
                                DayListChip(label: "Next week", palette: palette) { onLeave(); model.parkOnBoard(row.id, week: week, index: Int.max / 2) }
                            }
                            DayListChip(label: WeekBoardRules.shared.TO_SOMEDAY, palette: palette) { onLeave(); model.somedayOnBoard(row.id) }
                        }
                    }
                }
            }
            HStack(spacing: MekaSpace.xs) {
                DayListChip(label: "Edit…", palette: palette, action: onEdit)
                Spacer()
                if !row.done { DayListChip(label: "Done", selected: true, palette: palette, action: onDone) }
            }
            .padding(.top, MekaSpace.xxs)
        }
        .padding(MekaSpace.s)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised.opacity(0.5)))
        .padding(.bottom, MekaSpace.xs)
    }

    /// Today, Tomorrow and the rest of the week (not the list's own day), as "Wed 11".
    private func moveDays(_ board: WeekBoard) -> [DayListMoveDay] {
        let today = board.todayEpochDay
        return (today..<today + 7).filter { $0 != day }.compactMap { d in
            if d == today { return DayListMoveDay(id: d, name: "Today") }
            if d == today + 1 { return DayListMoveDay(id: d, name: "Tomorrow") }
            guard let bd = board.day(epochDay: d) else { return nil }
            return DayListMoveDay(id: d, name: "\(bd.weekday) \(bd.number)")
        }
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking).foregroundStyle(palette.textTertiary)
    }

    private func chips<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: MekaSpace.xs) { content() }
        }
    }
}

/// A Move to choice: the day and its name ("Tomorrow", "Wed 11").
private struct DayListMoveDay: Identifiable {
    let id: Int64
    let name: String
}

/// A chip: Times on, Paste a list, Fit times and the options; [selected] wears the accent edge.
struct DayListChip: View {
    let label: String
    var selected = false
    let palette: MekaPalette
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label).font(MekaType.caption).lineLimit(1)
                .foregroundStyle(selected ? palette.textPrimary : palette.textSecondary)
                .padding(.horizontal, MekaSpace.s).padding(.vertical, MekaSpace.xxs + 2)
                .background(Capsule().fill(palette.surfaceRaised))
                .overlay(Capsule().strokeBorder(selected ? palette.accent : Color.clear, lineWidth: 1))
        }
        .buttonStyle(MekaPressStyle())
    }
}
