@preconcurrency import MekaKit
import SwiftUI

/// ASK on the Mac (build plan M1, Four tabs, one front door): the front door to everything that isn't a tab. The field
/// asks MEKA in your own words (`AskMekaSection`, V1 AI layer slice 3b), with Search Everything (⌘F) beside it. Below it
/// (Fold review 2026-10-12, item 7): one status row when Setup or Health needs a look; LIFE, a grid of tiles (an SF
/// Symbol, the name and a live line each; Lists and Goals open their places with the shell's push, the rest their
/// sheets); DAILY, a few rows; and one Settings row that unfolds every setting in place under it (the Mac's simpler
/// layout: no sheet over a sheet). Motion: title, field, status row and each part stagger in, a part a step after the
/// one before began, tiles a row at a time; rows and tiles lift 2 pt on hover; lines cross-fade and lit ones blend to
/// the accent; Settings unfolds on the expand spring with its rows staggering in.
struct AskScreen: View {
    let palette: MekaPalette
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var settingsOpen = false
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
                sectionLabel(MoreGroup.life.label, first: true).staggeredAppear(ShellNav.moreLabelStep(0))
                // Ask polish, slice 1: on the first run one welcome line; tiles still to set up sink under "To set up".
                if let lines = model.lifeLines, let welcome = LifeTileRules.shared.welcome(lines: lines) {
                    Text(welcome).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .padding(.bottom, MekaSpace.xxs)
                        .staggeredAppear(ShellNav.tileStep(0))
                }
                tileGrid(ShellNav.lifeLive(model.lifeLines), first: 0)
                    .animation(MekaMotion.expand(reduced: reduceMotion), value: ShellNav.lifeLive(model.lifeLines))
                setUpSection()
                sectionLabel(MoreGroup.daily.label, first: false).staggeredAppear(ShellNav.moreLabelStep(1))
                ForEach(Array(ShellNav.daily().enumerated()), id: \.element) { i, item in
                    // Ask polish, item 3: each row says what it holds now; lit when it's ready or wants a look.
                    let line = ShellNav.dailyLine(item, lines: model.dailyLines, voiceNotes: model.voiceNotesLine(MacVoiceNoteStore.shared.notes))
                    MoreRow(item: item, line: line.text, lit: line.lit, palette: palette) { open(item) }
                        .staggeredAppear(ShellNav.moreRowStep(1, i))
                }
                MoreRow(item: .settings, line: MoreItem.settings.line, lit: false, palette: palette, unfolded: settingsOpen) {
                    MekaHaptics.tick()
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { settingsOpen.toggle() }
                }
                .padding(.top, MekaSpace.m)
                .staggeredAppear(ShellNav.settingsStep)
                if settingsOpen {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(Array(ShellNav.settings(connected: connected).enumerated()), id: \.element) { i, item in
                            Group {
                                if ShellNav.unfoldsInPlace(item) {
                                    AppearanceRow(palette: palette)
                                } else if item == .ai {
                                    MoreRow(item: item, line: model.aiStatus?.line ?? "Checking…", lit: model.aiStatus?.lit == true, palette: palette) {}
                                } else {
                                    MoreRow(item: item, line: ShellNav.moreLine(item, listsDue: model.listsDue, atWork: model.work?.atWork == true,
                                                                                health: model.health?.summary, setup: model.setup?.summary),
                                            lit: ShellNav.moreLit(item, listsDue: model.listsDue, healthAttention: Int(model.health?.attention ?? 0),
                                                                  setupLeft: Int(model.setup?.toDo ?? 0)), palette: palette) { open(item) }
                                }
                            }
                            .staggeredAppear(i)
                        }
                    }
                    .padding(.leading, MekaSpace.m)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }
            }
            .frame(maxWidth: 560, alignment: .leading)
            .padding(.horizontal, MekaSpace.gutterWide)
            .padding(.vertical, MekaSpace.xl)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .background(palette.background)
    }

    /// "To set up · N" (Ask polish, slice 1): the tiles still waiting to be set up, under a label that opens and closes
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

    private func sectionLabel(_ label: String, first: Bool) -> some View {
        Text(label.uppercased())
            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
            .padding(.top, first ? 0 : MekaSpace.m)
            .accessibilityAddTraits(.isHeader)
    }

    /// Take me there under a help answer: the sheet or place whose `MoreItem` case is the place's key; Appearance,
    /// Navilife's AI and Settings unfold Settings in place.
    private func openHelp(_ place: HelpPlace) {
        guard let item = MoreItem.allCases.first(where: { "\($0)" == place.key }) else { return }
        switch item {
        case .appearance, .ai, .settings:
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { settingsOpen = true }
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
        case .emergency: model.showEmergency = true
        case .trainQueue: model.showTrainQueue = true
        case .voiceNotes: model.showVoiceNotes = true
        case .errands: model.showErrands = true
        case .appointments: model.showAppointments = true
        case .bins: model.showBins = true
        case .car: model.showCar = true
        case .gifts: model.showGifts = true
        case .people: model.showPeople = true
        case .trips: model.showTrips = true
        case .facts: model.showFacts = true
        case .aboutYou: model.showAboutYou = true
        case .work: model.showWork = true
        case .protectedTime: model.showProtected = true
        case .notifications: model.showNotifications = true
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
                    Text(line)
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
                // Ask polish, slice 1: compact — the symbol beside the name, about 30% shorter.
                HStack(spacing: MekaSpace.xs) {
                    if let symbol = item.symbol {
                        Image(systemName: symbol).font(MekaType.caption).foregroundStyle(palette.accent)
                            .accessibilityHidden(true)
                    }
                    Text(item.label).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary).lineLimit(1)
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
    @AppStorage(NewsTickerChoice.key) private var ticker = "calm"
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
