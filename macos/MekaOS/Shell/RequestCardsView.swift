import SwiftUI
@preconcurrency import MekaKit

/// Requests from people Meka watches (V1, requests slice 4), in Needs you above the stack: "From Wife · 14:02", the
/// quote, what Add would do, what more it changes (work from home), then Add · Change · Not a task. The Fold read the
/// message; the cards sync, so answering here clears them there. Buttons press in (light haptic; Not a task a tick);
/// a card folds away with the expand spring and the undo bar rises. Cards stagger in after the after-work card.
/// Reduced motion: cross-fades. Nothing is sent to anyone.
struct RequestCardsView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// The stagger index of the first card.
    var firstIndex: Int = 2

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            ForEach(Array(model.requests.enumerated()), id: \.element.id) { i, card in
                RequestCardRow(card: card, palette: palette) { model.answerRequest(card, $0) }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + i)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.requests.map(\.id))
    }
}

private struct RequestCardRow: View {
    let card: RequestCard
    let palette: MekaPalette
    let answer: (RequestAnswer) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(card.from).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            Text(card.quote).font(MekaType.body).foregroundStyle(palette.textSecondary).lineLimit(3)
            Text(card.action).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary).padding(.top, MekaSpace.xxs)
            if let detail = card.detail {
                Text(detail).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            HStack(spacing: MekaSpace.xs) {
                pill(card.addLabel, filled: true, spoken: "\(card.addLabel): \(card.action)") { answer(.add) }
                if let change = card.changeLabel {
                    pill(change, filled: false, spoken: "\(change) \(card.action)") { answer(.change) }
                }
                pill(card.declineLabel, filled: false, quiet: true, spoken: card.declineLabel) { answer(.decline) }
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
