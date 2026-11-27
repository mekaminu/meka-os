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
            Text(LoneWordGlue.shared.kept(line: MailAccountRules.shared.HEADER)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .staggeredAppear(1)
            if let view = model.mail {
                // Found and fixing week 5, A13: with nothing connected, the line and its two buttons are one card.
                if let empty = view.emptyLine {
                    EmptyMailPanelView(line: empty, connects: view.connects, palette: palette) { provider, note in
                        Task { await model.connectMail(provider, note: note) }
                    }
                    .staggeredAppear(2)
                }
                // Drafted replies, slice 2: "3 emails need a reply · 2 drafted", or "No emails waiting on you ✓"
                // (walk-through A16: not over the life audit's own "Nothing needs you from 60 days of mail ✓").
                if let line = MailAutoDraftRules.shared.shownLine(line: view.repliesLine,
                                                                  auditSummary: view.rows.isEmpty ? nil : model.lifeAudit?.summary) {
                    Text(LoneWordGlue.shared.kept(line: line)).font(MekaType.itemMeta)
                        .foregroundStyle(line == MailAutoDraftRules.shared.NONE_WAITING ? palette.textSecondary : palette.accent)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
                        .staggeredAppear(2)
                }
                // Life audit, slice 2: what 60 days of mail turned up, as one review (Review / Hide).
                if !view.rows.isEmpty, let audit = model.lifeAudit {
                    LifeAuditPanelView(review: audit, palette: palette)
                        .staggeredAppear(2)
                }
                if let panel = view.autopilot {
                    // Quality gate slice 32: each switch shows its short line; the full one is behind How it works.
                    SwitchPanelView(on: panel.on && !panel.paused, title: panel.title, short: panel.shortLine, full: panel.line,
                                    action: panel.action, palette: palette) {
                        let on = !panel.on
                        Task { await model.setMailAutopilot(on) }
                    }
                    .staggeredAppear(2)
                    // Inbox fixes, slice 5: "Mark tidied mail as read" (synced with the Fold).
                    SwitchPanelView(on: panel.markRead, title: panel.markReadTitle, short: panel.markReadShort, full: panel.markReadLine,
                                    action: panel.markReadAction, palette: palette) {
                        let on = !panel.markRead
                        Task { await model.setMailMarkRead(on) }
                    }
                    .staggeredAppear(2)
                    // Read the full email, slice 3: "Mark an email read when I open it" (synced with the Fold).
                    SwitchPanelView(on: panel.readerRead, title: panel.readerReadTitle, short: panel.readerReadShort, full: panel.readerReadLine,
                                    action: panel.readerReadAction, palette: palette) {
                        let on = !panel.readerRead
                        Task { await model.setMailReaderMarksRead(on) }
                    }
                    .staggeredAppear(2)
                    // Slice 7: when the ten-minute inbox comes (synced with the Fold).
                    if let key = model.mailMomentKey {
                        let t = MailMomentTime.companion.of(key: key) ?? MailMomentTime.morning
                        MailChoicePanelView(row: MailChoiceRules.shared.momentRow(t: t), sheet: MailChoiceRules.shared.momentSheet(t: t), palette: palette) { name in
                            guard let picked = MailChoiceRules.shared.moment(name: name)?.key else { return }
                            Task { await model.setMailMomentKey(picked) }
                        }
                        .staggeredAppear(2)
                    }
                    // Slice 3f: which orders put a warranty's end on Renewals (synced with the Fold).
                    if let key = model.mailWarrantyKey {
                        let w = MailWarrantyLevel.companion.of(key: key) ?? MailWarrantyLevel.hundred
                        MailChoicePanelView(row: MailChoiceRules.shared.warrantyRow(w: w), sheet: MailChoiceRules.shared.warrantySheet(w: w), palette: palette) { name in
                            guard let picked = MailChoiceRules.shared.warranty(name: name)?.key else { return }
                            Task { await model.setMailWarrantyKey(picked) }
                        }
                        .staggeredAppear(2)
                    }
                    // Follow-up radar, slice 4: everyone he set his own chase wait for (synced with the Fold).
                    if let waits = model.followUpWaits {
                        WaitsPanelView(panel: waits, palette: palette) { id, days in
                            Task { await model.changeFollowUpWait(id, days: days) }
                        }
                        .staggeredAppear(2)
                    }
                }
                // Meeting prep, slice 2: how early the prep comes, or Off (synced with the Fold; works without mail).
                if let key = model.meetingPrepLeadKey {
                    let l = MeetingPrepLead.companion.of(key: key) ?? MeetingPrepLead.fifteen
                    MailChoicePanelView(row: MailChoiceRules.shared.prepRow(l: l), sheet: MailChoiceRules.shared.prepSheet(l: l), palette: palette) { name in
                        guard let picked = MailChoiceRules.shared.prep(name: name)?.key else { return }
                        Task { await model.setMeetingPrepLeadKey(picked) }
                    }
                    .staggeredAppear(2)
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
                    }, onRule: { id, rule in
                        Task { await model.setMailSenderRule(id, rule: rule) }
                    }, onApprove: { id, yes in
                        Task { await model.approveMailBacklog(id, approve: yes) }
                    }, onDeleteData: {
                        let provider = row.provider, email = row.email
                        Task { await model.disconnectMail(provider: provider, email: email, deleteData: true) }
                    })
                    .staggeredAppear(i + 3)
                }
                // Inbox fixes, slice 5: every sender with his own rule, once, under the accounts.
                if let line = view.rulesLine {
                    RulesPanelView(line: line, rules: view.rules, palette: palette) { id, sets in
                        Task { await model.setMailSenderRule(id, rule: sets) }
                    }
                    .staggeredAppear(view.rows.count + 3)
                    .transition(.opacity)
                }
                if let left = view.toConnect {
                    Text(LoneWordGlue.shared.kept(line: left)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                if view.emptyLine == nil { HStack(spacing: MekaSpace.xs) {
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
                .padding(.top, MekaSpace.xs) }
            } else if model.mailOffline {
                Text(MailAccountRules.shared.OFFLINE).font(MekaType.itemMeta).foregroundStyle(palette.critical)
            } else {
                SkeletonRows(count: 2, rowHeight: 56, palette: palette)
            }
            if let note = model.mailNote {
                Text(LoneWordGlue.shared.kept(line: note)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
            }
            if model.followUpWaitUndo {
                Button("Undo") { Task { await model.undoFollowUpWaitChange() } }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                    .accessibilityLabel("Undo the last chase wait change")
                    .transition(.opacity)
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
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.mail?.rows.map { $0.backlog?.line ?? "" } ?? [])
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.mail?.rows.map { $0.tidies.map { $0.state ?? "" } } ?? [])
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.mail?.autopilot?.line)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.mail?.rows.map { $0.senders.map { $0.state ?? "" } } ?? [])
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.mail?.rows.map { $0.senders.map { $0.ruleLine ?? "" } } ?? [])
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.mail?.rules.map(\.id) ?? [])
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.mail?.autopilot?.markRead)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.followUpWaits?.rows.map(\.id) ?? [])
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.followUpWaitUndo)
        .task { await model.loadMail(); await model.loadLifeAudit(); await model.loadMailMomentKey(); await model.loadMailWarrantyKey(); await model.loadMeetingPrepLeadKey(); await model.loadFollowUpWaits() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            Task { await model.loadMail(); await model.loadLifeAudit() }
        }
    }
}

/// Life audit, slice 2: the closed card says what the mail turned up in one line with Review; open, what was read, then
/// one line per finding (each saying where it lives) staggering in on the expand spring, clear lines quieter, and the
/// foot. Read-only: nothing here acts. Reduce Motion: cross-fades.
/// Nothing connected: the empty line and Connect Gmail · Connect Outlook in one raised card.
private struct EmptyMailPanelView: View {
    let line: String
    let connects: [MailConnect]
    let palette: MekaPalette
    let onConnect: (String, String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(LoneWordGlue.shared.kept(line: line)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: MekaSpace.xs) {
                ForEach(connects, id: \.provider) { c in
                    Button(c.label) { onConnect(c.provider, c.note) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.itemMeta)
                        .foregroundStyle(palette.accent)
                }
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
    }
}

private struct LifeAuditPanelView: View {
    let review: LifeAuditReview
    let palette: MekaPalette
    @State private var expanded = false
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .center, spacing: MekaSpace.xs) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(review.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Text(review.summary).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                }
                .accessibilityElement(children: .combine)
                Spacer(minLength: MekaSpace.xs)
                Button(expanded ? LifeAuditReviewRules.shared.CLOSE : LifeAuditReviewRules.shared.OPEN) {
                    MekaHaptics.tick()
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { expanded.toggle() }
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
                .padding(.horizontal, MekaSpace.xs)
                .padding(.vertical, MekaSpace.xxs)
                .background(Capsule().fill(palette.hairline))
                .accessibilityLabel(expanded ? "Hide the life audit" : "Review the life audit")
            }
            if expanded {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    Text("Read: " + review.read).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                        .staggeredAppear(0)
                    ForEach(Array(review.lines.enumerated()), id: \.element.key) { i, l in
                        VStack(alignment: .leading, spacing: 0) {
                            Text(l.title).font(MekaType.body).foregroundStyle(l.clear ? palette.textSecondary : palette.textPrimary)
                            Text(l.line).font(MekaType.caption).foregroundStyle(l.clear ? palette.textTertiary : palette.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                                .contentTransition(.opacity)
                            Text(l.place).font(MekaType.caption).foregroundStyle(palette.accent)
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(l.spoken)
                        .staggeredAppear(i + 1)
                    }
                    Text(review.foot).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                        .staggeredAppear(review.lines.count + 1)
                }
                .transition(.opacity)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: review.summary)
    }
}

/// Walk-through week 5, A17 (no open chip rows): the ten-minute inbox, meeting prep and warranty reminders are each one
/// card row saying what's chosen, a menu of the choices built from the core's sheet (the one in use ticked). A pick
/// sends only the choice's name (a String) back; the caller maps it to the setting's key. Synced with the Fold.
private struct MailChoicePanelView: View {
    let row: NotifyChoiceRow
    let sheet: NotifyChoiceSheet
    let palette: MekaPalette
    let onPick: (String) -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        let lit = sheet.choices.first(where: { $0.lit })?.name ?? ""
        HStack(spacing: MekaSpace.xs) {
            Text(LoneWordGlue.shared.kept(line: row.title)).font(MekaType.body).foregroundStyle(palette.textPrimary)
            Spacer(minLength: MekaSpace.xs)
            Picker(row.title, selection: Binding(get: { lit }, set: { name in if name != lit { onPick(name) } })) {
                ForEach(sheet.choices, id: \.name) { c in Text(c.label).tag(c.name) }
            }
            .labelsHidden().pickerStyle(.menu).fixedSize()
            .help(LoneWordGlue.shared.kept(line: sheet.note))
        }
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surface))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(row.spoken)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: row.value)
    }
}

/// Follow-up radar, slice 4: Chase waits — the usual wait, then one card row per person he set his own for. Walk-through
/// week 5, A17 (no open chip rows): each row is "Sam Taylor" · a menu of 1 day … 2 weeks and Back to usual built from
/// the core's sheet (the one in use ticked). Rows enter and leave on the expand spring; lines cross-fade. Only the id (a
/// String) and the number go back to the model.
private struct WaitsPanelView: View {
    let panel: FollowUpWaitsPanel
    let palette: MekaPalette
    let onPick: (String, Int32) -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: 0) {
                Text(panel.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(LoneWordGlue.shared.kept(line: panel.line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
            }
            .accessibilityElement(children: .combine)
            ForEach(panel.rows, id: \.id) { r in
                let id = r.id, current = r.days
                MailChoicePanelView(row: r.choiceRow, sheet: r.sheet, palette: palette) { name in
                    guard let days = FollowUpWaitsRules.shared.daysNamed(name: name)?.int32Value, days != current else { return }
                    onPick(id, days)
                }
                .transition(.opacity)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: panel.rows.map { $0.choiceRow.value })
    }
}

/// One of Mail's switches from plain values (only Strings and a Bool cross): the dot on the title's line, the title,
/// its short line, How it works (the full line, which VoiceOver reads), and Turn off / Turn on; below 420 pt wide the
/// button sits under the words (quality gate slice 32).
private struct SwitchPanelView: View {
    let on: Bool
    let title: String
    let short: String
    let full: String
    let action: String
    let palette: MekaPalette
    let onSwitch: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var open = false
    @State private var width: CGFloat = 600

    private var rules: MailAutopilotPanelRules { MailAutopilotPanelRules.shared }

    private var words: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: MekaSpace.xs) {
                    Circle().fill(on ? palette.accent : palette.hairline)
                        .frame(width: 10, height: 10)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: on)
                    Text(LoneWordGlue.shared.kept(line: title)).font(MekaType.body).foregroundStyle(palette.textPrimary)
                }
                // Walk-through A11: How it works puts the full line in the short one's place, never both.
                let line = rules.shownLine(short: short, full: full, open: open)
                Text(LoneWordGlue.shared.kept(line: line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(title + ". " + full)
            if rules.hasMore(short: short, full: full) {
                Button(open ? rules.HIDE_HOW : rules.HOW_IT_WORKS) {
                    withAnimation(MekaMotion.appear(reduced: reduceMotion)) { open.toggle() }
                }
                .buttonStyle(.plain)
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
                .padding(.top, MekaSpace.xxs)
            }
        }
    }

    private var button: some View {
        Button(action, action: onSwitch)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta)
            .foregroundStyle(palette.accent)
            .padding(.horizontal, MekaSpace.m)
            .padding(.vertical, MekaSpace.xxs)
            .background(Capsule().fill(palette.hairline))
    }

    var body: some View {
        Group {
            if rules.stacked(widthDp: Float(width)) {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    words
                    button
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(alignment: .top, spacing: MekaSpace.m) {
                    words
                    Spacer(minLength: MekaSpace.xs)
                    button
                }
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .onGeometryChange(for: CGFloat.self) { $0.size.width - 2 * MekaSpace.m } action: { width = $0 }
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
    }
}

/// Inbox fixes, slice 5: "Your rules" once for every account, each with Let Autopilot decide (only Strings cross).
private struct RulesPanelView: View {
    let line: String
    let rules: [MailRuleRow]
    let palette: MekaPalette
    let onRule: (String, String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(line).font(MekaType.body).foregroundStyle(palette.textPrimary)
                .contentTransition(.opacity)
            ForEach(rules, id: \.id) { r in
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                        Text(r.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(r.spoken)
                    Spacer()
                    let id = r.id, sets = r.sets
                    Button(r.action) { onRule(id, sets) }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.accent)
                }
                .padding(.top, MekaSpace.xxs)
                .transition(.opacity)
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
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
    var onRule: (String, String) -> Void = { _, _ in }
    var onApprove: (String, Bool) -> Void = { _, _ in }
    var onDeleteData: () -> Void = {}
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(\.openURL) private var openURL
    @State private var asking = false
    @State private var allSenders = false

    /// One sender: its name, "example.com · 8 emails · last Mon 12 Oct", Unsubscribe (or Open page), and the state once asked.
    /// Walk-through week 5, A17: "Label" with a menu of Personal · Business · Outlook, each saying what it does.
    private var labelRow: some View {
        let label = row.label
        let shown = MailLabelChoiceRules.shared.row(label: label)
        let sheet = MailLabelChoiceRules.shared.sheet(label: label)
        return HStack(spacing: MekaSpace.xs) {
            Text(shown.title).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            Picker(sheet.title, selection: Binding(get: { label.name }, set: { name in
                if let picked = MailLabelChoiceRules.shared.named(label: label, name: name) { onLabel(picked) }
            })) {
                ForEach(sheet.choices, id: \.name) { c in Text("\(c.label) · \(c.line)").tag(c.name) }
            }
            .pickerStyle(.menu)
            .labelsHidden()
            .fixedSize()
            .help(LoneWordGlue.shared.kept(line: sheet.note))
            .accessibilityLabel(shown.spoken)
        }
        .font(MekaType.caption)
    }

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
                // Completeness: his rule for the sender, and the action that sets or takes it off.
                if let rule = s.ruleLine {
                    Text(rule).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .transition(.opacity)
                }
                let id = s.id, sets = s.ruleSets
                Button(s.ruleAction) { onRule(id, sets) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(palette.accent)
                    .contentTransition(.opacity)
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
            Text(LoneWordGlue.shared.kept(line: row.line)).font(MekaType.caption)
                .foregroundStyle(row.ending ? palette.accent : row.needsReconnect ? palette.critical : palette.textTertiary)
                .contentTransition(.opacity)
            // Slice 2a: what the rules made of the inbox, once the server has sorted the account.
            if let sorted = row.sortedLine {
                Text(LoneWordGlue.shared.kept(line: sorted)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .transition(.opacity)
            }
            // Slice 3a: Autopilot's dry run, what it would tidy (nothing moves yet).
            if let tidy = row.tidyLine {
                Text(LoneWordGlue.shared.kept(line: tidy)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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
            // Slice 8a: the backlog clean-up's plan for the older mail already in the inbox (nothing moves yet).
            if let b = row.backlog {
                VStack(alignment: .leading, spacing: 0) {
                    Text(b.title).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    Text(b.line).font(MekaType.caption)
                        .foregroundStyle(b.ready ? palette.textPrimary : palette.textTertiary)
                        .contentTransition(.opacity)
                    if let detail = b.detail {
                        Text(detail).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Text(b.note).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    // Slice 8b: Approve (the plan is final), Cancel (approved, nothing moved yet), Undo (the clean-up's tidy).
                    if let id = b.approveId {
                        Button(MailBacklogRules.shared.APPROVE) { onApprove(id, true) }
                            .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                            .padding(.top, MekaSpace.xxs).transition(.opacity)
                    } else if let id = b.cancelId {
                        Button(MailBacklogRules.shared.CANCEL) { onApprove(id, false) }
                            .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            .padding(.top, MekaSpace.xxs).transition(.opacity)
                    } else if let id = b.undoId {
                        Button(MailBacklogRules.shared.UNDO) { onUndo(id) }
                            .buttonStyle(MekaPressStyle()).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                            .padding(.top, MekaSpace.xxs).transition(.opacity)
                    }
                }
                .padding(.top, MekaSpace.xs)
                .accessibilityElement(children: .combine)
                .transition(.opacity)
            }
            HStack(spacing: MekaSpace.xs) {
                // Walk-through week 5, A17: a "Label" row with a menu of the same choices the Fold's sheet has, each
                // saying what that label does, not an open row of label buttons.
                labelRow
                Spacer()
                Button(MailAccountRules.shared.DISCONNECT) {
                    MekaHaptics.tick()
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { asking.toggle() }
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textSecondary)
                // Never squeezed onto two lines by the chips (the Fold's Mail shots, quality gate slice 31).
                .lineLimit(1)
                .fixedSize()
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
                    // Completeness: Delete mail data per account, the heavier choice, with what it clears and keeps.
                    Text(MailAccountRules.shared.DELETE_DATA_LINE).font(MekaType.caption)
                        .foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                    Button(MailAccountRules.shared.DELETE_DATA) {
                        asking = false
                        onDeleteData()
                    }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.critical)
                }
                .padding(.top, MekaSpace.xs)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(MekaSpace.m)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
    }
}
