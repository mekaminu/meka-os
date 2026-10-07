import CryptoKit
import Foundation
import Security

/// Device secrets as small sealed files instead of Keychain items (build plan M1: "fix the Mac keychain prompts").
///
/// Why: macOS binds a Keychain item to the app's code signature. Without an Apple-issued certificate every rebuild is a
/// "different app", so each launch asked for the login keychain password. Files don't have that ACL, so the secrets are
/// sealed instead with a key that only this Mac's Secure Enclave can use:
///
/// - One P-256 key-agreement key is created in the Secure Enclave. Its `dataRepresentation` is an opaque blob that the
///   enclave encrypted for itself; it is useless on any other Mac, so it can sit in a file (`enclave.key`). It is usable
///   only while the Mac is unlocked (`WhenUnlockedThisDeviceOnly`).
/// - Each value is sealed ECIES-style: a fresh ephemeral P-256 key agrees a secret with the enclave key, HKDF-SHA256
///   (salted with the ephemeral public key, bound to the file's name) gives an AES-256-GCM key, and the file holds
///   `0x01 ‖ ephemeral public key (65 bytes, X9.63) ‖ nonce ‖ ciphertext ‖ tag`. Swapping or editing a file fails to open.
/// - Files live in Application Support/os.meka.mac/keys (folder 0700, files 0600), never in iCloud or Time Machine.
///
/// What it protects: copies of the files (a backup, another Mac, a disk image) are unreadable. It does not protect
/// against something already running as Meka on this unlocked Mac, which is no worse than an "Always allow" Keychain item.
nonisolated final class SealedKeyFiles: @unchecked Sendable {
    /// The private half that unseals; the Secure Enclave key in the app, a software key in tests.
    nonisolated struct Opener: @unchecked Sendable {
        let publicKey: P256.KeyAgreement.PublicKey
        let agree: (P256.KeyAgreement.PublicKey) throws -> SharedSecret
    }

    nonisolated enum Failure: Error, Equatable { case badFormat, cannotOpen, cannotWrite }

    static let formatVersion: UInt8 = 1
    private static let enclaveKeyFile = "enclave.key"

    let directory: URL
    private let opener: Opener

    init(directory: URL, opener: Opener) {
        self.directory = directory
        self.opener = opener
    }

    /// The app's store, backed by the Secure Enclave. Nil when this Mac has no enclave (an Intel Mac without a T2 chip)
    /// or the enclave key can't be made or read; the caller then keeps using the Keychain.
    static func secureEnclave(directory: URL = defaultDirectory()) -> SealedKeyFiles? {
        guard SecureEnclave.isAvailable, ensureDirectory(directory) else { return nil }
        let keyURL = directory.appendingPathComponent(enclaveKeyFile)
        let key: SecureEnclave.P256.KeyAgreement.PrivateKey
        if FileManager.default.fileExists(atPath: keyURL.path) {
            // A blob that can't be read or no longer opens (enclave reset, file copied from another Mac) is never
            // replaced: the sealed values would be lost with it. The caller falls back to the Keychain.
            guard let blob = try? Data(contentsOf: keyURL),
                  let k = try? SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: blob) else { return nil }
            key = k
        } else {
            var error: Unmanaged<CFError>?
            guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, .privateKeyUsage, &error),
                  let k = try? SecureEnclave.P256.KeyAgreement.PrivateKey(accessControl: access),
                  write(k.dataRepresentation, to: keyURL) else { return nil }
            key = k
        }
        return SealedKeyFiles(directory: directory, opener: Opener(publicKey: key.publicKey, agree: { try key.sharedSecretFromKeyAgreement(with: $0) }))
    }

    static func defaultDirectory() -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("os.meka.mac", isDirectory: true)
            .appendingPathComponent("keys", isDirectory: true)
    }

    // MARK: - Values

    nonisolated enum Lookup: Equatable { case found(String), missing, unreadable }

    func read(_ name: String) -> Lookup {
        let url = fileURL(name)
        guard FileManager.default.fileExists(atPath: url.path) else { return .missing }
        guard let data = try? Data(contentsOf: url), let plain = try? open(data, name: name),
              let s = String(data: plain, encoding: .utf8) else { return .unreadable }
        return .found(s)
    }

    @discardableResult
    func write(_ name: String, _ value: String) -> Bool {
        guard Self.ensureDirectory(directory), let sealed = try? seal(Data(value.utf8), name: name) else { return false }
        return Self.write(sealed, to: fileURL(name))
    }

    // MARK: - Sealing (internal for tests)

    func seal(_ plain: Data, name: String) throws -> Data {
        let ephemeral = P256.KeyAgreement.PrivateKey()
        let secret = try ephemeral.sharedSecretFromKeyAgreement(with: opener.publicKey)
        let epk = ephemeral.publicKey.x963Representation
        let box = try AES.GCM.seal(plain, using: Self.symmetricKey(secret, epk: epk, name: name))
        guard let combined = box.combined else { throw Failure.cannotWrite }
        return Data([Self.formatVersion]) + epk + combined
    }

    func open(_ sealed: Data, name: String) throws -> Data {
        let bytes = [UInt8](sealed)
        guard bytes.count > 1 + 65 + 12 + 16, bytes[0] == Self.formatVersion else { throw Failure.badFormat }
        let epk = Data(bytes[1..<66])
        guard let ephemeral = try? P256.KeyAgreement.PublicKey(x963Representation: epk) else { throw Failure.badFormat }
        do {
            let secret = try opener.agree(ephemeral)
            let box = try AES.GCM.SealedBox(combined: Data(bytes[66...]))
            return try AES.GCM.open(box, using: Self.symmetricKey(secret, epk: epk, name: name))
        } catch {
            throw Failure.cannotOpen
        }
    }

    private static func symmetricKey(_ secret: SharedSecret, epk: Data, name: String) -> SymmetricKey {
        secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: epk, sharedInfo: Data("meka-os sealed key file v1|\(name)".utf8), outputByteCount: 32)
    }

    // MARK: - Files

    private func fileURL(_ name: String) -> URL {
        precondition(name.allSatisfy { $0.isLetter || $0.isNumber || $0 == "_" }, "sealed file names are plain words")
        return directory.appendingPathComponent(name + ".sealed")
    }

    private static func ensureDirectory(_ url: URL) -> Bool {
        let fm = FileManager.default
        do {
            try fm.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
            try fm.setAttributes([.posixPermissions: 0o700], ofItemAtPath: url.path)
            var u = url
            var values = URLResourceValues()
            values.isExcludedFromBackup = true   // useless elsewhere anyway; keeps them out of Time Machine
            try? u.setResourceValues(values)
            return true
        } catch {
            return false
        }
    }

    private static func write(_ data: Data, to url: URL) -> Bool {
        do {
            try data.write(to: url, options: [.atomic])
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
            return true
        } catch {
            return false
        }
    }
}
