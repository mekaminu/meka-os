@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Settings → Mail on the Mac (Inbox autopilot, slice 1), like the Fold's MailPane: the connected mail
/// accounts with their labels (Personal · Business · Outlook), Reconnect when a sign-in ended, and Connect Gmail /
/// Connect Outlook, which open the provider's own sign-in page in the browser. The rows are the core's
/// `MailAccountRules`; only Strings and the chosen label cross to the core. The list is read again when MEKA comes back
/// to the front, so a connection made in the browser shows at once.
///
/// Motion: the sheet scale-fades (system); a shimmer until the server answers; rows stagger in; each account's title
/// and line cross-fade; a label chip's fill blends with a tick haptic; Connect gives a light haptic; the note under the
/// buttons cross-fades. Reduce Motion: cross-fades.
///
/// Slice 1b: an account whose Google sign-in ends within the day says so in the accent with Reconnect; Disconnect on
/// each row unfolds a question in place (expand spring, tick haptic) and Disconnect there (medium haptic) has the
/// server forget the sign-in; the row leaves and the note says where to remove MEKA at the provider as well.
///
/// Slice 3b-2: **Autopilot** on top (its line and Turn off / Turn on, medium haptic) and under each account its tidies
/// with **Undo** (light haptic); an asked Undo cross-fades to "Putting back at the next check…", then the server's note.
/// Reduce Motion: cross-fades only.
struct MailSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(MailAccountRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            Text(MailAccountRules.shared.HEADER).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .staggeredAppear(1)
            if let view = model.mail {
                if let empty = view.emptyLine {
                    Text(empty).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if let panel = view.autopilot {
                    AutopilotPanelView(panel: panel, palette: palette) {
                        let on = !panel.on
                        Task { await model.setMailAutopilot(on) }
                    }
                    .staggeredAppear(2)
                    // Slice 7: when the ten-minute inbox comes (synced with the Fold).
                    if let key = model.mailMomentKey {
                        MomentPanelView(key: key, palette: palette) { picked in
                            Task { await model.setMailMomentKey(picked) }
                        }
                        .staggeredAppear(2)
                    }
                }
                ForEach(Array(view.rows.enumerated()), id: \.element.email) { i, row in
                    MailAccountRowView(row: row, palette: palette, onReconnect: {
                        guard let c = view.connects.first(where: { $0.provider == row.provider }) else { return }
                        let provider = c.provider, note = c.note
                        Task { await model.connectMail(provider, note: note) }
                    }, onLabel: { label in
                        let provider = row.provider, email = row.email
                        Task { await model.setMailLabel(provider: provider, email: email, label: label) }
                    }, onDisconnect: {
                        let provider = row.provider, email = row.email
                        Task { await model.disconnectMail(provider: provider, email: email) }
                    }, onUndo: { id in
                        Task { await model.undoMailTidy(id) }
                    }, onUnsubscribe: { id in
                        Task { await model.unsubscribeMailSender(id) }
                    })
                    .staggeredAppear(i + 3)
                }
                if let left = view.toConnect {
                    Text(left).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                HStack(spacing: MekaSpace.xs) {
                    ForEach(view.connects, id: \.provider) { c in
                        Button(c.label) {
                            let provider = c.provider, note = c.note
                            Task { await model.connectMail(provider, note: note) }
                        }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.accent)
                    }
                }
                .padding(.top, MekaSpace.xs)
            } else if model.mailOffline {
                Text(MailAccountRules.shared.OFFLINE).font(MekaType.itemMeta).foregroundStyle(palette.critical)
            } else {
                SkeletonRows(count: 2, rowHeight: 56, palette: palette)
            }
            if let note = model.mailNote {
                Text(note).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
            }
            if let message = model.mailMessage {
                Text(message).font(MekaType.caption).foregroundStyle(palette.critical)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .background(palette.surface)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.mailNote)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.mail?.rows.map(\.title) ?? [])
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.mail?.rows.map(\.sortedLine) ?? [])
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.mail?.rows.map(\.tidyLine) ?? [])
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.mail?.rows.map { $0.tidies.map { $0.state ?? "" } } ?? [])
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.mail?.autopilot?.line)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.mail?.rows.map { $0.senders.map { $0.state ?? "" } } ?? [])
        .task { await model.loadMail(); await model.loadMailMomentKey() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            Task { await model.loadMail() }
        }
    }
}

/// Slice 7: the ten-minute inbox's time — Morning · Evening · Off (the lit one fills with the accent on the appear
/// curve, tick haptic); the line cross-fades. Only the key (a String) goes back to the model.
private struct MomentPanelView: View {
    let key: String
    let palette: MekaPalette
    let onPick: (String) -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion
    private let times: [MailMomentTime] = [.morning, .evening, .off]

    var body: some View {
        let current = MailMomentTime.companion.of(key: key) ?? MailMomentTime.morning
        let line = MailMomentRules.shared.settingLine(time: current)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: 0) {
                Text(MailMomentRules.shared.TITLE).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
            }
            .accessibilityElement(children: .combine)
            HStack(spacing: MekaSpace.xs) {
                ForEach(times, id: \.key) { t in
                    let lit = t.key == key
                    Button(t.label) { if !lit { onPick(t.key) } }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(lit ? palette.onAccent : palette.textPrimary)
                        .padding(.horizontal, MekaSpace.m)
                        .padding(.vertical, MekaSpace.xxs)
                        .background(Capsule().fill(lit ? palette.accent : palette.hairline))
                        .accessibilityLabel("Ten-minute inbox: \(t.label)")
                        .accessibilityAddTraits(lit ? .isSelected : [])
                }
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: key)
    }
}

/// Autopilot's switch: what it does (or that it's off or paused) and Turn off / Turn on.
private struct AutopilotPanelView: View {
    let panel: MailAutopilotPanel
    let palette: MekaPalette
    let onSwitch: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        HStack(alignment: .center, spacing: MekaSpace.xs) {
            Circle().fill(panel.on && !panel.paused ? palette.accent : palette.hairline)
                .frame(width: 10, height: 10)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: panel.on)
            VStack(alignment: .leading, spacing: 0) {
                Text(panel.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(panel.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
            }
            Spacer(minLength: MekaSpace.xs)
            Button(panel.action, action: onSwitch)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
                .padding(.horizontal, MekaSpace.m)
                .padding(.vertical, MekaSpace.xxs)
                .background(Capsule().fill(palette.hairline))
        }
        .padding(MekaSpace.m)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
    }
}

private struct MailAccountRowView: View {
    let row: MailAccountRow
    let palette: MekaPalette
    let onReconnect: () -> Void
    let onLabel: (MailLabel) -> Void
    let onDisconnect: () -> Void
    let onUndo: (String) -> Void
    var onUnsubscribe: (String) -> Void = { _ in }
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.openURL) private var openURL
    @State private var asking = false
    @State private var allSenders = false

    /// One sender: its name, "example.com · 8 emails · last Mon 12 Oct", Unsubscribe (or Open page), and the state once asked.
    @ViewBuilder private func senderLine(_ s: MailSenderRow) -> some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 0) {
                Text(s.name).font(MekaType.body).foregroundStyle(s.done ? palette.textTertiary : palette.textPrimary)
                if !s.line.isEmpty {
                    Text(s.line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                if let state = s.state {
                    Text(state).font(MekaType.caption).foregroundStyle(s.done ? palette.accent : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                        .transition(.opacity)
                }
            }
            Spacer()
            if let link = s.link, let url = URL(string: link) {
                Button(MailSenderPanelRules.shared.OPEN) { openURL(url) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
            } else if s.canUnsubscribe {
                let id = s.id
                Button(MailSenderPanelRules.shared.UNSUBSCRIBE) { onUnsubscribe(id) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
            }
        }
        .padding(.top, MekaSpace.xxs)
        .transition(.opacity)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(row.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        .contentTransition(.opacity)
                    Text(row.email).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
                Spacer()
                if row.needsReconnect {
                    Button("Reconnect", action: onReconnect)
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.accent)
                }
            }
            Text(row.line).font(MekaType.caption)
                .foregroundStyle(row.ending ? palette.accent : row.needsReconnect ? palette.critical : palette.textTertiary)
                .contentTransition(.opacity)
            // Slice 2a: what the rules made of the inbox, once the server has sorted the account.
            if let sorted = row.sortedLine {
                Text(sorted).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .transition(.opacity)
            }
            // Slice 3a: Autopilot's dry run, what it would tidy (nothing moves yet).
            if let tidy = row.tidyLine {
                Text(tidy).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .contentTransition(.opacity)
                    .transition(.opacity)
            }
            // Slice 3b-2: what Autopilot tidied here, newest first, each with Undo.
            ForEach(row.tidies, id: \.id) { t in
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("\(t.time) · \(t.summary)").font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        if let detail = t.detail {
                            Text(detail).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        if let state = t.state {
                            Text(state).font(MekaType.caption).foregroundStyle(palette.accent)
                                .contentTransition(.opacity)
                                .transition(.opacity)
                        }
                    }
                    Spacer()
                    if t.canUndo {
                        let id = t.id
                        Button(MailAutopilotPanelRules.shared.UNDO) { onUndo(id) }
                            .buttonStyle(MekaPressStyle())
                            .font(MekaType.itemMeta)
                            .foregroundStyle(palette.accent)
                    }
                }
                .padding(.top, MekaSpace.xxs)
                .transition(.opacity)
            }
            // Slice 5a: who sends the newsletters and promos, each with Unsubscribe (done at Navilife's next check).
            if let line = row.sendersLine {
                Text(MailSenderPanelRules.shared.TITLE).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .padding(.top, MekaSpace.xs)
                Text(line).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                ForEach(allSenders ? row.senders : Array(row.senders.prefix(3)), id: \.id) { s in
                    senderLine(s)
                }
                if row.senders.count > 3 {
                    Button(allSenders ? "Show fewer" : "Show \(row.senders.count - 3) more") {
                        MekaHaptics.tick()
                        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { allSenders.toggle() }
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
                }
            }
            HStack(spacing: MekaSpace.xs) {
                ForEach(MailAccountRules.shared.LABELS, id: \.name) { label in
                    let on = label == row.label
                    Button(label.label) { if !on { onLabel(label) } }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(on ? palette.onAccent : palette.textSecondary)
                        .padding(.horizontal, MekaSpace.m)
                        .padding(.vertical, MekaSpace.xxs)
                        .background(Capsule().fill(on ? palette.accent : palette.hairline))
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: on)
                        .accessibilityLabel("Label \(label.label)")
                        .accessibilityAddTraits(on ? .isSelected : [])
                }
                Spacer()
                Button(MailAccountRules.shared.DISCONNECT) {
                    MekaHaptics.tick()
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { asking.toggle() }
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textSecondary)
            }
            .padding(.top, MekaSpace.xs)
            if asking {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(MailAccountRules.shared.disconnectAsk(row: row)).font(MekaType.caption)
                        .foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: MekaSpace.l) {
                        Button(MailAccountRules.shared.DISCONNECT) {
                            asking = false
                            onDisconnect()
                        }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.critical)
                        Button(MailAccountRules.shared.KEEP) {
                            MekaHaptics.tick()
                            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { asking = false }
                        }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.accent)
                    }
                }
                .padding(.top, MekaSpace.xs)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(MekaSpace.m)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
    }
}
