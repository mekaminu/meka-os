@testable import MekaOS
@preconcurrency import MekaKit
import XCTest

/// The desktop News widget (news ticker slice 3b) says its lines in Swift: they must match the core's words.
final class DeskNewsSnapshotTests: XCTestCase {
    func testAgesMatchTheCore() {
        let hour: Int64 = 3_600_000
        for gap: Int64 in [0, 60_000, 5 * 60_000, 25 * 60_000, 59 * 60_000, hour, 2 * hour + 59 * 60_000, 23 * hour, 30 * hour, -60_000] {
            XCTAssertEqual(DeskNewsSnapshot.age(publishedAtMs: 10 * hour, nowMs: 10 * hour + gap),
                           NewsRules.shared.age(publishedAtMs: 10 * hour, nowMs: 10 * hour + gap), "gap \(gap)")
        }
    }

    func testLinesSpokenAndTheMatchGoingAtTheFinalWhistle() throws {
        let story = DeskNewsSnapshot.Card(id: "b1", label: "Barça", title: "Pedri returns", source: "Sport",
                                          publishedAtMs: 0, fixedLine: nil, picture: "0123456789abcdef0123456789abcdef.jpg",
                                          tileInitial: "S", isBarca: true, openUrl: "mekaos://news?story=b1", untilMs: Int64.max)
        let match = DeskNewsSnapshot.Card(id: "match", label: "MATCHDAY", title: "Barça v Real Madrid", source: "",
                                          publishedAtMs: 0, fixedLine: "21:00 · in 3 h", picture: nil, tileInitial: "⚽",
                                          isBarca: true, openUrl: "mekaos://news", untilMs: 7_200_000)
        XCTAssertEqual(DeskNewsSnapshot.line(story, nowMs: 2 * 3_600_000), "Sport · 2 h ago")
        XCTAssertEqual(DeskNewsSnapshot.line(match, nowMs: 0), "21:00 · in 3 h")
        XCTAssertEqual(DeskNewsSnapshot.spoken(story, nowMs: 0), "Barça · Sport · just now: Pedri returns")
        let snap = DeskNewsSnapshot(cards: [match, story], emptyTitle: "No news yet", emptyLine: "", writtenAtMs: 0)
        XCTAssertEqual(snap.showing(nowMs: 7_199_999).map(\.id), ["match", "b1"])
        XCTAssertEqual(snap.showing(nowMs: 7_200_000).map(\.id), ["b1"])
        // What the app writes is what the widget reads.
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("desk-news-\(UUID().uuidString).json")
        try JSONEncoder().encode(snap).write(to: url)
        XCTAssertEqual(DeskNewsSnapshot.read(from: url), snap)
        XCTAssertNil(DeskNewsSnapshot.read(from: url.appendingPathExtension("missing")))
    }

    func testOnlyServerKeysNamePicturesAndTheFolderIsTheRealHomes() {
        XCTAssertTrue(DeskNewsSnapshot.isPictureName("0123456789abcdef0123456789abcdef.jpg"))
        XCTAssertFalse(DeskNewsSnapshot.isPictureName("../0123456789abcdef0123456789abcd.jpg"))
        XCTAssertFalse(DeskNewsSnapshot.isPictureName("0123456789ABCDEF0123456789abcdef.jpg"))
        XCTAssertFalse(DeskNewsSnapshot.isPictureName("news.json"))
        XCTAssertTrue(DeskNewsSnapshot.folder.path.hasSuffix("/Library/Application Support/MekaOS/NewsWidget"))
        XCTAssertEqual(DeskNewsSnapshot.refreshSeconds * 1000, Double(DeskNewsWidgetRules.shared.REFRESH_MS))
    }

    @MainActor
    func testTheWidgetsLinksOpenNewsOnTheStory() throws {
        XCTAssertEqual(DeskNewsLink.storyId(try XCTUnwrap(URL(string: "mekaos://news?story=b1"))), "b1")
        XCTAssertNil(DeskNewsLink.storyId(try XCTUnwrap(URL(string: "mekaos://news"))))
        XCTAssertNil(DeskNewsLink.storyId(try XCTUnwrap(URL(string: "mekaos://news?story=a%26b"))))
        XCTAssertNil(DeskNewsLink.storyId(try XCTUnwrap(URL(string: "mekaos://publish-fold-update?story=b1"))))
        XCTAssertEqual(DeskNewsWidgetRules.shared.openUrl(storyId: "b1"), "mekaos://news?story=b1")
    }
}
