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

/// The sections of Ask's More list (Fold review 2026-10-08, item 10: twelve rows in one flat list were hard to scan).
enum MoreGroup: Int, CaseIterable, Identifiable {
    case places, daily, settings
    var id: Int { rawValue }

    var label: String {
        switch self {
        case .places: "Places"
        case .daily: "Daily"
        case .settings: "Settings"
        }
    }
}

/// One section of More: its label and its rows, in order.
struct MoreSection: Equatable, Identifiable {
    let group: MoreGroup
    let items: [MoreItem]
    var id: Int { group.rawValue }
}

/// A row in Ask's More list: a place behind Ask (`destination`), a sheet over the current screen (nil), or
/// Appearance, which shows its choices in the row itself (`ShellNav.unfoldsInPlace`). Today's header keeps only
/// Search and Plan my day (Today clarity, slice 2): the brief, the shutdown, work mode and the theme moved here.
enum MoreItem: Int, CaseIterable, Identifiable {
    case lists, goals, review, vault, brief, news, shutdown, work, notifications, appearance, voice, talk, calendars, setup, health, activity, yourData
    var id: Int { rawValue }

    var label: String {
        switch self {
        case .lists: "Lists"
        case .goals: "Goals and habits"
        case .review: "Review"
        case .vault: "Vault"
        case .brief: "Morning brief"
        case .news: "News"
        case .shutdown: "Shut down the day"
        case .work: "Work mode"
        case .notifications: "Notifications"
        case .appearance: "Appearance"
        case .voice: "MEKA's voice"
        case .talk: "Talk"
        case .activity: "Activity"
        case .yourData: "Your data"
        case .calendars: "Calendars"
        case .setup: "Setup"
        case .health: "Health"
        }
    }

    var line: String {
        switch self {
        case .lists: "Waiting for · Someday · Decisions · Renewals"
        case .goals: "Habits, goals and fasting"
        case .review: "Your week, looked back on"
        case .vault: "Your data now; documents later"
        case .brief: "Your day, who you're waiting on and headlines"
        case .news: "Barça, AI and the headlines · topics and sources"
        case .shutdown: "Tick off, carry over and see tomorrow"
        case .work: "Work hours and the Work switch"
        case .notifications: "Quiet hours, digests and what reaches you"
        case .appearance: "Dark, Light or Auto"
        case .voice: "How MEKA sounds in Talk, the brief and calls"
        case .talk: "⌥Space and the mic"
        case .activity: "What MEKA did and why"
        case .yourData: "Export everything as one file"
        case .calendars: "Connected accounts and feeds"
        case .setup: "Everything MEKA can do, and what's left to set up"
        case .health: "Is everything MEKA needs working?"
        }
    }

    var group: MoreGroup {
        switch self {
        case .lists, .goals, .review, .vault: .places
        case .brief, .news, .shutdown: .daily
        case .work, .notifications, .appearance, .voice, .talk, .calendars, .setup, .health, .activity, .yourData: .settings
        }
    }

    var destination: ShellDestination? {
        switch self {
        case .lists: .lists
        case .goals: .goals
        case .review: .review
        case .vault: .vault
        case .brief, .news, .shutdown, .work, .notifications, .appearance, .voice, .talk, .activity, .yourData, .calendars, .setup, .health: nil
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

    /// Ask's More list; Calendars only once this device is connected.
    static func more(connected: Bool) -> [MoreItem] { MoreItem.allCases.filter { connected || $0 != .calendars } }

    /// More in its sections (Places · Daily · Settings); a section with no rows is left out.
    static func moreSections(connected: Bool) -> [MoreSection] {
        let items = more(connected: connected)
        return MoreGroup.allCases.map { g in MoreSection(group: g, items: items.filter { $0.group == g }) }
            .filter { !$0.items.isEmpty }
    }

    /// Stagger steps for More (after the title 0 and field 1): each section starts one step after the one before
    /// began; a label leads its rows by one step. Places: 2, rows 3–6 · Daily: 4, rows 5–7 · Settings: 6, rows 7–16.
    static func moreLabelStep(_ section: Int) -> Int { 2 + 2 * section }

    static func moreRowStep(_ section: Int, _ row: Int) -> Int { moreLabelStep(section) + 1 + row }

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
        case .vault: "Encrypted documents, with expiry dates sent to your plan, land here."
        case .today, .needsYou, .calendar, .ask, .lists, .goals, .review: nil
        }
    }
}
