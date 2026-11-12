@testable import MekaOS
@preconcurrency import MekaKit
import XCTest

/// The desktop Countdown widget (Countdowns, slice 6): what the app writes is the core's words, the widget reads it back,
/// and its one link opens Countdowns.
final class DeskCountdownSnapshotTests: XCTestCase {
    private func widgetView(empty: Bool = false, lit: Bool = false) -> WidgetCountdown {
        WidgetCountdown(
            name: "Christmas", number: lit ? "Today" : "43", unit: lit ? "" : "days", dateLine: "Fri 25 Dec",
            extra: "turns 9", ringPercent: 140, lit: lit,
            next: [WidgetCountdownLine(name: "Holiday", count: "93 days"), WidgetCountdownLine(name: "Pancake day", count: "tomorrow")],
            spoken: "Countdown. Christmas, 43 days to go, Fri 25 Dec.", empty: empty)
    }

    @MainActor
    func testTheSnapshotCarriesTheCoresWordsAndReadsBack() throws {
        let w = widgetView()
        let snap = DeskCountdownWidgetWriter.snapshot(w, epochDay: 20_769, nowMs: 5)
        XCTAssertEqual(snap.name, "Christmas")
        XCTAssertEqual(snap.number, "43")
        XCTAssertEqual(snap.unit, "days")
        XCTAssertEqual(snap.dateLine, "Fri 25 Dec")
        XCTAssertEqual(snap.extra, "turns 9")
        XCTAssertEqual(snap.ringPercent, 100, "the ring never overdraws")
        XCTAssertFalse(snap.lit)
        XCTAssertEqual(snap.next.map(DeskCountdownSnapshot.nextLine), ["Holiday · 93 days", "Pancake day · tomorrow"])
        XCTAssertEqual(snap.emptyTitle, CountdownWidgetRules.shared.EMPTY)
        XCTAssertEqual(snap.emptyHint, "Click to add a holiday or a birthday")
        XCTAssertEqual(snap.spoken, w.spoken)
        XCTAssertTrue(snap.isFor(epochDay: 20_769))
        XCTAssertFalse(snap.isFor(epochDay: 20_770))

        let url = FileManager.default.temporaryDirectory.appendingPathComponent("desk-countdown-\(UUID().uuidString).json")
        try JSONEncoder().encode(snap).write(to: url)
        XCTAssertEqual(DeskCountdownSnapshot.read(from: url), snap)
        XCTAssertNil(DeskCountdownSnapshot.read(from: url.appendingPathExtension("missing")))
        XCTAssertTrue(DeskCountdownSnapshot.folder.path.hasSuffix("/Library/Application Support/MekaOS/CountdownWidget"))
    }

    @MainActor
    func testOnTheDayAndWithNothingLeft() {
        let today = DeskCountdownWidgetWriter.snapshot(widgetView(lit: true), epochDay: 1, nowMs: 0)
        XCTAssertTrue(today.lit)
        XCTAssertEqual(today.number, "Today")
        XCTAssertEqual(today.unit, "")
        XCTAssertTrue(DeskCountdownWidgetWriter.snapshot(widgetView(empty: true), epochDay: 1, nowMs: 0).empty)
    }

    func testTheLinkOpensCountdownsOnly() throws {
        XCTAssertEqual(DeskCountdownLink.url.absoluteString, "mekaos://countdowns")
        XCTAssertTrue(DeskCountdownLink.matches(DeskCountdownLink.url))
        XCTAssertFalse(DeskCountdownLink.matches(try XCTUnwrap(URL(string: "mekaos://day-list"))))
        XCTAssertFalse(DeskCountdownLink.matches(try XCTUnwrap(URL(string: "https://countdowns"))))
    }
}
