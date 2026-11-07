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

/// Plan's lower half, slice 2, like the Fold's MoneyChallengeTilesBlock: No-spend days as this week's dots (gold = no
/// spending, outlined = not reached yet) with the count and Start / Stop counting, and Fun money as a pot with what's
/// left, a bar and £40 / £60 / £80 (clicking the chosen one takes it off); then "Cap a category for the month · Set ›".
/// Dots and the pot change on the appear spring; Reduce Motion shows them at once.
struct MoneyChallengeTilesView: View {
    let tiles: MoneyChallengeTiles
    let palette: MekaPalette
    let reduced: Bool
    let onNoSpend: (Bool) -> Void
    let onFun: (Int32, Bool) -> Void
    let onCap: () -> Void

    var body: some View {
        let t = tiles
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(alignment: .top, spacing: MekaSpace.xs) {
                noSpendTile(t.noSpend)
                funTile(t.funMoney)
            }
            Button {
                MekaHaptics.tick()
                onCap()
            } label: {
                HStack {
                    Text(t.capRow).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Spacer()
                    Text(t.capSet).font(MekaType.body).foregroundStyle(palette.accent)
                }
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityLabel("\(t.capRow), set")
        }
        .animation(MekaMotion.appear(reduced: reduced), value: t.funMoney.value)
        .animation(MekaMotion.appear(reduced: reduced), value: t.noSpend.on)
    }

    private func noSpendTile(_ ns: MoneyNoSpendTile) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(MoneyPlanMore.shared.NO_SPEND).font(MekaType.micro).tracking(MekaType.microTracking)
                .foregroundStyle(palette.textSecondary).lineLimit(1)
            HStack(spacing: 3) { // rhythm: ok (the week's dots are one mark)
                ForEach(Array(ns.dots.enumerated()), id: \.offset) { _, d in
                    Circle()
                        .fill(d.noSpend ? palette.accent : (d.spent ? palette.background : palette.surface))
                        .overlay(Circle().strokeBorder(d.pending ? palette.textTertiary.opacity(0.4) : Color.clear, style: StrokeStyle(lineWidth: 1, dash: [2, 2])))
                        .frame(width: 18, height: 18)
                        .overlay(Text(d.letter).font(MekaType.micro).foregroundStyle(d.noSpend ? palette.onAccent : palette.textTertiary))
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(ns.spoken)
            HStack(alignment: .lastTextBaseline, spacing: MekaSpace.xxs) {
                Text(ns.count).font(MekaType.metaStrong).foregroundStyle(palette.textPrimary).contentTransition(.numericText())
                Text(MoneyPlanMore.shared.THIS_WEEK).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            Button {
                MekaHaptics.tick()
                onNoSpend(!ns.on)
            } label: {
                Text(ns.button).font(MekaType.captionStrong)
                    .foregroundStyle(ns.on ? palette.accent : palette.onAccent)
                    .frame(maxWidth: .infinity).padding(.vertical, MekaSpace.xs)
                    .background(ns.on ? palette.background : palette.accent, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                    .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(MekaSpace.xs)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
    }

    private func funTile(_ f: MoneyFunTile) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(MoneyPlanMore.shared.FUN).font(MekaType.micro).tracking(MekaType.microTracking)
                .foregroundStyle(palette.textSecondary).lineLimit(1)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(f.value).font(MekaType.metaStrong)
                    .foregroundStyle(f.over ? palette.critical : (f.hasPot ? palette.textPrimary : palette.textSecondary))
                    .lineLimit(1).contentTransition(.numericText())
                Text(f.of).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(f.spoken)
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.background)
                    Capsule().fill(palette.accent).frame(width: g.size.width * CGFloat(min(max(f.share, 0), 1)))
                }
            }
            .frame(height: 8)
            HStack(spacing: MekaSpace.xxs) {
                ForEach(f.chips, id: \.pounds) { c in
                    Button {
                        MekaHaptics.tick()
                        onFun(c.pounds, c.chosen)
                    } label: {
                        Text(c.label).font(MekaType.caption)
                            .foregroundStyle(c.chosen ? palette.textPrimary : palette.textSecondary)
                            .frame(maxWidth: .infinity).padding(.vertical, MekaSpace.xxs)
                            .background(c.chosen ? palette.accent.opacity(0.18) : palette.background, in: Capsule())
                            .contentShape(Capsule())
                    }
                    .buttonStyle(MekaPressStyle())
                    .accessibilityLabel("Fun money \(c.label) a week\(c.chosen ? ", chosen, click to take it off" : "")")
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(MekaSpace.xs)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
    }
}

/// Plan's lower half, slice 2, like the Fold's MoneyAbroadTilesBlock: the year's foreign fees in a big tile with what
/// it could be, a bar per account (fees against the biggest; one with none is a full gold bar), and See the payments
/// behind it, which unfolds each fee with what it was for. Changes on the appear spring; Reduce Motion fades.
struct MoneyAbroadTilesView: View {
    let tiles: MoneyAbroadTiles
    let palette: MekaPalette
    let reduced: Bool
    @State private var open = false

    var body: some View {
        let t = tiles
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            HStack(spacing: MekaSpace.xs) {
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(t.feesLabel).font(MekaType.micro).tracking(MekaType.microTracking)
                        .foregroundStyle(t.lit ? palette.critical : palette.textSecondary).lineLimit(1)
                    Text(t.fees).font(MekaType.upNextCardTitle)
                        .foregroundStyle(t.lit ? palette.critical : palette.textPrimary).contentTransition(.numericText())
                }
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(t.lit ? palette.critical.opacity(0.14) : palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                Text(t.line).font(MekaType.caption).foregroundStyle(palette.textSecondary).fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel("\(t.feesLabel), \(t.fees). \(t.line)")
            ForEach(t.bars, id: \.id) { b in
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    HStack {
                        Text(b.name).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Spacer()
                        Text(b.amount).font(MekaType.metaStrong).foregroundStyle(b.good ? palette.accent : palette.textPrimary)
                    }
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Capsule().fill(palette.surface)
                            Capsule().fill(b.good ? palette.accent : palette.critical).frame(width: g.size.width * CGFloat(min(max(b.share, 0), 1)))
                        }
                    }
                    .frame(height: 6)
                    Text(b.sub).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(b.spoken)
            }
            if !t.payments.isEmpty {
                Button {
                    MekaHaptics.tick()
                    open.toggle()
                } label: {
                    Text(open ? MoneyPlanMore.shared.HIDE_PAYMENTS : MoneyPlanMore.shared.SHOW_PAYMENTS)
                        .font(MekaType.captionStrong).foregroundStyle(palette.textPrimary)
                        .frame(maxWidth: .infinity).padding(.vertical, MekaSpace.xs)
                        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                        .contentShape(Rectangle())
                }
                .buttonStyle(MekaPressStyle())
                if open {
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        ForEach(Array(t.payments.enumerated()), id: \.offset) { _, p in
                            HStack {
                                Text(p.what).font(MekaType.caption).foregroundStyle(palette.textPrimary)
                                Spacer()
                                Text(p.fee).font(MekaType.caption).foregroundStyle(palette.critical)
                            }
                            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                            .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
                            .accessibilityElement(children: .combine)
                            .accessibilityLabel(p.spoken)
                        }
                        if let m = t.more { Text(m).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
                    }
                    .transition(reduced ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }
            }
        }
        .animation(MekaMotion.appear(reduced: reduced), value: open)
    }
}
