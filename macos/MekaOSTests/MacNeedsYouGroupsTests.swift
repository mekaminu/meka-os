@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// Needs you grouped and folding on the Mac (slice 4): the boxes' heads and groups come from the core's
/// NeedsYouGroupRules as on the Fold; what he leaves open is kept on this Mac; long groups page five at a time.
final class MacNeedsYouGroupsTests: XCTestCase {
    private func facts(decide: Int32 = 0, foot: String? = nil, messages: [String] = [], fyi: [String] = [],
                       mail: Bool = false, mailRows: Int32 = 0) -> NeedsYouGroupFacts {
        NeedsYouGroupFacts(decideCount: decide, decidePeek: decide > 0 ? "\(decide) tasks" : "", afterWorkFoot: foot,
                           messages: messages, fyi: fyi, chatsCount: 0, chatsPeek: "", mailAny: mail, mailCount: mail ? 3 : 0,
                           mailPeek: mail ? "Sam" : "", mailRows: mailRows, renewals: nil, tidiedAny: false, tidiedCount: 0,
                           tidiedPeek: "")
    }

    @MainActor
    func testToDecideAndMessagesOpenFirstAndWhatHeLeavesOpenIsKept() {
        XCTAssertEqual(NeedsYouOpenGroups.decode(nil), [NeedsYouGroup.decide, NeedsYouGroup.messages])
        let kept = NeedsYouOpenGroups.encode([NeedsYouGroup.fyi, NeedsYouGroup.mail])
        XCTAssertEqual(kept, "fyi,mail")
        XCTAssertEqual(NeedsYouOpenGroups.decode(kept), [NeedsYouGroup.fyi, NeedsYouGroup.mail])
        XCTAssertEqual(NeedsYouOpenGroups.decode(""), [])
    }

    @MainActor
    func testOnlyGroupsWithSomethingInThemShowAndAChipShowsItsOwn() {
        let f = facts(decide: 2, messages: ["Alex"], fyi: ["Sam"], mail: true)
        XCTAssertEqual(NeedsYouGroupRules.shared.present(facts: f, filter: .all).map(\.id), ["decide", "msgs", "fyi", "mail"])
        XCTAssertEqual(NeedsYouGroupRules.shared.present(facts: f, filter: .fyi).map(\.id), ["fyi"])
        let opened = NeedsYouGroupRules.shared.pick(open: [], filter: .mail)
        XCTAssertTrue(opened.contains(NeedsYouGroup.mail))
    }

    @MainActor
    func testHeadsSayTheirCountAndPeekAndOpenAllClosesThemAll() {
        let names = (1...7).map { "Person \($0)" }
        let f = facts(foot: "While you were at work · 2 people ›", messages: names)
        let closed = NeedsYouGroupRules.shared.headOf(group: .messages, facts: f, open: [], msgPage: 0, fyiPage: 0, mailPage: 0)
        XCTAssertEqual(closed.count, "7")
        XCTAssertEqual(closed.peek, "Person 1, Person 2, Person 3, Person 4 + 3 more")
        XCTAssertFalse(closed.open)
        let open = NeedsYouGroupRules.shared.headOf(group: .messages, facts: f, open: [NeedsYouGroup.messages],
                                                    msgPage: 0, fyiPage: 0, mailPage: 0)
        XCTAssertEqual(open.peek, "1–5 of 7")
        let decide = NeedsYouGroupRules.shared.headOf(group: .decide, facts: f, open: [], msgPage: 0, fyiPage: 0, mailPage: 0)
        XCTAssertEqual(decide.peek, "While you were at work · 2 people")
        let shown = [NeedsYouGroup.decide, NeedsYouGroup.messages]
        XCTAssertEqual(NeedsYouGroupRules.shared.allLabel(open: [NeedsYouGroup.decide], shown: shown), "Close all")
        XCTAssertEqual(NeedsYouGroupRules.shared.all(open: [NeedsYouGroup.decide], shown: shown), [])
        XCTAssertEqual(NeedsYouGroupRules.shared.allLabel(open: [], shown: shown), "Open all")
    }

    @MainActor
    func testLongGroupsPageFiveAtATime() {
        let rows = Array(1...12)
        let p = NeedsYouGroupRules.shared.page(total: 12, page: 2, size: NeedsYouGroupRules.shared.PAGE_SIZE)
        XCTAssertEqual(p.label, "11–12 of 12")
        XCTAssertEqual(needsYouPageRows(rows, p), [11, 12])
        XCTAssertTrue(p.hasPrev)
        XCTAssertFalse(p.hasNext)
        XCTAssertEqual(NeedsYouGroup.mail.symbol, "envelope")
    }

    /// Slice 4b: Mail to answer pages every email five at a time ("1–5 of 7" in its head while open), the due ten-minute
    /// inbox is its lead line with Start (gone once it runs), and a thin row's lines come from the core.
    @MainActor
    func testMailToAnswerPagesEveryEmailAndLeadsWithTheTenMinuteInbox() {
        let f = facts(mail: true, mailRows: 7)
        let open = NeedsYouGroupRules.shared.headOf(group: .mail, facts: f, open: [NeedsYouGroup.mail],
                                                    msgPage: 0, fyiPage: 0, mailPage: 1)
        XCTAssertEqual(open.peek, "6–7 of 7")
        let closed = NeedsYouGroupRules.shared.headOf(group: .mail, facts: f, open: [], msgPage: 0, fyiPage: 0, mailPage: 0)
        XCTAssertEqual(closed.peek, "Sam")
        let running = NeedsYouGroupRules.shared.headOf(group: .mail, facts: facts(mail: true, mailRows: 0),
                                                       open: [NeedsYouGroup.mail], msgPage: 0, fyiPage: 0, mailPage: 0)
        XCTAssertEqual(running.peek, "")
        XCTAssertEqual(NeedsYouGroupRules.shared.mailLead(momentDue: true, running: false), "Ten-minute inbox · one at a time")
        XCTAssertNil(NeedsYouGroupRules.shared.mailLead(momentDue: true, running: true))
        XCTAssertNil(NeedsYouGroupRules.shared.mailLead(momentDue: false, running: false))
        XCTAssertEqual(NeedsYouGroupRules.shared.MAIL_START, "Start")
    }
}
