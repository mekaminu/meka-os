package os.meka.core.facade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import os.meka.core.domain.CalendarAgenda
import os.meka.core.policy.ActionRequest
import os.meka.core.policy.ActionType
import os.meka.core.policy.AutonomyLevel
import os.meka.core.policy.PolicyConfig
import os.meka.core.policy.PolicyDecision
import os.meka.core.policy.PolicyDomain
import os.meka.core.policy.PolicyEngine
import os.meka.core.policy.Provenance
import os.meka.core.domain.CalendarEvents
import os.meka.core.domain.CalendarView
import os.meka.core.domain.CivilDate
import os.meka.core.domain.DayPlanner
import os.meka.core.domain.QuickCapture
import os.meka.core.domain.Search
import os.meka.core.domain.SearchSources
import os.meka.core.domain.SearchView
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.domain.Renewals
import os.meka.core.domain.addHomeUpkeep
import os.meka.core.domain.addCarDate
import os.meka.core.domain.addMoneyDate
import os.meka.core.domain.DayWindow
import os.meka.core.domain.EveningShutdown
import os.meka.core.domain.BriefRules
import os.meka.core.domain.MorningBrief
import os.meka.core.domain.MorningBriefView
import os.meka.core.domain.WeeklyReview
import os.meka.core.domain.WeeklyReviewView
import os.meka.core.domain.ShutdownView
import os.meka.core.domain.Fasting
import os.meka.core.domain.FastingView
import os.meka.core.domain.IdGenerator
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.ListsView
import os.meka.core.domain.GoalHorizon
import os.meka.core.domain.Goals
import os.meka.core.domain.GoalsView
import os.meka.core.domain.HabitTiming
import os.meka.core.domain.LocalClock
import os.meka.core.domain.SomedayKind
import os.meka.core.domain.RepeatChoice
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.NewTask
import os.meka.core.domain.TaskEdit
import os.meka.core.domain.Tasks
import os.meka.core.domain.Today
import os.meka.core.domain.TodayProjection
import os.meka.core.domain.WorkMode
import os.meka.core.domain.WorkModeState
import os.meka.core.domain.WorkSchedule
import os.meka.core.domain.DeviceAlerts
import os.meka.core.domain.Governor
import os.meka.core.domain.GovernorResult
import os.meka.core.domain.GovernorState
import os.meka.core.domain.Notice
import os.meka.core.domain.NoticeSource
import os.meka.core.domain.NoticeSources
import os.meka.core.domain.NoticeTier
import os.meka.core.domain.NotificationPreview
import os.meka.core.domain.NotificationPrefs
import os.meka.core.domain.NotificationSettings
import os.meka.core.domain.QuietHours
import os.meka.core.sync.AuthRejectedException
import os.meka.core.sync.Backoff
import os.meka.core.sync.Conflict
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Replica
import os.meka.core.sync.ReplicaStore
import os.meka.core.sync.SyncClient
import os.meka.core.sync.SyncStatus
import os.meka.core.sync.SyncTransport
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** UI-facing description of a conflict: what each device said, in plain values. */
data class ConflictChoice(val taskId: String, val field: String, val options: List<String>, internal val conflict: Conflict)

/**
 * The single entry point the Compose and SwiftUI apps use (ADR-001). All state changes are serialised on one
 * confined dispatcher, so UI threads never touch the store concurrently with sync.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class MekaCore(
    householdId: String,
    deviceId: String,
    store: ReplicaStore,
    transport: SyncTransport?,
    secureRandom: Random,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)
    private val ids = IdGenerator(secureRandom)
    private val jitter = Random(secureRandom.nextLong())

    private val replica = Replica(householdId, deviceId, HlcClock(deviceId, nowMs), store, MekaSchema, ids::next)
    private val tasks = Tasks(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val events = CalendarEvents(replica)
    private val bankHolidays = os.meka.core.domain.BankHolidayStore(replica)
    // Annual leave (V1, slice 1): booked days are days off for work mode, like a bank holiday.
    private val leave = os.meka.core.domain.Leave(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val work = WorkMode(replica, { bankHolidays.calendar() }, leave = { leave.days() }, nowMs = nowMs)
    private val lists = os.meka.core.domain.Lists(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val renewals = Renewals(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val shopping = os.meka.core.domain.Shopping(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val school = os.meka.core.domain.School(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val bins = os.meka.core.domain.Bins(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val car = os.meka.core.domain.Car(replica)
    private val mealPlan = os.meka.core.domain.MealPlan(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val emergency = os.meka.core.domain.EmergencyCard(replica, ids::next, nowMs)
    private val lifeFacts = os.meka.core.domain.LifeFacts(replica, ids::next, nowMs)
    private val trainQueue = os.meka.core.domain.TrainQueue(replica, ids::next, nowMs)
    private val errands = os.meka.core.domain.Errands(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val templateStore = os.meka.core.domain.ChecklistTemplates(replica, ids::next, nowMs)
    private val appointments = os.meka.core.domain.Appointments(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val wins = os.meka.core.domain.WorkWins(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val dateNight = os.meka.core.domain.DateNight(replica, nowMs, ZoneCalendar(timeZone))
    private val protectedTime = os.meka.core.domain.ProtectedTime(replica)
    private val leavingNow = os.meka.core.domain.LeavingNow(replica)
    private val weekAheadSettings = os.meka.core.domain.WeekAheadSettings(replica)
    private val autonomy = os.meka.core.domain.Autonomy(replica, nowMs)
    private val goals = Goals(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val fasting = Fasting(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val shutdown = EveningShutdown(replica, tasks, nowMs, ZoneCalendar(timeZone))
    private val brief = MorningBrief(replica, nowMs, ZoneCalendar(timeZone))
    private val news = os.meka.core.domain.News(replica)
    private val weather = os.meka.core.domain.WeatherStore(replica)
    private val weatherPlace = os.meka.core.domain.WeatherPlaceStore(replica)
    // Places item 2: the work place's forecast (server-written) and the work place setting (synced).
    private val workWeather = os.meka.core.domain.WeatherStore(replica, os.meka.core.domain.WeatherStore.WORK_ENTITY_ID)
    private val workPlace = os.meka.core.domain.WorkPlaceStore(replica)
    // Places item 4: the route's train lines (TfL status, server-written).
    private val lineStatus = os.meka.core.domain.LineStatusStore(replica)
    private val signIns = os.meka.core.domain.SignInStore(replica)
    // The call assistant's credit (low-balance guard, server-written).
    private val callCredit = os.meka.core.domain.CallCreditStore(replica)
    private val review = WeeklyReview(replica, nowMs, ZoneCalendar(timeZone))
    /** The week the review screen shows (null: the default for today); a screen choice, not synced. */
    private var reviewOffset: Int? = null
    private val notifyPrefs = NotificationPrefs(replica)
    private val interruptions = os.meka.core.domain.Interruptions(replica, ZoneCalendar(timeZone))
    private val activity = os.meka.core.domain.ActivityLog(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    /** Earned trust (V1): yeses in a row per kind of Ask card, and the kinds Meka said "just do it" to. */
    private val trust = os.meka.core.domain.EarnedTrust(replica)
    /** Which kind each tapped card's undo belongs to, so its Undo restarts that kind's run (this session only). */
    private val trustOfUndo = mutableMapOf<os.meka.core.domain.AskUndo, os.meka.core.domain.TrustKind>()
    private val eventActions = os.meka.core.domain.EventActions(replica, tasks, nowMs, ZoneCalendar(timeZone))
    private val held = os.meka.core.domain.HeldMessages(replica, nowMs)
    private val requestCards = os.meka.core.domain.RequestCards(replica, nowMs, ZoneCalendar(timeZone))
    // The approvals inbox (V1, slice 1): requests to do something with money or documents, answered in Needs you.
    private val approvalStore = os.meka.core.domain.Approvals(replica, nowMs, ZoneCalendar(timeZone))
    private val triageCards = os.meka.core.domain.TriageCards(replica, nowMs, ZoneCalendar(timeZone))
    private val gistStore = os.meka.core.domain.GroupGists(replica, nowMs)
    // Spam call protection (call assistant polish 8b): the synced block list.
    private val blockList = os.meka.core.domain.BlockedCallers(replica, nowMs, ZoneCalendar(timeZone))
    // Leave-by alarms are worked out from the calendar on every read (Alarms, slice 3).
    private val alarms = os.meka.core.domain.Alarms(replica, nowMs, ZoneCalendar(timeZone)) {
        os.meka.core.domain.LeaveAlarmRules.alarms(currentEvents(), eventActions.marks(), nowMs(), ZoneCalendar(timeZone))
    }
    /**
     * Calendar editing: the accounts where editing is allowed, as the server last listed them ([connectedAccounts]);
     * empty until read. The server checks again before it sends anything.
     */
    private val _editAccounts = MutableStateFlow<List<os.meka.core.domain.EditAccount>>(emptyList())
    private val calendarEdits = os.meka.core.domain.CalendarEdits(replica, nowMs, ids::next) { p, a ->
        _editAccounts.value.any { it.provider == p && it.email == a }
    }
    /** Plan my day's "Also add the blocks to Google" (calendar editing slice 2e), synced, off by default. */
    private val planCalendar = os.meka.core.domain.PlanCalendar(replica)
    private var syncClient: SyncClient? = transport?.let { SyncClient(replica, it) }
    private var accountsApi: AccountsApi? = transport as? AccountsApi
    private var releasesApi: ReleasesApi? = transport as? ReleasesApi
    private var pushApi: PushApi? = transport as? PushApi
    private var newsImagesApi: NewsImagesApi? = transport as? NewsImagesApi
    private var aiApi: AiApi? = transport as? AiApi
    private var speechApi: SpeechApi? = transport as? SpeechApi
    private var hereApi: HereApi? = transport as? HereApi
    private var healthApi: HealthApi? = transport as? HealthApi
    private var voiceMessageApi: VoiceMessageApi? = transport as? VoiceMessageApi
    private var familyApi: FamilyApi? = transport as? FamilyApi
    /** Family (sharing slice 4): the links as the server last listed them, in memory only (the server holds them). */
    private var familyMembers: List<os.meka.core.domain.FamilyMember> = emptyList()
    private val _family = MutableStateFlow<os.meka.core.domain.FamilyView?>(null)
    private var deviceLinkApi: DeviceLinkApi? = transport as? DeviceLinkApi
    /** Watch (Galaxy Watch slice 1): the linked watches as the server last listed them, in memory only. */
    private var linkedWatches: List<os.meka.core.domain.LinkedWatch> = emptyList()
    private val _watchLink = MutableStateFlow<os.meka.core.domain.WatchLinkView?>(null)
    /** "Where I am now" (Places item 3): the last answer, in memory only (never stored or synced), on the core thread. */
    private var hereFix: os.meka.core.domain.HereFix? = null
    /** MEKA's voice: the synced choice, the clips said so far (in memory, newest last) and a pause after a refusal. */
    private val mekaVoice = os.meka.core.domain.MekaVoiceStore(replica)
    private val speechClips = LinkedHashMap<String, String>()
    private var speechQuietUntilMs = 0L
    private var speechWarmed = false
    private val speechTimings = ArrayDeque<os.meka.core.domain.SpeechTiming>()

    // Declared before Today: Today's timeline reads the booked sessions.
    private val _sessions = MutableStateFlow(os.meka.core.domain.SessionsView.EMPTY)
    /**
     * The Gym (booked habits, [os.meka.core.domain.SessionRules]): the week's sessions booked around the calendar and
     * work, and today's card ("Today 17:45–18:45", "Did you go?", "Rebooked for Thu 17:45"). Moves with the clock.
     */
    val sessionsView: StateFlow<os.meka.core.domain.SessionsView> = _sessions.asStateFlow()

    private val _today = MutableStateFlow(project())
    val today: StateFlow<Today> = _today.asStateFlow()

    private val _lists = MutableStateFlow(ListsView.EMPTY)
    /**
     * Waiting for, Someday, Decisions and Renewals, with what is due to chase, review, renew or pay today. Synced;
     * moves with the clock.
     */
    val listsView: StateFlow<ListsView> = _lists.asStateFlow()

    private val _needsYouStack = MutableStateFlow(os.meka.core.domain.NeedsYouStack.EMPTY)
    /**
     * Needs you as a stack of decisions (four tabs, slice 2): Today's Needs you, then "From your lists" when something
     * is due, each card with its why and what right / left / up do. Follows edits and sync; moves with the clock.
     */
    val needsYouStack: StateFlow<os.meka.core.domain.NeedsYouStack> = _needsYouStack.asStateFlow()

    private val _goals = MutableStateFlow(GoalsView.EMPTY)
    /** Habits (pace, streaks, this week) and goals (progress). Synced; moves with the clock. */
    val goalsView: StateFlow<GoalsView> = _goals.asStateFlow()

    private val _fasting = MutableStateFlow(FastingView.EMPTY)
    /** The running fast, the eating window and the last seven days. Synced; moves with the clock. */
    val fastingView: StateFlow<FastingView> = _fasting.asStateFlow()

    private val _shutdown = MutableStateFlow(ShutdownView.EMPTY)
    /** Evening shutdown: what got done, what's left from today, tomorrow at a glance. Synced; moves with the clock. */
    val shutdownView: StateFlow<ShutdownView> = _shutdown.asStateFlow()

    private val _wake = MutableStateFlow(os.meka.core.domain.WakeView.EMPTY)
    /**
     * The smart wake alarm (Alarms, slice 1): the next morning's suggested wake time (its first commitment less the
     * get-ready buffer) and the alarm if Meka has set it. Synced; moves with the clock and the calendar.
     */
    val wakeView: StateFlow<os.meka.core.domain.WakeView> = _wake.asStateFlow()

    private val _nextAlarm = MutableStateFlow<os.meka.core.domain.AlarmRing?>(null)
    /**
     * The alarm that rings next or is ringing now (null when none is on), for the platform's own alarm: the Fold
     * registers it with `setAlarmClock`, the Mac schedules a notification. Follows sync, so Dismiss on one device
     * stops the other.
     */
    val nextAlarm: StateFlow<os.meka.core.domain.AlarmRing?> = _nextAlarm.asStateFlow()

    private val _quickAlarms = MutableStateFlow<List<os.meka.core.domain.QuickAlarmItem>>(emptyList())
    /**
     * Quick alarms and timers typed into capture (Alarms, slice 2) still to ring or ringing, soonest first, for Today's
     * slim rows ("Pasta" · "20 min · ends 14:52 · 18 min left" with a cancel ✕). Synced; moves with the minute.
     */
    val quickAlarms: StateFlow<List<os.meka.core.domain.QuickAlarmItem>> = _quickAlarms.asStateFlow()

    private val _brief = MutableStateFlow(MorningBriefView.EMPTY)
    /**
     * Morning brief: today at a glance, what you're waiting on, what needs you on your lists, habits and a running
     * fast. Offered from the end of quiet hours until noon; synced "Got it". Moves with the clock.
     */
    val briefView: StateFlow<MorningBriefView> = _brief.asStateFlow()

    private val _weather = MutableStateFlow(os.meka.core.domain.WeatherView.EMPTY)
    /**
     * Weather for home (weather item, slice 1): Today's header line ("14° · light rain from 16:00") and tomorrow's
     * ("Tomorrow 9–15°, light rain from 15:00 — take a coat"), from the forecast the server mirrors every 30 minutes
     * (Open-Meteo, Biggleswade). Empty until the first forecast arrives; moves with the clock.
     */
    val weatherView: StateFlow<os.meka.core.domain.WeatherView> = _weather.asStateFlow()

    private val _signIn = MutableStateFlow<List<os.meka.core.domain.SignInLine>>(emptyList())
    /**
     * Today's sign-in line (Reliability first, item 2): the most urgent calendar sign-in that is about to end or already
     * expired, as the server last wrote it ([os.meka.core.domain.SignInRules]); empty while every sign-in is fine. Tap
     * Reconnect: [startConnect] with the line's provider and `editing`.
     */
    val signInLine: StateFlow<List<os.meka.core.domain.SignInLine>> = _signIn.asStateFlow()

    private val _newsPlace = MutableStateFlow(os.meka.core.domain.NewsPlace.EMPTY)
    /**
     * The News place (Ask → More → News): the chosen topics as lanes (Barça, then AI, then the rest), each story once,
     * the last two days; [os.meka.core.domain.NewsPlace.detail] gives the detail sheet with Next/Previous. Headlines
     * are untrusted (ADR-006): text only, https links only.
     */
    val newsPlace: StateFlow<os.meka.core.domain.NewsPlace> = _newsPlace.asStateFlow()

    private val _review = MutableStateFlow(WeeklyReviewView.EMPTY)
    /**
     * Weekly review: the week looked back on (done, habits, fasts, lists, still open), the week ahead, and the
     * north-star numbers (ADR-013). Step weeks with [showReviewWeek]. Synced "Done reviewing"; moves with the clock.
     */
    val reviewView: StateFlow<WeeklyReviewView> = _review.asStateFlow()

    private val _wins = MutableStateFlow(os.meka.core.domain.WinsView.EMPTY)
    /**
     * Work wins (V1, slice 1): the reviewed week's wins (it follows [showReviewWeek]) and this quarter's, week by week.
     * Shown in the Review tab on both apps; Friday at 16:00 MEKA asks "What went well this week?". Synced.
     */
    val winsView: StateFlow<os.meka.core.domain.WinsView> = _wins.asStateFlow()

    private val _calendar = MutableStateFlow(CalendarView.EMPTY)
    /**
     * The Calendar tab: week strips and the next 30 days grouped by day (events, all-day events, fixtures and planned
     * tasks; free stretches folded). Follows sync and edits; moves with the clock.
     */
    val calendarView: StateFlow<CalendarView> = _calendar.asStateFlow()

    /** What the search field holds (a screen choice, not synced). */
    private var searchQuery: String = ""
    private val _eventMarks = MutableStateFlow(os.meka.core.domain.EventMarks.NONE)
    /** Hidden events and prep tasks (calendar actions); the event detail reads it. */
    val eventMarks: StateFlow<os.meka.core.domain.EventMarks> = _eventMarks.asStateFlow()

    /** Accounts MEKA may add events to (calendar editing), for the Add event button and sheet. */
    val calendarEditAccounts: StateFlow<List<os.meka.core.domain.EditAccount>> = _editAccounts.asStateFlow()

    /** Every synced calendar edit as last read (the event detail's note reads it off the core's thread). */
    private val _editsSeen = MutableStateFlow<List<os.meka.core.domain.EventEdit>>(emptyList())
    private val _editLines = MutableStateFlow<List<os.meka.core.domain.EditLine>>(emptyList())
    /**
     * Edits on their way to Google/Outlook, needing Meka, or just sent ("Adding “Dentist” to Google", "Added …"),
     * newest first, for the Calendar screen. Synced (the other device's edits too); moves with the clock.
     */
    val calendarEditLines: StateFlow<List<os.meka.core.domain.EditLine>> = _editLines.asStateFlow()

    private val _calendarsOnToday = MutableStateFlow<List<os.meka.core.domain.CalendarChoice>>(emptyList())
    /** Calendars' "On Today" list: every calendar in the mirror and whether it shows on Today (all-day polish). */
    val calendarsOnToday: StateFlow<List<os.meka.core.domain.CalendarChoice>> = _calendarsOnToday.asStateFlow()

    private val _activity = MutableStateFlow(os.meka.core.domain.ActivityView.EMPTY)
    /** What MEKA did and why (V1 activity log): the last 30 days by day, newest first; follows sync. */
    val activityView: StateFlow<os.meka.core.domain.ActivityView> = _activity.asStateFlow()

    /** The log changed outside a full refresh: the Activity screen and Today's evening receipt follow it. */
    private fun activityChanged() {
        val items = activity.items()
        _activity.value = os.meka.core.domain.ActivityRules.view(items, nowMs(), ZoneCalendar(timeZone))
        _shutdown.value = _shutdown.value.copy(receipt = os.meka.core.domain.ReceiptRules.receipt(items, nowMs(), ZoneCalendar(timeZone)))
    }

    private val _search = MutableStateFlow(SearchView.EMPTY)
    /**
     * Search everything: tasks (open, Someday, done), calendar events, Waiting for, decisions, renewals, habits and
     * goals matching [search]'s query, grouped by kind. Local only; follows edits and sync while a query is set.
     */
    val searchView: StateFlow<SearchView> = _search.asStateFlow()

    private val _afterWork = MutableStateFlow(os.meka.core.domain.AfterWorkSummary(emptyList()))
    /**
     * "While you were at work": what the Fold held during work mode, grouped by person (urgent, then family, then
     * latest). Synced (Needs Meka #10), so the Mac shows the same summary; Done on either device clears it on both.
     */
    val afterWork: StateFlow<os.meka.core.domain.AfterWorkSummary> = _afterWork.asStateFlow()

    private val _school = MutableStateFlow(os.meka.core.domain.SchoolView.EMPTY)
    private val _bins = MutableStateFlow(os.meka.core.domain.BinsView.EMPTY)

    /**
     * Bin days (V1, slice 1): each bin typed once with its day and pattern (Ask → More → Bins); the evening before a
     * collection Today says "Black bin and food caddy out tonight" and a heads-up comes at 19:00.
     */
    val binsView: StateFlow<os.meka.core.domain.BinsView> = _bins.asStateFlow()

    private val _car = MutableStateFlow(os.meka.core.domain.CarView.EMPTY)
    /**
     * Car on autopilot (V1, slice 1): the registration and the car's four dates (MOT, tax, insurance, service) against
     * the renewals radar (Lists → Renewals → Car). Synced.
     */
    val carView: StateFlow<os.meka.core.domain.CarView> = _car.asStateFlow()
    /**
     * School rhythm (V1, slice 1): Rex's and Logan's days off, dates and weekly things (Ask → More → School), and the
     * week-ahead questions ("Rex and Logan are off Mon 27 Oct · You're in the office that day. Who's covering?") that
     * Needs you shows above the requests. Synced, so both apps show the same; an answer on either clears both.
     */
    val schoolView: StateFlow<os.meka.core.domain.SchoolView> = _school.asStateFlow()

    private val _leave = MutableStateFlow(os.meka.core.domain.LeaveView.EMPTY)
    /**
     * Annual leave (V1, slice 1; Ask → More → Annual leave): the days booked against the allowance this leave year,
     * the stretches to come and taken, and the school holidays coming up with how much of each is booked. Synced;
     * booked days are days off for work mode on both apps.
     */
    val leaveView: StateFlow<os.meka.core.domain.LeaveView> = _leave.asStateFlow()

    private val _meals = MutableStateFlow(os.meka.core.domain.MealPlanView.EMPTY)
    /**
     * Meal plan → shopping (V1, slice 1): favourite dinners with their ingredients, the week's dinners (today and the
     * six days after) and what "Add to shopping" would put on the shared list (Ask → More → Dinners). Synced.
     */
    val mealsView: StateFlow<os.meka.core.domain.MealPlanView> = _meals.asStateFlow()

    private val _emergency = MutableStateFlow(os.meka.core.domain.EmergencyView.EMPTY)
    /**
     * The emergency card (V1, slice 1): who to call, where important documents are and key accounts by name only
     * (Ask → More → Emergency card). Synced; Jeanette sees it read-only on her family page. Never a password, PIN or
     * card or account number ([os.meka.core.domain.EmergencyRules.refusal]).
     */
    val emergencyView: StateFlow<os.meka.core.domain.EmergencyView> = _emergency.asStateFlow()

    private val _facts = MutableStateFlow(os.meka.core.domain.FactsView.EMPTY)
    /**
     * What MEKA knows about Meka's life (V1, slice 1; Ask → More → What MEKA knows): the facts he keeps (the car's reg,
     * the GP, sizes, the utilities) in groups, each visible, editable and deletable, and each can be kept from the AI.
     * Synced between his devices only. Never a password, PIN or card, bank or ID number
     * ([os.meka.core.domain.FactRules.refusal]).
     */
    val factsView: StateFlow<os.meka.core.domain.FactsView> = _facts.asStateFlow()

    private val _queue = MutableStateFlow(os.meka.core.domain.QueueView.EMPTY)
    /**
     * The train queue (V1, train-time queue slice 1): links and podcasts shared to MEKA for the commute, waiting
     * newest first, and the ones marked done lately (Ask → More → Train queue). Synced; nothing is fetched.
     */
    val queueView: StateFlow<os.meka.core.domain.QueueView> = _queue.asStateFlow()

    private val _errands = MutableStateFlow(os.meka.core.domain.ErrandView.EMPTY)
    /** Errands by location (V1, slice 1): what waits at each place, and what was ticked off lately. */
    val errandsView: StateFlow<os.meka.core.domain.ErrandView> = _errands.asStateFlow()

    private val _appointments = MutableStateFlow(os.meka.core.domain.AppointmentView.EMPTY)
    /**
     * Appointment nudges (V1, slice 1): the dentist, the optician, a haircut… each due from its last visit, and the
     * cards Needs you shows for the ones that have come due.
     */
    val appointmentsView: StateFlow<os.meka.core.domain.AppointmentView> = _appointments.asStateFlow()

    private val _dateNight = MutableStateFlow(os.meka.core.domain.DateNightView.EMPTY)
    /**
     * Date night (V1, slice 1): the fortnightly evening the planner and Gym bookings keep clear, its next few nights
     * and which can be skipped (Ask → More → Date night). Synced.
     */
    val dateNightView: StateFlow<os.meka.core.domain.DateNightView> = _dateNight.asStateFlow()

    private val _protected = MutableStateFlow(os.meka.core.domain.ProtectedView.EMPTY)
    /**
     * Protected time (V1, slice 1): evenings and weekends the planner and Move to later keep tasks out of (Ask → More →
     * Protected time). Synced.
     */
    val protectedView: StateFlow<os.meka.core.domain.ProtectedView> = _protected.asStateFlow()

    private val _leaving = MutableStateFlow(os.meka.core.domain.LeavingView.EMPTY)
    /** "Leaving now" (V1): the draft to Jeanette when an office day's work ends (Work mode → Leaving now). Synced. */
    val leavingView: StateFlow<os.meka.core.domain.LeavingView> = _leaving.asStateFlow()

    private val _weekAhead = MutableStateFlow(os.meka.core.domain.WeekAheadView.EMPTY)
    /**
     * The Sunday week ahead (V1, slice 1): the coming week day by day (office or home, events, fixtures, school, date
     * night, appointments, renewals, weather) and the family sync (Ask → More → Week ahead; Sunday's 18:00 heads-up).
     */
    val weekAheadView: StateFlow<os.meka.core.domain.WeekAheadView> = _weekAhead.asStateFlow()

    private val _weekAheadSetting = MutableStateFlow(os.meka.core.domain.WeekAheadSettingView.EMPTY)
    /** The week ahead's Sunday heads-up (slice 2): on or off and its time (default 18:00). Synced. */
    val weekAheadSettingView: StateFlow<os.meka.core.domain.WeekAheadSettingView> = _weekAheadSetting.asStateFlow()

    private val _autonomy = MutableStateFlow(os.meka.core.domain.AutonomyView.EMPTY)
    /**
     * What MEKA may do (V1, policy engine UI, slice 1): the kill switch, each earned-trust kind and the locked rows
     * (Ask → More → What MEKA may do). Synced.
     */
    val autonomyView: StateFlow<os.meka.core.domain.AutonomyView> = _autonomy.asStateFlow()

    private val _requests = MutableStateFlow<List<os.meka.core.domain.RequestCard>>(emptyList())
    /**
     * Requests from people Meka watches (V1, slice 2): the open Needs you cards MEKA proposed from their messages,
     * oldest first. Synced, so the Mac shows the same cards; Add or Not a task on either device clears both.
     */
    val requests: StateFlow<List<os.meka.core.domain.RequestCard>> = _requests.asStateFlow()

    private val _approvals = MutableStateFlow<List<os.meka.core.domain.ApprovalCard>>(emptyList())
    /**
     * The approvals inbox (V1, slice 1; ADR-006 addendum 2026-10-11): requests from outside MEKA to trade, pay or share
     * a document, waiting for Meka in Needs you, the soonest to expire first. Approving needs his fingerprint (Touch ID
     * on the Mac) every time; declining a tap. Synced, so an answer on either device clears both.
     */
    val approvals: StateFlow<List<os.meka.core.domain.ApprovalCard>> = _approvals.asStateFlow()

    private val _triage = MutableStateFlow<List<os.meka.core.domain.TriageCard>>(emptyList())
    /**
     * The messages assistant (V1, slice 2): the open triage cards — Needs a reply (with the drafted reply) first, then
     * FYI, newest first. Synced (lane, gist and draft only; never the message), so the Mac shows the same cards. An
     * Action's proposals are in [requests].
     */
    val triage: StateFlow<List<os.meka.core.domain.TriageCard>> = _triage.asStateFlow()

    private val _groupGists = MutableStateFlow<List<os.meka.core.domain.GroupGist>>(emptyList())
    /**
     * The group digest's synced cards (V1, messages slice 4b): one per busy group at the latest digest time — its name,
     * how many messages, who wrote and the AI's gist; never the messages. The Mac's whole digest; the Fold shows the gist
     * on its own cards. Caught-up ones are left out.
     */
    val groupGists: StateFlow<List<os.meka.core.domain.GroupGist>> = _groupGists.asStateFlow()

    private val _groupDigestCaughtUp = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** When Meka caught up with each digest group on either device (group key → when), for the Fold's own digest. */
    val groupDigestCaughtUp: StateFlow<Map<String, Long>> = _groupDigestCaughtUp.asStateFlow()

    private val _blockedCallers = MutableStateFlow(os.meka.core.domain.BlockedCallersView.EMPTY)
    /** Work mode → Blocked numbers: the synced block list, newest first (spam call protection). */
    val blockedCallers: StateFlow<os.meka.core.domain.BlockedCallersView> = _blockedCallers.asStateFlow()

    private val _workMode = MutableStateFlow(work.state(localClock(), todayEpochDay()))
    /** Work mode (schedule + manual switch), synced between devices. Time moves it: apps call [tick] each minute. */
    val workMode: StateFlow<WorkModeState> = _workMode.asStateFlow()

    private val _notifySettings = MutableStateFlow(NotificationSettings.DEFAULT)
    /** Quiet hours, digest times and sources moved to a lower tier (notification governor). Synced. */
    val notificationSettings: StateFlow<NotificationSettings> = _notifySettings.asStateFlow()

    private val _notifyPreview = MutableStateFlow(NotificationPreview("", ""))
    /** "Quiet until 07:00", "Next digest 18:00 · 3 things so far", for the settings screens. Moves with the clock. */
    val notificationPreview: StateFlow<NotificationPreview> = _notifyPreview.asStateFlow()

    private val _sync = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncStatus: StateFlow<SyncStatus> = _sync.asStateFlow()

    private val _conflicts = MutableStateFlow<List<ConflictChoice>>(emptyList())
    val conflicts: StateFlow<List<ConflictChoice>> = _conflicts.asStateFlow()

    private var syncLoop: Job? = null
    private var failures = 0
    /** One sync round at a time: the confined dispatcher alone would interleave rounds at suspension points. */
    private val syncMutex = Mutex()

    init {
        replica.addListener { refresh(); requestSync() }
        refresh()
    }

    // ---- Commands (suspend → Swift async via SKIE) ----

    suspend fun addTask(title: String): String = onCore { tasks.create(NewTask(title)) }
    /**
     * Capture from anywhere: typed, spoken or shared text becomes one task (first line the title, the rest in
     * the notes, see [QuickCapture]). Returns the new task's id, or null when there was nothing to capture.
     */
    suspend fun capture(text: String?, subject: String?): String? {
        val draft = QuickCapture.draft(text, subject) ?: return null
        return onCore { tasks.create(NewTask(draft.title, notes = draft.notes)) }
    }
    /**
     * What Meka types into MEKA's own capture field (Today's capture bar, the Mac's capture field, menu bar and command
     * bar): "alarm 6:30" or "timer 20 min" sets a quick alarm or timer (Alarms, slice 2, [QuickAlarmRules]); anything
     * else is captured as a task like [capture]. Never used for shared text (share sheet, Services), which is untrusted
     * and only ever becomes a task.
     */
    suspend fun captureTyped(text: String): os.meka.core.domain.CaptureOutcome {
        val cal = ZoneCalendar(timeZone)
        val q = os.meka.core.domain.QuickAlarmRules.parse(text, nowMs(), cal)
        if (q != null) {
            return onCore {
                val id = alarms.setQuick(q)
                if (id == null) os.meka.core.domain.CaptureOutcome.Empty
                else os.meka.core.domain.CaptureOutcome.AlarmSet(id, q.kind, os.meka.core.domain.QuickAlarmRules.setLine(q, nowMs(), cal))
            }
        }
        val id = capture(text, null) ?: return os.meka.core.domain.CaptureOutcome.Empty
        return os.meka.core.domain.CaptureOutcome.TaskAdded(id)
    }
    /** Cancels a quick alarm or timer on every device (Today's ✕, or Undo after setting it). */
    suspend fun cancelAlarm(id: String): Boolean = onCore { alarms.cancel(id) }
    suspend fun complete(taskId: String) = onCore { tasks.complete(taskId) }
    suspend fun reopen(taskId: String) = onCore { tasks.reopen(taskId) }
    /** Renames the task; its calendar block (Plan my day's "Also add the blocks") is renamed with it (slice 2g). */
    suspend fun rename(taskId: String, title: String) = onCore { tasks.edit(taskId, TaskEdit(title = title)); followBlocks(listOf(taskId)); Unit }
    suspend fun schedule(taskId: String, atMs: Long?) = onCore {
        tasks.edit(taskId, if (atMs == null) TaskEdit(clearScheduledAt = true) else TaskEdit(scheduledAtMs = atMs))
        followBlocks(listOf(taskId))
        Unit
    }
    suspend fun delete(taskId: String) = onCore { tasks.delete(taskId); followBlocks(listOf(taskId)); Unit }

    /**
     * "Move to later" on a late planned task (Fold review 2026-10-09 13:45, item 3; [os.meka.core.domain.LateTaskRules]):
     * re-plans it to the first free stretch from now, around today's events, booked sessions and the other planned
     * tasks. Returns what the undo bar needs ("Moved “Send the invoice” to 15:30"), or null when the task has no time or
     * today has no room left (nothing changes).
     */
    suspend fun moveLater(taskId: String): os.meka.core.domain.LaterMove? = onCore {
        val now = nowMs()
        val day = dayWindow(now)
        val all = tasks.all()
        val t = all.firstOrNull { it.id == taskId } ?: return@onCore null
        val from = t.scheduledAtMs ?: return@onCore null
        val open = all.filter {
            (it.lifecycle == os.meka.core.domain.Lifecycle.ACTIVE || it.lifecycle == os.meka.core.domain.Lifecycle.INBOX) &&
                !it.waitsForItsDay(day.epochDay)
        }
        // Protected time and date night stay clear of a moved task too.
        val cal = ZoneCalendar(timeZone)
        val kept = protectedSlots(day.epochDay) + listOfNotNull(os.meka.core.domain.DateNightRules.slot(dateNight.setting(), day.epochDay, cal))
        val to = os.meka.core.domain.LateTaskRules.laterSlot(
            t, open, visibleEvents(all), _sessions.value.todayBlocks(day.epochDay, now), now, day, kept = kept,
        ) ?: return@onCore null
        tasks.edit(taskId, TaskEdit(scheduledAtMs = to))
        followBlocks(listOf(taskId))
        val hhmm = os.meka.core.domain.LocalClock.formatMinute(ZoneCalendar(timeZone).minuteOfDay(to))
        os.meka.core.domain.LaterMove(taskId, from, to, os.meka.core.domain.LateTaskRules.movedLine(t.title, hhmm))
    }

    /** Undo on "Moved … to 15:30": the task goes back to the time it had. */
    suspend fun undoMoveLater(move: os.meka.core.domain.LaterMove) = onCore {
        tasks.edit(move.taskId, TaskEdit(scheduledAtMs = move.fromMs))
        followBlocks(listOf(move.taskId))
        Unit
    }

    /**
     * A suggested plan for the rest of today (DayPlanner v1), making room first for habits that are behind or due,
     * and keeping meals free around a fast. Changes nothing until [applyPlan].
     */
    suspend fun planDay(): DayPlanner.Plan = onCore {
        val now = nowMs()
        val day = dayWindow(now)
        val all = tasks.all()
        val sessions = _sessions.value.todayBlocks(day.epochDay, now)
            .map { DayPlanner.HabitPlacement(it.habitId, listOfNotNull(it.title, it.label).joinToString(" · "), it.startMs, it.endMs, behind = false) }
        // Date night (slice 1) is kept free like a fasting meal.
        val kept = fasting.plannerMeals(day) + listOfNotNull(os.meka.core.domain.DateNightRules.block(dateNight.setting(), day.epochDay, ZoneCalendar(timeZone)))
        // Protected time (slice 1): no tasks in the protected evening or on a protected day.
        val shield = protectedTime.setting()
        val schedule = work.schedule()
        val holidays = bankHolidays.calendar()
        DayPlanner.plan(
            all, visibleEvents(all), now, day, habits = goals.plannerHabits(), meals = kept.sortedBy { it.startMs }, sessions = sessions,
            protected = protectedSlots(day.epochDay),
            protectedNote = os.meka.core.domain.ProtectedRules.planNote(shield, day.epochDay, schedule, holidays),
            waitLabel = os.meka.core.domain.ProtectedRules.waitLabel(shield, day.epochDay, schedule, holidays),
        )
    }

    /** [day]'s protected time (Protected time, slice 1) as the planner's slots. */
    private fun protectedSlots(day: Long): List<DayPlanner.Slot> =
        os.meka.core.domain.ProtectedRules.slots(protectedTime.setting(), day, work.schedule(), bankHolidays.calendar(), ZoneCalendar(timeZone))

    /**
     * Schedules each planned task at its suggested time; everything syncs like a manual edit. With "Also add the
     * blocks" on (slice 2e) and an account that allows editing, each task's block is also added to that calendar as an
     * ordinary add with five seconds' Undo ([undoPlanBlocks]); the result names those adds and the undo bar's line.
     */
    suspend fun applyPlan(plan: DayPlanner.Plan): os.meka.core.domain.PlanApplied = onCore {
        plan.placements.forEach { tasks.edit(it.task.id, TaskEdit(scheduledAtMs = it.startMs)) }
        // Plan again (slice 2f): a task that already has a block moves it rather than adding a second one.
        val edits = calendarEdits.all()
        val overlaid = os.meka.core.domain.PendingEditRules.apply(events.all(), edits, nowMs())
        val (followed, fresh) = plan.placements.partition { p ->
            os.meka.core.domain.PlanCalendarRules.blockOf(p.task.id, overlaid, edits)?.removed == false
        }
        followBlocks(followed.map { it.task.id })
        val target = os.meka.core.domain.PlanCalendarRules.target(
            _editAccounts.value, os.meka.core.domain.AddEventRules.lastUsedKey(calendarEdits.all()),
        )
        if (!planCalendar.on() || target == null || fresh.isEmpty()) {
            os.meka.core.domain.PlanApplied(emptyList(), null)
        } else {
            val made = mutableListOf<String>()
            val refused = mutableListOf<String>()
            for (p in fresh) {
                when (val r = calendarEdits.add(target.provider, target.email, os.meka.core.domain.PlanCalendarRules.draft(p), forTask = p.task.id)) {
                    is os.meka.core.domain.EventEditResult.Made -> made += r.id
                    is os.meka.core.domain.EventEditResult.Refused -> refused += "${p.task.title}: ${r.reason}"
                }
            }
            os.meka.core.domain.PlanApplied(
                made, made.takeIf { it.isNotEmpty() }?.let { os.meka.core.domain.PlanCalendarRules.line(it.size, target.provider) }, refused,
            )
        }
    }

    /** Undo on "Adding 3 blocks to Google": the blocks inside their five seconds are taken back; the tasks stay planned. */
    suspend fun undoPlanBlocks(editIds: List<String>): Boolean = onCore { editIds.map { calendarEdits.undo(it) }.any { it } }

    /** Plan my day's "Also add the blocks to Google" as shown (read the accounts first: [refreshCalendarAccounts]). */
    suspend fun planCalendarSetting(): os.meka.core.domain.PlanCalendarSetting = onCore {
        os.meka.core.domain.PlanCalendarRules.setting(
            planCalendar.on(), _editAccounts.value, os.meka.core.domain.AddEventRules.lastUsedKey(calendarEdits.all()),
        )
    }

    /** Turns "Also add the blocks" on or off (synced; off by default). */
    suspend fun setPlanToCalendar(on: Boolean): os.meka.core.domain.PlanCalendarSetting {
        onCore { planCalendar.set(on) }
        return planCalendarSetting()
    }
    suspend fun restore(taskId: String) = onCore { tasks.restore(taskId); followBlocks(listOf(taskId)); Unit }

    /** Blocks whose add was taken back inside its five seconds because the task went (Delete, Someday): see [followBlocks]. */
    private val takenBackAdds = HashMap<String, os.meka.core.domain.EventEdit>()

    /**
     * Slice 2g: tasks Meka changed here while their block was still on its way to the calendar; after each sync the
     * block follows once the calendar has it ([catchUpBlocks]). Kept on this device only, so the other never follows too.
     * Slice 2h: kept as a device-local value of the store ([WaitingFollowCodec], never synced), read on first use, so a
     * wait survives closing the app; [saveWaitingFollows] writes it back whenever it changes.
     */
    private val waitingFollows: HashMap<String, os.meka.core.domain.WaitingFollow> by lazy {
        val saved = runCatching { localStore.transaction { localStore.localValue(WaitingFollowCodec.KEY) } }.getOrNull()
        savedWaitingFollows = saved
        WaitingFollowCodec.decode(saved).associateByTo(HashMap()) { it.taskId }
    }
    private val localStore = store
    private var savedWaitingFollows: String? = null

    private fun saveWaitingFollows() {
        val text = WaitingFollowCodec.encode(waitingFollows.values)
        if (text == savedWaitingFollows) return
        runCatching { localStore.transaction { localStore.setLocalValue(WaitingFollowCodec.KEY, text) } }
            .onSuccess { savedWaitingFollows = text }
    }

    /**
     * After a sync: blocks that were on their way follow their tasks now that the calendar may have them. Just after
     * opening the app nothing has read which accounts allow editing yet, so that is read first; while it can't be
     * (offline), the waits stay for the next sync rather than being refused and dropped.
     */
    private suspend fun catchUpBlocks() {
        if (waitingFollows.isEmpty()) return
        val now = nowMs()
        waitingFollows.values.removeAll { !it.stillWanted(tasks.get(it.taskId), now) }
        if (waitingFollows.isNotEmpty() && _editAccounts.value.isEmpty()) connectedAccounts()
        if (waitingFollows.isNotEmpty() && _editAccounts.value.isNotEmpty()) followBlocks(waitingFollows.keys.toList())
        saveWaitingFollows()
    }

    /**
     * Slice 2f: keeps the calendar blocks of [taskIds] (from Plan my day's "Also add the blocks") in step after Meka
     * changed those tasks here ([os.meka.core.domain.PlanCalendarRules.follow]): each step is an ordinary calendar edit
     * with its five seconds, made for the task. An edit for the block still inside its five seconds is taken back
     * first and worked out afresh, so Google only gets where the task ended up (a quick Undo sends nothing at all).
     * Only Meka's own changes on this device lead here, never a sync, so two devices never both follow (a block still
     * on its way is caught up after a later sync on this same device only, see [waitingFollows]). Returns the lines of blocks that couldn't follow yet (still on their way to the calendar).
     */
    private fun followBlocks(taskIds: Collection<String>): List<String> {
        val lines = mutableListOf<String>()
        for (id in taskIds.distinct()) {
            var takenBack: os.meka.core.domain.EventEdit? = null
            var guard = 0
            while (true) {
                val edits = calendarEdits.all()
                val overlaid = os.meka.core.domain.PendingEditRules.apply(events.all(), edits, nowMs())
                val block = os.meka.core.domain.PlanCalendarRules.blockOf(id, overlaid, edits)
                val task = tasks.get(id)
                if (block == null) {
                    // Its add was still inside its five seconds: add it afresh where the task is now. Taken back for a
                    // delete or Someday, it is kept in mind for this session, so the undo bar's Undo brings it back.
                    val add = takenBack?.takeIf { it.kind == os.meka.core.domain.EventEditKind.ADD } ?: takenBackAdds[id]
                    val start = task?.takeIf { !it.lifecycle.isTerminal && it.lifecycle != os.meka.core.domain.Lifecycle.SOMEDAY }?.scheduledAtMs
                    val d = add?.draft
                    if (add != null && d != null && start != null && task != null) {
                        takenBackAdds.remove(id)
                        calendarEdits.add(add.provider, add.account, d.copy(title = task.title.trim().ifEmpty { d.title }, startAtMs = start, endAtMs = start + (d.endAtMs - d.startAtMs)), forTask = id)
                    } else if (add != null) {
                        takenBackAdds[id] = add
                    }
                    break
                }
                // A removal still in its five seconds comes back with Undo even with the setting off.
                val waiting = block.latest.state(nowMs()) == os.meka.core.domain.EventEditState.WAITING
                val step = os.meka.core.domain.PlanCalendarRules.follow(task, block, planCalendar.on() || waiting)
                if (step !is os.meka.core.domain.BlockStep.OnItsWay) waitingFollows.remove(id)
                if (step is os.meka.core.domain.BlockStep.None) break
                if (waiting && guard++ < 4) {
                    if (calendarEdits.undo(block.latest.id)) { takenBack = block.latest; continue }
                }
                when (step) {
                    is os.meka.core.domain.BlockStep.Move ->
                        calendarEdits.change(step.event, os.meka.core.domain.PlanCalendarRules.moved(step.event, step.startAtMs), forTask = id)
                    is os.meka.core.domain.BlockStep.Change -> calendarEdits.change(step.event, step.draft, forTask = id)
                    is os.meka.core.domain.BlockStep.Remove -> calendarEdits.delete(step.event, forTask = id)
                    is os.meka.core.domain.BlockStep.Add -> calendarEdits.add(step.provider, step.account, step.draft, forTask = id)
                    is os.meka.core.domain.BlockStep.OnItsWay -> {
                        lines += step.line
                        val now = nowMs()
                        waitingFollows[id] = waitingFollows[id]?.takeIf { it.stillWanted(task, now) }
                            ?: os.meka.core.domain.WaitingFollow.of(id, task, now)
                    }
                    os.meka.core.domain.BlockStep.None -> Unit
                }
                break
            }
        }
        saveWaitingFollows()
        return lines
    }

    // ---- Task detail: When and Notes (Fold review 2026-10-08, item 8) ----

    /** The When row for [task]: its day, optional time, the chips and where "Add a time" starts. Pure. */
    fun taskWhen(task: os.meka.core.domain.Task): os.meka.core.domain.TaskWhenView =
        os.meka.core.domain.TaskWhenRules.view(task, nowMs(), ZoneCalendar(timeZone))
    /** Puts the task on local [day] (today or later) at [minuteOfDay], or with no time when null. */
    suspend fun setWhen(taskId: String, day: Long, minuteOfDay: Int?) = onCore {
        tasks.setWhen(taskId, day, minuteOfDay)
        followBlocks(listOf(taskId))
        Unit
    }
    /** For Swift: [setWhen] with -1 for no time. */
    suspend fun setWhenMinute(taskId: String, day: Long, minuteOfDay: Int) =
        onCore { tasks.setWhen(taskId, day, minuteOfDay.takeIf { it >= 0 }); followBlocks(listOf(taskId)); Unit }
    /** The task's notes; blank clears them. */
    suspend fun setNotes(taskId: String, notes: String) = onCore { tasks.setNotes(taskId, notes) }
    /** Remind me: the row as shown ("Off" or when) and the chips still ahead of now. */
    fun taskReminder(task: os.meka.core.domain.Task): os.meka.core.domain.TaskReminderView =
        os.meka.core.domain.TaskReminderRules.view(task, nowMs(), ZoneCalendar(timeZone))
    /** Reminds about the task at [atMs] (a heads-up through the governor); null turns it off. */
    suspend fun setReminder(taskId: String, atMs: Long?) = onCore { tasks.setReminder(taskId, atMs) }
    /** For Swift: [setReminder] with -1 for off. */
    suspend fun setReminderAt(taskId: String, atMs: Long) = onCore { tasks.setReminder(taskId, atMs.takeIf { it >= 0 }) }

    // ---- Repeating tasks and routines ----

    /** The Repeat picker for a task: "Doesn't repeat" and the presets for its day, the current one selected. */
    suspend fun repeatChoices(taskId: String): List<RepeatChoice> = onCore { tasks.repeatChoices(taskId) }
    /** Sets a repeat from [repeatChoices] (its `rule`); null stops the task repeating. */
    suspend fun setRepeat(taskId: String, rule: String?) = onCore { tasks.setRepeatRule(taskId, rule) }
    /** Skips this occurrence of a repeating task; the next one is queued for its day. */
    suspend fun skipOccurrence(taskId: String) = onCore { tasks.skipOccurrence(taskId); followBlocks(listOf(taskId)); Unit }
    /** Moves just this occurrence (or a one-off task) out of Today for [days] days. */
    suspend fun snooze(taskId: String, days: Int = 1) = onCore { tasks.snoozeOccurrence(taskId, days); followBlocks(listOf(taskId)); Unit }

    /**
     * Done or Tomorrow from the Needs you stack ([os.meka.core.domain.DecisionEffect.COMPLETE_TASK] /
     * [os.meka.core.domain.DecisionEffect.SNOOZE_TASK]); returns what the undo bar needs, or null when nothing changed
     * (the task was already done, or the effect is one the app handles: opening or setting aside).
     */
    suspend fun decide(taskId: String, effect: os.meka.core.domain.DecisionEffect): os.meka.core.domain.DecisionUndo? =
        onCore { tasks.decide(taskId, effect).also { followBlocks(listOf(taskId)) } }

    /** Undo for [decide]: puts the task back only while it is still as the move left it. */
    suspend fun undoDecision(undo: os.meka.core.domain.DecisionUndo): Boolean =
        onCore { tasks.undoDecision(undo).also { followBlocks(listOf(undo.taskId)) } }

    /** Steps: a repeating task with steps is a routine, and each new occurrence brings them back unticked. */
    suspend fun addStep(taskId: String, text: String): String = onCore { tasks.addChecklistItem(taskId, text) }
    suspend fun setStepDone(stepId: String, done: Boolean) = onCore { tasks.setChecklistItemChecked(stepId, done) }
    suspend fun removeStep(stepId: String) = onCore { tasks.deleteChecklistItem(stepId) }

    /**
     * Checklists in tasks (2026-10-11): the detail's Checklist section, to tick first then "Ticked", with its line and
     * the complete offer. Pure.
     */
    fun checklistView(task: os.meka.core.domain.Task): os.meka.core.domain.ChecklistView = os.meka.core.domain.ChecklistRules.view(task)

    /** One typed or pasted line: each of its items (new lines, commas) goes at the end, unticked. Returns their ids. */
    suspend fun addSteps(taskId: String, text: String): List<String> = onCore { tasks.addChecklistItems(taskId, text) }

    /** Undo for [removeStep]: the item comes back with its tick and place. */
    suspend fun restoreStep(stepId: String) = onCore { tasks.restoreChecklistItem(stepId) }

    /** Untick all, to reuse the list; returns what it unticked, for [tickSteps] (Undo). */
    suspend fun untickAllSteps(taskId: String): List<String> = onCore { tasks.untickAllChecklistItems(taskId) }

    /** Ticks these items again (Undo for [untickAllSteps]). */
    suspend fun tickSteps(stepIds: List<String>) = onCore { stepIds.forEach { tasks.setChecklistItemChecked(it, true) } }

    /** Drag to reorder: the item takes [toIndex] in the list's order. */
    suspend fun moveStep(taskId: String, stepId: String, toIndex: Int) = onCore { tasks.moveChecklistItem(taskId, stepId, toIndex) }

    /** Drag to reorder in the task detail (slice 2): [toGroupIndex] counts the rows of the item's own group (still to tick, or Ticked). */
    suspend fun moveStepInGroup(taskId: String, stepId: String, toGroupIndex: Int) =
        onCore { tasks.moveChecklistItemInGroup(taskId, stepId, toGroupIndex) }

    /** Shop mode for a task's checklist (slice 2): big rows, still to get first. Pure. */
    fun shopMode(task: os.meka.core.domain.Task): os.meka.core.domain.ShopModeView = os.meka.core.domain.ShopModeRules.ofChecklist(task)

    /** Shop mode for the shared shopping list. Pure. */
    fun shopMode(view: os.meka.core.domain.ShoppingView): os.meka.core.domain.ShopModeView = os.meka.core.domain.ShopModeRules.ofShopping(view)

    // ---- Checklist templates (Checklists in tasks, slice 3a; the checklist's ⋯ on both apps) ----

    /** Start from template's choices: Meka's own templates (latest first), then the starters he hasn't replaced. */
    suspend fun checklistTemplates(): os.meka.core.domain.ChecklistTemplatesView = onCore { templateStore.view() }

    /** The name Save as template suggests for [task] (its title). Pure. */
    fun templateName(task: os.meka.core.domain.Task): String = os.meka.core.domain.ChecklistTemplateRules.suggestedName(task)

    /** Whether saving [name] would update an existing template (the sheet says "Update"). */
    suspend fun templateExists(name: String): Boolean =
        onCore { os.meka.core.domain.ChecklistTemplateRules.existing(templateStore.saved(), name) != null }

    /** Save as template: the task's checklist (ticked or not, in order) under [name]; a template of that name is saved over. */
    suspend fun saveChecklistTemplate(taskId: String, name: String): os.meka.core.domain.TemplateSaved = onCore {
        val task = tasks.all().firstOrNull { it.id == taskId }
            ?: return@onCore os.meka.core.domain.TemplateSaved(null, false, os.meka.core.domain.ChecklistTemplateRules.NOTHING_TO_SAVE)
        templateStore.save(name, os.meka.core.domain.ChecklistTemplateRules.itemsOf(task))
    }

    /**
     * Start from template: the template's items not already on the task's checklist go at its end, unticked. Undo
     * removes [os.meka.core.domain.TemplateApplied.stepIds] with [removeStep].
     */
    suspend fun startFromTemplate(taskId: String, templateId: String): os.meka.core.domain.TemplateApplied = onCore {
        val t = templateStore.find(templateId)
        val task = tasks.all().firstOrNull { it.id == taskId }
        if (t == null || task == null) return@onCore os.meka.core.domain.TemplateApplied(emptyList(), "That template has gone")
        val texts = os.meka.core.domain.ChecklistTemplateRules.toAdd(t, task)
        val ids = tasks.addChecklistTexts(taskId, texts)
        os.meka.core.domain.TemplateApplied(ids, os.meka.core.domain.ChecklistTemplateRules.appliedLine(t.name, ids.size))
    }

    /** Removes a saved template; returns it for [restoreChecklistTemplate] (Undo). */
    suspend fun removeChecklistTemplate(id: String): os.meka.core.domain.ChecklistTemplate? = onCore { templateStore.remove(id) }

    /** Undo for [removeChecklistTemplate]. */
    suspend fun restoreChecklistTemplate(template: os.meka.core.domain.ChecklistTemplate): os.meka.core.domain.TemplateSaved =
        onCore { templateStore.restore(template) }

    /** The local day today as an epoch day, for [os.meka.core.domain.Task.repeatMeta]. */
    fun todayEpochDay(): Long = ZoneCalendar(timeZone).epochDayOf(nowMs())

    /** Event detail (calendar redesign, slice 3): when, how soon, which calendar, place, notes and a Join link. Pure. */
    fun eventDetail(event: os.meka.core.domain.CalendarEvent): os.meka.core.domain.EventDetailView =
        os.meka.core.domain.EventDetails.build(event, nowMs(), ZoneCalendar(timeZone), _eventMarks.value).copy(
            editable = canEditEvent(event),
            edit = os.meka.core.domain.EditEventRules.note(event.id, _editsSeen.value, nowMs(), ZoneCalendar(timeZone)),
        )

    /** Whether MEKA may change [event] in its calendar (a Google/Outlook account where editing is allowed). */
    fun canEditEvent(event: os.meka.core.domain.CalendarEvent): Boolean =
        os.meka.core.domain.CalendarEditRules.editable(event) { p, a -> _editAccounts.value.any { it.provider == p && it.email == a } }

    // ---- Calendar actions (MEKA-only; the real calendars stay read-only) ----

    /** Adds the event's prep task ("Prepare for …", planned 30 min before, due at its start); returns the task id. */
    suspend fun addPrepTask(event: os.meka.core.domain.CalendarEvent): String = onCore { eventActions.addPrep(event) }
    /**
     * Weekend football: Kit reminder on a club fixture ([os.meka.core.domain.FootballRules]) makes its kit task
     * ("Pack the kit for …", planned and reminded at 19:00 the evening before, the kit list as its steps). Returns the
     * task and the undo bar's line, or null when the event isn't a club fixture still to come or already has one open.
     */
    suspend fun addKitReminder(event: os.meka.core.domain.CalendarEvent): os.meka.core.domain.KitAdded? = onCore {
        val cal = ZoneCalendar(timeZone)
        val open = tasks.get(os.meka.core.domain.FootballRules.kitTaskId(event.id))?.takeIf { !it.lifecycle.isTerminal }
        if (open != null) return@onCore null
        val plan = os.meka.core.domain.FootballRules.plan(event, nowMs(), cal)
        eventActions.addKit(event)?.let { os.meka.core.domain.KitAdded(it, os.meka.core.domain.FootballRules.addedLine(plan, nowMs(), cal)) }
    }
    /**
     * Weekend football, slice 4: "How did it go?" on a club fixture keeps the score ([scoreFor] Meka's kid's team,
     * [scoreAgainst]; -1 for no score), the [scorers] and a [note] on the fixture, synced. Empty fields clear it.
     * Returns the undo bar's line and what was there before ([undoMatchResult]), or null when the fixture doesn't offer it.
     */
    suspend fun saveMatchResult(
        event: os.meka.core.domain.CalendarEvent, scoreFor: Int, scoreAgainst: Int, scorers: String, note: String,
    ): os.meka.core.domain.MatchSaved? = onCore {
        val f = os.meka.core.domain.FootballRules
        if (!f.canRecord(event, eventActions.marks().results[event.id], nowMs(), ZoneCalendar(timeZone))) return@onCore null
        val r = f.result(event, scoreFor, scoreAgainst, scorers, note, nowMs())
        val before = eventActions.setResult(event.id, r)
        os.meka.core.domain.MatchSaved(event.id, f.savedLine(r), before)
    }

    /** Undo for [saveMatchResult]: what was kept before comes back (nothing, if nothing was). */
    suspend fun undoMatchResult(saved: os.meka.core.domain.MatchSaved) = onCore {
        eventActions.setResult(saved.eventId, saved.previous)
        Unit
    }
    /** Hides an event from my day (timeline, planner, brief, shutdown, review); the Calendar tab still lists it. */
    suspend fun hideEvent(eventId: String) = onCore { eventActions.hide(eventId) }
    /**
     * Shows a hidden event in my day again (also the Undo for [hideEvent]); for a row standing for the same event on
     * several calendars ([os.meka.core.domain.DuplicateEvents]) every one of them, so the row comes back.
     */
    suspend fun showEvent(eventId: String) = onCore {
        os.meka.core.domain.DuplicateEvents.idsWith(eventId, currentEvents()).forEach { eventActions.show(it) }
    }
    /** "Make it a task" on an all-day entry that reads like a to-do: a task with its title, and the entry leaves Today. */
    suspend fun makeAllDayTask(event: os.meka.core.domain.CalendarEvent): String = onCore { eventActions.makeTask(event) }
    /** Undo for [makeAllDayTask]: the task goes and the entry is back in Today. */
    suspend fun undoAllDayTask(eventId: String) = onCore { eventActions.unmakeTask(eventId) }
    /**
     * "Hide <calendar> from Today": every event of the calendar with [calendarKey] ([os.meka.core.domain.CalendarRules.key])
     * leaves my day; the Calendar tab and Search keep them. Synced; [showCalendarOnToday] undoes it.
     */
    suspend fun hideCalendarFromToday(calendarKey: String, label: String) = onCore { eventActions.hideCalendar(calendarKey, label) }
    /** Shows a hidden calendar on Today again (the undo bar, and the Calendars switch). */
    suspend fun showCalendarOnToday(calendarKey: String) = onCore { eventActions.showCalendar(calendarKey) }
    /**
     * Renames a calendar in MEKA only (Calendars; the real calendar keeps its name). Blank goes back to the default
     * name. Synced, last rename wins. Returns the name now shown, or null for the default.
     */
    suspend fun renameCalendar(calendarKey: String, name: String): String? = onCore { eventActions.renameCalendar(calendarKey, name) }
    /** Remind me [minutes] before the event (a governor heads-up, CLOCK precision); 0 turns it off. */
    /**
     * The Fold's listener held these at work: they join the synced after-work summary (new ones only; a re-post or a
     * cleared one is skipped). [lists] marks family. Returns how many were new.
     */
    suspend fun holdCaptured(items: List<os.meka.core.domain.CapturedItem>, lists: os.meka.core.domain.PeopleLists): Int =
        onCore { held.hold(items, lists) }
    /** Done on the after-work summary: cleared on every device, texts blanked. WhatsApp and Messages are untouched. */
    suspend fun clearAfterWork(): Int = onCore { held.clear() }

    /**
     * Requests from people Meka watches (V1, slice 2): reads one message the Fold's listener captured. Only a message
     * [os.meka.core.domain.MessageRequestRules.shouldRead] allows (the family list or [watching], a 1:1 chat unless its
     * group is in [groups]) goes anywhere: a voice note becomes "Listen to Wife's voice note" with no AI, a bare photo
     * nothing, and anything else is sent alone (its text, the sender's label, the time; never a number or another chat)
     * to `POST /v1/ai/message-request`. The answer's proposals are checked again here and saved as synced Needs you
     * cards ([requests]); nothing is added, replied to or marked read. Returns the new cards (for the heads-up).
     */
    suspend fun readRequest(
        item: os.meka.core.domain.CapturedItem,
        lists: os.meka.core.domain.PeopleLists,
        watching: Set<String>,
        groups: Set<String> = emptySet(),
    ): RequestRead {
        val rules = os.meka.core.domain.MessageRequestRules
        if (!rules.shouldRead(item, lists, watching, groups)) return RequestRead.Skipped
        val text = item.text.orEmpty().trim()
        val message = os.meka.core.domain.RequestMessage(item.id, item.personName.trim(), text, item.atMs)
        if (rules.isVoiceNote(text)) {
            return RequestRead.Read(onCore { requestCards.save(message, listOf(rules.voiceNoteProposal(item.personName))) })
        }
        if (!rules.worthAsking(text)) return RequestRead.Skipped
        val api = aiApi ?: return RequestRead.Unavailable(os.meka.core.domain.AskRules.NOT_CONNECTED_LINE)
        val cal = ZoneCalendar(timeZone)
        val request = onCore {
            val m = messageContext(item)
            os.meka.core.wire.MessageRequestCodec.Request(
                sender = m.sender, sentAt = m.sentAt, date = m.date, now = m.now, work = m.work,
                text = text.take(os.meka.core.wire.MessageRequestCodec.MAX_TEXT),
            )
        }
        val reply = try { api.messageRequest(request) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return RequestRead.Unavailable(os.meka.core.domain.AskRules.OFFLINE_LINE)
        }
        if (reply.state != os.meka.core.wire.AskCodec.Response.ANSWERED) {
            return RequestRead.Unavailable(os.meka.core.domain.AskRules.unavailableLine(reply.state, reply.reason))
        }
        return RequestRead.Read(onCore {
            val raw = reply.proposals.map { os.meka.core.domain.RawRequestProposal(it.kind, it.title, it.date, it.time, it.words) }
            // The day the message came, not the day it was read: "tomorrow" in last night's message is today.
            requestCards.save(message, rules.check(raw, cal.epochDayOf(item.atMs), cal.minuteOfDay(item.atMs)))
        })
    }

    /**
     * "Handle this" (V1, slice 1): Meka shared [text] (and its [subject]) with MEKA and asked it to handle it. A share
     * with nothing but a link isn't sent (MEKA never opens links); otherwise the text alone (cut to size), its subject,
     * today's date and his work days go to `POST /v1/ai/handle-this`; never who sent it, an address or anything else.
     * The proposals (task · event · reminder · renewal · a drafted reply) are checked again here and saved as synced request cards that say "You
     * shared · 21:40": shown in the sheet that asked, waiting in Needs you on both apps, never a notification. Nothing
     * is added until Meka taps Add ([acceptRequest]). The same text shared again today makes no second card.
     */
    suspend fun handleThis(text: String, subject: String? = null): HandleOutcome {
        val rules = os.meka.core.domain.HandleThisRules
        if (!rules.worthAsking(text, subject)) return HandleOutcome.Unavailable(rules.NOTHING_LINE)
        val api = aiApi ?: return HandleOutcome.Unavailable(os.meka.core.domain.AskRules.NOT_CONNECTED_LINE)
        val atMs = nowMs()
        val cal = ZoneCalendar(timeZone)
        val today = cal.epochDayOf(atMs)
        val messageId = rules.messageId(text, subject, today)
        // Shared already today: nothing goes out again.
        if (onCore { requestCards.known(messageId) }) {
            val open = onCore { requestCards.open() }.filter { it.messageId == messageId }
            return if (open.isEmpty()) HandleOutcome.NothingToDo(rules.ALREADY_LINE) else HandleOutcome.Cards(open, rules.readyLine(open.size))
        }
        val request = onCore {
            val max = os.meka.core.wire.HandleThisCodec.MAX_WORK
            os.meka.core.wire.HandleThisCodec.Request(
                date = os.meka.core.domain.AskRules.isoDate(today),
                now = "${os.meka.core.domain.CivilDate.longLabel(today)} ${os.meka.core.domain.CivilDate.fromEpochDay(today).year} · ${LocalClock.formatMinute(cal.minuteOfDay(atMs))}",
                work = work.schedule().plainLine.let { if (it.length <= max) it else it.take(max).substringBeforeLast(", ") },
                subject = rules.outgoingSubject(subject), text = rules.outgoingText(text),
            )
        }
        val reply = try { api.handleThis(request) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return HandleOutcome.Unavailable(os.meka.core.domain.AskRules.OFFLINE_LINE)
        }
        if (reply.state != os.meka.core.wire.AskCodec.Response.ANSWERED) {
            return HandleOutcome.Unavailable(os.meka.core.domain.AskRules.unavailableLine(reply.state, reply.reason))
        }
        val cards = onCore {
            val raw = rules.allowed(reply.proposals.map { os.meka.core.domain.RawRequestProposal(it.kind, it.title, it.date, it.time, it.words, it.type, it.repeat, it.cost, it.body) })
            val message = os.meka.core.domain.RequestMessage(messageId, rules.PERSON, rules.quoteText(text, subject), atMs)
            requestCards.save(message, os.meka.core.domain.MessageRequestRules.check(raw, today, cal.minuteOfDay(atMs), allowRenewal = true, allowReply = true))
        }
        return if (cards.isEmpty()) HandleOutcome.NothingToDo(rules.NOTHING_LINE) else HandleOutcome.Cards(cards, rules.readyLine(cards.size))
    }

    private class MessageContext(val sender: String, val sentAt: String, val date: String, val now: String, val work: String)

    /** What an AI message call says besides the text: the sender's label, its time, today and Meka's work days. */
    private fun messageContext(item: os.meka.core.domain.CapturedItem): MessageContext {
        val cal = ZoneCalendar(timeZone)
        val now = nowMs()
        val today = cal.epochDayOf(now)
        val schedule = work.schedule()
        return MessageContext(
            sender = item.personName.trim().take(os.meka.core.wire.MessageRequestCodec.MAX_SENDER),
            sentAt = LocalClock.formatMinute(cal.minuteOfDay(item.atMs)),
            date = os.meka.core.domain.AskRules.isoDate(today),
            now = "${os.meka.core.domain.CivilDate.longLabel(today)} ${os.meka.core.domain.CivilDate.fromEpochDay(today).year} · ${LocalClock.formatMinute(cal.minuteOfDay(now))}",
            work = schedule.plainLine.let { if (it.length <= os.meka.core.wire.MessageRequestCodec.MAX_WORK) it else it.take(os.meka.core.wire.MessageRequestCodec.MAX_WORK).substringBeforeLast(", ") },
        )
    }

    /**
     * The messages assistant (V1, slice 2): triages one message the Fold's listener captured. Routed first with no AI
     * ([os.meka.core.domain.MessageTriageRules.route]): a Digest group's chatter comes back as [TriageRead.Digest] for the
     * phone to keep; a voice note becomes "Listen to Tunde's voice note" (a request card); a person or group in
     * [settings]' never-to-AI list is an FYI card that says so; a 1:1 message, or a group message naming Meka, goes alone
     * (its text, the sender's label, the group's name, its time; never a number or another chat) to
     * `POST /v1/ai/message-triage`. The answer is checked again here: Needs a reply and FYI become synced cards
     * ([triage]; lane, gist, draft — never the text), an Action's proposals become request cards ([requests]) quoting the
     * gist. Nothing is ever sent, replied to or marked read. A message already triaged is [TriageRead.Skipped].
     */
    suspend fun triageMessage(
        item: os.meka.core.domain.CapturedItem,
        settings: os.meka.core.domain.TriageSettings = os.meka.core.domain.TriageSettings(),
    ): TriageRead {
        val rules = os.meka.core.domain.MessageTriageRules
        val route = rules.route(item, settings)
        if (route == os.meka.core.domain.TriageRoute.Skip) return TriageRead.Skipped
        if (route is os.meka.core.domain.TriageRoute.Digest) return TriageRead.Digest(route.groupKey)
        if (onCore { triageCards.known(item.id) }) return TriageRead.Skipped
        val text = item.text.orEmpty().trim()
        val requestRules = os.meka.core.domain.MessageRequestRules
        val fyi = os.meka.core.domain.MessageTriage(os.meka.core.domain.TriageLane.FYI)
        when (route) {
            os.meka.core.domain.TriageRoute.VoiceNote -> return onCore {
                val message = os.meka.core.domain.RequestMessage(item.id, item.personName.trim(), text, item.atMs)
                val made = requestCards.save(message, listOf(requestRules.voiceNoteProposal(item.personName)))
                // Recorded (with no gist) so a re-post isn't triaged again; FYI cards for voice notes stay out of the way.
                triageCards.save(item, os.meka.core.domain.MessageTriage(os.meka.core.domain.TriageLane.ACTION))
                TriageRead.Read(null, made)
            }
            os.meka.core.domain.TriageRoute.LocalFyi -> return onCore {
                if (!settings.isPrivate(item.personName) && !settings.isPrivate(item.conversation)) {
                    // A bare photo or "ok": nothing worth a card, but remembered so it isn't looked at again.
                    triageCards.save(item, os.meka.core.domain.MessageTriage(os.meka.core.domain.TriageLane.ACTION))
                    TriageRead.Read(null)
                } else TriageRead.Read(triageCards.save(item, fyi, local = true))
            }
            else -> Unit
        }
        val api = aiApi ?: return TriageRead.Unavailable(os.meka.core.domain.AskRules.NOT_CONNECTED_LINE)
        val codec = os.meka.core.wire.MessageTriageCodec
        val request = onCore {
            val m = messageContext(item)
            os.meka.core.wire.MessageTriageCodec.Request(
                sender = m.sender,
                group = if ((route as? os.meka.core.domain.TriageRoute.AskAi)?.mentioned == true) {
                    item.conversation.orEmpty().trim().take(codec.MAX_GROUP)
                } else "",
                sentAt = m.sentAt, date = m.date, now = m.now, work = m.work,
                text = text.take(os.meka.core.wire.MessageRequestCodec.MAX_TEXT),
            )
        }
        val reply = try { api.messageTriage(request) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return TriageRead.Unavailable(os.meka.core.domain.AskRules.OFFLINE_LINE)
        }
        if (reply.state != os.meka.core.wire.AskCodec.Response.ANSWERED) {
            return TriageRead.Unavailable(os.meka.core.domain.AskRules.unavailableLine(reply.state, reply.reason))
        }
        return onCore {
            val cal = ZoneCalendar(timeZone)
            val raw = os.meka.core.domain.RawTriage(
                lane = reply.lane, draft = reply.draft, summary = reply.summary,
                proposals = reply.proposals.map { os.meka.core.domain.RawRequestProposal(it.kind, it.title, it.date, it.time, it.words) },
            )
            // The day the message came, not the day it was read.
            val checked = rules.check(raw, cal.epochDayOf(item.atMs), cal.minuteOfDay(item.atMs))
            val requests = if (checked.lane == os.meka.core.domain.TriageLane.ACTION) {
                // The card quotes the gist, not the message: the text stays on the phone.
                val quote = checked.summary ?: os.meka.core.domain.TriageCard.NO_GIST_LINE
                requestCards.save(os.meka.core.domain.RequestMessage(item.id, item.personName.trim(), quote, item.atMs), checked.proposals)
            } else emptyList()
            TriageRead.Read(triageCards.save(item, checked), requests)
        }
    }

    /**
     * The group digest's gist (V1, messages slice 4b): once a digest time (12:30, 18:30) has come, the Fold calls this
     * with the chatter it keeps ([items], sealed on the phone), Meka's group modes and when he last caught up with each
     * group ([seen]). Every group in the due digest gets a synced card for the slot (count, who wrote; never the
     * messages); the busy ones ([os.meka.core.domain.GroupGistRules.MIN_MESSAGES] or more) go together in **one** call
     * to `POST /v1/ai/group-digest` — each group's name and its latest lines (sender's label, time, text), nothing else —
     * and come back with a gist each, checked again here. Anything in the chatter that asks Meka for something becomes a
     * Needs you card: Needs a reply (no draft: a group reply is his to write, so the card opens the chat) or request
     * cards quoting the ask's gist. Nothing is sent, replied to or marked read. Called again in the same slot it does
     * nothing ([GistRead.NotDue]); offline or a failed call writes nothing, so the next call tries again; with MEKA's AI
     * off or the month's budget spent the cards are written without gists.
     */
    suspend fun gistGroupDigest(
        items: List<os.meka.core.domain.CapturedItem>,
        settings: os.meka.core.domain.TriageSettings,
        seen: Map<String, Long>,
    ): GistRead {
        val rules = os.meka.core.domain.GroupGistRules
        val cal = ZoneCalendar(timeZone)
        val plan = onCore<GistPlan?> {
            val now = nowMs()
            val today = cal.epochDayOf(now)
            val minute = cal.minuteOfDay(now)
            val slotMs = os.meka.core.domain.GroupDigestRules.slotStartMs(cal.toEpochMs(today, 0), minute) ?: return@onCore null
            val slotMinute = if (minute >= os.meka.core.domain.GroupDigestRules.EVENING_MINUTE) {
                os.meka.core.domain.GroupDigestRules.EVENING_MINUTE
            } else os.meka.core.domain.GroupDigestRules.LUNCH_MINUTE
            val synced = gistStore.caughtUpTimes()
            val merged = (seen.keys + synced.keys).associateWith { k -> maxOf(seen[k] ?: 0L, synced[k] ?: 0L) }
            val gisted = gistStore.gisted(slotMs)
            val cards = os.meka.core.domain.GroupDigestRules.cards(items, settings, merged)
                // Only groups with news since this slot began weren't caught up after it: the digest is due for them.
                .filter { (merged[it.groupKey] ?: 0L) < slotMs && it.groupKey !in gisted }
            if (cards.isEmpty()) return@onCore null
            val groups = rules.groups(cards, items, merged, gisted, { LocalClock.formatMinute(cal.minuteOfDay(it)) }, settings)
            GistPlan(slotMs, slotMinute, today, minute, cards, groups, items)
        } ?: return GistRead.NotDue
        if (plan.groups.isEmpty()) return GistRead.Read(onCore { plan.saveAll(emptyMap()) }, emptyList())
        val api = aiApi ?: return GistRead.Unavailable(os.meka.core.domain.AskRules.NOT_CONNECTED_LINE)
        val codec = os.meka.core.wire.GroupDigestCodec
        val request = onCore {
            val now = nowMs()
            os.meka.core.wire.GroupDigestCodec.Request(
                date = os.meka.core.domain.AskRules.isoDate(plan.today),
                now = "${os.meka.core.domain.CivilDate.longLabel(plan.today)} ${os.meka.core.domain.CivilDate.fromEpochDay(plan.today).year} · ${LocalClock.formatMinute(cal.minuteOfDay(now))}",
                groups = plan.groups.map { g ->
                    os.meka.core.wire.GroupDigestCodec.Group(
                        g.name.take(codec.MAX_NAME),
                        g.lines.map { os.meka.core.wire.GroupDigestCodec.Line(it.from.take(os.meka.core.wire.MessageRequestCodec.MAX_SENDER), it.at, it.text.take(codec.MAX_LINE)) },
                    )
                },
            )
        }
        val reply = try { api.groupDigest(request) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return GistRead.Unavailable(os.meka.core.domain.AskRules.OFFLINE_LINE)
        }
        when (reply.state) {
            os.meka.core.wire.AskCodec.Response.ANSWERED -> Unit
            // AI off or the month's budget spent: the cards still go to the Mac, without gists.
            os.meka.core.wire.AskCodec.Response.OFF, os.meka.core.wire.AskCodec.Response.OVER ->
                return GistRead.Read(onCore { plan.saveAll(emptyMap()) }, emptyList())
            else -> return GistRead.Unavailable(os.meka.core.domain.AskRules.unavailableLine(reply.state, reply.reason))
        }
        return onCore {
            val raw = reply.groups.map { g ->
                os.meka.core.domain.GroupGistRules.RawGroup(
                    g.name, g.gist,
                    g.asks.map { a ->
                        os.meka.core.domain.GroupGistRules.RawAsk(a.lane, a.from, a.summary, a.proposals.map { os.meka.core.domain.RawRequestProposal(it.kind, it.title, it.date, it.time, it.words) })
                    },
                )
            }
            val checked = rules.check(plan.groups, raw, plan.today, plan.minute)
            val saved = plan.saveAll(checked.gists.associate { it.groupKey to it.gist })
            val made = mutableListOf<os.meka.core.domain.TriageCard>()
            val requests = mutableListOf<os.meka.core.domain.RequestCard>()
            checked.asks.forEachIndexed { i, ask ->
                // An ask has no message of its own: its card is keyed by the slot, the group and its place in the answer.
                val id = "digest:${ask.groupKey}:${plan.slotMs}:$i"
                val app = plan.items.lastOrNull { it.conversation != null && os.meka.core.domain.GroupDigestRules.groupKey(it) == ask.groupKey }?.app
                    ?: os.meka.core.domain.CaptureApp.WHATSAPP
                val atMs = plan.items.lastOrNull {
                    it.conversation != null && os.meka.core.domain.GroupDigestRules.groupKey(it) == ask.groupKey &&
                        os.meka.core.domain.People.key(it.personName) == os.meka.core.domain.People.key(ask.from)
                }?.atMs ?: plan.slotMs
                val item = os.meka.core.domain.CapturedItem(id, app, os.meka.core.domain.CaptureKind.MESSAGE, ask.from, null, ask.group, atMs)
                when (ask.lane) {
                    os.meka.core.domain.TriageLane.NEEDS_REPLY ->
                        triageCards.save(item, os.meka.core.domain.MessageTriage(os.meka.core.domain.TriageLane.NEEDS_REPLY, summary = ask.summary))?.let { made += it }
                    else -> requests += requestCards.save(os.meka.core.domain.RequestMessage(id, ask.from, ask.summary, atMs), ask.proposals)
                }
            }
            GistRead.Read(saved, made, requests)
        }
    }

    private inner class GistPlan(
        val slotMs: Long,
        val slotMinute: Int,
        val today: Long,
        val minute: Int,
        val cards: List<os.meka.core.domain.GroupDigestCard>,
        val groups: List<os.meka.core.domain.GroupGistRules.Group>,
        val items: List<os.meka.core.domain.CapturedItem>,
    ) {
        /** A card for every group in the due digest, with its gist when it has one; returns the groups written. */
        fun saveAll(gists: Map<String, String?>): List<String> =
            cards.filter { gistStore.save(it, slotMs, slotMinute, gists[it.groupKey]) }.map { it.groupKey }
    }

    /**
     * Caught up with these digest groups (V1, messages slice 4b): their synced cards leave Needs you on both devices. Returns
     * what to hand [undoCatchUpGroupDigest] for the undo bar.
     */
    suspend fun catchUpGroupDigest(groupKeys: List<String>): GroupDigestUndo = onCore { GroupDigestUndo(gistStore.caughtUp(groupKeys)) }

    /** Takes back a Caught up: the cards come back on both devices. */
    suspend fun undoCatchUpGroupDigest(undo: GroupDigestUndo) = onCore { gistStore.undo(undo.before) }

    /** Not now on a triage card: gone from Needs you on every device, its gist and draft blanked. */
    suspend fun dismissTriage(messageId: String): Boolean =
        onCore { triageCards.resolve(messageId, os.meka.core.domain.TriageResolution.DISMISSED) }

    /**
     * After Meka's tap of Send (or Send all) went out through the message's own notification Reply action on the Fold
     * (V1, messages slice 3): the card leaves Needs you on every device, its gist and draft blanked. The core never sends
     * anything itself; this only records that he did.
     */
    suspend fun sentTriage(messageId: String): Boolean =
        onCore { triageCards.resolve(messageId, os.meka.core.domain.TriageResolution.SENT) }

    /** Seen on an FYI card, or the chat opened from a Needs a reply card: gone from Needs you on every device. */
    suspend fun seenTriage(messageId: String): Boolean =
        onCore { triageCards.resolve(messageId, os.meka.core.domain.TriageResolution.SEEN) }

    /**
     * Approve on an approval card (approvals inbox, slice 1). [confirmedWithBiometric] is true only when the app's
     * system fingerprint prompt (BiometricPrompt, strong biometrics only; Touch ID on the Mac) has just succeeded for
     * this card: without it the answer is refused ([os.meka.core.domain.ApprovalRefusal.NEEDS_BIOMETRIC]) and nothing is
     * written. A yes is recorded with a fresh idempotency key for whoever asked to act on once, and in Activity.
     */
    suspend fun approve(approvalId: String, confirmedWithBiometric: Boolean): ApprovalAnswer = onCore {
        answerApproval(
            approvalId, approve = true,
            if (confirmedWithBiometric) os.meka.core.domain.ApprovalConfirmation.BIOMETRIC else os.meka.core.domain.ApprovalConfirmation.TAP,
        )
    }

    /** Decline on an approval card: no fingerprint needed (saying no is always safe); in Activity. */
    suspend fun declineApproval(approvalId: String): ApprovalAnswer =
        onCore { answerApproval(approvalId, approve = false, os.meka.core.domain.ApprovalConfirmation.TAP) }

    private fun answerApproval(id: String, approve: Boolean, confirmation: os.meka.core.domain.ApprovalConfirmation): ApprovalAnswer {
        val before = approvalStore.find(id)
        return when (val d = approvalStore.decide(id, approve, confirmation, ids.next(), replica.deviceId)) {
            is os.meka.core.domain.ApprovalDecision.Refused -> ApprovalAnswer(false, d.reason.line)
            is os.meka.core.domain.ApprovalDecision.Write -> {
                before?.let { a ->
                    val (summary, detail, why) = os.meka.core.domain.ApprovalRules.activityLines(a, d.approved, confirmation)
                    activity.recordApproval(os.meka.core.domain.ApprovalRules.activityId(a.id), summary, detail, why)
                }
                ApprovalAnswer(true, d.line)
            }
        }
    }

    /** Not a task (Not needed, Not an event) on a request card: gone from Needs you on every device, its text blanked. */
    suspend fun declineRequest(cardId: String): Boolean =
        onCore { requestCards.resolve(cardId, os.meka.core.domain.RequestResolution.DECLINED) }

    /**
     * Add on a request card (V1, requests slice 4): a task on its day (planned at its time; a reminder rings at its time
     * while that is still ahead), an event in the calendar MEKA may edit (a planned task when none allows editing), a
     * work-from-home day, (a share's renewal card, "Handle this" slice 2) a renewal on the radar, or (a share's reply
     * card, slice 3) the draft handed back as [RequestDone.copyText] for the app's clipboard, never sent. The card leaves
     * Needs you on every device. Null when the card is already answered.
     */
    suspend fun acceptRequest(cardId: String): RequestDone? = onCore { doRequest(cardId, change = false) }

    /**
     * Change on a request card: the proposal as a task (an event too, so it can be edited before anything is written to
     * a calendar), then the app opens its detail ([RequestDone.taskId]). Null for a work-from-home card or one already
     * answered.
     */
    suspend fun changeRequest(cardId: String): RequestDone? = onCore { doRequest(cardId, change = true) }

    /**
     * Undo on the bar after Add or Change: deletes the task, cancels the calendar edit (inside its five seconds),
     * takes the work-from-home day back or takes the renewal off the radar. The card stays answered.
     */
    suspend fun undoRequest(done: RequestDone): Boolean = onCore {
        var undone = false
        done.taskId?.let { id -> tasks.get(id)?.let { tasks.delete(id); followBlocks(listOf(id)); undone = true } }
        done.editId?.let { if (calendarEdits.undo(it)) undone = true }
        if (done.homeDay >= 0 && work.setHomeDay(done.homeDay, false, todayEpochDay())) undone = true
        done.renewalId?.let { id -> if (renewals.items().any { it.id == id }) { renewals.delete(id); undone = true } }
        undone
    }

    private fun doRequest(cardId: String, change: Boolean): RequestDone? {
        val rules = os.meka.core.domain.RequestAcceptRules
        val card = requestCards.find(cardId) ?: return null
        val cal = ZoneCalendar(timeZone)
        val today = todayEpochDay()
        val accounts = _editAccounts.value
        val account = accounts.firstOrNull { it.key == os.meka.core.domain.AddEventRules.lastUsedKey(calendarEdits.all()) } ?: accounts.firstOrNull()
        val plan = rules.plan(card.proposal, canAddEvent = account != null, change = change) ?: return null
        val done = when (plan) {
            is os.meka.core.domain.RequestPlan.AddTask -> {
                val id = tasks.create(NewTask(plan.title))
                rules.taskDay(plan, today)?.let { day -> tasks.setWhen(id, day, plan.minute) }
                rules.remindAtMs(plan, today, nowMs(), cal)?.let { tasks.setReminder(id, it) }
                followBlocks(listOf(id))
                RequestDone(rules.doneLine(plan, card.proposal.kind, today), id, null)
            }
            is os.meka.core.domain.RequestPlan.AddEvent -> {
                val acct = account ?: return null
                val form = os.meka.core.domain.AddEventRules.start(today, cal.minuteOfDay(nowMs()), plan.day, accounts, acct.key)
                    .copy(title = plan.title, minute = plan.minute, lengthMin = plan.lengthMin)
                when (val r = calendarEdits.add(acct.provider, acct.email, os.meka.core.domain.AddEventRules.draft(form, cal))) {
                    is os.meka.core.domain.EventEditResult.Made -> RequestDone(rules.doneLine(plan, card.proposal.kind, today, acct.provider), null, r.id)
                    // Refused (a time already gone, say): keep it as a planned task rather than lose the request.
                    is os.meka.core.domain.EventEditResult.Refused -> {
                        val asTask = os.meka.core.domain.RequestPlan.AddTask(plan.title, plan.day, plan.minute, null)
                        val id = tasks.create(NewTask(plan.title))
                        rules.taskDay(asTask, today)?.let { day -> tasks.setWhen(id, day, plan.minute) }
                        followBlocks(listOf(id))
                        RequestDone(rules.doneLine(asTask, card.proposal.kind, today), id, null)
                    }
                }
            }
            is os.meka.core.domain.RequestPlan.HomeDay -> {
                work.setHomeDay(plan.day, true, today)
                val workDay = os.meka.core.domain.WorkModeRules.isWorkDay(work.schedule(), bankHolidays.calendar(), plan.day) && plan.day !in leave.days()
                RequestDone(rules.doneLine(plan, card.proposal.kind, today, isWorkDay = workDay), null, null, plan.day)
            }
            // "Handle this", slice 3: the app puts the draft on the clipboard; MEKA sends nothing and adds nothing.
            is os.meka.core.domain.RequestPlan.CopyReply -> RequestDone(rules.doneLine(plan, card.proposal.kind, today), null, null, copyText = plan.text)
            is os.meka.core.domain.RequestPlan.AddRenewal -> {
                val d = plan.details
                val id = renewals.add(
                    plan.title, d.kind, plan.day, d.repeat,
                    cost = d.costPence?.let { os.meka.core.domain.RenewalRules.formatPence(it) },
                )
                RequestDone(rules.doneLine(plan, card.proposal.kind, today), null, null, renewalId = id)
            }
        }
        requestCards.resolve(cardId, if (change) os.meka.core.domain.RequestResolution.CHANGED else os.meka.core.domain.RequestResolution.ADDED)
        return done
    }
    suspend fun setEventReminder(eventId: String, minutes: Int) = onCore { eventActions.setReminder(eventId, minutes) }
    /** Leave by: a heads-up [travelMinutes] before the event starts (how long it takes to get there); 0 turns it off. */
    suspend fun setEventLeaveBy(eventId: String, travelMinutes: Int) = onCore {
        eventActions.setLeaveBy(eventId, travelMinutes)
        rememberVenueOf(eventId)
    }
    /**
     * Ring as an alarm (Alarms, slice 3): the event's leave-by rings like the wake alarm (full screen on the Fold, a
     * notification with Snooze / Dismiss on the Mac) instead of a heads-up. Synced; last tap wins.
     */
    suspend fun setEventLeaveAlarm(eventId: String, on: Boolean) = onCore {
        eventActions.setLeaveAlarm(eventId, on)
        rememberVenueOf(eventId)
    }

    /** Weekend football, slice 2: a club fixture's ground keeps the travel time just set (and whether it rings). */
    private fun rememberVenueOf(eventId: String) {
        currentEvents().firstOrNull { it.id == eventId }?.let { eventActions.rememberVenue(it) }
    }

    /**
     * Weekend football, slice 2: "Leave by 09:15 · as last time" on a club fixture's detail sets the travel time Meka
     * set at that ground before (slice 2b/2c: or "Leave by 08:55 · 25 min drive", which then follows the traffic),
     * and Ring as an alarm when it rang then. Returns the offer taken (its line is the undo bar's: "Leave by 09:15 ·
     * 25 min away · alarm"), or null when there was none.
     */
    suspend fun useLastLeaveBy(event: os.meka.core.domain.CalendarEvent): os.meka.core.domain.LeaveOffer? = onCore {
        val offer = os.meka.core.domain.FootballRules.leaveOffer(event, eventActions.marks(), nowMs(), ZoneCalendar(timeZone))
            ?: return@onCore null
        // Slice 2c: taken from the drive, it follows later traffic answers ([os.meka.core.domain.TravelRules.follow]).
        eventActions.setLeaveBy(event.id, offer.travelMin, offer.driveKey)
        if (offer.rings) eventActions.setLeaveAlarm(event.id, true)
        offer
    }

    /** Undo for [useLastLeaveBy]: no travel time and no alarm on that fixture; the ground still remembers its own. */
    suspend fun undoLastLeaveBy(eventId: String) = onCore {
        eventActions.setLeaveBy(eventId, null)
        eventActions.setLeaveAlarm(eventId, false)
    }

    // ---- Lists: Waiting for · Someday · Decisions ----

    /** Adds something you're waiting for; [chaseInDays] from today (null: no chase date). */
    suspend fun addWaiting(title: String, who: String?, chaseInDays: Int?): String = onCore { lists.addWaiting(title, who, chaseInDays) }
    /** "Chased": records it and sets the next chase [againInDays] from today. */
    suspend fun chased(id: String, againInDays: Int?) = onCore { lists.chased(id, againInDays) }
    suspend fun setChase(id: String, days: Int?) = onCore { lists.setChase(id, days) }
    /** "Got it": it arrived; the item leaves the list. */
    suspend fun received(id: String) = onCore { lists.received(id) }
    suspend fun editWaiting(id: String, title: String?, who: String?, notes: String?) = onCore { lists.editWaiting(id, title, who, notes) }
    suspend fun deleteWaiting(id: String) = onCore { lists.deleteWaiting(id) }

    suspend fun addSomeday(title: String, kind: SomedayKind): String = onCore { lists.addSomeday(title, kind) }
    /** Moves an open, non-repeating task to Someday (out of Today and the planner). */
    suspend fun moveToSomeday(taskId: String, kind: SomedayKind) = onCore { lists.moveToSomeday(taskId, kind); followBlocks(listOf(taskId)); Unit }
    suspend fun setSomedayKind(taskId: String, kind: SomedayKind) = onCore { lists.setSomedayKind(taskId, kind) }
    /** "Do it now": back into Today. */
    suspend fun promoteSomeday(taskId: String) = onCore { lists.promote(taskId) }

    suspend fun recordDecision(statement: String, rationale: String?, reviewInDays: Int?): String =
        onCore { lists.recordDecision(statement, rationale, reviewInDays) }
    suspend fun setReview(id: String, days: Int?) = onCore { lists.setReview(id, days) }
    /** "Still right": the decision stands; reviewed again [againInDays] from today (null: no review). */
    suspend fun keepDecision(id: String, againInDays: Int?) = onCore { lists.keepDecision(id, againInDays) }
    suspend fun revisitDecision(id: String) = onCore { lists.revisit(id) }
    /** Replaces a decision with a new one; the old one is kept as superseded and leaves the list. */
    suspend fun replaceDecision(id: String, statement: String, rationale: String?, reviewInDays: Int?): String =
        onCore { lists.replaceDecision(id, statement, rationale, reviewInDays) }
    suspend fun editDecision(id: String, statement: String?, rationale: String?) = onCore { lists.editDecision(id, statement, rationale) }
    suspend fun deleteDecision(id: String) = onCore { lists.deleteDecision(id) }

    // ---- Renewals and bills radar ----

    /**
     * Adds a renewal or bill due on [dueDay] (a local epoch day, see [todayEpochDay]). [cost] is typed text ("9.99",
     * blank for not known); [cancelByDaysBefore] sets a cancel-by day that many days before it. Throws
     * [os.meka.core.domain.ValidationException] for a cost it can't read: check it first with
     * [os.meka.core.domain.RenewalRules.costError] (the Mac must, as Kotlin exceptions don't cross into Swift).
     */
    suspend fun addRenewal(title: String, kind: ObligationKind, dueDay: Long, repeats: RenewalRepeat, cost: String?, cancelByDaysBefore: Int?): String =
        onCore { renewals.add(title, kind, dueDay, repeats, cost, cancelByDaysBefore) }
    /** "Renewed" / "Paid": a repeating one moves to its next due day; a one-off leaves the list. */
    suspend fun renewalDone(id: String) = onCore { renewals.done(id) }
    suspend fun setRenewalDue(id: String, dueDay: Long) = onCore { renewals.setDue(id, dueDay) }
    suspend fun setRenewalRepeat(id: String, repeats: RenewalRepeat) = onCore { renewals.setRepeat(id, repeats) }
    suspend fun setRenewalCost(id: String, cost: String?) = onCore { renewals.setCost(id, cost) }
    /** How many days before the due day it starts showing. */
    suspend fun setRenewalLead(id: String, days: Int) = onCore { renewals.setLead(id, days) }
    /** The cancel-by day, [daysBefore] the due day (null: none). */
    suspend fun setRenewalCancelBy(id: String, daysBefore: Int?) = onCore { renewals.setCancelBy(id, daysBefore) }
    suspend fun setRenewalKind(id: String, kind: ObligationKind) = onCore { renewals.setKind(id, kind) }
    suspend fun editRenewal(id: String, title: String?, subject: String?, notes: String?) = onCore { renewals.edit(id, title, subject, notes) }
    /** "Cancelled it" / "Stop tracking": off the radar, kept as cancelled. */
    suspend fun stopRenewal(id: String) = onCore { renewals.stop(id) }
    suspend fun deleteRenewal(id: String) = onCore { renewals.delete(id) }

    /**
     * Home upkeep: adds one of [os.meka.core.domain.HomeUpkeepRules.PRESETS] to the radar with its interval, lead time
     * and first date. Returns the new item's id, or null when that job is already on the radar or isn't one (never
     * throws, so the Mac can call it). The view is [os.meka.core.domain.HomeUpkeepRules.view] over the radar's items.
     */
    suspend fun addHomeUpkeep(presetId: String): String? = onCore {
        try { renewals.addHomeUpkeep(presetId, todayEpochDay()) } catch (_: os.meka.core.domain.ValidationException) { null }
    }

    /** The suggested home jobs against the radar today (Lists → Renewals → Home upkeep). */
    fun homeUpkeep(): os.meka.core.domain.HomeUpkeepView =
        os.meka.core.domain.HomeUpkeepRules.view(_lists.value.renewals.all, todayEpochDay())

    /**
     * UK money deadlines (V1): adds one of [os.meka.core.domain.MoneyDateRules.PRESETS] to the radar — Self Assessment
     * and the ISA allowance on their next fixed day, the energy and broadband contracts and the pension check-in on a
     * placeholder for Meka to set. The new item's id, or null when it is already there or isn't one (never throws, so
     * the Mac can call it). The view is [os.meka.core.domain.MoneyDateRules.view] over the radar's items. Dates only:
     * nothing is read from or sent to any account.
     */
    suspend fun addMoneyDate(presetId: String): String? = onCore {
        try { renewals.addMoneyDate(presetId, todayEpochDay()) } catch (_: os.meka.core.domain.ValidationException) { null }
    }

    /** UK money deadlines: the five money dates against the radar today (pure, from the synced lists). */
    fun moneyDates(): os.meka.core.domain.MoneyDatesView =
        os.meka.core.domain.MoneyDateRules.view(_lists.value.renewals.all, todayEpochDay())

    /**
     * Car on autopilot: sets the registration from typed text ("ab12 cde"; blank clears it). The car's dates on the radar
     * that had no registration, or the old one, take the new one as their subject. False when it doesn't read (check
     * first with [os.meka.core.domain.CarRules.registrationError]) or nothing changed; never throws, so the Mac can call it.
     */
    suspend fun setCarRegistration(text: String): Boolean = onCore {
        val old = car.registration()?.let(os.meka.core.domain.CarRules::display)
        val changed = try { car.setRegistration(text) } catch (_: os.meka.core.domain.ValidationException) { false }
        if (changed) {
            val now = car.registration()?.let(os.meka.core.domain.CarRules::display)
            renewals.items().filter { os.meka.core.domain.CarRules.isCar(it) && (it.subject == null || it.subject == old) }
                .forEach { renewals.edit(it.id, subject = now ?: "") }
        }
        changed
    }

    /**
     * Car on autopilot: adds one of [os.meka.core.domain.CarRules.PRESETS] to the radar (a placeholder date a month
     * ahead for Meka to set, reminders four weeks and a week before). The new item's id, or null when it is already
     * there or isn't one (never throws).
     */
    suspend fun addCarDate(presetId: String): String? = onCore {
        try { renewals.addCarDate(presetId, todayEpochDay(), car.registration(), carCheck()) } catch (_: os.meka.core.domain.ValidationException) { null }
    }

    /**
     * Car on autopilot, slice 2: moves the radar's MOT or tax date ([presetId] "mot" / "tax") to the DVLA's day (the Car
     * row's "Use the DVLA's date"). Only on Meka's tap; false when there is no DVLA day, no such date on the radar, or
     * it already matches. Never throws.
     */
    suspend fun useDvlaDate(presetId: String): Boolean = onCore {
        val p = os.meka.core.domain.CarRules.preset(presetId) ?: return@onCore false
        val check = os.meka.core.domain.CarCheckRules.forRegistration(carCheck(), car.registration())
        val day = os.meka.core.domain.CarCheckRules.dayFor(p.id, check) ?: return@onCore false
        val item = renewals.items().sortedBy { it.dueDay }.firstOrNull { os.meka.core.domain.CarRules.matches(p, it) } ?: return@onCore false
        if (item.dueDay == day) return@onCore false
        try { renewals.setDue(item.id, day); true } catch (_: os.meka.core.domain.ValidationException) { false }
    }

    /** What MEKA's server last heard from the DVLA (slice 2), or null. */
    private fun carCheck(): os.meka.core.domain.CarCheck? =
        os.meka.core.domain.CarCheckRules.read(replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.CarCheckRules.ENTITY_ID)?.fields)

    // ---- Shopping (family sharing, slice 1; Lists → Shopping) ----

    /**
     * Adds what was typed ("milk, eggs": commas, semicolons and new lines separate) to the shopping list; a name
     * already to buy stays one row, a got one comes back. Returns the ids now to buy; empty when nothing was typed.
     */
    suspend fun addShopping(text: String): List<String> = onCore { shopping.add(text) }
    /** Ticks a shopping item as bought; it moves under Got for a week, where it can be put back. */
    suspend fun gotShopping(id: String): Boolean = onCore { shopping.got(id) }
    /** Unticks a got item: back to buy, in the place it was first added. */
    suspend fun putBackShopping(id: String): Boolean = onCore { shopping.putBack(id) }
    /** Takes an item off the list for good (added by mistake). */
    suspend fun removeShopping(id: String): Boolean = onCore { shopping.remove(id) }
    /** Clear under Got: every bought item leaves the list for good. Returns how many. */
    suspend fun clearGotShopping(): Int = onCore { shopping.clearGot() }

    // ---- Meal plan → shopping (V1, slice 1; Ask → More → Dinners) ----

    /**
     * Adds a favourite dinner from one typed line ("Chilli: mince, kidney beans, rice"; [os.meka.core.domain.MealRules.read]);
     * a dinner already a favourite gets the newly typed ingredients. Returns the line to show ("Added Chilli · 3
     * ingredients"), or null when nothing could be read (the pane says [os.meka.core.domain.MealRules.NOT_READ]).
     */
    suspend fun addMeal(text: String): String? = onCore {
        mealPlan.add(text)?.let { (e, updated) -> os.meka.core.domain.MealRules.addedLine(e, updated) }
    }

    /** Removes a favourite for good; days it was planned on read "Not planned". */
    suspend fun removeMeal(id: String): Boolean = onCore { mealPlan.remove(id) }

    /**
     * Plans the favourite [mealId] for [day] (an epoch day, today up to two weeks on), or clears the day with null.
     * Returns the line to show ("Chilli for tonight"), or null when nothing changed.
     */
    suspend fun planMeal(day: Long, mealId: String?): String? = onCore {
        if (!mealPlan.set(day, mealId)) return@onCore null
        val title = mealId?.let { id -> mealPlan.meals().firstOrNull { it.id == id }?.title }
        os.meka.core.domain.MealRules.plannedLine(day, todayEpochDay(), title)
    }

    /**
     * "Add to shopping": the week's planned dinners' ingredients go on the shared shopping list, each once, leaving out
     * what is already to buy (a got one comes back). Undo with [undoMealsShopping].
     */
    suspend fun mealsToShopping(): os.meka.core.domain.MealsShopped = onCore {
        val toBuy = shopping.view().toBuy.map { os.meka.core.domain.ShoppingRules.key(it.title) }.toSet()
        val v = mealPlan.view(toBuy)
        val wanted = v.week.mapNotNull { r -> r.mealId?.let { id -> mealPlan.meals().firstOrNull { it.id == id } } }
            .flatMap { it.ingredients }.distinctBy(os.meka.core.domain.ShoppingRules::key)
        if (v.toAdd.isEmpty()) return@onCore os.meka.core.domain.MealsShopped(
            os.meka.core.domain.MealRules.shoppedLine(0, wanted.size), emptyList(), emptyList(),
        )
        val a = shopping.addTracked(v.toAdd.joinToString(", "))
        val added = a.created.size + a.revived.size
        os.meka.core.domain.MealsShopped(
            os.meka.core.domain.MealRules.shoppedLine(added, wanted.size - added), a.created, a.revived,
        )
    }

    /** Undo on the bar after "Add to shopping": what it added leaves the list, what it brought back goes back under Got. */
    suspend fun undoMealsShopping(done: os.meka.core.domain.MealsShopped): Boolean = onCore {
        shopping.takeBack(done.added, done.revived)
    }

    // ---- Emergency card (V1, slice 1; Ask → More → Emergency card) ----

    /**
     * Adds one typed line to the card under [section] (`call` · `documents` · `accounts`): "GP: Dr Patel, 01767 123456".
     * The same name again in that part updates its details. A line holding a password, PIN or card, account or policy
     * number is refused and nothing is written; the answer says why (`refused`, lit in the accent).
     */
    suspend fun addEmergency(section: String, text: String): os.meka.core.domain.EmergencyAdded = onCore { emergency.add(section, text) }

    /** Takes a line off the card for good (on both apps and her page). */
    suspend fun removeEmergency(id: String): Boolean = onCore { emergency.remove(id) }

    // ---- Train queue (V1, train-time queue slice 1; the share sheet's For the train, Ask → More → Train queue) ----

    /**
     * Saves the first web link in shared or pasted [text] for the train; a share's [subject] gives the title when it
     * has one. No link: refused, nothing written. The same link again is kept once (back in the queue if it was done).
     */
    suspend fun addToQueue(text: String, subject: String? = null): os.meka.core.domain.QueueAdded =
        onCore { trainQueue.add(text, subject) }

    /** Marks a saved link read or listened to ([done] true), or puts it back in the queue (false). */
    suspend fun queueDone(id: String, done: Boolean): Boolean = onCore { trainQueue.setDone(id, done) }

    /** Takes a saved link off the queue for good (both apps); its saved words go from this device too. */
    suspend fun removeFromQueue(id: String): Boolean = onCore {
        val removed = trainQueue.remove(id)
        if (removed) dropQueuePages(setOf(id))
        removed
    }

    // ---- Errands by location (V1, slice 1; Ask → More → Errands on both apps) ----

    /**
     * Adds what [text] says: "Tesco: milk, batteries", "when I'm near the post office: parcel". Refused (nothing
     * written, [os.meka.core.domain.ErrandAdded.refused]) when it names no place.
     */
    suspend fun addErrand(text: String): os.meka.core.domain.ErrandAdded = onCore { errands.add(text) }

    /** Ticks an errand off ([done] true), or puts it back (false). */
    suspend fun errandDone(id: String, done: Boolean): Boolean = onCore { errands.setDone(id, done) }

    /** Takes an errand off the list for good (both apps). */
    suspend fun removeErrand(id: String): Boolean = onCore { errands.remove(id) }

    // ---- Appointment nudges (V1, slice 1; Ask → More → Appointments and Needs you on both apps) ----

    /**
     * One typed line: "Dentist every 6 months, last 12 Apr", "haircut every 6 weeks", "dentist booked Tue 20 Oct
     * 9:30". Refused (nothing written) when it names nothing, a date can't be read, or a new one says no interval.
     */
    suspend fun typeAppointment(text: String): os.meka.core.domain.AppointmentSaid = onCore {
        val before = appointments.items().associateBy { it.id }
        val said = appointments.type(text)
        // Slice 2c: a booking typed here goes in the calendar too, like Booked… (no Undo here: remove it from the event).
        val id = said.id ?: return@onCore said
        if (said.refused) return@onCore said
        val was = before[id]
        val item = appointments.items().firstOrNull { it.id == id } ?: return@onCore said
        if (item.bookedDay == null || (was != null && was.bookedDay == item.bookedDay && was.bookedMinute == item.bookedMinute)) return@onCore said
        appointmentToCalendar(item, was)?.let { (_, finish) -> said.copy(line = finish(said.line)) } ?: said
    }

    /** Adds a common appointment (Dentist, Optician, Haircut…) with its usual interval. */
    suspend fun addAppointmentPreset(presetId: String): os.meka.core.domain.AppointmentSaid = onCore { appointments.addPreset(presetId) }

    /** Went: a visit today (any booking and Not yet go). Null when it isn't on the list. */
    suspend fun appointmentWent(id: String): os.meka.core.domain.AppointmentDone? = onCore { appointments.went(id) }

    /** Booked…: the day and time typed ("Tue 20 Oct 9:30"); refused when it can't be read. MEKA books nothing. */
    suspend fun bookAppointment(id: String, whenText: String): os.meka.core.domain.AppointmentDone? = onCore {
        val was = appointments.items().firstOrNull { it.id == id }
        val done = appointments.book(id, whenText) ?: return@onCore null
        if (done.refused) return@onCore done
        val item = appointments.items().firstOrNull { it.id == id } ?: return@onCore done
        // Slice 2c: into Google or Outlook when an account allows editing (five seconds' Undo, taken back with the booking).
        appointmentToCalendar(item, was)?.let { (editId, finish) -> done.copy(line = finish(done.line), eventEditId = editId) } ?: done
    }

    /**
     * Appointment nudges, slice 2c: puts [item]'s booked visit in Meka's calendar through calendar editing, when an
     * account allows editing and the visit isn't there already. [was] is the appointment before this booking: when its
     * old booking is an event MEKA can edit, that event moves instead of a second one being added. Returns the edit's
     * id and how to finish the undo bar's line; null when nothing was sent (Today's timeline still shows the visit).
     */
    private fun appointmentToCalendar(
        item: os.meka.core.domain.AppointmentItem, was: os.meka.core.domain.AppointmentItem?,
    ): Pair<String, (String) -> String>? {
        val cal = ZoneCalendar(timeZone)
        val rules = os.meka.core.domain.AppointmentCalendarRules
        val draft = rules.draft(item, cal) ?: return null
        val inCalendar = currentEvents()
        if (inCalendar.any { rules.matches(item, it, cal) }) return null
        val old = was?.takeIf { it.bookedDay != null }?.let { w -> inCalendar.firstOrNull { rules.matches(w, it, cal) && canEditEvent(it) } }
        val result = if (old != null) {
            calendarEdits.change(old, os.meka.core.domain.CalendarEditRules.draftOf(old).copy(
                startAtMs = draft.startAtMs, endAtMs = draft.endAtMs, allDay = draft.allDay,
            ))
        } else {
            val accounts = _editAccounts.value
            val last = os.meka.core.domain.AddEventRules.lastUsedKey(calendarEdits.all())
            val account = accounts.firstOrNull { it.key == last } ?: accounts.firstOrNull() ?: return null
            calendarEdits.add(account.provider, account.email, draft)
        }
        val made = result as? os.meka.core.domain.EventEditResult.Made ?: return null
        val provider = os.meka.core.domain.CalendarEditRules.providerName(old?.provider ?: calendarEdits.edit(made.id)?.provider ?: "google")
        return made.id to { line: String -> rules.bookedLine(line, provider, moved = old != null) }
    }

    /** Not yet: no nudge for two weeks. */
    suspend fun appointmentNotYet(id: String): os.meka.core.domain.AppointmentDone? = onCore { appointments.notYet(id) }

    /** Undo for Went, Booked… and Not yet. */
    suspend fun undoAppointment(done: os.meka.core.domain.AppointmentDone): Boolean = onCore {
        done.eventEditId?.let { calendarEdits.undo(it) }
        appointments.undo(done)
    }

    /** Takes an appointment off the list for good (both apps). */
    suspend fun removeAppointment(id: String): Boolean = onCore { appointments.remove(id) }

    /**
     * Call to book on an appointment's card (slice 2b): a task "Call to book: Dentist" planned on Today (12:30 on a
     * weekday, or the next one), and the card set aside until two days after the call. MEKA calls no one. Undo with
     * [undoAppointmentCall]. Null when it isn't on the list.
     */
    suspend fun appointmentCallToBook(id: String): os.meka.core.domain.AppointmentCall? = onCore {
        val (plan, done) = appointments.callToBook(id) ?: return@onCore null
        val taskId = tasks.create(NewTask(plan.title))
        tasks.setWhen(taskId, plan.day, plan.minute)
        followBlocks(listOf(taskId))
        os.meka.core.domain.AppointmentCall(taskId, plan.line, done)
    }

    /** Undo for Call to book: the task goes and the card asks again as before. */
    suspend fun undoAppointmentCall(call: os.meka.core.domain.AppointmentCall): Boolean = onCore {
        tasks.get(call.taskId)?.let { tasks.delete(call.taskId); followBlocks(listOf(call.taskId)) }
        appointments.undo(call.done)
    }

    /**
     * Copy email on an appointment's card (slice 2b): the booking email for the app to put on the clipboard; MEKA sends
     * nothing. Null when it isn't on the list.
     */
    suspend fun appointmentBookingEmail(id: String): String? = onCore { appointments.bookingEmail(id) }

    // ---- Train queue, slice 2: an article's words kept on this device for reading offline ----

    private val _queuePages = MutableStateFlow<Map<String, String>>(emptyMap())
    private var queuePagesLoaded = false
    /** Pages that couldn't be fetched or had too little to read, and when (in memory; tried again after 6 hours). */
    private val queuePageFailedAt = HashMap<String, Long>()

    /**
     * The saved links whose words this device keeps, each with its row line ("6 min read · saved to read offline").
     * Device-local (never synced): the Fold fetches the pages itself on Wi-Fi ([queueToFetch], [saveQueuePage]).
     */
    val queuePages: StateFlow<Map<String, String>> = _queuePages.asStateFlow()

    private fun loadQueuePages() {
        if (queuePagesLoaded) return
        queuePagesLoaded = true
        _queuePages.value = os.meka.core.domain.ReaderCodec.decodeIndex(
            runCatching { localStore.transaction { localStore.localValue(os.meka.core.domain.ReaderCodec.INDEX_KEY) } }.getOrNull(),
        )
    }

    private fun dropQueuePages(ids: Set<String>) {
        loadQueuePages()
        val left = _queuePages.value - ids
        if (left.size == _queuePages.value.size) return
        runCatching {
            localStore.transaction {
                ids.forEach { localStore.setLocalValue(os.meka.core.domain.ReaderCodec.key(it), null) }
                localStore.setLocalValue(os.meka.core.domain.ReaderCodec.INDEX_KEY, os.meka.core.domain.ReaderCodec.encodeIndex(left))
            }
        }.onSuccess { _queuePages.value = left }
    }

    /**
     * The waiting articles whose words this device should fetch now (newest first, at most five; podcasts never; a page
     * that failed waits six hours). Pages of links gone from the queue are dropped here too.
     */
    suspend fun queueToFetch(): List<os.meka.core.domain.QueueFetch> = onCore {
        loadQueuePages()
        val items = trainQueue.items()
        dropQueuePages(os.meka.core.domain.ReaderRules.stale(_queuePages.value.keys, items, nowMs()))
        os.meka.core.domain.ReaderRules.toFetch(items, _queuePages.value.keys, queuePageFailedAt, nowMs())
    }

    /**
     * Keeps the readable words of [html], the page the device fetched for the waiting link [id] (null: the fetch
     * failed). False when the link no longer waits, the fetch failed or there was too little to read (tried again
     * after six hours). The words stay on this device only and never go to MEKA's AI.
     */
    suspend fun saveQueuePage(id: String, html: String?): Boolean = onCore {
        loadQueuePages()
        val item = trainQueue.items().firstOrNull { it.id == id && !it.done }
        val page = item?.let { html?.let { h -> os.meka.core.domain.ReaderRules.extract(h, it.title) } }?.copy(savedAtMs = nowMs())
        if (item == null || page == null) {
            if (item != null) queuePageFailedAt[id] = nowMs()
            false
        } else {
            val index = _queuePages.value + (id to os.meka.core.domain.ReaderRules.rowLine(page))
            runCatching {
                localStore.transaction {
                    localStore.setLocalValue(os.meka.core.domain.ReaderCodec.key(id), os.meka.core.domain.ReaderCodec.encode(page))
                    localStore.setLocalValue(os.meka.core.domain.ReaderCodec.INDEX_KEY, os.meka.core.domain.ReaderCodec.encodeIndex(index))
                }
            }.onSuccess { _queuePages.value = index; queuePageFailedAt.remove(id) }.isSuccess
        }
    }

    /** A saved link's words as kept on this device, or null (not saved here, or unreadable). */
    suspend fun queuePage(id: String): os.meka.core.domain.ReaderPage? = onCore {
        os.meka.core.domain.ReaderCodec.decode(
            runCatching { localStore.transaction { localStore.localValue(os.meka.core.domain.ReaderCodec.key(id)) } }.getOrNull(),
        )
    }

    // ---- Work wins (V1, slice 1; the Review tab) ----

    /**
     * Keeps one line ("Shipped the Q3 report") for the week the review shows: dated today in the week in progress, else
     * that week's Friday. An empty line is refused; the same line twice in a week is kept once.
     */
    suspend fun addWin(text: String): os.meka.core.domain.WinAdded = onCore { wins.add(text, _review.value.weekStart) }

    /** Takes a win off the list (both apps). */
    suspend fun removeWin(id: String): Boolean = onCore { wins.remove(id) }

    // ---- What MEKA knows (V1, slice 1; Ask → More → What MEKA knows) ----

    /**
     * Adds one typed fact ("Car reg: AB12 CDE", "my shoe size is 10"); the same name again changes it. A line holding a
     * password, PIN or card, bank or ID number is refused and nothing is written; the answer says why (`refused`).
     */
    suspend fun addFact(text: String): os.meka.core.domain.FactAdded = onCore { lifeFacts.add(text) }

    /** Lets MEKA's AI answer from a fact ([use] true) or keeps it from the AI (it stays on the page). */
    suspend fun setFactAi(id: String, use: Boolean): Boolean = onCore { lifeFacts.setAi(id, use) }

    /** Takes a fact off the page for good (on both apps). */
    suspend fun removeFact(id: String): Boolean = onCore { lifeFacts.remove(id) }

    /**
     * A shared form's fields answered from the facts page (slice 4, shown with "Handle this"): each field beside the
     * fact that answers it, for Copy. Worked out here on the device; nothing is sent anywhere and nothing is stored,
     * so it works with MEKA's AI off and answers from facts kept from the AI too.
     */
    suspend fun formAnswers(text: String, subject: String? = null): os.meka.core.domain.FormAnswersView =
        onCore { os.meka.core.domain.FormFillRules.answers(text, subject, lifeFacts.all()) }

    // ---- Protected time (V1, slice 1; Ask → More → Protected time) ----

    /**
     * Sets protected time: [evenings] from [fromMin] (local minutes) and [weekends] (with bank holidays). Returns the
     * summary line, or null when nothing changed.
     */
    suspend fun setProtectedTime(evenings: Boolean, fromMin: Int, weekends: Boolean): String? = onCore {
        if (!protectedTime.set(evenings, fromMin, weekends)) return@onCore null
        os.meka.core.domain.ProtectedRules.summary(protectedTime.setting())
    }

    // ---- "Leaving now" (V1, slice 1; Work mode → Leaving now) ----

    /**
     * Sets "Leaving now": on or off, who the draft goes to (a Family entry's name, e.g. "Jeanette") and the usual
     * journey home in minutes. Returns the summary line, or null when nothing changed or the values don't make sense.
     */
    suspend fun setLeavingNow(on: Boolean, to: String, journeyMin: Int): String? = onCore {
        if (!leavingNow.set(on, to, journeyMin)) return@onCore null
        refresh()
        os.meka.core.domain.LeavingRules.summary(leavingNow.setting())
    }

    /**
     * Send or Edit tapped on a "Leaving now" notification [key] that showed [shown] (Level 3: only from Meka's tap).
     * [os.meka.core.domain.LeavingTap.Send] carries exactly the shown words; Changed carries the updated notice to show
     * instead (nothing is sent); null when it no longer applies.
     */
    suspend fun leavingTap(key: String, shown: String, edit: Boolean): os.meka.core.domain.LeavingTap? = onCore {
        os.meka.core.domain.LeavingRules.tap(
            key, shown, leavingNow.setting(), officeToday(), lineStatus.snapshot(), nowMs(), ZoneCalendar(timeZone), edit,
        )
    }

    // ---- Sunday week ahead (V1, slice 2; Ask → More → Week ahead → Sunday heads-up) ----

    /**
     * Sets Sunday's week-ahead heads-up: on or off and its time ([minute], a quarter hour from 06:00 to 22:00). Returns
     * the summary line, or null when nothing changed or the time doesn't make sense.
     */
    suspend fun setWeekAhead(on: Boolean, minute: Int): String? = onCore {
        if (!weekAheadSettings.set(on, minute)) return@onCore null
        refresh()
        os.meka.core.domain.WeekAheadRules.summary(weekAheadSettings.setting())
    }

    /**
     * Picks a goal as one of the goals for the week the week ahead shows (next week from Saturday, else this one), or
     * unpicks it (Goals, properly, item 6; up to three a week). Returns "“Run 5k” is one of next week's goals" /
     * "“Run 5k” is off next week's goals", or null when nothing changed (a fourth pick is refused).
     */
    suspend fun pickWeekGoal(goalId: String, on: Boolean): String? = onCore {
        val today = todayEpochDay()
        val ws = os.meka.core.domain.WeekAheadRules.weekStart(today)
        if (!goals.pickForWeek(goalId, ws, on)) return@onCore null
        refresh()
        val title = _goals.value.goals.firstOrNull { it.id == goalId }?.title ?: "That goal"
        val week = if (ws > today) "next week's" else "this week's"
        if (on) "“$title” is one of $week goals" else "“$title” is off $week goals"
    }

    // ---- Date night (V1, slice 1; Ask → More → Date night) ----

    /**
     * Sets date night: every other [weekday] (ISO 1..7) from [startMin], the first on [firstDay] (that weekday, from
     * today on; the view's `starts` offer the two choices). Returns the summary line, or null when nothing changed.
     */
    suspend fun setDateNight(weekday: Int, startMin: Int, firstDay: Long): String? = onCore {
        if (!dateNight.set(weekday, startMin, firstDay)) return@onCore null
        os.meka.core.domain.DateNightRules.view(dateNight.setting(), todayEpochDay()).summary
    }

    /** Turns date night off: those evenings are free to plan again. */
    suspend fun dateNightOff(): Boolean = onCore { dateNight.off() }

    /** Skips one coming date night (its evening is free to plan), or keeps it again; returns the line to show. */
    suspend fun skipDateNight(day: Long, skip: Boolean): String? = onCore {
        if (!dateNight.skip(day, skip)) return@onCore null
        os.meka.core.domain.DateNightRules.skipLine(day, todayEpochDay(), skip)
    }

    /**
     * Booked on the week-before card in Needs you (slice 2): somewhere is booked and cover arranged, so the card and its
     * heads-up leave both apps; [booked] false (the undo bar) asks again. Returns the line to show, or null.
     */
    suspend fun bookDateNight(day: Long, booked: Boolean): String? = onCore {
        if (!dateNight.book(day, booked)) return@onCore null
        os.meka.core.domain.DateNightRules.bookedLine(day, todayEpochDay(), booked)
    }

    // ---- Bin days (V1, slice 1; Ask → More → Bins) ----

    /**
     * Adds one typed bin ("Black bin Tue fortnightly from 13 Oct", "Food caddy every Tuesday";
     * [os.meka.core.domain.BinRules.read]); a bin already there by name is updated. Refused (nothing written) when
     * MEKA couldn't read a day or a bin in it.
     */
    suspend fun addBin(text: String): os.meka.core.domain.BinAdded = onCore { bins.add(text, bankHolidays.calendar()) }

    /** Removes a bin for good. */
    suspend fun removeBin(id: String): Boolean = onCore { bins.remove(id) }

    /**
     * Bin days, slice 3: takes back a one-off ("Black bin Sat 2 Jan instead of 29 Dec", typed through [addBin]), so
     * that collection goes on its usual day again. Its id is a row's in [os.meka.core.domain.BinsView.oneOffs].
     */
    suspend fun removeBinMove(id: String): Boolean = onCore { bins.removeMove(id) }

    // ---- School rhythm (V1, slice 1; Ask → More → School, and Needs you's cover questions) ----

    /**
     * Adds one typed line ("INSET 27 Oct", "Half term 26–30 Oct", "Rex PE Tue"; [os.meka.core.domain.SchoolRules.read]).
     * Returns the line to show ("Added INSET day · Tue 27 Oct · Rex and Logan"), or null when MEKA couldn't read a date
     * or weekday in it (nothing is written; the pane says [os.meka.core.domain.SchoolRules.NOT_READ]).
     */
    suspend fun addSchool(text: String): String? = onCore {
        school.add(text)?.let { (_, e) -> os.meka.core.domain.SchoolRules.addedLine(e) }
    }

    /** Removes a school line for good (typed by mistake). */
    suspend fun removeSchool(id: String): Boolean = onCore { school.remove(id) }

    /**
     * Answers a day off's cover question in Needs you: [home] makes each office day it falls on a work-from-home day
     * (as Add on a request card does), else it is "Covered". The question leaves both apps. Null when it isn't open.
     */
    suspend fun coverSchool(id: String, home: Boolean): os.meka.core.domain.SchoolCoverDone? = onCore {
        val today = todayEpochDay()
        val hours = work.hours(localClock(), today)
        val items = school.items()
        val cover = os.meka.core.domain.SchoolRules.covers(items, today, hours).firstOrNull { it.id == id } ?: return@onCore null
        val title = items.first { it.id == id }.title
        if (!school.answer(id, home, cover.days)) return@onCore null
        val set = if (home) cover.days.filter { work.setHomeDay(it, true, today) } else emptyList()
        os.meka.core.domain.SchoolCoverDone(id, os.meka.core.domain.SchoolRules.coverLine(cover, home, title), set)
    }

    /** Undo on the bar after answering: the question is open again and the work-from-home days it made are taken back. */
    suspend fun undoSchoolCover(done: os.meka.core.domain.SchoolCoverDone): Boolean = onCore {
        var undone = school.reopen(done.id)
        val today = todayEpochDay()
        done.homeDays.forEach { if (work.setHomeDay(it, false, today)) undone = true }
        undone
    }

    // ---- Annual leave (V1, slice 1; Ask → More → Annual leave) ----

    /** Work hours today without the leave itself, so a stretch's cost is its work days ([os.meka.core.domain.LeaveRules]). */
    private fun leaveWork(): os.meka.core.domain.WorkHours = work.hours(localClock(), todayEpochDay())

    /**
     * Books one typed stretch ("27–30 Oct", "Spain 18 Dec – 4 Jan"; leave already taken with its year). Returns the
     * line to show ("Booked Spain · Fri 18 Dec – Mon 4 Jan · 7 days", or why nothing was written: no work days in it,
     * already booked), or null when MEKA couldn't read a date (the pane says [os.meka.core.domain.LeaveRules.NOT_READ]).
     */
    suspend fun addLeave(text: String): os.meka.core.domain.LeaveAdded? = onCore { leave.add(text, leaveWork()) }

    /** Books a school holiday's work days not booked yet, under its name; null when they all are (or it's gone). */
    suspend fun bookLeaveForHoliday(schoolId: String): os.meka.core.domain.LeaveAdded? =
        onCore { leave.bookHoliday(schoolId, school.items(), leaveWork()) }

    /** Takes a stretch off for good (cancelled, or typed by mistake); its days are work days again on both apps. */
    suspend fun removeLeave(id: String): Boolean = onCore { leave.remove(id) }

    /** The allowance (days a leave year, 1–60) and the month the leave year starts (1–12). */
    suspend fun setLeaveAllowance(days: Int, yearStartMonth: Int): Boolean = onCore { leave.setSettings(days, yearStartMonth) }

    // ---- Goals and habits ----

    /** Adds a habit: [perWeek] 1–7, a part of the day the planner prefers, and how long one go takes. */
    suspend fun addHabit(title: String, perWeek: Int, timing: HabitTiming, minutes: Int, goalId: String?): String =
        onCore { goals.addHabit(title, perWeek, timing, minutes, goalId) }
    suspend fun editHabit(id: String, title: String?, perWeek: Int?, timing: HabitTiming?, minutes: Int?) =
        onCore { goals.editHabit(id, title, perWeek, timing, minutes) }
    suspend fun setHabitTarget(id: String, perWeek: Int) = onCore { goals.editHabit(id, perWeek = perWeek) }
    suspend fun setHabitTiming(id: String, timing: HabitTiming) = onCore { goals.editHabit(id, timing = timing) }
    suspend fun setHabitMinutes(id: String, minutes: Int) = onCore { goals.editHabit(id, minutes = minutes) }
    /** Ticks or unticks a habit for today. */
    suspend fun setHabitDone(id: String, done: Boolean) = onCore { goals.setHabitDone(id, done) }
    suspend fun setHabitGoal(id: String, goalId: String?) = onCore { goals.setHabitGoal(id, goalId) }
    suspend fun deleteHabit(id: String) = onCore { goals.deleteHabit(id) }

    // ---- The Gym (booked habits) ----

    /** Adds "Gym": three times a week, evenings, an hour, its sessions booked into the week. Returns its id. */
    suspend fun addGym(): String = onCore {
        val id = goals.addHabit("Gym", perWeek = 3, timing = HabitTiming.EVENING, minutes = 60)
        goals.setHabitBooked(id, true)
        id
    }
    /**
     * "Let MEKA book “gym”" (Fold review 2026-10-09 13:45, item 2): a hand-made gym habit becomes the booked Gym in place,
     * so its ticks, streak and goal stay: sessions booked, an hour when it was shorter than half an hour, evenings when
     * it was "Any time"; its target is kept.
     */
    suspend fun letMekaBook(id: String) = onCore {
        val h = goals.habits().firstOrNull { it.id == id } ?: return@onCore
        val minutes = os.meka.core.domain.SessionRules.bookedMinutes(h.minutes)
        val timing = os.meka.core.domain.SessionRules.bookedTiming(h.timing)
        if (minutes != h.minutes || timing != h.timing) goals.editHabit(id, timing = timing, minutes = minutes)
        goals.setHabitBooked(id, true)
    }
    /** "Book my sessions": MEKA books the habit's sessions into the week around the calendar and work. */
    suspend fun setHabitBooked(id: String, on: Boolean) = onCore { goals.setHabitBooked(id, on) }
    /** The rotation preset at [index] in [os.meka.core.domain.SessionRules.ROTATIONS] (0: none). */
    suspend fun setHabitRotation(id: String, index: Int) = onCore { goals.setHabitRotation(id, os.meka.core.domain.SessionRules.rotationAt(index)) }
    /** The workout app's link ("hevy.com"), opened from Today's card; blank clears it. False when it isn't a web address. */
    suspend fun setHabitAppLink(id: String, link: String?): Boolean = onCore { goals.setHabitAppLink(id, link) }
    /** "Went": today ticked with the session's label and an optional one-line note. */
    suspend fun sessionWent(id: String, note: String?) = onCore {
        val label = _sessions.value.cards.firstOrNull { it.habitId == id }?.label
        goals.answerSession(id, went = true, label = label, note = note)
    }
    /** "Didn't go": the session is rebooked on another day this week, if there's room. */
    suspend fun sessionMissed(id: String) = onCore { goals.answerSession(id, went = false, label = null, note = null) }
    /** The note on today's session (blank clears it). */
    suspend fun setSessionNote(id: String, note: String?) = onCore { goals.setSessionNote(id, note) }
    /** Undo for Went / Didn't go. */
    suspend fun undoSession(id: String) = onCore { goals.clearSessionAnswer(id) }
    /**
     * Went / Didn't go from a "Did you go?" notification (Gym slice 2b). Answers only that day's session while it still
     * asks; a notification left over from yesterday, or a session already answered on the other device, changes
     * nothing (null). Returns the card after answering, for the quiet "Went · 2 of 3 this week" note and its Undo.
     */
    suspend fun answerSessionNotice(key: String, action: os.meka.core.domain.NoticeAction): os.meka.core.domain.SessionCard? = onCore {
        if (action != os.meka.core.domain.NoticeAction.WENT && action != os.meka.core.domain.NoticeAction.DIDNT_GO) return@onCore null
        val card = os.meka.core.domain.SessionRules.askedCard(_sessions.value, key, todayEpochDay()) ?: return@onCore null
        val went = action == os.meka.core.domain.NoticeAction.WENT
        goals.answerSession(card.habitId, went = went, label = if (went) card.label else null, note = null)
        _sessions.value.cards.firstOrNull { it.habitId == card.habitId }
    }

    suspend fun addGoal(title: String, target: String?, horizon: GoalHorizon): String = onCore { goals.addGoal(title, target, horizon) }
    suspend fun editGoal(id: String, title: String?, target: String?, horizon: GoalHorizon?) = onCore { goals.editGoal(id, title, target, horizon) }
    suspend fun setGoalHorizon(id: String, horizon: GoalHorizon) = onCore { goals.editGoal(id, horizon = horizon) }
    /** Hand-set progress (used while nothing is linked to the goal). */
    suspend fun setGoalProgress(id: String, pct: Int) = onCore { goals.setGoalProgress(id, pct) }
    suspend fun finishGoal(id: String) = onCore { goals.finishGoal(id) }
    suspend fun deleteGoal(id: String) = onCore { goals.deleteGoal(id) }
    /** Links a task to a goal (null unlinks); done tasks then count towards the goal. */
    suspend fun setTaskGoal(taskId: String, goalId: String?) = onCore { goals.setTaskGoal(taskId, goalId) }
    /** Why the goal matters (blank clears it). */
    suspend fun setGoalWhy(id: String, why: String?) = onCore { goals.setGoalWhy(id, why) }
    /** The day it should be done by, from [os.meka.core.domain.GoalRules.dueChoices] (null clears it). */
    suspend fun setGoalDue(id: String, dueDay: Long?) = onCore { goals.setGoalDue(id, dueDay) }
    /** Puts the goal under a bigger one ([os.meka.core.domain.GoalRules.parentChoices]); null moves it to the top. */
    suspend fun setGoalParent(id: String, parentId: String?) = onCore { goals.setGoalParent(id, parentId) }
    /** A new goal under [parentId] (a month goal under a year one…), its horizon one step shorter by default. */
    suspend fun addGoalUnder(parentId: String, title: String, horizon: GoalHorizon): String =
        onCore { goals.addGoal(title, null, horizon, parentId = parentId) }
    /** "Add a step": a new task linked to the goal; it becomes the next step if nothing else is planned sooner. */
    suspend fun addGoalStep(goalId: String, title: String): String = onCore {
        if (!goals.goalExists(goalId)) throw os.meka.core.domain.ValidationException("Goal not found")
        tasks.create(NewTask(title, goalId = goalId))
    }
    /** Goals, properly, slice 2: a milestone on the way ("Run 10k"); a title already there isn't added twice. */
    suspend fun addGoalMilestone(goalId: String, title: String): String = onCore { goals.addMilestone(goalId, title) }
    /** Ticks (or unticks) a milestone; it counts towards the goal's progress. */
    suspend fun setGoalMilestoneDone(id: String, done: Boolean) = onCore { goals.setMilestoneDone(id, done) }
    /** Removes a milestone; returns its title for Undo ([addGoalMilestone] adds it back), null if it was already gone. */
    suspend fun removeGoalMilestone(id: String): String? = onCore { goals.removeMilestone(id) }
    /**
     * Goals, properly, slice 3: the number to reach ("£2,000", "12 books"); blank makes it an ordinary goal again.
     * False (nothing written) when it isn't a number.
     */
    suspend fun setGoalNumber(goalId: String, text: String?): Boolean = onCore { goals.setMetric(goalId, text) }
    /** An update to a numeric goal ("+200", "-1", "=1200"); its id for Undo, or null when it changes nothing. */
    suspend fun addGoalUpdate(goalId: String, text: String): String? = onCore { goals.addMetricEntry(goalId, text) }
    /** Removes an update (Undo, or Remove in the list). */
    suspend fun removeGoalUpdate(id: String): Boolean = onCore { goals.removeMetricEntry(id) }
    /** Each tick of [habitId] adds 1 to the goal's number (null stops it). */
    suspend fun setGoalNumberHabit(goalId: String, habitId: String?) = onCore { goals.setMetricHabit(goalId, habitId) }
    /** Open tasks to offer under "Link a task" ([os.meka.core.domain.GoalRules.linkCandidates]). */
    suspend fun goalLinkCandidates(goalId: String, query: String): List<os.meka.core.domain.Task> =
        onCore { os.meka.core.domain.GoalRules.linkCandidates(tasks.all(), goalId, query) }

    /**
     * Goals, properly, slice 4: **Break it down** (approval only). The goal alone goes to `POST /v1/ai/goal-breakdown`
     * in Meka's own words (title, horizon, done-by, why, what done looks like, its milestones and open steps); never
     * another goal or task. The proposals are checked again here ([os.meka.core.domain.GoalBreakdownRules.check]) and
     * handed back for Meka to tick; nothing is written until [keepBreakdown].
     */
    suspend fun breakDownGoal(goalId: String): BreakdownOutcome {
        val rules = os.meka.core.domain.GoalBreakdownRules
        val atMs = nowMs()
        val cal = ZoneCalendar(timeZone)
        val today = cal.epochDayOf(atMs)
        val (g, openSteps) = onCore {
            val all = tasks.all()
            val goal = goals.goals(all).first.firstOrNull { it.id == goalId } ?: throw os.meka.core.domain.ValidationException("Goal not found")
            goal to all.filter { it.goalId == goalId && !it.lifecycle.isTerminal }.sortedBy { it.createdAtMs }.map { it.title.trim() }
        }
        if (rules.room(g.milestones.size) == 0 && openSteps.size >= rules.MAX_KNOWN_STEPS) return BreakdownOutcome.Unavailable(rules.FULL_LINE)
        val api = aiApi ?: return BreakdownOutcome.Unavailable(rules.NOT_CONNECTED_LINE)
        val c = os.meka.core.wire.GoalBreakdownCodec
        fun fit(s: String?, max: Int) = s?.replace(Regex("""\s+"""), " ")?.trim()?.take(max).orEmpty()
        fun items(xs: List<String>) = xs.map { fit(it, c.MAX_ITEM) }.filter { it.isNotEmpty() }.distinct().take(c.MAX_KNOWN)
        val request = os.meka.core.wire.GoalBreakdownCodec.Request(
            date = os.meka.core.domain.AskRules.isoDate(today),
            now = "${os.meka.core.domain.CivilDate.longLabel(today)} ${os.meka.core.domain.CivilDate.fromEpochDay(today).year} · ${LocalClock.formatMinute(cal.minuteOfDay(atMs))}",
            title = fit(g.title, c.MAX_TITLE),
            horizon = os.meka.core.domain.GoalRules.horizonLabel(g.horizon),
            due = g.dueDay?.let { os.meka.core.domain.GoalRules.dueLabel(it, today) }.orEmpty().take(c.MAX_LABEL),
            why = fit(g.why, c.MAX_TEXT),
            done = fit(g.target, c.MAX_TEXT),
            milestones = items(g.milestones.map { it.title }),
            steps = items(openSteps.take(rules.MAX_KNOWN_STEPS)),
        )
        val reply = try { api.goalBreakdown(request) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return BreakdownOutcome.Unavailable(os.meka.core.domain.AskRules.OFFLINE_LINE)
        }
        if (reply.state != os.meka.core.wire.AskCodec.Response.ANSWERED) {
            return BreakdownOutcome.Unavailable(os.meka.core.domain.AskRules.unavailableLine(reply.state, reply.reason))
        }
        // Checked against what the goal has now (it may have changed while MEKA thought).
        val checked = onCore {
            val open = tasks.all().filter { it.goalId == goalId && !it.lifecycle.isTerminal }.map { it.title }
            rules.check(reply.milestones, reply.steps, goals.milestones(goalId).map { it.title }, open)
        }
        return if (checked.isEmpty) BreakdownOutcome.NothingNew(rules.NOTHING_LINE) else BreakdownOutcome.Proposals(checked, rules.readyLine(checked))
    }

    /**
     * Keeps what Meka ticked of a breakdown: each milestone added to the goal (one already there isn't added twice; at
     * most the goal's room), each step an ordinary task linked to it. Returns the bar's line and what [undoBreakdown]
     * takes back.
     */
    suspend fun keepBreakdown(goalId: String, milestones: List<String>, steps: List<String>): BreakdownKept = onCore {
        if (!goals.goalExists(goalId)) throw os.meka.core.domain.ValidationException("Goal not found")
        val rules = os.meka.core.domain.GoalBreakdownRules
        val before = goals.milestones(goalId).map { it.id }.toSet()
        val room = (os.meka.core.domain.GoalRules.MAX_MILESTONES - before.size).coerceAtLeast(0)
        val ms = milestones.mapNotNull { rules.clean(it, os.meka.core.domain.GoalRules.MAX_MILESTONE_TITLE) }.take(room)
            .map { goals.addMilestone(goalId, it) }.filter { it !in before }.distinct()
        val ts = steps.mapNotNull { rules.clean(it, rules.MAX_STEP_TITLE) }.distinct().take(rules.MAX_STEPS)
            .map { tasks.create(NewTask(it, goalId = goalId)) }
        BreakdownKept(rules.keptLine(ms.size, ts.size), ms, ts)
    }

    /** Undo on a kept breakdown: its milestones come off the goal and its steps are deleted (a step already done stays). */
    suspend fun undoBreakdown(kept: BreakdownKept) = onCore {
        kept.milestoneIds.forEach { goals.removeMilestone(it) }
        val open = tasks.all().filter { it.id in kept.taskIds && !it.lifecycle.isTerminal }.map { it.id }
        open.forEach { tasks.delete(it) }
        followBlocks(open)
        Unit
    }

    // ---- Fasting ----

    /** Starts a fast [startedMinutesAgo] minutes ago (0: now) with the plan's goal. */
    suspend fun startFast(startedMinutesAgo: Int): String = onCore { fasting.start(startedMinutesAgo) }
    /** Starts an extended fast of [hours] (Fasting v2: [os.meka.core.domain.FastingRules.EXTENDED_CHOICES]). */
    suspend fun startExtendedFast(hours: Int, startedMinutesAgo: Int): String = onCore { fasting.startExtended(hours, startedMinutesAgo) }
    /** Starts an extended fast that runs until [untilMs] ([os.meka.core.domain.FastingView.untilChoices]). */
    suspend fun startFastUntil(untilMs: Long, startedMinutesAgo: Int): String = onCore { fasting.startUntil(untilMs, startedMinutesAgo) }
    /** Ends the running fast now. */
    suspend fun endFast() = onCore { fasting.end() }
    /** Undoes "End fast" for a few minutes after it ([os.meka.core.domain.LastFast.canResume]). */
    suspend fun resumeFast(id: String) = onCore { fasting.resume(id) }
    suspend fun setFastTarget(hours: Int) = onCore { fasting.setTarget(hours) }
    /** Moves the running fast's start by [deltaMinutes] (negative: earlier). */
    suspend fun moveFastStart(deltaMinutes: Int) = onCore { fasting.moveStart(deltaMinutes) }
    /** Throws away a fast started by mistake. */
    suspend fun discardFast() = onCore { fasting.discard() }
    /** Picks a plan from [os.meka.core.domain.FastingRules.PLAN_CHOICES] (goal and eating window). */
    suspend fun chooseFastingPlan(index: Int) = onCore { fasting.choosePlan(index) }

    // ---- Evening shutdown ----

    /** Carries one item over to tomorrow (the same "Tomorrow" as in the task detail). */
    suspend fun carryOver(taskId: String) = onCore { tasks.snoozeOccurrence(taskId, 1); followBlocks(listOf(taskId)); Unit }
    /** "Move the rest to tomorrow": everything still left from today waits for tomorrow. */
    suspend fun carryAllToTomorrow() = onCore {
        val left = os.meka.core.domain.ShutdownRules.left(tasks.all(), dayWindow(nowMs())).map { it.id }
        shutdown.carryAllToTomorrow(dayWindow(nowMs()))
        followBlocks(left)
        Unit
    }
    /** Calls it a day: the shutdown card is put away on every device until tomorrow evening. */
    suspend fun shutDown() = onCore { shutdown.shutDown() }

    // ---- Alarms ----

    /**
     * Sets the next morning's wake alarm at local [minute], noting the first commitment it's for. Returns false for a
     * time already gone (just after midnight).
     */
    suspend fun setWake(minute: Int): Boolean = onCore { alarms.setWake(minute, _wake.value.suggestion?.commitment?.line) }
    /** Sets the wake alarm to the suggested time ("Use 06:30"); false when there's no suggestion. */
    suspend fun useSuggestedWake(): Boolean = onCore {
        val s = _wake.value.suggestion
        s != null && alarms.setWake(s.minute, s.commitment.line)
    }
    /** Turns the next morning's wake alarm off on every device. */
    suspend fun wakeOff() = onCore { alarms.wakeOff() }
    /** The get-ready buffer (15 min–3 h in fives); false and nothing written otherwise. */
    suspend fun setWakeBuffer(minutes: Int): Boolean = onCore { alarms.setBuffer(minutes) }
    /** Snoozes a ringing alarm for 9 minutes; returns it as it now rings, or null when it can't be snoozed. */
    suspend fun snoozeAlarm(id: String): os.meka.core.domain.AlarmRing? = onCore { alarms.snooze(id) }
    /** Dismisses an alarm on every device; false when it was already dismissed (e.g. on the other device). */
    suspend fun dismissAlarm(id: String): Boolean = onCore { alarms.dismiss(id) }
    /** The alarm ringing right now, if any (for the ringing screen); read from [nextAlarm] as it stands. */
    fun ringingAlarm(): os.meka.core.domain.AlarmRing? {
        val now = nowMs()
        return _nextAlarm.value?.takeIf { now >= it.ringAtMs && now < it.ringAtMs + os.meka.core.domain.AlarmRules.RING_FOR_MS }
    }

    // ---- Morning brief ----

    /**
     * "Got it": the brief's card is put away on every device until tomorrow morning. [on] names this device as its
     * app calls it ("Mac", "Fold"), so the other one can say "Brief read on your Mac".
     */
    suspend fun briefSeen(on: String) = onCore { brief.markSeen(on) }
    /**
     * Calm Today: the brief pane closed after [openForMs] (measured by the app). Once it was open long enough to read
     * ([BriefRules.READ_AFTER_MS]) while this morning's card was offered, it counts as read on every device, as "Got
     * it" does, so the card folds away. Returns true when it did.
     */
    suspend fun briefLookedAt(on: String, openForMs: Long): Boolean = onCore {
        if (!BriefRules.putAwayOnClose(_brief.value, openForMs)) false else { brief.markSeen(on); true }
    }
    /** [briefLookedAt] without waiting, for a pane that is going away (its own scope is ending). */
    fun briefClosed(on: String, openForMs: Long) { scope.launch { runCatching { briefLookedAt(on, openForMs) } } }
    /** Shows or hides a news topic in the brief and the News place ([os.meka.core.domain.NewsTopics]); synced. */
    suspend fun setNewsTopic(topicId: String, on: Boolean) = onCore { news.setTopic(topicId, on) }

    /** News → Spanish sources (Fold review 2026-10-09 07:26 item 1): off = English only everywhere news shows; synced. */
    suspend fun setNewsSpanish(on: Boolean) = onCore { news.setSpanish(on) }

    // ---- Weekly review ----

    /** Shows the week [offset] weeks from this one (0 this week, -1 last week, back to -12). */
    suspend fun showReviewWeek(offset: Int) = onCore { reviewOffset = offset; refresh() }
    /** "Done reviewing" for the week on screen; synced. */
    suspend fun reviewDone() = onCore { review.markReviewed(_review.value.weekStart) }
    /** Opens the review on the week Today's card is about (this week on Sunday, last week on Monday). */
    suspend fun showReviewCardWeek() = onCore { reviewOffset = _review.value.card.offset; refresh() }

    // ---- Export ----

    /** What an export would hold right now ("312 items" · "214 tasks · 48 calendar events · …"). */
    suspend fun exportSummary(): os.meka.core.domain.ExportSummary =
        onCore { os.meka.core.domain.DataExport.summary(os.meka.core.domain.DataExport.collect(replica, replica.deviceId, nowMs())) }

    /**
     * Everything on this device as one JSON file (build plan M1): the platform asks where to save it and writes
     * [DataExportFile.json] there. Nothing is sent anywhere. The file isn't encrypted.
     */
    suspend fun exportAll(): DataExportFile = onCore {
        val now = nowMs()
        val data = os.meka.core.domain.DataExport.collect(replica, replica.deviceId, now)
        DataExportFile(
            fileName = os.meka.core.domain.DataExport.fileName(now, ZoneCalendar(timeZone)),
            json = DataExportCodec.encode(data),
            summary = os.meka.core.domain.DataExport.summary(data),
        )
    }

    // ---- Search ----

    /** Searches everything for [query] ("" clears it); results arrive on [searchView] and follow later edits. */
    suspend fun search(query: String) = onCore {
        searchQuery = query.take(os.meka.core.domain.SearchRules.MAX_QUERY)
        _search.value = runSearch(tasks.all())
    }

    // ---- Notification governor ----

    /** Quiet hours as local minutes of the day; an end before the start crosses midnight. */
    suspend fun setQuietHours(enabled: Boolean, startMinute: Int, endMinute: Int) =
        onCore { notifyPrefs.setQuietHours(QuietHours(enabled, startMinute, endMinute)) }
    /** Turns the digest at [minute] (e.g. [NotificationSettings.MIDDAY]) on or off. */
    suspend fun setDigest(minute: Int, on: Boolean) = onCore { notifyPrefs.setDigest(minute, on) }
    /** Moves a source to a lower tier ([NoticeSource.CHOICES]); its default tier clears the choice. */
    suspend fun setNoticeTier(source: NoticeSource, tier: NoticeTier) = onCore { notifyPrefs.setTier(source, tier) }

    /**
     * "Notify straight away" beside a watched person (Work mode → Watch for requests from; V1 requests): [name]'s
     * request cards become a heads-up when they arrive instead of a digest item, on every device (synced in
     * [notificationSettings]'s `requestNow`). "Requests from people you watch" set lower still wins.
     */
    suspend fun setRequestNotifyNow(name: String, on: Boolean) = onCore { notifyPrefs.setRequestNow(name, on) }

    /**
     * What this device should post now. [state] is what the last call returned ([GovernorResult.stateEncoded]),
     * kept on the device; [device] is this device's own choice. The platform posts, stores the new state and sets an
     * inexact alarm for [GovernorResult.nextWakeMs].
     */
    suspend fun governNotifications(state: String?, device: DeviceAlerts): GovernorResult = onCore {
        refresh()
        Governor.evaluate(currentNotices(), notifyPrefs.settings(), device, GovernorState.decode(state), nowMs(), ZoneCalendar(timeZone))
    }

    /**
     * What this device actually posted from the last [governNotifications] (leave out anything the platform couldn't
     * post). Call it every time the device could post, even with nothing, so the weekly review's Interruptions count
     * (ADR-013) starts the first time a device can notify you. Counts only, synced; never titles or text.
     */
    suspend fun notificationsPosted(posted: List<Notice>) = onCore {
        interruptions.record(posted, nowMs())
        // The activity log keeps what reached you, with why (V1); counts above stay text-free (ADR-013).
        activity.recordPosted(posted)
        activityChanged()
    }

    /** The digest from the last [governNotifications] went out on this device: it goes in the activity log. */
    suspend fun digestPosted(digest: os.meka.core.domain.Digest) = onCore {
        activity.recordDigest(digest)
        activityChanged()
    }

    /**
     * Same as [notificationsPosted], by notice key: lets Swift report what it posted with plain strings (Sendable).
     * [DIGEST_KEY] among [keys] means the digest went out too.
     */
    suspend fun notificationsPostedKeys(result: GovernorResult, keys: List<String>) {
        notificationsPosted(result.post.filter { it.key in keys.toSet() })
        val digest = result.digest
        if (digest != null && DIGEST_KEY in keys) digestPosted(digest)
    }

    // ---- Activity log ----

    /**
     * Undoes what activity entry [id] changed (only fields still as MEKA left them); returns what happened, in words
     * ("Undone", "Partly undone: …"). Syncs like any edit.
     */
    suspend fun undoActivity(id: String): String = onCore {
        val kind = trustKindOfEntry(id)
        val outcome = activity.undo(id)
        // Earned trust: undoing something MEKA did on its own sends that kind back to asking first.
        if (kind != null && outcome != os.meka.core.domain.UndoOutcome.NOT_UNDOABLE) trust.autoUndone(kind)
        refresh()
        activityChanged()
        if (kind != null && outcome != os.meka.core.domain.UndoOutcome.NOT_UNDOABLE) os.meka.core.domain.TrustRules.autoUndoneLine(kind, outcome)
        else outcome.line
    }

    private fun trustKindOfEntry(id: String): os.meka.core.domain.TrustKind? {
        val source = activity.items().firstOrNull { it.id == id }?.source ?: return null
        if (!source.startsWith("trust:")) return null
        return os.meka.core.domain.TrustKind.of(source.removePrefix("trust:"))
    }

    /**
     * Earned trust: the Ask answer's Undo on something MEKA did on its own ([os.meka.core.domain.AskAutoDone]): puts
     * it back like Activity's Undo, and that kind goes back to asking first. Returns the undo bar's line.
     */
    suspend fun undoAutoAsk(activityId: String): String = undoActivity(activityId)

    /**
     * Earned trust: Meka's answer to "want me to just do these?" ([os.meka.core.domain.TrustOffer]). Yes: cards of that
     * kind run on their own from his next question (only questions that carried nothing untrusted); no: MEKA keeps
     * asking and doesn't offer again. Returns the line to show. Synced, so it holds on every device.
     */
    suspend fun answerTrustOffer(kindKey: String, yes: Boolean): String = onCore {
        val kind = os.meka.core.domain.TrustKind.of(kindKey) ?: return@onCore "That can't be changed"
        trust.answer(kind, yes)
        os.meka.core.domain.TrustRules.answerLine(kind, yes)
    }

    // ---- What MEKA may do (V1, policy engine UI, slice 1; Ask → More → What MEKA may do) ----

    /**
     * The kill switch: while [paused], nothing runs on its own (the policy engine's `killSwitch`), so every
     * earned-trust kind waits for a tap; trust is kept for Resume. Synced. Returns the undo bar's line, or null when it
     * already was.
     */
    suspend fun setAutonomyPaused(paused: Boolean): String? = onCore {
        if (!autonomy.setPaused(paused)) return@onCore null
        os.meka.core.domain.AutonomyRules.pausedLine(paused)
    }

    /**
     * A row's action on the page: a trusted kind goes back to asking first ("Ask first"), a "keep asking" may be
     * offered again ("Let MEKA offer again"). Either way trust is earned again from nothing. Returns the line, or null
     * when the row offers nothing.
     */
    suspend fun resetTrust(kindKey: String): String? = onCore {
        val kind = os.meka.core.domain.TrustKind.of(kindKey) ?: return@onCore null
        val before = trust.reset(kind) ?: return@onCore null
        os.meka.core.domain.AutonomyRules.resetLine(kind, before)
    }

    // ---- Work mode ----

    /** The Work switch. Choosing what the schedule already says returns to the schedule. */
    suspend fun setWorkSwitch(on: Boolean) = onCore { work.setSwitch(on, localClock(), todayEpochDay()) }
    suspend fun workBackToSchedule() = onCore { work.backToSchedule() }

    /** The call assistant's one switch (synced; the Fold screens calls during work while it is on). */
    suspend fun setCallAssistant(on: Boolean) = onCore { work.setCallAssistant(on); refresh() }

    /**
     * "Keep callers' recordings" (call assistant polish 8c): 7 or 30 days, or 0 for don't keep. Synced; MEKA's server
     * deletes what is older at once and stops offering it, and the summary's ▶ Play follows. Other values are ignored.
     */
    suspend fun setRecordingDays(days: Int) = onCore {
        if (days in os.meka.core.domain.VoiceRecordingRules.KEEP_CHOICES && work.setRecordingDays(days)) refresh()
    }

    // ---- Spam call protection (call assistant polish 8b) ----

    /**
     * Puts [number] on the block list (synced; the Fold rejects its calls silently, any time). [why] is a short note
     * shown with it. Returns false, saving nothing, when it can't be a phone number.
     */
    suspend fun blockCaller(number: String, why: String?): Boolean = onCore { blockList.block(number, why).also { refresh() } }

    /**
     * "Block" on a held message from someone nobody knows (call assistant polish 8b b): puts [person]'s
     * [os.meka.core.domain.PersonSummary.blockNumber] on the list with why ("Left a message · today"). False, saving
     * nothing, for a known caller (no block number).
     */
    suspend fun blockHeldCaller(person: os.meka.core.domain.PersonSummary): Boolean = onCore {
        val number = person.blockNumber ?: return@onCore false
        val item = person.items.last { it.callerNumber != null }
        val why = os.meka.core.domain.BlockedCallerRules.whyFromHeld(item.kind, item.atMs, nowMs(), ZoneCalendar(timeZone))
        blockList.block(number, why).also { refresh() }
    }

    /** Takes a number ([key], from [blockedCallers]' rows) off the block list; its calls ring again. */
    suspend fun unblockCaller(key: String): Boolean = onCore { blockList.unblock(key).also { refresh() } }

    /**
     * Block on a Suspected spam row ([blockedCallers]' `suspects`; call assistant polish 8b c): the number goes on the
     * block list with the AI's reason. False when [key] isn't a suspect any more.
     */
    suspend fun confirmSuspectedCaller(key: String): Boolean = onCore { blockList.confirmSuspect(key).also { refresh() } }

    /** Not spam on a Suspected spam row: its calls are screened as before and MEKA never flags the number again. */
    suspend fun dismissSuspectedCaller(key: String): Boolean = onCore { blockList.dismissSuspect(key).also { refresh() } }

    /** The Fold, the first time it screens calls: puts the 9 Oct scam number on the list unless it was ever there. */
    suspend fun seedBlockList(): Boolean = onCore { blockList.seed().also { if (it) refresh() } }

    /**
     * "Unknown caller · Block?" (call assistant polish 8b b): the Fold, a minute after a call its screening marked with
     * [os.meka.core.domain.UnknownCallRules.watch], asks what to post for it — the call from [number] at [atMs] that
     * ended as [outcome] (read from the phone's own call log). Null when the number is on the block list by now or was
     * offered in the last day ([offered], the phone's own record).
     */
    suspend fun unknownCallOffer(
        number: String?,
        outcome: os.meka.core.domain.CallOutcome,
        durationS: Long,
        atMs: Long,
        offered: List<os.meka.core.domain.ScreenedCall>,
    ): os.meka.core.domain.UnknownCallOffer? = onCore {
        os.meka.core.domain.UnknownCallRules.offer(
            number, outcome, durationS, atMs, nowMs(), blockList.view().keys, offered, ZoneCalendar(timeZone),
        )
    }

    /**
     * The Fold's call screening asks this about every incoming call: the call assistant's switch, work mode, quiet
     * hours (filled in here), the block list and Suspected spam decide, with what the phone knows about the caller ([signals]: contacts,
     * recent calls, the network's caller check; never sent anywhere). A call spam protection stopped goes in Activity.
     */
    suspend fun screenIncomingCall(
        number: String?,
        lists: os.meka.core.domain.PeopleLists,
        recent: List<os.meka.core.domain.ScreenedCall>,
        signals: os.meka.core.domain.CallSignals,
    ): os.meka.core.domain.CallDecision = onCore {
        val state = work.state(localClock(), todayEpochDay())
        val now = nowMs()
        val quiet = notifyPrefs.settings().quiet.isQuietAt(now, ZoneCalendar(timeZone))
        val list = blockList.view()
        val decision = os.meka.core.domain.CallScreeningRules.decide(
            state.callAssistant, state.atWork, number, lists, recent, now, list.keys, signals.copy(quietHours = quiet), list.suspectedKeys,
            paused = callCredit.current()?.paused == true,
        )
        os.meka.core.domain.CallScreeningRules.activityLine(decision, number)?.let { (summary, why) ->
            activity.recordScreened(decision.callerKey, now, summary, why)
            activityChanged()
        }
        decision
    }

    /**
     * Where the forecast is for (Weather place setting, synced): a town name; blank or "Biggleswade" is home. Returns
     * false, saving nothing, for a name that can't be a place. The server follows it at its next poll (a few minutes);
     * [weatherView]'s `placeChoice` says when it has.
     */
    suspend fun setWeatherPlace(name: String): Boolean = onCore { weatherPlace.set(name).also { refresh() } }

    /**
     * Where work is (Places item 2, synced): a town or district name; blank or "Canary Wharf" is the default. Returns
     * false, saving nothing, for a name that can't be a place. The server forecasts it at its next poll; on office days
     * Today's weather line and the brief say both places, and the Day ring's rain follows where Meka will be.
     * [weatherView]'s `workChoice` says when the server has followed.
     */
    suspend fun setWorkPlace(name: String): Boolean = onCore { workPlace.set(name).also { refresh() } }

    /**
     * Work hours: the work days and the usual hours. [days] are ISO (1 = Monday); minutes are local minutes of the
     * day. Each day's own hours ([setWorkDayHours]) are kept.
     */
    suspend fun setWorkSchedule(days: List<Int>, startMinute: Int, endMinute: Int, enabled: Boolean) =
        onCore { work.setSchedule(WorkSchedule(days.toSet(), startMinute, endMinute, enabled, work.schedule().dayHours)) }

    /** [isoDay]'s own hours (Thursday's short day); the usual hours clear them. */
    suspend fun setWorkDayHours(isoDay: Int, startMinute: Int, endMinute: Int) =
        onCore { work.setSchedule(work.schedule().withDayHours(isoDay, os.meka.core.domain.DayHours(startMinute, endMinute))) }

    /** [isoDay] back to the usual hours. */
    suspend fun clearWorkDayHours(isoDay: Int) =
        onCore { work.setSchedule(work.schedule().withDayHours(isoDay, null)) }

    /** Fresh work-mode state for background callers (the notification listener), not waiting for a [tick]. */
    suspend fun currentWorkMode(): WorkModeState = onCore {
        work.state(localClock(), todayEpochDay())
            .let { it.copy(callAssistantPaused = it.callAssistant && callCredit.current()?.paused == true) }
            .also { _workMode.value = it }
    }

    /** Re-evaluates everything that depends on the clock (work mode, Today). Cheap; call it about once a minute. */
    suspend fun tick() = onCore { refresh() }

    /**
     * The bedside clock for the half-folded Fold (Fold modes): the time, the phone's next alarm ([nextAlarmMs], from the
     * platform; null when none is set) and one short section for this part of the day, read from the current views.
     * Pure and cheap; the Fold calls it every few seconds while it stands half folded. Nothing is stored.
     */
    fun bedside(nextAlarmMs: Long?): os.meka.core.domain.BedsideView =
        os.meka.core.domain.FoldModeRules.bedside(
            nowMs(), ZoneCalendar(timeZone), nextAlarmMs, _notifySettings.value.quiet, _today.value, _brief.value, _shutdown.value,
        )

    /**
     * The "now" card (Fold modes, slice 3): the one thing that matters now (an event or booked session starting or just
     * started, a session asking "Did you go?", Up next, or clear) with its one-tap actions, for the closed Fold's cover screen and the Mac's menu bar. Pure and
     * cheap, read from the current Today (which refreshes every minute). Nothing is stored.
     */
    fun coverNow(): os.meka.core.domain.NowView =
        os.meka.core.domain.CoverNowRules.now(_today.value, nowMs(), ZoneCalendar(timeZone), _sessions.value.cards)

    /**
     * Up next as the closed Fold's card on every screen (Fold review 2026-10-09, item 3): "UP NEXT", the title, its line
     * and Done · Tomorrow · Open, for the open Fold's Today and the Mac's window. Null when nothing is up next. Pure.
     */
    fun upNextCard(): os.meka.core.domain.NowView? =
        os.meka.core.domain.CoverNowRules.upNext(_today.value, nowMs(), ZoneCalendar(timeZone))

    /**
     * The Galaxy Watch's one screen (Galaxy Watch, slice 2): the cover screen's "now" card with only the taps a wrist can
     * make (Done · Tomorrow, Went · Didn't go) and the fast with Start or End. Pure and cheap. Nothing is stored.
     */
    fun watchHome(): os.meka.core.domain.WatchHomeView =
        os.meka.core.domain.WatchHomeRules.view(coverNow(), _fasting.value, nowMs())

    /** A watch button: the same change as the Fold's cover screen makes for that tap. */
    suspend fun watchPress(button: os.meka.core.domain.WatchButton) {
        when (button.action) {
            os.meka.core.domain.NowAction.DONE -> complete(button.targetId)
            os.meka.core.domain.NowAction.TOMORROW -> snooze(button.targetId)
            os.meka.core.domain.NowAction.WENT -> sessionWent(button.targetId, null)
            os.meka.core.domain.NowAction.DIDNT_GO -> sessionMissed(button.targetId)
            else -> Unit
        }
    }

    /** The watch's fast button: ends the running fast, or starts the plan's daily fast now. */
    suspend fun watchFastButton() {
        if (_fasting.value.isFasting) endFast() else startFast(0)
    }

    /**
     * The watch's tile (Galaxy Watch, slice 3): Up next with its one primary button and a running fast's clock, from
     * the same "now" card as [watchHome]. Pure and cheap; nothing is stored.
     */
    fun watchTile(): os.meka.core.domain.WatchTileView = os.meka.core.domain.WatchTileRules.tile(watchHome())

    /**
     * A tap on the tile's button: [clickableId] is the tile's last clickable id. Does the same as the watch's own
     * button, but only while the watch still offers that button (a stale tile does nothing). True when it did something.
     */
    suspend fun watchTilePress(clickableId: String?): Boolean {
        val b = os.meka.core.domain.WatchTileRules.pressed(clickableId, watchHome()) ?: return false
        watchPress(b)
        return true
    }

    /**
     * Quick capture by voice on the watch (Galaxy Watch, slice 4a): what the watch's on-device recogniser heard goes in
     * as if typed into Today's capture bar ([captureTyped]: a task, or a quick alarm or timer). Null when nothing was
     * said. The watch shows the result's line with Undo ([watchCaptureUndo]).
     */
    suspend fun watchCapture(heard: String?): os.meka.core.domain.WatchCaptured? {
        val text = os.meka.core.domain.WatchCaptureRules.clean(heard) ?: return null
        return os.meka.core.domain.WatchCaptureRules.captured(captureTyped(text), text)
    }

    /** Undo on the watch after a capture: the task is deleted, or the alarm or timer cancelled, on every device. */
    suspend fun watchCaptureUndo(c: os.meka.core.domain.WatchCaptured) {
        c.taskId?.let { delete(it) }
        c.alarmId?.let { cancelAlarm(it) }
    }

    /** The watch's complication (Galaxy Watch, slice 3): a running fast's ring, else how much of today's list is done. */
    fun watchComplication(): os.meka.core.domain.WatchComplicationView {
        val t = _today.value
        return os.meka.core.domain.WatchTileRules.complication(watchHome(), t.doneToday.size, t.dayRing.toDo)
    }

    /** When the tile and complication should be drawn again with nothing synced in between. */
    fun watchRefreshAfterMs(): Long = os.meka.core.domain.WatchTileRules.refreshAfterMs(watchHome(), nowMs())

    /**
     * What "3 held for later" unfolds to in Needs you during work (Fold review 2026-10-09, item 5): sender · first line ·
     * time, newest first, at most five. [summary] is the one the device shows (the Fold passes its lists-applied copy;
     * the Mac passes [afterWork]'s). A preview only: nothing is marked read or cleared. Pure.
     */
    fun heldPreview(summary: os.meka.core.domain.AfterWorkSummary): os.meka.core.domain.HeldPreview =
        os.meka.core.domain.HeldPreviewRules.build(summary, _workMode.value.line, nowMs(), ZoneCalendar(timeZone))

    /**
     * What runs outside the app (Outside the app, slice 1): the next event's countdown from 30 minutes before it and a
     * running fast, for the Fold's ongoing notifications and the Mac's menu bar. Pure and cheap, read from the current
     * Today and fasting views; [OngoingView.nextChangeMs] says when to look again. Nothing is stored.
     */
    fun ongoing(): os.meka.core.domain.OngoingView =
        os.meka.core.domain.OngoingRules.view(_today.value, _fasting.value, nowMs(), ZoneCalendar(timeZone))

    /**
     * The home-screen widgets (Outside the app, slice 2): Next up, Needs you and Fast, read from the current views.
     * Pure and cheap; [os.meka.core.domain.HomeWidgetsView.nextChangeMs] says when to look again. Nothing is stored.
     */
    fun homeWidgets(): os.meka.core.domain.HomeWidgetsView {
        val cal = ZoneCalendar(timeZone)
        val today = _today.value
        return os.meka.core.domain.HomeWidgetRules.view(
            os.meka.core.domain.CoverNowRules.now(today, nowMs(), cal, _sessions.value.cards), today, _lists.value.dueCount,
            _needsYouStack.value, _fasting.value, nowMs(), cal,
        )
    }

    /**
     * The Fold's News home-screen widget (news ticker slice 3a): Today's ticker as flipping cards, read from the current
     * News place. Pure and cheap; [os.meka.core.domain.NewsWidgetView.nextChangeMs] says when to look again.
     */
    fun newsWidget(): os.meka.core.domain.NewsWidgetView =
        os.meka.core.domain.NewsWidgetRules.view(os.meka.core.domain.TickerRules.ticker(_newsPlace.value), nowMs())

    /**
     * The Mac's desktop News widget (news ticker slice 3b): the top three stories still (the match, then Barça and AI),
     * read from the current News place. Pure and cheap; the Mac writes it beside the widget, which says the lines itself.
     */
    fun deskNewsWidget(): os.meka.core.domain.DeskNewsView =
        os.meka.core.domain.DeskNewsWidgetRules.view(os.meka.core.domain.TickerRules.ticker(_newsPlace.value))

    suspend fun resolve(choice: ConflictChoice, chosenOption: String) = onCore {
        tasks.resolveConflict(choice.conflict, FieldValue.Text(chosenOption))
    }

    /**
     * Starts sync while the app is in use: immediately, after local edits, and whenever the server's long-poll
     * reports another device's changes (about a second). [periodMs] is the fallback cadence when long-polling is
     * unavailable, with backoff on failure. Background catch-up is the platform scheduler's job.
     */
    fun startSync(periodMs: Long = FOREGROUND_SYNC_MS) {
        if (syncClient == null || syncLoop?.isActive == true) return
        syncLoop = scope.launch {
            while (true) {
                val backoff = syncMutex.withLock { runSyncOnce() }
                if (backoff != null) { delay(backoff); continue }
                // Live: hold a long-poll open so the other device's edits arrive within about a second. The mutex
                // is not held while waiting, so local edits still push immediately.
                val started = nowMs()
                val changed = try {
                    syncClient?.awaitRemoteChanges()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // the next round reports offline and backs off
                }
                // Unsupported or failed, or an empty answer that came back implausibly fast: fall back to polling.
                if (changed == null || (!changed && nowMs() - started < 1_000)) delay(periodMs)
            }
        }
    }

    companion object {
        const val FOREGROUND_SYNC_MS: Long = 30_000L
        const val SIGNED_OUT_MESSAGE = "This device was signed out of your server. Reconnect it with the enrolment code; nothing is lost."
        /** The key a platform reports among posted keys when the digest went out ([notificationsPostedKeys]). */
        const val DIGEST_KEY = "meka.digest"
    }

    fun stopSync() { syncLoop?.cancel(); syncLoop = null }

    /** Attaches sync after enrolment (or swaps it), without restarting the app. Local data is kept and pushed. */
    suspend fun connect(transport: SyncTransport) = withContext(confined) {
        syncMutex.withLock { 
            syncClient = SyncClient(replica, transport); accountsApi = transport as? AccountsApi; releasesApi = transport as? ReleasesApi
            pushApi = transport as? PushApi; newsImagesApi = transport as? NewsImagesApi; aiApi = transport as? AiApi
            speechApi = transport as? SpeechApi; hereApi = transport as? HereApi; healthApi = transport as? HealthApi
            voiceMessageApi = transport as? VoiceMessageApi; familyApi = transport as? FamilyApi; deviceLinkApi = transport as? DeviceLinkApi
        }
        startSync()
    }

    val isConnected: Boolean get() = syncClient != null

    /** Begins connecting a calendar account ("google" | "microsoft"); the app opens the returned URL in a browser. */
    suspend fun startConnect(provider: String): ConnectStart = startConnect(provider, editing = false)

    /**
     * Calendar editing: [editing] asks the provider for permission to add and change events as well (Allow editing,
     * or Reconnect on an account that had it). The server records whether it was actually granted.
     */
    suspend fun startConnect(provider: String, editing: Boolean): ConnectStart =
        accountsApi?.startConnect(provider, editing) ?: ConnectStart.Failed("Connect this device to your server first.")

    /**
     * Stop editing on one account: the server stops changing it at once. Returns the accounts as they are now, or null
     * when it couldn't be reached (nothing changed; the screen says so).
     */
    suspend fun stopCalendarEditing(provider: String, email: String): List<ConnectedAccount>? {
        val now = try { accountsApi?.stopEditing(provider, email) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        now?.let(::rememberEditing)
        return now
    }

    /** Accounts connected for this household, for the Calendars screen. Empty when offline or not connected. */
    suspend fun connectedAccounts(): List<ConnectedAccount> {
        val list = try { accountsApi?.accounts() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        // Offline keeps what was known: the server checks again before sending anything.
        list?.let(::rememberEditing)
        val accounts = list ?: return emptyList()
        // Each signed-in account is titled like its main calendar, with Meka's own name for it when he gave one.
        val names = onCore { eventActions.calendarNames() }
        // Whether each account's events come through (Meka's 10:48 screenshots): every mirrored event, hidden ones too.
        val mirrored = onCore { events.all() }
        val now = nowMs()
        return accounts.map {
            it.copy(
                title = os.meka.core.domain.CalendarAccountRules.title(it.provider, it.email, names),
                eventsLine = os.meka.core.domain.CalendarAccountRules.eventsLine(it.provider, it.email, it.status, it.lastSyncAtMs != null, mirrored, now),
            )
        }
    }

    private fun rememberEditing(accounts: List<ConnectedAccount>) {
        _editAccounts.value = accounts
            .filter { it.canEdit && it.provider in os.meka.core.domain.CalendarEditRules.WRITABLE }
            .map { os.meka.core.domain.EditAccount(it.provider, it.email) }
            .distinct()
    }

    // ---- Health (Reliability first, item 3) ----

    /** The last good sync this run (the Health screen's "last synced"). */
    private var lastSyncedMs: Long? = null
    private val _health = MutableStateFlow<os.meka.core.domain.HealthView?>(null)

    /** The Health screen as last checked ([refreshHealth]); null until checked once. Today reads its [todayLine]. */
    val healthView: StateFlow<os.meka.core.domain.HealthView?> = _health.asStateFlow()

    /**
     * Checks everything MEKA depends on ([os.meka.core.domain.HealthRules]): this device's own facts ([device]), the
     * replica, and the server's three answers (household health, the calendar list, the AI's status), asked together.
     * Today calls it on open ([force] false: at most every [os.meka.core.domain.HealthRules.TODAY_REFRESH_MS]); the
     * Health screen forces it. Never throws: an answer that didn't come is "Couldn't check".
     */
    suspend fun refreshHealth(device: os.meka.core.domain.DeviceHealth, force: Boolean = true): os.meka.core.domain.HealthView {
        val last = _health.value
        if (!force && last != null && nowMs() - last.checkedAtMs < os.meka.core.domain.HealthRules.TODAY_REFRESH_MS) {
            return refreshHealthLocal(device, last)
        }
        val connected = isConnected
        val server = fetchHealthServer()
        val facts = onCore { healthFacts(device, connected, server.server, server.accounts, server.ai) }
        val view = os.meka.core.domain.HealthRules.view(facts, ZoneCalendar(timeZone))
        _health.value = view
        return view
    }

    /** Between server checks, Today's open re-reads this device's own facts against what the server last said. */
    private suspend fun refreshHealthLocal(device: os.meka.core.domain.DeviceHealth, last: os.meka.core.domain.HealthView): os.meka.core.domain.HealthView {
        lastHealthServer ?: return last
        val facts = onCore { healthFacts(device, isConnected, lastHealthServer?.server, lastHealthServer?.accounts, lastHealthServer?.ai) }
        val view = os.meka.core.domain.HealthRules.view(facts, ZoneCalendar(timeZone)).copy(checkedAtMs = last.checkedAtMs)
        _health.value = view
        return view
    }

    private class HealthServerFacts(
        val server: os.meka.core.domain.ServerHealth?, val accounts: List<os.meka.core.domain.HealthAccount>?,
        val ai: os.meka.core.domain.AiStatusView?, val atMs: Long,
    )
    private var lastHealthServer: HealthServerFacts? = null

    /** The server's three answers (household health, the calendar list, the AI's status), asked together; kept for Health and Setup. */
    private suspend fun fetchHealthServer(): HealthServerFacts {
        val (server, accounts, ai) = kotlinx.coroutines.coroutineScope {
            val s = async { runCatchingNotCancel { healthApi?.householdHealth() } }
            val a = async { runCatchingNotCancel { accountsApi?.accounts() } }
            val i = async { if (aiApi == null) null else aiStatus().takeIf { it != os.meka.core.domain.AskRules.STATUS_UNKNOWN } }
            Triple(s.await(), a.await(), i.await())
        }
        accounts?.let(::rememberEditing)
        val names = onCore { eventActions.calendarNames() }
        val facts = HealthServerFacts(
            server?.let { os.meka.core.domain.ServerHealth(it.push, it.calls, it.speech, it.atMs, it.macs) },
            accounts?.map { os.meka.core.domain.HealthAccount(it.provider, it.email, os.meka.core.domain.CalendarAccountRules.title(it.provider, it.email, names), it.status, it.lastSyncAtMs) },
            ai, nowMs(),
        )
        onCore { lastHealthServer = facts }
        return facts
    }

    private fun lastVoiceMessageMs(): Long? = replica.entities(os.meka.core.domain.EntityTypes.HELD_MESSAGE)
        .filter { it[os.meka.core.domain.HeldMessageFields.KIND].textOrNull == os.meka.core.domain.CaptureKind.VOICE_MESSAGE.name }
        .mapNotNull { it[os.meka.core.domain.HeldMessageFields.AT].longOrNull }
        .maxOrNull()

    private fun healthFacts(
        device: os.meka.core.domain.DeviceHealth, connected: Boolean, server: os.meka.core.domain.ServerHealth?,
        accounts: List<os.meka.core.domain.HealthAccount>?, ai: os.meka.core.domain.AiStatusView?,
    ): os.meka.core.domain.HealthFacts {
        val sync = _sync.value
        val lastVoice = lastVoiceMessageMs()
        return os.meka.core.domain.HealthFacts(
            device = device, connected = connected, lastSyncedMs = lastSyncedMs,
            syncTrouble = when (sync) {
                is SyncStatus.Failing -> sync.reason
                is SyncStatus.Offline -> if (sync.pending > 0) "Offline · ${sync.pending} change${if (sync.pending == 1) "" else "s"} waiting" else "Offline"
                else -> null
            },
            syncFailing = sync is SyncStatus.Failing,
            server = server, accounts = accounts, signIns = signIns.all(), ai = ai,
            callAssistantOn = _workMode.value.callAssistant, lastVoiceMessageMs = lastVoice, nowMs = nowMs(),
            callCredit = callCredit.current(),
        )
    }

    // ---- Setup checklist (Meka approved 2026-10-09) ----

    private val _setup = MutableStateFlow<os.meka.core.domain.SetupView?>(null)

    /** The Setup page as last checked ([refreshSetup]); null until checked once. Today's card reads its todayLine. */
    val setupView: StateFlow<os.meka.core.domain.SetupView?> = _setup.asStateFlow()

    /**
     * Works out the Setup checklist ([os.meka.core.domain.SetupRules]) from this device's own facts ([device]), the
     * replica and the server's answers (shared with Health). [force] false (Today's open) reuses the server's answers
     * for up to [os.meka.core.domain.HealthRules.TODAY_REFRESH_MS]; the Setup page forces a fresh ask. Never throws.
     */
    suspend fun refreshSetup(device: os.meka.core.domain.SetupDevice, force: Boolean = true): os.meka.core.domain.SetupView {
        val cached = onCore { lastHealthServer }
        val server = if (!force && cached != null && nowMs() - cached.atMs < os.meka.core.domain.HealthRules.TODAY_REFRESH_MS) cached
        else fetchHealthServer()
        val connected = isConnected
        val facts = onCore {
            os.meka.core.domain.SetupFacts(
                device = device, connected = connected, server = server.server, accounts = server.accounts,
                signIns = signIns.all(), ai = server.ai, callAssistantOn = _workMode.value.callAssistant,
                voiceMessageSeen = lastVoiceMessageMs() != null, voiceChosen = mekaVoice.chosen(),
                homePlace = weatherPlace.wanted() ?: os.meka.core.domain.WeatherPlaceRules.HOME,
                workPlace = workPlace.wanted() ?: os.meka.core.domain.PlacesRules.WORK,
                nowMs = nowMs(),
            )
        }
        val view = os.meka.core.domain.SetupRules.view(facts)
        _setup.value = view
        return view
    }

    private suspend fun <T> runCatchingNotCancel(block: suspend () -> T): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    // ---- Calendar editing: Add event (slice 2b) ----

    /** Reads which accounts allow editing (the Calendar screen asks when it appears). */
    suspend fun refreshCalendarAccounts(): List<os.meka.core.domain.EditAccount> {
        connectedAccounts()
        return _editAccounts.value
    }

    /**
     * A fresh Add event sheet on local epoch day [day] (today when negative or earlier): the next quarter hour, an
     * hour long, on the account Meka last added to while it can still edit.
     */
    suspend fun addEventForm(day: Long): os.meka.core.domain.AddEventForm = onCore {
        val cal = ZoneCalendar(timeZone)
        os.meka.core.domain.AddEventRules.start(
            todayEpochDay(), cal.minuteOfDay(nowMs()), day.takeIf { it >= 0 }, _editAccounts.value,
            os.meka.core.domain.AddEventRules.lastUsedKey(calendarEdits.all()),
        )
    }

    /** The sheet as shown for [form] (pure): chips, times, lengths, accounts, and whether Add can go. */
    fun addEventView(form: os.meka.core.domain.AddEventForm): os.meka.core.domain.AddEventView =
        os.meka.core.domain.AddEventRules.view(form, _editAccounts.value, nowMs(), ZoneCalendar(timeZone))

    /**
     * Add: the event becomes a synced edit that waits five seconds for [undoEventEdit], then the server adds it to
     * Google/Outlook. Refused in words when it can't be written ("Give it a title", "Editing isn't allowed …").
     */
    suspend fun addEvent(form: os.meka.core.domain.AddEventForm): os.meka.core.domain.EventEditResult = onCore {
        val account = os.meka.core.domain.AddEventRules.account(form, _editAccounts.value)
        if (account == null) os.meka.core.domain.EventEditResult.Refused("Allow editing on an account in Calendars first")
        else calendarEdits.add(account.provider, account.email, os.meka.core.domain.AddEventRules.draft(form, ZoneCalendar(timeZone)))
    }

    /** Undo inside the five seconds: nothing is sent. False once it may be on its way. */
    suspend fun undoEventEdit(id: String): Boolean = onCore { calendarEdits.undo(id) }

    // ---- Calendar editing: change, move and delete from the event detail (slice 2c) ----

    /** The Edit form filled in from [event] (its own account; the time as it is). */
    fun editEventForm(event: os.meka.core.domain.CalendarEvent): os.meka.core.domain.AddEventForm =
        os.meka.core.domain.EditEventRules.start(event, nowMs(), ZoneCalendar(timeZone))

    /** The Edit sheet as shown for [form] (pure): the Add sheet's rows, "Save to Google", and whether Save can go. */
    fun editEventView(event: os.meka.core.domain.CalendarEvent, form: os.meka.core.domain.AddEventForm): os.meka.core.domain.AddEventView =
        os.meka.core.domain.EditEventRules.view(form, event, canEditEvent(event), nowMs(), ZoneCalendar(timeZone))

    /**
     * Save: only what changed becomes a synced edit (a new time alone is a move) that waits five seconds for
     * [undoEventEdit]; the server then checks it against the provider's copy and sends it. Refused in words.
     */
    suspend fun saveEventEdit(
        event: os.meka.core.domain.CalendarEvent, form: os.meka.core.domain.AddEventForm,
    ): os.meka.core.domain.EventEditResult = onCore {
        calendarEdits.change(event, os.meka.core.domain.EditEventRules.draft(form, event, nowMs(), ZoneCalendar(timeZone)))
    }

    /**
     * Delete [event] from its calendar after the five-second Undo. [guestsOk] is Meka's second tap after the server
     * said it cancels the event for its guests (Delete anyway); never set otherwise.
     */
    suspend fun deleteEvent(event: os.meka.core.domain.CalendarEvent, guestsOk: Boolean): os.meka.core.domain.EventEditResult =
        onCore { calendarEdits.delete(event, guestsOk) }

    /**
     * Clash chooser (slice 2c-iii), Keep mine: the clashed edit [clashId] is sent again against Google's version, with
     * five seconds' Undo (undoing it asks the clash again). Refused in words.
     */
    suspend fun keepMyVersion(clashId: String): os.meka.core.domain.EventEditResult = onCore { calendarEdits.keepMine(clashId) }

    /** Clash chooser, Keep theirs: nothing is sent and Google's version stays. False when it was already answered. */
    suspend fun keepTheirVersion(clashId: String): Boolean = onCore { calendarEdits.keepTheirs(clashId) }

    /** The undo bar's line for a delete ("Deleting “Dentist” from Google"). */
    fun deletingLine(event: os.meka.core.domain.CalendarEvent): String = os.meka.core.domain.EditEventRules.deletingLine(event)

    /** The edit's line as it stands ("Adding “Dentist” to Google"), for the undo bar. */
    fun eventEditLine(id: String): String? = _editLines.value.firstOrNull { it.id == id }?.text

    // ---- News pictures (news, images slice) ----

    private val newsImageCache = NewsImageCache()

    /**
     * The picture of a story ([os.meka.core.domain.NewsItem.imageKey]) from this household's server: a small JPEG, or
     * null when there is none, offline or not connected (the app shows the source's tile). Pictures are kept in memory
     * for the session ([NewsImageCache]); a key that isn't a server key is never sent.
     */
    suspend fun newsImage(key: String): ByteArray? {
        if (!os.meka.core.domain.NewsRules.isImageKey(key)) return null
        newsImageCache.get(key)?.let { return it }
        if (newsImageCache.missedRecently(key, nowMs())) return null
        val api = newsImagesApi ?: return null
        val bytes = try { api.newsImage(key) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (bytes == null || bytes.isEmpty() || bytes.size > NewsImageCache.MAX_BYTES) { newsImageCache.missed(key, nowMs()); return null }
        newsImageCache.put(key, bytes)
        return bytes
    }

    /** [newsImage] as base64, for the Mac (Swift turns it into `Data` without copying byte by byte). */
    suspend fun newsImageBase64(key: String): String? =
        newsImage(key)?.let { kotlin.io.encoding.Base64.encode(it) }

    // ---- Callers' recordings (call assistant polish 8c) ----

    /**
     * A held voice message's recording as MP3, fetched from this household's server over a signed request; null when
     * the message isn't in the summary, has no recording kept, or the server can't be reached. Never cached or saved:
     * the app plays it from memory and lets it go.
     */
    suspend fun voiceMessageAudio(id: String): ByteArray? {
        if (!os.meka.core.domain.VoiceRecordingRules.isHeldId(id)) return null
        if (_afterWork.value.people.none { p -> p.items.any { it.id == id && it.hasAudio } }) return null
        val api = voiceMessageApi ?: return null
        val bytes = try { api.voiceMessageAudio(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        return bytes?.takeIf { it.isNotEmpty() && it.size <= os.meka.core.domain.VoiceRecordingRules.MAX_BYTES }
    }

    /** [voiceMessageAudio] as base64, for the Mac (Swift turns it into `Data`). */
    suspend fun voiceMessageAudioBase64(id: String): String? =
        voiceMessageAudio(id)?.let { kotlin.io.encoding.Base64.encode(it) }

    /**
     * What Talk plays for "play my messages" (polish 8c): the voice messages in [summary] (the one the device shows;
     * the Fold passes its contacts-named copy), urgent first then oldest first, each introduced in MEKA's words and
     * then its recording ([voiceMessageAudio]) or its words. Pure; nothing is marked or cleared.
     */
    fun voicePlaylistOf(summary: os.meka.core.domain.AfterWorkSummary): os.meka.core.domain.VoicePlaylist =
        os.meka.core.domain.VoicePlaylistRules.build(summary, nowMs(), ZoneCalendar(timeZone))

    /** [voicePlaylistOf] the synced summary as it stands (the Mac's). */
    fun voicePlaylist(): os.meka.core.domain.VoicePlaylist = voicePlaylistOf(_afterWork.value)

    /**
     * The words for a read-out in Talk ("read my brief", "the headlines", "Barça news";
     * [os.meka.core.domain.ReadOutRules]): from the brief and the News place this device shows now. Plain text to be
     * said as a reading; nothing in it is ever acted on. Pure and cheap (no network), so the Mac calls it on the main
     * actor.
     */
    fun talkReadOut(read: os.meka.core.domain.ReadOut): String =
        os.meka.core.domain.ReadOutRules.text(read, _brief.value, _newsPlace.value)

    // ---- Ask MEKA (build plan V1, AI layer slice 3; ADR-006) ----

    /**
     * Asks MEKA [question] with a short picture of today ([os.meka.core.domain.AskRules.context]: tasks by handles
     * that stay on this device, no ids or notes). The server asks its small model once, with no tools; what comes back
     * is words and at most three cards, each checked here ([os.meka.core.domain.AskRules.card]) and by the policy
     * engine as a suggestion. A card does nothing until Meka taps it ([doAsk]). Never throws for a missing answer:
     * AI off, the month's budget spent, offline and failures come back as [os.meka.core.domain.AskOutcome.Unavailable].
     */
    suspend fun askMeka(question: String): os.meka.core.domain.AskOutcome = ask(question, emptyList(), voice = false)

    /**
     * Talk to MEKA (build plan V1, slice 1): asks [question] like [askMeka], with the conversation so far ([history],
     * oldest first; only the last [os.meka.core.domain.TalkRules.MAX_HISTORY] are sent) so "move it to Friday" means
     * something, and asks for an answer that reads well aloud. The cards are checked exactly as for typed questions;
     * a spoken yes runs them through [doAsk] like a tap ([os.meka.core.domain.TalkFlow]).
     */
    suspend fun talk(question: String, history: List<os.meka.core.domain.TalkTurn>): os.meka.core.domain.AskOutcome =
        ask(question, history.takeLast(os.meka.core.domain.TalkRules.MAX_HISTORY), voice = true)

    private suspend fun ask(question: String, history: List<os.meka.core.domain.TalkTurn>, voice: Boolean): os.meka.core.domain.AskOutcome {
        val q = os.meka.core.domain.AskRules.question(question)
            ?: return os.meka.core.domain.AskOutcome.Unavailable("Ask something first")
        val api = aiApi ?: return os.meka.core.domain.AskOutcome.Unavailable(os.meka.core.domain.AskRules.NOT_CONNECTED_LINE)
        val cal = ZoneCalendar(timeZone)
        val context = onCore {
            // Work's lines (Places item 2) are kept: home's hourly lines make room for them.
            val workLines = os.meka.core.domain.PlacesRules.workAskLines(workWeather.forecast(), nowMs(), cal)
            // Where Meka is (Places item 3): only for a question about "here", from a fix taken in the last 30 minutes.
            val hereLines = os.meka.core.domain.HereRules.askLinesFor(q, hereFix, nowMs(), cal)
            // The route's train lines (Places item 4): one line while TfL's status is fresh.
            val routeLines = os.meka.core.domain.RouteRules.askLines(lineStatus.snapshot(), nowMs(), cal)
            os.meka.core.domain.AskRules.context(_today.value, nowMs(), cal,
                os.meka.core.domain.WeatherRules.askLines(weather.forecast(), nowMs(), cal)
                    .take(os.meka.core.domain.WeatherRules.MAX_ASK_LINES - workLines.size - hereLines.size - routeLines.size) +
                    hereLines + workLines + routeLines,
                shopping.view(),
                // School rhythm, slice 2b: the school year, so "when's half term?" has an answer.
                os.meka.core.domain.SchoolRules.askLines(school.items(), cal.epochDayOf(nowMs()), bankHolidays.calendar()),
                // Meal plan, slice 2: the week's dinners and the favourites, so "what's for dinner tonight?" has an answer.
                os.meka.core.domain.MealRules.askLine(mealPlan.view(emptySet())),
                mealPlan.meals().any { os.meka.core.domain.ShoppingRules.byName(it.by) != null },
                // Date night, slice 3: the coming nights, so "when's date night?" has an answer.
                os.meka.core.domain.DateNightRules.askLine(dateNight.setting(), cal.epochDayOf(nowMs()), cal.minuteOfDay(nowMs())),
                // Home upkeep, slice 2: the renewals radar, so "when's the boiler service?" has an answer.
                os.meka.core.domain.RenewalAskRules.askLines(renewals.view(), cal.epochDayOf(nowMs())),
                // Work wins, slice 2: the log, so "show my wins this quarter" has an answer.
                os.meka.core.domain.WinAskRules.askLines(wins.all(), cal.epochDayOf(nowMs())),
                // Train-time queue, slice 3: the saved links, so "what's in my train queue?" has an answer.
                os.meka.core.domain.QueueAskRules.askLines(trainQueue.items(), nowMs(), cal),
                // Annual leave, slice 2b: the planner as the pane shows it, so "how many days have I got left?" has an answer.
                os.meka.core.domain.LeaveAskRules.askLines(_leave.value, work.schedule().enabled, q),
                // What MEKA knows, slice 1: only the facts that bear on the question, so "what's the car's reg?" has an answer.
                os.meka.core.domain.FactAskRules.askLines(lifeFacts.all(), q),
                // Protected time, slice 4: the rules, today and the next two weeks, so "is Saturday protected?" has an answer.
                os.meka.core.domain.ProtectedRules.askLines(protectedTime.setting(), cal.epochDayOf(nowMs()), work.schedule(), bankHolidays.calendar()),
                // Errands by location, slice 2b: what waits at each place, so "what do I need at Tesco?" has an answer.
                os.meka.core.domain.ErrandAskRules.askLines(errands.view()),
                // Appointment nudges, slice 2a: each appointment with its last visit, booking and when it's due.
                os.meka.core.domain.AppointmentAskRules.askLines(appointments.view()),
                // Checklists in tasks, slice 3b: each open task's checklist, so "what's left on the party checklist?" has an answer.
                os.meka.core.domain.ChecklistAskRules.askLines(tasks.all(), cal.epochDayOf(nowMs())),
                // Goals, properly, slice 8: each open goal with its progress and next step, so "how are my goals?" has an answer.
                os.meka.core.domain.GoalAskRules.askLines(goals.view(tasks.all())),
                // Bin days, slice 2: each bin and the coming collections, so "which bin is it this week?" has an answer.
                os.meka.core.domain.BinRules.askLines(bins.items(), cal.epochDayOf(nowMs()), bankHolidays.calendar()),
                // Car on autopilot, slice 3: the DVLA's answer and the car's dates, so "is the car taxed?" has an answer.
                os.meka.core.domain.CarAskRules.askLines(os.meka.core.domain.CarRules.view(car.registration(), renewals.items(), carCheck(), cal.epochDayOf(nowMs()), cal), cal.epochDayOf(nowMs())))
        }
        val reply = try { api.ask(q, context, history.map(::sendable), voice) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return os.meka.core.domain.AskOutcome.Unavailable(os.meka.core.domain.AskRules.OFFLINE_LINE)
        }
        return when (reply) {
            is AskReply.Unavailable -> os.meka.core.domain.AskOutcome.Unavailable(os.meka.core.domain.AskRules.unavailableLine(reply.state, reply.reason))
            is AskReply.Answered -> onCore {
                val titles = context.taskIds.values.mapNotNull { tasks.get(it) }.associate { it.id to it.title }
                val answer = os.meka.core.domain.AskRules.answer(reply.text, reply.actions, context, titles, nowMs(), cal, mealPlan.meals(), dateNight.setting(), renewals.items(), wins.all(), trainQueue.items(),
                    os.meka.core.domain.LeaveAskData(leave.items(), leaveWork(), leave.settings()), lifeFacts.all(), errands.items(), tasks.all(), goals.goals(tasks.all()).first)
                val allowed = answer.cards.filter { askAllowed(it.proposal, context.untrusted) }
                // Earned trust: a kind Meka said "just do it" to runs now, recorded in Activity with Undo, when the
                // policy engine permits Level 4 under his rule (never for a question that carried untrusted lines).
                val trusted = trust.trusted()
                val did = mutableListOf<os.meka.core.domain.AskAutoDone>()
                val cards = allowed.filter { card ->
                    val kind = os.meka.core.domain.TrustRules.kindOf(card.proposal)
                    if (kind == null || kind !in trusted || !askAuto(card.proposal, kind, context.untrusted)) return@filter true
                    val auto = try { autoAsk(card, kind) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    if (auto != null) { did += auto; false } else true
                }
                if (did.isNotEmpty()) activityChanged()
                os.meka.core.domain.AskOutcome.Answered(answer.copy(cards = cards, did = did))
            }
        }
    }

    /**
     * Does what an Ask card proposes, on Meka's tap (his own action, like any button): adds, ticks off or moves a task,
     * starts a fast, or sets a timer or alarm exactly as typing it would. Returns the undo bar's line and how to take it
     * back ([undoAsk]). Throws [os.meka.core.domain.ValidationException] when it can't be done any more (the task is
     * gone, already fasting).
     */
    suspend fun doAsk(card: os.meka.core.domain.AskCard): os.meka.core.domain.AskDone = onCore {
        val done = performAsk(card)
        // Earned trust: one more yes in a row for this kind; the offer comes with the fifth.
        val kind = os.meka.core.domain.TrustRules.kindOf(card.proposal)
        if (kind == null) done
        else {
            done.undo?.let { trustOfUndo[it] = kind }
            done.copy(offer = trust.approved(kind))
        }
    }

    /**
     * Earned trust: does [card] on its own (the same code as a tap) through the activity log, which records every
     * field it changed so Undo puts it back. Null when it changed nothing. Caller is on the core.
     */
    private fun autoAsk(card: os.meka.core.domain.AskCard, kind: os.meka.core.domain.TrustKind): os.meka.core.domain.AskAutoDone? {
        val today = ZoneCalendar(timeZone).epochDayOf(nowMs())
        val summary = os.meka.core.domain.AskRules.doneLine(card.proposal, today)
        val (_, id) = activity.actRecorded(
            summary, os.meka.core.domain.TrustRules.why(kind), os.meka.core.domain.TrustRules.source(kind),
            AutonomyLevel.AUTO_UNDER_RULE.name, detail = "Asked in MEKA",
        ) { performAsk(card) }
        return id?.let { os.meka.core.domain.AskAutoDone(card.proposal, os.meka.core.domain.TrustRules.autoLine(summary), it) }
    }

    /** What a card does, on a tap or on its own; caller is on the core. */
    private fun performAsk(card: os.meka.core.domain.AskCard): os.meka.core.domain.AskDone {
        val cal = ZoneCalendar(timeZone)
        val today = cal.epochDayOf(nowMs())
        fun openTask(id: String): os.meka.core.domain.Task =
            tasks.get(id)?.takeIf { !it.lifecycle.isTerminal } ?: throw os.meka.core.domain.ValidationException("That task isn't open any more")
        val undo: os.meka.core.domain.AskUndo? = when (val p = card.proposal) {
            is os.meka.core.domain.AskProposal.AddTask -> {
                val id = tasks.create(NewTask(p.title))
                p.day?.let { d -> tasks.setWhen(id, d, p.minute); followBlocks(listOf(id)) }
                os.meka.core.domain.AskUndo.RemoveTask(id)
            }
            is os.meka.core.domain.AskProposal.CompleteTask -> {
                val before = os.meka.core.domain.TaskTiming.of(openTask(p.taskId))
                tasks.complete(p.taskId)
                tasks.get(p.taskId)?.let { os.meka.core.domain.AskUndo.PutBack(p.taskId, before, os.meka.core.domain.TaskTiming.of(it)) }
            }
            is os.meka.core.domain.AskProposal.MoveTask -> {
                val before = os.meka.core.domain.TaskTiming.of(openTask(p.taskId))
                tasks.setWhen(p.taskId, p.day, p.minute); followBlocks(listOf(p.taskId))
                tasks.get(p.taskId)?.let { os.meka.core.domain.AskUndo.PutBack(p.taskId, before, os.meka.core.domain.TaskTiming.of(it)) }
            }
            is os.meka.core.domain.AskProposal.StartFast -> os.meka.core.domain.AskUndo.DiscardFast(fasting.startExtended(p.hours, 0))
            is os.meka.core.domain.AskProposal.Timer, is os.meka.core.domain.AskProposal.Alarm -> {
                val q = os.meka.core.domain.QuickAlarmRules.parse(os.meka.core.domain.AskRules.captureLine(p), nowMs(), cal)
                    ?: throw os.meka.core.domain.ValidationException("That can't be set")
                os.meka.core.domain.AskUndo.CancelAlarm(alarms.setQuick(q) ?: throw os.meka.core.domain.ValidationException("That can't be set"))
            }
            is os.meka.core.domain.AskProposal.AddShopping -> {
                val a = shopping.addTracked(p.items.joinToString("\n"))
                os.meka.core.domain.AskUndo.TakeBackShopping(a.created, a.revived)
            }
            is os.meka.core.domain.AskProposal.PlanDinner -> {
                val before = mealPlan.plan()[p.day]
                // The favourite by name now (it may have been added or removed since the card was made), else a new one.
                val existing = mealPlan.meals().firstOrNull { os.meka.core.domain.MealRules.key(it.title) == os.meka.core.domain.MealRules.key(p.title) }
                val created = if (existing == null) {
                    mealPlan.add(p.title) ?: throw os.meka.core.domain.ValidationException("That dinner can't be added")
                    mealPlan.meals().firstOrNull { os.meka.core.domain.MealRules.key(it.title) == os.meka.core.domain.MealRules.key(p.title) }?.id
                        ?: throw os.meka.core.domain.ValidationException("That dinner can't be added")
                } else null
                val id = existing?.id ?: created!!
                if (before != id && !mealPlan.set(p.day, id)) {
                    created?.let { mealPlan.remove(it) }
                    throw os.meka.core.domain.ValidationException("That day can't be planned any more")
                }
                os.meka.core.domain.AskUndo.PutBackDinner(p.day, before, id, created)
            }
            is os.meka.core.domain.AskProposal.SkipDateNight -> {
                // Checked again now: the night may have been skipped, or date night changed, since the card was made.
                if (!dateNight.skip(p.day, true)) throw os.meka.core.domain.ValidationException("That date night can't be skipped any more")
                os.meka.core.domain.AskUndo.KeepDateNight(p.day)
            }
            // Checked again now: only while it is still due on the card's day (Done here or on the other device moved it on).
            is os.meka.core.domain.AskProposal.RenewalDone -> os.meka.core.domain.AskUndo.PutBackRenewal(renewals.doneFromAsk(p.id, p.dueDay))
            // Kept in today's week (whatever week the review shows); the same line already there is refused.
            is os.meka.core.domain.AskProposal.AddWin -> os.meka.core.domain.AskUndo.RemoveWin(
                wins.add(p.text, os.meka.core.domain.GoalRules.weekStart(today)).id
                    ?: throw os.meka.core.domain.ValidationException(os.meka.core.domain.WinRules.ALREADY),
            )
            // Checked again now: only while the link still waits (Done here or on the other device took it off).
            is os.meka.core.domain.AskProposal.QueueDone -> {
                if (!trainQueue.setDone(p.id, true)) throw os.meka.core.domain.ValidationException(os.meka.core.domain.QueueAskRules.GONE)
                os.meka.core.domain.AskUndo.PutBackQueue(p.id)
            }
            // Checked again now: refused when its work days were booked meanwhile (here or on the other device).
            is os.meka.core.domain.AskProposal.BookLeave -> {
                val (plan, id) = leave.bookEntry(os.meka.core.domain.LeaveEntry(p.title, p.startDay, p.endDay), leaveWork())
                os.meka.core.domain.AskUndo.RemoveLeave(id ?: throw os.meka.core.domain.ValidationException(plan.line))
            }
            // Checked again now (a secret or number is still turned away; the same fact already there is refused).
            is os.meka.core.domain.AskProposal.AddFact -> {
                val (id, before) = lifeFacts.keep(p.label, p.value, p.factId)
                if (before == null) os.meka.core.domain.AskUndo.RemoveFact(id) else os.meka.core.domain.AskUndo.PutBackFact(id, before, p.value)
            }
            // The list's own add (a thing already waiting there stays one errand, a done one comes back).
            is os.meka.core.domain.AskProposal.AddErrand -> {
                val a = errands.addThings(p.place, p.things)
                if (a.created.isEmpty() && a.revived.isEmpty()) throw os.meka.core.domain.ValidationException(os.meka.core.domain.ErrandAskRules.ALREADY)
                os.meka.core.domain.AskUndo.TakeBackErrands(a.created, a.revived)
            }
            // The task's own checklist add, against the list as it is now: ticked ones come back, waiting ones stay one row.
            is os.meka.core.domain.AskProposal.AddChecklist -> {
                val task = tasks.get(p.taskId)?.takeIf { !it.lifecycle.isTerminal }
                    ?: throw os.meka.core.domain.ValidationException(os.meka.core.domain.ChecklistAskRules.GONE)
                val plan = os.meka.core.domain.ChecklistAskRules.plan(task.checklist, p.items)
                if (plan.isEmpty) throw os.meka.core.domain.ValidationException(os.meka.core.domain.ChecklistAskRules.ALREADY)
                plan.revive.forEach { tasks.setChecklistItemChecked(it, false) }
                val added = tasks.addChecklistTexts(task.id, plan.add)
                os.meka.core.domain.AskUndo.TakeBackChecklist(task.id, added, plan.revive)
            }
            // Checked again now: an open goal with the same name (added here or on the other device meanwhile) is refused.
            is os.meka.core.domain.AskProposal.AddGoal -> {
                if (os.meka.core.domain.GoalAskRules.exists(goals.goals(tasks.all()).first, p.title)) {
                    throw os.meka.core.domain.ValidationException(os.meka.core.domain.GoalAskRules.ALREADY)
                }
                os.meka.core.domain.AskUndo.RemoveGoal(goals.addGoal(p.title, horizon = p.horizon, dueDay = p.dueDay))
            }
        }
        return os.meka.core.domain.AskDone(os.meka.core.domain.AskRules.doneLine(card.proposal, today), undo)
    }

    /**
     * The undo bar's Undo after an Ask card: the added task goes, a ticked-off or moved task is put back while it is
     * still as the card left it, the fast is thrown away while it runs, the timer or alarm is cancelled. False when
     * there was nothing left to take back (changed since, here or on the other device).
     */
    suspend fun undoAsk(undo: os.meka.core.domain.AskUndo): Boolean = onCore {
        // Earned trust: taking a yes back starts that kind's run again.
        trustOfUndo.remove(undo)?.let { trust.takenBack(it) }
        when (undo) {
            is os.meka.core.domain.AskUndo.RemoveTask -> {
                if (tasks.get(undo.taskId) == null) false else { tasks.delete(undo.taskId); followBlocks(listOf(undo.taskId)); true }
            }
            is os.meka.core.domain.AskUndo.PutBack ->
                tasks.putBack(undo.taskId, undo.before, undo.after).also { if (it) followBlocks(listOf(undo.taskId)) }
            is os.meka.core.domain.AskUndo.DiscardFast -> fasting.discardIfOpen(undo.fastId)
            is os.meka.core.domain.AskUndo.CancelAlarm -> alarms.cancel(undo.alarmId)
            is os.meka.core.domain.AskUndo.TakeBackShopping -> shopping.takeBack(undo.added, undo.revived)
            is os.meka.core.domain.AskUndo.PutBackDinner -> {
                // Only while the day still has the card's dinner (a change since, here or on the other device, wins).
                val putBack = mealPlan.plan()[undo.day] == undo.after && (
                    undo.before == undo.after ||
                        mealPlan.set(undo.day, undo.before?.takeIf { b -> mealPlan.meals().any { it.id == b } })
                    )
                // The favourite the card made goes too, unless a day still has it.
                val removed = undo.createdMealId?.takeIf { it !in mealPlan.plan().values }?.let { mealPlan.remove(it) } ?: false
                putBack || removed
            }
            // Kept clear again only while it is still skipped (a change since, here or on the other device, wins).
            is os.meka.core.domain.AskUndo.KeepDateNight -> dateNight.skip(undo.day, false)
            // Put back only while it is still as Done left it (a change since, here or on the other device, wins).
            is os.meka.core.domain.AskUndo.PutBackRenewal -> renewals.undoDone(undo.before)
            // The win the card kept leaves the log (false when it was already removed, here or on the other device).
            is os.meka.core.domain.AskUndo.RemoveWin -> wins.remove(undo.winId)
            // Back to waiting only while it is still marked done (false when put back or removed meanwhile).
            is os.meka.core.domain.AskUndo.PutBackQueue -> trainQueue.setDone(undo.itemId, false)
            // The stretch the card booked comes off (false when it was already removed, here or on the other device).
            is os.meka.core.domain.AskUndo.RemoveLeave -> leave.remove(undo.leaveId)
            // The fact the card added comes off; a changed one gets its old value back while it still reads the new one.
            is os.meka.core.domain.AskUndo.RemoveFact -> lifeFacts.remove(undo.factId)
            is os.meka.core.domain.AskUndo.PutBackFact -> lifeFacts.putBack(undo.factId, undo.before, undo.after)
            // New errands leave the list, ones back from Done go back (anything ticked or removed since is left).
            is os.meka.core.domain.AskUndo.TakeBackErrands -> errands.takeBack(undo.added, undo.revived)
            // Added items still unticked come off, unticked ones are ticked again (anything ticked or removed since is left).
            is os.meka.core.domain.AskUndo.TakeBackChecklist -> {
                val waiting = tasks.get(undo.taskId)?.checklist.orEmpty().filter { !it.checked }.map { it.id }.toSet()
                var any = false
                undo.added.forEach { if (it in waiting) { tasks.deleteChecklistItem(it); any = true } }
                undo.revived.forEach { if (it in waiting) { tasks.setChecklistItemChecked(it, true); any = true } }
                any
            }
            // The goal comes off only while nothing has been added to it since (a step, a milestone, a number…).
            is os.meka.core.domain.AskUndo.RemoveGoal -> {
                val g = goals.goals(tasks.all()).first.firstOrNull { it.id == undo.goalId }
                if (g != null && os.meka.core.domain.GoalAskRules.untouched(g)) { goals.deleteGoal(g.id); true } else false
            }
        }
    }

    /**
     * Talk to MEKA (voice slice 2): a spoken yes does the confirmed [cards] one after another exactly as tapping each
     * would ([doAsk]); one that can't be done any more is counted as failed and the rest still go. Never throws for a
     * card that can't be done.
     */
    suspend fun doTalk(cards: List<os.meka.core.domain.AskCard>): os.meka.core.domain.TalkDid {
        val done = mutableListOf<os.meka.core.domain.AskProposal>()
        val lines = mutableListOf<String>()
        val undos = mutableListOf<os.meka.core.domain.AskUndo>()
        var failed = 0
        var offer: os.meka.core.domain.TrustOffer? = null
        cards.forEach { card ->
            try {
                val d = doAsk(card)
                done += card.proposal; lines += d.line; d.undo?.let { undos += it }
                if (offer == null) offer = d.offer
            } catch (e: CancellationException) { throw e } catch (e: Exception) { failed++ }
        }
        return os.meka.core.domain.TalkDid(done, lines, undos, failed, offer)
    }

    /** The undo bar's Undo after a spoken yes: takes each change back, newest first. True when anything was undone. */
    suspend fun undoTalk(undos: List<os.meka.core.domain.AskUndo>): Boolean {
        var any = false
        undos.asReversed().forEach { if (undoAsk(it)) any = true }
        return any
    }

    // ---- MEKA's voice (Weather and a voice, item 3) ----

    /** The synced "MEKA's voice": a Polly name ("Amy"), "device" for the device's own voice, or null for MEKA's default. */
    suspend fun mekaVoice(): String? = onCore { mekaVoice.chosen() }

    /** Chooses MEKA's voice on every device (null: MEKA's default). False, and nothing saved, for a name that can't be one. */
    suspend fun chooseMekaVoice(name: String?): Boolean = onCore { mekaVoice.choose(name) }

    /**
     * One piece of MEKA's own words ([os.meka.core.domain.SpeechRules.pieces]) said in MEKA's voice: the MP3 as base64,
     * or null when the device's own voice should say it — the device voice was chosen, not connected, the server has no
     * voice ("off"), the month's characters are used up ("over"), it failed, or it was slower than
     * [os.meka.core.domain.SpeechRules.FIRST_AUDIO_MS] for the [first] piece (else
     * [os.meka.core.domain.SpeechRules.NEXT_AUDIO_MS]). A refusal leaves the server alone for a while
     * ([os.meka.core.domain.SpeechRules.quietUntil]); clips are kept in memory per voice and words. Never throws.
     */
    suspend fun speechClip(text: String, first: Boolean): String? = speechClip(text, first, reading = false)

    /**
     * [speechClip] for a long [reading] (the morning brief): its first piece may take up to
     * [os.meka.core.domain.SpeechRules.READ_FIRST_AUDIO_MS] ([os.meka.core.domain.SpeechRules.firstWaitMs]).
     */
    suspend fun speechClip(text: String, first: Boolean, reading: Boolean): String? = speechClip(text, first, reading, hold = false)

    /**
     * [speechClip] that may [hold]: the request runs up to [os.meka.core.domain.SpeechRules.HOLD_AUDIO_MS] past its
     * usual wait ([os.meka.core.domain.SpeechRules.budgetMs]), while the device says
     * [os.meka.core.domain.SpeechRules.HOLD_LINE] ([speechHoldClip]) once the usual wait is over, so a late answer in
     * a conversation stays in MEKA's voice ([os.meka.core.domain.SpeechRules.holds]). Each request's time to answer is
     * kept for Activity ([voiceTimingLine]). Never throws (a cancelled request is simply not counted).
     */
    suspend fun speechClip(text: String, first: Boolean, reading: Boolean, hold: Boolean): String? {
        val api = speechApi ?: return null
        val words = text.trim().takeIf { it.isNotEmpty() && it.length <= os.meka.core.domain.SpeechRules.MAX_PIECE } ?: return null
        val voice = onCore { speechVoiceNow() } ?: return null
        onCore { speechCached(voice.name, words) }?.let { return it }
        val wait = os.meka.core.domain.SpeechRules.budgetMs(first, reading, hold)
        val started = nowMs()
        val r = withTimeoutOrNull(wait) {
            try { api.speak(words, voice.name) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                os.meka.core.wire.SpeechCodec.Response(os.meka.core.wire.SpeechCodec.Response.FAILED)
            }
        }
        if (r == null) { // slow this time: the device speaks, and the next line asks again
            onCore { speechTimed(started, wait, os.meka.core.domain.SpeechTiming.Outcome.LATE) }
            return null
        }
        return onCore {
            val audio = r.audio
            if (r.state == os.meka.core.wire.SpeechCodec.Response.SPOKEN && !audio.isNullOrEmpty()) {
                speechTimed(started, nowMs() - started, os.meka.core.domain.SpeechTiming.Outcome.SPOKEN)
                speechClips[os.meka.core.domain.SpeechRules.cacheKey(voice.name, words)] = audio
                while (speechClips.size > os.meka.core.domain.SpeechRules.CACHE_CLIPS) speechClips.remove(speechClips.keys.first())
                audio
            } else {
                speechTimed(started, nowMs() - started, os.meka.core.domain.SpeechTiming.Outcome.FAILED)
                os.meka.core.domain.SpeechRules.quietUntil(r.state, nowMs())?.let { speechQuietUntilMs = it }
                null
            }
        }
    }

    /**
     * [os.meka.core.domain.SpeechRules.HOLD_LINE] in MEKA's voice, only when it is already on the device (fetched with
     * the common lines by [warmVoice]); null otherwise, and the device then waits without a word. Never asks the server.
     */
    suspend fun speechHoldClip(): String? = onCore {
        val voice = speechVoiceNow() ?: return@onCore null
        speechCached(voice.name, os.meka.core.domain.SpeechRules.HOLD_LINE)
    }

    /**
     * Activity's line about how quickly MEKA's voice answered on this device lately
     * ([os.meka.core.domain.SpeechRules.timingLine]: "Time to MEKA's voice · 1.8 s · 0.9 s · late"); null before the
     * first clip. Kept in memory only, never sent.
     */
    // ---- Where I am now (build plan "Places…", item 3) ----

    /**
     * Whether the app may ask the phone for an approximate fix now ([os.meka.core.domain.HereRules.shouldLocate]): the
     * switch is on, the phone allows it, and a question is about "here" or Today is opening ([question] null), with no
     * fix from the last 30 minutes.
     */
    suspend fun hereShouldLocate(on: Boolean, permitted: Boolean, question: String?): Boolean = onCore {
        os.meka.core.domain.HereRules.shouldLocate(on, permitted, question, hereFix?.atMs, nowMs())
    }

    /**
     * Asks the server for the forecast where Meka is. The point is rounded to about 1 km here, before anything is sent
     * ([os.meka.core.domain.HereRules.round]); the answer is kept in memory for 30 minutes (Today's line reads "Near
     * you · …" while it is away from home and work; Ask hears it for a question about here). Never stored, never synced.
     * Returns true when a forecast came back. Never throws for a missing answer (offline, no route, a refusal).
     */
    suspend fun hereWeather(lat: Double, lon: Double): Boolean {
        val point = os.meka.core.domain.HereRules.round(lat, lon) ?: return false
        val api = hereApi ?: return false
        val r = try { api.hereWeather(point.lat, point.lon) } catch (e: CancellationException) { throw e } catch (e: Exception) { return false }
        if (r.state != os.meka.core.wire.HereCodec.Response.OK) return false
        val forecast = os.meka.core.domain.WeatherForecast(
            os.meka.core.domain.HereRules.NEAR_YOU,
            os.meka.core.domain.WeatherCodec.decodeHours(r.hours),
            os.meka.core.domain.WeatherCodec.decodeDays(r.days),
        )
        if (forecast.isEmpty) return false
        onCore {
            hereFix = os.meka.core.domain.HereFix(point, forecast, r.away, nowMs())
            refresh()
        }
        return true
    }

    /** The switch went off (or the phone took the permission back): the fix is dropped and Today shows home again. */
    suspend fun forgetHere() {
        onCore {
            if (hereFix != null) {
                hereFix = null
                refresh()
            }
            Unit
        }
    }

    // ---- Family (family sharing with Jeanette, slice 4) ----

    /**
     * Ask → More → Family: who can use the shopping list ("Jeanette can see and add to the shopping list") and each
     * link's state. Null until [refreshFamily] has run once.
     */
    val familyView: StateFlow<os.meka.core.domain.FamilyView?> = _family.asStateFlow()

    /**
     * Reads the links from MEKA's server and logs, once across both devices, the day each was opened ("Jeanette joined
     * the shopping list"). When the server can't be read the last rows stay, with the reason as the view's problem.
     */
    suspend fun refreshFamily(): os.meka.core.domain.FamilyView {
        val api = familyApi ?: return showFamily(os.meka.core.domain.FamilyRules.NOT_CONNECTED)
        val listed = try {
            api.familyInvites()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return showFamily(familyProblem(e))
        }
        return onCore {
            familyMembers = listed.map { familyMember(it) }
            for (m in familyMembers) {
                val at = m.claimedAtMs ?: continue
                activity.recordFamily(
                    os.meka.core.domain.FamilyRules.joinedId(m.id), at,
                    os.meka.core.domain.FamilyRules.joinedSummary(m.name), os.meka.core.domain.FamilyRules.WHY_JOINED,
                )
            }
            refresh()
            familyNow(null)
        }
    }

    /**
     * Makes a link for [name] ("Jeanette"): the whole address and what the share sheet sends with it. Null when it
     * couldn't be made (a name the server won't take, or the server can't be reached; [familyView]'s problem says why).
     * The address carries the link's secret, so it is handed to the share sheet once and never kept.
     */
    suspend fun inviteFamily(name: String): os.meka.core.domain.FamilyLink? {
        val clean = os.meka.core.domain.FamilyRules.validName(name) ?: run {
            showFamily("Use letters only for the name (spaces, hyphens and apostrophes are fine)"); return null
        }
        val api = familyApi ?: run { showFamily(os.meka.core.domain.FamilyRules.NOT_CONNECTED); return null }
        val (made, url) = try {
            api.createFamilyInvite(clean)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showFamily(familyProblem(e)); return null
        }
        onCore {
            familyMembers = listOf(
                os.meka.core.domain.FamilyMember(made.id, made.name, os.meka.core.domain.FamilyState.WAITING, nowMs()),
            ) + familyMembers.filter { it.id != made.id }
            activity.recordFamily(
                os.meka.core.domain.FamilyRules.invitedId(made.id), nowMs(),
                os.meka.core.domain.FamilyRules.invitedSummary(made.name), os.meka.core.domain.FamilyRules.WHY_YOU,
            )
            refresh()
            familyNow(null)
        }
        return os.meka.core.domain.FamilyRules.link(made.id, made.name, url)
    }

    /** Turns [id]'s link off at once: her page's next request is refused. False when it couldn't be (the problem says why). */
    suspend fun turnOffFamily(id: String): Boolean {
        val api = familyApi ?: run { showFamily(os.meka.core.domain.FamilyRules.NOT_CONNECTED); return false }
        val known = try {
            api.revokeFamilyInvite(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showFamily(familyProblem(e)); return false
        }
        onCore {
            val m = familyMembers.firstOrNull { it.id == id }
            familyMembers = familyMembers.map { if (it.id == id) it.copy(state = os.meka.core.domain.FamilyState.OFF) else it }
            if (known && m != null) {
                activity.recordFamily(
                    os.meka.core.domain.FamilyRules.turnedOffId(id), nowMs(),
                    os.meka.core.domain.FamilyRules.turnedOffSummary(m.name), os.meka.core.domain.FamilyRules.WHY_YOU,
                )
            }
            refresh()
            familyNow(null)
        }
        // The server's own word on every link (also when it didn't know this one).
        refreshFamily()
        return known
    }

    private fun familyMember(i: os.meka.core.wire.FamilyCodec.Invite) = os.meka.core.domain.FamilyMember(
        id = i.id, name = i.name, state = os.meka.core.domain.FamilyRules.state(i.state),
        createdAtMs = i.createdAtMs, claimedAtMs = i.claimedAtMs, lastSeenAtMs = i.lastSeenAtMs,
    )

    private fun familyProblem(e: Exception): String = when {
        e is FamilyUnavailableException && e.reason == FamilyUnavailableException.NO_KEY -> os.meka.core.domain.FamilyRules.NO_KEY
        e is FamilyUnavailableException -> os.meka.core.domain.FamilyRules.NO_ROUTE
        e is AuthRejectedException -> SIGNED_OUT_MESSAGE
        else -> os.meka.core.domain.FamilyRules.OFFLINE
    }

    private suspend fun showFamily(problem: String?): os.meka.core.domain.FamilyView = onCore { familyNow(problem) }

    /** On the core thread. */
    private fun familyNow(problem: String?): os.meka.core.domain.FamilyView =
        os.meka.core.domain.FamilyRules.view(familyMembers, nowMs(), ZoneCalendar(timeZone), problem).also { _family.value = it }

    // ---- Watch (Galaxy Watch, slice 1) ----

    /** Ask → More → Watch: the linked watches and what to do on the watch. Null until [refreshWatches] has run once. */
    val watchLinkView: StateFlow<os.meka.core.domain.WatchLinkView?> = _watchLink.asStateFlow()

    /** Reads the linked watches from MEKA's server; when it can't be read the last rows stay, with the reason. */
    suspend fun refreshWatches(): os.meka.core.domain.WatchLinkView {
        val api = deviceLinkApi ?: return showWatches(os.meka.core.domain.WatchLinkRules.NOT_CONNECTED)
        val listed = try {
            api.linkedWatches()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return showWatches(watchProblem(e))
        }
        return onCore {
            linkedWatches = listed.map { os.meka.core.domain.LinkedWatch(it.id, it.name, it.linkedAtMs) }
            watchesNow(null)
        }
    }

    /**
     * Links the watch showing [code] ("1234 5678", however it was typed): it becomes a device of this household and
     * picks up its secret by itself. False when it couldn't be linked ([watchLinkView]'s problem says why).
     */
    suspend fun linkWatch(code: String): Boolean {
        val clean = os.meka.core.domain.WatchLinkRules.normaliseCode(code) ?: run {
            showWatches(os.meka.core.domain.WatchLinkRules.NOT_A_CODE); return false
        }
        val api = deviceLinkApi ?: run { showWatches(os.meka.core.domain.WatchLinkRules.NOT_CONNECTED); return false }
        val linked = try {
            api.linkWatch(clean)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showWatches(watchProblem(e)); return false
        }
        onCore {
            linkedWatches = listOf(os.meka.core.domain.LinkedWatch(linked.deviceId, linked.name, nowMs())) +
                linkedWatches.filter { it.id != linked.deviceId }
            activity.recordDevice(
                os.meka.core.domain.WatchLinkRules.linkedId(linked.deviceId), nowMs(),
                os.meka.core.domain.WatchLinkRules.linkedSummary(linked.name), os.meka.core.domain.WatchLinkRules.WHY_YOU,
            )
            refresh()
            watchesNow(null)
        }
        return true
    }

    /** Unlinks [id] at once: the watch's next request is refused. False when it couldn't be (the problem says why). */
    suspend fun unlinkWatch(id: String): Boolean {
        val api = deviceLinkApi ?: run { showWatches(os.meka.core.domain.WatchLinkRules.NOT_CONNECTED); return false }
        val gone = try {
            api.unlinkWatch(id); true
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkRefusedException) {
            false // the server didn't know it: already unlinked
        } catch (e: Exception) {
            showWatches(watchProblem(e)); return false
        }
        onCore {
            val w = linkedWatches.firstOrNull { it.id == id }
            linkedWatches = linkedWatches.filter { it.id != id }
            if (gone && w != null) {
                activity.recordDevice(
                    os.meka.core.domain.WatchLinkRules.unlinkedId(id), nowMs(),
                    os.meka.core.domain.WatchLinkRules.unlinkedSummary(w.name), os.meka.core.domain.WatchLinkRules.WHY_YOU,
                )
            }
            refresh()
            watchesNow(null)
        }
        return true
    }

    private fun watchProblem(e: Exception): String = when {
        e is LinkRefusedException -> os.meka.core.domain.WatchLinkRules.refusal(e.reason)
        e is FamilyUnavailableException && e.reason == FamilyUnavailableException.NO_KEY -> os.meka.core.domain.WatchLinkRules.NO_KEY
        e is FamilyUnavailableException -> os.meka.core.domain.WatchLinkRules.NO_ROUTE
        e is AuthRejectedException -> SIGNED_OUT_MESSAGE
        else -> os.meka.core.domain.WatchLinkRules.OFFLINE
    }

    private suspend fun showWatches(problem: String?): os.meka.core.domain.WatchLinkView = onCore { watchesNow(problem) }

    /** On the core thread. */
    private fun watchesNow(problem: String?): os.meka.core.domain.WatchLinkView =
        os.meka.core.domain.WatchLinkRules.view(linkedWatches, nowMs(), ZoneCalendar(timeZone), problem).also { _watchLink.value = it }

    /** The settings row (Ask → More → Settings → Where I am now); the switch itself is kept on each device. */
    fun hereSetting(on: Boolean, permitted: Boolean): os.meka.core.domain.HereSettingView =
        os.meka.core.domain.HereRules.setting(on, permitted)

    suspend fun voiceTimingLine(): String? = onCore { os.meka.core.domain.SpeechRules.timingLine(speechTimings.toList()) }

    private fun speechTimed(atMs: Long, ms: Long, outcome: os.meka.core.domain.SpeechTiming.Outcome) {
        speechTimings.addLast(os.meka.core.domain.SpeechTiming(atMs, ms, outcome))
        while (speechTimings.size > os.meka.core.domain.SpeechRules.TIMINGS_KEPT) speechTimings.removeFirst()
    }

    /**
     * MEKA's voice isn't to be asked right now: not connected, the device's own voice is chosen, or the server refused
     * a moment ago and is being left alone ([os.meka.core.domain.SpeechRules.onMiss]). Never throws.
     */
    suspend fun speechResting(): Boolean = speechApi == null || onCore { speechVoiceNow() == null }

    /**
     * Fetches MEKA's common lines ([os.meka.core.domain.SpeechRules.COMMON]) once, so they play at once in a
     * conversation. Does nothing when the device's voice is chosen or the server is resting. Never throws.
     */
    suspend fun warmVoice() {
        if (speechApi == null || onCore { speechWarmed }) return
        onCore { speechWarmed = true }
        os.meka.core.domain.SpeechRules.COMMON.forEach { if (speechClip(it, first = false) == null) return }
    }

    /**
     * Activity's line about MEKA's voice this month ([os.meka.core.domain.SpeechRules.usageLine]): the voice and the
     * month's characters against the cap, from the server's `POST /v1/speech/voices`; null when the voice isn't on
     * here, the device isn't connected or the server didn't answer. Never throws.
     */
    suspend fun voiceUsageLine(): String? {
        val chosen = onCore { mekaVoice.chosen() }
        if (chosen == os.meka.core.domain.MekaVoiceRules.DEVICE) {
            return os.meka.core.domain.SpeechRules.usageLine("", null, 0, 0, null, deviceChosen = true)
        }
        val api = speechApi ?: return null
        val v = try { api.speechVoices() } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
        val voice = v.voices.firstOrNull { it.id.equals(chosen, ignoreCase = true) }?.id ?: v.defaultVoice
        return os.meka.core.domain.SpeechRules.usageLine(v.state, v.month, v.usedChars, v.capChars, voice, deviceChosen = false)
    }

    /**
     * Ask → More → MEKA's voice ([os.meka.core.domain.VoicePickerRules.view]): the server's voices (best first), then
     * the device's own, the chosen one lit, and the month's line; [mac] words the device's row for the Mac. Never
     * throws: no answer reads as "failed", not connected says so.
     */
    suspend fun voicePicker(mac: Boolean): os.meka.core.domain.VoicePickerView {
        val chosen = onCore { mekaVoice.chosen() }
        val api = speechApi ?: return os.meka.core.domain.VoicePickerRules.view(null, emptyList(), null, chosen, null, mac, connected = false)
        val v = try { api.speechVoices() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            os.meka.core.wire.SpeechCodec.Voices(os.meka.core.wire.SpeechCodec.Voices.FAILED)
        }
        val offered = v.voices.map { os.meka.core.domain.OfferedVoice(it.id, it.gender, it.engine) }
        val speaking = v.voices.firstOrNull { it.id.equals(chosen, ignoreCase = true) }?.id ?: v.defaultVoice
        val usage = os.meka.core.domain.SpeechRules.usageLine(
            v.state, v.month, v.usedChars, v.capChars, speaking, deviceChosen = chosen == os.meka.core.domain.MekaVoiceRules.DEVICE,
        )
        return os.meka.core.domain.VoicePickerRules.view(v.state, offered, v.defaultVoice, chosen, usage, mac)
    }

    /**
     * ▶ Sample in the voice picker: [os.meka.core.domain.VoicePickerRules.SAMPLE] said by the server's [voice] (a Polly
     * name), as base64 MP3, or null when it can't be (not connected, refused, slower than
     * [os.meka.core.domain.SpeechRules.NEXT_AUDIO_MS]); the device then says it in its own voice. Kept in memory like
     * every clip. Never throws.
     */
    suspend fun speechSample(voice: String): String? {
        val api = speechApi ?: return null
        val name = os.meka.core.domain.MekaVoiceRules.normalize(voice)?.takeIf { it != os.meka.core.domain.MekaVoiceRules.DEVICE } ?: return null
        val words = os.meka.core.domain.VoicePickerRules.SAMPLE
        onCore { speechCached(name, words) }?.let { return it }
        val r = withTimeoutOrNull(os.meka.core.domain.SpeechRules.NEXT_AUDIO_MS) {
            try { api.speak(words, name) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        } ?: return null
        val audio = r.audio
        if (r.state != os.meka.core.wire.SpeechCodec.Response.SPOKEN || audio.isNullOrEmpty()) return null
        return onCore {
            speechClips[os.meka.core.domain.SpeechRules.cacheKey(name, words)] = audio
            while (speechClips.size > os.meka.core.domain.SpeechRules.CACHE_CLIPS) speechClips.remove(speechClips.keys.first())
            audio
        }
    }

    private class SpeechVoice(val name: String?)

    /** The voice to ask the server for now, or null when the device should speak (chosen, or resting after a refusal). */
    private fun speechVoiceNow(): SpeechVoice? {
        val chosen = mekaVoice.chosen()
        if (chosen == os.meka.core.domain.MekaVoiceRules.DEVICE || nowMs() < speechQuietUntilMs) return null
        return SpeechVoice(chosen)
    }

    private fun speechCached(voice: String?, words: String): String? {
        val key = os.meka.core.domain.SpeechRules.cacheKey(voice, words)
        val clip = speechClips.remove(key) ?: return null
        speechClips[key] = clip // newest last
        return clip
    }

    /**
     * What Ask says about MEKA's AI under its field ([os.meka.core.domain.AskRules.statusView]): on with the month's
     * spend, off, used up or not answering, from the server's `POST /v1/ai/status`. Never throws.
     */
    suspend fun aiStatus(): os.meka.core.domain.AiStatusView {
        val api = aiApi ?: return os.meka.core.domain.AskRules.STATUS_NOT_CONNECTED
        val r = try { api.aiStatus() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return os.meka.core.domain.AskRules.STATUS_UNKNOWN
        } ?: return os.meka.core.domain.AskRules.STATUS_UNKNOWN
        return os.meka.core.domain.AskRules.statusView(r.state, r.reason, r.spentCents, r.budgetCents, r.level)
    }

    /**
     * Activity's daily count of MEKA's AI ([os.meka.core.domain.AiTodayRules.line]): "MEKA's AI today · Talk 6 · Ask 2
     * · 3¢", from the server's status. Null when nothing was asked today, the device isn't connected or the server
     * can't be reached. Never throws.
     */
    suspend fun aiTodayLine(): String? {
        val api = aiApi ?: return null
        val r = try { api.aiStatus() } catch (e: CancellationException) { throw e } catch (e: Exception) { return null } ?: return null
        return os.meka.core.domain.AiTodayRules.line(r.today)
    }

    /** An earlier exchange trimmed to what the server accepts (question, answer, at most three confirmed lines). */
    private fun sendable(t: os.meka.core.domain.TalkTurn) = os.meka.core.domain.TalkTurn(
        os.meka.core.domain.AskRules.question(t.question) ?: "…",
        t.answer.take(os.meka.core.domain.AskRules.MAX_ANSWER),
        t.done.takeLast(os.meka.core.domain.TalkRules.MAX_DONE).map { it.take(os.meka.core.domain.AskRules.MAX_LINE) },
    )

    /** ADR-006: a model's proposal is a suggestion the policy engine must allow; it can never run without a tap. */
    private fun askAllowed(p: os.meka.core.domain.AskProposal, untrusted: Boolean): Boolean {
        val (type, domain) = askAction(p)
        val decision = PolicyEngine(PolicyConfig()).decide(
            ActionRequest(
                type, domain,
                provenance = if (untrusted) Provenance.MODEL_FROM_UNTRUSTED else Provenance.MODEL_FROM_TRUSTED,
                reversible = true,
                requestedLevel = AutonomyLevel.SUGGEST,
            ),
        )
        return decision !is PolicyDecision.Deny && decision !is PolicyDecision.Permit
    }

    /**
     * Earned trust: may [p] run on its own? Only when the policy engine permits Level 4 under the explicit rule Meka's
     * "just do it" made for [kind] (ADR-006: reversible, an allowlisted rule, never from untrusted provenance).
     */
    private fun askAuto(p: os.meka.core.domain.AskProposal, kind: os.meka.core.domain.TrustKind, untrusted: Boolean): Boolean {
        val (type, domain) = askAction(p)
        val rule = os.meka.core.domain.TrustRules.ruleId(kind)
        // The kill switch ("What MEKA may do" → Pause) holds every kind at asking first.
        val config = PolicyConfig(
            levels = mapOf((domain to type) to AutonomyLevel.AUTO_UNDER_RULE), autoRules = mapOf(rule to type),
            killSwitch = autonomy.paused(),
        )
        val decision = PolicyEngine(config).decide(
            ActionRequest(
                type, domain,
                provenance = if (untrusted) Provenance.MODEL_FROM_UNTRUSTED else Provenance.MODEL_FROM_TRUSTED,
                reversible = true,
                requestedLevel = AutonomyLevel.AUTO_UNDER_RULE,
                ruleId = rule,
            ),
        )
        return decision is PolicyDecision.Permit
    }

    private fun askAction(p: os.meka.core.domain.AskProposal): Pair<ActionType, PolicyDomain> {
        return when (p) {
            is os.meka.core.domain.AskProposal.AddTask -> ActionType.CREATE_TASK to PolicyDomain.TASKS
            is os.meka.core.domain.AskProposal.CompleteTask, is os.meka.core.domain.AskProposal.MoveTask -> ActionType.RESCHEDULE_ITEM to PolicyDomain.TASKS
            is os.meka.core.domain.AskProposal.StartFast -> ActionType.CREATE_TASK to PolicyDomain.HEALTH
            is os.meka.core.domain.AskProposal.Timer, is os.meka.core.domain.AskProposal.Alarm -> ActionType.CREATE_TASK to PolicyDomain.TASKS
            // Meka's own list (Jeanette sees it, but adding to it sends nothing to anyone).
            is os.meka.core.domain.AskProposal.AddShopping -> ActionType.CREATE_TASK to PolicyDomain.TASKS
            // The week's dinners: Meka's own plan (nothing is sent or bought).
            is os.meka.core.domain.AskProposal.PlanDinner -> ActionType.CREATE_TASK to PolicyDomain.TASKS
            // Meka's own evening setting (nothing is booked, cancelled or sent).
            is os.meka.core.domain.AskProposal.SkipDateNight -> ActionType.CREATE_TASK to PolicyDomain.TASKS
            // Marks Meka's own radar item done, like its button (nothing is paid, booked or sent).
            is os.meka.core.domain.AskProposal.RenewalDone -> ActionType.RESCHEDULE_ITEM to PolicyDomain.TASKS
            // Keeps a line in Meka's own wins log (nothing is sent to anyone).
            is os.meka.core.domain.AskProposal.AddWin -> ActionType.CREATE_TASK to PolicyDomain.TASKS
            // Marks one of Meka's own saved links read, like its Done button (nothing is opened, fetched or sent).
            is os.meka.core.domain.AskProposal.QueueDone -> ActionType.RESCHEDULE_ITEM to PolicyDomain.TASKS
            // Marks days off in Meka's own planner, like the Leave pane (nothing is sent to his employer or anyone).
            is os.meka.core.domain.AskProposal.BookLeave -> ActionType.CREATE_TASK to PolicyDomain.TASKS
            // Keeps a fact on Meka's own page (nothing is sent to anyone).
            is os.meka.core.domain.AskProposal.AddFact -> ActionType.CREATE_TASK to PolicyDomain.TASKS
            // Adds to Meka's own errands list (nothing is sent; where a place is stays on the Fold).
            is os.meka.core.domain.AskProposal.AddErrand -> ActionType.CREATE_TASK to PolicyDomain.TASKS
            // Adds to one of Meka's own task checklists (nothing is sent to anyone).
            is os.meka.core.domain.AskProposal.AddChecklist -> ActionType.CREATE_TASK to PolicyDomain.TASKS
            // Adds one of Meka's own goals (nothing is sent to anyone).
            is os.meka.core.domain.AskProposal.AddGoal -> ActionType.CREATE_TASK to PolicyDomain.TASKS
        }
    }

    // ---- Self-updating phone app (build plan M1) ----

    /** The newest published build for [platform]; null when there is none, offline, or not connected. */
    suspend fun latestRelease(platform: String = ReleaseTransfer.ANDROID): AppRelease? =
        try { releasesApi?.latestRelease(platform) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /**
     * Downloads [release] into [sink] chunk by chunk, calling [progress] with the chunks done. Throws on network
     * errors; the caller checks the whole file's hash before installing.
     */
    suspend fun downloadRelease(release: AppRelease, sink: suspend (ByteArray) -> Unit, progress: (Int) -> Unit) {
        val api = releasesApi ?: throw os.meka.core.sync.TransportException("not connected")
        ReleaseTransfer.download(api, release, sink, progress)
    }

    /** Publishes a build this device holds (the Mac publishing the phone app it built). */
    suspend fun publishRelease(bytes: ByteArray, versionCode: Long, versionName: String, platform: String = ReleaseTransfer.ANDROID): PublishOutcome {
        val api = releasesApi ?: return PublishOutcome.Failed("Connect this Mac to your server first.")
        return ReleaseTransfer.publish(api, platform, bytes, versionCode, versionName)
    }

    // ---- Push (build plan M1: push via Firebase) ----

    /**
     * Tells the server where to wake this device (an FCM token; empty removes it). True once the server has it;
     * false when offline, not connected or the server has no push yet: the platform tries again later. The server
     * only ever sends "sync now"; the change itself comes over the normal signed sync.
     */
    suspend fun registerPushToken(token: String, service: String = "fcm"): Boolean =
        try { pushApi?.registerPushToken(service, token) != null } catch (e: CancellationException) { throw e } catch (e: Exception) { false }

    /** For platform schedulers (WorkManager, BGTask): one round, returns true on success. */
    suspend fun syncNow(): Boolean = withContext(confined) { syncMutex.withLock { runSyncOnce() } == null }

    fun close() { scope.coroutineContext[Job]?.cancel() }

    // ---- Internals ----

    /** Push local edits promptly while background sync is running; otherwise the platform scheduler will. */
    private fun requestSync() {
        if (syncLoop?.isActive != true) return
        scope.launch {
            if (syncMutex.tryLock()) {
                try { runSyncOnce() } finally { syncMutex.unlock() }
            } // else a round is already running and will pick the edit up on its next push
        }
    }

    /** Returns null on success, or the backoff delay before the next attempt. Caller holds [syncMutex]. */
    private suspend fun runSyncOnce(): Long? {
        val client = syncClient ?: return null
        _sync.value = SyncStatus.Syncing
        return try {
            val report = client.syncOnce()
            failures = 0
            lastSyncedMs = nowMs()
            _sync.value = if (report.rejected.isEmpty()) SyncStatus.Synced(nowMs())
            else SyncStatus.Failing("${report.rejected.size} change(s) were refused by the server", replica.pendingPushCount())
            refresh()
            catchUpBlocks()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthRejectedException) {
            // Retrying cannot fix this; say so instead of looking "offline" forever. Check again in a while.
            _sync.value = SyncStatus.Failing(SIGNED_OUT_MESSAGE, replica.pendingPushCount())
            15 * 60_000L
        } catch (e: Exception) {
            failures++
            val wait = Backoff.delayMs(failures) { bound -> jitter.nextLong(bound) }
            _sync.value = SyncStatus.Offline(replica.pendingPushCount(), nowMs() + wait)
            wait
        }
    }

    private suspend fun <T> onCore(block: () -> T): T = withContext(confined) { block() }

    private fun refresh() {
        val all = tasks.all()
        val marks = eventActions.marks(all)
        _eventMarks.value = marks
        val allEvents = currentEvents()
        val dayEvents = marks.visible(allEvents)
        val credit = callCredit.current()
        val workState = work.state(localClock(), todayEpochDay())
            .let { it.copy(callAssistantPaused = it.callAssistant && credit?.paused == true) }
        val holidays = bankHolidays.calendar()
        // Booked leave is a day off for work like a bank holiday (Gym bookings, the shutdown, the brief's work line);
        // the school's days follow the bank holidays alone.
        val leaveDays = leave.days()
        val workHolidays = os.meka.core.domain.LeaveRules.withLeave(holidays, leaveDays)
        // The schedule and the bank holidays alone, for the lines either side of leave (annual leave, slice 2).
        val leaveHours = os.meka.core.domain.WorkHours(workState.schedule, holidays)
        val cal = ZoneCalendar(timeZone)
        // Sessions first: Today's timeline shows today's booked sessions still to come.
        val sessionHabits = goals.sessionHabits()
        // Date night (slice 1): its evening is busy for Gym bookings.
        val dateNightNow = dateNight.setting()
        _sessions.value = if (sessionHabits.isEmpty()) os.meka.core.domain.SessionsView.EMPTY
        else os.meka.core.domain.SessionRules.book(sessionHabits, todayEpochDay(), nowMs(), cal) { day ->
            os.meka.core.domain.SessionRules.busyOn(day, dayEvents, workState.schedule, workHolidays, cal) +
                listOfNotNull(os.meka.core.domain.DateNightRules.slot(dateNightNow, day, cal))
        }
        val listsNow = lists.view(all, renewals.view(), shopping.view())
        val fastingNow = fasting.view()
        val goalsNow = goals.view(all).withSessions(_sessions.value)
        val projected = project(all, dayEvents)
        val today = dayWindow(nowMs())
        // The forecast (Weather slice 2): Today's line, the brief's today, the shutdown's tomorrow, rain on the Day ring.
        val forecast = weather.forecast()
        val todayDay = cal.epochDayOf(nowMs())
        // Places item 2: on an office day, both places on Today's line and the brief, and the ring's rain follows Meka.
        val workForecast = workWeather.forecast()
        val office = os.meka.core.domain.PlacesRules.officeWindow(
            hoursOf(workState, todayDay), todayDay, cal,
        )
        val placesLine = office?.let { os.meka.core.domain.PlacesRules.placesLine(forecast, workForecast, nowMs(), it, cal) }
        // School rhythm, slice 2: what the school says about today (Today's header, the brief) and tomorrow (the shutdown).
        val schoolItems = school.items()
        val schoolToday = os.meka.core.domain.SchoolRules.todayLine(schoolItems, todayDay, holidays)
        // Bin days, slice 1: the bins (each collection a day later in a bank-holiday week).
        val binItems = bins.items()
        // The Sunday week ahead, slice 1: the coming week from what MEKA already knows.
        val weekAheadNow = os.meka.core.domain.WeekAheadRules.view(
            os.meka.core.domain.WeekAheadInputs(
                // Appointment nudges, slice 2c: a booked visit is said once, as its Google event when it has one.
                events = dayEvents.filterNot { os.meka.core.domain.AppointmentCalendarRules.isAppointment(it) },
                work = os.meka.core.domain.WorkHours(workState.schedule, holidays, homeDays = work.homeDays(todayDay), leaveDays = leaveDays),
                holidays = holidays,
                school = schoolItems,
                dateNight = dateNightNow,
                renewals = listsNow.renewals.all,
                appointments = os.meka.core.domain.AppointmentCalendarRules.notInCalendar(appointments.items(), dayEvents, cal),
                forecast = forecast,
                goals = goalsNow.goals,
                bins = binItems,
            ),
            nowMs(), cal, ::dayWindowOf,
        )
        _weekAhead.value = weekAheadNow
        val shutdownRaw = shutdown.view(all, dayEvents, workState.schedule, workState.atWork, today, dayWindow(today.endMs), workHolidays)
        val shutdownNow = shutdownRaw.copy(
            // Daily receipt: what MEKA handled today, from the activity log (Today's evening line opens Activity).
            receipt = os.meka.core.domain.ReceiptRules.receipt(activity.items(), nowMs(), cal),
            tomorrow = shutdownRaw.tomorrow.copy(
                weatherLine = os.meka.core.domain.WeatherRules.dayGlance(forecast, todayDay + 1, cal),
                school = os.meka.core.domain.SchoolRules.tomorrowLine(schoolItems, todayDay, holidays),
                // Annual leave, slice 2: "Annual leave · no work · back Mon 2 Nov" on the evening before leave.
                workLine = os.meka.core.domain.LeaveRules.workLine(shutdownRaw.tomorrow.workLine, leaveDays, leaveHours, dayWindow(today.endMs).epochDay),
            ),
        )
        // The live tiles under the Day ring: next event, a running fast, habits today, renewals due.
        // A running fast also shows as the Day ring's inner arc (Living Today, slice 3); once the day is shut down the
        // ring looks ahead to tomorrow's first commitment (Living Today, item 1).
        _today.value = projected.copy(
            dayRing = projected.dayRing.copy(
                fast = os.meka.core.domain.DayRingRules.fastArc(fastingNow.current, nowMs(), cal),
                tomorrow = os.meka.core.domain.DayRingRules.tomorrow(shutdownNow),
                rain = os.meka.core.domain.WeatherRules.rainBands(os.meka.core.domain.PlacesRules.whereYouAre(forecast, workForecast, office), nowMs(), cal),
            ),
            dayTiles = os.meka.core.domain.DayTileRules.build(
                projected.events, nowMs(), dayWindow(nowMs()), fastingNow.current, goalsNow.habits, listsNow.renewals.dueCount,
            ),
            // The watch face (Fold review 2026-10-09 07:26, item 2): the next 12 hours, across midnight.
            watchFace = watchFaceNow(all, dayEvents, cal),
            school = schoolToday,
            dinner = mealPlan.tonight(),
            dateNight = os.meka.core.domain.DateNightRules.todayLine(dateNightNow, todayDay, cal.minuteOfDay(nowMs())),
            // Train-time queue, slice 1: on an office day's commute, what's saved for the train.
            trainQueue = os.meka.core.domain.QueueRules.trainLine(trainQueue.items(), office, nowMs(), cal),
            weekAhead = os.meka.core.domain.WeekAheadRules.todayLine(weekAheadNow, nowMs(), cal, weekAheadSettings.setting()),
            bins = os.meka.core.domain.BinRules.tonightLine(binItems, todayDay, cal.minuteOfDay(nowMs()), holidays),
        )
        _dateNight.value = os.meka.core.domain.DateNightRules.view(dateNightNow, todayDay, cal.minuteOfDay(nowMs()))
        _protected.value = os.meka.core.domain.ProtectedRules.view(protectedTime.setting(), todayDay, workState.schedule, holidays)
        _leaving.value = os.meka.core.domain.LeavingRules.view(leavingNow.setting())
        _weekAheadSetting.value = os.meka.core.domain.WeekAheadRules.settingView(weekAheadSettings.setting())
        _autonomy.value = os.meka.core.domain.AutonomyRules.view(trust.states(), autonomy.setting(), cal, nowMs())
        _lists.value = listsNow
        _meals.value = mealPlan.view(listsNow.shopping.toBuy.map { os.meka.core.domain.ShoppingRules.key(it.title) }.toSet())
        _emergency.value = emergency.view()
        _facts.value = lifeFacts.view()
        _queue.value = trainQueue.view()
        _errands.value = errands.view()
        _appointments.value = appointments.view()
        _needsYouStack.value = os.meka.core.domain.NeedsYouStackRules.build(
            _today.value, _lists.value.dueLine, nowMs(), ZoneCalendar(timeZone),
            credit = os.meka.core.domain.CallCreditRules.card(credit, workState.callAssistant),
        )
        _fasting.value = fastingNow
        _goals.value = goalsNow
        _workMode.value = workState
        _shutdown.value = shutdownNow
        _wake.value = alarms.wakeView(dayEvents, hoursOf(workState, todayEpochDay()))
        _nextAlarm.value = alarms.next()
        _quickAlarms.value = alarms.quickItems()
        val notifySettings = notifyPrefs.settings()
        _notifySettings.value = notifySettings
        _brief.value = brief.view(all, dayEvents, workState.schedule, notifySettings.quiet, _lists.value, _goals.value, _fasting.value, today,
            news.all(), news.choices(), workHolidays, placesLine ?: os.meka.core.domain.WeatherRules.nowLine(forecast, nowMs(), cal), schoolToday).let {
                // Annual leave, slice 2: "… · last day before leave · back Mon 2 Nov" and "Annual leave · no work · back …".
                it.copy(
                    weatherLine = os.meka.core.domain.WeatherRules.dayGlance(forecast, todayDay, cal),
                    workLine = os.meka.core.domain.LeaveRules.workLine(it.workLine, leaveDays, leaveHours, today.epochDay),
                )
            }
        _newsPlace.value = news.place(nowMs(), dayEvents, ZoneCalendar(timeZone))
        val weatherNow = os.meka.core.domain.WeatherRules.view(forecast, nowMs(), cal, weatherPlace.wanted(), workForecast, workPlace.wanted(), office)
            // The trains on an office day's commute (Places item 4), under the weather line.
            .copy(route = os.meka.core.domain.RouteRules.todayLine(lineStatus.snapshot(), office, nowMs(), cal))
        // Away from home and work with a fresh fix (Places item 3): Today's line says where Meka is.
        val hereLine = os.meka.core.domain.HereRules.todayLine(hereFix, nowMs(), cal)
        _signIn.value = listOfNotNull(os.meka.core.domain.SignInRules.line(signIns.all(), nowMs(), cal))
        _weather.value = if (hereLine == null) weatherNow
        else weatherNow.copy(nowLine = hereLine, nowSpoken = hereLine.replace("°", " degrees"))
        _review.value = review.view(reviewOffset, all, dayEvents, _goals.value, fasting.ended()) { day ->
            dayWindow(ZoneCalendar(timeZone).toEpochMs(day, 12 * 60))
        }
        _wins.value = wins.view(_review.value.weekStart)
        _search.value = runSearch(all)
        _activity.value = activity.view()
        _calendar.value = CalendarAgenda.build(
            all, marks.forCalendarTab(allEvents), nowMs(), ZoneCalendar(timeZone), hidden = marks.hidden,
            work = hoursOf(workState, todayEpochDay()),
        )
        _calendarsOnToday.value = os.meka.core.domain.CalendarRules.choices(allEvents, marks.hiddenCalendars, marks.shownCalendars)
        val editsNow = calendarEdits.all()
        _editsSeen.value = editsNow
        _editLines.value = os.meka.core.domain.EditLineRules.lines(editsNow, nowMs())
        _afterWork.value = held.summary()
        _requests.value = requestCards.open()
        _approvals.value = approvalStore.cards()
        _school.value = school.view(hoursOf(workState, todayDay))
        _bins.value = os.meka.core.domain.BinRules.view(binItems, todayDay, holidays)
        _car.value = os.meka.core.domain.CarRules.view(car.registration(), listsNow.renewals.all, carCheck(), todayDay, cal)
        _leave.value = leave.view(hoursOf(workState, todayDay), schoolItems)
        _triage.value = triageCards.open()
        _groupGists.value = gistStore.open()
        _blockedCallers.value = blockList.view()
        _groupDigestCaughtUp.value = gistStore.caughtUpTimes()
        _notifyPreview.value = Governor.preview(currentNotices(all), notifySettings, nowMs(), ZoneCalendar(timeZone))
        _conflicts.value = tasks.conflicts().map { c ->
            ConflictChoice(
                taskId = c.key.entityId,
                field = c.key.field,
                options = (listOf(c.winning) + c.competing).mapNotNull { it.value.textOrNull }.distinct(),
                conflict = c,
            )
        }
    }

    /** Search over the views as they stand (call after the lists and goals views are fresh). */
    private fun runSearch(all: List<os.meka.core.domain.Task>): SearchView {
        if (os.meka.core.domain.SearchRules.tokens(searchQuery).isEmpty()) return SearchView(searchQuery, emptyList(), 0)
        val sources = SearchSources(
            tasks = all,
            // A booked visit is found once, as its appointment (slice 2c's stand-in event isn't a second hit).
            events = currentEvents().filterNot { os.meka.core.domain.AppointmentCalendarRules.isAppointment(it) },
            waiting = _lists.value.waiting,
            decisions = lists.decisionItems(includeSuperseded = true),
            renewals = _lists.value.renewals.all,
            habits = _goals.value.habits,
            goals = _goals.value.goals,
            shopping = _lists.value.shopping.let { it.toBuy + it.got },
            errands = errands.items(),
            appointments = appointments.items(),
        )
        return Search.run(searchQuery, sources, nowMs(), ZoneCalendar(timeZone))
    }

    /** Notices from the views as they stand (call after [refresh]). */
    private fun currentNotices(all: List<os.meka.core.domain.Task> = tasks.all()) =
        NoticeSources.collect(
            _lists.value, _fasting.value, _shutdown.value, _today.value, nowMs(), ZoneCalendar(timeZone), _brief.value, _review.value.card,
            currentEvents(), _eventMarks.value, _sessions.value, all, weather.forecast(),
            requests = requestCards.open(), settings = notifyPrefs.settings(), signIns = signIns.all(),
            lines = lineStatus.snapshot(), office = officeToday(),
            callCredit = callCredit.current(), callAssistantOn = _workMode.value.callAssistant,
            school = school.items(), schoolCovers = _school.value.covers,
            dateNight = dateNight.setting(),
            wins = wins.all(),
            leave = leave.items(), leaveWork = hoursOf(_workMode.value, todayEpochDay()),
            approvals = os.meka.core.domain.ApprovalRules.pending(approvalStore.all(), nowMs()),
            appointments = appointments.items(),
            goals = _goals.value,
            leaving = leavingNow.setting(),
            weekAhead = _weekAhead.value,
            weekAheadSetting = weekAheadSettings.setting(),
            bins = bins.items(), holidays = bankHolidays.calendar(),
            carRegistration = car.registration(),
        )

    /** Work hours on [day] from the work-mode [state]: bank holidays, work-from-home days and booked leave. */
    private fun hoursOf(state: WorkModeState, day: Long): os.meka.core.domain.WorkHours =
        os.meka.core.domain.WorkHours.of(state, bankHolidays.calendar(), day, work.homeDays(day), leave.days())

    /** Today's office window (Places item 2): a work day that isn't a work-from-home day, or null. */
    private fun officeToday(): os.meka.core.domain.OfficeWindow? {
        val day = todayEpochDay()
        val hours = work.hours(localClock(), day)
        return os.meka.core.domain.PlacesRules.officeWindow(hours, day, ZoneCalendar(timeZone))
    }

    /** The watch face's next 12 hours: events, planned tasks, the week's booked sessions, today's and tomorrow's work. */
    private fun watchFaceNow(
        all: List<os.meka.core.domain.Task>, dayEvents: List<os.meka.core.domain.CalendarEvent>, cal: ZoneCalendar,
    ): os.meka.core.domain.WatchFace {
        val now = nowMs()
        val day = dayWindow(now).epochDay
        val hours = work.hours(localClock(), day)
        return os.meka.core.domain.WatchFaceRules.build(
            dayEvents, all.filter { !it.waitsForItsDay(day) }, _sessions.value.sessions, now, cal,
            work = hours.blocks(day, cal) + hours.blocks(day + 1, cal),
        )
    }

    private fun project(all: List<os.meka.core.domain.Task> = tasks.all(), dayEvents: List<os.meka.core.domain.CalendarEvent> = visibleEvents(all)): Today {
        val now = nowMs()
        val day = dayWindow(now)
        return TodayProjection.project(
            all, now, day, dayEvents, ZoneCalendar(timeZone), _sessions.value.todayBlocks(day.epochDay, now),
            work = work.hours(localClock(), day.epochDay),
        )
    }

    /**
     * The mirrored events with Meka's own calendar edits laid over (slice 2c-ii, [os.meka.core.domain.PendingEditRules]):
     * an add, move, change or delete shows everywhere as soon as it's made, before Google answers.
     */
    private fun currentEvents(): List<os.meka.core.domain.CalendarEvent> {
        val edits = calendarEdits.all()
        // Plan my day's blocks (slice 2e): the task stands for that time, so its block isn't shown twice.
        val shown = os.meka.core.domain.PlanCalendarRules.withoutTaskBlocks(
            os.meka.core.domain.PendingEditRules.apply(events.all(), edits, nowMs()), edits,
        )
        // Meka's own calendar names (Fold review 2026-10-09 07:26, item 9) ride on every event, so each place that
        // names a calendar (the Calendar key, rows, details, Calendars) says the same thing.
        // Appointment nudges, slice 2c: a booked visit that isn't in Google or Outlook stands in as MEKA's own event.
        val cal = ZoneCalendar(timeZone)
        val list = shown + os.meka.core.domain.AppointmentCalendarRules.events(appointments.items(), shown, cal.epochDayOf(nowMs()), cal)
        val names = eventActions.calendarNames()
        if (names.isEmpty()) return list
        return list.map { e -> names[os.meka.core.domain.CalendarRules.key(e)]?.let { e.copy(calendarTitle = it) } ?: e }
    }

    /** Calendar events minus those hidden from my day. */
    private fun visibleEvents(all: List<os.meka.core.domain.Task>) = eventActions.marks(all).visible(currentEvents())

    private fun localClock(): LocalClock {
        val t = Instant.fromEpochMilliseconds(nowMs()).toLocalDateTime(timeZone())
        return LocalClock(t.dayOfWeek.isoDayNumber, t.hour * 60 + t.minute)
    }

    /** The local day [epochDay]'s window (DST-safe). */
    private fun dayWindowOf(epochDay: Long): DayWindow = dayWindow(ZoneCalendar(timeZone).toEpochMs(epochDay, 12 * 60))

    private fun dayWindow(now: Long): DayWindow {
        val tz = timeZone()
        val date = Instant.fromEpochMilliseconds(now).toLocalDateTime(tz).date
        val start = date.atStartOfDayIn(tz).toEpochMilliseconds()
        val end = date.plus(DatePeriod(days = 1)).atStartOfDayIn(tz).toEpochMilliseconds() // DST-safe day length
        val offsetMs = tz.offsetAt(Instant.fromEpochMilliseconds(start)).totalSeconds * 1000L
        return DayWindow(start, end, offsetMs)
    }
}

/** The user's local calendar for repeating tasks, from the device time zone (DST-aware). */
@OptIn(ExperimentalTime::class)
internal class ZoneCalendar(private val timeZone: () -> TimeZone) : LocalCalendar {
    override fun epochDayOf(epochMs: Long): Long =
        Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone()).date.toEpochDays().toLong()

    override fun minuteOfDay(epochMs: Long): Int =
        Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone()).let { it.hour * 60 + it.minute }

    override fun toEpochMs(epochDay: Long, minuteOfDay: Int): Long {
        val d = CivilDate.fromEpochDay(epochDay)
        return LocalDate(d.year, d.month, d.day).atTime(minuteOfDay / 60, minuteOfDay % 60).toInstant(timeZone()).toEpochMilliseconds()
    }
}
