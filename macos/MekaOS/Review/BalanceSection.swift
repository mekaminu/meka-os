@preconcurrency import MekaKit
import SwiftUI

/// A grey line as drawn: durations and the last words kept together, as on the Fold (quality gate slice 62).
private func balanceShown(_ line: String) -> String {
    LoneWordGlue.shared.kept(line: DurationGlue.shared.kept(line: line))
}

/// Balance view in Review on the Mac (V2, slice 1), like the Fold's: the reviewed week's time across Work, Family,
/// Health and Self, each with its bar against his target, a status ("Met" or work's "3 h over" in the accent) and what
/// counted; "Targets ›" unfolds a row of chips per area (synced with the Fold). Only Strings and Ints cross to the core.
///
/// Motion: the section staggers in with the review's others; bars fill on appear and glide to a new target; statuses
/// cross-fade; Targets unfolds in place on the expand spring (the chevron turns); a chip blends to the accent.
/// Reduce Motion: cross-fades, bars set.
///
/// Slice 5: "Count as …": each event with no clear area is listed with chips (Family, Health, Self, Work, Don't count);
/// a click counts every event with that title (synced with the Fold), the row folds away and the line says so with
/// Undo. His choices are listed under Targets, each with Forget.
struct BalanceSection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let balance: BalanceView
    let palette: MekaPalette
    @State private var open = false
    @State private var said: String?
    @State private var countSaid: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel(BalanceRules.shared.TITLE, palette)
            Text(balanceShown(balance.line)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: balance.line)
            ForEach(balance.rows, id: \.area.name) { r in BalanceRowView(row: r, palette: palette) }
            if let u = balance.uncounted {
                Text(balanceShown(u)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            countAs
            Text(balanceShown(balance.sources)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            targets
        }
        .padding(.top, MekaSpace.l)
    }

    /// Slice 5: the events with no clear area, each with a chip per area; the line after a click with Undo.
    @ViewBuilder
    private var countAs: some View {
        ForEach(Array(balance.unclear.enumerated()), id: \.element.key) { i, u in
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                HStack {
                    Text(u.title).font(MekaType.body).foregroundStyle(palette.textPrimary)
                    Spacer()
                    Text(balanceShown(u.line)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                HStack(spacing: MekaSpace.xs) {
                    Text("Count as").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    ForEach(countChoices, id: \.0) { choice in
                        chip(choice.1, lit: false) { count(u.title, choice.0) }
                    }
                }
            }
            .staggeredAppear(i)
            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: balance.unclear.map(\.key))
        if let s = countSaid {
            HStack {
                Text(balanceShown(s)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                Spacer()
                Button("Undo") {
                    Task { if await model.balanceUndoCountAs() { countSaid = nil } }
                }
                .buttonStyle(.plain).font(MekaType.itemMeta).foregroundStyle(palette.accent)
            }
            .transition(.opacity)
        }
    }

    private var countChoices: [(String, String)] {
        BalanceRules.shared.countAsCodes().map { ($0, BalanceRules.shared.countAsLabel(code: $0)) }
    }

    private func count(_ title: String, _ code: String) {
        Task {
            if let line = await model.balanceCountAs(title, area: code) {
                withAnimation(MekaMotion.appear(reduced: reduceMotion)) { countSaid = line }
            }
        }
    }

    /// Slice 5: his "Count as …" choices under Targets, each with Forget.
    @ViewBuilder
    private var choices: some View {
        if !balance.choices.isEmpty {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text("Counted as you chose").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                ForEach(balance.choices, id: \.key) { c in
                    HStack {
                        Text("\(c.title) · \(c.label)").font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                        Spacer()
                        Button("Forget") { count(c.title, "CLEAR") }
                            .buttonStyle(.plain).font(MekaType.caption).foregroundStyle(palette.accent)
                            .accessibilityLabel("Forget how \(c.title) counts")
                    }
                }
            }
        }
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
                choices
                if let s = said {
                    Text(balanceShown(s)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
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
            Text(balanceShown(row.line)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
            ForEach(row.items, id: \.self) { Text(balanceShown($0)).font(MekaType.caption).foregroundStyle(palette.textTertiary) }
            if row.moreItems > 0 {
                Text("and \(row.moreItems) more").font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            if let line = row.trendLine {
                BalanceTrendView(row: row, palette: palette)
                Text(balanceShown(line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
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
