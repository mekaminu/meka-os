@preconcurrency import MekaKit
import AVFoundation
import Foundation
import os
import Speech

/// The microphone's buffers, shared with the audio thread (voice notes into actions, slice 3). The tap hands each
/// buffer to the current recognition request and the recording file and keeps its loudness; everything behind one
/// lock, so pausing or moving to the next recognition piece never races a buffer in flight.
nonisolated final class VoiceNoteTapSink: @unchecked Sendable {
    private let lock = NSLock()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var file: AVAudioFile?
    private var paused = false
    private var db: Float = -160
    private var writeFailed = false

    func setRequest(_ r: SFSpeechAudioBufferRecognitionRequest?) { lock.withLock { request = r } }
    func setFile(_ f: AVAudioFile?) { lock.withLock { file = f } }
    func setPaused(_ p: Bool) { lock.withLock { paused = p; if p { db = -160 } } }
    /// Lets go of the file (AVAudioFile finishes writing it when released).
    func closeFile() { lock.withLock { file = nil } }
    var level: Float { lock.withLock { db } }
    var failedWriting: Bool { lock.withLock { writeFailed } }

    func take(_ buffer: AVAudioPCMBuffer) {
        let loud = Self.dbfs(buffer)
        lock.withLock {
            guard !paused else { return }
            request?.append(buffer)
            if let file {
                do { try file.write(from: buffer) } catch { writeFailed = true; self.file = nil }
            }
            db = loud
        }
    }

    /// A buffer's loudness in dB full scale (the first channel's RMS).
    static func dbfs(_ buffer: AVAudioPCMBuffer) -> Float {
        guard let samples = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return -160 }
        let n = Int(buffer.frameLength)
        var sum: Float = 0
        for i in 0..<n { sum += samples[i] * samples[i] }
        let rms = (sum / Float(n)).squareRoot()
        return rms <= 1e-8 ? -160 : max(-160, 20 * log10(rms))
    }
}

/// Records one voice note on the Mac and writes it down as it records, on the Mac only: `SFSpeechRecognizer` with
/// **on-device recognition required** (as Talk), so no audio goes to Apple; a Mac without English for Dictation keeps
/// the recording and says so. The recording is AAC in the note's ".part.m4a" (the store encrypts it when it stops).
///
/// The recogniser is given the note in pieces: a new request at each Resume and every `pieceMs` of talk, so a long
/// note never runs into a request's limit; each piece's best words are kept in order. Done stops the microphone, ends
/// the last piece and waits up to `finalWaitMs` for the pieces' final words before handing them over.
@MainActor
final class MacVoiceNoteRecorder {
    struct Finished {
        let segments: [String]
        let recognizerFailed: Bool
        /// No on-device English recogniser (or speech recognition not allowed): there were never going to be words.
        let noOnDevice: Bool
        let recordingOk: Bool
        let durationMs: Int64
    }

    var onProgress: (Int64, Float) -> Void = { _, _ in }
    var onLimit: () -> Void = {}
    var onFinished: (Finished) -> Void = { _ in }

    private static let pieceMs: Int64 = 50_000
    private static let finalWaitMs = 3_000
    private static let tickMs = 50

    private let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-GB"))
    private var engine: AVAudioEngine?
    private let sink = VoiceNoteTapSink()
    private var tasks: [SFSpeechRecognitionTask] = []
    private var currentRequest: SFSpeechAudioBufferRecognitionRequest?
    private var segments: [String] = []
    private var finals: Set<Int> = []
    private var pieceStartedMs: Int64 = 0
    private var failedAny = false
    private var useSpeech = false
    private var noOnDevice = false
    private var accumulated: TimeInterval = 0
    private var runningSince: Date?
    private var clock: Task<Void, Never>?
    private var ended = false
    private(set) var isPaused = false

    var elapsedMs: Int64 {
        let running = runningSince.map { Date().timeIntervalSince($0) } ?? 0
        return Int64((accumulated + running) * 1000)
    }

    /// Starts recording into `part`; `speech` is whether speech recognition is allowed. False when the microphone
    /// couldn't be opened (another app has it, no input device).
    func start(part: URL, speech: Bool) -> Bool {
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { return false }
        // AAC at the microphone's own rate and channels (above 48 kHz AAC can't be written: words only, no recording).
        if format.sampleRate <= 48_000 {
            let settings: [String: Any] = [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: format.sampleRate,
                AVNumberOfChannelsKey: format.channelCount,
                AVEncoderBitRateKey: 64_000,
            ]
            sink.setFile(try? AVAudioFile(forWriting: part, settings: settings, commonFormat: .pcmFormatFloat32,
                                          interleaved: format.isInterleaved))
        }
        noOnDevice = !speech || recognizer == nil || recognizer?.supportsOnDeviceRecognition != true
        useSpeech = !noOnDevice && recognizer?.isAvailable == true
        if !noOnDevice && !useSpeech { failedAny = true } // on the Mac but busy: the recording is still kept
        Self.tap(input, format: format, into: sink)
        engine.prepare()
        do { try engine.start() } catch {
            input.removeTap(onBus: 0)
            sink.closeFile()
            return false
        }
        self.engine = engine
        runningSince = Date()
        if useSpeech { startPiece() }
        clock = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(Self.tickMs))
                guard let self, !self.ended else { return }
                self.tick()
            }
        }
        return true
    }

    func pause() {
        guard !ended, !isPaused, let engine else { return }
        isPaused = true
        sink.setPaused(true)
        engine.pause()
        if let since = runningSince { accumulated += Date().timeIntervalSince(since) }
        runningSince = nil
        endPiece()
    }

    func resume() {
        guard !ended, isPaused, let engine else { return }
        if useSpeech { startPiece() }
        do { try engine.start() } catch { return }
        isPaused = false
        sink.setPaused(false)
        runningSince = Date()
    }

    /// Done: stops the microphone, waits briefly for the last words, then `onFinished`.
    func stop() {
        guard !ended else { return }
        let duration = elapsedMs
        shutDown()
        endPiece()
        let recordingOk = !sink.failedWriting
        sink.closeFile()
        Task { [weak self] in
            var waited = 0
            while let self, self.waitingForWords, waited < Self.finalWaitMs {
                try? await Task.sleep(for: .milliseconds(Self.tickMs))
                waited += Self.tickMs
            }
            guard let self else { return }
            self.tasks.forEach { $0.cancel() }
            self.tasks.removeAll()
            self.onFinished(Finished(segments: self.segments, recognizerFailed: self.failedAny || !self.useSpeech,
                                     noOnDevice: self.noOnDevice, recordingOk: recordingOk, durationMs: duration))
        }
    }

    /// Cancel: stops everything; nothing is handed over (the caller deletes the ".part.m4a").
    func cancel() {
        guard !ended else { return }
        shutDown()
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
        sink.setRequest(nil)
        sink.closeFile()
    }

    private var waitingForWords: Bool { useSpeech && finals.count < segments.count }

    private func shutDown() {
        ended = true
        clock?.cancel()
        clock = nil
        if let since = runningSince { accumulated += Date().timeIntervalSince(since) }
        runningSince = nil
        sink.setPaused(true)
        engine?.stop()
        engine?.inputNode.removeTap(onBus: 0)
        engine = nil
    }

    private func tick() {
        let ms = elapsedMs
        onProgress(ms, isPaused ? 0 : TalkOrb.shared.levelDbfs(db: sink.level))
        if VoiceNoteRules.shared.atLimit(ms: ms) {
            onLimit()
            stop()
            return
        }
        // The next piece, so no request runs long.
        if useSpeech && !isPaused && ms - pieceStartedMs >= Self.pieceMs {
            endPiece()
            startPiece()
        }
    }

    private func startPiece() {
        guard let recognizer else { return }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = true
        request.taskHint = .dictation
        let index = segments.count
        segments.append("")
        pieceStartedMs = elapsedMs
        currentRequest = request
        sink.setRequest(request)
        recognizer.queue = .main
        let task = recognizer.recognitionTask(with: request) { result, error in
            let text = result?.bestTranscription.formattedString
            let final = result?.isFinal ?? false
            let failed = error != nil
            Task { @MainActor in self.recognised(index, text: text, final: final, failed: failed) }
        }
        tasks.append(task)
    }

    private func endPiece() {
        guard let request = currentRequest else { return }
        currentRequest = nil
        sink.setRequest(nil)
        request.endAudio()
    }

    private func recognised(_ index: Int, text: String?, final: Bool, failed: Bool) {
        guard index < segments.count else { return }
        if let text, !text.isEmpty { segments[index] = text }
        // An error with no words is how the recogniser ends a silent piece too, so it isn't counted as a failure:
        // a note with no words in any piece reads "Nothing was heard".
        if final || failed { finals.insert(index) }
    }

    /// The microphone's buffers go to the sink. Nonisolated: the tap runs on the audio thread.
    nonisolated private static func tap(_ input: AVAudioInputNode, format: AVAudioFormat, into sink: VoiceNoteTapSink) {
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            sink.take(buffer)
        }
    }
}
