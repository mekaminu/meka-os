import Foundation
import Security

/// Device identity and enrolment secret (ADR-005). This device only, never synced to iCloud.
///
/// Kept in Secure Enclave-sealed files (SealedKeyFiles) so an app not signed by an Apple certificate stops asking for
/// the login keychain password on every rebuild. Values from earlier builds are moved over from the Keychain once (that
/// launch may ask one last time; the old items are left in place, untouched). A Mac without a Secure Enclave, or a
/// launch where an old item couldn't be read (the password prompt was cancelled), keeps using the Keychain as before.
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

    nonisolated enum Lookup { case found(String), missing, unreadable(OSStatus) }

    /// The device signing key's stored form (an enclave handle or a software key), see MacDeviceKey. Distinguishes
    /// "never created" from "exists but unreadable", so a keychain hiccup never silently replaces the key.
    nonisolated static func signingKey() -> Lookup { lookup("device_signing_key") }
    nonisolated static func saveSigningKey(_ value: String) { write("device_signing_key", value) }

    /// Every value this struct keeps. Migration copies exactly these.
    nonisolated static let accounts = ["device_id", "household_id", "device_secret", "server_url", "device_signing_key"]
    nonisolated private static let migratedMarker = "moved_from_keychain_v3"

    /// Where values live for this launch: sealed files, or the Keychain (no enclave, or the move couldn't finish).
    nonisolated private static let files: SealedKeyFiles? = {
        guard let files = SealedKeyFiles.secureEnclave() else { return nil }
        return DeviceIdentity.migrate(into: files, from: DeviceIdentity.keychainLookup) ? files : nil
    }()

    /// Copies the Keychain values into [files] once. All or nothing: if any old item exists but can't be read, nothing
    /// is marked as moved and this launch keeps using the Keychain, so a cancelled prompt never loses the device's
    /// identity (the next launch tries again). Internal for tests.
    nonisolated static func migrate(into files: SealedKeyFiles, from old: (String) -> Lookup) -> Bool {
        if case .found = files.read(migratedMarker) { return true }
        var values: [(String, String)] = []
        for account in accounts {
            switch old(account) {
            case .found(let v): values.append((account, v))
            case .missing: continue
            case .unreadable: return false
            }
        }
        for (account, value) in values {
            // A value already sealed (an earlier move that stopped half way) is newer or the same: keep it.
            if case .missing = files.read(account), !files.write(account, value) { return false }
        }
        return files.write(migratedMarker, "1")
    }

    nonisolated private static func lookup(_ account: String) -> Lookup {
        guard let files else { return keychainLookup(account) }
        switch files.read(account) {
        case .found(let v): return .found(v)
        case .missing: return .missing
        case .unreadable: return .unreadable(errSecDecode)
        }
    }

    nonisolated private static func read(_ account: String) -> String? {
        if case .found(let v) = lookup(account) { return v }
        return nil
    }

    nonisolated private static func write(_ account: String, _ value: String) {
        if let files {
            files.write(account, value)
        } else {
            keychainWrite(account, value)
        }
    }

    nonisolated private static func keychainLookup(_ account: String) -> Lookup {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
        ]
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        if status == errSecItemNotFound { return .missing }
        guard status == errSecSuccess, let data = out as? Data, let s = String(data: data, encoding: .utf8) else { return .unreadable(status) }
        return .found(s)
    }

    nonisolated private static func keychainWrite(_ account: String, _ value: String) {
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
