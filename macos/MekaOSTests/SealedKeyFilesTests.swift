import CryptoKit
import XCTest
@testable import MekaOS

/// Sealed key files (the Keychain-prompt fix). CI runners have no Secure Enclave, so a software key stands in for it;
/// the format and the move from the Keychain are the same either way.
final class SealedKeyFilesTests: XCTestCase {
    private var dir: URL!

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("meka-sealed-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
    }

    private func store(_ key: P256.KeyAgreement.PrivateKey = P256.KeyAgreement.PrivateKey()) -> SealedKeyFiles {
        SealedKeyFiles(directory: dir, opener: .init(publicKey: key.publicKey, agree: { try key.sharedSecretFromKeyAgreement(with: $0) }))
    }

    func testValuesRoundTripAndMissingIsMissing() {
        let s = store()
        XCTAssertEqual(s.read("device_secret"), .missing)
        XCTAssertTrue(s.write("device_secret", String(repeating: "ab", count: 32)))
        XCTAssertEqual(s.read("device_secret"), .found(String(repeating: "ab", count: 32)))
        XCTAssertTrue(s.write("device_secret", "rotated"))
        XCTAssertEqual(s.read("device_secret"), .found("rotated"))
    }

    func testFilesArePrivateAndHoldNoPlaintext() throws {
        let s = store()
        s.write("server_url", "https://example.invalid")
        let file = dir.appendingPathComponent("server_url.sealed")
        let attrs = try FileManager.default.attributesOfItem(atPath: file.path)
        XCTAssertEqual((attrs[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        let dirAttrs = try FileManager.default.attributesOfItem(atPath: dir.path)
        XCTAssertEqual((dirAttrs[.posixPermissions] as? NSNumber)?.intValue, 0o700)
        let raw = try Data(contentsOf: file)
        XCTAssertEqual(raw.first, SealedKeyFiles.formatVersion)
        XCTAssertNil(raw.range(of: Data("example.invalid".utf8)))
    }

    func testAnotherKeyCannotOpenAndEditsOrSwapsAreRefused() throws {
        let key = P256.KeyAgreement.PrivateKey()
        let s = store(key)
        s.write("device_id", "mac0123456789ab")
        s.write("household_id", "home")

        // Another Mac (another enclave key) can't read the copy.
        XCTAssertEqual(store().read("device_id"), .unreadable)

        // One flipped byte, or a file renamed over another, fails to open.
        let idFile = dir.appendingPathComponent("device_id.sealed")
        var bytes = [UInt8](try Data(contentsOf: idFile))
        bytes[bytes.count - 1] ^= 0x01
        XCTAssertThrowsError(try s.open(Data(bytes), name: "device_id"))
        let household = try Data(contentsOf: dir.appendingPathComponent("household_id.sealed"))
        XCTAssertThrowsError(try s.open(household, name: "device_id"))
        XCTAssertThrowsError(try s.open(Data([SealedKeyFiles.formatVersion, 1, 2, 3]), name: "device_id"))
        XCTAssertEqual(store(key).read("device_id"), .found("mac0123456789ab"))
    }

    func testMoveFromTheKeychainCopiesWhatExistsOnce() {
        let s = store()
        var asked: [String] = []
        let old: (String) -> DeviceIdentity.Lookup = { account in
            asked.append(account)
            switch account {
            case "device_id": return .found("mac0123456789ab")
            case "household_id": return .found("home")
            default: return .missing
            }
        }
        XCTAssertTrue(DeviceIdentity.migrate(into: s, from: old))
        XCTAssertEqual(s.read("device_id"), .found("mac0123456789ab"))
        XCTAssertEqual(s.read("household_id"), .found("home"))
        XCTAssertEqual(s.read("device_secret"), .missing)
        XCTAssertEqual(asked, DeviceIdentity.accounts)

        // Moved once: the Keychain isn't asked again.
        asked = []
        XCTAssertTrue(DeviceIdentity.migrate(into: s, from: old))
        XCTAssertEqual(asked, [])
    }

    func testAnUnreadableOldItemMovesNothingSoTheNextLaunchCanTryAgain() {
        let s = store()
        let cancelled: (String) -> DeviceIdentity.Lookup = { account in
            account == "device_secret" ? .unreadable(-128) : (account == "device_id" ? .found("mac0123456789ab") : .missing)
        }
        XCTAssertFalse(DeviceIdentity.migrate(into: s, from: cancelled))
        XCTAssertEqual(s.read("device_id"), .missing)

        let allowed: (String) -> DeviceIdentity.Lookup = { account in
            account == "device_secret" ? .found(String(repeating: "cd", count: 32)) : (account == "device_id" ? .found("mac0123456789ab") : .missing)
        }
        XCTAssertTrue(DeviceIdentity.migrate(into: s, from: allowed))
        XCTAssertEqual(s.read("device_secret"), .found(String(repeating: "cd", count: 32)))
        XCTAssertEqual(s.read("device_id"), .found("mac0123456789ab"))
    }

    func testAValueAlreadySealedIsNotOverwrittenByTheOldOne() {
        let s = store()
        s.write("server_url", "https://new.example.invalid")
        XCTAssertTrue(DeviceIdentity.migrate(into: s, from: { $0 == "server_url" ? .found("https://old.example.invalid") : .missing }))
        XCTAssertEqual(s.read("server_url"), .found("https://new.example.invalid"))
    }
}
