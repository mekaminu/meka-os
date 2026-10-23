@preconcurrency import MekaKit
import AppKit
import SwiftUI

/// Ask → More → Setup on the Mac (Setup checklist, Meka approved 2026-10-09), like the Fold's SetupPane: every
/// capability MEKA has, section by section, with a tick or the next step. The steps are the core's `SetupRules`;
/// the phone's own steps (call screening, family, WhatsApp and texts, request watch, battery) read "On the Fold".
///
/// Motion: the sheet scale-fades (system); a shimmer until the first check; sections and steps stagger in; each dot's
/// colour blends and its line cross-fades as a step is done; a button presses in with a light haptic. Reduce Motion:
/// cross-fades.
struct SetupSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Setup").font(MekaType.upNextTitle).staggeredAppear(0)
            if let view = model.setup {
                Text(LoneWordGlue.shared.kept(line: view.summary)).font(MekaType.body)
                    .foregroundStyle(view.toDo > 0 ? palette.accent : palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.themeBlend(reduced: reduceMotion), value: view.summary)
                    .staggeredAppear(0)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(Array(view.sections.enumerated()), id: \.element.title) { s, section in
                            Text(section.title.uppercased())
                                .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                                .foregroundStyle(palette.textTertiary)
                                .padding(.top, s == 0 ? 0 : MekaSpace.m)
                                .accessibilityAddTraits(.isHeader)
                                .staggeredAppear(s + 1)
                            ForEach(section.steps, id: \.key) { step in
                                SetupStepView(step: step, palette: palette) { fix(step) }
                                    .staggeredAppear(s + 1)
                            }
                        }
                    }
                }
                .frame(maxHeight: 520)
            } else {
                Text("Checking…").font(MekaType.body).foregroundStyle(palette.textSecondary)
                SkeletonRows(count: 8, palette: palette)
            }
            HStack {
                Button("Check again") { Task { await model.refreshSetup() } }
                    .buttonStyle(MekaPressStyle())
                    .disabled(model.checkingSetup)
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 520)
        .background(palette.surface)
        .task { await model.refreshSetup() }
    }

    /// What a step's button does on the Mac.
    private func fix(_ step: SetupStep) {
        MekaHaptics.light()
        guard let f = step.fix else { return }
        switch f {
        case .calendars: dismiss(); model.showCalendars = true
        case .work: dismiss(); model.showWork = true
        case .voice: dismiss(); model.showVoice = true
        case .recovery: dismiss(); model.showRecovery = true
        case .aboutYou: dismiss(); model.showAboutYou = true
        case .mail: dismiss(); model.showMail = true
        case .notifications:
            Task {
                // macOS asks once; after that the choice lives in System Settings → Notifications → MEKA.
                if !(await MacNotifier.requestPermission()),
                   let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") {
                    NSWorkspace.shared.open(url)
                }
                await model.refreshSetup()
            }
        case .notificationAccess, .callRole, .battery, .screenSaver: break
        }
    }

    /// Whether the Mac can do a step's button (the phone's own settings can't be reached from here).
    static func fixHere(_ step: SetupStep) -> Bool {
        guard step.toDo, let f = step.fix else { return false }
        switch f {
        case .calendars, .work, .voice, .notifications, .recovery, .aboutYou, .mail: return true
        case .notificationAccess, .callRole, .battery, .screenSaver: return false
        }
    }

    /// The local day as the core counts it (days since 1970-01-01 in this time zone), for "Not today".
    static func today(_ date: Date = Date(), zone: TimeZone = .current) -> Int64 {
        Int64(((date.timeIntervalSince1970 + Double(zone.secondsFromGMT(for: date))) / 86_400).rounded(.down))
    }

    static let hiddenKey = "meka.setup.hiddenDay"
}

private struct SetupStepView: View {
    let step: SetupStep
    let palette: MekaPalette
    let onFix: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    private var dot: Color {
        switch step.state {
        case .done: palette.success
        case .todo: palette.accent
        case .elsewhere, .later, .unknown: palette.textTertiary
        }
    }

    private var spoken: String {
        switch step.state {
        case .done: "done"
        case .todo: "to do"
        case .elsewhere: "on the Fold"
        case .later: "comes later"
        case .unknown: "couldn't check"
        }
    }

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            Circle().fill(dot).frame(width: 9, height: 9)
                .animation(MekaMotion.themeBlend(reduced: reduceMotion), value: spoken)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(step.title).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                Text(LoneWordGlue.shared.kept(line: step.line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: step.line)
            }
            Spacer()
            if SetupSheet.fixHere(step), let label = step.fixLabel {
                Button(label, action: onFix)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.surfaceRaised, in: Capsule())
            }
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(step.title), \(spoken). \(step.line)")
    }
}

/// Today's one quiet line on the Mac (Mac presentation pass, slice 2, item 4; core MacHeaderRules): what's left to set
/// up and anything Health needs, merged — "Finish setting up · 4 steps · notifications are off" with Open Setup — in
/// place of a red Health line plus a Setup card. With nothing left to set up it is Health's own line (critical colour
/// when something is broken) with Open Health. Right-click → Not today hides the setup part until tomorrow (folds away
/// on the expand spring). The text cross-fades; Setup and Health are checked on open.
struct MacSetupLineView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @AppStorage(SetupSheet.hiddenKey) private var hiddenDay: Int = Int.min
    let palette: MekaPalette

    private var line: MacSetupLine? {
        let hidden: KotlinLong? = hiddenDay == Int.min ? nil : KotlinLong(longLong: Int64(hiddenDay))
        let shown = SetupRules.shared.cardShown(view: model.setup, hiddenOnDay: hidden, today: SetupSheet.today())
        return MacHeaderRules.shared.setupLine(setupShown: shown, stepsLeft: model.setup?.toDo ?? 0, health: model.health)
    }

    var body: some View {
        // A VStack rather than a Group, so the checks below run even while there is no line to show.
        VStack(alignment: .leading, spacing: 0) {
            if let line {
                let setup = line.opens == MacHeaderOpens.setup
                Button {
                    MekaHaptics.light()
                    if setup { model.showSetup = true } else { model.showHealth = true }
                } label: {
                    HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                        Text(line.text)
                            .foregroundStyle(line.critical ? palette.critical : palette.textSecondary)
                            .lineLimit(1)
                            .truncationMode(.tail)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.appear(reduced: reduceMotion), value: line.text)
                        Text(line.action + " ›").foregroundStyle(palette.accent).fixedSize()
                    }
                    .font(MekaType.caption)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                .accessibilityLabel(line.spoken)
                .accessibilityHint(setup ? "Opens Setup" : "Opens Health")
                .contextMenu {
                    if setup {
                        Button("Not today") {
                            MekaHaptics.tick()
                            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { hiddenDay = Int(SetupSheet.today()) }
                        }
                    }
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .task { await model.refreshSetup(force: false) }
        .task { await model.refreshHealth(force: false) }
    }
}
