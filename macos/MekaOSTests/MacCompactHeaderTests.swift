@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// The Today rebuild, slice 2d-2a: the Mac's compact header reads core TodayHeaderRules and WatchFaceRules.
final class MacCompactHeaderTests: XCTestCase {
    @MainActor
    func testTheTilesSayTasksNextAndMailAndEachOpensItsPlace() {
        let tiles = TodayHeaderRules.shared.tiles(timeline: DayTimeline.companion.EMPTY, done: [], mail: nil)
        XCTAssertEqual(tiles.map(\.key), ["TASKS", "NEXT"])
        XCTAssertEqual(tiles.last?.value, "Free")
        XCTAssertNil(TodayHeaderRules.shared.nextRow(timeline: DayTimeline.companion.EMPTY))
        XCTAssertEqual(MacHeaderTilesRow.hint(HeaderTileKind.tasks), "Opens today's list")
        XCTAssertEqual(MacHeaderTilesRow.hint(HeaderTileKind.mail), "Opens mail")
    }

    func testTheClockIsSmallAndTheWeatherIsAChip() {
        XCTAssertEqual(WatchFaceRules.shared.CLOCK_DP, 40)
        XCTAssertEqual(TodayHeaderRules.shared.weatherChip(nowLine: "14° · light rain from 16:00"), "14° light rain")
    }
}
