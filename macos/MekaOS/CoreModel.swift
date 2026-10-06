import Foundation
@preconcurrency import MekaKit
import Observation
import SwiftUI
import AppKit

/// Bridges the Kotlin `MekaCore` facade into SwiftUI. Kotlin owns all state and rules; this only mirrors flows.
@MainActor
@Observable
final class CoreModel {
    private(set) var today: Today?
    private(set) var syncLine: String?
    private(set) var conflicts: [ConflictChoice] = []
    /// Work mode (schedule + manual switch), synced with the Fold. The held messages live on the Fold only.
    private(set) var work: WorkModeState?
    /// Waiting for, Someday and Decisions, with what is due to chase or review today. Synced with the Fold.
    private(set) var lists: ListsView?
    var showWork = false
    var selectedID: String?
    /// The shell's current destination and which way the last switch moved (for the push transition).
    private(set) var destination: ShellDestination = .today
    private(set) var lastDirection = 0
    var focusCapture = false
    var lastError: String?
    private(set) var isConnected = false
    var showConnect = false
    var showCalendars = false
    var showPlan = false
    private(set) var plan: DayPlanner.Plan?
    /// Tasks Plan Apply just sent into Today; their rows are softly lit for a moment once the sheet has gone.
    private(set) var landing: Set<String> = []
    private(set) var accounts: [ConnectedAccount]? = nil
    var calendarsMessage: String?
    private var identity: DeviceIdentity?
    /// Created lazily so a Mac that never connects never touches the Secure Enclave.
    @ObservationIgnored private lazy var deviceKey = MacDeviceKey()

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
            databaseName: "meka.db",
            deviceKey: syncURL == nil ? nil : deviceKey
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
        observers.append(Task { [weak self] in
            for await w in core.workMode { self?.work = w }
        })
        observers.append(Task { [weak self] in
            for await l in core.listsView { self?.lists = l }
        })
        // Work mode and Today move with the clock: re-evaluate every half minute.
        observers.append(Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                try? await core.tick()
            }
        })
        core.startSync(periodMs: 30_000)   // MekaCore.FOREGROUND_SYNC_MS
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
                try await MacCoreFactory.shared.connect(core: core, serverUrl: url, deviceSecret: e.deviceSecret, deviceKey: deviceKey)
                isConnected = true
                return nil
            case .rejected: return "That enrolment code wasn't accepted."
            case .failed(let f): return f.reason
            }
        } catch {
            return error.localizedDescription
        }
    }

    /// The server refused this device (revoked or key mismatch); re-enrolling with the code fixes it.
    var signedOut: Bool { syncLine?.hasPrefix("This device was signed out") == true }

    // MARK: Shell

    func go(to d: ShellDestination, reduced: Bool) {
        guard d != destination else { return }
        lastDirection = ShellNav.direction(from: destination, to: d)
        MekaHaptics.tick()
        withAnimation(MekaMotion.replan(reduced: reduced)) { destination = d }
    }

    // MARK: Day plan

    func loadPlan() async {
        guard let core else { return }
        plan = try? await core.planDay()
    }

    func applyPlan() async {
        guard let core, let plan else { return }
        try? await core.applyPlan(plan: plan)
        let placed = Set(plan.placements.map { $0.task.id })
        landing = placed
        showPlan = false
        try? await Task.sleep(for: .seconds(SharedMotion.landedSeconds))
        if landing == placed { landing = [] }
    }

    /// Selects a task from a list: the highlight glides to the row and the detail slides across.
    func select(_ id: String, reduced: Bool) {
        withAnimation(MekaMotion.expand(reduced: reduced)) { selectedID = id }
    }

    // MARK: Calendars

    func loadAccounts() async {
        guard let core else { return }
        accounts = (try? await core.connectedAccounts()) ?? []
    }

    /// Opens the provider's sign-in page in the default browser; the server finishes the connection.
    func connectCalendar(_ provider: String) async {
        guard let core else { return }
        calendarsMessage = nil
        do {
            switch onEnum(of: try await core.startConnect(provider: provider)) {
            case .openBrowser(let o):
                if let url = URL(string: o.url) { NSWorkspace.shared.open(url) }
            case .notSetUp:
                calendarsMessage = "\(Self.providerName(provider)) isn't set up on your server yet. Finish the registration steps, then try again."
            case .failed(let f):
                calendarsMessage = f.reason
            }
        } catch {
            calendarsMessage = error.localizedDescription
        }
    }

    static func providerName(_ p: String) -> String {
        switch p { case "google": "Google"; case "microsoft": "Outlook"; case "fixtures": "Fixtures"; default: p }
    }

    // MARK: Work mode

    func setWorkSwitch(_ on: Bool) { MekaHaptics.tick(); run { try await $0.setWorkSwitch(on: on) } }
    func workBackToSchedule() { run { try await $0.workBackToSchedule() } }

    /// Saves work hours. Days are ISO (1 = Monday); minutes are local minutes of the day.
    func setWorkSchedule(days: Set<Int>, startMinute: Int, endMinute: Int, enabled: Bool) {
        let list = days.sorted().map { KotlinInt(int: Int32($0)) }
        run { try await $0.setWorkSchedule(days: list, startMinute: Int32(startMinute), endMinute: Int32(endMinute), enabled: enabled) }
    }

    // MARK: Lists

    /// Due chases and decision reviews: they count in the Needs you badge.
    var listsDue: Int { Int(lists?.dueCount ?? 0) }

    func addWaiting(_ title: String, who: String?, chaseInDays: Int?) {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        let w = who?.trimmingCharacters(in: .whitespacesAndNewlines)
        run { _ = try await $0.addWaiting(title: t, who: (w?.isEmpty ?? true) ? nil : w, chaseInDays: Self.k(chaseInDays)) }
    }

    func chased(_ id: String) { run { try await $0.chased(id: id, againInDays: KotlinInt(int: ListRules.shared.DEFAULT_CHASE_DAYS)) } }
    func setChase(_ id: String, days: Int?) { run { try await $0.setChase(id: id, days: Self.k(days)) } }
    func received(_ id: String) { MekaHaptics.light(); run { try await $0.received(id: id) } }
    func deleteWaiting(_ id: String) { run { try await $0.deleteWaiting(id: id) } }

    func addSomeday(_ title: String, kind: SomedayKind) {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        run { _ = try await $0.addSomeday(title: t, kind: kind) }
    }

    func setSomedayKind(_ id: String, _ kind: SomedayKind) { run { try await $0.setSomedayKind(taskId: id, kind: kind) } }
    /// "Do it now": back into Today.
    func promote(_ id: String) { MekaHaptics.light(); run { try await $0.promoteSomeday(taskId: id) } }
    func deleteSomeday(_ id: String) { run { try await $0.delete(taskId: id) } }
    /// Moves a one-off task out of Today into Someday (from the task detail).
    func moveToSomeday(_ id: String) {
        if selectedID == id { selectedID = nil }
        run { try await $0.moveToSomeday(taskId: id, kind: .idea) }
    }

    func recordDecision(_ statement: String, why: String?, reviewInDays: Int?) {
        let s = statement.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return }
        let r = why?.trimmingCharacters(in: .whitespacesAndNewlines)
        run { _ = try await $0.recordDecision(statement: s, rationale: (r?.isEmpty ?? true) ? nil : r, reviewInDays: Self.k(reviewInDays)) }
    }

    /// "Still right" (and when to review it again), or just a new review date.
    func keepDecision(_ id: String, againInDays: Int?) { run { try await $0.keepDecision(id: id, againInDays: Self.k(againInDays)) } }
    func revisit(_ id: String) { run { try await $0.revisitDecision(id: id) } }
    func replaceDecision(_ id: String, with statement: String) {
        let s = statement.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return }
        run { _ = try await $0.replaceDecision(id: id, statement: s, rationale: nil, reviewInDays: nil) }
    }
    func deleteDecision(_ id: String) { run { try await $0.deleteDecision(id: id) } }

    private static func k(_ v: Int?) -> KotlinInt? { v.map { KotlinInt(int: Int32($0)) } }

    // MARK: Commands

    /// Capture from anywhere (menu bar, Services): typed or pasted text becomes one task, first line the title and
    /// the rest kept in the notes (the same rule as the Fold's share sheet, in the core).
    func capture(_ text: String, subject: String? = nil) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || subject != nil else { return }
        run { _ = try await $0.capture(text: text, subject: subject) }
    }

    func complete(_ id: String) {
        if selectedID == id { selectedID = nil }
        run { try await $0.complete(taskId: id) }
    }

    func rename(_ id: String, to title: String) { run { try await $0.rename(taskId: id, title: title) } }
    func delete(_ id: String) { selectedID = nil; run { try await $0.delete(taskId: id) } }
    func resolve(_ choice: ConflictChoice, with option: String) { run { try await $0.resolve(choice: choice, chosenOption: option) } }
    // MARK: Repeating tasks and routines

    /// The Repeat menu for a task: "Doesn't repeat" and the presets for its day, the current one selected.
    func repeatChoices(_ id: String) async -> [RepeatChoice] {
        guard let core else { return [] }
        return (try? await core.repeatChoices(taskId: id)) ?? []
    }

    func setRepeat(_ id: String, rule: String?) { run { try await $0.setRepeat(taskId: id, rule: rule) } }

    /// Skip this occurrence (the next one is queued for its day) or move just this one to tomorrow.
    func skip(_ id: String) {
        if selectedID == id { selectedID = nil }
        run { try await $0.skipOccurrence(taskId: id) }
    }

    func snooze(_ id: String) {
        if selectedID == id { selectedID = nil }
        run { try await $0.snooze(taskId: id, days: 1) }
    }

    func addStep(_ taskID: String, _ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        run { _ = try await $0.addStep(taskId: taskID, text: trimmed) }
    }

    func setStepDone(_ stepID: String, _ done: Bool) { MekaHaptics.light(); run { try await $0.setStepDone(stepId: stepID, done: done) } }
    func removeStep(_ stepID: String) { run { try await $0.removeStep(stepId: stepID) } }

    /// "↻ Every weekday", with "· since Mon 5 Oct" when an earlier day's occurrence is still open.
    func repeatLine(_ task: MekaTask) -> String? {
        guard let core, let meta = task.repeatMeta(todayEpochDay: core.todayEpochDay()) else { return nil }
        return "↻ " + meta
    }

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
