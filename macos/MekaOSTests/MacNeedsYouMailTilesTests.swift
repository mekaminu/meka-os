@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// The approved Needs you look on the Mac, the mail: today's tidies as four tiles and the one unsubscribe question take
/// the place of the long Archived for you log and the weekly offer's card (core NeedsYouMailRules; NeedsYouMailGroup).
final class MacNeedsYouMailTilesTests: XCTestCase {
    private func tile(_ n: String, _ label: String, warn: Bool = false) -> NeedsYouMailTile {
        NeedsYouMailTile(number: n, label: label, warn: warn)
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
        let both = NeedsYouMailSummary(tiles: tiles, question: question(), foot: rules.FOOT, spoken: "Tidied today: 138.")
        XCTAssertFalse(NeedsYouMailGroup.showsLog(both))
        XCTAssertFalse(NeedsYouMailGroup.showsOffer(both))
        XCTAssertEqual(both.tiles.map(\.label), ["tidied", "newsletters", "promos", "junk & scams"])
        XCTAssertEqual(both.tiles.filter(\.warn).map(\.label), ["junk & scams"])
    }

    @MainActor
    func testNothingTidiedTodayKeepsTheLogAndNoQuestionKeepsTheOffer() {
        let rules = NeedsYouMailRules.shared
        let questionOnly = NeedsYouMailSummary(tiles: [], question: question(), foot: rules.FOOT, spoken: "Nothing tidied yet today.")
        XCTAssertTrue(NeedsYouMailGroup.showsLog(questionOnly))
        XCTAssertFalse(NeedsYouMailGroup.showsOffer(questionOnly))
        XCTAssertTrue(NeedsYouMailGroup.showsLog(nil))
        XCTAssertTrue(NeedsYouMailGroup.showsOffer(nil))
        XCTAssertEqual(NeedsYouMailTilesView.heading, "MAIL NAVILIFE TIDIED TODAY")
        XCTAssertEqual(rules.SEE_ALL, "See all ›")
    }
}
