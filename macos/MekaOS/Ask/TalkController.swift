@preconcurrency import MekaKit
import AVFoundation
import Observation
import os
import Speech

/// Talk to MEKA on the Mac (build plan V1, voice slice 3): runs the core's `TalkFlow` with the Mac's own speech, like
/// the Fold's TalkController.
///
/// Listening uses `SFSpeechRecognizer` with **on-device recognition required** (`requiresOnDeviceRecognition`):
/// nothing is recorded, kept or sent away as audio, and a Mac without English on the device says so
/// (`TalkProblem.noOnDevice`) rather than using Apple's servers. The Mac's recogniser doesn't decide when a question is
/// over, so `TalkEndpoint` does (a 1.5 s pause after words; 8 s of nothing is silence). Speaking uses MEKA's voice
/// (Amazon Polly through MEKA's own server, `MekaCore.speechClip`: only MEKA's own words are sent), piece by piece,
/// and falls back to `AVSpeechSynthesizer` with the best installed English voice (`TalkVoice.best`: British first,
/// premium, then enhanced) when MEKA's voice is off, used up for the month, offline or slow. Every change still goes through `MekaCore.doTalk` exactly as clicking a card would, with one undo bar.
/// Clicking the orb while MEKA speaks stops it and listens (barge-in by click, as on the Fold); otherwise it ends.
///
/// Main actor throughout. The microphone tap runs on the audio thread and touches only the recognition request and a
/// lock holding the level; recogniser and speech callbacks hop back with plain values (Strings, Bools, Ints). Kotlin
/// values cross into the core once each: the question and history in `talk`, the cards in `doTalk`.
@MainActor
@Observable
final class TalkController {
    private(set) var phase: TalkPhase = .ended
    /// Meka's voice level, 0 … 1, smoothed (`TalkOrb.smooth`).
    private(set) var level: Float = 0
    /// The live transcript while listening, then the question as heard.
    private(set) var heard = ""
    /// What MEKA last said aloud.
    private(set) var said = ""
    /// Why MEKA couldn't listen, shown under the orb until the next start.
    private(set) var problem: TalkProblem?

    var active: Bool { phase != .ended }

    /// An answer arrived: Ask shows it (the words and the cards, which can still be clicked).
    var onAnswer: (String, AskOutcome) -> Void = { _, _ in }
    /// The places, in the answer on screen, of cards a spoken yes is doing.
    var indicesOf: ([AskCard]) -> [Int] = { _ in [] }
    /// A spoken yes did these cards (their places): Ask folds them away.
    var onDid: ([Int]) -> Void = { _ in }

    @ObservationIgnored private var model: CoreModel?
    @ObservationIgnored private var session = TalkSession(phase: .ended, conversation: Conversation(turns: []), pending: [], questions: 0, endAfterSpeaking: false)
    /// Bumped by every start and stop, so a late answer or callback from an earlier conversation changes nothing.
    @ObservationIgnored private var generation = 0

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-GB"))
    @ObservationIgnored private var engine: AVAudioEngine?
    @ObservationIgnored private var request: SFSpeechAudioBufferRecognitionRequest?
    @ObservationIgnored private var task: SFSpeechRecognitionTask?
    @ObservationIgnored private var listenStarted = Date()
    @ObservationIgnored private var lastWords = Date()
    @ObservationIgnored private var heardAnything = false
    @ObservationIgnored private var clock: Task<Void, Never>?
    private let meter = OSAllocatedUnfairLock<Float>(initialState: -160)

    private let synthesizer = AVSpeechSynthesizer()
    private let speechDone = SpeechDone()
    private let clipDone = ClipDone()
    @ObservationIgnored private var voice: AVSpeechSynthesisVoice?
    /// Bumped by every line and every stop, so a line that was cut short doesn't move the conversation on.
    @ObservationIgnored private var utterance = 0
    @ObservationIgnored private var speaking: Task<Void, Never>?
    @ObservationIgnored private var player: AVAudioPlayer?
    /// The Mac's own lines being said, by id, resumed when each is over (or stopped).
    @ObservationIgnored private var deviceWaiting: [Int: CheckedContinuation<Void, Never>] = [:]
    @ObservationIgnored private var deviceLine = 0

    func attach(_ model: CoreModel) {
        self.model = model
        speechDone.onFinish = { [weak self] id in self?.deviceLineSaid(id) }
        synthesizer.delegate = speechDone
    }

    /// Starts a conversation: asks for the microphone and speech recognition the first time.
    func start() {
        guard !active else { return }
        generation += 1
        problem = nil
        heard = ""
        said = ""
        let gen = generation
        Task {
            guard await Self.allowed() else {
                if gen == generation { problem = .noPermission }
                return
            }
            guard gen == generation else { return }
            if voice == nil { voice = Self.bestVoice() }
            Task { await model?.warmVoice() } // MEKA's common lines in its voice, fetched once
            apply(TalkFlow.shared.start())
        }
    }

    /// The orb clicked: while MEKA speaks, stop and listen; otherwise end.
    func tapOrb() {
        switch phase {
        case .speaking: apply(TalkFlow.shared.bargeIn(s: session))
        case .ended: start()
        default: stop()
        }
    }

    /// Ask left the screen, MEKA went to the background, the mic clicked again: everything stops at once.
    func stop() {
        guard active else { return }
        generation += 1
        apply(TalkFlow.shared.stop(s: session))
    }

    /// A card clicked by hand while talking: it is no longer on offer aloud.
    func cardTapped(_ card: AskCard) {
        session = session.doCopy(phase: session.phase, conversation: session.conversation,
                                  pending: session.pending.filter { $0 != card },
                                  questions: session.questions, endAfterSpeaking: session.endAfterSpeaking)
    }

    private func apply(_ step: TalkStep) {
        session = step.session
        phase = session.phase
        step.effects.forEach(run)
    }

    private func run(_ effect: any TalkEffect) {
        if effect is TalkEffectListen {
            listen()
        } else if let ask = effect as? TalkEffectAsk {
            let question = ask.question
            heard = question
            level = 0
            let gen = generation
            Task {
                guard let model else { return }
                let out = await model.talk(question, history: ask.history)
                guard gen == generation else { return }
                guard let out else { return fail(.failed) }
                onAnswer(question, out)
                apply(TalkFlow.shared.answered(s: session, question: question, outcome: out, today: Self.today()))
            }
        } else if let doing = effect as? TalkEffectDo {
            let cards = doing.cards
            let indices = indicesOf(cards)
            let gen = generation
            Task {
                guard let model else { return }
                // Done is done: the cards fold away and the undo bar rises even if the talk was stopped meanwhile.
                guard let did = await model.doTalk(cards, indices: indices) else {
                    if gen == generation { fail(.failed) }
                    return
                }
                onDid(indices)
                guard gen == generation else { return }
                apply(TalkFlow.shared.did(s: session, done: did.done, lines: did.lines, failed: did.failed, today: Self.today()))
            }
        } else if let speak = effect as? TalkEffectSpeak {
            said = speak.text
            say(speak.text)
        } else if effect is TalkEffectStopSpeaking {
            stopSaying()
        } else if effect is TalkEffectEnd {
            stopListening()
            stopSaying()
            level = 0
        }
    }

    private func fail(_ p: TalkProblem) {
        problem = p
        generation += 1
        apply(TalkFlow.shared.stop(s: session))
    }

    private static func today() -> Int64 { CoreModel.epochDay(of: Date()) }

    // MARK: Listening (on the device only)

    private static func allowed() async -> Bool {
        let mic: Bool
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: mic = true
        case .notDetermined: mic = await AVCaptureDevice.requestAccess(for: .audio)
        default: mic = false
        }
        guard mic else { return false }
        let speech: SFSpeechRecognizerAuthorizationStatus
        switch SFSpeechRecognizer.authorizationStatus() {
        case .notDetermined:
            speech = await withCheckedContinuation { c in SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0) } }
        case let s: speech = s
        }
        return speech == .authorized
    }

    private func listen() {
        stopListening()
        guard let recognizer else { return fail(.noOnDevice) }
        // On the device or not at all: without English on this Mac, MEKA says so instead of using Apple's servers.
        guard recognizer.supportsOnDeviceRecognition else { return fail(.noOnDevice) }
        guard recognizer.isAvailable else { return fail(.busy) }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = true
        request.taskHint = .dictation
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { return fail(.busy) }
        Self.tap(input, format: format, into: request, meter: meter)
        engine.prepare()
        do { try engine.start() } catch {
            input.removeTap(onBus: 0)
            return fail(.busy)
        }
        self.engine = engine
        self.request = request
        heard = ""
        level = 0
        heardAnything = false
        listenStarted = Date()
        lastWords = listenStarted
        meter.withLock { $0 = -160 }
        let gen = generation
        recognizer.queue = .main
        // Holds the controller until the task is cancelled (every stop cancels it).
        task = recognizer.recognitionTask(with: request) { result, error in
            let text = result?.bestTranscription.formattedString
            let final = result?.isFinal ?? false
            let failed = error != nil
            Task { @MainActor in self.recognised(text, final: final, failed: failed, gen: gen) }
        }
        clock = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(TalkEndpoint.shared.TICK_MS))
                guard let self, gen == self.generation, self.phase == .listening else { return }
                self.tick()
            }
        }
    }

    /// The microphone's buffers go to the recogniser and its loudness to the meter. Nonisolated: the tap runs on the
    /// audio thread, so its closure must not belong to the main actor.
    nonisolated private static func tap(_ input: AVAudioInputNode, format: AVAudioFormat,
                                         into request: SFSpeechAudioBufferRecognitionRequest,
                                         meter: OSAllocatedUnfairLock<Float>) {
        nonisolated(unsafe) let req = request
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            req.append(buffer)
            let db = Self.dbfs(buffer)
            meter.withLock { $0 = db }
        }
    }

    /// A buffer's loudness in dB full scale (the first channel's RMS).
    nonisolated private static func dbfs(_ buffer: AVAudioPCMBuffer) -> Float {
        guard let samples = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return -160 }
        let n = Int(buffer.frameLength)
        var sum: Float = 0
        for i in 0..<n { sum += samples[i] * samples[i] }
        let rms = (sum / Float(n)).squareRoot()
        return rms <= 1e-8 ? -160 : max(-160, 20 * log10(rms))
    }

    private func tick() {
        let target = TalkOrb.shared.levelDbfs(db: meter.withLock { $0 })
        level = TalkOrb.shared.smooth(shown: level, target: target)
        let now = Date()
        let step = TalkEndpoint.shared.step(
            startedMs: Int64(listenStarted.timeIntervalSince1970 * 1000),
            heardAnything: heardAnything,
            lastWordsMs: Int64(lastWords.timeIntervalSince1970 * 1000),
            nowMs: Int64(now.timeIntervalSince1970 * 1000)
        )
        switch step {
        case .keep: break
        case .finish: finishListening()
        case .silence:
            stopListening()
            apply(TalkFlow.shared.silence(s: session))
        }
    }

    private func recognised(_ text: String?, final: Bool, failed: Bool, gen: Int) {
        guard gen == generation, phase == .listening else { return }
        if let text, !text.isEmpty {
            if text != heard { lastWords = Date() }
            heard = text
            heardAnything = true
        }
        if final {
            finishListening()
        } else if failed {
            // The recogniser gave up: what was heard is the question, nothing heard is silence.
            if heardAnything { finishListening() } else {
                stopListening()
                apply(TalkFlow.shared.silence(s: session))
            }
        }
    }

    /// The question is over: what was heard goes to the flow.
    private func finishListening() {
        let text = heard
        stopListening()
        level = 0
        apply(TalkFlow.shared.heard(s: session, utterance: text))
    }

    private func stopListening() {
        clock?.cancel()
        clock = nil
        task?.cancel()
        task = nil
        request?.endAudio()
        request = nil
        if let engine {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
        }
        engine = nil
    }

    // MARK: Speaking (on the device only)

    private static func bestVoice() -> AVSpeechSynthesisVoice? {
        let voices = AVSpeechSynthesisVoice.speechVoices()
        let candidates = voices.map { v in
            VoiceCandidate(name: v.identifier, language: v.language, quality: Int32(v.quality.rawValue),
                           needsNetwork: false, installed: true)
        }
        guard let best = TalkVoice.shared.best(voices: candidates) else { return AVSpeechSynthesisVoice(language: "en-GB") }
        return AVSpeechSynthesisVoice(identifier: best.name)
    }

    /// Says `text`: piece by piece (`SpeechRules.pieces`) in MEKA's voice, fetching the next piece while one plays;
    /// the Mac's own voice says whatever is left when a piece doesn't come in time, the server refuses, or a clip won't
    /// play. Only Strings and Data cross between here and the core.
    private func say(_ text: String) {
        stopSaying()
        utterance += 1
        let id = utterance
        let pieces = SpeechRules.shared.pieces(text: text)
        speaking = Task { [weak self] in
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
            guard !Task.isCancelled else { return }
            self.finishedSaying(id)
        }
    }

    /// Stops whatever is being said, in either voice.
    private func stopSaying() {
        utterance += 1
        speaking?.cancel()
        speaking = nil
        player?.stop()
        player = nil
        clipDone.cancelAll()
        synthesizer.stopSpeaking(at: .immediate)
        let waiting = deviceWaiting
        deviceWaiting = [:]
        waiting.values.forEach { $0.resume() }
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

    /// The synthesiser finished the Mac's line `id`.
    private func deviceLineSaid(_ id: Int) {
        deviceWaiting.removeValue(forKey: id)?.resume()
    }

    /// The line `id` finished: listen again, unless it was cut short since.
    private func finishedSaying(_ id: Int) {
        guard id == utterance, phase == .speaking else { return }
        apply(TalkFlow.shared.spoke(s: session))
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
