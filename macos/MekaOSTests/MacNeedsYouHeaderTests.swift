@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// The approved Needs you look on the Mac, the header (Mac slice 5): "Needs you" over its counts with Clear FYI, the
/// chips All · Decide · Reply · FYI · Mail (each shows only its section), and chats needing nothing as FYI thin rows,
/// no longer among MESSAGES. Every count and line is read from the core (NeedsYouLayoutRules), as on the Fold.
final class MacNeedsYouHeaderTests: XCTestCase {
    private func card(_ id: String, who: String, gist: String, at: Int64, lane: TriageLane) -> TriageCard {
        TriageCard(id: id, lane: lane, personKey: who.lowercased(), from: "\(who) · 14:02", gist: gist, draft: nil,
                   app: .whatsapp, mentioned: false, atMs: at, spoken: who, group: nil, askedBy: nil, number: nil,
                   phoneLive: false, phoneSend: nil)
    }

    private var chats: [TriageChat] {
        let cards = [
            card("wa-1", who: "Alex", gist: "Can you call me tonight?", at: 1_000, lane: .needsReply),
            card("wa-2", who: "Sam", gist: "Thanks for today", at: 2_000, lane: .fyi),
            card("wa-3", who: "Jo", gist: "Thumbs up", at: 3_000, lane: .fyi),
        ]
        return TriageChatRules.shared.chatsWith(cards: cards, requests: [])
    }

    @MainActor
    func testTheHeaderCountsAndChips() {
        let counts = NeedsYouHeaderCounts(decide: 2, chats: chats, mail: 14)
        XCTAssertEqual(counts.reply, 1)
        XCTAssertEqual(counts.fyi, 2)
        XCTAssertEqual(counts.subtitle, "2 to decide · 1 to reply · 2 FYI")
        XCTAssertTrue(counts.showsClear)
        XCTAssertEqual(counts.chips.map(\.label), ["All", "Decide 2", "Reply 1", "FYI 2", "Mail 14"])
        XCTAssertEqual(NeedsYouLayoutRules.shared.CLEAR_FYI, "Clear FYI")
        XCTAssertEqual(NeedsYouLayoutRules.shared.clearedLine(n: 2), "FYI cleared · 2 chats")
    }

    @MainActor
    func testAllClearHidesClearFyi() {
        let counts = NeedsYouHeaderCounts(decide: 0, chats: [], mail: 0)
        XCTAssertEqual(counts.subtitle, "All clear")
        XCTAssertFalse(counts.showsClear)
        XCTAssertEqual(counts.chips.map(\.label), ["All", "Decide", "Reply", "FYI", "Mail"])
    }

    @MainActor
    func testEachChipShowsOnlyItsSection() {
        XCTAssertTrue(NeedsYouHeaderCounts.shows(.all, .mail))
        XCTAssertTrue(NeedsYouHeaderCounts.shows(.decide, .decide))
        XCTAssertFalse(NeedsYouHeaderCounts.shows(.decide, .messages))
        XCTAssertTrue(NeedsYouHeaderCounts.shows(.reply, .messages))
        XCTAssertFalse(NeedsYouHeaderCounts.shows(.fyi, .messages))
        XCTAssertTrue(NeedsYouHeaderCounts.shows(.mail, .mail))
    }

    @MainActor
    func testFyiChatsAreThinRowsNotMessages() {
        let messages = NeedsYouLayoutRules.shared.messages(chats: chats)
        XCTAssertEqual(messages.map(\.title), ["Alex"])
        let fyi = NeedsYouLayoutRules.shared.fyi(chats: chats).map { NeedsYouLayoutRules.shared.fyiRow(chat: $0) }
        XCTAssertEqual(Set(fyi.map(\.who)), ["Sam", "Jo"])
        XCTAssertTrue(fyi.allSatisfy { $0.spoken.hasSuffix("No reply needed.") })
    }
}
