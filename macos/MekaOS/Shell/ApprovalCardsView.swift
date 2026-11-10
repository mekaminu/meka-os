import SwiftUI
@preconcurrency import MekaKit

/// The approvals inbox (V1, slice 1), first in Needs you: a request from outside MEKA to trade, pay or share a document
/// ("TRADE · KESTREL", what would happen, its detail lines, "Asked 21:40 · expires in 14 min", lit in the critical
/// colour in its last 15 minutes), then Approve (Touch ID every time) and Decline. Nothing happens until Meka answers;
/// answering here clears the Fold. Cards stagger in; Approve presses in and asks for Touch ID; once taken the card
/// folds away on the expand spring (medium haptic) and the bar says "Approved · …" (no Undo: the one who asked acts on
/// it). Reduced motion: cross-fades. In the approved Needs you look (Mac slice 3) each is a compact
/// NeedsYouDecideCardView like the Fold's: its icon, the title over "Trade · Kestrel · Asked 21:40 · expires in 14 min"
/// (lit in its last 15 minutes), the detail lines whole, Approve (Touch ID) · Decline, the Touch ID note.
struct ApprovalCardsView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    /// The stagger index of the first card.
    var firstIndex: Int = 1

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.m) {
            ForEach(Array(model.approvals.enumerated()), id: \.element.id) { i, card in
                // The approved look (TO DECIDE, the Fold's slice 5): one compact card, what would happen kept whole.
                NeedsYouDecideCardView(card: NeedsYouDecideRules.shared.approval(card: card, lock: ApprovalRules.shared.LOCK_MAC),
                                       palette: palette,
                                       onYes: { model.approve(card) }, onNo: { model.declineApproval(card) },
                                       yesSymbol: "touchid", yesSpokenSuffix: ", with Touch ID")
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                    .staggeredAppear(firstIndex + i)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: model.approvals.map(\.id))
    }
}
