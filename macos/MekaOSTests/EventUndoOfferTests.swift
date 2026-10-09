import XCTest
@testable import MekaOS

/// The undo bar animates on `model.eventUndo` changing, so an offer must compare as itself (Talk's action carries
/// the core's `AskUndo`, which isn't Equatable in Swift, so the offer is compared by identity).
final class EventUndoOfferTests: XCTestCase {
    func testAnOfferEqualsItselfAndANewOfferWithTheSameWordsDoesNot() {
        let a = EventUndoOffer(message: "Done 2 things · Undo", action: .talk([], [0, 1]))
        let copy = a
        let b = EventUndoOffer(message: "Done 2 things · Undo", action: .talk([], [0, 1]))
        XCTAssertEqual(a, copy)
        XCTAssertNotEqual(a, b)
        XCTAssertNotEqual(a, EventUndoOffer(message: "Hidden · Undo", action: .showEvent("e1")))
    }
}
