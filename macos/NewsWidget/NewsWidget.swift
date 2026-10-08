import AppKit
import SwiftUI
import WidgetKit

/// The Mac's desktop News widget (build plan M1, news ticker slice 3b). Mac desktop widgets can't animate, so it shows
/// the top stories still: today's match first in Barça's colour, then Barça and AI, then the rest, each with its
/// picture (the server's small JPEG, saved by MEKA beside the snapshot) or a tile with the source's initial. Small: the
/// first story over its picture. Medium: three rows. Clicking a story opens MEKA's News on it (the match opens News).
/// It reads only what MEKA wrote (`DeskNewsSnapshot`, a read-only sandbox exception for that one folder), fetches
/// nothing itself, and looks again every 30 minutes or when MEKA writes; "2 h ago" moves on between those.
@main
struct MekaNewsWidgetBundle: WidgetBundle {
    var body: some Widget {
        MekaNewsWidget()
    }
}

struct MekaNewsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: DeskNewsSnapshot.widgetKind, provider: DeskNewsProvider()) { entry in
            DeskNewsWidgetView(entry: entry)
                .containerBackground(for: .widget) { DeskNewsBackground() }
        }
        .configurationDisplayName("MEKA News")
        .description("Today's match, Barça and AI headlines with pictures.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct DeskNewsEntry: TimelineEntry {
    let date: Date
    let snapshot: DeskNewsSnapshot?
    /// Picture bytes by file name, read once per timeline.
    let pictures: [String: Data]

    var nowMs: Int64 { Int64(date.timeIntervalSince1970 * 1000) }
    var cards: [DeskNewsSnapshot.Card] { snapshot?.showing(nowMs: nowMs) ?? [] }

    static let placeholder = DeskNewsEntry(
        date: Date(),
        snapshot: DeskNewsSnapshot(
            cards: [
                .init(id: "p1", label: "Barça", title: "Barça news appears here", source: "Sport", publishedAtMs: 0,
                      fixedLine: "Sport · 2 h ago", picture: nil, tileInitial: "S", isBarca: true, openUrl: "mekaos://news",
                      untilMs: Int64.max),
                .init(id: "p2", label: "AI", title: "AI headlines appear here", source: "The Verge", publishedAtMs: 0,
                      fixedLine: "The Verge · 1 h ago", picture: nil, tileInitial: "T", isBarca: false, openUrl: "mekaos://news",
                      untilMs: Int64.max),
                .init(id: "p3", label: "Top stories", title: "And the rest of your topics", source: "BBC News", publishedAtMs: 0,
                      fixedLine: "BBC News · 3 h ago", picture: nil, tileInitial: "B", isBarca: false, openUrl: "mekaos://news",
                      untilMs: Int64.max),
            ],
            emptyTitle: "No news yet", emptyLine: "Choose topics in MEKA · Ask › More › News", writtenAtMs: 0),
        pictures: [:])
}

struct DeskNewsProvider: TimelineProvider {
    func placeholder(in context: Context) -> DeskNewsEntry { .placeholder }

    func getSnapshot(in context: Context, completion: @escaping (DeskNewsEntry) -> Void) {
        completion(context.isPreview && DeskNewsSnapshot.read() == nil ? .placeholder : Self.entry(at: Date()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<DeskNewsEntry>) -> Void) {
        let now = Date()
        let base = Self.entry(at: now)
        // "25 min ago" moves on: an entry every 10 minutes until the next look (MEKA also reloads when it writes).
        let entries = stride(from: 0.0, to: DeskNewsSnapshot.refreshSeconds, by: 600).map {
            DeskNewsEntry(date: now.addingTimeInterval($0), snapshot: base.snapshot, pictures: base.pictures)
        }
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(DeskNewsSnapshot.refreshSeconds))))
    }

    static func entry(at date: Date) -> DeskNewsEntry {
        let snapshot = DeskNewsSnapshot.read()
        var pictures: [String: Data] = [:]
        for name in snapshot?.cards.compactMap(\.picture) ?? [] where DeskNewsSnapshot.isPictureName(name) {
            if let data = try? Data(contentsOf: DeskNewsSnapshot.folder.appendingPathComponent(name)), data.count <= 64 * 1024 {
                pictures[name] = data
            }
        }
        return DeskNewsEntry(date: date, snapshot: snapshot, pictures: pictures)
    }
}

// MARK: Views

private struct DeskNewsColours {
    let palette: MekaPalette
    init(_ scheme: ColorScheme) { palette = scheme == .dark ? .dark : .light }
}

struct DeskNewsBackground: View {
    @Environment(\.colorScheme) private var scheme
    var body: some View { DeskNewsColours(scheme).palette.surface }
}

struct DeskNewsWidgetView: View {
    let entry: DeskNewsEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var scheme

    private var palette: MekaPalette { DeskNewsColours(scheme).palette }

    var body: some View {
        let cards = entry.cards
        if cards.isEmpty {
            empty
        } else if family == .systemSmall, let first = cards.first {
            small(first)
        } else {
            medium(Array(cards.prefix(3)))
        }
    }

    private var empty: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("NEWS").font(.system(size: 11, weight: .semibold)).tracking(0.9).foregroundStyle(palette.textSecondary)
            Spacer(minLength: 0)
            Text(entry.snapshot?.emptyTitle ?? "No news yet").font(.system(size: 15, weight: .medium))
                .foregroundStyle(palette.textPrimary)
            Text(entry.snapshot?.emptyLine ?? "Open MEKA to bring your headlines here")
                .font(.system(size: 12)).foregroundStyle(palette.textSecondary).lineLimit(3)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .widgetURL(URL(string: "mekaos://news"))
    }

    private func small(_ card: DeskNewsSnapshot.Card) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            DeskNewsPicture(card: card, data: card.picture.flatMap { entry.pictures[$0] }, palette: palette)
                .frame(maxWidth: .infinity)
                .frame(height: 64)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            label(card)
            Text(card.title).font(.system(size: 13, weight: .medium)).foregroundStyle(palette.textPrimary)
                .lineLimit(3).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .widgetURL(URL(string: card.openUrl))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(DeskNewsSnapshot.spoken(card, nowMs: entry.nowMs))
    }

    private func medium(_ cards: [DeskNewsSnapshot.Card]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(cards, id: \.id) { card in
                Link(destination: URL(string: card.openUrl) ?? URL(string: "mekaos://news")!) {
                    HStack(alignment: .center, spacing: 10) {
                        DeskNewsPicture(card: card, data: card.picture.flatMap { entry.pictures[$0] }, palette: palette)
                            .frame(width: 56, height: 42)
                            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                        VStack(alignment: .leading, spacing: 1) {
                            Text(card.title).font(.system(size: 12, weight: .medium)).foregroundStyle(palette.textPrimary)
                                .lineLimit(2)
                            HStack(spacing: 4) {
                                Text(card.label.uppercased()).font(.system(size: 9, weight: .semibold)).tracking(0.7)
                                    .foregroundStyle(card.isBarca ? palette.barca : palette.textSecondary)
                                Text(DeskNewsSnapshot.line(card, nowMs: entry.nowMs)).font(.system(size: 10))
                                    .foregroundStyle(palette.textSecondary).lineLimit(1)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(DeskNewsSnapshot.spoken(card, nowMs: entry.nowMs))
            }
            Spacer(minLength: 0)
        }
    }

    private func label(_ card: DeskNewsSnapshot.Card) -> some View {
        HStack(spacing: 4) {
            Text(card.label.uppercased()).font(.system(size: 9, weight: .semibold)).tracking(0.7)
                .foregroundStyle(card.isBarca ? palette.barca : palette.textSecondary)
            Text(DeskNewsSnapshot.line(card, nowMs: entry.nowMs)).font(.system(size: 10))
                .foregroundStyle(palette.textSecondary).lineLimit(1)
        }
    }
}

/// The story's picture, or a tile with the source's initial (Barça's colour on Barça stories and the match).
struct DeskNewsPicture: View {
    let card: DeskNewsSnapshot.Card
    let data: Data?
    let palette: MekaPalette

    var body: some View {
        if let data, let image = NSImage(data: data) {
            Image(nsImage: image).resizable().aspectRatio(contentMode: .fill)
        } else {
            ZStack {
                (card.isBarca ? palette.barca : palette.surfaceRaised)
                Text(card.tileInitial).font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(card.isBarca ? palette.onAccent : palette.textSecondary)
            }
        }
    }
}
