@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → MEKA's voice on the Mac (Weather and a voice, item 2), like the Fold's VoicePane: MEKA's voices
/// (Amazon Polly's British voices through MEKA's own server) first with the default marked, then the Mac's own voice,
/// each with ▶ Sample (MEKA's own words). Choosing one sets the synced "MEKA's voice" for Talk, the spoken brief and
/// the call assistant on every device. Under the list: why MEKA's voices are missing, the month's characters, and how
/// to get a better Mac voice.
///
/// Motion: the sheet scale-fades (system); a shimmer while the server answers; rows stagger in; the chosen row's dot
/// and border blend to the accent with a tick haptic; Sample presses in with a light haptic and its label cross-fades
/// to "■ Stop" (Stop: tick haptic); the lines cross-fade. Reduce Motion: cross-fades.
struct VoiceSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var speaker = MekaSpeaker()
    /// The row whose sample is playing.
    @State private var playing: String?

    var body: some View {
        VStack(alignment: .leading, spacing: MekaSpace.s) {
            Text(VoicePickerRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            Text(VoicePickerRules.shared.INTRO).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .staggeredAppear(0)
            if let v = model.voicePicker {
                ForEach(Array(v.choices.enumerated()), id: \.element.id) { i, c in
                    row(c).staggeredAppear(i + 1)
                }
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    if let status = v.statusLine {
                        Text(status).font(MekaType.caption).foregroundStyle(palette.accent).transition(.opacity)
                    }
                    if let usage = v.usageLine {
                        Text(usage).font(MekaType.caption).foregroundStyle(palette.textTertiary).transition(.opacity)
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
                .staggeredAppear(v.choices.count + 1)
                Text(v.help).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, MekaSpace.s)
                    .staggeredAppear(v.choices.count + 2)
            } else {
                SkeletonRows(count: 4, rowHeight: 48, palette: palette)
            }
            HStack {
                Spacer()
                Button("Done") { speaker.stop(); dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.s)
        }
        .padding(MekaSpace.l)
        .frame(width: 500)
        .background(palette.surface)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.voicePicker?.statusLine)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.voicePicker?.usageLine)
        .onAppear { speaker.attach(model) }
        .onDisappear { speaker.stop() }
        .task { await model.refreshVoicePicker() }
    }

    private func row(_ c: VoiceChoice) -> some View {
        let isPlaying = playing == c.id && speaker.speaking
        return HStack(spacing: MekaSpace.s) {
            Button { if !c.selected { model.chooseVoice(c.id) } } label: {
                HStack(spacing: MekaSpace.s) {
                    Circle().strokeBorder(c.selected ? palette.accent : palette.textTertiary, lineWidth: 1.5)
                        .background(Circle().fill(c.selected ? palette.accent : Color.clear).padding(4))
                        .frame(width: 18, height: 18)
                    VStack(alignment: .leading, spacing: 2) {
                        // A setting you choose, not something to act on: the regular weight.
                        Text(c.label).font(MekaType.body).foregroundStyle(palette.textPrimary)
                        Text(c.detail).font(MekaType.caption).foregroundStyle(palette.textSecondary).lineLimit(2)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(MekaPressStyle())
            .accessibilityAddTraits(c.selected ? .isSelected : AccessibilityTraits())
            .accessibilityLabel("Use \(c.label)")
            if c.sample {
                Button { toggleSample(c) } label: {
                    Text(isPlaying ? "■  Stop" : "▶  Sample").font(MekaType.itemMeta).foregroundStyle(palette.accent)
                        .contentTransition(.opacity)
                }
                .buttonStyle(MekaPressStyle())
                .help(isPlaying ? "Stop the sample" : "Hear \(c.label)")
            }
        }
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.s)
        .background(RoundedRectangle(cornerRadius: MekaRadius.m).fill(palette.surfaceRaised))
        .overlay(RoundedRectangle(cornerRadius: MekaRadius.m).strokeBorder(c.selected ? palette.accent : Color.clear, lineWidth: 1))
        .animation(MekaMotion.appear(reduced: reduceMotion), value: c.selected)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: isPlaying)
    }

    private func toggleSample(_ c: VoiceChoice) {
        if playing == c.id && speaker.speaking {
            MekaHaptics.tick()
            speaker.stop()
            playing = nil
            return
        }
        MekaHaptics.light()
        playing = c.id
        speaker.sample(c.id)
    }
}
