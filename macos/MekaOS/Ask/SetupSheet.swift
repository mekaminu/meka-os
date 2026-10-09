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
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            Text("Setup").font(MekaType.upNextTitle).staggeredAppear(0)
            if let view = model.setup {
                Text(view.summary).font(MekaType.body)
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
                                .padding(.top, s == 0 ? 0 : MekaSpace.s)
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
            .padding(.top, MekaSpace.s)
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
        case .notifications:
            Task {
                // macOS asks once; after that the choice lives in System Settings → Notifications → MEKA.
                if !(await MacNotifier.requestPermission()),
                   let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") {
                    NSWorkspace.shared.open(url)
                }
                await model.refreshSetup()
            }
        case .notificationAccess, .callRole, .battery: break
        }
    }

    /// Whether the Mac can do a step's button (the phone's own settings can't be reached from here).
    static func fixHere(_ step: SetupStep) -> Bool {
        guard step.toDo, let f = step.fix else { return false }
        switch f {
        case .calendars, .work, .voice, .notifications: return true
        case .notificationAccess, .callRole, .battery: return false
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
        HStack(spacing: MekaSpace.s) {
            Circle().fill(dot).frame(width: 9, height: 9)
                .animation(MekaMotion.themeBlend(reduced: reduceMotion), value: spoken)
            VStack(alignment: .leading, spacing: 2) {
                Text(step.title).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                Text(step.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
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
        .padding(.vertical, MekaSpace.s)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(step.title), \(spoken). \(step.line)")
    }
}

/// Today's setup card on the Mac (Setup checklist): while something is left, "SET UP MEKA" and the line ("3 steps
/// left · …") with Open Setup (light haptic; the sheet scale-fades) and Not today (tick haptic; folds away until
/// tomorrow on the expand spring). Checked on open, the server asked at most every quarter hour.
struct SetupTodayCard: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @AppStorage(SetupSheet.hiddenKey) private var hiddenDay: Int = Int.min
    let palette: MekaPalette

    private var shown: Bool {
        let hidden: KotlinLong? = hiddenDay == Int.min ? nil : KotlinLong(longLong: Int64(hiddenDay))
        return SetupRules.shared.cardShown(view: model.setup, hiddenOnDay: hidden, today: SetupSheet.today())
    }

    var body: some View {
        // A VStack rather than a Group, so the check below runs even while there is no card to show.
        VStack(alignment: .leading, spacing: 0) {
            if shown, let line = model.setup?.todayLine {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text("SET UP MEKA")
                        .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                        .foregroundStyle(palette.textTertiary)
                    Text(line).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
                    HStack(spacing: MekaSpace.m) {
                        Button("Open Setup") {
                            MekaHaptics.light()
                            model.showSetup = true
                        }
                        .buttonStyle(MekaPressStyle())
                        .foregroundStyle(palette.accent)
                        Button("Not today") {
                            MekaHaptics.tick()
                            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { hiddenDay = Int(SetupSheet.today()) }
                        }
                        .buttonStyle(MekaPressStyle())
                        .foregroundStyle(palette.textSecondary)
                    }
                    .font(MekaType.itemMeta)
                }
                .padding(.horizontal, MekaSpace.m)
                .padding(.vertical, MekaSpace.s)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .task { await model.refreshSetup(force: false) }
    }
}
