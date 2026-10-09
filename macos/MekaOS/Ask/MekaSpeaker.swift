@preconcurrency import MekaKit
import AVFoundation
import Observation

/// MEKA saying something aloud on the Mac (Weather and a voice, slice 8): the spoken morning brief, like the Fold's
/// MekaSpeaker. A line is said piece by piece (`SpeechRules.pieces`) in MEKA's voice (Amazon Polly through MEKA's own
/// server, `CoreModel.speechClip`: only MEKA's own words are sent), the next piece fetched while one plays; the Mac's
/// own voice (`AVSpeechSynthesizer`, the best installed English voice by `TalkVoice.best`) says whatever is left when a
/// piece doesn't come in time, the server refuses, or a clip won't play.
///
/// Main actor throughout; only Strings and Data cross to the core. The delegates (`SpeechDone`, `ClipDone`) are
/// Talk's, shared.
@MainActor
@Observable
final class MekaSpeaker {
    /// Something is being said, in either voice.
    private(set) var speaking = false

    @ObservationIgnored private var model: CoreModel?
    private let synthesizer = AVSpeechSynthesizer()
    private let speechDone = SpeechDone()
    private let clipDone = ClipDone()
    @ObservationIgnored private var voice: AVSpeechSynthesisVoice?
    /// Bumped by every line and every stop, so a line cut short never reports that it finished.
    @ObservationIgnored private var line = 0
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var player: AVAudioPlayer?
    @ObservationIgnored private var deviceWaiting: [Int: CheckedContinuation<Void, Never>] = [:]
    @ObservationIgnored private var deviceLine = 0

    func attach(_ model: CoreModel) {
        self.model = model
        speechDone.onFinish = { [weak self] id in self?.deviceLineSaid(id) }
        synthesizer.delegate = speechDone
    }

    /// Says `text`, stopping anything already being said.
    func say(_ text: String) {
        stop()
        line += 1
        let id = line
        speaking = true
        if voice == nil { voice = Self.bestVoice() }
        let pieces = SpeechRules.shared.pieces(text: text)
        task = Task { [weak self] in
            guard let self else { return }
            var i = 0
            var next: Task<Data?, Never>?
            if let first = pieces.first { next = Task { await self.model?.speechClip(first, first: true) } }
            while i < pieces.count {
                guard let clip = await next?.value, !Task.isCancelled else { break }
                if i + 1 < pieces.count {
                    let piece = pieces[i + 1]
                    next = Task { await self.model?.speechClip(piece, first: false) }
                } else {
                    next = nil
                }
                guard await self.play(clip) else { break }
                i += 1
            }
            next?.cancel()
            guard !Task.isCancelled else { return }
            if i < pieces.count { await self.sayOnDevice(pieces[i...].joined(separator: " ")) }
            if id == self.line { self.speaking = false }
        }
    }

    /// Stops whatever is being said, in either voice.
    func stop() {
        line += 1
        task?.cancel()
        task = nil
        player?.stop()
        player = nil
        clipDone.cancelAll()
        synthesizer.stopSpeaking(at: .immediate)
        let waiting = deviceWaiting
        deviceWaiting = [:]
        waiting.values.forEach { $0.resume() }
        speaking = false
    }

    private static func bestVoice() -> AVSpeechSynthesisVoice? {
        let voices = AVSpeechSynthesisVoice.speechVoices()
        let candidates = voices.map { v in
            VoiceCandidate(name: v.identifier, language: v.language, quality: Int32(v.quality.rawValue),
                           needsNetwork: false, installed: true)
        }
        guard let best = TalkVoice.shared.best(voices: candidates) else { return AVSpeechSynthesisVoice(language: "en-GB") }
        return AVSpeechSynthesisVoice(identifier: best.name)
    }

    /// Plays one MP3 clip to the end. False when it can't be played (the Mac's voice takes over) or was stopped.
    private func play(_ data: Data) async -> Bool {
        guard let p = try? AVAudioPlayer(data: data) else { return false }
        player = p
        p.delegate = clipDone
        let key = ObjectIdentifier(p)
        let ok = await withCheckedContinuation { (c: CheckedContinuation<Bool, Never>) in
            clipDone.expect(key, c)
            if !p.play() { clipDone.finish(key, ok: false) }
        }
        if player === p { player = nil }
        return ok
    }

    /// Says `text` with the Mac's own voice and waits until it has been said (or stopped).
    private func sayOnDevice(_ text: String) async {
        deviceLine += 1
        let id = deviceLine
        let u = AVSpeechUtterance(string: text)
        u.voice = voice
        speechDone.expect(ObjectIdentifier(u), id: id)
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            deviceWaiting[id] = c
            synthesizer.speak(u)
        }
    }

    private func deviceLineSaid(_ id: Int) {
        deviceWaiting.removeValue(forKey: id)?.resume()
    }
}
