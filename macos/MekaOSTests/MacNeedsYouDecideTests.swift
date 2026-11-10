@preconcurrency import MekaKit
@testable import MekaOS
import XCTest

/// The approved Needs you look on the Mac, TO DECIDE: a request (and a school day off, date night) is one compact card
/// read from the core (NeedsYouDecideRules), drawn by NeedsYouDecideCardView; a reply keeps its full card for its draft.
final class MacNeedsYouDecideTests: XCTestCase {
    private func request(_ kind: RequestKind, action: String, draft: String? = nil, change: String? = "Change") -> RequestCard {
        RequestCard(id: "r1", messageId: "m1", personKey: "alex",
                    proposal: RequestProposal(kind: kind, title: "Check the algorithm", day: nil, minute: nil,
                                              renewal: nil, reply: draft, booking: nil),
                    from: "From Alex · 14:02", quote: "“can you check the algorithm?”", action: action, detail: nil,
                    addLabel: "Add", changeLabel: change, declineLabel: "Not a task",
                    spoken: "Alex asked: can you check the algorithm?", atMs: 0, draft: draft, person: "Alex")
    }

    @MainActor
    func testARequestIsOneCompactCard() {
        let rules = NeedsYouDecideRules.shared
        let r = request(.task, action: "Add task: Check the algorithm · Today")
        XCTAssertTrue(rules.compact(card: r))
        let c = rules.card(card: r)
        XCTAssertEqual(c.what, "Task: Check the algorithm · Today")
        XCTAssertEqual(c.from, "Alex · “can you check the algorithm?”")
        XCTAssertEqual([c.yesLabel, c.changeLabel, c.noLabel], ["Add", "Change", "No"])
        XCTAssertEqual(NeedsYouDecideCardView.symbol(c.icon), "checkmark")
    }

    @MainActor
    func testAReplyKeepsItsFullCard() {
        let r = request(.reply, action: "Reply", draft: "Yes, that works.", change: nil)
        XCTAssertFalse(NeedsYouDecideRules.shared.compact(card: r))
    }

    @MainActor
    func testEveryIconHasASymbol() {
        let icons: [NeedsYouDecideIcon] = [.home, .task, .event, .reminder, .renewal, .trip, .idea, .reply, .school,
                                           .night, .appointment, .trade, .payment, .share]
        let symbols = icons.map { NeedsYouDecideCardView.symbol($0) }
        XCTAssertFalse(symbols.contains("circle"))
        XCTAssertEqual(Set(symbols).count, icons.count)
    }
}
