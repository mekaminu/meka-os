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

    var body: some View {
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
                // "Jeanette asked" above the group's name (Fold review 2026-10-12, bug 3): why this chat is at the top.
                if let name = chat.askedBy {
                    Text(rules.askedLine(name: name)).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                }
                Text(chat.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary).lineLimit(1)
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
                    pill(rules.DONE, filled: true) { model.doneTriageChat(chat) }
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
    /// true: the leading button (Copy reply or Seen); false: Not now.
    let answer: (Bool) -> Void

    var body: some View {
        let rules = TriageReplyRules.shared
        let lead = rules.label(primary: rules.primary(card: card, live: false, canSend: false))
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            // Someone Meka watches for asked this in a group (Fold review 2026-10-12, bug 3): "Jeanette asked", in brass.
            if let name = card.askedBy {
                Text(TriageChatRules.shared.askedLine(name: name)).font(MekaType.itemMeta).foregroundStyle(palette.accent)
            }
            Text("\(card.from) · \(card.lane.label)").font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            Text(card.gist).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(3)
            if let draft = card.draft {
                Text(draft).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    .textSelection(.enabled)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                    .padding(.top, MekaSpace.xs)
            }
            HStack(spacing: MekaSpace.xs) {
                pill(lead, filled: true, spoken: card.draft != nil ? rules.copiedLine(card: card) : lead) { answer(true) }
                if let quiet = rules.secondary(card: card) {
                    pill(quiet, filled: false, quiet: true, spoken: "\(quiet): \(rules.who(card: card))") { answer(false) }
                }
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(card.spoken)
        .mekaHoverLift()
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
