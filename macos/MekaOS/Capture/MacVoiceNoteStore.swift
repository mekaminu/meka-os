@preconcurrency import MekaKit
import CryptoKit
import Foundation
import Observation

/// Voice notes into actions, slice 3 (the Mac): the notes this Mac keeps and their recordings, like the Fold's
/// VoiceNoteStore. They live in Application Support/os.meka.mac/voice-notes (folder 0700, out of Time Machine) and are
/// encrypted with AES-GCM under a 256-bit key kept as a Secure Enclave-sealed file (`DeviceIdentity.voiceNotesKey`),
/// so a copy of the folder is unreadable anywhere else. Nothing here is synced or sent; the core says what to keep,
/// show and delete (`VoiceNoteRules`), and the index is the phone's own format (one line per note).
///
/// A recording in progress is written as a plain "<id>.part.m4a" inside the folder while the microphone runs and
/// encrypted into "<id>.m4a.enc" the moment it stops; `sweep` deletes notes past 30 days, recordings no note owns and
/// any leftover ".part.m4a". Main actor throughout: the Kotlin notes never leave it, and the files are small (five
/// minutes of AAC is about 2.5 MB).
@MainActor
@Observable
final class MacVoiceNoteStore {
    static let shared = MacVoiceNoteStore()

    /// The notes, newest first (nil until first read).
    private(set) var notes: [VoiceNote]?

    @ObservationIgnored private var key: SymmetricKey?
    @ObservationIgnored private var keyLooked = false

    /// Whether this Mac can keep voice notes (it has the sealed key).
    var canKeep: Bool { symmetricKey() != nil }

    static func folder() -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("os.meka.mac", isDirectory: true)
            .appendingPathComponent("voice-notes", isDirectory: true)
    }

    /// Where a recording in progress is written (plain, inside the private folder, until `keep`).
    func partFile(_ id: String) -> URL {
        ensureFolder()
        return Self.folder().appendingPathComponent(id + ".part.m4a")
    }

    private func recordingFile(_ id: String) -> URL {
        Self.folder().appendingPathComponent(VoiceNoteRules.shared.fileName(id: id))
    }

    private func indexFile() -> URL { Self.folder().appendingPathComponent(VoiceNoteRules.shared.INDEX_FILE) }

    @discardableResult
    private func ensureFolder() -> Bool {
        let fm = FileManager.default
        var url = Self.folder()
        do {
            try fm.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? url.setResourceValues(values)
            return true
        } catch {
            return false
        }
    }

    private func symmetricKey() -> SymmetricKey? {
        if keyLooked { return key }
        keyLooked = true
        guard let hex = DeviceIdentity.voiceNotesKey() else { return nil }
        var bytes = [UInt8]()
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            guard let b = UInt8(hex[i..<j], radix: 16) else { return nil }
            bytes.append(b)
            i = j
        }
        key = SymmetricKey(data: bytes)
        return key
    }

    private func encrypt(_ plain: Data) -> Data? {
        guard let key = symmetricKey() else { return nil }
        return try? AES.GCM.seal(plain, using: key).combined
    }

    private func decrypt(_ blob: Data) -> Data? {
        guard let key = symmetricKey(), let box = try? AES.GCM.SealedBox(combined: blob) else { return nil }
        return try? AES.GCM.open(box, using: key)
    }

    private func readIndex() -> [VoiceNote] {
        guard let blob = try? Data(contentsOf: indexFile()), let plain = decrypt(blob),
              let text = String(data: plain, encoding: .utf8) else { return [] }
        return VoiceNoteRules.shared.decode(text: text)
    }

    @discardableResult
    private func writeIndex(_ all: [VoiceNote]) -> Bool {
        guard ensureFolder(), let blob = encrypt(Data(VoiceNoteRules.shared.encodeList(notes: all).utf8)) else { return false }
        do { try blob.write(to: indexFile(), options: [.atomic]) } catch { return false }
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: indexFile().path)
        notes = VoiceNoteRules.shared.ordered(notes: all)
        return true
    }

    /// Reads the notes (and publishes them). Never throws.
    func load() {
        notes = canKeep ? readIndex() : []
    }

    /// Keeps a finished recording: encrypts its ".part.m4a" into the recording and adds `note` to the index. False when
    /// the recording couldn't be kept (the note is still added, without a recording to play).
    @discardableResult
    func keep(_ note: VoiceNote) -> Bool {
        let part = partFile(note.id)
        defer { try? FileManager.default.removeItem(at: part) }
        var ok = false
        if let plain = try? Data(contentsOf: part), !plain.isEmpty, let blob = encrypt(plain) {
            ok = (try? blob.write(to: recordingFile(note.id), options: [.atomic])) != nil
            if ok { try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: recordingFile(note.id).path) }
        }
        writeIndex(VoiceNoteRules.shared.upsert(notes: readIndex(), note: note))
        return ok
    }

    /// Replaces one note in the index (kept, the AI's tidy note and cards). Never throws.
    func replace(_ note: VoiceNote) {
        let all = readIndex()
        guard all.contains(where: { $0.id == note.id }) else { return }
        writeIndex(VoiceNoteRules.shared.upsert(notes: all, note: note))
    }

    /// Takes `id` out of the index; its recording stays until the next `sweep`, so `restore` (Undo) brings it back whole.
    func remove(_ id: String) -> VoiceNote? {
        let all = readIndex()
        guard let n = all.first(where: { $0.id == id }) else { return nil }
        writeIndex(all.filter { $0.id != id })
        return n
    }

    /// Puts a removed note back (its recording is still there unless a sweep ran).
    func restore(_ note: VoiceNote) {
        writeIndex(VoiceNoteRules.shared.upsert(notes: readIndex(), note: note))
    }

    /// Deletes the notes past 30 days (unless kept), recordings no note owns and leftover ".part.m4a" files, except the
    /// one being recorded (`recording`).
    func sweep(now: Date = Date(), recording: String? = nil) {
        guard canKeep else { notes = []; return }
        let all = readIndex()
        let gone = Set(VoiceNoteRules.shared.expired(notes: all, nowMs: Int64(now.timeIntervalSince1970 * 1000)).map(\.id))
        let left = all.filter { !gone.contains($0.id) }
        if gone.isEmpty { notes = left } else { writeIndex(left) }
        let names = (try? FileManager.default.contentsOfDirectory(atPath: Self.folder().path)) ?? []
        let keepPart = recording.map { $0 + ".part.m4a" }
        // A ".part.m4a" isn't a recording any note owns, so the core lists it with the orphans.
        for name in VoiceNoteRules.shared.orphans(files: names, notes: left) where name != keepPart {
            try? FileManager.default.removeItem(at: Self.folder().appendingPathComponent(name))
        }
    }

    /// Whether `id` still has a recording to play.
    func hasRecording(_ id: String) -> Bool { FileManager.default.fileExists(atPath: recordingFile(id).path) }

    /// `id`'s recording decrypted in memory for playing (never written out); nil when there's none or it can't be read.
    func playable(_ id: String) -> Data? {
        guard let blob = try? Data(contentsOf: recordingFile(id)) else { return nil }
        return decrypt(blob)
    }
}
