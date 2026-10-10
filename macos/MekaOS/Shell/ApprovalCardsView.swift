import SwiftUI
@preconcurrency import MekaKit

/// The approvals inbox (V1, slice 1), first in Needs you: a request from outside MEKA to trade, pay or share a document
/// ("TRADE · KESTREL", what would happen, its detail lines, "Asked 21:40 · expires in 14 min", lit in the critical
/// colour in its last 15 minutes), then Approve (Touch ID every time) and Decline. Nothing happens until Meka answers;
/// answering here clears the Fold. Cards stagger in; Approve presses in and asks for Touch ID; once taken the card
/// folds away on the expand spring (medium haptic) and the bar says "Approved · …" (no Undo: the one who asked acts on
/// it). Reduced motion: cross-fades.
struct ApprovalCardsView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// The stagger index of the first card.
    var firstIndex: Int = 1

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            ForEach(Array(model.approvals.enumerated()), id: \.element.id) { i, card in
                ApprovalCardRow(card: card, palette: palette,
                                approve: { model.approve(card) }, decline: { model.declineApproval(card) })
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + i)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.approvals.map(\.id))
    }
}

private struct ApprovalCardRow: View {
    let card: ApprovalCard
    let palette: MekaPalette
    let approve: () -> Void
    let decline: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(card.label).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            Text(card.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
            ForEach(card.lines, id: \.self) { line in
                Text(line).font(MekaType.body).foregroundStyle(palette.textSecondary)
            }
            Text(card.why).font(MekaType.itemMeta)
                .foregroundStyle(card.urgent ? palette.critical : palette.textTertiary)
                .padding(.top, MekaSpace.xxs)
            HStack(spacing: MekaSpace.xs) {
                Button(action: approve) {
                    Label(card.approveLabel, systemImage: "touchid")
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.onAccent)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(palette.accent, in: Capsule())
                .accessibilityLabel("\(card.approveLabel) \(card.title), with Touch ID")
                Button(card.declineLabel, action: decline)
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.textSecondary)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(palette.surface, in: Capsule())
                    .accessibilityLabel("\(card.declineLabel) \(card.title)")
            }
            .padding(.top, MekaSpace.m)
            Text(ApprovalRules.shared.LOCK_MAC).font(MekaType.caption).foregroundStyle(palette.textTertiary)
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(card.spoken)
        .mekaHoverLift()
    }
}
