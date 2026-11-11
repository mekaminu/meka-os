@preconcurrency import MekaKit
import SwiftUI

/// TODAY on macOS: Today (two columns from 600 pt: the Today rebuild, slice 2d) | an open task, resizable, keyboard-first (brief §45).
/// Motion (App shell): the selection highlight glides from row to row and the detail slides across to the new task;
/// after Plan Apply the placed tasks glide into place and are softly lit for a moment. Reduce Motion: cross-fades.
struct TodayView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.mekaReduceMotion) private var reduceMotion
    /// App open: greeting fades up, then each section 40 ms apart. Plays once; later arrivals use row transitions.
    @State private var introPlayed = false
    /// "3 earlier" unfolds the finished events in place.
    @State private var earlierOpen = false
    @State private var doneOpen = false
    @State private var allDayOpen = false
    /// The Day ring's opening: in full the first time today on this Mac, quickly after, at once with Motion → Off.
    @State private var ringPlay: DayRingPlayback?
    /// When MEKA last went behind another app (Living Today: a return after a while replays the opening).
    @State private var resignedAt: Date?
    /// Appearance → Play the opening bumps this: Today's column is rebuilt so its stagger plays again.
    @State private var openings = 0
    /// The full 24-hour Day ring as a sheet, opened by clicking the watch face (Fold review 2026-10-09 07:26, item 2).
    @State private var showDayRing = false
    /// The arc clicked in that sheet, opened once the sheet has gone.
    @State private var dayRingArc: DayArc?
    private var play: Bool { !introPlayed }
    /// The opening stagger's steps, in the order Today's sections sit (Calm Today, slice 3: core TodayOrderRules).
    private static let sections = Int(TodayOrderRules.shared.SECTIONS)

    private var palette: MekaPalette { scheme == .dark ? .dark : .light }
    @Namespace private var selection
    /// The content width, for the command centre's columns.
    @State private var width: CGFloat = 0
    /// The split's header height (its content's) and the whole of Today's height, so the header takes at most its share.
    @State private var splitHeaderHeight: CGFloat = 0
    @State private var splitHeight: CGFloat = 0
    /// Today's scroll position (the compact header has nothing to collapse: the Today rebuild, slice 2d-2a).
    @State private var scrollPosition = ScrollPosition(edge: .top)
    /// The Also today tile unfolded under the row (prep, the study check-in, a day stacking up), or nil.
    @State private var alsoUnfolded: AlsoKind?
    /// The course a STUDY tile opened, shown as its sheet.
    @State private var alsoCourse: String?
    /// The day Later put the evening card away on (until 21:00), or nil (the Today rebuild, slice 2d-2c).
    @State private var eveningLaterDay: Int64?

    var body: some View {
        @Bindable var model = model
        // The Today rebuild, slice 2d (core TodayColumnsRules): from 600 pt Today is two columns across the window, in
        // place of the command centre's Today | Needs you | Coming up (Needs you keeps its sidebar tab; Coming up and
        // News sit in Today's right-hand column). An open task keeps a column of its own on the right, about a third of
        // the window; Today splits again in what's left while that is still wide enough, else it's one list. Plain
        // columns (not a split view) so each keeps the title bar's safe area and its share as the window resizes.
        let cols = TodayColumnsRules.shared
        let widths = cols.macWidths(contentWidthPt: Float(width), taskOpen: model.selected != nil)
            .map { max(0, CGFloat($0.floatValue)) }
        let todayWidth = widths.first ?? 0
        let split = width > 0 && cols.macTodaySplits(todayWidthPt: Float(todayWidth))
        let slide = CGFloat(MacCommandCentreRules.shared.COLUMN_SLIDE_PT)
        HStack(spacing: 0) {
            todayColumn(split: split)
                .environment(\.selectionNamespace, selection)
                .frame(width: todayWidth)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier(TodayView.columnID(CommandColumn.today))
            if widths.count > 1 {
                CommandSideView(column: CommandColumn.detail, palette: palette)
                    .frame(width: widths[1])
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier(TodayView.columnID(CommandColumn.detail))
                    .overlay(alignment: .leading) { columnRule }
                    .transition(.column(slide: slide))
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: widths.count)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: split)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .background(palette.background)
        .sheet(isPresented: $model.showConnect) { ConnectSheet(palette: palette) }
        .sheet(isPresented: $model.showPlan) { PlanSheet(palette: palette) }
        .sheet(isPresented: $showDayRing, onDismiss: openAfterDayRing) {
            if let today = model.today {
                DayRingSheet(ring: today.dayRing, tiles: today.dayTiles, palette: palette,
                             onOpenArc: { arc in dayRingArc = arc; showDayRing = false },
                             onClose: { showDayRing = false })
            }
        }
    }

    /// Accessibility identifiers for the UI-test target's fit check: "column-today", "column-needs_you", …
    static func columnID(_ column: CommandColumn) -> String { "column-" + column.name.lowercased() }

    /// The hairline between two columns.
    private var columnRule: some View {
        Rectangle().fill(palette.textTertiary.opacity(0.18)).frame(width: 1).accessibilityHidden(true)
    }

    /// An arc clicked in the Day ring sheet: the sheet goes, then (once it has gone, one sheet at a time) its event's
    /// or task's detail opens.
    private func openAfterDayRing() {
        guard let arc = dayRingArc, let today = model.today else { return }
        dayRingArc = nil
        switch arc.kind {
        case .event:
            if let e = today.events.first(where: { "e-" + $0.id == arc.id }) { model.openEvent = e }
        case .task:
            model.select(String(arc.id.dropFirst(2)), reduced: reduceMotion)
        default:
            break // a session's row in the timeline carries its actions
        }
    }

    /// Today itself: one list, or (the Today rebuild, slice 2d; core TodayColumnsRules) the header across the top over
    /// two columns. The opening, the return-after-a-while replay and the capture field are the same either way.
    private func todayColumn(split: Bool) -> some View {
        VStack(spacing: 0) {
            Group {
                if split { splitToday } else { singleToday }
            }
            .id(openings)
            .onAppear {
                if ringPlay == nil { ringPlay = DayRingOpen.claim(reduced: reduceMotion) }
                takeOpeningRequest()
                model.morningReadOutIfDue(reduced: reduceMotion)
            }
            .onChange(of: model.openingRequested) { takeOpeningRequest() }
            // The morning read-out also starts when the brief's day comes in after Today opened.
            .onChange(of: model.brief?.dateLabel ?? "") { model.morningReadOutIfDue(reduced: reduceMotion) }
            // Every open is a moment (Living Today, slice 2): coming back to MEKA after a while draws the ring in
            // again and replays Today's stagger; the first return on a new day plays the full opening even when MEKA
            // stayed open overnight. A quick switch away and back leaves Today as it was.
            .onReceive(NotificationCenter.default.publisher(for: NSApplication.didResignActiveNotification)) { _ in
                resignedAt = Date()
            }
            .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
                // Reconnect clears at once: back from a sign-in page, the accounts are checked again straight away.
                Task { await model.returnedToApp() }
                model.morningReadOutIfDue(reduced: reduceMotion)
                let away = resignedAt.map { Date().timeIntervalSince($0) } ?? 0
                guard let back = DayRingOpen.onReturn(away: away, reduced: reduceMotion) else { return }
                ringPlay = back
                if resignedAt != nil {
                    introPlayed = false
                    openings += 1
                }
            }
            .task(id: openings) {
                guard !introPlayed else { return }
                try? await Task.sleep(for: .seconds(MotionMath.staggerSpan(count: Self.sections, reduced: false, expressive: true) + 0.3))
                introPlayed = true
            }
            // The Today rebuild, slice 2d-2e: the floating ticker strip at Today's foot gave way to NEWS, the picture cards
            // below Also today (MacTodayNewsRow); the drifting strip stays for the floating ticker.
            CaptureField(palette: palette)
                .padding(MekaSpace.m)
        }
    }

    /// One list (a narrow window, or Today beside an open task while it's too narrow for two columns), in the order
    /// it has always had on the Mac.
    private var singleToday: some View {
        ScrollView {
            todayMotion(
                LazyVStack(alignment: .leading, spacing: MekaSpace.xs) {
                    headerBlock
                    nowBlock
                    scheduleBlock
                    alsoBlock
                    newsBlock
                    momentCards
                    eveningBlock
                    receiptBlock
                    tasksBlock(listsNeedsYou: true)
                    comingUpBlock
                }
                .padding(.horizontal, MekaSpace.gutterWide)
                .padding(.vertical, MekaSpace.xl)
            )
        }
        .scrollPosition($scrollPosition)
        // The foot fades into the ticker and capture field rather than cutting a row in half (Fold review item
        // 4); the xl bottom padding is more than the fade, so the last row still scrolls fully clear.
        .footFade()
        .scrollIndicators(.never)
    }

    /// The Today rebuild, slice 2d (Meka approved 2026-11-08 22:58; as the open Fold's slice 2c): the header runs across
    /// the top (at most TodayColumnsRules.HEADER_MAX_SHARE of the height, scrolling beyond, the watch face pinned in its
    /// slot); under it two lists side by side, each scrolling on its own: what to do on the left (Now, the evening, Needs
    /// you's tasks, Today's tasks, done today), the shape of the day on the right (Schedule, the moment cards, Coming up
    /// and News, the evening receipt). Needs you keeps its sidebar tab.
    private var splitToday: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: MekaSpace.xs) { headerBlock }
                    .padding(.horizontal, MekaSpace.gutterWide)
                    .padding(.top, MekaSpace.xl)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { splitHeaderHeight = $0 }
            }
            .scrollIndicators(.never)
            .frame(height: min(splitHeaderHeight, splitHeight * CGFloat(TodayColumnsRules.shared.HEADER_MAX_SHARE)))
            HStack(alignment: .top, spacing: 0) {
                splitList(TodayView.leftColumnID, leading: MekaSpace.gutterWide, trailing: MekaSpace.gutterWide / 2) {
                    nowBlock
                    eveningBlock
                    tasksBlock(listsNeedsYou: true)
                }
                splitList(TodayView.rightColumnID, leading: MekaSpace.gutterWide / 2, trailing: MekaSpace.gutterWide) {
                    scheduleBlock
                    alsoBlock
                    newsBlock
                    momentCards
                    ComingUpColumnView(shared: true, palette: palette, embedded: true)
                        .environment(\.mekaStaggerPlays, play)
                        .padding(.top, MekaSpace.l)
                    receiptBlock
                }
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { splitHeight = $0 }
    }

    /// The split's two lists, for the UI tests' fit check ("column-today" is the whole of Today either way).
    static let leftColumnID = "column-today-left"
    static let rightColumnID = "column-today-right"

    /// One of the split's two lists: it scrolls on its own and its foot fades into the capture field.
    private func splitList<Content: View>(_ id: String, leading: CGFloat, trailing: CGFloat,
                                          @ViewBuilder content: () -> Content) -> some View {
        ScrollView {
            todayMotion(
                LazyVStack(alignment: .leading, spacing: MekaSpace.xs) { content() }
                    .padding(.leading, leading)
                    .padding(.trailing, trailing)
                    .padding(.top, MekaSpace.m)
                    .padding(.bottom, MekaSpace.xl)
            )
        }
        .footFade()
        .scrollIndicators(.never)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(id)
    }

    /// Today's list motion: rows glide to new positions instead of redrawing; cards rise in and fold away.
    private func todayMotion<V: View>(_ v: V) -> some View {
        v
            // Replan / complete motion: rows glide to new positions instead of redrawing.
            .animation(MekaMotion.replan(reduced: reduceMotion), value: model.allTasks.map(\.id))
            .animation(MekaMotion.replan(reduced: reduceMotion), value: model.today?.upNext?.id)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: model.shutdown?.offered)
            .animation(MekaMotion.expand(reduced: reduceMotion), value: model.brief?.offered)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: model.review?.card.offered)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: model.sessions?.cards.map(\.habitId) ?? [])
            .animation(MekaMotion.replan(reduced: reduceMotion), value: model.today?.timeline.rows.map(\.id))
            .animation(MekaMotion.appear(reduced: reduceMotion), value: earlierOpen)
            .animation(MekaMotion.complete(reduced: reduceMotion), value: allDayOpen)
            .animation(MekaMotion.replan(reduced: reduceMotion), value: model.today?.timeline.allDayItems.map(\.event.id))
    }

    /// The greeting, date, header lines and links, the watch face's slot, the habit chips, the ring's tiles and a
    /// running fast.
    @ViewBuilder
    private var headerBlock: some View {
        // The Today rebuild, slice 2d-2a (mockup TodayNew.dc.html; as the Fold's 2b-2b-2b-2): the compact header. The
        // date over the compact greeting on the left; the weather chip, ⋯ (Search, Plan my day) and a small 40 pt clock
        // on the right (a click: the whole day); TASKS · NEXT · MAIL across the page under them.
        HStack(alignment: .top, spacing: MekaSpace.m) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                if let date = model.today?.timeline.dateLabel, !date.isEmpty {
                    // Click the date: tomorrow, any time of day (Meka 2026-10-26).
                    Button { model.openTomorrow() } label: {
                        Text(date).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(1)
                    }
                    .buttonStyle(MekaPressStyle())
                    .help("See tomorrow")
                    .accessibilityHint("Opens tomorrow")
                }
                // The opening moment, part 2: on the first open of the day the greeting's letters fade in.
                GreetingText(text: greeting, play: ringPlay ?? .still, palette: palette, compact: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            HStack(alignment: .center, spacing: MekaSpace.xs) {
                // The weather as a small chip ("14° light rain"); VoiceOver hears the whole line.
                if let chip = TodayHeaderRules.shared.weatherChip(nowLine: model.weather?.nowLine) {
                    MacWeatherChip(text: chip,
                                   spoken: "Weather: " + (model.weather?.nowSpoken ?? model.weather?.nowLine ?? chip),
                                   palette: palette)
                }
                MacHeaderMoreMenu(palette: palette,
                                  openSearch: { model.showSearch = true },
                                  openPlan: { model.showPlan = true })
                if let today = model.today, !today.timeline.dateLabel.isEmpty {
                    MacSmallClock(face: today.watchFace, play: ringPlay ?? .still, played: { ringPlay = .still },
                                  palette: palette, night: model.shutdown?.night ?? false,
                                  onOpen: { showDayRing = true })
                }
            }
        }
        .staggeredAppear(0, play: play)
        // TASKS · NEXT · MAIL (core TodayHeaderRules), in place of the unread-mail lines and the countdown tile: TASKS
        // opens the Day list, NEXT its event, MAIL the inbox.
        if let today = model.today, !today.timeline.dateLabel.isEmpty {
            MacHeaderTilesRow(
                tiles: TodayHeaderRules.shared.tiles(timeline: today.timeline, done: today.doneToday, mail: today.mailUnread),
                palette: palette,
                onOpen: { kind in
                    switch kind {
                    case .tasks: model.openDayList()
                    case .next:
                        if let e = TodayHeaderRules.shared.nextRow(timeline: today.timeline)?.event { model.openEvent = e }
                    default: model.showMail = true
                    }
                })
                .padding(.top, MekaSpace.xs)
                .staggeredAppear(0, play: play)
        }
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
        // Mac presentation pass, slice 2 (item 4): what's left to set up and anything Health needs, as one
        // quiet line under the greeting ("Finish setting up · 4 steps · notifications are off · Open Setup ›").
        MacSetupLineView(palette: palette)
            .staggeredAppear(0, play: play)
        // The Today rebuild, slice 2d-2b: the trains, school, clash, goals, dinner, For the train, week ahead, bins, gifts,
        // study, trip, parcels, trading, replies, meeting prep, the study check-in, the week's one thing, a day stacking up,
        // date night and life admin are ALSO TODAY's tiles below the schedule now (core AlsoTodayRules).
        // Study plan, slice 2b rest: from 10 min before a study session, Obi's quiet card with Start, then
        // Done and Not now; folds away.
        StudyNudgeCard(nudge: model.today?.studyNudge, palette: palette)
            .padding(.top, model.today?.studyNudge == nil ? 0 : MekaSpace.xs)
            .staggeredAppear(0, play: play)
        // A calendar sign-in about to end or expired (Reliability first, item 2): one line, Reconnect.
        if let line = model.signInLines.first {
            SignInLineView(line: line, palette: palette)
                .staggeredAppear(0, play: play)
        }
        // "What Navilife may do" completeness: while paused, one accent line with Resume; it folds away on Resume.
        if let paused = model.autonomy?.todayLine {
            HStack(spacing: MekaSpace.l) {
                Text(paused).font(MekaType.caption).foregroundStyle(palette.accent).lineLimit(1)
                    .contentTransition(.opacity)
                Button(AutonomyRules.shared.RESUME) { Task { await model.setAutonomyPaused(false) } }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
                    .accessibilityLabel("Resume Navilife")
            }
            .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.move(edge: .top).combined(with: .opacity))
            .staggeredAppear(0, play: play)
        }
        if !model.isConnected || model.signedOut {
            Button(model.signedOut ? "Reconnect this Mac" : "This Mac isn't syncing yet · Connect") { model.showConnect = true }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
        }
        // Search and Plan my day are in the header's ⋯ now (the Today rebuild, slice 2d-2a); the sync ring stays.
        HStack(spacing: MekaSpace.l) {
            if model.syncRingShowing {
                SyncRingView(palette: palette)
                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.scale(scale: 0.6).combined(with: .opacity))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.syncRingShowing)
        if let line = model.syncLine {
            Text(line).font(MekaType.caption).foregroundStyle(palette.offline)
                .transition(.opacity)
        }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        // Today's habits as chips to tick (Fold review 2026-10-09 07:26, item 3): the small clock carries no dots
        // (core WatchFaceRules.carriesDots), so they are always the chips; none, no row.
        let habitChips = HabitChipRules.shared.build(goals: model.goals)
        if HabitDotRules.shared.chipsShown(faceShown: false, chips: habitChips) {
            HabitChipsRow(chips: habitChips, palette: palette, play: play)
                .padding(.top, MekaSpace.xs)
        }
        // The ring's live tiles, a slim row under the header (they left the dial with the move); the
        // habits tile is left to the chips.
        if let today = model.today, !today.timeline.dateLabel.isEmpty {
            // Fasting rebuild slice 4: while a fast runs it has its own card below, so the strip drops its tile.
            let fastCard = model.fasting?.current == nil ? nil
                : model.fastingScreen(atMs: Int64(Date().timeIntervalSince1970 * 1000))?.todayCard
            // The Today rebuild, slice 2d-2a: the countdown is the NEXT tile now.
            let stripTiles = FastingScreenRules.shared.stripTiles(
                tiles: HabitChipRules.shared.stripTiles(tiles: today.dayTiles), card: fastCard)
                .filter { $0.kind != DayTileKind.nextEvent }
            if !stripTiles.isEmpty {
                DayTilesStrip(tiles: stripTiles, play: ringPlay ?? .still, arcs: today.watchFace.arcs.count, palette: palette)
                    .padding(.top, MekaSpace.xs)
            }
        }
        // Fasting rebuild slice 4: the live Fasting card while a fast runs (the approved today-card design).
        FastingTodayCardView(palette: palette)
        Spacer().frame(height: MekaSpace.l)
    }

    /// What's on now: all clear, carried over, Up next and the next event, the Gym, quick alarms.
    @ViewBuilder
    private var nowBlock: some View {
        if let today = model.today {
            // "You're clear." only when nothing at all is left today; "Nothing else timed today" beside
            // all-day items (Today clarity).
            if let line = today.clearLine {
                // All clear: the brass ring breathes beside it (catalogue "Empty states"); Off: still.
                HStack(spacing: MekaSpace.xs) {
                    if today.isAllClear { BreathingRingView(palette: palette) }
                    Text(line)
                        .font(today.isAllClear ? MekaType.upNextTitle : MekaType.body)
                        .foregroundStyle(palette.textSecondary)
                }
                .staggeredAppear(step(.clear), play: play)
            }
            // Roll-over (Week board, slice 1): open tasks planned for an earlier day lead, oldest first.
            if !today.fromYesterday.isEmpty {
                FromYesterdayView(carried: today.fromYesterday, palette: palette)
                    .padding(.bottom, MekaSpace.l)
                    .staggeredAppear(step(.fromYesterday), play: play)
            }
            // Up next is the menu bar's card here too (Fold review 2026-10-09, item 3): it carries its own
            // "UP NEXT", so the section label only heads a lone next event.
            let upNextCard = model.upNextCard
            if upNextCard == nil && today.timeline.nextEvent != nil {
                SectionLabel("Up next", palette).staggeredAppear(step(.upNext), play: play)
            }
            if let card = upNextCard, let next = card.task {
                // Up next changes: the new card pushes in from the right (cross-fade with Reduce Motion).
                UpNextCard(card: card, palette: palette)
                    .id(next.id)
                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.push(from: .trailing))
                    .padding(.bottom, today.timeline.nextEvent == nil ? MekaSpace.l : MekaSpace.xs)
                    .staggeredAppear(step(.upNext), play: play)
            }
            // The next event within the hour: "Call with Tunde in 25 min", under Up next's card as the menu
            // bar's "Then: …" is.
            if let e = today.timeline.nextEvent {
                NextEventCard(next: e, palette: palette)
                    .padding(.bottom, MekaSpace.l)
                    .transition(.opacity)
                    .staggeredAppear(step(.upNext), play: play)
            }
            // The Gym (booked habits): today's session, "Did you go?" once it's over, or where it was rebooked.
            if !(model.sessions?.cards ?? []).isEmpty {
                SessionCardsView(palette: palette)
                    .padding(.bottom, MekaSpace.l)
                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .bottom)))
                    .staggeredAppear(step(.upNext), play: play)
            }
            // Quick alarms and timers typed into capture ("alarm 6:30", "timer 20 min"), each with ✕.
            if !model.quickAlarms.isEmpty {
                QuickAlarmRowsView(palette: palette)
                    .padding(.bottom, MekaSpace.l)
                    .staggeredAppear(step(.upNext), play: play)
            }
        }
    }

    /// The day's one timeline (Schedule).
    @ViewBuilder
    private var scheduleBlock: some View {
        if let today = model.today {
            // One timeline under "Today": the All day group first (one row each, at most 3 then "+2
            // more"), finished events folded, events and planned tasks in time order with the now line
            // and free gaps; then tasks with no time.
            let tl = today.timeline
            // The label says what the section holds ("Today · Work 09:00–17:30 · 2 events"; Fold review
            // 2026-10-09 07:26, item 6), as on the Fold.
            // The Today rebuild, slice 2d-2d (the Fold's point (e)): the whole schedule is the mockup's one compact card
            // (core ScheduleCardRules), headed SCHEDULE: "All day" in the narrow time column of the first all-day row, the
            // earlier fold, then the rows without the free gaps (the now line still says what's free from now).
            if tl.head != nil {
                SectionLabel(EveningCardRules.shared.scheduleLabel(sectionLabel: tl.sectionLabel), palette)
                    .staggeredAppear(step(.timeline), play: play)
                ScheduleCard(palette: palette) {
                    let shown = AllDayRules.shared.shown(items: tl.allDayItems, open: allDayOpen)
                    ForEach(Array(shown.enumerated()), id: \.element.event.id) { i, a in
                        AllDayRow(item: a, palette: palette, timeLabel: ScheduleCardRules.shared.allDayTime(index: Int32(i)))
                            .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .top)))
                    }
                    if let more = AllDayRules.shared.moreLabel(items: tl.allDayItems, open: allDayOpen) {
                        AllDayMore(label: more, open: $allDayOpen, palette: palette)
                    }
                    if let label = tl.earlierLabel {
                        EarlierToggle(label: label, open: $earlierOpen, palette: palette)
                        if earlierOpen {
                            ForEach(tl.earlier, id: \.id) { r in TimelineEventRow(row: r, past: true, palette: palette).transition(.opacity) }
                        }
                    }
                    ForEach(ScheduleCardRules.shared.rows(rows: tl.rows), id: \.id) { r in
                        timelineRow(r)
                    }
                }
                .staggeredAppear(step(.timeline), play: play)
                Spacer().frame(height: MekaSpace.l)
            }
        }
    }

    /// The Today rebuild, slice 2d-2e: NEWS below Also today, the mockup's row of picture cards that drifts on to the
    /// next card on its own (core TodayNewsRules, as the Fold's); Appearance → News ticker's Off still hides it.
    private var newsBlock: some View {
        MacTodayNewsRow(palette: palette)
            .staggeredAppear(step(.cards), play: play)
    }

    /// The Today rebuild, slice 2d-2b: ALSO TODAY below the schedule (core AlsoTodayRules), with the trains from the
    /// weather, this week's goals, meeting prep, a day stacking up and the Sunday study check-in folded in. A tile opens
    /// its place (AlsoTodayRules.place); prep, the check-in and a day stacking up unfold under the row.
    @ViewBuilder
    private var alsoBlock: some View {
        if let today = model.today {
            // The check-in's Looks good puts it away in the core, so the tile goes with it.
            let checkin = today.studyCheckin
            let tiles = AlsoTodayRules.shared.withHeader(
                tiles: today.alsoToday, route: model.weather?.route,
                goalsLine: model.goals?.week.line, goalsSpoken: model.goals?.week.spoken ?? "",
                prep: today.meetingPrep, pressure: today.pressure, checkin: checkin,
                review: EveningCardRules.shared.reviewForTile(review: model.review?.card, card: eveningCard))
            if !tiles.isEmpty {
                MacAlsoTodayRow(tiles: tiles, palette: palette, unfolded: alsoUnfolded, onOpen: openAlso) { kind in
                    alsoDetail(kind, today: today, checkin: checkin)
                }
                .padding(.bottom, MekaSpace.l)
                .staggeredAppear(step(.cards), play: play)
                .sheet(isPresented: Binding(get: { alsoCourse != nil }, set: { if !$0 { alsoCourse = nil } })) {
                    if let id = alsoCourse {
                        StudySheet(courseId: id, palette: palette, onClose: { alsoCourse = nil }, onRemoved: { _ in alsoCourse = nil })
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func alsoDetail(_ kind: AlsoKind, today: Today, checkin: StudyCheckin?) -> some View {
        if kind == AlsoKind.prep, let p = today.meetingPrep {
            MacPrepDetail(prep: p, palette: palette)
        } else if kind == AlsoKind.pressure, let p = today.pressure {
            MacPressureDetail(pressure: p, palette: palette) { fix in
                alsoUnfolded = nil
                model.easePressure(taskId: fix.taskId, toDay: fix.toDay)
            }
        } else if kind == AlsoKind.studyWeek, let c = checkin {
            StudyCheckinCard(checkin: c, palette: palette)
        } else if kind == AlsoKind.trains, let t = model.weather?.trains {
            MacTrainsDetail(detail: t, palette: palette)
        }
    }

    /// Where an Also today tile's click goes (core AlsoTodayRules.place), as on the Fold.
    private func openAlso(_ tile: AlsoTile) {
        let place = AlsoTodayRules.shared.place(tile: tile)
        switch place {
        case .unfold:
            let kind = tile.kind
            alsoUnfolded = alsoUnfolded == kind ? nil : kind
        case .study: if let id = tile.targetId { alsoCourse = id }
        case .renewals: model.go(to: .lists, reduced: reduceMotion)
        case .weekAhead: model.showWeekAhead = true
        case .trainQueue: model.showTrainQueue = true
        case .bins: model.showBins = true
        case .trips: model.showTrips = true
        case .gifts: model.showGifts = true
        case .dinners: model.showMeals = true
        case .dateNight: model.showDateNight = true
        case .lifeAdmin: model.showLifeAdmin = true
        case .school: model.showSchool = true
        case .mail: model.showMail = true
        case .review: model.openReviewCard(reduced: reduceMotion)
        default: break
        }
    }

    /// The moment cards: the Motion card, the morning brief, the weekly review.
    @ViewBuilder
    private var momentCards: some View {
        // Calm Today, slice 3: Up next and the timeline lead; the moment cards follow them (core
        // TodayOrderRules), then Needs you, Anytime today and done today.
        // Motion pass 2: with Reduce Motion on and nothing chosen in Appearance → Motion, a one-time card.
        MotionSystemCardView(palette: palette)
            .staggeredAppear(step(.cards), play: play)

        // Morning brief: the card rises in when the morning starts and goes at noon or once read (Got it, or
        // the sheet open long enough to read: Calm Today); it folds away, shrinking to its top as it fades.
        if let b = model.brief, b.offered {
            BriefCard(brief: b, palette: palette)
                .transition(reduceMotion ? AnyTransition.opacity : .asymmetric(
                    insertion: AnyTransition.opacity.combined(with: .move(edge: .bottom)),
                    removal: AnyTransition.opacity.combined(with: .scale(scale: 0.96, anchor: .top))))
                .padding(.bottom, MekaSpace.l)
                .staggeredAppear(step(.cards), play: play)
        } else if let line = model.brief?.readElsewhereLine {
            // Read on the Fold this morning: a slim line in the card's place until noon.
            BriefReadLineView(line: line, palette: palette)
                .transition(.opacity)
                .padding(.bottom, MekaSpace.l)
                .staggeredAppear(step(.cards), play: play)
        }

        // The Today rebuild, slice 2d-2c: the weekly review is no longer a card of its own; Sunday's rides on the
        // evening card as its first step, Monday's "Review last week" is Also today's REVIEW tile.
    }

    /// "This evening · 1 of 3" (core EveningCardRules), or nil before the evening or with the card switched off.
    private var eveningCard: EveningCard? {
        guard let s = model.shutdown else { return nil }
        return EveningCardRules.shared.evening(shutdown: s, review: model.review?.card ?? ReviewCard.companion.NONE)
    }

    /// Later on the evening card, while 21:00 is at least a quarter of an hour away.
    private func laterAction(today: Int64, minute: Int32) -> (() -> Void)? {
        guard EveningCardRules.shared.laterOffered(minute: minute) else { return nil }
        let reduced = reduceMotion
        return { withAnimation(MekaMotion.appear(reduced: reduced)) { eveningLaterDay = today } }
    }

    /// The minute of the day now, for Later's 21:00.
    private var clockMinute: Int32 {
        let c = Calendar.current.dateComponents([.hour, .minute], from: Date())
        return Int32((c.hour ?? 0) * 60 + (c.minute ?? 0))
    }

    /// The evening: from 17:00 one "This evening" card under Now (the Today rebuild, slice 2d-2c; as the Fold's 2a) with
    /// the review, the shutdown and tomorrow as its steps; without it (switched off) the shutdown card, Tomorrow at a
    /// glance and the Tomorrow peek as before.
    @ViewBuilder
    private var eveningBlock: some View {
        let today = model.todayEpochDay
        let minute = clockMinute
        if let card = eveningCard {
            if let shown = EveningCardRules.shared.shown(card: card, laterDay: eveningLaterDay.map { KotlinLong(value: $0) },
                                                         today: today, minute: minute) {
                MacEveningCard(
                    card: shown, palette: palette,
                    onStep: { kind in
                        switch kind {
                        case .review: model.openReviewCard(reduced: reduceMotion)
                        case .shutDown: model.showShutdown = true
                        default: model.openTomorrow()
                        }
                    },
                    onLater: laterAction(today: today, minute: minute))
                .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .bottom)))
                .padding(.bottom, MekaSpace.l)
                .staggeredAppear(step(.evening), play: play)
            }
        } else if let s = model.shutdown {
            // Evening shutdown: the card rises in when the evening starts; once done, one quiet line stays.
            if s.offered {
                ShutdownCard(shutdown: s, palette: palette)
                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .bottom)))
                    .padding(.bottom, MekaSpace.l)
                    .staggeredAppear(step(.cards), play: play)
            } else if (s.evening && s.peek == nil) || s.doneLine != nil {
                // Tomorrow at a glance once the evening starts (after shutting down, or while still at work);
                // with the Tomorrow peek up, only "Day shut down" stays.
                TomorrowGlanceView(doneLine: s.doneLine, glance: s.evening && s.peek == nil ? s.tomorrow.glance : nil, palette: palette)
                    .padding(.bottom, MekaSpace.l)
                    .transition(.opacity)
            }
            // The Tomorrow peek (Meka 2026-10-26): from 17:00 or once shut down; clicking opens the Tomorrow sheet.
            if let peek = s.peek {
                TomorrowPeekView(peek: peek, palette: palette)
                    .padding(.bottom, MekaSpace.l)
                    .transition(.opacity)
            }
        }
    }

    /// The evening receipt: what Navilife handled today.
    @ViewBuilder
    private var receiptBlock: some View {
        if let s = model.shutdown {
            // Daily receipt: in the evening, what MEKA handled today; clicking opens Activity.
            if s.evening, let r = s.receipt {
                DailyReceiptView(receipt: r, palette: palette)
                    .padding(.bottom, MekaSpace.l)
                    .transition(.opacity)
            }
        }
    }

    /// Needs you's tasks (unless a Needs you column sits beside Today), Today's own tasks and done today.
    @ViewBuilder
    private func tasksBlock(listsNeedsYou: Bool) -> some View {
        if let today = model.today {
            let tl = today.timeline
            // Listed here unless a Needs you column sits beside Today (never shown twice).
            if !today.needsYou.isEmpty && listsNeedsYou {
                SectionLabel("Needs you", palette).staggeredAppear(step(.needsYou), play: play)
                ForEach(today.needsYou, id: \.task.id) { item in
                    TaskRow(task: item.task, reason: item.reason, palette: palette).staggeredAppear(step(.needsYou), play: play)
                }
                Spacer().frame(height: MekaSpace.l)
            }
            if tl.anytime.isEmpty {
                // Nothing unplanned today: the Day list is still one click away, to type a day's tasks fast.
                DayListLink(label: DayListRules.shared.EMPTY_LINK, palette: palette)
                    .padding(.bottom, MekaSpace.l)
                    .staggeredAppear(step(.anytime), play: play)
            }
            if !tl.anytime.isEmpty {
                // Beside the calmer mockup (2026-10-29): the label counts the open tasks and a hairline
                // sits between rows (never under the last), as on the Fold.
                // The Today rebuild, slice 2d-2c (as the Fold's 2a): "Today · 2 left · 40m" with the gold done bar.
                let anytime = tl.anytime
                let head = EveningCardRules.shared.head(open: anytime, done: today.doneToday)
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    HStack(alignment: .firstTextBaseline) {
                        SectionLabel(head.label, palette)
                            .contentTransition(.opacity)
                        Spacer(minLength: MekaSpace.xs)
                        DayListLink(label: DayListRules.shared.LINK, palette: palette)
                    }
                    MacTodayDoneBar(progress: head.progress, palette: palette)
                        .padding(.bottom, MekaSpace.xxs)
                }
                .staggeredAppear(step(.anytime), play: play)
                ForEach(Array(anytime.enumerated()), id: \.element.id) { i, t in
                    TaskRow(task: t, reason: nil, palette: palette)
                        .overlay(alignment: .bottom) {
                            if TodayBesideRules.shared.dividerAfter(index: Int32(i), count: Int32(anytime.count)) {
                                Rectangle().fill(palette.hairline).frame(height: 1)
                            }
                        }
                        .staggeredAppear(step(.anytime), play: play)
                }
                // "+ Add for today · Enter for the next" opens the Day list, where Enter adds one after another.
                Button { MekaHaptics.tick(); model.openDayList() } label: {
                    Text(EveningCardRules.shared.ADD_LINE).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                }
                .buttonStyle(MekaPressStyle())
                .help("Type today's tasks one after another (⌘N)")
                .accessibilityLabel("Add tasks for today")
                .padding(.vertical, MekaSpace.xxs)
                .staggeredAppear(step(.anytime), play: play)
            }
            let doneChoice = model.doneTodayShown
            if !today.doneToday.isEmpty && doneChoice.show != .hidden {
                // Straight under the day's section, which already ends with its own spacing (Meka's
                // 10:48 screenshots: a double gap left a large empty stretch above it). The Sunsama tick
                // (Week board by hand, slice 6): the day's done tasks stay, ticked and struck through.
                // Calmer Today, slice 1: Folded (the default) is one line, "2 done today ›", that opens them.
                let top = tl.head != nil && tl.anytime.isEmpty ? 0 : MekaSpace.l
                if let line = DoneTodayRules.shared.foldLine(doneToday: today.doneToday, view: doneChoice) {
                    DoneFoldLine(line: line, label: DoneTodayRules.shared.foldLabel(doneToday: today.doneToday, view: doneChoice), palette: palette) {
                        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { model.toggleDoneTodayFold() }
                    }
                    .padding(.top, top)
                    .staggeredAppear(step(.done), play: play)
                }
                if let doneLabel = DoneTodayRules.shared.heading(doneToday: today.doneToday, view: doneChoice) {
                    SectionLabel(doneLabel, palette)
                        .padding(.top, top)
                        .staggeredAppear(step(.done), play: play)
                }
                ForEach(DoneTodayRules.shared.rows(doneToday: today.doneToday, view: doneChoice, more: doneOpen), id: \.id) { t in
                    DoneTodayRow(task: t, palette: palette).staggeredAppear(step(.done), play: play)
                }
                if let more = DoneTodayRules.shared.moreLine(doneToday: today.doneToday, view: doneChoice, more: doneOpen) {
                    Button(more) { withAnimation(MekaMotion.appear(reduced: reduceMotion)) { doneOpen = true } }
                        .buttonStyle(.plain).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .padding(.vertical, MekaSpace.xs)
                }
            }
        }
    }

    /// Coming up and News under Today's own day when they have no column of their own.
    @ViewBuilder
    private var comingUpBlock: some View {
        // Mac presentation pass, slice 1: two columns put Coming up and News under Today's own day.
        if MacCommandCentreRules.shared.comingUpUnderToday(layout: MacCommandCentreRules.shared.layout(contentWidthPt: Float(width))) {
            ComingUpColumnView(shared: true, palette: palette, embedded: true)
                .environment(\.mekaStaggerPlays, play)
                .padding(.top, MekaSpace.l)
                .transition(.opacity)
        }
    }


    /// Appearance → Play the opening: the Day ring, its tiles, the greeting and the stagger play again.
    private func takeOpeningRequest() {
        guard model.openingRequested else { return }
        model.openingRequested = false
        ringPlay = reduceMotion ? .still : .full
        introPlayed = false
        openings += 1
    }

    /// A section's step in the opening stagger, in the order Today's sections sit (Calm Today, slice 3).
    private func step(_ slot: TodaySlot) -> Int { Int(TodayOrderRules.shared.stagger(slot: slot)) }

    @ViewBuilder
    private func timelineRow(_ r: TimelineRow) -> some View {
        switch r.kind {
        case .event: TimelineEventRow(row: r, past: false, palette: palette).eventActions(r.event, palette: palette)
        case .task:
            if let t = r.task { TaskRow(task: t, reason: nil, palette: palette, time: r.time, timelineLine: r.detail, late: r.late) }
        case .gap: GapRow(row: r, palette: palette)
        case .now: NowLine(row: r, palette: palette)
        case .session: SessionTimelineRow(row: r, palette: palette)
        case .work: WorkTimelineRow(row: r, palette: palette)
        case .commute: CommuteTimelineRow(row: r, palette: palette)
        default: EmptyView()
        }
    }

    /// One name everywhere (2026-10-21): About you's name, as the orb's hello and the brief say it; none, no name.
    private var greeting: String {
        model.todayGreeting(callName: model.aboutYou?.callName)
    }
}

/// "Day list ›" beside Anytime today (or "Plan today’s tasks · Day list ›" when nothing is unplanned): opens the Day list.
struct DayListLink: View {
    @Environment(CoreModel.self) private var model
    let label: String
    let palette: MekaPalette

    var body: some View {
        Button { MekaHaptics.tick(); model.openDayList() } label: {
            Text(label).font(MekaType.itemMeta).foregroundStyle(palette.accent)
        }
        .buttonStyle(MekaPressStyle())
        .help("Type today's tasks one after another (⌘N)")
        .accessibilityLabel("Open the Day list")
    }
}

struct SectionLabel: View {
    let text: String
    let palette: MekaPalette
    init(_ text: String, _ palette: MekaPalette) { self.text = text; self.palette = palette }
    var body: some View {
        Text(text.uppercased())
            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
            .padding(.bottom, MekaSpace.xxs)
    }
}

struct TaskRow: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.scheduleCard) private var card
    @Environment(\.selectionNamespace) private var selectionNamespace
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let task: MekaTask
    let reason: NeedsYouReason?
    let palette: MekaPalette
    /// Where the row is on screen, so its detail can grow out of it.
    @State private var frame: CGRect = .zero
    /// The Sunsama tick (Week board by hand, slice 6): ticked, the row keeps its place struck through for a moment.
    @State private var ticking = false

    /// On the timeline: the time column on the left and the core's line ("30 min · ↻ Every weekday") under the title.
    let time: String?
    let timelineLine: String?
    /// A planned task whose time has gone by: its line ("Since 09:15 · 30 min") is lit in the accent colour.
    let late: Bool

    /// Roll-over: a "From yesterday" row's line ("Carried 2 days"), lit in the accent colour.
    let carriedLine: String?

    init(task: MekaTask, reason: NeedsYouReason?, palette: MekaPalette, time: String? = nil, timelineLine: String? = nil, late: Bool = false, carriedLine: String? = nil) {
        self.task = task
        self.reason = reason
        self.palette = palette
        self.time = time
        self.timelineLine = timelineLine
        self.late = late
        self.carriedLine = carriedLine
    }

    var body: some View {
        HStack(spacing: MekaSpace.m) {
            if let time {
                Text(TimelineMetrics.shown(time, card: card)).font(MekaType.itemMeta).monospacedDigit().foregroundStyle(palette.textSecondary)
                    .lineLimit(card ? 1 : nil)
                    .frame(width: TimelineMetrics.column(card: card) - MekaSpace.m, alignment: .leading)
            }
            CompleteButton(id: task.id, title: task.title, palette: palette, settle: true) { ticking = true }
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(TaskTitleRules.shared.display(title: task.title)).font(MekaType.taskTitle)
                    .strikethrough(ticking, color: palette.textTertiary)
                    .foregroundStyle(ticking ? palette.textTertiary : palette.textPrimary)
                    .lineLimit(2)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: ticking)
                if let line = subtitle {
                    // The calmer scale (beside the mockup, 2026-10-29): a row's line in the caption, as the day's times.
                    Text(line).font(MekaType.caption)
                        // Gold is for clicks (the Today rebuild's point (i)): a late or carried line is amber (core TodayInkRules).
                        .foregroundStyle(TodayInkRules.shared.taskLine(
                            late: late && reason == nil && time != nil, carried: carriedLine != nil && reason == nil, flagged: isAlert
                        )?.color(palette) ?? palette.critical)
                }
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
        .background {
            ZStack {
                // Just landed from the plan: a soft accent light that settles.
                if landed {
                    RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.accent.opacity(0.14))
                        .transition(.opacity)
                }
                // One highlight per list, gliding to whichever row is selected.
                if model.selectedID == task.id {
                    RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised)
                        .selectionGlide(selectionNamespace, reduced: reduceMotion)
                }
            }
            .animation(MekaMotion.appear(reduced: reduceMotion), value: landed)
        }
        .contentShape(Rectangle())
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame = $0 }
        .onTapGesture { model.select(task.id, reduced: reduceMotion, origin: frame) }
        .transition(.asymmetric(insertion: .opacity, removal: .opacity.combined(with: .scale(scale: 0.96))))
    }

    private var isAlert: Bool { reason == .conflict || reason == .overdue }
    private var landed: Bool { SharedMotion.highlightLanded(task.id, planOpen: model.showPlan, landing: model.landing) }

    private var subtitle: String? {
        switch reason {
        case .conflict: "Edited on two devices — choose a version"
        case .overdue: "Overdue"
        case .dueTodayUnscheduled: "Due today · not scheduled"
        default: carriedLine ?? (time != nil ? timelineLine : model.repeatLine(task))
        }
    }
}

/// Connected calendars. Connecting opens the provider's own sign-in page in the browser; MEKA OS never sees the
/// password. The list refreshes whenever the app becomes active again (i.e. when the owner returns from the browser).
/// Connected calendars and feeds; opened from Today's header or Ask's More list (sheet lives on the shell).
struct CalendarsSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text("Calendars").font(MekaType.upNextTitle)
            Text(CalendarAccessRules.shared.header(anyEditable: (model.accounts ?? []).contains { $0.canEdit }))
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
            if let accounts = model.accounts {
                if accounts.isEmpty {
                    Text("No calendars connected yet.").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                }
                ForEach(Array(accounts.enumerated()), id: \.element) { i, a in
                    HStack {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            // Titled like its main calendar ("Personal", "Hotmail", Meka's own) or the feed's name;
                            // the address and "Headlines" (never "news_more") under it (Meka's screenshot 2026-10-09 09:01).
                            Text(a.title).font(MekaType.itemTitle)
                            // On the core's clock and zone, like Health's (quality gate slice 33).
                            Text(a.shownStatus).font(MekaType.caption)
                                .foregroundStyle(a.needsReconnect ? palette.critical : palette.textTertiary)
                            // Whether its events come through: "No events in the next 30 days" says an empty calendar,
                            // not a broken one (Meka's 10:48 screenshots, 2026-10-09).
                            if let events = a.eventsLine {
                                Text(events).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            }
                            // Calendar editing: read-only or editing allowed; the line cross-fades as it changes.
                            if let line = a.editingShown {
                                Text(line).font(MekaType.caption)
                                    .foregroundStyle(a.canEdit ? palette.accent : palette.textTertiary)
                                    .contentTransition(.opacity)
                            }
                        }
                        Spacer()
                        let provider = a.provider, email = a.email, canEdit = a.canEdit
                        if a.needsReconnect {
                            Button("Reconnect") {
                                Task { await model.connectCalendar(provider, editing: CalendarAccessRules.shared.reconnectAsksEditing(canEdit: canEdit)) }
                            }
                        }
                        if let action = a.editingAction {
                            Button(action.label) {
                                if canEdit {
                                    Task { await model.stopCalendarEditing(provider: provider, email: email) }
                                } else {
                                    MekaHaptics.tick()
                                    Task { await model.connectCalendar(provider, editing: true) }
                                }
                            }
                            .buttonStyle(MekaPressStyle())
                            .foregroundStyle(canEdit ? palette.textSecondary : palette.accent)
                        }
                    }
                    .padding(MekaSpace.m)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                    .staggeredAppear(i)
                }
            } else {
                SkeletonRows(count: 2, rowHeight: 48, palette: palette)
            }
            // On Today (all-day polish): a planning calendar can stay in the Calendar section but off Today. Synced.
            if !model.calendarsOnToday.isEmpty {
                SectionLabel("On Today", palette).padding(.top, MekaSpace.m)
                Text("Turn a calendar off to keep it in the Calendar section but off Today and your day.")
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                ForEach(Array(model.calendarsOnToday.enumerated()), id: \.element.key) { i, c in
                    CalendarOnTodayRow(choice: c, palette: palette)
                        .staggeredAppear(i)
                }
            }
            // Weather place setting: the town the forecast is for (home unless Meka types another). Synced.
            WeatherPlaceSection(palette: palette).padding(.top, MekaSpace.m)
            HStack {
                Button("Connect Google Calendar") { Task { await model.connectCalendar("google") } }
                Button("Connect Outlook Calendar") { Task { await model.connectCalendar("microsoft") } }
            }
            if let note = model.calendarsNote {
                Text(note).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            if let message = model.calendarsMessage {
                Text(message).font(MekaType.caption).foregroundStyle(palette.critical)
            }
            HStack {
                Button("Refresh") { Task { await model.loadAccounts() } }
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        // Calendar editing: the header and each account's editing line cross-fade as editing turns on or off.
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.accounts?.map(\.canEdit) ?? [])
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.calendarsNote)
        .task { await model.loadAccounts() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            Task { await model.loadAccounts() }
        }
    }
}

/// "Plan my day": tasks fitted around events and fixtures, as a suggestion applied only on request.
private struct PlanSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let palette: MekaPalette

    private struct Row: Identifiable { let id: String; let start: Int64; let time: String; let title: String; let suggested: Bool }
    private var habitNote: String? {
        guard let plan = model.plan else { return nil }
        var parts: [String] = []
        if !plan.habits.isEmpty { parts.append("↻ Room for habits that are due. Tick them in Goals when they're done.") }
        if let kept = plan.keptLine { parts.append(kept) }
        if !plan.habitsUnplaced.isEmpty { parts.append("No room today for: " + plan.habitsUnplaced.map(\.title).joined(separator: ", ")) }
        return parts.isEmpty ? nil : parts.joined(separator: "\n")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text("Your day").font(MekaType.upNextTitle)
            if let plan = model.plan {
                if plan.isBlank {
                    Text("Nothing to plan: every open task is already scheduled.")
                        .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                } else {
                    Text("A suggestion. Nothing changes until you apply it.")
                        .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    // Timeline blocks cascade in, 40 ms apart.
                    ForEach(Array(rows(plan).enumerated()), id: \.element.id) { i, r in
                        HStack {
                            Text(r.time).font(MekaType.itemMeta).monospacedDigit().foregroundStyle(palette.textSecondary)
                                .frame(width: 110, alignment: .leading)
                            Text(r.title).font(MekaType.itemTitle)
                                .foregroundStyle(r.suggested ? palette.textPrimary : palette.textTertiary)
                            Spacer()
                        }
                        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(r.suggested ? palette.surfaceRaised : .clear))
                        .staggeredAppear(i)
                    }
                    // "Won't fit today: …", or "Waits for Mon 12 Oct: …" on a protected day (Protected time).
                    if let line = plan.unplacedLine {
                        Text(line)
                            .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    }
                    if let note = habitNote {
                        Text(note).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    CountUpText(Int(plan.freeMinutesLeft)) { "\($0 / 60) h \($0 % 60) min still free." }
                        .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    if !plan.isEmpty, let c = model.planCalendar, c.available {
                        PlanCalendarToggle(setting: c, palette: palette) { model.setPlanToCalendar($0) }
                    }
                }
            } else {
                SkeletonRows(count: 4, palette: palette)
            }
            HStack {
                Spacer()
                Button("Close") { dismiss() }.keyboardShortcut(.cancelAction)
                if let plan = model.plan, !plan.isEmpty {
                    Button("Apply plan") { MekaHaptics.light(); Task { await model.applyPlan() } }.keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .task { await model.loadPlan() }
    }

    private func rows(_ plan: DayPlanner.Plan) -> [Row] {
        let f = Date.FormatStyle.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits)
        func t(_ ms: Int64) -> String { Date(timeIntervalSince1970: Double(ms) / 1000).formatted(f) }
        let busy = plan.busy.map { Row(id: "e" + $0.id, start: $0.startAtMs, time: "\(t($0.startAtMs))–\(t($0.endAtMs))", title: $0.title, suggested: false) }
        let tasks = plan.placements.map { Row(id: "t" + $0.task.id, start: $0.startMs, time: "\(t($0.startMs))–\(t($0.endMs))", title: $0.task.title, suggested: true) }
        let habits = plan.habits.map { Row(id: "h" + $0.habitId, start: $0.startMs, time: "\(t($0.startMs))–\(t($0.endMs))", title: "↻ " + $0.title + ($0.behind ? " · behind" : ""), suggested: true) }
        let meals = plan.meals.map { Row(id: "m" + $0.title, start: $0.startMs, time: "\(t($0.startMs))–\(t($0.endMs))", title: "◐ " + $0.title, suggested: true) }
        return (busy + tasks + habits + meals).sorted { $0.start < $1.start }
    }
}

/// Up next (Fold review 2026-10-09, item 3): the menu bar's "now" card face — "UP NEXT", the title (semibold 22 pt),
/// its line and Done · Tomorrow · Open — instead of a bare title with a ring. A tap (or Open) grows the task's detail
/// out of the card; Done completes it as the ring did.
private struct UpNextCard: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let card: NowView
    let palette: MekaPalette
    @State private var frame: CGRect = .zero

    var body: some View {
        NowCardFace(
            v: card, palette: palette,
            openTask: { id in model.select(id, reduced: reduceMotion, origin: frame) },
            openEvent: { e in model.openEvent = e }
        )
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame = $0 }
        .mekaHoverLift()
    }
}

/// Completion motion (catalogue "Complete a task"; motion pass 2, slice 4): the accent ring sweeps round, fills and
/// the check strokes in (`CheckRingView`, `checkDraw`) with a light haptic, then the row leaves. Also used by the
/// evening shutdown. Motion → Off: shown done at once.
/// A task done today, at Today's foot (Week board by hand, slice 6): the circle filled with its tick, the title struck
/// through and dimmed. A click on the circle un-ticks it (Undo on the bar ticks it again); on the row, opens it.
struct DoneTodayRow: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let task: MekaTask
    let palette: MekaPalette

    var body: some View {
        HStack(spacing: MekaSpace.m) {
            Button { model.reopenOnBoard(task.id, title: task.title) } label: {
                TickRingView(done: true, palette: palette)
                    .frame(width: 22, height: 22)
                    .contentShape(Circle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel(TodayTickRules.shared.untickLabel(title: task.title))
            Text(TaskTitleRules.shared.display(title: task.title)).font(MekaType.taskTitle)
                .strikethrough(true, color: palette.textTertiary)
                .foregroundStyle(palette.textTertiary)
                .lineLimit(2)
            Spacer()
        }
        .padding(.vertical, MekaSpace.xs)
        .padding(.horizontal, MekaSpace.xs)
        .contentShape(Rectangle())
        .onTapGesture { model.select(task.id, reduced: reduceMotion) }
        .transition(.opacity)
    }
}

/// Calmer Today, slice 1 (the approved mockup): Done today folded into one quiet line, "2 done today ›", beside a small
/// filled check; a click opens the ticked rows under it ("2 done today · hide" closes them) and the text cross-fades.
/// Reduce Motion: at once.
struct DoneFoldLine: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let line: String
    let label: String
    let palette: MekaPalette
    let toggle: () -> Void

    var body: some View {
        Button {
            MekaHaptics.light()
            toggle()
        } label: {
            HStack(spacing: MekaSpace.m) {
                TickRingView(done: true, palette: palette).frame(width: 22, height: 22)
                Text(line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
                Spacer()
            }
            .padding(.vertical, MekaSpace.xs)
            .padding(.horizontal, MekaSpace.xs)
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(label)
    }
}

struct CompleteButton: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let id: String
    let title: String
    let palette: MekaPalette
    var size: CGFloat = 22
    /// Sunsama-style (Week board by hand, slice 6): after the draw the row keeps its place a moment before it settles.
    var settle = false
    /// Told at the click, so the row can strike its title through at once.
    var onPressed: (() -> Void)?
    @State private var began: Date?

    init(task: MekaTask, palette: MekaPalette) {
        self.init(id: task.id, title: task.title, palette: palette)
    }

    /// A task known by its id and title (the brief's Today rows, Fold review 2026-10-09 07:26, item 7).
    init(id: String, title: String, palette: MekaPalette, size: CGFloat = 22, settle: Bool = false, onPressed: (() -> Void)? = nil) {
        self.id = id
        self.title = title
        self.palette = palette
        self.size = size
        self.settle = settle
        self.onPressed = onPressed
    }

    var body: some View {
        Button {
            guard began == nil else { return }
            began = Date()
            MekaHaptics.light()
            onPressed?()
            let taskId = id
            let draw = MotionMath.checkDraw(reduced: reduceMotion)
            let drawMs = Int(draw * 1000)
            let wait = settle ? Int(TodayTickRules.shared.settleWaitMs(drawMs: Int32(drawMs), reduced: reduceMotion)) : 0
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(drawMs + 50 + wait))
                model.complete(taskId)
            }
        } label: {
            CheckRingView(palette: palette, began: began)
                .frame(width: size, height: size)
                .contentShape(Circle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel("Complete \(title)")
    }
}

/// The capture bar: "Capture anything…" with the Talk mic at its trailing end (Fold review 2026-10-09 07:26, item 5:
/// the mic sits here, not on a card). The mic presses in and brings Ask forward, already listening (as ⌥Space).
/// Voice notes, slice 3: holding the mic (half a second) records a voice note instead, with a light haptic as the
/// voice note sheet opens; the click that ends the hold doesn't also start Talk. Its context menu and the
/// accessibility action "Voice note" do the same.
private struct CaptureField: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var text = ""
    @FocusState private var focused: Bool
    /// A hold just started a voice note: the button's own click (on release) is swallowed.
    @State private var held = false

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            TextField("Capture anything…", text: $text)
                .textFieldStyle(.plain)
                .font(MekaType.body)
                .focused($focused)
                .padding(.vertical, MekaSpace.m)
                .onSubmit { model.captureTyped(text); text = "" }
                .onChange(of: model.focusCapture) { focused = true }
            Button {
                if held { held = false; return }
                model.requestTalk(reduced: reduceMotion)
            } label: {
                VoiceOrbView(phase: .ended, level: 0, palette: palette, size: 30)
            }
            .buttonStyle(MekaPressStyle())
            .simultaneousGesture(LongPressGesture(minimumDuration: 0.5).onEnded { _ in
                held = true
                MekaHaptics.light()
                model.startVoiceNote()
                // If the release lands off the button no click comes: the next click talks as usual.
                Task {
                    try? await Task.sleep(for: .seconds(2))
                    held = false
                }
            })
            .contextMenu {
                Button("Take a voice note") { model.startVoiceNote() }
                Button("Read a picture…") { model.choosePictureToRead() }
                Button("Read the copied picture") { model.readCopiedPicture() }
            }
            .help(VoiceNoteRules.shared.MAC_HINT)
            .accessibilityLabel("Talk to Navilife")
            .accessibilityAction(named: "Voice note") { model.startVoiceNote() }
        }
        .padding(.leading, MekaSpace.l)
        .padding(.trailing, MekaSpace.xs)
        .background(Capsule().fill(palette.surfaceRaised))
    }
}

/// The detail beside a list. Selecting another task slides the new one across from the trailing edge (cross-fade
/// with Reduce Motion); each task gets its own fresh title field.
struct DetailView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let task: MekaTask?
    let palette: MekaPalette
    /// Whether a task opened from a row grows out of it (the list beside it); off for search's own detail.
    let growsFromRow: Bool
    /// Container transform (motion pass 2): the row it grew out of (global frame), the detail's own frame and how far
    /// it has grown (0 = row-sized, 1 = the whole detail).
    @State private var origin: CGRect?
    @State private var bounds: CGRect = .zero
    @State private var grown: Double = 1

    init(task: MekaTask?, palette: MekaPalette, growsFromRow: Bool = false) {
        self.task = task
        self.palette = palette
        self.growsFromRow = growsFromRow
    }

    /// Grows out of a row when one was tapped (the content then fades in); otherwise pushes across as before.
    private var contentTransition: AnyTransition {
        if reduceMotion { return .opacity }
        if growsFromRow && model.selectionOrigin != nil { return .opacity }
        return AnyTransition.push(from: .trailing).combined(with: .opacity)
    }

    var body: some View {
        ZStack {
            DetailContent(task: task, palette: palette)
                .id(task?.id ?? "")
                .transition(contentTransition)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .clipped()
        .padding(MekaSpace.gutterWide)
        .background(palette.surface)
        .clipShape(ContainerShape(from: origin.flatMap { MotionMath.containerOrigin(row: $0, pane: bounds) },
                                  progress: grown, rowRadius: MekaRadius.m))
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { bounds = $0 }
        .onChange(of: task?.id, initial: true) { _, id in grow(id) }
    }

    /// A task just opened from a row: start row-sized and grow on the expand spring; anything else: shown whole.
    private func grow(_ id: String?) {
        guard growsFromRow, id != nil, !reduceMotion, let row = model.selectionOrigin else {
            origin = nil
            grown = 1
            return
        }
        model.selectionOrigin = nil
        origin = row
        grown = 0
        withAnimation(MekaMotion.expand(reduced: false)) { grown = 1 }
    }
}

/// The detail's clip while it grows out of a row: a rounded rectangle travelling from the row's frame (`from`, in
/// the detail's own coordinates) to the whole detail. No row: the whole detail.
private struct ContainerShape: Shape {
    var from: CGRect?
    var progress: Double
    let rowRadius: CGFloat

    nonisolated var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    nonisolated func path(in rect: CGRect) -> Path {
        guard let from else { return Path(rect) }
        let bounds = MotionMath.containerBounds(from: from, to: rect, progress: progress)
        let corner = MotionMath.containerCorner(from: rowRadius, to: 0, progress: progress)
        return Path(roundedRect: bounds, cornerRadius: corner)
    }
}

private struct DetailContent: View {
    @Environment(CoreModel.self) private var model
    let task: MekaTask?
    let palette: MekaPalette
    @State private var title = ""
    /// Something typed here hasn't been saved yet.
    @State private var dirty = false
    @State private var pendingSave: Task<Void, Never>?
    @FocusState private var titleFocused: Bool

    /// Saves what was typed onto the task it was typed for, if it changed and isn't blank (`TextAutosave`).
    private func saveTitle(id: String) {
        pendingSave?.cancel()
        pendingSave = nil
        guard dirty else { return }
        dirty = false
        let saved = model.allTasks.first(where: { $0.id == id })?.title ?? ""
        if let t = TextAutosave.shared.titleToSave(typed: title, saved: saved) { model.rename(id, to: t) }
    }

    /// Typing: the title saves itself 0.8 s (`TextAutosave.DELAY_MS`) after the last keystroke.
    private func typed(_ text: String, id: String) {
        title = text.replacingOccurrences(of: "\n", with: " ")
        dirty = true
        pendingSave?.cancel()
        pendingSave = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(800))
            guard !Task.isCancelled else { return }
            saveTitle(id: id)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let task {
                // The title saves itself (Meka, 2026-10-08): shortly after typing stops, on Return, when it loses
                // focus, when another task is shown and when the detail goes away. A blank title is never saved; a
                // save landing mid-typing leaves the field as typed ("Buy " keeps its space before "milk").
                TextField("Title", text: Binding(get: { title }, set: { typed($0, id: task.id) }))
                    .textFieldStyle(.plain)
                    .font(MekaType.upNextTitle)
                    .focused($titleFocused)
                    .onSubmit { saveTitle(id: task.id) }
                    .onAppear { title = task.title; dirty = false }
                    .onChange(of: task.id) { oldId, _ in
                        saveTitle(id: oldId)
                        title = task.title
                    }
                    .onChange(of: task.title) { _, newTitle in
                        if TextAutosave.shared.adoptSaved(typed: title, saved: newTitle, dirty: dirty) { title = newTitle }
                    }
                    .onChange(of: titleFocused) { _, focused in if !focused { saveTitle(id: task.id) } }
                    .onDisappear { saveTitle(id: task.id) }

                ForEach(model.conflicts.filter { $0.taskId == task.id }, id: \.field) { c in
                    Text("EDITED ON TWO DEVICES").font(MekaType.sectionLabel).foregroundStyle(palette.textTertiary)
                    ForEach(c.options, id: \.self) { option in
                        Button(option) { model.resolve(c, with: option) }
                            .buttonStyle(MekaPressStyle())
                            .font(MekaType.itemTitle)
                            .padding(MekaSpace.m)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                    }
                }
                ScrollView {
                    // Entrance: the rows stagger in, 40 ms apart (fresh for each task: the detail is re-made per id).
                    VStack(alignment: .leading, spacing: MekaSpace.m) {
                        WhenRow(task: task, palette: palette).staggeredAppear(0)
                        ReminderRow(task: task, palette: palette).staggeredAppear(1)
                        RepeatMenu(task: task, palette: palette).staggeredAppear(2)
                        NotesEditor(task: task, palette: palette).staggeredAppear(3)
                        StepsList(task: task, palette: palette).staggeredAppear(4)
                        GoalMenu(task: task, palette: palette).staggeredAppear(5)
                    }
                }
                DetailActionPills(task: task, palette: palette).staggeredAppear(6)
            } else {
                Spacer()
                Text("Select something to see it here.")
                    .font(MekaType.body).foregroundStyle(palette.textTertiary)
                    .frame(maxWidth: .infinity)
                Spacer()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

struct QuickCaptureMenu: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    private var palette: MekaPalette { scheme == .dark ? .dark : .light }
    @State private var text = ""
    /// Notice new screenshots (screenshots and photos, slice 2): this Mac's own switch.
    @AppStorage(MacScreenshotWatch.enabledKey) private var noticeScreenshots = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // The menu-bar "now" (Fold modes, slice 3): the one thing that matters now, with its one-tap actions.
            NowCard()
            // "Hello, Navilife" slice 2: the orb in the menu bar's window; a click brings Navilife forward and greets.
            SayHelloButton(palette: palette) { GlobalTalkHotKey.bringForward() }
                .padding([.horizontal, .top], MekaSpace.m)
            TextField("Capture anything…", text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit { model.captureTyped(text); text = "" }
                .padding(MekaSpace.m)
                .frame(width: 320)
            // Voice notes, slice 3: from the menu bar, Navilife comes forward recording one.
            Button("🎙 Take a voice note") { model.startVoiceNote() }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .padding([.horizontal, .bottom], MekaSpace.m)
            // Screenshots and photos, slice 1: a picture's words, read on this Mac, go to Handle this.
            Button("🖼 Read a picture…") { model.choosePictureToRead() }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .padding([.horizontal, .bottom], MekaSpace.m)
            // Slice 2: new screenshots noticed by themselves, read on this Mac (off until turned on here).
            Toggle(ScreenshotRules.shared.OFF_LINE_MAC, isOn: Binding(
                get: { noticeScreenshots },
                set: { noticeScreenshots = $0; model.setNoticeScreenshots($0) }
            ))
            .toggleStyle(.switch)
            .controlSize(.mini)
            .font(MekaType.caption)
            .help(ScreenshotRules.shared.TURNED_ON_MAC)
            .padding([.horizontal, .bottom], MekaSpace.m)
        }
    }
}

/// One-time enrolment: server address + enrolment code. Everything works offline before and after. Or (Recovery key,
/// slice 3) the 24 words when both devices were lost: the live line under them says how they read ("12 of 24 words",
/// "Word 3 isn't one of the key's words"); the window is left out of screenshots and screen sharing meanwhile, and the
/// words are cleared when the sheet closes.
///
/// Motion: the sheet scale-fades (system); switching to the words cross-fades the title and the field, and the reading
/// line cross-fades as it changes. Reduce Motion: cross-fades.
private struct ConnectSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var url = ""
    @State private var code = ""
    @State private var busy = false
    @State private var error: String?
    @State private var recovering = false
    @State private var words = ""

    private var ready: Bool { recovering ? RecoveryKeyRules.shared.readable(text: words) : !code.isEmpty }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text(recovering ? "Recover this Mac" : "Connect this Mac").font(MekaType.upNextTitle)
                .contentTransition(.opacity)
            TextField("Server address (https://…)", text: $url).textFieldStyle(.roundedBorder)
            if recovering {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    Text(RecoveryKeyRules.shared.RECOVER_HOW).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("Recovery key").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    TextEditor(text: Binding(get: { words }, set: { words = String($0.prefix(400)) }))
                        .font(MekaType.body)
                        .autocorrectionDisabled()
                        .frame(height: 84)
                        .scrollContentBackground(.hidden)
                        .padding(MekaSpace.xs)
                        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                        .accessibilityLabel("Recovery key")
                    if let line = RecoveryKeyRules.shared.typedLine(text: words) {
                        Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
                    }
                }
                .transition(.opacity)
            } else {
                SecureField("Enrolment code", text: $code).textFieldStyle(.roundedBorder)
                    .transition(.opacity)
            }
            if let error { Text(error).font(MekaType.caption).foregroundStyle(palette.critical) }
            if let ask = model.joinWaiting {
                // Clean-ups (2026-10-19): waiting for Allow on the phone — the code to compare there, minutes left.
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(DeviceJoinRules.shared.waitingTitle(kind: DeviceJoinRules.shared.MAC)).font(MekaType.body)
                    TimelineView(.periodic(from: .now, by: 15)) { context in
                        Text(DeviceJoinRules.shared.waitingLine(
                            code: ask.code, expiresAtMs: ask.expiresAtMs, nowMs: Int64(context.date.timeIntervalSince1970 * 1000)
                        ))
                        .font(MekaType.caption).foregroundStyle(palette.accent)
                    }
                }
                .transition(.opacity)
            }
            HStack {
                Button(recovering ? "Use an enrolment code instead" : RecoveryKeyRules.shared.RECOVER) {
                    recovering.toggle(); error = nil; words = ""
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
                .disabled(busy)
                Spacer()
                Button("Not now") { words = ""; dismiss() }.keyboardShortcut(.cancelAction)
                Button(busy ? "Connecting…" : "Connect") {
                    busy = true; error = nil
                    let address = url, typed = words, enrolCode = code, viaKey = recovering
                    Task {
                        if viaKey {
                            error = await model.recover(serverURL: address, words: typed)
                        } else {
                            error = await model.connect(serverURL: address, code: enrolCode)
                        }
                        busy = false
                        if error == nil { words = ""; code = ""; dismiss() }
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(busy || url.isEmpty || !ready)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .background(HiddenFromCapture(hidden: recovering))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: recovering)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.joinWaiting?.code)
        .onAppear { url = model.defaultServerURL }
        .onDisappear { words = "" }
    }
}

/// "Also add the blocks to Google" in Plan my day (calendar editing slice 2e): a switch, with the line under it
/// cross-fading between what Apply will do.
private struct PlanCalendarToggle: View {
    let setting: PlanCalendarSetting
    let palette: MekaPalette
    let onChange: (Bool) -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Toggle(setting.label, isOn: Binding(get: { setting.on }, set: { onChange($0) }))
                .toggleStyle(.switch).tint(palette.accent).font(MekaType.body)
            Text(setting.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .id(setting.line).transition(.opacity)
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: setting.line)
        .padding(.top, MekaSpace.m)
    }
}

/// One calendar in Calendars' On Today list: its switch, and Rename (Fold review 2026-10-09 07:26, item 9), which
/// unfolds a field under the row in place; Return saves MEKA's own name (empty: back to the default, the field's
/// placeholder), the name cross-fades and the field folds away. The real calendar keeps its name.
private struct CalendarOnTodayRow: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let choice: CalendarChoice
    let palette: MekaPalette
    @State private var renaming = false
    @State private var text = ""

    var body: some View {
        let key = choice.key, label = choice.label
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(spacing: MekaSpace.xs) {
                Toggle(isOn: Binding(
                    get: { choice.onToday },
                    set: { model.setCalendarOnToday(key: key, label: label, on: $0) }
                )) {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(label).font(MekaType.itemMeta).id(label).transition(.opacity)
                        if let detail = choice.detail {
                            // Quality gate slice 35: whole when it fits on one line, else without its provider so the
                            // address starts the line (core detailShort), never "Outlook ·" alone above it.
                            ViewThatFits(in: .horizontal) {
                                Text(detail).lineLimit(1)
                                Text(choice.detailShort ?? detail)
                            }
                            .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .accessibilityLabel(detail)
                        }
                    }
                }
                .toggleStyle(.switch)
                .tint(palette.accent)
                if choice.canRename {
                    Button(renaming ? "Cancel" : "Rename") {
                        MekaHaptics.tick()
                        text = choice.renamed ? label : ""
                        renaming.toggle()
                    }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                    .accessibilityLabel(renaming ? "Cancel renaming \(label)" : "Rename \(label)")
                }
            }
            if renaming {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    TextField(choice.defaultLabel, text: $text)
                        .textFieldStyle(.roundedBorder).font(MekaType.body).frame(maxWidth: 260)
                        .accessibilityLabel("Name for \(choice.defaultLabel)")
                        .onSubmit {
                            model.renameCalendar(key: key, name: text)
                            renaming = false
                        }
                        .onChange(of: text) {
                            let max = Int(CalendarRules.shared.MAX_NAME)
                            if text.count > max { text = String(text.prefix(max)) }
                        }
                    Text("Only in Navilife: the calendar keeps its own name. Empty goes back to \(choice.defaultLabel).")
                        .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: renaming)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: label)
    }
}
