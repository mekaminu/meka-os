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
    /// Habits (pace, streaks) and goals (progress). Synced with the Fold.
    private(set) var goals: GoalsView?
    /// The running fast, the eating window and the last seven days. Synced with the Fold.
    private(set) var fasting: FastingView?
    /// Evening shutdown: done today, left from today, tomorrow at a glance. Synced with the Fold.
    private(set) var shutdown: ShutdownView?
    var showShutdown = false
    /// Morning brief: today at a glance, waiting on, what needs you on your lists. "Got it" syncs with the Fold.
    private(set) var brief: MorningBriefView?
    var showBrief = false
    /// Weekly review: the week looked back on, the week ahead, north-star numbers. "Done reviewing" syncs with the Fold.
    private(set) var review: WeeklyReviewView?
    /// The Calendar tab: week strips and the next 30 days grouped by day. Follows sync; moves with the clock.
    private(set) var calendar: CalendarView?
    /// The event whose detail sheet is open (calendar redesign, slice 3), from Today or the Calendar section.
    var openEvent: CalendarEvent?
    /// Quiet hours, digest times and tiers (notification governor), synced with the Fold.
    private(set) var notifySettings: NotificationSettings?
    /// "Quiet until 07:00", "Next digest 18:00 · 3 things so far".
    private(set) var notifyPreview: NotificationPreview?
    /// What this Mac posts. Off until turned on here (turning it on asks macOS for permission); not synced.
    private(set) var macAlerts: DeviceAlerts = NotifyRules.shared.deviceFromName(
        name: UserDefaults.standard.string(forKey: CoreModel.alertsKey), fallback: .off
    )
    var showNotifications = false
    /// Search everything: results for the query in the sheet (local; follows edits and sync while it is open).
    private(set) var searchResults: SearchView?
    var showSearch = false
    /// A search result Lists or Goals should open (its tab and unfolded row); cleared once shown.
    var openItem: OpenItem?
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
        observers.append(Task { [weak self] in
            for await g in core.goalsView { self?.goals = g }
        })
        observers.append(Task { [weak self] in
            for await f in core.fastingView { self?.fasting = f }
        })
        observers.append(Task { [weak self] in
            for await s in core.shutdownView { self?.shutdown = s }
        })
        observers.append(Task { [weak self] in
            for await b in core.briefView { self?.brief = b }
        })
        observers.append(Task { [weak self] in
            for await r in core.reviewView { self?.review = r }
        })
        observers.append(Task { [weak self] in
            for await c in core.calendarView { self?.calendar = c }
        })
        observers.append(Task { [weak self] in
            for await s in core.notificationSettings { self?.notifySettings = s }
        })
        observers.append(Task { [weak self] in
            for await p in core.notificationPreview { self?.notifyPreview = p }
        })
        observers.append(Task { [weak self] in
            for await v in core.searchView { self?.searchResults = v }
        })
        // Work mode and Today move with the clock: re-evaluate every half minute, then let the notification governor
        // post anything that is due on this Mac.
        observers.append(Task { [weak self] in
            await self?.governNotifications()
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                try? await core.tick()
                await self?.governNotifications()
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

    // MARK: Search

    /// Searches everything for `query` ("" clears it); results arrive on `search`.
    func search(_ query: String) { run { try await $0.search(query: query) } }

    /// Opens a search result where it lives: Lists on its tab or Goals, with its row unfolded.
    func open(_ hit: SearchHit, reduced: Bool) {
        guard let d = SearchNav.destination(hit.target) else { return }
        showSearch = false
        search("")
        openItem = OpenItem(target: hit.target, id: hit.id)
        go(to: d, reduced: reduced)
    }

    /// "Reopen" on a done task found by search.
    func reopen(_ id: String) { MekaHaptics.light(); run { try await $0.reopen(taskId: id) } }

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
        switch p { case "google": "Google"; case "microsoft": "Outlook"; case "fixtures": "Fixtures"; case "news": "Headlines"; default: p }
    }

    // MARK: Notifications

    static let alertsKey = "meka.notify.device"
    static let governorStateKey = "meka.notify.state"

    /// Runs the shared governor and posts what it says. Each notice posts once on this Mac (state kept here).
    func governNotifications() async {
        guard let core, macAlerts != .off else { return }
        let state = UserDefaults.standard.string(forKey: Self.governorStateKey)
        guard let result = try? await core.governNotifications(state: state, device: macAlerts) else { return }
        UserDefaults.standard.set(result.stateEncoded, forKey: Self.governorStateKey)
        guard await MacNotifier.allowed() else { return } // nothing reaches you, so nothing counts
        let posted = await MacNotifier.post(result)
        // Counts only (ADR-013): the weekly review's Interruptions. Called even when nothing posted, so counting starts.
        try? await core.notificationsPosted(posted: posted)
    }

    /// Everything, digests only, or off on this Mac. Turning it on asks macOS for permission once.
    func setMacAlerts(_ d: DeviceAlerts) {
        MekaHaptics.tick()
        macAlerts = d
        UserDefaults.standard.set(NotifyRules.shared.deviceName(d: d), forKey: Self.alertsKey)
        guard d != .off else { return }
        Task {
            _ = await MacNotifier.requestPermission()
            await governNotifications()
        }
    }

    func setQuietHours(enabled: Bool, start: Int, end: Int) {
        run { try await $0.setQuietHours(enabled: enabled, startMinute: Int32(start), endMinute: Int32(end)) }
    }
    func setDigest(_ minute: Int32, on: Bool) { MekaHaptics.tick(); run { try await $0.setDigest(minute: minute, on: on) } }
    func setNoticeTier(_ source: NoticeSource, _ tier: NoticeTier) { run { try await $0.setNoticeTier(source: source, tier: tier) } }

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

    // MARK: Renewals and bills radar

    /// Adds a renewal or bill. Returns what's wrong with the cost (shown under the field), or nil when it was added.
    /// The cost is checked here first: Kotlin exceptions don't cross into Swift.
    @discardableResult
    func addRenewal(_ title: String, kind: ObligationKind, dueDay: Int64, repeats: RenewalRepeat, cost: String, cancelByDaysBefore: Int?) -> String? {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return nil }
        if let problem = RenewalRules.shared.costError(text: cost) { return problem }
        let c = cost.trimmingCharacters(in: .whitespacesAndNewlines)
        run {
            _ = try await $0.addRenewal(title: t, kind: kind, dueDay: dueDay, repeats: repeats,
                                        cost: c.isEmpty ? nil : c, cancelByDaysBefore: Self.k(cancelByDaysBefore))
        }
        return nil
    }

    /// "Renewed" / "Paid": a repeating one rolls on to its next date; a one-off leaves the list.
    func renewalDone(_ id: String) { MekaHaptics.light(); run { try await $0.renewalDone(id: id) } }
    func setRenewalDue(_ id: String, day: Int64) { run { try await $0.setRenewalDue(id: id, dueDay: day) } }
    func setRenewalRepeat(_ id: String, _ r: RenewalRepeat) { run { try await $0.setRenewalRepeat(id: id, repeats: r) } }
    /// Returns what's wrong with the cost, or nil when it was saved (blank clears it).
    @discardableResult
    func setRenewalCost(_ id: String, _ cost: String) -> String? {
        if let problem = RenewalRules.shared.costError(text: cost) { return problem }
        let c = cost.trimmingCharacters(in: .whitespacesAndNewlines)
        run { try await $0.setRenewalCost(id: id, cost: c.isEmpty ? nil : c) }
        return nil
    }
    func setRenewalLead(_ id: String, days: Int) { run { try await $0.setRenewalLead(id: id, days: Int32(days)) } }
    func setRenewalCancelBy(_ id: String, daysBefore: Int?) { run { try await $0.setRenewalCancelBy(id: id, daysBefore: Self.k(daysBefore)) } }
    func setRenewalKind(_ id: String, _ kind: ObligationKind) { run { try await $0.setRenewalKind(id: id, kind: kind) } }
    func stopRenewal(_ id: String) { MekaHaptics.light(); run { try await $0.stopRenewal(id: id) } }
    func deleteRenewal(_ id: String) { run { try await $0.deleteRenewal(id: id) } }

    /// Today as a local epoch day (the core's day numbering, for due dates).
    var todayEpochDay: Int64 { core?.todayEpochDay() ?? Self.epochDay(of: Date()) }

    /// A local calendar date as an epoch day, and back (for the date picker).
    nonisolated static func epochDay(of date: Date) -> Int64 {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return CivilDate.shared.toEpochDay(year: Int32(c.year ?? 1970), month: Int32(c.month ?? 1), day: Int32(c.day ?? 1))
    }

    nonisolated static func date(ofEpochDay day: Int64) -> Date {
        let ymd = CivilDate.shared.fromEpochDay(epochDay: day)
        let comps = DateComponents(year: Int(ymd.year), month: Int(ymd.month), day: Int(ymd.day), hour: 12)
        return Calendar.current.date(from: comps) ?? Date()
    }

    // MARK: Fasting

    /// Starts a fast `minutesAgo` minutes ago (0: now) with the plan's goal.
    func startFast(minutesAgo: Int32) { MekaHaptics.light(); run { _ = try await $0.startFast(startedMinutesAgo: minutesAgo) } }
    func endFast() { MekaHaptics.light(); run { try await $0.endFast() } }
    func resumeFast(_ id: String) { run { try await $0.resumeFast(id: id) } }
    func setFastTarget(_ hours: Int32) { run { try await $0.setFastTarget(hours: hours) } }
    func moveFastStart(_ minutes: Int32) { run { try await $0.moveFastStart(deltaMinutes: minutes) } }
    func discardFast() { run { try await $0.discardFast() } }
    func chooseFastingPlan(_ index: Int) { run { try await $0.chooseFastingPlan(index: Int32(index)) } }

    // MARK: Evening shutdown

    /// Carries one item over to tomorrow (the same "Tomorrow" as in the task detail).
    func carryOver(_ id: String) {
        if selectedID == id { selectedID = nil }
        MekaHaptics.tick()
        run { try await $0.carryOver(taskId: id) }
    }

    /// "Move the rest to tomorrow".
    func carryAllToTomorrow() { MekaHaptics.tick(); run { try await $0.carryAllToTomorrow() } }

    /// Calls it a day; the card is put away on the Fold too.
    func shutDown() async {
        guard let core else { return }
        do { try await core.shutDown() } catch { lastError = error.localizedDescription }
    }

    // MARK: Morning brief

    /// "Got it": the card is put away on the Fold too, until tomorrow morning.
    func briefSeen() async {
        guard let core else { return }
        do { try await core.briefSeen() } catch { lastError = error.localizedDescription }
    }

    /// Shows or hides a news topic's headlines in the brief; synced with the Fold.
    func setNewsTopic(_ id: String, on: Bool) { MekaHaptics.tick(); run { try await $0.setNewsTopic(topicId: id, on: on) } }

    // MARK: Weekly review

    /// Shows the week `offset` weeks from this one (0 this week, -1 last week, back to -12); a tick haptic.
    func showReviewWeek(_ offset: Int) { MekaHaptics.tick(); run { try await $0.showReviewWeek(offset: Int32(offset)) } }

    /// "Done reviewing" for the week on screen; synced with the Fold (light haptic; the check pops).
    func reviewDone() { MekaHaptics.light(); run { try await $0.reviewDone() } }
    /// Today's Sunday-evening card: opens Review on the week it is about (this week on Sunday, last week on Monday).
    func openReviewCard(reduced: Bool) {
        run { try await $0.showReviewCardWeek() }
        go(to: .review, reduced: reduced)
    }

    // MARK: Goals and habits

    func addHabit(_ title: String, perWeek: Int, timing: HabitTiming) {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        run { _ = try await $0.addHabit(title: t, perWeek: Int32(perWeek), timing: timing, minutes: GoalRules.shared.DEFAULT_MINUTES, goalId: nil) }
    }

    /// Ticks or unticks a habit for today (light haptic; the circle pops).
    func setHabitDone(_ id: String, _ done: Bool) { MekaHaptics.light(); run { try await $0.setHabitDone(id: id, done: done) } }
    func setHabitTarget(_ id: String, _ perWeek: Int32) { run { try await $0.setHabitTarget(id: id, perWeek: perWeek) } }
    func setHabitTiming(_ id: String, _ timing: HabitTiming) { run { try await $0.setHabitTiming(id: id, timing: timing) } }
    func setHabitMinutes(_ id: String, _ minutes: Int32) { run { try await $0.setHabitMinutes(id: id, minutes: minutes) } }
    func setHabitGoal(_ id: String, _ goalID: String?) { run { try await $0.setHabitGoal(id: id, goalId: goalID) } }
    func deleteHabit(_ id: String) { run { try await $0.deleteHabit(id: id) } }

    func addGoal(_ title: String, target: String?, horizonIndex: Int) {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        let tg = target?.trimmingCharacters(in: .whitespacesAndNewlines)
        let h = GoalRules.shared.horizonAt(index: Int32(horizonIndex))
        run { _ = try await $0.addGoal(title: t, target: (tg?.isEmpty ?? true) ? nil : tg, horizon: h) }
    }

    func setGoalHorizon(_ id: String, index: Int) {
        let h = GoalRules.shared.horizonAt(index: Int32(index))
        run { try await $0.setGoalHorizon(id: id, horizon: h) }
    }
    func stepGoal(_ id: String, from pct: Int32, by delta: Int32) { run { try await $0.setGoalProgress(id: id, pct: pct + delta) } }
    func finishGoal(_ id: String) { MekaHaptics.light(); run { try await $0.finishGoal(id: id) } }
    func deleteGoal(_ id: String) { run { try await $0.deleteGoal(id: id) } }
    /// Links a task to a goal (nil unlinks); finishing it then counts towards the goal.
    func setTaskGoal(_ taskID: String, _ goalID: String?) { run { try await $0.setTaskGoal(taskId: taskID, goalId: goalID) } }

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

    /// Event detail: when, how soon, which calendar, place, notes and a Join link (pure, computed in the core).
    func eventDetail(_ event: CalendarEvent) -> EventDetailView? { core?.eventDetail(event: event) }

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
