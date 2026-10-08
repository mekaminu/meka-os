import UserNotifications
import XCTest
@testable import MekaOS

/// Gym slice 2b: buttons pressed on MEKA's notifications wait for the core, then flow straight through.
final class NotificationActionInboxTests: XCTestCase {
    @MainActor
    func testButtonPressesWaitForTheCoreAndPlainClicksAreIgnored() {
        let inbox = NotificationActionInbox()
        inbox.receive(NotificationAnswer(key: "session:gym:20352:ask", action: "WENT", habit: nil))
        inbox.receive(NotificationAnswer(key: "session:gym:20352:ask", action: UNNotificationDefaultActionIdentifier, habit: nil))
        inbox.receive(NotificationAnswer(key: "session:gym:20352:ask", action: UNNotificationDismissActionIdentifier, habit: nil))
        XCTAssertEqual(inbox.waitingCount, 1)

        var got: [NotificationAnswer] = []
        inbox.attach { got.append($0) }
        XCTAssertEqual(got.map(\.action), ["WENT"])
        XCTAssertEqual(inbox.waitingCount, 0)

        inbox.receive(NotificationAnswer(key: "session:gym:20352:ask", action: MacNotifier.undoAction, habit: "gym"))
        XCTAssertEqual(got.last?.habit, "gym")
    }

    func testEachSetOfButtonsHasItsOwnCategory() {
        XCTAssertEqual(MacNotifier.categoryID(names: ["WENT", "DIDNT_GO"]), "meka.actions.WENT.DIDNT_GO")
        XCTAssertNotEqual(MacNotifier.categoryID(names: ["WENT"]), MacNotifier.categoryID(names: ["WENT", "DIDNT_GO"]))
    }
}
