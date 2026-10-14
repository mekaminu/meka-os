@preconcurrency import MekaKit
import AVFoundation
import SwiftUI

/// ▶ Play under a voice message MEKA's server kept (call assistant polish 8c). Play fetches the recording over a signed
/// request, plays it from memory (never saved) and lets it go when it ends, on Stop, or when the row goes away. A brass
/// bar fills as it plays, with "0:12 / 0:40". Motion: Play presses in (press style) with a light haptic and its label
/// cross-fades to "■ Stop" (tick haptic); the bar fills on a short ease; Reduce Motion: the bar steps, cross-fades.
///
/// Call notifications slice 7: the bar is the recording's waveform (the core reads it from the MP3's frames), brass up
/// to where it has got to. Click or drag along it to scrub (tick haptic as the drag starts, the time follows the
/// pointer, light haptic on letting go, which plays from there); VoiceOver reads "Voice message, 0:12 of 0:40" and can
/// adjust it. The bars rise from the middle line on the expand spring when they first appear (at once with Reduce Motion).
struct VoiceMessagePlayerView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let id: String
    let palette: MekaPalette
    @State private var playback = VoiceMessagePlayback()
    /// Where the pointer is on the waveform while dragging (0...1).
    @State private var scrub: Double?
    @State private var rise: Double = 0

    var body: some View {
        let rules = VoiceRecordingRules.shared
        let on = playback.state == .loading || playback.state == .playing
        VStack(alignment: .leading, spacing: MekaSpace.xxs) {
            HStack(spacing: MekaSpace.m) {
                Button(on ? rules.STOP : rules.PLAY) {
                    if on {
                        MekaHaptics.tick()
                        playback.stop()
                    } else {
                        MekaHaptics.light()
                        let id = id, model = model
                        playback.start(fetch: { await model.voiceMessageAudio(id) }, wave: { model.voiceWaveform($0) })
                    }
                }
                .buttonStyle(MekaPressStyle())
                .foregroundStyle(palette.accent)
                .contentTransition(.opacity)
                .accessibilityLabel(on ? "Stop the voice message" : "Play the voice message")
                if let line = line(rules) {
                    Text(line)
                        .font(MekaType.caption).monospacedDigit()
                        .foregroundStyle(playback.state == .failed ? palette.critical : palette.textTertiary)
                        .contentTransition(.opacity)
                }
            }
            if playback.state == .playing || playback.positionMs > 0 {
                if playback.wave.isEmpty {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(palette.hairline)
                            Capsule().fill(palette.accent)
                                .frame(width: geo.size.width * CGFloat(rules.fraction(positionMs: playback.positionMs, durationMs: playback.durationMs)))
                        }
                    }
                    .frame(height: 3)
                    .transition(.opacity)
                } else {
                    waveform(rules)
                        .transition(.opacity)
                }
            }
        }
        .padding(.top, MekaSpace.xxs)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.22), value: playback.positionMs)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: playback.state)
        .onDisappear { playback.stop() }
    }

    private func waveform(_ rules: VoiceRecordingRules) -> some View {
        let played = scrub ?? Double(rules.fraction(positionMs: playback.positionMs, durationMs: playback.durationMs))
        let bars = playback.wave
        let accent = palette.accent, rest = palette.textTertiary.opacity(0.35), lift = rise
        let shownMs = scrub.map { rules.seekMs(fraction: Float($0), durationMs: playback.durationMs) } ?? playback.positionMs
        return GeometryReader { geo in
            Canvas { ctx, size in
                let slot = size.width / CGFloat(bars.count)
                let barW = max(1.5, slot * 0.6)
                for (i, h) in bars.enumerated() {
                    let bh = max(barW, size.height * CGFloat(h * lift))
                    let rect = CGRect(x: CGFloat(i) * slot + (slot - barW) / 2, y: (size.height - bh) / 2, width: barW, height: bh)
                    let on = (Double(i) + 0.5) / Double(bars.count) <= played
                    ctx.fill(Path(roundedRect: rect, cornerRadius: barW / 2), with: .color(on ? accent : rest))
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { g in
                        if scrub == nil { MekaHaptics.tick() }
                        scrub = min(1, max(0, g.location.x / max(1, geo.size.width)))
                    }
                    .onEnded { g in
                        let f = min(1, max(0, g.location.x / max(1, geo.size.width)))
                        scrub = nil
                        MekaHaptics.light()
                        seek(f)
                    },
            )
        }
        .frame(height: 24)
        .onAppear {
            if reduceMotion { rise = 1 } else { withAnimation(MekaMotion.expand(reduced: false)) { rise = 1 } }
        }
        .accessibilityElement()
        .accessibilityLabel(rules.spoken(positionMs: shownMs, durationMs: playback.durationMs))
        .accessibilityAdjustableAction { direction in
            let step = 0.1
            switch direction {
            case .increment: seek(min(1, played + step))
            case .decrement: seek(max(0, played - step))
            @unknown default: break
            }
        }
    }

    private func seek(_ f: Double) {
        let id = id, model = model
        playback.seek(fraction: f, fetch: { await model.voiceMessageAudio(id) }, wave: { model.voiceWaveform($0) })
    }

    private func line(_ rules: VoiceRecordingRules) -> String? {
        switch playback.state {
        case .loading: return rules.LOADING
        case .failed: return rules.FAILED
        default:
            if let scrub {
                return rules.progressLine(positionMs: rules.seekMs(fraction: Float(scrub), durationMs: playback.durationMs), durationMs: playback.durationMs)
            }
            guard playback.positionMs > 0 || playback.durationMs > 0 else { return nil }
            return rules.progressLine(positionMs: playback.positionMs, durationMs: playback.durationMs)
        }
    }
}

/// One message's player, on the main actor (AVAudioPlayer never leaves it). Polls the position a few times a second
/// while playing, and notices the end the same way (no delegate, so nothing crosses isolation).
@MainActor
@Observable
final class VoiceMessagePlayback {
    enum State { case idle, loading, playing, failed }

    private(set) var state: State = .idle
    private(set) var positionMs: Int64 = 0
    private(set) var durationMs: Int64 = 0
    /// The recording's bars (slice 7), once fetched; empty draws the plain bar.
    private(set) var wave: [Double] = []
    @ObservationIgnored private var player: AVAudioPlayer?
    @ObservationIgnored private var task: Task<Void, Never>?
    /// The recording, kept in memory while the row is open so a scrub after the end doesn't fetch again.
    @ObservationIgnored private var data: Data?

    func start(fetch: @escaping @MainActor () async -> Data?, wave makeWave: @escaping @MainActor (Data) -> [Double], fromMs: Int64 = 0) {
        stop()
        state = .loading
        positionMs = fromMs
        task = Task { [weak self] in
            var bytes = self?.data
            if bytes == nil { bytes = await fetch() }
            guard let self, !Task.isCancelled, self.state == .loading else { return }
            guard let bytes, let p = try? AVAudioPlayer(data: bytes) else {
                self.state = .failed
                return
            }
            if self.data == nil {
                self.data = bytes
                self.wave = makeWave(bytes)
            }
            if fromMs > 0 { p.currentTime = Double(fromMs) / 1000 }
            guard p.play() else {
                self.state = .failed
                return
            }
            self.player = p
            self.durationMs = Int64(p.duration * 1000)
            self.state = .playing
            while !Task.isCancelled, let current = self.player, current === p {
                if !p.isPlaying {
                    // Ended: the bar stays full until it is played again.
                    self.positionMs = self.durationMs
                    self.player = nil
                    self.state = .idle
                    return
                }
                self.positionMs = Int64(p.currentTime * 1000)
                try? await Task.sleep(for: .milliseconds(200))
            }
        }
    }

    /// A click, a let-go drag or VoiceOver's adjust at `fraction` of the way: jump there while playing, else play from
    /// there (from the top if let go at the end).
    func seek(fraction: Double, fetch: @escaping @MainActor () async -> Data?, wave makeWave: @escaping @MainActor (Data) -> [Double]) {
        let rules = VoiceRecordingRules.shared
        if state == .playing, let p = player {
            let at = rules.seekMs(fraction: Float(fraction), durationMs: durationMs)
            p.currentTime = Double(at) / 1000
            positionMs = at
        } else if state != .loading {
            start(fetch: fetch, wave: makeWave, fromMs: rules.startFromMs(fraction: Float(fraction), durationMs: durationMs))
        }
    }

    func stop() {
        task?.cancel()
        task = nil
        player?.stop()
        player = nil
        positionMs = 0
        state = .idle
    }
}
