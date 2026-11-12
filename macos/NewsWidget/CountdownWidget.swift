import SwiftUI
import WidgetKit

/// The Mac's desktop Countdown widget (Countdowns, slice 6; `docs/design/countdowns/Countdowns.dc.html`, "Now on Today and
/// the home-screen widget", beside the Day list and News widgets). The one chosen with Show on Today, else the soonest,
/// with its brass ring and number ("43 days", "43 sleeps" with Sleeps on, "Today" lit on the day), its name, date and
/// extra line ("turns 9"), then on medium and large the next ones ("Holiday · 93 days"). A click anywhere opens
/// Countdowns. It reads only what Navilife wrote (`DeskCountdownSnapshot`) and writes nothing. A widget is still, so
/// there is no motion to reduce.
struct MekaCountdownWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: DeskCountdownSnapshot.widgetKind, provider: DeskCountdownProvider()) { entry in
            DeskCountdownWidgetView(entry: entry)
                .containerBackground(for: .widget) { DeskNewsBackground() }
        }
        .configurationDisplayName("Navilife Countdown")
        .description("Days to go to the next holiday, birthday or Christmas.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

struct DeskCountdownEntry: TimelineEntry {
    let date: Date
    let snapshot: DeskCountdownSnapshot?

    /// Today's snapshot only; one left from an earlier day has yesterday's number.
    var today: DeskCountdownSnapshot? {
        guard let snapshot, snapshot.isFor(epochDay: DeskDayListSnapshot.localEpochDay(date)) else { return nil }
        return snapshot
    }

    static let placeholder = DeskCountdownEntry(
        date: Date(),
        snapshot: DeskCountdownSnapshot(
            name: "Christmas", number: "43", unit: "days", dateLine: "Fri 25 Dec", extra: nil, ringPercent: 60, lit: false,
            next: [.init(name: "Holiday", count: "93 days"), .init(name: "A birthday", count: "120 days")],
            empty: false, emptyTitle: "", emptyHint: "", spoken: "Countdown",
            epochDay: DeskDayListSnapshot.localEpochDay(Date()), writtenAtMs: 0))
}

struct DeskCountdownProvider: TimelineProvider {
    func placeholder(in context: Context) -> DeskCountdownEntry { .placeholder }

    func getSnapshot(in context: Context, completion: @escaping (DeskCountdownEntry) -> Void) {
        let snapshot = DeskCountdownSnapshot.read()
        completion(context.isPreview && snapshot == nil ? .placeholder : DeskCountdownEntry(date: Date(), snapshot: snapshot))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<DeskCountdownEntry>) -> Void) {
        let now = Date()
        let snapshot = DeskCountdownSnapshot.read()
        var entries = [DeskCountdownEntry(date: now, snapshot: snapshot)]
        // At midnight yesterday's number gives way, even before Navilife writes the new day's.
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let next = now.addingTimeInterval(DeskCountdownSnapshot.refreshSeconds)
        if let midnight = cal.nextDate(after: now, matching: DateComponents(hour: 0, minute: 0, second: 5),
                                       matchingPolicy: .nextTime), midnight < next {
            entries.append(DeskCountdownEntry(date: midnight, snapshot: snapshot))
        }
        completion(Timeline(entries: entries, policy: .after(next)))
    }
}

struct DeskCountdownWidgetView: View {
    let entry: DeskCountdownEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var scheme

    private var palette: MekaPalette { scheme == .dark ? .dark : .light }

    var body: some View {
        Group {
            if let c = entry.today {
                if c.empty { empty(c) } else { content(c) }
            } else {
                notYet
            }
        }
        .widgetURL(DeskCountdownLink.url)
    }

    private var ringSize: CGFloat { family == .systemLarge ? 112 : (family == .systemSmall ? 76 : 88) }
    private var nextShown: Int { family == .systemSmall ? DeskCountdownSnapshot.smallNext : Int.max }

    private func content(_ c: DeskCountdownSnapshot) -> some View {
        let lines = Array(c.next.prefix(nextShown))
        return VStack(alignment: .leading, spacing: 8) {
            Text("COUNTDOWN").font(.system(size: 11, weight: .semibold)).tracking(0.9)
                .foregroundStyle(palette.textSecondary)
            if family == .systemSmall {
                HStack { Spacer(minLength: 0); ring(c); Spacer(minLength: 0) }
                Text(c.name).font(.system(size: 13, weight: .medium)).foregroundStyle(palette.textPrimary)
                    .lineLimit(1).frame(maxWidth: .infinity)
            } else {
                HStack(alignment: .center, spacing: 14) {
                    ring(c)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(c.name).font(.system(size: 15, weight: .medium)).foregroundStyle(palette.textPrimary)
                            .lineLimit(2)
                        if !c.lit {
                            Text(c.dateLine).font(.system(size: 12)).foregroundStyle(palette.textSecondary).lineLimit(1)
                        }
                        if let extra = c.extra {
                            Text(extra).font(.system(size: 12)).foregroundStyle(palette.textSecondary).lineLimit(1)
                        }
                    }
                    Spacer(minLength: 0)
                }
                if !lines.isEmpty {
                    Rectangle().fill(palette.hairline).frame(height: 1)
                    ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                        Text(DeskCountdownSnapshot.nextLine(line)).font(.system(size: 12))
                            .foregroundStyle(palette.textSecondary).lineLimit(1)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(c.spoken)
    }

    /// The brass ring, fuller as the day gets closer, with the number inside it (lit on the day).
    private func ring(_ c: DeskCountdownSnapshot) -> some View {
        ZStack {
            Circle().stroke(palette.surfaceRaised, lineWidth: 5)
            Circle().trim(from: 0, to: CGFloat(c.ringPercent) / 100)
                .stroke(palette.accent, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                .rotationEffect(.degrees(-90))
            VStack(spacing: 0) {
                Text(c.number)
                    .font(.system(size: c.lit ? ringSize * 0.22 : ringSize * 0.32, weight: .semibold).monospacedDigit())
                    .foregroundStyle(c.lit ? palette.accent : palette.textPrimary)
                    .minimumScaleFactor(0.6).lineLimit(1)
                if !c.unit.isEmpty {
                    Text(c.unit).font(.system(size: 11)).foregroundStyle(palette.textSecondary)
                }
            }
            .padding(8)
        }
        .frame(width: ringSize, height: ringSize)
    }

    private func empty(_ c: DeskCountdownSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("COUNTDOWN").font(.system(size: 11, weight: .semibold)).tracking(0.9).foregroundStyle(palette.textSecondary)
            Spacer(minLength: 0)
            Text(c.emptyTitle).font(.system(size: 15, weight: .medium)).foregroundStyle(palette.textPrimary)
            Text(c.emptyHint).font(.system(size: 12)).foregroundStyle(palette.textSecondary).lineLimit(3)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(c.spoken)
    }

    private var notYet: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("COUNTDOWN").font(.system(size: 11, weight: .semibold)).tracking(0.9).foregroundStyle(palette.textSecondary)
            Spacer(minLength: 0)
            Text("Countdowns").font(.system(size: 15, weight: .medium)).foregroundStyle(palette.textPrimary)
            Text("Open Navilife to bring your countdowns here").font(.system(size: 12))
                .foregroundStyle(palette.textSecondary).lineLimit(3)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}
