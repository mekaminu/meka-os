@preconcurrency import MekaKit
import AVFoundation
import Observation
import os

/// MEKA saying something aloud on the Mac (Weather and a voice, slice 8; Morning brief read aloud): the spoken morning brief (a long read), like the Fold's
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
    /// When MEKA's own voice was last heard (wall clock, ms), so a late answer in the same conversation holds.
    @ObservationIgnored private var lastMekaVoiceMs: Int64?

    func attach(_ model: CoreModel) {
        self.model = model
        speechDone.onFinish = { [weak self] id in self?.deviceLineSaid(id) }
        synthesizer.delegate = speechDone
    }

    /// Says `text`, stopping anything already being said. `done` runs once the whole line has been said (in either
    /// voice), never when it was cut short by `stop()` or another line. A long `reading` (the morning brief) waits
    /// longer for its first piece and, when one piece is late, lets the Mac say only that piece before MEKA's voice
    /// carries on (`SpeechRules.onMiss`). In a conversation where MEKA's voice has been heard (`SpeechRules.holds`) a
    /// late piece never switches voice at once: MEKA says "One moment…" in its own voice (from memory) and waits up to
    /// `SpeechRules.HOLD_AUDIO_MS` more before the Mac's voice takes over.
    func say(_ text: String, reading: Bool = false, done: (@MainActor @Sendable () -> Void)? = nil) {
        stop()
        line += 1
        let id = line
        speaking = true
        loadDeviceVoice()
        task = Task { [weak self] in
            guard let self else { return }
            await self.speakNow(text, reading: reading)
            guard !Task.isCancelled, id == self.line else { return }
            self.speaking = false
            done?()
        }
    }

    /// "Play my messages" in Talk (call assistant polish 8c): says and plays the playlist's steps in order — MEKA's
    /// lines as `say` would, each caller's recording (MEKA's server through `CoreModel.voiceMessageAudio`, played from
    /// memory), or the step's words when it can't be had or played. `done` runs once all of it is over, never when
    /// `stop()` cut it short. Only Strings and Data cross to the core.
    func playlist(_ steps: [PlaylistStep], done: (@MainActor @Sendable () -> Void)? = nil) {
        stop()
        line += 1
        let id = line
        speaking = true
        loadDeviceVoice()
        task = Task { [weak self] in
            guard let self else { return }
            for step in steps {
                guard !Task.isCancelled else { return }
                switch step {
                case .say(let text):
                    await self.speakNow(text, reading: false)
                case .recording(let heldId, let otherwise):
                    var played = false
                    if let data = await self.model?.voiceMessageAudio(heldId), !Task.isCancelled {
                        played = await self.play(data)
                    }
                    guard !Task.isCancelled else { return }
                    if !played { await self.speakNow(otherwise, reading: false) }
                }
            }
            guard !Task.isCancelled, id == self.line else { return }
            self.speaking = false
            done?()
        }
    }

    /// Says `text` piece by piece in MEKA's voice, the Mac's own for what's left; returns once said (or cancelled).
    private func speakNow(_ text: String, reading: Bool) async {
        let pieces = SpeechRules.shared.pieces(text: text)
        do {
            var held = false
            var i = 0
            var next = await self.fetch(pieces, 0, reading: reading)
            while i < pieces.count {
                var clip: Data?
                if let p = next {
                    clip = await self.clipOf(p, reading: reading)
                    if clip == nil && p.hold && !p.box.done && !Task.isCancelled {
                        // Late: "One moment…" in MEKA's voice (once a line), then the rest of the piece's budget.
                        if !held {
                            held = true
                            if let line = await self.model?.speechHoldClip() { _ = await self.play(line) }
                        }
                        clip = await p.clip.value
                    }
                }
                guard !Task.isCancelled else { break }
                if clip != nil { self.lastMekaVoiceMs = Self.nowMs() } // heard in this conversation
                next = await self.fetch(pieces, i + 1, reading: reading)
                if let clip, await self.play(clip) {
                    self.lastMekaVoiceMs = Self.nowMs()
                    i += 1
                    continue
                }
                guard !Task.isCancelled else { break }
                let resting = await self.model?.speechResting() ?? true
                if SpeechRules.shared.onMiss(reading: reading, resting: resting) == .restOnDevice { break }
                await self.sayOnDevice(pieces[i])
                guard !Task.isCancelled else { break }
                i += 1
            }
            next?.clip.cancel()
            guard !Task.isCancelled else { return }
            if i < pieces.count { await self.sayOnDevice(pieces[i...].joined(separator: " ")) }
        }
    }

    /// One piece's clip on its way: when it was asked for, whether it is the line's first, and whether it may hold.
    private struct Pending {
        let clip: Task<Data?, Never>
        let box: ClipBox
        let startedAt: ContinuousClock.Instant
        let first: Bool
        let hold: Bool
    }

    /// Whether a piece's clip has come back (set on the main actor by the clip's own task).
    @MainActor private final class ClipBox {
        var done = false
    }

    /// Asks for piece `k` (nil past the end); it may hold when MEKA's voice was heard in this conversation.
    private func fetch(_ pieces: [String], _ k: Int, reading: Bool) async -> Pending? {
        guard k < pieces.count else { return nil }
        let piece = pieces[k]
        let first = k == 0
        let resting = await model?.speechResting() ?? true
        let heard = lastMekaVoiceMs.map { KotlinLong(longLong: $0) }
        let hold = SpeechRules.shared.holds(reading: reading, resting: resting, lastMekaVoiceMs: heard, nowMs: Self.nowMs())
        let box = ClipBox()
        let clip = Task { [weak self] () -> Data? in
            let d = await self?.model?.speechClip(piece, first: first, reading: reading, hold: hold)
            box.done = true
            return d
        }
        return Pending(clip: clip, box: box, startedAt: ContinuousClock.now, first: first, hold: hold)
    }

    /// A piece's clip: awaited to the end when it can't hold; else only for its usual wait (nil if still on its way,
    /// and `box.done` false says so).
    private func clipOf(_ p: Pending, reading: Bool) async -> Data? {
        guard p.hold else { return await p.clip.value }
        let wait = Duration.milliseconds(SpeechRules.shared.waitMs(first: p.first, reading: reading))
        while !p.box.done && ContinuousClock.now < p.startedAt + wait && !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(50))
        }
        return p.box.done ? await p.clip.value : nil
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

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

/// One step of "play my messages" as plain Swift values (the core's `PlayStep`, read once on the main actor).
enum PlaylistStep: Sendable {
    case say(String)
    case recording(heldId: String, otherwise: String)

    static func of(_ steps: [PlayStep]) -> [PlaylistStep] {
        steps.compactMap { step in
            if let say = step as? PlayStepSay { return .say(say.text) }
            if let rec = step as? PlayStepRecording { return .recording(heldId: rec.heldId, otherwise: rec.otherwise) }
            return nil
        }
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
        // The utterance itself isn't Sendable; only its identity crosses into the lock's closure.
        let key = ObjectIdentifier(utterance)
        guard let id = lines.withLock({ $0[key] }) else { return }
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
