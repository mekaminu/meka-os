import SwiftUI
@preconcurrency import MekaKit

/// "From your mail" on the Mac (Inbox autopilot, slice 6a), in Needs you above "Archived for you": the emails the server
/// sorted as needing Meka, still in the inbox, newest first — who, which account (the business one wears a Business
/// chip), when, the subject and the first words — each with Reply (slice 6b-2a: written on the card, sent from that
/// account only on Send, at the server's next check; Undo on the bar stops it; a reply that didn't send opens again with
/// his words), Done (archived at the server's next check), Task and Waiting
/// for (slice 6b-1: made from the email, which is archived too) and Snooze (back tomorrow 08:00), each with Undo on the bar. It staggers in after the cards above; clicking the header folds the
/// emails in and out on the expand spring; the headline cross-fades. Reduced motion: cross-fades. Slice 6c: emails linked
/// to one (same thread, order number, or person and topic) fold under it and go with it.
/// Email triage completeness (2026-10-15): with two or more emails, Draft replies to N drafts each one whose editor is
/// empty and opens it to read and change; once two or more open editors hold a short reply, Send all N fades in and
/// sends each from the account its email came to (the same path and hold as Send), one Undo for all.
struct MailNeedsView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 2
    @State private var open = true
    // Draft replies and Send all: each open editor's words by email, the drafts Draft replies put in, and its line.
    @State private var texts: [String: String] = [:]
    @State private var incoming: [String: MailIncomingDraft] = [:]
    @State private var drafted: [String: String] = [:]
    @State private var seq = 0
    @State private var draftingAll = false
    @State private var allLine: String?

    var body: some View {
        Group {
            // While the ten-minute inbox runs (slice 7) it shows these one at a time instead.
            if let card = model.mailNeeds, model.mailMomentSession == nil {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Button { MekaHaptics.tick(); open.toggle() } label: {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text(card.title.uppercased())
                                .font(MekaType.captionStrong).foregroundStyle(palette.textSecondary)
                            Text(card.headline).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                .contentTransition(.opacity)
                                .animation(MekaMotion.expand(reduced: reduceMotion), value: card.headline)
                            // Drafted replies: "3 emails need a reply · 2 drafted" (cross-fades as drafts go).
                            if let replies = card.replyLine {
                                Text(replies).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                                    .contentTransition(.opacity)
                                    .animation(MekaMotion.expand(reduced: reduceMotion), value: replies)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(card.title). \(card.headline)")
                    if open {
                        VStack(alignment: .leading, spacing: MekaSpace.m) {
                            if let draftLabel = MailSendAllRules.shared.draftLabel(rows: Int32(card.rows.count)) {
                                sendAllBar(card: card, draftLabel: draftLabel)
                            }
                            ForEach(card.rows, id: \.id) { row in
                                MailNeedsRowView(row: row, palette: palette,
                                                 done: { model.doneMailItem(row.id) },
                                                 task: { model.taskFromMailItem(row.id) },
                                                 waiting: { model.waitingFromMailItem(row.id) },
                                                 snooze: { model.snoozeMailItem(row.id) },
                                                 send: { text, sent in model.replyToMailItem(row.id, text, sent: sent) },
                                                 draft: { await model.draftMailReply(row.id) },
                                                 alwaysTidy: { model.alwaysTidyMailItem(row.id) },
                                                 incoming: incoming[row.id],
                                                 onEditor: { t in texts[row.id] = t },
                                                 suggestTimes: { await model.suggestMeetingTimes(row.id) },
                                                 book: { model.bookMeetingPick(row.id) },
                                                 notNeeded: { model.dismissMailDraft(row.id) })
                            }
                            if let more = card.moreLine {
                                Text(more).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                            }
                            if let snoozed = card.snoozedLine {
                                Text(snoozed).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                            }
                        }
                        .padding(.top, MekaSpace.xs)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                }
                .padding(MekaSpace.m)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                .mekaHoverLift()
                .staggeredAppear(firstIndex)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.mailNeeds == nil)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.mailNeeds?.rows.map(\.id) ?? [])
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
    }
}

extension MailNeedsView {
    /// Draft replies to N, and Send all N once two or more open editors hold a short reply (it fades in), with the line
    /// saying what Draft replies did or how Send all sends.
    @ViewBuilder
    fileprivate func sendAllBar(card: MailNeedsCard, draftLabel: String) -> some View {
        let all = MailSendAllRules.shared.sendAll(rows: card.rows, texts: texts)
        let shown = all != nil ? MailSendAllRules.shared.LINE : allLine
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(spacing: MekaSpace.xs) {
                if let all {
                    MailNeedsPill(label: all.label, palette: palette, spoken: all.spoken, action: { sendAll(all) })
                        .transition(.opacity)
                }
                MailNeedsPill(label: draftingAll ? MailSendAllRules.shared.DRAFTING : draftLabel, palette: palette, quiet: true,
                              spoken: MailSendAllRules.shared.draftSpoken(rows: Int32(card.rows.count)),
                              action: { draftAll(card: card) })
                    .disabled(draftingAll)
            }
            if let shown {
                Text(shown).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .transition(.opacity)
            }
        }
        .animation(MekaMotion.appear(reduced: reduceMotion), value: all?.label)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: shown)
    }

    /// Draft it for each email whose editor is empty or still holds Navilife's draft (never over his words), one after
    /// another; each draft opens its email's editor.
    fileprivate func draftAll(card: MailNeedsCard) {
        MekaHaptics.light()
        draftingAll = true
        let targets = card.rows.map(\.id).filter { MailDraftRules.shared.canDraft(editor: texts[$0] ?? "", lastDraft: drafted[$0]) }
        Task { @MainActor in
            var n = 0
            var problem: String?
            for id in targets {
                let d = await model.draftMailReply(id)
                if let t = d.text {
                    n += 1
                    drafted[id] = t
                    seq += 1
                    incoming[id] = MailIncomingDraft(seq: seq, text: t)
                } else {
                    problem = d.line
                }
            }
            draftingAll = false
            allLine = MailSendAllRules.shared.draftedLine(drafted: Int32(n), asked: Int32(targets.count), lastProblem: problem)
        }
    }

    fileprivate func sendAll(_ all: MailSendAll) {
        let ids = all.ids
        let words = all.texts
        model.replyToMailItems(ids, words) {
            for id in ids { texts[id] = nil; incoming[id] = nil; drafted[id] = nil }
            allLine = nil
        }
    }
}

/// A draft Draft replies puts into one email's editor; `seq` makes the same words twice a new arrival.
struct MailIncomingDraft: Equatable {
    let seq: Int
    let text: String
}

struct MailNeedsRowView: View {
    let row: MailNeedsRow
    let palette: MekaPalette
    let done: () -> Void
    let task: () -> Void
    let waiting: () -> Void
    let snooze: () -> Void
    let send: (String, @escaping @MainActor () -> Void) -> Void
    /// Slice 6b-2b: Navilife's draft for the editor and the line to show; nothing is sent.
    let draft: @MainActor () async -> (text: String?, line: String)
    /// Slice 10b: Done, and Autopilot tidies this sender's later emails as they arrive (nil: not offered here).
    var alwaysTidy: (() -> Void)? = nil
    /// Draft replies on the card: a draft to open the editor with (nil: none), and where the open editor's words go
    /// (nil when closed) for Send all.
    var incoming: MailIncomingDraft? = nil
    var onEditor: ((String?) -> Void)? = nil
    /// Scheduling assistant, slice 1: his free times as a reply for an email that asks to meet (nil: not offered here).
    var suggestTimes: (@MainActor () async -> (text: String?, line: String))? = nil
    /// Scheduling assistant, slice 2: Book the time their reply picked from his offer (nil: not offered here).
    var book: (() -> Void)? = nil
    /// Drafted replies: Not needed on Navilife's draft (nil: not offered here).
    var notNeeded: (() -> Void)? = nil
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var replying = false
    @State private var fullDraft = false
    @State private var text = ""
    @State private var lastDraft: String?
    @State private var draftLine: String?
    @State private var drafting = false
    @State private var suggesting = false
    @State private var showLinked = false

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                // Account-security mail: who it's really from and what to do, above the sender.
                if let security = row.security {
                    Text(security).font(MekaType.itemMeta).foregroundStyle(palette.critical)
                }
                HStack(spacing: MekaSpace.xs) {
                    Text(row.from).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                    if row.business {
                        Text(MailNeedsRules.shared.BUSINESS_CHIP)
                            .font(MekaType.itemMeta).foregroundStyle(palette.accent)
                            .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                            .background(palette.surface, in: Capsule())
                    }
                }
                Text(row.subject).font(MekaType.body).foregroundStyle(palette.textSecondary).lineLimit(2)
                if let preview = row.preview {
                    Text(preview).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).lineLimit(2)
                }
                Text(row.line).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                // Slice 3: an answer turning his times down says so in its own line instead.
                if row.asksToMeet, row.pickedLine == nil {
                    Text(MeetingRequestRules.shared.ASKS).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
                // Scheduling assistant, slice 2: the time they picked, then "Booked · …" (cross-fades in place).
                if let picked = row.pickedLine {
                    Text(picked).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                        .id(picked)
                        .transition(.opacity)
                }
                if let notice = row.notice {
                    Text(notice).font(MekaType.itemMeta).foregroundStyle(palette.critical)
                }
                // Drafted replies: what they want, in their words.
                if let wants = row.wants {
                    let needs = MailAutoDraftRules.shared.NEEDS_REPLY
                    Text(wants == needs ? wants : "\(needs) · \(wants)")
                        .font(MekaType.itemMeta).foregroundStyle(palette.accent).lineLimit(2)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(row.spoken)
            // Drafted replies: Navilife's draft, ready to Send (his click), Edit or put away; it unfolds on the expand
            // spring and folds away once he edits, sends or says Not needed (reduced motion: cross-fade).
            if let auto = row.autoDraft, !replying {
                autoDraftBox(auto)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            // Slice 6c: the emails linked to this one fold under it; the line unfolds them. Done, Snooze, Task and
            // Waiting for take them all, and one Undo brings them back.
            if let linkedLine = row.linkedLine {
                Button { MekaHaptics.tick(); showLinked.toggle() } label: {
                    Text(linkedLine).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
                .buttonStyle(.plain)
                .padding(.vertical, MekaSpace.xxs)
                .accessibilityLabel(showLinked ? "\(linkedLine). Hide them" : "\(linkedLine). Show them")
                if showLinked {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(row.linked, id: \.id) { l in
                            VStack(alignment: .leading, spacing: 0) {
                                Text(l.from).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary).lineLimit(1)
                                Text(l.subject).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(1)
                                Text(l.line).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                            }
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel("\(l.from), \(l.subject). \(l.line)")
                        }
                    }
                    .padding(MekaSpace.xs)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }
            }
            if replying {
                MailReplyEditor(row: row, palette: palette, text: $text,
                                lastDraft: lastDraft, draftLine: draftLine, drafting: drafting,
                                send: { send(text) { replying = false; text = ""; lastDraft = nil; draftLine = nil } },
                                askDraft: { askDraft() },
                                cancel: { replying = false })
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            HStack(spacing: MekaSpace.xs) {
                // Scheduling assistant, slice 2: Book it adds their pick to his calendar (five seconds' Undo on the bar).
                if row.canBook, let book {
                    MailNeedsPill(label: MeetingPickRules.shared.BOOK, palette: palette,
                                  spoken: "Book it: \(row.pickedLine ?? ""), in your calendar",
                                  action: { book() })
                }
                // Scheduling assistant, slice 1: the editor opens (expand spring) with his free times; never over his words.
                if row.asksToMeet, suggestTimes != nil, MailDraftRules.shared.canDraft(editor: text, lastDraft: lastDraft) {
                    MailNeedsPill(label: suggesting ? MeetingRequestRules.shared.FINDING : MeetingRequestRules.shared.SUGGEST,
                                  palette: palette,
                                  spoken: "Put your free times in a reply to \(row.from); nothing is sent until you tap Send",
                                  action: { askTimes() })
                    .disabled(suggesting)
                }
                if !replying {
                    MailNeedsPill(label: MailNeedsRules.shared.REPLY, palette: palette,
                                  spoken: "Reply to \(row.from)", action: { replying = true })
                }
                MailNeedsPill(label: MailNeedsRules.shared.DONE, palette: palette,
                              spoken: "Done: archive \(row.subject)", action: done)
                MailNeedsPill(label: MailNeedsRules.shared.TASK, palette: palette,
                              spoken: "Make a task from \(row.subject) and archive the email", action: task)
                MailNeedsPill(label: MailNeedsRules.shared.WAITING, palette: palette, quiet: true,
                              spoken: "Waiting for \(row.from) on \(row.subject), chase in 3 days", action: waiting)
                MailNeedsPill(label: MailNeedsRules.shared.SNOOZE, palette: palette, quiet: true,
                              spoken: "Snooze \(row.subject) till tomorrow 8 o'clock", action: snooze)
                if let alwaysTidy {
                    MailNeedsPill(label: MailSenderRuleRules.shared.ALWAYS_TIDY, palette: palette, quiet: true,
                                  spoken: "Archive this and always tidy \(row.from)'s emails as they arrive", action: alwaysTidy)
                }
            }
            .padding(.top, MekaSpace.xxs)
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: replying)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: showLinked)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: row.autoDraft)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: fullDraft)
        .onAppear {
            // A reply that didn't send opens again with his words.
            if let draft = row.replyDraft { text = draft; replying = true }
        }
        .onChange(of: row.replyDraft) { _, draft in
            if let draft { text = draft; replying = true }
        }
        .onChange(of: incoming) { _, d in
            if let d {
                text = d.text; lastDraft = d.text; draftLine = MailDraftRules.shared.DRAFTED_LINE; replying = true
            }
        }
        .onChange(of: text) { _, t in onEditor?(replying ? t : nil) }
        .onChange(of: replying) { _, open in onEditor?(open ? text : nil) }
    }
}

extension MailNeedsRowView {
    /// Drafted replies: the draft (four lines, click for all of it), its line, and Send · Edit · Not needed · Remind me later.
    @ViewBuilder
    fileprivate func autoDraftBox(_ auto: String) -> some View {
        let R = MailAutoDraftRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Button { MekaHaptics.tick(); fullDraft.toggle() } label: {
                Text(auto).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    .lineLimit(fullDraft ? nil : 4)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Navilife's draft reply to \(row.from): \(auto)")
            Text(R.DRAFT_LABEL).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
            HStack(spacing: MekaSpace.xs) {
                MailNeedsPill(label: R.SEND, palette: palette, spoken: "Send Navilife's draft to \(row.from) now",
                              action: { send(auto) {} })
                MailNeedsPill(label: R.EDIT, palette: palette, quiet: true, spoken: "Edit the draft before sending",
                              action: {
                                  MekaHaptics.light()
                                  text = auto; lastDraft = auto; draftLine = MailDraftRules.shared.DRAFTED_LINE; replying = true
                              })
                if let notNeeded {
                    MailNeedsPill(label: R.NOT_NEEDED, palette: palette, quiet: true,
                                  spoken: "No reply needed to \(row.from); put the draft away", action: notNeeded)
                }
                MailNeedsPill(label: R.LATER, palette: palette, quiet: true,
                              spoken: "Remind me about \(row.subject) tomorrow at 8", action: snooze)
            }
        }
        .padding(MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
        .padding(.top, MekaSpace.xs)
    }

    /// Suggest times: his free times from his calendars into the editor (never over his own words); nothing is sent.
    fileprivate func askTimes() {
        guard let suggestTimes else { return }
        MekaHaptics.light()
        suggesting = true
        Task { @MainActor in
            let result = await suggestTimes()
            suggesting = false
            if let t = result.text, MailDraftRules.shared.canDraft(editor: text, lastDraft: lastDraft) { text = t; lastDraft = t }
            draftLine = result.line
            replying = true
        }
    }

    /// Draft it: ask once, put the draft in the editor (never over his own words) and say what happened.
    fileprivate func askDraft() {
        MekaHaptics.light()
        drafting = true
        Task { @MainActor in
            let result = await draft()
            drafting = false
            if let t = result.text { text = t; lastDraft = t }
            draftLine = result.line
        }
    }
}

/// The reply editor under an email (slice 6b-2a): his words, who it goes to and from which account, Send and Cancel;
/// slice 6b-2b: Draft it (only while the editor is empty or still holds the last draft unchanged) and the draft's line,
/// which cross-fades in.
private struct MailReplyEditor: View {
    let row: MailNeedsRow
    let palette: MekaPalette
    @Binding var text: String
    let lastDraft: String?
    let draftLine: String?
    let drafting: Bool
    let send: () -> Void
    let askDraft: () -> Void
    let cancel: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        let problem = MailNeedsRules.shared.replyProblem(text: text)
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            TextField(MailNeedsRules.shared.REPLY_HINT, text: $text, axis: .vertical)
                .textFieldStyle(.plain)
                .font(MekaType.body)
                .lineLimit(4...12)
                .padding(MekaSpace.xs)
                .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                .accessibilityLabel("Reply to \(row.from)")
            Text(row.replyLine).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
            if let draftLine {
                Text(draftLine).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .transition(.opacity)
            }
            if problem == MailNeedsRules.shared.REPLY_TOO_LONG {
                Text(MailNeedsRules.shared.REPLY_TOO_LONG).font(MekaType.itemMeta).foregroundStyle(palette.critical)
            }
            HStack(spacing: MekaSpace.xs) {
                MailNeedsPill(label: MailNeedsRules.shared.SEND, palette: palette,
                              spoken: "Send this reply to \(row.from) now", action: send)
                    .disabled(problem != nil)
                    .keyboardShortcut(.return, modifiers: .command)
                if MailDraftRules.shared.canDraft(editor: text, lastDraft: lastDraft) {
                    MailNeedsPill(label: drafting ? MailDraftRules.shared.DRAFTING : MailDraftRules.shared.label(editor: text),
                                  palette: palette, quiet: true,
                                  spoken: "Let Navilife draft a reply to \(row.from) for you to check; nothing is sent",
                                  action: askDraft)
                        .disabled(drafting)
                }
                MailNeedsPill(label: MailNeedsRules.shared.CANCEL, palette: palette, quiet: true,
                              spoken: "Close the reply without sending", action: cancel)
            }
        }
        .padding(.top, MekaSpace.xs)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: draftLine)
    }
}

struct MailNeedsPill: View {
    let label: String
    let palette: MekaPalette
    var quiet: Bool = false
    let spoken: String
    let action: () -> Void

    var body: some View {
        Button(label, action: action)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta)
            .foregroundStyle(quiet ? palette.textSecondary : palette.accent)
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(palette.surface, in: Capsule())
            .accessibilityLabel(spoken)
    }
}

/// "Time to chase" on the Mac (Inbox autopilot, slice 6b-3), in Needs you under "From your mail": each Waiting for item
/// made from an email whose chase day has come — who, what, which account and since when — with Chase (an editor that
/// opens holding Navilife's short chase, his to change; it goes from that account, in the email's thread, only on Send,
/// at the server's next check; Undo on the bar stops it), Got it (off Waiting for) and Not yet (chase again in 3 days,
/// nothing sent). A chase that didn't send opens again with his words and why. Clicking the header folds the rows on the
/// expand spring; reduced motion: cross-fades.
struct MailChaseView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 2
    @State private var open = true

    var body: some View {
        Group {
            if let card = model.mailChases {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Button { MekaHaptics.tick(); open.toggle() } label: {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text(card.title.uppercased())
                                .font(MekaType.captionStrong).foregroundStyle(palette.textSecondary)
                            Text(card.headline).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                .contentTransition(.opacity)
                                .animation(MekaMotion.expand(reduced: reduceMotion), value: card.headline)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(card.title). \(card.headline)")
                    if open {
                        VStack(alignment: .leading, spacing: MekaSpace.m) {
                            ForEach(card.rows, id: \.id) { row in
                                MailChaseRowView(row: row, palette: palette,
                                                 got: { model.gotMailChase(row.id) },
                                                 notYet: { model.notYetMailChase(row.id) },
                                                 draft: { await model.draftMailChase(row.id) },
                                                 send: { text, sent in model.chaseMailItem(row.id, text, sent: sent) })
                            }
                            if let more = card.moreLine {
                                Text(more).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                            }
                        }
                        .padding(.top, MekaSpace.xs)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                }
                .padding(MekaSpace.m)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                .mekaHoverLift()
                .staggeredAppear(firstIndex)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.mailChases == nil)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.mailChases?.rows.map(\.id) ?? [])
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
    }
}

private struct MailChaseRowView: View {
    let row: MailChaseRow
    let palette: MekaPalette
    let got: () -> Void
    let notYet: () -> Void
    /// Follow-up radar, slice 5: the chase written by the AI (only the id crosses; two Strings come back).
    let draft: @MainActor () async -> (text: String?, line: String)
    let send: (String, @escaping @MainActor () -> Void) -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var chasing = false
    @State private var text = ""
    @State private var lastDraft: String?
    @State private var draftLine: String?
    @State private var drafting = false

    private var rules: MailChaseDraftRules { MailChaseDraftRules.shared }

    private func writeChase() {
        guard !drafting, rules.canDraft(editor: text, plain: row.draft, lastDraft: lastDraft) else { return }
        drafting = true
        draftLine = rules.DRAFTING
        Task { @MainActor in
            let d = await draft()
            drafting = false
            // Only if the editor still holds what was there: a word he typed meanwhile wins.
            if let t = d.text, rules.canDraft(editor: text, plain: row.draft, lastDraft: lastDraft) { text = t; lastDraft = t }
            draftLine = d.line
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                HStack(spacing: MekaSpace.xs) {
                    Text(row.from).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                    if row.business {
                        Text(MailNeedsRules.shared.BUSINESS_CHIP)
                            .font(MekaType.itemMeta).foregroundStyle(palette.accent)
                            .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                            .background(palette.surface, in: Capsule())
                    }
                }
                Text(row.title).font(MekaType.body).foregroundStyle(palette.textSecondary).lineLimit(2)
                Text(row.line).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                if let notice = row.notice {
                    Text(notice).font(MekaType.itemMeta).foregroundStyle(palette.critical)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(row.spoken)
            if chasing {
                let problem = MailNeedsRules.shared.replyProblem(text: text)
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    TextField(MailNeedsRules.shared.REPLY_HINT, text: $text, axis: .vertical)
                        .textFieldStyle(.plain)
                        .font(MekaType.body)
                        .lineLimit(4...12)
                        .padding(MekaSpace.xs)
                        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                        .accessibilityLabel("Chase \(row.from)")
                    Text(row.replyLine).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                    if let line = draftLine {
                        Text(line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                            .transition(.opacity)
                    }
                    if problem == MailNeedsRules.shared.REPLY_TOO_LONG {
                        Text(MailNeedsRules.shared.REPLY_TOO_LONG).font(MekaType.itemMeta).foregroundStyle(palette.critical)
                    }
                    HStack(spacing: MekaSpace.xs) {
                        MailNeedsPill(label: MailNeedsRules.shared.SEND, palette: palette,
                                      spoken: "Send this chase to \(row.from) now",
                                      action: { send(text) { chasing = false; lastDraft = nil; draftLine = nil } })
                            .disabled(problem != nil || drafting)
                            .keyboardShortcut(.return, modifiers: .command)
                        if rules.canDraft(editor: text, plain: row.draft, lastDraft: lastDraft) {
                            MailNeedsPill(label: drafting ? rules.DRAFTING : rules.label(editor: text, lastDraft: lastDraft),
                                          palette: palette, quiet: true,
                                          spoken: "Let Navilife write the chase to \(row.from) from your email; nothing is sent",
                                          action: { MekaHaptics.tick(); writeChase() })
                                .disabled(drafting)
                        }
                        MailNeedsPill(label: MailNeedsRules.shared.CANCEL, palette: palette, quiet: true,
                                      spoken: "Close the chase without sending", action: { chasing = false })
                    }
                }
                .padding(.top, MekaSpace.xs)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            HStack(spacing: MekaSpace.xs) {
                if !chasing {
                    MailNeedsPill(label: MailChaseRules.shared.CHASE, palette: palette,
                                  spoken: "Chase \(row.from) by email; Navilife drafts it, nothing is sent until you tap Send",
                                  action: {
                                      if lastDraft == nil { text = row.draft }
                                      chasing = true
                                      // A fresh chase asks the AI once for one written from the email; the plain one shows meanwhile.
                                      if rules.autoDraft(row: row) && lastDraft == nil { writeChase() }
                                  })
                }
                MailNeedsPill(label: MailChaseRules.shared.GOT_IT, palette: palette,
                              spoken: "Got it: \(row.title) arrived, off Waiting for", action: got)
                MailNeedsPill(label: MailChaseRules.shared.NOT_YET, palette: palette, quiet: true,
                              spoken: "Not yet: chase \(row.from) again in 3 days", action: notYet)
            }
            .padding(.top, MekaSpace.xxs)
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: chasing)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: draftLine)
        .onAppear {
            // A chase that didn't send opens again with his words.
            if row.notice != nil { text = row.draft; chasing = true }
        }
        .onChange(of: row.notice) { _, notice in
            if notice != nil { text = row.draft; chasing = true }
        }
    }
}

/// "Renewals from your mail" on the Mac (Promises, receipts and renewals from email, slice 1), in Needs you under "Time
/// to chase": each renewal the server read from a renewal email — who, when it renews, the price and how often, the
/// kind, the account and the email's subject — with Add (onto Lists → Renewals; Undo on the bar takes it off) and Not
/// needed (gone on both apps; Undo brings it back). Nothing reaches the radar without Add. Clicking the header folds the
/// rows on the expand spring; reduced motion: cross-fades. Slice 2: promises made to him share the card ("Waiting for:
/// Signed contract · by Fri 16 Oct"); their Add puts a Waiting for item on, chased the day after.
struct MailFindView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 2
    @State private var open = true

    var body: some View {
        Group {
            if let card = model.mailFinds {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Button { MekaHaptics.tick(); open.toggle() } label: {
                        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                            Text(card.title.uppercased())
                                .font(MekaType.captionStrong).foregroundStyle(palette.textSecondary)
                            Text(card.headline).font(MekaType.body).foregroundStyle(palette.textPrimary)
                                .contentTransition(.opacity)
                                .animation(MekaMotion.expand(reduced: reduceMotion), value: card.headline)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(card.title). \(card.headline)")
                    if open {
                        VStack(alignment: .leading, spacing: MekaSpace.m) {
                            ForEach(card.rows, id: \.id) { row in
                                MailFindRowView(row: row, palette: palette,
                                                add: { model.addMailFind(row.id) },
                                                notNeeded: { model.notNeededMailFind(row.id) },
                                                wait: { days in model.setFollowUpWait(row.id, days: days) })
                            }
                            if let more = card.moreLine {
                                Text(more).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                            }
                        }
                        .padding(.top, MekaSpace.xs)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                }
                .padding(MekaSpace.m)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
                .mekaHoverLift()
                .staggeredAppear(firstIndex)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.mailFinds == nil)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.mailFinds?.rows.map(\.id) ?? [])
        .animation(MekaMotion.expand(reduced: reduceMotion), value: open)
    }
}

private struct MailFindRowView: View {
    let row: MailFindRow
    let palette: MekaPalette
    let add: () -> Void
    let notNeeded: () -> Void
    let wait: (Int32) -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion
    /// Follow-up radar, slice 3: "Wait 3 days ▾" unfolds the chips for how long he waits for this person's replies.
    @State private var waitOpen = false

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                HStack(spacing: MekaSpace.xs) {
                    Text(row.title).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                    if row.business {
                        Text(MailNeedsRules.shared.BUSINESS_CHIP)
                            .font(MekaType.itemMeta).foregroundStyle(palette.accent)
                            .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                            .background(palette.surface, in: Capsule())
                    }
                }
                Text(row.line).font(MekaType.body).foregroundStyle(palette.textSecondary)
                Text(row.from).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).lineLimit(2)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(row.spoken)
            HStack(spacing: MekaSpace.xs) {
                MailNeedsPill(label: row.addLabel, palette: palette,
                              spoken: row.addSpoken, action: add)
                MailNeedsPill(label: row.notNeededLabel, palette: palette, quiet: true,
                              spoken: row.notNeededSpoken, action: notNeeded)
                if let pill = row.waitPill {
                    MailNeedsPill(label: pill, palette: palette, quiet: true,
                                  spoken: row.waitSpoken ?? pill, action: { waitOpen.toggle() })
                }
            }
            .padding(.top, MekaSpace.xxs)
            if waitOpen, !row.waits.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    if let heading = row.waitHeading {
                        Text(heading).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                    }
                    HStack(spacing: MekaSpace.xs) {
                        ForEach(row.waits, id: \.days) { c in
                            MailNeedsPill(label: c.label, palette: palette, quiet: !c.chosen, spoken: c.spoken,
                                          action: {
                                              waitOpen = false
                                              if !c.chosen { wait(c.days) }
                                          })
                        }
                    }
                }
                .padding(.top, MekaSpace.xs)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: waitOpen)
    }
}
