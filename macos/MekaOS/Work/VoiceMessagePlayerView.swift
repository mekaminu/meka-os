@preconcurrency import MekaKit
import AVFoundation
import SwiftUI

/// ▶ Play under a voice message MEKA's server kept (call assistant polish 8c). Play fetches the recording over a signed
/// request, plays it from memory (never saved) and lets it go when it ends, on Stop, or when the row goes away. A brass
/// bar fills as it plays, with "0:12 / 0:40". Motion: Play presses in (press style) with a light haptic and its label
/// cross-fades to "■ Stop" (tick haptic); the bar fills on a short ease; Reduce Motion: the bar steps, cross-fades.
struct VoiceMessagePlayerView: View {
    @Environment(CoreModel.self) private var model
    @Environment(\.mekaReduceMotion) private var reduceMotion
    let id: String
    let palette: MekaPalette
    @State private var playback = VoiceMessagePlayback()

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
                        playback.start { await model.voiceMessageAudio(id) }
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
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(palette.hairline)
                        Capsule().fill(palette.accent)
                            .frame(width: geo.size.width * CGFloat(rules.fraction(positionMs: playback.positionMs, durationMs: playback.durationMs)))
                    }
                }
                .frame(height: 3)
                .transition(.opacity)
            }
        }
        .padding(.top, MekaSpace.xxs)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.22), value: playback.positionMs)
        .animation(MekaMotion.appear(reduced: reduceMotion), value: playback.state)
        .onDisappear { playback.stop() }
    }

    private func line(_ rules: VoiceRecordingRules) -> String? {
        switch playback.state {
        case .loading: return rules.LOADING
        case .failed: return rules.FAILED
        default:
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
    @ObservationIgnored private var player: AVAudioPlayer?
    @ObservationIgnored private var task: Task<Void, Never>?

    func start(fetch: @escaping @MainActor () async -> Data?) {
        stop()
        state = .loading
        task = Task { [weak self] in
            let data = await fetch()
            guard let self, !Task.isCancelled, self.state == .loading else { return }
            guard let data, let p = try? AVAudioPlayer(data: data), p.play() else {
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

    func stop() {
        task?.cancel()
        task = nil
        player?.stop()
        player = nil
        positionMs = 0
        state = .idle
    }
}
