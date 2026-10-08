@preconcurrency import MekaKit
import XCTest
@testable import MekaOS

/// Mac database encryption (slice 2b): a plain database is encrypted once and nothing is lost on the way.
final class DatabaseEncryptionTests: XCTestCase {
    private var dir: URL!
    private let name = "meka.db"
    private let key = String(repeating: "0123456789abcdef", count: 4)

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("meka-enc-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
    }

    private func opens(_ d: String, _ n: String, _ k: String) -> Bool {
        MacCoreFactory.shared.databaseOpensWithKey(directory: d, name: n, keyHex: k)
    }

    private func prepare(key: (hex: String, made: Bool)?, ready: Bool = true) -> DatabaseEncryption.Prepared {
        DatabaseEncryption.prepare(directory: dir, name: name, key: key, sqlcipherReady: ready, opensWithKey: opens)
    }

    /// A plain database the way every Mac had it before this slice, with one task in it.
    private func makePlainDatabase() async throws {
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: nil, encrypted: false, databaseDirectory: dir.path, databaseName: name, deviceKey: nil
        )
        _ = try await core.addTask(title: "Encrypt me")
        core.close()
    }

    private var url: URL { dir.appendingPathComponent(name) }

    func testAPlainDatabaseIsEncryptedOnceWithEveryRowKept() async throws {
        try await makePlainDatabase()
        XCTAssertEqual(DatabaseEncryption.fileState(url), .plain)
        let before = try XCTUnwrap(DatabaseEncryption.tableCounts(url, keyHex: nil))
        XCTAssertGreaterThan(before.values.reduce(0, +), 0)

        let prepared = prepare(key: (key, true))
        XCTAssertEqual(prepared.keyHex, key)
        XCTAssertTrue(prepared.encrypted)
        XCTAssertEqual(prepared.protection, .encrypted)
        XCTAssertNil(prepared.note)
        XCTAssertEqual(DatabaseEncryption.fileState(url), .sealed)
        XCTAssertEqual(DatabaseEncryption.tableCounts(url, keyHex: key), before)
        XCTAssertNil(DatabaseEncryption.tableCounts(url, keyHex: nil), "the encrypted file read without its key")
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: dir.path)
            .filter { $0.hasSuffix(DatabaseEncryption.copySuffix) || $0.hasSuffix(DatabaseEncryption.oldSuffix) }
        XCTAssertEqual(leftovers, [], "the plain copy or the half-made one was left behind")

        // The next launch opens it as it is.
        let again = prepare(key: (key, false))
        XCTAssertEqual(again.keyHex, key)
        XCTAssertEqual(DatabaseEncryption.tableCounts(url, keyHex: key), before)

        // And the core reads the task back through its own driver.
        let core = MacCoreFactory.shared.create(
            householdId: "test", deviceId: "mactest", syncUrl: nil, deviceSecret: nil,
            databaseKeyHex: key, encrypted: true, databaseDirectory: dir.path, databaseName: name, deviceKey: nil
        )
        let summary = try await core.exportSummary()
        XCTAssertGreaterThan(summary.total, 0)
        core.close()
    }

    func testWithoutAKeyOrSQLCipherNothingChanges() async throws {
        try await makePlainDatabase()
        let noKey = prepare(key: nil)
        XCTAssertNil(noKey.keyHex)
        XCTAssertEqual(noKey.protection, .noEnclave)
        let noCipher = prepare(key: (key, true), ready: false)
        XCTAssertNil(noCipher.keyHex)
        XCTAssertEqual(noCipher.protection, .notYet)
        XCTAssertEqual(DatabaseEncryption.fileState(url), .plain)
    }

    func testASealedFileWhoseKeyIsGoneIsSetAsideNotDeleted() async throws {
        try await makePlainDatabase()
        XCTAssertEqual(prepare(key: (key, true)).keyHex, key)
        let other = String(repeating: "fedcba9876543210", count: 4)
        let prepared = prepare(key: (other, true))
        XCTAssertEqual(prepared.keyHex, other)
        XCTAssertNotNil(prepared.note)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        let aside = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.contains("-set-aside-") && $0.hasSuffix(".db") }
        XCTAssertEqual(aside.count, 1)
        XCTAssertNotNil(DatabaseEncryption.tableCounts(dir.appendingPathComponent(aside[0]), keyHex: key), "the set-aside file no longer opens")
    }

    func testAnInterruptedSwapPutsThePlainFileBack() async throws {
        try await makePlainDatabase()
        // The app quit after moving the plain file aside and before the copy took its place.
        try FileManager.default.moveItem(at: url, to: URL(fileURLWithPath: url.path + DatabaseEncryption.oldSuffix))
        let prepared = prepare(key: (key, false))
        XCTAssertEqual(prepared.keyHex, key)
        XCTAssertEqual(DatabaseEncryption.fileState(url), .sealed)
        XCTAssertGreaterThan(DatabaseEncryption.tableCounts(url, keyHex: key)?.values.reduce(0, +) ?? 0, 0)
    }
}
