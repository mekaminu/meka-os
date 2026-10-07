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

    /// SF Symbol for the sidebar.
    var symbol: String {
        switch self {
        case .today: "sun.horizon"
        case .needsYou: "exclamationmark.circle"
        case .calendar: "calendar"
        case .ask: "magnifyingglass"
        case .lists: "list.bullet"
        case .goals: "target"
        case .review: "chart.bar"
        case .vault: "lock"
        }
    }
}

enum ShellLayout { case bottomBar, rail }

/// A row in Ask's More list: a place behind Ask (`destination`) or a sheet over the current screen (nil).
enum MoreItem: Int, CaseIterable, Identifiable {
    case lists, goals, review, vault, work, notifications, activity, yourData, calendars
    var id: Int { rawValue }

    var label: String {
        switch self {
        case .lists: "Lists"
        case .goals: "Goals and habits"
        case .review: "Review"
        case .vault: "Vault"
        case .work: "Work mode"
        case .notifications: "Notifications"
        case .activity: "Activity"
        case .yourData: "Your data"
        case .calendars: "Calendars"
        }
    }

    var line: String {
        switch self {
        case .lists: "Waiting for · Someday · Decisions · Renewals"
        case .goals: "Habits, goals and fasting"
        case .review: "Your week, looked back on"
        case .vault: "Your data now; documents later"
        case .work: "Work hours and the Work switch"
        case .notifications: "Quiet hours, digests and what reaches you"
        case .activity: "What MEKA did and why"
        case .yourData: "Export everything as one file"
        case .calendars: "Connected accounts and feeds"
        }
    }

    var destination: ShellDestination? {
        switch self {
        case .lists: .lists
        case .goals: .goals
        case .review: .review
        case .vault: .vault
        case .work, .notifications, .activity, .yourData, .calendars: nil
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

    /// Lists says what's due when something is ("2 need you · …"), else the fixed words.
    static func moreLine(_ item: MoreItem, listsDue: Int) -> String {
        guard item == .lists, listsDue > 0 else { return item.line }
        return "\(listsDue) need\(listsDue == 1 ? "s" : "") you · \(item.line)"
    }

    static func moreLit(_ item: MoreItem, listsDue: Int) -> Bool { item == .lists && listsDue > 0 }

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
