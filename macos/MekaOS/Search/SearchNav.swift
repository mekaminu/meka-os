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
        // Ask → More → Errands springs up (errands by location, slice 2b).
        case .errands: .ask
        // Ask → More → Appointments springs up (appointment nudges, slice 2a).
        case .appointments: .ask
        // A help entry: Ask opens where the feature lives, as Take me there does (Ask knows how Navilife works, slice 2).
        case .help: .ask
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
        case .listsWaiting: "Opens Promises"
        case .listsSomeday: "Opens Someday"
        case .listsDecisions: "Opens Decisions"
        case .listsRenewals: "Opens Renewals"
        case .listsShopping: "Opens Shopping"
        case .errands: "Opens Errands"
        case .appointments: "Opens Appointments"
        case .goals: "Opens Goals"
        case .help: "Opens where it lives"
        default: nil
        }
    }
}
