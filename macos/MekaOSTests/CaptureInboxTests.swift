import XCTest
@testable import MekaOS

/// Services captures that arrive before the core starts are kept and delivered in order.
final class CaptureInboxTests: XCTestCase {
    @MainActor
    func testCapturesWaitUntilTheCoreIsReadyThenFlowStraightThrough() {
        let inbox = CaptureInbox()
        inbox.receive("First\nwith a note")
        inbox.receive("   \n ")
        inbox.receive("Second", subject: "Subject")
        XCTAssertEqual(inbox.waitingCount, 2)

        var got: [String] = []
        inbox.attach { text, subject in got.append(subject ?? text) }
        XCTAssertEqual(got, ["First\nwith a note", "Subject"])
        XCTAssertEqual(inbox.waitingCount, 0)

        inbox.receive("Third")
        XCTAssertEqual(got.last, "Third")
        XCTAssertEqual(inbox.waitingCount, 0)
    }
}
