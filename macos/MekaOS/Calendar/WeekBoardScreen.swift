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
/// Option B slice 2 (the layout): Monday to Friday as five columns across, Saturday and Sunday as two shorter columns
/// underneath, then No day yet and Someday as always-open lists (drop a card on either, drag one out onto a day); the
/// tray chips and their lane are gone from the Mac (the cover screen of the Fold keeps them).
///
/// Motion: weeks push across the way you moved; a targeted column lifts (scale 1.01) and its edge blends to gold on the
/// approve spring; cards glide to their new places (replan). Reduce Motion: cross-fades, no lift.
struct WeekBoardScreen: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let board: WeekBoard
    let palette: MekaPalette
    @State private var week = 0
    /// Option B match: the week chosen once as the board first appears (next week on a weekend).
    @State private var opened = false
    @State private var direction = 0
    @State private var target: Int64?
    /// By hand, slice 4: the past days he opened (the rest fold narrow).
    @State private var openedPast: Set<Int64> = []
    /// Option B slice 5 (refinement 2): today's timeline beside the week; open unless he hides it.
    @AppStorage("board.timeline") private var showTimeline = true

    /// Option B match: "October 2026  W44", the month in serif, the ISO week in mono.
    @ViewBuilder private func weekTitle(_ w: BoardWeek) -> some View {
        let head = BoardMatchRules.shared.weekHead(startEpochDay: w.startEpochDay)
        HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
            Text(head.month).font(MekaType.greeting).fontDesign(.serif).foregroundStyle(palette.textPrimary).lineLimit(1)
            Text(head.week).font(MekaType.itemTitle).fontDesign(.monospaced).foregroundStyle(palette.textTertiary).lineLimit(1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(head.spoken + ", " + w.title)
    }

    /// Today, Review, Timeline, Plan your week and ‹ › on the header's right.
    @ViewBuilder private var weekButtons: some View {
        // Match slice 4: the mockup's round buttons (`.btn`), 40 high, outlined on the card surface.
        Button { MekaHaptics.tick(); goTo(0) } label: {
            Text(BoardMatchRules.shared.TODAY).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                .padding(.horizontal, MekaSpace.m)
                .frame(height: CGFloat(BoardMatchRules.shared.BUTTON))
                .background(Capsule().fill(palette.surface))
                .overlay(Capsule().strokeBorder(palette.hairline, lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .help("Back to this week")
        // Item (8): from Friday, on this week, Review the week opens Review with the reflection open.
        if board.offersReview && week == 0 {
            Button(BoardReviewRules.shared.REVIEW) { MekaHaptics.tick(); model.openReflectionFromTalk() }
                .foregroundStyle(palette.textSecondary).font(MekaType.itemMeta)
                .help("Review this week: planned against done, and what kept slipping")
        }
        // Option B slice 5: Timeline / Hide timeline opens or shuts today's timeline beside the week.
        Button(showTimeline ? BoardTimelineRules.shared.HIDE : BoardTimelineRules.shared.SHOW) {
            MekaHaptics.tick()
            withAnimation(MekaMotion.replan(reduced: reduceMotion)) { showTimeline.toggle() }
        }
        .foregroundStyle(palette.textSecondary).font(MekaType.itemMeta)
        .help(showTimeline ? "Hide today's timeline" : "Show today's timeline beside the week")
        // Item (6): Plan your week opens the guided sheet.
        Button(PlanWeekRules.shared.OPEN) { MekaHaptics.tick(); model.showPlanWeek = true }
            .foregroundStyle(palette.accent).font(MekaType.itemMeta)
            .keyboardShortcut("p", modifiers: [.command, .shift])
            .help("Plan your week (⇧⌘P)")
        Button { step(-1) } label: { RoundStep(systemName: "chevron.left", palette: palette) }
            .buttonStyle(.plain)
            .opacity(week == 0 ? 0.3 : 1)
            .disabled(week == 0)
            .keyboardShortcut("[", modifiers: .command)
            .help("Previous week")
        Button { step(1) } label: { RoundStep(systemName: "chevron.right", palette: palette) }
            .buttonStyle(.plain)
            .opacity(week >= board.weeks.count - 1 ? 0.3 : 1)
            .disabled(week >= board.weeks.count - 1)
            .keyboardShortcut("]", modifiers: .command)
            .help("Next week")
    }

    var body: some View {
        let w = board.weeks[min(week, board.weeks.count - 1)]
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            // Match slice 3: Obi's pill sits in the header's row just before Today when the window has room (the mockup
            // at 1280 wide), else on its own line under the month (the mockup at 390 wide).
            let nudge = model.boardNudge(board, weekStart: w.startEpochDay)
            ViewThatFits(in: .horizontal) {
                HStack {
                    weekTitle(w)
                    Spacer(minLength: MekaSpace.xs)
                    if let nudge {
                        ObiNudgeLine(nudge: nudge, palette: palette)
                            .frame(maxWidth: CGFloat(BoardMatchRules.shared.NUDGE_MAX_WIDTH))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    weekButtons
                }
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    HStack {
                        weekTitle(w)
                        Spacer(minLength: MekaSpace.xs)
                        weekButtons
                    }
                    if let nudge { ObiNudgeLine(nudge: nudge, palette: palette) }
                }
            }
            .buttonStyle(.borderless)

            // The week's goals as small gold pills across the top (Option B slice 2: the trays are lists below now).
            BoardGoalsView(week: w, palette: palette, target: $target)
                .animation(MekaMotion.replan(reduced: reduceMotion), value: w.goals.map(\.goalId))

            // Option B slice 2 (the layout; Meka's refinement 1): Monday to Friday as five columns across, the weekend
            // as two shorter columns underneath, then No day yet and Someday as lists, each a place to drop a card.
            let layout = BoardWeekLayoutRules.shared
            HStack(alignment: .top, spacing: MekaSpace.m) {
            VStack(alignment: .leading, spacing: MekaSpace.m) {
                dayRow(layout.weekdays(week: w), minHeight: CGFloat(layout.MIN_WEEKDAYS_DP))
                let weekend = layout.weekend(week: w)
                if !weekend.isEmpty {
                    dayRow(weekend, minHeight: CGFloat(layout.MIN_WEEKEND_DP))
                }
                // Option B match, slice 2: a hairline, then the lists as the mockup's card columns, in its order
                // (Someday, Side hustle, House, then No day yet).
                Rectangle().fill(palette.hairline).frame(height: 1).accessibilityHidden(true)
                HStack(alignment: .top, spacing: MekaSpace.m) {
                    ForEach(BoardMatchRules.shared.lists(week: w, board: board), id: \.key) { column in
                        let key = listKey(column)
                        BoardListView(column: column, week: w, board: board, palette: palette, targeted: target == key) { on in
                            if on { target = key } else if target == key { target = nil }
                        }
                    }
                }
                .animation(MekaMotion.replan(reduced: reduceMotion), value: w.lane.map(\.id))
                .animation(MekaMotion.replan(reduced: reduceMotion), value: board.someday.map(\.id))
            }
            .id(w.startEpochDay)
            .transition(weekTransition)
            .animation(MekaMotion.replan(reduced: reduceMotion), value: w.days.map { $0.cards.map(\.id) })
                // Option B slice 5: today's timeline beside the week (the mockup's panel), sliding in from the right.
                if showTimeline, let timeline = model.boardTimeline(board, epochDay: board.todayEpochDay) {
                    BoardTimelineView(timeline: timeline, palette: palette, board: board)
                        .frame(width: CGFloat(BoardTimelineRules.shared.PANEL_DP))
                        .transition(reduceMotion ? .opacity : .move(edge: .trailing).combined(with: .opacity))
                }
            }
        }
        .background(keyboard)
        .onAppear {
            guard !opened else { return }
            opened = true
            week = Int(BoardMatchRules.shared.opensOnWeek(board: board))
        }
    }

    private func goTo(_ target: Int) {
        guard target != week else { return }
        direction = target > week ? 1 : -1
        withAnimation(MekaMotion.replan(reduced: reduceMotion)) { week = max(0, min(board.weeks.count - 1, target)) }
    }

    /// One row of the week's days: each opened day shares the row, a past one folds narrow.
    private func dayRow(_ days: [BoardDay], minHeight: CGFloat) -> some View {
        HStack(alignment: .top, spacing: MekaSpace.xs) {
            // Option B match: every day a full column (a past one dimmed), never folded narrow.
            ForEach(days, id: \.epochDay) { d in
                BoardColumnView(day: d, board: board, palette: palette, targeted: target == d.epochDay, onFold: nil, minHeight: minHeight) { on in
                    if on { target = d.epochDay } else if target == d.epochDay { target = nil }
                }
            }
        }
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

    /// A list's drop-target key (Option B slice 3: Side hustle -5, House -6 beside No day yet and Someday).
    private func listKey(_ column: BoardListColumn) -> Int64 {
        if column.tray == BoardTray.noDay { return laneKey }
        if column.area == BoardArea.sideHustle { return -5 }
        if column.area == BoardArea.house { return -6 }
        return trayKey
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

/// Option B slice 4: Obi's one-line nudge in the header (match slice 3: beside Today, or under the month when narrow), the mockup's pill: a small gold orb, "Obi" and the
/// line ("Mon is 1h over · move “Gym” to Fri?"), amber when a day is over or stacking up. A line with a fix is a button
/// (press style, light haptic, Undo on the bar). A new line cross-fades in (Reduce Motion: a plain fade).
private struct ObiNudgeLine: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let nudge: BoardNudge
    let palette: MekaPalette

    var body: some View {
        let tone = nudge.warns ? palette.calendar3 : palette.textSecondary
        let pill = HStack(spacing: MekaSpace.xs) {
            Circle()
                .fill(RadialGradient(
                    colors: [palette.accent.opacity(0.45), palette.accent, palette.accent.opacity(0.35)],
                    center: UnitPoint(x: 0.35, y: 0.35), startRadius: 0, endRadius: CGFloat(BoardMatchRules.shared.NUDGE_ORB) / 2
                ))
                .frame(width: CGFloat(BoardMatchRules.shared.NUDGE_ORB), height: CGFloat(BoardMatchRules.shared.NUDGE_ORB))
                .accessibilityHidden(true)
            Text(BoardNudgeRules.shared.SPEAKER).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
            Text(nudge.line).font(MekaType.caption).foregroundStyle(tone).lineLimit(Int(BoardMatchRules.shared.NUDGE_LINES))
                .id(nudge.line)
                .transition(.opacity)
        }
        .padding(.leading, MekaSpace.xs).padding(.trailing, MekaSpace.m)
        .frame(minHeight: 32)
        .background(Capsule().fill(palette.surface))
        .overlay(Capsule().strokeBorder(palette.hairline, lineWidth: 1))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: nudge.line)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(nudge.spoken)
        if let task = nudge.fixTaskId {
            let day = nudge.fixDay
            Button { model.easePressure(taskId: task, toDay: day) } label: { pill }
                .buttonStyle(MekaPressStyle())
                .help("Move it there (Undo on the bar)")
        } else {
            pill
        }
    }
}

/// Option B slice 5: a day as an hour-by-hour timeline, matching android/.../calendar/BoardTimelineView.kt: "Today" over
/// its date, all-day events as one quiet line, the hours 07–22 down the side with their hairlines, and the day's blocks
/// over them (events in their calendar's tone, timed tasks in gold, a done one faded, the commute in orange; overlapping
/// blocks share the width), a gold line at now. A click on an event opens it. Blocks fade in (appear); Reduce Motion: at once.
private struct BoardTimelineView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let timeline: BoardTimeline
    let palette: MekaPalette
    /// Option B match, slice 2: the board the blocks' cards are on, so a task block wears its area's colour.
    var board: WeekBoard? = nil
    private let labelWidth: CGFloat = 36

    var body: some View {
        let hour = CGFloat(BoardTimelineRules.shared.HOUR_DP)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .firstTextBaseline) {
                Text(timeline.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary).lineLimit(1)
                Spacer(minLength: MekaSpace.xs)
                if timeline.dateLabel != timeline.title {
                    Text(timeline.dateLabel).font(MekaType.micro).foregroundStyle(palette.textTertiary).lineLimit(1)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(timeline.spoken)
            .accessibilityAddTraits(.isHeader)
            if let line = timeline.allDayLine {
                Text(line).font(MekaType.micro).foregroundStyle(palette.textSecondary).lineLimit(2).accessibilityHidden(true)
            }
            if let empty = timeline.emptyLine {
                Text(empty).font(MekaType.micro).foregroundStyle(palette.textTertiary)
            }
            ZStack(alignment: .topLeading) {
                VStack(spacing: 0) {
                    ForEach(Array(timeline.hours.enumerated()), id: \.offset) { _, label in
                        HStack(alignment: .top, spacing: 0) {
                            Text(label).font(MekaType.micro).foregroundStyle(palette.textTertiary)
                                .frame(width: labelWidth, alignment: .leading).padding(.top, MekaSpace.xxs)
                            Rectangle().fill(palette.hairline).frame(height: 1)
                        }
                        .frame(height: hour, alignment: .top)
                    }
                }
                .accessibilityHidden(true)
                GeometryReader { geo in
                    let area = max(geo.size.width - labelWidth, 0)
                    let first = timeline.firstHour
                    ForEach(timeline.blocks, id: \.key) { b in
                        let colW = area / CGFloat(max(b.columns, 1))
                        block(b)
                            .frame(width: max(colW - 2, 0), height: CGFloat(b.heightDp))
                            .offset(x: labelWidth + colW * CGFloat(b.column), y: CGFloat(b.topDp(firstHour: first)))
                    }
                    if let top = timeline.nowTopDp {
                        Rectangle().fill(palette.accent).frame(width: area, height: 2)
                            .offset(x: labelWidth, y: CGFloat(top.floatValue) - 1)
                            .accessibilityHidden(true)
                    }
                }
            }
            .frame(height: CGFloat(timeline.heightDp))
            .animation(MekaMotion.appear(reduced: reduceMotion), value: timeline.blocks.map(\.key))
        }
    }

    @ViewBuilder
    private func block(_ b: DayBlock) -> some View {
        let area: BoardArea? = board.flatMap { BoardMatchRules.shared.blockArea(block: b, board: $0) }
        let tone: Color = b.kind == DayBlockKind.event ? palette.calendarTone(b.tone)
            : (b.kind == DayBlockKind.task ? (area.map { palette.areaTone($0) } ?? palette.accent) : palette.calendar3)
        let face = HStack(spacing: 0) {
            Rectangle().fill(tone).frame(width: 3)
            VStack(alignment: .leading, spacing: 0) {
                Text(b.title).font(MekaType.micro).foregroundStyle(palette.textPrimary).lineLimit(b.oneLine ? 1 : 2)
                if !b.oneLine {
                    Text(b.label).font(MekaType.micro).foregroundStyle(palette.textTertiary).lineLimit(1)
                }
            }
            .padding(.horizontal, MekaSpace.xs).padding(.vertical, b.oneLine ? 1 : MekaSpace.xxs)
            Spacer(minLength: 0)
        }
        .frame(maxHeight: .infinity, alignment: .top)
        .background(tone.opacity(0.16))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .opacity(b.done ? Double(BoardPlannerRules.shared.DONE_ALPHA) : 1)
        .transition(.opacity)
        if let event = b.event {
            Button { model.openEvent = event } label: { face }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel(b.spoken)
        } else {
            face.accessibilityElement(children: .ignore).accessibilityLabel(b.spoken)
        }
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
    /// Option B slice 2: the weekend's row is shorter than the weekdays'.
    var minHeight: CGFloat = 320
    let onTarget: (Bool) -> Void
    /// By hand, slice 8 (the quality gate's findings): how wide the lane is drawn, so a narrow one fits its words.
    @State private var laneWidth: CGFloat = CGFloat(BoardTidyRules.shared.MAX_LANE_DP)

    var body: some View {
        let glow = targeted && day.canDrop
        let compact = BoardFitRules.shared.compact(laneDp: Float(laneWidth))
        let tagBelow = BoardFitRules.shared.tagBelow(compact: compact)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            // Option B match (Meka 2026-10-24 14:39): "27 Oct" in serif with "Mon" on the right (today in gold), the thin
            // done bar without words, then the budget pill, always "planned · 4h 05 / 3h".
            let head = BoardMatchRules.shared.dayHead(day: day)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                HStack(alignment: .firstTextBaseline) {
                    Text(head.date).font(MekaType.nowTitle).fontDesign(.serif)
                        .foregroundStyle(day.isToday ? palette.accent : palette.textPrimary).lineLimit(1)
                    Spacer(minLength: 0)
                    Text(head.weekday).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).lineLimit(1)
                }
                if let progress = BoardLookRules.shared.progress(day: day) {
                    DayProgressBar(progress: progress, palette: palette)
                }
                if let capacity = day.capacity {
                    BudgetPill(capacity: capacity, today: day.isToday, palette: palette, compact: compact)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(day.accessibilityLabel)

            // Item (10): "Stacking up: Trip form, Council tax and Gym" in amber, with its one-tap fixes.
            if let pressure = day.pressure {
                PressureNote(pressure: pressure, palette: palette)
            }

            // Option B match: the day's events are cards too, a blue time chip and their length.
            let events = BoardMatchRules.shared.eventCards(day: day)
            if !events.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(events, id: \.id) { e in
                        BoardEventCardView(card: e, palette: palette, compact: compact) { model.openEvent = e.event }
                    }
                    if let more = day.moreLabel {
                        Text(more).font(MekaType.micro).foregroundStyle(palette.textTertiary)
                    }
                }
            }

            // Free time shows only while a card is held over the day, as the drop targets that block time.
            if glow && !day.freeTimes.isEmpty {
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
            // Option B match: "+ add" at the foot of every day from today, a quiet dashed button; the field opens there.
            if day.canDrop { BoardAddRow(day: day, palette: palette) }
            // Match slice 4: a past day with nothing on it says so quietly under its date, never a bare column.
            if let empty = BoardMatchRules.shared.pastEmpty(day: day) {
                Text(empty).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(MekaSpace.xs)
        .frame(maxWidth: .infinity, minHeight: minHeight, alignment: .topLeading)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { laneWidth = $0 }
        .background(
            RoundedRectangle(cornerRadius: MekaRadius.m)
                // Option B match: no box round a day; today is the gold date and pill.
                .fill(glow ? palette.surfaceRaised : Color.clear)
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
                    Text(BoardMatchRules.shared.ADD).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .frame(maxWidth: .infinity, minHeight: 34, alignment: .center)
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
    /// Option B match, slice 2: a card in one of the lists at the foot (its top line only with a time or a length).
    var inList: Bool = false
    @State private var over = false
    /// Slice 6: Block time for a day's card, read as the pointer reaches it (the menu can't wait for the core).
    @State private var offer: BlockOffer?

    var body: some View {
        let selected = model.boardSelectedID == card.id
        // Option B, slice 1 (the planner cards, BoardPlannerRules): a borderless tile on the lighter surface; the time
        // chip and the duration on top, the title across the whole card, the tick and the card's chips and source in the
        // foot. A done card fades (its words stay plain, never struck through); the fade glides (Reduce Motion: steps).
        let planner = BoardPlannerRules.shared
        // Option B match: every card has its top line, the time chip (gold, or a quiet "Any time") and its length.
        let match = BoardMatchRules.shared
        let time = inList ? match.listTime(card: card) : match.timeChip(card: card)
        let timed = match.timed(card: card)
        let listDuration = inList ? match.listDuration(card: card) : ""
        let duration: String? = inList ? (listDuration.isEmpty ? nil : listDuration) : planner.durationLabel(card: card)
        let foot = planner.footChips(card: card)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if !time.isEmpty || duration != nil {
                HStack(spacing: MekaSpace.xs) {
                    if !time.isEmpty {
                        BoardTimeChip(time: time, ink: timed ? palette.accent : palette.textTertiary, quiet: !timed, palette: palette)
                    }
                    Spacer(minLength: 0)
                    if let duration {
                        Text(duration).font(BoardFont.mono).foregroundStyle(palette.textTertiary).lineLimit(1)
                    }
                }
            }
            Text(card.title).font(compact ? MekaType.itemMeta : MekaType.body)
                .foregroundStyle(palette.textPrimary)
                .strikethrough(card.done && planner.STRIKE_DONE, color: palette.textTertiary)
                .lineLimit(Int(BoardFitRules.shared.titleLines(compact: compact)))
            HStack(spacing: MekaSpace.xs) {
                BoardTick(done: card.done, palette: palette) {
                    if card.done { model.reopenOnBoard(card.id, title: card.title) } else { model.completeOnBoard(card.id, title: card.title) }
                }
                .accessibilityLabel(BoardHandRules.shared.tickLabel(title: card.title, done: card.done))
                Spacer(minLength: 0)
                // Slice 2: the small chips (carried in amber, repeat, goal in gold); time and estimate are on top.
                if !foot.isEmpty {
                    HStack(spacing: MekaSpace.xxs) {
                        ForEach(Array(foot.enumerated()), id: \.offset) { _, chip in
                            CardChipPill(chip: chip, palette: palette)
                        }
                    }
                }
                // Option B slice 3: the area's colour dot and name ("● Family"); a card with no area keeps slice 5's dot
                // for where it came from ("● Mail").
                if let area = BoardAreaRules.shared.of(card: card) {
                    HStack(spacing: MekaSpace.xxs) {
                        Circle().fill(palette.areaTone(area)).frame(width: 8, height: 8)
                        // Match slice 4: the name shows on the narrow lanes too, cut short with "…" if it must be.
                        if BoardMatchRules.shared.footWords(compact: compact) {
                            Text(BoardAreaRules.shared.label(area: area)).font(MekaType.micro)
                                .foregroundStyle(palette.textSecondary).lineLimit(1).truncationMode(.tail)
                        }
                    }
                } else if let source = card.source {
                    HStack(spacing: MekaSpace.xxs) {
                        Circle().fill(palette.calendarTone(source.tone)).frame(width: 8, height: 8)
                        if BoardMatchRules.shared.footWords(compact: compact) {
                            Text(planner.sourceLabel(source: source)).font(MekaType.micro)
                                .foregroundStyle(palette.textSecondary).lineLimit(1).truncationMode(.tail)
                        }
                    }
                    .help(source.label)
                }
            }
        }
        .padding(.horizontal, MekaSpace.xs).padding(.top, MekaSpace.xs).padding(.bottom, MekaSpace.xxs)
        .frame(maxWidth: .infinity, alignment: .leading)
        // Refinement 3: a borderless tile on the lighter surface; a done one fades rather than sinking or striking through.
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
        .opacity(Double(planner.alpha(card: card)))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: card.done)
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
                // Option B slice 3: Area — Family, Work, Side hustle, Money, House (the one it shows ticked), or No area.
                Divider()
                Section(BoardAreaRules.shared.MENU_TITLE.capitalized) {
                    ForEach(Array(BoardAreaRules.shared.choices(card: card).enumerated()), id: \.offset) { _, c in
                        Button(c.label) { model.areaOnBoard(card.id, area: c.area?.name) }
                            .disabled(c.selected)
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
            // Option B match: the bar only; the words stay for VoiceOver.
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

/// ‹ or › in the Board's header. Match slice 4: the mockup's round outlined button, a 40 pt circle on the card surface
/// edged in the hairline.
private struct RoundStep: View {
    let systemName: String
    let palette: MekaPalette

    var body: some View {
        let size = CGFloat(BoardMatchRules.shared.BUTTON)
        Image(systemName: systemName).font(.system(size: 13, weight: .semibold)).foregroundStyle(palette.textPrimary)
            .frame(width: size, height: size)
            .background(Circle().fill(palette.surface))
            .overlay(Circle().strokeBorder(palette.hairline, lineWidth: 1))
            .contentShape(Circle())
    }
}

/// Option B, slice 1: the day's budget pill under its header, "planned · 4h 05 / 3h" (a narrow lane: "4h 05 / 3h"),
/// edged gold on today, its thin bar filling gold; amber when more is planned than the day has free, the bar breathing
/// gently then; colours cross-fade and the width glides. Reduce Motion: steady, no glide.
private struct BudgetPill: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let capacity: BoardCapacity
    let today: Bool
    let palette: MekaPalette
    var compact: Bool = false
    @State private var breathe = false

    var body: some View {
        let b = BoardPlannerRules.shared.budget(c: capacity)
        let tone = b.over ? palette.calendar3 : palette.accent
        let edge = b.over ? palette.calendar3.opacity(0.7) : (today ? palette.accent : palette.hairline)
        VStack(spacing: 0) {
            HStack(spacing: MekaSpace.xs) {
                if BoardMatchRules.shared.pillStacked(compact: compact) {
                    // Match slice 4: a narrow lane stacks "planned" over the figures, so the word is never dropped.
                    VStack(alignment: .leading, spacing: 0) {
                        Text(BoardMatchRules.shared.pillLabel(b: b)).font(BoardFont.mono)
                            .foregroundStyle(palette.textTertiary).lineLimit(1)
                        Text(BoardPlannerRules.shared.compactLine(b: b)).font(BoardFont.mono)
                            .foregroundStyle(b.over ? palette.calendar3 : palette.textPrimary).lineLimit(2)
                    }
                    Spacer(minLength: 0)
                } else {
                    Text(b.label).font(BoardFont.mono).foregroundStyle(palette.textTertiary).lineLimit(1)
                    Spacer(minLength: 0)
                    if !b.value.isEmpty {
                        Text(b.value).font(BoardFont.mono)
                            .foregroundStyle(b.over ? palette.calendar3 : palette.textPrimary).lineLimit(1)
                    }
                }
            }
            .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Rectangle().fill(palette.hairline)
                    Rectangle().fill(tone)
                        .frame(width: geo.size.width * CGFloat(b.fill))
                        .opacity(b.over && breathe ? 0.55 : 1)
                }
            }
            .frame(height: 2)
        }
        .background(RoundedRectangle(cornerRadius: MekaRadius.s).fill(palette.surface))
        .clipShape(RoundedRectangle(cornerRadius: MekaRadius.s))
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.s).strokeBorder(edge, lineWidth: 1))
        .padding(.top, MekaSpace.xxs)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: b.fill)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: b.over)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(b.spoken)
        .onAppear { startBreathing() }
        .onChange(of: b.over) { _, _ in startBreathing() }
    }

    private func startBreathing() {
        let over = BoardPlannerRules.shared.budget(c: capacity).over
        guard over, !reduceMotion else { breathe = false; return }
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
                // Option B slice 3: onto a card in Someday, Side hustle or House: into that card's list.
                model.listOnBoard(id, list: BoardAreaRules.shared.listOf(card: card)?.name)
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

/// Option B slice 2: one of the lists under the weekend ("No day yet · 3", "Someday · 12"): its title in the day
/// headers' weight, its cards stacked (they drag out onto a day and have the same context menu as a day's), or a quiet
/// line when empty. A card dropped on it is parked in the week or put on Someday; it lifts with a gold edge while
/// targeted (approve spring; Reduce Motion: no lift).
private struct BoardListView: View {
    @Environment(CoreModel.self) private var model
    let column: BoardListColumn
    let week: BoardWeek
    let board: WeekBoard
    let palette: MekaPalette
    let targeted: Bool
    let onTarget: (Bool) -> Void

    var body: some View {
        let someday = column.tray == BoardTray.someday
        let cards = column.cards
        // Option B match, slice 2: the mockup's heading, the name in serif (the day headers' type) and the count quiet
        // in mono on the right; no box round the list, only a held card over it lifts it with the gold edge.
        let head = BoardMatchRules.shared.listHead(column: column)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .lastTextBaseline, spacing: MekaSpace.xs) {
                Text(head.name).font(MekaType.nowTitle).fontDesign(.serif).foregroundStyle(palette.textPrimary).lineLimit(1)
                Spacer(minLength: 0)
                if let count = head.count {
                    Text(count).font(BoardFont.mono).foregroundStyle(palette.textTertiary).lineLimit(1)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(head.spoken)
            .accessibilityAddTraits(.isHeader)
            if cards.isEmpty {
                Text(targeted ? WeekBoardRules.shared.DROP_HERE : column.empty).font(MekaType.caption)
                    .foregroundStyle(targeted ? palette.accent : palette.textTertiary)
            } else {
                ForEach(cards, id: \.id) { card in
                    BoardCardView(card: card, spot: someday ? .someday : .lane(week), board: board, palette: palette, inList: true)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, minHeight: CGFloat(BoardWeekLayoutRules.shared.MIN_LISTS_DP), alignment: .topLeading)
        .modifier(BoardListZone(palette: palette, targeted: targeted))
        .dropDestination(for: String.self) { ids, _ in
            guard let id = ids.first else { return false }
            if someday {
                // Option B slice 3: Someday, Side hustle or House; the core says no when it is already there.
                guard board.find(taskId: id) != nil else { return false }
                model.listOnBoard(id, list: column.area?.name)
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
    var minHeight: CGFloat = 320
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
            .frame(minHeight: minHeight)
            .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).strokeBorder(palette.hairline, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .help("Open this day")
        .accessibilityLabel(BoardTidyRules.shared.collapsedLabel(day: day))
    }
}

/// Option B match, slice 2: a list at the board's foot sits on the page (no box); a held card over it lifts it to the
/// raised tone with the gold edge (approve spring; Reduce Motion: no lift).
private struct BoardListZone: ViewModifier {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    let targeted: Bool

    func body(content: Content) -> some View {
        content
            .padding(.vertical, MekaSpace.xs)
            .frame(maxWidth: .infinity, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(targeted ? palette.surfaceRaised : Color.clear))
            .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).strokeBorder(targeted ? palette.accent : Color.clear, lineWidth: 1.5))
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

extension MekaPalette {
    /// A Board card's area dot (Option B slice 3, the approved mockup's colours): Family the warm orange, Work the blue,
    /// Side hustle the brass, Money the green, House the violet.
    func areaTone(_ area: BoardArea) -> Color {
        if area == BoardArea.family { return calendar3 }
        if area == BoardArea.work { return calendar4 }
        if area == BoardArea.sideHustle { return accent }
        if area == BoardArea.money { return success }
        return calendar2
    }
}

/// Option B match: the mockup's mono (JetBrains Mono) for times and figures is the micro token in the system's
/// monospaced design; its serif (Fraunces) for the month and the dates is the greeting and now-title tokens with
/// `.fontDesign(.serif)` (New York).
enum BoardFont {
    static var mono: Font { MekaType.micro.monospaced() }
}

/// The time chip on a card's top line (the mockup's `.tm`): mono figures on a faint tint, gold for a task's time, blue
/// for an event, quiet for "Any time".
private struct BoardTimeChip: View {
    let time: String
    let ink: Color
    var quiet = false
    let palette: MekaPalette

    var body: some View {
        Text(time).font(BoardFont.mono).foregroundStyle(ink).lineLimit(1)
            .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
            .background(RoundedRectangle(cornerRadius: 6).fill(quiet ? palette.surface : ink.opacity(0.16)))
    }
}

/// Option B match: one of the day's events as a card in its column (the mockup's stand-up): the blue time chip and its
/// length on top, the title, then the calendar's dot and name in the foot. A click opens the event.
private struct BoardEventCardView: View {
    let card: BoardEventCard
    let palette: MekaPalette
    var compact = false
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                HStack(spacing: MekaSpace.xs) {
                    BoardTimeChip(time: card.time, ink: palette.calendar4, palette: palette)
                    Spacer(minLength: 0)
                    if let duration = card.duration {
                        Text(duration).font(BoardFont.mono).foregroundStyle(palette.textTertiary).lineLimit(1)
                    }
                }
                Text(card.title).font(compact ? MekaType.itemMeta : MekaType.body).foregroundStyle(palette.textPrimary)
                    .lineLimit(Int(BoardFitRules.shared.titleLines(compact: compact)))
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack(spacing: MekaSpace.xxs) {
                    Spacer(minLength: 0)
                    CalendarDot(tone: card.tone, palette: palette)
                    if BoardMatchRules.shared.footWords(compact: compact) {
                        Text(card.calendar).font(MekaType.micro).foregroundStyle(palette.textSecondary).lineLimit(1)
                            .truncationMode(.tail)
                    }
                }
            }
            .padding(MekaSpace.xs)
            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
            .contentShape(RoundedRectangle(cornerRadius: MekaRadius.m))
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(card.spoken)
    }
}
