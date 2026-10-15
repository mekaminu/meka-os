@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// Mac presentation pass, slice 3 (items 6 and 7): Needs you cards' one strong line, likely scams set aside, and the
/// timeline's "Whose is it? ›" chip, from core NeedsYouCardRules and ClubChildRules.
final class MacNeedsYouCardsTests: XCTestCase {
    private let rules = NeedsYouCardRules.shared

    private func card(_ id: String, from: String, gist: String, lane: TriageLane = .fyi) -> TriageCard {
        TriageCard(id: id, lane: lane, personKey: id, from: from, gist: gist, draft: nil, app: .sms, mentioned: false,
                   atMs: 1_000, spoken: from, group: nil, askedBy: nil, number: nil)
    }

    func testOneStrongLineThenAMutedOne() {
        let c = card("wa-1", from: "Alex · 14:02", gist: "Asks if you're coming Saturday", lane: .needsReply)
        XCTAssertEqual(rules.headline(card: c), "Alex · Asks if you're coming Saturday")
        XCTAssertEqual(rules.meta(card: c), "14:02 · Needs a reply · SMS")
    }

    func testALikelyScamLeavesTheCardsAndANumberCanBeBlocked() {
        let scam = card("s1", from: "07700 900123 · 09:12", gist: "Unverified bank alert asking to confirm a payment; likely a scam")
        let friend = card("f1", from: "Alex · 14:02", gist: "Running late")
        XCTAssertEqual(rules.shown(cards: [scam, friend]).map(\.id), ["f1"])
        XCTAssertEqual(rules.scams(cards: [scam, friend]).map(\.id), ["s1"])
        XCTAssertEqual(rules.number(card: scam), "07700 900123")
        XCTAssertNil(rules.number(card: friend))
        XCTAssertEqual(rules.scamHeading(count: 1), "LIKELY SCAMS · 1")
    }

    func testTheTimelineChipTakesTheQuestionsPlaceAndKeepsThePlace() {
        // Rule 13, release 2: the children come from About you (invented here).
        _ = Household.shared.useTyped(children: "Max, U7\nFelix, U10", clubs: "")
        defer { _ = Household.shared.useTyped(children: "", clubs: "") }
        let club = ClubChildRules.shared
        let detail = club.ASK + " · Oakfield School"
        XCTAssertTrue(club.asking(detail: detail))
        XCTAssertEqual(club.withoutAsk(detail: detail), "Oakfield School")
        XCTAssertEqual(club.WHOSE, "Whose is it? ›")
        XCTAssertFalse(club.NAMES.isEmpty)
    }
}
