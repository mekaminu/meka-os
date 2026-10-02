import CryptoKit
import Foundation
@preconcurrency import MekaKit

/// The Mac's request-signing key (ADR-005): ECDSA P-256 in the Secure Enclave when the Mac has one, so the private key
/// cannot be copied off this machine. Only an opaque, enclave-bound handle is kept in the Keychain. Macs without an
/// enclave fall back to a software key stored in the Keychain.
///
/// Called from Kotlin on background threads, so it is not main-actor isolated; its state never changes after init.
nonisolated final class MacDeviceKey: NSObject, DeviceKey, @unchecked Sendable {
    nonisolated private enum Backing {
        case enclave(SecureEnclave.P256.Signing.PrivateKey)
        case software(P256.Signing.PrivateKey)
    }

    private let backing: Backing
    let publicKeyDerBase64: String

    override init() {
        backing = MacDeviceKey.loadOrCreate()
        switch backing {
        case .enclave(let k): publicKeyDerBase64 = k.publicKey.derRepresentation.base64EncodedString()
        case .software(let k): publicKeyDerBase64 = k.publicKey.derRepresentation.base64EncodedString()
        }
        super.init()
    }

    func signBase64(message: String) -> String {
        let data = Data(message.utf8)
        // Signing only fails if the enclave key was invalidated; an empty signature makes the server refuse the request.
        switch backing {
        case .enclave(let k): return (try? k.signature(for: data).derRepresentation.base64EncodedString()) ?? ""
        case .software(let k): return (try? k.signature(for: data).derRepresentation.base64EncodedString()) ?? ""
        }
    }

    /// Creates a key only when none was ever stored. If a stored key can't be read or decoded, an ephemeral key is
    /// used and NOT saved: the server will refuse it and the app shows "signed out — reconnect", which re-enrols
    /// cleanly, instead of silently replacing the registered key.
    private static func loadOrCreate() -> Backing {
        switch DeviceIdentity.signingKey() {
        case .found(let stored):
            if let blob = Data(base64Encoded: String(stored.dropFirst(3))) {
                if stored.hasPrefix("se:"), let k = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: blob) { return .enclave(k) }
                if stored.hasPrefix("sw:"), let k = try? P256.Signing.PrivateKey(rawRepresentation: blob) { return .software(k) }
            }
            return .software(P256.Signing.PrivateKey())
        case .unreadable:
            return .software(P256.Signing.PrivateKey())
        case .missing:
            break
        }
        if SecureEnclave.isAvailable, let k = try? SecureEnclave.P256.Signing.PrivateKey() {
            DeviceIdentity.saveSigningKey("se:" + k.dataRepresentation.base64EncodedString())
            return .enclave(k)
        }
        let k = P256.Signing.PrivateKey()
        DeviceIdentity.saveSigningKey("sw:" + k.rawRepresentation.base64EncodedString())
        return .software(k)
    }
}
