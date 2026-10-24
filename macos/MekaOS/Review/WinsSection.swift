@preconcurrency import MekaKit
import SwiftUI

/// Work wins in Review on the Mac (V1, slice 1), like the Fold's: the reviewed week's wins, a field to keep one ("What
/// went well this week?"), and this quarter's wins week by week. Friday at 16:00 MEKA asks; until a win is kept the
/// section's line is lit in the accent. Only Strings cross to the core.
///
/// Motion: the section staggers in with the review's others; the lines cross-fade (the week's line blends to the
/// accent while MEKA is asking); a kept win arrives and a removed one leaves on the expand spring; "This quarter"
/// unfolds its weeks in place (the chevron turns). Reduce Motion: cross-fades.
struct WinsSection: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let wins: WinsView
    /// The review's week, so a line said for one week isn't shown on another.
    let weekStart: Int64
    let palette: MekaPalette
    @State private var text = ""
    @State private var said: (week: Int64, said: CoreModel.WinSaid)?
    @State private var quarterOpen = false

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            SectionLabel("Wins", palette)
            Text(LoneWordGlue.shared.kept(line: wins.weekLine)).font(MekaType.itemMeta)
                .foregroundStyle(wins.asking ? palette.accent : palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: wins.weekLine)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: wins.asking)
            ForEach(wins.week, id: \.id) { w in
                WinRowView(win: w, palette: palette) { model.removeWin(w.id) }
                    .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
            TextField(wins.hint, text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit {
                    let typed = text
                    let week = weekStart
                    text = ""
                    Task {
                        guard let r = await model.addWin(typed) else { return }
                        said = (week, r)
                        if r.refused { text = typed }
                    }
                }
            if let s = said, s.week == weekStart {
                Text(s.said.line).font(MekaType.caption)
                    .foregroundStyle(s.said.refused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: s.said)
            }
            if wins.quarterCount > 0 { quarter }
        }
        .padding(.top, MekaSpace.l)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: wins.week.map(\.id))
    }

    @ViewBuilder
    private var quarter: some View {
        Button {
            MekaHaptics.tick()
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { quarterOpen.toggle() }
        } label: {
            HStack {
                Text(wins.quarterTitle).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundStyle(palette.accent)
                    .rotationEffect(.degrees(quarterOpen ? 90 : 0))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(MekaPressStyle())
        .accessibilityLabel(quarterOpen ? "Hide this quarter's wins" : "Show this quarter's wins")
        .padding(.top, MekaSpace.xs)
        if quarterOpen {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                ForEach(Array(wins.quarter.enumerated()), id: \.element.weekStart) { i, week in
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(week.label).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        ForEach(week.rows, id: \.id) { w in
                            HStack {
                                Text(w.text).font(MekaType.body).foregroundStyle(palette.textPrimary).textSelection(.enabled)
                                Spacer()
                                Text(w.dayLabel).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            }
                            .accessibilityElement(children: .combine)
                            .accessibilityLabel(w.spoken)
                        }
                    }
                    .padding(.top, MekaSpace.xs)
                    .staggeredAppear(i)
                }
            }
            .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
        }
    }
}

private struct WinRowView: View {
    let win: WinRow
    let palette: MekaPalette
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(win.text).font(MekaType.body).foregroundStyle(palette.textPrimary).textSelection(.enabled)
                Text(win.dayLabel).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(win.spoken)
            Spacer()
            Button("Remove", action: onRemove)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.textTertiary)
                .accessibilityLabel("Remove \(win.text)")
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}
