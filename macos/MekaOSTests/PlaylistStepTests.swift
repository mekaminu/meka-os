@preconcurrency import MekaKit
import XCTest
@testable import MekaOS

/// "Play my messages" (call assistant polish 8c): the core's steps become plain Swift values for MekaSpeaker.
final class PlaylistStepTests: XCTestCase {
    func testStepsCrossAsPlainValues() {
        let steps = PlaylistStep.of([
            PlayStepSay(text: "You've got one voice message."),
            PlayStepRecording(heldId: "h0123456789abcdef", otherwise: "They said: hi."),
        ])
        XCTAssertEqual(steps.count, 2)
        guard case .say(let line) = steps[0] else { return XCTFail("a line first") }
        XCTAssertEqual(line, "You've got one voice message.")
        guard case .recording(let id, let otherwise) = steps[1] else { return XCTFail("then a recording") }
        XCTAssertEqual(id, "h0123456789abcdef")
        XCTAssertEqual(otherwise, "They said: hi.")
    }
}
