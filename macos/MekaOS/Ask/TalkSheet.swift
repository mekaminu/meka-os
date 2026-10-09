@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → Talk on the Mac (Talk without tapping the mic, slice 1), like the Fold's TalkPane: how to start
/// talking to MEKA without reaching for the mic (⌥Space while MEKA is in front, or the mic beside Ask's field) and why
/// it stays safe. The words are the core's `TalkStartRules`, shared with the Fold (whose pane adds the side button and
/// the headphones' button).
///
/// "When I open MEKA" (the Mac's slice): `TalkOnOpenRules.section(mac: true)` after "On the Mac", with Turn on / Turn
/// off (kept on this Mac; turning it on asks for the microphone and speech recognition if not yet decided, since an
/// open never asks).
///
/// Motion: the sheet scale-fades (system); sections stagger in; Turn on / Turn off presses in with a tick haptic and
/// the status line blends to the accent while on (`themeBlend`, the words cross-fade). Reduce Motion: cross-fades.
struct TalkSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @AppStorage(MacTalkOnOpen.key) private var listenOnOpen = false

    /// The core's Mac sections with "When I open MEKA" after "On the Mac".
    private var sections: [TalkSetupSection] {
        var all = TalkStartRules.shared.setup(mac: true, assistantHeld: false, samsung: false).sections
        all.insert(TalkOnOpenRules.shared.section(on: listenOnOpen, mac: true), at: min(1, all.count))
        return all
    }

    var body: some View {
        let setup = TalkStartRules.shared.setup(mac: true, assistantHeld: false, samsung: false)
        let view = (title: setup.title, intro: setup.intro, sections: sections)
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            Text(view.title).font(MekaType.upNextTitle).staggeredAppear(0)
            Text(view.intro).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .staggeredAppear(0)
            ForEach(Array(view.sections.enumerated()), id: \.offset) { i, s in
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(s.label.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                        .foregroundStyle(palette.textTertiary)
                        .accessibilityAddTraits(.isHeader)
                    Text(s.status).font(MekaType.body).foregroundStyle(s.lit ? palette.accent : palette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                        .contentTransition(.opacity)
                        .animation(MekaMotion.themeBlend(reduced: reduceMotion), value: s.lit)
                    ForEach(s.steps, id: \.self) { step in
                        Text(step).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if let action = s.action, s.label == TalkOnOpenRules.shared.LABEL {
                        Button(action) { toggleListenOnOpen() }
                            .buttonStyle(MekaPressStyle())
                            .font(MekaType.itemMeta)
                            .foregroundStyle(palette.accent)
                            .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
                            .background(palette.surfaceRaised, in: Capsule())
                            .padding(.top, MekaSpace.xxs)
                            .accessibilityLabel("\(action): listen when I open MEKA")
                    }
                }
                .padding(.top, MekaSpace.s)
                .staggeredAppear(i + 1)
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.s)
        }
        .padding(MekaSpace.l)
        .frame(width: 460)
        .background(palette.surface)
    }

    private func toggleListenOnOpen() {
        MekaHaptics.tick()
        withAnimation(MekaMotion.themeBlend(reduced: reduceMotion)) { listenOnOpen.toggle() }
        // An open never asks, so turning it on is the moment to (only if not yet decided).
        if listenOnOpen { Task { _ = await TalkController.allowed() } }
    }
}
