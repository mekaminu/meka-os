@preconcurrency import MekaKit
import SwiftUI

/// Ask → More → MEKA's voice on the Mac (Weather and a voice, item 2), like the Fold's VoicePane: MEKA's voices
/// (Amazon Polly's British voices through MEKA's own server) first with the default marked, then the Mac's own voice,
/// each with ▶ Sample (MEKA's own words). Choosing one sets the synced "MEKA's voice" for Talk, the spoken brief and
/// the call assistant on every device. Under the list: why MEKA's voices are missing, the month's characters, and how
/// to get a better Mac voice. Then "This Mac's voices" (slice 10): Automatic (MEKA's pick) and each installed English
/// voice best first (engine · accent · Premium/Enhanced), each with ▶ Sample, and Speed and Pitch sliders, kept on this
/// Mac only (`DeviceVoiceStore`).
///
/// Motion: the sheet scale-fades (system); a shimmer while the server answers; rows stagger in; the chosen row's dot
/// and border blend to the accent with a tick haptic; Sample presses in with a light haptic and its label cross-fades
/// to "■ Stop" (Stop: tick haptic); the lines cross-fade; "+3 more" unfolds the rest of the Mac's voices (they stagger
/// in); a slider's line cross-fades as it moves, a tick haptic per step. Reduce Motion: cross-fades.
struct VoiceSheet: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let palette: MekaPalette
    @State private var speaker = MekaSpeaker()
    /// The row whose sample is playing.
    @State private var playing: String?
    @State private var deviceVoices: [DeviceVoice]?
    @State private var deviceSettings = DeviceVoiceStore.load()
    @State private var showAll = false

    var body: some View {
        ScrollView {
            content.padding(MekaSpace.l)
        }
        .frame(width: 500)
        .frame(minHeight: 420, idealHeight: 640, maxHeight: 760)
        .background(palette.surface)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.voicePicker?.statusLine)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.voicePicker?.usageLine)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: showAll)
        .onAppear {
            speaker.attach(model)
            deviceVoices = DeviceVoiceStore.voices()
        }
        .onDisappear { speaker.stop() }
        .task { await model.refreshVoicePicker() }
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
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
                if let voices = deviceVoices {
                    deviceSection(voices, after: v.choices.count + 2)
                }
                Text(v.help).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, MekaSpace.m)
                    .staggeredAppear(v.choices.count + 4)
            } else {
                SkeletonRows(count: 4, rowHeight: 48, palette: palette)
            }
            HStack {
                Spacer()
                Button("Done") { speaker.stop(); dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(.top, MekaSpace.m)
        }
    }

    /// This Mac's voices, speed and pitch (kept on the Mac).
    @ViewBuilder
    private func deviceSection(_ voices: [DeviceVoice], after: Int) -> some View {
        let d = DeviceVoiceRules.shared.view(voices: voices, settings: deviceSettings, mac: true, expanded: showAll)
        Text(d.title.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
            .foregroundStyle(palette.textSecondary)
            .padding(.top, MekaSpace.m)
            .staggeredAppear(after)
        ForEach(Array(d.rows.enumerated()), id: \.element.id) { i, r in
            row(VoiceChoice(id: "device:" + r.id, label: r.label, detail: r.detail, selected: r.selected, sample: true),
                choose: { chooseDevice(r) }, sample: { toggleDeviceSample(r) })
                .staggeredAppear(after + 1 + i)
        }
        if let more = d.moreLabel {
            Button(more) { MekaHaptics.light(); showAll = true }
                .buttonStyle(.plain).font(MekaType.itemMeta).foregroundStyle(palette.accent)
                .help("Show the other voices")
        }
        if let empty = d.emptyLine {
            Text(empty).font(MekaType.caption).foregroundStyle(palette.accent).fixedSize(horizontal: false, vertical: true)
        }
        slider(d.rateLine, value: Binding(
            get: { deviceSettings.rate },
            set: { saveDevice(DeviceVoiceSettings(voice: deviceSettings.voice, rate: DeviceVoiceRules.shared.rate(r: $0), pitch: deviceSettings.pitch)) }
        ), range: DeviceVoiceRules.shared.RATE_MIN...DeviceVoiceRules.shared.RATE_MAX)
        .staggeredAppear(after + 1 + d.rows.count)
        slider(d.pitchLine, value: Binding(
            get: { deviceSettings.pitch },
            set: { saveDevice(DeviceVoiceSettings(voice: deviceSettings.voice, rate: deviceSettings.rate, pitch: DeviceVoiceRules.shared.pitch(p: $0))) }
        ), range: DeviceVoiceRules.shared.PITCH_MIN...DeviceVoiceRules.shared.PITCH_MAX)
        .staggeredAppear(after + 2 + d.rows.count)
        Text(d.note).font(MekaType.caption).foregroundStyle(palette.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
            .staggeredAppear(after + 3 + d.rows.count)
    }

    private func slider(_ line: String, value: Binding<Float>, range: ClosedRange<Float>) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            Text(line).font(MekaType.body).foregroundStyle(palette.textPrimary).contentTransition(.opacity)
                .animation(MekaMotion.appear(reduced: reduceMotion), value: line)
            Slider(value: value, in: range, step: DeviceVoiceRules.shared.STEP)
                .tint(palette.accent)
                .accessibilityLabel(line)
        }
    }

    private func saveDevice(_ next: DeviceVoiceSettings) {
        guard next != deviceSettings else { return }
        MekaHaptics.tick()
        deviceSettings = next
        DeviceVoiceStore.save(next)
    }

    private func chooseDevice(_ r: DeviceVoiceRow) {
        guard !r.selected else { return }
        saveDevice(DeviceVoiceSettings(voice: r.id.isEmpty ? nil : r.id, rate: deviceSettings.rate, pitch: deviceSettings.pitch))
    }

    private func toggleDeviceSample(_ r: DeviceVoiceRow) {
        let key = "device:" + r.id
        if playing == key && speaker.speaking {
            MekaHaptics.tick()
            speaker.stop()
            playing = nil
            return
        }
        MekaHaptics.light()
        playing = key
        speaker.sampleOnDevice(r.id)
    }

    private func row(_ c: VoiceChoice) -> some View {
        row(c, choose: { if !c.selected { model.chooseVoice(c.id) } }, sample: { toggleSample(c) })
    }

    private func row(_ c: VoiceChoice, choose: @escaping () -> Void, sample: @escaping () -> Void) -> some View {
        let isPlaying = playing == c.id && speaker.speaking
        return HStack(spacing: MekaSpace.xs) {
            Button { choose() } label: {
                HStack(spacing: MekaSpace.xs) {
                    Circle().strokeBorder(c.selected ? palette.accent : palette.textTertiary, lineWidth: 1.5)
                        .background(Circle().fill(c.selected ? palette.accent : Color.clear).padding(4)) // rhythm: ok (the radio dot's own inset)
                        .frame(width: 18, height: 18)
                    VStack(alignment: .leading, spacing: MekaSpace.xxs) {
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
                Button { sample() } label: {
                    Text(isPlaying ? "■  Stop" : "▶  Sample").font(MekaType.itemMeta).foregroundStyle(palette.accent)
                        .contentTransition(.opacity)
                }
                .buttonStyle(MekaPressStyle())
                .help(isPlaying ? "Stop the sample" : "Hear \(c.label)")
            }
        }
        .padding(.horizontal, MekaSpace.m).padding(.vertical, MekaSpace.xs)
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
