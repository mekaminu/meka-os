import Foundation

/// What Navilife hands the Mac's desktop Countdown widget (Countdowns, slice 6; the Fold's home-screen widget is slice 4).
/// The widget runs sandboxed without the core, so the app writes this small file into
/// `~/Library/Application Support/MekaOS/CountdownWidget/`, a read-only sandbox exception beside News's and Day list's.
/// The words come from the core's `CountdownWidgetRules` (checked by DeskCountdownSnapshotTests). A click anywhere opens
/// Countdowns through `mekaos://countdowns`, so the widget never writes anything. Compiled into both the app and the
/// widget, so nothing here belongs to an actor.
nonisolated struct DeskCountdownSnapshot: Codable, Equatable, Sendable {
    nonisolated struct Line: Codable, Equatable, Sendable {
        /// "Holiday"
        var name: String
        /// "93 days" · "tomorrow" · "today"
        var count: String
    }

    /// "Christmas"
    var name: String
    /// "43", or "Today" on the day.
    var number: String
    /// "days" / "sleeps"; "" on the day.
    var unit: String
    /// "Fri 25 Dec"
    var dateLine: String
    /// "turns 9", "breaks up Fri 18 Dec"; nil when there's nothing to add.
    var extra: String?
    /// 0...100, how much of the brass ring is drawn.
    var ringPercent: Int
    /// On the day: the number lit.
    var lit: Bool
    /// The next ones, soonest first.
    var next: [Line]
    /// Nothing to count down to: the widget asks for one.
    var empty: Bool
    /// "No countdowns yet"
    var emptyTitle: String
    /// "Click to add a holiday or a birthday"
    var emptyHint: String
    /// What a screen reader says for the whole widget.
    var spoken: String
    /// The day it was written for (days since 1970 in this Mac's time zone), so yesterday's number isn't shown as today's.
    var epochDay: Int64
    /// When Navilife wrote it (ms).
    var writtenAtMs: Int64

    static let fileName = "countdown.json"
    /// The widget looks again this often besides when Navilife writes, and Navilife rewrites it as often while it runs.
    static let refreshSeconds: TimeInterval = 30 * 60
    /// The widget's kind, for `WidgetCenter.reloadTimelines(ofKind:)`.
    static let widgetKind = "os.meka.mac.countdown"
    /// Small shows the big one only; medium and large add the next ones.
    static let smallNext = 0

    /// `~/Library/Application Support/MekaOS/CountdownWidget/` (the widget's read-only sandbox exception names this path).
    static var folder: URL {
        DeskNewsSnapshot.realHome.appendingPathComponent("Library/Application Support/MekaOS/CountdownWidget", isDirectory: true)
    }

    static var fileURL: URL { folder.appendingPathComponent(fileName) }

    /// Reads the snapshot; nil when Navilife hasn't written one or it can't be read.
    static func read(from url: URL = fileURL) -> DeskCountdownSnapshot? {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe), data.count < 64 * 1024 else { return nil }
        return try? JSONDecoder().decode(DeskCountdownSnapshot.self, from: data)
    }

    /// Whether it was written for [epochDay]; a number from an earlier day shows "Open Navilife" until it writes again.
    func isFor(epochDay day: Int64) -> Bool { epochDay == day }

    /// "Holiday · 93 days", as the Fold's widget.
    static func nextLine(_ line: Line) -> String { "\(line.name) · \(line.count)" }
}

/// The widget's one link back into Navilife: Countdowns.
nonisolated enum DeskCountdownLink {
    static let host = "countdowns"
    static let url = URL(string: "mekaos://countdowns")!

    /// Whether [url] is the widget's link.
    static func matches(_ url: URL) -> Bool { url.scheme == "mekaos" && url.host == host }
}
