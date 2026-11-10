import SwiftUI
@preconcurrency import MekaKit

/// Messages the Fold triaged (V1, messages slice 3), in Needs you above the request cards: "Tunde · 14:02 · Needs a
/// reply", the gist (never the message), and MEKA's drafted reply in a quiet box. The Mac never sends: Copy reply puts
/// the draft on the pasteboard (Meka pastes it into the chat himself) and answers the card; an FYI has Seen; Not now
/// clears it. Answering here clears the card on the Fold too. Buttons press in (light haptic; Not now a tick); a card
/// folds away with the expand spring and the undo bar rises. Cards stagger in after the after-work card. Reduced
/// motion: cross-fades.
struct TriageCardsView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// The stagger index of the first card.
    var firstIndex: Int = 2
    /// The approved Needs you look (MESSAGES): each chat is one row that opens in place. Today's command centre keeps
    /// the cards (false), as the Fold's command centre keeps its compact list.
    var rows: Bool = false

    var body: some View {
        if rows { messageRows } else { cards }
    }

    /// MESSAGES as the approved look draws it (`docs/design/needs-you/needs-you.dc.html`, the Fold's slices 1 and 6):
    /// "MESSAGES · 4", then one row per chat (initials, name, time, its one line, "2 to reply"); a click opens it in
    /// place to its messages as lines with Reply · Done · Later · Task, a second click closes it. A chat carrying
    /// requests opens to its full card, so every request keeps its own answers. Hint line under them.
    private var messageRows: some View {
        // Chats needing nothing from Meka sit under FYI as thin rows (NeedsYouFyiRowsView), not here.
        let chats = NeedsYouLayoutRules.shared.messages(chats: model.triageChats)
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if !chats.isEmpty {
                Text(NeedsYouLayoutRules.shared.head(title: NeedsYouLayoutRules.shared.MESSAGES_HEAD, count: Int32(chats.count)))
                    .font(MekaType.captionStrong).tracking(MekaType.captionStrongTracking)
                    .foregroundStyle(palette.textTertiary)
                    .accessibilityAddTraits(.isHeader)
                    .staggeredAppear(firstIndex)
            }
            ForEach(Array(chats.enumerated()), id: \.element.key) { i, chat in
                NeedsYouChatRow(chat: chat, palette: palette)
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + 1 + i)
            }
            if !chats.isEmpty {
                Text(NeedsYouLayoutRules.shared.MESSAGES_HINT_MAC)
                    .font(MekaType.micro).foregroundStyle(palette.textTertiary)
                    .padding(.leading, MekaSpace.xxs)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.triage.map(\.id))
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.requests.map(\.id))
    }

    private var cards: some View {
        // One card per conversation (messages grouped per chat, slice 1): a chat with one message shows its card as
        // before; two or more show the chat's card with every message under Show all.
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            ForEach(Array(model.triageChats.enumerated()), id: \.element.key) { i, chat in
                Group {
                    if chat.single, let card = chat.cards.first {
                        TriageCardRow(card: card, palette: palette) { model.answerTriage(card, lead: $0) }
                    } else {
                        TriageChatRow(chat: chat, palette: palette)
                    }
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                .staggeredAppear(firstIndex + i)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.triage.map(\.id))
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.requests.map(\.id))
    }
}

/// A chat as one row of MESSAGES (the approved look): a 32 pt circle with its initials, the name and time over its one
/// line, the badge at the right ("1 request" / "2 to reply" lit, "3 new" quiet). A click opens it in place on the expand
/// spring (a cross-fade with reduced motion): a plain chat's messages as lines under the name, then Reply (gold) · Done ·
/// Later · Task; Reply unfolds the chat's own card (its draft and Copy reply / Send via your phone, still his click).
/// A chat with requests opens to its full card. Done is also in the row's right-click menu and VoiceOver's actions (the
/// Fold's swipe right).
struct NeedsYouChatRow: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let chat: TriageChat
    let palette: MekaPalette
    @State private var open = false
    @State private var replying = false

    static let avatar: CGFloat = 32

    var body: some View {
        let row = NeedsYouLayoutRules.shared.row(chat: chat)
        let rules = NeedsYouOpenRules.shared
        VStack(alignment: .leading, spacing: 0) {
            Button {
                MekaHaptics.light()
                withAnimation(MekaMotion.expand(reduced: reduceMotion)) {
                    open.toggle()
                    if !open { replying = false }
                }
            } label: {
                HStack(alignment: .center, spacing: MekaSpace.xs) {
                    Text(row.initials).font(MekaType.captionStrong).foregroundStyle(palette.accent).lineLimit(1)
                        .frame(width: Self.avatar, height: Self.avatar)
                        .background(palette.surfaceRaised, in: Circle())
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: MekaSpace.xs) {
                            Text(row.name).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(1)
                            Text(row.time).font(MekaType.micro).foregroundStyle(palette.textTertiary).lineLimit(1)
                        }
                        Text(row.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Text(row.badge).font(MekaType.micro).lineLimit(1)
                        .foregroundStyle(row.hot ? palette.approval : palette.textSecondary)
                        .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                        .background(palette.surfaceRaised, in: Capsule())
                }
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(row.spoken)
            .accessibilityValue(open ? "Open" : "Closed")
            .accessibilityAddTraits(.isButton)
            .accessibilityAction(named: rules.DONE) { model.doneTriageChat(chat) }
            .contextMenu {
                Button(rules.DONE) { model.doneTriageChat(chat) }
                Button(rules.LATER) { model.laterTriageChat(chat) }
                Button(rules.TASK) { model.taskFromTriageChat(chat) }
            }
            if open {
                Group {
                    if rules.plain(chat: chat) { openLines } else { fullCard }
                }
                .padding(.horizontal, MekaSpace.xs).padding(.bottom, MekaSpace.xs)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }

    /// The open row: its messages as lines under the name (past the avatar), then the four taps.
    private var openLines: some View {
        let rules = NeedsYouOpenRules.shared
        let indent = Self.avatar + MekaSpace.xs + MekaSpace.xs
        return VStack(alignment: .leading, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                ForEach(Array(rules.lines(chat: chat).enumerated()), id: \.offset) { _, line in
                    Text(line).font(MekaType.caption).foregroundStyle(palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                        .textSelection(.enabled)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(rules.spoken(chat: chat))
            HStack(spacing: MekaSpace.xxs) {
                pill(replying ? rules.SHOW_LESS : rules.REPLY, gold: true) {
                    MekaHaptics.light()
                    withAnimation(MekaMotion.expand(reduced: reduceMotion)) { replying.toggle() }
                }
                pill(rules.DONE) { model.doneTriageChat(chat) }
                pill(rules.LATER) { model.laterTriageChat(chat) }
                pill(rules.TASK) { model.taskFromTriageChat(chat) }
            }
            if replying {
                fullCard
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.leading, indent - MekaSpace.xs)
    }

    /// The chat's own card: one message's card, or the chat's with its requests and Show all.
    @ViewBuilder private var fullCard: some View {
        if chat.single, let card = chat.cards.first {
            TriageCardRow(card: card, palette: palette) { model.answerTriage(card, lead: $0) }
        } else {
            TriageChatRow(chat: chat, palette: palette)
        }
    }

    private func pill(_ label: String, gold: Bool = false, action: @escaping () -> Void) -> some View {
        Button(label, action: action)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta)
            .foregroundStyle(gold ? palette.onAccent : palette.textPrimary)
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(gold ? palette.accent : palette.surfaceRaised, in: Capsule())
    }
}

/// A chat's card: its name, "21 new · 4 people · 1 needs a reply · WhatsApp · 14:20", up to three lines of what matters,
/// Done (every message seen on both devices; the undo bar says so) and Show all, which unfolds each message's own card
/// under it on the expand spring. Reduced motion: cross-fades.
private struct TriageChatRow: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let chat: TriageChat
    let palette: MekaPalette
    @State private var open = false

    var body: some View {
        let rules = TriageChatRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                // "Jordan asked" above the group's name (Fold review 2026-10-12, bug 3): why this chat is at the top.
                if let name = chat.askedBy {
                    Text(rules.askedLine(name: name)).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
                // Done at the card's far right, beside the name, as on every Needs you card (Meka, 2026-10-20 17:33).
                HStack(alignment: .center, spacing: MekaSpace.xs) {
                    Text(chat.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary).lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    DonePill(palette: palette, label: rules.DONE, spoken: "Done with \(chat.title)") { model.doneTriageChat(chat) }
                }
                Text("\(chat.countLine) · \(chat.meta)").font(MekaType.itemMeta)
                    .foregroundStyle(chat.replies > 0 ? palette.accent : palette.textSecondary)
                // MEKA's one-line summary of a busy chat (slice 3c), first; only the lines that need a reply stay under it.
                if let summary = chat.summary {
                    Text(summary).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(3).padding(.top, MekaSpace.xxs)
                        .transition(.opacity)
                }
                ForEach(chat.top, id: \.self) { line in
                    Text(line).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(2).padding(.top, MekaSpace.xxs)
                }
                // The rest of the group's chatter (slice 3b): its count and MEKA's gist, shown here instead of the digest.
                if let chatter = chat.chatterLine {
                    Text(chatter).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(2).padding(.top, MekaSpace.xxs)
                }
                HStack(spacing: MekaSpace.xs) {
                    pill(open ? rules.SHOW_LESS : rules.expandLabel(chat: chat), filled: false) {
                        MekaHaptics.tick()
                        withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open.toggle() }
                    }
                }
                .padding(.top, MekaSpace.m)
            }
            .padding(MekaSpace.m)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
            .accessibilityElement(children: .contain)
            .accessibilityLabel(chat.spoken)
            .mekaHoverLift()
            // What the person asked for stays in view under the card (slice 3): Done never answers it.
            if !chat.requests.isEmpty {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(chat.requests, id: \.id) { request in
                        RequestCardRow(card: request, palette: palette) { model.answerRequest(request, $0) }
                            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    }
                }
                .padding(.leading, MekaSpace.m)
            }
            if open {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    ForEach(chat.cards, id: \.id) { card in
                        TriageCardRow(card: card, palette: palette) { model.answerTriage(card, lead: $0) }
                    }
                }
                .padding(.leading, MekaSpace.m)
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
    }

    private func pill(_ label: String, filled: Bool, action: @escaping () -> Void) -> some View {
        Button(label, action: action)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta)
            .foregroundStyle(filled ? palette.onAccent : palette.accent)
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(filled ? palette.accent : palette.surface, in: Capsule())
    }
}

private struct TriageCardRow: View {
    let card: TriageCard
    let palette: MekaPalette
    @Environment(\.mekaReduceMotion) private var reduceMotion
    /// true: the leading button (Copy reply or Seen); false: Not now.
    let answer: (Bool) -> Void

    var body: some View {
        let rules = TriageReplyRules.shared
        let primary = rules.primary(card: card, live: false, canSend: false)
        let lead = rules.label(primary: primary)
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            // Someone Meka watches for asked this in a group (Fold review 2026-10-12, bug 3): "Jordan asked", in brass.
            if let name = card.askedBy {
                Text(TriageChatRules.shared.askedLine(name: name)).font(MekaType.itemMeta).foregroundStyle(palette.accent)
            }
            // One strong line (who · what they need), the rest muted (Mac presentation pass, item 6).
            let look = NeedsYouCardRules.shared
            Text(look.headline(card: card)).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary).lineLimit(2)
            Text(look.meta(card: card)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary).lineLimit(1)
            if let draft = card.draft {
                Text(draft).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    .textSelection(.enabled)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                    .padding(.top, MekaSpace.xs)
            }
            HStack(spacing: MekaSpace.xs) {
                pill(lead, filled: true, spoken: spokenLead(primary, lead: lead)) { answer(true) }
                    // While the phone has the request, the button waits (it can't be pressed twice).
                    .disabled(rules.waitingOnPhone(card: card))
                    .opacity(rules.waitingOnPhone(card: card) ? 0.5 : 1)
                if let quiet = rules.secondary(card: card) {
                    pill(quiet, filled: false, quiet: true, spoken: "\(quiet): \(rules.who(card: card))") { answer(false) }
                }
            }
            .padding(.top, MekaSpace.xs)
            // Send (via your phone) waiting, or why the phone couldn't (WhatsApp replies, slice 2); cross-fades as it changes.
            if let phone = rules.phoneSendLine(card: card) {
                Text(phone).font(MekaType.caption)
                    .foregroundStyle(rules.waitingOnPhone(card: card) ? palette.accent : palette.textSecondary)
                    .transition(.opacity)
            }
            // Which way the reply goes (WhatsApp replies, slice 1, item 4).
            if let route = rules.routeLine(card: card, primary: primary) {
                Text(route).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
        }
        // Less inner padding at the Mac's type sizes, still on the 8/16 rhythm.
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        // The phone's answer to Send (via your phone) cross-fades in (WhatsApp replies, slice 2).
        .animation(MekaMotion.appear(reduced: reduceMotion), value: TriageReplyRules.shared.phoneSendLine(card: card) ?? "")
        .accessibilityElement(children: .contain)
        .accessibilityLabel(card.spoken)
        .mekaHoverLift()
    }

    /** What VoiceOver says for the lead button. */
    private func spokenLead(_ primary: TriageCardPrimary, lead: String) -> String {
        let rules = TriageReplyRules.shared
        if primary == .sendViaPhone { return "Send your reply to \(rules.who(card: card)) from your phone" }
        if primary == .replyInApp { return "Open WhatsApp with your reply to \(rules.who(card: card)) typed" }
        return card.draft != nil ? rules.copiedLine(card: card) : lead
    }

    private func pill(_ label: String, filled: Bool, quiet: Bool = false, spoken: String, action: @escaping () -> Void) -> some View {
        Button(label, action: action)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta)
            .foregroundStyle(filled ? palette.onAccent : (quiet ? palette.textSecondary : palette.accent))
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(filled ? palette.accent : palette.surface, in: Capsule())
            .accessibilityLabel(spoken)
    }
}
