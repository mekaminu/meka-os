@preconcurrency import MekaKit
import Foundation
import UserNotifications

/// The wake alarm on the Mac (Alarms, slice 1): the Fold rings it full screen; the Mac shows it as a notification at
/// the same moment with Snooze and Dismiss, which sync like the Fold's. One request at a time, replaced whenever the
/// core's next alarm changes (set, moved, snoozed, dismissed here or on the Fold). A critical (or time-sensitive)
/// notification needs an Apple entitlement that comes with the Developer Program (Needs Meka #6, skipped), so it is
/// an ordinary notification with the default sound.
@MainActor
enum MacAlarm {
    nonisolated static let keyPrefix = "meka.alarm."
    nonisolated static let category = "meka.alarm"
    nonisolated static let snoozeAction = "meka.alarm.snooze"
    nonisolated static let dismissAction = "meka.alarm.dismiss"

    /// "id@ringAtMs" of the request now scheduled, so an unchanged alarm isn't rescheduled every refresh.
    private static var scheduled: String?
    private static var scheduledKey: String?

    /// The alarm's id from a notification's identifier, or nil when it isn't an alarm.
    nonisolated static func alarmID(fromKey key: String) -> String? {
        key.hasPrefix(keyPrefix) ? String(key.dropFirst(keyPrefix.count)) : nil
    }

    /// The wake alarm's id is `wake.d<day>` (`AlarmRules.wakeId`); quick alarms and timers don't open the brief.
    nonisolated static func opensBrief(alarmID: String) -> Bool { alarmID.hasPrefix("wake.") }

    static func schedule(_ ring: AlarmRing?) async {
        // Plain values first: nothing Kotlin crosses an await.
        let id = ring?.id
        let atMs = ring?.ringAtMs
        let title = ring.map { "\($0.title) · \($0.timeLabel)" }
        let body = ring.map { $0.snoozeLine ?? $0.line }
        let sig = id.flatMap { i in atMs.map { "\(i)@\($0)" } }
        guard sig != scheduled else { return }
        let center = UNUserNotificationCenter.current()
        if let old = scheduledKey {
            center.removePendingNotificationRequests(withIdentifiers: [old])
            // A dismissed or moved alarm leaves Notification Center too; a snoozed one is replaced by the next ring.
            center.removeDeliveredNotifications(withIdentifiers: [old])
        }
        scheduled = sig
        scheduledKey = nil
        guard let id, let atMs, let title, let body, await MacNotifier.allowed() else { return }
        await ensureCategory()
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.threadIdentifier = "meka-alarm"
        content.interruptionLevel = .active
        content.categoryIdentifier = category
        content.userInfo = ["target": "TODAY"]
        let fireAt = Date(timeIntervalSince1970: Double(atMs) / 1000)
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, fireAt.timeIntervalSinceNow), repeats: false)
        let key = keyPrefix + id
        if (try? await center.add(UNNotificationRequest(identifier: key, content: content, trigger: trigger))) != nil {
            scheduledKey = key
        }
    }

    private static func ensureCategory() async {
        let center = UNUserNotificationCenter.current()
        let existing = await center.notificationCategories()
        guard !existing.contains(where: { $0.identifier == category }) else { return }
        center.setNotificationCategories(existing.union([
            UNNotificationCategory(
                identifier: category,
                actions: [
                    UNNotificationAction(identifier: snoozeAction, title: "Snooze 9 min", options: []),
                    UNNotificationAction(identifier: dismissAction, title: "Dismiss", options: [.foreground]),
                ],
                intentIdentifiers: [], options: []
            ),
        ]))
    }
}
