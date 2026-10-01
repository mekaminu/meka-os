import Foundation
@preconcurrency import MekaKit
import Observation
import SwiftUI

/// Bridges the Kotlin `MekaCore` facade into SwiftUI. Kotlin owns all state and rules; this only mirrors flows.
@MainActor
@Observable
final class CoreModel {
    private(set) var today: Today?
    private(set) var syncLine: String?
    private(set) var conflicts: [ConflictChoice] = []
    var selectedID: String?
    var focusCapture = false
    var lastError: String?
    private(set) var isConnected = false
    var showConnect = false
    private var identity: DeviceIdentity?

    private var core: MekaCore?
    private var observers: [Task<Void, Never>] = []

    func start() async {
        guard core == nil else { return }
        let identity = DeviceIdentity.loadOrCreate()
        self.identity = identity
        let syncURL = identity.serverURL
        let core = MacCoreFactory.shared.create(
            householdId: identity.householdID,
            deviceId: identity.deviceID,
            syncUrl: syncURL,
            deviceSecret: identity.deviceSecret,
            databaseKeyHex: nil,     // spike S6: SQLCipher linkage pending (ADR-002)
            encrypted: false,
            databaseDirectory: Self.databaseDirectory(),
            databaseName: "meka.db"
        )
        self.core = core
        observers.append(Task { [weak self] in
            for await t in core.today { self?.today = t }
        })
        observers.append(Task { [weak self] in
            for await s in core.syncStatus { self?.syncLine = Self.describe(s) }
        })
        observers.append(Task { [weak self] in
            for await c in core.conflicts { self?.conflicts = c }
        })
        core.startSync(periodMs: 5 * 60_000)
        isConnected = core.isConnected
    }

    /// Default for the Connect sheet: the saved server, else the build-time setting.
    var defaultServerURL: String {
        identity?.serverURL ?? (Bundle.main.object(forInfoDictionaryKey: "MekaSyncURL") as? String) ?? ""
    }

    /// One-time enrolment. Returns a user-facing error, or nil on success.
    func connect(serverURL: String, code: String) async -> String? {
        guard let core, let identity else { return "Not ready yet" }
        do {
            let result = try await MacCoreFactory.shared.enrol(
                serverUrl: serverURL, enrolCode: code, householdId: identity.householdID,
                deviceId: identity.deviceID, deviceName: Host.current().localizedName ?? "Mac"
            )
            switch onEnum(of: result) {
            case .enrolled(let e):
                let url = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
                DeviceIdentity.saveEnrolment(serverURL: url, secret: e.deviceSecret)
                try await MacCoreFactory.shared.connect(core: core, serverUrl: url, deviceSecret: e.deviceSecret)
                isConnected = true
                return nil
            case .rejected: return "That enrolment code wasn't accepted."
            case .failed(let f): return f.reason
            }
        } catch {
            return error.localizedDescription
        }
    }

    // MARK: Commands

    func add(_ title: String) {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        run { try await $0.addTask(title: trimmed) }
    }

    func complete(_ id: String) {
        if selectedID == id { selectedID = nil }
        run { try await $0.complete(taskId: id) }
    }

    func rename(_ id: String, to title: String) { run { try await $0.rename(taskId: id, title: title) } }
    func delete(_ id: String) { selectedID = nil; run { try await $0.delete(taskId: id) } }
    func resolve(_ choice: ConflictChoice, with option: String) { run { try await $0.resolve(choice: choice, chosenOption: option) } }
    func completeSelected() { if let id = selectedID { complete(id) } }
    func deleteSelected() { if let id = selectedID { delete(id) } }
    func syncNow() async { _ = try? await core?.syncNow() }

    var allTasks: [MekaTask] {
        guard let t = today else { return [] }
        return t.needsYou.map(\.task) + (t.upNext.map { [$0] } ?? []) + t.yourDay
    }

    var selected: MekaTask? { allTasks.first { $0.id == selectedID } }

    private func run(_ body: @escaping (MekaCore) async throws -> Void) {
        guard let core else { return }
        Task {
            do { try await body(core) } catch { lastError = error.localizedDescription }
        }
    }

    /// ~/Library/Application Support/os.meka.mac — app-specific, created on first launch.
    private static func databaseDirectory() -> String {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("os.meka.mac", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.path
    }

    /// Silence is the default: only offline-with-pending and failures are worth a line.
    private static func describe(_ s: SyncStatus) -> String? {
        switch onEnum(of: s) {
        case .offline(let o): return o.pending > 0 ? "Offline · \(o.pending) change(s) waiting to sync" : nil
        case .failing(let f): return f.reason
        default: return nil
        }
    }
}
