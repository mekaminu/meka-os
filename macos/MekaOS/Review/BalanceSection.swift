@preconcurrency import MekaKit
import SwiftUI

/// Balance view in Review on the Mac (V2, slice 1), like the Fold's: the reviewed week's time across Work, Family,
/// Health and Self, each with its bar against his target, a status ("Met" or work's "3 h over" in the accent) and what
/// counted; "Targets ›" unfolds a row of chips per area (synced with the Fold). Only Strings and Ints cross to the core.
///
/// Motion: the section staggers in with the review's others; bars fill on appear and glide to a new target; statuses
/// cross-fade; Targets unfolds in place on the expand spring (the chevron turns); a chip blends to the accent.
/// Reduce Motion: cross-fades, bars set.
struct BalanceSection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let balance: BalanceView
    let palette: MekaPalette
    @State private var open = false
    @State private var said: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel(BalanceRules.shared.TITLE, palette)
            Text(balance.line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: balance.line)
            ForEach(balance.rows, id: \.area.name) { r in BalanceRowView(row: r, palette: palette) }
            if let u = balance.uncounted {
                Text(u).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            Text(balance.sources).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            targets
        }
        .padding(.top, MekaSpace.l)
    }

    @ViewBuilder
    private var targets: some View {
        Button {
            MekaHaptics.tick()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { open.toggle() }
        } label: {
            HStack {
                Text("Targets").font(MekaType.itemMeta).foregroundStyle(palette.accent)
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundStyle(palette.accent)
                    .rotationEffect(.degrees(open ? 90 : 0))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(open ? "Hide the targets" : "Show the targets")
        if open {
            VStack(alignment: .leading, spacing: MekaSpace.m) {
                ForEach(Array(balance.rows.enumerated()), id: \.element.area.name) { i, r in
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        Text("\(r.label) · a week").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        HStack(spacing: MekaSpace.xs) {
                            ForEach(r.choices.map { Int($0.intValue) }, id: \.self) { c in
                                chip(label(c), lit: chosen(r, c)) { set(r, c) }
                            }
                        }
                    }
                    .staggeredAppear(i)
                }
                if let s = said {
                    Text(s).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: s)
                }
            }
            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
        }
    }

    private var yourHours: Int { Int(BalanceRules.shared.YOUR_HOURS) }

    private func label(_ c: Int) -> String {
        c == yourHours ? "Your hours" : BalanceRules.shared.hours(min: Int32(c))
    }

    private func chosen(_ r: BalanceRow, _ c: Int) -> Bool {
        if c == yourHours { return r.defaultTarget }
        if r.area.name == "WORK" { return !r.defaultTarget && c == Int(r.targetMin) }
        return c == Int(r.targetMin)
    }

    private func set(_ r: BalanceRow, _ c: Int) {
        guard !chosen(r, c) else { return }
        let area = r.area.name
        Task {
            if let line = await model.setBalanceTarget(area, minutes: c) { said = line }
        }
    }

    private func chip(_ label: String, lit: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(MekaType.caption).lineLimit(1)
                .foregroundStyle(lit ? palette.onAccent : palette.textPrimary)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(Capsule().fill(lit ? palette.accent : palette.surfaceRaised))
                .animation(MekaMotion.appear(reduced: reduceMotion), value: lit)
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityAddTraits(lit ? .isSelected : [])
    }
}

private struct BalanceRowView: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let row: BalanceRow
    let palette: MekaPalette
    @State private var shown: Double = 0

    private var fraction: Double { min(1, max(0, Double(row.pct) / 100)) }

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack {
                Text(row.label).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                Spacer()
                if let s = row.status {
                    Text(s).font(MekaType.itemMeta)
                        .foregroundStyle(row.lit ? palette.accent : palette.textSecondary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: s)
                }
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.surfaceRaised)
                    Capsule().fill(palette.accent).frame(width: geo.size.width * shown)
                }
            }
            .frame(height: 6)
            .padding(.vertical, MekaSpace.xxs)
            Text(row.line).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            ForEach(row.items, id: \.self) { Text($0).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
            if row.moreItems > 0 {
                Text("and \(row.moreItems) more").font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            if let line = row.trendLine {
                BalanceTrendView(row: row, palette: palette)
                Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
            }
        }
        .padding(.vertical, MekaSpace.xxs)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(row.spoken)
        .onAppear {
            if reduceMotion { shown = fraction } else { withAnimation(MekaMotion.replan(reduced: false)) { shown = fraction } }
        }
        .onChange(of: row.pct) { withAnimation(MekaMotion.replan(reduced: reduceMotion)) { shown = fraction } }
    }
}

/// Slice 2: the last four weeks as four small bars (oldest first), the met ones (work: over) in the accent, a hairline
/// at the target and each week's Monday under its bar. Bars rise one after another on appear and glide to a new week;
/// Reduce Motion sets them.
private struct BalanceTrendView: View {
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let row: BalanceRow
    let palette: MekaPalette
    @State private var risen = false

    private let barHeight: CGFloat = 24

    var body: some View {
        let weeks = row.trend
        HStack(alignment: .bottom, spacing: MekaSpace.xs) {
            ForEach(Array(weeks.enumerated()), id: \.element.weekStart) { i, w in
                VStack(spacing: MekaSpace.xxs) {
                    ZStack(alignment: .bottom) {
                        Rectangle().fill(palette.textTertiary.opacity(0.5)).frame(height: 1)
                            .offset(y: -barHeight * CGFloat(row.trendTargetPct) / 100)
                        RoundedRectangle(cornerRadius: 2)
                            .fill(w.met ? palette.accent : palette.textTertiary)
                            .frame(width: 18, height: risen ? barHeight * CGFloat(w.pct) / 100 : 0)
                            .animation(
                                reduceMotion ? nil : MekaMotion.replan(reduced: false).delay(0.04 * Double(i)),
                                value: risen
                            )
                            .animation(MekaMotion.replan(reduced: reduceMotion), value: w.pct)
                    }
                    .frame(maxWidth: .infinity, minHeight: barHeight, maxHeight: barHeight, alignment: .bottom)
                    Text(w.current ? "So far" : w.label).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
            }
        }
        .padding(.top, MekaSpace.xs)
        .accessibilityHidden(true)
        .onAppear { risen = true }
    }
}
