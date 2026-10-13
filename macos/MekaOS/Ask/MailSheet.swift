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
                    })
                    .staggeredAppear(i + 2)
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
        .task { await model.loadMail() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            Task { await model.loadMail() }
        }
    }
}

private struct MailAccountRowView: View {
    let row: MailAccountRow
    let palette: MekaPalette
    let onReconnect: () -> Void
    let onLabel: (MailLabel) -> Void
    let onDisconnect: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var asking = false

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
