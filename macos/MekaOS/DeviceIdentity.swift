import Foundation
import Security

/// Device identity and enrolment secret, kept in the Keychain (ADR-005). This device only, never synced to iCloud.
struct DeviceIdentity {
    let householdID: String
    let deviceID: String
    let deviceSecret: String?
    let serverURL: String?

    /// Shared by both of Meka's devices; part of every op, so it never changes once set.
    static let household = "home"

    // v3: items created by builds signed with an Apple Development certificate (tools/install-mac.sh), which macOS
    // binds to the Team ID, so rebuilds keep access without a password prompt. Older v1/v2 items are left untouched.
    nonisolated private static let service = "os.meka.mac.identity.v3"

    static func loadOrCreate() -> DeviceIdentity {
        let device = read("device_id") ?? {
            var bytes = [UInt8](repeating: 0, count: 6)
            _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
            let id = "mac" + bytes.map { String(format: "%02x", $0) }.joined()
            write("device_id", id)
            return id
        }()
        let household = read("household_id") ?? { write("household_id", Self.household); return Self.household }()
        return DeviceIdentity(householdID: household, deviceID: device, deviceSecret: read("device_secret"), serverURL: read("server_url"))
    }

    static func saveEnrolment(serverURL: String, secret: String) {
        precondition(secret.count == 64, "invalid device secret")
        write("server_url", serverURL)
        write("device_secret", secret)
    }

    enum Lookup { case found(String), missing, unreadable(OSStatus) }

    /// The device signing key's stored form (an enclave handle or a software key), see MacDeviceKey. Distinguishes
    /// "never created" from "exists but unreadable", so a keychain hiccup never silently replaces the key.
    nonisolated static func signingKey() -> Lookup {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "device_signing_key",
            kSecReturnData as String: true,
        ]
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        if status == errSecItemNotFound { return .missing }
        guard status == errSecSuccess, let data = out as? Data, let s = String(data: data, encoding: .utf8) else { return .unreadable(status) }
        return .found(s)
    }
    nonisolated static func saveSigningKey(_ value: String) { write("device_signing_key", value) }

    nonisolated private static func read(_ account: String) -> String? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    nonisolated private static func write(_ account: String, _ value: String) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
}
