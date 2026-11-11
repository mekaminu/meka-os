import Foundation

/// What Navilife hands the Mac's desktop Day list widget (the Day list, slice 6b; the Fold's home-screen widget is 6a).
/// The widget runs sandboxed without the core, so the app writes this small file into
/// `~/Library/Application Support/MekaOS/DayListWidget/`, the one other folder the widget may read (a read-only sandbox
/// exception beside News's). The words come from the core's `DayListWidgetRules` (checked by DeskDayListSnapshotTests).
/// Clicks open Navilife through `mekaos://day-list…` links (`DeskDayListLink`): a tick marks that task done there, so
/// the widget itself never writes anything. Compiled into both the app and the widget, so nothing here belongs to an actor.
nonisolated struct DeskDayListSnapshot: Codable, Equatable, Sendable {
    nonisolated struct Row: Codable, Equatable, Sendable {
        var id: String
        var title: String
        /// "09:00" when the task has its own time; nil otherwise.
        var time: String?
        /// "30m"; nil when there is no length.
        var length: String?
        /// "Book MOT, 09:00, 30m long".
        var spoken: String
        /// "Mark done: Book MOT".
        var tickLabel: String
    }

    /// "Today"
    var title: String
    /// "1 of 4 done · ends about 11:15" · "All 4 done" · "Nothing planned yet"
    var line: String
    /// 0...100, the gold done bar.
    var progressPercent: Int
    var rows: [Row]
    /// "+2 more"
    var more: String?
    var empty: Bool
    /// "Click + Add tasks and type one after another"
    var emptyHint: String
    /// "+ Add tasks"
    var addLabel: String
    /// What a screen reader says for the whole widget.
    var spoken: String
    /// The day it was written for (days since 1970 in this Mac's time zone, as the board counts), so a list left over from yesterday isn't shown as today's.
    var epochDay: Int64
    /// When Navilife wrote it (ms).
    var writtenAtMs: Int64

    static let fileName = "daylist.json"
    /// The widget looks again this often besides when Navilife writes (`DayListWidgetRules.DESK_REFRESH_MS`).
    static let refreshSeconds: TimeInterval = 30 * 60
    /// The widget's kind, for `WidgetCenter.reloadTimelines(ofKind:)`.
    static let widgetKind = "os.meka.mac.day-list"
    /// Small shows three rows, medium and large the core's four.
    static let smallRows = 3

    /// `~/Library/Application Support/MekaOS/DayListWidget/` (the widget's read-only sandbox exception names this path).
    static var folder: URL {
        DeskNewsSnapshot.realHome.appendingPathComponent("Library/Application Support/MekaOS/DayListWidget", isDirectory: true)
    }

    static var fileURL: URL { folder.appendingPathComponent(fileName) }

    /// Reads the snapshot; nil when Navilife hasn't written one or it can't be read.
    static func read(from url: URL = fileURL) -> DeskDayListSnapshot? {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe), data.count < 128 * 1024 else { return nil }
        return try? JSONDecoder().decode(DeskDayListSnapshot.self, from: data)
    }

    /// Whether it was written for [epochDay]; a snapshot from an earlier day shows "Open Navilife" until it writes again.
    func isFor(epochDay day: Int64) -> Bool { epochDay == day }

    /// Small shows three rows: the ones it leaves off join the core's "+n more" ("+2 more" and 1 left off: "+3 more").
    static func moreLine(hidden: Int, coreMore: String?) -> String {
        let fromCore = coreMore.flatMap { Int(String($0.drop(while: { $0 == "+" }).prefix(while: { $0.isNumber }))) } ?? 0
        return "+\(hidden + fromCore) more"
    }

    /// Today's day number in this Mac's time zone, as the board counts days, from inside the widget too.
    static func localEpochDay(_ date: Date, in zone: TimeZone = .current) -> Int64 {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let c = cal.dateComponents([.year, .month, .day], from: date)
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        guard let midnight = utc.date(from: DateComponents(year: c.year, month: c.month, day: c.day)) else { return 0 }
        return Int64((midnight.timeIntervalSince1970 / 86_400).rounded(.down))
    }
}

/// The widget's links back into Navilife: the header and a title open today's Day list, + Add tasks opens it ready to
/// type (as ⌘N and the Dock's Add Tasks…), and a tick marks that task done.
nonisolated enum DeskDayListLink: Equatable, Sendable {
    case open
    case add
    case done(taskId: String)

    static let host = "day-list"

    var url: URL {
        var c = URLComponents()
        c.scheme = "mekaos"
        c.host = Self.host
        switch self {
        case .open: break
        case .add: c.queryItems = [URLQueryItem(name: "add", value: "1")]
        case .done(let id):
            c.path = "/done"
            // "&", "=" and "+" are left alone by queryItems, so the id is encoded by hand: only unreserved characters stay.
            let unreserved = CharacterSet(charactersIn:
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
            let encoded = id.addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
            c.percentEncodedQueryItems = [URLQueryItem(name: "task", value: encoded)]
        }
        return c.url ?? URL(string: "mekaos://day-list")!
    }

    /// nil for anything that isn't one of the widget's links. A done link names a task id only (no paths, at most 200
    /// characters); the app also checks the task is on today's list before ticking it.
    static func parse(_ url: URL) -> DeskDayListLink? {
        guard url.scheme == "mekaos", url.host == host,
              let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let items = c.queryItems ?? []
        switch c.path {
        case "", "/":
            return items.contains { $0.name == "add" && $0.value == "1" } ? .add : .open
        case "/done":
            guard let id = items.first(where: { $0.name == "task" })?.value, !id.isEmpty, id.count <= 200,
                  !id.contains("/"), !id.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
            else { return nil }
            return .done(taskId: id)
        default:
            return nil
        }
    }
}
