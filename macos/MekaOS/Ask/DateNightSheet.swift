@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Date night on the Mac (date night, slice 1), like the Fold's DateNightPane: the evening picked once (a
/// weekday chip, a start time, and which of the next two weeks it starts), the next four nights with Skip this one /
/// Keep it, and Turn off. The planner and Gym bookings keep each night's evening clear. Only Ints, an Int64 and a Bool
/// cross to the core.
///
/// Motion: the sheet scale-fades (system); the summary and the line after an action cross-fade; the chip rows and the
/// nights stagger in; a chip's fill blends as it is chosen (tick haptic); a night's line cross-fades between kept and
/// skipped; the Starts row and Turn off arrive on the expand spring once it is on. Reduce Motion: cross-fades.
struct DateNightSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette

    var body: some View {
        let v = model.dateNight ?? DateNightView.companion.EMPTY
        let rules = DateNightRules.shared
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text("Date night").font(MekaType.upNextTitle).staggeredAppear(0)
            Text(v.summary).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                .staggeredAppear(1)
            label("Evening").staggeredAppear(2)
            HStack(spacing: MekaSpace.xs) {
                ForEach(Array(rules.WEEKDAY_CHOICES.enumerated()), id: \.offset) { i, day in
                    let weekday = Int32(i + 1)
                    chip(day, lit: v.on && v.weekday == weekday) {
                        model.setDateNight(weekday: weekday, startMin: v.startMin, firstDay: anchor(v, weekday))
                    }
                }
            }
            .staggeredAppear(3)
            HStack(spacing: MekaSpace.xs) {
                ForEach(rules.START_CHOICES.map { $0.int32Value }, id: \.self) { m in
                    chip(LocalClock.companion.formatMinute(m: m), lit: v.startMin == m) {
                        model.setDateNight(weekday: v.weekday, startMin: m, firstDay: anchor(v, v.weekday))
                    }
                }
            }
            .staggeredAppear(4)
            if v.on && !v.starts.isEmpty {
                HStack(spacing: MekaSpace.xs) {
                    Text("Starts").font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    ForEach(v.starts, id: \.day) { s in
                        chip(s.label, lit: s.chosen) { model.setDateNight(weekday: v.weekday, startMin: v.startMin, firstDay: s.day) }
                    }
                }
                .transition(.opacity)
            }
            if !v.nights.isEmpty {
                label("Coming up").padding(.top, MekaSpace.xs).staggeredAppear(5)
                ForEach(Array(v.nights.enumerated()), id: \.element.day) { i, row in
                    NightRowView(row: row, palette: palette) { model.skipDateNight(day: row.day, skip: !row.skipped) }
                        .staggeredAppear(6 + i)
                }
            }
            if let said = model.dateNightSaid {
                Text(said).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: said)
            }
            Text(rules.NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, MekaSpace.xs)
            HStack {
                if v.on {
                    Button(rules.OFF_LABEL) { model.dateNightOff() }
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
        .onDisappear { model.forgetDateNightSaid() }
    }

    /// A night that stays one when only the time changes (so the fortnight isn't moved); else the weekday's next.
    private func anchor(_ v: DateNightView, _ weekday: Int32) -> Int64 {
        if v.on, v.weekday == weekday, let first = v.nights.first { return first.day }
        return DateNightRules.shared.firstNight(weekday: weekday, today: model.todayEpochDay, nextWeek: false)
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

private struct NightRowView: View {
    let row: DateNightRow
    let palette: MekaPalette
    let toggle: () -> Void
    @Environment(\.mekaReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: MekaSpace.xs) {
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(row.label).font(MekaType.body)
                    .foregroundStyle(row.label == "Tonight" ? palette.accent : palette.textPrimary)
                Text(row.line).font(MekaType.caption)
                    .foregroundStyle(row.skipped ? palette.textTertiary : palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: row.line)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(row.spoken)
            Spacer()
            Button(row.skipped ? DateNightRules.shared.KEEP_LABEL : DateNightRules.shared.SKIP_LABEL, action: toggle)
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(row.skipped ? palette.accent : palette.textTertiary)
                .accessibilityLabel(row.skipped ? "Keep \(row.label)" : "Skip \(row.label)")
        }
        .padding(.horizontal, MekaSpace.m)
        .padding(.vertical, MekaSpace.xs)
        .background(palette.background, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .mekaHoverLift()
    }
}
