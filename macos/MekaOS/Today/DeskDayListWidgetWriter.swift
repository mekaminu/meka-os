@preconcurrency import MekaKit
import Foundation
import WidgetKit

/// Keeps the Mac's desktop Day list widget fed (the Day list, slice 6b). Whenever the Week board changes (a task added,
/// ticked, moved or reordered on any device), and every half hour while Navilife runs (so a new day shows after
/// midnight), it writes today's Day list as the core cuts it for a widget (`DayListWidgetRules`, the same words as the
/// Fold's home-screen widget) to `~/Library/Application Support/MekaOS/DayListWidget/daylist.json`, and asks WidgetKit
/// to redraw only when what it says changed. Only that one file is written; the folder is readable only by this user.
@MainActor
final class DeskDayListWidgetWriter {
    static let shared = DeskDayListWidgetWriter()

    private weak var model: CoreModel?
    private var lastWritten: DeskDayListSnapshot?
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
                try? await Task.sleep(for: .seconds(DeskDayListSnapshot.refreshSeconds))
            }
        }
    }

    private func observe() {
        guard let model else { return }
        withObservationTracking {
            _ = model.board
        } onChange: {
            Task { @MainActor in
                DeskDayListWidgetWriter.shared.refresh()
                DeskDayListWidgetWriter.shared.observe()
            }
        }
    }

    func refresh() {
        guard let model, model.board != nil, let view = model.deskDayListWidget() else { return }
        write(Self.snapshot(view, epochDay: model.board?.todayEpochDay ?? DeskDayListSnapshot.localEpochDay(Date()),
                            nowMs: Int64(Date().timeIntervalSince1970 * 1000)))
    }

    /// The core's widget view as the file the widget reads. Only Strings, numbers and Bools leave the Kotlin view.
    static func snapshot(_ w: WidgetDayList, epochDay: Int64, nowMs: Int64) -> DeskDayListSnapshot {
        let rules = DayListWidgetRules.shared
        return DeskDayListSnapshot(
            title: w.title,
            line: w.line,
            progressPercent: Int(w.progressPercent),
            rows: w.rows.map { r in
                .init(id: r.id, title: r.title, time: r.time, length: r.length, spoken: r.spoken,
                      tickLabel: rules.tickLabel(r: r))
            },
            more: w.more,
            empty: w.empty,
            emptyHint: rules.emptyHint(desk: true),
            addLabel: rules.ADD,
            spoken: rules.describe(w: w),
            epochDay: epochDay,
            writtenAtMs: nowMs)
    }

    private func write(_ snapshot: DeskDayListSnapshot) {
        var compare = snapshot
        compare.writtenAtMs = lastWritten?.writtenAtMs ?? 0
        if lastWritten == compare { return }
        guard Self.ensureFolder(), let data = try? JSONEncoder().encode(snapshot) else { return }
        do {
            try data.write(to: DeskDayListSnapshot.fileURL, options: .atomic)
        } catch {
            return
        }
        lastWritten = snapshot
        WidgetCenter.shared.reloadTimelines(ofKind: DeskDayListSnapshot.widgetKind)
    }

    private static func ensureFolder() -> Bool {
        do {
            try FileManager.default.createDirectory(at: DeskDayListSnapshot.folder, withIntermediateDirectories: true,
                                                    attributes: [.posixPermissions: 0o700])
            return true
        } catch {
            return false
        }
    }
}
