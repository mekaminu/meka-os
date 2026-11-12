@preconcurrency import MekaKit
import SwiftUI

/// ASK on the Mac (build plan M1, Four tabs, one front door): the front door to everything that isn't a tab. The field
/// asks MEKA in your own words (`AskMekaSection`, V1 AI layer slice 3b), with Search Everything (⌘F) beside it. Below it
/// one status row when Setup or Health needs a look, then the Ask hub (approved 2026-11-11, docs/design/look/AskHub.dc.html):
/// a Life · Daily · Settings switch, remembered on this Mac. LIFE: the tiles (an SF Symbol, the name and a live line each)
/// eight a page, today's first, ‹ › and a dot a page, "Places to set up" folded under them naming what's in it; DAILY: one
/// card of rows; SETTINGS: four boxes that fold open (You and your day the first time; remembered). Motion: title,
/// field, status row, switch and tab stagger in; the switch's pill slides across on the expand spring; a page's tiles
/// and a box's rows arrive on the expand spring; rows and tiles lift 2 pt on hover; lines cross-fade and lit ones blend
/// to the accent. Reduce Motion: fades.
struct AskScreen: View {
    let palette: MekaPalette
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    /// The Ask hub's tab on this Mac (a tab's name; Life until he picks another).
    @AppStorage(AskHub.tabKey) private var tabRaw = ""
    /// Settings' open boxes on this Mac, as the core stores them; nil until he first folds or unfolds one.
    @AppStorage(AskHub.boxesKey) private var boxesRaw: String?
    @State private var lifePageAsked = 0
    /// "To set up" open or closed on this Mac (Ask polish, slice 1); empty until he first clicks it.
    @AppStorage(AskSetUp.key) private var setUpChoice = ""

    private var connected: Bool { model.isConnected && !model.signedOut }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text(ShellDestination.ask.label)
                    .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                    .foregroundStyle(palette.textPrimary)
                    .padding(.bottom, MekaSpace.m)
                    .staggeredAppear(0)
                // Ask MEKA (V1 AI layer, slice 3b): the field asks in your own words; Search sits beside it.
                AskMekaSection(palette: palette, openHelp: openHelp)
                    .staggeredAppear(1)
                    // A help entry found by Search or Ask's field (slice 2) opens where it lives.
                    .onChange(of: model.openHelpPlace) { takeHelpPlace() }
                    .onAppear { takeHelpPlace(); MacVoiceNoteStore.shared.load() }
                // Setup or Health, one row, only when something needs a look.
                if let status = ShellNav.statusItem(healthAttention: Int(model.health?.attention ?? 0), setupLeft: Int(model.setup?.toDo ?? 0)) {
                    MoreRow(item: status, line: ShellNav.moreLine(status, listsDue: 0, health: model.health?.summary, setup: model.setup?.summary),
                            lit: true, palette: palette) { open(status) }
                        .padding(.bottom, MekaSpace.xs)
                        .staggeredAppear(ShellNav.statusStep)
                }
                // The Ask hub (approved 2026-11-11): Life · Daily · Settings in place of one long page.
                AskTabSwitch(tab: tab, palette: palette) { pick($0) }
                    .padding(.vertical, MekaSpace.xs)
                    .staggeredAppear(ShellNav.moreLabelStep(0))
                Group {
                    switch tab {
                    case AskTab.daily: dailyCard()
                    case AskTab.settings: settingsBoxes()
                    default: lifePage()
                    }
                }
                .transition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: tabRaw)
            }
            .frame(maxWidth: 560, alignment: .leading)
            .padding(.horizontal, MekaSpace.gutterWide)
            .padding(.vertical, MekaSpace.xl)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .background(palette.background)
    }

    /// "Places to set up · N" (Ask polish, slice 1; named so since walk-through 2026-11-08 A8): the tiles still waiting to be set up, under a label that opens and closes
    /// them (remembered on this Mac); a tile leaves as soon as it has something live. The group unfolds on the expand
    /// spring and the chevron turns with it; reduced motion: it fades.
    @ViewBuilder
    private func setUpSection() -> some View {
        let toSetUp = ShellNav.lifeToSetUp(model.lifeLines)
        if !toSetUp.isEmpty, let lines = model.lifeLines {
            let setUpOpen = LifeTileRules.shared.setUpOpen(stored: AskSetUp.stored(setUpChoice), lines: lines)
            Button {
                MekaHaptics.tick()
                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { setUpChoice = setUpOpen ? AskSetUp.closed : AskSetUp.open }
            } label: {
                HStack(spacing: MekaSpace.xs) {
                    Text(LifeTileRules.shared.setUpLabel(count: Int32(toSetUp.count)).uppercased())
                        .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                        .foregroundStyle(palette.textTertiary)
                    Image(systemName: "chevron.right").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .rotationEffect(.degrees(setUpOpen ? 90 : 0))
                        .accessibilityHidden(true)
                    // The Ask hub: folded, the places' names follow ("Bins, Gifts, Annual leave").
                    if !setUpOpen {
                        Text(AskHubRules.shared.setUpPeek(names: toSetUp.map(\.label)))
                            .font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                    }
                }
                .padding(.top, MekaSpace.xs)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityValue(setUpOpen ? "Open" : "Closed")
            .staggeredAppear(ShellNav.tileStep(ShellNav.lifeLive(model.lifeLines).count) + 1)
            if setUpOpen {
                tileGrid(toSetUp, first: 0)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    /// Life tiles, two to a row; each line cross-fades and today's ones are lit.
    private func tileGrid(_ items: [MoreItem], first: Int) -> some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: MekaSpace.xs), GridItem(.flexible(), spacing: MekaSpace.xs)],
                  spacing: MekaSpace.xs) {
            ForEach(Array(items.enumerated()), id: \.element) { i, item in
                let line = ShellNav.tileLine(item, live: item.tile.flatMap { model.lifeLines?.of(tile: $0) })
                LifeTileView(item: item, text: line.text, lit: line.lit, palette: palette) { open(item) }
                    .staggeredAppear(ShellNav.tileStep(first + i))
            }
        }
    }

    private var tab: AskTab { AskHubRules.shared.TABS.first { $0.name == tabRaw } ?? AskTab.life }

    private func pick(_ t: AskTab) {
        guard t.name != tabRaw else { return }
        MekaHaptics.tick()
        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { tabRaw = t.name }
    }

    /// Life: the tiles a page at a time (eight, two to a row), today's first; ‹ › and a dot a page under them when they
    /// run past one page; "Places to set up" folded under them.
    @ViewBuilder
    private func lifePage() -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            // Ask polish, slice 1: on the first run one welcome line; tiles still to set up sink under "To set up".
            if let lines = model.lifeLines, let welcome = LifeTileRules.shared.welcome(lines: lines) {
                Text(welcome).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .padding(.bottom, MekaSpace.xxs)
                    .staggeredAppear(ShellNav.tileStep(0))
            }
            let live = ShellNav.lifeLive(model.lifeLines)
            let count = Int32(live.count)
            let start = Int(AskHubRules.shared.pageStart(page: Int32(lifePageAsked), count: count, columns: 2))
            let end = Int(AskHubRules.shared.pageEnd(page: Int32(lifePageAsked), count: count, columns: 2))
            tileGrid(Array(live[start..<end]), first: 0)
                .animation(MekaMotion.expand(reduced: reduceMotion), value: Array(live[start..<end]))
            if let line = AskHubRules.shared.pageLine(page: Int32(lifePageAsked), count: count, columns: 2) {
                LifePageTurner(line: line, page: Int(AskHubRules.shared.page(asked: Int32(lifePageAsked), count: count, columns: 2)),
                               pages: Int(AskHubRules.shared.pages(count: count, columns: 2)),
                               canPrev: AskHubRules.shared.canTurn(page: Int32(lifePageAsked), by: -1, count: count, columns: 2),
                               canNext: AskHubRules.shared.canTurn(page: Int32(lifePageAsked), by: 1, count: count, columns: 2),
                               palette: palette) { by in
                    MekaHaptics.tick()
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                        lifePageAsked = Int(AskHubRules.shared.turn(page: Int32(lifePageAsked), by: Int32(by), count: count, columns: 2))
                    }
                }
            }
            setUpSection()
        }
    }

    /// Daily: one card of rows, parted by a faint line.
    private func dailyCard() -> some View {
        VStack(spacing: 0) {
            ForEach(Array(ShellNav.daily().enumerated()), id: \.element) { i, item in
                if i > 0 { Rectangle().fill(palette.hairline).frame(height: 1).padding(.horizontal, MekaSpace.m) }
                // Ask polish, item 3: each row says what it holds now; lit when it's ready or wants a look.
                let line = ShellNav.dailyLine(item, lines: model.dailyLines, voiceNotes: model.voiceNotesLine(MacVoiceNoteStore.shared.notes))
                MoreRow(item: item, line: line.text, lit: line.lit, palette: palette) { open(item) }
            }
        }
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .staggeredAppear(ShellNav.moreRowStep(0, 0))
    }

    /// Settings: four boxes that fold open, You and your day open the first time; remembered on this Mac.
    private func settingsBoxes() -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            ForEach(Array(AskHubRules.shared.BOXES.enumerated()), id: \.offset) { i, box in
                let rows = ShellNav.settingsRows(box, connected: connected)
                if !rows.isEmpty {
                    SettingsBoxView(box: box, rows: rows, open: AskHubRules.shared.isOpen(stored: boxesRaw, box: box), palette: palette) {
                        MekaHaptics.tick()
                        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { boxesRaw = AskHubRules.shared.toggled(stored: boxesRaw, box: box) }
                    } row: { item in settingRow(item) }
                    .staggeredAppear(ShellNav.moreRowStep(0, i))
                }
            }
        }
    }

    @ViewBuilder
    private func settingRow(_ item: MoreItem) -> some View {
        if ShellNav.unfoldsInPlace(item) {
            AppearanceRow(palette: palette)
        } else if item == .ai {
            AiSpendRow(ai: model.aiStatus, palette: palette)
        } else {
            MoreRow(item: item, line: ShellNav.moreLine(item, listsDue: model.listsDue, atWork: model.work?.atWork == true,
                                                        health: model.health?.summary, setup: model.setup?.summary),
                    lit: ShellNav.moreLit(item, listsDue: model.listsDue, healthAttention: Int(model.health?.attention ?? 0),
                                          setupLeft: Int(model.setup?.toDo ?? 0)), palette: palette) { open(item) }
        }
    }

    /// Take me there under a help answer: the sheet or place whose `MoreItem` case is the place's key; Appearance,
    /// Navilife's AI and Settings switch to the Settings tab with their box open.
    private func openHelp(_ place: HelpPlace) {
        guard let item = MoreItem.allCases.first(where: { "\($0)" == place.key }) else { return }
        switch item {
        case .appearance, .ai, .settings:
            // The Settings tab, with the box that holds it open.
            pick(AskTab.settings)
            if let box = AskHubRules.shared.settingsTarget(place: place), !AskHubRules.shared.isOpen(stored: boxesRaw, box: box) {
                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { boxesRaw = AskHubRules.shared.toggled(stored: boxesRaw, box: box) }
            }
        default:
            open(item)
        }
    }

    private func takeHelpPlace() {
        guard let place = model.openHelpPlace else { return }
        model.openHelpPlace = nil
        openHelp(place)
    }

    private func open(_ item: MoreItem) {
        if let d = item.destination { model.go(to: d, reduced: reduceMotion); return }
        switch item {
        case .brief: model.showBrief = true
        case .news: model.showNews = true
        case .shutdown: model.showShutdown = true
        case .weekAhead: model.showWeekAhead = true
        case .school: model.showSchool = true
        case .kids: model.showKids = true
        case .leave: model.showLeave = true
        case .dinners: model.showMeals = true
        case .dateNight: model.showDateNight = true
        case .lifeAdmin: model.showLifeAdmin = true
        case .workingOn: model.showWorkingOn = true
        case .routines: model.showRoutines = true
        case .team: model.showTeam = true
        case .emergency: model.showEmergency = true
        case .trainQueue: model.showTrainQueue = true
        case .reading: model.showReading = true
        case .voiceNotes: model.showVoiceNotes = true
        case .errands: model.showErrands = true
        case .appointments: model.showAppointments = true
        case .bins: model.showBins = true
        case .car: model.showCar = true
        case .money: model.showMoney = true
        case .gifts: model.showGifts = true
        case .countdowns: model.showCountdowns = true
        case .people: model.showPeople = true
        case .trips: model.showTrips = true
        case .facts: model.showFacts = true
        case .aboutYou: model.showAboutYou = true
        case .work: model.showWork = true
        case .protectedTime: model.showProtected = true
        case .notifications: model.showNotifications = true
        case .speakUp: model.showSpeakUp = true
        case .autonomy: model.showAutonomy = true
        case .activity: model.showActivity = true
        case .yourData: model.showYourData = true
        case .calendars: model.showCalendars = true
        case .mail: model.showMail = true
        case .family: model.showFamily = true
        case .watch: model.showWatch = true
        case .recovery: model.showRecovery = true
        case .voice: model.showVoice = true
        case .talk: model.showTalk = true
        case .health: model.showHealth = true
        case .setup: model.showSetup = true
        case .lists, .goals, .review, .vault, .appearance, .ai, .settings: break
        }
    }
}

/// Settings → Navilife's AI (AI layer completeness slice 2): the status line as before, or "Key refused by Anthropic · …"
/// in the accent; once the server has metered something this month a click unfolds "This month by feature" with a row
/// per kind of use ("Talk · 40 calls · $1.20") and a thin accent bar for its share. Rows stagger in, bars grow from the
/// left, the chevron turns. Reduce Motion: they appear at once, bars full. While the AI can't answer (slice 3) a list
/// under the line says what each AI feature does instead ("While it's off · Morning brief · read from your day…").
private struct AiSpendRow: View {
    let ai: AiStatusView?
    let palette: MekaPalette
    @State private var open = false
    @State private var shown = false
    @Environment(\.mekaReduceMotion) private var reduceMotion

    private var lines: [AiSpendLine] { ai?.spend ?? [] }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Button {
                guard !lines.isEmpty else { return }
                withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open.toggle() }
                shown = false
                if open { withAnimation(MekaMotion.expand(reduced: reduceMotion).delay(reduceMotion ? 0 : 0.04)) { shown = true } }
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(MoreItem.ai.label).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                        Text(LoneWordGlue.shared.kept(line: ai?.line ?? "Checking…"))
                            .font(MekaType.caption)
                            .foregroundStyle(ai?.lit == true ? palette.accent : palette.textSecondary)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: ai?.line)
                    }
                    Spacer()
                    if !lines.isEmpty {
                        Image(systemName: "chevron.right").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .rotationEffect(.degrees(open ? 90 : 0))
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: open)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(lines.isEmpty ? "" : (open ? "Folds away this month's spend" : "Shows this month's spend by feature"))
            // AI layer completeness slice 3: while it can't answer, what each AI feature does instead, always shown.
            if let fallbacks = ai?.fallbacks, !fallbacks.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    if let heading = ai?.fallbackHeading {
                        Text(LoneWordGlue.shared.kept(line: heading)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    ForEach(Array(fallbacks.enumerated()), id: \.offset) { _, f in
                        VStack(alignment: .leading, spacing: 0) {
                            Text(f.label).font(MekaType.caption).foregroundStyle(palette.textPrimary)
                            Text(LoneWordGlue.shared.kept(line: f.detail)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
                .transition(.opacity)
            }
            if open && !lines.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    if let heading = ai?.spendHeading {
                        Text(LoneWordGlue.shared.kept(line: heading)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    }
                    ForEach(Array(lines.enumerated()), id: \.offset) { i, s in
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            HStack {
                                Text(s.label).font(MekaType.caption).foregroundStyle(palette.textPrimary)
                                Spacer()
                                Text(s.detail).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            }
                            GeometryReader { geo in
                                ZStack(alignment: .leading) {
                                    Capsule().fill(palette.surfaceRaised)
                                    Capsule().fill(palette.accent)
                                        .frame(width: geo.size.width * CGFloat(shown || reduceMotion ? max(0.02, min(1, s.share)) : 0))
                                }
                            }
                            .frame(height: 3)
                        }
                        .staggeredAppear(i)
                    }
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
    }
}

private struct MoreRow: View {
    let item: MoreItem
    let line: String
    let lit: Bool
    let palette: MekaPalette
    /// The Settings row: its chevron turns down while its rows are unfolded.
    var unfolded: Bool = false
    let action: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    // Places and settings you open, not things you act on: the regular weight (type weight, 2026-10-06).
                    Text(item.label).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                    Text(LoneWordGlue.shared.kept(line: line))
                        .font(MekaType.caption)
                        .foregroundStyle(lit ? palette.accent : palette.textSecondary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
                }
                Spacer()
                if ShellNav.opens(item) {
                    Image(systemName: "chevron.right").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .rotationEffect(.degrees(unfolded ? 90 : 0))
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: unfolded)
                }
            }
            .padding(.horizontal, MekaSpace.m)
            .padding(.vertical, MekaSpace.xs)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .contentShape(RoundedRectangle(cornerRadius: MekaRadius.m))
        }
        .buttonStyle(MekaPressStyle())
        .mekaHoverLift()
        .disabled(!ShellNav.opens(item))
        .accessibilityHint(item == .settings ? (unfolded ? "Folds the settings away" : "Shows every setting")
                           : item.destination == nil ? "Opens a sheet" : "Opens \(item.label)")
    }
}

/// A Life tile (Fold review 2026-10-12, item 7): its SF Symbol in the accent, its name and its live line ("Black bin
/// out tonight", lit in the accent when it's about today). The line cross-fades as it changes; lifts 2 pt on hover.
private struct LifeTileView: View {
    let item: MoreItem
    let text: String
    let lit: Bool
    let palette: MekaPalette
    let action: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: { MekaHaptics.tick(); action() }) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                // Ask polish, slice 1: compact — the symbol beside the name, about 30% shorter. The quality gate's
                // Ask re-shot (2026-10-22): a name on two lines keeps its symbol on the first line, not centred.
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    if let symbol = item.symbol {
                        Image(systemName: symbol).font(MekaType.caption).foregroundStyle(palette.accent)
                            .accessibilityHidden(true)
                    }
                    Text(item.label).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                        .lineLimit(Int(LifeTileRules.shared.NAME_LINES))
                }
                Text(text)
                    .font(MekaType.caption)
                    .foregroundStyle(lit ? palette.accent : palette.textSecondary)
                    // Two lines at most, space kept for both, so every tile is the same height.
                    .lineLimit(Int(LifeTileRules.shared.MAX_LINES), reservesSpace: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: text)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .topLeading)
            .padding(.horizontal, MekaSpace.m)
            .padding(.vertical, MekaSpace.xs)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .contentShape(RoundedRectangle(cornerRadius: MekaRadius.m))
        }
        .buttonStyle(MekaPressStyle())
        .mekaHoverLift()
        .accessibilityLabel("\(item.label), \(text)")
        .accessibilityHint(item.destination == nil ? "Opens a sheet" : "Opens \(item.label)")
    }
}

/// Appearance (Today clarity, slice 2: the theme moved here from Today's header). On the Mac the three choices sit in a
/// segmented control in the row itself; every colour blends across (`themeBlend`) as the choice changes.
private struct AppearanceRow: View {
    let palette: MekaPalette
    @Environment(CoreModel.self) private var model
    @AppStorage(MekaAppearance.key) private var appearance = MekaAppearance.dark.rawValue
    /// News ticker (news ticker, slice 2): how the strip at the foot of Today moves on this Mac.
    @AppStorage(NewsTickerChoice.key) private var ticker = "moving"
    /// The floating ticker (slice 2b): the same strip over every app, also in View → Floating Ticker.
    @AppStorage(FloatingTicker.enabledKey) private var floating = false
    /// Motion (motion pass 2): MEKA's own motion on this Mac; "" until chosen (then Reduce Motion decides).
    @AppStorage(MotionSetting.key) private var motion = ""
    @State private var hovering = false
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceMotion) private var systemReduce

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(MoreItem.appearance.label).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                    Text(MoreItem.appearance.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
                Spacer()
                Picker(MoreItem.appearance.label, selection: $appearance) {
                    ForEach(MekaAppearance.allCases) { a in Text(a.label).tag(a.rawValue) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
            }
            HStack {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text("Motion").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Text(MotionRules.shared.line(stored: MotionSetting.stored(motion), systemOff: systemReduce, mac: true))
                        .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: motion)
                }
                Spacer()
                // Nothing chosen shows Expressive selected: it is what plays (Meka, 2026-10-08).
                Picker("Motion", selection: Binding(get: { MotionRules.shared.lit(stored: MotionSetting.stored(motion)).id },
                                                     set: { motion = $0 })) {
                    ForEach(MotionSetting.all, id: \.id) { m in Text(m.label).tag(m.id) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
                .onChange(of: motion) { MekaHaptics.tick() }
            }
            MotionCheckView(motion: $motion, palette: palette)
            // Calmer Today, slice 1: how Today shows the day's done tasks, synced with the Fold.
            HStack {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(DoneTodayRules.shared.HEADING).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Text(model.doneTodayShown.show.line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.doneTodayShown.show.code)
                }
                Spacer()
                Picker(DoneTodayRules.shared.HEADING, selection: Binding(
                    get: { model.doneTodayShown.show.code },
                    set: { code in if let s = DoneTodayChoiceMac.all.first(where: { $0.code == code }) { model.setDoneTodayShow(s) } })) {
                    ForEach(DoneTodayChoiceMac.all, id: \.code) { s in Text(s.label).tag(s.code) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
            }
            // Text size (Mac text is too big, Meka 2026-10-15): the same four sizes as View → Text Size.
            TextSizeRow(palette: palette)
            HStack {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text("News ticker").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Text(NewsTickerChoice.mode(ticker).line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: ticker)
                }
                Spacer()
                Button("Topics…") { model.showNews = true }
                    .buttonStyle(MekaPressStyle()).font(MekaType.caption).foregroundStyle(palette.accent)
                Picker("News ticker", selection: $ticker) {
                    ForEach(NewsTickerChoice.all, id: \.id) { m in Text(m.label).tag(m.id) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
            }
            HStack {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text("Floating ticker").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Text(floating ? "Over every app · drag its grip to the top or bottom" : "Off · a thin strip over every app")
                        .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: floating)
                }
                Spacer()
                Toggle("Floating ticker", isOn: $floating)
                    .toggleStyle(.switch)
                    .labelsHidden()
                    .onChange(of: floating) {
                        MekaHaptics.tick()
                        FloatingTicker.shared.refresh()
                    }
            }
            // The name and tagline (the rename to Navilife, 2026-10-13), over the build line.
            Text(AppUpdateRules.shared.TAGLINE)
                .font(MekaType.caption).foregroundStyle(palette.textSecondary)
            // The build on this Mac (Meka, 2026-10-08), so we can tell which build is running.
            Text(AppUpdateRules.shared.versionLine(versionName: MacBuild.version, versionCode: MacBuild.number, latestCode: nil))
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .offset(y: hovering && !reduceMotion ? -2 : 0)
        .onHover { h in withAnimation(MekaMotion.appear(reduced: reduceMotion)) { hovering = h } }
    }
}

/// Calmer Today, slice 1: the three choices for Done tasks on Today, in the Fold's order.
enum DoneTodayChoiceMac {
    static var all: [DoneShow] { [.folded, .hidden, .shown] }
}

/// This Mac app's version and build number (Info.plist).
enum MacBuild {
    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0" }
    static var number: Int64 { Int64(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 1 }
}

/// Motion check (Meka, 2026-10-08: "the animation is something I have not seen work"): what MEKA sees on this Mac (its
/// own Motion choice, Reduce Motion, Low Power Mode) and the result; when animations are off the result is lit and a
/// click turns MEKA's own Expressive motion on. "Play the opening" goes back to Today and replays its opening.
private struct MotionCheckView: View {
    @Binding var motion: String
    let palette: MekaPalette
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceMotion) private var systemReduce

    var body: some View {
        let check = MotionCheckRules.shared.mac(
            stored: MotionSetting.stored(motion), reduceMotion: systemReduce,
            lowPower: ProcessInfo.processInfo.isLowPowerModeEnabled
        )
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text("Motion check").font(MekaType.caption).foregroundStyle(palette.textSecondary)
            ForEach(check.rows, id: \.label) { row in
                HStack {
                    Text(row.label).foregroundStyle(palette.textTertiary)
                    Spacer()
                    Text(row.value).foregroundStyle(palette.textSecondary)
                }
                .font(MekaType.caption)
            }
            HStack {
                if let fix = check.fix {
                    Button(check.result) {
                        MekaHaptics.tick()
                        motion = fix.id
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption).foregroundStyle(palette.accent)
                } else {
                    Text(check.result).font(MekaType.caption).foregroundStyle(palette.textPrimary)
                }
                Spacer()
                Button(MotionCheckRules.shared.PLAY_OPENING) {
                    MekaHaptics.light()
                    model.playOpening(reduced: reduceMotion)
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption).foregroundStyle(palette.accent)
                .help(MotionCheckRules.shared.PLAY_OPENING_LINE)
            }
            .contentTransition(.opacity)
            .animation(MekaMotion.appear(reduced: reduceMotion), value: check.result)
        }
    }
}

/// "To set up" on this Mac (Ask polish, slice 1): "" until he first clicks it (the core opens it on the first run only).
/// The Ask hub's choices remembered on this Mac.
enum AskHub {
    static let tabKey = "meka.ask.tab"
    static let boxesKey = "meka.ask.settingsBoxes"
}

/// The Ask hub's Life · Daily · Settings switch: one rounded track, the lit pill (the brass tint) slides across to the
/// tab picked on the expand spring (matched geometry); labels' colours blend. Reduce Motion: the pill moves at once.
private struct AskTabSwitch: View {
    let tab: AskTab
    let palette: MekaPalette
    let pick: (AskTab) -> Void
    @Namespace private var pill
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 0) {
            ForEach(AskHubRules.shared.TABS, id: \.name) { t in
                let lit = t == tab
                Button { pick(t) } label: {
                    Text(t.label)
                        .font(MekaType.itemMeta).fontWeight(lit ? .semibold : .regular)
                        .foregroundStyle(lit ? palette.accent : palette.textSecondary)
                        .frame(maxWidth: .infinity, minHeight: 30)
                        .background {
                            if lit {
                                Capsule().fill(palette.accent.opacity(0.16))
                                    .matchedGeometryEffect(id: "ask-tab-pill", in: pill)
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(lit ? [.isSelected, .isButton] : .isButton)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
            }
        }
        .padding(MekaSpace.xxs)
        .background(palette.surface, in: Capsule())
    }
}

/// Under Life's tiles when they run past a page: "1–8 of 15 · today’s first", a dot a page (the one showing long and
/// lit) and ‹ › (dimmed at the ends).
private struct LifePageTurner: View {
    let line: String
    let page: Int
    let pages: Int
    let canPrev: Bool
    let canNext: Bool
    let palette: MekaPalette
    let turn: (Int) -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
            Spacer(minLength: MekaSpace.xs)
            HStack(spacing: MekaSpace.xxs) {
                ForEach(0..<pages, id: \.self) { i in
                    Capsule().fill(i == page ? palette.accent : palette.hairline)
                        .frame(width: i == page ? 14 : 5, height: 5)
                }
            }
            .accessibilityHidden(true)
            arrow("chevron.left", can: canPrev, label: "Previous page of places") { turn(-1) }
            arrow("chevron.right", can: canNext, label: "Next page of places") { turn(1) }
        }
    }

    private func arrow(_ symbol: String, can: Bool, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(MekaType.caption)
                .foregroundStyle(can ? palette.accent : palette.textTertiary)
                .frame(width: 28, height: 28)
                .background(palette.surfaceRaised, in: Circle())
        }
        .buttonStyle(MekaPressStyle())
        .disabled(!can)
        .accessibilityLabel(label)
    }
}

/// A Settings box (the Ask hub): its title, and while folded the names in it on one line; a click unfolds its rows on
/// the expand spring, the chevron turning with it. Reduce Motion: it fades.
private struct SettingsBoxView<Row: View>: View {
    let box: SettingsBox
    let rows: [MoreItem]
    let open: Bool
    let palette: MekaPalette
    let toggle: () -> Void
    @ViewBuilder let row: (MoreItem) -> Row
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button(action: toggle) {
                HStack {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(box.title).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
                            .accessibilityAddTraits(.isHeader)
                        if !open {
                            Text(AskHubRules.shared.peek(labels: rows.map(\.label)))
                                .font(MekaType.caption).foregroundStyle(palette.textTertiary).lineLimit(1)
                        }
                    }
                    Spacer()
                    Image(systemName: "chevron.right").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .rotationEffect(.degrees(open ? 90 : 0))
                        .accessibilityHidden(true)
                }
                .padding(.horizontal, MekaSpace.m)
                .padding(.vertical, MekaSpace.xs)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityValue(open ? "Open" : "Closed")
            if open {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(rows, id: \.self) { item in
                        Rectangle().fill(palette.hairline).frame(height: 1).padding(.horizontal, MekaSpace.m)
                        row(item)
                    }
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .clipShape(RoundedRectangle(cornerRadius: MekaRadius.m))
    }
}

enum AskSetUp {
    static let key = "meka.ask.setUpOpen"
    static let open = "open"
    static let closed = "closed"

    static func stored(_ raw: String) -> KotlinBoolean? {
        switch raw {
        case open: return KotlinBoolean(bool: true)
        case closed: return KotlinBoolean(bool: false)
        default: return nil
        }
    }
}
