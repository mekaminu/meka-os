import Foundation
import LocalAuthentication

/// Touch ID for approving (approvals inbox, slice 1; ADR-006: financial and trading approvals always need biometric
/// confirmation). Biometrics only: no password fallback, so a yes is always Meka's finger. Since Trading approvals
/// slice 2 Touch ID only ever unlocks the approval key (MacApprovalKey) for one signature; only Strings come back, on
/// the main actor; the LAContext stays here, kept alive while the system asks.
@MainActor
enum MacBiometric {
    private static var context: LAContext?

    /// Whether this Mac can take Touch ID now (a Mac without it, or with none enrolled, can't approve: the Fold can).
    static var available: Bool {
        var error: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
    }

    /// Trading approvals, slice 2: Touch ID, then the approval key signs [message] with that same unlocked context
    /// (MacApprovalKey). Only Strings cross back, on the main actor.
    static func sign(reason: String, message: String, done: @escaping @MainActor @Sendable (MacApprovalKey.Signed) -> Void) {
        let ctx = LAContext()
        ctx.localizedFallbackTitle = "" // no "Use Password…"
        context = ctx
        ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason) { @Sendable (ok, _) in
            Task { @MainActor in
                let unlocked = context
                context = nil
                guard ok, let unlocked else { done(.failed); return }
                done(MacApprovalKey.sign(message, context: unlocked))
            }
        }
    }
}
