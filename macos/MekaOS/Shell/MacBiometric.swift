import Foundation
import LocalAuthentication

/// Touch ID for approving (approvals inbox, slice 1; ADR-006: financial and trading approvals always need biometric
/// confirmation). Biometrics only: no password fallback, so a yes is always Meka's finger. Only a Bool comes back, on
/// the main actor; the LAContext stays here, kept alive while the system asks.
@MainActor
enum MacBiometric {
    private static var context: LAContext?

    /// Whether this Mac can take Touch ID now (a Mac without it, or with none enrolled, can't approve: the Fold can).
    static var available: Bool {
        var error: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
    }

    /// Asks for Touch ID with [reason]; [done] gets true only when it succeeded.
    static func confirm(reason: String, done: @escaping @MainActor @Sendable (Bool) -> Void) {
        let ctx = LAContext()
        ctx.localizedFallbackTitle = "" // no "Use Password…"
        context = ctx
        ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason) { @Sendable (ok, _) in
            Task { @MainActor in
                context = nil
                done(ok)
            }
        }
    }
}
