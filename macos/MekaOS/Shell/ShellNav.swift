@preconcurrency import MekaKit
import Foundation

// The app shell's rules (build plan M1, App shell; Four tabs, one front door). Matches
// android/.../shell/ShellNav.kt rule for rule.
//
// Four tabs (Today · Needs you · Calendar · Ask). Everything else stays a destination but sits behind Ask: reached
// from Ask's More list, cards on Today, search and notifications, with Ask lit. The order is the order content
// moves in: the places behind Ask come after it.

enum ShellDestination: Int, CaseIterable, Identifiable {
    case today, needsYou, calendar, ask, lists, goals, review, vault
    var id: Int { rawValue }

    var label: String {
        switch self {
        case .today: "Today"
        case .needsYou: "Needs you"
        case .calendar: "Calendar"
        case .ask: "Ask"
        case .lists: "Lists"
        case .goals: "Goals"
        case .review: "Review"
        case .vault: "Vault"
        }
    }

    /// SF Symbol for the sidebar (the outline; the lit row shows its filled variant, the Fold's drawn tab icons
    /// match the four tabs: a sun on the horizon, a circle with "!", a calendar, a speech bubble).
    var symbol: String {
        switch self {
        case .today: "sun.horizon"
        case .needsYou: "exclamationmark.circle"
        case .calendar: "calendar"
        case .ask: "bubble.left"
        case .lists: "list.bullet"
        case .goals: "target"
        case .review: "chart.bar"
        case .vault: "lock"
        }
    }
}

enum ShellLayout { case bottomBar, rail }

/// The parts of Ask's More (Fold review 2026-10-12, item 7: the 30-row list was too long to scan). Life is a grid of
/// tiles with a live line each; Daily a short list of rows; Settings one row that unfolds every setting in place.
/// Setup and Health show as one status row at the top only when something needs a look.
enum MoreGroup: Int, CaseIterable, Identifiable {
    case life, daily, settings
    var id: Int { rawValue }

    var label: String {
        switch self {
        case .life: "Life"
        case .daily: "Daily"
        case .settings: "Settings"
        }
    }
}

/// A row or tile in Ask's More: a place behind Ask (`destination`), a sheet over the current screen (nil), Appearance,
/// which shows its choices in the row itself (`ShellNav.unfoldsInPlace`), or MEKA's AI, which only says how it is
/// (`ShellNav.opens`). Life tiles carry their `tile` (the core's live line) and an SF Symbol.
enum MoreItem: Int, CaseIterable, Identifiable {
    case lists, goals, people, dinners, bins, dateNight, school, appointments, leave, gifts, trips, errands, emergency, facts, brief, weekAhead, news, shutdown, trainQueue, lifeAdmin, review, work, protectedTime, notifications, appearance, voice, talk, calendars, family, watch, recovery, autonomy, ai, activity, setup, health, vault, yourData, settings
    var id: Int { rawValue }

    var label: String {
        switch self {
        case .lists: "Lists"
        case .goals: "Goals and habits"
        case .people: "People"
        case .dinners: "Dinners"
        case .bins: "Bins"
        case .dateNight: "Date night"
        case .school: "School"
        case .appointments: "Appointments"
        case .leave: "Annual leave"
        case .gifts: "Gifts"
        case .trips: "Trips"
        case .errands: "Errands"
        case .emergency: "Emergency card"
        case .facts: "What MEKA knows"
        case .brief: "Morning brief"
        case .weekAhead: "Week ahead"
        case .news: "News"
        case .shutdown: "Shut down the day"
        case .trainQueue: "Train queue"
        case .lifeAdmin: "Life admin hour"
        case .review: "Review"
        case .work: "Work mode"
        case .protectedTime: "Protected time"
        case .notifications: "Notifications"
        case .appearance: "Appearance"
        case .voice: "MEKA's voice"
        case .talk: "Talk"
        case .calendars: "Calendars"
        case .family: "Family"
        case .watch: "Watch"
        case .recovery: "Recovery key"
        case .autonomy: "What MEKA may do"
        case .ai: "MEKA's AI"
        case .activity: "Activity"
        case .setup: "Setup"
        case .health: "Health"
        case .vault: "Vault"
        case .yourData: "Your data"
        case .settings: "Settings"
        }
    }

    var line: String {
        switch self {
        case .lists: "Waiting for · Someday · Decisions · Renewals"
        case .goals: "Habits, goals and fasting"
        case .people: "Promises both ways, birthdays and last contact"
        case .dinners: "Favourites, the week's dinners and their shopping"
        case .bins: "Which bins go out, the evening before"
        case .dateNight: "An evening every two weeks, kept clear"
        case .school: "Rex and Logan's days off, PE and trips"
        case .appointments: "Dentist, optician, haircut · a nudge when one's due"
        case .leave: "Days booked, days left and the school holidays"
        case .gifts: "Birthdays, anniversaries and gift ideas"
        case .trips: "Trips and bookings, day by day"
        case .errands: "Things to get or do when you're at a place"
        case .emergency: "Who to call and where things are"
        case .facts: "Car reg, GP, sizes · the facts MEKA answers from"
        case .brief: "Your day, who you're waiting on and headlines"
        case .weekAhead: "The coming week day by day · Sunday at 18:00"
        case .news: "Barça, AI and the headlines · topics and sources"
        case .shutdown: "Tick off, carry over and see tomorrow"
        case .trainQueue: "Links and podcasts saved for the commute"
        case .lifeAdmin: "One hour a week for forms, calls and renewals"
        case .review: "Your week, looked back on"
        case .work: "Work hours and the Work switch"
        case .protectedTime: "Evenings and weekends, kept free of tasks"
        case .notifications: "Quiet hours, digests and what reaches you"
        case .appearance: "Dark, Light or Auto"
        case .voice: "How MEKA sounds in Talk, the brief and calls"
        case .talk: "⌥Space and the mic"
        case .calendars: "Connected accounts and feeds"
        case .family: "Share the shopping list with Jeanette"
        case .watch: "Link your Galaxy Watch to MEKA"
        case .recovery: RecoveryKeyRules.shared.ROW_LINE
        case .autonomy: "What it does on its own · the pause switch"
        case .ai: "Whether it's on and what it costs this month"
        case .activity: "What MEKA did and why"
        case .setup: "Everything MEKA can do, and what's left to set up"
        case .health: "Is everything MEKA needs working?"
        case .vault: "Documents and when they run out; your data"
        case .yourData: "Export everything as one file"
        case .settings: "Work mode, notifications, appearance, voice and MEKA's AI"
        }
    }

    var group: MoreGroup {
        switch self {
        case .lists, .goals, .people, .dinners, .bins, .dateNight, .school, .appointments, .leave, .gifts, .trips, .errands, .emergency, .facts: .life
        case .brief, .weekAhead, .news, .shutdown, .trainQueue, .lifeAdmin, .review: .daily
        case .work, .protectedTime, .notifications, .appearance, .voice, .talk, .calendars, .family, .watch, .recovery, .autonomy, .ai, .activity, .setup, .health, .vault, .yourData, .settings: .settings
        }
    }

    var destination: ShellDestination? {
        switch self {
        case .lists: .lists
        case .goals: .goals
        case .review: .review
        case .vault: .vault
        default: nil
        }
    }

    /// The Life tile this item is, for its live line; nil for rows.
    var tile: LifeTile? {
        switch self {
        case .lists: .lists
        case .goals: .goals
        case .people: .people
        case .dinners: .dinners
        case .bins: .bins
        case .dateNight: .dateNight
        case .school: .school
        case .appointments: .appointments
        case .leave: .leave
        case .gifts: .gifts
        case .trips: .trips
        case .errands: .errands
        case .emergency: .emergency
        case .facts: .facts
        default: nil
        }
    }

    /// A Life tile's SF Symbol (the Fold draws matching outlines, LifeIcon.kt).
    var symbol: String? {
        switch self {
        case .lists: "list.bullet"
        case .goals: "target"
        case .people: "person.2"
        case .dinners: "fork.knife"
        case .bins: "trash"
        case .dateNight: "moon.stars"
        case .school: "graduationcap"
        case .appointments: "clock"
        case .leave: "sun.horizon"
        case .gifts: "gift"
        case .trips: "suitcase"
        case .errands: "mappin.and.ellipse"
        case .emergency: "cross.case"
        case .facts: "book"
        default: nil
        }
    }
}

enum ShellNav {
    /// Same breakpoint the Fold uses for its two panes.
    static let wideWidth: Double = 600

    /// The four tabs: the Fold's bar and rail, and the top of the Mac sidebar.
    static let tabs: [ShellDestination] = [.today, .needsYou, .calendar, .ask]

    static func layout(forWidth width: Double) -> ShellLayout { width >= wideWidth ? .rail : .bottomBar }

    /// The same four tabs in the bar and the rail.
    static func destinations(_ layout: ShellLayout) -> [ShellDestination] { tabs }

    /// The tab a destination sits behind: Ask for the places in More, nil for the tabs.
    static func parent(_ d: ShellDestination) -> ShellDestination? { tabs.contains(d) ? nil : .ask }

    /// The lit tab: itself, or Ask for a place reached from More.
    static func barSelection(_ current: ShellDestination) -> ShellDestination { parent(current) ?? current }

    /// +1 forward (content arrives from the trailing edge), -1 back, 0 for no change.
    static func direction(from: ShellDestination, to: ShellDestination) -> Int {
        max(-1, min(1, to.rawValue - from.rawValue))
    }

    /// Everything reachable from Ask's More; Calendars, Family, Watch and Recovery key only once this device is
    /// connected (they live on the server).
    static func more(connected: Bool) -> [MoreItem] {
        MoreItem.allCases.filter { connected || ($0 != .calendars && $0 != .family && $0 != .watch && $0 != .recovery) }
    }

    /// The Life grid's tiles, in the core's order (both apps).
    static func lifeTiles() -> [MoreItem] {
        LifeTileRules.shared.ORDER.compactMap { t in MoreItem.allCases.first { $0.tile == t } }
    }

    /// Daily's rows on Ask.
    static func daily() -> [MoreItem] { MoreItem.allCases.filter { $0.group == .daily } }

    /// What Settings lists, in order.
    static func settings(connected: Bool) -> [MoreItem] {
        more(connected: connected).filter { $0.group == .settings && $0 != .settings }
    }

    /// The status row at the top of More: Health when something needs a look, else Setup while steps are left.
    static func statusItem(healthAttention: Int, setupLeft: Int) -> MoreItem? {
        if healthAttention > 0 { return .health }
        if setupLeft > 0 { return .setup }
        return nil
    }

    /// Stagger steps for Ask (the title 0, the field 1, the status row 2): each part a step after the one before
    /// began, its label leading. Life: label 3, tiles a row at a time from 4 · Daily: label 5, rows 6–12 · Settings 7.
    static let statusStep = 2

    static func moreLabelStep(_ section: Int) -> Int { 3 + 2 * section }

    static func moreRowStep(_ section: Int, _ row: Int) -> Int { moreLabelStep(section) + 1 + row }

    /// Tiles arrive a row (two) at a time.
    static func tileStep(_ index: Int) -> Int {
        Int(LifeTileRules.shared.step(first: Int32(moreRowStep(0, 0)), index: Int32(index)))
    }

    static var settingsStep: Int { moreLabelStep(2) }

    /// Every item opens something except MEKA's AI, which only says how it is.
    static func opens(_ item: MoreItem) -> Bool { item != .ai }

    /// A tile's line: the core's live one when there is one (lit when it's about today), else the fixed words.
    static func tileLine(_ item: MoreItem, live: LifeTileLine?) -> (text: String, lit: Bool) {
        if let live { return (live.text, live.lit) }
        return (item.line, false)
    }

    /// Lists says what's due when something is ("2 need you · …"), Health and Setup their summaries once checked,
    /// else the fixed words.
    static func moreLine(_ item: MoreItem, listsDue: Int, atWork: Bool = false, health: String? = nil, setup: String? = nil) -> String {
        if item == .work { return workLine(atWork: atWork) }
        if item == .health, let health { return health }
        if item == .setup, let setup { return setup }
        guard item == .lists, listsDue > 0 else { return item.line }
        return "\(listsDue) need\(listsDue == 1 ? "s" : "") you · \(item.line)"
    }

    /// Appearance shows its three choices in the row itself; every other row opens something.
    static func unfoldsInPlace(_ item: MoreItem) -> Bool { item == .appearance }

    /// Work mode's line says where you are now ("At work · Work hours and the Work switch"), as the header did.
    static func workLine(atWork: Bool) -> String { "\(atWork ? "At work" : "Off work") · \(MoreItem.work.line)" }

    static func moreLit(_ item: MoreItem, listsDue: Int, healthAttention: Int = 0, setupLeft: Int = 0) -> Bool {
        (item == .lists && listsDue > 0) || (item == .health && healthAttention > 0) || (item == .setup && setupLeft > 0)
    }

    /// Nothing at zero, the count up to 9, then "9+".
    static func badge(_ count: Int) -> String? {
        if count <= 0 { return nil }
        return count > 9 ? "9+" : "\(count)"
    }

    static func accessibilityLabel(_ d: ShellDestination, needsYouCount: Int) -> String {
        let n = d == .needsYou ? needsYouCount : 0
        return n > 0 ? "\(d.label), \(n) waiting" : d.label
    }

    /// Placeholder line for destinations whose feature hasn't landed yet.
    static func upcomingLine(_ d: ShellDestination) -> String? {
        switch d {
        case .vault: "Your documents and when they run out; MEKA reminds you in time to renew."
        case .today, .needsYou, .calendar, .ask, .lists, .goals, .review: nil
        }
    }
}
