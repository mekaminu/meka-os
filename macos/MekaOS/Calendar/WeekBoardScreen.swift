@preconcurrency import MekaKit
import SwiftUI

/// Calendar's Board view on the Mac (Week board and roll-over, slice 2; core `WeekBoardRules`), matching
/// android/.../calendar/WeekBoardView.kt: a column per day from this week's Monday for five weeks — a week fills the
/// window — each day's tasks as cards in his order, the day's events as faded chips above them, an Office · Home · Away
/// marker under the date, and "+ Add" at the foot.
///
/// Drag a card onto another card to take its place, or onto a column's free space to go last; the column under it
/// lifts with a gold edge where it can land (today and later). Click a card to select it: ⌘← ⌘→ move it a day,
/// ⌘↑ ⌘↓ a place; its context menu has the same moves and the next days. ‹ › (⌘[ ⌘]) step a week. Every move puts
/// "Moved “Book MOT” to Thu 22 Oct · Undo" on the bar.
///
/// Slice 3: a card's context menu asks "How long?" (15m · 30m · 1h · 2h, Navilife's guess marked "suggested",
/// nothing set until he picks), the card's line shows it, and each column from today on shows "3h planned · 4h free"
/// over a slim bar, gold as it fills and amber (breathing gently) when the day is over-planned.
///
/// Motion: weeks push across the way you moved; a targeted column lifts (scale 1.01) and its edge blends to gold on the
/// approve spring; cards glide to their new places (replan). Reduce Motion: cross-fades, no lift.
struct WeekBoardScreen: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let board: WeekBoard
    let palette: MekaPalette
    @State private var week = 0
    @State private var direction = 0
    @State private var target: Int64?

    var body: some View {
        let w = board.weeks[min(week, board.weeks.count - 1)]
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(w.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                    if w.title != w.range {
                        Text(w.range).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                }
                Spacer()
                Button { step(-1) } label: { Image(systemName: "chevron.left") }
                    .disabled(week == 0)
                    .keyboardShortcut("[", modifiers: .command)
                    .help("Previous week")
                Button { step(1) } label: { Image(systemName: "chevron.right") }
                    .disabled(week >= board.weeks.count - 1)
                    .keyboardShortcut("]", modifiers: .command)
                    .help("Next week")
            }
            .buttonStyle(.borderless)

            HStack(alignment: .top, spacing: MekaSpace.xs) {
                ForEach(w.days, id: \.epochDay) { d in
                    BoardColumnView(day: d, board: board, palette: palette, targeted: target == d.epochDay) { on in
                        if on { target = d.epochDay } else if target == d.epochDay { target = nil }
                    }
                }
            }
            .id(w.startEpochDay)
            .transition(weekTransition)
            .animation(MekaMotion.replan(reduced: reduceMotion), value: w.days.map { $0.cards.map(\.id) })
        }
        .background(keyboard)
    }

    private func step(_ by: Int) {
        MekaHaptics.tick()
        direction = by
        withAnimation(MekaMotion.replan(reduced: reduceMotion)) { week = max(0, min(board.weeks.count - 1, week + by)) }
    }

    private var weekTransition: AnyTransition {
        if reduceMotion || direction == 0 { return .opacity }
        return .push(from: direction > 0 ? .trailing : .leading).combined(with: .opacity)
    }

    /// ⌘← ⌘→ ⌘↑ ⌘↓ on the selected card (hidden buttons, so the shortcuts show nowhere but work).
    private var keyboard: some View {
        ZStack {
            if let id = model.boardSelectedID {
                Button("") { model.nudgeOnBoard(id, days: -1, places: 0) }.keyboardShortcut(.leftArrow, modifiers: .command)
                Button("") { model.nudgeOnBoard(id, days: 1, places: 0) }.keyboardShortcut(.rightArrow, modifiers: .command)
                Button("") { model.nudgeOnBoard(id, days: 0, places: -1) }.keyboardShortcut(.upArrow, modifiers: .command)
                Button("") { model.nudgeOnBoard(id, days: 0, places: 1) }.keyboardShortcut(.downArrow, modifiers: .command)
            }
        }
        .opacity(0)
        .accessibilityHidden(true)
    }
}

private struct BoardColumnView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let day: BoardDay
    let board: WeekBoard
    let palette: MekaPalette
    let targeted: Bool
    let onTarget: (Bool) -> Void
    @State private var adding = false
    @State private var text = ""

    var body: some View {
        let glow = targeted && day.canDrop
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(day.title).font(MekaType.metaStrong)
                        .foregroundStyle(day.isToday ? palette.accent : palette.textPrimary)
                    if let place = day.placeLabel {
                        Text(place).font(MekaType.micro).foregroundStyle(palette.textTertiary)
                    }
                }
                Text(day.countLine).font(MekaType.micro).foregroundStyle(palette.textTertiary)
                if let capacity = day.capacity {
                    CapacityBar(capacity: capacity, palette: palette)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(day.accessibilityLabel)

            if !day.chips.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    ForEach(day.chips, id: \.id) { chip in
                        Button { model.openEvent = chip.event } label: {
                            HStack(spacing: MekaSpace.xxs) {
                                CalendarDot(tone: chip.tone, palette: palette)
                                Text("\(chip.time) \(chip.title)").font(MekaType.micro)
                                    .foregroundStyle(palette.textSecondary).lineLimit(1)
                            }
                        }
                        .buttonStyle(MekaPressStyle())
                        .opacity(0.7)
                    }
                    if let more = day.moreLabel {
                        Text(more).font(MekaType.micro).foregroundStyle(palette.textTertiary)
                    }
                }
            }

            ForEach(Array(day.cards.enumerated()), id: \.element.id) { i, card in
                BoardCardView(card: card, day: day, index: i, board: board, palette: palette)
            }

            if glow && day.openCards.isEmpty {
                Text(WeekBoardRules.shared.DROP_HERE).font(MekaType.caption).foregroundStyle(palette.accent)
            }
            if day.canDrop { addRow }
            Spacer(minLength: 0)
        }
        .padding(MekaSpace.xs)
        .frame(maxWidth: .infinity, minHeight: 320, alignment: .topLeading)
        .background(
            RoundedRectangle(cornerRadius: MekaRadius.m)
                .fill(glow ? palette.surfaceRaised : (day.isToday ? palette.surface : Color.clear))
        )
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).strokeBorder(palette.accent, lineWidth: glow ? 1.5 : 0))
        .scaleEffect(glow && !reduceMotion ? 1.01 : 1)
        .opacity(day.isPast ? 0.6 : 1)
        .animation(MekaMotion.approve(reduced: reduceMotion), value: glow)
        // A card dropped on the column's free space goes last in the day.
        .dropDestination(for: String.self) { ids, _ in
            guard day.canDrop, let id = ids.first, board.dayOf(taskId: id) != nil else { return false }
            model.moveOnBoard(id, day: day.epochDay, index: Int.max / 2)
            return true
        } isTargeted: { onTarget($0) }
    }

    @ViewBuilder
    private var addRow: some View {
        if adding {
            TextField("New task", text: $text)
                .textFieldStyle(.plain)
                .font(MekaType.body)
                .onSubmit {
                    model.addOnBoard(text, day: day.epochDay)
                    text = ""; adding = false
                }
                .onExitCommand { text = ""; adding = false }
        } else {
            Button { adding = true } label: {
                Text("+ Add").font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            .buttonStyle(MekaPressStyle())
            .help("Add a task on this day")
        }
    }
}

private struct BoardCardView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let card: BoardCard
    let day: BoardDay
    let index: Int
    let board: WeekBoard
    let palette: MekaPalette
    @State private var over = false

    var body: some View {
        let selected = model.boardSelectedID == card.id
        HStack(alignment: .top, spacing: MekaSpace.xs) {
            Button { model.completeOnBoard(card.id, title: card.title) } label: {
                Circle()
                    .strokeBorder(card.done ? palette.accent : palette.textTertiary, lineWidth: 1.5)
                    .background(Circle().fill(card.done ? palette.accent : Color.clear))
                    .frame(width: 14, height: 14)
            }
            .buttonStyle(MekaPressStyle())
            .disabled(card.done)
            .accessibilityLabel(card.done ? "Done" : "Tick off \(card.title)")
            .padding(.top, MekaSpace.xxs)
            VStack(alignment: .leading, spacing: 0) {
                Text(card.title).font(MekaType.body)
                    .foregroundStyle(card.done ? palette.textTertiary : palette.textPrimary)
                    .lineLimit(3)
                let meta = card.meta
                if !meta.isEmpty {
                    Text(meta).font(MekaType.micro)
                        .foregroundStyle(card.carried ? palette.accent : palette.textTertiary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.s).fill(palette.surfaceRaised.opacity(selected || over ? 1 : 0.6)))
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.s).strokeBorder(palette.accent.opacity(selected ? 0.8 : 0), lineWidth: 1))
        .overlay(alignment: .top) {
            // Where a held card would land: a gold line above this one.
            Rectangle().fill(palette.accent).frame(height: 2).opacity(over ? 1 : 0).offset(y: -MekaSpace.xxs - 1)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            guard card.canMove else { return }
            MekaHaptics.tick()
            model.boardSelectedID = selected ? nil : card.id
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: over)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(card.accessibilityLabel)
        .modifier(BoardDrag(card: card, day: day, index: index, board: board, over: $over))
        .contextMenu {
            if card.canMove {
                let at = day.openCards.firstIndex { $0.id == card.id } ?? 0
                Button("Move up") { model.nudgeOnBoard(card.id, days: 0, places: -1) }.disabled(at == 0)
                Button("Move down") { model.nudgeOnBoard(card.id, days: 0, places: 1) }.disabled(at >= day.openCards.count - 1)
                Divider()
                ForEach(Array((0..<7).map { board.todayEpochDay + Int64($0) }.filter { $0 != day.epochDay }), id: \.self) { d in
                    Button(label(d)) { model.moveOnBoard(card.id, day: d, index: Int.max / 2) }
                }
                Divider()
                // Slice 3: how long it takes; Navilife's guess is marked, nothing is set until he picks.
                Section(TaskEstimateRules.shared.MENU_TITLE) {
                    ForEach(card.estimateChoices, id: \.minutes) { c in
                        Button(c.menuLabel) { model.setEstimateOnBoard(card.id, minutes: Int(c.minutes)) }
                            .disabled(c.selected)
                    }
                    if card.estimate != nil {
                        Button(TaskEstimateRules.shared.CLEAR) { model.setEstimateOnBoard(card.id, minutes: nil) }
                    }
                }
            }
        }
    }

    private func label(_ d: Int64) -> String {
        if d == board.todayEpochDay { return "Today" }
        if d == board.todayEpochDay + 1 { return "Tomorrow" }
        guard let day = board.day(epochDay: d) else { return "" }
        return "\(day.weekday) \(day.number)"
    }
}

/// "3h planned · 4h free" with a slim bar under a column's date (slice 3): gold as it fills, amber when over-planned,
/// breathing gently then; the width glides as estimates change. Reduce Motion: steady, no glide.
private struct CapacityBar: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let capacity: BoardCapacity
    let palette: MekaPalette
    @State private var breathe = false

    var body: some View {
        let tone = capacity.over ? palette.calendar3 : palette.accent
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(capacity.line).font(MekaType.micro)
                .foregroundStyle(capacity.over ? palette.calendar3 : palette.textTertiary)
                .lineLimit(1)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.hairline)
                    Capsule().fill(tone)
                        .frame(width: geo.size.width * CGFloat(capacity.fill))
                        .opacity(capacity.over && breathe ? 0.55 : 1)
                }
            }
            .frame(height: 3)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: capacity.fill)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(capacity.spoken)
        .onAppear { startBreathing() }
        .onChange(of: capacity.over) { _, _ in startBreathing() }
    }

    private func startBreathing() {
        guard capacity.over, !reduceMotion else { breathe = false; return }
        withAnimation(.easeInOut(duration: 1.6).repeatForever(autoreverses: true)) { breathe = true }
    }
}

/// An open card can be dragged; any card is a place to drop one (it takes that card's place in the day).
private struct BoardDrag: ViewModifier {
    @Environment(CoreModel.self) private var model
    let card: BoardCard
    let day: BoardDay
    let index: Int
    let board: WeekBoard
    @Binding var over: Bool

    func body(content: Content) -> some View {
        let dropped = content.dropDestination(for: String.self) { ids, _ in
            guard day.canDrop, let id = ids.first, id != card.id, board.dayOf(taskId: id) != nil else { return false }
            // It takes that card's place: before it when moving up or from another day, after it when moving down.
            let place = day.openCards.firstIndex { $0.id == card.id } ?? day.openCards.count
            model.moveOnBoard(id, day: day.epochDay, index: place)
            return true
        } isTargeted: { over = $0 && day.canDrop }
        if card.canMove {
            dropped.draggable(card.id)
        } else {
            dropped
        }
    }
}
