@preconcurrency import MekaKit
import AppKit
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
/// and falls back to `AVSpeechSynthesizer` with the best installed English voice (the one chosen in the voice picker, else
/// `TalkVoice.best`: British first, premium, then enhanced; at the Mac's speed and pitch) when MEKA's voice is off, used up for the month, offline or slow. Every change still goes through `MekaCore.doTalk` exactly as clicking a card would, with one undo bar.
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
    /// "Listen when I open MEKA": when the opening's listening began, and what to do if nothing is said in time.
    @ObservationIgnored private var openAt: Date?
    @ObservationIgnored private var openLapsed: (() -> Void)?
    /// The conversation the opening's window belongs to (any later start or stop moves `generation` on and ends it).
    @ObservationIgnored private var openGen = -1
    /// The quiet chime that marks listening on open (kept while it plays).
    @ObservationIgnored private var chimeSound: NSSound?
    /// After the chime, so the recogniser doesn't hear it.
    private static let chimeGapMs = 220

    /// MEKA's voice, else the Mac's own (the brief's speaker too; re-reads the picker's Mac voice before each line).
    private let speaker = MekaSpeaker()
    /// Bumped by every line and every stop, so a line that was cut short doesn't move the conversation on.
    @ObservationIgnored private var utterance = 0

    func attach(_ model: CoreModel) {
        self.model = model
        speaker.attach(model)
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
            Task { await model?.warmVoice() } // MEKA's common lines in its voice, fetched once
            apply(TalkFlow.shared.start())
        }
    }

    /// "Listen when I open MEKA" (the Mac's slice of Talk without tapping the mic): checks the room for 300 ms (a level,
    /// never audio, nothing kept); too noisy → "Too noisy — click to talk" under the resting orb (a click starts as
    /// usual); else a quiet chime and listening, and if no speech has begun within 6 s, listening stops and `onLapsed`
    /// (Today slides back). Only called when the microphone and speech recognition are already allowed: nothing asks.
    func startOnOpen(onLapsed: @escaping () -> Void) {
        guard !active else { return }
        generation += 1
        problem = nil
        let gen = generation
        Task {
            let rms = await Self.roomRms()
            guard gen == generation, !active else { return }
            if let rms, TalkOnOpenRules.shared.tooNoisyAt(rms: rms) {
                problem = .tooNoisy
                return
            }
            chime()
            try? await Task.sleep(for: .milliseconds(Self.chimeGapMs))
            guard gen == generation, !active else { return }
            start()
            openGen = generation
            openAt = nil
            openLapsed = onLapsed
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
        generation += 1 // also drops a room check or chime still on its way ("Listen when I open MEKA")
        openAt = nil
        openLapsed = nil
        guard active else { return }
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
        } else if effect is TalkEffectPlayMessages {
            playMessages()
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
        openAt = nil
        openLapsed = nil
        generation += 1
        apply(TalkFlow.shared.stop(s: session))
    }

    private static func today() -> Int64 { CoreModel.epochDay(of: Date()) }

    // MARK: Listening (on the device only)

    /// Asks for the microphone and speech recognition if not yet decided (the Talk sheet's Turn on asks here too).
    static func allowed() async -> Bool {
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
        if openLapsed != nil && openGen == generation && openAt == nil { openAt = listenStarted } // the window starts now
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

    /// The room check: the microphone for `TalkOnOpenRules.ROOM_CHECK_MS`, summed into one full-scale RMS (a number,
    /// never audio, nothing kept). Nil when it couldn't be measured (then MEKA listens).
    private static func roomRms() async -> Double? {
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { return nil }
        let sums = OSAllocatedUnfairLock<RoomSums>(initialState: RoomSums())
        Self.tapRoom(input, format: format, into: sums)
        engine.prepare()
        do { try engine.start() } catch {
            input.removeTap(onBus: 0)
            return nil
        }
        try? await Task.sleep(for: .milliseconds(Int(TalkOnOpenRules.shared.ROOM_CHECK_MS)))
        engine.stop()
        input.removeTap(onBus: 0)
        let total = sums.withLock { $0 }
        return total.count == 0 ? nil : (total.squares / Double(total.count)).squareRoot()
    }

    /// The room check's buffers: only the sum of squares and the count are kept. Nonisolated: the audio thread runs it.
    nonisolated private static func tapRoom(_ input: AVAudioInputNode, format: AVAudioFormat,
                                             into sums: OSAllocatedUnfairLock<RoomSums>) {
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            guard let samples = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return }
            let n = Int(buffer.frameLength)
            var sum = 0.0
            for i in 0..<n { sum += Double(samples[i]) * Double(samples[i]) }
            sums.withLock { $0.squares += sum; $0.count += n }
        }
    }

    /// A quiet chime: the system's Tink at a low volume.
    private func chime() {
        let sound = NSSound(named: NSSound.Name("Tink"))
        sound?.volume = 0.3
        sound?.play()
        chimeSound = sound
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
        if let at = openAt, let lapsed = openLapsed, openGen == generation {
            if heardAnything {
                openAt = nil
                openLapsed = nil
            } else if TalkOnOpenRules.shared.windowLapsed(startedAtMs: Int64(at.timeIntervalSince1970 * 1000),
                                                         nowMs: Int64(now.timeIntervalSince1970 * 1000),
                                                         speechBegan: false) {
                stop()
                lapsed()
                return
            }
        }
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

    // MARK: Speaking (MekaSpeaker)

    /// Says `text` through `MekaSpeaker`: piece by piece in MEKA's voice, the Mac's own voice for whatever is left.
    /// Only Strings and Data cross between here and the core.
    private func say(_ text: String) {
        utterance += 1
        let id = utterance
        speaker.say(text) { [weak self] in self?.finishedSaying(id) }
    }

    /// "Play my messages" (call assistant polish 8c): each voice message introduced in MEKA's voice, then the caller's
    /// recording from MEKA's server (played from memory) or their words; then MEKA listens again.
    private func playMessages() {
        let steps = model?.voicePlaylist() ?? []
        if case .say(let first) = steps.first { said = first }
        utterance += 1
        let id = utterance
        speaker.playlist(steps) { [weak self] in self?.finishedSaying(id) }
    }

    /// Stops whatever is being said, in either voice.
    private func stopSaying() {
        utterance += 1
        speaker.stop()
    }

    /// The line `id` finished: listen again, unless it was cut short since.
    private func finishedSaying(_ id: Int) {
        guard id == utterance, phase == .speaking else { return }
        apply(TalkFlow.shared.spoke(s: session))
    }
}

/// The room check's running sums (Sendable, so the audio thread's tap can add to them under a lock).
struct RoomSums: Sendable {
    var squares = 0.0
    var count = 0
}
