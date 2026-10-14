@preconcurrency import MekaKit
import Foundation
import SQLCipher

/// Mac database encryption (build plan M1, slice 2b; ADR-002, spike S6 proved the SQLCipher linkage).
///
/// Before the core opens `meka.db`, the app reads what the file is and whether it has a key, asks the core's
/// `DatabaseProtectionRules.plan` what to do, and does it here:
///
/// - A plain database (every Mac before this slice) is copied once into an encrypted one with `sqlcipher_export`
///   (the schema version carried over). The copy must have the same rows in every table and open through the core's
///   own driver with the key before it is swapped in; the plain file is kept until the swapped-in copy has opened
///   again, then removed. Any failure leaves the plain file where it was and the next launch tries again.
/// - A sealed database that this launch can't open (its key is gone) is renamed aside, never deleted, and MEKA
///   starts afresh and re-syncs.
/// - Without sealed key files (no Secure Enclave) nothing is encrypted and Your data says FileVault is the protection.
///
/// Everything here is plain file and SQLite work on strings and paths; no Kotlin object crosses an actor.
nonisolated enum DatabaseEncryption {
    nonisolated struct Prepared {
        /// The key to open with, or nil to open plain.
        let keyHex: String?
        let protection: DatabaseProtection
        /// A one-time line when a file was set aside.
        let note: String?
        var encrypted: Bool { keyHex != nil }
    }

    /// Suffix of the encrypted copy while it's being made, and of the plain file while the copy is swapped in.
    static let copySuffix = ".encrypting"
    static let oldSuffix = ".plain-old"
    private static let sidecars = ["-wal", "-shm", "-journal"]

    /// [opensWithKey] (directory, name, key) opens a database exactly as the core will; the app passes
    /// `MacCoreFactory.databaseOpensWithKey`.
    static func prepare(
        directory: URL,
        name: String,
        key: (hex: String, made: Bool)?,
        sqlcipherReady: Bool,
        opensWithKey: (String, String, String) -> Bool,
        now: Date = Date()
    ) -> Prepared {
        let url = directory.appendingPathComponent(name)
        cleanUpInterrupted(directory: directory, name: name)
        let file = fileState(url)
        let keyState: DatabaseKeyState
        if let key, sqlcipherReady, DeviceIdentity.isKeyHex(key.hex) {
            keyState = key.made ? .made : .kept
        } else {
            keyState = .unavailable
        }
        let plan = DatabaseProtectionRules.shared.plan(file: file, key: keyState)
        let unkeyed: DatabaseProtection = sqlcipherReady ? .noEnclave : .notYet
        var note: String?

        if plan.setAside {
            note = setAside(url, directory: directory, name: name, now: now)
        }
        guard plan.keyed, let hex = key?.hex else {
            return Prepared(keyHex: nil, protection: unkeyed, note: note)
        }
        if plan.encryptFirst {
            guard encryptInPlace(url, directory: directory, name: name, keyHex: hex, opensWithKey: opensWithKey) else {
                return Prepared(keyHex: nil, protection: .notYet, note: note)
            }
        } else if fileState(url) == .sealed, !opensWithKey(directory.path, name, hex) {
            // Sealed with another key (the key file was replaced): keep the file aside, start afresh with this key.
            note = setAside(url, directory: directory, name: name, now: now)
        }
        return Prepared(keyHex: hex, protection: .encrypted, note: note)
    }

    // MARK: - The file

    static func fileState(_ url: URL) -> DatabaseFile {
        guard FileManager.default.fileExists(atPath: url.path) else { return .missing }
        guard let handle = try? FileHandle(forReadingFrom: url) else { return .sealed }
        defer { try? handle.close() }
        let head = (try? handle.read(upToCount: 16)) ?? Data()
        let plain = Data("SQLite format 3".utf8) + Data([0])
        if head.count < plain.count { return .plain }
        return head.prefix(plain.count) == plain ? .plain : .sealed
    }

    /// A copy or swap that stopped half way (the app quit): the plain file is always the one to trust.
    private static func cleanUpInterrupted(directory: URL, name: String) {
        let fm = FileManager.default
        let url = directory.appendingPathComponent(name)
        let old = directory.appendingPathComponent(name + oldSuffix)
        if fm.fileExists(atPath: old.path) {
            if fm.fileExists(atPath: url.path), fileState(url) == .sealed {
                // The swap finished but the old file wasn't removed yet: only remove it once the new one is checked
                // again below, so put the plain one back and redo the copy.
                removeDatabase(url)
            }
            if !fm.fileExists(atPath: url.path) { moveDatabase(old, to: url) }
        }
        removeDatabase(directory.appendingPathComponent(name + copySuffix))
    }

    /// The core couldn't open the database at all (it threw): rename it aside, never delete it, so a fresh one can be
    /// made. Returns the one-time line, or nil when there was nothing to move.
    static func setAsideUnopenable(directory: URL, name: String, now: Date = Date()) -> String? {
        let url = directory.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return setAside(url, directory: directory, name: name, now: now)
    }

    private static func setAside(_ url: URL, directory: URL, name: String, now: Date) -> String? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyyMMdd-HHmm"
        let asideName = DatabaseProtectionRules.shared.setAsideName(name: name, stamp: f.string(from: now))
        guard moveDatabase(url, to: directory.appendingPathComponent(asideName)) else { return nil }
        return DatabaseProtectionRules.shared.setAsideLine(asideName: asideName)
    }

    // MARK: - Encrypting a plain database

    /// Which step of the last encryption attempt failed (nil after a success): the Mac tests print it, since the CI
    /// runner's log can't be read from a build run (nightly ci run 553 saw the copy refused without saying why).
    nonisolated(unsafe) static var lastFailure: String?

    private static func encryptInPlace(
        _ url: URL, directory: URL, name: String, keyHex: String, opensWithKey: (String, String, String) -> Bool
    ) -> Bool {
        let copyName = name + copySuffix
        let copy = directory.appendingPathComponent(copyName)
        let old = directory.appendingPathComponent(name + oldSuffix)
        removeDatabase(copy)
        // Fold any write-ahead log into the plain file first, so the copy and the counts read one file.
        checkpoint(url)
        func fail(_ step: String) -> Bool {
            lastFailure = step
            removeDatabase(copy)
            return false
        }
        guard let version = export(url, to: copy, keyHex: keyHex) else { return fail("export: \(lastSQLiteError ?? "?")") }
        guard fileState(copy) == .sealed else { return fail("the copy isn't sealed") }
        guard let plainRows = tableCounts(url, keyHex: nil), !plainRows.isEmpty else { return fail("the plain file's rows didn't read") }
        guard let sealedRows = tableCounts(copy, keyHex: keyHex) else { return fail("the copy's rows didn't read with the key") }
        guard sealedRows == plainRows else { return fail("rows differ: plain \(plainRows.sorted { $0.key < $1.key }) copy \(sealedRows.sorted { $0.key < $1.key })") }
        let copyVersion = userVersion(copy, keyHex: keyHex)
        guard copyVersion == version else { return fail("user_version \(version) became \(copyVersion.map(String.init) ?? "nil")") }
        guard opensWithKey(directory.path, copyName, keyHex) else { return fail("the core's driver didn't open the copy") }
        removeDatabase(old)
        guard moveDatabase(url, to: old) else { return fail("moving the plain file aside failed") }
        guard moveDatabase(copy, to: url), opensWithKey(directory.path, name, keyHex) else {
            lastFailure = "the swapped-in copy didn't open through the core's driver"
            removeDatabase(url)
            removeDatabase(copy)
            moveDatabase(old, to: url)
            return false
        }
        removeDatabase(old)
        lastFailure = nil
        return true
    }

    /// The last SQLite error message seen by [export] (diagnostics for the tests).
    nonisolated(unsafe) private(set) static var lastSQLiteError: String?

    /// `PRAGMA wal_checkpoint(TRUNCATE)` on a plain database; nothing happens to one that isn't in WAL mode.
    private static func checkpoint(_ url: URL) {
        guard let db = open(url, keyHex: nil) else { return }
        defer { sqlite3_close(db) }
        sqlite3_exec(db, "PRAGMA wal_checkpoint(TRUNCATE);", nil, nil, nil)
    }

    /// Copies the plain database at [url] into a new encrypted one at [copy]; returns the schema version carried over.
    static func export(_ url: URL, to copy: URL, keyHex: String) -> Int64? {
        // ATTACH opens the new file with the main connection's flags, so it needs CREATE too (nightly ci run 555:
        // "unable to open database: …meka.db.encrypting").
        guard let db = open(url, keyHex: nil, create: true) else { return nil }
        defer { sqlite3_close(db) }
        guard let version = int(db, "PRAGMA user_version;") else { return nil }
        let sql = "ATTACH DATABASE '\(copy.path.replacingOccurrences(of: "'", with: "''"))' AS encrypted KEY \"x'\(keyHex)'\";"
            + " SELECT sqlcipher_export('encrypted');"
            + " PRAGMA encrypted.user_version = \(version);"
            + " DETACH DATABASE encrypted;"
        guard sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK else {
            lastSQLiteError = String(cString: sqlite3_errmsg(db))
            return nil
        }
        return version
    }

    /// Rows per table (SQLite's own tables aside), or nil when the database doesn't open or read.
    static func tableCounts(_ url: URL, keyHex: String?) -> [String: Int64]? {
        guard let db = open(url, keyHex: keyHex) else { return nil }
        defer { sqlite3_close(db) }
        guard let names = strings(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name;")
        else { return nil }
        var counts: [String: Int64] = [:]
        for table in names {
            guard let n = int(db, "SELECT count(*) FROM \"\(table.replacingOccurrences(of: "\"", with: "\"\""))\";") else { return nil }
            counts[table] = n
        }
        return counts
    }

    static func userVersion(_ url: URL, keyHex: String?) -> Int64? {
        guard let db = open(url, keyHex: keyHex) else { return nil }
        defer { sqlite3_close(db) }
        return int(db, "PRAGMA user_version;")
    }

    // MARK: - SQLite

    private static func open(_ url: URL, keyHex: String?, create: Bool = false) -> OpaquePointer? {
        var db: OpaquePointer?
        // Only ever opens a file that exists (no CREATE for the main file's own path), whatever [create] says.
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let flags = SQLITE_OPEN_READWRITE | (create ? SQLITE_OPEN_CREATE : 0)
        guard sqlite3_open_v2(url.path, &db, flags, nil) == SQLITE_OK, let db else {
            if let db { sqlite3_close(db) }
            return nil
        }
        if let keyHex, sqlite3_exec(db, "PRAGMA key = \"x'\(keyHex)'\";", nil, nil, nil) != SQLITE_OK {
            sqlite3_close(db)
            return nil
        }
        return db
    }

    private static func int(_ db: OpaquePointer, _ sql: String) -> Int64? {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { return nil }
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return sqlite3_column_int64(stmt, 0)
    }

    private static func strings(_ db: OpaquePointer, _ sql: String) -> [String]? {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { return nil }
        defer { sqlite3_finalize(stmt) }
        var out: [String] = []
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_DONE { return out }
            guard rc == SQLITE_ROW, let text = sqlite3_column_text(stmt, 0) else { return nil }
            out.append(String(cString: text))
        }
    }

    // MARK: - Files with their sidecars

    @discardableResult
    private static func moveDatabase(_ from: URL, to: URL) -> Bool {
        let fm = FileManager.default
        do {
            try fm.moveItem(at: from, to: to)
        } catch {
            return false
        }
        // A -wal or -shm left beside the old name belongs to that file; never let it pair with another one.
        for s in sidecars {
            let side = URL(fileURLWithPath: from.path + s)
            guard fm.fileExists(atPath: side.path) else { continue }
            let target = URL(fileURLWithPath: to.path + s)
            try? fm.removeItem(at: target)
            try? fm.moveItem(at: side, to: target)
        }
        return true
    }

    private static func removeDatabase(_ url: URL) {
        let fm = FileManager.default
        for s in [""] + sidecars { try? fm.removeItem(atPath: url.path + s) }
    }
}
