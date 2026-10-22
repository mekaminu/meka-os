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
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.voicePicker?.googleLine)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: showAll)
        .onAppear {
            speaker.attach(model)
            deviceVoices = DeviceVoiceStore.voices()
        }
        .onDisappear { speaker.stop() }
        .task { await model.refreshVoicePicker() }
        .task { await model.refreshLive() }
    }

    /// Ada's Live mode, slice 3: Live Off · On (synced, off until he turns it on), the line under it (the month's meter
    /// or why Live can't run), what goes to Google, and while on Ada's Live voices, each with ▶ Sample in Google's
    /// studio voice of that name (never another voice standing in). The voices unfold on the expand spring; lines
    /// cross-fade; Reduce Motion: cross-fades.
    private func liveBlock(_ lv: LiveSettingsView) -> some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(lv.title.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textSecondary)
            Text(lv.intro).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            Picker(lv.title, selection: Binding(get: { lv.on }, set: { model.setLiveOn($0) })) {
                Text(LiveTalkRules.shared.OFF_LABEL).tag(false)
                Text(LiveTalkRules.shared.ON_LABEL).tag(true)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(maxWidth: 220)
            .accessibilityLabel("Ada's Live mode")
            Text(lv.line).font(MekaType.caption).foregroundStyle(lv.on ? palette.accent : palette.textTertiary)
                .id(lv.line)
                .transition(.opacity)
                .fixedSize(horizontal: false, vertical: true)
            if let meter = lv.meterLine {
                Text(meter).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .transition(.opacity)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(lv.sharing).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            if lv.showVoices {
                VStack(alignment: .leading, spacing: MekaSpace.xs) {
                    Text(LiveTalkRules.shared.VOICE_TITLE.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                        .foregroundStyle(palette.textSecondary)
                        .padding(.top, MekaSpace.xs)
                    Text(LiveTalkRules.shared.SAMPLE_NOTE).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                    ForEach(Array(lv.voices.enumerated()), id: \.element.name) { i, v in
                        row(VoiceChoice(id: "live:" + v.name, label: v.name, detail: v.detail, selected: v.selected, sample: true, group: nil),
                            choose: { if !v.selected { model.setLiveVoice(v.name) } }, sample: { toggleLiveSample(v.name) })
                            .staggeredAppear(i)
                    }
                    if liveSampleFailed {
                        Text(LiveTalkRules.shared.SAMPLE_FAILED).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .transition(.opacity)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.vertical, MekaSpace.xs)
        .animation(MekaMotion.expand(reduced: reduceMotion), value: lv.showVoices)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: lv.line)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: liveSampleFailed)
    }

    /// A Live voice's sample that couldn't play says so under the rows.
    @State private var liveSampleFailed = false

    private func toggleLiveSample(_ name: String) {
        let key = "live:" + name
        if playing == key && speaker.speaking {
            MekaHaptics.tick()
            speaker.stop()
            playing = nil
            return
        }
        MekaHaptics.light()
        playing = key
        liveSampleFailed = false
        speaker.sampleLive(name) { ok in
            if !ok, playing == key { liveSampleFailed = true }
        }
    }

    /// The companion, slice 3: how Navilife addresses him in Ask, Talk and Hello, by name or "sir", on every device.
    private var callMe: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(CompanionRules.shared.CALL_TITLE.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textSecondary)
            Picker(CompanionRules.shared.CALL_TITLE, selection: Binding(get: { model.callsSir }, set: { model.setCallsSir($0) })) {
                Text(CompanionRules.shared.CALL_BY_NAME).tag(false)
                Text(CompanionRules.shared.CALL_SIR_LABEL).tag(true)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(maxWidth: 220)
            Text(CompanionRules.shared.callLine(sir: model.callsSir)).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .id(model.callsSir)
                .transition(.opacity)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, MekaSpace.xs)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.callsSir)
    }

    /// The companion, slice 5: what Navilife remembers of earlier chats, with Remember / Don't remember and Forget all.
    private var memory: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(CompanionMemoryRules.shared.TITLE.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textSecondary)
            Picker(CompanionMemoryRules.shared.TITLE, selection: Binding(get: { model.remembersChats }, set: { model.setRemembersChats($0) })) {
                Text(CompanionMemoryRules.shared.ON_LABEL).tag(true)
                Text(CompanionMemoryRules.shared.OFF_LABEL).tag(false)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .frame(maxWidth: 260)
            Text(model.remembersChats ? CompanionMemoryRules.shared.onLine(count: Int32(model.memoryRows.count)) : CompanionMemoryRules.shared.OFF_LINE)
                .font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            if model.remembersChats && model.memoryRows.isEmpty {
                Text(CompanionMemoryRules.shared.EMPTY).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            ForEach(model.memoryRows, id: \.self) { row in
                Text(row).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            if !model.memoryRows.isEmpty {
                Button(CompanionMemoryRules.shared.FORGET_ALL) { model.forgetChats() }
                    .buttonStyle(.link)
            }
        }
        .padding(.vertical, MekaSpace.xs)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.memoryRows)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.remembersChats)
    }

    /// The orb speed pass, slice 2: the last 20 hellos and answers, how long each took to start and where the wait
    /// went (this Mac only, times only), with Clear.
    private var speed: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(TurnSpeedRules.shared.TITLE.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                .foregroundStyle(palette.textSecondary)
            Text(TurnSpeedRules.shared.INTRO).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            if let summary = model.speedSummary {
                Text(summary).font(MekaType.itemMeta).foregroundStyle(palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            if model.speedRows.isEmpty {
                Text(TurnSpeedRules.shared.EMPTY).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            ForEach(Array(model.speedRows.enumerated()), id: \.offset) { _, row in
                Text(row).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity)
            }
            if !model.speedRows.isEmpty {
                Button(TurnSpeedRules.shared.CLEAR) { model.clearSpeed() }
                    .buttonStyle(.link)
            }
            if model.serverSpeedShown {
                // Talking to Ada like a conversation, slice 2: the server's own timings of the last day's answers.
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    Text(ServerSpeedRules.shared.TITLE).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                    Text(ServerSpeedRules.shared.INTRO).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                    ForEach(Array(model.serverSpeedLines.enumerated()), id: \.offset) { _, line in
                        Text(line).font(MekaType.caption).foregroundStyle(palette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if let empty = model.serverSpeedEmpty {
                        Text(empty).font(MekaType.caption).foregroundStyle(palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.top, MekaSpace.xs)
                .transition(.opacity)
            }
        }
        .padding(.vertical, MekaSpace.xs)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.speedRows)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: model.serverSpeedLines)
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: MekaSpace.xs) {
            Text(VoicePickerRules.shared.TITLE).font(MekaType.upNextTitle).staggeredAppear(0)
            Text(VoicePickerRules.shared.INTRO).font(MekaType.itemMeta).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .staggeredAppear(0)
            callMe.staggeredAppear(1)
            memory.staggeredAppear(2)
            speed.staggeredAppear(3)
            if let lv = model.liveSettings { liveBlock(lv).staggeredAppear(4) }
            if let v = model.voicePicker {
                ForEach(Array(v.choices.enumerated()), id: \.element.id) { i, c in
                    // Each provider's voices under their heading (Google's British, American, then Polly's).
                    if let group = c.group, group != (i > 0 ? v.choices[i - 1].group : nil) {
                        Text(group.uppercased()).font(MekaType.sectionLabel).tracking(MekaType.sectionLabelTracking)
                            .foregroundStyle(palette.textSecondary)
                            .padding(.top, i == 0 ? MekaSpace.xs : MekaSpace.m)
                            .staggeredAppear(i + 1)
                    }
                    row(c).staggeredAppear(i + 1)
                }
                VStack(alignment: .leading, spacing: MekaSpace.xxs) {
                    if let status = v.statusLine {
                        Text(status).font(MekaType.caption).foregroundStyle(palette.accent).transition(.opacity)
                    }
                    if let google = v.googleLine {
                        Text(google).font(MekaType.caption).foregroundStyle(palette.textSecondary).transition(.opacity)
                    }
                    if let sampleLine = v.sampleLine {
                        Text(sampleLine).font(MekaType.caption).foregroundStyle(palette.textTertiary).transition(.opacity)
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
            row(VoiceChoice(id: "device:" + r.id, label: r.label, detail: r.detail, selected: r.selected, sample: true, group: nil),
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
                    HStack(spacing: MekaSpace.xs) {
                        if isPlaying {
                            SamplePulse(color: palette.accent, reduceMotion: reduceMotion).transition(.opacity)
                        }
                        Text(isPlaying ? "■  Stop" : "▶  Sample").font(MekaType.itemMeta).foregroundStyle(palette.accent)
                            .contentTransition(.opacity)
                    }
                    .animation(MekaMotion.appear(reduced: reduceMotion), value: isPlaying)
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

/// Natural voice, slice 2: three thin brass bars that rise and fall out of step while a sample plays (the Fold's
/// `SamplePulse`). Reduce Motion: the bars stand still at three heights.
private struct SamplePulse: View {
    let color: Color
    let reduceMotion: Bool

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            HStack(spacing: 2) { // rhythm: ok (a glyph's own spacing)
                ForEach(0..<3, id: \.self) { i in
                    Capsule().fill(color)
                        .frame(width: 2, height: 14)
                        .scaleEffect(x: 1, y: Self.level(i, t: t, still: reduceMotion), anchor: .center)
                }
            }
            .frame(height: 14)
        }
        .accessibilityHidden(true)
    }

    /// 0.35…1 on a 0.84 s cycle, each bar 0.14 s behind the one before; still heights under Reduce Motion.
    static func level(_ i: Int, t: TimeInterval, still: Bool) -> CGFloat {
        if still { return [0.55, 1, 0.7][i] }
        let phase = (t - Double(i) * 0.14) / 0.84 * 2 * .pi
        return 0.35 + 0.65 * CGFloat((1 - cos(phase)) / 2)
    }
}
