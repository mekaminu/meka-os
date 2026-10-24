@preconcurrency import MekaKit
import AVFoundation
import os

/// Obi's Live mode on the Mac (slice 3): one real-time Gemini Live conversation, like the Fold's `LiveTalk`.
///
/// The conversation itself runs in the core (`LiveConversation`, from `CoreModel.newLiveConversation`): the session
/// token from Navilife's server (the Gemini key never reaches the Mac), Google's WebSocket, Obi's tools (the web
/// search, his days, Ask's cards through the policy gate, his mail, his money while unlocked), each turn's words and
/// sources for Ask, the seconds reported, a fresh window carrying the talk on while he is still talking, and normal Obi
/// taking over when Live isn't set up within 2 s. Here only sound moves: the microphone through the Mac's voice
/// processing (its echo canceller takes Obi's voice out of what the microphone hears), turned into 16 kHz chunks of
/// 40 ms (base64, with each chunk's loudness), and Obi's 24 kHz voice played as it comes, dropped at once when Meka
/// talks over him. Nothing is recorded or kept.
///
/// Main actor throughout, and the conversation is only ever called here. The microphone's tap runs on the audio thread
/// and hands back plain values (a String, an Int, a Float) in a main-actor task.
@MainActor
final class MacLiveTalk {
    /// Google set the session up: he can talk.
    var onReady: () -> Void = {}
    /// Obi started or stopped speaking.
    var onSpeaking: (Bool) -> Void = { _ in }
    /// The orb's level, 0 … 1: his voice while he talks, Obi's while Obi speaks.
    var onLevel: (Float) -> Void = { _ in }
    /// This turn's words so far, his and Obi's.
    var onWords: (String, String) -> Void = { _, _ in }
    /// A turn finished: his words and Obi's answer for Ask.
    var onTurn: (String, AskOutcome) -> Void = { _, _ in }
    /// The conversation ended; `true` when Live never opened and normal Obi should answer instead.
    var onEnded: (Bool) -> Void = { _ in }

    private(set) var running = false
    private var conversation: LiveConversation?
    private var events: Task<Void, Never>?
    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var herVoice = false
    private let voiceFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Double(LiveTalkRules.shared.OUT_RATE),
                                            channels: 1, interleaved: false)

    /// Starts a Live conversation with `model`'s core (the microphone is already allowed). False when the core isn't
    /// open yet: normal Obi answers.
    func start(_ model: CoreModel) -> Bool {
        guard !running else { return true }
        guard ensure(model) != nil else { return false }
        running = true
        herVoice = false
        conversation?.start()
        return true
    }

    /// Slice 3b: the hello is playing (`greeted` is what it says): the session is asked for now, so `start` opens at
    /// once when the hello ends and Obi doesn't greet him twice. Only a String crosses into the core.
    func prepare(_ model: CoreModel, greeted: String) {
        guard !running, let c = ensure(model) else { return }
        c.prepare(greeted: greeted)
    }

    /// Slice 3b: the hello was ended by hand, so the session asked for during it won't be used (reported as 0 s).
    func discardPrepared() {
        conversation?.discardPrepared()
    }

    /// The core's conversation, made once (nil while the core isn't open yet) with its events followed here.
    private func ensure(_ model: CoreModel) -> LiveConversation? {
        if let conversation { return conversation }
        guard let c = model.newLiveConversation() else { return nil }
        conversation = c
        events = Task { [weak self] in
            for await e in c.events { self?.handle(e) }
        }
        return c
    }

    /// Ends the conversation at once (the orb clicked, Ask left): the sound stops now, the core reports the seconds.
    func stop() {
        guard running else { return }
        closeAudio()
        conversation?.stop()
    }

    private func handle(_ e: LiveEvent) {
        switch onEnum(of: e) {
        case .ready:
            guard running else { return }
            guard openAudio() else {
                conversation?.stop()
                return
            }
            onReady()
        case .speaking(let s):
            herVoice = s.speaking
            if running { onSpeaking(s.speaking) }
        case .audio(let a):
            play(a.base64)
        case .interrupted:
            // Meka talked over him: what is queued goes at once.
            player?.stop()
            player?.play()
        case .words(let w):
            if running { onWords(w.heard, w.said) }
        case .turn(let t):
            onTurn(t.question, t.answer)
        case .ended(let end):
            closeAudio()
            let was = running
            running = false
            herVoice = false
            if was { onEnded(end.fellBack) }
        }
    }

    // MARK: Sound

    /// The microphone (voice processing on, so Obi's own voice is taken out of it) and Obi's voice, one engine.
    private func openAudio() -> Bool {
        closeAudio()
        guard let voiceFormat else { return false }
        let engine = AVAudioEngine()
        let input = engine.inputNode
        // The echo canceller: without it (an older Mac's input), the core still sends only his voice clearly over
        // Obi's while Obi speaks on the speakers.
        try? input.setVoiceProcessingEnabled(true)
        let player = AVAudioPlayerNode()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: voiceFormat)
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0,
              let chunker = MicChunker(from: format) else { return false }
        Self.tapMic(input, format: format, chunker: chunker) { [weak self] base64, bytes, dbfs in
            self?.sendMic(base64, bytes: bytes, dbfs: dbfs)
        }
        engine.prepare()
        do { try engine.start() } catch {
            input.removeTap(onBus: 0)
            return false
        }
        player.play()
        self.engine = engine
        self.player = player
        return true
    }

    private func closeAudio() {
        player?.stop()
        engine?.inputNode.removeTap(onBus: 0)
        engine?.stop()
        player = nil
        engine = nil
    }

    /// One 40 ms chunk of his microphone: up to the core (which sends Obi's echo as silence on the speakers), and his
    /// level to the orb while he is quiet.
    private func sendMic(_ base64: String, bytes: Int, dbfs: Float) {
        guard running, engine != nil else { return }
        conversation?.sendAudio(base64: base64, bytes: Int32(bytes), dbfs: dbfs, headphones: MacAudioOut.headphones)
        if !herVoice { onLevel(LiveTalkRules.shared.level(dbfs: dbfs)) }
    }

    /// Obi's voice: base64 16-bit PCM at 24 kHz, queued on the player; his level to the orb.
    private func play(_ base64: String) {
        guard running, let player, let voiceFormat, let data = Data(base64Encoded: base64), data.count >= 2 else { return }
        let frames = data.count / 2
        guard let buffer = AVAudioPCMBuffer(pcmFormat: voiceFormat, frameCapacity: AVAudioFrameCount(frames)),
              let out = buffer.floatChannelData?[0] else { return }
        var sum: Float = 0
        data.withUnsafeBytes { raw in
            for i in 0..<frames {
                let v = Int16(littleEndian: raw.loadUnaligned(fromByteOffset: i * 2, as: Int16.self))
                let f = Float(v) / 32768
                out[i] = f
                sum += f * f
            }
        }
        buffer.frameLength = AVAudioFrameCount(frames)
        player.scheduleBuffer(buffer, completionHandler: nil)
        let rms = (sum / Float(frames)).squareRoot()
        onLevel(LiveTalkRules.shared.level(dbfs: rms <= 1e-8 ? -160 : max(-160, 20 * log10(rms))))
    }

    /// The microphone's buffers become 40 ms chunks of 16 kHz PCM on the audio thread; each goes back to the main actor
    /// as plain values. Nonisolated: the tap's closure must not belong to the main actor.
    nonisolated private static func tapMic(_ input: AVAudioInputNode, format: AVAudioFormat, chunker: MicChunker,
                                           send: @escaping @MainActor @Sendable (String, Int, Float) -> Void) {
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            for chunk in chunker.add(buffer) {
                let b64 = chunk.base64
                let bytes = chunk.bytes
                let db = chunk.dbfs
                Task { @MainActor in send(b64, bytes, db) }
            }
        }
    }
}

/// One microphone chunk ready to go up: base64 PCM, its length in bytes and its loudness.
nonisolated struct MicChunk: Sendable {
    let base64: String
    let bytes: Int
    let dbfs: Float
}

/// Turns the microphone's buffers into 16 kHz, 16-bit mono chunks of `LiveTalkRules.CHUNK_SAMPLES` (40 ms). Used only
/// on the audio thread (the tap's), so it keeps its converter and leftover samples without a lock.
nonisolated final class MicChunker: @unchecked Sendable {
    private let converter: AVAudioConverter
    private let outFormat: AVAudioFormat
    private let ratio: Double
    private let chunkSamples = Int(LiveTalkRules.shared.CHUNK_SAMPLES)
    private var pending: [Int16] = []

    init?(from input: AVAudioFormat) {
        guard let out = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: Double(LiveTalkRules.shared.IN_RATE),
                                      channels: 1, interleaved: true),
              let c = AVAudioConverter(from: input, to: out) else { return nil }
        c.downmix = true
        converter = c
        outFormat = out
        ratio = out.sampleRate / input.sampleRate
    }

    /// The whole chunks `buffer` completes.
    func add(_ buffer: AVAudioPCMBuffer) -> [MicChunk] {
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
        guard buffer.frameLength > 0, let out = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: capacity) else { return [] }
        let fed = Fed()
        nonisolated(unsafe) let source = buffer
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if fed.done {
                status.pointee = .noDataNow
                return nil
            }
            fed.done = true
            status.pointee = .haveData
            return source
        }
        guard error == nil, let samples = out.int16ChannelData?[0], out.frameLength > 0 else { return [] }
        pending.append(contentsOf: UnsafeBufferPointer(start: samples, count: Int(out.frameLength)))
        var chunks: [MicChunk] = []
        while pending.count >= chunkSamples {
            let piece = Array(pending.prefix(chunkSamples))
            pending.removeFirst(chunkSamples)
            var sum: Float = 0
            for s in piece {
                let f = Float(s) / 32768
                sum += f * f
            }
            let rms = (sum / Float(piece.count)).squareRoot()
            let db = rms <= 1e-8 ? -160 : max(-160, 20 * log10(rms))
            let data = piece.withUnsafeBufferPointer { Data(buffer: $0) } // little-endian on Apple silicon
            chunks.append(MicChunk(base64: data.base64EncodedString(), bytes: data.count, dbfs: db))
        }
        return chunks
    }

    /// Whether the converter has had this buffer already (its input block is asked until it says there's no more).
    nonisolated private final class Fed: @unchecked Sendable {
        var done = false
    }
}
