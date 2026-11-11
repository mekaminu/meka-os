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
        // Meka 2026-11-11: no tile is a dead end; this week's goals open Goals.
        XCTAssertEqual(tiles.first.map { AlsoTodayRules.shared.place(tile: $0) }, AlsoPlace.goals)
        XCTAssertEqual(tiles.first.map { AlsoTodayRules.shared.hint(tile: $0) }, "Opens Goals")
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

    /// Meka 2026-11-11: the parcels tile unfolds every parcel; trading opens Money's Trading; a clash the Calendar.
    func testTheTilesThatOnlyToldNowOpenSomething() {
        let parcel = AlsoTile(kind: AlsoKind.parcels, key: "PARCELS", value: "Parcel arriving today", line: "Shop One",
                              spoken: "Parcel arriving today.", lit: false, targetId: nil)
        XCTAssertEqual(AlsoTodayRules.shared.place(tile: parcel), AlsoPlace.unfold)
        XCTAssertEqual(AlsoTodayRules.shared.hint(tile: parcel), "Shows the parcels")
        let trading = AlsoTile(kind: AlsoKind.trading, key: "TRADING", value: "up today", line: "2 positions open",
                               spoken: "Trading up today.", lit: false, targetId: nil)
        XCTAssertEqual(AlsoTodayRules.shared.place(tile: trading), AlsoPlace.moneyTrading)
        let clash = AlsoTodayRules.shared.clash(text: "Training at 16:55 while you're at work — who's taking Alex?")
        XCTAssertEqual(AlsoTodayRules.shared.place(tile: clash), AlsoPlace.calendar)
        let today: Int64 = 20_400
        let parcels = [Parcel(id: "a", account: "Mail", state: ParcelState.outForDelivery, day: today, window: "10:15–11:15",
                              carrier: "Evri", merchant: "Shop One", lastAtMs: 0)]
        let d = ParcelRules.shared.detail(parcels: parcels, todayDay: today, officeDay: false)
        XCTAssertEqual(d?.rows.first?.status, "Out for delivery")
        XCTAssertEqual(d?.rows.first?.meta, "10:15–11:15 · with Evri")
    }
}
