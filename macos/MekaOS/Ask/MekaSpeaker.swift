@preconcurrency import MekaKit
import AVFoundation
import Observation
import os

/// MEKA saying something aloud on the Mac (Weather and a voice, slice 8): the spoken morning brief, like the Fold's
/// MekaSpeaker. A line is said piece by piece (`SpeechRules.pieces`) in MEKA's voice (Amazon Polly through MEKA's own
/// server, `CoreModel.speechClip`: only MEKA's own words are sent), the next piece fetched while one plays; the Mac's
/// own voice (`AVSpeechSynthesizer`: the voice chosen in the picker, else the best installed English voice, at the Mac's
/// speed and pitch; `DeviceVoiceStore`) says whatever is left when a piece doesn't come in time, the server refuses, or
/// a clip won't play.
///
/// Main actor throughout; only Strings and Data cross to the core. Talk's `TalkController` speaks through it too
/// (`say(_:done:)` tells it when a line was said to the end, so the conversation moves on).
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
    /// This Mac's own voice, speed and pitch, re-read before each line (the picker may have changed them).
    @ObservationIgnored private var settings = DeviceVoiceSettings.companion.DEFAULT
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

    /// Says `text`, stopping anything already being said. `done` runs once the whole line has been said (in either
    /// voice), never when it was cut short by `stop()` or another line.
    func say(_ text: String, done: (@MainActor @Sendable () -> Void)? = nil) {
        stop()
        line += 1
        let id = line
        speaking = true
        loadDeviceVoice()
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
            guard !Task.isCancelled, id == self.line else { return }
            self.speaking = false
            done?()
        }
    }

    /// ▶ Sample in the voice picker: `VoicePickerRules.SAMPLE` in the server's `voice` (a Polly name), or in the Mac's
    /// own voice for "device" or when the clip can't be had or played. Stops anything already being said.
    func sample(_ voice: String) {
        stop()
        line += 1
        let id = line
        speaking = true
        loadDeviceVoice()
        let text = VoicePickerRules.shared.SAMPLE
        task = Task { [weak self] in
            guard let self else { return }
            var clip: Data?
            if voice != MekaVoiceRules.shared.DEVICE { clip = await self.model?.speechSample(voice) }
            guard !Task.isCancelled else { return }
            var played = false
            if let clip { played = await self.play(clip) }
            guard !Task.isCancelled else { return }
            if !played { await self.sayOnDevice(text) }
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

    /// ▶ Sample on one of the Mac's voices: `VoicePickerRules.SAMPLE` in that voice (`DeviceVoiceRules.AUTOMATIC`:
    /// MEKA's pick) at the Mac's speed and pitch. Stops anything already being said.
    func sampleOnDevice(_ id: String) {
        stop()
        line += 1
        let lineId = line
        speaking = true
        let saved = DeviceVoiceStore.load()
        settings = DeviceVoiceSettings(voice: id.isEmpty ? nil : id, rate: saved.rate, pitch: saved.pitch)
        voice = DeviceVoiceStore.voice(settings)
        task = Task { [weak self] in
            guard let self else { return }
            await self.sayOnDevice(VoicePickerRules.shared.SAMPLE)
            if lineId == self.line { self.speaking = false }
        }
    }

    private func loadDeviceVoice() {
        let s = DeviceVoiceStore.load()
        if voice == nil || s != settings {
            settings = s
            voice = DeviceVoiceStore.voice(s)
        }
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
        DeviceVoiceStore.style(u, settings)
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

/// This Mac's own voice, speed and pitch (Weather and a voice, slice 10): kept on the Mac only (UserDefaults), never
/// synced, since each device has its own voices. The rules are the core's `DeviceVoiceRules`, shared with the Fold.
enum DeviceVoiceStore {
    static let key = "meka.deviceVoice"

    static func load() -> DeviceVoiceSettings {
        DeviceVoiceRules.shared.decode(line: UserDefaults.standard.string(forKey: key))
    }

    static func save(_ s: DeviceVoiceSettings) {
        UserDefaults.standard.set(DeviceVoiceRules.shared.encode(s: s), forKey: key)
    }

    /// The Mac's voices as the picker lists them (each speaks on the Mac).
    static func voices() -> [DeviceVoice] {
        AVSpeechSynthesisVoice.speechVoices().map { v in
            DeviceVoice(name: v.identifier, displayName: v.name, language: v.language, quality: Int32(v.quality.rawValue),
                        engine: "Apple", needsNetwork: false, installed: true)
        }
    }

    /// The chosen voice while it is installed, else the best installed English one.
    static func voice(_ s: DeviceVoiceSettings) -> AVSpeechSynthesisVoice? {
        guard let pick = DeviceVoiceRules.shared.pick(voices: voices(), settings: s) else { return AVSpeechSynthesisVoice(language: "en-GB") }
        return AVSpeechSynthesisVoice(identifier: pick.name)
    }

    /// An utterance at the Mac's speed and pitch.
    static func style(_ u: AVSpeechUtterance, _ s: DeviceVoiceSettings) {
        u.rate = DeviceVoiceRules.shared.macRate(r: s.rate)
        u.pitchMultiplier = DeviceVoiceRules.shared.pitch(p: s.pitch)
    }
}

/// The synthesiser's delegate: says which line finished, as an Int, on the main actor.
nonisolated final class SpeechDone: NSObject, AVSpeechSynthesizerDelegate, @unchecked Sendable {
    private let lines = OSAllocatedUnfairLock<[ObjectIdentifier: Int]>(initialState: [:])
    /// Set once on the main actor before anything is spoken.
    nonisolated(unsafe) var onFinish: (@MainActor @Sendable (Int) -> Void)?

    func expect(_ utterance: ObjectIdentifier, id: Int) {
        lines.withLock { $0 = [utterance: id] }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        guard let id = lines.withLock({ $0[ObjectIdentifier(utterance)] }) else { return }
        guard let finish = onFinish else { return }
        Task { @MainActor in finish(id) }
    }
}

/// The clip player's delegate: resumes whoever waits on a clip, once, with whether it played to the end.
nonisolated final class ClipDone: NSObject, AVAudioPlayerDelegate, @unchecked Sendable {
    private let waiting = OSAllocatedUnfairLock<[ObjectIdentifier: CheckedContinuation<Bool, Never>]>(initialState: [:])

    func expect(_ player: ObjectIdentifier, _ c: CheckedContinuation<Bool, Never>) {
        waiting.withLock { $0[player] = c }
    }

    func finish(_ player: ObjectIdentifier, ok: Bool) {
        waiting.withLock { $0.removeValue(forKey: player) }?.resume(returning: ok)
    }

    /// Stopped: every clip still playing counts as not played (AVAudioPlayer's stop() calls no delegate).
    func cancelAll() {
        let all = waiting.withLock { w -> [CheckedContinuation<Bool, Never>] in
            let v = Array(w.values)
            w.removeAll()
            return v
        }
        all.forEach { $0.resume(returning: false) }
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        finish(ObjectIdentifier(player), ok: flag)
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: (any Error)?) {
        finish(ObjectIdentifier(player), ok: false)
    }
}
