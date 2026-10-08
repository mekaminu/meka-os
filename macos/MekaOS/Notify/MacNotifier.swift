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

    @discardableResult
    static func post(_ result: GovernorResult) async -> [String] {
        let center = UNUserNotificationCenter.current()
        guard await allowed() else { return [] }
        var posted: [String] = []
        for notice in result.post {
            let content = UNMutableNotificationContent()
            content.title = notice.title
            content.body = notice.text
            content.threadIdentifier = "meka"
            content.interruptionLevel = .active
            content.userInfo = ["target": NotifyRules.shared.targetName(t: notice.target)]
            // Buttons answered from Notification Center ("Did you go?": Went · Didn't go), Gym slice 2b.
            let buttons = notice.actions.map { (NotifyRules.shared.actionName(a: $0), NotifyRules.shared.actionLabel(a: $0)) }
            if !buttons.isEmpty {
                let category = categoryID(names: buttons.map(\.0))
                await ensureCategory(category, buttons: buttons)
                content.categoryIdentifier = category
            }
            if (try? await center.add(UNNotificationRequest(identifier: notice.key, content: content, trigger: nil))) != nil {
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
        return posted
    }

    // MARK: Buttons (Gym slice 2b)

    /// The category of the quiet note that replaces "Did you go?" once answered, and its one button.
    static let answeredCategory = "meka.answered"
    static let undoAction = "meka.undo"

    /// One macOS category per set of buttons: "meka.actions.WENT.DIDNT_GO".
    nonisolated static func categoryID(names: [String]) -> String { "meka.actions." + names.joined(separator: ".") }

    /// Registers a category the first time a notice needs it (the others are kept).
    private static func ensureCategory(_ id: String, buttons: [(String, String)]) async {
        let center = UNUserNotificationCenter.current()
        let existing = await center.notificationCategories()
        guard !existing.contains(where: { $0.identifier == id }) else { return }
        let actions = buttons.map { UNNotificationAction(identifier: $0.0, title: $0.1, options: []) }
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
        let answer = NotificationAnswer(
            key: request.identifier, action: response.actionIdentifier, habit: request.content.userInfo["habit"] as? String
        )
        await NotificationActionInbox.shared.receive(answer)
    }
}
