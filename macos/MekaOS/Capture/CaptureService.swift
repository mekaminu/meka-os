import AppKit
import Foundation
import UserNotifications

/// Capture from anywhere on the Mac (build plan M1): select text in any app → Services → "Add to MEKA".
/// The text becomes one task in the shared core (first line the title, the rest kept in the notes), exactly as the
/// menu-bar field and the Fold's share sheet do. The Services entries are declared in Info.plist (`NSServices`).
/// "Handle this with MEKA" (V1) hands the text to the core's `handleThis` instead: MEKA reads it and offers what to add
/// as cards in Needs you; nothing is added until Meka taps Add.
///
/// A request can arrive before the core has started (macOS launches the app to serve it), so captures wait in
/// `CaptureInbox` until the model is ready.
@MainActor
final class CaptureInbox {
    static let shared = CaptureInbox()

    /// What a Services request asks for: a task ("Add to Navilife"), Handle this, Reading's up next, or the train queue.
    enum Kind: Sendable { case task, handle, reading, queue }

    private var pending: [(text: String, subject: String?, kind: Kind)] = []
    private var sink: ((String, String?, Kind) -> Void)?
    private var pendingPictures: [MacPictureReader.Picture] = []
    private var pictureSink: ((MacPictureReader.Picture) -> Void)?

    /// A capture from Services (`kind`: "Add to Navilife", "Handle this with Navilife" or "Add to Navilife Reading").
    /// Empty text is ignored.
    func receive(_ text: String, subject: String? = nil, kind: Kind = .task) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        if let sink { sink(text, subject, kind) } else { pending.append((text, subject, kind)) }
    }

    /// Called once the core is running; anything that arrived earlier is captured (or handled) now, in order.
    func attach(_ sink: @escaping (String, String?, Kind) -> Void) {
        self.sink = sink
        let waiting = pending
        pending.removeAll()
        waiting.forEach { sink($0.text, $0.subject, $0.kind) }
    }

    /// A picture from Services; read once the core is running.
    func receive(picture: MacPictureReader.Picture) {
        if let pictureSink { pictureSink(picture) } else { pendingPictures.append(picture) }
    }

    func attachPictures(_ sink: @escaping (MacPictureReader.Picture) -> Void) {
        pictureSink = sink
        let waiting = pendingPictures
        pendingPictures.removeAll()
        waiting.forEach(sink)
    }

    var waitingCount: Int { pending.count + pendingPictures.count }
}

/// The object macOS calls for the "Add to MEKA" service (`NSMessage` = captureText).
final class CaptureServiceProvider: NSObject {
    @objc func captureText(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        guard let text = pboard.string(forType: .string), !text.isEmpty else {
            error.pointee = "There was no text to add to Navilife." as NSString
            return
        }
        MainActor.assumeIsolated { CaptureInbox.shared.receive(text) }
    }

    /// "Handle this with MEKA": the text goes to the core's `handleThis` (only a String crosses).
    @objc func handleText(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        guard let text = pboard.string(forType: .string), !text.isEmpty else {
            error.pointee = "There was no text for Navilife to handle." as NSString
            return
        }
        MainActor.assumeIsolated { CaptureInbox.shared.receive(text, kind: .handle) }
    }

    /// "Add to Navilife Reading" (reading and learning, slice 3): a selected book title, podcast or course (or its
    /// link) goes under Reading's UP NEXT, with Undo on the bar.
    @objc func readingText(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        guard let text = pboard.string(forType: .string), !text.isEmpty else {
            error.pointee = "There was nothing to add to your reading list." as NSString
            return
        }
        MainActor.assumeIsolated { CaptureInbox.shared.receive(text, kind: .reading) }
    }

    /// "Save for the train with Navilife" (train-time queue completeness): the selection's first web link goes in the
    /// train queue, as the Fold's share sheet's For the train does, with Undo on the bar.
    @objc func queueText(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        guard let text = pboard.string(forType: .string), !text.isEmpty else {
            error.pointee = "There was no link to save for the train." as NSString
            return
        }
        MainActor.assumeIsolated { CaptureInbox.shared.receive(text, kind: .queue) }
    }

    /// "Handle this picture with Navilife" (screenshots and photos, slice 1): an image, or an image file from Finder, is
    /// read on this Mac with Vision and only its words go to Handle this.
    @objc func handlePicture(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        guard let picture = MacPictureReader.picture(on: pboard) else {
            error.pointee = "There was no picture for Navilife to read." as NSString
            return
        }
        MainActor.assumeIsolated { CaptureInbox.shared.receive(picture: picture) }
    }
}

/// Registers the Services provider at launch.
final class MekaAppDelegate: NSObject, NSApplicationDelegate {
    private let provider = CaptureServiceProvider()

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.servicesProvider = provider
        NSUpdateDynamicServices()
        // Went / Didn't go on "Did you go?" (Gym slice 2b); set at launch so a press that launched MEKA arrives.
        UNUserNotificationCenter.current().delegate = MacNotificationActions.shared
        // "Listen when I open MEKA": a plain launch (not for a link, a file or a notification) may listen.
        let plain = (notification.userInfo?[NSApplication.launchIsDefaultUserInfoKey] as? Bool) ?? true
        MainActor.assumeIsolated { MacTalkOnOpen.shared.launched(plain: plain) }
    }

    func applicationDidBecomeActive(_ notification: Notification) {
        MainActor.assumeIsolated {
            MacTalkOnOpen.shared.becameActive()
            MacHelloWake.shared.setAppActive(true)
        }
    }

    func applicationDidResignActive(_ notification: Notification) {
        MainActor.assumeIsolated {
            MacTalkOnOpen.shared.resignedActive()
            MacHelloWake.shared.setAppActive(false)
        }
    }

    /// A Dock click: "Listen when I open MEKA" listens when it brought MEKA forward; the window comes back as usual.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        MainActor.assumeIsolated { MacTalkOnOpen.shared.reopened() }
        return true
    }
}
