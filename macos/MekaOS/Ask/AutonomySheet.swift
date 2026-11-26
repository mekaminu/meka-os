@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → What MEKA may do on the Mac (policy engine UI, slice 1), like the Fold's AutonomyPane: the pause switch
/// on top, each earned-trust kind under Your lists ("Does it for you", "Asks first · 3 of 5 yeses in a row", "Asks first
/// · you said keep asking"), Your mail's switches, then the locked rows no setting changes. Walk-through week 5, A17:
/// no pills; the pause switch and each row that can change are menus of the core's two choices (AutonomyChoiceRules).
/// Only a Bool or a kind's key (a String) crosses to the core.
///
/// Motion: the sheet scale-fades (system); the summary, each row's line and the words under the pause menu cross-fade;
/// the sections stagger in; a row's dot blends to the accent while MEKA does it on its own; the answer's line arrives
/// on the expand spring. Reduce Motion: cross-fades.
struct AutonomySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        let v = model.autonomy ?? AutonomyView.companion.EMPTY
        let rules = AutonomyRules.shared
        let paused = v.paused
        ScrollView {
            VStack(alignment: .leading, spacing: MekaSpace.xs) {
                Text("What Navilife may do").font(MekaType.upNextTitle).staggeredAppear(0)
                Text(LoneWordGlue.shared.kept(line: v.summary)).font(MekaType.body)
                    .foregroundStyle(paused ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                    .staggeredAppear(1)
                if let said = rules.answerUnder(said: model.autonomySaid, summary: v.summary) {
                    Text(LoneWordGlue.shared.kept(line: said)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
                        .transition(.opacity)
                }
                // Walk-through week 5, A17: the kill switch is an Off · On menu from the core's sheet, as the Fold's row and
                // sheet, the lit choice's words under it; only a Bool crosses to the core.
                pauseRow(paused)
                    .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                    .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
                    .padding(.top, MekaSpace.xs)
                    .staggeredAppear(2)
                ForEach(Array(v.sections.enumerated()), id: \.offset) { i, section in
                    label(section.label).staggeredAppear(3 + 2 * i)
                    ForEach(section.rows, id: \.key) { row in
                        rowView(row, paused: paused)
                    }
                    .staggeredAppear(4 + 2 * i)
                }
                Text(LoneWordGlue.shared.kept(line: rules.NOTE)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, MekaSpace.xs)
                    .staggeredAppear(3 + 2 * v.sections.count)
                HStack {
                    Spacer()
                    Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
                }
                .padding(.top, MekaSpace.m)
            }
            .animation(MekaMotion.expand(reduced: reduceMotion), value: model.autonomySaid)
            .padding(MekaSpace.l)
        }
        .frame(width: 560, height: 640)
        .background(palette.surface)
    }

    private func rowView(_ row: AutonomyRow, paused: Bool) -> some View {
        // Walk-through 2026-11-15, A21: off was surfaceRaised on the sheet (near-invisible); now an empty dot in a
        // tertiary ring, as on the Fold.
        let off = !row.onItsOwn && !row.locked
        let dot: Color = row.onItsOwn ? palette.accent : (row.locked ? palette.textTertiary : palette.surface)
        return HStack(spacing: MekaSpace.xs) {
            Circle().fill(dot).frame(width: 10, height: 10)
                .overlay { if off { Circle().strokeBorder(palette.textTertiary, lineWidth: 1.5) } }
                .animation(MekaMotion.appear(reduced: reduceMotion), value: row.onItsOwn)
            VStack(alignment: .leading, spacing: 0) {
                Text(LoneWordGlue.shared.kept(line: row.title)).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(LoneWordGlue.shared.kept(line: row.line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: row.line)
            }
            Spacer(minLength: MekaSpace.xs)
            if row.locked {
                Image(systemName: "lock.fill").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .accessibilityLabel("Locked")
            } else if let sheet = choices.sheet(row: row, paused: paused) {
                // Walk-through week 5, A17: the row's two choices are a menu (the pill it had is gone); choosing the
                // other sends only the row's key (a String) to the core, as the pill did.
                let key = row.key
                menu(sheet, spoken: choices.spoken(row: row)) { name in
                    if choices.changes(name: name) { Task { await model.resetTrust(key) } }
                }
            }
        }
        .padding(.vertical, MekaSpace.xs)
        .accessibilityElement(children: .combine)
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased())
            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
            .padding(.top, MekaSpace.xs)
    }

    private var choices: AutonomyChoiceRules { AutonomyChoiceRules.shared }

    /// The kill switch: its title, an Off · On menu saying what's in force, and the lit choice's words under it.
    private func pauseRow(_ paused: Bool) -> some View {
        let row = choices.pauseRow(paused: paused)
        let sheet = choices.pauseSheet(paused: paused)
        let lit = sheet.choices.first(where: { $0.lit })
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack(spacing: MekaSpace.xs) {
                Text(LoneWordGlue.shared.kept(line: row.title)).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Spacer(minLength: MekaSpace.xs)
                menu(sheet, spoken: row.spoken) { name in
                    if let on = choices.pauseNamed(paused: paused, name: name)?.boolValue {
                        Task { await model.setAutonomyPaused(on) }
                    }
                }
            }
            Text(LoneWordGlue.shared.kept(line: lit?.line ?? "")).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: paused)
        }
    }

    /// A menu of a sheet's choices, the lit one shown; a pick of another sends only its name to [pick].
    private func menu(_ sheet: NotifyChoiceSheet, spoken: String, pick: @escaping (String) -> Void) -> some View {
        let litName = sheet.choices.first(where: { $0.lit })?.name ?? ""
        return Picker(sheet.title, selection: Binding(get: { litName }, set: { name in if name != litName { pick(name) } })) {
            ForEach(sheet.choices, id: \.name) { c in Text(c.label).tag(c.name) }
        }
        .pickerStyle(.menu)
        .labelsHidden()
        .fixedSize()
        .help(LoneWordGlue.shared.kept(line: sheet.note))
        .accessibilityLabel(spoken)
    }
}
