import SwiftUI
import WidgetKit

/// The Mac's desktop Day list widget (the Day list, slice 6b; `docs/design/day-list/Today.dc.html`, "the home-screen
/// widget", beside the News widget). "Today" with the done line and the gold done bar, today's open tasks (a tick
/// circle · own time · title · length; three on small, four on medium and large) and "+n more", then + Add tasks.
/// A click on the header or a title opens today's Day list; + Add tasks opens it ready to type; a tick marks that task
/// done in Navilife (with Undo there). It reads only what Navilife wrote (`DeskDayListSnapshot`) and writes nothing.
struct MekaDayListWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: DeskDayListSnapshot.widgetKind, provider: DeskDayListProvider()) { entry in
            DeskDayListWidgetView(entry: entry)
                .containerBackground(for: .widget) { DeskNewsBackground() }
        }
        .configurationDisplayName("Navilife Day list")
        .description("Today's tasks: tick one off or add more.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct DeskDayListEntry: TimelineEntry {
    let date: Date
    let snapshot: DeskDayListSnapshot?

    /// Today's snapshot only; one left from an earlier day isn't today's list.
    var today: DeskDayListSnapshot? {
        guard let snapshot, snapshot.isFor(epochDay: DeskDayListSnapshot.localEpochDay(date)) else { return nil }
        return snapshot
    }

    static let placeholder = DeskDayListEntry(
        date: Date(),
        snapshot: DeskDayListSnapshot(
            title: "Today", line: "1 of 4 done · ends about 11:15", progressPercent: 25,
            rows: [
                .init(id: "p1", title: "Your tasks for today", time: "09:00", length: "30m", spoken: "", tickLabel: ""),
                .init(id: "p2", title: "Tick one off here", time: nil, length: "15m", spoken: "", tickLabel: ""),
                .init(id: "p3", title: "Or add more", time: nil, length: nil, spoken: "", tickLabel: ""),
            ],
            more: nil, empty: false, emptyHint: "", addLabel: "+ Add tasks", spoken: "Today",
            epochDay: DeskDayListSnapshot.localEpochDay(Date()), writtenAtMs: 0))
}

struct DeskDayListProvider: TimelineProvider {
    func placeholder(in context: Context) -> DeskDayListEntry { .placeholder }

    func getSnapshot(in context: Context, completion: @escaping (DeskDayListEntry) -> Void) {
        let snapshot = DeskDayListSnapshot.read()
        completion(context.isPreview && snapshot == nil ? .placeholder : DeskDayListEntry(date: Date(), snapshot: snapshot))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<DeskDayListEntry>) -> Void) {
        let now = Date()
        let snapshot = DeskDayListSnapshot.read()
        var entries = [DeskDayListEntry(date: now, snapshot: snapshot)]
        // At midnight yesterday's list gives way, even before Navilife writes the new day's.
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let next = now.addingTimeInterval(DeskDayListSnapshot.refreshSeconds)
        if let midnight = cal.nextDate(after: now, matching: DateComponents(hour: 0, minute: 0, second: 5),
                                       matchingPolicy: .nextTime), midnight < next {
            entries.append(DeskDayListEntry(date: midnight, snapshot: snapshot))
        }
        completion(Timeline(entries: entries, policy: .after(next)))
    }
}

struct DeskDayListWidgetView: View {
    let entry: DeskDayListEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var scheme

    private var palette: MekaPalette { scheme == .dark ? .dark : .light }

    var body: some View {
        if let list = entry.today {
            content(list)
        } else {
            notYet
        }
    }

    private var rowsShown: Int { family == .systemSmall ? DeskDayListSnapshot.smallRows : Int.max }

    private func content(_ list: DeskDayListSnapshot) -> some View {
        let rows = Array(list.rows.prefix(rowsShown))
        let hidden = list.rows.count - rows.count
        let more = hidden > 0 ? DeskDayListSnapshot.moreLine(hidden: hidden, coreMore: list.more) : list.more
        return VStack(alignment: .leading, spacing: 6) {
            Link(destination: DeskDayListLink.open.url) { header(list) }
            if list.empty {
                Text(list.emptyHint).font(.system(size: 12)).foregroundStyle(palette.textSecondary)
                    .lineLimit(3).fixedSize(horizontal: false, vertical: true)
            } else {
                ForEach(rows, id: \.id) { row(row: $0) }
                if let more {
                    Link(destination: DeskDayListLink.open.url) {
                        Text(more).font(.system(size: 11)).foregroundStyle(palette.textSecondary)
                    }
                }
            }
            Spacer(minLength: 0)
            Link(destination: DeskDayListLink.add.url) {
                Text(list.addLabel).font(.system(size: 12, weight: .semibold)).foregroundStyle(palette.accent)
                    .frame(maxWidth: .infinity, minHeight: 24, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(list.addLabel)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private func header(_ list: DeskDayListSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(list.title.uppercased()).font(.system(size: 11, weight: .semibold)).tracking(0.9)
                .foregroundStyle(palette.textSecondary)
            Text(list.line).font(.system(size: 12)).foregroundStyle(palette.textPrimary).lineLimit(1)
            // The gold done bar, as the Day list's.
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.surfaceRaised)
                    Capsule().fill(palette.accent)
                        .frame(width: g.size.width * CGFloat(min(max(list.progressPercent, 0), 100)) / 100)
                }
            }
            .frame(height: 4)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(list.spoken)
    }

    private func row(row: DeskDayListSnapshot.Row) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Link(destination: DeskDayListLink.done(taskId: row.id).url) {
                Image(systemName: "circle").font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                    .frame(width: 18, height: 18)
                    .contentShape(Rectangle())
            }
            .accessibilityLabel(row.tickLabel)
            Link(destination: DeskDayListLink.open.url) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    if let time = row.time {
                        Text(time).font(.system(size: 11).monospacedDigit()).foregroundStyle(palette.textSecondary)
                    }
                    Text(row.title).font(.system(size: 12)).foregroundStyle(palette.textPrimary).lineLimit(1)
                    Spacer(minLength: 0)
                    if let length = row.length, family != .systemSmall {
                        Text(length).font(.system(size: 10).monospacedDigit()).foregroundStyle(palette.textSecondary)
                    }
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(row.spoken)
        }
    }

    private var notYet: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("TODAY").font(.system(size: 11, weight: .semibold)).tracking(0.9).foregroundStyle(palette.textSecondary)
            Spacer(minLength: 0)
            Text("Day list").font(.system(size: 15, weight: .medium)).foregroundStyle(palette.textPrimary)
            Text("Open Navilife to bring today's tasks here").font(.system(size: 12))
                .foregroundStyle(palette.textSecondary).lineLimit(3)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .widgetURL(DeskDayListLink.open.url)
    }
}
