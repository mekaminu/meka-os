@preconcurrency import MekaKit
import SwiftUI

/// Ask → Daily → Obi is working on, on the Mac (Ada plans with you, part 3 slice 1), like the Fold's WorkingOnPane:
/// what needs him first, each card with WHAT I DID and, when there is one, WHAT I NEED FROM YOU (in the accent) and the
/// button to the screen that holds it; then STANDING ERRANDS with Turn off / Turn on (synced with the Fold). The words
/// are the core's `WorkingOnRules.view`. Only Strings and Bools cross to the core.
///
/// Motion: the sheet scale-fades (system); cards and switches stagger in; a switch presses in with a tick haptic and
/// the list re-flows on the expand spring as a card comes or goes. Reduce Motion: cross-fades.
struct WorkingOnSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var view: WorkingOnView?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(WorkingOnRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            if let v = view {
                Text(LoneWordGlue.shared.kept(line: v.summary)).font(MekaType.body)
                    .foregroundStyle(v.needCount > 0 ? palette.accent : palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .contentTransition(.opacity)
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: v.summary)
                    .staggeredAppear(0)
                Text(LoneWordGlue.shared.kept(line: WorkingOnRules.shared.INTRO)).font(MekaType.caption)
                    .foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
                    .staggeredAppear(0)
                ScrollView {
                    VStack(alignment: .leading, spacing: MekaSpace.xs) {
                        ForEach(Array(v.cards.enumerated()), id: \.element.errand.code) { i, c in
                            card(c).staggeredAppear(i + 1)
                                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                        }
                        Text(WorkingOnRules.shared.SWITCHES.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                            .foregroundStyle(palette.textTertiary)
                            .padding(.top, MekaSpace.m)
                            .accessibilityAddTraits(.isHeader)
                        ForEach(Array(v.switches.enumerated()), id: \.element.errand.code) { i, s in
                            switchRow(s).staggeredAppear(v.cards.count + 2 + i)
                        }
                        Text(LoneWordGlue.shared.kept(line: WorkingOnRules.shared.NEXT)).font(MekaType.caption)
                            .foregroundStyle(palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, MekaSpace.m)
                    }
                    .animation(MekaMotion.expand(reduced: reduceMotion), value: v.cards.count)
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
        .task { view = await model.workingOnView() }
    }

    private func card(_ c: WorkingOnCard) -> some View {
        let place = c.errand.place
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(c.title).font(MekaType.itemTitle).foregroundStyle(palette.textPrimary)
                .accessibilityAddTraits(.isHeader)
            label(WorkingOnRules.shared.DID)
            Text(LoneWordGlue.shared.kept(line: c.did)).font(MekaType.body).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if let need = c.need {
                label(WorkingOnRules.shared.NEED).padding(.top, MekaSpace.xs)
                Text(LoneWordGlue.shared.kept(line: need)).font(MekaType.body).foregroundStyle(palette.accent)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                Spacer()
                Button(c.errand.openLabel) {
                    MekaHaptics.tick()
                    model.openFromWorkingOn(place, reduced: reduceMotion)
                }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(palette.surface, in: Capsule())
                .accessibilityLabel("\(c.errand.openLabel): \(c.title)")
            }
        }
        .padding(MekaSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceRaised, in: RoundedRectangle(cornerRadius: MekaRadius.m))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(c.spoken)
    }

    private func switchRow(_ s: WorkingOnSwitch) -> some View {
        let code = s.errand.code
        let turnOn = !s.on
        return VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(s.errand.title).font(MekaType.body).foregroundStyle(s.on ? palette.textPrimary : palette.textTertiary)
            Text(LoneWordGlue.shared.kept(line: s.line)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Button(s.action) { toggle(code, on: turnOn) }
                .buttonStyle(MekaPressStyle())
                .font(MekaType.itemMeta)
                .foregroundStyle(palette.accent)
                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                .background(palette.surfaceRaised, in: Capsule())
                .accessibilityLabel("\(s.action): \(s.errand.title)")
        }
        .padding(.top, MekaSpace.xs)
    }

    private func label(_ text: String) -> some View {
        Text(text.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textTertiary)
    }

    private func toggle(_ code: String, on: Bool) {
        MekaHaptics.tick()
        Task {
            let next = await model.setStandingErrand(code, on: on)
            withAnimation(MekaMotion.expand(reduced: reduceMotion)) { if let next { view = next } }
        }
    }
}
