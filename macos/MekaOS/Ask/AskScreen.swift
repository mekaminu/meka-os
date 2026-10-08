@preconcurrency import MekaKit
import SwiftUI

/// ASK on the Mac (build plan M1, Four tabs, one front door): the front door to everything that isn't a tab. Until the
/// AI layer lands, asking is searching (the field opens Search Everything, as ⌘F does). More lists the places behind
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
                Button { model.showSearch = true } label: {
                    HStack {
                        Image(systemName: "magnifyingglass").foregroundStyle(palette.textTertiary)
                        Text("Search everything").font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                        Spacer()
                        Text("⌘F").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    }
                    .padding(.horizontal, MekaSpace.l)
                    .frame(minHeight: 44)
                    .background(palette.surfaceRaised, in: Capsule())
                    .contentShape(Capsule())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel("Search everything")
                .staggeredAppear(1)
                Text("Tasks, events, lists, goals and habits. Asking in your own words comes with the AI layer.")
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.leading, MekaSpace.xxs)
                    .padding(.bottom, MekaSpace.l)
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
                                MoreRow(item: item, line: ShellNav.moreLine(item, listsDue: model.listsDue, atWork: model.work?.atWork == true),
                                        lit: ShellNav.moreLit(item, listsDue: model.listsDue), palette: palette) { open(item) }
                            }
                        }
                        .staggeredAppear(ShellNav.moreRowStep(s, i))
                    }
                }
            }
            .frame(maxWidth: 560, alignment: .leading)
            .padding(.horizontal, MekaSpace.gutter)
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
                VStack(alignment: .leading, spacing: 2) {
                    // Places and settings you open, not things you act on: the regular weight (type weight, 2026-10-06).
                    Text(item.label).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                    Text(line)
                        .font(MekaType.caption)
                        .foregroundStyle(lit ? palette.accent : palette.textSecondary)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(palette.textTertiary)
            }
            .padding(.horizontal, MekaSpace.m)
            .padding(.vertical, MekaSpace.s)
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
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
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
                VStack(alignment: .leading, spacing: 2) {
                    Text("Motion").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Text(MotionRules.shared.line(stored: MotionSetting.stored(motion), systemOff: systemReduce, mac: true))
                        .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: motion)
                }
                Spacer()
                Picker("Motion", selection: $motion) {
                    ForEach(MotionSetting.all, id: \.id) { m in Text(m.label).tag(m.id) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .fixedSize()
                .onChange(of: motion) { MekaHaptics.tick() }
            }
            HStack {
                VStack(alignment: .leading, spacing: 2) {
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
                VStack(alignment: .leading, spacing: 2) {
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
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.s)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .offset(y: hovering && !reduceMotion ? -2 : 0)
        .onHover { h in withAnimation(MekaMotion.appear(reduced: reduceMotion)) { hovering = h } }
    }
}
