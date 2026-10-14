@preconcurrency import MekaKit
import Foundation
import UserNotifications

/// Screenshots and photos that do something, slice 2 (the Mac's half): **new screenshots, noticed**. Off until he turns
/// on "Notice new screenshots" in the menu bar's capture. While on, a Spotlight query watches for new screen captures
/// (macOS marks them `kMDItemIsScreenCapture`, wherever they're saved); each one from the last few minutes is read on
/// this Mac with Vision (`MacPictureReader`) and, when the core says it looks like something Navilife can act on, one
/// quiet notification offers it: "Screenshot · looks like a booking" with its telling line and **Handle this**, which
/// reads it again into Handle this (`CoreModel.handlePicture`). The picture and its words never leave the Mac unless he
/// presses Handle this (then the words only, as for shared text). The switch and the newest screenshot looked at are
/// kept in this Mac's defaults (never synced). Only Strings, URLs and Int64s cross into the reading task.
final class MacScreenshotWatch {
    static let shared = MacScreenshotWatch()

    nonisolated static let enabledKey = "meka.screenshots.on"
    nonisolated static let sinceKey = "meka.screenshots.sinceMs"
    /// The notification's Handle this button (and a click on the notification itself).
    nonisolated static let action = "HANDLE_SCREENSHOT"
    nonisolated static let category = "meka.screenshot"

    private var query: NSMetadataQuery?
    private var observers: [NSObjectProtocol] = []
    /// Paths already read this run (a query update can list one twice).
    private var read: Set<String> = []

    var isOn: Bool { UserDefaults.standard.bool(forKey: Self.enabledKey) }

    private var sinceMs: Int64 { Int64(UserDefaults.standard.double(forKey: Self.sinceKey)) }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Turns it on from now (screenshots already taken aren't offered) or off.
    func set(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: Self.enabledKey)
        UserDefaults.standard.set(Double(Self.nowMs()), forKey: Self.sinceKey)
        if on { start() } else { stop() }
    }

    /// At launch, and when turned on: starts the Spotlight query for screen captures made since `sinceMs`.
    func start() {
        guard isOn, query == nil else { return }
        let q = NSMetadataQuery()
        let since = Date(timeIntervalSince1970: Double(sinceMs) / 1000)
        q.predicate = NSPredicate(format: "kMDItemIsScreenCapture == 1 AND kMDItemContentCreationDate >= %@", since as NSDate)
        q.searchScopes = [NSMetadataQueryUserHomeScope]
        let center = NotificationCenter.default
        for name in [Notification.Name.NSMetadataQueryDidFinishGathering, .NSMetadataQueryDidUpdate] {
            observers.append(center.addObserver(forName: name, object: q, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.look() }
            })
        }
        query = q
        q.start()
    }

    func stop() {
        query?.stop()
        query = nil
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers = []
    }

    /// One look: the new screen captures the core picks are read on this Mac and offered.
    private func look() {
        guard let q = query, isOn else { return }
        q.disableUpdates()
        var candidates: [ScreenshotCandidate] = []
        for i in 0..<q.resultCount {
            guard let item = q.result(at: i) as? NSMetadataItem,
                  let path = item.value(forAttribute: NSMetadataItemPathKey) as? String else { continue }
            let made = (item.value(forAttribute: "kMDItemContentCreationDate") as? Date)
                ?? (item.value(forAttribute: NSMetadataItemFSCreationDateKey) as? Date)
                ?? .distantPast
            candidates.append(ScreenshotCandidate(id: path, takenAtMs: Int64(made.timeIntervalSince1970 * 1000), screenshot: true))
        }
        q.enableUpdates()
        let rules = ScreenshotRules.shared
        let now = Self.nowMs()
        let since = sinceMs
        let picked: [String] = rules.pick(candidates: candidates, sinceMs: since, nowMs: now).map(\.id).filter { !read.contains($0) }
        UserDefaults.standard.set(Double(rules.nextSince(candidates: candidates, sinceMs: since, nowMs: now)), forKey: Self.sinceKey)
        guard !picked.isEmpty else { return }
        read.formUnion(picked)
        for path in picked.reversed() {
            let url = URL(fileURLWithPath: path)
            Task {
                guard case .words(let text) = await MacPictureReader.read(url: url),
                      let notice = ScreenshotRules.shared.noticeFor(text: text) else { return }
                await Self.post(path: path, title: notice.title, text: notice.text, button: notice.action)
            }
        }
    }

    /// The quiet notification: no sound, Handle this, the screenshot's path in its userInfo (stays on this Mac).
    private static func post(path: String, title: String, text: String, button: String) async {
        guard await MacNotifier.allowed() else { return }
        let center = UNUserNotificationCenter.current()
        let existing = await center.notificationCategories()
        if !existing.contains(where: { $0.identifier == category }) {
            center.setNotificationCategories(existing.union([
                UNNotificationCategory(
                    identifier: category,
                    actions: [UNNotificationAction(identifier: action, title: button, options: .foreground)],
                    intentIdentifiers: [], options: []
                ),
            ]))
        }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = text
        content.threadIdentifier = "meka.screenshots"
        content.interruptionLevel = .active
        content.categoryIdentifier = category
        content.userInfo = ["target": "NEEDS_YOU", "picture": path]
        _ = try? await center.add(UNNotificationRequest(identifier: "meka.screenshot.\(abs(path.hashValue))", content: content, trigger: nil))
    }
}
