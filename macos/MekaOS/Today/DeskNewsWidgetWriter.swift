@preconcurrency import MekaKit
import Foundation
import WidgetKit

/// Keeps the Mac's desktop News widget fed (build plan M1, news ticker slice 3b). Whenever the News place changes, and
/// every half hour while MEKA runs (the match's "in 3 h" moves on), it writes the top three stories
/// (`DeskNewsWidgetRules`) to `~/Library/Application Support/MekaOS/NewsWidget/news.json` with each story's picture
/// beside it as "<key>.jpg" (the server's small JPEG, fetched through the core's cache, never from the publisher), and
/// asks WidgetKit to redraw only when what it says changed. Pictures no card names are removed. Only files in that one
/// folder are written; the folder is readable only by this user.
@MainActor
final class DeskNewsWidgetWriter {
    static let shared = DeskNewsWidgetWriter()

    private weak var model: CoreModel?
    private var lastWritten: DeskNewsSnapshot?
    private var timer: Task<Void, Never>?
    private var writing: Task<Void, Never>?

    /// Called once the model exists.
    func attach(_ model: CoreModel) {
        guard self.model !== model else { return }
        self.model = model
        observe()
        timer?.cancel()
        timer = Task { [weak self] in
            while !Task.isCancelled {
                self?.refresh()
                try? await Task.sleep(for: .seconds(DeskNewsSnapshot.refreshSeconds))
            }
        }
    }

    private func observe() {
        guard let model else { return }
        withObservationTracking {
            _ = model.newsPlace
        } onChange: {
            Task { @MainActor in
                DeskNewsWidgetWriter.shared.refresh()
                DeskNewsWidgetWriter.shared.observe()
            }
        }
    }

    func refresh() {
        guard let model, model.newsPlace != nil, let view = model.deskNewsWidget() else { return }
        // Only Strings, numbers and Bools leave the Kotlin view; nothing Kotlin crosses into the task.
        let cards = view.cards.map { c in
            (id: c.id, label: c.label, title: c.title, source: c.source, publishedAtMs: c.publishedAtMs,
             fixedLine: c.fixedLine, imageKey: c.imageKey, tileInitial: c.tileInitial, isBarca: c.isBarca,
             openUrl: c.openUrl, untilMs: c.untilMs)
        }
        let emptyTitle = view.emptyTitle
        let emptyLine = view.emptyLine
        writing?.cancel()
        writing = Task { [weak self] in
            var out: [DeskNewsSnapshot.Card] = []
            for c in cards {
                var picture: String?
                if let key = c.imageKey {
                    let name = "\(key).jpg"
                    if DeskNewsSnapshot.isPictureName(name) {
                        if FileManager.default.fileExists(atPath: DeskNewsSnapshot.folder.appendingPathComponent(name).path) {
                            picture = name
                        } else if let data = await model.newsImageData(key), Self.save(data, as: name) {
                            picture = name
                        }
                    }
                }
                out.append(.init(id: c.id, label: c.label, title: c.title, source: c.source, publishedAtMs: c.publishedAtMs,
                                 fixedLine: c.fixedLine, picture: picture, tileInitial: c.tileInitial, isBarca: c.isBarca,
                                 openUrl: c.openUrl, untilMs: c.untilMs))
            }
            if Task.isCancelled { return }
            self?.write(DeskNewsSnapshot(cards: out, emptyTitle: emptyTitle, emptyLine: emptyLine,
                                         writtenAtMs: Int64(Date().timeIntervalSince1970 * 1000)))
        }
    }

    private func write(_ snapshot: DeskNewsSnapshot) {
        let same = lastWritten.map { $0.cards == snapshot.cards && $0.emptyTitle == snapshot.emptyTitle } ?? false
        if same { return }
        guard Self.ensureFolder(), let data = try? JSONEncoder().encode(snapshot) else { return }
        do {
            try data.write(to: DeskNewsSnapshot.fileURL, options: .atomic)
        } catch {
            return
        }
        lastWritten = snapshot
        Self.tidy(keeping: Set(snapshot.cards.compactMap(\.picture)))
        WidgetCenter.shared.reloadTimelines(ofKind: DeskNewsSnapshot.widgetKind)
    }

    private static func ensureFolder() -> Bool {
        do {
            try FileManager.default.createDirectory(at: DeskNewsSnapshot.folder, withIntermediateDirectories: true,
                                                    attributes: [.posixPermissions: 0o700])
            return true
        } catch {
            return false
        }
    }

    private static func save(_ data: Data, as name: String) -> Bool {
        guard ensureFolder(), data.count <= 64 * 1024 else { return false }
        return (try? data.write(to: DeskNewsSnapshot.folder.appendingPathComponent(name), options: .atomic)) != nil
    }

    /// Removes pictures no card names (only "<key>.jpg" files; nothing else in the folder is touched).
    private static func tidy(keeping: Set<String>) {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: DeskNewsSnapshot.folder.path)) ?? []
        for name in names where DeskNewsSnapshot.isPictureName(name) && !keeping.contains(name) {
            try? FileManager.default.removeItem(at: DeskNewsSnapshot.folder.appendingPathComponent(name))
        }
    }
}

/// mekaos://news?story=<id>: the story the widget's card opens (nil for News itself, which leads with the match).
enum DeskNewsLink {
    static func storyId(_ url: URL) -> String? {
        guard url.scheme == "mekaos", url.host == "news" else { return nil }
        let id = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?
            .first { $0.name == DeskNewsWidgetRules.shared.STORY_PARAM }?.value
        guard let id, !id.isEmpty, id.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }) else { return nil }
        return id
    }
}
