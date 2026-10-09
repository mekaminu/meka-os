@preconcurrency import MekaKit
import XCTest

/// Task title doesn't save (Meka, 2026-10-08 21:48): the Mac's title field follows the core's `TextAutosave`.
final class TextAutosaveTests: XCTestCase {
    func testRenameThenCloseKeepsTheTitle() {
        // Closing saves what was typed; reopening shows it.
        XCTAssertEqual(TextAutosave.shared.titleToSave(typed: "Buy milk ", saved: "Buy mlik"), "Buy milk")
        XCTAssertTrue(TextAutosave.shared.adoptSaved(typed: "Buy mlik", saved: "Buy milk", dirty: false))
    }

    func testBlankIsNeverSavedAndASaveMidTypingKeepsTheSpace() {
        XCTAssertNil(TextAutosave.shared.titleToSave(typed: "  ", saved: "Buy milk"))
        XCTAssertFalse(TextAutosave.shared.adoptSaved(typed: "Buy ", saved: "Buy", dirty: false))
        XCTAssertFalse(TextAutosave.shared.adoptSaved(typed: "Buy milk!", saved: "Buy oat milk", dirty: true))
    }

    func testAStepTypedButNotAddedIsAddedOnClose() {
        XCTAssertEqual(TextAutosave.shared.pendingAdd(typed: " Ring the school "), "Ring the school")
        XCTAssertNil(TextAutosave.shared.pendingAdd(typed: " "))
    }
}
