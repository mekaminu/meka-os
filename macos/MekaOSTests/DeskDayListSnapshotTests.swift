@testable import MekaOS
@preconcurrency import MekaKit
import XCTest

/// The desktop Day list widget (the Day list, slice 6b): what the app writes is the core's words, the widget reads it
/// back, and its links open the Day list or tick only what they name.
final class DeskDayListSnapshotTests: XCTestCase {
    private func widgetView(open: Int, more: String? = nil) -> WidgetDayList {
        let rows = (0..<open).map { i in
            WidgetDayListRow(id: "t\(i)", title: "Task \(i)", time: i == 0 ? "09:00" : nil, length: "30m",
                             spoken: "Task \(i), 30m long")
        }
        return WidgetDayList(title: "Today", line: "1 of \(open + 1) done · ends about 11:15", progressPercent: 25,
                             rows: rows, more: more, empty: false)
    }

    @MainActor
    func testTheSnapshotCarriesTheCoresWordsAndReadsBack() throws {
        let w = widgetView(open: 2, more: "+1 more")
        let snap = DeskDayListWidgetWriter.snapshot(w, epochDay: 20_402, nowMs: 5)
        XCTAssertEqual(snap.title, "Today")
        XCTAssertEqual(snap.rows.map(\.id), ["t0", "t1"])
        XCTAssertEqual(snap.rows[0].time, "09:00")
        XCTAssertNil(snap.rows[1].time)
        XCTAssertEqual(snap.rows[0].tickLabel, "Mark done: Task 0")
        XCTAssertEqual(snap.emptyHint, "Click + Add tasks and type one after another")
        XCTAssertEqual(snap.addLabel, DayListWidgetRules.shared.ADD)
        XCTAssertEqual(snap.spoken, DayListWidgetRules.shared.describe(w: w))
        XCTAssertEqual(snap.progressPercent, 25)
        XCTAssertEqual(snap.more, "+1 more")
        XCTAssertTrue(snap.isFor(epochDay: 20_402))
        XCTAssertFalse(snap.isFor(epochDay: 20_403))

        let url = FileManager.default.temporaryDirectory.appendingPathComponent("desk-day-\(UUID().uuidString).json")
        try JSONEncoder().encode(snap).write(to: url)
        XCTAssertEqual(DeskDayListSnapshot.read(from: url), snap)
        XCTAssertNil(DeskDayListSnapshot.read(from: url.appendingPathExtension("missing")))
        XCTAssertTrue(DeskDayListSnapshot.folder.path.hasSuffix("/Library/Application Support/MekaOS/DayListWidget"))
        XCTAssertEqual(DeskDayListSnapshot.refreshSeconds * 1000, Double(DayListWidgetRules.shared.DESK_REFRESH_MS))
    }

    func testSmallsHiddenRowsJoinTheMoreLine() {
        XCTAssertEqual(DeskDayListSnapshot.moreLine(hidden: 1, coreMore: nil), "+1 more")
        XCTAssertEqual(DeskDayListSnapshot.moreLine(hidden: 1, coreMore: "+2 more"), "+3 more")
    }

    func testTheDayNumberCountsFromMidnightInTheZone() throws {
        let london = try XCTUnwrap(TimeZone(identifier: "Europe/London"))
        // Tue 10 Nov 2026 23:59:59 and Wed 11 Nov 00:00:01 in London (GMT).
        XCTAssertEqual(DeskDayListSnapshot.localEpochDay(Date(timeIntervalSince1970: 1_794_355_199), in: london), 20_767)
        XCTAssertEqual(DeskDayListSnapshot.localEpochDay(Date(timeIntervalSince1970: 1_794_355_201), in: london), 20_768)
    }

    func testTheLinksRoundTripAndRefuseAnythingElse() throws {
        for link in [DeskDayListLink.open, .add, .done(taskId: "abc-123"), .done(taskId: "todoist:42 & more=+é")] {
            XCTAssertEqual(DeskDayListLink.parse(link.url), link, link.url.absoluteString)
        }
        XCTAssertEqual(DeskDayListLink.open.url.absoluteString, "mekaos://day-list")
        XCTAssertEqual(DeskDayListLink.add.url.absoluteString, "mekaos://day-list?add=1")
        XCTAssertNil(DeskDayListLink.parse(try XCTUnwrap(URL(string: "mekaos://day-list/done"))))
        XCTAssertNil(DeskDayListLink.parse(try XCTUnwrap(URL(string: "mekaos://day-list/done?task="))))
        XCTAssertNil(DeskDayListLink.parse(try XCTUnwrap(URL(string: "mekaos://day-list/done?task=a%2Fb"))))
        XCTAssertNil(DeskDayListLink.parse(try XCTUnwrap(URL(string: "mekaos://day-list/delete?task=a"))))
        XCTAssertNil(DeskDayListLink.parse(try XCTUnwrap(URL(string: "mekaos://news?task=a"))))
        XCTAssertNil(DeskDayListLink.parse(try XCTUnwrap(URL(string: "https://day-list/done?task=a"))))
    }
}
