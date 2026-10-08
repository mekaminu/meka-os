@preconcurrency import MekaKit
import SwiftUI

/// TODAY on macOS: Today | detail split, resizable, keyboard-first (brief §45).
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
    @State private var allDayOpen = false
    /// The Day ring's opening: in full the first time today on this Mac, quickly after, at once with Motion → Off.
    @State private var ringPlay: DayRingPlayback?
    private var play: Bool { !introPlayed }
    private static let sections = 6 // greeting, needs you, up next, your day (header), your day (rows), done

    private var palette: MekaPalette { scheme == .dark ? .dark : .light }
    @Namespace private var selection
    /// The content width, for the command centre's columns.
    @State private var width: CGFloat = 0

    var body: some View {
        @Bindable var model = model
        // The command centre (Fold modes, slice 2): beside Today, Needs you over Coming up; a wide window gives
        // Coming up its own column. An open task's detail takes Needs you's place.
        let layout = CommandCentreRules.shared.layout(contentWidthDp: Float(width))
        let columns = CommandCentreRules.shared.columns(layout: layout, taskOpen: model.selected != nil)
        let three = columns.contains(CommandColumn.comingUp)
        HSplitView {
            todayColumn
                .environment(\.selectionNamespace, selection)
                .frame(minWidth: 380, idealWidth: 520)
            CommandSideView(column: columns.count > 1 ? columns[1] : CommandColumn.detail, palette: palette)
                .frame(minWidth: 280, idealWidth: 360)
            if three {
                ComingUpColumnView(shared: false, palette: palette)
                    .frame(minWidth: 240, idealWidth: 300)
                    .transition(.opacity)
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .background(palette.background)
        .sheet(isPresented: $model.showConnect) { ConnectSheet(palette: palette) }
        .sheet(isPresented: $model.showPlan) { PlanSheet(palette: palette) }
    }

    private var todayColumn: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: MekaSpace.xs) {
                    // The opening moment, part 2: on the first open of the day the greeting's letters fade in.
                    GreetingText(text: greeting, play: ringPlay ?? .still, palette: palette)
                        .staggeredAppear(0, play: play)
                    if let date = model.today?.timeline.dateLabel, !date.isEmpty {
                        Text(date).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            .staggeredAppear(0, play: play)
                    }
                    if !model.isConnected || model.signedOut {
                        Button(model.signedOut ? "Reconnect this Mac" : "This Mac isn't syncing yet · Connect") { model.showConnect = true }
                            .buttonStyle(MekaPressStyle())
                            .font(MekaType.caption)
                            .foregroundStyle(palette.accent)
                    }
                    HStack(spacing: MekaSpace.l) {
                        Button("Search") { model.showSearch = true }
                        Button("Plan my day") { model.showPlan = true }
                        // Work mode, the brief, the shutdown and the theme live in Ask's More now (Today clarity, slice 2).
                        if model.syncRingShowing {
                            SyncRingView(palette: palette)
                                .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.scale(scale: 0.6).combined(with: .opacity))
                        }
                    }
                    .animation(MekaMotion.expand(reduced: reduceMotion), value: model.syncRingShowing)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
                    .staggeredAppear(0, play: play)
                    if let line = model.syncLine {
                        Text(line).font(MekaType.caption).foregroundStyle(palette.offline)
                            .transition(.opacity)
                    }
                    // The opening moment (motion pass 2, slice 7): the Day ring under the header.
                    if let today = model.today, !today.timeline.dateLabel.isEmpty {
                        DayRingView(ring: today.dayRing, play: ringPlay ?? .still, played: { ringPlay = .still }, palette: palette,
                                    tiles: today.dayTiles)
                            .padding(.top, MekaSpace.s)
                    }
                    Spacer().frame(height: MekaSpace.l)

                    // Motion pass 2: with Reduce Motion on and nothing chosen in Appearance → Motion, a one-time card.
                    MotionSystemCardView(palette: palette)
                        .staggeredAppear(1, play: play)

                    // Morning brief: the card rises in when the morning starts and goes at noon or once read.
                    if let b = model.brief, b.offered {
                        BriefCard(brief: b, palette: palette)
                            .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .bottom)))
                            .padding(.bottom, MekaSpace.l)
                            .staggeredAppear(1, play: play)
                    } else if let line = model.brief?.readElsewhereLine {
                        // Read on the Fold this morning: a slim line in the card's place until noon.
                        BriefReadLineView(line: line, palette: palette)
                            .transition(.opacity)
                            .padding(.bottom, MekaSpace.l)
                            .staggeredAppear(1, play: play)
                    }

                    // Weekly review: the card rises in on Sunday evening and stays through Monday until reviewed.
                    if let c = model.review?.card, c.offered {
                        ReviewCardView(card: c, palette: palette)
                            .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .bottom)))
                            .padding(.bottom, MekaSpace.l)
                            .staggeredAppear(1, play: play)
                    }

                    // Evening shutdown: the card rises in when the evening starts; once done, one quiet line stays.
                    if let s = model.shutdown {
                        if s.offered {
                            ShutdownCard(shutdown: s, palette: palette)
                                .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .bottom)))
                                .padding(.bottom, MekaSpace.l)
                                .staggeredAppear(1, play: play)
                        } else if s.evening || s.doneLine != nil {
                            // Tomorrow at a glance once the evening starts (after shutting down, or while still at work).
                            TomorrowGlanceView(doneLine: s.doneLine, glance: s.evening ? s.tomorrow.glance : nil, palette: palette)
                                .padding(.bottom, MekaSpace.l)
                                .transition(.opacity)
                        }
                    }

                    if let today = model.today {
                        // "You're clear." only when nothing at all is left today; "Nothing else timed today" beside
                        // all-day items (Today clarity).
                        if let line = today.clearLine {
                            // All clear: the brass ring breathes beside it (catalogue "Empty states"); Off: still.
                            HStack(spacing: MekaSpace.s) {
                                if today.isAllClear { BreathingRingView(palette: palette) }
                                Text(line)
                                    .font(today.isAllClear ? MekaType.upNextTitle : MekaType.body)
                                    .foregroundStyle(palette.textSecondary)
                            }
                            .staggeredAppear(1, play: play)
                        }
                        // The Needs you column beside Today lists them (never shown twice).
                        if !today.needsYou.isEmpty && CommandCentreRules.shared.todayListsNeedsYou(layout: CommandCentreRules.shared.layout(contentWidthDp: Float(width))) {
                            SectionLabel("Needs you", palette).staggeredAppear(1, play: play)
                            ForEach(today.needsYou, id: \.task.id) { item in
                                TaskRow(task: item.task, reason: item.reason, palette: palette).staggeredAppear(1, play: play)
                            }
                            Spacer().frame(height: MekaSpace.l)
                        }
                        if today.upNext != nil || today.timeline.nextEvent != nil {
                            SectionLabel("Up next", palette).staggeredAppear(2, play: play)
                        }
                        // The next event within the hour: "Call with Tunde in 25 min".
                        if let e = today.timeline.nextEvent {
                            NextEventCard(next: e, palette: palette)
                                .padding(.bottom, today.upNext == nil ? MekaSpace.l : MekaSpace.xs)
                                .transition(.opacity)
                                .staggeredAppear(2, play: play)
                        }
                        if let next = today.upNext {
                            // Up next changes: the new card pushes in from the right (cross-fade with Reduce Motion).
                            UpNextCard(task: next, palette: palette)
                                .id(next.id)
                                .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.push(from: .trailing))
                                .staggeredAppear(2, play: play)
                            Spacer().frame(height: MekaSpace.l)
                        }
                        // The Gym (booked habits): today's session, "Did you go?" once it's over, or where it was rebooked.
                        if !(model.sessions?.cards ?? []).isEmpty {
                            SessionCardsView(palette: palette)
                                .padding(.bottom, MekaSpace.l)
                                .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .bottom)))
                                .staggeredAppear(2, play: play)
                        }
                        // One timeline under "Today": the All day group first (one row each, at most 3 then "+2
                        // more"), finished events folded, events and planned tasks in time order with the now line
                        // and free gaps; then tasks with no time.
                        let tl = today.timeline
                        if tl.hasTimedOrAllDay {
                            SectionLabel("Today", palette).staggeredAppear(3, play: play)
                            if !tl.allDayItems.isEmpty {
                                AllDayLabel(label: tl.allDayLabel, palette: palette).staggeredAppear(3, play: play)
                            }
                            ForEach(AllDayRules.shared.shown(items: tl.allDayItems, open: allDayOpen), id: \.event.id) { a in
                                AllDayRow(item: a, palette: palette)
                                    .transition(reduceMotion ? AnyTransition.opacity : AnyTransition.opacity.combined(with: .move(edge: .top)))
                                    .staggeredAppear(3, play: play)
                            }
                            if let more = AllDayRules.shared.moreLabel(items: tl.allDayItems, open: allDayOpen) {
                                AllDayMore(label: more, open: $allDayOpen, palette: palette).staggeredAppear(3, play: play)
                            }
                            if let label = tl.earlierLabel {
                                EarlierToggle(label: label, open: $earlierOpen, palette: palette).staggeredAppear(3, play: play)
                                if earlierOpen {
                                    ForEach(tl.earlier, id: \.id) { r in TimelineEventRow(row: r, past: true, palette: palette).transition(.opacity) }
                                }
                            }
                            ForEach(tl.rows, id: \.id) { r in
                                timelineRow(r).staggeredAppear(4, play: play)
                            }
                            Spacer().frame(height: MekaSpace.l)
                        }
                        if !tl.anytime.isEmpty {
                            SectionLabel("Anytime today", palette).staggeredAppear(4, play: play)
                            ForEach(tl.anytime, id: \.id) { t in TaskRow(task: t, reason: nil, palette: palette).staggeredAppear(4, play: play) }
                        }
                        if !today.doneToday.isEmpty {
                            Text("\(today.doneToday.count) done today")
                                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                                .padding(.top, MekaSpace.l)
                                .staggeredAppear(5, play: play)
                        }
                    }
                }
                .padding(.horizontal, MekaSpace.gutter)
                .padding(.vertical, MekaSpace.xl)
                // Replan / complete motion: rows glide to new positions instead of redrawing.
                .animation(MekaMotion.replan(reduced: reduceMotion), value: model.allTasks.map(\.id))
                .animation(MekaMotion.replan(reduced: reduceMotion), value: model.today?.upNext?.id)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: model.shutdown?.offered)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: model.brief?.offered)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: model.review?.card.offered)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: model.sessions?.cards.map(\.habitId) ?? [])
                .animation(MekaMotion.replan(reduced: reduceMotion), value: model.today?.timeline.rows.map(\.id))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: earlierOpen)
                .animation(MekaMotion.complete(reduced: reduceMotion), value: allDayOpen)
                .animation(MekaMotion.replan(reduced: reduceMotion), value: model.today?.timeline.allDayItems.map(\.event.id))
            }
            // The foot fades into the ticker and capture field rather than cutting a row in half (Fold review item
            // 4); the xl bottom padding is more than the fade, so the last row still scrolls fully clear.
            .footFade()
            .onAppear {
                if ringPlay == nil { ringPlay = DayRingOpen.claim(reduced: reduceMotion) }
            }
            .task {
                guard !introPlayed else { return }
                try? await Task.sleep(for: .seconds(MotionMath.staggerSpan(count: Self.sections, reduced: false, expressive: true) + 0.3))
                introPlayed = true
            }
            // News ticker (news ticker, slice 2): the drifting strip at the foot of Today; Appearance → News ticker.
            NewsTickerStrip(palette: palette)
                .padding(.horizontal, MekaSpace.m)
                .padding(.top, MekaSpace.s)
            CaptureField(palette: palette)
                .padding(MekaSpace.m)
        }
    }

    @ViewBuilder
    private func timelineRow(_ r: TimelineRow) -> some View {
        switch r.kind {
        case .event: TimelineEventRow(row: r, past: false, palette: palette).eventActions(r.event, palette: palette)
        case .task:
            if let t = r.task { TaskRow(task: t, reason: nil, palette: palette, time: r.time, timelineLine: r.detail) }
        case .gap: GapRow(row: r, palette: palette)
        case .now: NowLine(row: r, palette: palette)
        case .session: SessionTimelineRow(row: r, palette: palette)
        case .work: WorkTimelineRow(row: r, palette: palette)
        default: EmptyView()
        }
    }

    private var greeting: String {
        switch Calendar.current.component(.hour, from: .now) {
        case 5..<12: "Good morning, Meka"
        case 12..<18: "Good afternoon, Meka"
        default: "Good evening, Meka"
        }
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
    @Environment(\.selectionNamespace) private var selectionNamespace
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let task: MekaTask
    let reason: NeedsYouReason?
    let palette: MekaPalette
    /// Where the row is on screen, so its detail can grow out of it.
    @State private var frame: CGRect = .zero

    /// On the timeline: the time column on the left and the core's line ("30 min · ↻ Every weekday") under the title.
    let time: String?
    let timelineLine: String?

    init(task: MekaTask, reason: NeedsYouReason?, palette: MekaPalette, time: String? = nil, timelineLine: String? = nil) {
        self.task = task
        self.reason = reason
        self.palette = palette
        self.time = time
        self.timelineLine = timelineLine
    }

    var body: some View {
        HStack(spacing: MekaSpace.m) {
            if let time {
                Text(time).font(MekaType.itemMeta).monospacedDigit().foregroundStyle(palette.textSecondary)
                    .frame(width: TimelineMetrics.timeColumn - MekaSpace.m, alignment: .leading)
            }
            CompleteButton(task: task, palette: palette)
            VStack(alignment: .leading, spacing: 2) {
                Text(task.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                if let line = subtitle {
                    Text(line).font(MekaType.itemMeta).foregroundStyle(isAlert ? palette.critical : palette.textSecondary)
                }
            }
            Spacer()
        }
        .padding(.vertical, MekaSpace.s)
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
        default: time != nil ? timelineLine : model.repeatLine(task)
        }
    }
}

/// Connected calendars. Connecting opens the provider's own sign-in page in the browser; MEKA OS never sees the
/// password. The list refreshes whenever the app becomes active again (i.e. when the owner returns from the browser).
/// Connected calendars and feeds; opened from Today's header or Ask's More list (sheet lives on the shell).
struct CalendarsSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text("Calendars").font(MekaType.upNextTitle)
            Text("Read-only. Events appear in Today on all your devices.")
                .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            if let accounts = model.accounts {
                if accounts.isEmpty {
                    Text("No calendars connected yet.").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                }
                ForEach(Array(accounts.enumerated()), id: \.element) { i, a in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(a.email).font(MekaType.itemTitle)
                            Text(status(a)).font(MekaType.caption)
                                .foregroundStyle(a.needsReconnect ? palette.critical : palette.textTertiary)
                        }
                        Spacer()
                        if a.needsReconnect {
                            Button("Reconnect") { Task { await model.connectCalendar(a.provider) } }
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
                SectionLabel("On Today", palette).padding(.top, MekaSpace.s)
                Text("Turn a calendar off to keep it in the Calendar section but off Today and your day.")
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                ForEach(Array(model.calendarsOnToday.enumerated()), id: \.element.key) { i, c in
                    let key = c.key, label = c.label
                    Toggle(isOn: Binding(
                        get: { c.onToday },
                        set: { model.setCalendarOnToday(key: key, label: label, on: $0) }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(label).font(MekaType.itemMeta)
                            if let detail = c.detail {
                                Text(detail).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            }
                        }
                    }
                    .toggleStyle(.switch)
                    .tint(palette.accent)
                    .staggeredAppear(i)
                }
            }
            HStack {
                Button("Connect Google Calendar") { Task { await model.connectCalendar("google") } }
                Button("Connect Outlook Calendar") { Task { await model.connectCalendar("microsoft") } }
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
        .task { await model.loadAccounts() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            Task { await model.loadAccounts() }
        }
    }

    private func status(_ a: ConnectedAccount) -> String {
        if a.needsReconnect { return "Access expired · Reconnect" }
        if a.status == "error" { return "Couldn't sync last time · retrying" }
        guard let ms = a.lastSyncAtMs?.int64Value else { return "\(CoreModel.providerName(a.provider)) · first sync in progress" }
        let d = Date(timeIntervalSince1970: Double(ms) / 1000)
        return "\(CoreModel.providerName(a.provider)) · synced \(d.formatted(date: .omitted, time: .shortened))"
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
        if !plan.meals.isEmpty { parts.append("◐ Kept free for your fast's meals. Nothing is planned over them.") }
        if !plan.habitsUnplaced.isEmpty { parts.append("No room today for: " + plan.habitsUnplaced.map(\.title).joined(separator: ", ")) }
        return parts.isEmpty ? nil : parts.joined(separator: "\n")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
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
                    if !plan.unplaced.isEmpty {
                        Text("Won't fit today: " + plan.unplaced.map(\.title).joined(separator: ", "))
                            .font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    }
                    if let note = habitNote {
                        Text(note).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    CountUpText(Int(plan.freeMinutesLeft)) { "\($0 / 60) h \($0 % 60) min still free." }
                        .font(MekaType.caption).foregroundStyle(palette.textTertiary)
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

private struct UpNextCard: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let task: MekaTask
    let palette: MekaPalette
    @State private var frame: CGRect = .zero

    var body: some View {
        HStack {
            Text(task.title).font(MekaType.upNextTitle).foregroundStyle(palette.textPrimary)
            Spacer()
            CompleteButton(task: task, palette: palette)
        }
        .padding(MekaSpace.l)
        .background(RoundedRectangle(cornerRadius: MekaRadius.l).fill(palette.surfaceRaised))
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame = $0 }
        .onTapGesture { model.select(task.id, reduced: reduceMotion, origin: frame) }
        .mekaHoverLift()
    }
}

/// Completion motion (catalogue "Complete a task"; motion pass 2, slice 4): the accent ring sweeps round, fills and
/// the check strokes in (`CheckRingView`, `checkDraw`) with a light haptic, then the row leaves. Also used by the
/// evening shutdown. Motion → Off: shown done at once.
struct CompleteButton: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let task: MekaTask
    let palette: MekaPalette
    @State private var began: Date?

    var body: some View {
        Button {
            guard began == nil else { return }
            began = Date()
            MekaHaptics.light()
            let id = task.id
            let draw = MotionMath.checkDraw(reduced: reduceMotion)
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(Int(draw * 1000) + 50))
                model.complete(id)
            }
        } label: {
            CheckRingView(palette: palette, began: began)
                .frame(width: 22, height: 22)
                .contentShape(Circle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel("Complete \(task.title)")
    }
}

private struct CaptureField: View {
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        TextField("Capture anything…", text: $text)
            .textFieldStyle(.plain)
            .font(MekaType.body)
            .focused($focused)
            .padding(.horizontal, MekaSpace.l)
            .padding(.vertical, MekaSpace.m)
            .background(Capsule().fill(palette.surfaceRaised))
            .onSubmit { model.capture(text); text = "" }
            .onChange(of: model.focusCapture) { focused = true }
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
        .padding(MekaSpace.gutter)
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

    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    func path(in rect: CGRect) -> Path {
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

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            if let task {
                TextField("Title", text: $title)
                    .textFieldStyle(.plain)
                    .font(MekaType.upNextTitle)
                    .onSubmit { model.rename(task.id, to: title) }
                    .onAppear { title = task.title }

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
                    VStack(alignment: .leading, spacing: MekaSpace.s) {
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
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // The menu-bar "now" (Fold modes, slice 3): the one thing that matters now, with its one-tap actions.
            NowCard()
            TextField("Capture anything…", text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit { model.capture(text); text = "" }
                .padding(MekaSpace.m)
                .frame(width: 320)
        }
    }
}

/// One-time enrolment: server address + enrolment code. Everything works offline before and after.
private struct ConnectSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let palette: MekaPalette
    @State private var url = ""
    @State private var code = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            Text("Connect this Mac").font(MekaType.upNextTitle)
            TextField("Server address (https://…)", text: $url).textFieldStyle(.roundedBorder)
            SecureField("Enrolment code", text: $code).textFieldStyle(.roundedBorder)
            if let error { Text(error).font(MekaType.caption).foregroundStyle(palette.critical) }
            HStack {
                Spacer()
                Button("Not now") { dismiss() }.keyboardShortcut(.cancelAction)
                Button(busy ? "Connecting…" : "Connect") {
                    busy = true; error = nil
                    Task {
                        error = await model.connect(serverURL: url, code: code)
                        busy = false
                        if error == nil { dismiss() }
                    }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(busy || url.isEmpty || code.isEmpty)
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 420)
        .onAppear { url = model.defaultServerURL }
    }
}
