@preconcurrency import MekaKit
import AppKit
import AVFoundation
import Speech

/// "Listen when I open MEKA" on the Mac (Talk without tapping the mic, the Mac's slice): off by default and kept on
/// this Mac (UserDefaults, never synced), switched in Ask → More → Talk. With it on, launching MEKA plainly (the Dock,
/// Finder, Spotlight; not to open a link, a file or a notification) or clicking it in the Dock while it's in the
/// background brings Ask forward with a room check, then the orb listening for up to 6 s (`TalkController.startOnOpen`).
/// An open never asks for the microphone or speech recognition: until both are allowed (by clicking the mic once),
/// nothing happens. The decisions are the core's `TalkOnOpenRules`, shared with the Fold.
///
/// The app delegate reports launches, reopens and activation here; the shell attaches the handler once the core has
/// started, and a launch that arrived before that is handed over then.
@MainActor
final class MacTalkOnOpen {
    static let shared = MacTalkOnOpen()
    static let key = "meka.talk.listenOnOpen"

    static var enabled: Bool { UserDefaults.standard.bool(forKey: key) }

    /// The microphone and speech recognition are both already allowed (checked, never asked).
    static var micAllowed: Bool {
        AVCaptureDevice.authorizationStatus(for: .audio) == .authorized
            && SFSpeechRecognizer.authorizationStatus() == .authorized
    }

    private var pending = false
    private var handler: (() -> Void)?
    private var activatedAt: Date?

    func becameActive() { activatedAt = Date() }
    func resignedActive() { activatedAt = nil }

    /// MEKA finished launching; `plain` is AppKit's "default launch" (false when opened for a link, file or notification).
    func launched(plain: Bool) { opened(plain: plain) }

    /// A Dock click (AppKit's reopen): an open only when it brought MEKA forward.
    func reopened() {
        let ms: KotlinLong?
        if NSApp.isActive {
            let since = activatedAt.map { Int64(Date().timeIntervalSince($0) * 1000) } ?? Int64.max
            ms = KotlinLong(longLong: since)
        } else {
            ms = nil
        }
        opened(plain: TalkOnOpenRules.shared.reopenFromBackground(msSinceActivated: ms))
    }

    /// The shell is ready: opens from now on go straight to `handler`; one that came before goes now.
    func attach(_ handler: @escaping () -> Void) {
        self.handler = handler
        if pending {
            pending = false
            handler()
        }
    }

    private func opened(plain: Bool) {
        guard TalkOnOpenRules.shared.macOpenStart(plainOpen: plain, listenOnOpen: Self.enabled, micAllowed: Self.micAllowed) != nil
        else { return }
        if let handler { handler() } else { pending = true }
    }
}
