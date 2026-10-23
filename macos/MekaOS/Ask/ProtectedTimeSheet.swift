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
/// Slice 4: "Not protected tonight" (or "… today") under today's line lifts today's protected time just this once; it
/// cross-fades to "Undo · protect it again". Only a Bool crosses to the core.
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
                Text(LoneWordGlue.shared.kept(line: today)).font(MekaType.caption).foregroundStyle(palette.accent)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: today)
                    .transition(.opacity)
            }
            if let action = v.openAction ?? (v.openToday ? "Undo · protect it again" : nil) {
                let open = !v.openToday
                Button(action) { model.setProtectedOpenToday(open: open) }
                    .buttonStyle(.plain)
                    .font(MekaType.itemMeta)
                    .foregroundStyle(palette.accent)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: action)
                    .transition(.opacity)
            }
            label(rules.EVENINGS_LABEL).staggeredAppear(2)
            HStack(spacing: MekaSpace.xs) {
                chip("Off", lit: !v.evenings) {
                    model.setProtectedTime(evenings: false, fromMin: v.fromMin, weekends: v.weekends)
                }
                ForEach(rules.FROM_CHOICES.map { $0.int32Value }, id: \.self) { m in
                    chip("From " + LocalClock.companion.formatMinute(m: m), lit: v.evenings && v.fromMin == m) {
                        model.setProtectedTime(evenings: true, fromMin: m, weekends: v.weekends)
                    }
                }
            }
            .staggeredAppear(3)
            label(rules.WEEKENDS_LABEL).staggeredAppear(4)
            HStack(spacing: MekaSpace.xs) {
                chip("Open", lit: !v.weekends) {
                    model.setProtectedTime(evenings: v.evenings, fromMin: v.fromMin, weekends: false)
                }
                chip("Protected", lit: v.weekends) {
                    model.setProtectedTime(evenings: v.evenings, fromMin: v.fromMin, weekends: true)
                }
            }
            .staggeredAppear(5)
            if v.on {
                // Slice 3: work mail waits until morning.
                let mail = ProtectedMailRules.shared
                label(mail.LABEL)
                HStack(spacing: MekaSpace.xs) {
                    chip(mail.ON, lit: v.holdMail) { model.setProtectedMail(hold: true) }
                    chip(mail.OFF, lit: !v.holdMail) { model.setProtectedMail(hold: false) }
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

    private func label(_ text: String) -> some View {
        Text(text.uppercased())
            .font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
            .padding(.top, MekaSpace.xs)
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
