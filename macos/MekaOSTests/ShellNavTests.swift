@testable import MekaOS
import XCTest

/// The Mac shell must agree with the Fold's rule for rule (android ShellNavTest).
final class ShellNavTests: XCTestCase {
    @MainActor
    func testClosedFoldGetsTheBarOpenFoldGetsTheRail() {
        XCTAssertEqual(ShellNav.layout(forWidth: 360), .bottomBar)
        XCTAssertEqual(ShellNav.layout(forWidth: 599.9), .bottomBar)
        XCTAssertEqual(ShellNav.layout(forWidth: 600), .rail)
    }

    @MainActor
    func testBarAndSidebarHaveTheSameFourTabsInOrder() {
        let four = ["Today", "Needs you", "Calendar", "Ask"]
        XCTAssertEqual(ShellNav.destinations(.bottomBar).map(\.label), four)
        XCTAssertEqual(ShellNav.destinations(.rail).map(\.label), four)
    }

    @MainActor
    func testPlacesBehindAskLightAskAndGoBackToIt() {
        for d in [ShellDestination.lists, .goals, .review, .vault] {
            XCTAssertEqual(ShellNav.barSelection(d), .ask)
            XCTAssertEqual(ShellNav.parent(d), .ask)
        }
        for d in ShellNav.tabs {
            XCTAssertEqual(ShellNav.barSelection(d), d)
            XCTAssertNil(ShellNav.parent(d))
        }
    }

    @MainActor
    func testContentMovesTheWayYouMoved() {
        XCTAssertEqual(ShellNav.direction(from: .today, to: .needsYou), 1)
        XCTAssertEqual(ShellNav.direction(from: .needsYou, to: .calendar), 1)
        XCTAssertEqual(ShellNav.direction(from: .ask, to: .today), -1)
        XCTAssertEqual(ShellNav.direction(from: .lists, to: .lists), 0)
        XCTAssertEqual(ShellNav.direction(from: .ask, to: .lists), 1)
        XCTAssertEqual(ShellNav.direction(from: .ask, to: .vault), 1)
        XCTAssertEqual(ShellNav.direction(from: .review, to: .ask), -1)
        XCTAssertEqual(ShellNav.direction(from: .goals, to: .calendar), -1)
    }

    @MainActor
    func testMoreListsEveryPlaceAndPaneCalendarsOnlyOnceConnected() {
        XCTAssertEqual(
            ShellNav.more(connected: true).map(\.label),
            ["Lists", "Goals and habits", "Review", "Vault", "Morning brief", "News", "Shut down the day", "Work mode", "Notifications",
             "Appearance", "Calendars", "Activity", "Your data"]
        )
        XCTAssertFalse(ShellNav.more(connected: false).contains(.calendars))
        let places = Set(ShellNav.more(connected: false).compactMap(\.destination))
        XCTAssertEqual(places, Set(ShellDestination.allCases.filter { ShellNav.parent($0) != nil }))
    }

    @MainActor
    func testMoreIsGroupedIntoPlacesDailyAndSettings() {
        // Fold review 2026-10-08, item 10.
        let sections = ShellNav.moreSections(connected: true)
        XCTAssertEqual(sections.map(\.group.label), ["Places", "Daily", "Settings"])
        XCTAssertEqual(sections[0].items.map(\.label), ["Lists", "Goals and habits", "Review", "Vault"])
        XCTAssertEqual(sections[1].items.map(\.label), ["Morning brief", "News", "Shut down the day"])
        XCTAssertEqual(sections[2].items.map(\.label), ["Work mode", "Notifications", "Appearance", "Calendars", "Activity", "Your data"])
        XCTAssertEqual(sections.flatMap(\.items), ShellNav.more(connected: true))
        XCTAssertTrue(sections[0].items.allSatisfy { $0.destination != nil })
        XCTAssertTrue(sections.dropFirst().flatMap(\.items).allSatisfy { $0.destination == nil })
        let offline = ShellNav.moreSections(connected: false)
        XCTAssertEqual(offline.count, 3)
        XCTAssertFalse(offline[2].items.contains(.calendars))
    }

    @MainActor
    func testMoreSectionsStaggerOneStepApart() {
        XCTAssertEqual((0...2).map(ShellNav.moreLabelStep), [2, 4, 6])
        XCTAssertEqual((0...3).map { ShellNav.moreRowStep(0, $0) }, [3, 4, 5, 6])
        XCTAssertEqual(ShellNav.moreRowStep(1, 0), 5)
        XCTAssertEqual(ShellNav.moreRowStep(2, 5), 12)
        for s in 0...2 { XCTAssertLessThan(ShellNav.moreLabelStep(s), ShellNav.moreRowStep(s, 0)) }
    }

    @MainActor
    func testHeaderMovesIntoMoreWithWorkSayingWhereYouAre() {
        // Today clarity, slice 2: Today's header keeps Search and Plan my day; the rest is in More.
        XCTAssertEqual(ShellNav.moreLine(.work, listsDue: 0, atWork: true), "At work · Work hours and the Work switch")
        XCTAssertEqual(ShellNav.moreLine(.work, listsDue: 3, atWork: false), "Off work · Work hours and the Work switch")
        XCTAssertEqual(ShellNav.moreLine(.brief, listsDue: 2), "Your day, who you're waiting on and headlines")
        XCTAssertEqual(ShellNav.moreLine(.shutdown, listsDue: 0), "Tick off, carry over and see tomorrow")
        XCTAssertFalse(ShellNav.moreLit(.work, listsDue: 2))
        XCTAssertEqual(MoreItem.allCases.filter(ShellNav.unfoldsInPlace), [.appearance])
        XCTAssertTrue([MoreItem.brief, .news, .shutdown, .appearance].allSatisfy { $0.destination == nil })
        XCTAssertEqual(ShellNav.moreLine(.news, listsDue: 3), "Barça, AI and the headlines · topics and sources")
    }

    @MainActor
    func testListsSaysWhatIsDue() {
        XCTAssertEqual(ShellNav.moreLine(.lists, listsDue: 0), "Waiting for · Someday · Decisions · Renewals")
        XCTAssertEqual(ShellNav.moreLine(.lists, listsDue: 1), "1 needs you · Waiting for · Someday · Decisions · Renewals")
        XCTAssertEqual(ShellNav.moreLine(.lists, listsDue: 3), "3 need you · Waiting for · Someday · Decisions · Renewals")
        XCTAssertEqual(ShellNav.moreLine(.goals, listsDue: 3), "Habits, goals and fasting")
        XCTAssertTrue(ShellNav.moreLit(.lists, listsDue: 2))
        XCTAssertFalse(ShellNav.moreLit(.lists, listsDue: 0))
        XCTAssertFalse(ShellNav.moreLit(.review, listsDue: 2))
    }

    @MainActor
    func testBadgeAndScreenReaderLabel() {
        XCTAssertNil(ShellNav.badge(0))
        XCTAssertEqual(ShellNav.badge(3), "3")
        XCTAssertEqual(ShellNav.badge(10), "9+")
        XCTAssertEqual(ShellNav.accessibilityLabel(.needsYou, needsYouCount: 12), "Needs you, 12 waiting")
        XCTAssertEqual(ShellNav.accessibilityLabel(.today, needsYouCount: 12), "Today")
    }

    @MainActor
    func testEveryUpcomingDestinationSaysWhatIsComing() {
        XCTAssertNotNil(ShellNav.upcomingLine(.vault))
        for d in [ShellDestination.today, .needsYou, .calendar, .ask, .lists, .goals, .review] { XCTAssertNil(ShellNav.upcomingLine(d)) }
    }
}
