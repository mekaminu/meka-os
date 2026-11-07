@preconcurrency import MekaKit
import SwiftUI

/// Plan's lower half (approved mockup `docs/design/money/Phone-PlanMore.dc.html`), slice 1, like the Fold's
/// MoneyPlanMoreBlocks: Goals' empty card with start tiles, and the forecast as In / Out / Left over tiles, a 6 · 12
/// months toggle, month bars growing to the total (the 6th and 12th tagged in gold), By tiles and What if I cut…, a
/// slider worked out on this Mac as he slides (never stored). Bars grow in turn and the figures cross-fade; Reduce
/// Motion shows them at once.
struct MoneyGoalsEmptyView: View {
    let empty: MoneyGoalsEmpty
    let palette: MekaPalette
    let onStart: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(spacing: MekaSpace.xs) {
                Circle().strokeBorder(palette.accent.opacity(0.5), style: StrokeStyle(lineWidth: 2, dash: [4, 3]))
                    .frame(width: 36, height: 36)
                    .overlay(Text("◎").font(MekaType.body).foregroundStyle(palette.accent))
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(empty.title).font(MekaType.upNextCardTitle).foregroundStyle(palette.textPrimary)
                    Text(empty.line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
            }
            HStack(spacing: MekaSpace.xs) {
                ForEach(empty.tiles, id: \.key) { t in
                    Button {
                        MekaHaptics.tick()
                        onStart(t.key)
                    } label: {
                        VStack(spacing: MekaSpace.xxs) {
                            Text(t.icon).font(MekaType.body).foregroundStyle(palette.accent)
                            Text(t.name).font(MekaType.caption).foregroundStyle(palette.textPrimary).multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .padding(.horizontal, MekaSpace.xs)
                        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel(t.spoken)
                }
            }
        }
        .moneyCard(palette)
    }
}

/// A small-caps tile: gold when lit (left over), the alarm red when below zero.
struct MoneyPlanTileView: View {
    let tile: MoneyPlanTile
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(tile.label).font(MekaType.micro).tracking(MekaType.microTracking)
                .foregroundStyle(tile.lit ? palette.accent : palette.textSecondary).lineLimit(1)
            Text(tile.value).font(MekaType.metaStrong)
                .foregroundStyle(tile.alarm ? palette.critical : (tile.lit ? palette.accent : palette.textPrimary))
                .lineLimit(1).contentTransition(.numericText())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xs)
        .background(tile.lit ? palette.accent.opacity(0.10) : palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tile.spoken)
    }
}

/// The forecast card's tiles, toggle, bars, By tiles and the slider; the What if… row follows in the same card.
struct MoneyForecastTilesView: View {
    let tiles: MoneyForecastTiles
    let palette: MekaPalette
    let reduced: Bool
    let onSpan: (Int32) -> Void
    let onCut: (Int32) -> Void
    @State private var grown = false

    var body: some View {
        let t = tiles
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(spacing: MekaSpace.xs) {
                ForEach(t.tiles, id: \.label) { MoneyPlanTileView(tile: $0, palette: palette) }
            }
            HStack(spacing: MekaSpace.xxs) {
                ForEach(t.spans.map { $0.int32Value }, id: \.self) { n in
                    let on = n == t.span
                    Button {
                        if !on { MekaHaptics.tick(); onSpan(n) }
                    } label: {
                        Text("\(n) months").font(MekaType.caption)
                            .foregroundStyle(on ? palette.textPrimary : palette.textSecondary)
                            .padding(.horizontal, MekaSpace.xs).padding(.vertical, MekaSpace.xxs)
                            .background(on ? palette.accent.opacity(0.18) : Color.clear, in: Capsule())
                            .contentShape(Capsule())
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel("\(n) months")
                    .accessibilityAddTraits(on ? [.isSelected] : [])
                }
            }
            .padding(MekaSpace.xxs)
            .background(palette.surface, in: Capsule())
            bars(t.bars)
            HStack(spacing: MekaSpace.xs) {
                ForEach(t.by, id: \.label) { MoneyPlanTileView(tile: $0, palette: palette) }
            }
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                HStack {
                    Text(MoneyPlanMore.shared.CUT_HEAD).font(MekaType.captionStrong).foregroundStyle(palette.textPrimary)
                    Spacer()
                    Text(t.cutLabel).font(MekaType.captionStrong).foregroundStyle(palette.accent).contentTransition(.numericText())
                }
                Slider(
                    value: Binding(
                        get: { Double(t.cutPence) / 100 },
                        set: { pounds in
                            let step = Int(MoneyPlanMore.shared.CUT_STEP_POUNDS)
                            let p = (Int(pounds.rounded()) / step) * step
                            if Int64(p) * 100 != t.cutPence { MekaHaptics.tick(); onCut(Int32(p)) }
                        }
                    ),
                    in: 0 ... Double(MoneyPlanMore.shared.CUT_MAX_POUNDS)
                )
                .tint(palette.accent)
                .controlSize(.small)
                .accessibilityLabel("What if I cut, \(t.cutLabel)")
                Text(t.cutNote).font(MekaType.caption).foregroundStyle(palette.textSecondary).contentTransition(.opacity)
            }
        }
        .animation(MekaMotion.appear(reduced: reduced), value: t.cutPence)
        .onAppear { grown = true }
    }

    private func bars(_ bars: [MoneyPlanBar]) -> some View {
        HStack(alignment: .bottom, spacing: MekaSpace.xxs) {
            ForEach(Array(bars.enumerated()), id: \.offset) { i, b in
                VStack(spacing: MekaSpace.xxs) {
                    Spacer(minLength: 0)
                    if let tag = b.tag {
                        Text(tag).font(MekaType.micro).foregroundStyle(b.below ? palette.critical : palette.accent)
                            .lineLimit(1).fixedSize()
                    }
                    RoundedRectangle(cornerRadius: 4)
                        .fill(b.below ? palette.critical.opacity(b.key ? 1 : 0.35) : palette.accent.opacity(b.key ? 1 : 0.28))
                        .frame(height: max(3, 80 * CGFloat(grown || reduced ? b.share : 0)))
                        .animation(reduced ? nil : .easeOut(duration: 0.6).delay(Double(i) * 0.04), value: grown)
                        .animation(MekaMotion.appear(reduced: reduced), value: b.share)
                    Text(b.month).font(MekaType.micro).foregroundStyle(palette.textTertiary).lineLimit(1).fixedSize()
                }
                .frame(maxWidth: .infinity)
            }
        }
        .frame(height: 120)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Month by month, " + bars.filter { $0.key }.map { "\($0.month) \($0.tag ?? "")" }.joined(separator: ", "))
    }
}
