import SwiftUI
@preconcurrency import MekaKit

/// "From your mail" on the Mac (Inbox autopilot, slice 6a), in Needs you above "Archived for you": the emails the server
/// sorted as needing Meka, still in the inbox, newest first — who, which account (the business one wears a Business
/// chip), when, the subject and the first words — each with Reply (slice 6b-2a: written on the card, sent from that
/// account only on Send, at the server's next check; Undo on the bar stops it; a reply that didn't send opens again with
/// his words), Done (archived at the server's next check), Task and Waiting
/// for (slice 6b-1: made from the email, which is archived too) and Snooze (back tomorrow 08:00), each with Undo on the bar. It staggers in after the cards above; clicking the header folds the
/// emails in and out on the expand spring; the headline cross-fades. Reduced motion: cross-fades.
struct MailNeedsView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 2
    @State private var open = true

    var body: some View {
        Group {
            if let card = model.mailNeeds {
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
                                MailNeedsRowView(row: row, palette: palette,
                                                 done: { model.doneMailItem(row.id) },
                                                 task: { model.taskFromMailItem(row.id) },
                                                 waiting: { model.waitingFromMailItem(row.id) },
                                                 snooze: { model.snoozeMailItem(row.id) },
                                                 send: { text, sent in model.replyToMailItem(row.id, text, sent: sent) },
                                                 draft: { await model.draftMailReply(row.id) })
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

private struct MailNeedsRowView: View {
    let row: MailNeedsRow
    let palette: MekaPalette
    let done: () -> Void
    let task: () -> Void
    let waiting: () -> Void
    let snooze: () -> Void
    let send: (String, @escaping @MainActor () -> Void) -> Void
    /// Slice 6b-2b: Navilife's draft for the editor and the line to show; nothing is sent.
    let draft: @MainActor () async -> (text: String?, line: String)
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @State private var replying = false
    @State private var text = ""
    @State private var lastDraft: String?
    @State private var draftLine: String?
    @State private var drafting = false

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
                Text(row.subject).font(MekaType.body).foregroundStyle(palette.textSecondary).lineLimit(2)
                if let preview = row.preview {
                    Text(preview).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).lineLimit(2)
                }
                Text(row.line).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary)
                if let notice = row.notice {
                    Text(notice).font(MekaType.itemMeta).foregroundStyle(palette.critical)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(row.spoken)
            if replying {
                MailReplyEditor(row: row, palette: palette, text: $text,
                                lastDraft: lastDraft, draftLine: draftLine, drafting: drafting,
                                send: { send(text) { replying = false; text = ""; lastDraft = nil; draftLine = nil } },
                                askDraft: { askDraft() },
                                cancel: { replying = false })
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            HStack(spacing: MekaSpace.xs) {
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
            }
            .padding(.top, MekaSpace.xxs)
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: replying)
        .onAppear {
            // A reply that didn't send opens again with his words.
            if let draft = row.replyDraft { text = draft; replying = true }
        }
        .onChange(of: row.replyDraft) { _, draft in
            if let draft { text = draft; replying = true }
        }
    }
}

extension MailNeedsRowView {
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

private struct MailNeedsPill: View {
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
