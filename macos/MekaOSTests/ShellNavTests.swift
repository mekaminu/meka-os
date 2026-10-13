@preconcurrency import MekaKit
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
    func testMoreReachesEveryPlaceAndPaneCalendarsOnlyOnceConnected() {
        XCTAssertFalse(ShellNav.more(connected: false).contains(.calendars))
        XCTAssertFalse(ShellNav.more(connected: false).contains(.mail))
        XCTAssertFalse(ShellNav.more(connected: false).contains(.family))
        XCTAssertFalse(ShellNav.more(connected: false).contains(.watch))
        XCTAssertFalse(ShellNav.more(connected: false).contains(.recovery))
        let places = Set(ShellNav.more(connected: false).compactMap(\.destination))
        XCTAssertEqual(places, Set(ShellDestination.allCases.filter { ShellNav.parent($0) != nil }))
        // Every item is a tile, a Daily row, the Settings row or in Settings, exactly once.
        let all = ShellNav.lifeTiles() + ShellNav.daily() + [MoreItem.settings] + ShellNav.settings(connected: true)
        XCTAssertEqual(Set(all), Set(MoreItem.allCases))
        XCTAssertEqual(all.count, Set(all).count)
    }

    @MainActor
    func testAskIsALifeGridDailyAndOneSettingsRow() {
        // Fold review 2026-10-12, item 7: the same parts as the Fold's.
        XCTAssertEqual(ShellNav.lifeTiles().map(\.label),
                       ["Lists", "Goals and habits", "People", "Dinners", "Bins", "Date night", "School", "Appointments", "Annual leave", "Gifts", "Trips", "Errands", "Emergency card", "What MEKA knows"])
        XCTAssertEqual(ShellNav.lifeTiles().compactMap(\.tile), LifeTileRules.shared.ORDER)
        XCTAssertTrue(ShellNav.lifeTiles().allSatisfy { $0.symbol != nil })
        XCTAssertEqual(ShellNav.daily().map(\.label), ["Morning brief", "Week ahead", "News", "Shut down the day", "Train queue", "Life admin hour", "Review"])
        XCTAssertEqual(ShellNav.settings(connected: true).map(\.label),
                       ["About you", "Work mode", "Protected time", "Notifications", "Appearance", "MEKA's voice", "Talk", "Calendars", "Mail", "Family", "Watch", "Recovery key", "What MEKA may do", "MEKA's AI", "Activity", "Setup", "Health", "Vault", "Your data"])
        XCTAssertFalse(ShellNav.settings(connected: false).contains(.watch))
        XCTAssertFalse(ShellNav.settings(connected: false).contains(.recovery))
        XCTAssertEqual(MoreItem.allCases.filter { !ShellNav.opens($0) }, [.ai])
        XCTAssertNil(ShellNav.statusItem(healthAttention: 0, setupLeft: 0))
        XCTAssertEqual(ShellNav.statusItem(healthAttention: 0, setupLeft: 3), .setup)
        XCTAssertEqual(ShellNav.statusItem(healthAttention: 2, setupLeft: 3), .health)
        XCTAssertEqual(ShellNav.tileLine(.bins, live: nil).text, "Set your bin days")
        XCTAssertFalse(ShellNav.tileLine(.bins, live: nil).lit)
        let live = ShellNav.tileLine(.dinners, live: LifeTileLine(text: "Chilli tonight", lit: true))
        XCTAssertEqual(live.text, "Chilli tonight")
        XCTAssertTrue(live.lit)
        // Before the core's first lines, the usual order.
        XCTAssertEqual(ShellNav.lifeTiles(nil), ShellNav.lifeTiles())
        // Ask polish, slice 1: set-up tiles sink under "To set up"; none before the core's first lines.
        XCTAssertEqual(ShellNav.lifeLive(nil), ShellNav.lifeTiles())
        XCTAssertEqual(ShellNav.lifeToSetUp(nil), [])
        let lines = LifeLines(lines: [:], toSetUp: [LifeTile.bins, LifeTile.school])
        XCTAssertTrue(ShellNav.lifeToSetUp(lines).contains(.bins))
        XCTAssertFalse(ShellNav.lifeLive(lines).contains(.bins))
        XCTAssertEqual(Set(ShellNav.lifeLive(lines) + ShellNav.lifeToSetUp(lines)), Set(ShellNav.lifeTiles()))
        XCTAssertNil(AskSetUp.stored(""))
        XCTAssertEqual(AskSetUp.stored(AskSetUp.open)?.boolValue, true)
    }

    @MainActor
    func testHealthSaysItsSummaryAndIsLit() {
        // Reliability first, item 3.
        XCTAssertEqual(ShellNav.moreLine(.health, listsDue: 0), "Is everything MEKA needs working?")
        XCTAssertEqual(ShellNav.moreLine(.health, listsDue: 0, health: "2 things need a look"), "2 things need a look")
        XCTAssertTrue(ShellNav.moreLit(.health, listsDue: 0, healthAttention: 1))
        XCTAssertFalse(ShellNav.moreLit(.health, listsDue: 2))
        XCTAssertTrue(ShellNav.more(connected: false).contains(.health))
    }

    @MainActor
    func testSetupSaysWhatIsLeftAndIsLit() {
        // Setup checklist (Meka approved 2026-10-09).
        XCTAssertEqual(ShellNav.moreLine(.setup, listsDue: 0), "Everything MEKA can do, and what's left to set up")
        XCTAssertEqual(ShellNav.moreLine(.setup, listsDue: 0, setup: "3 steps left · 12 of 15 done"), "3 steps left · 12 of 15 done")
        XCTAssertTrue(ShellNav.moreLit(.setup, listsDue: 0, setupLeft: 3))
        XCTAssertFalse(ShellNav.moreLit(.setup, listsDue: 0, healthAttention: 2))
        XCTAssertTrue(ShellNav.more(connected: false).contains(.setup))
    }

    @MainActor
    func testAskStaggersAPartAStepAfterTheOneBefore() {
        XCTAssertEqual(ShellNav.statusStep, 2)
        XCTAssertEqual((0...2).map(ShellNav.moreLabelStep), [3, 5, 7])
        XCTAssertEqual((0...4).map(ShellNav.tileStep), [4, 4, 5, 5, 6])
        XCTAssertEqual(ShellNav.moreRowStep(1, 0), 6)
        XCTAssertEqual(ShellNav.settingsStep, 7)
        for s in 0...1 { XCTAssertLessThan(ShellNav.moreLabelStep(s), ShellNav.moreRowStep(s, 0)) }
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

    @MainActor
    func testTheTabsHaveTheIconsTheFoldDraws() {
        // Outline symbols; the lit row shows the filled variant (sun on the horizon, "!", a calendar, a bubble).
        XCTAssertEqual(ShellNav.tabs.map(\.symbol), ["sun.horizon", "exclamationmark.circle", "calendar", "bubble.left"])
    }

    @MainActor
    func testDailyRowsTakeTheCoresLiveLinesElseTheirWords() {
        let lines = DailyLines(lines: [DailyRow.shutdown: DailyLine(text: "2 left", lit: true)])
        XCTAssertEqual(ShellNav.dailyLine(.shutdown, lines: lines).text, "2 left")
        XCTAssertTrue(ShellNav.dailyLine(.shutdown, lines: lines).lit)
        XCTAssertEqual(ShellNav.dailyLine(.news, lines: lines).text, MoreItem.news.line)
        XCTAssertEqual(ShellNav.dailyLine(.brief, lines: nil).text, MoreItem.brief.line)
        XCTAssertNil(ShellNav.dailyRow(.review))
    }
}
