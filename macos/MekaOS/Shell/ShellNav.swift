import Foundation

// The app shell's rules (build plan M1, App shell). Matches android/.../shell/ShellNav.kt rule for rule.

enum ShellDestination: Int, CaseIterable, Identifiable {
    case today, calendar, needsYou, lists, goals, review, vault
    var id: Int { rawValue }

    var label: String {
        switch self {
        case .today: "Today"
        case .calendar: "Calendar"
        case .needsYou: "Needs you"
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
        case .calendar: "calendar"
        case .needsYou: "exclamationmark.circle"
        case .lists: "list.bullet"
        case .goals: "target"
        case .review: "chart.bar"
        case .vault: "lock"
        }
    }
}

enum ShellLayout { case bottomBar, rail }

enum ShellNav {
    /// Same breakpoint the Fold uses for its two panes.
    static let wideWidth: Double = 600

    static func layout(forWidth width: Double) -> ShellLayout { width >= wideWidth ? .rail : .bottomBar }

    /// The closed Fold's bar has six; the rail and the Mac sidebar have all seven.
    static func destinations(_ layout: ShellLayout) -> [ShellDestination] {
        switch layout {
        case .rail: ShellDestination.allCases
        case .bottomBar: ShellDestination.allCases.filter { $0 != .vault }
        }
    }

    static func barSelection(_ current: ShellDestination, _ layout: ShellLayout) -> ShellDestination? {
        destinations(layout).contains(current) ? current : nil
    }

    /// +1 forward (content arrives from the trailing edge), -1 back, 0 for no change.
    static func direction(from: ShellDestination, to: ShellDestination) -> Int {
        max(-1, min(1, to.rawValue - from.rawValue))
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
        case .today, .calendar, .needsYou, .lists, .goals, .review: nil
        }
    }
}
