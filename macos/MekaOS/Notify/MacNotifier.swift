@preconcurrency import MekaKit
import Foundation
import UserNotifications

/// Posts what the shared notification governor says, through macOS notifications. The rules (tiers, quiet hours,
/// digests) live in the Kotlin core; this only delivers. Titles and digests replace earlier ones with the same id.
enum MacNotifier {
    /// Asks macOS once; afterwards the choice lives in System Settings → Notifications → Meka.
    static func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    static func post(_ result: GovernorResult) async {
        let center = UNUserNotificationCenter.current()
        for notice in result.post {
            let content = UNMutableNotificationContent()
            content.title = notice.title
            content.body = notice.text
            content.threadIdentifier = "meka"
            content.interruptionLevel = .active
            content.userInfo = ["target": NotifyRules.shared.targetName(t: notice.target)]
            try? await center.add(UNNotificationRequest(identifier: notice.key, content: content, trigger: nil))
        }
        if let digest = result.digest {
            let content = UNMutableNotificationContent()
            content.title = digest.title
            content.subtitle = digest.summary
            content.body = digest.lines.joined(separator: "\n")
            content.threadIdentifier = "meka-digest"
            content.interruptionLevel = .passive   // a digest never interrupts
            content.userInfo = ["target": NotifyRules.shared.targetName(t: digest.target)]
            try? await center.add(UNNotificationRequest(identifier: "meka.digest", content: content, trigger: nil))
        }
    }
}
