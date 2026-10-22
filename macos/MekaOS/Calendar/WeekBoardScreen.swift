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
/// Slice 4: above the days, the week's lane "No day yet" (tasks parked for the week, off Today until they get a day)
/// and the Someday tray ("Someday · 12", click to open). Drop a card on either to park it or put it on Someday, drag one
/// out onto a day; the context menu has No day this week · Someday. Both lift with a gold edge while targeted.
///
/// Slice 7: across the top, the week's goals (up to three; this week with none picked shows Today's This-week goals),
/// each "2 of 4 done" over a slim gold bar from the week's tasks tagged to it. Drop a card on a goal to tag it, or use
/// the context menu's For a goal; a tagged card says "◎ Run 5k". Right-click a goal for Off this week; "+ Goal" offers
/// open goals or New goal… to type one. Every change has Undo.
///
/// Slice 9: under its events each day from today shows its timeline of free times ("10:00–12:00 · 2h free", taller for
/// longer stretches). Drop a card on one to block it there, where you let go on the quarter hour, moved to that day if
/// it was elsewhere, its block going to the calendar as busy time with Undo; a stretch too short says so. The targeted
/// free time glows gold and says "Drop to block here".
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
    /// By hand, slice 4: the tray open under its chip, and the past days he opened (the rest fold narrow).
    @State private var openTray: BoardTray?
    @State private var openedPast: Set<Int64> = []

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
                // Item (8): from Friday, on this week, Review the week opens Review with the reflection open.
                if board.offersReview && week == 0 {
                    Button(BoardReviewRules.shared.REVIEW) { MekaHaptics.tick(); model.openReflectionFromTalk() }
                        .foregroundStyle(palette.textSecondary).font(MekaType.itemMeta)
                        .help("Review this week: planned against done, and what kept slipping")
                }
                // Item (6): Plan your week opens the guided sheet.
                Button(PlanWeekRules.shared.OPEN) { MekaHaptics.tick(); model.showPlanWeek = true }
                    .foregroundStyle(palette.accent).font(MekaType.itemMeta)
                    .keyboardShortcut("p", modifiers: [.command, .shift])
                    .help("Plan your week (⇧⌘P)")
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

            // By hand, slice 4: the week's goals as small gold pills and the two trays as chips on one row; an open
            // tray's cards in a lane under them.
            let chips = BoardTidyRules.shared.trayChips(week: w, board: board, open: openTray)
            HStack(alignment: .center, spacing: MekaSpace.xs) {
                BoardGoalsView(week: w, palette: palette, target: $target)
                    .animation(MekaMotion.replan(reduced: reduceMotion), value: w.goals.map(\.goalId))
                ForEach(chips, id: \.label) { chip in
                    let key = chip.tray == BoardTray.noDay ? laneKey : trayKey
                    BoardTrayChip(chip: chip, week: w, board: board, palette: palette, isOpen: openTray == chip.tray, targeted: target == key) {
                        MekaHaptics.tick()
                        withAnimation(MekaMotion.appear(reduced: reduceMotion)) { openTray = BoardTidyRules.shared.toggle(open: openTray, tapped: chip) }
                    } onTarget: { on in
                        if on { target = key } else if target == key { target = nil }
                    }
                }
            }
            if let tray = openTray {
                BoardTrayLane(tray: tray, week: w, board: board, palette: palette, targeted: target == laneKey || target == trayKey) { on in
                    let key = tray == BoardTray.noDay ? laneKey : trayKey
                    if on { target = key } else if target == key { target = nil }
                }
                .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                .animation(MekaMotion.replan(reduced: reduceMotion), value: w.lane.map(\.id))
                .animation(MekaMotion.replan(reduced: reduceMotion), value: board.someday.map(\.id))
            }

            HStack(alignment: .top, spacing: MekaSpace.xs) {
                ForEach(w.days, id: \.epochDay) { d in
                    if d.isPast && !openedPast.contains(d.epochDay) {
                        BoardPastDayView(day: d, palette: palette) {
                            MekaHaptics.tick()
                            withAnimation(MekaMotion.replan(reduced: reduceMotion)) { _ = openedPast.insert(d.epochDay) }
                        }
                    } else {
                        BoardColumnView(day: d, board: board, palette: palette, targeted: target == d.epochDay, onFold: foldAction(d)) { on in
                            if on { target = d.epochDay } else if target == d.epochDay { target = nil }
                        }
                        .frame(maxWidth: CGFloat(BoardTidyRules.shared.MAX_LANE_DP))
                    }
                }
            }
            .id(w.startEpochDay)
            .transition(weekTransition)
            .animation(MekaMotion.replan(reduced: reduceMotion), value: w.days.map { $0.cards.map(\.id) })
        }
        .background(keyboard)
        // An open tray closes once its last card has gone (never an empty box).
        .onChange(of: trayCounts) {
            let w = board.weeks[min(week, board.weeks.count - 1)]
            openTray = BoardTidyRules.shared.stillOpen(open: openTray, chips: BoardTidyRules.shared.trayChips(week: w, board: board, open: openTray))
        }
    }

    /// The visible week's No day yet count and Someday's, so an open tray closes once it empties.
    private var trayCounts: [Int] {
        let w = board.weeks[min(week, board.weeks.count - 1)]
        return [w.lane.count, board.someday.count, week]
    }

    /// By hand, slice 4: an opened past day folds narrow again; other days have nothing to fold.
    private func foldAction(_ d: BoardDay) -> (() -> Void)? {
        guard d.isPast else { return nil }
        let epoch = d.epochDay
        return {
            MekaHaptics.tick()
            withAnimation(MekaMotion.replan(reduced: reduceMotion)) { _ = openedPast.remove(epoch) }
        }
    }

    /// Drop-target keys besides a day's epoch day.
    private let laneKey: Int64 = -1
    private let trayKey: Int64 = -2

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

/// Week board item (10): a stacking-up day's amber line and its fixes as accent buttons (press style, light haptic, Undo).
private struct PressureNote: View {
    @Environment(CoreModel.self) private var model
    let pressure: DayPressure
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(pressure.short).font(MekaType.micro).foregroundStyle(palette.calendar3).lineLimit(3)
                .accessibilityLabel(pressure.spoken)
            ForEach(pressure.fixes, id: \.label) { fix in
                Button { model.easePressure(taskId: fix.taskId, toDay: fix.toDay) } label: {
                    Text(fix.label).font(MekaType.micro).foregroundStyle(palette.accent).lineLimit(2)
                }
                .buttonStyle(MekaPressStyle())
            }
        }
        .contentTransition(.opacity)
    }
}

private struct BoardColumnView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let day: BoardDay
    let board: WeekBoard
    let palette: MekaPalette
    let targeted: Bool
    /// By hand, slice 4: an opened past day folds narrow again with a click on its header.
    var onFold: (() -> Void)? = nil
    let onTarget: (Bool) -> Void
    /// By hand, slice 8 (the quality gate's findings): how wide the lane is drawn, so a narrow one fits its words.
    @State private var laneWidth: CGFloat = CGFloat(BoardTidyRules.shared.MAX_LANE_DP)

    var body: some View {
        let glow = targeted && day.canDrop
        let compact = BoardFitRules.shared.compact(laneDp: Float(laneWidth))
        let tagBelow = BoardFitRules.shared.tagBelow(compact: compact)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            // By hand, slice 2 (the look): a big day number over its weekday, a small tag, today underlined in gold,
            // "2 of 5 done" over a thin gold bar (a past day only what was done), then the day's capacity.
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Text(day.number).font(MekaType.nowTitle)
                        .foregroundStyle(day.isToday ? palette.accent : palette.textPrimary)
                    Text(day.weekday).font(MekaType.metaStrong)
                        .foregroundStyle(day.isToday ? palette.accent : palette.textSecondary)
                    Spacer(minLength: 0)
                    if !tagBelow, let tag = BoardLookRules.shared.tag(day: day) {
                        Text(tag).font(MekaType.micro).foregroundStyle(palette.textTertiary).lineLimit(1)
                    }
                }
                if tagBelow, let tag = BoardLookRules.shared.tag(day: day) {
                    Text(tag).font(MekaType.micro).foregroundStyle(palette.textTertiary).lineLimit(1)
                }
                if day.isToday {
                    Capsule().fill(palette.accent).frame(width: MekaSpace.l, height: 2)
                }
                if day.isPast {
                    Text(BoardLookRules.shared.pastLine(day: day)).font(MekaType.micro).foregroundStyle(palette.textTertiary)
                } else if let progress = BoardLookRules.shared.progress(day: day) {
                    DayProgressBar(progress: progress, palette: palette)
                }
                if let capacity = day.capacity {
                    CapacityBar(capacity: capacity, palette: palette, compact: compact)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(day.accessibilityLabel + (onFold != nil ? ". Click to fold it" : ""))
            .contentShape(Rectangle())
            .onTapGesture { onFold?() }

            // Item (10): "Stacking up: Trip form, Council tax and Gym" in amber, with its one-tap fixes.
            if let pressure = day.pressure {
                PressureNote(pressure: pressure, palette: palette)
            }

            // By hand, slice 1: "+ Add a task" at the top of every day from today, always there.
            if day.canDrop { BoardAddRow(day: day, palette: palette) }

            if !day.chips.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    // Slice 2: events as slim one-line chips, "09:30 · Dentist", a dot in the calendar's colour.
                    ForEach(day.chips, id: \.id) { chip in
                        Button { model.openEvent = chip.event } label: {
                            HStack(spacing: MekaSpace.xxs) {
                                CalendarDot(tone: chip.tone, palette: palette)
                                Text(chip.time).font(MekaType.micro).foregroundStyle(palette.accent).lineLimit(1)
                                Text(chip.title).font(MekaType.micro).foregroundStyle(palette.textSecondary).lineLimit(1)
                                Spacer(minLength: 0)
                            }
                            .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                            .background(Capsule().fill(palette.surface))
                        }
                        .buttonStyle(MekaPressStyle())
                        .accessibilityLabel(BoardLookRules.shared.eventLine(chip: chip))
                    }
                    if let more = day.moreLabel {
                        Text(more).font(MekaType.micro).foregroundStyle(palette.textTertiary)
                    }
                }
            }

            // Slice 2: free time as quiet lines, never boxes; each is still a drop target that lights up gold.
            if !day.freeTimes.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(day.freeTimes, id: \.startMs) { free in
                        FreeTimeView(free: free, day: day, board: board, palette: palette, compact: compact)
                    }
                }
            }

            ForEach(Array(day.cards.enumerated()), id: \.element.id) { i, card in
                BoardCardView(card: card, spot: .day(day), board: board, palette: palette, compact: compact)
            }

            if glow && day.openCards.isEmpty {
                Text(WeekBoardRules.shared.DROP_HERE).font(MekaType.caption).foregroundStyle(palette.accent)
            }
            Spacer(minLength: 0)
        }
        .padding(MekaSpace.xs)
        .frame(maxWidth: .infinity, minHeight: 320, alignment: .topLeading)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { laneWidth = $0 }
        .background(
            RoundedRectangle(cornerRadius: MekaRadius.m)
                .fill(glow ? palette.surfaceRaised : (day.isToday ? palette.surface : Color.clear))
        )
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).strokeBorder(palette.accent, lineWidth: glow ? 1.5 : 0))
        // Item (10): a day stacking up wears an amber edge (the gold landing edge covers it while a card is held over).
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.m)
            .strokeBorder(palette.calendar3.opacity(0.7), lineWidth: day.pressure != nil && !glow ? 1 : 0))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: day.pressure?.short)
        .scaleEffect(glow && !reduceMotion ? 1.01 : 1)
        .opacity(day.isPast ? 0.6 : 1)
        .animation(MekaMotion.approve(reduced: reduceMotion), value: glow)
        // A card dropped on the column's free space goes last in the day.
        .dropDestination(for: String.self) { ids, _ in
            guard day.canDrop, let id = ids.first, board.find(taskId: id) != nil else { return false }
            model.moveOnBoard(id, day: day.epochDay, index: Int.max / 2)
            return true
        } isTargeted: { onTarget($0) }
    }
}

/// "+ Add a task" at the top of a day (by hand, slice 1), always there as a dashed row. A click opens a field focused at
/// once; Return adds the task last in the day and keeps the field focused for the next one; the chips under it (15m ·
/// 30m · 1h · 2h) give the next one an estimate; Esc, or clicking away, closes it (adding what was typed). The field
/// opens on the expand spring (Reduce Motion: it fades).
private struct BoardAddRow: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let day: BoardDay
    let palette: MekaPalette
    @State private var open = false
    @State private var text = ""
    @State private var picked: Int?
    @FocusState private var focused: Bool

    var body: some View {
        Group {
            if open {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    TextField(BoardHandRules.shared.ADD_HINT, text: $text)
                        .textFieldStyle(.plain)
                        .font(MekaType.body)
                        .focused($focused)
                        .onSubmit {
                            if text.trimmingCharacters(in: .whitespaces).isEmpty { close(); return }
                            add()
                            focused = true
                        }
                        .onExitCommand { text = ""; close() }
                        .accessibilityLabel(BoardHandRules.shared.addLabel(dayTitle: day.title))
                    HStack(spacing: MekaSpace.xxs) {
                        ForEach(BoardHandRules.shared.estimateChips(picked: picked.map { KotlinInt(int: Int32($0)) }), id: \.minutes) { chip in
                            Button {
                                MekaHaptics.tick()
                                let next = BoardHandRules.shared.toggleEstimate(
                                    picked: picked.map { KotlinInt(int: Int32($0)) }, tapped: chip.minutes
                                )
                                picked = next.map { Int($0.int32Value) }
                                focused = true
                            } label: {
                                Text(chip.label).font(MekaType.micro)
                                    .foregroundStyle(chip.picked ? palette.background : palette.textSecondary)
                                    .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                                    .background(Capsule().fill(chip.picked ? palette.accent : palette.surface))
                            }
                            .buttonStyle(MekaPressStyle())
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: chip.picked)
                        }
                    }
                }
                .padding(MekaSpace.xs)
                .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).strokeBorder(palette.accent, lineWidth: 1))
                .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                .onAppear { focused = true }
                .onChange(of: focused) { _, now in
                    // Clicking away closes it; a chip's click hands focus straight back, so wait a beat first.
                    guard !now, open else { return }
                    Task { @MainActor in
                        try? await Task.sleep(for: .milliseconds(150))
                        if !focused && open { add(); close() }
                    }
                }
            } else {
                Button {
                    MekaHaptics.tick()
                    withAnimation(MekaMotion.appear(reduced: reduceMotion)) { open = true }
                } label: {
                    Text(BoardHandRules.shared.ADD).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .frame(maxWidth: .infinity, minHeight: 32, alignment: .leading)
                        .padding(.horizontal, MekaSpace.m)
                        .contentShape(Rectangle())
                        .overlay(
                            RoundedRectangle(cornerRadius: MekaRadius.m)
                                .strokeBorder(palette.hairline, style: StrokeStyle(lineWidth: 1, dash: [6, 4]))
                        )
                }
                .buttonStyle(MekaPressStyle())
                .help("Add a task on this day")
                .accessibilityLabel(BoardHandRules.shared.addLabel(dayTitle: day.title))
            }
        }
    }

    private func add() {
        let words = text
        let minutes = picked
        text = ""; picked = nil
        model.addOnBoard(words, day: day.epochDay, minutes: minutes)
    }

    private func close() {
        withAnimation(MekaMotion.appear(reduced: reduceMotion)) { open = false }
    }
}

/// Slice 9: one free stretch in a day's timeline, taller for a longer stretch. A card dropped on it is blocked there
/// (where it was let go, as a fraction of the height). Targeted: a gold edge and tint, "Drop to block here".
private struct FreeTimeView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let free: BoardFreeTime
    let day: BoardDay
    let board: WeekBoard
    let palette: MekaPalette
    /// Slice 8: a narrow lane says only when ("19:00–21:00 free"); the length is on the capacity line.
    var compact: Bool = false
    @State private var targeted = false
    @State private var height: CGFloat = 24

    var body: some View {
        Text(targeted ? "Drop to block here" : (compact ? "\(free.label) free" : "\(free.label) · \(free.lengthLabel)"))
            .font(MekaType.micro)
            .foregroundStyle(targeted ? palette.accent : palette.textTertiary)
            .lineLimit(1)
            .padding(.horizontal, MekaSpace.xs)
            .padding(.vertical, MekaSpace.xxs)
            .frame(maxWidth: .infinity, minHeight: targeted ? 24 + 40 * CGFloat(free.weight) : 20, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: MekaRadius.s).fill(targeted ? palette.accent.opacity(0.14) : Color.clear))
            .overlay(RoundedRectangle(cornerRadius: MekaRadius.s).strokeBorder(palette.accent, lineWidth: targeted ? 1 : 0))
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
            .animation(MekaMotion.appear(reduced: reduceMotion), value: targeted)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(free.spoken)
            .dropDestination(for: String.self) { ids, location in
                guard let id = ids.first, board.find(taskId: id) != nil else { return false }
                let fraction = Float(max(0, min(1, location.y / max(height, 1))))
                model.dropOnFreeTime(id, day: day.epochDay, start: free.startMs, fraction: fraction)
                return true
            } isTargeted: { targeted = $0 }
    }
}

/// Where a card sits on the Board (slice 4): a day's column, a week's lane or the Someday tray.
private enum CardSpot {
    case day(BoardDay)
    case lane(BoardWeek)
    case someday

    /// The cards it moves up and down among (a day's open cards; none elsewhere).
    var siblings: [BoardCard] {
        if case .day(let d) = self { return d.openCards }
        return []
    }

    var dayEpoch: Int64? {
        if case .day(let d) = self { return d.epochDay }
        return nil
    }
}

private struct BoardCardView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let card: BoardCard
    let spot: CardSpot
    let board: WeekBoard
    let palette: MekaPalette
    /// Slice 8: on a narrow lane the title takes the design's card size (item meta, 12 pt) and up to three lines rather than be cut.
    var compact: Bool = false
    @State private var over = false
    /// Slice 6: Block time for a day's card, read as the pointer reaches it (the menu can't wait for the core).
    @State private var offer: BlockOffer?

    var body: some View {
        let selected = model.boardSelectedID == card.id
        HStack(alignment: .top, spacing: MekaSpace.xs) {
            BoardTick(done: card.done, palette: palette) {
                if card.done { model.reopenOnBoard(card.id, title: card.title) } else { model.completeOnBoard(card.id, title: card.title) }
            }
            .accessibilityLabel(BoardHandRules.shared.tickLabel(title: card.title, done: card.done))
            VStack(alignment: .leading, spacing: 0) {
                Text(card.title).font(compact ? MekaType.itemMeta : MekaType.body)
                    .foregroundStyle(card.done ? palette.textTertiary : palette.textPrimary)
                    .strikethrough(card.done, color: palette.textTertiary)
                    .lineLimit(Int(BoardFitRules.shared.titleLines(compact: compact)))
                // Slice 2: small chips under the title (time, estimate, carried in amber, repeat, goal in gold).
                let chips = BoardLookRules.shared.chips(card: card)
                if !chips.isEmpty {
                    HStack(spacing: MekaSpace.xxs) {
                        ForEach(Array(chips.enumerated()), id: \.offset) { _, chip in
                            CardChipPill(chip: chip, palette: palette)
                        }
                    }
                    .padding(.top, MekaSpace.xxs)
                }
            }
            Spacer(minLength: 0)
            // Slice 5: a small colour dot for where it came from (mail, Needs you, Discord, School).
            if let source = card.source {
                Circle()
                    .fill(palette.calendarTone(source.tone).opacity(card.done ? 0.4 : 1))
                    .frame(width: 6, height: 6)
                    .padding(.top, MekaSpace.xs)
                    .help(source.label)
            }
        }
        .padding(MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        // Slice 2: a raised tile; a done one sinks to the lane's colour.
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(card.done ? palette.surface : palette.surfaceRaised))
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).strokeBorder(palette.accent.opacity(selected ? 0.8 : (over ? 0.4 : 0)), lineWidth: 1))
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
        .modifier(BoardDrag(card: card, spot: spot, board: board, over: $over))
        .onHover { inside in
            guard inside, card.canMove, spot.dayEpoch != nil else { return }
            let id = card.id
            Task { offer = await model.blockOffer(id) }
        }
        .contextMenu {
            if card.canMove {
                let siblings = spot.siblings
                if !siblings.isEmpty {
                    let at = siblings.firstIndex { $0.id == card.id } ?? 0
                    Button("Move up") { model.nudgeOnBoard(card.id, days: 0, places: -1) }.disabled(at == 0)
                    Button("Move down") { model.nudgeOnBoard(card.id, days: 0, places: 1) }.disabled(at >= siblings.count - 1)
                    Divider()
                }
                ForEach(Array((0..<7).map { board.todayEpochDay + Int64($0) }.filter { $0 != spot.dayEpoch }), id: \.self) { d in
                    Button(label(d)) { model.moveOnBoard(card.id, day: d, index: Int.max / 2) }
                }
                // Slice 4: no day yet this week (a repeat belongs to its days), or Someday.
                if !card.task.isRepeating {
                    Divider()
                    if let thisWeek = board.weeks.first?.startEpochDay, !isLane(thisWeek) {
                        Button(WeekBoardRules.shared.TO_WEEK) { model.parkOnBoard(card.id, week: thisWeek, index: Int.max / 2) }
                    }
                    if !isSomeday {
                        Button(WeekBoardRules.shared.TO_SOMEDAY) { model.somedayOnBoard(card.id) }
                    }
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
                // Slice 7: For a goal — the week's goals (the one it has ticked), or No goal.
                let tags = BoardGoalRules.shared.tagChoices(board: board, taskId: card.id)
                if !tags.isEmpty {
                    Divider()
                    Section(BoardGoalRules.shared.MENU_TITLE) {
                        ForEach(Array(tags.enumerated()), id: \.offset) { _, t in
                            Button(t.label) { model.tagOnBoard(card.id, goal: t.goalId) }
                                .disabled(t.selected)
                        }
                    }
                }
                // Slice 6: Block time — a free start in the day; his click adds the block to his calendar (Undo on the bar).
                if let o = offer, spot.dayEpoch != nil {
                    Divider()
                    Section(o.heading) {
                        if o.canBlock {
                            ForEach(o.slots, id: \.startMs) { slot in
                                Button(slot.label) { model.blockOnBoard(card.id, start: slot.startMs) }
                            }
                        }
                        Text(o.line)
                    }
                }
            }
        }
    }

    private func isLane(_ week: Int64) -> Bool {
        if case .lane(let w) = spot { return w.startEpochDay == week }
        return false
    }

    private var isSomeday: Bool {
        if case .someday = spot { return true }
        return false
    }

    private func label(_ d: Int64) -> String {
        if d == board.todayEpochDay { return "Today" }
        if d == board.todayEpochDay + 1 { return "Tomorrow" }
        guard let day = board.day(epochDay: d) else { return "" }
        return "\(day.weekday) \(day.number)"
    }
}

/// The tick on a card (by hand, slice 1): a 20 pt circle in its own 28 pt target, separate from the card's click. A click
/// fills it gold with a tick and a spark ring that grows and fades (0.6 s); clicked again it empties. Reduce Motion: the
/// fill cross-fades, no spark.
private struct BoardTick: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let done: Bool
    let palette: MekaPalette
    let action: () -> Void
    @State private var spark = false

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().stroke(palette.accent, lineWidth: 2)
                    .frame(width: 20, height: 20)
                    .scaleEffect(spark ? 2.4 : 0.4)
                    .opacity(spark ? 0 : (done && !reduceMotion ? 1 : 0))
                Circle()
                    .strokeBorder(done ? palette.accent : palette.textTertiary, lineWidth: 1.5)
                    .background(Circle().fill(done ? palette.accent : Color.clear))
                    .frame(width: 20, height: 20)
                if done {
                    Image(systemName: "checkmark").font(MekaType.metaStrong).foregroundStyle(palette.background)
                }
            }
            .frame(width: 28, height: 28)
            .contentShape(Circle())
        }
        .buttonStyle(MekaPressStyle())
        .animation(MekaMotion.appear(reduced: reduceMotion), value: done)
        .onChange(of: done) { _, now in
            guard now, !reduceMotion else { spark = false; return }
            spark = false
            withAnimation(.easeOut(duration: 0.6)) { spark = true }
        }
    }
}

/// One small chip under a card's title (slice 2): gold for its estimate and goal, amber when carried, quiet otherwise.
private struct CardChipPill: View {
    let chip: CardChip
    let palette: MekaPalette

    var body: some View {
        let ink: Color
        let fill: Color
        switch chip.kind {
        case .carried: ink = palette.calendar3; fill = palette.calendar3.opacity(0.14)
        case .estimate, .goal: ink = palette.accent; fill = palette.accent.opacity(0.12)
        default: ink = palette.textSecondary; fill = palette.surface
        }
        return Text(chip.label).font(MekaType.micro).foregroundStyle(ink).lineLimit(1)
            .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
            .background(Capsule().fill(fill))
    }
}

/// "2 of 5 done" under a day's header with a thin bar that fills gold as cards are ticked (slice 2); the width glides
/// (Reduce Motion: it steps).
private struct DayProgressBar: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let progress: DayProgress
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(progress.line).font(MekaType.micro)
                .foregroundStyle(progress.complete ? palette.accent : palette.textTertiary)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.hairline)
                    Capsule().fill(palette.accent).frame(width: geo.size.width * CGFloat(progress.fill))
                }
            }
            .frame(height: 2)
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: progress.fill)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(progress.line)
    }
}

/// "3h planned · 4h free" with a slim bar under a column's date (slice 3): gold as it fills, amber when over-planned,
/// breathing gently then; the width glides as estimates change. Reduce Motion: steady, no glide.
private struct CapacityBar: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let capacity: BoardCapacity
    let palette: MekaPalette
    /// Slice 8: "1h 30m of 2h" (planned of free) on a narrow lane.
    var compact: Bool = false
    @State private var breathe = false

    var body: some View {
        let tone = capacity.over ? palette.calendar3 : palette.accent
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(BoardFitRules.shared.capacityLine(c: capacity, compact: compact)).font(MekaType.micro)
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

/// An open card can be dragged; any card is a place to drop one (it takes that card's place in the day or the lane; on
/// the Someday tray it goes to Someday).
private struct BoardDrag: ViewModifier {
    @Environment(CoreModel.self) private var model
    let card: BoardCard
    let spot: CardSpot
    let board: WeekBoard
    @Binding var over: Bool

    private var canDrop: Bool {
        if case .day(let d) = spot { return d.canDrop }
        return true
    }

    func body(content: Content) -> some View {
        let dropped = content.dropDestination(for: String.self) { ids, _ in
            guard canDrop, let id = ids.first, id != card.id, board.find(taskId: id) != nil else { return false }
            switch spot {
            case .day(let day):
                // It takes that card's place: before it when moving up or from another day, after it when moving down.
                let place = day.openCards.firstIndex { $0.id == card.id } ?? day.openCards.count
                model.moveOnBoard(id, day: day.epochDay, index: place)
            case .lane(let week):
                let place = week.lane.firstIndex { $0.id == card.id } ?? week.lane.count
                model.parkOnBoard(id, week: week.startEpochDay, index: place)
            case .someday:
                model.somedayOnBoard(id)
            }
            return true
        } isTargeted: { over = $0 && canDrop }
        if card.canMove {
            dropped.draggable(card.id)
        } else {
            dropped
        }
    }
}

/// By hand, slice 4: "No day yet · 3" or "Someday · 12" as a small chip; click one with cards to open it (one at a time,
/// an empty one never opens). Each is a drop target that lifts with a gold edge: a card dropped on No day yet is parked
/// in the week, on Someday it goes to Someday.
private struct BoardTrayChip: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let chip: TrayChip
    let week: BoardWeek
    let board: WeekBoard
    let palette: MekaPalette
    let isOpen: Bool
    let targeted: Bool
    let onTap: () -> Void
    let onTarget: (Bool) -> Void

    var body: some View {
        let lit = targeted || isOpen
        Button(action: onTap) {
            HStack(spacing: MekaSpace.xxs) {
                Text(chip.label).font(MekaType.caption)
                    .foregroundStyle(isOpen ? palette.accent : (chip.canOpen ? palette.textPrimary : palette.textTertiary))
                    .lineLimit(1)
                if chip.canOpen {
                    Image(systemName: isOpen ? "chevron.up" : "chevron.down").font(MekaType.micro).foregroundStyle(palette.textTertiary)
                }
            }
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xxs)
            .frame(minHeight: 28)
            .background(Capsule().fill(lit ? palette.surfaceRaised : palette.surface))
            .overlay(Capsule().strokeBorder(lit ? palette.accent : palette.hairline, lineWidth: targeted ? 1.5 : 1))
        }
        .buttonStyle(MekaPressStyle())
        // Never disabled: an empty chip is still a place to drop a card (a click on it simply does nothing).
        .fixedSize()
        .scaleEffect(targeted && !reduceMotion ? CGFloat(BoardFoldRules.shared.PILL_SWELL) : 1)
        .animation(MekaMotion.approve(reduced: reduceMotion), value: targeted)
        .accessibilityLabel(chip.accessibilityLabel)
        .dropDestination(for: String.self) { ids, _ in drop(ids) } isTargeted: { onTarget($0) }
    }

    private func drop(_ ids: [String]) -> Bool {
        guard let id = ids.first else { return false }
        if chip.tray == BoardTray.noDay {
            guard board.find(taskId: id) != nil else { return false }
            model.parkOnBoard(id, week: week.startEpochDay, index: Int.max / 2)
        } else {
            guard WeekBoardRules.shared.canSomeday(board: board, taskId: id) else { return false }
            model.somedayOnBoard(id)
        }
        return true
    }
}

/// By hand, slice 4: an open tray's cards side by side under the chips (No day yet in his order, Someday newest first);
/// they drag out onto a day and have the same context menu as a day's. Also a drop target, like its chip.
private struct BoardTrayLane: View {
    @Environment(CoreModel.self) private var model
    let tray: BoardTray
    let week: BoardWeek
    let board: WeekBoard
    let palette: MekaPalette
    let targeted: Bool
    let onTarget: (Bool) -> Void

    var body: some View {
        let someday = tray == BoardTray.someday
        let cards = someday ? board.someday : week.lane
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: MekaSpace.xs) {
                ForEach(cards, id: \.id) { card in
                    BoardCardView(card: card, spot: someday ? .someday : .lane(week), board: board, palette: palette)
                        .frame(width: 200)
                }
            }
        }
        .scrollIndicators(.never)
        .modifier(BoardZone(palette: palette, targeted: targeted))
        .dropDestination(for: String.self) { ids, _ in
            guard let id = ids.first else { return false }
            if someday {
                guard WeekBoardRules.shared.canSomeday(board: board, taskId: id) else { return false }
                model.somedayOnBoard(id)
            } else {
                guard board.find(taskId: id) != nil else { return false }
                model.parkOnBoard(id, week: week.startEpochDay, index: Int.max / 2)
            }
            return true
        } isTargeted: { onTarget($0) }
    }
}

/// By hand, slice 4: a past day folded narrow: its big number over the weekday and "3 done" (or "Nothing done"); click
/// it to open the day.
private struct BoardPastDayView: View {
    let day: BoardDay
    let palette: MekaPalette
    let onOpen: () -> Void

    var body: some View {
        let done = day.cards.filter { $0.done }.count
        Button(action: onOpen) {
            VStack(spacing: MekaSpace.xxs) {
                Text(day.number).font(MekaType.nowTitle).foregroundStyle(palette.textSecondary)
                Text(day.weekday).font(MekaType.micro).foregroundStyle(palette.textTertiary)
                Spacer().frame(height: MekaSpace.xs)
                if done == 0 {
                    Text("Nothing").font(MekaType.micro).foregroundStyle(palette.textTertiary)
                } else {
                    Text("\(done)").font(MekaType.metaStrong).foregroundStyle(palette.accent)
                }
                Text("done").font(MekaType.micro).foregroundStyle(palette.textTertiary)
                Spacer(minLength: 0)
            }
            .padding(.vertical, MekaSpace.m)
            .frame(width: CGFloat(BoardTidyRules.shared.PAST_DP))
            .frame(minHeight: 320)
            .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).strokeBorder(palette.hairline, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .help("Open this day")
        .accessibilityLabel(BoardTidyRules.shared.collapsedLabel(day: day))
    }
}

/// The lane's and the tray's frame: raised with a gold edge and a slight lift while a card is over it.
private struct BoardZone: ViewModifier {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    let targeted: Bool

    func body(content: Content) -> some View {
        content
            .padding(MekaSpace.xs)
            .frame(maxWidth: .infinity, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(targeted ? palette.surfaceRaised : palette.surface))
            .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).strokeBorder(targeted ? palette.accent : palette.hairline, lineWidth: targeted ? 1.5 : 1))
            .scaleEffect(targeted && !reduceMotion ? 1.01 : 1)
            .animation(MekaMotion.approve(reduced: reduceMotion), value: targeted)
    }
}

/// The week's goals across the top of the Board (slice 7): up to three, each its title, "2 of 4 done" and a slim bar
/// that fills gold as its tasks are done. A card dropped on a goal is tagged to it (the goal lifts with a gold edge
/// while targeted); right-click a goal for Off this week; "+ Goal" offers open goals and New goal…. Reduce Motion:
/// cross-fades, no lift.
private struct BoardGoalsView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let week: BoardWeek
    let palette: MekaPalette
    @Binding var target: Int64?
    @State private var offers: [GoalPickChoice] = []
    @State private var typing = false
    @State private var text = ""

    var body: some View {
        HStack(alignment: .center, spacing: MekaSpace.xs) {
            ForEach(Array(week.goals.enumerated()), id: \.element.goalId) { i, g in
                chip(g, key: goalKey - Int64(i))
            }
            if week.canAddGoal { add }
            Spacer(minLength: 0)
        }
    }

    /// Drop-target keys for the goals: -10, -11, -12.
    private let goalKey: Int64 = -10

    /// By hand, slice 4: a goal as a small gold pill ("◎ Run 5k  2/4") whose background fills gold as its tasks are done.
    private func chip(_ g: BoardGoal, key: Int64) -> some View {
        let lit = target == key && !g.finished
        let pill = BoardTidyRules.shared.goalPill(g: g)
        return HStack(spacing: MekaSpace.xs) {
            Text(pill.label).font(MekaType.caption).foregroundStyle(palette.accent).lineLimit(1)
            if let count = pill.count {
                Text(count).font(MekaType.micro).foregroundStyle(pill.finished ? palette.accent : palette.textSecondary)
            }
        }
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xxs)
        .frame(minHeight: 28)
        .frame(maxWidth: 240)
        .background(
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(lit ? palette.surfaceRaised : palette.surface)
                    Rectangle().fill(palette.accent.opacity(0.18)).frame(width: geo.size.width * CGFloat(pill.fill))
                }
                .clipShape(Capsule())
            }
        )
        .overlay(Capsule().strokeBorder(lit ? palette.accent : palette.accent.opacity(0.45), lineWidth: lit ? 1.5 : 1))
        .fixedSize()
        .animation(MekaMotion.appear(reduced: reduceMotion), value: pill.fill)
        .scaleEffect(lit && !reduceMotion ? CGFloat(BoardFoldRules.shared.PILL_SWELL) : 1)
        .animation(MekaMotion.approve(reduced: reduceMotion), value: lit)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(g.accessibilityLabel)
        .contextMenu {
            if g.picked && !g.finished {
                Button(BoardGoalRules.shared.OFF_WEEK) { model.unpickBoardGoal(g.goalId, week: week.startEpochDay) }
            }
        }
        .dropDestination(for: String.self) { ids, _ in
            guard !g.finished, let id = ids.first else { return false }
            model.tagOnBoard(id, goal: g.goalId)
            return true
        } isTargeted: { on in
            if on { target = key } else if target == key { target = nil }
        }
    }

    @ViewBuilder
    private var add: some View {
        if typing {
            TextField("New goal", text: $text)
                .textFieldStyle(.plain)
                .font(MekaType.body)
                .frame(width: 200)
                .onSubmit {
                    model.addBoardGoal(text, week: week.startEpochDay)
                    text = ""; typing = false
                }
                .onExitCommand { text = ""; typing = false }
        } else {
            Menu {
                ForEach(offers, id: \.goalId) { o in
                    Button("\(o.title) · \(o.horizon)") { model.pickBoardGoal(o.goalId, week: week.startEpochDay) }
                }
                if !offers.isEmpty { Divider() }
                Button(BoardGoalRules.shared.NEW_GOAL) { typing = true }
            } label: {
                HStack(spacing: MekaSpace.xs) {
                    Text(BoardGoalRules.shared.ADD).font(MekaType.caption).foregroundStyle(palette.accent)
                    if week.goals.isEmpty {
                        Text(BoardGoalRules.shared.EMPTY).font(MekaType.micro).foregroundStyle(palette.textTertiary)
                    }
                }
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
            .help("Pick a goal for this week")
            .onHover { inside in
                guard inside else { return }
                let ws = week.startEpochDay
                Task { offers = await model.boardGoalChoices(ws) }
            }
            .task(id: week.startEpochDay) { [ws = week.startEpochDay] in
                offers = await model.boardGoalChoices(ws)
            }
        }
    }
}
