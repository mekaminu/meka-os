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
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            ForEach(Array(model.triage.enumerated()), id: \.element.id) { i, card in
                TriageCardRow(card: card, palette: palette) { model.answerTriage(card, lead: $0) }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + i)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.triage.map(\.id))
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
            Text("\(card.from) · \(card.lane.label)").font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            Text(card.gist).font(MekaType.body).foregroundStyle(palette.textPrimary).lineLimit(3)
            if let draft = card.draft {
                Text(draft).font(MekaType.body).foregroundStyle(palette.textSecondary)
                    .textSelection(.enabled)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.s)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                    .padding(.top, MekaSpace.xs)
            }
            HStack(spacing: MekaSpace.s) {
                pill(lead, filled: true, spoken: card.draft != nil ? rules.copiedLine(card: card) : lead) { answer(true) }
                if let quiet = rules.secondary(card: card) {
                    pill(quiet, filled: false, quiet: true, spoken: "\(quiet): \(rules.who(card: card))") { answer(false) }
                }
            }
            .padding(.top, MekaSpace.s)
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
