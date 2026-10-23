@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Life admin hour on the Mac (slice 1), like the Fold's LifeAdminPane: the day and time picked once
/// (weekday and time chips); MEKA books the hour each week around the calendar and gathers the admin jobs into it (the
/// renewals that need you, then tasks like "Call the GP"). Skip this week frees the hour; Turn off stops it. Only Ints and
/// a Bool cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary and the line after an action cross-fade; the chip rows and the
/// jobs stagger in; a chip's fill blends as it is chosen (tick haptic); Skip this week and Turn off arrive on the expand
/// spring once it is on, and the skip button's words cross-fade. Reduce Motion: cross-fades.
struct LifeAdminSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        let v = model.lifeAdmin ?? LifeAdminView.companion.EMPTY
        let rules = LifeAdminRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Life admin hour").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(LoneWordGlue.shared.kept(line: v.summary)).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            label("When").staggeredAppear(2)
            HStack(spacing: MekaSpace.xs) {
                ForEach(Array(rules.WEEKDAY_CHOICES.enumerated()), id: \.offset) { i, day in
                    let weekday = Int32(i + 1)
                    chip(day, lit: v.on && v.weekday == weekday) { model.setLifeAdmin(weekday: weekday, startMin: v.startMin) }
                }
            }
            .staggeredAppear(3)
            HStack(spacing: MekaSpace.xs) {
                ForEach(rules.START_CHOICES.map { $0.int32Value }, id: \.self) { m in
                    chip(LocalClock.companion.formatMinute(m: m), lit: v.startMin == m) {
                        model.setLifeAdmin(weekday: v.weekday, startMin: m)
                    }
                }
            }
            .staggeredAppear(4)
            if v.on && v.thisWeek != nil {
                Button(v.skipped ? rules.KEEP_LABEL : rules.SKIP_LABEL) { model.skipLifeAdmin(!v.skipped) }
                    .buttonStyle(MekaPressStyle())
                    .font(MekaType.caption)
                    .foregroundStyle(v.skipped ? palette.accent : palette.textTertiary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: v.skipped)
                    .transition(.opacity)
            }
            if v.on {
                label("Gathered into it").padding(.top, MekaSpace.xs).staggeredAppear(5)
                if v.jobs.isEmpty {
                    Text(LoneWordGlue.shared.kept(line: rules.NO_JOBS)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                        .staggeredAppear(6)
                }
                ForEach(Array(v.jobs.enumerated()), id: \.element.id) { i, job in
                    JobRowView(job: job, palette: palette).staggeredAppear(6 + i)
                }
                if v.jobCount > Int32(v.jobs.count) {
                    Text("and \(Int(v.jobCount) - v.jobs.count) more").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
            }
            if let said = model.lifeAdminSaid {
                Text(LoneWordGlue.shared.kept(line: said)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
            }
            Text(LoneWordGlue.shared.kept(line: rules.NOTE)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, MekaSpace.xs)
            HStack {
                if v.on {
                    Button(rules.OFF_LABEL) { model.lifeAdminOff() }
                        .buttonStyle(MekaPressStyle())
                        .font(MekaType.caption)
                        .foregroundStyle(palette.textTertiary)
                        .transition(.opacity)
                }
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .animation(MekaMotion.expand(reduced: reduceMotion), value: v.on)
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
        .onDisappear { model.forgetLifeAdminSaid() }
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

private struct JobRowView: View {
    let job: LifeAdminJob
    let palette: MekaPalette

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(LoneWordGlue.shared.kept(line: job.title)).font(MekaType.body).foregroundStyle(palette.textPrimary)
            if let line = job.lineShown {
                Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(job.spoken)
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}
