import CryptoKit
import Foundation
import LocalAuthentication
import Security

/// The Mac's approval key (Trading approvals, slice 2; ADR-006 addendum 2026-10-12): ECDSA P-256 in the Secure Enclave
/// that only Touch ID unlocks (`.biometryCurrentSet`: a change to the enrolled fingers kills it), separate from the
/// request-signing key (MacDeviceKey). A yes in Needs you is signed with it right after Touch ID, so the server and
/// Kestrel can check the yes came from Meka's finger on this Mac. Only an enclave-bound handle is stored (DeviceIdentity);
/// the private key never leaves the chip. A Mac without a Secure Enclave or Touch ID has none and can't approve.
@MainActor
enum MacApprovalKey {
    static let label = "Mac"

    enum Signed: Sendable {
        /// Base64 DER signature and the key's public half (X.509 DER, base64).
        case signature(String, publicKey: String)
        /// Touch ID changed since the key was made: a new key was made (register it; approve again).
        case renewed
        case failed
    }

    /// The key's public half (made now if there is none; making it needs no Touch ID), or nil when this Mac can't hold one.
    static func publicKey() -> String? {
        guard let blob = storedBlob() ?? create(),
              let key = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: blob) else { return nil }
        return key.publicKey.derRepresentation.base64EncodedString()
    }

    /// Signs [message] with [context], which Touch ID has just unlocked. A key that no longer signs (the enrolled
    /// fingers changed) is replaced and `.renewed` comes back.
    static func sign(_ message: String, context: LAContext) -> Signed {
        guard let blob = storedBlob() ?? create() else { return .failed }
        if let key = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: blob, authenticationContext: context),
           let sig = try? key.signature(for: Data(message.utf8)) {
            return .signature(sig.derRepresentation.base64EncodedString(), publicKey: key.publicKey.derRepresentation.base64EncodedString())
        }
        return create() != nil ? .renewed : .failed
    }

    private static func storedBlob() -> Data? {
        if case .found(let stored) = DeviceIdentity.approvalKey() { return Data(base64Encoded: stored) }
        return nil
    }

    private static func create() -> Data? {
        guard SecureEnclave.isAvailable,
              let access = SecAccessControlCreateWithFlags(
                  nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, [.privateKeyUsage, .biometryCurrentSet], nil
              ),
              let key = try? SecureEnclave.P256.Signing.PrivateKey(accessControl: access) else { return nil }
        DeviceIdentity.saveApprovalKey(key.dataRepresentation.base64EncodedString())
        return key.dataRepresentation
    }
}
