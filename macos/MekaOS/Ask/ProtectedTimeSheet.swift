@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Protected time on the Mac (protected time, slice 1), like the Fold's ProtectedTimePane: evenings from a
/// chosen time (Off · From 18:00 … 20:00) and weekends with bank holidays (Open · Protected). Plan my day and Move to
/// later never put a task into protected time; habits, booked sessions and date night are left as they are. Only two
/// Bools and an Int cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary and today's line cross-fade; the sections stagger in; a chip's
/// fill blends as it is chosen (tick haptic); today's line arrives on the expand spring. Reduce Motion: cross-fades.
/// Slice 3: WORK MAIL (Waits till morning · Shows as it comes) arrives on the expand spring once something is protected.
/// Slice 4: "Open tonight, just this once" (or "… today") under today's line lifts today's protected time just this
/// once; it cross-fades to "Protect tonight again" (week 5 A8: verbs; today's line is in the text colour). Only a Bool
/// crosses to the core.
/// Quality gate slice 49: the sheet's lines keep their last words and dots together (LoneWordGlue), like the Fold's.
struct ProtectedTimeSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        let v = model.protectedTime ?? ProtectedView.companion.EMPTY
        let rules = ProtectedRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Protected time").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(LoneWordGlue.shared.kept(line: v.summary)).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            if let today = v.todayLine {
                Text(LoneWordGlue.shared.kept(line: today)).font(MekaType.caption).foregroundStyle(palette.textPrimary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: today)
                    .transition(.opacity)
            }
            if let action = v.openAction ?? v.undoAction {
                let open = !v.openToday
                Button(action) { model.setProtectedOpenToday(open: open) }
                    .buttonStyle(.plain)
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: action)
                    .transition(.opacity)
            }
            // Walk-through week 5, A17: each choice is one menu saying what's chosen ("Evenings from: 19:00"), as the
            // Fold's row and sheet, not a row of chips.
            let choices = ProtectedChoiceRules.shared
            menu(choices.eveningsSheet(v: v)) { name in
                if let pick = choices.eveningNamed(name: name, current: v.fromMin) {
                    model.setProtectedTime(evenings: pick.on, fromMin: pick.fromMin, weekends: v.weekends)
                }
            }
            .padding(.top, MekaSpace.xs)
            .staggeredAppear(2)
            menu(choices.weekendsSheet(v: v)) { name in
                if let w = choices.weekendNamed(name: name) {
                    model.setProtectedTime(evenings: v.evenings, fromMin: v.fromMin, weekends: w.boolValue)
                }
            }
            .staggeredAppear(3)
            if v.on {
                // Slice 3: work mail waits until morning.
                let mail = ProtectedMailRules.shared
                menu(choices.mailSheet(v: v)) { name in
                    if let hold = choices.mailNamed(name: name) { model.setProtectedMail(hold: hold.boolValue) }
                }
                if let held = v.mailLine {
                    Text(LoneWordGlue.shared.kept(line: held)).font(MekaType.caption).foregroundStyle(palette.accent)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.appear(reduced: reduceMotion), value: held)
                        .transition(.opacity)
                }
                Text(LoneWordGlue.shared.kept(line: mail.NOTE)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(LoneWordGlue.shared.kept(line: rules.NOTE)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, MekaSpace.xs)
                .staggeredAppear(6)
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: v.todayLine)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: v.on)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: v.openToday)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: v.mailLine)
        .padding(MekaSpace.l)
        .frame(width: 560)
        .background(palette.surface)
    }

    /// A choice as one menu: the sheet's title, the lit choice shown, a pick passes its name back.
    private func menu(_ sheet: NotifyChoiceSheet, choose: @escaping (String) -> Void) -> some View {
        let lit = sheet.choices.first(where: { $0.lit })?.name ?? ""
        return Picker(sheet.title, selection: Binding(get: { lit }, set: { if $0 != lit { choose($0) } })) {
            ForEach(sheet.choices, id: \.name) { c in Text(c.label).tag(c.name) }
        }
        .pickerStyle(.menu).fixedSize()
        .help(LoneWordGlue.shared.kept(line: sheet.note))
    }
}
