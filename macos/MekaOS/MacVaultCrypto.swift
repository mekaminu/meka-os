import CryptoKit
import Foundation
@preconcurrency import MekaKit

/// The Mac's crypto for the Vault key (Vault slice 2a; ADR-005 addendum 2026-10-18), like the Fold's FoldVaultCrypto:
/// a P-256 key-agreement key in the Secure Enclave (only its enclave-bound handle is kept, in DeviceIdentity's sealed
/// files), ECDH with it or with a one-off key, and AES-256-GCM (CryptoKit's combined box: 12-byte nonce, ciphertext,
/// 16-byte tag). The HKDF in between is the core's, so both platforms derive the same keys. A Mac without a Secure
/// Enclave can't hold the Vault key (agreementPublicKey is nil).
///
/// Called from Kotlin on the core's thread, so it is not main-actor isolated; the key is made once under a lock.
nonisolated final class MacVaultCrypto: NSObject, VaultCrypto, @unchecked Sendable {
    private let lock = NSLock()
    private var cached: SecureEnclave.P256.KeyAgreement.PrivateKey?
    /// A stored handle that wouldn't open: never replaced silently (the Vault waits rather than asking afresh).
    private var broken = false

    private func agreementKey() -> SecureEnclave.P256.KeyAgreement.PrivateKey? {
        lock.lock()
        defer { lock.unlock() }
        if let cached { return cached }
        guard !broken, SecureEnclave.isAvailable, let stored = DeviceIdentity.vaultAgreementKey() else { return nil }
        switch stored {
        case .found(let handle):
            guard let blob = Data(base64Encoded: handle),
                  let key = try? SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: blob) else {
                broken = true
                return nil
            }
            cached = key
            return key
        case .unreadable:
            return nil
        case .missing:
            guard let key = try? SecureEnclave.P256.KeyAgreement.PrivateKey(),
                  DeviceIdentity.saveVaultAgreementKey(key.dataRepresentation.base64EncodedString()) else { return nil }
            cached = key
            return key
        }
    }

    func agreementPublicKey() -> String? {
        agreementKey()?.publicKey.derRepresentation.base64EncodedString()
    }

    func agree(publicKey: String) -> String? {
        guard let key = agreementKey(), let der = Data(base64Encoded: publicKey),
              let other = try? P256.KeyAgreement.PublicKey(derRepresentation: der),
              let shared = try? key.sharedSecretFromKeyAgreement(with: other) else { return nil }
        return shared.withUnsafeBytes { Data($0) }.base64EncodedString()
    }

    func agreeOnce(publicKey: String) -> VaultAgreement? {
        guard let der = Data(base64Encoded: publicKey),
              let other = try? P256.KeyAgreement.PublicKey(derRepresentation: der) else { return nil }
        let once = P256.KeyAgreement.PrivateKey()
        guard let shared = try? once.sharedSecretFromKeyAgreement(with: other) else { return nil }
        let secret = shared.withUnsafeBytes { Data($0) }.base64EncodedString()
        return VaultAgreement(publicKey: once.publicKey.derRepresentation.base64EncodedString(), shared: secret)
    }

    func sealBox(key: String, plain: String) -> String? {
        guard let k = Data(base64Encoded: key), k.count == 32, let p = Data(base64Encoded: plain),
              let box = try? AES.GCM.seal(p, using: SymmetricKey(data: k)), let combined = box.combined else { return nil }
        return combined.base64EncodedString()
    }

    func openBox(key: String, sealed: String) -> String? {
        guard let k = Data(base64Encoded: key), k.count == 32, let s = Data(base64Encoded: sealed),
              let box = try? AES.GCM.SealedBox(combined: s),
              let p = try? AES.GCM.open(box, using: SymmetricKey(data: k)) else { return nil }
        return p.base64EncodedString()
    }
}
