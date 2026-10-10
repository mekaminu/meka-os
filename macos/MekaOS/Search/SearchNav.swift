@preconcurrency import MekaKit
import Foundation

/// Something search asked a destination to open: its tab (Lists) and the row to unfold.
struct OpenItem: Equatable {
    let target: SearchTarget
    let id: String
}

/// Where a search result goes (build plan M1, Search everything). Matches android/.../shell/SearchNav.kt rule for rule.
/// Tasks open their detail beside the results instead.
enum SearchNav {
    /// The destination that shows `target`; nil for results that open in place (tasks) or not at all (events).
    static func destination(_ target: SearchTarget) -> ShellDestination? {
        switch target {
        case .listsWaiting, .listsSomeday, .listsDecisions, .listsRenewals, .listsShopping: .lists
        case .goals: .goals
        default: nil
        }
    }

    /// The Lists tab for a Lists result.
    static func listTab(_ target: SearchTarget) -> ListTab? {
        switch target {
        case .listsWaiting: .waiting
        case .listsSomeday: .someday
        case .listsDecisions: .decisions
        case .listsRenewals: .renewals
        case .listsShopping: .shopping
        default: nil
        }
    }

    /// VoiceOver hint for a result.
    static func hint(_ target: SearchTarget) -> String? {
        switch target {
        case .task: "Opens the task"
        case .listsWaiting: "Opens Waiting for"
        case .listsSomeday: "Opens Someday"
        case .listsDecisions: "Opens Decisions"
        case .listsRenewals: "Opens Renewals"
        case .listsShopping: "Opens Shopping"
        case .goals: "Opens Goals"
        default: nil
        }
    }
}
