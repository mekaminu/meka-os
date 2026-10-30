@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Health on the Mac (Reliability first, item 3), like the Fold's HealthPane: everything MEKA depends on
/// with a tick or the next step — MEKA's server, each calendar, the feeds, the call assistant, the AI and MEKA's voice
/// (the Fold adds its battery, notification access, instant updates and its build). The rows are the core's
/// `HealthRules`; a fix that needs the phone (battery, notification access, call screening) shows on the Fold only.
///
/// Motion: the sheet scale-fades (system); a shimmer until the first check; rows stagger in; each status dot's colour
/// blends as it changes and the lines cross-fade; a fix presses in with a light haptic. Reduce Motion: cross-fades.
struct HealthSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Health").font(MekaType.upNextTitle).staggeredAppear(0)
            if let view = model.health {
                Text(LoneWordGlue.shared.kept(line: view.summary)).font(MekaType.body)
                    .foregroundStyle(view.critical ? palette.critical : view.attention > 0 ? palette.accent : palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.themeBlend(reduced: reduceMotion), value: view.summary)
                    .staggeredAppear(0)
                Text(HealthRules.shared.checkedShown(view: view, checking: model.checkingHealth))
                    .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .contentTransition(.opacity)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(Array(view.rows.enumerated()), id: \.element.key) { i, row in
                            HealthRowView(row: row, palette: palette, onOpen: row.place.flatMap { opener($0) }) { fix(row) }
                                .staggeredAppear(i + 1)
                        }
                    }
                }
                .frame(maxHeight: 460)
            } else {
                Text("Checking…").font(MekaType.body).foregroundStyle(palette.textSecondary)
                SkeletonRows(count: 6, palette: palette)
            }
            HStack {
                Button("Check again") { Task { await model.refreshHealth() } }
                    .buttonStyle(MekaPressStyle())
                    .disabled(model.checkingHealth)
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 500)
        .background(palette.surface)
        .task { await model.refreshHealth() }
    }

    /// Meka 2026-10-24 13:12: a click on a row opens its screen; nil (nowhere to go here) leaves the row plain.
    private func opener(_ place: HealthPlace) -> (() -> Void)? {
        let open: (() -> Void)?
        switch place {
        case .voice: open = { model.showVoice = true }
        case .calendars: open = { model.showCalendars = true }
        case .mail: open = { model.showMail = true }
        case .money: open = { model.showMoney = true }
        case .aboutYou: open = { model.showAboutYou = true }
        case .work: open = { model.showWork = true }
        case .notifications: open = { model.showNotifications = true }
        case .systemBattery, .systemListener: open = nil
        }
        guard let open else { return nil }
        return {
            MekaHaptics.light()
            dismiss()
            open()
        }
    }

    /// What a row's button does on the Mac. Only the strings cross into the core.
    private func fix(_ row: HealthRow) {
        MekaHaptics.light()
        guard let f = row.fix else { return }
        switch f {
        case .syncNow: Task { await model.syncNow(); await model.refreshHealth() }
        case .reconnect:
            guard let provider = row.provider else { return }
            let editing = row.editing
            Task { await model.connectCalendar(provider, editing: editing) }
        case .calendars: dismiss(); model.showCalendars = true
        case .work: dismiss(); model.showWork = true
        // Notifications never seen, the Mac's half: asks macOS the first time, else opens System Settings.
        case .notificationsOn: model.turnOnNotifications()
        // Obi's face: look at the server's list now, then check again so the face line says what it found.
        case .faceLookAgain: Task { await MacFaceStore.shared.refresh(model, force: true); await model.refreshHealth() }
        case .battery, .notificationAccess, .callRole, .installUpdate, .callerLookups: break
        }
    }

    /// Whether the Mac can do a row's fix (the phone's own settings can't be reached from here).
    static func fixHere(_ row: HealthRow) -> Bool {
        guard let f = row.fix else { return false }
        switch f {
        case .syncNow, .reconnect, .calendars, .work, .notificationsOn, .faceLookAgain: return true
        case .battery, .notificationAccess, .callRole, .installUpdate, .callerLookups: return false
        }
    }
}

private struct HealthRowView: View {
    let row: HealthRow
    let palette: MekaPalette
    let onOpen: (() -> Void)?
    let onFix: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    private var dot: Color {
        switch row.state {
        case .ok: palette.success
        case .warn: palette.accent
        case .bad: palette.critical
        case .unknown: palette.textTertiary
        // Off by his choice (the walk-through's A7): neither the working green nor a fault's colour.
        case .off: palette.textTertiary
        }
    }

    private var spoken: String {
        switch row.state {
        case .ok: "working"
        case .warn: "needs a look"
        case .bad: "not working"
        case .unknown: "couldn't check"
        case .off: "off"
        }
    }

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            Circle().fill(dot).frame(width: 9, height: 9)
                .animation(MekaMotion.themeBlend(reduced: reduceMotion), value: spoken)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.title).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                Text(LoneWordGlue.shared.kept(line: row.line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: row.line)
                if let detail = row.detail {
                    Text(LoneWordGlue.shared.kept(line: detail)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: detail)
                }
            }
            Spacer()
            if HealthSheet.fixHere(row), let label = row.fixLabel {
                Button(label, action: onFix)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.surfaceRaised, in: Capsule())
            } else if onOpen != nil {
                Image(systemName: "chevron.right").font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .contentShape(RoundedRectangle(cornerRadius: MekaRadius.m))
        .onTapGesture { onOpen?() }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(row.title), \(spoken). \(row.line)" + (row.detail.map { " \($0)" } ?? ""))
        .accessibilityAddTraits(onOpen != nil ? .isButton : [])
    }
}

