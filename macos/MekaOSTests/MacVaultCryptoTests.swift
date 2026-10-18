import CryptoKit
@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// The Mac's Vault crypto (Vault slice 2a): the parts that need no Secure Enclave (a one-off key's ECDH and the
/// AES-GCM box), in the same format the Fold uses (12-byte nonce, ciphertext, 16-byte tag; X.509 DER public keys).
final class MacVaultCryptoTests: XCTestCase {
    func testAOneOffAgreementMatchesTheOtherSideAndTheBoxOpensOnlyWithItsKey() throws {
        let crypto = MacVaultCrypto()
        let mac = P256.KeyAgreement.PrivateKey()
        let once = try XCTUnwrap(crypto.agreeOnce(publicKey: mac.publicKey.derRepresentation.base64EncodedString()))
        let ephemeral = try P256.KeyAgreement.PublicKey(derRepresentation: XCTUnwrap(Data(base64Encoded: once.publicKey)))
        let shared = try mac.sharedSecretFromKeyAgreement(with: ephemeral).withUnsafeBytes { Data($0) }
        XCTAssertEqual(shared.base64EncodedString(), once.shared)
        XCTAssertEqual(shared.count, 32)

        let key = Data((0..<32).map { UInt8($0) }).base64EncodedString()
        let plain = Data((0..<32).map { UInt8($0 * 7 & 0xff) }).base64EncodedString()
        let sealed = try XCTUnwrap(crypto.sealBox(key: key, plain: plain))
        XCTAssertEqual(Data(base64Encoded: sealed)?.count, 12 + 32 + 16)
        XCTAssertEqual(crypto.openBox(key: key, sealed: sealed), plain)
        XCTAssertNil(crypto.openBox(key: Data(repeating: 9, count: 32).base64EncodedString(), sealed: sealed))
        XCTAssertNil(crypto.sealBox(key: "c2hvcnQ=", plain: plain))
        XCTAssertNil(crypto.agreeOnce(publicKey: "not a key"))
    }
}
