@preconcurrency import MekaKit
import SwiftUI

/// Work wins in Review on the Mac (V1, slice 1), like the Fold's: the reviewed week's wins, the "+ Add a win" row opening
/// a sheet to keep one ("What went well this week?"; walk-through week 5, A17), and this quarter's wins week by week. Friday at 16:00 MEKA asks; until a win is kept the
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
    @State private var adding = false
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
            // Walk-through week 5, A17: "+ Add a win" is a row opening a sheet, not an open box on the page.
            GoalAddRowButton(row: wins.addRow, palette: palette) { adding = true }
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
        .sheet(isPresented: $adding) {
            LineAddSheet(sheet: wins.sheet, palette: palette, canAdd: { WinRules.shared.canAdd(text: $0) }, close: { adding = false }) { typed in
                let week = weekStart
                Task {
                    guard let r = await model.addWin(typed) else { return }
                    said = (week, r)
                }
            }
        }
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

/// A sheet with one line to add on the Mac (walk-through week 5, A17; Review's "+ Add a win"), like the Fold's: the line
/// with an example, the note, Close · Add (disabled until [canAdd] says the line is kept). Add hands the line on and
/// closes at once; only Strings cross to the core.
struct LineAddSheet: View {
    let sheet: GoalAddSheet
    let palette: MekaPalette
    /// The words the field starts with (a goal's words to change); empty for a new line.
    var initial: String = ""
    let canAdd: (String) -> Bool
    let close: () -> Void
    let add: (String) -> Void
    @State private var line = ""

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(sheet.title).font(MekaType.nowTitle).foregroundStyle(palette.textPrimary)
            TextField(sheet.hint, text: $line).textFieldStyle(.roundedBorder).onSubmit(save)
                .accessibilityLabel(sheet.spokenHint)
            Text(sheet.note).font(MekaType.caption).foregroundStyle(palette.textTertiary).fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                Button(GoalAddRules.shared.CLOSE, action: close).keyboardShortcut(.cancelAction)
                Button(sheet.addLabel, action: save).keyboardShortcut(.defaultAction)
                    .disabled(!canAdd(line))
            }
        }
        .padding(MekaSpace.l)
        .frame(width: 420)
        .onAppear { if line.isEmpty { line = initial } }
    }

    private func save() {
        guard canAdd(line) else { return }
        let typed = line
        MekaHaptics.light()
        add(typed)
        close()
    }
}
