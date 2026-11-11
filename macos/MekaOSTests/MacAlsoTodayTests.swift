@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// The Today rebuild, slice 2d-2b: the Mac's ALSO TODAY reads core AlsoTodayRules (the tiles, where a tap goes, the hint).
final class MacAlsoTodayTests: XCTestCase {
    func testTheHeaderNotesBecomeTilesAndEachSaysWhereItGoes() {
        let tiles = AlsoTodayRules.shared.withHeader(
            tiles: [], route: nil, goalsLine: "This week: 2 of 3 goals moving", goalsSpoken: "2 of 3 goals moving.",
            prep: nil, pressure: nil, checkin: nil, review: nil)
        XCTAssertEqual(tiles.map(\.key), ["GOALS"])
        XCTAssertEqual(tiles.first?.value, "2 of 3 goals moving")
        XCTAssertEqual(tiles.first.map { AlsoTodayRules.shared.place(tile: $0) }, AlsoPlace.info)
        let bins = AlsoTile(kind: AlsoKind.bins, key: "BINS", value: "Black bin", line: "out tonight",
                            spoken: "Black bin out tonight.", lit: true, targetId: nil)
        XCTAssertEqual(AlsoTodayRules.shared.place(tile: bins), AlsoPlace.bins)
        XCTAssertEqual(AlsoTodayRules.shared.hint(tile: bins), "Opens Bins")
        XCTAssertTrue(AlsoTodayRules.shared.unfolds(kind: AlsoKind.studyWeek))
    }

    /// Meka 2026-11-11 07:30: the Trains tile stays in Also today and a click unfolds the train details.
    @MainActor
    func testTheTrainsTileUnfoldsItsDetails() {
        let line = RouteLine(text: "South Rail · good service", lit: false, spoken: "Trains: South Rail, good service",
                             brief: "South Rail, good service")
        let tiles = AlsoTodayRules.shared.withHeader(
            tiles: [], route: line, goalsLine: nil, goalsSpoken: "",
            prep: nil, pressure: nil, checkin: nil, review: nil)
        guard let tile = tiles.first else { return XCTFail("no trains tile") }
        XCTAssertEqual(tile.kind, AlsoKind.trains)
        XCTAssertEqual(AlsoTodayRules.shared.place(tile: tile), AlsoPlace.unfold)
        XCTAssertEqual(AlsoTodayRules.shared.hint(tile: tile), "Shows the train details")
        let row = TrainDetailRow(time: "07:12", status: "on time", platform: "platform 3", arrives: "arrives 07:41", bad: false)
        XCTAssertEqual(row.meta, "platform 3 · arrives 07:41")
        let detail = TrainDetail(title: "South Rail · Oakfield to Oakfield Junction", status: "good service", leaveBy: nil,
                                 rows: [row], noTrains: nil, change: nil, fallback: nil, fallbackRows: [], checked: nil,
                                 lit: false, spoken: "South Rail.")
        _ = MacTrainsDetail(detail: detail, palette: MekaPalette.dark).body
    }
}
