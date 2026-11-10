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

    /// Mac slice 3: an approval is a compact card; what would happen stays whole and the Touch ID note sits under it.
    @MainActor
    func testAnApprovalIsACompactCardWithItsDetailWhole() {
        let a = ApprovalCard(id: "a1", kind: .trade, label: "TRADE · KESTREL", title: "Buy 0.1 ETH",
                             lines: ["At about £250", " "], why: "Asked 21:40 · expires in 14 min", biometric: true,
                             urgent: true, approveLabel: "Approve", declineLabel: "Decline", spoken: "Kestrel asks to buy 0.1 ETH")
        let c = NeedsYouDecideRules.shared.approval(card: a, lock: ApprovalRules.shared.LOCK_MAC)
        XCTAssertEqual(c.what, "Buy 0.1 ETH")
        XCTAssertEqual(c.from, "Trade · Kestrel · Asked 21:40 · expires in 14 min")
        XCTAssertEqual(c.details, ["At about £250"])
        XCTAssertTrue(c.urgent)
        XCTAssertNil(c.changeLabel)
        XCTAssertEqual([c.yesLabel, c.noLabel], ["Approve", "Decline"])
        XCTAssertEqual(c.note, ApprovalRules.shared.LOCK_MAC)
        XCTAssertEqual(NeedsYouDecideCardView.symbol(c.icon), "chart.line.uptrend.xyaxis")
    }

    /// Mac slice 3: a due appointment is a compact card with Call to book · Copy email in its quieter row.
    @MainActor
    func testAnAppointmentIsACompactCardWithAQuieterRow() {
        let n = AppointmentNudge(id: "dentist", line: "Due Mon 2 Nov · every 6 months", title: "Dentist",
                                 question: "Have you booked it?", detail: "Last went in May", spoken: "The dentist is due")
        let c = NeedsYouDecideRules.shared.appointment(card: n)
        XCTAssertEqual(c.what, "Dentist")
        XCTAssertEqual(c.from, "Due Mon 2 Nov · every 6 months · Have you booked it?")
        XCTAssertEqual([c.yesLabel, c.changeLabel, c.noLabel], [n.bookedLabel, n.wentLabel, n.laterLabel])
        XCTAssertEqual(c.more, [n.callLabel, n.emailLabel])
        XCTAssertEqual(NeedsYouDecideCardView.symbol(c.icon), "clock")
    }
}
