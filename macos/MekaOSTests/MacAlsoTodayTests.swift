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
}
