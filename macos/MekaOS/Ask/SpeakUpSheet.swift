@preconcurrency import MekaKit
import SwiftUI

/// Ask → Settings → Speaking up on the Mac (the JARVIS step, slice 1), like the Fold's SpeakUpPane: a section per moment
/// Ada says out loud on her own (Turn on / Turn off), How often, and Mute for 2 hours / Speak again now. The words are
/// the core's `SpeakUpRules.view`, synced with the Fold. On the Mac she speaks only while he is using it.
///
/// Motion: the sheet scale-fades (system); sections stagger in; a button presses in with a tick haptic and the status
/// line blends to the accent while on (`themeBlend`, the words cross-fade). Reduce Motion: cross-fades.
struct SpeakUpSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    @Environment(CoreModel.self) private var model
    let palette: MekaPalette
    @State private var view: SpeakUpView?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(SpeakUpRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            if let v = view {
                Text(LoneWordGlue.shared.kept(line: v.intro)).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .staggeredAppear(0)
                ForEach(Array(v.sections.enumerated()), id: \.offset) { i, s in
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                        Text(s.label.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                            .foregroundStyle(palette.textTertiary)
                            .accessibilityAddTraits(.isHeader)
                        Text(LoneWordGlue.shared.kept(line: s.status)).font(MekaType.body).foregroundStyle(s.lit ? palette.accent : palette.textPrimary)
                            .contentTransition(.opacity)
                            .animation(MekaMotion.themeBlend(reduced: reduceMotion), value: s.lit)
                        ForEach(s.steps, id: \.self) { step in
                            Text(LoneWordGlue.shared.kept(line: step)).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        if let action = s.action {
                            let label = s.label
                            Button(action) { run(label) }
                                .buttonStyle(MekaPressStyle())
                                .font(MekaType.itemMeta)
                                .foregroundStyle(palette.accent)
                                .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                                .background(palette.surfaceRaised, in: Capsule())
                                .padding(.top, MekaSpace.xxs)
                                .accessibilityLabel("\(action): \(s.label)")
                        }
                    }
                    .padding(.top, MekaSpace.m)
                    .staggeredAppear(i + 1)
                }
                Text(LoneWordGlue.shared.kept(line: v.footer)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .padding(.top, MekaSpace.m)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .background(palette.surface)
        .task { view = await model.speakUpView() }
    }

    private func run(_ label: String) {
        MekaHaptics.tick()
        Task {
            let next = await model.speakUpAction(label)
            withAnimation(MekaMotion.themeBlend(reduced: reduceMotion)) { if let next { view = next } }
        }
    }
}
