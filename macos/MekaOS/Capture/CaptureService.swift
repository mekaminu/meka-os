import AppKit
import Foundation

/// Capture from anywhere on the Mac (build plan M1): select text in any app → Services → "Add to MEKA".
/// The text becomes one task in the shared core (first line the title, the rest kept in the notes), exactly as the
/// menu-bar field and the Fold's share sheet do. The Services entry is declared in Info.plist (`NSServices`).
///
/// A request can arrive before the core has started (macOS launches the app to serve it), so captures wait in
/// `CaptureInbox` until the model is ready.
@MainActor
final class CaptureInbox {
    static let shared = CaptureInbox()

    private var pending: [(text: String, subject: String?)] = []
    private var sink: ((String, String?) -> Void)?

    /// A capture from Services. Empty text is ignored.
    func receive(_ text: String, subject: String? = nil) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        if let sink { sink(text, subject) } else { pending.append((text, subject)) }
    }

    /// Called once the core is running; anything that arrived earlier is captured now, in order.
    func attach(_ sink: @escaping (String, String?) -> Void) {
        self.sink = sink
        let waiting = pending
        pending.removeAll()
        waiting.forEach { sink($0.text, $0.subject) }
    }

    var waitingCount: Int { pending.count }
}

/// The object macOS calls for the "Add to MEKA" service (`NSMessage` = captureText).
final class CaptureServiceProvider: NSObject {
    @objc func captureText(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        guard let text = pboard.string(forType: .string), !text.isEmpty else {
            error.pointee = "There was no text to add to MEKA." as NSString
            return
        }
        MainActor.assumeIsolated { CaptureInbox.shared.receive(text) }
    }
}

/// Registers the Services provider at launch.
final class MekaAppDelegate: NSObject, NSApplicationDelegate {
    private let provider = CaptureServiceProvider()

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.servicesProvider = provider
        NSUpdateDynamicServices()
    }
}
