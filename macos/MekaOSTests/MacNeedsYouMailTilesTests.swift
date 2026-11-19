@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// The approved Needs you look on the Mac, the mail: today's tidies as four tiles and the one unsubscribe question take
/// the place of the long Archived for you log and the weekly offer's card (core NeedsYouMailRules; NeedsYouMailGroup).
final class MacNeedsYouMailTilesTests: XCTestCase {
    private func tile(_ n: String, _ label: String, warn: Bool = false) -> NeedsYouMailTile {
        NeedsYouMailTile(number: n, label: label, warn: warn, kind: TidiedKind.all.id, spoken: "\(n) \(label)")
    }

    private func question() -> NeedsYouUnsubQuestion {
        NeedsYouUnsubQuestion(senderId: "s1", question: "Unsubscribe from The Weekly Digest?",
                              line: "Personal · 6 emails, none opened", more: "1 of 11",
                              spoken: "Unsubscribe from The Weekly Digest?")
    }

    @MainActor
    func testTheTilesStandForTodayAndTheQuestionForTheOffer() {
        let rules = NeedsYouMailRules.shared
        let tiles = [tile("138", rules.TIDIED), tile("33", rules.NEWSLETTERS), tile("71", rules.PROMOS), tile("34", rules.JUNK, warn: true)]
        let both = NeedsYouMailSummary(tiles: tiles, question: question(), foot: rules.FOOT, spoken: "Tidied today: 138.",
                                       rest: "Also 1 receipt or order")
        XCTAssertFalse(NeedsYouMailGroup.showsLog(both))
        XCTAssertFalse(NeedsYouMailGroup.showsOffer(both))
        XCTAssertEqual(both.tiles.map(\.label), ["tidied", "newsletters", "promos", "junk & scams"])
        XCTAssertEqual(both.tiles.filter(\.warn).map(\.label), ["junk & scams"])
    }

    /// Meka 2026-11-18 23:34: each tile opens All tidied mail on its kind; the kinds' ids are what the sheet asks for.
    @MainActor
    func testEachTileNamesTheKindItOpens() {
        XCTAssertEqual(TidiedKind.companion.of(id: "junk"), TidiedKind.junk)
        XCTAssertEqual(NeedsYouMailRules.shared.tileSpoken(n: 2, label: "promos", k: .promos), "2 promos tidied today. Opens them")
        XCTAssertEqual(NeedsYouMailTilesView.chevron, "›")
    }

    @MainActor
    func testNothingTidiedTodayKeepsTheLogAndNoQuestionKeepsTheOffer() {
        let rules = NeedsYouMailRules.shared
        let questionOnly = NeedsYouMailSummary(tiles: [], question: question(), foot: rules.FOOT,
                                               spoken: "Nothing tidied yet today.", rest: nil)
        XCTAssertTrue(NeedsYouMailGroup.showsLog(questionOnly))
        XCTAssertFalse(NeedsYouMailGroup.showsOffer(questionOnly))
        XCTAssertTrue(NeedsYouMailGroup.showsLog(nil))
        XCTAssertTrue(NeedsYouMailGroup.showsOffer(nil))
        XCTAssertEqual(NeedsYouMailTilesView.heading, "MAIL NAVILIFE TIDIED TODAY")
        XCTAssertEqual(rules.SEE_ALL, "See all ›")
    }

    /// Week 4, A7: "138 tidied" over 33 + 71 + 33: the peek and the line under the tiles name what the parts leave out.
    func testTheTilesAddUpWithTheRestNamed() {
        let rules = NeedsYouMailRules.shared
        let tiles = [tile("138", rules.TIDIED), tile("33", rules.NEWSLETTERS), tile("71", rules.PROMOS), tile("33", rules.JUNK, warn: true)]
        XCTAssertEqual(NeedsYouGroupRules.shared.tidiedPeek(tiles: tiles),
                       "138 tidied · 33 newsletters · 71 promos · 33 junk & scams · 1 other")
    }
}
