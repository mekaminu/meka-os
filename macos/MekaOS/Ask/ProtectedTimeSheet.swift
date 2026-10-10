@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Protected time on the Mac (protected time, slice 1), like the Fold's ProtectedTimePane: evenings from a
/// chosen time (Off · From 18:00 … 20:00) and weekends with bank holidays (Open · Protected). Plan my day and Move to
/// later never put a task into protected time; habits, booked sessions and date night are left as they are. Only two
/// Bools and an Int cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary and today's line cross-fade; the sections stagger in; a chip's
/// fill blends as it is chosen (tick haptic); today's line arrives on the expand spring. Reduce Motion: cross-fades.
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
            Text(v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            if let today = v.todayLine {
                Text(today).font(MekaType.caption).foregroundStyle(palette.accent)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: today)
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
            Text(rules.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
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
