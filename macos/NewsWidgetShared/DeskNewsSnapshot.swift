import Foundation

/// What MEKA hands the Mac's desktop News widget (build plan M1, news ticker slice 3b). The widget runs in its own
/// sandboxed process without the core, so the app writes this small file (and each card's picture beside it) into
/// `~/Library/Application Support/MekaOS/NewsWidget/`, the one folder the widget may read (a read-only sandbox
/// exception; no app group, which a build without the Apple Developer Program can't have). The cards come from
/// `DeskNewsWidgetRules` in core; a story's line ("Sport · 2 h ago") is said here as time moves on, in the same words
/// as `NewsRules.age` (checked by DeskNewsSnapshotTests). Headlines are untrusted (ADR-006): shown as text only.
/// Compiled into both the app and the widget, so nothing here belongs to an actor.
nonisolated struct DeskNewsSnapshot: Codable, Equatable, Sendable {
    nonisolated struct Card: Codable, Equatable, Sendable {
        var id: String
        /// "Barça", "AI" … or "MATCHDAY" / "ON NOW".
        var label: String
        var title: String
        /// The publisher; empty for the match.
        var source: String
        /// Milliseconds since 1970; 0 for the match.
        var publishedAtMs: Int64
        /// The match's "21:00 · in 3 h"; nil for a story.
        var fixedLine: String?
        /// The picture's file name beside the snapshot ("<key>.jpg"), when MEKA has it.
        var picture: String?
        var tileInitial: String
        var isBarca: Bool
        /// `mekaos://news?story=<id>` or `mekaos://news`.
        var openUrl: String
        /// After this (ms) the card isn't shown: the match's final whistle.
        var untilMs: Int64
    }

    var cards: [Card]
    var emptyTitle: String
    var emptyLine: String
    /// When MEKA wrote it (ms).
    var writtenAtMs: Int64

    static let fileName = "news.json"
    /// The widget looks again this often besides when MEKA writes (`DeskNewsWidgetRules.REFRESH_MS`).
    static let refreshSeconds: TimeInterval = 30 * 60
    /// The widget's kind, for `WidgetCenter.reloadTimelines(ofKind:)`.
    static let widgetKind = "os.meka.mac.news"

    /// The user's real home, also from inside the widget's sandbox (where `NSHomeDirectory()` is the container).
    static var realHome: URL {
        if let pw = getpwuid(getuid()), let dir = pw.pointee.pw_dir {
            return URL(fileURLWithPath: String(cString: dir), isDirectory: true)
        }
        return URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)
    }

    /// `~/Library/Application Support/MekaOS/NewsWidget/` (the widget's read-only sandbox exception names this path).
    static var folder: URL {
        realHome.appendingPathComponent("Library/Application Support/MekaOS/NewsWidget", isDirectory: true)
    }

    static var fileURL: URL { folder.appendingPathComponent(fileName) }

    /// The cards still to show at [nowMs] (`DeskNewsWidgetRules.showing`).
    func showing(nowMs: Int64) -> [Card] { cards.filter { nowMs < $0.untilMs } }

    /// "just now", "25 min ago", "3 h ago", "yesterday" (`NewsRules.age`).
    static func age(publishedAtMs: Int64, nowMs: Int64) -> String {
        let min = max(0, (nowMs - publishedAtMs) / 60_000)
        if min < 5 { return "just now" }
        if min < 60 { return "\(min) min ago" }
        if min < 24 * 60 { return "\(min / 60) h ago" }
        return "yesterday"
    }

    /// The card's line at [nowMs] (`DeskNewsWidgetRules.line`).
    static func line(_ card: Card, nowMs: Int64) -> String {
        if let fixed = card.fixedLine { return fixed }
        return [card.source, age(publishedAtMs: card.publishedAtMs, nowMs: nowMs)].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// What a screen reader says (`DeskNewsWidgetRules.spoken`).
    static func spoken(_ card: Card, nowMs: Int64) -> String { "\(card.label) · \(line(card, nowMs: nowMs)): \(card.title)" }

    /// Reads the snapshot; nil when MEKA hasn't written one or it can't be read.
    static func read(from url: URL = fileURL) -> DeskNewsSnapshot? {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe), data.count < 256 * 1024 else { return nil }
        return try? JSONDecoder().decode(DeskNewsSnapshot.self, from: data)
    }

    /// A picture file name is only ever "<32 hex>.jpg" (the server's key), never a path.
    static func isPictureName(_ name: String) -> Bool {
        guard name.hasSuffix(".jpg") else { return false }
        let key = name.dropLast(4)
        return key.count == 32 && key.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
    }
}
