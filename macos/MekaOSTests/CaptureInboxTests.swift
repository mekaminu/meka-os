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

        inbox.receive("Parents' evening Tue 6pm", kind: .handle)
        XCTAssertEqual(inbox.waitingCount, 3)

        var got: [String] = []
        var handled: [String] = []
        var reading: [String] = []
        inbox.receive("Dune by Frank Herbert", kind: .reading)
        XCTAssertEqual(inbox.waitingCount, 4)
        inbox.attach { text, subject, kind in
            switch kind {
            case .handle: handled.append(text)
            case .reading: reading.append(text)
            case .task: got.append(subject ?? text)
            }
        }
        XCTAssertEqual(reading, ["Dune by Frank Herbert"])
        XCTAssertEqual(got, ["First\nwith a note", "Subject"])
        XCTAssertEqual(handled, ["Parents' evening Tue 6pm"])
        XCTAssertEqual(inbox.waitingCount, 0)

        inbox.receive("Third")
        XCTAssertEqual(got.last, "Third")
        inbox.receive("Reply slip by Friday", kind: .handle)
        XCTAssertEqual(handled.last, "Reply slip by Friday")
        XCTAssertEqual(inbox.waitingCount, 0)
    }

    /// Screenshots and photos, slice 1: a picture from Services waits for the core too, then is read straight away.
    @MainActor
    func testPicturesWaitUntilTheCoreIsReady() {
        let inbox = CaptureInbox()
        inbox.receive(picture: .bytes(Data([1, 2, 3])))
        inbox.receive(picture: .file(URL(fileURLWithPath: "/tmp/Screenshot.png")))
        XCTAssertEqual(inbox.waitingCount, 2)
        var read = 0
        inbox.attachPictures { _ in read += 1 }
        XCTAssertEqual(read, 2)
        XCTAssertEqual(inbox.waitingCount, 0)
        inbox.receive(picture: .bytes(Data([4])))
        XCTAssertEqual(read, 3)
    }
}
