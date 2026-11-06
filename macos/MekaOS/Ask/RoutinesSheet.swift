@preconcurrency import MekaKit
import SwiftUI

/// Ask → Daily → Routines, on the Mac (Routines, slice 1), like the Fold's RoutinesPane: how many are on, the rule (a
/// routine never sends, pays, books or trades), YOUR ROUTINES — each a card with its day tile ("SUN" over "19:00"), its
/// name, its line, its next run, Remove and an On/Off switch (synced with the Fold) — and START WITH ONE, the starters
/// not added yet, each with Add. The words are the core's `RoutineRules`. Only Strings and Bools cross to the core.
///
/// Motion: the sheet scale-fades (system); cards stagger in; a switch slides with a tick haptic and the list re-flows
/// on the expand spring as a card comes or goes. Reduce Motion: cross-fades.
struct RoutinesSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var said: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(RoutineRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            if let v = model.routines {
                Text(LoneWordGlue.shared.kept(line: v.summary)).font(MekaType.body)
                    .foregroundStyle(palette.textSecondary)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                    .staggeredAppear(0)
                Text(LoneWordGlue.shared.kept(line: RoutineRules.shared.RULE_LINE)).font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .staggeredAppear(0)
                if let said {
                    Text(LoneWordGlue.shared.kept(line: said)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                        .transition(.opacity)
                }
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        if !v.rows.isEmpty {
                            label(RoutineRules.shared.YOURS).padding(.top, MekaSpace.m).accessibilityAddTraits(.isHeader)
                        }
                        ForEach(Array(v.rows.enumerated()), id: \.element.id) { i, r in
                            routineCard(r).staggeredAppear(i + 1)
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        }
                        if !v.starters.isEmpty {
                            label(RoutineRules.shared.START_WITH).padding(.top, MekaSpace.m).accessibilityAddTraits(.isHeader)
                        }
                        ForEach(Array(v.starters.enumerated()), id: \.element.key) { i, s in
                            starterCard(s).staggeredAppear(v.rows.count + i + 2)
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        }
                    }
                    .animation(MekaMotion.expand(reduced: reduceMotion), value: v.rows.count)
                    .animation(MekaMotion.expand(reduced: reduceMotion), value: v.starters.count)
                }
                .scrollIndicators(.never)
                .frame(maxHeight: 520)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 480)
        .background(palette.surface)
    }

    /// The day tile: "SUN" over "19:00".
    private func dayTile(_ day: String, _ time: String, on: Bool) -> some View {
        VStack(spacing: MekaSpace.xxs) {
            Text(day).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(on ? palette.accent : palette.textTertiary).lineLimit(1)
            Text(time).font(MekaType.itemMeta).foregroundStyle(on ? palette.textPrimary : palette.textTertiary).lineLimit(1)
        }
        .frame(minWidth: 64)
        .padding(MekaSpace.xs)
        .background(palette.surface, in: RoundedRectangle(cornerRadius: MekaRadius.s))
    }

    private func routineCard(_ r: RoutineRow) -> some View {
        let id = r.id
        let name = r.name
        let isOn = r.on
        return HStack(alignment: .center, spacing: MekaSpace.m) {
            dayTile(r.day, r.time, on: r.on)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(LoneWordGlue.shared.kept(line: r.name)).font(MekaType.body)
                    .foregroundStyle(r.on ? palette.textPrimary : palette.textTertiary)
                Text(LoneWordGlue.shared.kept(line: r.line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let next = r.next {
                    Text(LoneWordGlue.shared.kept(line: next)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                }
                Button(RoutineRules.shared.REMOVE) {
                    model.removeRoutine(id)
                    withAnimation(MekaMotion.appear(reduced: reduceMotion)) { said = "Removed \(name)" }
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.caption)
                .foregroundStyle(palette.accent)
                .accessibilityLabel("\(RoutineRules.shared.REMOVE) \(name)")
            }
            Spacer(minLength: 0)
            Toggle("", isOn: Binding(get: { isOn }, set: { model.setRoutineOn(id, on: $0) }))
                .toggleStyle(.switch)
                .tint(palette.accent)
                .labelsHidden()
                .accessibilityLabel(r.switchSpoken)
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(r.spoken)
    }

    private func starterCard(_ s: RoutineStarterRow) -> some View {
        let key = s.key
        return HStack(alignment: .center, spacing: MekaSpace.m) {
            dayTile(s.day, s.time, on: false)
            VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                Text(LoneWordGlue.shared.kept(line: s.name)).font(MekaType.body).foregroundStyle(palette.textPrimary)
                Text(LoneWordGlue.shared.kept(line: s.line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(LoneWordGlue.shared.kept(line: s.whenLine)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            }
            Spacer(minLength: 0)
            Button(RoutineRules.shared.ADD) { add(key) }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(palette.surface, in: Capsule())
                .accessibilityLabel("\(RoutineRules.shared.ADD) \(s.name)")
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(s.spoken)
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
    }

    private func add(_ key: String) {
        Task {
            let r = await model.addStarterRoutine(key)
            withAnimation(MekaMotion.appear(reduced: reduceMotion)) { said = r?.line ?? RoutineRules.shared.GONE_LINE }
        }
    }
}
