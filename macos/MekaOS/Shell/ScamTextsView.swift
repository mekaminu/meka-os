import SwiftUI
@preconcurrency import MekaKit

/// Likely-scam texts (Mac presentation pass, item 6; Meka, 2026-10-15): kept out of Needs you's cards and listed greyed
/// at the column's foot under "LIKELY SCAMS · n", each with a ⚠ Likely scam chip, its one line, Delete (clears it on
/// both devices, the undo bar says so) and Block number when the sender is a number. Rows fold away on the expand
/// spring and stagger in after the cards; reduced motion cross-fades.
struct ScamTextsView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    var firstIndex: Int = 3

    var body: some View {
        let scams = model.scamTriage
        let rules = NeedsYouCardRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            if !scams.isEmpty {
                SectionLabel(rules.scamHeading(count: Int32(scams.count)), palette)
                    .padding(.top, MekaSpace.m)
                    .staggeredAppear(firstIndex)
                ForEach(Array(scams.enumerated()), id: \.element.id) { i, card in
                    ScamTextRow(card: card, palette: palette)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        .staggeredAppear(firstIndex + 1 + i)
                }
                Text(rules.SCAM_HINT).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: scams.map(\.id))
    }
}

private struct ScamTextRow: View {
    @Environment(CoreModel.self) private var model
    let card: TriageCard
    let palette: MekaPalette

    var body: some View {
        let rules = NeedsYouCardRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(rules.SCAM_CHIP)
                .font(MekaType.captionStrong)
                .foregroundStyle(palette.critical)
                .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                .background(palette.surface, in: Capsule())
            Text(rules.headline(card: card)).font(MekaType.body).foregroundStyle(palette.textSecondary).lineLimit(2)
            Text(rules.meta(card: card)).font(MekaType.itemMeta).foregroundStyle(palette.textTertiary).lineLimit(1)
            HStack(spacing: MekaSpace.xs) {
                pill(rules.DELETE) { model.deleteScamText(card) }
                if rules.number(card: card) != nil {
                    pill(rules.BLOCK) { model.blockScamText(card) }
                }
            }
            .padding(.top, MekaSpace.xs)
        }
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised.opacity(0.6), in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(rules.SCAM_CHIP.dropFirst(2)): \(rules.headline(card: card))")
    }

    private func pill(_ label: String, action: @escaping () -> Void) -> some View {
        Button(label, action: action)
            .buttonStyle(MekaPressStyle())
            .font(MekaType.itemMeta)
            .foregroundStyle(palette.textSecondary)
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(palette.surface, in: Capsule())
    }
}
