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
    /// Activity → Calls (call notifications, slice 4): recent calls with their detail, numbers as numbers on the Mac.
    private(set) var callLog: CallLogView?
    var showAfterWork = false
    /// "Archived for you" shows which emails, slice 3: All tidied mail, opened from the card.
    var showArchivedAll = false
    /// Requests from people Meka watches (V1, requests slice 4): the Fold read them, the cards sync; Add · Change ·
    /// Not a task here clears them there too.
    private(set) var requests: [RequestCard] = []
    /// The approvals inbox (V1, slice 1): requests to trade, pay or share a document, first in Needs you. Approve asks
    /// for Touch ID every time; synced with the Fold.
    private(set) var approvals: [ApprovalCard] = []
    /// School rhythm (V1, slice 1): Theo's and Finn's days off, dates and weekly things (Ask → More → School), and the
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
    /// "Leaving now" (V1): the draft to his wife when an office day's work ends; set here or on the Fold.
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
    /// Emergency card). Synced with the Fold; his wife reads it on her family page.
    private(set) var emergency: EmergencyView?
    /// Ask → More → Emergency card.
    var showEmergency = false
    /// Kids' admin (slice 1): a card per child from About you with their sizes and when they were checked. Synced.
    private(set) var kids: KidsView?
    var showKids = false
    /// Money manager (slice 1): locked until Touch ID opens it; the month's spending by category. Synced.
    private(set) var money: MoneyView?
    /// Taps feel instant for Money's payment chips: the core's own view, and the taps on their way, drawn over it in the
    /// same frame ([InstantPaymentRules]); a tap is forgotten when its call returns.
    @ObservationIgnored private var coreMoney: MoneyView?
    @ObservationIgnored private var paymentTaps: [PaymentTap] = []
    var showMoney = false
    /// About you (rule 13, slice 1): the household profile, synced with the Fold; kept in his data, not in code.
    private(set) var aboutYou: AboutYouView?
    /// Ask → Settings → About you.
    var showAboutYou = false
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
    /// Reading and learning (slice 1): books, podcasts and courses, on the go, up next and finished lately. Synced.
    private(set) var reading: ReadingView?
    /// Ask → Daily → Reading.
    var showReading = false
    /// Goals → the Fasting tile (Fasting rebuild slice 3): the Fasting sheet in the approved design.
    var showFasting = false
    /// Voice notes into actions, slice 3: the voice note sheet (recording one) and Ask → Daily → Voice notes.
    var showVoiceNote = false
    var showVoiceNotes = false
    /// The Sunday week ahead (V1, slice 1): the coming week day by day and the family sync (Ask → More → Week ahead,
    /// and Today's "Your week ahead · …" line on Sunday from 18:00). Read-only.
    private(set) var weekAhead: WeekAheadView?
    var showWeekAhead = false
    /// Slice 2: Sunday's heads-up on or off and its time (synced with the Fold).
    private(set) var weekAheadSetting: WeekAheadSettingView?
    /// Calmer Today, slice 1: how Today shows the day's done tasks (Folded by default · Hidden · Shown) and whether the
    /// fold is open today; synced with the Fold.
    private(set) var doneTodayChoice: DoneTodayChoiceView = DoneTodayChoiceView.companion.DEFAULT
    /// A click's choice shown at once, until the core's view says the same (taps feel instant).
    private var doneTodayPickedShow: DoneShow?
    private var doneTodayTappedOpen: Bool?
    /// What Today and Appearance draw: the core's choice with any click still on its way laid over it.
    var doneTodayShown: DoneTodayChoiceView {
        DoneTodayChoiceView(show: doneTodayPickedShow ?? doneTodayChoice.show,
                            openToday: doneTodayTappedOpen ?? doneTodayChoice.openToday)
    }
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
    /// Two cars: every car, first car first.
    private(set) var cars: CarsView?
    /// Car and parking, slice 2: each car's parking timer (synced; no location in it).
    private(set) var parkingTimers: TimersView?
    /// Ask → More → Bins.
    var showBins = false
    /// Car and parking, slice 2: Ask → Life → Car (the parking timer; the dates open Lists).
    var showCar = false
    /// Ask's Life grid (Fold review 2026-10-12, item 7): each tile's live line ("Chilli tonight", "Next Fri 23 Oct").
    private(set) var lifeLines: LifeLines?
    /// Ask's Daily rows (Ask polish, item 3): each row's live line ("Tomorrow 06:40 · 5 things", "2 saved").
    private(set) var dailyLines: DailyLines?
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
    /// Trip lists (slice 1): each trip's To buy, To pack and Before we leave, by trip id.
    private(set) var tripLists: [TripListsView] = []
    /// Study plan (slice 1): the courses under Goals, with their modules and lessons.
    private(set) var study: StudyView?
    /// Ask → More → Trips.
    var showTrips = false
    /// The Vault (V2, slice 1): the documents and when they run out.
    private(set) var documents: DocumentsView?
    /// The Vault key (Vault slice 2a): its id and where it is, devices asking for it, or what this Mac waits for.
    private(set) var vaultKey: VaultKeyView?
    /// Why a Booked… on a Needs you card couldn't be read, by appointment id (shown under its field).
    private(set) var appointmentRefusal: (id: String, line: String)?
    /// Annual leave (V1, slice 1): the allowance, the days booked and the school holidays (Ask → More → Annual leave).
    /// Synced with the Fold; booked days are days off for work mode on both apps.
    private(set) var leave: LeaveView?
    /// Ask → More → Annual leave.
    var showLeave = false
    /// Work wins (V1, slice 1): the reviewed week's wins and this quarter's, shown in Review. Synced with the Fold.
    private(set) var wins: WinsView?
    /// Balance view (V2, slice 1): the reviewed week across work, family, health and self against his targets. Synced.
    private(set) var balance: BalanceView?
    /// Weekly reflection (V1, slice 1): the review week's look back and the journal, shown in Review. Synced with the Fold.
    private(set) var reflection: ReflectionView?
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
    /// Every triaged message as the core holds it; `triage` is these less the ones just decided on this Mac.
    private var allTriage: [TriageCard] = []
    /// Taps feel instant (Meka 2026-10-25 01:45): message and card ids decided on this Mac, hidden at once while the
    /// core's write follows; forgotten once the views no longer hold them, back with a line if the work fails.
    private(set) var decidedHere: Set<String> = []
    /// The same cards, one per conversation (messages grouped per chat, slice 1).
    /// A person's requests ride inside their chat's card (messages grouped per chat, slice 3).
    /// A group's digest chatter rides in its chat's card, shown once (slice 3b).
    var triageChats: [TriageChat] {
        // A busy chat's one-line summary rides on its card (slice 3c), made on the Fold and synced.
        ChatSummaryRules.shared.withSummaries(
            // Likely-scam texts are kept out (Mac presentation pass, item 6): they sit greyed under LIKELY SCAMS.
            chats: TriageChatRules.shared.withGists(chats: TriageChatRules.shared.chatsWith(cards: NeedsYouCardRules.shared.shown(cards: triage), requests: requests), gists: groupGists),
            summaries: chatSummaries
        )
    }
    /// Texts the AI judged a scam (Mac presentation pass, item 6): out of Needs you, greyed at its foot with Delete/Block.
    var scamTriage: [TriageCard] { NeedsYouCardRules.shared.scams(cards: triage) }
    /// Busy chats' one-line summaries (messages grouped per chat, slice 3c): made on the Fold, never the messages.
    private(set) var chatSummaries: [ChatSummary] = []
    /// The digest's cards no chat carries: GroupGistsView shows only these (slice 3b).
    var digestGists: [GroupGist] { TriageChatRules.shared.gistsOutside(chats: triageChats, gists: groupGists) }
    /// The requests no chat carries: Needs you shows these as cards of their own.
    var looseRequests: [RequestCard] { TriageChatRules.shared.looseRequests(chats: triageChats, requests: requests) }
    /// The group digest (V1, messages slice 4b): one card per busy group at the latest digest time, made on the Fold
    /// (its name, how many messages, who wrote, MEKA's gist; never the messages). Caught up here clears it there too.
    private(set) var groupGists: [GroupGist] = []
    /// Inbox autopilot, slice 4: what Autopilot archived, shown with the 12:30 and 18:30 digests until Got it.
    private(set) var archivedForYou: ArchivedForYouCard?
    /// Taps feel instant, slice 20: the mail cards as the core holds them; the shown ones are these less what was just
    /// answered on this Mac (From your mail, Time to chase, the finds, Archived for you's Got it).
    private var allArchivedForYou: ArchivedForYouCard?
    private var allMailNeeds: MailNeedsCard?
    private var allMailChases: MailChaseCard?
    private var allMailFinds: MailFindCard?
    private var mailDecided: Set<String> = []
    /// The ids the bar's Undo brings back at once (the last mail answer offered with Undo).
    private var mailUndoIds: [String] = []
    /// Inbox autopilot, slice 5c: the weekly "Not opened in 30 days · unsubscribe from 11?", from Saturday 09:00.
    private(set) var mailUnopened: MailUnopenedCard?
    /// Inbox autopilot, slice 6a: "From your mail" — the emails that need Meka, each with Done and Snooze.
    private(set) var mailNeeds: MailNeedsCard?
    /// Inbox autopilot, slice 6b-3: "Time to chase" — Waiting for items from email whose chase is due, each drafted.
    private(set) var mailChases: MailChaseCard?
    /// Promises, receipts and renewals, slice 1: "Renewals from your mail" — renewals to add, each only on his tap.
    private(set) var mailFinds: MailFindCard?
    /// Discord channel watch, slice 1: one card per channel he follows with the posts the Fold kept, each with Done.
    private(set) var discord: DiscordCards?
    /// Discord channel watch, slice 1: Work → Discord's channels (from the Fold's notifications), each Follow / Stop.
    private(set) var discordWatch: DiscordWatchView?
    /// Inbox autopilot, slice 7: the ten-minute inbox's card in Needs you (from the moment while emails need him).
    private(set) var mailMoment: MailMomentCard?
    /// Slice 7: the ten-minute inbox under way on this Mac (one email at a time), or nil.
    private(set) var mailMomentSession: MailMomentSession?
    /// Slice 7: when the ten-minute inbox comes, as its key ("morning", "evening", "off"); nil until read.
    private(set) var mailMomentKey: String?
    /// Slice 3f: Warranty reminders' threshold as its key ("off", "50", "100", "250", "500"); nil until read.
    private(set) var mailWarrantyKey: String?
    /// Meeting prep, slice 2: how early the prep comes ("off", "5", "10", "15", "30"); the Mail sheet's chips.
    private(set) var meetingPrepLeadKey: String?
    /// Follow-up radar, slice 4: Mail → Chase waits (the people he set his own chase wait for); nil until read.
    private(set) var followUpWaits: FollowUpWaitsPanel?
    /// Life audit, slice 2: Mail → Life audit, counted from the synced data (nil while no mail is connected).
    private(set) var lifeAudit: LifeAuditReview?
    /// Slice 4: whether the Mail sheet offers Undo on the last Chase waits change.
    private(set) var followUpWaitUndo = false
    /// Waiting for, Someday and Decisions, with what is due to chase or review today. Synced with the Fold.
    private(set) var lists: ListsView?
    /// Needs you as a stack of decisions (four tabs, slice 2); see `needsYouCards`.
    private(set) var needsYouStack: NeedsYouStack?
    /// Cards set aside with "Later" on this Mac (nothing is written): they go to the back of the stack.
    private(set) var needsYouSetAside: [String] = []
    /// Habits (pace, streaks) and goals (progress). Synced with the Fold.
    private(set) var goals: GoalsView?
    /// Taps feel instant for habits: the core's own goals, and the ticks on their way (habit id → done), drawn over it
    /// in the same frame ([InstantHabitRules]); a tick is forgotten when its call returns.
    @ObservationIgnored private var coreGoals: GoalsView?
    @ObservationIgnored private var habitTaps: [String: KotlinBoolean] = [:]
    /// The Gym (booked habits): the week's sessions booked around the calendar and work, and today's card.
    private(set) var sessions: SessionsView?
    /// The running fast, the eating window and the last seven days. Synced with the Fold.
    private(set) var fasting: FastingView?
    /// Evening shutdown: done today, left from today, tomorrow at a glance. Synced with the Fold.
    private(set) var shutdown: ShutdownView?
    /// Taps feel instant for task moves (InstantTaskRules): the core's own shutdown, and the moves on their way
    /// (task id → move), drawn over it and over the brief's rows in the same frame; a move is forgotten when its call returns.
    @ObservationIgnored private var coreShutdown: ShutdownView?
    private(set) var taskTaps: [String: TaskMove] = [:]
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
    /// Calendar's Board view (Week board, slice 2): a column per day for five weeks, each day's tasks in his order.
    private(set) var board: WeekBoard?
    /// Taps feel instant, slice 13: the core's own board, and the Board taps waiting for it (card id → tap), drawn over
    /// it in the same frame ([InstantBoardRules]); a tap is forgotten when its call returns.
    @ObservationIgnored private var coreBoard: WeekBoard?
    @ObservationIgnored private var boardTaps: [String: BoardTap] = [:]
    /// Which view the Calendar section shows: the agenda (false) or the Board; kept while the app runs.
    var calendarShowsBoard = false
    /// Plan your week's sheet (Week board item (6)): from the Board's header, Sunday's Needs you card or "plan my week".
    var showPlanWeek = false
    /// The card picked on the Board (a click), which ⌘← ⌘→ ⌘↑ ⌘↓ move.
    var boardSelectedID: String?
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
    /// Ask → Settings → Speaking up (the JARVIS step, slice 1).
    var showSpeakUp = false
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
    /// A help entry found by Search or Ask's field: Ask opens where it lives (Take me there); cleared once opened.
    var openHelpPlace: HelpPlace?
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
    /// Clean-ups (2026-10-19): while this new Mac waits for Allow on the household's phone or other Mac, the code to
    /// compare there and when the ask runs out (plain values); nil otherwise.
    private(set) var joinWaiting: (code: String, expiresAtMs: Int64)?
    var showConnect = false
    var showCalendars = false
    /// Ask → More → Settings → Mail (Inbox autopilot, slice 1): connect Gmail and Outlook, label each account.
    var showMail = false
    /// Review opens its own list (Meka, 2026-10-17), slice 1: the Empty Junk card's review sheet (who, ticks, Approve).
    var showJunkReview = false
    private(set) var junkReview: JunkReview?
    private(set) var junkReviewCardId: String?
    /// The mail accounts as the server last listed them; nil until read once (or when it couldn't be reached).
    private(set) var mail: MailAccountsView?
    /// The server couldn't be reached the last time Mail asked.
    private(set) var mailOffline = false
    /// A quiet line under the accounts (what the provider will ask, or the new label).
    var mailNote: String?
    var mailMessage: String?
    /// Ask → More → Family (family sharing slice 4): his wife's link to the shopping list.
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
        // Read the lazy key here, not inside the closure (Swift wants self explicit there; nightly ci run 551).
        let syncDeviceKey: MacDeviceKey? = syncURL == nil ? nil : deviceKey
        let open = { () throws -> MekaCore in
            try MacCoreFactory.shared.openCore(
                householdId: identity.householdID,
                deviceId: identity.deviceID,
                syncUrl: syncURL,
                deviceSecret: identity.deviceSecret,
                databaseKeyHex: database.keyHex,   // slice 2b: SQLCipher with a sealed key (ADR-002)
                encrypted: database.encrypted,
                databaseDirectory: Self.databaseDirectory(),
                databaseName: "meka.db",
                deviceKey: syncDeviceKey
            )
        }
        let core: MekaCore
        do {
            core = try open()
        } catch {
            // The file won't open (a wrong key, damage): keep it aside, never delete it, start afresh and re-sync.
            let aside = DatabaseEncryption.setAsideUnopenable(
                directory: URL(fileURLWithPath: Self.databaseDirectory(), isDirectory: true), name: "meka.db"
            )
            do {
                core = try open()
                lastError = aside ?? "Navilife's database wouldn't open, so Navilife started afresh."
            } catch {
                lastError = "Navilife's database won't open: \(error.localizedDescription)"
                return
            }
        }
        self.core = core
        // Part (4): times taken before the core opened (none yet, usually) go in now.
        for f in feelWaiting { core.noteFeel(moment: f.moment, ms: f.ms, place: f.place) }
        feelWaiting = []
        // Ask knows how Navilife itself works: help answers give the Mac's paths and open the Mac's sheets.
        core.helpOnMac = true
        // The Vault, slice 2a: the Secure Enclave agreement key and CryptoKit hold the household's Vault key.
        core.vaultCrypto = MacVaultCrypto()
        // Trading approvals, slice 2: this Mac's Touch ID-only approval key is known (synced) before the first yes, so
        // Kestrel can pin it. Nothing without Touch ID and a Secure Enclave.
        if MacBiometric.available, let approvalKey = MacApprovalKey.publicKey() {
            run { _ = try await $0.registerApprovalKey(publicKeyBase64: approvalKey, label: MacApprovalKey.label) }
        }
        observers.append(Task { [weak self] in
            for await t in core.today { self?.today = t; self?.todayFirstShown() }
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
            for await a in core.archivedForYou { self?.allArchivedForYou = a; self?.showMailCards() }
        })
        observers.append(Task { [weak self] in
            for await u in core.mailUnopened { self?.mailUnopened = u }
        })
        observers.append(Task { [weak self] in
            for await n in core.mailNeeds { self?.allMailNeeds = n; self?.showMailCards() }
        })
        observers.append(Task { [weak self] in
            for await c in core.mailChases { self?.allMailChases = c; self?.showMailCards() }
        })
        observers.append(Task { [weak self] in
            for await f in core.mailFinds { self?.allMailFinds = f; self?.showMailCards() }
        })
        observers.append(Task { [weak self] in
            for await d in core.discord { self?.discord = d }
        })
        observers.append(Task { [weak self] in
            for await w in core.discordWatch { self?.discordWatch = w }
        })
        observers.append(Task { [weak self] in
            for await m in core.mailMoment { self?.mailMoment = m }
        })
        observers.append(Task { [weak self] in
            for await m in core.mailMomentSession { self?.mailMomentSession = m }
        })
        observers.append(Task { [weak self] in
            for await b in core.blockedCallers { self?.blockedCallers = b }
        })
        observers.append(Task { [weak self] in
            for await c in core.callLog { self?.callLog = c }
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
            for await d in core.dailyLines { self?.dailyLines = d }
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
            for await l in core.tripListsView { self?.tripLists = l }
        })
        observers.append(Task { [weak self] in
            for await v in core.studyView { self?.study = v }
        })
        observers.append(Task { [weak self] in
            for await d in core.documentsView { self?.documents = d }
        })
        observers.append(Task { [weak self] in
            for await k in core.vaultKeyView { self?.vaultKey = k }
        })
        observers.append(Task { [weak self] in
            for await c in core.carView { self?.car = c }
        })
        observers.append(Task { [weak self] in
            for await c in core.carsView { self?.cars = c }
        })
        observers.append(Task { [weak self] in
            for await t in core.parkingTimersView { self?.parkingTimers = t }
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
            for await c in core.doneTodayChoice {
                guard let self else { continue }
                self.doneTodayChoice = c
                if self.doneTodayPickedShow == c.show { self.doneTodayPickedShow = nil }
                if self.doneTodayTappedOpen == c.openToday { self.doneTodayTappedOpen = nil }
            }
        })
        observers.append(Task { [weak self] in
            for await a in core.autonomyView { self?.autonomy = a }
        })
        observers.append(Task { [weak self] in
            for await e in core.emergencyView { self?.emergency = e }
        })
        observers.append(Task { [weak self] in
            for await k in core.kidsView { self?.kids = k }
        })
        observers.append(Task { [weak self] in
            for await m in core.moneyView { self?.coreMoney = m; self?.showMoneyView(); self?.moneyFirstShown() }
        })
        observers.append(Task { [weak self] in
            for await a in core.aboutYouView { self?.aboutYou = a }
        })
        observers.append(Task { [weak self] in
            for await f in core.factsView { self?.facts = f }
        })
        observers.append(Task { [weak self] in
            for await q in core.queueView { self?.queue = q }
        })
        observers.append(Task { [weak self] in
            for await r in core.readingView { self?.reading = r }
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
            for await b in core.balanceView { self?.balance = b }
        })
        observers.append(Task { [weak self] in
            for await r in core.reflectionView { self?.reflection = r }
        })
        observers.append(Task { [weak self] in
            for await t in core.triage {
                guard let self else { return }
                self.allTriage = t
                self.settleDecided()
            }
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
            for await n in core.needsYouStack {
                guard let self else { return }
                self.needsYouStack = n
                self.settleDecided()
            }
        })
        observers.append(Task { [weak self] in
            for await g in core.goalsView { self?.coreGoals = g; self?.showGoals() }
        })
        observers.append(Task { [weak self] in
            for await f in core.fastingView { self?.fasting = f }
        })
        observers.append(Task { [weak self] in
            for await s in core.sessionsView { self?.sessions = s }
        })
        observers.append(Task { [weak self] in
            for await s in core.shutdownView { self?.coreShutdown = s; self?.showShutdownView() }
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
            for await b in core.weekBoard { self?.coreBoard = b; self?.showBoard() }
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
            var outcome = result
            if case .waiting(let w) = onEnum(of: result) {
                // Clean-ups: a new Mac waits for Allow on the phone; only plain values cross back to the core.
                let requestId = w.requestId, joinCode = w.code, expires = w.expiresAtMs
                joinWaiting = (joinCode, expires)
                defer { joinWaiting = nil }
                outcome = try await MacCoreFactory.shared.awaitApproval(
                    serverUrl: serverURL, enrolCode: code, requestId: requestId, code: joinCode, expiresAtMs: expires
                )
            }
            switch onEnum(of: outcome) {
            case .enrolled(let e):
                let url = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
                let secret = e.deviceSecret
                DeviceIdentity.saveEnrolment(serverURL: url, secret: secret)
                try await MacCoreFactory.shared.connect(core: core, serverUrl: url, deviceSecret: secret, deviceKey: deviceKey)
                isConnected = true
                return nil
            case .rejected: return "That enrolment code wasn't accepted."
            case .failed(let f): return f.reason
            case .waiting: return DeviceJoinRules.shared.EXPIRED_LINE
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
            let secret = outcome.secret, problem = outcome.problem, vaultRecovery = outcome.vaultRecovery
            guard let secret else { return problem ?? "Couldn't connect" }
            DeviceIdentity.saveEnrolment(serverURL: url, secret: secret)
            // The Vault, slice 2a: the words' key opens the Vault's recovery seal once it syncs.
            try await core.rememberVaultRecovery(key: vaultRecovery)
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
        guard let core else { foldUpdateOutcome = "Navilife is still starting. Try again in a moment."; return }
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
        panel.message = "Save everything in Navilife as one JSON file. It isn't encrypted."
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
        switchAt = ProcessInfo.processInfo.systemUptime
        lastDirection = ShellNav.direction(from: destination, to: d)
        MekaHaptics.tick()
        withAnimation(MekaMotion.replan(reduced: reduced)) { destination = d }
    }

    // MARK: How it feels (taps feel instant, part 4)

    /// When a sidebar row (or ⌘1–⌘8) asked for a screen, until that screen appears.
    @ObservationIgnored private var switchAt: TimeInterval?
    @ObservationIgnored private var coldStartNoted = false
    @ObservationIgnored private var feelWaiting: [(moment: FeelMoment, ms: Int64, place: String?)] = []

    /// A time for Health → Speed (times only, kept in memory on this Mac). Before the core opens it waits here.
    func noteFeel(_ moment: FeelMoment, ms: Int64, place: String?) {
        guard let core else { feelWaiting.append((moment, ms, place)); return }
        core.noteFeel(moment: moment, ms: ms, place: place)
    }

    /// The moments as a Swift value, so only plain values cross into the deferred task (never a Kotlin object).
    private enum FeelKind: Sendable {
        case coldStart, tab, screen
        var moment: FeelMoment {
            switch self {
            case .coldStart: .coldStart
            case .tab: .tab
            case .screen: .screen
            }
        }
    }

    /// [kind] since [since] (system uptime), measured once the current frame has gone to the screen.
    private func noteFeelAfterFrame(_ kind: FeelKind, since: TimeInterval, place: String?) {
        Task { @MainActor [weak self] in
            await Task.yield()
            let ms = Int64(max(ProcessInfo.processInfo.systemUptime - since, 0) * 1000)
            self?.noteFeel(kind.moment, ms: ms, place: place)
        }
    }

    /// The shell's screen [d] appeared: a switch asked for is timed against its aim (150 ms).
    func switchShown(_ d: ShellDestination) {
        guard let at = switchAt, d == destination else { return }
        switchAt = nil
        noteFeelAfterFrame(.tab, since: at, place: d.label)
    }

    /// Today's first content after launch: the cold start, timed once (aim 1.5 s). Only when Today is the screen.
    private func todayFirstShown() {
        guard !coldStartNoted else { return }
        coldStartNoted = true
        guard destination == .today else { return }
        noteFeelAfterFrame(.coldStart, since: MacLaunchClock.launchedAt, place: nil)
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

    /// "Shut down the day" said to Talk (Done for the day, completeness 2): Today opens with the shutdown sheet up,
    /// as the evening card does; the day is shut down only by Shut down there.
    func openShutdownFromTalk() {
        go(to: .today, reduced: MotionSetting.reduced)
        showBrief = false
        showPlan = false
        showShutdown = true
    }

    /// "Plan my week" said to Obi, or Plan now on Sunday's Needs you card (Week board item (6) part 2): Calendar opens
    /// on the Board with Plan your week up; nothing moves until Accept plan there.
    func openPlanWeek() {
        go(to: .calendar, reduced: MotionSetting.reduced)
        showBrief = false
        calendarShowsBoard = true
        showPlanWeek = true
    }

    /// "Take me there" said to Talk after a help answer (Ask knows how Navilife works, slice 3): Ask comes forward and
    /// opens the sheet or place, as the answer's Take me there does (AskScreen takes it on appear or on change).
    func openHelpFromTalk(_ place: HelpPlace) {
        go(to: .ask, reduced: MotionSetting.reduced)
        openHelpPlace = place
    }

    /// "Clear my inbox" said to Talk (Inbox autopilot, slice 7b): Needs you comes forward and the ten-minute inbox starts,
    /// as its Start does (nothing is archived or sent but by his own clicks there).
    func openInboxFromTalk() {
        go(to: .needsYou, reduced: MotionSetting.reduced)
        startMailMoment()
    }

    /// "Reflect on my week" said to Talk (weekly reflection, slice 2): Review comes forward on the week Today's card is
    /// about with its reflection open and lit (the core keeps it open until Save); nothing crosses but the call.
    func openReflectionFromTalk() {
        run { try await $0.openReflection() }
        go(to: .review, reduced: MotionSetting.reduced)
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
        if hit.target == .help {
            // Ask knows how Navilife itself works, slice 2: Ask opens the sheet or place once the search sheet has gone.
            let place = hit.place
            go(to: d, reduced: reduced)
            Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(reduced ? 50 : 350))
                self?.openHelpPlace = place
            }
            return
        }
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

    // MARK: Mail (Inbox autopilot, slice 1)

    func loadMail() async {
        guard let core else { return }
        let view = try? await core.mailAccounts()
        mailOffline = view == nil
        if let view { mail = view }
    }

    /// Opens Google's or Microsoft's sign-in page in the default browser; the server finishes the connection.
    func connectMail(_ provider: String, note: String) async {
        guard let core else { return }
        MekaHaptics.light()
        mailMessage = nil
        mailNote = note
        do {
            switch onEnum(of: try await core.startConnect(provider: provider)) {
            case .openBrowser(let o):
                if let url = URL(string: o.url) { NSWorkspace.shared.open(url) }
            case .notSetUp:
                mailMessage = "Mail isn't set up on your server yet. Finish the registration steps, then try again."
            case .failed(let f):
                mailMessage = f.reason
            }
        } catch {
            mailMessage = error.localizedDescription
        }
    }

    /// Names what an account is for (Personal · Business · Outlook); only Strings and the label cross to the core.
    func setMailLabel(provider: String, email: String, label: MailLabel) async {
        guard let core else { return }
        MekaHaptics.tick()
        mailMessage = nil
        if let now = try? await core.setMailLabel(provider: provider, email: email, label: label) {
            mail = now
            mailOffline = false
            mailNote = MailAccountRules.shared.labelledLine(label: label)
        } else {
            mailMessage = MailAccountRules.shared.OFFLINE
        }
    }

    /// Disconnects a mail account (slice 1b): the server forgets its sign-in; nothing in the mailbox changes.
    /// `deleteData` (completeness: Delete mail data) also clears what Navilife made from that mail on both apps.
    /// Only the two Strings cross to the core.
    func disconnectMail(provider: String, email: String, deleteData: Bool = false) async {
        guard let core else { return }
        MekaHaptics.medium()
        mailMessage = nil
        let now: MailAccountsView?
        if deleteData {
            now = try? await core.disconnectMailAndDeleteData(provider: provider, email: email)
        } else {
            now = try? await core.disconnectMail(provider: provider, email: email)
        }
        if let now {
            mail = now
            mailOffline = false
            mailNote = deleteData
                ? MailAccountRules.shared.deletedLine(provider: provider, email: email)
                : MailAccountRules.shared.disconnectedLine(provider: provider, email: email)
        } else {
            mailMessage = MailAccountRules.shared.DISCONNECT_FAILED
        }
    }

    /// Inbox autopilot, slice 3b-2: Mail's switch (synced; the server tidies only while it's on). Only the Bool
    /// crosses to the core; the line and the pane come back in one value.
    func setMailAutopilot(_ on: Bool) async {
        guard let core else { return }
        MekaHaptics.medium()
        mailMessage = nil
        guard let r = try? await core.setMailAutopilot(on: on) else { return }
        mailNote = r.line
        if let v = r.view { mail = v } else { await loadMail() }
    }

    /// Inbox fixes, slice 5: "Mark tidied mail as read" (synced; the server follows it from its next check). Only the Bool crosses.
    func setMailMarkRead(_ on: Bool) async {
        guard let core else { return }
        MekaHaptics.tick()
        mailMessage = nil
        guard let r = try? await core.setMailMarkRead(on: on) else { return }
        mailNote = r.line
        if let v = r.view { mail = v } else { await loadMail() }
    }

    /// Read the full email, slice 3: "Mark an email read when I open it" (synced; off unless he turns it on). Only the Bool crosses.
    func setMailReaderMarksRead(_ on: Bool) async {
        guard let core else { return }
        MekaHaptics.tick()
        mailMessage = nil
        guard let r = try? await core.setMailReaderMarksRead(on: on) else { return }
        mailNote = r.line
        if let v = r.view { mail = v } else { await loadMail() }
    }

    /// Undo on one of Autopilot's tidies: the server puts the emails back at its next check. Only the id crosses.
    func undoMailTidy(_ id: String) async {
        guard let core else { return }
        MekaHaptics.light()
        mailMessage = nil
        guard let r = try? await core.undoMailTidy(id: id) else { return }
        mailNote = r.line
        if let v = r.view { mail = v } else { await loadMail() }
    }

    /// Unsubscribe on one newsletter or promo sender: the server does it at its next check. Only the id crosses.
    func unsubscribeMailSender(_ id: String) async {
        guard let core else { return }
        MekaHaptics.medium()
        mailMessage = nil
        guard let r = try? await core.unsubscribeMailSender(id: id) else { return }
        mailNote = r.line
        if let v = r.view { mail = v } else { await loadMail() }
    }

    /// Inbox autopilot, completeness: Keep in inbox / Let Autopilot decide on one sender (synced).
    func setMailSenderRule(_ id: String, rule: String) async {
        guard let core else { return }
        MekaHaptics.tick()
        mailMessage = nil
        guard let r = try? await core.setMailSenderRule(id: id, rule: rule) else { return }
        mailNote = r.line
        if let v = r.view { mail = v } else { await loadMail() }
    }

    /// Inbox autopilot, slice 8b: Approve (or Cancel, before anything moved) on an account's backlog clean-up.
    func approveMailBacklog(_ id: String, approve: Bool) async {
        guard let core else { return }
        if approve { MekaHaptics.medium() } else { MekaHaptics.light() }
        mailMessage = nil
        let result: MailSwitched?
        if approve { result = try? await core.approveMailBacklog(id: id) } else { result = try? await core.unapproveMailBacklog(id: id) }
        guard let r = result else { return }
        mailNote = r.line
        if let v = r.view { mail = v } else { await loadMail() }
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
    func deleteEvent(_ event: CalendarEvent, guestsOk: Bool, everyEvent: Bool = false, fromThisOne: Bool = false) async -> String? {
        guard let core else { return "Connect this Mac to your server first." }
        // Worked out before the event is handed to the core, which then owns it.
        let fallback = core.deletingLine(event: event, everyEvent: everyEvent, fromThisOne: fromThisOne)
        MekaHaptics.light()
        do {
            // Completeness slice 1: everyEvent is the whole repeating series (the Edit sheet's choice); slice 2: fromThisOne
            // is this event and the ones after it.
            switch onEnum(of: try await core.deleteEvent(event: event, guestsOk: guestsOk, everyEvent: everyEvent, fromThisOne: fromThisOne)) {
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

    // MARK: Calendar editing, completeness slice 7: drag to move and keyboard shortcuts

    /// The event being dragged in the Calendar (set when the drag starts), so a day can say where it would go.
    var draggingEvent: CalendarEvent?

    /// A day took the drop: the dragged event moves there (after the five-second Undo). False with nothing dragged.
    func dropEvent(on day: Int64) -> Bool {
        guard let e = draggingEvent else { return false }
        draggingEvent = nil
        let id = e.id
        Task { await moveEventToDay(id, day: day) }
        return true
    }

    /// Whether an event may be dragged onto another day or nudged with ⌥⌘ and an arrow (pure, in the core).
    func canMoveEvent(_ event: CalendarEvent) -> Bool { core?.canMoveEvent(event: event) ?? false }

    /// Why it can't be (the pointer's help), or nil.
    func moveEventNote(_ event: CalendarEvent) -> String? { core?.moveEventNote(event: event) }

    /// "Move to Fri 23 Oct · 15:00" while it's held over a day.
    func moveEventHint(_ event: CalendarEvent, day: Int64) -> String { core?.moveEventHint(event: event, day: day) ?? "Move here" }

    /// Dropped on a day: it moves there keeping its time and length, after five seconds' Undo. Only the id (a String)
    /// crosses into the core; a refusal is said on the undo bar.
    func moveEventToDay(_ eventID: String, day: Int64) async {
        guard let core else { return }
        MekaHaptics.light()
        do {
            let result = try await core.moveEventToDay(eventId: eventID, day: day)
            await afterMove(eventID, result, core)
        } catch {
            offerEventUndo(error.localizedDescription, nil)
        }
    }

    /// ⌥⌘ and an arrow: a day earlier or later, or fifteen minutes, after five seconds' Undo. Only the id crosses.
    func moveEventBy(_ eventID: String, days: Int32, minutes: Int32) async {
        guard let core else { return }
        MekaHaptics.tick()
        do {
            let result = try await core.moveEventBy(eventId: eventID, days: days, minutes: minutes)
            await afterMove(eventID, result, core)
        } catch {
            offerEventUndo(error.localizedDescription, nil)
        }
    }

    /// Undo of a calendar edit; an open detail follows a keyboard move back to where it was (completeness slice 7).
    private func undoEventEdit(_ id: String) async {
        guard let core else { return }
        do { _ = try await core.undoEventEdit(id: id) } catch { lastError = error.localizedDescription; return }
        if let open = openEvent?.id, let now = try? await core.currentEvent(eventId: open) { openEvent = now }
    }

    private func afterMove(_ eventID: String, _ result: EventEditResult, _ core: MekaCore) async {
        switch onEnum(of: result) {
        case .made(let m):
            let id = m.id
            offerEventUndo(core.eventEditLine(id: id) ?? "Moving it in your calendar", .eventEdit(id))
            // An open detail follows the event to its new time.
            if openEvent?.id == eventID, let now = try? await core.currentEvent(eventId: eventID) { openEvent = now }
        case .refused(let r):
            offerEventUndo(r.reason, nil)
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
        // Speaking up (slice 1) rides the same call, so `result` crosses into the core once: at most one notice said
        // out loud, and only while he is using the Mac.
        let idleMs = Int64(CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: CGEventType(rawValue: UInt32.max)!) * 1000)
        let place = SpeakUpRules.shared.macPlace(idleMs: idleMs)
        let pick = try? await core.postedKeysThenSpeak(result: result, keys: postedKeys, place: place, onCall: false, talking: talkActive)
        if let line = pick?.line { speakUpNow(line) }
    }

    // MARK: Speaking up (the JARVIS step, slice 1)

    /// A Talk or Hello conversation is running (TalkController sets it): Obi never speaks up over it.
    var talkActive = false
    /// Obi is saying a moment on his own: the sidebar's orb shows Speaking.
    private(set) var speakingUp = false
    @ObservationIgnored private var speakUpSpeaker: MekaSpeaker?

    /// A soft chime, then the line in Obi's voice.
    private func speakUpNow(_ line: String) {
        let speaker = speakUpSpeaker ?? MekaSpeaker()
        if speakUpSpeaker == nil { speaker.attach(self); speakUpSpeaker = speaker }
        speakingUp = true
        let chime = NSSound(named: NSSound.Name("Glass"))
        chime?.volume = 0.35
        chime?.play()
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(450))
            speaker.say(line) { [weak self] in self?.speakingUp = false }
        }
    }

    /// A click on the orb while he speaks up: stop him.
    func stopSpeakingUp() {
        speakUpSpeaker?.stop()
        speakingUp = false
    }

    /// "shh" said to Obi in Talk (slice 2): nothing said out loud on any device for 2 hours; stops him if he's speaking up.
    func muteSpeakUp() async {
        stopSpeakingUp()
        guard let core else { return }
        try? await core.muteSpeakUp()
    }

    /// Ask → Settings → Speaking up.
    func speakUpView() async -> SpeakUpView? {
        guard let core else { return nil }
        return try? await core.speakUpView()
    }

    /// A section's button there (a moment's Turn on / Turn off, How often, Mute); the pane as it now reads.
    func speakUpAction(_ label: String) async -> SpeakUpView? {
        guard let core else { return nil }
        return try? await core.speakUpAction(label: label)
    }

    /// Whether macOS lets MEKA notify, as last checked (Health, the Notifications sheet); true until checked.
    private(set) var notificationsAllowed = true
    /// Send a test on this Mac: nil until run, then sending… / arrived ✓ / didn't show (MacNotifyRules).
    private(set) var notifyTest: NotifyTestView?

    /// Reads macOS's answer again (the Notifications sheet opening, after Turn on).
    func refreshNotificationsAllowed() async {
        notificationsAllowed = await MacNotifier.allowed()
    }

    /// Health's / the sheet's Turn on: asks macOS the first time, otherwise opens System Settings → Notifications.
    func turnOnNotifications() {
        MekaHaptics.tick()
        Task {
            if await MacNotifier.notAsked() {
                _ = await MacNotifier.requestPermission()
            } else {
                NSWorkspace.shared.open(MacNotifier.settingsURL)
            }
            await refreshNotificationsAllowed()
            await refreshHealth()
        }
    }

    /// Send a test: one notification now; the line says whether it arrived or what to check.
    func sendTestNotification() {
        MekaHaptics.tick()
        notifyTest = MacNotifyRules.shared.testSending()
        Task {
            let shown = await MacNotifier.postTest()
            notifyTest = MacNotifyRules.shared.testView(shown: shown)
            await refreshNotificationsAllowed()
            await refreshHealth()
        }
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
    /// Spam protection's switch (spam calls still ring, slice 1): always on unless turned off in Advanced. Synced.
    func setSpamProtection(_ on: Bool) { MekaHaptics.tick(); run { _ = try await $0.setSpamProtection(on: on) } }
    /// "Screen unknown callers all the time", synced with the Fold (which does the screening).
    func setScreenUnknownAlways(_ on: Bool) { MekaHaptics.tick(); run { _ = try await $0.setScreenUnknownAlways(on: on) } }
    /// "Keep callers' recordings" (call assistant polish 8c): 7 or 30 days, or 0 for don't keep. Synced; the server
    /// deletes older recordings at once. Only an Int32 crosses to the core.
    func setRecordingDays(_ days: Int) {
        MekaHaptics.tick()
        let d = Int32(days)
        run { try await $0.setRecordingDays(days: d) }
    }

    /// "Leaving now": on or off, who it goes to and the usual journey home. Only a Bool, a String and an Int32 cross.
    /// The door-to-door commute each way (the commute counts everywhere, slice 1), synced with the Fold. Only two
    /// Int32s cross.
    func setCommute(toWorkMin: Int, homeMin: Int) {
        MekaHaptics.tick()
        let t = Int32(toWorkMin), h = Int32(homeMin)
        run { _ = try await $0.setCommute(toWorkMin: t, homeMin: h) }
    }

    /// The commute, slice 6: one day's own door-to-door times (the usual times put it back).
    func setCommuteDay(epochDay: Int64, toWorkMin: Int, homeMin: Int) {
        MekaHaptics.tick()
        let t = Int32(toWorkMin), h = Int32(homeMin)
        run { _ = try await $0.setCommuteDay(epochDay: epochDay, toWorkMin: t, homeMin: h) }
    }

    func clearCommuteDay(epochDay: Int64) {
        MekaHaptics.tick()
        run { _ = try await $0.clearCommuteDay(epochDay: epochDay) }
    }

    func setLeavingNow(on: Bool, to: String, journeyMin: Int) {
        MekaHaptics.tick()
        let j = Int32(journeyMin)
        run { _ = try await $0.setLeavingNow(on: on, to: to, journeyMin: j) }
    }

    /// The week ahead's Sunday heads-up: on or off and its time. Only a Bool and an Int32 cross.
    /// Calmer Today, slice 1: Done tasks on Today, Folded · Hidden · Shown (synced). Shows at once; the write follows.
    func setDoneTodayShow(_ show: DoneShow) {
        MekaHaptics.tick()
        doneTodayPickedShow = show
        let code = show.code
        run { _ = try await $0.setDoneTodayShow(code: code) }
    }

    /// Opens the folded "2 done today" for the rest of today, or closes it. Shows at once; the write follows.
    func toggleDoneTodayFold() {
        let open = !doneTodayShown.openToday
        doneTodayTappedOpen = open
        run { _ = try await $0.setDoneTodayOpen(open: open) }
    }

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
        moveTask([id], .someday) { try await $0.moveToSomeday(taskId: id, kind: .idea) }
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

    /// Two cars: the same three for one car ([carId]); only Strings cross.
    @discardableResult
    func setCarRegistration(_ carId: String, _ text: String) -> String? {
        if let problem = CarRules.shared.registrationError(text: text) { return problem }
        MekaHaptics.light()
        run { _ = try await $0.setCarRegistration(carId: carId, text: text) }
        return nil
    }

    func useDvlaDate(_ carId: String, _ presetId: String) {
        MekaHaptics.light()
        run { _ = try await $0.useDvlaDate(carId: carId, presetId: presetId) }
    }

    func addCarDate(_ carId: String, _ presetId: String, opened: @escaping @MainActor (String) -> Void) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                if let id = try await core.addCarDate(carId: carId, presetId: presetId) { opened(id) }
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Two cars: names a car (blank clears it). Only Strings cross.
    func setCarName(_ carId: String, _ text: String) {
        MekaHaptics.light()
        run { _ = try await $0.setCarName(carId: carId, text: text) }
    }

    /// Car and parking, slice 2: starts a car's parking timer from typed text ("2 h", "until 15:30"); [said] gets the
    /// line, or nil when it didn't read. Only Strings cross each way.
    func setParkingTimer(_ carId: String, _ text: String, said: @escaping @MainActor (String?) -> Void) {
        guard let core else { return }
        Task {
            do {
                let line = try await core.setParkingTimer(carId: carId, text: text)
                if line != nil { MekaHaptics.light() } else { MekaHaptics.tick() }
                said(line)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// +15 min / +30 min / +1 h on a car's timer. A String and an Int32 cross; the line comes back.
    func extendParkingTimer(_ carId: String, _ minutes: Int32, said: @escaping @MainActor (String?) -> Void) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do { said(try await core.extendParkingTimer(carId: carId, minutes: minutes)) } catch { lastError = error.localizedDescription }
        }
    }

    /// Stop timer (Clear once ended): clears a car's timer ([undoEndParkingTimer] puts it back). Only Strings cross.
    func endParkingTimer(_ carId: String, said: @escaping @MainActor (String?) -> Void) {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            do { said(try await core.endParkingTimer(carId: carId)) } catch { lastError = error.localizedDescription }
        }
    }

    func undoEndParkingTimer(_ carId: String) {
        MekaHaptics.light()
        run { _ = try await $0.undoEndParkingTimer(carId: carId) }
    }

    /// Two cars: adds another car; [opened] gets its id so the sheet can unfold its row. Only a String crosses each way.
    func addCar(opened: @escaping @MainActor (String) -> Void) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                if let id = try await core.addCar(name: "") { opened(id) }
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Two cars: removes a later car (its dates stay on the radar) or puts it back. Only a String crosses.
    func removeCar(_ carId: String) {
        MekaHaptics.light()
        run { _ = try await $0.removeCar(carId: carId) }
    }

    func undoRemoveCar(_ carId: String) {
        MekaHaptics.light()
        run { _ = try await $0.undoRemoveCar(carId: carId) }
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

    /// Today's greeting by the core's clock and zone (quality gate, 2026-10-22), the device clock only before the core is up.
    func todayGreeting(callName: String?) -> String {
        core?.todayGreeting(shownName: callName)
            ?? CallNameRules.shared.todayGreeting(hour: Int32(Calendar.current.component(.hour, from: .now)), shownName: callName)
    }

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
    /// End on Today's live Fasting card: "Fast ended" with Undo on the bar (the same fast comes back).
    func endFastFromToday(_ id: String) {
        endFast()
        offerEventUndo(FastingScreenRules.shared.TODAY_ENDED, .resumeFast(id))
    }
    func setFastTarget(_ hours: Int32) { run { try await $0.setFastTarget(hours: hours) } }
    func moveFastStart(_ minutes: Int32) { run { try await $0.moveFastStart(deltaMinutes: minutes) } }
    func discardFast() { run { try await $0.discardFast() } }
    func chooseFastingPlan(_ index: Int) { run { try await $0.chooseFastingPlan(index: Int32(index)) } }
    /// What every part of the Fasting screen says at `atMs` (core FastingScreenRules, pure; the views tick the clock).
    /// Nil until the core is running.
    func fastingScreen(atMs: Int64) -> FastingScreen? { core?.fastingScreen(atMs: atMs) }

    // MARK: Evening shutdown

    /// Carries one item over to tomorrow (the same "Tomorrow" as in the task detail).
    func carryOver(_ id: String) {
        if selectedID == id { selectedID = nil }
        MekaHaptics.tick()
        moveTask([id], .tomorrow) { try await $0.carryOver(taskId: id) }
    }

    /// "Move the rest to tomorrow": every row left leaves at once.
    func carryAllToTomorrow() {
        MekaHaptics.tick()
        guard let v = coreShutdown else { run { try await $0.carryAllToTomorrow() }; return }
        let ids = InstantTaskRules.shared.carried(taps: taskTaps, v: v)
        taskTaps = InstantTaskRules.shared.carryAll(taps: taskTaps, v: v)
        landTaskMove(ids, .tomorrow) { try await $0.carryAllToTomorrow() }
    }

    /// The brief's Focus and Today rows as drawn: a task ticked here leaves at once.
    func briefLines(_ lines: [BriefDayLine]) -> [BriefDayLine] {
        taskTaps.isEmpty ? lines : InstantTaskRules.shared.lines(lines: lines, taps: taskTaps)
    }

    /// A task move (Done, Tomorrow, Skip, Someday) shows in the same frame in the shutdown and the brief; the core's
    /// write follows, and a failed one puts the task back with the reason on the bar (taps feel instant).
    private func moveTask(_ ids: [String], _ move: TaskMove, _ body: @escaping (MekaCore) async throws -> Void) {
        for id in ids { taskTaps = InstantTaskRules.shared.move(taps: taskTaps, id: id, move: move) }
        landTaskMove(ids, move, body)
    }

    private func landTaskMove(_ ids: [String], _ move: TaskMove, _ body: @escaping (MekaCore) async throws -> Void) {
        showShutdownView()
        guard let core else { forgetTaskMove(ids, move); return }
        Task {
            do {
                try await body(core)
                forgetTaskMove(ids, move)
            } catch {
                forgetTaskMove(ids, move)
                offerEventUndo(InstantTapRules.shared.failedLine(what: InstantTaskRules.shared.what(move: move), reason: error.localizedDescription), nil)
            }
        }
    }

    private func forgetTaskMove(_ ids: [String], _ move: TaskMove) {
        taskTaps = InstantTaskRules.shared.forgetAll(taps: taskTaps, ids: ids, move: move)
        showShutdownView()
    }

    /// The shutdown as drawn: the core's with the task moves still on their way.
    private func showShutdownView() {
        guard let coreShutdown else { shutdown = nil; return }
        shutdown = taskTaps.isEmpty ? coreShutdown : InstantTaskRules.shared.shutdown(v: coreShutdown, taps: taskTaps, time: InstantTaskRules.shared.JUST_NOW)
    }

    /// Done for the day, completeness: "Later" on Today's evening card: it leaves and comes back an hour on (both apps).
    func shutdownLater() {
        MekaHaptics.light()
        run { _ = try await $0.shutdownLater(key: nil) }
    }

    /// The evening card and its heads-up off or back on, on both apps; returns what it says.
    func setShutdownCard(on: Bool) async -> String? {
        guard let core else { return nil }
        do { return try await core.setShutdownCard(on: on) } catch { lastError = error.localizedDescription; return nil }
    }

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
    /// The commute counts everywhere, slice 7: "Wake 10 min earlier" for tomorrow's trains (Shut down's button).
    func wakeEarlierForTrains() { MekaHaptics.light(); run { _ = try await $0.wakeEarlierForTrains(key: nil) } }
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

    /// Obi's face, slice 4: the server's list of the face's files (name → tag); nil when it couldn't be read (the Mac
    /// keeps what it has and looks again later). Only Strings cross back.
    func faceManifest() async -> [String: String]? {
        guard let core else { return nil }
        guard let offered = try? await core.faceManifest() else { return nil }
        return offered
    }

    /// Meka 2026-10-24 19:26: why the last look wasn't all seven (the server's note, its status or the failure); a String only.
    func faceLastWhy() -> String? {
        core?.faceLastWhy()
    }

    /// One of the face's seven files as bytes (nil when not there or offline); the core refuses any other name.
    func faceFileData(_ name: String) async -> Data? {
        guard let core else { return nil }
        guard let b64 = try? await core.faceFileBase64(name: name) else { return nil }
        return Data(base64Encoded: b64)
    }

    /// Obi's face, slice 4: while this Mac draws the face, Navilife's clips come with their mouth sounds.
    func speechFaceMarks(_ on: Bool) async {
        guard let core else { return }
        _ = try? await core.speechFaceMarks(on: on)
    }

    /// The mouth for a clip of `text`, `clipMs` long, from its kept marks; nil without them (the loudness mouth then).
    func speechMouth(_ text: String, clipMs: Int64) async -> MouthTrack? {
        guard let core else { return nil }
        return try? await core.speechMouth(text: text, clipMs: clipMs)
    }

    /// The Mac's desktop News widget (news ticker slice 3b): the top three stories still, from the current News place.
    func deskNewsWidget() -> DeskNewsView? { core?.deskNewsWidget() }

    /// Shows or hides a news topic's headlines in the brief; synced with the Fold.
    /// "A morning brief worth listening to", slice 3: Short · Standard · Full (synced). Only the id String crosses.
    func setBriefLength(_ id: String) { MekaHaptics.tick(); run { try await $0.setBriefLength(id: id) } }
    func setNewsTopic(_ id: String, on: Bool) { MekaHaptics.tick(); run { try await $0.setNewsTopic(topicId: id, on: on) } }
    /// News → Spanish sources (off = English only everywhere news shows); synced.
    func setNewsSpanish(on: Bool) { MekaHaptics.tick(); run { try await $0.setNewsSpanish(on: on) } }
    /// Full news hub, slice 2: switches one source on or off in News, the ticker and the brief; synced with the Fold.
    /// Only the source's name String crosses.
    func setNewsSource(_ name: String, on: Bool) { MekaHaptics.tick(); run { _ = try await $0.setNewsSource(name: name, on: on) } }
    /// Full news hub, slice 3: follows what he typed ("Arsenal, Lamine Yamal"); stories naming one lead News, the ticker
    /// and the brief on both apps. Only the typed String crosses; the line to show comes back.
    /// Full news hub, slice 5: asks the server which feeds aren't coming through (at most every 10 minutes);
    /// `newsPlace` follows on its own.
    func refreshNewsSources() async {
        guard let core else { return }
        _ = try? await core.refreshNewsSources()
    }
    func newsFollow(_ typed: String) async -> String? {
        guard let core else { return nil }
        do {
            let line = try await core.newsFollow(typed: typed)
            MekaHaptics.tick()
            return line
        } catch { lastError = error.localizedDescription; return nil }
    }
    /// Stops following one word or name (Undo is `newsFollow` with it); whether it was followed.
    func newsUnfollow(_ name: String) async -> Bool {
        guard let core else { return false }
        MekaHaptics.tick()
        return (try? await core.newsUnfollow(name: name))?.boolValue ?? false
    }

    // Full news hub, slice 1: read and new (synced with the Fold), Mark all read with Undo, Save for the train.
    /// Opening a story in News marks it read on both apps. Only the story's id crosses.
    func newsOpened(_ id: String) { run { _ = try await $0.newsOpened(id: id) } }
    /// Mark all read: the count put away (0 when nothing was new).
    func newsMarkAllRead() async -> Int {
        guard let core else { return 0 }
        do {
            let n = Int(try await core.newsMarkAllRead().int32Value)
            if n > 0 { MekaHaptics.tick() }
            return n
        } catch { lastError = error.localizedDescription; return 0 }
    }
    /// Puts the last Mark all read back.
    func newsUndoAllRead() { MekaHaptics.tick(); run { _ = try await $0.newsUndoAllRead() } }
    /// Save for the train from a story: its link into the train queue; the line to show and whether it was refused.
    func newsForTrain(_ id: String) async -> QueueSaid? {
        guard let core else { return nil }
        do {
            let r = try await core.newsForTrain(id: id)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return QueueSaid(line: r.line, refused: r.refused)
        } catch { lastError = error.localizedDescription; return nil }
    }

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

    /// Activity completeness: Export — every entry the log holds as a CSV, made on the Mac from his own data. Only
    /// Strings come back across.
    struct ActivityExport {
        let line: String
        let csv: String?
        let fileName: String?
    }

    func activityExport() async -> ActivityExport? {
        guard let core else { return nil }
        do {
            let f = try await core.activityExport()
            return ActivityExport(line: f.line, csv: f.csv, fileName: f.fileName)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// The line under Activity's days (how long things are kept, what Export saves); nil while the log is empty.
    func activityKeptLine() async -> String? {
        guard let core else { return nil }
        return try? await core.activityKeptLine()
    }

    /// Activity completeness: what the last Export did, shown in the sheet.
    func setActivityNote(_ line: String?) { activityNote = line }

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
        // Notifications never seen, the Mac's half: whether macOS lets MEKA notify, "This Mac" and the last post.
        let allowed = await MacNotifier.allowed()
        notificationsAllowed = allowed
        let notifications = MacNotifyRules.shared.health(allowed: allowed, macOff: macAlerts == .off, lastPostMs: MacNotifier.lastPostMs)
        let device = DeviceHealth(mac: true, batteryExempt: nil, batteryStopped: false, notificationAccess: nil,
                                  lastNotificationMs: nil, callRoleHeld: nil, version: version, updateReady: false,
                                  updateHidden: false, installedCode: nil, updateFailure: nil, sizeBytes: nil, previousSizeBytes: nil,
                                  notifications: notifications, callerLookups: nil, face: MacFaceStore.shared.health(),
                                  stageClosed: MacStageLog.last(), voiceInUse: core.speechVoiceInUse())
        _ = try? await core.refreshHealth(device: device, force: force)
    }

    // MARK: Family (family sharing with his wife, slice 4)

    /// Reads the links from MEKA's server (and logs, once across both devices, the day one was opened).
    func refreshFamily() async {
        guard let core else { return }
        _ = try? await core.refreshFamily()
    }

    /// Makes the partner's link (About you's name, never one in the code); nil when it couldn't be made (the view's
    /// problem says why). Only the name String crosses.
    func inviteFamily() async -> FamilyLink? {
        guard let core, !familyBusy, let name = family?.inviteName else { return nil }
        familyBusy = true
        defer { familyBusy = false }
        return try? await core.inviteFamily(name: name)
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
    /// `beyondHelp`: "Still stuck? Ask Navilife" under a help answer asks the same question of the AI, past the help.
    func askMeka(_ question: String, beyondHelp: Bool = false) async -> AskReplyView {
        guard let core else { return AskReplyView(lines: [], cards: [], unavailable: AskRules.shared.NOT_CONNECTED_LINE) }
        var reply = AskReplyView(lines: [], cards: [], unavailable: AskRules.shared.OFFLINE_LINE)
        let asked: AskOutcome?
        if beyondHelp { asked = try? await core.askBeyondHelp(question: question) } else { asked = try? await core.askMeka(question: question) }
        if let out = asked {
            switch onEnum(of: out) {
            case .answered(let a):
                reply = AskReplyView(lines: AskRules.shared.answerLines(text: a.answer.text), cards: a.answer.cards, unavailable: nil, did: a.answer.did, help: a.answer.help, stillStuck: a.answer.stillStuck, unlockMoney: a.answer.unlockMoney, sources: a.answer.sources, shown: a.answer.shown)
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

    /// "Hello, Navilife" slice 2: the sidebar's orb, the menu bar's Say hello, or ⌥Space after a while away: Ask comes
    /// forward and greets him, then listens (or ends the conversation that's running). Ask's section takes it.
    var helloRequested = false
    /// When this Mac last said the greeting, so ⌥Space greets on a return and otherwise just listens.
    @ObservationIgnored private var lastHelloAt: Date?

    func requestHello(reduced: Bool) {
        go(to: .ask, reduced: reduced)
        helloRequested = true
    }

    /// Slice 3: the next greeting opens "Welcome back, …" (the Mac's welcome back); `hello()` takes it.
    @ObservationIgnored private var welcomeNext = false

    /// Slice 3, "Hello Navilife" heard (`MacHelloWake`): Ask greets him, then listens; never ends a conversation.
    func requestHelloFromWake(reduced: Bool) {
        guard !helloRequested else { return }
        requestHello(reduced: reduced)
    }

    /// Slice 3, the Mac's welcome back (`MacWelcomeBack`): after `minutesAway`, the core decides (two hours or more,
    /// once per return, not in quiet hours or protected time); then Navilife comes forward and greets him.
    func welcomeBack(minutesAway: Int, reduced: Bool) {
        guard let core else { return }
        let since = lastHelloAt.map { Int32(min(Date().timeIntervalSince($0) / 60, 100_000)) } ?? -1
        guard core.welcomeBackGreets(on: MacWelcomeBack.enabled, awayMin: Int32(min(minutesAway, 100_000)),
                                     minutesSinceHello: since, micAllowed: MacTalkOnOpen.micAllowed) else { return }
        welcomeNext = true
        GlobalTalkHotKey.bringForward()
        requestHello(reduced: reduced)
    }

    /// ⌥Space and the Talk to Navilife command: a greeting when none was said in the last two hours
    /// (`HelloRules.hotkeyGreets`), else straight to listening as before.
    func requestTalkOrHello(reduced: Bool) {
        let minutes = lastHelloAt.map { Int32(min(Date().timeIntervalSince($0) / 60, 100_000)) } ?? -1
        if HelloRules.shared.hotkeyGreets(minutesSinceLast: minutes) { requestHello(reduced: reduced) } else { requestTalk(reduced: reduced) }
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

    /// The companion, slice 9: the spoken answer's first sentence, once the server has sent it ahead (already said and
    /// cached, so it plays at once), or nil. Only a String crosses; Talk reads it while it waits for the answer.
    func talkEarlyPiece() -> String? { core?.talkEarlyPiece() }

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
    /// A `hold` piece may take up to `SpeechRules.HOLD_AUDIO_MS` longer, quietly (`SpeechRules.holds`).
    func speechClip(_ text: String, first: Bool, reading: Bool = false, hold: Bool = false) async -> Data? {
        guard let core else { return nil }
        guard let clip = try? await core.speechClip(text: text, first: first, reading: reading, hold: hold) else { return nil }
        return Data(base64Encoded: clip)
    }

    /// Voice completeness: "Using this Mac's voice while Navilife's can't be reached." once, when the Mac's own voice
    /// takes over because Navilife's voice wasn't reached (nil otherwise). Only a String crosses back.
    func speechOfflineNote() async -> String? {
        guard let core else { return nil }
        return try? await core.speechOfflineNote(mac: true)
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

    /// Obi's Live mode, slice 2b: Live this month ("Obi's Live · 3 talks · 13 min · £0.42 of £15 in October"); nil
    /// when there is nothing to say or the server can't say. Only a String crosses back.
    private(set) var liveActivityLine: String?

    /// How quickly what he said was added lately ("Speech to Added · 0.6 s · 0.9 s"; the mic fix, step 3); nil before
    /// the first. Kept in memory only.
    private(set) var heardTimingLine: String?

    /// The mic fix, step 3: something he said to Talk was added `ms` after his words ended. Only an Int64 and a Bool
    /// cross; the engine is made here and sent once.
    /// The orb speed pass, slice 2: a hello was clicked; the core times it to Navilife's voice (nothing crosses).
    func turnHelloBegan() async {
        try? await core?.turnHelloBegan()
    }

    /// The orb speed pass, slice 2: a spoken question whose words ended `agoMs` ago (only a number crosses).
    func turnTalkBegan(agoMs: Int64) async {
        try? await core?.turnTalkBegan(agoMs: agoMs)
    }

    /// The orb speed pass, slice 2: the turn's speaking is over (or the conversation stopped).
    func turnEnded() async {
        try? await core?.turnEnded()
    }

    /// The orb speed pass, slice 2: Voice → Speed, the last 20 hellos and answers on this Mac (strings only).
    private(set) var speedSummary: String?
    private(set) var speedRows: [String] = []

    /// Talking to Obi like a conversation, slice 2: Speed's "On the server" part (the server's own timings of the last
    /// day's answers, times only); empty when not connected or the server is older.
    private(set) var serverSpeedLines: [String] = []
    private(set) var serverSpeedEmpty: String?
    private(set) var serverSpeedShown = false

    func refreshSpeed() async {
        guard let core, let v = try? await core.turnSpeed(device: "Mac") else { speedSummary = nil; speedRows = []; return }
        speedSummary = v.summary
        speedRows = v.rows
        if let sv = try? await core.serverSpeed() {
            serverSpeedLines = sv.lines
            serverSpeedEmpty = sv.empty
            serverSpeedShown = true
        } else {
            serverSpeedLines = []
            serverSpeedEmpty = nil
            serverSpeedShown = false
        }
    }

    /// Speed's Clear: forgets the kept turns on this Mac; a tick haptic.
    func clearSpeed() {
        guard let core, !speedRows.isEmpty else { return }
        MekaHaptics.tick()
        speedSummary = nil
        speedRows = []
        Task { try? await core.clearTurnSpeed() }
    }

    func recordHeardTiming(ms: Int64, online: Bool) async {
        guard let core else { return }
        nonisolated(unsafe) let engine: ListenEngine = online ? .online : .onDevice
        try? await core.recordHeardTiming(ms: ms, engine: engine)
    }

    func refreshVoiceUsage() async {
        guard let core, !signedOut else { voiceUsageLine = nil; voiceTimingLine = nil; heardTimingLine = nil; aiTodayLine = nil; liveActivityLine = nil; return }
        voiceTimingLine = (try? await core.voiceTimingLine()) ?? nil
        heardTimingLine = (try? await core.heardTimingLine()) ?? nil
        voiceUsageLine = (try? await core.voiceUsageLine()) ?? nil
        aiTodayLine = (try? await core.aiTodayLine()) ?? nil
        liveActivityLine = (try? await core.liveActivityLine()) ?? nil
    }

    /// The voice picker (MEKA's voices, then the Mac's own), nil until the server answers. Only the view crosses back.
    private(set) var voicePicker: VoicePickerView?

    func refreshVoicePicker() async {
        guard let core else { voicePicker = nil; return }
        voicePicker = try? await core.voicePicker(mac: true)
        // The companion, slice 3: "sir" or by name, read with the picker (only a Bool crosses).
        callsSir = (try? await core.companionCallsSir())?.boolValue ?? false
        // The companion, slice 5: whether Navilife remembers chats and what it keeps (only a Bool and strings cross).
        remembersChats = (try? await core.companionRemembers())?.boolValue ?? true
        memoryRows = (try? await core.companionMemoryRows()) ?? []
        await refreshSpeed()
    }

    // MARK: Obi's Live mode, slice 3 (the Mac)

    /// The Live block in Obi's voice settings: the switch, its line (the month's meter or why Live can't run), what goes
    /// to Google and the Live voices; nil until read.
    private(set) var liveSettings: LiveSettingsView?

    /// Reads the Live block (the server's meter with it; offline it says Live can't be reached).
    func refreshLive() async {
        guard let core else { liveSettings = nil; return }
        liveSettings = try? await core.liveSettings()
    }

    /// Turns Live on or off on every device, lit at once (the voices show or fold away); the line follows from the
    /// server's meter. A tick haptic. Only a Bool crosses.
    func setLiveOn(_ on: Bool) {
        guard let core, let now = liveSettings, now.on != on else { return }
        MekaHaptics.tick()
        liveSettings = now.doCopy(on: on, line: now.line, meterLine: now.meterLine, sharing: now.sharing, voices: now.voices, showVoices: on)
        Task {
            try? await core.setLiveOn(on: on)
            await refreshLive()
        }
    }

    /// Picks Obi's Live voice on every device, lit at once; a tick haptic. Only the name crosses.
    func setLiveVoice(_ name: String) {
        guard let core, let now = liveSettings else { return }
        MekaHaptics.tick()
        let voices = now.voices.map { LiveVoiceRow(name: $0.name, detail: $0.detail, selected: $0.name == name) }
        liveSettings = now.doCopy(on: now.on, line: now.line, meterLine: now.meterLine, sharing: now.sharing, voices: voices, showVoices: now.showVoices)
        Task { _ = try? await core.setLiveVoice(name: name) }
    }

    /// ▶ Sample on a Live voice: the sample in Google's studio voice of that name as MP3 data, or nil (never another voice).
    func liveSample(_ name: String) async -> Data? {
        guard let core else { return nil }
        guard let clip = try? await core.liveVoiceSample(name: name) else { return nil }
        return Data(base64Encoded: clip)
    }

    /// Whether Live is on (synced; off unless he turns it on). Only a Bool crosses.
    func liveIsOn() async -> Bool {
        guard let core else { return false }
        return (try? await core.liveOn())?.boolValue ?? false
    }

    /// A Live conversation for Talk, run in the core on the main thread (`MacCoreFactory.liveConversation`); nil before
    /// the core opens. The Talk controller keeps it and only it calls it, always on the main actor.
    func newLiveConversation() -> LiveConversation? {
        guard let core else { return nil }
        return MacCoreFactory.shared.liveConversation(core: core)
    }

    /// The companion, slice 5: whether Navilife remembers chats (synced; on by default).
    private(set) var remembersChats = true
    /// The companion, slice 5: what Navilife remembers of earlier chats, newest first ("Yesterday · Talked through …").
    private(set) var memoryRows: [String] = []

    /// Turns remembering on or off on every device, lit at once (off forgets the notes); a tick haptic.
    func setRemembersChats(_ on: Bool) {
        guard let core, on != remembersChats else { return }
        MekaHaptics.tick()
        remembersChats = on
        if !on { memoryRows = [] }
        Task { try? await core.setCompanionRemembers(on: on) }
    }

    /// Forgets every note on every device.
    func forgetChats() {
        guard let core, !memoryRows.isEmpty else { return }
        MekaHaptics.tick()
        memoryRows = []
        Task { try? await core.forgetCompanionMemory() }
    }

    /// The companion, slice 3: whether Navilife calls him "sir" in Ask, Talk and Hello (synced; false: by name).
    private(set) var callsSir = false

    /// Chooses "sir" or by name on every device, lit at once; a tick haptic.
    func setCallsSir(_ sir: Bool) {
        guard let core, sir != callsSir else { return }
        MekaHaptics.tick()
        callsSir = sir
        Task { try? await core.setCompanionCallsSir(sir: sir) }
    }

    /// Chooses MEKA's voice on every device (a Polly name or "device"), lighting it at once; a tick haptic.
    func chooseVoice(_ id: String) {
        guard let core, let v = voicePicker else { return }
        MekaHaptics.tick()
        voicePicker = VoicePickerView(loaded: v.loaded, intro: v.intro,
                                      choices: v.choices.map { VoiceChoice(id: $0.id, label: $0.label, detail: $0.detail, selected: $0.id == id, sample: $0.sample, group: $0.group) },
                                      statusLine: v.statusLine, usageLine: v.usageLine, help: v.help, googleLine: v.googleLine, sampleLine: v.sampleLine)
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

    /// What ▶ Sample says now: the start of today's brief, or the stock line before there is one (natural voice,
    /// slice 2). Read on the main actor; a String crosses, nothing of Kotlin's is sent anywhere.
    func speechSampleWords() -> String {
        core?.speechSampleWords() ?? VoicePickerRules.shared.SAMPLE
    }

    /// Fetches MEKA's common lines in its voice once, so they play at once in a conversation.
    func warmVoice() async {
        guard let core else { return }
        _ = try? await core.warmVoice()
    }

    /// The orb speed pass, slice 5: tells Navilife's server he's about to talk, so it opens its AI and voice connections
    /// before his question arrives (at most every 20 s from the Mac; nothing he said is sent). Nothing crosses back.
    func warmTalk() async {
        guard let core else { return }
        _ = try? await core.warmTalk()
    }

    /// The orb speed pass, slice 3: the hello's openers (this part of the day's and the next hour's, and the welcome
    /// back) made ahead in Navilife's voice, so a click on the orb speaks with no voice fetch. Nothing crosses back.
    func warmHello() async {
        guard let core else { return }
        _ = try? await core.warmHello(welcome: true)
    }

    /// The orb speed pass, slice 3: asks for `text`'s first piece in Navilife's voice ahead of being said (the rest of
    /// an answer while its first sentence plays); the speaker's own ask then finds it ready. Only the String crosses.
    func speechPrefetch(_ text: String) async {
        guard let core else { return }
        _ = try? await core.speechPrefetch(text: text)
    }

    /// "Hello, Navilife": the greeting the orb says when clicked at rest, built by the core's rules (`HelloRules`; no AI
    /// call, nothing sent; his partner's open asks counted by the core). Only the String crosses back; nil when not
    /// connected or it couldn't be read.
    func hello() async -> HelloSaid? {
        guard let core else { return nil }
        let welcome = welcomeNext
        welcomeNext = false
        guard let g = try? await core.hello(partnerAsks: -1, welcome: welcome) else { return nil }
        lastHelloAt = Date()
        // Slice 8: the greeting's cards as plain Swift values, each with when it rises (ms after the voice begins).
        let reveal = HelloCards.shared.revealAt(g: g).map { Int($0.int64Value) }
        let cards = g.cards.enumerated().map { i, c in
            HelloCardLine(
                id: i, eyebrow: c.eyebrow, title: c.title, detail: c.detail, warm: c.warm,
                open: HelloCardLine.destination(c.open), revealMs: i < reveal.count ? reveal[i] : 0,
                label: HelloCards.shared.label(c: c)
            )
        }
        return HelloSaid(words: g.spoken, cards: cards)
    }

    /// How many cards Needs you holds: the Hello orb's warm halo while any do.
    var needsYouCount: Int { needsYouStack?.cards.count ?? 0 }

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

    /// Weekly reflection, slice 4: "Later tonight" on Today's review card (Sunday only): it leaves Today and the
    /// heads-up comes back two hours on, on both apps.
    func reviewLater() {
        MekaHaptics.light()
        run { _ = try await $0.reviewLater(key: nil) }
    }

    // MARK: Goals and habits

    func addHabit(_ title: String, perWeek: Int, timing: HabitTiming) {
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        run { _ = try await $0.addHabit(title: t, perWeek: Int32(perWeek), timing: timing, minutes: GoalRules.shared.DEFAULT_MINUTES, goalId: nil) }
    }

    /// Ticks or unticks a habit for today (light haptic; the circle pops).
    func setHabitDone(_ id: String, _ done: Bool) {
        MekaHaptics.light()
        tickHabit(id, done)
    }

    /// The tick shows in the same frame on the dots, chips, Goals and Needs you; the core's write follows, and a failed
    /// one puts the habit back with the reason on the bar (taps feel instant).
    private func tickHabit(_ id: String, _ done: Bool) {
        guard let core else { return }
        habitTaps = InstantHabitRules.shared.tick(taps: habitTaps, id: id, done: done)
        showGoals()
        Task {
            do {
                _ = try await core.setHabitDone(id: id, done: done)
                landHabitTap(id, done)
            } catch {
                landHabitTap(id, done)
                offerEventUndo(InstantTapRules.shared.failedLine(what: done ? "Tick" : "Untick", reason: error.localizedDescription), nil)
            }
        }
    }

    /// Goals as drawn: the core's goals with the habit ticks still on their way.
    private func showGoals() {
        guard let coreGoals else { goals = nil; return }
        goals = habitTaps.isEmpty ? coreGoals : InstantHabitRules.shared.shown(goals: coreGoals, taps: habitTaps)
    }

    private func landHabitTap(_ id: String, _ done: Bool) {
        guard habitTaps[id] != nil else { return }
        habitTaps = InstantHabitRules.shared.forget(taps: habitTaps, id: id, done: done)
        showGoals()
    }
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

    /// The player's waveform (call notifications slice 7) for a recording [voiceMessageAudio] fetched: the core reads
    /// the MP3's frames in memory; only a String goes in and plain Doubles come back, on the main actor.
    func voiceWaveform(_ data: Data) -> [Double] {
        guard let core else { return [] }
        return core.voiceWaveformOfBase64(base64: data.base64EncodedString()).map { Double($0.floatValue) }
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
        let fallback = brief.map { BriefScriptRules.shared.script(v: $0, name: aboutYou?.callName ?? "") } ?? ""
        guard let core else { return fallback }
        do {
            return try await core.briefScript(name: nil)
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
    /// Delete recording in a call's detail (2026-10-20): shown as deleted at once, written after 5 s unless undone.
    func deleteCallRecording(_ id: String) { MekaHaptics.light(); run { try await $0.deleteCallRecordingSoon(id: id) } }
    /// Undo after Delete recording: the recording comes back, nothing was written.
    func undoDeleteCallRecording(_ id: String) { MekaHaptics.light(); run { try await $0.undoDeleteCallRecording(id: id) } }
    /// Block in a call's detail (Activity → Calls, call notifications slice 4): the call's number goes on the list.
    func blockLoggedCaller(_ id: String) { MekaHaptics.light(); run { _ = try await $0.blockLoggedCaller(id: id) } }
    /// Block again on an unblocked row of Activity → Blocked calls (spam calls still ring, slice 4).
    func reblockCaller(_ key: String) { MekaHaptics.light(); run { _ = try await $0.reblockCaller(key: key) } }
    /// Suspected spam (call assistant polish 8b c): Block puts the AI-flagged number on the block list with its reason.
    func confirmSuspectedCaller(_ key: String) { MekaHaptics.light(); run { _ = try await $0.confirmSuspectedCaller(key: key) } }
    /// Not spam: off Suspected spam; its calls are screened as before and MEKA never flags it again.
    func dismissSuspectedCaller(_ key: String) { MekaHaptics.tick(); run { _ = try await $0.dismissSuspectedCaller(key: key) } }
    /// Weather place setting (Calendars → Weather): the town the forecast is for; blank or About you's home follows About you.
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
    /// Where work is (Places item 2, Calendars → Weather → Work): blank or About you's work follows About you. False (nothing
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
        // Screenshots and photos, slice 2: Handle this on a noticed screenshot reads it again into Handle this.
        if a.action == MacScreenshotWatch.action {
            MacNotifier.remove(key: a.key)
            if let path = a.picture { handlePicture(.file(URL(fileURLWithPath: path))) }
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
        // Tomorrow's trains (the commute counts everywhere, slice 7), or this morning's train in trouble before the
        // alarm (live trains, slice 5): only this click moves the wake alarm.
        if name == MacNotifier.wakeEarlierAction || name == MacNotifier.wakeForTrainAction {
            Task {
                _ = try? await core.wakeEarlierForTrains(key: key)
                MacNotifier.remove(key: key)
            }
            return
        }
        // Sunday's weekly review: Later tonight brings it back two hours on, on both apps (weekly reflection, slice 4).
        if name == MacNotifier.laterTonightAction {
            Task {
                _ = try? await core.reviewLater(key: key)
                MacNotifier.remove(key: key)
            }
            return
        }
        // The evening card: Later brings it back an hour on, on both apps (Done for the day, completeness).
        if name == MacNotifier.shutdownLaterAction {
            Task {
                _ = try? await core.shutdownLater(key: key)
                MacNotifier.remove(key: key)
            }
            return
        }
        // The size check: All still fit stamps it on both apps (kids' admin, slice 6).
        if name == MacNotifier.allStillFitAction {
            Task {
                _ = try? await core.kidsStillFit(key: key)
                MacNotifier.remove(key: key)
            }
            return
        }
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
                // Screenshots and photos, slice 3: a passport, licence, GHIC… is offered to the Vault once the cards
                // are in (read on this Mac; nothing is written until Add to the Vault is clicked).
                let document = try await core.documentFromShared(text: text, subject: subject)
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
                if let document {
                    let line = DocumentShareRules.shared.ICON + " " + document.line
                    if document.already {
                        offerEventUndo(line, nil)
                    } else {
                        offerEventUndo(line, .addDocument(text, subject), label: DocumentShareRules.shared.ACTION, seconds: 10)
                    }
                }
            } catch { lastError = error.localizedDescription }
        }
    }

    // MARK: Pictures (screenshots and photos that do something, slice 1)

    /// A screenshot or photo (Services → "Handle this picture with Navilife", the menu bar's Read a picture…, the capture
    /// mic's context menu): Navilife comes forward on Needs you saying it's reading the picture on this Mac, Vision reads
    /// it off the main actor, and the words alone go to Handle this; with no words the bar says so. Never kept or sent.
    func handlePicture(_ picture: MacPictureReader.Picture) {
        NSApp.activate()
        go(to: .needsYou, reduced: MotionSetting.reduced)
        offerEventUndo(PictureTextRules.shared.READING_LINE_MAC, nil)
        Task {
            switch await MacPictureReader.read(picture) {
            case .words(let text): handleThis(text)
            case .nothing(let line):
                MekaHaptics.tick()
                offerEventUndo(line, nil)
            }
        }
    }

    /// Read a picture…: an open panel for one image, read as above.
    func choosePictureToRead() {
        NSApp.activate()
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        panel.message = "Pick a screenshot or photo · Navilife reads it on this Mac and doesn't keep it"
        panel.prompt = "Read it"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        handlePicture(.file(url))
    }

    /// Notice new screenshots (slice 2), from the menu bar's capture: turns the watch on or off and says so in the bar
    /// (or that the Mac's notifications are off, so nothing could be offered).
    func setNoticeScreenshots(_ on: Bool) {
        MacScreenshotWatch.shared.set(on)
        let rules = ScreenshotRules.shared
        guard on else {
            offerEventUndo(rules.TURNED_OFF, nil)
            return
        }
        Task {
            let allowed = await MacNotifier.allowed()
            offerEventUndo(allowed ? rules.TURNED_ON_MAC : rules.MAC_NO_NOTIFY, nil)
        }
    }

    /// Read the copied picture: a screenshot copied with ⌃⇧⌘4, or an image copied in a browser.
    func readCopiedPicture() {
        guard let picture = MacPictureReader.picture(on: .general) else {
            offerEventUndo(PictureTextRules.shared.NO_COPIED_LINE, nil)
            return
        }
        handlePicture(picture)
    }

    // MARK: Voice notes (voice notes into actions, slice 3)

    /// Holding the capture field's mic, or "take a voice note" said to Talk: Navilife comes forward with the voice note
    /// sheet, which starts recording as it opens.
    func startVoiceNote() {
        NSApp.activate()
        showVoiceNote = true
    }

    /// "Take a voice note" said to Talk: the conversation has ended (End came first), then the sheet opens recording.
    func openVoiceNoteFromTalk() { startVoiceNote() }

    /// A note's words (never its recording) to the AI's tidy-up, as on the Fold: the tidy note, the people and the
    /// cards (which wait in Needs you). Only Strings cross to the core; nil when it threw.
    func tidyVoiceNote(_ words: String, subject: String) async -> VoiceNoteRead? {
        guard let core else { return nil }
        do { return try await core.tidyVoiceNote(words: words, subject: subject) } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// "Voice note · 14:02" for a note made at `atMs`.
    func voiceNoteSubject(_ atMs: Int64) -> String {
        core?.voiceNoteSubject(atMs: atMs) ?? VoiceNoteRules.shared.subject(minuteOfDay: 0)
    }

    /// The notes as Voice notes shows them (the Mac's wording), newest first.
    func voiceNoteRows(_ notes: [VoiceNote]) -> [VoiceNoteRow] { core?.voiceNoteRows(notes: notes, onMac: true) ?? [] }

    /// Daily's Voice notes line for the notes this Mac keeps (nil: the row's fixed words).
    func voiceNotesLine(_ notes: [VoiceNote]?) -> DailyLine? {
        guard let notes else { return nil }
        return core?.voiceNotesLine(notes: notes)
    }

    /// The ✕ on a quick alarm or timer: cancelled on every device.
    func cancelAlarm(_ id: String) {
        MekaHaptics.tick()
        run { _ = try await $0.cancelAlarm(id: id) }
    }

    func complete(_ id: String) {
        if selectedID == id { selectedID = nil }
        moveTask([id], .done) { try await $0.complete(taskId: id) }
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
        moveTask([id], .skip) { try await $0.skipOccurrence(taskId: id) }
    }

    func snooze(_ id: String) {
        if selectedID == id { selectedID = nil }
        run { try await $0.snooze(taskId: id, days: 1) }
    }

    /// Roll-over (Week board, slice 1): the three-carry prompt on a "From yesterday" row. Break it down marks it answered
    /// (the view opens the task's steps); Someday and Drop leave with Undo on the bar; Keep stops it asking.
    func answerCarried(_ id: String, _ choice: CarryChoice) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            guard let line = try? await core.answerCarried(taskId: id, choice: choice) else { return }
            offerEventUndo(line, .carried(id))
        }
    }

    // MARK: Week board, slice 2: the Board view

    /// A drop on the Board: the card goes to that day (today or later, keeping its time) at that place; the undo bar
    /// says where ("Moved “Book MOT” to Thu 22 Oct") and Undo puts it and the day's order back.
    func moveOnBoard(_ id: String, day: Int64, index: Int) {
        guard let core else { return }
        let place = Int32(clamping: index)
        tapBoard(InstantBoardRules.shared.toDay(taps: boardTaps, id: id, day: day, index: place))
        Task {
            do {
                let line = try await core.moveOnBoard(taskId: id, day: day, toIndex: place)
                landBoardTap(id)
                guard let line else { return }
                MekaHaptics.light()
                offerEventUndo(line, .boardMove)
            } catch {
                landBoardTap(id)
                offerEventUndo(InstantTapRules.shared.failedLine(what: "Move", reason: error.localizedDescription), nil)
            }
        }
    }

    /// The Board as drawn: the core's board with the taps still on their way (taps feel instant, slice 13).
    private func showBoard() {
        guard let coreBoard else { board = nil; return }
        board = boardTaps.isEmpty ? coreBoard : InstantBoardRules.shared.shown(board: coreBoard, taps: boardTaps)
    }

    private func tapBoard(_ taps: [String: BoardTap]) {
        boardTaps = taps
        showBoard()
    }

    /// A Board call returned (done or failed): the core's board is the truth again for that card.
    private func landBoardTap(_ id: String) {
        guard boardTaps[id] != nil else { return }
        tapBoard(InstantBoardRules.shared.forget(taps: boardTaps, id: id))
    }

    /// Week board item (5) part 3: the days To a day… offers under an overdue or due-today card (tomorrow on).
    func needsYouFeedDays() -> [FeedDay] { core?.needsYouFeedDays() ?? [] }

    /// Week board item (5) part 3: To a day… on an overdue or due-today card in Needs you — the task goes to that day of
    /// the Board (its due date kept), leaving Needs you till then. Only the task's id (a String) and the day (an Int64)
    /// cross; Undo on the bar puts it back.
    func needsYouToDay(_ taskId: String, day: Int64) {
        guard let core else { return }
        MekaHaptics.medium()
        if selectedID == taskId { selectedID = nil }
        Task {
            do {
                let line = try await core.needsYouToDay(taskId: taskId, day: day)
                offerEventUndo(line, line == BoardFeedRules.shared.CANT_MOVE ? nil : .boardMove)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Week board item (5) part 4: the days To a day… offers under a school date or a birthday (today up to it).
    func dateFeedDays(_ date: Int64) -> [FeedDay] { core?.dateFeedDays(date: date) ?? [] }

    /// Week board item (5) part 4: To a day… on a school date — "Ready for …" as a task on that day of the Board. Only
    /// the item's id (a String) and the day (an Int64) cross; returns the line to show (Undo: undoDateToDay).
    func schoolDateToDay(_ itemId: String, day: Int64) async -> String? {
        guard let core else { return nil }
        MekaHaptics.medium()
        do { return try await core.schoolDateToDay(itemId: itemId, day: day) } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Week board item (5) part 4: To a day… on a birthday or anniversary — "Gift for …" as a task on that day of the
    /// Board (nothing is ordered). Only the occasion's id and the day cross; returns the line to show.
    func giftToDay(_ occasionId: String, day: Int64) async -> String? {
        guard let core else { return nil }
        MekaHaptics.medium()
        do { return try await core.giftToDay(occasionId: occasionId, day: day) } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Undo after schoolDateToDay or giftToDay: the task it made leaves the Board.
    func undoDateToDay() async {
        guard let core else { return }
        do { _ = try await core.undoDateToDay() } catch { lastError = error.localizedDescription }
    }

    /// Week board item (10): a stacking-up day's one-tap fix (on the Board, Today or the brief): the task moves to the
    /// fix's day as a Board move; the undo bar says so ("Moved “Gym” to Sat") and Undo puts it back.
    func easePressure(taskId: String, toDay: Int64) {
        guard let core else { return }
        Task {
            guard let line = try? await core.easePressure(taskId: taskId, day: toDay) else { return }
            MekaHaptics.light()
            offerEventUndo(line, .boardMove)
        }
    }

    /// Option B slice 4: Obi's one line in the Board's header for the week starting [weekStart] on [board] (the board
    /// the screen draws), or nil for a week gone. A line with a fix is easePressure with its task and day.
    func boardNudge(_ board: WeekBoard, weekStart: Int64) -> BoardNudge? {
        core?.boardNudge(board: board, weekStart: weekStart)
    }

    /// Option B slice 5: the day timeline for local `epochDay` on `board` (the board being drawn), or nil off the board.
    func boardTimeline(_ board: WeekBoard, epochDay: Int64) -> BoardTimeline? {
        core?.boardTimeline(board: board, epochDay: epochDay)
    }

    /// ⌘← ⌘→ (a day earlier or later) and ⌘↑ ⌘↓ (a place up or down) on the selected card, and its menu.
    /// Slice 17: the card moves in the same frame (InstantBoardRules.nudge), the core's line follows.
    func nudgeOnBoard(_ id: String, days: Int, places: Int) {
        guard let core, let coreBoard else { return }
        let d = Int32(clamping: days), p = Int32(clamping: places)
        let next = InstantBoardRules.shared.nudge(board: coreBoard, taps: boardTaps, id: id, days: d, places: p)
        guard next != boardTaps else { return }
        tapBoard(next)
        landBoard(id, what: "Move", undo: .boardMove, tick: true) { try await core.nudgeOnBoard(taskId: id, days: d, places: p) }
    }

    /// Slice 17: runs a Board call whose tap is already drawn; forgets the tap when it returns, then offers the core's
    /// line with Undo, or brings the card back with why it didn't go through.
    private func landBoard(_ id: String, what: String, undo: EventUndoOffer.Action, tick: Bool = false, _ work: @escaping @MainActor () async throws -> String?) {
        Task {
            do {
                let line = try await work()
                landBoardTap(id)
                guard let line else { return }
                if tick { MekaHaptics.tick() } else { MekaHaptics.light() }
                offerEventUndo(line, undo)
            } catch {
                landBoardTap(id)
                offerEventUndo(InstantTapRules.shared.failedLine(what: what, reason: error.localizedDescription), nil)
            }
        }
    }

    /// A drop on a week's lane (Week board, slice 4): the card is parked in that week with no day (off Today), at that
    /// place; the undo bar says "Moved “Book MOT” to This week" and Undo puts it back where it was.
    func parkOnBoard(_ id: String, week: Int64, index: Int) {
        guard let core else { return }
        let place = Int32(clamping: index)
        tapBoard(InstantBoardRules.shared.toWeek(taps: boardTaps, id: id, weekStart: week, index: place))
        Task {
            do {
                let line = try await core.parkOnBoard(taskId: id, weekStart: week, toIndex: place)
                landBoardTap(id)
                guard let line else { return }
                MekaHaptics.light()
                offerEventUndo(line, .boardMove)
            } catch {
                landBoardTap(id)
                offerEventUndo(InstantTapRules.shared.failedLine(what: "Move", reason: error.localizedDescription), nil)
            }
        }
    }

    /// A drop on the Someday tray (Week board, slice 4), or a card's Someday: kept for later, with Undo.
    func somedayOnBoard(_ id: String) {
        guard let core else { return }
        if boardSelectedID == id { boardSelectedID = nil }
        // Slice 17: on the Board it leaves for the tray in the same frame; elsewhere (the shutdown) the call alone.
        if let coreBoard {
            let next = InstantBoardRules.shared.someday(board: coreBoard, taps: boardTaps, id: id)
            if next != boardTaps {
                tapBoard(next)
                landBoard(id, what: "Someday", undo: .boardMove) { try await core.somedayOnBoard(taskId: id) }
                return
            }
        }
        Task {
            guard let line = try? await core.somedayOnBoard(taskId: id) else { return }
            MekaHaptics.light()
            offerEventUndo(line, .boardMove)
        }
    }

    // MARK: Week board Option B, slice 3: the colour categories

    /// The Kotlin area for its name ("HOUSE"), or nil for none; made where it is used so no Kotlin value crosses
    /// isolation twice.
    nonisolated static func area(_ name: String?) -> BoardArea? {
        switch name {
        case "FAMILY": return .family
        case "WORK": return .work
        case "SIDE_HUSTLE": return .sideHustle
        case "MONEY": return .money
        case "HOUSE": return .house
        default: return nil
        }
    }

    /// The card menu's Area: files the task under the area named ("HOUSE"), or nil for No area; Undo on the bar.
    func areaOnBoard(_ id: String, area: String?) {
        guard let core else { return }
        // Slice 17: the dot changes in the same frame.
        if let coreBoard {
            let next = InstantBoardRules.shared.area(board: coreBoard, taps: boardTaps, id: id, area: Self.area(area))
            if next != boardTaps {
                tapBoard(next)
                landBoard(id, what: "Area", undo: .boardArea) { try await core.areaOnBoard(taskId: id, area: Self.area(area)) }
                return
            }
        }
        Task {
            guard let line = try? await core.areaOnBoard(taskId: id, area: Self.area(area)) else { return }
            MekaHaptics.light()
            offerEventUndo(line, .boardArea)
        }
    }

    /// A card dropped on Someday, Side hustle or House under the weekend (the list's area name, nil for Someday).
    func listOnBoard(_ id: String, list: String?) {
        guard let core else { return }
        if boardSelectedID == id { boardSelectedID = nil }
        // Slice 17: it goes to the list in the same frame.
        if let coreBoard {
            let next = InstantBoardRules.shared.toList(board: coreBoard, taps: boardTaps, id: id, list: Self.area(list))
            if next != boardTaps {
                tapBoard(next)
                landBoard(id, what: "Move", undo: .boardList) { try await core.listOnBoard(taskId: id, list: Self.area(list)) }
                return
            }
        }
        Task {
            guard let line = try? await core.listOnBoard(taskId: id, list: Self.area(list)) else { return }
            MekaHaptics.light()
            offerEventUndo(line, .boardList)
        }
    }

    // MARK: Week board, item (11): "How are you feeling?"

    /// The Kotlin feeling for a name, made where it is used so no Kotlin value crosses isolation twice.
    nonisolated static func feeling(_ name: String) -> Feeling {
        switch name {
        case "TIRED": return .tired
        case "STRETCHED": return .stretched
        default: return .good
        }
    }

    /// How he said he feels today on this Mac ("TIRED"), or nil. Kept on this Mac only, for a week.
    func feelingToday() async -> String? {
        guard let core else { return nil }
        return (try? await core.feelingToday())?.name
    }

    /// Good · Tired · Stretched picked: kept here, and what Navilife offers (nothing moves until his click).
    func setFeeling(_ name: String) async -> FeelingOffer? {
        guard let core else { return nil }
        MekaHaptics.tick()
        return try? await core.setFeeling(feeling: Self.feeling(name))
    }

    /// The offer for a feeling picked earlier today (the sheet reopened), keeping nothing.
    func feelingOffer(_ name: String) async -> FeelingOffer? {
        guard let core else { return nil }
        return try? await core.feelingOffer(feeling: Self.feeling(name))
    }

    /// While Stretched holds the alerts that can wait on this Mac: "Mail and call alerts held till …", else nil.
    func alertHoldLine() async -> String? {
        guard let core else { return nil }
        return try? await core.alertHoldLine()
    }

    /// Hold alerts till tomorrow (Stretched, his click): the held line on the bar with Undo (which resumes them).
    func holdAlerts() {
        guard let core else { return }
        Task {
            guard let line = try? await core.holdAlerts() else { return }
            MekaHaptics.tick()
            offerEventUndo(line, .resumeAlerts)
        }
    }

    /// Resume: the hold ends now; held alerts that still apply come on the next look.
    func resumeAlerts() async {
        guard let core else { return }
        _ = try? await core.resumeAlerts()
        MekaHaptics.tick()
    }

    /// Lighten tomorrow: two of tomorrow's tasks to lighter days, one Undo for both.
    func lightenTomorrow(_ name: String) {
        guard let core else { return }
        Task {
            guard let line = try? await core.lightenTomorrow(feeling: Self.feeling(name)) else { return }
            MekaHaptics.light()
            offerEventUndo(line, .lighten)
        }
    }

    /// Plan your week (Week board item (6)): this week (next week on a Sunday) as a guided flow; nothing moves yet.
    func planWeek() async -> WeekPlan? {
        guard let core else { return nil }
        return try? await core.planWeek()
    }

    /// Accept plan: every row he left with a day goes there; the bar says "Week planned · …" with one Undo for all.
    func acceptWeekPlan(_ plan: WeekPlan) async {
        guard let core else { return }
        guard let line = try? await core.acceptWeekPlan(plan: plan) else { return }
        MekaHaptics.light()
        offerEventUndo(line, .weekPlan)
    }

    /// "+ Add" at the foot of a Board column: a task on that day, last in its order.
    func addOnBoard(_ text: String, day: Int64, minutes: Int? = nil) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        MekaHaptics.light()
        // By hand, slice 1: the estimate chip picked under the add field (15m · 30m · 1h · 2h), or none.
        run { core in
            let value = minutes.map { KotlinInt(int: Int32(clamping: $0)) }
            _ = try await core.addOnBoardSized(text: trimmed, day: day, minutes: value)
        }
    }

    /// By hand, slice 1: a second click on a done card's tick un-ticks it, with Undo (ticked off again).
    func reopenOnBoard(_ id: String, title: String) {
        guard let core else { return }
        MekaHaptics.tick()
        tapBoard(InstantBoardRules.shared.done(taps: boardTaps, id: id, done: false))
        Task {
            do {
                _ = try await core.reopen(taskId: id)
                landBoardTap(id)
                offerEventUndo("Not done “\(title)”", .complete(id))
            } catch {
                landBoardTap(id)
                offerEventUndo(InstantTapRules.shared.failedLine(what: "Not done", reason: error.localizedDescription), nil)
            }
        }
    }

    /// "How long?" on a Board card (Week board, slice 3): the estimate in minutes, or nil to clear it; the undo bar
    /// says what was set ("“Book MOT” · about 30m") and Undo puts the old one back.
    func setEstimateOnBoard(_ id: String, minutes: Int?) {
        guard let core else { return }
        let value = minutes.map { KotlinInt(int: Int32(clamping: $0)) }
        Task {
            guard let line = try? await core.setEstimate(taskId: id, minutes: value) else { return }
            MekaHaptics.tick()
            offerEventUndo(line, .estimate)
        }
    }

    /// Block time on a Board card (Week board, slice 6): its free starts and where the block would go, read as the
    /// pointer reaches the card so the right-click menu has them.
    func blockOffer(_ id: String) async -> BlockOffer? {
        guard let core else { return nil }
        return try? await core.blockOffer(taskId: id)
    }

    /// His click on a Block time start: the task is planned then and its block goes to his calendar, with Undo.
    func blockOnBoard(_ id: String, start: Int64) {
        guard let core else { return }
        Task {
            guard let line = try? await core.blockOnBoard(taskId: id, startMs: start) else { return }
            MekaHaptics.light()
            offerEventUndo(line, .block)
        }
    }

    /// Slice 9: a card dropped on a free time in a day's timeline: moved to that day if elsewhere and blocked where he
    /// let go ([fraction] down the stretch), with Undo; a stretch too short (or no calendar allowing editing) says so.
    func dropOnFreeTime(_ id: String, day: Int64, start: Int64, fraction: Float) {
        guard let core else { return }
        Task {
            guard let result = try? await core.dropOnFreeTime(taskId: id, day: day, freeStartMs: start, fraction: fraction) else { return }
            if result.done {
                MekaHaptics.light()
                offerEventUndo(result.line, .block)
            } else {
                offerEventUndo(result.line, nil)
            }
        }
    }

    // MARK: Week board, slice 7: the goals lane

    /// "For a goal" on a Board card, or a card dropped on a goal: tagged to that goal (nil: no goal), with Undo.
    func tagOnBoard(_ id: String, goal: String?) {
        guard let core else { return }
        Task {
            guard let line = try? await core.tagOnBoard(taskId: id, goalId: goal) else { return }
            MekaHaptics.light()
            offerEventUndo(line, .goalTag)
        }
    }

    /// "+ Goal" on a week's goals lane: the open goals not picked for it yet, read as the menu opens.
    func boardGoalChoices(_ week: Int64) async -> [GoalPickChoice] {
        guard let core else { return [] }
        return (try? await core.boardGoalChoices(weekStart: week)) ?? []
    }

    /// A goal picked for the week on the Board (at most three), with Undo.
    func pickBoardGoal(_ goal: String, week: Int64) {
        guard let core else { return }
        Task {
            guard let line = try? await core.pickBoardGoal(goalId: goal, weekStart: week) else { return }
            MekaHaptics.light()
            offerEventUndo(line, .boardGoal)
        }
    }

    /// New goal… on the lane: a This-week goal picked for that week, with Undo.
    func addBoardGoal(_ title: String, week: Int64) {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !trimmed.isEmpty else { return }
        Task {
            guard let line = try? await core.addBoardGoal(title: trimmed, weekStart: week) else { return }
            MekaHaptics.light()
            offerEventUndo(line, .boardGoal)
        }
    }

    /// Off this week on a lane goal (its tasks keep their tag), with Undo.
    func unpickBoardGoal(_ goal: String, week: Int64) {
        guard let core else { return }
        Task {
            guard let line = try? await core.unpickBoardGoal(goalId: goal, weekStart: week) else { return }
            MekaHaptics.tick()
            offerEventUndo(line, .boardGoal)
        }
    }

    /// The ring on a Board card: ticked off, with Undo.
    func completeOnBoard(_ id: String, title: String) {
        guard let core else { return }
        MekaHaptics.light()
        if boardSelectedID == id { boardSelectedID = nil }
        tapBoard(InstantBoardRules.shared.done(taps: boardTaps, id: id, done: true))
        offerEventUndo("Done “\(title)”", .reopen(id))
        Task {
            do {
                _ = try await core.complete(taskId: id)
                landBoardTap(id)
            } catch {
                landBoardTap(id)
                offerEventUndo(InstantTapRules.shared.failedLine(what: "Done", reason: error.localizedDescription), nil)
            }
        }
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

    /// Fold review 17:56, slice 3: whose club session the event's whole series is ("Theo"; nil clears it), so the
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

    /// Done on one person's card, or Clear all (Meka, 2026-10-15 20:28): they leave the sheet and Needs you at once and
    /// the bar offers Undo for 5 s; then the core clears them here and on the Fold. Only Strings cross to the core.
    func clearAfterWorkCards(_ people: [PersonSummary], line: String) {
        let ids: [String] = people.flatMap { $0.ids }
        guard !ids.isEmpty else { return }
        MekaHaptics.light()
        run { try await $0.clearAfterWorkSoon(ids: ids) }
        offerEventUndo(line, .afterWork(ids))
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
        case .habit(let id, let done): tickHabit(id, done)
        case .matchResult(let saved): run { try await $0.undoMatchResult(saved: saved) }
        case .clubChild(let saved): run { try await $0.undoClubChild(saved: saved) }
        case .decision(let undo): run { _ = try await $0.undoDecision(undo: undo) }
        case .request(let done): run { _ = try await $0.undoRequest(done: done) }
        case .schoolCover(let done): run { _ = try await $0.undoSchoolCover(done: done) }
        case .dateNightBooked(let day): run { _ = try await $0.bookDateNight(day: day, booked: false) }
        case .dateNightSkipped(let day): run { _ = try await $0.skipDateNight(day: day, skip: false) }
        case .groupDigest(let undo): run { try await $0.undoCatchUpGroupDigest(undo: undo) }
        case .afterWork(let ids): run { try await $0.undoClearAfterWork(ids: ids) }
        case .archivedSeen: unhideMail(); run { try await $0.undoSeenArchivedForYou() }
        case .archivedEmail(let batchId, let key, let action): run { try await $0.undoArchivedEmail(batchId: batchId, key: key, action: action) }
        case .mailUnopened: run { try await $0.undoUnopened() }
        case .mailItem: unhideMail(); run { try await $0.undoMailItem() }
        case .mailChase: unhideMail(); run { try await $0.undoMailChase() }
        case .mailFind: unhideMail(); run { try await $0.undoMailFind() }
        case .discord: run { try await $0.undoDiscord() }
        case .discordIdea(let postId): run { try await $0.undoKeepDiscordIdea(postId: postId) }
        case .mailMoment: run { try await $0.undoMailMoment() }
        case .appointment(let done): run { _ = try await $0.undoAppointment(done: done) }
        case .appointmentCall(let call): run { _ = try await $0.undoAppointmentCall(call: call) }
        case .carried(let id): run { _ = try await $0.undoCarried(taskId: id) }
        case .boardMove: run { _ = try await $0.undoBoardMove() }
        case .estimate: run { _ = try await $0.undoEstimate() }
        case .block: run { _ = try await $0.undoBlock() }
        case .goalTag: run { _ = try await $0.undoTagOnBoard() }
        case .boardArea: run { _ = try await $0.undoBoardArea() }
        case .boardList: run { _ = try await $0.undoBoardList() }
        case .boardGoal: run { _ = try await $0.undoBoardGoal() }
        case .lighten: run { _ = try await $0.undoLighten() }
        case .weekPlan: run { _ = try await $0.undoWeekPlan() }
        case .resumeAlerts: run { _ = try await $0.resumeAlerts() }
        case .reopen(let id): run { try await $0.reopen(taskId: id) }
        case .complete(let id): run { try await $0.complete(taskId: id) }
        case .restoreStep(let id): run { try await $0.restoreStep(stepId: id) }
        case .tickSteps(let ids): run { try await $0.tickSteps(stepIds: ids) }
        case .removeSteps(let ids): run { core in for id in ids { try await core.removeStep(stepId: id) } }
        case .removeTemplate(let id): run { _ = try await $0.removeChecklistTemplate(id: id) }
        case .restoreTemplate(let template): run { _ = try await $0.restoreChecklistTemplate(template: template) }
        case .addDocument(let text, let subject): addDocumentFromShared(text, subject)
        case .removeDocument(let id): run { _ = try await $0.removeDocument(id: id) }
        case .reading(let token): run { _ = try await $0.undoReading(token: token) }
        case .unqueue(let id): run { _ = try await $0.removeFromQueue(id: id) }
        case .resumeFast(let id): run { _ = try await $0.resumeFast(id: id) }
        case .unsetAside(let id): needsYouSetAside.removeAll { $0 == id }
        case .unapproveBacklog(let id):
            decidedHere.remove(id)
            run { _ = try await $0.unapproveMailBacklog(id: id) }
        case .junkKeep(let cardId, let key, let keep):
            Task {
                guard let core else { return }
                if let change = try? await core.keepJunkSender(cardId: cardId, key: key, keep: keep), junkReviewCardId == cardId {
                    junkReview = change.review
                }
            }
        case .eventEdit(let id): Task { await undoEventEdit(id) }
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

    /// Add to the Vault on the bar (screenshots and photos, slice 3): the document's name and expiry go in the Vault
    /// and the bar says so, with Undo unless it updated one already there. Only Strings cross to the core.
    private func addDocumentFromShared(_ text: String, _ subject: String?) {
        guard let core else { return }
        Task {
            do {
                guard let added = try await core.addDocumentFromShared(text: text, subject: subject) else { return }
                let undo: EventUndoOffer.Action? = added.updated ? nil : added.id.map { .removeDocument($0) }
                offerEventUndo(added.line, undo)
            } catch { lastError = error.localizedDescription }
        }
    }

    // MARK: About you (rule 13, slice 1)

    /// What saving an About you line said: the line, whether it was turned away, and what Undo writes back.
    struct AboutSaid: Equatable {
        let line: String
        let refused: Bool
        let undo: String?
    }

    private func aboutSaid(_ r: AboutSaved) -> AboutSaid {
        if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
        return AboutSaid(line: r.line, refused: r.refused, undo: r.undo)
    }

    /// Saves one line by its field's key (blank clears it); only Strings cross to the core.
    func setAboutYou(key: String, text: String) async -> AboutSaid? {
        guard let core else { return nil }
        do {
            guard let r = try await core.setAboutYouField(key: key, text: text) else { return nil }
            return aboutSaid(r)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Adds a child typed as "Alex, U7, Oakfield School" (also Undo after a removal); only a String crosses.
    func addAboutYouChild(_ text: String) async -> AboutSaid? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        do {
            return aboutSaid(try await core.addAboutYouChild(text: t))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Takes a child off by position; only an Int crosses.
    func removeAboutYouChild(_ index: Int32) async -> AboutSaid? {
        guard let core else { return nil }
        do {
            return aboutSaid(try await core.removeAboutYouChild(index: index))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// MEKA watches the partner's messages for requests, or not (only a Bool crosses).
    func setAboutYouPartnerWatch(_ on: Bool) { MekaHaptics.tick(); run { _ = try await $0.setAboutYouPartnerWatch(on: on) } }

    // MARK: Emergency card (slice 1)

    // MARK: Money manager (slice 1)

    /// What an import, a category change or a removal said, and the token Undo hands back.
    struct MoneySaid: Equatable {
        let line: String
        let refused: Bool
        let undo: String?
    }

    private func moneySaid(_ r: MoneySaved) -> MoneySaid {
        if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
        return MoneySaid(line: r.line, refused: r.refused, undo: r.undo)
    }

    /// After Touch ID's yes (MoneySheet asks): the core works out this month's figures. Nothing but the call crosses.
    func openMoney() {
        moneyYesAt = ProcessInfo.processInfo.systemUptime
        run { try await $0.openMoney() }
    }

    /// Part (4): when Touch ID said yes, until the sheet's first content shows (aim 300 ms).
    @ObservationIgnored private var moneyYesAt: TimeInterval?

    private func moneyFirstShown() {
        guard let at = moneyYesAt, let m = coreMoney, !m.locked else { return }
        moneyYesAt = nil
        noteFeelAfterFrame(.screen, since: at, place: MoneyRules.shared.TITLE)
    }

    /// Touch ID said no: the locked sheet says so (Speed 4c). Synchronous; nothing but the call crosses.
    func moneyUnlockRefused() { core?.moneyUnlockRefused(mac: true) }

    /// Locks Money again (the sheet closed or the app went to the background).
    func closeMoney() { run { try await $0.closeMoney() } }

    // Money manager (16): money questions in Ask and Talk, answered on this Mac from his own Money, behind Touch ID.
    /// The question to ask again once Touch ID said yes; AskMekaSection asks it when `moneyAskUnlocks` changes.
    private(set) var moneyAskAgain: String?
    private(set) var moneyAskUnlocks = 0

    /// "Unlock Money to answer": Touch ID (or the Mac's password), then the core answers money questions for five minutes
    /// and Ask asks `question` again. Only a String crosses into the core.
    func unlockMoneyAsk(question: String) {
        MekaHaptics.tick()
        MacBiometric.unlock(reason: MoneyAskRules.shared.UNLOCK_REASON) { [weak self] ok in
            guard ok else { MekaHaptics.tick(); return }
            MekaHaptics.light()
            Task { await self?.finishMoneyUnlock(question) }
        }
    }

    private func finishMoneyUnlock(_ question: String) async {
        guard let core else { return }
        _ = try? await core.unlockMoneyAsk()
        moneyAskAgain = question
        moneyAskUnlocks += 1
    }

    /// Ends an unlock from Ask at once (the screen slept or the session went away).
    func lockMoneyAsk() { run { try await $0.lockMoneyAsk() } }

    /// ‹ (−1) or › (+1) a month.
    func moneyStep(_ step: Int32) { MekaHaptics.tick(); run { try await $0.moneyStep(step: step) } }

    /// Imports a statement file's text (CSV) into the named account. Only Strings cross.
    func importMoneyStatement(account: String, text: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.importMoneyStatement(account: account, text: text))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Puts every payment to that line's shop in another category from now on (Undo with `undoMoneyCategory`).
    func setMoneyCategory(txnId: String, category: String) async -> MoneySaid? {
        guard let core else { return nil }
        let tap = InstantPaymentRules.shared.categoryTap(txnId: txnId, key: category)
        holdPaymentTap(tap)
        do {
            let said = moneySaid(try await core.setMoneyCategory(txnId: txnId, category: category))
            landPaymentTap(tap)
            return said
        } catch {
            landPaymentTap(tap)
            lastError = InstantPaymentRules.shared.failedLine(tap: tap, reason: error.localizedDescription)
            return nil
        }
    }

    /// Money as drawn: the core's view with the payment chips still on their way (taps feel instant).
    private func showMoneyView() {
        guard let coreMoney else { money = nil; return }
        money = paymentTaps.isEmpty ? coreMoney : InstantPaymentRules.shared.shown(view: coreMoney, taps: paymentTaps)
    }

    /// A payment chip shows in the same frame; the core's write follows.
    private func holdPaymentTap(_ tap: PaymentTap) {
        paymentTaps = InstantPaymentRules.shared.add(taps: paymentTaps, tap: tap)
        showMoneyView()
    }

    /// Its call returned: the core's view (with any newer tap) shows; a refused or failed one puts the payment back.
    private func landPaymentTap(_ tap: PaymentTap) {
        paymentTaps = InstantPaymentRules.shared.forget(taps: paymentTaps, tap: tap)
        showMoneyView()
    }

    /// Slice 2: hides every tip of that kind ("habit", "over", "rise") on both devices; Undo with `undoMoneyCategory`.
    func hideMoneyTips(kind: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.hideMoneyTips(kind: kind))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 4: the forecast's "Halve <category>" what-if on or off (this Mac only, never stored). Only a String crosses.
    func moneyForecastCut(_ category: String) { MekaHaptics.tick(); run { try await $0.moneyForecastCut(category: category) } }

    /// Slice 4: the forecast's extra a month ("£200", "£50 a week"); blank clears it. This Mac only. Only a String crosses.
    func moneyForecastExtra(_ text: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyForecastExtra(text: text))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 6: the pay account's balance as he typed it (blank clears it); Undo with `undoMoneyCategory`.
    func moneyBalance(accountId: String, text: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyBalance(accountId: accountId, text: text))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 8: one thing typed for his net worth ("Pension £42,000"; the same name again updates it); Undo with `undoMoneyCategory`.
    func moneyWorthAdd(text: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyWorthAdd(text: text))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Bank sync: Refresh — asks Navilife's server to fetch his banks through Lunch Flow now. Nothing but the call crosses.
    func moneyBankRefresh() async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyBankRefresh())
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Bank sync: the bank accounts he removed are synced again.
    func moneyBankRestore() async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyBankRestore())
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 8: takes a typed thing off his net worth; Undo with `undoMoneyCategory`.
    /// Trading tracker: a Hyperliquid public address typed in Money ("Main 0x…"); Undo with `undoMoneyCategory`.
    func tradingWalletAdd(text: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.tradingWalletAdd(text: text))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Trading tracker, slice 3: one of the ALERTS choices (kind "liq" or "drop", key "off", "5", "10", "20", "30").
    func setTradingAlert(kind: String, key: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            let line = try await core.setTradingAlert(kind: kind, key: key)
            MekaHaptics.light()
            return MoneySaid(line: line, refused: false, undo: nil)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Trading tracker, slice 8: what EXPORT FOR TAX came to — the line, and the CSV with its file name when there's one to save.
    struct TradingExportFile {
        let said: MoneySaid
        let csv: String?
        let fileName: String?
    }

    /// Trading tracker, slice 8: a UK tax year ("2025-26") of his followed accounts' trades and funding as a CSV, read by
    /// Navilife's server from Hyperliquid's public data (this can take a minute). Only while Money is open.
    func tradingExport(key: String) async -> TradingExportFile? {
        guard let core else { return nil }
        do {
            let r = try await core.tradingExport(yearKey: key)
            return TradingExportFile(said: moneySaid(r.said), csv: r.csv, fileName: r.fileName)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Money slice 13b: a UK tax year ("2025-26") of the side hustle's lines with totals by category as a CSV, made from
    /// his own statements on the Mac. Only while Money is open.
    func moneyBusinessExport(key: String) async -> TradingExportFile? {
        guard let core else { return nil }
        do {
            let r = try await core.moneyBusinessExport(yearKey: key)
            return TradingExportFile(said: moneySaid(r.said), csv: r.csv, fileName: r.fileName)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Trading tracker: stops following an address on every device; Undo with `undoMoneyCategory`.
    func tradingWalletRemove(id: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.tradingWalletRemove(id: id))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func moneyWorthRemove(id: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyWorthRemove(id: id))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 7: marks an account as a card (`card` false: no longer one), a charge card when `charge`; Undo with `undoMoneyCategory`.
    /// Money slice 13: Side hustle account on an account (synced; Undo through undoMoneyCategory).
    func moneyBusinessAccount(accountId: String, on: Bool) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyBusinessAccount(accountId: accountId, on: on))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Money slice 13: Business on a payment's payer, on every account (synced; Undo through undoMoneyCategory).
    func moneyBusinessMerchant(txnId: String, on: Bool) async -> MoneySaid? {
        guard let core else { return nil }
        let tap = InstantPaymentRules.shared.businessTap(txnId: txnId, on: on)
        holdPaymentTap(tap)
        do {
            let said = moneySaid(try await core.moneyBusinessMerchant(txnId: txnId, on: on))
            landPaymentTap(tap)
            return said
        } catch {
            landPaymentTap(tap)
            lastError = InstantPaymentRules.shared.failedLine(tap: tap, reason: error.localizedDescription)
            return nil
        }
    }

    /// Money slice 15: counting no-spend days on or off (synced; Undo through undoMoneyCategory).
    func moneyNoSpend(on: Bool) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyNoSpend(on: on))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Money slice 15: fun money a week ("£50"; blank stops it), synced.
    func moneyFunMoney(text: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyFunMoney(text: text))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Money slice 15: a monthly cap on a category ("Eating out £150"), synced.
    func moneyCap(text: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyCap(text: text))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Money slice 15: takes a cap (its category's key) or fun money ("fun") off.
    /// Money manager (6): a £ goal follows an account's balance (nil stops it), synced; Undo through undoMoneyCategory.
    func moneyGoalFollow(goalId: String, accountId: String?) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyGoalFollow(goalId: goalId, accountId: accountId))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func moneyChallengeRemove(key: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyChallengeRemove(key: key))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Money slice 14: Shared on one payment (synced; Undo through undoMoneyCategory).
    func moneySharedTxn(txnId: String, on: Bool) async -> MoneySaid? {
        guard let core else { return nil }
        let tap = InstantPaymentRules.shared.sharedTap(txnId: txnId, on: on)
        holdPaymentTap(tap)
        do {
            let said = moneySaid(try await core.moneySharedTxn(txnId: txnId, on: on))
            landPaymentTap(tap)
            return said
        } catch {
            landPaymentTap(tap)
            lastError = InstantPaymentRules.shared.failedLine(tap: tap, reason: error.localizedDescription)
            return nil
        }
    }

    /// Money slice 14: Always shared on a payment's payer, on every account.
    func moneySharedMerchant(txnId: String, on: Bool) async -> MoneySaid? {
        guard let core else { return nil }
        let tap = InstantPaymentRules.shared.alwaysTap(txnId: txnId, on: on)
        holdPaymentTap(tap)
        do {
            let said = moneySaid(try await core.moneySharedMerchant(txnId: txnId, on: on))
            landPaymentTap(tap)
            return said
        } catch {
            landPaymentTap(tap)
            lastError = InstantPaymentRules.shared.failedLine(tap: tap, reason: error.localizedDescription)
            return nil
        }
    }

    /// Money slice 14: his share of the shared costs ("50"), synced.
    func moneySharedShare(key: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneySharedShare(key: key))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Money slice 14: what his partner paid ("Tesco £42.50"), synced.
    func moneySharedAdd(text: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneySharedAdd(text: text))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Money slice 14: takes one of his partner's payments off.
    func moneySharedRemove(id: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneySharedRemove(id: id))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Money slice 14: Settled up, counting again from today.
    func moneySharedSettle() async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneySharedSettle())
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Money slice 13: the share of the side hustle's profit to put aside ("20"), synced.
    func moneySetAside(key: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneySetAside(key: key))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func moneyCardKind(accountId: String, card: Bool, charge: Bool) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyCardKind(accountId: accountId, card: card, charge: charge))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 7: one card field typed off its statement (`statement`, `minimum`, `due`, `limit`, `promo`; blank clears it).
    func moneyCardField(accountId: String, field: String, text: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyCardField(accountId: accountId, field: field, text: text))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 7: Paid on a card's next payment (its reminder stops until the next one); Undo with `undoMoneyCategory`.
    func moneyCardPaid(accountId: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyCardPaid(accountId: accountId))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 3: That's fine on one watchdog alert, on both devices; Undo with `undoMoneyCategory`.
    func moneyAlertFine(alertId: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyAlertFine(alertId: alertId))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 5: Close it on a refund that's back in his statement (Waiting for's row as received); Undo with `undoMoneyCategory`.
    func moneyRefundClose(waitingId: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyRefundClose(waitingId: waitingId))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 5: after Copy the chase put the words on the pasteboard, marks the refund chased (next look in a week).
    func moneyRefundChased(waitingId: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.moneyRefundChased(waitingId: waitingId))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 2: every hidden kind of tip shown again.
    func showMoneyTips() async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.showMoneyTips())
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func undoMoneyCategory(token: String) async -> Bool {
        guard let core else { return false }
        let ok = (try? await core.undoMoneyCategory(token: token))?.boolValue ?? false
        MekaHaptics.light()
        return ok
    }

    func removeMoneyAccount(id: String) async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.removeMoneyAccount(accountId: id))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func deleteAllMoney() async -> MoneySaid? {
        guard let core else { return nil }
        do {
            return moneySaid(try await core.deleteAllMoney())
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    // MARK: Kids' admin (slice 1)

    /// What a size save said: the line under the field, whether it was turned away, and what Undo puts back.
    struct KidSaid: Equatable {
        let line: String
        let refused: Bool
        let undo: String?
        /// Slice 2: the club fee Undo takes back (after Paid or Remove).
        var feeUndo: String? = nil
        /// Slice 5: the token Undo hands back for a removed chore, a pocket money change or a Paid.
        var moneyUndo: String? = nil
    }

    /// Kids' admin, slice 5: chores and pocket money. Each call sends only Strings and a day number to the core; the
    /// answer becomes a `KidSaid` (a light haptic, or a tick when turned away).
    private func kidSaid(_ r: KidSaved) -> KidSaid {
        if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
        return KidSaid(line: r.line, refused: r.refused, undo: nil, moneyUndo: r.moneyUndo)
    }

    /// Adds a chore from one typed line ("Tidy room £1", "Feed the cat").
    func addKidChore(child: String, text: String) async -> KidSaid? {
        guard let core else { return nil }
        do {
            return kidSaid(try await core.addKidChore(child: child, text: text))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Takes a chore off the card; Undo with `undoKidMoney`.
    func removeKidChore(id: String) async -> KidSaid? {
        guard let core else { return nil }
        do {
            return kidSaid(try await core.removeKidChore(choreId: id))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Ticks (or unticks) a chore on a day of this week up to today.
    func tickKidChore(id: String, day: Int64) async -> KidSaid? {
        guard let core else { return nil }
        do {
            return kidSaid(try await core.tickKidChore(choreId: id, day: day))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// A child's pocket money a week ("£5"); empty stops it. Undo with `undoKidMoney`.
    func setKidPocketMoney(child: String, text: String) async -> KidSaid? {
        guard let core else { return nil }
        do {
            return kidSaid(try await core.setKidPocketMoney(child: child, text: text))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Notes this week's pocket money and chore money as given (Navilife never pays); Undo with `undoKidMoney`.
    func kidPocketMoneyPaid(child: String) async -> KidSaid? {
        guard let core else { return nil }
        do {
            return kidSaid(try await core.kidPocketMoneyPaid(child: child))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Undo for a removed chore, a pocket money change or a Paid; false when there's nothing to take back.
    func undoKidMoney(token: String) async -> Bool {
        guard let core else { return false }
        let ok = (try? await core.undoKidMoney(token: token))?.boolValue ?? false
        MekaHaptics.light()
        return ok
    }

    /// Kids' admin, slice 2: adds a club fee for a child from one typed line ("Football £35 monthly from 1 Nov"); it goes
    /// on the radar as a bill. Only Strings cross to the core.
    func addKidFee(child: String, text: String) async -> KidSaid? {
        guard let core else { return nil }
        do {
            let r = try await core.addKidFee(child: child, text: text)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return KidSaid(line: r.line, refused: r.refused, undo: nil)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// A child's club fee paid (moves on to its next time, or closes); Undo with `undoKidFee`.
    func kidFeePaid(id: String) async -> KidSaid? {
        guard let core else { return nil }
        do {
            let r = try await core.kidFeePaid(feeId: id)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return KidSaid(line: r.line, refused: r.refused, undo: nil, feeUndo: r.feeUndo)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Takes a child's club fee off the card and the radar; Undo with `undoKidFee`.
    func removeKidFee(id: String) async -> KidSaid? {
        guard let core else { return nil }
        do {
            let r = try await core.removeKidFee(feeId: id)
            MekaHaptics.light()
            return KidSaid(line: r.line, refused: r.refused, undo: nil, feeUndo: r.feeUndo)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Undo for Paid or Remove on a club fee; false when it changed since.
    func undoKidFee(id: String) async -> Bool {
        guard let core else { return false }
        let ok = (try? await core.undoKidFee(feeId: id))?.boolValue ?? false
        MekaHaptics.light()
        return ok
    }

    /// Keeps one size for a child (blank clears it); only Strings cross to the core.
    func setKidSize(child: String, size: String, text: String) async -> KidSaid? {
        guard let core else { return nil }
        do {
            let r = try await core.setKidSize(child: child, size: size, text: text)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return KidSaid(line: r.line, refused: r.refused, undo: r.undo)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// "All still fit": the child's sizes count as checked today. Returns the line to show, nil when nothing is kept.
    func kidSizesFit(child: String) async -> String? {
        guard let core else { return nil }
        do {
            let line = try await core.kidSizesFit(child: child)
            if line != nil { MekaHaptics.light() }
            return line
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

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

    /// Services → "Save for the train with Navilife" (train-time queue completeness): the selection's first web link
    /// goes in the train queue; the bar says so, with Undo when it made a new one. Only Strings cross to the core.
    func addToQueueShared(_ text: String, subject: String? = nil) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return }
        Task {
            do {
                let r = try await core.addToQueue(text: t, subject: subject)
                if r.refused {
                    MekaHaptics.tick()
                    offerEventUndo(r.line, nil)
                } else {
                    MekaHaptics.light()
                    let undo: EventUndoOffer.Action? = r.created ? r.id.map { EventUndoOffer.Action.unqueue($0) } : nil
                    offerEventUndo(r.line + " · in Ask → More → Train queue", undo)
                }
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Done when read or listened to (`done` true), or Back into the queue.
    func queueDone(_ id: String, done: Bool) {
        if done { MekaHaptics.light() } else { MekaHaptics.tick() }
        run { _ = try await $0.queueDone(id: id, done: done) }
    }

    func removeFromQueue(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeFromQueue(id: id) } }

    // MARK: Reading (reading and learning, slice 1)

    /// What a reading command said: the line under the field, whether it was turned away, and its Undo token.
    struct ReadingSaidLine: Equatable {
        let line: String
        let refused: Bool
        let undo: String?
    }

    /// Runs one reading command; only Strings cross to the core and back.
    private func reading(_ light: Bool = false, _ body: @escaping (MekaCore) async throws -> ReadingSaid) async -> ReadingSaidLine? {
        guard let core else { return nil }
        do {
            let r = try await body(core)
            if r.refused { MekaHaptics.tick() } else if light { MekaHaptics.light() } else { MekaHaptics.tick() }
            return ReadingSaidLine(line: r.line, refused: r.refused, undo: r.undo)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Services → "Add to Navilife Reading" (reading and learning, slice 3): the selection (a title, or a book, podcast
    /// or course link) goes under Reading's UP NEXT; the bar says where, with Undo. A plain line is taken as typed; a
    /// link only from a book, podcast or course site. One call to the core.
    func addReadingShared(_ text: String, subject: String? = nil) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return }
        Task {
            do {
                // A plain line goes in as typed; a link only from a book, podcast or course site (the core decides).
                let r = try await core.addReadingFromShared(text: t, subject: subject)
                if r.refused {
                    offerEventUndo(r.line, nil)
                } else {
                    MekaHaptics.light()
                    offerEventUndo(r.line + " · in Ask → Daily → Reading", r.undo.map { EventUndoOffer.Action.reading($0) })
                }
            } catch { lastError = error.localizedDescription }
        }
    }

    func addReading(_ text: String) async -> ReadingSaidLine? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return nil }
        return await reading(true) { try await $0.addReading(text: t, subject: nil) }
    }

    func startReading(_ id: String) async -> ReadingSaidLine? { await reading(true) { try await $0.startReading(id: id) } }

    func readingProgress(_ id: String, _ text: String) async -> ReadingSaidLine? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return nil }
        return await reading(true) { try await $0.readingProgress(id: id, text: t) }
    }

    func finishReading(_ id: String) async -> ReadingSaidLine? { await reading(true) { try await $0.finishReading(id: id) } }

    func putDownReading(_ id: String) async -> ReadingSaidLine? { await reading { try await $0.putDownReading(id: id) } }

    func removeReading(_ id: String) async -> ReadingSaidLine? { await reading { try await $0.removeReading(id: id) } }

    /// The reading goal (slice 4): books a year ("12", "24 books"; "none" clears it), synced so Goals tracks it.
    func setReadingGoal(_ text: String) async -> ReadingSaidLine? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return nil }
        return await reading(true) { try await $0.setReadingGoal(text: t) }
    }

    /// Takes back the last reading command; false once too late.
    func undoReading(_ token: String) async -> Bool {
        guard let core else { return false }
        MekaHaptics.tick()
        do { return try await core.undoReading(token: token).boolValue } catch { lastError = error.localizedDescription; return false }
    }

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

    /// Balance view (slice 1): sets an area's weekly target ("FAMILY", 600); only a String and an Int cross. Returns the
    /// line to show, or nil when nothing changed.
    func setBalanceTarget(_ area: String, minutes: Int) async -> String? {
        guard let core else { return nil }
        do {
            let line = try await core.setBalanceTarget(area: area, minutes: Int32(minutes))
            if line != nil { MekaHaptics.light() }
            return line
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Balance view (slice 5): "Count as …" for events titled like `title` ("FAMILY", "HEALTH", "YOURSELF", "WORK",
    /// "NONE" = don't count, "CLEAR" = forget); only Strings cross. Returns the line to show, or nil when nothing changed.
    func balanceCountAs(_ title: String, area: String) async -> String? {
        guard let core else { return nil }
        do {
            let line = try await core.balanceCountAs(title: title, area: area)
            if line != nil { MekaHaptics.light() }
            return line
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 5: Undo for the last "Count as …"; false when there was nothing to undo.
    func balanceUndoCountAs() async -> Bool {
        guard let core else { return false }
        do {
            let undone = try await core.balanceUndoCountAs().boolValue
            if undone { MekaHaptics.tick() }
            return undone
        } catch {
            lastError = error.localizedDescription
            return false
        }
    }

    func removeWin(_ id: String) { MekaHaptics.tick(); run { _ = try await $0.removeWin(id: id) } }

    // MARK: Weekly reflection (slice 1)

    /// What Save said: the line under the fields and whether it was turned away.
    struct ReflectionSaid: Equatable {
        let line: String
        let refused: Bool
    }

    /// Saves the review week's reflection; only Strings and a Bool cross to the core.
    func saveReflection(wentWell: String, drop: String, matters: String, goalId: String?, keepWins: Bool) async -> ReflectionSaid? {
        guard let core else { return nil }
        do {
            let r = try await core.saveReflection(wentWell: wentWell, drop: drop, matters: matters, goalId: goalId, keepWins: keepWins)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return ReflectionSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// "Skip this week" (true) or, after a skip, "Reflect now" (false).
    func skipReflection(_ on: Bool) { MekaHaptics.tick(); run { try await $0.skipReflection(on: on) } }

    /// Slice 3: when on Sunday the review and reflection come (minutes after midnight); only an Int32 crosses. Returns
    /// what to say ("Your review and reflection now come on Sundays at 19:00"), or nil when nothing changed.
    func setReviewTime(_ minute: Int) async -> String? {
        guard let core else { return nil }
        MekaHaptics.tick()
        do {
            return try await core.setReviewTime(minute: Int32(minute))
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Takes one week's reflection out of the journal (true), or puts it back (Undo, false).
    func removeReflection(_ weekStart: Int64, removed: Bool) {
        if removed { MekaHaptics.tick() } else { MekaHaptics.light() }
        run { _ = try await $0.removeReflection(weekStart: weekStart, removed: removed) }
    }

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

    /// Protected time, slice 3: business mail waits till morning (`hold`) or shows as it comes; only a Bool crosses.
    func setProtectedMail(hold: Bool) {
        MekaHaptics.tick()
        run { _ = try await $0.setProtectedMail(hold: hold) }
    }

    /// Protected time, slice 4: "Not protected tonight" (`open`) lifts today's protected time just this once; false is
    /// Undo. Only a Bool crosses.
    func setProtectedOpenToday(open: Bool) {
        MekaHaptics.tick()
        run { _ = try await $0.setProtectedOpenToday(open: open) }
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

    /// Bin days completeness: the evening reminder's time (synced, so the Fold reminds at it too); only an Int32 crosses.
    func setBinReminder(minute: Int) {
        MekaHaptics.tick()
        let m = Int32(minute)
        run { _ = try await $0.setBinReminder(minute: m) }
    }

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

    /// Travel mode completeness slice 4: what × or Remove trip took, with the token Undo gives back (Strings only).
    struct TripUndo: Equatable {
        let line: String
        let token: String
    }

    /// Removes a trip and its bookings; "Removed Barcelona and its 3 bookings" with Undo's token. Only Strings cross.
    func removeTripForUndo(_ id: String) async -> TripUndo? {
        guard let core else { return nil }
        MekaHaptics.tick()
        do {
            guard let r = try await core.removeTripForUndo(id: id) else { return nil }
            return TripUndo(line: r.line, token: r.token)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Removes one booking; "Removed Flight BA478 from Barcelona" with Undo's token.
    func removeTripBookingForUndo(_ id: String) async -> TripUndo? {
        guard let core else { return nil }
        MekaHaptics.tick()
        do {
            guard let r = try await core.removeTripBookingForUndo(id: id) else { return nil }
            return TripUndo(line: r.line, token: r.token)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Undo: puts back exactly what the removal took.
    func undoTripRemoval(_ token: String) { MekaHaptics.light(); run { _ = try await $0.undoTripRemoval(token: token) } }

    // MARK: Study plan (slice 1)

    /// What adding a course or lessons said, and the course it went on.
    struct StudySaid: Equatable {
        let line: String
        let refused: Bool
        let courseId: String?
    }

    /// Adds a course named by the typed line; only a String crosses. The sheet opens it once it's there.
    func addStudyCourse(_ text: String) async -> StudySaid? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        do {
            let r = try await core.addStudyCourse(text: t)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return StudySaid(line: r.line, refused: r.refused, courseId: r.courseId)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Adds the lessons a pasted outline lists; Strings cross once.
    func addStudyLessons(_ courseId: String, _ text: String) async -> StudySaid? {
        guard let core, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        do {
            let r = try await core.addStudyLessons(courseId: courseId, text: text)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return StudySaid(line: r.line, refused: r.refused, courseId: r.courseId)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Ticks or unticks one lesson; the sheet already shows it (the rule for every tap), this writes it after.
    func tickStudyLesson(_ id: String, done: Bool) async -> Bool {
        guard let core else { return false }
        do { _ = try await core.tickStudyLesson(id: id, done: done); return true } catch {
            lastError = error.localizedDescription
            return false
        }
    }

    /// Removes one lesson: "Removed Pivot tables" with Undo's token.
    func removeStudyLesson(_ id: String) async -> TripUndo? {
        guard let core else { return nil }
        MekaHaptics.tick()
        do {
            guard let r = try await core.removeStudyLesson(id: id) else { return nil }
            return TripUndo(line: r.line, token: r.token)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Removes a course and its lessons: "Removed Data analytics" with Undo's token.
    func removeStudyCourse(_ id: String) async -> TripUndo? {
        guard let core else { return nil }
        MekaHaptics.tick()
        do {
            guard let r = try await core.removeStudyCourse(id: id) else { return nil }
            return TripUndo(line: r.line, token: r.token)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func undoStudyRemoval(_ token: String) { MekaHaptics.light(); run { _ = try await $0.undoStudyRemoval(token: token) } }

    // MARK: Study plan (slice 2a): the plan card. Only Strings and numbers cross; the sheet already shows the tap.

    func setStudyFinish(_ courseId: String, day: Int64) async -> Bool {
        guard let core else { return false }
        do { _ = try await core.setStudyFinish(courseId: courseId, epochDay: day); return true } catch {
            lastError = error.localizedDescription
            return false
        }
    }

    func setStudySession(_ courseId: String, mins: Int32) async -> Bool {
        guard let core else { return false }
        do { _ = try await core.setStudySession(courseId: courseId, mins: mins); return true } catch {
            lastError = error.localizedDescription
            return false
        }
    }

    func setStudySlot(_ courseId: String, key: String, on: Bool) async -> Bool {
        guard let core else { return false }
        do { _ = try await core.setStudySlot(courseId: courseId, key: key, on: on); return true } catch {
            lastError = error.localizedDescription
            return false
        }
    }

    // MARK: Trip lists (slice 1)

    /// What adding to a trip's lists said, and the list it went on (the panel shows that tab).
    struct TripListSaid: Equatable {
        let line: String
        let refused: Bool
        let list: TripList
        /// Editing slice 4a: the new thing's id, and the groups to offer when it matched none.
        var id: String? = nil
        var askGroups: [String] = []
    }

    /// Adds "buy sun cream", "pack 2 chargers" or anything else to the open tab; Strings and the tab cross once.
    func addTripItem(_ tripId: String, _ text: String, open: TripList) async -> TripListSaid? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        do {
            let r = try await core.addTripItem(tripId: tripId, text: t, open: open)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return TripListSaid(line: r.line, refused: r.refused, list: r.list, id: r.id, askGroups: r.askGroups)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Ticks or unticks one item; the panel already shows it (the rule for every tap), this writes it after.
    func tickTripItem(_ id: String, done: Bool) async -> Bool {
        guard let core else { return false }
        do { _ = try await core.tickTripItem(id: id, done: done); return true } catch {
            lastError = error.localizedDescription
            return false
        }
    }

    /// Removes one item: "Removed Sun cream" with Undo's token.
    func removeTripItem(_ id: String) async -> TripUndo? {
        guard let core else { return nil }
        MekaHaptics.tick()
        do {
            guard let r = try await core.removeTripItem(id: id) else { return nil }
            return TripUndo(line: r.line, token: r.token)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func undoTripItemRemoval(_ token: String) { MekaHaptics.light(); run { _ = try await $0.undoTripItemRemoval(token: token) } }

    // MARK: Trip lists editing (slice 4a): the groups are his

    /// What a group change said ("Moved Blender to Kitchen"); `token` is a deleted group's Undo.
    struct TripGroupSaid: Equatable {
        let line: String
        let refused: Bool
        let token: String
    }

    private static func said(_ r: TripGroupDone?) -> TripGroupSaid? {
        r.map { TripGroupSaid(line: $0.line, refused: $0.refused, token: $0.token) }
    }

    /// Moves one thing to the group `group` (a new name makes it); the panel already shows it there.
    func moveTripItem(_ id: String, to group: String) async -> TripGroupSaid? {
        guard let core else { return nil }
        do { return Self.said(try await core.moveTripItem(id: id, group: group)) } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// The item sheet: a new name, how many, or whose (nil keeps that as it is).
    func editTripItem(_ id: String, text: String? = nil, qty: Int? = nil, who: TripWho? = nil) async -> TripGroupSaid? {
        guard let core else { return nil }
        do { return Self.said(try await core.editTripItem(id: id, text: text, qty: Self.k(qty), who: who)) } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func renameTripGroup(_ tripId: String, list: TripList, old: String, name: String) async -> TripGroupSaid? {
        guard let core else { return nil }
        do { return Self.said(try await core.renameTripGroup(tripId: tripId, list: list, old: old, name: name)) } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func addTripGroup(_ tripId: String, list: TripList, name: String) async -> TripGroupSaid? {
        guard let core else { return nil }
        do { return Self.said(try await core.addTripGroup(tripId: tripId, list: list, name: name)) } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Deletes a group: its things go to Other; the result's token is Undo's.
    func deleteTripGroup(_ tripId: String, list: TripList, name: String) async -> TripGroupSaid? {
        guard let core else { return nil }
        do { return Self.said(try await core.deleteTripGroup(tripId: tripId, list: list, name: name)) } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func undoTripGroupDelete(_ tripId: String, token: String) {
        MekaHaptics.light()
        run { _ = try await $0.undoTripGroupDelete(tripId: tripId, token: token) }
    }

    /// Puts a list's groups in the order `names` (after Move up / Move down); Other stays last.
    func setTripGroupOrder(_ tripId: String, list: TripList, names: [String]) async -> Bool {
        guard let core else { return false }
        do { return try await core.setTripGroupOrder(tripId: tripId, list: list, names: names).boolValue } catch {
            lastError = error.localizedDescription
            return false
        }
    }

    /// What "Start from last trip" did (slice 2b): "Copied 23 things from Lisbon trip" and Undo's token.
    struct TripListCopy: Equatable {
        let line: String
        let token: String
        let refused: Bool
    }

    /// Copies the trip `fromTripId`'s lists onto `toTripId`'s empty ones, unticked; only Strings cross.
    func copyTripLists(from fromTripId: String, to toTripId: String) async -> TripListCopy? {
        guard let core else { return nil }
        do {
            let r = try await core.doCopyTripLists(fromTripId: fromTripId, toTripId: toTripId)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return TripListCopy(line: r.line, token: r.token, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    func undoTripListsCopy(_ token: String) { MekaHaptics.light(); run { _ = try await $0.undoTripListsCopy(token: token) } }

    /// Slice 2e: what "Save as a template for next time" said ("Saved as a template · 23 things").
    struct TripTemplateSaid: Equatable {
        let line: String
        let refused: Bool
    }

    /// Saves the trip's lists (never the ticks) as a template for next time; only a String crosses.
    func saveTripTemplate(_ tripId: String) async -> TripTemplateSaid? {
        guard let core else { return nil }
        do {
            let r = try await core.saveTripTemplate(tripId: tripId)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return TripTemplateSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 2e: starts the trip's empty lists from a saved template, unticked (Undo with undoTripListsCopy); Strings cross.
    func startTripFromTemplate(_ templateId: String, to toTripId: String) async -> TripListCopy? {
        guard let core else { return nil }
        do {
            let r = try await core.startTripFromTemplate(templateId: templateId, toTripId: toTripId)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return TripListCopy(line: r.line, token: r.token, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 2e: forgets a saved template on every device.
    func forgetTripTemplate(_ templateId: String) { MekaHaptics.tick(); run { _ = try await $0.forgetTripTemplate(templateId: templateId) } }

    /// Slice 2d: puts one person on or off the trip's who's going (`goer` a wire: "me", "partner", "kids"); only
    /// Strings and a Bool cross. The panel lights the chip at once; this writes it after.
    func setTripGoing(_ tripId: String, goer: String, on: Bool) async -> Bool {
        guard let core else { return false }
        do { _ = try await core.setTripGoing(tripId: tripId, goer: goer, on: on); return true } catch {
            lastError = error.localizedDescription
            return false
        }
    }

    /// Slice 3b, Obi's suggestions: adds the chips `keys` of the trip's `list` card ("Added 3 things to To pack") with
    /// Undo's token (undoTripListsCopy); only Strings and the tab cross, once.
    func addTripSuggestions(_ tripId: String, list: TripList, keys: [String]) async -> TripListCopy? {
        guard let core, !keys.isEmpty else { return nil }
        MekaHaptics.light()
        do {
            let r = try await core.addTripSuggestions(tripId: tripId, list: list, keys: keys)
            if r.refused { MekaHaptics.tick() }
            return TripListCopy(line: r.line, token: r.token, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Slice 3b: "Not now" puts the card's chips away on this trip, on every device.
    func dismissTripSuggestions(_ tripId: String, keys: [String]) {
        guard !keys.isEmpty else { return }
        MekaHaptics.tick()
        run { _ = try await $0.dismissTripSuggestions(tripId: tripId, keys: keys) }
    }

    /// What setting the leave time said: "Leaving home 06:30 on Wed 28 Oct", or why not.
    struct TripLeaveSaid: Equatable {
        let line: String
        let refused: Bool
    }

    /// Slice 2d: the time he leaves home on the trip's first day ("06:30", "6.30am"); blank clears it.
    func setTripLeave(_ tripId: String, _ text: String) async -> TripLeaveSaid? {
        guard let core else { return nil }
        do {
            let r = try await core.setTripLeave(tripId: tripId, text: text.trimmingCharacters(in: .whitespacesAndNewlines))
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return TripLeaveSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

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

    // MARK: The Vault's copies (Vault slice 2b)

    /// Add a copy… / Replace copy…: an open panel for one photo or PDF, prepared off the main actor and handed to the
    /// core as Strings, which seals it with the Vault key. What it said goes under the row.
    func chooseVaultCopy(_ docId: String) async -> TripSaid? {
        NSApp.activate()
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.jpeg, .png, .pdf]
        panel.allowsMultipleSelection = false
        panel.message = "Pick a photo or PDF of the document · it's locked with your Vault key on this Mac before it leaves"
        panel.prompt = "Add copy"
        guard panel.runModal() == .OK, let url = panel.url else { return nil }
        let picked = await MacVaultCopy.prepare(url: url)
        guard let core, let b64 = picked.base64 else {
            MekaHaptics.tick()
            return TripSaid(line: picked.problem ?? VaultFileRules.shared.COULDNT_READ, refused: true)
        }
        do {
            let r = try await core.addVaultCopy(docId: docId, type: picked.type, base64: b64, name: url.lastPathComponent)
            if r.refused { MekaHaptics.tick() } else { MekaHaptics.light() }
            return TripSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Open copy: the copy opened by the core (from this Mac's sealed copy, else fetched), as plain Swift values; or
    /// the line saying why it didn't open.
    func openVaultCopy(_ docId: String) async -> (ViewedCopy?, TripSaid?) {
        guard let core else { return (nil, nil) }
        do {
            let o = try await core.openVaultCopy(docId: docId)
            if let b64 = o.base64, let data = Data(base64Encoded: b64) {
                MekaHaptics.light()
                return (ViewedCopy(title: o.title, isPdf: o.isPdf, data: data), nil)
            }
            MekaHaptics.tick()
            return (nil, TripSaid(line: o.problem ?? VaultFileRules.shared.OFFLINE, refused: true))
        } catch {
            lastError = error.localizedDescription
            return (nil, nil)
        }
    }

    /// The Vault, slice 4: Open copy behind Touch ID (the Mac's password without it). When the copies are locked,
    /// Touch ID first, then the core unlocks them for five minutes at most and opens this one; a no says so and shows
    /// nothing. Only the id String crosses into the core.
    func openVaultCopyUnlocking(_ docId: String) async -> (ViewedCopy?, TripSaid?) {
        guard let core else { return (nil, nil) }
        let locked = (try? await core.vaultCopiesLocked())?.boolValue ?? true
        if locked {
            let ok = await withCheckedContinuation { (k: CheckedContinuation<Bool, Never>) in
                MacBiometric.unlock(reason: VaultLockRules.shared.UNLOCK_REASON) { yes in k.resume(returning: yes) }
            }
            guard ok else {
                MekaHaptics.tick()
                return (nil, TripSaid(line: VaultLockRules.shared.LOCKED, refused: true))
            }
            _ = try? await core.unlockVault()
        }
        return await openVaultCopy(docId)
    }

    /// Locks the Vault's copies again (the Vault left, the screen slept or the session went away).
    func lockVault() { run { _ = try await $0.lockVault() } }

    /// Remove copy: gone from every device and the server; the document stays.
    func removeVaultCopy(_ docId: String) async -> TripSaid? {
        guard let core else { return nil }
        MekaHaptics.tick()
        do {
            let r = try await core.removeVaultCopy(docId: docId)
            return TripSaid(line: r.line, refused: r.refused)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    // MARK: The Vault key (Vault slice 2a)

    /// Allow on a device asking for the Vault key, once Meka checked its code; only a String crosses.
    func allowVaultDevice(_ deviceId: String) { MekaHaptics.light(); run { _ = try await $0.allowVaultDevice(deviceId: deviceId) } }

    func refuseVaultDevice(_ deviceId: String) { MekaHaptics.tick(); run { _ = try await $0.refuseVaultDevice(deviceId: deviceId) } }

    func askVaultAgain() { MekaHaptics.light(); run { _ = try await $0.askVaultAgain() } }

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

    /// Adds one typed line ("INSET 27 Oct", "Theo PE Tue"); only a String crosses. Returns the line to show under the
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

    // MARK: School dates filled in (slice 1)

    /// Fill in on the council card (and Undo after Remove all); only the council's id (a String) crosses. Returns the
    /// line to show, or nil for a council not in the table.
    func fillSchoolTerms(_ councilId: String) async -> String? {
        guard let core else { return nil }
        MekaHaptics.light()
        do { return try await core.fillSchoolTerms(councilId: councilId) } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Remove all on the council card (and Undo after Fill in); only the council's id crosses.
    func removeSchoolTerms(_ councilId: String) async -> String? {
        guard let core else { return nil }
        MekaHaptics.tick()
        do { return try await core.removeSchoolTerms(councilId: councilId) } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    // MARK: School dates filled in (slice 2)

    /// Add them on the council card's yearly line: only the council's id crosses in, and only Strings come back (the
    /// line and the ids Undo takes out). Nil when there are no new dates.
    func addNewSchoolTerms(_ councilId: String) async -> (line: String, ids: [String])? {
        guard let core else { return nil }
        MekaHaptics.light()
        do {
            guard let added = try await core.addNewSchoolTerms(councilId: councilId) else { return nil }
            return (added.line, added.ids)
        } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

    /// Undo for Add them: a fresh value made from the ids crosses once.
    func undoNewSchoolTerms(_ ids: [String]) async {
        guard let core else { return }
        MekaHaptics.tick()
        do { _ = try await core.undoNewSchoolTerms(added: SchoolTermsAdded(line: "", ids: ids)) } catch {
            lastError = error.localizedDescription
        }
    }

    /// The school's INSET days pasted as dates or a letter; only the String crosses. The line to show, or that no date
    /// was found in it.
    func addSchoolInset(_ text: String) async -> String? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let core, !t.isEmpty else { return nil }
        MekaHaptics.light()
        do { return try await core.addSchoolInset(text: t) ?? SchoolTerms.shared.INSET_NONE } catch {
            lastError = error.localizedDescription
            return nil
        }
    }

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
        // Taps feel instant (Meka 2026-10-25 01:45): the chat's card leaves in this frame; one batched write follows.
        hideDecided(ids)
        Task {
            do {
                let seen = try await core.seenTriageChat(messageIds: ids).int32Value
                if !groupKeys.isEmpty { _ = try await core.catchUpGroupDigest(groupKeys: groupKeys) }
                if seen > 0 { offerEventUndo(line, nil) }
            } catch { restoreDecided(ids, what: "Done", error: error) }
        }
    }

    /// Delete on a likely scam (Mac presentation pass, item 6): clears it on both devices like Not now; only the id
    /// crosses to the core.
    func deleteScamText(_ card: TriageCard) {
        guard let core else { return }
        MekaHaptics.tick()
        let id = card.id
        let line = NeedsYouCardRules.shared.deletedLine(card: card)
        Task {
            do {
                if try await core.dismissTriage(messageId: id).boolValue { offerEventUndo(line, nil) }
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Block number on a likely scam: the sender's number goes on the block list, then the text is deleted.
    func blockScamText(_ card: TriageCard) {
        guard let core, let number = NeedsYouCardRules.shared.number(card: card) else { return }
        // Only Strings are kept: the card itself never goes into the task.
        let id = card.id
        let line = NeedsYouCardRules.shared.deletedLine(card: card)
        Task {
            guard await blockCaller(number) else { return }
            do {
                if try await core.dismissTriage(messageId: id).boolValue { offerEventUndo(line, nil) }
            } catch { lastError = error.localizedDescription }
        }
    }

    func answerTriage(_ card: TriageCard, lead: Bool) {
        guard let core else { return }
        let id = card.id
        let rules = TriageReplyRules.shared
        if lead {
            MekaHaptics.light()
            // Send (via your phone) (WhatsApp replies, slice 2): the Fold still holds the message's notification, so the
            // Mac asks it, through the synced card, to send the draft with that notification's own Reply. Only Strings
            // cross to the core; the card stays until the phone answers (Sent: it leaves; or why it couldn't).
            if rules.primary(card: card, live: false, canSend: false) == .sendViaPhone, let draft = card.draft {
                guard !rules.waitingOnPhone(card: card) else { return }
                let line = rules.viaPhoneLine(card: card)
                Task {
                    do {
                        if try await core.requestPhoneSend(messageId: id, text: draft).boolValue { offerEventUndo(line, nil) }
                    } catch { lastError = error.localizedDescription }
                }
                return
            }
            let line: String
            // Reply in WhatsApp (WhatsApp replies, slice 1): the chat opens in WhatsApp (the desktop app, else the web)
            // with the reply typed; Meka presses send there. Nothing is sent from here.
            if rules.primary(card: card, live: false, canSend: false) == .replyInApp,
               let link = rules.replyLink(card: card, text: card.draft), let url = URL(string: link),
               NSWorkspace.shared.open(url) {
                line = rules.replyInAppLine(card: card)
            } else if let draft = card.draft {
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

    /// Got it on "Archived for you" (Inbox autopilot, slice 4): the card folds away here and on the Fold (synced), with
    /// Undo on the bar. Nothing crosses to the core but the call.
    func seenArchivedForYou() {
        guard let card = archivedForYou else { return }
        MekaHaptics.light()
        answerMail(InstantMailRules.shared.archivedKey(card: card), what: ArchivedForYouRules.shared.GOT_IT, undo: .archivedSeen) {
            try await $0.seenArchivedForYou()
        }
    }

    /// Review opens its own list, slice 1: reads the Empty Junk card's review and opens its sheet. Only the id crosses.
    func openJunkReview(_ cardId: String) {
        guard let core else { return }
        MekaHaptics.light()
        junkReviewCardId = cardId
        Task {
            junkReview = try? await core.junkReview(cardId: cardId)
            showJunkReview = true
        }
    }

    /// The tick on one sender in the Empty Junk review ([keep] true: their mail stays in Junk), with Undo on the bar.
    /// Only Strings and a Bool cross to the core; the sheet comes back as the core's value.
    func keepJunkSender(_ key: String, keep: Bool, title: String) {
        guard let core, let cardId = junkReviewCardId else { return }
        MekaHaptics.tick()
        Task {
            do {
                let change = try await core.keepJunkSender(cardId: cardId, key: key, keep: keep)
                junkReview = change.review
                if let line = change.line { offerEventUndo("\(line) · \(title)", .junkKeep(cardId, key, !keep)) }
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Approve inside the Empty Junk review: the same synced approval as the card's →, then the sheet closes.
    func approveJunkReview() {
        guard let core, let cardId = junkReviewCardId else { return }
        MekaHaptics.medium()
        Task {
            guard let r = try? await core.approveMailBacklog(id: cardId) else { return }
            showJunkReview = false
            offerEventUndo(r.line, .unapproveBacklog(cardId))
        }
    }

    /// Unsubscribe from all on "Not opened in 30 days" (Inbox autopilot, slice 5c): the server unsubscribes from each at its
    /// next check; the offer folds away here and on the Fold, with Undo on the bar. Nothing crosses but the call.
    func unsubscribeUnopened() {
        guard let core else { return }
        MekaHaptics.medium()
        Task {
            do {
                let line = try await core.unsubscribeUnopened()
                offerEventUndo(line, .mailUnopened)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Not now on "Not opened in 30 days": back next Saturday, with Undo.
    func notNowUnopened() {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                let line = try await core.notNowUnopened()
                offerEventUndo(line, .mailUnopened)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Keep on one sender of "Not opened in 30 days": never offered again, with Undo. Only the id (a String) crosses.
    func keepMailSender(_ id: String) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                let line = try await core.keepMailSender(id: id)
                offerEventUndo(line, .mailUnopened)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Done on an email in "From your mail" (Inbox autopilot, slice 6a): the card goes here and on the Fold, and the
    /// server archives the email at its next check; Undo on the bar. Only the id (a String) crosses.
    func doneMailItem(_ id: String) {
        MekaHaptics.medium()
        answerMail(id, what: MailNeedsRules.shared.DONE, undo: .mailItem) { try await $0.doneMailItem(id: id) }
    }

    /// Always tidy on an email in "From your mail" (slice 10b): Done, and the server gives its sender the always-tidy
    /// rule; Undo on the bar brings the card back and takes the rule off. Only the id (a String) crosses.
    func alwaysTidyMailItem(_ id: String) {
        MekaHaptics.medium()
        answerMail(id, what: "Always tidy", undo: .mailItem) { try await $0.alwaysTidyMailItem(id: id) }
    }

    /// Block sender (Bin & block on a possible scam) on an email in "From your mail" (junk fix slice 2): Done, and at the
    /// server's next check this email and the sender's later ones go to Trash; Undo on the bar brings it back and takes
    /// the block off. Only the id (a String) crosses.
    func blockMailItem(_ id: String) {
        MekaHaptics.medium()
        answerMail(id, what: "Block", undo: .mailItem) { try await $0.blockMailItem(id: id) }
    }

    /// Unsubscribe on an email in "From your mail" (junk fix slice 2): Done, and the server unsubscribes the sender its
    /// own way at its next check; Undo before then calls it off. Only the id crosses.
    func unsubscribeMailItem(_ id: String) {
        MekaHaptics.medium()
        answerMail(id, what: "Unsubscribe", undo: .mailItem) { try await $0.unsubscribeMailItem(id: id) }
    }

    /// Task on an email in "From your mail" (slice 6b-1): a task from its subject, the email archived at the server's
    /// next check, Undo on the bar. Only the id (a String) crosses.
    func taskFromMailItem(_ id: String) {
        MekaHaptics.medium()
        answerMail(id, what: MailNeedsRules.shared.TASK, undo: .mailItem) { try await $0.taskFromMailItem(id: id) }
    }

    /// Week board item (5): To a day… on an email — its task on that day's Board column, the email archived at the
    /// server's next check, Undo on the bar. Only the id (a String) and the day (an Int64) cross.
    func mailToDay(_ id: String, day: Int64) {
        MekaHaptics.medium()
        answerMail(id, what: "To a day", undo: .mailItem) { try await $0.mailToDay(id: id, day: day) }
    }

    /// The days To a day… offers now (Today, Tomorrow, the rest of the week, into next week until five).
    func boardFeedDays() -> [FeedDay] { core?.boardFeedDays() ?? [] }

    /// Waiting for on an email in "From your mail" (slice 6b-1): in Waiting for from the sender, chase in 3 days, the
    /// email archived; Undo on the bar. Only the id crosses.
    func waitingFromMailItem(_ id: String) {
        MekaHaptics.medium()
        answerMail(id, what: MailNeedsRules.shared.WAITING, undo: .mailItem) { try await $0.waitingFromMailItem(id: id) }
    }

    /// Draft it in the reply editor on an email in "From your mail" (slice 6b-2b): Navilife's AI drafts a reply in his
    /// tone for the editor; nothing is written or sent (Send does that, on his tap). Only the id (a String) crosses to the
    /// core; the draft's two Strings come back.
    func draftMailReply(_ id: String) async -> (text: String?, line: String) {
        guard let core else { return (nil, AskRules.shared.NOT_CONNECTED_LINE) }
        do {
            let draft = try await core.draftMailReply(id: id)
            return (draft.text, draft.line)
        } catch {
            return (nil, AskRules.shared.OFFLINE_LINE)
        }
    }

    /// Suggest times on an email that asks to meet (Scheduling assistant, slice 1): his free times as a reply, worked out
    /// on the device; nothing is written or sent. Only the id (a String) crosses to the core; two Strings come back.
    func suggestMeetingTimes(_ id: String) async -> (text: String?, line: String) {
        guard let core else { return (nil, AskRules.shared.NOT_CONNECTED_LINE) }
        do {
            let draft = try await core.suggestMeetingTimes(id: id)
            return (draft.text, draft.line)
        } catch {
            return (nil, MeetingRequestRules.shared.NONE_FREE_LINE)
        }
    }

    /// Book it on an email whose reply picks one of the times he offered (Scheduling assistant, slice 2): the event goes to
    /// his calendar after the usual five seconds' Undo on the bar; nothing is sent to them. Only the id (a String) crosses.
    func bookMeetingPick(_ id: String) {
        guard let core else { return }
        MekaHaptics.medium()
        Task {
            do {
                let line = try await core.bookMeetingPick(id: id)
                offerEventUndo(line, .mailItem)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// The chase written by the AI in "Time to chase" (Follow-up radar, slice 5): nothing is written or sent (Send does that,
    /// on his tap). Only the id (a String) crosses to the core; the draft's two Strings come back.
    func draftMailChase(_ id: String) async -> (text: String?, line: String) {
        guard let core else { return (nil, AskRules.shared.NOT_CONNECTED_LINE) }
        do {
            let draft = try await core.draftMailChase(id: id)
            return (draft.text, draft.line)
        } catch {
            return (nil, AskRules.shared.OFFLINE_LINE)
        }
    }

    /// Send on a reply written on an email in "From your mail" (slice 6b-2a): nothing is sent here; the server sends it
    /// from the account the email came to at its next check (Undo on the bar stops it) and archives the email. A problem
    /// with the words (empty, too long) leaves the editor open. Only the id and the text (Strings) cross.
    func replyToMailItem(_ id: String, _ text: String, sent: @escaping @MainActor () -> Void) {
        guard let core else { return }
        if MailNeedsRules.shared.replyProblem(text: text) != nil { return }
        MekaHaptics.medium()
        Task {
            do {
                let line = try await core.replyToMailItem(id: id, text: text)
                sent()
                offerEventUndo(line, .mailItem)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Send all on "From your mail" (email triage completeness, 2026-10-15): the short replies he read in each email's
    /// editor, each sent exactly as Send sends one — from the account its email came to, at the server's next check, one
    /// Undo on the bar for all. Only Strings cross (two arrays, copied once into the task).
    func replyToMailItems(_ ids: [String], _ texts: [String], sent: @escaping @MainActor () -> Void) {
        guard let core, !ids.isEmpty, ids.count == texts.count else { return }
        MekaHaptics.medium()
        Task {
            do {
                let line = try await core.replyToMailItems(ids: ids, texts: texts)
                sent()
                offerEventUndo(line, .mailItem)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Send on a chase in "Time to chase" (Inbox autopilot, slice 6b-3): nothing is sent here; the server sends it from
    /// the account the email came to, in its thread, at its next check (Undo on the bar stops it). A problem with the
    /// words leaves the editor open. Only the id and the text (Strings) cross.
    func chaseMailItem(_ id: String, _ text: String, sent: @escaping @MainActor () -> Void) {
        guard let core else { return }
        if MailNeedsRules.shared.replyProblem(text: text) != nil { return }
        MekaHaptics.medium()
        Task {
            do {
                let line = try await core.chaseMailItem(id: id, text: text)
                sent()
                offerEventUndo(line, .mailChase)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Add on "Renewals from your mail": onto Lists → Renewals, Undo on the bar. Only the id crosses.
    func addMailFind(_ id: String) {
        MekaHaptics.medium()
        answerMail(id, what: MailRenewalRules.shared.ADD, undo: .mailFind) { try await $0.addMailFind(id: id) }
    }

    /// Follow-up radar, slice 3: a chip under "Wait 3 days ▾" — how many days he waits for that person's replies, on
    /// both apps; Undo on the bar puts the last wait back. Only the id and the number cross.
    func setFollowUpWait(_ id: String, days: Int32) {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            do {
                let line = try await core.setFollowUpWait(findId: id, days: days)
                offerEventUndo(line, .mailFind)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Done on a Discord channel's card (Discord channel watch, slice 1): its posts leave both apps, Undo on the bar.
    /// Only the channel's key crosses.
    func discordDone(_ key: String) {
        guard let core else { return }
        MekaHaptics.medium()
        Task {
            do {
                let line = try await core.discordDone(key: key)
                offerEventUndo(line, .discord)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Keep as idea on a Discord post (Discord channel watch, slice 4): a note in Lists → Someday → Trading ideas on
    /// both apps, never an order. Only the post's id crosses; Undo on the bar takes the note out again.
    func keepDiscordIdea(_ postId: String) {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            do {
                let line = try await core.keepDiscordIdea(postId: postId)
                offerEventUndo(line, line == DiscordRules.shared.KEPT_LINE ? .discordIdea(postId) : nil)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Week board item (5) part 2: To a day… under a Discord post — a task on that day of his week to look at it (never an
    /// order). Only the post's id (a String) and the day (an Int64) cross; Undo on the bar takes it off again.
    func discordIdeaToDay(_ postId: String, day: Int64) {
        guard let core else { return }
        MekaHaptics.medium()
        Task {
            do {
                let line = try await core.discordIdeaToDay(postId: postId, day: day)
                let kept = line != DiscordRules.shared.ALREADY_KEPT && line != DiscordRules.shared.CANT_KEEP
                offerEventUndo(line, kept ? .discordIdea(postId) : nil)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Follow / Stop on Work → Discord: synced, so the Fold keeps (or drops) that channel's posts. Returns via the bar.
    func setDiscordWatched(_ key: String, on: Bool) {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            do {
                let line = try await core.setDiscordWatched(key: key, on: on)
                offerEventUndo(line, nil)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Work → Discord's "Alert me straight away" (Discord channel watch, slice 2): add or remove a person or a word;
    /// synced, so the Fold's listener alerts for them. Only the typed text crosses. Says what changed on the bar.
    func setDiscordAlert(_ text: String, person: Bool, add: Bool) {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            do {
                let line = try await core.setDiscordAlert(text: text, person: person, add: add)
                offerEventUndo(line, nil)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// Not needed on "Renewals from your mail": gone on both apps, Undo on the bar. Only the id crosses.
    func notNeededMailFind(_ id: String) {
        MekaHaptics.light()
        answerMail(id, what: MailRenewalRules.shared.NOT_NEEDED, undo: .mailFind) { try await $0.notNeededMailFind(id: id) }
    }

    /// Got it on "Time to chase": off Waiting for, Undo on the bar. Only the id crosses.
    func gotMailChase(_ id: String) {
        MekaHaptics.medium()
        answerMail(id, what: MailChaseRules.shared.GOT_IT, undo: .mailChase) { try await $0.gotMailChase(id: id) }
    }

    /// Not yet on "Time to chase": chase again in 3 days, nothing sent, Undo on the bar. Only the id crosses.
    func notYetMailChase(_ id: String) {
        MekaHaptics.light()
        answerMail(id, what: MailChaseRules.shared.NOT_YET, undo: .mailChase) { try await $0.notYetMailChase(id: id) }
    }

    /// Start on the ten-minute inbox (Inbox autopilot, slice 7): the emails that need him, one at a time. Nothing crosses.
    func startMailMoment() {
        guard let core else { return }
        MekaHaptics.medium()
        Task {
            do { try await core.startMailMoment() } catch { lastError = error.localizedDescription }
        }
    }

    /// Skip in the ten-minute inbox: this email goes to the back (nothing written). Only the id (a String) crosses.
    func skipMailMoment(_ id: String) {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            do { try await core.skipMailMoment(id: id) } catch { lastError = error.localizedDescription }
        }
    }

    /// Finish or Close on the ten-minute inbox: the session ends here; what's left stays in "From your mail".
    func endMailMoment() {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do { try await core.endMailMoment() } catch { lastError = error.localizedDescription }
        }
    }

    /// Not today on the ten-minute inbox's card: gone on both apps until tomorrow's moment, with Undo on the bar.
    func notTodayMailMoment() {
        guard let core else { return }
        MekaHaptics.tick()
        Task {
            do {
                let line = try await core.notTodayMailMoment()
                offerEventUndo(line, .mailMoment)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// The Mail sheet reads when the ten-minute inbox comes (a String key comes back).
    func loadMailMomentKey() async {
        guard let core else { return }
        mailMomentKey = try? await core.mailMomentTimeKey()
    }

    /// Meeting prep, slice 2: the Mail sheet reads how early the prep comes (a String key comes back).
    func loadMeetingPrepLeadKey() async {
        guard let core else { return }
        meetingPrepLeadKey = try? await core.meetingPrepLeadKey()
    }

    /// Off · 5 · 10 · 15 · 30 min in the Mail sheet (synced). Only the key (a String) crosses; the line comes back.
    func setMeetingPrepLeadKey(_ key: String) async {
        guard let core else { return }
        MekaHaptics.tick()
        mailMessage = nil
        guard let line = try? await core.setMeetingPrepLeadKey(key: key) else { return }
        meetingPrepLeadKey = key
        mailNote = line
    }

    /// Slice 3f: the Mail sheet reads Warranty reminders' threshold (a String key comes back).
    func loadMailWarrantyKey() async {
        guard let core else { return }
        mailWarrantyKey = try? await core.mailWarrantyKey()
    }

    /// Life audit, slice 2: the Mail sheet reads the review (after loadMail, so the accounts are listed).
    func loadLifeAudit() async {
        guard let core else { return }
        lifeAudit = try? await core.lifeAuditReview()
    }

    /// Follow-up radar, slice 4: the Mail sheet reads Chase waits.
    func loadFollowUpWaits() async {
        guard let core else { return }
        followUpWaits = try? await core.followUpWaitsPanel()
    }

    /// A chip or Back to usual (days 0) on one person in Chase waits (synced). Only the id and the number cross.
    func changeFollowUpWait(_ id: String, days: Int32) async {
        guard let core else { return }
        MekaHaptics.tick()
        mailMessage = nil
        guard let line = try? await core.changeFollowUpWait(waitId: id, days: days) else { return }
        mailNote = line
        followUpWaitUndo = true
        await loadFollowUpWaits()
    }

    /// Undo on the last Chase waits change on this Mac.
    func undoFollowUpWaitChange() async {
        guard let core else { return }
        MekaHaptics.light()
        followUpWaitUndo = false
        if let line = try? await core.undoFollowUpWaitChange() { mailNote = line }
        await loadFollowUpWaits()
    }

    /// Off · £50 · £100 · £250 · £500 in the Mail sheet (synced). Only the key (a String) crosses; the line comes back.
    func setMailWarrantyKey(_ key: String) async {
        guard let core else { return }
        MekaHaptics.tick()
        mailMessage = nil
        guard let line = try? await core.setMailWarrantyKey(key: key) else { return }
        mailWarrantyKey = key
        mailNote = line
    }

    /// Morning · Evening · Off in the Mail sheet (synced). Only the key (a String) crosses; the line comes back.
    func setMailMomentKey(_ key: String) async {
        guard let core else { return }
        MekaHaptics.tick()
        mailMessage = nil
        guard let line = try? await core.setMailMomentTimeKey(key: key) else { return }
        mailMomentKey = key
        mailNote = line
    }

    /// Snooze on an email in "From your mail": back tomorrow 08:00 on both apps, with Undo. Only the id crosses.
    /// Drafted replies: Not needed on Navilife's draft — the draft goes, the email stays (Undo on the bar). Only the id
    /// (a String) crosses; the line comes back for the bar.
    func dismissMailDraft(_ id: String) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                let line = try await core.dismissMailDraft(id: id)
                offerEventUndo(line, .mailItem)
            } catch { lastError = error.localizedDescription }
        }
    }

    func snoozeMailItem(_ id: String) {
        MekaHaptics.light()
        answerMail(id, what: MailNeedsRules.shared.SNOOZE, undo: .mailItem) { try await $0.snoozeMailItem(id: id) }
    }

    /// Put back on one of "Archived for you"'s tidies: the server returns those emails to the inbox at its next check.
    /// Only the id (a String) crosses; the line comes back for the bar.
    func putBackArchived(_ id: String) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                let r = try await core.undoMailTidy(id: id)
                offerEventUndo(r.line, nil)
            } catch { lastError = error.localizedDescription }
        }
    }

    /// "Archived for you" shows which emails, slice 2: one email's preview (from the synced tidy), or nil when it has gone.
    func archivedEmail(batchId: String, key: String) async -> ArchivedEmailPreview? {
        guard let core else { return nil }
        return try? await core.archivedEmail(batchId: batchId, key: key)
    }

    /// Read the full email inside Navilife (2026-10-20): a From your mail card's whole email for the reader, read from the
    /// provider now through Navilife's server (nothing kept, not marked read). Only Strings and a Bool cross to the core.
    func readMailItem(_ id: String, images: Bool) async -> MailReaderView? {
        guard let core else { return nil }
        return try? await core.readMailItem(id: id, images: images)
    }

    /// Read the full email, slice 2: one of the reader's attachments fetched now through Navilife's server, ready for the
    /// in-app viewer (a picture or PDF) or another app (after the sheet's confirm). The handle and the row are read here,
    /// on the main actor, into plain Strings and numbers; only those cross to the core.
    func openMailAttachment(_ view: MailReaderView, _ row: MailAttachmentRow) async -> MailFileOpened {
        let rules = MailAttachmentRules.shared
        guard let core, let source = view.source else { return .line(rules.GONE_LINE) }
        let kind = source.kind, id = source.id, batch = source.batch
        let index = row.index, name = row.name, type = row.type, size = row.bytes
        guard let o = try? await core.openMailAttachmentAt(kind: kind, id: id, batch: batch, index: index, name: name, type: type, size: size) else {
            return .line(rules.FAILED_LINE)
        }
        if o.state == rules.VIEW, let v = o.viewer, let b64 = v.base64, let data = Data(base64Encoded: b64) {
            return .view(ViewedCopy(title: v.title, isPdf: v.isPdf, data: data))
        }
        if o.state == rules.SYSTEM, let b64 = o.base64, let data = Data(base64Encoded: b64) {
            return .system(name: o.name, fileName: o.fileName, data: data)
        }
        return .line(o.line ?? rules.FAILED_LINE)
    }

    /// The same for one email of a tidy (Archived for you, All tidied mail): archived, in Junk or in Trash.
    func readArchivedMail(batchId: String, key: String, images: Bool) async -> MailReaderView? {
        guard let core else { return nil }
        return try? await core.readArchivedMail(batchId: batchId, key: key, images: images)
    }

    /// Slice 4: Open in Gmail / Outlook — the link is made now in the core (the account list asked for if needed), opened in
    /// the browser and never kept. Returns the line to show under the button. Only Strings cross to the core.
    func openArchivedEmail(batchId: String, key: String) async -> String {
        guard let core else { return MailOpenRules.shared.OFFLINE }
        MekaHaptics.light()
        guard let r = try? await core.archivedEmailLink(batchId: batchId, key: key) else { return MailOpenRules.shared.OFFLINE }
        if let link = r.link {
            if let url = URL(string: link.url) { NSWorkspace.shared.open(url) }
            return link.hint
        }
        return r.problem ?? MailOpenRules.shared.OFFLINE
    }

    /// Receipts → Vault: Keep in the Vault (or Take it out) on a receipt's preview. Returns the line to show; only Strings
    /// and a Bool cross to the core.
    func receiptInVault(batchId: String, key: String, keep: Bool) async -> String {
        guard let core else { return MailOpenRules.shared.OFFLINE }
        MekaHaptics.light()
        if keep {
            return (try? await core.keepReceiptInVault(batchId: batchId, key: key))?.line ?? VaultReceiptRules.shared.GONE
        }
        return (try? await core.takeReceiptOutOfVault(key: key)) ?? VaultReceiptRules.shared.GONE
    }

    /// Receipts → Vault: Open the email on a kept receipt's Vault row (the browser; the link is never kept).
    func openVaultReceipt(docId: String) async -> String {
        guard let core else { return MailOpenRules.shared.OFFLINE }
        MekaHaptics.light()
        guard let r = try? await core.vaultReceiptLink(docId: docId) else { return MailOpenRules.shared.OFFLINE }
        if let link = r.link {
            if let url = URL(string: link.url) { NSWorkspace.shared.open(url) }
            return link.hint
        }
        return r.problem ?? MailOpenRules.shared.OFFLINE
    }

    /// Slice 3: All tidied mail (every email tidied in the last 30 days), filtered to one sender's id when given.
    /// Only a String crosses to the core.
    func archivedAll(sender: String?) async -> ArchivedAllList? {
        guard let core else { return nil }
        return try? await core.archivedAll(sender: sender)
    }

    /// Slice 2: Put back, Always keep or Unsubscribe on one email (an action's name), done at the server's next check;
    /// Undo on the bar takes the ask off until then. Only Strings cross to the core.
    func askArchivedEmail(batchId: String, key: String, action: String) {
        guard let core else { return }
        MekaHaptics.light()
        Task {
            do {
                if let line = try await core.askArchivedEmail(batchId: batchId, key: key, action: action) {
                    offerEventUndo(line, .archivedEmail(batchId, key, action))
                }
            } catch { lastError = error.localizedDescription }
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

    // MARK: Mail cards' instant answers

    /// Taps feel instant, slice 20: the row (or Archived for you's card) leaves in this frame and the core's write
    /// follows; a failure or a refusal ("Couldn't find that email") brings it back with the bar saying why. Only the id
    /// (a String) crosses to the core.
    private func answerMail(_ id: String, what: String, undo: EventUndoOffer.Action,
                            work: @escaping (MekaCore) async throws -> String) {
        guard let core else { return }
        mailDecided.insert(id)
        showMailCards()
        Task {
            do {
                let line = try await work(core)
                if InstantMailRules.shared.refused(line: line) {
                    backMail(id, InstantTapRules.shared.failedLine(what: what, reason: line))
                } else {
                    mailUndoIds = [id]
                    offerEventUndo(line, undo)
                }
            } catch {
                backMail(id, InstantTapRules.shared.failedLine(what: what, reason: error.localizedDescription))
            }
        }
    }

    private func backMail(_ id: String, _ line: String) {
        mailDecided.remove(id)
        showMailCards()
        offerEventUndo(line, nil)
    }

    /// Undo on the bar: the last answered row shows again at once, before the core's undo lands.
    private func unhideMail() {
        mailDecided.subtract(mailUndoIds)
        mailUndoIds = []
        showMailCards()
    }

    /// The mail cards as shown: the core's, less what was just answered here; ids the core no longer holds are forgotten.
    private func showMailCards() {
        let R = InstantMailRules.shared
        if !mailDecided.isEmpty {
            let present = Set(R.present(needs: allMailNeeds, chases: allMailChases, finds: allMailFinds, archived: allArchivedForYou))
            mailDecided = mailDecided.filter { present.contains($0) }
        }
        mailNeeds = R.needs(card: allMailNeeds, hidden: mailDecided)
        mailChases = R.chases(card: allMailChases, hidden: mailDecided)
        mailFinds = R.finds(card: allMailFinds, hidden: mailDecided)
        archivedForYou = R.archived(card: allArchivedForYou, hidden: mailDecided)
    }

    // MARK: Needs you stack

    /// The stack as shown: set-aside cards at the back, in the order they were set aside.
    var needsYouCards: [DecisionCard] {
        guard let stack = needsYouStack else { return [] }
        let shown = decidedHere.isEmpty ? stack : NeedsYouStack(cards: InstantTapRules.shared.stack(cards: stack.cards, hidden: decidedHere), working: stack.working)
        return NeedsYouStackRules.shared.ordered(stack: shown, setAside: needsYouSetAside)
    }

    /// Taps feel instant: [ids] leave the screen in this frame.
    func hideDecided(_ ids: [String]) {
        decidedHere.formUnion(ids)
        triage = InstantTapRules.shared.triage(cards: allTriage, hidden: decidedHere)
    }

    /// The work behind a tap failed: [ids] come back, with the bar saying why.
    func restoreDecided(_ ids: [String], what: String, error: Error) {
        decidedHere.subtract(ids)
        triage = InstantTapRules.shared.triage(cards: allTriage, hidden: decidedHere)
        offerEventUndo(InstantTapRules.shared.failedLine(what: what, reason: error.localizedDescription), nil)
    }

    /// The views changed: the decided ids they no longer hold are forgotten (their writes landed).
    private func settleDecided() {
        if !decidedHere.isEmpty {
            var present = Set(allTriage.map(\.id))
            if let stack = needsYouStack { present.formUnion(stack.cards.map(\.id)) }
            decidedHere = decidedHere.filter { present.contains($0) }
        }
        triage = InstantTapRules.shared.triage(cards: allTriage, hidden: decidedHere)
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
            let cardId = card.id, what = card.yesLabel
            hideDecided([cardId])
            Task {
                do {
                    if let undo = try await core.decide(taskId: id, effect: effect) {
                        offerEventUndo(message, .decision(undo))
                    }
                } catch { restoreDecided([cardId], what: what, error: error) }
            }
        case .openTask:
            if let id = card.taskId { select(id, reduced: reduced) }
        case .openLists:
            go(to: .lists, reduced: reduced)
        case .openLink:
            // Top up (the call assistant's credit): the billing page in the browser; MEKA never pays anything.
            MekaHaptics.light()
            if let link = card.link, let url = URL(string: link) { NSWorkspace.shared.open(url) }
        case .approveBacklog:
            // Inbox fixes, slice 2: Approve on an account's clean-up (synced); Undo withdraws it while nothing moved.
            guard let core else { return }
            MekaHaptics.medium()
            let id = card.id, what = card.yesLabel
            // Taps feel instant (4b, Meka 2026-10-25 01:50): the card leaves at once and never comes back while the
            // write catches up; a failure brings it back with the reason.
            hideDecided([id])
            Task {
                do {
                    let r = try await core.approveMailBacklog(id: id)
                    // Approve must land (2026-10-25): nothing written → the card comes back with the reason.
                    if r.refused {
                        decidedHere.subtract([id])
                        triage = InstantTapRules.shared.triage(cards: allTriage, hidden: decidedHere)
                        offerEventUndo(r.line, nil)
                    } else {
                        offerEventUndo(r.line, .unapproveBacklog(id))
                    }
                } catch { restoreDecided([id], what: what, error: error) }
            }
        case .allowDevice, .refuseDevice:
            // Clean-ups (2026-10-19): Allow or Not this one on a new phone or Mac asking to join; the card leaves on
            // every device as the ask's state syncs. Nothing to undo (an allowed device is revoked from the server).
            guard let core else { return }
            MekaHaptics.medium()
            let id = card.id, allow = effect == .allowDevice
            Task {
                guard let line = try? await core.answerDeviceJoin(requestId: id, allow: allow) else { return }
                offerEventUndo(line, nil)
            }
        case .openMail:
            // Review: Mail, where the plan lists what goes where.
            showMail = true
        case .openReview:
            // Review opens its own list, slice 1: the card's own sheet (Empty Junk: who, a tick per sender, Approve).
            openJunkReview(card.id)
        case .openPlanWeek:
            // Week board item (6) part 2: Plan now on Sunday's card opens Plan your week over the Board.
            MekaHaptics.tick()
            openPlanWeek()
        case .setAside:
            MekaHaptics.light()
            withAnimation(MekaMotion.replan(reduced: reduced)) {
                needsYouSetAside.removeAll { $0 == card.id }
                needsYouSetAside.append(card.id)
            }
            offerEventUndo(message, .unsetAside(card.id))
        }
    }

    private func offerEventUndo(_ message: String, _ action: EventUndoOffer.Action?, label: String = "Undo", seconds: Double = 5) {
        let offer = EventUndoOffer(message: message, action: action, label: label)
        eventUndo = offer
        Task {
            try? await Task.sleep(for: .seconds(seconds))
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
        return t.needsYou.map(\.task) + t.fromYesterday.map(\.task) + (t.upNext.map { [$0] } ?? []) + t.yourDay
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
    /// Ask knows how Navilife itself works: Take me there under an answer from the help.
    var help: MekaKit.HelpLink? = nil
    /// "Still stuck? Ask Navilife" under a help answer (slice 2).
    var stillStuck: String? = nil
    /// Money manager (16): "Unlock Money to answer" under a money question asked while Money is locked.
    var unlockMoney: String? = nil
    /// The companion, slice 2: the web results a live answer drew on, each a card that opens in the browser.
    var sources: [AskSource] = []
    /// The assistant becomes Obi, slice 3: a spoken answer's detail, shown under its words and not said.
    var shown: [String] = []
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
        /// Approve on a Needs you clean-up card (inbox fixes, slice 2): withdrawn while nothing has moved.
        case unapproveBacklog(String)
        /// A tick in the Empty Junk review (card id, sender key, the keep to put back).
        case junkKeep(String, String, Bool)
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
        /// Done on an after-work card, or Clear all: those held messages come back (nothing was cleared yet).
        case afterWork([String])
        /// Caught up on the group digest: its cards come back on both devices.
        case groupDigest(GroupDigestUndo)
        /// Got it on "Archived for you": the card comes back on both devices.
        case archivedSeen
        /// Put back, Always keep or Unsubscribe on one archived email: the ask comes off (while the server hasn't acted).
        case archivedEmail(String, String, String)
        /// Unsubscribe from all, Not now or Keep on "Not opened in 30 days": the last answer is taken back.
        case mailUnopened
        /// Done or Snooze on an email in "From your mail": the card comes back (and the email, if already archived).
        case mailItem
        /// Send, Got it or Not yet on "Time to chase": the chase is stopped (if not sent yet) and the date put back.
        case mailChase
        /// Add or Not needed on "Renewals from your mail": the find comes back (an added reminder comes off).
        case mailFind
        /// Done on a Discord channel's card: its posts come back on both apps.
        case discord
        /// Keep as idea on a Discord post (slice 4): the note leaves Trading ideas on both apps.
        case discordIdea(String)
        /// Not today on the ten-minute inbox: its card comes back on both apps.
        case mailMoment
        /// Went, Booked… or Not yet on an appointment: its visit, booking and set-aside day go back.
        case appointment(AppointmentDone)
        /// Call to book on an appointment (slice 2b): the task goes and the card asks again as before.
        case appointmentCall(AppointmentCall)
        /// Someday or Drop on a carried task's prompt: it comes back as it was.
        case carried(String)
        /// A move on the Week board: the card and its day's order go back.
        case boardMove
        /// "How long?" on a Board card: the estimate it had comes back.
        case estimate
        /// Block time on a Board card (slice 6): the block is taken back (or follows the task back) and the task's time returns.
        case block
        /// For a goal on a Board card (slice 7): the goal it had comes back.
        case goalTag
        /// Area on a Board card (Option B slice 3): the area it had comes back.
        case boardArea
        /// A card dropped on Someday, Side hustle or House (Option B slice 3): its day or week and its area come back.
        case boardList
        /// A goal picked, typed or taken off a week on the Board (slice 7): taken back.
        case boardGoal
        /// Lighten tomorrow (Week board, item (11)): every task it moved goes back to tomorrow.
        case lighten
        /// Accept plan (Week board, item (6)): every task the plan moved goes back where it was.
        case weekPlan
        /// Hold alerts (Week board, item (11) part 2): the Stretched hold ends now.
        case resumeAlerts
        /// The ring on a Board card: ticked back open.
        case reopen(String)
        /// Un-ticking a Board card (by hand, slice 1): ticked off again.
        case complete(String)
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
        /// Add to the Vault for a shared picture or text of a document (screenshots and photos, slice 3); not an undo.
        case addDocument(String, String?)
        /// Undo on "Added Alex passport · …": the row comes off again.
        case removeDocument(String)
        /// Undo on "Added book Dune … · up next" from Services (reading and learning, slice 3): Reading's own undo token.
        case reading(String)
        /// Undo on "Saved for the train: …" from Services (train-time queue completeness): the new link comes off.
        case unqueue(String)
        /// End on Today's Fasting card (Fasting rebuild slice 4): the same fast carries on.
        case resumeFast(String)
    }

    let id = UUID()
    let message: String
    let action: Action?
    /// The bar's button: "Undo", or what the action does when it isn't one ("Add to the Vault").
    var label: String = "Undo"
}
