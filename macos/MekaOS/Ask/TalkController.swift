@preconcurrency import MekaKit
import AppKit
import AVFoundation
import CoreAudio
import Network
import Observation
import os
import Speech

/// Talk to MEKA on the Mac (build plan V1, voice slice 3): runs the core's `TalkFlow` with the Mac's own speech, like
/// the Fold's TalkController.
///
/// Listening uses `SFSpeechRecognizer`: since the mic fix (steps 3–4, 2026-10-17; ADR-006 addendum, Meka's yes) Apple's
/// online recognition whenever the Mac has a connection (`MacSignal`), on-device recognition without one
/// (`ListenRules.engine`; a failure online before any words listens again on the device). Nothing is recorded or kept.
/// The Mac's recogniser doesn't decide when a question is over, so `TalkEndpoint` does (a 0.6 s pause after his last
/// word or sound; 8 s of nothing is silence). Speaking uses MEKA's voice
/// (Amazon Polly through MEKA's own server, `MekaCore.speechClip`: only MEKA's own words are sent), piece by piece,
/// and falls back to `AVSpeechSynthesizer` with the best installed English voice (the one chosen in the voice picker, else
/// `TalkVoice.best`: British first, premium, then enhanced; at the Mac's speed and pitch) when MEKA's voice is off, used up for the month, offline or slow. Every change still goes through `MekaCore.doTalk` exactly as clicking a card would, with one undo bar.
/// Clicking the orb while MEKA speaks stops it and listens (as on the Fold); otherwise it ends. With "Talk over MEKA" on
/// (the default, `BargeInRules`; `MacTalkOver`) talking over MEKA does the same: while a line is said a second engine
/// reads only the microphone's level (the loudest buffer since the last look, every 40 ms; no recogniser, nothing kept)
/// and the core decides when Meka's voice has clearly risen over MEKA's own. It is stopped before the recogniser listens.
///
/// Main actor throughout. The microphone tap runs on the audio thread and touches only the recognition request and a
/// lock holding the level; recogniser and speech callbacks hop back with plain values (Strings, Bools, Ints). Kotlin
/// values cross into the core once each: the question and history in `talk`, the cards in `doTalk`.
@MainActor
@Observable
final class TalkController {
    private(set) var phase: TalkPhase = .ended {
        // Speaking up: Ada never speaks up over a conversation.
        didSet { model?.talkActive = phase != .ended }
    }
    /// Meka's voice level, 0 … 1, smoothed (`TalkOrb.smooth`).
    private(set) var level: Float = 0
    /// The live transcript while listening, then the question as heard.
    private(set) var heard = ""
    /// What MEKA last said aloud.
    private(set) var said = ""
    /// "Hello, Navilife" slice 7: when the last hello's entrance began (the orb scatters, gathers and sends its shockwave).
    private(set) var entranceAt: Date?
    /// "Hello, Navilife" slice 8: the greeting's cards (they rise under the orb as each sentence starts), and when its
    /// voice began; cleared once he answers.
    private(set) var helloCards: [HelloCardLine] = []
    private(set) var helloSpokeAt: Date?
    /// Why MEKA couldn't listen, shown under the orb until the next start.
    private(set) var problem: TalkProblem?
    /// "Talk over MEKA" is on for this conversation (read from the Mac's setting at each start; the orb's line).
    private(set) var talkOver = true

    var active: Bool { phase != .ended }

    /// An answer arrived: Ask shows it (the words and the cards, which can still be clicked).
    var onAnswer: (String, AskOutcome) -> Void = { _, _ in }
    /// The places, in the answer on screen, of cards a spoken yes is doing.
    var indicesOf: ([AskCard]) -> [Int] = { _ in [] }
    /// A spoken yes did these cards (their places): Ask folds them away.
    var onDid: ([Int]) -> Void = { _ in }

    @ObservationIgnored private var model: CoreModel?
    @ObservationIgnored private var session = TalkSession(phase: .ended, conversation: Conversation(turns: []), pending: [], questions: 0, endAfterSpeaking: false, openAfter: nil, help: nil, openHelpAfter: nil, early: nil)
    /// Bumped by every start and stop, so a late answer or callback from an earlier conversation changes nothing.
    @ObservationIgnored private var generation = 0

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-GB"))
    @ObservationIgnored private var engine: AVAudioEngine?
    @ObservationIgnored private var request: SFSpeechAudioBufferRecognitionRequest?
    @ObservationIgnored private var task: SFSpeechRecognitionTask?
    @ObservationIgnored private var listenStarted = Date()
    @ObservationIgnored private var lastWords = Date()
    /// When the microphone last heard his voice (the mic fix, step 3), so lagging online words don't cut him off.
    @ObservationIgnored private var lastVoice: Date?
    /// When his words ended (the endpoint, or the final result), for Activity's "Speech to Added" line.
    @ObservationIgnored private var wordsEnded: Date?
    /// Whether this listen is Apple's online recognition (else the Mac's own).
    @ObservationIgnored private var listeningOnline = false
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
    /// "Talk over MEKA": the microphone's level while MEKA speaks (its own engine) and the core's detector over it.
    @ObservationIgnored private var overEngine: AVAudioEngine?
    @ObservationIgnored private var overClock: Task<Void, Never>?
    @ObservationIgnored private var bargeState = BargeInRules.shared.start()
    private let overMeter = OSAllocatedUnfairLock<Float>(initialState: -160)
    private static let overTickMs = 40

    // The orb speed pass, slice 7 (`HelloListenRules`): the mic open while the hello is still speaking.
    /// The hello's words, until `say` starts saying them.
    @ObservationIgnored private var helloLine: String?
    /// The hello is being said now (barge-in is quicker meanwhile).
    @ObservationIgnored private var helloSaying = false
    /// The recogniser is open while the hello still speaks (headphones): his words stop it (`answeredOverHello`).
    @ObservationIgnored private var micOverHello = false
    /// The hello's words, taken out of what the recogniser hears this turn (its echo); nil once his words are taken.
    @ObservationIgnored private var echoOf: String?
    /// The recogniser heard only the hello's echo once already after it ended: the next time is silence as usual.
    @ObservationIgnored private var echoRetried = false

    func attach(_ model: CoreModel) {
        self.model = model
        speaker.attach(model)
        // The orb speed pass, slice 3: the hello's openers kept ready in Navilife's voice (checked every
        // `HelloRules.WARM_EVERY_MS`; ones already here cost nothing), so a click on the orb speaks at once.
        warmHello?.cancel()
        warmHello = Task { [weak model] in
            while !Task.isCancelled {
                await model?.warmHello()
                try? await Task.sleep(for: .milliseconds(HelloRules.shared.WARM_EVERY_MS))
            }
        }
    }

    @ObservationIgnored private var warmHello: Task<Void, Never>?

    /// Starts a conversation: asks for the microphone and speech recognition the first time.
    func start() { begin(afterBrief: false) }

    /// "Hello, Navilife": the orb clicked at rest. The greeting is built by the core's rules on the Mac
    /// (`CoreModel.hello`, a String back), said in Navilife's voice, then Talk listens as after any answer. Asks for the
    /// microphone and speech recognition first, as a start does; the core's step is made on the main actor.
    func hello() {
        guard !active else { return }
        generation += 1
        problem = nil
        heard = ""
        said = ""
        talkOver = MacTalkOver.enabled
        let gen = generation
        Task {
            guard await Self.allowed() else {
                if gen == generation { problem = .noPermission }
                return
            }
            // The orb speed pass, slice 5: the server opens its AI and voice connections while the hello plays.
            Task { await model?.warmTalk() }
            // The orb speed pass, slice 2: this hello is timed from the click to Navilife's voice (Voice → Speed).
            await model?.turnHelloBegan()
            guard gen == generation, let hello = await model?.hello() else { return }
            guard gen == generation, !active else { return }
            let words = hello.words
            helloCards = hello.cards
            helloSpokeAt = nil
            Task { await model?.warmVoice() }
            // Slice 7, the entrance: the orb's points scatter and gather. The orb speed pass: it starts speaking while
            // the orb is still zooming up (`GREET_AT`, 0.24 s), not after the points re-form.
            if !MotionSetting.reduced {
                let start = Date()
                entranceAt = start
                try? await Task.sleep(for: .milliseconds(Int(HelloEntrance.shared.GREET_AT)))
                guard gen == generation, !active else { return }
            }
            helloLine = words // slice 7: the line said next is the hello (the mic may open with it)
            apply(TalkFlow.shared.greet(greeting: words))
        }
    }

    /// Starts a conversation fresh, or (`afterBrief`) listening as if Talk had just read the brief. The core's first
    /// step is made inside the main-actor task, so no Kotlin value crosses isolation.
    private func begin(afterBrief: Bool) {
        guard !active else { return }
        generation += 1
        problem = nil
        heard = ""
        said = ""
        talkOver = MacTalkOver.enabled
        let gen = generation
        Task {
            guard await Self.allowed() else {
                if gen == generation { problem = .noPermission }
                return
            }
            guard gen == generation else { return }
            Task { await model?.warmVoice() } // MEKA's common lines in its voice, fetched once
            Task { await model?.warmTalk() } // slice 5: the server gets ready for his question (at most every 20 s)
            apply(afterBrief ? TalkFlow.shared.afterBrief() : TalkFlow.shared.start())
        }
    }

    /// The microphone and speech recognition are both already allowed (checked without asking).
    static var allowedWithoutAsking: Bool {
        AVCaptureDevice.authorizationStatus(for: .audio) == .authorized && SFSpeechRecognizer.authorizationStatus() == .authorized
    }

    /// The window after the brief (`BriefFollowOnRules.WINDOW_MS`) rather than the opening's.
    @ObservationIgnored private var openAfterBrief = false

    /// The brief's Listen ran to its end (morning brief, slice 7, `BriefFollowOnRules`): a quiet chime, then listening
    /// as if Talk had just read the brief ("plan my day", "more news" or a question) for up to 8 s; nothing said →
    /// listening stops and `onLapsed` runs. Only called with the microphone and speech recognition already allowed.
    func startAfterBrief(onLapsed: @escaping () -> Void) {
        guard !active, Self.allowedWithoutAsking else { return }
        generation += 1
        problem = nil
        let gen = generation
        Task {
            chime()
            try? await Task.sleep(for: .milliseconds(Self.chimeGapMs))
            guard gen == generation, !active else { return }
            begin(afterBrief: true)
            openGen = generation
            openAt = nil
            openLapsed = onLapsed
            openAfterBrief = true
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
            openAfterBrief = false
        }
    }

    /// The orb clicked: during the hello's entrance, skip the rest of it (the hello carries on); while MEKA speaks,
    /// stop and listen; otherwise end.
    func tapOrb() {
        if let at = entranceAt {
            let t = Int64(Date().timeIntervalSince(at) * 1000)
            if let to = HelloEntrance.shared.tapThrough(kind: .big, t: t, reduced: MotionSetting.reduced) {
                entranceAt = Date().addingTimeInterval(-Double(to.int64Value) / 1000)
                return
            }
        }
        switch phase {
        case .speaking: apply(TalkFlow.shared.bargeIn(s: session))
        case .ended: start()
        default: stop()
        }
    }

    /// Ask left the screen, MEKA went to the background, the mic clicked again: everything stops at once.
    func stop() {
        generation += 1 // also drops a room check or chime still on its way ("Listen when I open MEKA")
        helloCards = [] // slice 8: the greeting's cards go with the conversation
        openAt = nil
        openLapsed = nil
        guard active else { return }
        apply(TalkFlow.shared.stop(s: session))
    }

    /// A card clicked by hand while talking: it is no longer on offer aloud.
    func cardTapped(_ card: AskCard) {
        session = session.doCopy(phase: session.phase, conversation: session.conversation,
                                  pending: session.pending.filter { $0 != card },
                                  questions: session.questions, endAfterSpeaking: session.endAfterSpeaking,
                                  openAfter: session.openAfter, help: session.help, openHelpAfter: session.openHelpAfter,
                                  early: session.early)
    }

    private func apply(_ step: TalkStep) {
        session = step.session
        phase = session.phase
        MacListen.latch.sessionOpen = phase != .ended // a switch to the Mac's own speech lasts the conversation
        step.effects.forEach(run)
        if phase == .speaking { meterVoice() }
        // Slice 8: the greeting's cards rise as its voice goes; the first time it speaks marks the moment.
        if phase == .speaking, !helloCards.isEmpty, helloSpokeAt == nil { helloSpokeAt = Date() }
    }

    /// "Hello, Navilife", slice 6: while Navilife speaks, the orb's level is its voice's metered loudness
    /// (`MekaSpeaker.outputLevel`, followed by `VoiceOut.follow`); 0 without a meter, so the orb's own swell plays.
    @ObservationIgnored private var voiceMetering = false

    private func meterVoice() {
        guard !voiceMetering else { return }
        voiceMetering = true
        Task { [weak self] in
            while !Task.isCancelled {
                guard let self, self.phase == .speaking else { break }
                self.level = VoiceOut.shared.follow(shown: self.level, target: self.speaker.outputLevel())
                try? await Task.sleep(for: .milliseconds(VoiceOut.shared.POLL_MS))
            }
            guard let self else { return }
            self.voiceMetering = false
            if self.phase != .listening { self.level = 0 }
        }
    }

    private func run(_ effect: any TalkEffect) {
        if effect is TalkEffectListen {
            listen()
        } else if let ask = effect as? TalkEffectAsk {
            let question = ask.question
            heard = question
            level = 0
            let gen = generation
            let ended = wordsEnded
            let online = listeningOnline
            // The companion, slice 9: while the answer is written, its first sentence may come ahead, already said;
            // Navilife starts speaking it at once. Only a String is read from the core.
            let early = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(80))
                    guard let self, gen == self.generation, self.phase == .thinking else { return }
                    if let piece = self.model?.talkEarlyPiece() {
                        self.apply(TalkFlow.shared.early(s: self.session, piece: piece))
                        return
                    }
                }
            }
            waitCue(gen)
            Task {
                guard let model else { return }
                // The orb speed pass, slice 2: timed from his last word to the answer's voice (only a number crosses).
                await model.turnTalkBegan(agoMs: ended.map { Int64(Date().timeIntervalSince($0) * 1000) } ?? 0)
                let out = await model.talk(question, history: ask.history)
                early.cancel()
                guard gen == generation else { return }
                guard let out else { return fail(.failed) }
                // The mic fix, step 3: his own "add …" was done at once; how long from his words ending to Added.
                if let ended, case .answered(let a) = onEnum(of: out), !a.answer.did.isEmpty {
                    await model.recordHeardTiming(ms: Int64(Date().timeIntervalSince(ended) * 1000), online: online)
                }
                helloCards = [] // slice 8: his answer's own cards take over under the orb, on Ask as before
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
        } else if let early = effect as? TalkEffectSpeakEarly {
            said = early.text
            sayEarly(early.text)
        } else if let rest = effect as? TalkEffectSpeakRest {
            said = [said, rest.text].filter { !$0.isEmpty }.joined(separator: " ")
            sayRest(rest.text)
        } else if effect is TalkEffectPlayMessages {
            playMessages()
        } else if let readOut = effect as? TalkEffectReadOut {
            readAloud(readOut.read)
        } else if let open = effect as? TalkEffectOpen {
            // "Plan my day" (morning brief, slice 5): the conversation has ended (End came first); Today opens with
            // the planner up, as its button does. Only a Kotlin enum is compared here, on the main actor.
            // "Clear my inbox" (Inbox autopilot, slice 7b): Needs you comes forward with the ten-minute inbox started.
            // "Take a voice note" (voice notes, slice 3): the voice note sheet opens recording.
            if open.place == TalkPlace.plan { model?.openPlanFromTalk() } else if open.place == TalkPlace.inbox { model?.openInboxFromTalk() }
            else if open.place == TalkPlace.voiceNote { model?.openVoiceNoteFromTalk() }
            // "Reflect on my week" (weekly reflection, slice 2): Review on the card's week with the reflection open.
            else if open.place == TalkPlace.reflect { model?.openReflectionFromTalk() }
            // "Shut down the day" (Done for the day, completeness 2): Today with the shutdown sheet up.
            else if open.place == TalkPlace.shutdown { model?.openShutdownFromTalk() }
            // "Plan my week" (Week board item (6) part 2): Calendar on the Board with Plan your week up.
            else if open.place == TalkPlace.planWeek { model?.openPlanWeek() }
        } else if let openHelp = effect as? TalkEffectOpenHelp {
            // Ask knows how Navilife works, slice 3: "take me there" after a help answer opens its sheet, as the
            // answer's Take me there does. Only a Kotlin enum crosses, on the main actor.
            model?.openHelpFromTalk(openHelp.link.place)
        } else if effect is TalkEffectStopSpeaking {
            stopSaying()
        } else if effect is TalkEffectEnd {
            micOverHello = false
            echoOf = nil
            helloLine = nil
            waitTask?.cancel()
            Task { await model?.turnEnded() }
            stopListening()
            stopSaying()
            level = 0
        }
    }

    /// The tick and holding line of the question being answered now (`waitCue`).
    @ObservationIgnored private var waitTask: Task<Void, Never>?
    /// The soft thinking tick (kept while it plays).
    @ObservationIgnored private var tickSound: NSSound?

    /// The orb speed pass, slice 4: while the answer to conversation `gen`'s question is on its way (the orb already
    /// shows Thinking), a soft tick after `AnswerWaitRules.CUE_AFTER_MS` and, still nothing after
    /// `AnswerWaitRules.HOLD_AFTER_MS`, a holding line in Navilife's voice from memory ("One sec, Meka…"), which the
    /// answer then follows. Nothing once the answer has begun. Only Data crosses from the core.
    private func waitCue(_ gen: Int) {
        waitTask?.cancel()
        let sent = ContinuousClock.now
        waitTask = Task { [weak self] in
            func waited() -> Int64 { Int64((ContinuousClock.now - sent) / .milliseconds(1)) }
            try? await Task.sleep(for: .milliseconds(AnswerWaitRules.shared.CUE_AFTER_MS))
            guard let self, !Task.isCancelled, AnswerWaitRules.shared.cues(waitingMs: waited(), answerStarted: self.answerStarted(gen)) else { return }
            self.thinkingTick()
            try? await Task.sleep(for: .milliseconds(AnswerWaitRules.shared.HOLD_AFTER_MS - AnswerWaitRules.shared.CUE_AFTER_MS))
            guard !Task.isCancelled, AnswerWaitRules.shared.holds(waitingMs: waited(), answerStarted: self.answerStarted(gen), held: false) else { return }
            guard let clip = await self.model?.answerHoldClip(), !Task.isCancelled, !self.answerStarted(gen) else { return }
            self.speaker.holdOn(clip)
        }
    }

    /// The answer to conversation `gen`'s question has begun (or the conversation moved on).
    private func answerStarted(_ gen: Int) -> Bool {
        gen != generation || phase != .thinking || session.early != nil
    }

    /// The soft thinking tick: the system's Tink, quieter than the listening chime.
    private func thinkingTick() {
        let sound = NSSound(named: NSSound.Name("Tink"))
        sound?.volume = 0.12
        sound?.play()
        tickSound = sound
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

    private func listen(onDevice forced: Bool = false) {
        stopListening()
        guard let recognizer else { return fail(.noOnDevice) }
        // Online with a connection, else on the device; neither: say so (the mic fix, step 4).
        // The latch (2026-10-19): after Apple's online speech failed, the Mac's own listens for the session or 15 min.
        let resting = recognizer.supportsOnDeviceRecognition && MacListen.latch.resting(nowMs: MacListen.nowMs)
        let online = !forced && MacSignal.online && !resting
        guard online || recognizer.supportsOnDeviceRecognition else { return fail(.noOnDevice) }
        guard recognizer.isAvailable else { return fail(.busy) }
        // The orb speed pass, slice 5: he's about to ask something; the server warms again if its last was a while ago.
        Task { await model?.warmTalk() }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.requiresOnDeviceRecognition = !online
        listeningOnline = online
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
        lastVoice = nil
        wordsEnded = nil
        if openLapsed != nil && openGen == generation && openAt == nil { openAt = listenStarted } // the window starts now
        meter.withLock { $0 = -160 }
        let gen = generation
        recognizer.queue = .main
        // Holds the controller until the task is cancelled (every stop cancels it).
        task = recognizer.recognitionTask(with: request) { result, error in
            let text = result?.bestTranscription.formattedString
            let final = result?.isFinal ?? false
            let failed = error != nil
            // "No speech detected" (1110) and a cancel (216, 301) are silence or a stop, not online speech failing.
            let code = (error as NSError?)?.code ?? 0
            let quiet = code == 1110 || code == 216 || code == 301
            Task { @MainActor in self.recognised(text, final: final, failed: failed, quiet: quiet, gen: gen) }
        }
        clock = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(TalkEndpoint.shared.TICK_MS))
                // Slice 7: while the hello still speaks with the mic open, the clock waits for it to end.
                guard let self, gen == self.generation, self.phase == .listening || self.micOverHello else { return }
                if self.micOverHello { continue }
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
            let squares = sum // a let copy: a captured var can't cross into withLock's @Sendable closure
            sums.withLock { $0.squares += squares; $0.count += n }
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
        if ListenRules.shared.isVoice(level: target) { lastVoice = now }
        if let at = openAt, let lapsed = openLapsed, openGen == generation {
            if heardAnything {
                openAt = nil
                openLapsed = nil
            } else if openAfterBrief
                        ? BriefFollowOnRules.shared.windowLapsed(startedAtMs: Int64(at.timeIntervalSince1970 * 1000),
                                                                 nowMs: Int64(now.timeIntervalSince1970 * 1000),
                                                                 speechBegan: false)
                        : TalkOnOpenRules.shared.windowLapsed(startedAtMs: Int64(at.timeIntervalSince1970 * 1000),
                                                              nowMs: Int64(now.timeIntervalSince1970 * 1000),
                                                              speechBegan: false) {
                stop()
                lapsed()
                return
            }
        }
        let lastSoundMs = ListenRules.shared.lastSound(
            lastWordsMs: Int64(lastWords.timeIntervalSince1970 * 1000),
            lastVoiceMs: lastVoice.map { KotlinLong(value: Int64($0.timeIntervalSince1970 * 1000)) }
        )
        let step = TalkEndpoint.shared.step(
            startedMs: Int64(listenStarted.timeIntervalSince1970 * 1000),
            heardAnything: heardAnything,
            lastWordsMs: lastSoundMs,
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

    private func recognised(_ text: String?, final: Bool, failed: Bool, quiet: Bool = false, gen: Int) {
        guard gen == generation, phase == .listening || micOverHello else { return }
        if micOverHello {
            // Slice 7: the hello is still speaking; two words of his own (its echo taken out) stop it.
            guard let raw = text, HelloListenRules.shared.isHim(heard: raw, hello: echoOf ?? "", playing: true) else {
                if failed {
                    // Give the mic back: the hello's end listens as before.
                    micOverHello = false
                    stopListening()
                } else if final {
                    listen() // it stopped by itself on the hello's echo: open it again
                }
                return
            }
            answeredOverHello()
        }
        var text = text
        if let echo = echoOf, let raw = text {
            if (final || failed) && !echoRetried && HelloListenRules.shared.onlyEcho(heard: raw, hello: echo) {
                echoRetried = true // it heard only the hello: listen again once rather than end
                return listen()
            }
            text = HelloListenRules.shared.his(heard: raw, hello: echo)
        }
        if let text, !text.isEmpty {
            if text != heard { lastWords = Date() }
            heard = text
            heardAnything = true
        }
        if final {
            if listeningOnline && heardAnything { MacListen.latch.worked(nowMs: MacListen.nowMs) }
            finishListening()
        } else if failed {
            // Apple's online recognition failed before any words: listen again on the Mac itself, and keep to it a while.
            if !heardAnything && listeningOnline && !quiet { MacListen.latch.failed(nowMs: MacListen.nowMs, timeout: false) }
            if !heardAnything && listeningOnline && recognizer?.supportsOnDeviceRecognition == true {
                return listen(onDevice: true)
            }
            // The recogniser gave up: what was heard is the question, nothing heard is silence.
            if heardAnything { finishListening() } else {
                stopListening()
                apply(TalkFlow.shared.silence(s: session))
            }
        }
    }

    /// The question is over: what was heard goes to the flow.
    private func finishListening() {
        echoOf = nil
        if wordsEnded == nil { wordsEnded = Date() }
        let text = heard
        stopListening()
        level = 0
        apply(TalkFlow.shared.heard(s: session, utterance: text, places: TalkOpenRules.shared.MAC))
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
        let hello = helloLine
        helloLine = nil
        helloSaying = hello != nil
        speaker.say(text) { [weak self] in self?.finishedSaying(id) }
        // Slice 7: with headphones the recogniser opens with the hello, so he can just start talking.
        if let hello, talkOver, MacAudioOut.headphones { listenOverHello(hello) } else { listenOver() }
    }

    /// The orb speed pass, slice 7: the recogniser opens while the hello is still speaking (headphones only: on the
    /// speakers it would mostly hear Navilife). What it catches of the hello is taken out; his own words stop it.
    private func listenOverHello(_ hello: String) {
        echoOf = hello
        echoRetried = false
        micOverHello = true
        listen()
    }

    /// He answered the hello while it was still speaking: it stops, and the recogniser already has his words.
    private func answeredOverHello() {
        micOverHello = false
        helloSaying = false
        listenStarted = Date()
        lastWords = listenStarted
        Task { await model?.turnEnded() } // the hello's trace is kept (Voice → Speed)
        apply(TalkFlow.shared.overHello(s: session))
    }

    /// The companion, slice 9: the answer's first sentence, said while the rest comes; its end moves nothing on.
    private func sayEarly(_ text: String) {
        utterance += 1
        speaker.say(text)
        listenOver()
    }

    /// The rest of the answer, once the early piece is over; then Talk listens again (barge-in keeps watching).
    private func sayRest(_ text: String) {
        utterance += 1
        let id = utterance
        speaker.follow(text) { [weak self] in self?.finishedSaying(id) }
    }

    /// "Play my messages" (call assistant polish 8c): each voice message introduced in MEKA's voice, then the caller's
    /// recording from MEKA's server (played from memory) or their words; then MEKA listens again.
    private func playMessages() {
        let steps = model?.voicePlaylist() ?? []
        if case .say(let first) = steps.first { said = first }
        utterance += 1
        let id = utterance
        speaker.playlist(steps) { [weak self] in self?.finishedSaying(id) }
        listenOver()
    }

    /// "Read my brief", "the headlines", "Barça news": the words from the brief and the News place this Mac shows, said
    /// as a reading (short pieces asked for ahead, so MEKA's voice holds throughout); then MEKA listens again. Headlines
    /// are only ever read out, never acted on (ADR-006). The read-out stays on the main actor; only the String goes on.
    private func readAloud(_ readOut: ReadOut) {
        if readOut.kind == ReadOut.Kind.brief {
            // The brief as MEKA's AI writes it from the facts (at most 6 s, else MEKA's own); stopped meanwhile, nothing is said.
            guard let model else { return fail(.failed) }
            said = ReadOutRules.shared.showing(read: readOut)
            let gen = generation
            Task {
                let text = await model.briefScript()
                guard gen == generation else { return }
                readText(text)
            }
            return
        }
        guard let text = model?.talkReadOut(readOut) else { return fail(.failed) }
        said = ReadOutRules.shared.showing(read: readOut)
        readText(text)
    }

    private func readText(_ text: String) {
        utterance += 1
        let id = utterance
        speaker.say(text, reading: true) { [weak self] in self?.finishedSaying(id) }
        listenOver()
    }

    /// Stops whatever is being said, in either voice.
    private func stopSaying() {
        utterance += 1
        helloSaying = false
        micOverHello = false // a click or barge-in during the hello: Talk listens afresh (the echo is still taken out)
        stopListeningOver()
        speaker.stop()
    }

    /// The line `id` finished: listen again, unless it was cut short since.
    private func finishedSaying(_ id: Int) {
        guard id == utterance, phase == .speaking else { return }
        helloSaying = false
        stopListeningOver() // let go of the microphone before the recogniser takes it
        Task { await model?.turnEnded() } // the turn's trace is kept (Voice → Speed)
        if micOverHello {
            // Slice 7: the hello ended with the recogniser already open: keep listening; the silence timer starts now.
            micOverHello = false
            listenStarted = Date()
            lastWords = listenStarted
            return apply(TalkFlow.shared.spokeListening(s: session))
        }
        apply(TalkFlow.shared.spoke(s: session))
    }

    // MARK: Talking over MEKA (voice barge-in)

    /// A line has started: with "Talk over MEKA" on, watch the microphone's level while it is said.
    private func listenOver() {
        guard talkOver else { return }
        stopListeningOver()
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { return }
        overMeter.withLock { $0 = -160 }
        Self.tapLevel(input, format: format, meter: overMeter)
        engine.prepare()
        do { try engine.start() } catch {
            input.removeTap(onBus: 0)
            return // only a click interrupts this line
        }
        overEngine = engine
        bargeState = BargeInRules.shared.start()
        let gen = generation
        let line = utterance
        overClock = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(Self.overTickMs))
                guard let self, gen == self.generation, line == self.utterance, self.phase == .speaking else { return }
                self.overheard()
            }
        }
    }

    /// Every 40 ms while MEKA speaks: the loudest buffer since the last look goes to the core's detector.
    private func overheard() {
        let db = overMeter.withLock { (v: inout Float) -> Float in
            let loudest = v
            v = -160
            return loudest
        }
        // Slice 7: quicker while the hello plays (it asks him a question).
        let tuning = HelloListenRules.shared.tuning(output: .speaker, hello: helloSaying)
        let step = BargeInRules.shared.stepWith(s: bargeState, levelDbfs: db, playing: speaker.audible, tuning: tuning,
                                                nowMs: Int64(ProcessInfo.processInfo.systemUptime * 1000))
        bargeState = step.state
        if step.interrupt { apply(TalkFlow.shared.bargeIn(s: session)) } // stops MEKA, lets go of the mic, listens
    }

    private func stopListeningOver() {
        overClock?.cancel()
        overClock = nil
        if let overEngine {
            overEngine.stop()
            overEngine.inputNode.removeTap(onBus: 0)
        }
        overEngine = nil
    }

    /// The level tap: only each buffer's loudness is kept (the loudest since the last look). Nonisolated: the audio
    /// thread runs it.
    nonisolated private static func tapLevel(_ input: AVAudioInputNode, format: AVAudioFormat,
                                              meter: OSAllocatedUnfairLock<Float>) {
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            let db = Self.dbfs(buffer)
            meter.withLock { $0 = max($0, db) }
        }
    }
}

/// "Talk over MEKA" on the Mac (voice barge-in): on by default (`BargeInRules.DEFAULT_ON`) and kept on this Mac
/// (UserDefaults, never synced), switched in Ask → More → Talk.
enum MacTalkOver {
    static let key = "meka.talk.talkOver"

    static var enabled: Bool { UserDefaults.standard.object(forKey: key) as? Bool ?? true }
}

/// The room check's running sums (Sendable, so the audio thread's tap can add to them under a lock).
struct RoomSums: Sendable {
    var squares = 0.0
    var count = 0
}

/// The Mac's listening latch (small refinement, Meka 2026-10-19: "engine fallback latches for the session"): after
/// Apple's online speech fails before any words, Talk listens on the Mac itself for the rest of the conversation or
/// 15 minutes, whichever is longer (`EngineLatchRules`), then quietly tries online again. Main actor only.
enum MacListen {
    static let latch = ListenLatch()
    static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/// Whether the Mac has a working connection now, for choosing Apple's online speech (the mic fix, step 4). Watched once
/// for the app's life; read from any thread (nonisolated: the project's default isolation is the main actor).
enum MacSignal {
    nonisolated private static let state = OSAllocatedUnfairLock<Bool>(initialState: true)
    nonisolated(unsafe) private static let monitor: NWPathMonitor = makeMonitor()

    nonisolated private static func makeMonitor() -> NWPathMonitor {
        let m = NWPathMonitor()
        m.pathUpdateHandler = { @Sendable path in
            let ok = path.status == .satisfied
            MacSignal.state.withLock { $0 = ok }
        }
        m.start(queue: DispatchQueue(label: "navilife.signal"))
        return m
    }

    nonisolated static var online: Bool {
        _ = monitor
        return state.withLock { $0 }
    }
}

/// Where the Mac's sound goes (the orb speed pass, slice 7): headphones are AirPods or other Bluetooth audio, or
/// wired headphones in the Mac's jack. Read from CoreAudio's default output each time (nothing kept); anything else
/// (the built-in speakers, a display, USB speakers) counts as the speakers.
enum MacAudioOut {
    static var headphones: Bool {
        var device = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice,
                                                 mScope: kAudioObjectPropertyScopeGlobal,
                                                 mElement: kAudioObjectPropertyElementMain)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device) == noErr,
              device != 0 else { return false }
        var transport: UInt32 = 0
        size = UInt32(MemoryLayout<UInt32>.size)
        address.mSelector = kAudioDevicePropertyTransportType
        if AudioObjectGetPropertyData(device, &address, 0, nil, &size, &transport) == noErr,
           transport == kAudioDeviceTransportTypeBluetooth || transport == kAudioDeviceTransportTypeBluetoothLE {
            return true
        }
        var source: UInt32 = 0
        size = UInt32(MemoryLayout<UInt32>.size)
        address.mSelector = kAudioDevicePropertyDataSource
        address.mScope = kAudioDevicePropertyScopeOutput
        return AudioObjectGetPropertyData(device, &address, 0, nil, &size, &source) == noErr && source == 0x6864_706E // 'hdpn'
    }
}
