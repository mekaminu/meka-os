@preconcurrency import MekaKit
import Foundation
import UserNotifications

/// Posts what the shared notification governor says, through macOS notifications. The rules (tiers, quiet hours,
/// digests) live in the Kotlin core; this only delivers. Titles and digests replace earlier ones with the same id.
@MainActor
enum MacNotifier {
    /// Same value as the core's `MekaCore.DIGEST_KEY`.
    static let digestKey = "meka.digest"

    /// Asks macOS once; afterwards the choice lives in System Settings → Notifications → Meka.
    static func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    /// Posts what the governor says and returns the notices that reached macOS (none when Meka isn't allowed to
    /// notify), for the weekly review's Interruptions count. A digest is passive, so it never counts.
    /// Whether macOS lets Meka notify at all (System Settings → Notifications → Meka).
    static func allowed() async -> Bool {
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        return status == .authorized || status == .provisional
    }

    /// macOS hasn't been asked yet (so Turn on asks, rather than opening System Settings).
    static func notAsked() async -> Bool {
        await UNUserNotificationCenter.current().notificationSettings().authorizationStatus == .notDetermined
    }

    /// When MEKA last posted on this Mac (ms; 0 = never), for Health's Notifications row.
    static let lastPostKey = "meka.notify.lastPostMs"
    static var lastPostMs: Int64 { Int64(UserDefaults.standard.double(forKey: lastPostKey)) }
    private static func notePosted() {
        UserDefaults.standard.set(Date().timeIntervalSince1970 * 1000, forKey: lastPostKey)
    }

    /// System Settings → Notifications (macOS opens MEKA's page from the list).
    static let settingsURL = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension")!

    /// Send a test (notifications never seen, the Mac's half): posts one notification now and says whether macOS took
    /// it. Not posted while macOS won't let MEKA notify.
    static func postTest() async -> Bool {
        guard await allowed() else { return false }
        let content = UNMutableNotificationContent()
        content.title = MacNotifyRules.shared.TEST_TITLE
        content.body = MacNotifyRules.shared.TEST_TEXT
        content.threadIdentifier = "meka"
        content.interruptionLevel = .active
        content.userInfo = ["target": "TODAY"]
        let ok = (try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "meka.test", content: content, trigger: nil))) != nil
        if ok { notePosted() }
        return ok
    }

    @discardableResult
    static func post(_ result: GovernorResult) async -> [String] {
        let center = UNUserNotificationCenter.current()
        // What no longer applies comes down first (an approval answered on either device, or run out). Plain Strings.
        let withdraw: [String] = result.withdraw
        if !withdraw.isEmpty { center.removeDeliveredNotifications(withIdentifiers: withdraw) }
        guard await allowed() else { return [] }
        var posted: [String] = []
        for notice in result.post {
            let content = UNMutableNotificationContent()
            content.title = notice.title
            content.body = notice.text
            content.threadIdentifier = "meka"
            content.interruptionLevel = .active
            var info: [String: String] = ["target": NotifyRules.shared.targetName(t: notice.target)]
            // Bills due: the page Pay opens (where Meka pays it); MEKA never pays.
            if let link = notice.link { info["link"] = link }
            content.userInfo = info
            // Buttons answered from Notification Center ("Did you go?": Went · Didn't go), Gym slice 2b. The Mac can't
            // send a WhatsApp message, so "Leaving now" offers Copy here (Send and Edit are the Fold's).
            let buttons = NotifyRules.shared.actionsOn(n: notice, send: false).map { (NotifyRules.shared.actionName(a: $0), NotifyRules.shared.actionLabel(a: $0)) }
            if !buttons.isEmpty {
                let category = categoryID(names: buttons.map(\.0))
                await ensureCategory(category, buttons: buttons)
                content.categoryIdentifier = category
            }
            // Posted at its postId: an approval's "expires soon" replaces its first notice. Counted by its key.
            if (try? await center.add(UNNotificationRequest(identifier: notice.postId, content: content, trigger: nil))) != nil {
                posted.append(notice.key)
            }
        }
        if let digest = result.digest {
            let content = UNMutableNotificationContent()
            content.title = digest.title
            content.subtitle = digest.summary
            content.body = digest.lines.joined(separator: "\n")
            content.threadIdentifier = "meka-digest"
            content.interruptionLevel = .passive   // a digest never interrupts
            content.userInfo = ["target": NotifyRules.shared.targetName(t: digest.target)]
            // The digest's key (MekaCore.DIGEST_KEY) tells the core it went out, for the activity log; it still never
            // counts as an interruption.
            if (try? await center.add(UNNotificationRequest(identifier: digestKey, content: content, trigger: nil))) != nil {
                posted.append(digestKey)
            }
        }
        if !posted.isEmpty { notePosted() }
        return posted
    }

    // MARK: Buttons (Gym slice 2b)

    /// The category of the quiet note that replaces "Did you go?" once answered, and its one button.
    static let answeredCategory = "meka.answered"
    static let undoAction = "meka.undo"
    /// Copy on a "Leaving now" notification: the core's `NoticeAction.COPY_MESSAGE` name.
    static let copyMessageAction = "COPY_MESSAGE"
    /// Listen on Sunday's week ahead: the core's `NoticeAction.LISTEN` name. It opens MEKA, so it brings it forward.
    static let listenAction = "LISTEN"
    /// Pay on a bill's reminder: the core's `NoticeAction.PAY` name. It opens the saved pay link in the browser.
    static let payAction = "PAY"
    /// Wake 10 min earlier on tomorrow's trains heads-up: the core's `NoticeAction.WAKE_EARLIER` name.
    static let wakeEarlierAction = "WAKE_EARLIER"
    /// Later tonight on Sunday's weekly review: the core's `NoticeAction.LATER_TONIGHT` name (weekly reflection, slice 4).
    static let laterTonightAction = "LATER_TONIGHT"
    /// All still fit on "Time to check Alex's sizes": the core's `NoticeAction.ALL_STILL_FIT` name (kids' admin, slice 6).
    static let allStillFitAction = "ALL_STILL_FIT"

    /// One macOS category per set of buttons: "meka.actions.WENT.DIDNT_GO".
    nonisolated static func categoryID(names: [String]) -> String { "meka.actions." + names.joined(separator: ".") }

    /// Registers a category the first time a notice needs it (the others are kept).
    private static func ensureCategory(_ id: String, buttons: [(String, String)]) async {
        let center = UNUserNotificationCenter.current()
        let existing = await center.notificationCategories()
        guard !existing.contains(where: { $0.identifier == id }) else { return }
        let actions = buttons.map {
            UNNotificationAction(identifier: $0.0, title: $0.1, options: $0.0 == listenAction ? UNNotificationActionOptions.foreground : UNNotificationActionOptions())
        }
        center.setNotificationCategories(existing.union([
            UNNotificationCategory(identifier: id, actions: actions, intentIdentifiers: [], options: []),
        ]))
    }

    /// After Went / Didn't go: the same notification becomes a quiet note ("Went · 2 of 3 this week · Next: Thu 17:45")
    /// with Undo.
    static func postAnswered(key: String, heading: String, line: String, habitId: String) async {
        guard await allowed() else { return }
        await ensureCategory(answeredCategory, buttons: [(undoAction, "Undo")])
        let content = UNMutableNotificationContent()
        content.title = heading
        content.body = line
        content.threadIdentifier = "meka"
        content.interruptionLevel = .passive
        content.categoryIdentifier = answeredCategory
        content.userInfo = ["target": "TODAY", "habit": habitId]
        _ = try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: key, content: content, trigger: nil))
    }

    static func remove(key: String) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [key])
    }
}

/// A button pressed on one of MEKA's notifications: the notice's key, the button and (for Undo) the habit.
struct NotificationAnswer: Equatable, Sendable {
    let key: String
    let action: String
    let habit: String?
    /// The notification's words ("Leaving now": what Copy copies).
    var body: String? = nil
    /// The page Pay opens (Bills due).
    var link: String? = nil
    /// A noticed screenshot's file (screenshots and photos, slice 2): Handle this reads it.
    var picture: String? = nil
}

/// Buttons can be pressed before the core has started (macOS launches MEKA to deliver them), so they wait here until
/// the model is ready, like `CaptureInbox`. Plain clicks and dismissals aren't answers and are ignored.
@MainActor
final class NotificationActionInbox {
    static let shared = NotificationActionInbox()

    private var pending: [NotificationAnswer] = []
    private var sink: ((NotificationAnswer) -> Void)?

    func receive(_ a: NotificationAnswer) {
        guard a.action != UNNotificationDefaultActionIdentifier, a.action != UNNotificationDismissActionIdentifier else { return }
        if let sink { sink(a) } else { pending.append(a) }
    }

    func attach(_ sink: @escaping (NotificationAnswer) -> Void) {
        self.sink = sink
        let waiting = pending
        pending.removeAll()
        waiting.forEach(sink)
    }

    var waitingCount: Int { pending.count }
}

/// MEKA's notification-centre delegate: hands each button press to `NotificationActionInbox` as plain strings (no
/// Kotlin object crosses isolation here). It doesn't change how notifications show while MEKA is in front.
@MainActor
final class MacNotificationActions: NSObject, UNUserNotificationCenterDelegate {
    static let shared = MacNotificationActions()

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let request = response.notification.request
        let picture = request.content.userInfo["picture"] as? String
        // A click on a noticed screenshot's notification is its Handle this (screenshots and photos, slice 2).
        let action = picture != nil && response.actionIdentifier == UNNotificationDefaultActionIdentifier
            ? MacScreenshotWatch.action : response.actionIdentifier
        let answer = NotificationAnswer(
            key: request.identifier, action: action, habit: request.content.userInfo["habit"] as? String,
            body: request.content.body, link: request.content.userInfo["link"] as? String, picture: picture
        )
        await NotificationActionInbox.shared.receive(answer)
    }
}
