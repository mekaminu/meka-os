@preconcurrency import MekaKit
import SwiftUI

/// Money redesign, Plan on the Mac, like the Fold's MoneySaveBlocks: **What if I save…** as one card. The slider (£25
/// steps, a tick per step), the months and rate pills, month-by-month bars, the total and the month it's reached, the
/// split line, Obi's line on whether it fits (amber when it doesn't) and Make it a goal. Every word and number is the
/// core's `MoneySave.view`, worked out on each change on this Mac (plain values in, the view out), so dragging never
/// rebuilds the money view.
///
/// Motion: the bars grow on the appear spring when anything changes (snap with Reduce Motion); the amount, total and
/// Obi's line cross-fade.
struct MoneySaveCardView: View {
    let base: MoneySaveBase
    let palette: MekaPalette
    let reduced: Bool
    let onGoal: (Int64, Int32, Int32) -> Void
    @State private var amount: Int64? = nil
    @State private var months: Int32 = MoneySave.shared.DEFAULT_MONTHS
    @State private var rate: Int32 = 0

    private var view: MoneySaveView {
        MoneySave.shared.view(amountPence: amount ?? base.startPence, months: months, rateBp: rate, base: base)
    }

    var body: some View {
        let v = view
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .lastTextBaseline) {
                Text(v.each).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Spacer()
                Text(v.amount).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary).contentTransition(.numericText())
            }
            Slider(
                value: Binding(
                    get: { Double(v.amountPence) / 100 },
                    set: { pounds in
                        let p = MoneySave.shared.snap(pence: Int64((pounds * 100).rounded()))
                        if p != v.amountPence { MekaHaptics.tick(); amount = p }
                    }
                ),
                in: Double(MoneySave.shared.MIN_PENCE) / 100 ... Double(MoneySave.shared.MAX_PENCE) / 100
            )
            .tint(palette.accent)
            .accessibilityLabel("Amount to save each month, \(v.amount)")
            HStack(spacing: MekaSpace.xs) {
                ForEach(v.months, id: \.value) { c in
                    pill(c) { months = c.value }
                }
            }
            HStack(spacing: MekaSpace.xs) {
                ForEach(v.rates, id: \.value) { c in
                    pill(c) { rate = c.value }
                }
            }
            HStack(alignment: .bottom, spacing: MekaSpace.xxs) {
                ForEach(Array(v.bars.enumerated()), id: \.offset) { _, b in
                    GeometryReader { g in
                        VStack(spacing: 0) {
                            Spacer(minLength: 0)
                            UnevenRoundedRectangle(topLeadingRadius: 4, topTrailingRadius: 4, style: .continuous)
                                .fill(b.last ? palette.accent : palette.accent.opacity(0.28))
                                .frame(height: g.size.height * min(max(b.share, 0.04), 1))
                        }
                    }
                    .help(b.label)
                }
            }
            .frame(height: 96)
            .padding(.top, MekaSpace.xs)
            .animation(MekaMotion.appear(reduced: reduced), value: v.bars.map(\.share))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(v.bars.map(\.label).joined(separator: ", "))
            HStack(alignment: .lastTextBaseline, spacing: MekaSpace.xs) {
                Text(v.total).font(MekaType.greeting).foregroundStyle(palette.accent).contentTransition(.numericText())
                Text(v.end).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            Text(v.split).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
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
            Button(MoneySave.shared.MAKE_GOAL) {
                MekaHaptics.tick()
                onGoal(v.amountPence, months, rate)
            }
            .buttonStyle(MekaPressStyle())
            .font(MekaType.captionStrong)
            .foregroundStyle(palette.onAccent)
            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
            .background(palette.accent, in: Capsule())
            .accessibilityLabel("\(MoneySave.shared.MAKE_GOAL): \(v.goalTitle)")
            Text(v.basis).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: MekaRadius.l, style: .continuous).fill(palette.surface))
        .animation(MekaMotion.appear(reduced: reduced), value: v.obi)
    }

    private func pill(_ c: MoneySaveChip, pick: @escaping () -> Void) -> some View {
        Button(c.label) {
            guard !c.on else { return }
            MekaHaptics.tick()
            pick()
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
