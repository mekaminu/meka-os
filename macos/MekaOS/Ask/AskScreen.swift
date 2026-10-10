@preconcurrency import MekaKit
import SwiftUI

/// ASK on the Mac (build plan M1, Four tabs, one front door): the front door to everything that isn't a tab. The field
/// asks MEKA in your own words (`AskMekaSection`, V1 AI layer slice 3b), with Search Everything (⌘F) beside it. More lists the places behind
/// Ask (they open with the shell's push and keep Ask lit in spirit: the sidebar shows them under More) and the sheets.
/// Motion: title, field and More's sections (Places · Daily · Settings) stagger in, each a step after the one before; rows lift 2 pt on hover; a lit Lists line blends its colour.
struct AskScreen: View {
    let palette: MekaPalette
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text(ShellDestination.ask.label)
                    .font(MekaType.greeting).tracking(MekaType.greetingTracking)
                    .foregroundStyle(palette.textPrimary)
                    .padding(.bottom, MekaSpace.m)
                    .staggeredAppear(0)
                // Ask MEKA (V1 AI layer, slice 3b): the field asks in your own words; Search sits beside it.
                AskMekaSection(palette: palette)
                    .staggeredAppear(1)
                // More in sections (Fold review 2026-10-08, item 10): Places · Daily · Settings, a small label over each.
                ForEach(Array(ShellNav.moreSections(connected: model.isConnected && !model.signedOut).enumerated()), id: \.element.id) { s, section in
                    Text(section.group.label.uppercased())
                        .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                        .foregroundStyle(palette.textTertiary)
                        .padding(.top, s == 0 ? 0 : MekaSpace.m)
                        .accessibilityAddTraits(.isHeader)
                        .staggeredAppear(ShellNav.moreLabelStep(s))
                    ForEach(Array(section.items.enumerated()), id: \.element) { i, item in
                        Group {
                            if ShellNav.unfoldsInPlace(item) {
                                AppearanceRow(palette: palette)
                            } else {
                                MoreRow(item: item, line: ShellNav.moreLine(item, listsDue: model.listsDue, atWork: model.work?.atWork == true,
                                                                            health: model.health?.summary, setup: model.setup?.summary),
                                        lit: ShellNav.moreLit(item, listsDue: model.listsDue, healthAttention: Int(model.health?.attention ?? 0),
                                                              setupLeft: Int(model.setup?.toDo ?? 0)), palette: palette) { open(item) }
                            }
                        }
                        .staggeredAppear(ShellNav.moreRowStep(s, i))
                    }
                }
            }
            .frame(maxWidth: 560, alignment: .leading)
            .padding(.horizontal, MekaSpace.gutterWide)
            .padding(.vertical, MekaSpace.xl)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .background(palette.background)
    }

    private func open(_ item: MoreItem) {
        if let d = item.destination { model.go(to: d, reduced: reduceMotion); return }
        switch item {
        case .brief: model.showBrief = true
        case .news: model.showNews = true
        case .shutdown: model.showShutdown = true
        case .work: model.showWork = true
        case .notifications: model.showNotifications = true
        case .activity: model.showActivity = true
        case .yourData: model.showYourData = true
        case .calendars: model.showCalendars = true
        case .family: model.showFamily = true
        case .voice: model.showVoice = true
        case .talk: model.showTalk = true
        case .health: model.showHealth = true
        case .setup: model.showSetup = true
        case .lists, .goals, .review, .vault, .appearance: break
        }
    }
}

private struct MoreRow: View {
    let item: MoreItem
    let line: String
    let lit: Bool
    let palette: MekaPalette
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
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
                }
                Spacer()
                Image(systemName: "chevron.right").font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            .padding(.horizontal, MekaSpace.m)
            .padding(.vertical, MekaSpace.xs)
            .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .contentShape(RoundedRectangle(cornerRadius: MekaRadius.m))
        }
        .buttonStyle(MekaPressStyle())
        .mekaHoverLift()
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
