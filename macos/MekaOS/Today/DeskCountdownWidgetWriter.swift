@preconcurrency import MekaKit
import Foundation
import WidgetKit

/// Keeps the Mac's desktop Countdown widget fed (Countdowns, slice 6). Whenever Countdowns changes (one added, hidden or
/// put on Today on any device, Days · Sleeps flipped) and every half hour while Navilife runs (so a new day's number shows
/// after midnight), it writes the countdown as the core cuts it for a widget (`CountdownWidgetRules`, the same words as
/// the Fold's home-screen widget) to `~/Library/Application Support/MekaOS/CountdownWidget/countdown.json`, and asks
/// WidgetKit to redraw only when what it says changed. Only that one file is written; the folder is readable only by
/// this user.
@MainActor
final class DeskCountdownWidgetWriter {
    static let shared = DeskCountdownWidgetWriter()

    private weak var model: CoreModel?
    private var lastWritten: DeskCountdownSnapshot?
    private var timer: Task<Void, Never>?

    /// Called once the model exists.
    func attach(_ model: CoreModel) {
        guard self.model !== model else { return }
        self.model = model
        observe()
        timer?.cancel()
        timer = Task { [weak self] in
            while !Task.isCancelled {
                self?.refresh()
                try? await Task.sleep(for: .seconds(DeskCountdownSnapshot.refreshSeconds))
            }
        }
    }

    private func observe() {
        guard let model else { return }
        withObservationTracking {
            _ = model.countdowns
        } onChange: {
            Task { @MainActor in
                DeskCountdownWidgetWriter.shared.refresh()
                DeskCountdownWidgetWriter.shared.observe()
            }
        }
    }

    /// Nothing is written until Countdowns has been worked out once (the core answers nil while it loads).
    func refresh() {
        guard let model, let view = model.deskCountdownWidget() else { return }
        write(Self.snapshot(view, epochDay: DeskDayListSnapshot.localEpochDay(Date()),
                            nowMs: Int64(Date().timeIntervalSince1970 * 1000)))
    }

    /// The core's widget view as the file the widget reads. Only Strings, numbers and Bools leave the Kotlin view.
    static func snapshot(_ w: WidgetCountdown, epochDay: Int64, nowMs: Int64) -> DeskCountdownSnapshot {
        let rules = CountdownWidgetRules.shared
        return DeskCountdownSnapshot(
            name: w.name,
            number: w.number,
            unit: w.unit,
            dateLine: w.dateLine,
            extra: w.extra,
            ringPercent: min(max(Int(w.ringPercent), 0), 100),
            lit: w.lit,
            next: w.next.map { .init(name: $0.name, count: $0.count) },
            empty: w.empty,
            emptyTitle: rules.EMPTY,
            emptyHint: rules.emptyHint(desk: true),
            spoken: w.spoken,
            epochDay: epochDay,
            writtenAtMs: nowMs)
    }

    private func write(_ snapshot: DeskCountdownSnapshot) {
        var compare = snapshot
        compare.writtenAtMs = lastWritten?.writtenAtMs ?? 0
        if lastWritten == compare { return }
        guard Self.ensureFolder(), let data = try? JSONEncoder().encode(snapshot) else { return }
        do {
            try data.write(to: DeskCountdownSnapshot.fileURL, options: .atomic)
        } catch {
            return
        }
        lastWritten = snapshot
        WidgetCenter.shared.reloadTimelines(ofKind: DeskCountdownSnapshot.widgetKind)
    }

    private static func ensureFolder() -> Bool {
        do {
            try FileManager.default.createDirectory(at: DeskCountdownSnapshot.folder, withIntermediateDirectories: true,
                                                    attributes: [.posixPermissions: 0o700])
            return true
        } catch {
            return false
        }
    }
}
