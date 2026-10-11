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

        inbox.receive("Parents' evening Tue 6pm", handle: true)
        XCTAssertEqual(inbox.waitingCount, 3)

        var got: [String] = []
        var handled: [String] = []
        inbox.attach { text, subject, handle in
            if handle { handled.append(text) } else { got.append(subject ?? text) }
        }
        XCTAssertEqual(got, ["First\nwith a note", "Subject"])
        XCTAssertEqual(handled, ["Parents' evening Tue 6pm"])
        XCTAssertEqual(inbox.waitingCount, 0)

        inbox.receive("Third")
        XCTAssertEqual(got.last, "Third")
        inbox.receive("Reply slip by Friday", handle: true)
        XCTAssertEqual(handled.last, "Reply slip by Friday")
        XCTAssertEqual(inbox.waitingCount, 0)
    }
}
