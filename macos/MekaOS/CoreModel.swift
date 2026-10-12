import Foundation
@preconcurrency import MekaKit
import Observation
import SwiftUI
import AppKit
import UniformTypeIdentifiers

/// Bridges the Kotlin `MekaCore` facade into SwiftUI. Kotlin owns all state and rules; this only mirrors flows.
@MainActor
@Observable
final class CoreModel {
    private(set) var today: Today?
    private(set) var syncLine: String?
    private(set) var conflicts: [ConflictChoice] = []
    /// Work mode (schedule + manual switch), synced with the Fold.
    private(set) var work: WorkModeState?
    /// "While you were at work": what the Fold held during work mode, synced (Needs Meka #10). Done clears both.
    private(set) var afterWork: AfterWorkSummary?
    /// Spam call protection's block list (call assistant polish 8b), synced with the Fold, which does the blocking.
    private(set) var blockedCallers: BlockedCallersView?
    var showAfterWork = false
    /// Requests from people Meka watches (V1, requests slice 4): the Fold read them, the cards sync; Add · Change ·
    /// Not a task here clears them there too.
    private(set) var requests: [RequestCard] = []
    /// The approvals inbox (V1, slice 1): requests to trade, pay or share a document, first in Needs you. Approve asks
    /// for Touch ID every time; synced with the Fold.
    private(set) var approvals: [ApprovalCard] = []
    /// School rhythm (V1, slice 1): Rex's and Logan's days off, dates and weekly things (Ask → More → School), and the
    /// week-ahead cover questions Needs you shows above the requests. Synced with the Fold.
    private(set) var school: SchoolView?
    /// The open cover questions (none until the core has said).
    var schoolCovers: [SchoolCover] { school?.covers ?? [] }
    /// Date night's week-before card (slice 2), in Needs you beside the school's questions; nil when nothing to book.
    var dateNightNudge: DateNightNudge? { dateNight?.nudge }
    /// The school's questions and date night's card: the plans Needs you asks Meka to make (counts and stagger places).
    var needsYouAsks: Int { schoolCovers.count + (dateNightNudge == nil ? 0 : 1) + appointmentNudges.count }
    /// Appointments that have come due (appointment nudges, slice 1): one card each in Needs you, after date night's.
    var appointmentNudges: [AppointmentNudge] { appointments?.nudges ?? [] }
    /// Ask → More → School.
    var showSchool = false
    /// Meal plan → shopping (V1, slice 1): favourite dinners, the week's dinners and what "Add to shopping" would add
    /// (Ask → More → Dinners). Synced with the Fold.
    private(set) var meals: MealPlanView?
    /// Ask → More → Dinners.
    var showMeals = false
    /// Date night (V1, slice 1): the fortnightly evening the planner and Gym bookings keep clear, and its next nights
    /// (Ask → More → Date night). Synced with the Fold.
    private(set) var dateNight: DateNightView?
    /// Ask → More → Date night.
    var showDateNight = false
    /// Life admin hour (V1, slice 1): the weekly hour MEKA books and the admin jobs gathered into it
    /// (Ask → More → Life admin hour). Synced with the Fold.
    private(set) var lifeAdmin: LifeAdminView?
    /// Ask → More → Life admin hour.
    var showLifeAdmin = false
    /// The line after Skip this week / Keep it, until the sheet closes.
    private(set) var lifeAdminSaid: String?
    /// Protected time (V1, slice 1): the evenings and weekends Plan my day and Move to later keep tasks out of
    /// (Ask → More → Protected time). Synced with the Fold.
    private(set) var protectedTime: ProtectedView?
    /// "Leaving now" (V1): the draft to Jeanette when an office day's work ends; set here or on the Fold.
    private(set) var leaving: LeavingView?
    /// Ask → More → Protected time.
    var showProtected = false
    /// What MEKA may do (policy engine UI, slice 1): the pause switch, earned trust per kind and the locked rows
    /// (Ask → More → What MEKA may do). Synced with the Fold.
    private(set) var autonomy: AutonomyView?
    /// Ask → More → What MEKA may do.
    var showAutonomy = false
    /// The answer to the sheet's last tap ("Paused · MEKA asks before everything"), shown under its summary.
    private(set) var autonomySaid: String?
    /// The emergency card (V1, slice 1): who to call, where things are and key accounts by name (Ask → More →
    /// Emergency card). Synced with the Fold; Jeanette reads it on her family page.
    private(set) var emergency: EmergencyView?
    /// Ask → More → Emergency card.
    var showEmergency = false
    /// What MEKA knows (facts page, slice 1): the facts Ask and Talk answer from, in groups (Ask → More → What MEKA
    /// knows). Synced with the Fold.
    private(set) var facts: FactsView?
    /// Ask → More → What MEKA knows.
    var showFacts = false
    /// What MEKA knows, slice 4: a form shared with "Handle this with MEKA" answered from the facts page (worked out
    /// in the core on this Mac; nothing sent). Shown in the Form answers sheet.
    private(set) var formAnswers: FormAnswersView?
    var showFormAnswers = false
    /// The train queue (V1, train-time queue slice 1): links and podcasts saved for the commute, waiting and done
    /// lately (Ask → More → Train queue). Synced with the Fold, where the share sheet saves them.
    private(set) var queue: QueueView?
    /// Ask → More → Train queue (also Today's "For the train" line).
    var showTrainQueue = false
    /// The Sunday week ahead (V1, slice 1): the coming week day by day and the family sync (Ask → More → Week ahead,
    /// and Today's "Your week ahead · …" line on Sunday from 18:00). Read-only.
    private(set) var weekAhead: WeekAheadView?
    var showWeekAhead = false
    /// Slice 2: Sunday's heads-up on or off and its time (synced with the Fold).
    private(set) var weekAheadSetting: WeekAheadSettingView?
    /// Listen on Sunday's heads-up: the week ahead sheet reads itself aloud once open; the sheet clears it.
    var readWeekAheadOnOpen = false
    /// Errands by location (V1, slice 1): what waits at each place, and what was ticked off lately (Ask → More →
    /// Errands). Synced with the Fold, which (slice 2) reminds Meka when he's near a place.
    private(set) var errands: ErrandView?
    /// Ask → More → Errands.
    var showErrands = false
    /// Appointment nudges (V1, slice 1): the dentist, the optician, a haircut… due from the last visit, and the cards
    /// Needs you shows for the ones that have come due (Ask → More → Appointments).
    private(set) var appointments: AppointmentView?
    /// Ask → More → Appointments.
    var showAppointments = false
    /// Bin days (V1, slice 1): each bin with its day and pattern, the next collection (Ask → More → Bins).
    private(set) var bins: BinsView?
    /// Car on autopilot (V1, slice 1): the registration and the car's four dates against the radar (Lists → Renewals → Car).
    private(set) var car: CarView?
    /// Ask → More → Bins.
    var showBins = false
    /// Ask's Life grid (Fold review 2026-10-12, item 7): each tile's live line ("Chilli tonight", "Next Fri 23 Oct").
    private(set) var lifeLines: LifeLines?
    /// Gift planner (V1, slice 1): birthdays and anniversaries, their ideas and order-by days (Ask → More → Gifts).
    private(set) var gifts: GiftsView?
    /// Ask → More → Gifts.
    var showGifts = false
    /// People (V1, slice 2): one row per person in a promise either way or a birthday (Ask → More → People).
    private(set) var people: PeopleView?
    /// Ask → More → People.
    var showPeople = false
    /// Travel mode (V1, slice 1): trips and their bookings as day-by-day timelines (Ask → More → Trips).
    private(set) var trips: TripsView?
    /// Ask → More → Trips.
    var showTrips = false
    /// The Vault (V2, slice 1): the documents and when they run out.
    private(set) var documents: DocumentsView?
    /// Why a Booked… on a Needs you card couldn't be read, by appointment id (shown under its field).
    private(set) var appointmentRefusal: (id: String, line: String)?
    /// Annual leave (V1, slice 1): the allowance, the days booked and the school holidays (Ask → More → Annual leave).
    /// Synced with the Fold; booked days are days off for work mode on both apps.
    private(set) var leave: LeaveView?
    /// Ask → More → Annual leave.
    var showLeave = false
    /// Work wins (V1, slice 1): the reviewed week's wins and this quarter's, shown in Review. Synced with the Fold.
    private(set) var wins: WinsView?
    /// The line after a skip or keep ("Skipped Fri 23 Oct · the evening is free to plan"), until the sheet closes.
    private(set) var dateNightSaid: String?
    /// What the last "Add to shopping" did, kept here so only its line and whether it can be undone reach the sheet.
    private var lastMealsShopped: MealsShopped?
    /// The line under "Add to shopping" after a tap ("Added 9 to shopping"), until the sheet closes or Undo.
    private(set) var mealsShoppedLine: String?
    private(set) var mealsShoppedCanUndo = false
    /// Messages the Fold triaged (V1, messages slice 3): Needs a reply (with MEKA's draft) and FYI, synced. The Mac
    /// never sends: Copy reply puts the draft on the pasteboard; Not now / Seen clear the card on both devices.
    private(set) var triage: [TriageCard] = []
    /// The same cards, one per conversation (messages grouped per chat, slice 1).
    /// A person's requests ride inside their chat's card (messages grouped per chat, slice 3).
    /// A group's digest chatter rides in its chat's card, shown once (slice 3b).
    var triageChats: [TriageChat] {
        // A busy chat's one-line summary rides on its card (slice 3c), made on the Fold and synced.
        ChatSummaryRules.shared.withSummaries(
            chats: TriageChatRules.shared.withGists(chats: TriageChatRules.shared.chatsWith(cards: triage, requests: requests), gists: groupGists),
            summaries: chatSummaries
        )
    }
    /// Busy chats' one-line summaries (messages grouped per chat, slice 3c): made on the Fold, never the messages.
    private(set) var chatSummaries: [ChatSummary] = []
    /// The digest's cards no chat carries: GroupGistsView shows only these (slice 3b).
    var digestGists: [GroupGist] { TriageChatRules.shared.gistsOutside(chats: triageChats, gists: groupGists) }
    /// The requests no chat carries: Needs you shows these as cards of their own.
    var looseRequests: [RequestCard] { TriageChatRules.shared.looseRequests(chats: triageChats, requests: requests) }
    /// The group digest (V1, messages slice 4b): one card per busy group at the latest digest time, made on the Fold
    /// (its name, how many messages, who wrote, MEKA's gist; never the messages). Caught up here clears it there too.
    private(set) var groupGists: [GroupGist] = []
    /// Waiting for, Someday and Decisions, with what is due to chase or review today. Synced with the Fold.
    private(set) var lists: ListsView?
    /// Needs you as a stack of decisions (four tabs, slice 2); see `needsYouCards`.
    private(set) var needsYouStack: NeedsYouStack?
    /// Cards set aside with "Later" on this Mac (nothing is written): they go to the back of the stack.
    private(set) var needsYouSetAside: [String] = []
    /// Habits (pace, streaks) and goals (progress). Synced with the Fold.
    private(set) var goals: GoalsView?
    /// The Gym (booked habits): the week's sessions booked around the calendar and work, and today's card.
    private(set) var sessions: SessionsView?
    /// The running fast, the eating window and the last seven days. Synced with the Fold.
    private(set) var fasting: FastingView?
    /// Evening shutdown: done today, left from today, tomorrow at a glance. Synced with the Fold.
    private(set) var shutdown: ShutdownView?
    var showShutdown = false
    /// The smart wake alarm (Alarms, slice 1): the next morning's suggested time and the alarm if set. Synced with the Fold.
    private(set) var wake: WakeView?
    /// Quick alarms and timers typed into capture (Alarms, slice 2), soonest first. Synced with the Fold.
    private(set) var quickAlarms: [QuickAlarmItem] = []
    /// Morning brief: today at a glance, waiting on, what needs you on your lists. "Got it" syncs with the Fold.
    private(set) var brief: MorningBriefView?
    /// Weather for home (weather item, slice 1): Today's quiet line under the date ("14° · light rain from 16:00").
    private(set) var weather: WeatherView?
    /// Calendar sign-ins about to end or expired (Reliability first, item 2): Today's line with Reconnect; empty when fine.
    private(set) var signInLines: [SignInLine] = []
    var showBrief = false
    /// Spoken morning brief: the brief sheet reads itself aloud once it has the day (the Listen to the Brief command,
    /// and the wake alarm's Dismiss, as on the Fold); the sheet clears it when it starts reading.
    var readBriefOnOpen = false
    /// Appearance → Play the opening: Today replays its opening (Day ring, tiles, greeting, stagger), then clears it.
    var openingRequested = false
    /// News (Ask → More → News): the chosen topics as lanes, Barça first, then AI. Topics sync with the Fold.
    private(set) var newsPlace: NewsPlace?
    var showNews = false
    /// A story to open the News sheet on (from the command centre's News); taken once by the sheet.
    var newsStoryId: String?
    /// Weekly review: the week looked back on, the week ahead, north-star numbers. "Done reviewing" syncs with the Fold.
    private(set) var review: WeeklyReviewView?
    /// The Calendar tab: week strips and the next 30 days grouped by day. Follows sync; moves with the clock.
    private(set) var calendar: CalendarView?
    /// The event whose detail sheet is open (calendar redesign, slice 3), from Today or the Calendar section.
    var openEvent: CalendarEvent?
    /// Hidden events and prep tasks (calendar actions), synced with the Fold. MEKA-only: the real calendars are untouched.
    private(set) var eventMarks: EventMarks?
    /// Calendars' "On Today" switches (all-day polish): every calendar in the mirror and whether it shows on Today.
    private(set) var calendarsOnToday: [CalendarChoice] = []
    /// The calendar-action undo bar ("Hidden from your day · Undo"); goes after 5 seconds.
    private(set) var eventUndo: EventUndoOffer?
    /// Calendar editing (slice 2b): the accounts MEKA may add events to, as the server last listed them.
    private(set) var editAccounts: [EditAccount] = []
    /// Edits on their way to Google/Outlook, needing Meka, or just sent ("Added “Dentist” to Google"), newest first.
    private(set) var editLines: [EditLine] = []
    /// The day the Add event sheet is open on (nil: closed).
    var addEventDay: Int64?
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
    /// What the command bar's "Search for …" hands to the search sheet; taken (and cleared) when the sheet opens.
    var searchSeed: String?
    /// A task match clicked in Ask's field: the search sheet opens with its detail chosen beside the results.
    var searchChosenSeed: String?
    /// The ⌘K command bar over MEKA's window (Outside the app).
    var showCommandBar = false
    /// A search result Lists or Goals should open (its tab and unfolded row); cleared once shown.
    var openItem: OpenItem?
    var showWork = false
    /// Self-updating phone app: the APK chosen to publish, what it is (or why it can't go), and how publishing went.
    var showFoldUpdate = false
    private(set) var foldUpdatePath: String?
    private(set) var foldUpdateCheck: FoldUpdateCheck?
    private(set) var publishingFoldUpdate = false
    private(set) var foldUpdateOutcome: String?
    private(set) var foldUpdatePublished = false
    /// Export and backup: what an export would hold, and how the last one went.
    var showYourData = false
    /// What MEKA did and why (V1 activity log), synced with the Fold; and what the last undo said when it wasn't
    /// simply "Undone" (the row itself shows that).
    private(set) var activity: ActivityView?
    private(set) var activityNote: String?
    var showActivity = false
    /// Ask → More → MEKA's voice.
    var showVoice = false
    /// Ask → More → Talk (Talk without tapping the mic): how to start talking on the Mac.
    var showTalk = false
    /// Ask → More → Health (Reliability first, item 3), also from Today's health line.
    var showHealth = false
    /// The Health screen as last checked; Today shows its `todayLine`. Nil until checked once.
    private(set) var health: HealthView?
    private(set) var checkingHealth = false
    /// Ask → More → Setup (Setup checklist), also from Today's setup card.
    var showSetup = false
    /// The Setup checklist as last checked; Today's card reads its `todayLine`. Nil until checked once.
    private(set) var setup: SetupView?
    private(set) var checkingSetup = false
    private(set) var exportSummary: ExportSummary?
    /// Your data's line on how MEKA's database on this Mac is protected (encrypted, or FileVault only).
    private(set) var databaseLine: String?
    private(set) var exporting = false
    private(set) var exportOutcome: String?
    private(set) var exportSaved = false
    var selectedID: String?
    /// "Break it down" from Up next (Fold review 2026-10-12 17:56, item 5): the task whose checklist field takes the
    /// focus when its detail shows; the field clears it as it takes it.
    var breakDownTaskID: String?
    /// The global frame of the row a task was just opened from, until the detail grows out of it.
    var selectionOrigin: CGRect?
    /// The shell's current destination and which way the last switch moved (for the push transition).
    private(set) var destination: ShellDestination = .today
    private(set) var lastDirection = 0
    var focusCapture = false
    var lastError: String?
    private(set) var isConnected = false
    var showConnect = false
    var showCalendars = false
    /// Ask → More → Family (family sharing slice 4): Jeanette's link to the shopping list.
    var showFamily = false
    /// Who can use the shopping list and each link's state, as the server last listed them; nil until read once.
    private(set) var family: FamilyView?
    /// A link is being made or turned off.
    private(set) var familyBusy = false
    /// Ask → More → Watch (Galaxy Watch slice 1): link a watch with the code it shows.
    var showWatch = false
    /// The linked watches as the server last listed them; nil until read once.
    private(set) var watchLink: WatchLinkView?
    /// A watch is being linked or unlinked.
    private(set) var watchLinkBusy = false
    /// Ask → More → Settings → Recovery key (Recovery key, slice 3): the household's key and making a new one.
    var showRecovery = false
    /// The Recovery key sheet's status as the server last said; nil until asked once.
    private(set) var recoveryKey: RecoveryKeyView?
    /// A key is being saved.
    private(set) var recoveryBusy = false
    var showPlan = false
    private(set) var plan: DayPlanner.Plan?
    /// Plan my day's "Also add the blocks to Google" (calendar editing slice 2e); nil until read.
    private(set) var planCalendar: PlanCalendarSetting?
    /// Tasks Plan Apply just sent into Today; their rows are softly lit for a moment once the sheet has gone.
    private(set) var landing: Set<String> = []
    private(set) var accounts: [ConnectedAccount]? = nil
    var calendarsMessage: String?
    /// A quiet line under the accounts (calendar editing: what Allow editing will ask, or that editing is off now).
    var calendarsNote: String?
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
        let database = Self.prepareDatabase()
        databaseLine = DatabaseProtectionRules.shared.macLine(protection: database.protection)
        if let note = database.note { lastError = note }
        let core = MacCoreFactory.shared.create(
            householdId: identity.householdID,
            deviceId: identity.deviceID,
            syncUrl: syncURL,
            deviceSecret: identity.deviceSecret,
            databaseKeyHex: database.keyHex,   // slice 2b: SQLCipher with a sealed key (ADR-002)
            encrypted: database.encrypted,
            databaseDirectory: Self.databaseDirectory(),
            databaseName: "meka.db",
            deviceKey: syncURL == nil ? nil : deviceKey
        )
        self.core = core
        // Trading approvals, slice 2: this Mac's Touch ID-only approval key is known (synced) before the first yes, so
        // Kestrel can pin it. Nothing without Touch ID and a Secure Enclave.
        if MacBiometric.available, let approvalKey = MacApprovalKey.publicKey() {
            run { _ = try await $0.registerApprovalKey(publicKeyBase64: approvalKey, label: MacApprovalKey.label) }
        }
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
            for await s in core.afterWork { self?.afterWork = s }
        })
        observers.append(Task { [weak self] in
            for await b in core.blockedCallers { self?.blockedCallers = b }
        })
        observers.append(Task { [weak self] in
            for await r in core.requests { self?.requests = r }
        })
        observers.append(Task { [weak self] in
            for await a in core.approvals { self?.approvals = a }
        })
        observers.append(Task { [weak self] in
            for await s in core.schoolView { self?.school = s }
        })
        observers.append(Task { [weak self] in
            for await b in core.binsView { self?.bins = b }
        })
        observers.append(Task { [weak self] in
            for await l in core.lifeLines { self?.lifeLines = l }
        })
        observers.append(Task { [weak self] in
            for await g in core.giftsView { self?.gifts = g }
        })
        observers.append(Task { [weak self] in
            for await p in core.peopleView { self?.people = p }
        })
        observers.append(Task { [weak self] in
            for await t in core.tripsView { self?.trips = t }
        })
        observers.append(Task { [weak self] in
            for await d in core.documentsView { self?.documents = d }
        })
        observers.append(Task { [weak self] in
            for await c in core.carView { self?.car = c }
        })
        observers.append(Task { [weak self] in
            for await m in core.mealsView { self?.meals = m }
        })
        observers.append(Task { [weak self] in
            for await d in core.dateNightView { self?.dateNight = d }
        })
        observers.append(Task { [weak self] in
            for await a in core.lifeAdminView { self?.lifeAdmin = a }
        })
        observers.append(Task { [weak self] in
            for await p in core.protectedView { self?.protectedTime = p }
        })
        observers.append(Task { [weak self] in
            for await l in core.leavingView { self?.leaving = l }
        })
        observers.append(Task { [weak self] in
            for await w in core.weekAheadView { self?.weekAhead = w }
        })
        observers.append(Task { [weak self] in
            for await s in core.weekAheadSettingView { self?.weekAheadSetting = s }
        })
        observers.append(Task { [weak self] in
            for await a in core.autonomyView { self?.autonomy = a }
        })
        observers.append(Task { [weak self] in
            for await e in core.emergencyView { self?.emergency = e }
        })
        observers.append(Task { [weak self] in
            for await f in core.factsView { self?.facts = f }
        })
        observers.append(Task { [weak self] in
            for await q in core.queueView { self?.queue = q }
        })
        observers.append(Task { [weak self] in
            for await e in core.errandsView { self?.errands = e }
        })
        observers.append(Task { [weak self] in
            for await a in core.appointmentsView { self?.appointments = a }
        })
        observers.append(Task { [weak self] in
            for await l in core.leaveView { self?.leave = l }
        })
        observers.append(Task { [weak self] in
            for await w in core.winsView { self?.wins = w }
        })
        observers.append(Task { [weak self] in
            for await t in core.triage { self?.triage = t }
        })
        observers.append(Task { [weak self] in
            for await g in core.groupGists { self?.groupGists = g }
        })
        observers.append(Task { [weak self] in
            for await c in core.chatSummaries { self?.chatSummaries = c }
        })
        observers.append(Task { [weak self] in
            for await l in core.listsView { self?.lists = l }
        })
        observers.append(Task { [weak self] in
            for await n in core.needsYouStack { self?.needsYouStack = n }
        })
        observers.append(Task { [weak self] in
            for await g in core.goalsView { self?.goals = g }
        })
        observers.append(Task { [weak self] in
            for await f in core.fastingView { self?.fasting = f }
        })
        observers.append(Task { [weak self] in
            for await s in core.sessionsView { self?.sessions = s }
        })
        observers.append(Task { [weak self] in
            for await s in core.shutdownView { self?.shutdown = s }
        })
        observers.append(Task { [weak self] in
            for await w in core.wakeView { self?.wake = w }
        })
        observers.append(Task { [weak self] in
            for await q in core.quickAlarms { self?.quickAlarms = q }
        })
        // The next alarm as a scheduled notification (the Fold rings it full screen).
        observers.append(Task {
            for await a in core.nextAlarm { await MacAlarm.schedule(a) }
        })
        observers.append(Task { [weak self] in
            for await b in core.briefView { self?.brief = b }
        })
        observers.append(Task { [weak self] in
            for await w in core.weatherView { self?.weather = w }
        })
        observers.append(Task { [weak self] in
            for await l in core.signInLine { self?.signInLines = l }
        })
        observers.append(Task { [weak self] in
            for await h in core.healthView { self?.health = h }
        })
        observers.append(Task { [weak self] in
            for await f in core.familyView { self?.family = f }
        })
        observers.append(Task { [weak self] in
            for await w in core.watchLinkView { self?.watchLink = w }
        })
        observers.append(Task { [weak self] in
            for await r in core.recoveryKeyView { self?.recoveryKey = r }
        })
        observers.append(Task { [weak self] in
            for await v in core.setupView { self?.setup = v }
        })
        observers.append(Task { [weak self] in
            for await n in core.newsPlace { self?.newsPlace = n }
        })
        observers.append(Task { [weak self] in
            for await r in core.reviewView { self?.review = r }
        })
        observers.append(Task { [weak self] in
            for await c in core.calendarView { self?.calendar = c }
        })
        observers.append(Task { [weak self] in
            for await m in core.eventMarks { self?.eventMarks = m }
        })
        observers.append(Task { [weak self] in
            for await c in core.calendarsOnToday { self?.calendarsOnToday = c }
        })
        observers.append(Task { [weak self] in
            for await a in core.calendarEditAccounts { self?.editAccounts = a }
        })
        observers.append(Task { [weak self] in
            for await l in core.calendarEditLines { self?.editLines = l }
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
        observers.append(Task { [weak self] in
            for await v in core.activityView { self?.activity = v }
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

    /// Recovery key, slice 3: this Mac joins the household with the 24 words (both devices lost) instead of the code.
    /// Only Strings cross to the core; the words are read there and only their secret is sent. Returns a user-facing
    /// error, or nil once connected (the data comes back with the first sync; the key is then used up).
    func recover(serverURL: String, words: String) async -> String? {
        guard let core, let identity else { return "Not ready yet" }
        let url = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let outcome = try await MacCoreFactory.shared.recover(
                serverUrl: url, words: words, householdId: identity.householdID, deviceId: identity.deviceID,
                deviceName: Host.current().localizedName ?? "Mac", deviceKey: deviceKey
            )
            // Plain Strings from here on.
            let secret = outcome.secret, problem = outcome.problem
            guard let secret else { return problem ?? "Couldn't connect" }
            DeviceIdentity.saveEnrolment(serverURL: url, secret: secret)
            try await MacCoreFactory.shared.connect(core: core, serverUrl: url, deviceSecret: secret, deviceKey: deviceKey)
            isConnected = true
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    // MARK: Fold update (self-updating phone app)

    /// Opens the publish sheet for the APK at [path] (from File → Publish Fold Update… or tools/publish-fold.sh).
    func prepareFoldUpdate(path: String?) {
        foldUpdatePath = path
        foldUpdateCheck = path.map { MacCoreFactory.shared.checkFoldUpdate(apkPath: $0) }
        foldUpdateOutcome = nil
        foldUpdatePublished = false
        showFoldUpdate = true
    }

    /// mekaos://publish-fold-update?apk=/path/to/app-debug.apk
    func handle(url: URL) {
        guard url.scheme == "mekaos" else { return }
        // The desktop News widget (news ticker slice 3b): mekaos://news?story=<id> opens News on that story.
        if url.host == "news" {
            newsStoryId = DeskNewsLink.storyId(url)
            showNews = true
            return
        }
        guard url.host == "publish-fold-update" else { return }
        let apk = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "apk" }?.value
        prepareFoldUpdate(path: apk)
    }

    func publishFoldUpdate(reduced: Bool) async {
        guard let path = foldUpdatePath, !publishingFoldUpdate else { return }
        guard let core else { foldUpdateOutcome = "MEKA is still starting. Try again in a moment."; return }
        publishingFoldUpdate = true
        let message: String
        do {
            message = try await MacCoreFactory.shared.publishFoldUpdate(core: core, apkPath: path)
        } catch {
            message = "Couldn't publish: \(error.localizedDescription)"
        }
        publishingFoldUpdate = false
        let published = message.hasPrefix("Published")
        if published { MekaHaptics.light() }
        withAnimation(reduced ? MekaMotion.appear(reduced: true) : .spring(response: 0.35, dampingFraction: 0.6)) {
            foldUpdatePublished = published
            foldUpdateOutcome = message
        }
    }

    /// The server refused this device (revoked or key mismatch); re-enrolling with the code fixes it.
    var signedOut: Bool { syncLine?.hasPrefix("This device was signed out") == true }

    // MARK: Export (your data)

    /// "312 items" · "214 tasks · 48 calendar events · …", for the export section.
    func loadExportSummary() async {
        guard let core else { return }
        do { exportSummary = try await core.exportSummary() } catch { lastError = error.localizedDescription }
    }

    /// Builds the export, asks where to save it (save panel) and writes it there. Nothing is sent anywhere.
    func exportEverything(reduced: Bool) async {
        guard let core, !exporting else { return }
        exporting = true
        exportOutcome = nil
        exportSaved = false
        let file: DataExportFile
        do {
            file = try await core.exportAll()
        } catch {
            exporting = false
            exportOutcome = "Couldn't put the export together: \(error.localizedDescription)"
            return
        }
        // Plain strings from here on: the Kotlin object stays on the main actor.
        let json = file.json
        let totalLine = file.summary.totalLine
        exportSummary = file.summary
        let panel = NSSavePanel()
        panel.nameFieldStringValue = file.fileName
        panel.allowedContentTypes = [.json]
        panel.canCreateDirectories = true
        panel.message = "Save everything in MEKA as one JSON file. It isn't encrypted."
        guard panel.runModal() == .OK, let url = panel.url else { exporting = false; return }
        do {
            try json.write(to: url, atomically: true, encoding: .utf8)
            MekaHaptics.light()
            withAnimation(reduced ? MekaMotion.appear(reduced: true) : .spring(response: 0.35, dampingFraction: 0.6)) {
                exportSaved = true
                exportOutcome = "Saved \(url.lastPathComponent) · \(totalLine)"
            }
        } catch {
            exportOutcome = "Couldn't save there: \(error.localizedDescription)"
        }
        exporting = false
    }

    // MARK: Shell

    func go(to d: ShellDestination, reduced: Bool) {
        guard d != destination else { return }
        lastDirection = ShellNav.direction(from: destination, to: d)
        MekaHaptics.tick()
        withAnimation(MekaMotion.replan(reduced: reduced)) { destination = d }
    }

    /// Appearance → Play the opening: back to Today, which replays its opening.
    func playOpening(reduced: Bool) {
        openingRequested = true
        go(to: .today, reduced: reduced)
    }

    // MARK: Command bar

    /// What the command bar needs to offer the right half of each pair.
    func commandBarContext(appearance: String) -> CommandBarContext {
        CommandBarContext(
            atWork: work?.atWork ?? false,
            fasting: fasting?.current != nil,
            fastGoalHours: fasting?.plan.targetHours ?? 16,
            connected: isConnected,
            appearance: appearance
        )
    }

    /// Today → Listen to the Brief (⇧⌘B; the Mac's counterpart of the Fold's brief widget): Today comes forward and
    /// the brief sheet opens reading itself aloud. With the sheet already open, it starts reading there.
    func listenToBrief(reduced: Bool) {
        go(to: .today, reduced: reduced)
        readBriefOnOpen = true
        showBrief = true
    }

    /// "Plan my day" said to Talk (morning brief, slice 5): Today comes forward and the planner opens over it, as the
    /// command bar's Plan my day does (nothing is planned until Apply).
    func openPlanFromTalk() {
        go(to: .today, reduced: MotionSetting.reduced)
        showBrief = false
        showPlan = true
    }

    /// Runs a command-bar row by doing what the same button elsewhere on the Mac does (appearance is the view's).
    func perform(_ row: CommandBarRow, reduced: Bool) {
        if let d = CommandBarNav.destination(row.action) {
            go(to: d, reduced: reduced)
            return
        }
        switch row.action {
        case .planDay: go(to: .today, reduced: reduced); showPlan = true
        case .morningBrief: go(to: .today, reduced: reduced); showBrief = true
        case .shutDown: go(to: .today, reduced: reduced); showShutdown = true
        case .syncNow: Task { await syncNow() }
        case .workStart: setWorkSwitch(true)
        case .workFinish: setWorkSwitch(false)
        case .fastStart: startFast(minutesAgo: 0)
        case .fastLonger: if let h = row.hours { startExtendedFast(hours: h.int32Value) }
        case .fastEnd: endFast()
        case .workMode: showWork = true
        case .notifications: showNotifications = true
        case .activity: showActivity = true
        case .yourData: showYourData = true
        case .calendars: showCalendars = true
        case .searchFor: searchSeed = row.text; showSearch = true
        case .addTask: if let text = row.text { MekaHaptics.light(); captureTyped(text) }
        default: break
        }
    }

    // MARK: Search

    /// Searches everything for `query` ("" clears it); results arrive on `search`.
    func search(_ query: String) { run { try await $0.search(query: query) } }

    /// Opens a search result where it lives: Lists on its tab or Goals, with its row unfolded.
    func open(_ hit: SearchHit, reduced: Bool) {
        guard let d = SearchNav.destination(hit.target) else { return }
        showSearch = false
        search("")
        if hit.target == .errands || hit.target == .appointments {
            // Errands and Appointments are sheets: they rise once the search sheet has gone (appointments: slice 2a).
            let errands = hit.target == .errands
            go(to: d, reduced: reduced)
            Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(reduced ? 50 : 350))
                if errands { self?.showErrands = true } else { self?.showAppointments = true }
            }
            return
        }
        openItem = OpenItem(target: hit.target, id: hit.id)
        go(to: d, reduced: reduced)
    }

    /// "Reopen" on a done task found by search.
    func reopen(_ id: String) { MekaHaptics.light(); run { try await $0.reopen(taskId: id) } }

    // MARK: Day plan

    func loadPlan() async {
        guard let core else { return }
        plan = try? await core.planDay()
        _ = try? await core.refreshCalendarAccounts()
        planCalendar = try? await core.planCalendarSetting()
    }

    /// "Also add the blocks to Google": synced, off by default.
    func setPlanToCalendar(_ on: Bool) {
        guard let core else { return }
        MekaHaptics.tick()
        Task { planCalendar = try? await core.setPlanToCalendar(on: on) }
    }

    func applyPlan() async {
        guard let core, let plan else { return }
        let placed = Set(plan.placements.map { $0.task.id })
        // The plan is handed to the core once; only the result's ids and line come back.
        if let applied = try? await core.applyPlan(plan: plan), let line = applied.line {
            offerEventUndo(line, .planBlocks(applied.editIds))
        }
        landing = placed
        showPlan = false
        try? await Task.sleep(for: .seconds(SharedMotion.landedSeconds))
        if landing == placed { landing = [] }
    }

    /// Selects a task from a list: the highlight glides to the row and the detail slides across, or, given the
    /// tapped row's global frame (`origin`), grows out of it (container transform; the detail takes it once).
    func select(_ id: String, reduced: Bool, origin: CGRect? = nil) {
        if id != selectedID { selectionOrigin = reduced ? nil : origin }
        withAnimation(MekaMotion.expand(reduced: reduced)) { selectedID = id }
    }

    // MARK: Calendars

    func loadAccounts() async {
        guard let core else { return }
        accounts = (try? await core.connectedAccounts()) ?? []
    }

    /// Opens the provider's sign-in page in the default browser; the server finishes the connection.
    /// `editing`: Allow editing (calendar editing) asks the provider for the write permission too.
    func connectCalendar(_ provider: String, editing: Bool = false) async {
        guard let core else { return }
        calendarsMessage = nil
        calendarsNote = editing ? CalendarAccessRules.shared.allowNote(provider: provider) : nil
        do {
            switch onEnum(of: try await core.startConnect(provider: provider, editing: editing)) {
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

    /// Stop editing on one account: the server only reads it from now on.
    func stopCalendarEditing(provider: String, email: String) async {
        guard let core else { return }
        MekaHaptics.tick()
        calendarsMessage = nil
        if let now = try? await core.stopCalendarEditing(provider: provider, email: email) {
            accounts = now
            calendarsNote = CalendarAccessRules.shared.stoppedLine(provider: provider)
        } else {
            calendarsMessage = "Couldn't reach your server. Editing is still on; try again."
        }
    }

    // MARK: Calendar editing: Add event (slice 2b)

    /// Reads which accounts allow editing (the Calendar section asks when it appears).
    func refreshEditAccounts() async {
        guard let core else { return }
        _ = try? await core.refreshCalendarAccounts()
    }

    /// A fresh Add event form on a local epoch day (negative: today).
    func addEventForm(day: Int64) async -> AddEventForm? {
        guard let core else { return nil }
        return try? await core.addEventForm(day: day)
    }

    /// The sheet as shown for a form (pure, in the core).
    func addEventView(_ form: AddEventForm) -> AddEventView? { core?.addEventView(form: form) }

    /// Add: the event becomes a synced edit that waits five seconds for Undo (the undo bar), then the server adds it.
    /// The form is handed to the core once. Returns why it couldn't be added, or nil once it's on its way.
    func addEvent(_ form: AddEventForm) async -> String? {
        guard let core else { return "Connect this Mac to your server first." }
        MekaHaptics.light()
        do {
            switch onEnum(of: try await core.addEvent(form: form)) {
            case .made(let m):
                let id = m.id
                offerEventUndo(core.eventEditLine(id: id) ?? "Adding it to your calendar", .eventEdit(id))
                return nil
            case .refused(let r):
                return r.reason
            }
        } catch {
            return error.localizedDescription
        }
    }

    // MARK: Calendar editing: change, move and delete from the event detail (slice 2c)

    /// The Edit form filled in from the event (its own account; the time as it is). Pure, in the core.
    func editEventForm(_ event: CalendarEvent) -> AddEventForm? { core?.editEventForm(event: event) }

    /// The Edit sheet as shown for a form (pure): the Add sheet's rows, "Save to Google", whether Save can go.
    func editEventView(_ event: CalendarEvent, _ form: AddEventForm) -> AddEventView? { core?.editEventView(event: event, form: form) }

    /// Save: only what changed becomes a synced edit (a new time alone is a move) that waits five seconds for Undo.
    /// The event and form are handed to the core once each. Returns why it couldn't be saved, or nil once it's on its way.
    func saveEventEdit(_ event: CalendarEvent, _ form: AddEventForm) async -> String? {
        guard let core else { return "Connect this Mac to your server first." }
        MekaHaptics.light()
        do {
            switch onEnum(of: try await core.saveEventEdit(event: event, form: form)) {
            case .made(let m):
                let id = m.id
                offerEventUndo(core.eventEditLine(id: id) ?? "Changing it in your calendar", .eventEdit(id))
                return nil
            case .refused(let r):
                return r.reason
            }
        } catch {
            return error.localizedDescription
        }
    }

    /// Delete from the calendar after five seconds' Undo; `guestsOk` only for Delete anyway (Meka's second tap after
    /// the server said it cancels the event for its guests). Returns why not, or nil once it's on its way.
    func deleteEvent(_ event: CalendarEvent, guestsOk: Bool) async -> String? {
        guard let core else { return "Connect this Mac to your server first." }
        // Worked out before the event is handed to the core, which then owns it.
        let fallback = core.deletingLine(event: event)
        MekaHaptics.light()
        do {
            switch onEnum(of: try await core.deleteEvent(event: event, guestsOk: guestsOk)) {
            case .made(let m):
                let id = m.id
                offerEventUndo(core.eventEditLine(id: id) ?? fallback, .eventEdit(id))
                return nil
            case .refused(let r):
                return r.reason
            }
        } catch {
            return error.localizedDescription
        }
    }

    /// Clash chooser (slice 2c-iii), Keep mine: the clashed edit is sent again against Google's version after five
    /// seconds' Undo (undoing asks again). Only the edit's id (a String) crosses into the core. Returns why not, or nil.
    func keepMyVersion(_ clashId: String) async -> String? {
        guard let core else { return "Connect this Mac to your server first." }
        MekaHaptics.light()
        do {
            switch onEnum(of: try await core.keepMyVersion(clashId: clashId)) {
            case .made(let m):
                let id = m.id
                offerEventUndo(core.eventEditLine(id: id) ?? "Sending your version", .eventEdit(id))
                return nil
            case .refused(let r):
                return r.reason
            }
        } catch {
            return error.localizedDescription
        }
    }

    /// Clash chooser, Keep theirs: nothing is sent; Google's version stays.
    func keepTheirVersion(_ clashId: String) async {
        guard let core else { return }
        MekaHaptics.tick()
        _ = try? await core.keepTheirVersion(clashId: clashId)
    }

    static func providerName(_ p: String) -> String {
        CalendarAccountRules.shared.providerLabel(provider: p)
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
        let postedKeys = await MacNotifier.post(result)
        // Counts only (ADR-013): the weekly review's Interruptions. Called even when nothing posted, so counting starts.
        // Keys (Strings) cross into the core rather than a Swift array of Kotlin notices, which strict concurrency rejects.
        try? await core.notificationsPostedKeys(result: result, keys: postedKeys)
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
    /// The call assistant's one switch (synced; the Fold does the screening during work).
    func setCallAssistant(_ on: Bool) { MekaHaptics.tick(); run { try await $0.setCallAssistant(on: on) } }
    /// "Keep callers' recordings" (call assistant polish 8c): 7 or 30 days, or 0 for don't keep. Synced; the server
    /// deletes older recordings at once. Only an Int32 crosses to the core.
    func setRecordingDays(_ days: Int) {
        MekaHaptics.tick()
        let d = Int32(days)
        run { try await $0.setRecordingDays(days: d) }
    }

    /// "Leaving now": on or off, who it goes to and the usual journey home. Only a Bool, a String and an Int32 cross.
    func setLeavingNow(on: Bool, to: String, journeyMin: Int) {
        MekaHaptics.tick()
        let j = Int32(journeyMin)
        run { _ = try await $0.setLeavingNow(on: on, to: to, journeyMin: j) }
    }

    /// The week ahead's Sunday heads-up: on or off and its time. Only a Bool and an Int32 cross.
    func setWeekAhead(on: Bool, minute: Int) {
        MekaHaptics.tick()
        let m = Int32(minute)
        run { _ = try await $0.setWeekAhead(on: on, minute: m) }
    }

    /// Goals, properly, item 6: picks a goal for the week the week ahead shows (up to three) or unpicks it. Only a
    /// String and a Bool cross to the core.
    func pickWeekGoal(_ goalId: String, on: Bool) {
        if on { MekaHaptics.light() } else { MekaHaptics.tick() }
        run { _ = try await $0.pickWeekGoal(goalId: goalId, on: on) }
    }

    /// Saves work hours. Days are ISO (1 = Monday); minutes are local minutes of the day.
    func setWorkSchedule(days: Set<Int>, startMinute: Int, endMinute: Int, enabled: Bool) {
        let list = days.sorted().map { KotlinInt(int: Int32($0)) }
        run { try await $0.setWorkSchedule(days: list, startMinute: Int32(startMinute), endMinute: Int32(endMinute), enabled: enabled) }
    }

    /// One weekday's own hours (Thursday's short day); the usual hours clear them. Only Ints cross to the core.
    func setWorkDayHours(isoDay: Int, startMinute: Int, endMinute: Int) {
        MekaHaptics.tick()
        let d = Int32(isoDay), a = Int32(startMinute), b = Int32(endMinute)
        run { try await $0.setWorkDayHours(isoDay: d, startMinute: a, endMinute: b) }
    }

    /// A weekday back on the usual hours.
    func clearWorkDayHours(isoDay: Int) {
        MekaHaptics.tick()
        let d = Int32(isoDay)
        run { try await $0.clearWorkDayHours(isoDay: d) }
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

    // Promised (People, slice 1): only Strings and Ints cross to the core; nothing is sent to anyone.
    func addPromise(_ title: String, to: String?, byInDays: Int?) {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        let w = to?.trimmingCharacters(in: .whitespacesAndNewlines)
        run { _ = try await $0.addPromise(title: t, to: (w?.isEmpty ?? true) ? nil : w, byInDays: Self.k(byInDays)) }
    }

    func setPromiseBy(_ id: String, days: Int?) { run { try await $0.setPromiseBy(id: id, days: Self.k(days)) } }
    func keptPromise(_ id: String) { MekaHaptics.light(); run { try await $0.keptPromise(id: id) } }
    func deletePromise(_ id: String) { run { try await $0.deletePromise(id: id) } }

    // Shopping (family sharing, slice 1): only Strings cross to the core.
    func addShopping(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        run { _ = try await $0.addShopping(text: t) }
    }
    func gotShopping(_ id: String) { MekaHaptics.light(); run { _ = try await $0.gotShopping(id: id) } }
    func putBackShopping(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.putBackShopping(id: id) } }
    func removeShopping(_ id: String) { run { _ = try await $0.removeShopping(id: id) } }
    func clearGotShopping() { run { _ = try await $0.clearGotShopping() } }

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
    /// Bills due: where Meka pays it ("octopus.energy"); blank clears it. False when it isn't a web address.
    func setRenewalPayLink(_ id: String, _ link: String) async -> Bool {
        guard let core else { return false }
        do {
            let ok = try await core.setRenewalPayLink(id: id, link: link).boolValue
            if ok { MekaHaptics.light() } else { MekaHaptics.tick() }
            return ok
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }
    func setRenewalLead(_ id: String, days: Int) { run { try await $0.setRenewalLead(id: id, days: Int32(days)) } }
    func setRenewalCancelBy(_ id: String, daysBefore: Int?) { run { try await $0.setRenewalCancelBy(id: id, daysBefore: Self.k(daysBefore)) } }
    func setRenewalKind(_ id: String, _ kind: ObligationKind) { run { try await $0.setRenewalKind(id: id, kind: kind) } }
    func stopRenewal(_ id: String) { MekaHaptics.light(); run { try await $0.stopRenewal(id: id) } }
    func deleteRenewal(_ id: String) { run { try await $0.deleteRenewal(id: id) } }

    /// Home upkeep: the suggested home jobs against the radar today (pure, from the synced lists).
    var homeUpkeep: HomeUpkeepView? {
        guard let r = lists?.renewals else { return nil }
        return HomeUpkeepRules.shared.view(items: r.all, today: todayEpochDay)
    }

    /// Adds a suggested home job to the radar (light haptic); [opened] gets the new row's id when its date is one only
    /// Meka knows, so the sheet can unfold it. Only a String crosses each way.
    func addHomeUpkeep(_ presetId: String, opened: @escaping @MainActor (String) -> Void) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                if let id = try await core.addHomeUpkeep(presetId: presetId) { opened(id) }
            } catch { lastError = error.localizedDescription }
        }
    }

    /// UK money deadlines: the five money dates against the radar today (pure, from the synced lists).
    var moneyDates: MoneyDatesView? {
        guard let r = lists?.renewals else { return nil }
        return MoneyDateRules.shared.view(items: r.all, today: todayEpochDay)
    }

    /// Adds one of the money dates to the radar (light haptic); [opened] gets the new row's id when its date is one only
    /// Meka knows (a contract's end, the pension check-in), so the sheet can unfold it. Only a String crosses each way.
    func addMoneyDate(_ presetId: String, opened: @escaping @MainActor (String) -> Void) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                if let id = try await core.addMoneyDate(presetId: presetId) { opened(id) }
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Car on autopilot: sets the registration from typed text (blank clears it). Returns what's wrong with it (shown
    /// under the field), or nil when it was saved. Checked here first: Kotlin exceptions don't cross into Swift. Only a
    /// String crosses.
    @discardableResult
    func setCarRegistration(_ text: String) -> String? {
        if let problem = CarRules.shared.registrationError(text: text) { return problem }
        MekaHaptics.light()
        run { _ = try await $0.setCarRegistration(text: text) }
        return nil
    }

    /// Car slice 2: moves the radar's MOT or tax date to the DVLA's day (only on this tap). Only a String crosses.
    func useDvlaDate(_ presetId: String) {
        MekaHaptics.light()
        run { _ = try await $0.useDvlaDate(presetId: presetId) }
    }

    /// Adds one of the car's dates to the radar (light haptic); [opened] gets the new row's id so the sheet can unfold
    /// it for the real date. Only a String crosses each way.
    func addCarDate(_ presetId: String, opened: @escaping @MainActor (String) -> Void) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                if let id = try await core.addCarDate(presetId: presetId) { opened(id) }
            } catch { lastError = error.localizedDescription }
        }
    }

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
    /// Starts an extended fast of `hours` (Fasting v2: 24 h … 7 days), now.
    func startExtendedFast(hours: Int32) { MekaHaptics.light(); run { _ = try await $0.startExtendedFast(hours: hours, startedMinutesAgo: 0) } }
    /// Starts an extended fast that runs until `untilMs` ("Until Fri 18:00"), now.
    func startFastUntil(_ untilMs: Int64) { MekaHaptics.light(); run { _ = try await $0.startFastUntil(untilMs: untilMs, startedMinutesAgo: 0) } }
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

    // MARK: Alarms

    /// Sets the next morning's wake alarm (the Fold rings it); also how ‹ › move a set alarm.
    func setWake(_ minute: Int32) { MekaHaptics.light(); run { _ = try await $0.setWake(minute: minute) } }
    /// "Use 06:30": the suggestion moved earlier than the alarm.
    func useSuggestedWake() { MekaHaptics.light(); run { _ = try await $0.useSuggestedWake() } }
    func wakeOff() { MekaHaptics.tick(); run { try await $0.wakeOff() } }
    func setWakeBuffer(_ minutes: Int32) { MekaHaptics.tick(); run { _ = try await $0.setWakeBuffer(minutes: minutes) } }

    // MARK: Morning brief

    /// "Got it": the card is put away on the Fold too, until tomorrow morning.
    func briefSeen() async {
        guard let core else { return }
        do { try await core.briefSeen(on: "Mac") } catch { lastError = error.localizedDescription }
    }

    /// Calm Today: the brief sheet closed after `seconds`; open long enough to read, it counts as read on both devices
    /// and Today's card folds away. Only a number crosses to the core.
    func briefClosed(openFor seconds: TimeInterval) {
        guard let core else { return }
        core.briefClosed(on: "Mac", openForMs: Int64(max(seconds, 0) * 1000))
    }

    /// A story's picture (images slice): the small JPEG the server made from the feed's picture, fetched from the
    /// server by key (never from the publisher) and kept in memory by the core. Nil when there is none or offline.
    func newsImage(_ key: String?) async -> NSImage? {
        guard let key, let core else { return nil }
        guard let b64 = try? await core.newsImageBase64(key: key), let data = Data(base64Encoded: b64) else { return nil }
        return NSImage(data: data)
    }

    /// A story's picture as the server's JPEG bytes (for the desktop widget's file); nil when there is none or offline.
    func newsImageData(_ key: String) async -> Data? {
        guard let core else { return nil }
        guard let b64 = try? await core.newsImageBase64(key: key) else { return nil }
        return Data(base64Encoded: b64)
    }

    /// The Mac's desktop News widget (news ticker slice 3b): the top three stories still, from the current News place.
    func deskNewsWidget() -> DeskNewsView? { core?.deskNewsWidget() }

    /// Shows or hides a news topic's headlines in the brief; synced with the Fold.
    /// "A morning brief worth listening to", slice 3: Short · Standard · Full (synced). Only the id String crosses.
    func setBriefLength(_ id: String) { MekaHaptics.tick(); run { try await $0.setBriefLength(id: id) } }
    func setNewsTopic(_ id: String, on: Bool) { MekaHaptics.tick(); run { try await $0.setNewsTopic(topicId: id, on: on) } }
    /// News → Spanish sources (off = English only everywhere news shows); synced.
    func setNewsSpanish(on: Bool) { MekaHaptics.tick(); run { try await $0.setNewsSpanish(on: on) } }

    // MARK: Activity log

    /// Puts back what an activity entry changed (light haptic); syncs like any edit. Only a String crosses into the core.
    func undoActivity(_ id: String) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                let line = try await core.undoActivity(id: id)
                activityNote = line == "Undone" ? nil : line
            } catch { lastError = error.localizedDescription }
        }
    }

    // MARK: Ask MEKA (V1 AI layer, slice 3b)

    /// MEKA's AI and the month's spend under Ask's field ("On · $1.20 of $20 this month"); nil until checked.
    private(set) var aiStatus: AiStatusView?
    /// The last Ask card whose Undo was pressed, so Ask shows it again.
    private(set) var askUndone: AskUndone?
    /// Earned trust: "want me to just do these?" after the fifth yes in a row; Ask shows it under the answer.
    var askTrustOffer: TrustOffer?

    // MARK: Health (Reliability first, item 3)

    /// Checks everything MEKA depends on. `force` false (Today's open) asks the server at most every quarter hour.
    /// The Mac's own facts are made here and handed to the core once.
    func refreshHealth(force: Bool = true) async {
        guard let core else { return }
        checkingHealth = true
        defer { checkingHealth = false }
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
        let device = DeviceHealth(mac: true, batteryExempt: nil, batteryStopped: false, notificationAccess: nil,
                                  lastNotificationMs: nil, callRoleHeld: nil, version: version, updateReady: false,
                                  installedCode: nil, updateFailure: nil, sizeBytes: nil, previousSizeBytes: nil)
        _ = try? await core.refreshHealth(device: device, force: force)
    }

    // MARK: Family (family sharing with Jeanette, slice 4)

    /// Reads the links from MEKA's server (and logs, once across both devices, the day one was opened).
    func refreshFamily() async {
        guard let core else { return }
        _ = try? await core.refreshFamily()
    }

    /// Makes Jeanette's link; nil when it couldn't be made (the view's problem says why). Only the name crosses.
    func inviteFamily() async -> FamilyLink? {
        guard let core, !familyBusy else { return nil }
        familyBusy = true
        defer { familyBusy = false }
        return try? await core.inviteFamily(name: "Jeanette")
    }

    /// Turns a link off at once. Only the id String crosses.
    func turnOffFamily(_ id: String) async {
        guard let core, !familyBusy else { return }
        familyBusy = true
        defer { familyBusy = false }
        _ = try? await core.turnOffFamily(id: id)
    }

    // MARK: Watch (Galaxy Watch, slice 1)

    /// Reads the linked watches from MEKA's server.
    func refreshWatches() async {
        guard let core else { return }
        _ = try? await core.refreshWatches()
    }

    /// Links the watch showing `code`; false when it couldn't be (the view's problem says why). Only the String crosses.
    func linkWatch(_ code: String) async -> Bool {
        guard let core, !watchLinkBusy else { return false }
        watchLinkBusy = true
        defer { watchLinkBusy = false }
        _ = try? await core.linkWatch(code: code)
        return watchLink?.problem == nil
    }

    /// Unlinks a watch at once. Only the id String crosses.
    func unlinkWatch(_ id: String) async {
        guard let core, !watchLinkBusy else { return }
        watchLinkBusy = true
        defer { watchLinkBusy = false }
        _ = try? await core.unlinkWatch(id: id)
    }

    // MARK: Recovery key (slice 3)

    /// Asks MEKA's server about the household's key (the sheet shows "Checking…" until it answers).
    func refreshRecoveryKey() async {
        guard let core else { return }
        _ = try? await core.refreshRecoveryKey()
    }

    /// A new key's words for the sheet, made on this Mac; nothing is sent until `saveRecoveryKey`. The sheet keeps it
    /// only while it shows the words (never stored or synced).
    func makeRecoveryKey() -> RecoveryKeyDraft? { core?.makeRecoveryKey() }

    /// Saves `draft` once the three typed words match (only its verifier is sent; the old key stops working). The
    /// draft crosses to the core once, here, and the sheet drops it afterwards. False when the words don't match or
    /// the server couldn't take it (the view's problem says why).
    func saveRecoveryKey(_ draft: RecoveryKeyDraft, answers: [String]) async -> Bool {
        guard let core, !recoveryBusy else { return false }
        recoveryBusy = true
        defer { recoveryBusy = false }
        // The core ticks Setup's step itself once saved.
        return (try? await core.saveRecoveryKey(draft: draft, answers: answers))?.boolValue ?? false
    }

    // MARK: Setup checklist (Meka approved 2026-10-09)

    /// Works out the Setup checklist. `force` false (Today's open) reuses the server's answers for a quarter hour.
    /// The Mac's own facts (only whether macOS lets MEKA notify) are made here and handed to the core once.
    func refreshSetup(force: Bool = true) async {
        guard let core else { return }
        checkingSetup = true
        defer { checkingSetup = false }
        let allowed = await MacNotifier.allowed()
        let device = SetupDevice(mac: true, notificationsAllowed: KotlinBoolean(bool: allowed), batteryExempt: nil,
                                 notificationAccess: nil, callRoleHeld: nil, people: nil, watch: nil)
        _ = try? await core.refreshSetup(device: device, force: force)
    }

    func refreshAiStatus() async {
        guard let core, !signedOut else { aiStatus = AskRules.shared.STATUS_NOT_CONNECTED; return }
        aiStatus = try? await core.aiStatus()
    }

    /// Asks MEKA; only the question (a String) crosses into the core. The answer as Ask shows it: its lines (they fade
    /// in one after another) and cards, or why there is none.
    func askMeka(_ question: String) async -> AskReplyView {
        guard let core else { return AskReplyView(lines: [], cards: [], unavailable: AskRules.shared.NOT_CONNECTED_LINE) }
        var reply = AskReplyView(lines: [], cards: [], unavailable: AskRules.shared.OFFLINE_LINE)
        if let out = try? await core.askMeka(question: question) {
            switch onEnum(of: out) {
            case .answered(let a):
                reply = AskReplyView(lines: AskRules.shared.answerLines(text: a.answer.text), cards: a.answer.cards, unavailable: nil, did: a.answer.did)
            case .unavailable(let u):
                reply = AskReplyView(lines: [], cards: [], unavailable: u.line)
            }
        }
        await refreshAiStatus()
        return reply
    }

    /// Does what an Ask card proposes on Meka's click (light haptic), then the undo bar rises with what it did. The
    /// card crosses into the core once. Returns false when it couldn't be done (the bar says why).
    func doAsk(_ card: AskCard, index: Int) async -> Bool {
        guard let core else { return false }
        MekaHaptics.light()
        do {
            let done = try await core.doAsk(card: card)
            if let offer = done.offer { askTrustOffer = offer }
            offerEventUndo(done.line, done.undo == nil ? nil : EventUndoOffer.Action.ask(done, index))
            return true
        } catch {
            offerEventUndo(error.localizedDescription, nil)
            return false
        }
    }

    // MARK: Earned trust (V1)

    /// Meka's answer to the offer: only the kind's key (a String) and a Bool cross into the core. The bar says what
    /// MEKA will do from now on.
    func answerTrustOffer(_ offer: TrustOffer, yes: Bool) async {
        guard let core else { return }
        if yes { MekaHaptics.light() } else { MekaHaptics.tick() }
        let key = offer.kindKey
        askTrustOffer = nil
        let line = (try? await core.answerTrustOffer(kindKey: key, yes: yes)) ?? "That can't be changed"
        offerEventUndo(line, nil)
    }

    /// Undo on something MEKA did on its own: only the Activity entry's id (a String) crosses; that kind goes back to
    /// asking first, and the bar says so.
    func undoAutoAsk(_ done: AskAutoDone) async {
        guard let core else { return }
        MekaHaptics.tick()
        let id = done.activityId
        let line = (try? await core.undoAutoAsk(activityId: id)) ?? "That can't be undone now"
        offerEventUndo(line, nil)
    }

    // MARK: Talk to MEKA (V1 voice, slice 3)

    /// ⌥Space (the Talk to MEKA command): Ask comes forward and the conversation starts, or ends if one is running.
    /// Ask's section takes the request (sets it back to false) when it is on screen.
    var talkRequested = false

    func requestTalk(reduced: Bool) {
        go(to: .ask, reduced: reduced)
        talkRequested = true
    }

    /// "Listen when I open MEKA" (Talk without tapping the mic, the Mac's slice): MEKA was opened with the setting on,
    /// so Ask comes forward and checks the room before listening. Ask's section takes it (sets it back to false).
    var talkOnOpenRequested = false

    /// The brief's Listen ran to its end (morning brief, slice 7): Ask comes forward and listens for 8 s; nothing said →
    /// `talkAfterBriefBack` (where the brief was read) comes back. Ask's section takes it (sets it back to nil).
    var talkAfterBriefBack: ShellDestination?

    func requestTalkAfterBrief(reduced: Bool) {
        let back = destination
        go(to: .ask, reduced: reduced)
        talkAfterBriefBack = back
    }

    func requestTalkOnOpen(reduced: Bool) {
        go(to: .ask, reduced: reduced)
        talkOnOpenRequested = true
    }

    /// One spoken question with the conversation so far: only the question and the history cross into the core, once.
    /// Nil when not connected (the conversation says so and ends).
    func talk(_ question: String, history: [TalkTurn]) async -> AskOutcome? {
        guard let core else { return nil }
        // Kotlin values are immutable; an array of them isn't Sendable to Swift, so it crosses once, unchecked.
        nonisolated(unsafe) let turns = history
        let out = try? await core.talk(question: question, history: turns)
        await refreshAiStatus()
        return out
    }

    /// A spoken yes: does the cards exactly as clicking them would (`MekaCore.doTalk`), then one undo bar takes them
    /// all back. The cards cross into the core once; `indices` are their places in the answer on screen.
    func doTalk(_ cards: [AskCard], indices: [Int]) async -> TalkDid? {
        guard let core else { return nil }
        nonisolated(unsafe) let sent = cards
        guard let did = try? await core.doTalk(cards: sent) else { return nil }
        if !did.done.isEmpty { MekaHaptics.light() }
        if let offer = did.offer { askTrustOffer = offer }
        let undos = did.undos
        offerEventUndo(did.barLine, undos.isEmpty ? nil : .talk(undos, indices))
        return did
    }

    // MARK: MEKA's voice

    /// One piece of MEKA's own words said in MEKA's voice (Amazon Polly through MEKA's server; only these words are
    /// sent), as MP3 data, or nil when the Mac's own voice should say it (chosen, offline, off, over the month, slow).
    /// A long `reading` (the morning brief) waits longer for its first piece (`SpeechRules.firstWaitMs`).
    /// A `hold` piece may take up to `SpeechRules.HOLD_AUDIO_MS` longer while "One moment…" plays (`SpeechRules.holds`).
    func speechClip(_ text: String, first: Bool, reading: Bool = false, hold: Bool = false) async -> Data? {
        guard let core else { return nil }
        guard let clip = try? await core.speechClip(text: text, first: first, reading: reading, hold: hold) else { return nil }
        return Data(base64Encoded: clip)
    }

    /// "One moment…" in MEKA's voice, only when already fetched with the common lines (never asks the server).
    func speechHoldClip() async -> Data? {
        guard let core else { return nil }
        guard let clip = try? await core.speechHoldClip() else { return nil }
        return Data(base64Encoded: clip)
    }

    /// MEKA's voice isn't to be asked right now (not connected, the Mac's own voice chosen, or the server refused a
    /// moment ago), so a missed piece of the brief hands the rest to the Mac's voice (`SpeechRules.onMiss`).
    func speechResting() async -> Bool {
        guard let core else { return true }
        return (try? await core.speechResting())?.boolValue ?? true
    }

    /// Activity's line about MEKA's voice this month ("MEKA's voice · Amy · 12,400 of 1,000,000 characters in
    /// October"); nil until the server answers or when there is nothing to say. Only a String crosses back.
    private(set) var voiceUsageLine: String?

    /// How quickly MEKA's voice answered on this Mac lately ("Time to MEKA's voice · 1.8 s · 0.9 s · late"); nil
    /// before the first clip. Kept in memory only.
    private(set) var voiceTimingLine: String?

    /// MEKA's AI today ("MEKA's AI today · Talk 6 · Ask 2 · 3¢"), from the server's day count; nil when nothing was
    /// asked today or the server can't say. Only a String crosses back.
    private(set) var aiTodayLine: String?

    func refreshVoiceUsage() async {
        guard let core, !signedOut else { voiceUsageLine = nil; voiceTimingLine = nil; aiTodayLine = nil; return }
        voiceTimingLine = (try? await core.voiceTimingLine()) ?? nil
        voiceUsageLine = (try? await core.voiceUsageLine()) ?? nil
        aiTodayLine = (try? await core.aiTodayLine()) ?? nil
    }

    /// The voice picker (MEKA's voices, then the Mac's own), nil until the server answers. Only the view crosses back.
    private(set) var voicePicker: VoicePickerView?

    func refreshVoicePicker() async {
        guard let core else { voicePicker = nil; return }
        voicePicker = try? await core.voicePicker(mac: true)
    }

    /// Chooses MEKA's voice on every device (a Polly name or "device"), lighting it at once; a tick haptic.
    func chooseVoice(_ id: String) {
        guard let core, let v = voicePicker else { return }
        MekaHaptics.tick()
        voicePicker = VoicePickerView(loaded: v.loaded, intro: v.intro,
                                      choices: v.choices.map { VoiceChoice(id: $0.id, label: $0.label, detail: $0.detail, selected: $0.id == id, sample: $0.sample) },
                                      statusLine: v.statusLine, usageLine: v.usageLine, help: v.help)
        Task {
            if (try? await core.chooseMekaVoice(name: id))?.boolValue == true { await refreshVoicePicker() }
        }
    }

    /// ▶ Sample: the sample line in the server's `voice`, as MP3 data, or nil (the Mac's own voice says it).
    func speechSample(_ voice: String) async -> Data? {
        guard let core else { return nil }
        guard let clip = try? await core.speechSample(voice: voice) else { return nil }
        return Data(base64Encoded: clip)
    }

    /// Fetches MEKA's common lines in its voice once, so they play at once in a conversation.
    func warmVoice() async {
        guard let core else { return }
        _ = try? await core.warmVoice()
    }

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
    /// A habit's dot on the watch face (Calm Today, slice 2): ticks or unticks it, and the undo bar says which habit.
    func tickHabitDot(_ dot: HabitDot) {
        let id = dot.id
        let was = dot.done
        setHabitDone(id, !was)
        offerEventUndo(HabitDotRules.shared.tickedLine(dot: dot, nowDone: !was), .habit(id, was))
    }
    func setHabitTarget(_ id: String, _ perWeek: Int32) { run { try await $0.setHabitTarget(id: id, perWeek: perWeek) } }
    func setHabitTiming(_ id: String, _ timing: HabitTiming) { run { try await $0.setHabitTiming(id: id, timing: timing) } }
    func setHabitMinutes(_ id: String, _ minutes: Int32) { run { try await $0.setHabitMinutes(id: id, minutes: minutes) } }
    func setHabitGoal(_ id: String, _ goalID: String?) { run { try await $0.setHabitGoal(id: id, goalId: goalID) } }
    func deleteHabit(_ id: String) { run { try await $0.deleteHabit(id: id) } }

    // MARK: The Gym (booked habits)

    /// Adds "Gym" (three times a week, evenings, an hour) with its sessions booked into the week.
    func addGym() { MekaHaptics.light(); run { _ = try await $0.addGym() } }
    /// "Let MEKA book “gym”": a hand-made gym habit becomes the booked Gym in place, keeping its ticks.
    func letMekaBook(_ id: String) { MekaHaptics.light(); run { try await $0.letMekaBook(id: id) } }
    /// "Book my sessions" on or off.
    func setHabitBooked(_ id: String, _ on: Bool) { MekaHaptics.tick(); run { try await $0.setHabitBooked(id: id, on: on) } }
    /// The rotation preset at `index` in `SessionRules.ROTATIONS` (0: none).
    func setHabitRotation(_ id: String, _ index: Int) { MekaHaptics.tick(); run { try await $0.setHabitRotation(id: id, index: Int32(index)) } }
    /// Puts a number on the block list (synced; the Fold rejects its calls silently). False (nothing saved) when it
    /// can't be a phone number. Only Strings cross to the core.
    func blockCaller(_ number: String) async -> Bool {
        guard let core else { return false }
        do {
            let ok = try await core.blockCaller(number: number, why: nil).boolValue
            if ok { MekaHaptics.light() } else { MekaHaptics.tick() }
            return ok
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }
    /// Takes a number (its row's key) off the block list.
    /// Block on a held message from someone nobody knows (call assistant polish 8b b): the person crosses into the
    /// core once; light haptic when it's on the list.
    /// A kept voice message's recording (call assistant polish 8c): the MP3 from MEKA's server over a signed request,
    /// played from memory and never saved. Nil when none is kept or the server can't be reached. Only Strings cross.
    func voiceMessageAudio(_ id: String) async -> Data? {
        guard let core else { return nil }
        guard let b64 = try? await core.voiceMessageAudioBase64(id: id), let data = Data(base64Encoded: b64) else { return nil }
        return data
    }

    /// "Play my messages" in Talk (call assistant polish 8c): the synced summary's voice messages as plain steps (MEKA's
    /// lines, then each recording or its words). Empty when not connected.
    func voicePlaylist() -> [PlaylistStep] {
        guard let core else { return [] }
        return PlaylistStep.of(core.voicePlaylist().steps)
    }

    /// Talk's read-outs ("read my brief", "the headlines", "Barça news"): the words to read, from the brief and the News
    /// place the core holds. Synchronous and on the main actor (no network), so the Kotlin value never crosses
    /// isolation. Nil when not connected.
    func talkReadOut(_ read: ReadOut) -> String? {
        guard let core else { return nil }
        return core.talkReadOut(read: read)
    }

    /// The brief as MEKA reads it ("A morning brief worth listening to", slice 2): written by MEKA's AI from the brief's
    /// facts when it answers within 6 s, else MEKA's own. Nothing but the String crosses back; never fails.
    func briefScript() async -> String {
        let fallback = brief.map { BriefScriptRules.shared.script(v: $0, name: "Meka") } ?? ""
        guard let core else { return fallback }
        do {
            return try await core.briefScript(name: "Meka")
        } catch {
            return fallback
        }
    }

    func blockHeldCaller(_ person: PersonSummary) {
        guard let core else { return }
        Task {
            do {
                if try await core.blockHeldCaller(person: person).boolValue { MekaHaptics.light() }
            } catch { lastError = error.localizedDescription }
        }
    }
    func unblockCaller(_ key: String) { MekaHaptics.tick(); run { _ = try await $0.unblockCaller(key: key) } }
    /// Suspected spam (call assistant polish 8b c): Block puts the AI-flagged number on the block list with its reason.
    func confirmSuspectedCaller(_ key: String) { MekaHaptics.light(); run { _ = try await $0.confirmSuspectedCaller(key: key) } }
    /// Not spam: off Suspected spam; its calls are screened as before and MEKA never flags it again.
    func dismissSuspectedCaller(_ key: String) { MekaHaptics.tick(); run { _ = try await $0.dismissSuspectedCaller(key: key) } }
    /// Weather place setting (Calendars → Weather): the town the forecast is for; blank or "Biggleswade" is home.
    /// False (nothing saved) for a name that can't be a town. The server follows it at its next poll.
    func setWeatherPlace(_ name: String) async -> Bool {
        guard let core else { return false }
        do {
            let ok = try await core.setWeatherPlace(name: name).boolValue
            if ok { MekaHaptics.light() } else { MekaHaptics.tick() }
            return ok
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }
    /// Where work is (Places item 2, Calendars → Weather → Work): blank or "Canary Wharf" is the default. False (nothing
    /// saved) for a name that can't be a place. The server follows it at its next poll.
    func setWorkPlace(_ name: String) async -> Bool {
        guard let core else { return false }
        do {
            let ok = try await core.setWorkPlace(name: name).boolValue
            if ok { MekaHaptics.light() } else { MekaHaptics.tick() }
            return ok
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }
    /// The workout app's link ("hevy.com"), opened from Today's card; blank clears it. False when it isn't a web address.
    func setHabitAppLink(_ id: String, _ link: String) async -> Bool {
        guard let core else { return false }
        do {
            let ok = try await core.setHabitAppLink(id: id, link: link).boolValue
            if ok { MekaHaptics.light() } else { MekaHaptics.tick() }
            return ok
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }
    /// "Went": today ticked with the session's label (light haptic; the check pops).
    func sessionWent(_ id: String) { MekaHaptics.light(); run { try await $0.sessionWent(id: id, note: nil) } }
    /// "Didn't go": rebooked on another day this week if there's room.
    func sessionMissed(_ id: String) { MekaHaptics.tick(); run { try await $0.sessionMissed(id: id) } }
    func setSessionNote(_ id: String, _ note: String) {
        let n = note.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty else { return }
        run { try await $0.setSessionNote(id: id, note: n) }
    }
    /// Undo for Went / Didn't go.
    func undoSession(_ id: String) { MekaHaptics.tick(); run { try await $0.undoSession(id: id) } }

    /// Went / Didn't go pressed on a "Did you go?" notification, or Undo on the note that replaces it. The core answers
    /// only that day's session while it still asks; otherwise the notification just goes.
    func answerFromNotification(_ a: NotificationAnswer) {
        // "Leaving now": Copy puts the drafted words on the clipboard for Meka to paste into WhatsApp himself.
        if a.action == MacNotifier.copyMessageAction {
            if let words = a.body, !words.isEmpty {
                let board = NSPasteboard.general
                board.clearContents()
                board.setString(words, forType: .string)
                MekaHaptics.light()
            }
            MacNotifier.remove(key: a.key)
            return
        }
        // Pay on a bill's reminder (Bills due): the saved pay link opens in the browser. MEKA never pays.
        if a.action == MacNotifier.payAction {
            MacNotifier.remove(key: a.key)
            if let link = a.link, let url = URL(string: link) { NSWorkspace.shared.open(url) }
            return
        }
        // Listen on Sunday's week ahead (slice 2): MEKA comes forward with the week ahead reading itself aloud.
        if a.action == MacNotifier.listenAction {
            MacNotifier.remove(key: a.key)
            NSApp.activate()
            readWeekAheadOnOpen = true
            showWeekAhead = true
            return
        }
        // The wake alarm's Snooze / Dismiss (Alarms, slice 1); Dismiss brings up the morning brief.
        if let alarm = MacAlarm.alarmID(fromKey: a.key) {
            if a.action == MacAlarm.snoozeAction {
                run { _ = try await $0.snoozeAlarm(id: alarm) }
            } else if a.action == MacAlarm.dismissAction {
                run { _ = try await $0.dismissAlarm(id: alarm) }
                // Only the wake alarm brings up the brief; a quick alarm or a timer just stops (Alarms, slice 2).
                if MacAlarm.opensBrief(alarmID: alarm) { readBriefOnOpen = true; showBrief = true }
            }
            return
        }
        if a.action == MacNotifier.undoAction {
            MacNotifier.remove(key: a.key)
            if let habit = a.habit { run { try await $0.undoSession(id: habit) } }
            return
        }
        guard let core else { return }
        let key = a.key
        let name = a.action
        Task {
            guard let action = NotifyRules.shared.actionFromName(name: name),
                  let card = try? await core.answerSessionNotice(key: key, action: action) else {
                MacNotifier.remove(key: key)
                return
            }
            await MacNotifier.postAnswered(
                key: key, heading: card.heading, line: SessionRules.shared.answeredLine(card: card), habitId: card.habitId
            )
        }
    }

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
    /// Goals, properly, slice 1: why it matters (blank clears it).
    func setGoalWhy(_ id: String, _ why: String) {
        let w = why.trimmingCharacters(in: .whitespacesAndNewlines)
        run { try await $0.setGoalWhy(id: id, why: w.isEmpty ? nil : w) }
    }
    /// The day it should be done by (a `GoalRules.dueChoices` day; nil clears it). The Kotlin box is made inside the
    /// closure so nothing non-Sendable crosses isolation.
    func setGoalDue(_ id: String, day: Int64?) {
        MekaHaptics.tick()
        run { try await $0.setGoalDue(id: id, dueDay: day.map { KotlinLong(longLong: $0) }) }
    }
    /// Puts the goal under a bigger one (nil: at the top).
    func setGoalParent(_ id: String, _ parentID: String?) { MekaHaptics.tick(); run { try await $0.setGoalParent(id: id, parentId: parentID) } }
    /// "Add a step": a new task linked to the goal.
    func addGoalStep(_ goalID: String, _ title: String) {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        MekaHaptics.light()
        run { _ = try await $0.addGoalStep(goalId: goalID, title: t) }
    }
    /// Goals, properly, slice 2: what done looks like (blank clears it).
    func setGoalTarget(_ id: String, _ target: String) {
        let t = target.trimmingCharacters(in: .whitespacesAndNewlines)
        run { try await $0.editGoal(id: id, title: nil, target: t, horizon: nil) }
    }
    /// A milestone on the way ("Run 10k"); a title already there isn't added twice.
    func addGoalMilestone(_ goalID: String, _ title: String) {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        MekaHaptics.light()
        run { _ = try await $0.addGoalMilestone(goalId: goalID, title: t) }
    }
    func setGoalMilestoneDone(_ id: String, _ done: Bool) {
        MekaHaptics.light()
        run { try await $0.setGoalMilestoneDone(id: id, done: done) }
    }
    /// Removes a milestone; its title comes back for Undo (nil if it was already gone).
    func removeGoalMilestone(_ id: String) async -> String? {
        guard let core else { return nil }
        MekaHaptics.tick()
        return try? await core.removeGoalMilestone(id: id)
    }
    /// Goals, properly, slice 3: the number to reach ("£2,000", "12 books"); blank stops counting. False when it
    /// isn't a number (nothing written).
    func setGoalNumber(_ goalID: String, _ text: String) async -> Bool {
        guard let core else { return false }
        do {
            let ok = try await core.setGoalNumber(goalId: goalID, text: text).boolValue
            if ok { MekaHaptics.light() } else { MekaHaptics.tick() }
            return ok
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }
    /// An update ("+200", "-1", "=1200"). `.added(id)` for Undo, `.noChange`, or `.notANumber`.
    enum GoalUpdateResult: Equatable { case added(String), noChange, notANumber }
    func addGoalUpdate(_ goalID: String, _ text: String) async -> GoalUpdateResult {
        guard let core else { return .notANumber }
        do {
            let id = try await core.addGoalUpdate(goalId: goalID, text: text)
            MekaHaptics.light()
            return id.map { .added($0) } ?? .noChange
        } catch {
            MekaHaptics.tick()
            return .notANumber
        }
    }
    func removeGoalUpdate(_ id: String) {
        MekaHaptics.tick()
        run { _ = try await $0.removeGoalUpdate(id: id) }
    }

    // MARK: Goals, properly, slice 4: Break it down (approval only)

    /// What MEKA proposed for a goal, as Swift values: the milestones and first steps to tick, or a line to show.
    enum BreakdownResult: Equatable {
        case offered(milestones: [String], steps: [String], line: String)
        case said(String)
    }
    /// Asks MEKA to break the goal down. Only the goal's id crosses to the core; nothing is added until Keep.
    func breakDownGoal(_ goalID: String) async -> BreakdownResult {
        guard let core else { return .said(GoalBreakdownRules.shared.NOT_CONNECTED_LINE) }
        do {
            let out = try await core.breakDownGoal(goalId: goalID)
            switch onEnum(of: out) {
            case .proposals(let p): return .offered(milestones: p.breakdown.milestones, steps: p.breakdown.steps, line: p.line)
            case .nothingNew(let n): return .said(n.line)
            case .unavailable(let u): return .said(u.line)
            }
        } catch {
            return .said(GoalBreakdownRules.shared.NOT_CONNECTED_LINE)
        }
    }
    /// Keeps what Meka ticked (Strings only cross to the core); the result carries the bar's line and what Undo takes back.
    func keepBreakdown(_ goalID: String, milestones: [String], steps: [String]) async -> BreakdownKept? {
        guard let core else { return nil }
        do {
            let kept = try await core.keepBreakdown(goalId: goalID, milestones: milestones, steps: steps)
            MekaHaptics.light()
            return kept
        } catch {
            MekaHaptics.tick()
            lastError = error.localizedDescription
            return nil
        }
    }
    /// Undo on a kept breakdown: sent to the core once.
    func undoBreakdown(_ kept: BreakdownKept) {
        MekaHaptics.tick()
        run { try await $0.undoBreakdown(kept: kept) }
    }
    /// Each tick of the habit adds 1 to the goal's number (nil stops it).
    func setGoalNumberHabit(_ goalID: String, _ habitID: String?) {
        MekaHaptics.tick()
        run { try await $0.setGoalNumberHabit(goalId: goalID, habitId: habitID) }
    }
    /// "Link a task": open tasks matching the words, not already on the goal.
    func goalLinkCandidates(_ goalID: String, _ query: String) async -> [MekaTask] {
        guard let core else { return [] }
        return (try? await core.goalLinkCandidates(goalId: goalID, query: query)) ?? []
    }

    // MARK: Commands

    /// Capture from anywhere (menu bar, Services): typed or pasted text becomes one task, first line the title and
    /// the rest kept in the notes (the same rule as the Fold's share sheet, in the core).
    func capture(_ text: String, subject: String? = nil) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || subject != nil else { return }
        run { _ = try await $0.capture(text: text, subject: subject) }
    }

    /// Typed into MEKA's own capture field (Today, the menu bar, the command bar): "alarm 6:30" or "timer 20 min" sets
    /// a quick alarm or timer (Alarms, slice 2) with Undo on the bar; anything else is a task. Shared text (Services)
    /// goes through `capture` and only ever becomes a task. Only a string crosses to the core.
    func captureTyped(_ text: String) {
        guard let core, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        Task {
            do {
                let outcome = try await core.captureTyped(text: text)
                if case .alarmSet(let set) = onEnum(of: outcome) {
                    MekaHaptics.light()
                    offerEventUndo(set.line, .cancelAlarm(set.alarmId))
                }
            } catch { lastError = error.localizedDescription }
        }
    }

    /// "Handle this with MEKA" (Services, V1 slice 1): MEKA comes forward on Needs you while it reads the text, then
    /// the bar says what it found ("2 things to add", "Nothing in that for you to do · …") and the cards wait there with
    /// Add · Change · Not needed. Only Strings cross to the core; nothing is added until Meka taps Add.
    func handleThis(_ text: String, subject: String? = nil) {
        guard let core, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        NSApp.activate()
        go(to: .needsYou, reduced: MotionSetting.reduced)
        offerEventUndo(HandleThisRules.shared.READING_LINE, nil)
        Task {
            do {
                // A form's fields answered from the facts page, on this Mac before the AI answers (and without it).
                let answers = try await core.formAnswers(text: text, subject: subject)
                if !answers.answers.isEmpty {
                    formAnswers = answers
                    showFormAnswers = true
                }
                let outcome = try await core.handleThis(text: text, subject: subject)
                switch onEnum(of: outcome) {
                case .cards(let c):
                    MekaHaptics.light()
                    offerEventUndo(c.line, nil)
                case .nothingToDo(let n): offerEventUndo(n.line, nil)
                case .unavailable(let u): offerEventUndo(u.line, nil)
                }
            } catch { lastError = error.localizedDescription }
        }
    }

    /// The ✕ on a quick alarm or timer: cancelled on every device.
    func cancelAlarm(_ id: String) {
        MekaHaptics.tick()
        run { _ = try await $0.cancelAlarm(id: id) }
    }

    func complete(_ id: String) {
        if selectedID == id { selectedID = nil }
        run { try await $0.complete(taskId: id) }
    }

    func rename(_ id: String, to title: String) { run { try await $0.rename(taskId: id, title: title) } }
    /// Delete gives an undo bar ("Deleted “Buy milk”" · Undo), not a dialog.
    func delete(_ id: String) {
        let title = allTasks.first { $0.id == id }?.title
        selectedID = nil
        run { try await $0.delete(taskId: id) }
        if let title { offerEventUndo(TaskWhenRules.shared.deletedLine(title: title), .restoreTask(id)) }
    }

    // MARK: Task detail: When and Notes (Fold review 2026-10-08, item 8)

    /// The When row for a task: its day, optional time, chips and where "Add a time" starts (pure, in the core).
    func taskWhen(_ task: MekaTask) -> TaskWhenView? { core?.taskWhen(task: task) }

    /// Puts the task on a local epoch day, at minutes past midnight or with no time (nil).
    func setWhen(_ id: String, day: Int64, minute: Int?) {
        MekaHaptics.tick()
        let m = Int32(minute ?? -1)
        run { try await $0.setWhenMinute(taskId: id, day: day, minuteOfDay: m) }
    }

    func setNotes(_ id: String, _ notes: String) { run { try await $0.setNotes(taskId: id, notes: notes) } }

    /// Remind me: "Off" or when, and the chips still ahead of now (pure, in the core).
    func taskReminder(_ task: MekaTask) -> TaskReminderView? { core?.taskReminder(task: task) }

    /// Reminds about the task at epoch ms (a heads-up through the governor); nil turns it off.
    func setReminder(_ id: String, at ms: Int64?) {
        MekaHaptics.tick()
        let at = ms ?? -1
        run { try await $0.setReminderAt(taskId: id, atMs: at) }
    }
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

    /// "Move to later" on a late planned task (Fold review 2026-10-09 13:45, item 3): re-planned to the first free
    /// stretch from now; the undo bar says where it went ("Moved “Send the invoice” to 15:30") and Undo puts it back.
    func moveLater(_ id: String) {
        guard let core else { return }
        Task {
            guard let move = try? await core.moveLater(taskId: id) else { return }
            offerEventUndo(move.line, .later(move))
        }
    }

    /// Checklists (slice 1): one typed or pasted line adds each of its items (new lines, commas).
    func addStep(_ taskID: String, _ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        run { _ = try await $0.addSteps(taskId: taskID, text: trimmed) }
    }

    func setStepDone(_ stepID: String, _ done: Bool) { MekaHaptics.light(); run { try await $0.setStepDone(stepId: stepID, done: done) } }

    /// Remove (or a swipe): the item goes, with Undo on the bar.
    func removeStep(_ stepID: String, _ text: String) {
        MekaHaptics.tick()
        run { try await $0.removeStep(stepId: stepID) }
        offerEventUndo(ChecklistRules.shared.removedLine(text: text), .restoreStep(stepID))
    }

    /// Untick all, to reuse the list; Undo ticks them again.
    func untickAllSteps(_ taskID: String) {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            guard let ids = try? await core.untickAllSteps(taskId: taskID), !ids.isEmpty else { return }
            offerEventUndo(ChecklistRules.shared.untickedLine(count: Int32(ids.count)), .tickSteps(ids))
        }
    }

    /// The detail's Checklist: to tick first, then Ticked, its line and the complete offer.
    func checklistView(_ task: MekaTask) -> ChecklistView? { core?.checklistView(task: task) }

    /// Drag to reorder (Checklists, slice 2): the item takes [toGroupIndex] among the rows of its own group.
    func moveStep(_ taskID: String, _ stepID: String, toGroupIndex: Int) {
        guard toGroupIndex >= 0 else { return }
        MekaHaptics.tick()
        run { try await $0.moveStepInGroup(taskId: taskID, stepId: stepID, toGroupIndex: Int32(toGroupIndex)) }
    }

    // MARK: Checklist templates (Checklists in tasks, slice 3a)

    /// Start from template's choices: Meka's own templates (latest first), then the starters he hasn't replaced.
    func checklistTemplates() async -> ChecklistTemplatesView? {
        guard let core else { return nil }
        return try? await core.checklistTemplates()
    }

    /// The name Save as template suggests (the task's title).
    func templateName(_ task: MekaTask) -> String { core?.templateName(task: task) ?? task.title }

    /// Save as template: the task's checklist under [name]; the bar says so (Undo removes a new one).
    func saveChecklistTemplate(_ taskID: String, name: String) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            guard let saved = try? await core.saveChecklistTemplate(taskId: taskID, name: name) else { return }
            if let id = saved.id, !saved.replaced {
                offerEventUndo(saved.line, .removeTemplate(id))
            } else {
                offerEventUndo(saved.line, nil)
            }
        }
    }

    /// Start from template: its items join the task's checklist, unticked; Undo takes them off again.
    func startFromTemplate(_ taskID: String, templateID: String) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            guard let applied = try? await core.startFromTemplate(taskId: taskID, templateId: templateID) else { return }
            offerEventUndo(applied.line, applied.stepIds.isEmpty ? nil : .removeSteps(applied.stepIds))
        }
    }

    /// Remove a saved template; Undo brings it back.
    func removeChecklistTemplate(_ id: String) {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            guard let removed = try? await core.removeChecklistTemplate(id: id) else { return }
            offerEventUndo(ChecklistTemplateRules.shared.removedLine(name: removed.name), .restoreTemplate(removed))
        }
    }

    /// Shop mode (slice 2): a task's checklist, or the shared shopping list, as big rows.
    func shopMode(_ task: MekaTask) -> ShopModeView? { core?.shopMode(task: task) }
    func shopMode(shopping: ShoppingView) -> ShopModeView? { core?.shopMode(view: shopping) }

    /// "↻ Every weekday", with "· since Mon 5 Oct" when an earlier day's occurrence is still open.
    func repeatLine(_ task: MekaTask) -> String? {
        guard let core, let meta = task.repeatMeta(todayEpochDay: core.todayEpochDay()) else { return nil }
        return "↻ " + meta
    }

    /// Event detail: when, how soon, which calendar, place, notes and a Join link (pure, computed in the core).
    func eventDetail(_ event: CalendarEvent) -> EventDetailView? { core?.eventDetail(event: event) }

    /// The "now" card for the menu bar (Fold modes, slice 3): read from Today, so it follows Today's minute refresh.
    var coverNow: NowView? {
        _ = today // observed: the card redraws whenever Today does
        _ = sessions // and when a booked session is answered ("Did you go?" changes the card, not Today)
        return core?.coverNow()
    }

    /// Up next as the menu bar's card in Today's window too (Fold review 2026-10-09, item 3): "UP NEXT", the title, its
    /// line and Done · Tomorrow · Open. Read from Today; nil when nothing is up next.
    var upNextCard: NowView? {
        _ = today // observed: the card redraws whenever Today does
        return core?.upNextCard()
    }

    /// What "3 held for later" unfolds to in Needs you during work (Fold review 2026-10-09, item 5): sender · first line ·
    /// time, newest first, at most five. A preview only: nothing is marked read. Nil when nothing is held.
    var heldPreview: HeldPreview? {
        _ = work // observed: the label follows work mode's line
        guard let core, let summary = afterWork, !summary.isEmpty else { return nil }
        return core.heldPreview(summary: summary)
    }

    /// What runs outside the app (Outside the app, slice 1): beside MEKA's mark in the menu bar, the next event's
    /// countdown from half an hour before ("12 min", then "Now") or a running fast ("14 h 12 m"). Read from Today and the
    /// fast, so it follows Today's minute refresh. nil when nothing is going on.
    var menuBarStatus: (text: String, symbol: String)? {
        _ = today; _ = fasting // observed: the menu bar redraws whenever either does
        guard let v = core?.ongoing(), let text = v.menuBar, let first = v.items.first else { return nil }
        return (text, first.kind == .meeting ? "clock" : "circle.lefthalf.filled")
    }

    // MARK: Calendar actions (MEKA-only; the real calendars stay read-only)

    /// Adds the event's prep task ("Prepare for …", planned 30 min before it); Undo deletes it.
    func addPrepTask(_ event: CalendarEvent) {
        guard let core else { return }
        let hadOpen = eventMarks?.prepTasks[event.id].map { !$0.isDone } ?? false
        MekaHaptics.light()
        Task {
            do {
                let id = try await core.addPrepTask(event: event)
                offerEventUndo(hadOpen ? "Already has a prep task" : "Prep task added", hadOpen ? nil : .deleteTask(id))
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Weekend football: Kit reminder on a club fixture makes its kit list, planned and reminded at 19:00 the evening
    /// before ("Kit reminder tomorrow 19:00"); Undo deletes it.
    func addKitReminder(_ event: CalendarEvent) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                guard let added = try await core.addKitReminder(event: event) else { return }
                offerEventUndo(added.line, .deleteTask(added.taskId))
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Weekend football, slice 4: "How did it go?" keeps the score (-1 for none), the scorers and a note on a club
    /// fixture once it's over ("Saved · Won 3–1 · Leo 2, Sam"); Undo puts back what was there. Only the event (once),
    /// Ints and Strings cross to the core; the saved result comes back once for its undo.
    func saveMatchResult(_ event: CalendarEvent, ours: Int, theirs: Int, scorers: String, note: String) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                guard let saved = try await core.saveMatchResult(
                    event: event, scoreFor: Int32(ours), scoreAgainst: Int32(theirs), scorers: scorers, note: note
                ) else { return }
                offerEventUndo(saved.line, .matchResult(saved))
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Fold review 17:56, slice 3: whose club session the event's whole series is ("Rex"; nil clears it), so the
    /// Calendar and Today name the child; Undo puts back who was set. The event crosses to the core once; the saved
    /// value comes back once for its undo.
    func setClubChild(_ event: CalendarEvent, _ child: String?) {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            do {
                guard let saved = try await core.setClubChild(event: event, child: child) else { return }
                offerEventUndo(saved.line, .clubChild(saved))
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Weekend football, slice 2: "Leave by 09:15 · as last time" on a club fixture sets the travel time (and the alarm)
    /// Meka set at that ground before ("Leave by 09:15 · 40 min away · alarm"); Undo takes them off. The event is
    /// handed to the core once.
    func useLastLeaveBy(_ event: CalendarEvent) {
        guard let core else { return }
        let eventID = event.id
        MekaHaptics.light()
        Task {
            do {
                guard let used = try await core.useLastLeaveBy(event: event) else { return }
                offerEventUndo(used.line, .lastLeaveBy(eventID))
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Hides an event from my day (timeline, planner, brief, shutdown, review); Undo shows it again.
    func hideEvent(_ eventID: String, offerUndo: Bool = true) {
        MekaHaptics.light()
        run { try await $0.hideEvent(eventId: eventID) }
        if offerUndo { offerEventUndo("Hidden from your day", .showEvent(eventID)) }
    }

    /// "Make it a task" on an all-day entry that reads like a to-do (Today clarity): a task with its title, and the
    /// entry leaves Today; Undo deletes the task and brings the entry back. The event is handed to the core once.
    func makeAllDayTask(_ event: CalendarEvent) {
        guard let core else { return }
        let eventID = event.id
        MekaHaptics.light()
        Task {
            do {
                _ = try await core.makeAllDayTask(event: event)
                offerEventUndo("Made it a task", .unmakeTask(eventID))
            } catch { lastError = error.localizedDescription }
        }
    }

    /// "Hide <calendar> from Today" (all-day polish): the calendar's events leave my day but stay in the Calendar
    /// section and Search; synced with the Fold. Only strings cross to the core. Undo shows it again.
    func hideCalendarFromToday(key: String, label: String) {
        MekaHaptics.light()
        run { try await $0.hideCalendarFromToday(calendarKey: key, label: label) }
        offerEventUndo(CalendarRules.shared.hiddenLine(label: label), .showCalendar(key))
    }

    /// The Calendars switch: on shows the calendar on Today again, off hides it (no undo bar: the switch is the undo).
    func setCalendarOnToday(key: String, label: String, on: Bool) {
        MekaHaptics.tick()
        if on {
            run { try await $0.showCalendarOnToday(calendarKey: key) }
        } else {
            run { try await $0.hideCalendarFromToday(calendarKey: key, label: label) }
        }
    }

    /// Rename in Calendars (Fold review 2026-10-09 07:26, item 9): MEKA's own name for the calendar, synced; empty goes
    /// back to the default ("Personal" for an account's main calendar). The real calendar keeps its name.
    func renameCalendar(key: String, name: String) {
        MekaHaptics.light()
        run { _ = try await $0.renameCalendar(calendarKey: key, name: name) }
    }

    /// Done on the after-work summary: cleared here and on the Fold. WhatsApp and Messages are untouched.
    func clearAfterWork() {
        MekaHaptics.light()
        showAfterWork = false
        run { _ = try await $0.clearAfterWork() }
    }

    func showEvent(_ eventID: String) {
        MekaHaptics.light()
        run { try await $0.showEvent(eventId: eventID) }
    }

    func isEventHidden(_ eventID: String) -> Bool { eventMarks?.hidden.contains(eventID) ?? false }

    /// Remind me this many minutes before (0: none) and the minutes it takes to get there (0: no leave-by).
    func eventReminder(_ eventID: String) -> Int32 { eventMarks?.reminderOf(eventId: eventID) ?? 0 }
    func eventTravel(_ eventID: String) -> Int32 { eventMarks?.travelOf(eventId: eventID) ?? 0 }

    /// The reminder choices still ahead for an event (empty for all-day or started events).
    func remindChoices(_ event: CalendarEvent) -> [Int32] {
        ReminderRules.shared.remindChoices(e: event, nowMs: Self.nowMs()).map { $0.int32Value }
    }

    /// The travel times still ahead (empty when the event has no place).
    func travelChoices(_ event: CalendarEvent) -> [Int32] {
        ReminderRules.shared.travelChoices(e: event, nowMs: Self.nowMs()).map { $0.int32Value }
    }

    /// Remind me [minutes] before (a heads-up through the notification governor); 0 turns it off. Undo puts it back.
    func setEventReminder(_ eventID: String, _ minutes: Int32, offerUndo: Bool = true) {
        let before = eventReminder(eventID)
        MekaHaptics.tick()
        run { try await $0.setEventReminder(eventId: eventID, minutes: minutes) }
        guard offerUndo else { return }
        offerEventUndo(minutes == 0 ? "Reminder off" : "Reminder " + ReminderRules.shared.choiceLabel(minutes: minutes), .reminder(eventID, before))
    }

    /// Leave by: a heads-up [minutes] before the start (how long it takes to get there); 0 turns it off.
    func setEventLeaveBy(_ eventID: String, _ minutes: Int32, offerUndo: Bool = true) {
        let before = eventTravel(eventID)
        MekaHaptics.tick()
        run { try await $0.setEventLeaveBy(eventId: eventID, travelMinutes: minutes) }
        guard offerUndo else { return }
        offerEventUndo(minutes == 0 ? "Leave-by reminder off" : "Leave-by reminder · " + ReminderRules.shared.travelLabel(minutes: minutes), .leaveBy(eventID, before))
    }

    /// Ring as an alarm (Alarms, slice 3): the event's leave-by rings like the wake alarm instead of a heads-up.
    func setEventLeaveAlarm(_ eventID: String, _ on: Bool) {
        MekaHaptics.tick()
        run { try await $0.setEventLeaveAlarm(eventId: eventID, on: on) }
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    func undoEventAction() {
        guard let offer = eventUndo, let action = offer.action else { return }
        eventUndo = nil
        MekaHaptics.light()
        switch action {
        case .deleteTask(let id): run { try await $0.delete(taskId: id) }
        case .restoreTask(let id): run { try await $0.restore(taskId: id) }
        case .showEvent(let id): run { try await $0.showEvent(eventId: id) }
        case .unmakeTask(let id): run { try await $0.undoAllDayTask(eventId: id) }
        case .cancelAlarm(let id): run { _ = try await $0.cancelAlarm(id: id) }
        case .showCalendar(let key): run { try await $0.showCalendarOnToday(calendarKey: key) }
        case .reminder(let id, let m): run { try await $0.setEventReminder(eventId: id, minutes: m) }
        case .leaveBy(let id, let m): run { try await $0.setEventLeaveBy(eventId: id, travelMinutes: m) }
        case .lastLeaveBy(let id): run { try await $0.undoLastLeaveBy(eventId: id) }
        case .habit(let id, let done): run { try await $0.setHabitDone(id: id, done: done) }
        case .matchResult(let saved): run { try await $0.undoMatchResult(saved: saved) }
        case .clubChild(let saved): run { try await $0.undoClubChild(saved: saved) }
        case .decision(let undo): run { _ = try await $0.undoDecision(undo: undo) }
        case .request(let done): run { _ = try await $0.undoRequest(done: done) }
        case .schoolCover(let done): run { _ = try await $0.undoSchoolCover(done: done) }
        case .dateNightBooked(let day): run { _ = try await $0.bookDateNight(day: day, booked: false) }
        case .dateNightSkipped(let day): run { _ = try await $0.skipDateNight(day: day, skip: false) }
        case .groupDigest(let undo): run { try await $0.undoCatchUpGroupDigest(undo: undo) }
        case .appointment(let done): run { _ = try await $0.undoAppointment(done: done) }
        case .appointmentCall(let call): run { _ = try await $0.undoAppointmentCall(call: call) }
        case .restoreStep(let id): run { try await $0.restoreStep(stepId: id) }
        case .tickSteps(let ids): run { try await $0.tickSteps(stepIds: ids) }
        case .removeSteps(let ids): run { core in for id in ids { try await core.removeStep(stepId: id) } }
        case .removeTemplate(let id): run { _ = try await $0.removeChecklistTemplate(id: id) }
        case .restoreTemplate(let template): run { _ = try await $0.restoreChecklistTemplate(template: template) }
        case .unsetAside(let id): needsYouSetAside.removeAll { $0 == id }
        case .eventEdit(let id): run { _ = try await $0.undoEventEdit(id: id) }
        case .planBlocks(let ids): run { _ = try await $0.undoPlanBlocks(editIds: ids) }
        case .later(let move): run { try await $0.undoMoveLater(move: move) }
        case .ask(let done, let card):
            guard let undo = done.undo else { break }
            askUndone = AskUndone(n: (askUndone?.n ?? 0) + 1, cards: [card])
            run { _ = try await $0.undoAsk(undo: undo) }
        case .talk(let undos, let cards):
            askUndone = AskUndone(n: (askUndone?.n ?? 0) + 1, cards: Set(cards))
            nonisolated(unsafe) let sent = undos
            run { _ = try await $0.undoTalk(undos: sent) }
        }
    }

    func dismissEventUndo() { eventUndo = nil }

    // MARK: Emergency card (slice 1)

    /// What adding a line said: the line to show and whether it was turned away (a secret or a number).
    struct EmergencySaid: Equatable {
        let line: String
        let refused: Bool
    }

    /// Adds one typed line under a part (`call` · `documents` · `accounts`); only Strings cross. A refused line writes
    /// nothing and says why.
    func addEmergency(section: String, text: String) async -> EmergencySaid? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        do {
            let r = try await core.addEmergency(section: section, text: t)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return EmergencySaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// What adding a fact said: the line to show and whether it was turned away (a secret or a card, bank or ID number).
    struct FactSaid: Equatable {
        let line: String
        let refused: Bool
    }

    /// Adds one typed fact ("Car reg: AB12 CDE"); the same name again changes it. Only a String crosses.
    func addFact(_ text: String) async -> FactSaid? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        do {
            let r = try await core.addFact(text: t)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return FactSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Lets MEKA's AI answer from a fact, or keeps it from the AI (only a String and a Bool cross).
    func setFactAi(_ id: String, use: Bool) { MekaHaptics.tick(); run { _ = try await $0.setFactAi(id: id, use: use) } }

    func removeFact(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeFact(id: id) } }

    func removeEmergency(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeEmergency(id: id) } }

    // MARK: Train queue (train-time queue, slice 1)

    /// What saving a pasted link said: the line to show and whether it was turned away (no link in it).
    struct QueueSaid: Equatable {
        let line: String
        let refused: Bool
    }

    /// Saves the first web link in pasted words for the train; only Strings cross. No link: nothing is written.
    func addToQueue(_ text: String) async -> QueueSaid? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        do {
            let r = try await core.addToQueue(text: t, subject: nil)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return QueueSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Done when read or listened to (`done` true), or Back into the queue.
    func queueDone(_ id: String, done: Bool) {
        if done { MekaHaptics.light() } else { MekaHaptics.tick() }
        run { _ = try await $0.queueDone(id: id, done: done) }
    }

    func removeFromQueue(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeFromQueue(id: id) } }

    // MARK: Errands (errands by location, slice 1)

    /// What adding a typed errand said: the line under the field, and whether nothing was written (no place in it).
    struct ErrandSaid: Equatable {
        let line: String
        let refused: Bool
    }

    /// Adds "Tesco: milk, batteries" (or "when I'm near …: …"); only Strings cross. No place: nothing is written.
    func addErrand(_ text: String) async -> ErrandSaid? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        do {
            let r = try await core.addErrand(text: t)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return ErrandSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Done ticks an errand off (`done` true); Back puts it back at its place.
    func errandDone(_ id: String, done: Bool) {
        if done { MekaHaptics.light() } else { MekaHaptics.tick() }
        run { _ = try await $0.errandDone(id: id, done: done) }
    }

    func removeErrand(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeErrand(id: id) } }

    // MARK: Annual leave (annual leave planner, slice 1)

    /// What booking said: the line under the field, and whether nothing was written (no date, no work days, booked).
    struct LeaveSaid: Equatable {
        let line: String
        let refused: Bool
    }

    /// Books one typed stretch ("27–30 Oct", "Spain 18 Dec – 4 Jan"); only Strings cross.
    func addLeave(_ text: String) async -> LeaveSaid? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        MekaHaptics.light()
        do {
            guard let r = try await core.addLeave(text: t) else {
                MekaHaptics.tick()
                return LeaveSaid(line: LeaveRules.shared.NOT_READ, refused: true)
            }
            if r.refused { MekaHaptics.tick() }
            return LeaveSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Book on a school holiday: its work days not booked yet; only the school item's id crosses.
    func bookLeave(forHoliday id: String) async -> LeaveSaid? {
        guard let core else { return nil }
        MekaHaptics.light()
        do {
            guard let r = try await core.bookLeaveForHoliday(schoolId: id) else { return nil }
            return LeaveSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func removeLeave(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeLeave(id: id) } }

    /// The allowance (days a leave year) and the month the leave year starts; only Ints cross.
    func setLeaveAllowance(days: Int32, yearStartMonth: Int32) {
        MekaHaptics.tick()
        run { _ = try await $0.setLeaveAllowance(days: days, yearStartMonth: yearStartMonth) }
    }

    // MARK: Work wins (slice 1)

    /// What keeping a win said: the line to show under the field and whether it was turned away.
    struct WinSaid: Equatable {
        let line: String
        let refused: Bool
    }

    /// Keeps one typed line for the week Review shows (today, or that week's Friday); only a String crosses.
    func addWin(_ text: String) async -> WinSaid? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        do {
            let r = try await core.addWin(text: t)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return WinSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func removeWin(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeWin(id: id) } }

    // MARK: Meal plan → shopping (slice 1)

    /// Adds a favourite from one typed line ("Chilli: mince, beans, rice"); only a String crosses. Returns the line to
    /// show under the field: what was added, or how to type it.
    func addMeal(_ text: String) async -> String? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        MekaHaptics.light()
        do {
            return try await core.addMeal(text: t) ?? MealRules.shared.NOT_READ
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func removeMeal(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeMeal(id: id) } }

    /// Plans a favourite for a day (nil clears it); only an Int64 and a String cross.
    func planMeal(day: Int64, mealId: String?) { MekaHaptics.tick(); run { _ = try await $0.planMeal(day: day, mealId: mealId) } }

    /// "Add to shopping": the week's ingredients go on the shared list once each. The result stays here; the sheet
    /// gets its line and Undo.
    func mealsToShopping() {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                let done = try await core.mealsToShopping()
                lastMealsShopped = done
                mealsShoppedLine = done.line
                mealsShoppedCanUndo = !done.added.isEmpty || !done.revived.isEmpty
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Undo under "Add to shopping": what it added leaves the list. The kept result crosses to the core once.
    func undoMealsShopping() {
        guard let core, let done = lastMealsShopped else { return }
        MekaHaptics.tick()
        lastMealsShopped = nil
        mealsShoppedLine = nil
        mealsShoppedCanUndo = false
        nonisolated(unsafe) let sent = done
        Task {
            do { _ = try await core.undoMealsShopping(done: sent) } catch { lastError = error.localizedDescription }
        }
    }

    /// The sheet closed: its "Added …" line and Undo go with it.
    func forgetMealsShopped() {
        lastMealsShopped = nil
        mealsShoppedLine = nil
        mealsShoppedCanUndo = false
    }

    // MARK: What MEKA may do (policy engine UI, slice 1)

    /// Pause or resume everything MEKA does on its own; only a Bool crosses (a String comes back), shown under the
    /// sheet's summary.
    func setAutonomyPaused(_ paused: Bool) async {
        guard let core else { return }
        MekaHaptics.light()
        if let line = try? await core.setAutonomyPaused(paused: paused) { autonomySaid = line }
    }

    /// A row's Ask first / Let MEKA offer again; only the kind's key (a String) crosses.
    func resetTrust(_ kindKey: String) async {
        guard let core else { return }
        MekaHaptics.tick()
        if let line = try? await core.resetTrust(kindKey: kindKey) { autonomySaid = line }
    }

    // MARK: Protected time (slice 1)

    /// Evenings from `fromMin` and weekends (with bank holidays) kept free of tasks; only two Bools and an Int cross.
    func setProtectedTime(evenings: Bool, fromMin: Int32, weekends: Bool) {
        MekaHaptics.tick()
        run { _ = try await $0.setProtectedTime(evenings: evenings, fromMin: fromMin, weekends: weekends) }
    }

    // MARK: Date night (slice 1)

    /// Every other `weekday` (ISO 1…7) from `startMin`, the first on `firstDay`; only Ints and an Int64 cross.
    func setDateNight(weekday: Int32, startMin: Int32, firstDay: Int64) {
        MekaHaptics.tick()
        dateNightSaid = nil
        run { _ = try await $0.setDateNight(weekday: weekday, startMin: startMin, firstDay: firstDay) }
    }

    /// Turns date night off: those evenings are free to plan again.
    func dateNightOff() {
        MekaHaptics.tick()
        dateNightSaid = nil
        run { _ = try await $0.dateNightOff() }
    }

    /// Skips one coming night (its evening is free to plan) or keeps it again; an Int64 and a Bool cross, a String comes back.
    func skipDateNight(day: Int64, skip: Bool) {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            do {
                if let line = try await core.skipDateNight(day: day, skip: skip) { dateNightSaid = line }
            } catch { lastError = error.localizedDescription }
        }
    }

    /// The sheet closed: its line goes with it.
    func forgetDateNightSaid() { dateNightSaid = nil }

    // MARK: Life admin hour (slice 1)

    /// Books the hour every `weekday` (ISO 1…7) from `startMin`; only Ints cross.
    func setLifeAdmin(weekday: Int32, startMin: Int32) {
        MekaHaptics.tick()
        lifeAdminSaid = nil
        run { _ = try await $0.setLifeAdmin(weekday: weekday, startMin: startMin) }
    }

    /// Turns the life admin hour off.
    func lifeAdminOff() {
        MekaHaptics.tick()
        lifeAdminSaid = nil
        run { _ = try await $0.lifeAdminOff() }
    }

    /// Skips this week's hour (it's free again) or keeps it; a Bool crosses, a String comes back.
    func skipLifeAdmin(_ skip: Bool) {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            do {
                if let line = try await core.skipLifeAdmin(skip: skip) { lifeAdminSaid = line }
            } catch { lastError = error.localizedDescription }
        }
    }

    /// The sheet closed: its line goes with it.
    func forgetLifeAdminSaid() { lifeAdminSaid = nil }

    /// Booked · Skip this one on Needs you's week-before card (slice 2): an Int64 and a Bool cross, a String comes back
    /// for the undo bar. Booked only takes the card and its heads-up away on both apps; MEKA books nothing.
    func answerDateNight(day: Int64, booked: Bool) {
        guard let core else { return }
        if booked { MekaHaptics.light() } else { MekaHaptics.tick() }
        Task {
            do {
                if booked {
                    if let line = try await core.bookDateNight(day: day, booked: true) { offerEventUndo(line, .dateNightBooked(day)) }
                } else {
                    if let line = try await core.skipDateNight(day: day, skip: true) { offerEventUndo(line, .dateNightSkipped(day)) }
                }
            } catch { lastError = error.localizedDescription }
        }
    }

    // MARK: Appointment nudges (slice 1)

    /// What a typed line or a Common ones chip said: the line under the field, and whether nothing was written.
    struct AppointmentLine: Equatable {
        let line: String
        let refused: Bool
    }

    /// One typed line ("Dentist every 6 months, last 12 Apr", "dentist booked Tue 20 Oct 9:30"); only a String crosses.
    func typeAppointment(_ text: String) async -> AppointmentLine? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        do {
            let r = try await core.typeAppointment(text: t)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return AppointmentLine(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// "+ Dentist" under Common ones: added with its usual interval.
    func addAppointmentPreset(_ id: String) async -> AppointmentLine? {
        guard let core else { return nil }
        do {
            let r = try await core.addAppointmentPreset(presetId: id)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return AppointmentLine(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Went (the sheet's rows and the Needs you card): a visit today; the undo bar takes it back.
    func appointmentWent(_ id: String) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                if let done = try await core.appointmentWent(id: id) { offerEventUndo(done.line, .appointment(done)) }
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Booked… on a Needs you card: the typed day and time ("Tue 20 Oct 9:30"), Strings in; a date that can't be read
    /// stays on the card with the reason under its field. MEKA books nothing.
    func bookAppointment(_ id: String, when text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return }
        MekaHaptics.light()
        Task {
            do {
                guard let done = try await core.bookAppointment(id: id, whenText: t) else { return }
                if done.refused {
                    MekaHaptics.tick()
                    appointmentRefusal = (id, done.line)
                } else {
                    appointmentRefusal = nil
                    offerEventUndo(done.line, .appointment(done))
                }
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Not yet on a Needs you card: asks again in two weeks.
    func appointmentNotYet(_ id: String) {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            do {
                if let done = try await core.appointmentNotYet(id: id) { offerEventUndo(done.line, .appointment(done)) }
            } catch { lastError = error.localizedDescription }
        }
    }

    func removeAppointment(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeAppointment(id: id) } }

    /// Call to book on a Needs you card (slice 2b): "Call to book: Dentist" planned on Today, the card asking again two
    /// days after the call; the undo bar takes both back. MEKA calls no one. Only the id String crosses.
    func appointmentCallToBook(_ id: String) {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            do {
                if let call = try await core.appointmentCallToBook(id: id) { offerEventUndo(call.line, .appointmentCall(call)) }
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Copy email on a Needs you card (slice 2b): the booking email on the pasteboard for Meka to send himself; nothing
    /// was sent or changed, so the bar has no Undo.
    func appointmentCopyEmail(_ id: String) {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            do {
                guard let text = try await core.appointmentBookingEmail(id: id) else { return }
                let board = NSPasteboard.general
                board.clearContents()
                board.setString(text, forType: .string)
                offerEventUndo(AppointmentRules.shared.EMAIL_COPIED, nil)
            } catch { lastError = error.localizedDescription }
        }
    }

    // MARK: Bin days (slice 1)

    /// What adding a typed bin said: the line under the field, and whether nothing was written.
    struct BinSaid: Equatable {
        let line: String
        let refused: Bool
    }

    /// Adds "Black bin Tue fortnightly from 13 Oct" (a bin already there by name is updated); only a String crosses.
    func addBin(_ text: String) async -> BinSaid? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        do {
            let r = try await core.addBin(text: t)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return BinSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func removeBin(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeBin(id: id) } }

    // MARK: Travel mode (slice 1)

    /// What adding a typed trip or booking said: the line under the field, and whether nothing was written.
    struct TripSaid: Equatable {
        let line: String
        let refused: Bool
    }

    /// Adds "Barcelona 14–18 Nov" or "Flight BA478 Sat 14 Nov 07:40 ref ABC123"; only a String crosses.
    func addTrip(_ text: String) async -> TripSaid? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        do {
            let r = try await core.addTrip(text: t)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return TripSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func removeTrip(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeTrip(id: id) } }

    func removeTripBooking(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeTripBooking(id: id) } }

    // MARK: The Vault (V2, slice 1)

    /// Adds "Passport expires 3 Mar 2029" or "Railcard 05/2027"; only a String crosses. Says what it did like Trips.
    func addDocument(_ text: String) async -> TripSaid? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        do {
            let r = try await core.addDocument(text: t)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return TripSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func removeDocument(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeDocument(id: id) } }

    // MARK: Gift planner (slice 1)

    /// What adding a typed birthday or anniversary said: the line under the field, and whether nothing was written.
    struct GiftSaid: Equatable {
        let line: String
        let refused: Bool
    }

    /// Adds "Mum's birthday 3 Nov" (the same person and kind again updates it); only a String crosses.
    func addOccasion(_ text: String) async -> GiftSaid? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        do {
            let r = try await core.addOccasion(text: t)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return GiftSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func removeOccasion(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeOccasion(id: id) } }

    func addGiftIdea(_ occasionId: String, _ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        MekaHaptics.light()
        run { _ = try await $0.addGiftIdea(occasionId: occasionId, text: t) }
    }

    func removeGiftIdea(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeGiftIdea(id: id) } }

    func setGiftSorted(_ id: String, _ sorted: Bool) { MekaHaptics.light(); run { _ = try await $0.setGiftSorted(id: id, sorted: sorted) } }

    /// Takes back a bin one-off (slice 3): that collection goes on its usual day again; only the id String crosses.
    func removeBinMove(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeBinMove(id: id) } }

    // MARK: School rhythm (slice 1)

    /// Adds one typed line ("INSET 27 Oct", "Rex PE Tue"); only a String crosses. Returns the line to show under the
    /// field: what was added, or that MEKA couldn't read a date in it.
    func addSchool(_ text: String) async -> String? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        MekaHaptics.light()
        do {
            return try await core.addSchool(text: t) ?? SchoolRules.shared.NOT_READ
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func removeSchool(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeSchool(id: id) } }

    /// I'll work from home ([home]) or Covered on a school day off's cover question; only the id and a Bool cross.
    /// The undo bar rises with what it did.
    func answerSchoolCover(_ id: String, home: Bool) {
        guard let core else { return }
        if home { MekaHaptics.light() } else { MekaHaptics.tick() }
        Task {
            do {
                if let done = try await core.coverSchool(id: id, home: home) { offerEventUndo(done.line, .schoolCover(done)) }
            } catch { lastError = error.localizedDescription }
        }
    }

    // MARK: Approvals inbox

    /// Approve on an approval card: Touch ID first, every time (biometrics only, no password). Slice 2: the core says
    /// what to sign (an expired or answered one is refused before any prompt), Touch ID unlocks the Mac's approval key
    /// (Secure Enclave) for that one signature, and only Strings and an Int64 cross back to the core with the signed
    /// yes. A Mac without Touch ID or a Secure Enclave says to approve on the Fold.
    func approve(_ card: ApprovalCard) {
        guard let core else { return }
        guard MacBiometric.available, MacApprovalKey.publicKey() != nil else {
            MekaHaptics.tick()
            offerEventUndo(ApprovalRules.shared.NO_BIOMETRIC_MAC, nil)
            return
        }
        let id = card.id
        let reason = ApprovalRules.shared.promptReason(card: card)
        MekaHaptics.light()
        Task {
            do {
                let prep = try await core.prepareApproval(approvalId: id)
                if let refusal = prep.refusal {
                    offerEventUndo(refusal, nil)
                    return
                }
                guard let message = prep.message else { return }
                let key = prep.decisionKey
                let at = prep.decidedAtMs
                MacBiometric.sign(reason: reason, message: message) { [weak self] signed in
                    self?.finishApprove(id, decisionKey: key, decidedAtMs: at, signed: signed)
                }
            } catch { lastError = error.localizedDescription }
        }
    }

    private func finishApprove(_ id: String, decisionKey: String, decidedAtMs: Int64, signed: MacApprovalKey.Signed) {
        guard let core else { return }
        switch signed {
        case .failed:
            return
        case .renewed:
            if let pub = MacApprovalKey.publicKey() {
                run { _ = try await $0.registerApprovalKey(publicKeyBase64: pub, label: MacApprovalKey.label) }
            }
            offerEventUndo(ApprovalRules.shared.KEY_RENEWED_MAC, nil)
        case .signature(let signature, let publicKey):
            Task {
                do {
                    let answer = try await core.approve(
                        approvalId: id, decisionKey: decisionKey, decidedAtMs: decidedAtMs,
                        publicKeyBase64: publicKey, signatureBase64: signature, keyLabel: MacApprovalKey.label
                    )
                    if answer.done { MekaHaptics.medium() }
                    offerEventUndo(answer.line, nil)
                } catch { lastError = error.localizedDescription }
            }
        }
    }

    /// Decline on an approval card: a tap is enough (saying no is always safe).
    func declineApproval(_ card: ApprovalCard) {
        guard let core else { return }
        let id = card.id
        MekaHaptics.tick()
        Task {
            do {
                let answer = try await core.declineApproval(approvalId: id)
                offerEventUndo(answer.line, nil)
            } catch { lastError = error.localizedDescription }
        }
    }

    // MARK: Requests from people Meka watches

    /// Add · Change · Not a task on a request card. Add makes the task, event or work-from-home day; Change makes it a
    /// task and opens it in Search beside the results; each raises the undo bar (Undo takes back what was made; the
    /// card stays answered on both devices). Nothing is sent to anyone.
    func answerRequest(_ card: RequestCard, _ choice: RequestAnswer) {
        guard let core else { return }
        let id = card.id
        switch choice {
        case .add:
            MekaHaptics.light()
            Task {
                do {
                    guard let done = try await core.acceptRequest(cardId: id) else { return }
                    // "Handle this", slice 3: a reply's draft goes on the pasteboard (Meka sends it himself); nothing
                    // was sent or added, so the bar has no Undo.
                    if let text = done.copyText {
                        let board = NSPasteboard.general
                        board.clearContents()
                        board.setString(text, forType: .string)
                    }
                    offerEventUndo(done.line, done.undoable ? .request(done) : nil)
                } catch { lastError = error.localizedDescription }
            }
        case .change:
            MekaHaptics.light()
            let title = card.proposal.title
            Task {
                do {
                    guard let done = try await core.changeRequest(cardId: id) else { return }
                    offerEventUndo(done.line, .request(done))
                    if let task = done.taskId {
                        searchSeed = title
                        searchChosenSeed = task
                        showSearch = true
                    }
                } catch { lastError = error.localizedDescription }
            }
        case .decline:
            MekaHaptics.tick()
            let line = "\(card.declineLabel) · " + (card.from.replacingOccurrences(of: "From ", with: "").components(separatedBy: " · ").first ?? "")
            Task {
                do {
                    if try await core.declineRequest(cardId: id).boolValue { offerEventUndo(line, nil) }
                } catch { lastError = error.localizedDescription }
            }
        }
    }

    /// A triaged message's buttons (V1, messages slice 3). [lead]: Copy reply (a draft; it goes on the pasteboard and
    /// the card is answered, Meka sends it himself) or Seen (an FYI, or a reply with no draft); otherwise Not now.
    /// Only the card's id (a String) crosses to the core. Nothing is ever sent from the Mac.
    /// Done on a chat's card (messages grouped per chat, slice 1): every message in it seen on both devices. Only the
    /// ids and the undo line (Strings) cross to the core's task. Nothing is sent or marked read anywhere.
    func doneTriageChat(_ chat: TriageChat) {
        guard let core else { return }
        MekaHaptics.light()
        let ids = chat.ids
        let line = TriageChatRules.shared.doneLine(chat: chat)
        // The group's digest chatter rode in this card (slice 3b): Done catches the group up too, here and on the Fold.
        // Only the keys (Strings) cross to the core.
        let groupKeys = TriageChatRules.shared.gistsInside(chat: chat, gists: groupGists).map(\.groupKey)
        Task {
            do {
                let seen = try await core.seenTriageChat(messageIds: ids).int32Value
                if !groupKeys.isEmpty { _ = try await core.catchUpGroupDigest(groupKeys: groupKeys) }
                if seen > 0 { offerEventUndo(line, nil) }
            } catch { lastError = error.localizedDescription }
        }
    }

    func answerTriage(_ card: TriageCard, lead: Bool) {
        guard let core else { return }
        let id = card.id
        let rules = TriageReplyRules.shared
        if lead {
            MekaHaptics.light()
            let line: String
            if let draft = card.draft {
                let board = NSPasteboard.general
                board.clearContents()
                board.setString(draft, forType: .string)
                line = rules.copiedLine(card: card)
            } else {
                line = rules.seenLine(card: card)
            }
            Task {
                do {
                    if try await core.seenTriage(messageId: id).boolValue { offerEventUndo(line, nil) }
                } catch { lastError = error.localizedDescription }
            }
        } else {
            MekaHaptics.tick()
            let line = rules.notNowLine(card: card)
            Task {
                do {
                    if try await core.dismissTriage(messageId: id).boolValue { offerEventUndo(line, nil) }
                } catch { lastError = error.localizedDescription }
            }
        }
    }

    /// Caught up on the group digest (one group's card, or all of them): the cards leave Needs you here and on the Fold,
    /// with Undo on the bar. Only the groups' keys (Strings) cross to the core.
    func catchUpGroupDigest(_ gists: [GroupGist]) {
        guard let core, !gists.isEmpty else { return }
        MekaHaptics.light()
        let keys = gists.map(\.groupKey)
        let line = gists.count == 1 ? "\(gists[0].title) · caught up" : "Group digest · caught up"
        Task {
            do {
                let undo = try await core.catchUpGroupDigest(groupKeys: keys)
                offerEventUndo(line, .groupDigest(undo))
            } catch { lastError = error.localizedDescription }
        }
    }

    // MARK: Needs you stack

    /// The stack as shown: set-aside cards at the back, in the order they were set aside.
    var needsYouCards: [DecisionCard] {
        guard let stack = needsYouStack else { return [] }
        return NeedsYouStackRules.shared.ordered(stack: stack, setAside: needsYouSetAside)
    }

    /// A move on a card (→ yes, ← later, ↑ open): Done and Tomorrow go to the core with an undo bar; Choose/Open
    /// select the task beside the stack; Go through opens Lists; Later sets the card aside on this screen.
    func decide(_ card: DecisionCard, _ move: DecisionMove, reduced: Bool) {
        let message = NeedsYouStackRules.shared.message(card: card, move: move)
        let effect = card.effect(move: move)
        switch effect {
        case .completeTask, .snoozeTask:
            guard let id = card.taskId, let core else { return }
            MekaHaptics.light()
            if selectedID == id { selectedID = nil }
            Task {
                do {
                    if let undo = try await core.decide(taskId: id, effect: effect) {
                        offerEventUndo(message, .decision(undo))
                    }
                } catch { lastError = error.localizedDescription }
            }
        case .openTask:
            if let id = card.taskId { select(id, reduced: reduced) }
        case .openLists:
            go(to: .lists, reduced: reduced)
        case .openLink:
            // Top up (the call assistant's credit): the billing page in the browser; MEKA never pays anything.
            MekaHaptics.light()
            if let link = card.link, let url = URL(string: link) { NSWorkspace.shared.open(url) }
        case .setAside:
            MekaHaptics.light()
            withAnimation(MekaMotion.replan(reduced: reduced)) {
                needsYouSetAside.removeAll { $0 == card.id }
                needsYouSetAside.append(card.id)
            }
            offerEventUndo(message, .unsetAside(card.id))
        }
    }

    private func offerEventUndo(_ message: String, _ action: EventUndoOffer.Action?) {
        let offer = EventUndoOffer(message: message, action: action)
        eventUndo = offer
        Task {
            try? await Task.sleep(for: .seconds(5))
            if eventUndo?.id == offer.id { eventUndo = nil }
        }
    }

    func completeSelected() { if let id = selectedID { complete(id) } }
    func deleteSelected() { if let id = selectedID { delete(id) } }
    /// True while a sync asked for here (⌘R, the command bar) runs: Today's brass ring turns (motion pass 2, slice 3).
    private(set) var syncRingShowing = false

    func syncNow() async {
        guard !syncRingShowing else { return }
        syncRingShowing = true
        defer { syncRingShowing = false }
        let began = Date()
        _ = try? await core?.syncNow()
        // At least one turn, so a quick sync is still seen.
        let hold = MotionMath.ringHold(elapsed: Date().timeIntervalSince(began))
        if hold > 0 { try? await Task.sleep(for: .seconds(hold)) }
    }

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
    /// Mac database encryption (slice 2b): encrypts a plain database once, before the core opens it. The key lives
    /// only as a sealed file; SQLCipher is checked on throwaway files first, so a key never reaches a plain SQLite.
    private static func prepareDatabase() -> DatabaseEncryption.Prepared {
        let directory = databaseDirectory()
        let key = DeviceIdentity.databaseKey()
        var ready = false
        if key != nil {
            let probe = MacCoreFactory.shared.databaseEncryptionProbe(directory: directory)
            ready = probe.cipherVersion != nil && probe.opensWithKey && probe.fileUnreadable && probe.refusedWithoutKey
        }
        return DatabaseEncryption.prepare(
            directory: URL(fileURLWithPath: directory, isDirectory: true),
            name: "meka.db",
            key: key,
            sqlcipherReady: ready,
            opensWithKey: { dir, name, hex in MacCoreFactory.shared.databaseOpensWithKey(directory: dir, name: name, keyHex: hex) }
        )
    }

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

/// An answer as Ask shows it on the Mac: its lines and cards, or the line saying why there is none.
struct AskReplyView {
    let lines: [String]
    let cards: [AskCard]
    let unavailable: String?
    /// Earned trust: what MEKA already did on its own for this answer, each with Undo.
    var did: [AskAutoDone] = []
}

/// Ask cards taken back with Undo: `n` counts, `cards` are their places in the answer.
struct AskUndone: Equatable {
    let n: Int
    let cards: Set<Int>
}

/// What Meka pressed on a request card.
enum RequestAnswer { case add, change, decline }

/// What the calendar-action undo bar offers.
/// Equal when it is the same offer (each has its own id; its message and action never change). `Action` isn't
/// Equatable: `talk` carries the core's `AskUndo`, a Kotlin sealed interface that Swift sees as a protocol.
struct EventUndoOffer: Identifiable, Equatable {
    static func == (a: EventUndoOffer, b: EventUndoOffer) -> Bool { a.id == b.id && a.message == b.message }

    enum Action {
        case deleteTask(String)
        /// Delete in the task detail: the task comes back.
        case restoreTask(String)
        case showEvent(String)
        /// "Make it a task" on an all-day entry: the task goes, the entry comes back.
        case unmakeTask(String)
        /// "alarm 6:30" / "timer 20 min" typed into capture: Undo cancels it.
        case cancelAlarm(String)
        /// "Hide <calendar> from Today": the calendar (by key) is back on Today.
        case showCalendar(String)
        case reminder(String, Int32)
        case leaveBy(String, Int32)
        /// "Leave by 09:15 · as last time" on a club fixture: its travel time and alarm come off again.
        case lastLeaveBy(String)
        /// A habit ticked from its dot on the watch face: back to how it was.
        case habit(String, Bool)
        /// "How did it go?" on a club fixture: what was kept before comes back.
        case matchResult(MatchSaved)
        /// "Whose is it?" on a club session: whoever was set for its series before comes back.
        case clubChild(ClubChildSaved)
        /// Done / Tomorrow from the Needs you stack.
        case decision(DecisionUndo)
        /// A card set aside with Later comes back to the front.
        case unsetAside(String)
        /// Add event (calendar editing): taken back inside the five seconds, so nothing is sent.
        case eventEdit(String)
        /// Plan my day's blocks on their way to the calendar (slice 2e): taken back; the tasks stay planned.
        case planBlocks([String])
        /// "Move to later": Undo puts the task back at its old time.
        case later(LaterMove)
        /// An Ask card (V1 AI layer, slice 3b): what it did is taken back; the Int is the card's place in the answer.
        case ask(AskDone, Int)
        /// A spoken yes (Talk to MEKA): everything it did is taken back, newest first; the Ints are the cards' places.
        case talk([AskUndo], [Int])
        /// Add or Change on a request card: the task, event edit or work-from-home day is taken back.
        case request(RequestDone)
        /// I'll work from home · Covered on a school day off: the question opens again, its work-from-home days go.
        case schoolCover(SchoolCoverDone)
        /// Booked on date night's week-before card: the card asks again.
        case dateNightBooked(Int64)
        /// Skip this one on date night's week-before card: the night is kept clear again.
        case dateNightSkipped(Int64)
        /// Caught up on the group digest: its cards come back on both devices.
        case groupDigest(GroupDigestUndo)
        /// Went, Booked… or Not yet on an appointment: its visit, booking and set-aside day go back.
        case appointment(AppointmentDone)
        /// Call to book on an appointment (slice 2b): the task goes and the card asks again as before.
        case appointmentCall(AppointmentCall)
        /// Remove on a checklist item: it comes back with its tick and place.
        case restoreStep(String)
        /// Untick all on a checklist: these are ticked again.
        case tickSteps([String])
        /// Start from template: the items it added come off again.
        case removeSteps([String])
        /// Save as template (a new one): it is removed again.
        case removeTemplate(String)
        /// Remove on a saved template: it comes back.
        case restoreTemplate(ChecklistTemplate)
    }

    let id = UUID()
    let message: String
    let action: Action?
}
