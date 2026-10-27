@preconcurrency import MekaKit
import SwiftUI

/// Money redesign, Plan on the Mac, like the Fold's MoneyClearBlocks: **Clear my cards** as one card. Each credit card
/// with its rate, the month it clears and what it owes; one slider for the total a month (£25 steps, a tick per step);
/// Highest interest first · Smallest balance first; the Debt-free and Interest tiles; what paying only the minimums would
/// mean; Obi's line (amber when it doesn't fit or never clears) and Use this plan. Every word and number is the core's
/// `MoneyClear.view`, worked out on each change on this Mac (plain values in, the view out), so dragging never rebuilds
/// the money view.
///
/// Motion: the dates, tiles and Obi's line cross-fade as he slides; an order pill lights on the appear spring.
struct MoneyClearCardView: View {
    let base: MoneyClearBase
    let palette: MekaPalette
    let reduced: Bool
    let onPlan: (Int64, String) -> Void
    @State private var pay: Int64? = nil
    @State private var order: String = MoneyClear.shared.BY_RATE

    private var view: MoneyClearView {
        MoneyClear.shared.view(payPence: pay ?? base.startPence, order: order, base: base)
    }

    var body: some View {
        let v = view
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            ForEach(v.rows, id: \.id) { r in row(r) }
            HStack(alignment: .lastTextBaseline) {
                Text(v.payLabel).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Spacer()
                Text(v.pay).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary).contentTransition(.numericText())
            }
            .padding(.top, MekaSpace.xxs)
            Slider(
                value: Binding(
                    get: { Double(v.payPence) / 100 },
                    set: { pounds in
                        let p = MoneyClear.shared.snap(pence: Int64((pounds * 100).rounded()), base: base)
                        if p != v.payPence { MekaHaptics.tick(); pay = p }
                    }
                ),
                in: Double(base.minPence) / 100 ... Double(base.maxPence) / 100
            )
            .tint(palette.accent)
            .accessibilityLabel("Total to pay off the cards each month, \(v.pay)")
            if !v.orders.isEmpty {
                HStack(spacing: MekaSpace.xs) {
                    ForEach(v.orders, id: \.key) { c in pill(c) }
                }
            }
            HStack(spacing: MekaSpace.xs) {
                tile(v.freeLabel, v.free, v.freeLit ? palette.money3 : palette.accent)
                tile(v.interestLabel, v.interest, palette.textPrimary)
            }
            Text(v.vsMin).font(MekaType.caption).foregroundStyle(palette.money3)
                .fixedSize(horizontal: false, vertical: true).contentTransition(.opacity)
            HStack(alignment: .top, spacing: MekaSpace.xs) {
                Circle()
                    .fill(RadialGradient(colors: [palette.accent, palette.accent.opacity(0.35)], center: .topLeading, startRadius: 1, endRadius: 14))
                    .frame(width: 18, height: 18)
                    .padding(.top, MekaSpace.xxs)
                Text(v.obi).font(MekaType.caption).foregroundStyle(v.obiLit ? palette.money3 : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
            }
            .padding(MekaSpace.xs)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: MekaRadius.m, style: .continuous).fill(palette.surfaceRaised))
            let goal: String? = v.goalTitle
            Button(MoneyClear.shared.USE) {
                MekaHaptics.tick()
                let amount: Int64 = v.payPence
                let chosen: String = order
                onPlan(amount, chosen)
            }
            .buttonStyle(MekaPressStyle())
            .font(MekaType.captionStrong)
            .foregroundStyle(goal != nil ? palette.onAccent : palette.textTertiary)
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(goal != nil ? palette.accent : palette.surfaceRaised, in: Capsule())
            .accessibilityLabel("\(MoneyClear.shared.USE): \(goal ?? MoneyClear.shared.NOT_AT_PACE)")
            // Layout rule (c): how this is worked out is said once, behind Plan's foot ⓘ (MoneyTabs.info).
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.l, style: .continuous).fill(palette.surface))
        .animation(MekaMotion.appear(reduced: reduced), value: v.obi)
    }

    private func row(_ r: MoneyClearRow) -> some View {
        HStack(spacing: MekaSpace.xs) {
            Text("CC").font(MekaType.captionStrong).foregroundStyle(palette.money3)
                .frame(width: 40, height: 40)
                .background(RoundedRectangle(cornerRadius: MekaRadius.m, style: .continuous).fill(palette.surfaceRaised))
            VStack(alignment: .leading, spacing: 0) {
                Text(r.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(r.rate).font(MekaType.caption).foregroundStyle(r.rateLit ? palette.money3 : palette.textSecondary)
                    .contentTransition(.opacity)
            }
            Spacer()
            Text(r.owed).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(r.spoken)
    }

    private func tile(_ label: String, _ value: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(label).font(MekaType.micro).foregroundStyle(palette.textSecondary)
            Text(value).font(MekaType.upNextCardTitle).foregroundStyle(color).contentTransition(.opacity)
        }
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m, style: .continuous).fill(palette.surfaceRaised))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label), \(value)")
    }

    private func pill(_ c: MoneyClearChip) -> some View {
        Button(c.label) {
            guard !c.on else { return }
            MekaHaptics.tick()
            order = c.key
        }
        .buttonStyle(MekaPressStyle())
        .font(MekaType.caption)
        .lineLimit(1)
        .foregroundStyle(c.on ? palette.onAccent : palette.textPrimary)
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
        .background(c.on ? palette.accent : palette.surfaceRaised, in: Capsule())
        .animation(MekaMotion.appear(reduced: reduced), value: c.on)
        .accessibilityLabel(c.spoken)
        .accessibilityAddTraits(c.on ? .isSelected : [])
    }
}
