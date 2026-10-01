import Foundation
import Security

/// Device identity and enrolment secret, kept in the Keychain (ADR-005). This device only, never synced to iCloud.
struct DeviceIdentity {
    let householdID: String
    let deviceID: String
    let deviceSecret: String?

    private static let service = "os.meka.mac.identity"

    static func loadOrCreate() -> DeviceIdentity {
        let device = read("device_id") ?? {
            var bytes = [UInt8](repeating: 0, count: 6)
            _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
            let id = "mac" + bytes.map { String(format: "%02x", $0) }.joined()
            write("device_id", id)
            return id
        }()
        let household = read("household_id") ?? { write("household_id", "local"); return "local" }()
        return DeviceIdentity(householdID: household, deviceID: device, deviceSecret: read("device_secret"))
    }

    static func enrol(householdID: String, deviceID: String, secret: String) {
        precondition(secret.count == 64, "invalid device secret")
        write("household_id", householdID)
        write("device_id", deviceID)
        write("device_secret", secret)
    }

    private static func read(_ account: String) -> String? {
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

    private static func write(_ account: String, _ value: String) {
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
