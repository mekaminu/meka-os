@preconcurrency import MekaKit
import AVFoundation
import Foundation
import Observation
import Speech

/// Where a voice note being made on the Mac is up to (voice notes into actions, slice 3), as the Fold's
/// VoiceNoteState.
enum VoiceNoteStep: Equatable {
    case idle
    /// The microphone isn't allowed.
    case needsMic
    /// The microphone couldn't be opened (another app has it, no input).
    case busy
    case recording
    case finishing
    /// Kept (or, without the sealed key, read but not kept); the words, tidy note and cards follow.
    case done
    case tooShort
    case cancelled
}

/// One voice note at a time on the Mac: records it (`MacVoiceNoteRecorder`), keeps it (`MacVoiceNoteStore`), then sends
/// its words alone to the AI's tidy-up and shows what came back. Lives outside the sheet, so a sheet closed
/// mid-recording still stops and keeps the note (as the Fold's session). Main actor throughout: Kotlin values are made
/// and kept here; only Strings go to the core.
@MainActor
@Observable
final class VoiceNoteController {
    static let shared = VoiceNoteController()

    private(set) var step: VoiceNoteStep = .idle
    private(set) var elapsedMs: Int64 = 0
    private(set) var paused = false
    /// The last levels of his voice, 0 … 1, newest last (the strip's bars).
    private(set) var levels: [Float] = []
    /// What was heard (nil when there are no words), the AI's tidy note and "Mentions …".
    private(set) var heard: String?
    private(set) var tidy: String?
    private(set) var mentions: String?
    /// Why there are no words, or that nothing could be kept.
    private(set) var noWords: String?
    /// Whether the note was kept on this Mac.
    private(set) var kept = false
    /// The AI is reading the words.
    private(set) var reading = false
    /// What came of it ("Tidied · 2 things to add", "… · make cards in Voice notes later").
    private(set) var line: String?
    /// Cards offered (they wait in Needs you).
    private(set) var cards = 0

    static let bars = 40

    @ObservationIgnored private var recorder: MacVoiceNoteRecorder?
    @ObservationIgnored private var noteId: String?
    @ObservationIgnored private var startedAt = Date()
    @ObservationIgnored private weak var model: CoreModel?
    @ObservationIgnored private var generation = 0

    var active: Bool { step == .recording || step == .finishing }

    /// The note being recorded now (its ".part.m4a" is left alone by a sweep).
    var recordingId: String? { active ? noteId : nil }

    /// Starts a note (asks for the microphone and speech recognition the first time).
    func start(_ model: CoreModel) {
        guard !active else { return }
        self.model = model
        generation += 1
        let gen = generation
        reset()
        Task {
            let mic: Bool
            switch AVCaptureDevice.authorizationStatus(for: .audio) {
            case .authorized: mic = true
            case .notDetermined: mic = await AVCaptureDevice.requestAccess(for: .audio)
            default: mic = false
            }
            guard gen == generation else { return }
            guard mic else { step = .needsMic; return }
            let speech: Bool
            switch SFSpeechRecognizer.authorizationStatus() {
            case .authorized: speech = true
            case .notDetermined:
                speech = await withCheckedContinuation { c in SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0 == .authorized) } }
            default: speech = false
            }
            guard gen == generation else { return }
            begin(speech: speech)
        }
    }

    private func reset() {
        step = .idle
        elapsedMs = 0
        paused = false
        levels = []
        heard = nil
        tidy = nil
        mentions = nil
        noWords = nil
        kept = false
        reading = false
        line = nil
        cards = 0
    }

    private func begin(speech: Bool) {
        let store = MacVoiceNoteStore.shared
        startedAt = Date()
        let id = VoiceNoteRules.shared.id(atMs: Self.ms(startedAt))
        noteId = id
        store.sweep(recording: id)
        let part = store.partFile(id)
        let r = MacVoiceNoteRecorder()
        r.onProgress = { [weak self] ms, level in
            guard let self, self.step == .recording else { return }
            self.elapsedMs = ms
            self.levels.append(level)
            if self.levels.count > Self.bars { self.levels.removeFirst(self.levels.count - Self.bars) }
        }
        r.onLimit = { [weak self] in self?.step = .finishing }
        let gen = generation
        r.onFinished = { [weak self] f in
            guard let self, gen == self.generation else { return }
            self.finished(id, f)
        }
        recorder = r
        if r.start(part: part, speech: speech) {
            step = .recording
        } else {
            recorder = nil
            try? FileManager.default.removeItem(at: part)
            step = .busy
        }
    }

    func pause() {
        guard step == .recording, !paused else { return }
        recorder?.pause()
        paused = true
        levels = levels.map { _ in 0 }
    }

    func resume() {
        guard step == .recording, paused else { return }
        recorder?.resume()
        paused = recorder?.isPaused ?? false
    }

    /// Done: stop and keep it.
    func stop() {
        guard step == .recording else { return }
        step = .finishing
        recorder?.stop()
    }

    /// Cancel: stop and throw it away.
    func cancel() {
        generation += 1
        recorder?.cancel()
        recorder = nil
        if let id = noteId { try? FileManager.default.removeItem(at: MacVoiceNoteStore.shared.partFile(id)) }
        step = .cancelled
    }

    /// The sheet closed: a note still recording is stopped and kept (its words are read as usual).
    func sheetClosed() {
        if step == .recording { stop() }
    }

    private func finished(_ id: String, _ f: MacVoiceNoteRecorder.Finished) {
        recorder = nil
        let rules = VoiceNoteRules.shared
        let store = MacVoiceNoteStore.shared
        guard rules.longEnough(ms: f.durationMs) else {
            try? FileManager.default.removeItem(at: store.partFile(id))
            step = .tooShort
            return
        }
        let note = rules.made(id: id, atMs: Self.ms(startedAt), durationMs: f.durationMs, segments: f.segments,
                              failed: f.recognizerFailed)
        if !f.recordingOk { try? FileManager.default.removeItem(at: store.partFile(id)) }
        if store.canKeep {
            store.keep(note)
            kept = true
            noWords = rules.noWordsLineMac(words: note.words, noOnDevice: f.noOnDevice)
        } else {
            try? FileManager.default.removeItem(at: store.partFile(id))
            noWords = note.words == VoiceNoteWords.heard ? Optional(rules.MAC_NO_KEY) : rules.noWordsLineMac(words: note.words, noOnDevice: f.noOnDevice)
        }
        heard = note.words == VoiceNoteWords.heard ? note.transcript : nil
        step = .done
        MekaHaptics.light()
        guard rules.worthAsking(note: note) else {
            line = noWords == nil ? Optional(HandleThisRules.shared.NOTHING_LINE) : nil
            return
        }
        reading = true
        let gen = generation
        // Only plain values go into the task; the kept note is read back from the store afterwards.
        let words = note.transcript
        let atMs = note.atMs
        Task {
            guard let model else { reading = false; return }
            let read = await model.tidyVoiceNote(words, subject: model.voiceNoteSubject(atMs))
            guard gen == generation else { return }
            reading = false
            guard let read else { line = AskRules.shared.OFFLINE_LINE; return }
            tidy = read.tidy.isEmpty ? nil : read.tidy
            mentions = read.peopleLine
            line = read.captureLine
            cards = max(0, Int(read.cardCount))
            if read.cardCount > 0 { MekaHaptics.light() }
            let store = MacVoiceNoteStore.shared
            if kept, let mine = store.notes?.first(where: { $0.id == id }),
               let updated = VoiceNoteRules.shared.tidied(note: mine, cards: read.cardCount, tidy: read.tidy, people: read.people) {
                store.replace(updated)
            }
        }
    }

    static func ms(_ d: Date) -> Int64 { Int64(d.timeIntervalSince1970 * 1000) }
}
