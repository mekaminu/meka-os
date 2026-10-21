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
import os.meka.core.sync.fv
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
    /**
     * Background work never takes the app down (nightly Mac build 2026-10-18, ci run 871: on Kotlin/Native an exception
     * escaping a `scope.launch` ends the process — the Mac app, and the native test run). It is kept as
     * [lastBackgroundError] and printed with its stack, and the work it was doing simply stops.
     */
    private val backgroundErrors = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        lastBackgroundError = e.toString().take(300)
        println("MekaCore background error: " + e.stackTraceToString())
    }
    private val scope = CoroutineScope(SupervisorJob() + confined + backgroundErrors)

    /** The last exception background work hit ([backgroundErrors]); null when none. For tests and Health. */
    var lastBackgroundError: String? = null
        private set
    private val ids = IdGenerator(secureRandom)
    private val jitter = Random(secureRandom.nextLong())

    private val replica = Replica(householdId, deviceId, HlcClock(deviceId, nowMs), store, MekaSchema, ids::next)
    /**
     * This device's own values (never synced). Declared before init's first refresh (nightly Mac build 2026-10-18,
     * ci run 877): declared further down, it was still unset when that refresh read the morning trains, which the JVM
     * turned into a caught NullPointerException (and an empty list kept for the session) and Kotlin/Native into a
     * segfault at start.
     */
    private val localStore = store
    /**
     * Full news hub, slice 5: the news feeds the server last said it couldn't read (display only, never synced).
     * Declared up here, before `init`, because the first refresh reads it.
     */
    private var newsDown: List<os.meka.core.domain.NewsFeedDown> = emptyList()
    private var newsDownAskedMs: Long? = null
    private val tasks = Tasks(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val events = CalendarEvents(replica)
    private val bankHolidays = os.meka.core.domain.BankHolidayStore(replica)
    // Annual leave (V1, slice 1): booked days are days off for work mode, like a bank holiday.
    private val leave = os.meka.core.domain.Leave(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val work = WorkMode(replica, { bankHolidays.calendar() }, leave = { leave.days() }, nowMs = nowMs, away = { trips.awayDays() })
    private val lists = os.meka.core.domain.Lists(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val renewals = Renewals(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val shopping = os.meka.core.domain.Shopping(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val school = os.meka.core.domain.School(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val schoolTerms = os.meka.core.domain.SchoolTermsStore(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val bins = os.meka.core.domain.Bins(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val gifts = os.meka.core.domain.Gifts(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val trips = os.meka.core.domain.Trips(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val documents = os.meka.core.domain.Documents(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val car = os.meka.core.domain.Car(replica)
    private val parkingTimers = os.meka.core.domain.ParkingTimers(replica)
    private val mealPlan = os.meka.core.domain.MealPlan(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val emergency = os.meka.core.domain.EmergencyCard(replica, ids::next, nowMs)
    private val lifeFacts = os.meka.core.domain.LifeFacts(replica, ids::next, nowMs)
    private val trainQueue = os.meka.core.domain.TrainQueue(replica, ids::next, nowMs)
    private val reading = os.meka.core.domain.Reading(replica, ids::next, nowMs)
    private val errands = os.meka.core.domain.Errands(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val templateStore = os.meka.core.domain.ChecklistTemplates(replica, ids::next, nowMs)
    private val appointments = os.meka.core.domain.Appointments(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val wins = os.meka.core.domain.WorkWins(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    /** Balance view (V2, slice 1): the targets per area, synced. */
    private val balanceTargets = os.meka.core.domain.BalanceTargets(replica)
    /** Balance view, slice 4: this week's nudge as planned, worked out in [refresh]. */
    private var balanceNudge: os.meka.core.domain.BalanceNudge? = null
    /** Balance view, slice 5: the choices' stored text before the last "Count as …", for its Undo (set = undoable). */
    private var balanceCountUndo: Pair<String?, Boolean>? = null
    private val reflections = os.meka.core.domain.Reflections(replica, nowMs)
    private val dateNight = os.meka.core.domain.DateNight(replica, nowMs, ZoneCalendar(timeZone))
    private val lifeAdmin = os.meka.core.domain.LifeAdmin(replica, nowMs, ZoneCalendar(timeZone))
    /** This week's life admin hour as last booked ([refresh]), for the planner and Move to later. */
    private var lifeAdminWeek: os.meka.core.domain.LifeAdminSlot? = null
    /** Health Connect slice 2: this device reads workouts in the background (set by the Fold; never synced). */
    private var workoutsInBackground = false
    /** Health Connect slice 3: the sleep sessions the Fold read (this device only, in memory, never synced or stored). */
    private var sleepSessions: List<os.meka.core.domain.SleepSession> = emptyList()
    private val protectedTime = os.meka.core.domain.ProtectedTime(replica)
    private val leavingNow = os.meka.core.domain.LeavingNow(replica)
    // About you (rule 13, slice 1): the household profile, synced.
    private val aboutYou = os.meka.core.domain.AboutYouStore(replica, nowMs)
    private val kids = os.meka.core.domain.Kids(replica, nowMs)
    // Money manager, slice 1: its lines are kept in memory between changes; this listener (before refresh's) drops them.
    private val money = os.meka.core.domain.Money(replica, nowMs).also { m -> replica.addListener { refs -> m.changed(refs) } }
    // Money manager, bank sync: what the server last did through Lunch Flow, and Refresh.
    private val moneyBank = os.meka.core.domain.MoneyBankStore(replica)
    private val trading = os.meka.core.domain.TradingStore(replica)
    private val kidChores = os.meka.core.domain.KidChores(replica)
    private val discordStore = os.meka.core.domain.DiscordStore(replica)
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
    /** Travel mode completeness, slice 3: the trip destination's forecast, written by the server ([os.meka.core.domain.TripWeatherRules]). */
    private val tripWeather = os.meka.core.domain.WeatherStore(replica, os.meka.core.domain.WeatherStore.TRIP_ENTITY_ID)
    private val workPlace = os.meka.core.domain.WorkPlaceStore(replica)
    // Places item 4: the route's train lines (TfL status, server-written).
    private val lineStatus = os.meka.core.domain.LineStatusStore(replica)
    private val signIns = os.meka.core.domain.SignInStore(replica)
    // The call assistant's credit (low-balance guard, server-written).
    private val callCredit = os.meka.core.domain.CallCreditStore(replica)
    /** Clean-ups (2026-10-19): new phones or Macs asking to join, written by the server. */
    private val deviceJoins = os.meka.core.domain.DeviceJoinStore(replica)
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
    private val approvalKeys = os.meka.core.domain.ApprovalKeys(replica, nowMs)
    private val triageCards = os.meka.core.domain.TriageCards(replica, nowMs, ZoneCalendar(timeZone))
    private val gistStore = os.meka.core.domain.GroupGists(replica, nowMs)
    private val chatSummaryStore = os.meka.core.domain.ChatSummaries(replica, nowMs)
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
    private var talkWarmApi: TalkWarmApi? = transport as? TalkWarmApi
    private var hereApi: HereApi? = transport as? HereApi
    private var fuelApi: FuelApi? = transport as? FuelApi
    private var numberLookupApi: NumberLookupApi? = transport as? NumberLookupApi
    private var tradingExportApi: TradingExportApi? = transport as? TradingExportApi
    private var healthApi: HealthApi? = transport as? HealthApi
    private var updateFailureApi: UpdateFailureApi? = transport as? UpdateFailureApi
    private var foldPostureApi: FoldPostureApi? = transport as? FoldPostureApi
    private var notifyDiagnosticsApi: NotifyDiagnosticsApi? = transport as? NotifyDiagnosticsApi
    private var voiceMessageApi: VoiceMessageApi? = transport as? VoiceMessageApi
    private var familyApi: FamilyApi? = transport as? FamilyApi
    /** Family (sharing slice 4): the links as the server last listed them, in memory only (the server holds them). */
    private var familyMembers: List<os.meka.core.domain.FamilyMember> = emptyList()
    private val _family = MutableStateFlow<os.meka.core.domain.FamilyView?>(null)
    private var deviceLinkApi: DeviceLinkApi? = transport as? DeviceLinkApi
    /** Recovery key (slice 2): the household's key as the server last said, when it was read, and the pane's state. */
    private var recoveryApi: RecoveryApi? = transport as? RecoveryApi
    private var vaultFileApi: VaultFileApi? = transport as? VaultFileApi
    private var recoveryStatus: os.meka.core.domain.RecoveryKeyRules.Status? = null
    private var recoveryReadAtMs: Long? = null
    private val _recovery = MutableStateFlow<os.meka.core.domain.RecoveryKeyView?>(null)
    private val keyRandom: Random = secureRandom
    /** Watch (Galaxy Watch slice 1): the linked watches as the server last listed them, in memory only. */
    private var linkedWatches: List<os.meka.core.domain.LinkedWatch> = emptyList()
    private val _watchLink = MutableStateFlow<os.meka.core.domain.WatchLinkView?>(null)
    /** "Where I am now" (Places item 3): the last answer, in memory only (never stored or synced), on the core thread. */
    private var hereFix: os.meka.core.domain.HereFix? = null
    /** People, slice 4: the Fold's Family, Always notify and watched lists, passed in; in memory only, never synced. */
    private var listedPeople = os.meka.core.domain.ListedPeople.NONE
    /** MEKA's voice: the synced choice, the clips said so far (in memory, newest last) and a pause after a refusal. */
    private val mekaVoice = os.meka.core.domain.MekaVoiceStore(replica)
    /** The companion, slice 5: notes of earlier chats (Settings → Navilife's voice → What Navilife remembers). */
    private val companionMemory = os.meka.core.domain.CompanionMemoryStore(replica)
    private val speechClips = LinkedHashMap<String, String>()
    /** Voice completeness, slice 3: clips in [speechClips] that came with a spoken answer, not yet played. */
    private val speechAnswerClips = LinkedHashSet<String>()
    private var speechQuietUntilMs = 0L
    private var speechWarmed = false
    /**
     * The orb speed pass, slice 3: clip requests on their way, by cache key (single flight), so a clip asked for ahead
     * ([speechPrefetch]: the hello's openers, the rest of an answer) and the speaker's own ask share one request.
     */
    private val speechInFlight = HashMap<String, kotlinx.coroutines.CompletableDeferred<String?>>()
    private val speechTimings = ArrayDeque<os.meka.core.domain.SpeechTiming>()
    /** Voice completeness: the last request couldn't reach the server, the offline note was said, clips go over Bluetooth. */
    private var speechUnreachable = false
    private var speechOfflineSaid = false
    private var speechOverBluetooth = false
    /** The mic fix, step 3: end of his words → "Added", the last few, in memory only. */
    private val heardTimings = ArrayDeque<os.meka.core.domain.HeardTiming>()
    /** The orb speed pass, slice 2: the Hello/Talk turn being timed now, and the last 20 (device only, numbers only). */
    private var turnWatch: os.meka.core.domain.TurnStopwatch? = null
    private var turnWallStart = 0L
    private var turnTraces: List<os.meka.core.domain.TurnTrace>? = null

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
     * (Open-Meteo, for About you's home). Empty until the first forecast arrives; moves with the clock.
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

    private val _balance = MutableStateFlow(os.meka.core.domain.BalanceView.EMPTY)
    /**
     * Balance view (V2, slice 1): the week Review shows (it follows [showReviewWeek]) across work, family, health and
     * self against his targets ([setBalanceTarget], synced), counted from the calendar, his work hours and habits.
     */
    val balanceView: StateFlow<os.meka.core.domain.BalanceView> = _balance.asStateFlow()

    private val _reflection = MutableStateFlow(os.meka.core.domain.ReflectionView.EMPTY)
    /**
     * Weekly reflection (V1, slice 1): the two-minute look back for the week Review shows (it follows [showReviewWeek]) —
     * what went well, what to drop, one thing that matters next week, each with suggestions from the week's facts —
     * and the journal of earlier weeks. Shown in Review on both apps; open and lit from Sunday 18:00 (or the time set) through Monday. Synced.
     */
    val reflectionView: StateFlow<os.meka.core.domain.ReflectionView> = _reflection.asStateFlow()

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

    /** Held items whose card's Done (or Clear all) is waiting out its Undo: hidden from [afterWork], not yet written. */
    private val afterWorkHiding = LinkedHashSet<String>()
    /** Delete recording tapped, its Undo still offered: shown as deleted, nothing written yet. */
    private val recordingDeleting = LinkedHashMap<String, Long>()
    private var recordingDeleteSeq = 0L
    private val _afterWork = MutableStateFlow(os.meka.core.domain.AfterWorkSummary(emptyList()))
    /**
     * "While you were at work": what the Fold held during work mode, grouped by person (urgent, then family, then
     * latest). Synced (Needs Meka #10), so the Mac shows the same summary; Done on either device clears it on both.
     */
    val afterWork: StateFlow<os.meka.core.domain.AfterWorkSummary> = _afterWork.asStateFlow()

    private val _heldCalls = MutableStateFlow<List<os.meka.core.domain.HeldCall>>(emptyList())
    /**
     * Activity → Calls (call notifications, slice 4): the call assistant's messages and the missed calls held at work
     * from the last 7 days, cleared ones too, newest first. The Fold names them from its contacts with [callLogOf].
     */
    val heldCalls: StateFlow<List<os.meka.core.domain.HeldCall>> = _heldCalls.asStateFlow()

    private val _callLog = MutableStateFlow(os.meka.core.domain.CallLogView.EMPTY)
    /** [heldCalls] as Activity → Calls shows them, numbers unnamed (the Mac's; the Fold uses [callLogOf]). */
    val callLog: StateFlow<os.meka.core.domain.CallLogView> = _callLog.asStateFlow()

    private val _school = MutableStateFlow(os.meka.core.domain.SchoolView.EMPTY)
    private val _bins = MutableStateFlow(os.meka.core.domain.BinsView.EMPTY)

    /**
     * Bin days (V1, slice 1): each bin typed once with its day and pattern (Ask → More → Bins); the evening before a
     * collection Today says "Black bin and food caddy out tonight" and a heads-up comes at 19:00.
     */
    val binsView: StateFlow<os.meka.core.domain.BinsView> = _bins.asStateFlow()

    private val _trips = MutableStateFlow(os.meka.core.domain.TripsView.EMPTY)

    /**
     * Travel mode (V1, slice 1): trips and their bookings typed in Ask → More → Trips, each trip a day-by-day
     * timeline; Today's header shows the trip a week ahead and while it's on.
     */
    val tripsView: StateFlow<os.meka.core.domain.TripsView> = _trips.asStateFlow()

    /**
     * Travel mode completeness, slice 2: the UK's clock and each destination's (by the trip's name), so a booking at the
     * destination shows UK time beside it ([os.meka.core.domain.TripZoneRules]). Zones the device doesn't know add nothing.
     */
    private val tripClocks by lazy {
        val uk = runCatching { TimeZone.of("Europe/London") }.getOrDefault(TimeZone.UTC)
        os.meka.core.domain.TripClocks(ZoneCalendar { uk }) { id -> runCatching { TimeZone.of(id) }.getOrNull()?.let { z -> ZoneCalendar { z } } }
    }
    private val _documents = MutableStateFlow(os.meka.core.domain.DocumentsView.EMPTY)
    // The Vault key (Vault slice 2a): declared before init's first refresh.
    /**
     * This phone's or Mac's platform crypto for the Vault key ([VaultCrypto]); the Fold and the Mac set it at start,
     * the watch and tests without it never touch the key. With [aboutYouDevice] it names this device ("Fold", "Mac").
     */
    var vaultCrypto: VaultCrypto? = null
        set(value) { field = value; scope.launch { vaultKeyNow() } }
    private val vaultKeys = os.meka.core.domain.VaultKeys(replica, nowMs)
    private val _vaultKey = MutableStateFlow(os.meka.core.domain.VaultKeyView.NONE)
    private var vaultPublicKey: String? = null

    /**
     * The Vault (V2, slice 1): the household's documents with the day each runs out ("Theo passport 20 May 2027"), the
     * ones to renew first; their expiry dates reach the plan as heads-ups ([os.meka.core.domain.DocumentRules.notices]).
     */
    val documentsView: StateFlow<os.meka.core.domain.DocumentsView> = _documents.asStateFlow()
    private val _gifts = MutableStateFlow(os.meka.core.domain.GiftsView.EMPTY)

    /**
     * Gift planner (V1, slice 1): birthdays and anniversaries typed once (Ask → More → Gifts), gift ideas against each
     * and the order-by day; a heads-up two weeks ahead and on the order-by day, Today's line and the week ahead.
     */
    val giftsView: StateFlow<os.meka.core.domain.GiftsView> = _gifts.asStateFlow()

    private val _people = MutableStateFlow(os.meka.core.domain.PeopleView.EMPTY)

    /**
     * People (V1, slice 2; Ask → More → People): one row per person in a promise either way or a birthday in Gifts,
     * with what's owed both ways, the next birthday and the last contact MEKA already knows of (triage cards, held
     * messages). Read only: the rows' promises are kept, moved and deleted through the Lists commands.
     */
    val peopleView: StateFlow<os.meka.core.domain.PeopleView> = _people.asStateFlow()

    private val _car = MutableStateFlow(os.meka.core.domain.CarView.EMPTY)
    /**
     * Car on autopilot (V1, slice 1): the registration and the car's four dates (MOT, tax, insurance, service) against
     * the renewals radar (Lists → Renewals → Car). Synced.
     */
    val carView: StateFlow<os.meka.core.domain.CarView> = _car.asStateFlow()
    private val _cars = MutableStateFlow(os.meka.core.domain.CarsView.EMPTY)
    /**
     * Two cars (2026-10-14): every car, first car first (its view is also [carView]), each with only its own dates,
     * and whether another can be added. Synced.
     */
    val carsView: StateFlow<os.meka.core.domain.CarsView> = _cars.asStateFlow()
    private val _parkingTimers = MutableStateFlow(os.meka.core.domain.TimersView.EMPTY)
    /**
     * Car and parking, slice 2: each car's parking timer ("Paid until 15:30 · 1 h 20 left"), ended ones for two hours.
     * Synced (no location in it), so the Mac shows it too.
     */
    val parkingTimersView: StateFlow<os.meka.core.domain.TimersView> = _parkingTimers.asStateFlow()
    private val _barcaNear = MutableStateFlow<List<os.meka.core.domain.CalendarEvent>>(emptyList())
    /**
     * Barça match day, slice 4: the Barça matches within a day or so of now (not hidden), with the lines the server
     * wrote into their notes; the watch's screen and tile redraw when a sync brings a new score.
     */
    val barcaNearView: StateFlow<List<os.meka.core.domain.CalendarEvent>> = _barcaNear.asStateFlow()
    /**
     * School rhythm (V1, slice 1): Theo's and Finn's days off, dates and weekly things (Ask → More → School), and the
     * week-ahead questions ("Theo and Finn are off Mon 27 Oct · You're in the office that day. Who's covering?") that
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

    private val _aboutYou = MutableStateFlow(os.meka.core.domain.AboutYouView.EMPTY)
    /**
     * About you (rule 13, slice 1; Ask → Settings → About you on both apps): the household profile — his name, partner,
     * children, home and work, the call assistant's and voicemail numbers. Synced; kept in his data, never in code.
     */
    val aboutYouView: StateFlow<os.meka.core.domain.AboutYouView> = _aboutYou.asStateFlow()

    /**
     * Set by the Fold only (About you, release 1): after a successful sync finds no profile on either device, copy the
     * values MEKA had so far into it, once. The Mac never copies (a reinstall pulls first, so nothing is written over).
     */
    var seedsAboutYou: Boolean = false

    /**
     * Release 2's one-off copies into About you (clubs, the council's holidays, the train route). The Fold copied his
     * on 15–16 Oct (deploy 741's About you notice: "clubs copied · council dates copied · route copied"), so the code no
     * longer carries them (rule 13): null in the apps, and only tests swap in invented ones to check the copy still
     * runs once and never over a clear.
     */
    internal var releaseOneClubs: String? = null
    internal var releaseOneCouncil: os.meka.core.domain.TermCalendar? = null
    internal var releaseOneRoute: String? = null

    /**
     * Which app this is (About you, slice 2): after a clean sync with a filled-in profile, it writes once that it holds
     * it, so Health and the publish job can say whether the Fold and the Mac both have it (release 2's gate). The Fold
     * sets FOLD; [MacCoreFactory] sets MAC; null (tests, the server) writes nothing.
     */
    var aboutYouDevice: os.meka.core.domain.AboutYouDevice? = null

    /** Saves one About you line ([field]); blank clears it, with Undo ([os.meka.core.domain.AboutSaved.undo]). */
    suspend fun setAboutYou(field: os.meka.core.domain.AboutField, text: String): os.meka.core.domain.AboutSaved =
        onCore {
            aboutYou.set(field, text).also {
                refreshAboutYouStep()
                // Home, work, the partner or the assistant's number changed: what reads them follows at once.
                if (os.meka.core.domain.HouseholdNames.of(aboutYou.profile()) != os.meka.core.domain.Household.names) refresh()
            }
        }

    /** [setAboutYou] by the field's key ("partnerName"), so only Strings cross from Swift; null for an unknown key. */
    suspend fun setAboutYouField(key: String, text: String): os.meka.core.domain.AboutSaved? {
        val f = os.meka.core.domain.AboutField.entries.firstOrNull { it.key == key } ?: return null
        return setAboutYou(f, text)
    }

    /** Whether MEKA watches the partner's messages for requests. */
    suspend fun setAboutYouPartnerWatch(on: Boolean): Boolean = onCore { aboutYou.setPartnerWatch(on) }

    /** Adds a child typed as "Alex, U7, Oakfield School". */
    suspend fun addAboutYouChild(text: String): os.meka.core.domain.AboutSaved =
        onCore { aboutYou.addChild(text).also { refreshAboutYouStep() } }

    /** Replaces the child at [index] with what's typed. */
    suspend fun editAboutYouChild(index: Int, text: String): os.meka.core.domain.AboutSaved =
        onCore { aboutYou.editChild(index, text) }

    /** Takes the child at [index] off; Undo is [addAboutYouChild] with the result's undo text. */
    suspend fun removeAboutYouChild(index: Int): os.meka.core.domain.AboutSaved =
        onCore { aboutYou.removeChild(index).also { refreshAboutYouStep() } }

    /** Setup's About you step follows the profile at once (Today's Setup card drops it). Caller is on the core. */
    private fun refreshAboutYouStep() {
        _setup.value?.let { v ->
            val step = os.meka.core.domain.AboutYouRules.setupStep(aboutYou.profile())
            if (v.steps.any { it.key == step.key }) _setup.value = os.meka.core.domain.SetupRules.replaceStep(v, step)
        }
    }

    /**
     * About you, release 1: what MEKA held before the profile existed — the Family/Leaving now name, the children by
     * club age group, the weather's home and work places and the call assistant's number. Read from his synced
     * settings where he set them, else from the values the code still holds until release 2 removes them. Release 2
     * slice 1 (2026-10-15): the features now read the children and clubs from the profile only; these are the last
     * copies, for a profile that predates them, and go with the final release-2 slice.
     */
    private fun aboutYouBefore(): os.meka.core.domain.AboutYou = os.meka.core.domain.AboutYou(
        partnerName = leavingNow.setting().to.ifBlank { null },
        partnerWatch = true,
        // The children were copied on 13 Oct and the clubs on 15 Oct; both live only in his profile now.
        clubs = releaseOneClubs,
        // Release 2 slice 3: home, work and the assistant's number were copied on 13 Oct and live only in his profile.
        home = weatherPlace.wanted(),
        work = workPlace.wanted(),
    )

    // ---- Money manager (V1, slice 1) ----

    private val _money = MutableStateFlow(os.meka.core.domain.MoneyView.LOCKED)
    /**
     * Ask → Life → Money on both apps: locked ([os.meka.core.domain.MoneyView.LOCKED]) until the app's fingerprint
     * (Touch ID) prompt says yes and calls [openMoney]; [closeMoney] locks it again and drops the figures from the view.
     */
    val moneyView: StateFlow<os.meka.core.domain.MoneyView> = _money.asStateFlow()
    private var moneyOpen = false
    private var moneyOffset = 0
    // Slice 4: the forecast's what-ifs, on this device only, dropped when Money locks.
    private var moneyWhatIf = os.meka.core.domain.MoneyWhatIf()

    /** After the app's own biometric yes: the Money screen for this month. */
    suspend fun openMoney() = onCore { moneyOpen = true; moneyOffset = 0; syncMoneyGoals(); refreshMoney() }

    /** Locks Money again (the pane closed or the app went away). */
    suspend fun closeMoney() = onCore { moneyOpen = false; moneyAskUntilMs = 0L; moneyOffset = 0; moneyWhatIf = os.meka.core.domain.MoneyWhatIf(); _money.value = os.meka.core.domain.MoneyView.LOCKED }

    // ---- Money manager (16), slice 1: money questions in Ask and Talk, answered on the device ----

    // Until when an unlock from Ask lets money questions be answered (0 = locked); this device only, never synced.
    private var moneyAskUntilMs = 0L

    /**
     * After the app's own fingerprint (Touch ID) yes on Ask's "Unlock Money to answer": money questions are answered for
     * [os.meka.core.domain.MoneyAskRules.ASK_WINDOW_MS] (Talk's follow-ups too). Ask the question again after it.
     */
    suspend fun unlockMoneyAsk() = onCore { moneyAskUntilMs = nowMs() + os.meka.core.domain.MoneyAskRules.ASK_WINDOW_MS }

    /** Ends an unlock from Ask at once (the app went to the background). */
    suspend fun lockMoneyAsk() = onCore { moneyAskUntilMs = 0L }

    /** What Ask's money answers read: every account's lines, what's due to go out and the payday view. Caller is on the core. */
    private fun moneyAskData(): os.meka.core.domain.MoneyAskData {
        val today = todayEpochDay()
        val cal = ZoneCalendar(timeZone)
        val accounts = money.accounts()
        val txns = money.txns()
        val rules = money.rules()
        val ids = accounts.map { it.id }.toSet()
        val cards = money.cards().filter { it.id in ids }
        val lines = os.meka.core.domain.MoneyRules.askLines(accounts, txns, rules)
        val bills = moneyBills()
        val view = os.meka.core.domain.MoneyRules.view(
            accounts, txns, rules, today, cal, 0, bills = bills, holidays = bankHolidays.calendar(), cards = cards,
        )
        val upcoming = os.meka.core.domain.MoneyAskRules.upcoming(
            os.meka.core.domain.MoneyTips.regular(lines, today),
            os.meka.core.domain.BillRules.withoutCards(bills, cards, today) + os.meka.core.domain.MoneyCards.bills(cards, today), today,
        )
        val travel = os.meka.core.domain.MoneyTravel.view(
            os.meka.core.domain.MoneyRules.travelLines(accounts, txns, rules), accounts.associate { it.id to it.name }, today,
            moneyAbroadNow(), os.meka.core.domain.MoneyTravel.tripNear(trips.items(), today),
        )
        return os.meka.core.domain.MoneyAskData(lines, today, upcoming, view.payday, travel)
    }

    /**
     * A money question ([os.meka.core.domain.MoneyAskRules.isAbout]) answered from his own Money on this device, never
     * by the AI: while Money is open or unlocked from Ask; otherwise the locked line with Unlock. Caller is on the core.
     */
    private fun moneyAnswer(q: String): os.meka.core.domain.AskOutcome {
        val rules = os.meka.core.domain.MoneyAskRules
        if (money.accounts().isEmpty() && moneyBills().isEmpty()) {
            return os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.AskAnswer(rules.EMPTY, emptyList()))
        }
        if (!rules.unlocked(moneyOpen, moneyAskUntilMs, nowMs())) {
            return os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.AskAnswer(rules.LOCKED, emptyList(), unlockMoney = rules.UNLOCK))
        }
        return os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.AskAnswer(rules.answer(q, moneyAskData()), emptyList()))
    }

    /** ‹ / ›: [step] −1 a month earlier, +1 a month later (never past this month). */
    suspend fun moneyStep(step: Int) = onCore {
        if (!moneyOpen) return@onCore
        val m = _money.value.month
        moneyOffset = ((m?.offset ?: moneyOffset) - step).coerceAtLeast(0)
        refreshMoney()
    }

    private fun moneyLocked() = os.meka.core.domain.MoneySaved(os.meka.core.domain.MoneyRules.LOCKED, refused = true)

    /** Imports a statement file's [text] (CSV) into [account]; only while Money is open. */
    suspend fun importMoneyStatement(account: String, text: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.import(account, text).also { if (!it.refused) { moneyOffset = 0; syncMoneyGoals() }; refreshMoney() }
    }

    /** Every payment to [txnId]'s merchant goes in [category] from now on; Undo by [undoMoneyCategory]. */
    suspend fun setMoneyCategory(txnId: String, category: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.setCategory(txnId, category).also { refreshMoney() }
    }

    suspend fun undoMoneyCategory(token: String): Boolean = onCore {
        if (!moneyOpen) return@onCore false
        if (token.startsWith(os.meka.core.domain.TradingTracker.UNDO)) return@onCore trading.undo(token).also { refreshMoney() }
        if (token.startsWith(moneyGoalUndoPrefix)) {
            val parts = token.removePrefix(moneyGoalUndoPrefix).split('|')
            val gid = parts.getOrNull(0) ?: return@onCore false
            if (!goals.goalExists(gid)) return@onCore false
            if (runCatching { goals.setMetricMoneyAccount(gid, parts.getOrNull(1)?.ifEmpty { null }) }.isFailure) return@onCore false
            syncMoneyGoals()
            refresh()
            return@onCore true
        }
        if (token.startsWith(moneyRefundUndoPrefix)) {
            val undo = moneyRefundUndo.takeIf { token == it.first }?.second ?: return@onCore false
            moneyRefundUndo = "" to null
            undo()
            refresh()
            return@onCore true
        }
        money.undoCategory(token).also { refreshMoney() }
    }

    private val moneyRefundUndoPrefix = "refund|"
    // Slice 5: the last Close it on a refund, undone by its token (Waiting for's row back as it was).
    private var moneyRefundUndo: Pair<String, (() -> Unit)?> = "" to null

    /**
     * Slice 5: **Close it** on a refund that's back in his statement — the Waiting for row leaves the list as received,
     * on both devices; Undo (by [undoMoneyCategory] with the result's token) puts it back as it was. Only while Money is
     * open and only for a row the refunds section shows as back.
     */
    suspend fun moneyRefundClose(waitingId: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) return@onCore moneyLocked()
        val row = _money.value.refunds?.rows?.firstOrNull { it.id == waitingId && it.state == os.meka.core.domain.MoneyRefunds.BACK }
        val w = lists.waitingItems().firstOrNull { it.id == waitingId }
        if (row == null || w == null) return@onCore os.meka.core.domain.MoneySaved(os.meka.core.domain.MoneyRules.GONE, refused = true)
        val before = chaseState(w.id)
        lists.received(w.id)
        val token = moneyRefundUndoPrefix + w.id
        val undo: () -> Unit = { replica.commitLocal(os.meka.core.domain.EntityTypes.COMMITMENT, w.id, before) }
        moneyRefundUndo = token to undo
        refresh()
        os.meka.core.domain.MoneySaved(os.meka.core.domain.MoneyRefunds.closedSaid(w.title), undo = token)
    }

    /**
     * Slice 5: **Copy the chase** — the app has put the drafted chase on the clipboard; this marks the refund chased on
     * Waiting for (next look in a week). Navilife sends nothing.
     */
    suspend fun moneyRefundChased(waitingId: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) return@onCore moneyLocked()
        if (lists.waitingItems().none { it.id == waitingId }) return@onCore os.meka.core.domain.MoneySaved(os.meka.core.domain.MoneyRules.GONE, refused = true)
        lists.chased(waitingId, os.meka.core.domain.MoneyRefunds.AGAIN_DAYS)
        refresh()
        os.meka.core.domain.MoneySaved(os.meka.core.domain.MoneyRefunds.chasedSaid(todayEpochDay()))
    }

    /** Takes [accountId] and its transactions off every device. */
    suspend fun removeMoneyAccount(accountId: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.removeAccount(accountId).also { refreshMoney() }
    }

    /** Delete all money data: every account, transaction and category choice, on every device (after his yes). */
    suspend fun deleteAllMoney(): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.deleteAll().also { trading.deleteAll(); moneyOffset = 0; moneyWhatIf = os.meka.core.domain.MoneyWhatIf(); refreshMoney() }
    }

    /** Slice 2: hides every tip of [kind] ("habit", "over", "rise") on both devices; Undo by [undoMoneyCategory]. */
    suspend fun hideMoneyTips(kind: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.hideTips(kind).also { refreshMoney() }
    }

    /** Slice 2: every hidden kind of tip shown again. */
    suspend fun showMoneyTips(): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.showTips().also { refreshMoney() }
    }

    /** Slice 3: "That's fine" on one watchdog alert, on both devices; Undo by [undoMoneyCategory]. */
    suspend fun moneyAlertFine(alertId: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.alertFine(alertId).also { refreshMoney() }
    }

    /** Slice 4: turns the forecast's "Halve <category>" what-if on or off (this device only, never stored). */
    suspend fun moneyForecastCut(category: String) = onCore {
        if (!moneyOpen) return@onCore
        val c = moneyWhatIf.cuts
        moneyWhatIf = moneyWhatIf.copy(cuts = if (category in c) c - category else c + category)
        refreshMoney()
    }

    /** Slice 4: the forecast's extra a month ("£200", "£50 a week"); blank clears it. This device only, never stored. */
    suspend fun moneyForecastExtra(text: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) return@onCore moneyLocked()
        val p = os.meka.core.domain.MoneyForecast.parseExtra(text)
            ?: return@onCore os.meka.core.domain.MoneySaved(os.meka.core.domain.MoneyForecast.EXTRA_BAD, refused = true)
        moneyWhatIf = moneyWhatIf.copy(extraPence = p)
        refreshMoney()
        os.meka.core.domain.MoneySaved(os.meka.core.domain.MoneyForecast.extraSaid(p))
    }

    private fun refreshMoney() {
        if (!moneyOpen) return
        val wallets = trading.wallets()
        _money.value = os.meka.core.domain.MoneyRules.view(
            money.accounts(), money.txns(), money.rules(), todayEpochDay(), ZoneCalendar(timeZone), moneyOffset, money.hiddenTips(), money.fineAlerts(),
            moneyWhatIf, lists.waitingItems(), moneyBills(), bankHolidays.calendar(), money.cards(), money.worthItems(),
            moneyBank.sync(), moneyBank.removed().size, moneyBank.askedAtMs(), nowMs(), wallets,
            money.businessAccounts(), money.businessMerchants(), money.setAsidePct(),
            money.sharedTxns(), money.sharedMerchants(), money.sharedEntries(), money.sharedSetting(), aboutYou.profile().partnerName,
            money.challenges(),
            moneyAbroadNow(), os.meka.core.domain.MoneyTravel.tripNear(trips.items(), todayEpochDay()),
            // Money at a glance: the receipt and order emails Inbox autopilot tidied, matched to their payments.
            receipts = os.meka.core.domain.MoneyReceipts.fromTidies(activity.mailTidyBatches(), ZoneCalendar(timeZone)),
        ).let { v ->
            v.copy(
                trading = os.meka.core.domain.TradingTracker.view(wallets, nowMs(), ZoneCalendar(timeZone), tradingAlertSetting()),
                // Slice 13c: the side hustle's money in his Business mail, checked against the statements.
                business = v.business?.let { b -> b.copy(mail = moneyBusinessMail()) },
                goals = if (v.locked || moneyOffset != 0) null
                else os.meka.core.domain.MoneyGoals.view(goals.goals(tasks.all()).first, money.accounts(), money.cards().map { it.id }.toSet()),
            )
        }
    }

    /** Slice 13c: FROM YOUR BUSINESS EMAIL — this tax year's invoice finds against every statement line. Caller is on the core. */
    private fun moneyBusinessMail(): os.meka.core.domain.MoneyBusinessMailView? {
        val finds = activity.mailFinds().filter { os.meka.core.domain.MoneyBusinessMail.isInvoice(it) }
        if (finds.isEmpty()) return null
        val lines = os.meka.core.domain.MoneyRules.watchLines(money.accounts(), money.txns(), money.rules())
        return os.meka.core.domain.MoneyBusinessMail.view(finds, lines, money.businessAccounts(), money.businessMerchants(), todayEpochDay())
    }

    // ---- Money manager (6): money goals linked to Goals ----

    /**
     * Brings every £ goal that follows an account up to that account's balance (one update a day per goal, the same id on
     * every device). Only while Money is open: it's what puts the amount into Goals, and he opened Money for it.
     */
    private fun syncMoneyGoals() {
        if (!moneyOpen) return
        val pairs = os.meka.core.domain.MoneyGoals.toSync(goals.goals(tasks.all()).first, money.accounts(), money.cards().map { it.id }.toSet())
        var wrote = false
        for ((goalId, pence) in pairs) if (goals.syncMoneyBalance(goalId, pence)) wrote = true
        if (wrote) refresh()
    }

    private val moneyGoalUndoPrefix = "mgoal|"

    /**
     * MONEY GOALS: the £ goal [goalId] follows [accountId]'s balance (null stops it; the number stays where it got to),
     * on both devices, and is brought up to it at once. Undo by [undoMoneyCategory] with the result's token. Only while
     * Money is open.
     */
    suspend fun moneyGoalFollow(goalId: String, accountId: String?): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) return@onCore moneyLocked()
        val goal = goals.goals(tasks.all()).first.firstOrNull { it.id == goalId }
            ?: return@onCore os.meka.core.domain.MoneySaved(os.meka.core.domain.MoneyRules.GONE, refused = true)
        val account = accountId?.let { id -> money.accounts().firstOrNull { it.id == id } }
        if (accountId != null && (account == null || money.cards().any { it.id == accountId })) {
            return@onCore os.meka.core.domain.MoneySaved(os.meka.core.domain.MoneyRules.GONE, refused = true)
        }
        val before = try { goals.setMetricMoneyAccount(goalId, accountId) } catch (e: os.meka.core.domain.ValidationException) {
            return@onCore os.meka.core.domain.MoneySaved(os.meka.core.domain.MoneyGoals.NOT_POUNDS, refused = true)
        }
        syncMoneyGoals()
        refresh()
        val said = if (account == null) os.meka.core.domain.MoneyGoals.unlinkedSaid(goal.title)
        else os.meka.core.domain.MoneyGoals.linkedSaid(goal.title, account.name, account.balance?.pence, goal.metric)
        os.meka.core.domain.MoneySaved(said, undo = moneyGoalUndoPrefix + goalId + "|" + before.orEmpty())
    }

    /**
     * Money manager (17): whether this device is outside UK time now (its time zone's offset isn't London's), for
     * PAYING ABROAD's banner. A zone the system can't name counts as home.
     */
    /** Money manager (7): last month, when its review is ready (its statements reach its end; the month's first days only). */
    private fun moneyMonthReady(): Int? {
        val today = todayEpochDay()
        if (os.meka.core.domain.CivilDate.fromEpochDay(today).day > os.meka.core.domain.MoneyMonthReview.LEADS_DAYS) return null
        val ids = money.accounts().map { it.id }.toSet()
        return os.meka.core.domain.MoneyMonthReview.readyMonth(money.txns().asSequence().filter { it.account in ids }.map { it.day }.asIterable(), today)
    }

    // Money manager, nudges: the last answer and what it was worked out from (the lines are re-read only when they change).
    private var moneyLookMemo: Triple<List<os.meka.core.domain.MoneyTxn>, List<Any?>, List<os.meka.core.domain.MoneyNudges.Item>>? = null

    /** Money manager, nudges: what recent payments made newly worth a look in Money ([os.meka.core.domain.MoneyNudges.items]). Caller is on the core. */
    private fun moneyLookItems(): List<os.meka.core.domain.MoneyNudges.Item> {
        val accounts = money.accounts()
        if (accounts.isEmpty()) return emptyList()
        val txns = money.txns()
        val rules = money.rules()
        val today = todayEpochDay()
        val hidden = money.hiddenTips()
        val fine = money.fineAlerts()
        val names = accounts.associate { it.id to it.name }
        val inputs = listOf(accounts, rules, today, hidden, fine)
        moneyLookMemo?.let { (t, k, v) -> if (t === txns && k == inputs) return v }
        val items = os.meka.core.domain.MoneyNudges.items(
            os.meka.core.domain.MoneyRules.watchLines(accounts, txns, rules), today, names, hidden, fine,
        )
        moneyLookMemo = Triple(txns, inputs, items)
        return items
    }

    private fun moneyYearReady(): Int? {
        val today = todayEpochDay()
        if (os.meka.core.domain.CivilDate.fromEpochDay(today).month != 1) return null
        val ids = money.accounts().map { it.id }.toSet()
        return os.meka.core.domain.MoneyYear.readyYear(money.txns().asSequence().filter { it.account in ids }.map { it.day }.asIterable(), today)
    }

    /**
     * Money manager (15), the streaks in Goals: the challenges he set as streaks, from every account's lines (a statement
     * line and the same bank line counted once). No amounts, so it doesn't wait for Money's lock; nothing when none is set.
     */
    private fun moneyStreaks(): os.meka.core.domain.MoneyStreaksView? {
        val setting = money.challenges()
        if (!setting.any) return null
        val lines = os.meka.core.domain.MoneyRules.askLines(money.accounts(), money.txns(), money.rules())
            .map { os.meka.core.domain.MoneyChallengeLine(it.day, it.pence, it.category) }
        return os.meka.core.domain.MoneyChallenges.streaks(lines, setting, todayEpochDay())
    }

    private fun moneyAbroadNow(): Boolean = runCatching {
        val now = Instant.fromEpochMilliseconds(nowMs())
        timeZone().offsetAt(now) != TimeZone.of(MONEY_HOME_ZONE).offsetAt(now)
    }.getOrDefault(false)

    // ---- Trading tracker, slice 3: the alerts ----

    private fun tradingAlertSetting(): os.meka.core.domain.TradingAlertSetting = os.meka.core.domain.TradingAlerts.read(
        replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.TradingAlerts.ENTITY_ID)?.fields,
    )

    /**
     * Money → TRADING → ALERTS: [kind] is [os.meka.core.domain.TradingAlerts.LIQ], [os.meka.core.domain.TradingAlerts.DROP],
     * or (slice 7) [os.meka.core.domain.TradingAlerts.FILL] / [os.meka.core.domain.TradingAlerts.FUNDING], [key] a chip's key
     * ("off", "0.5", "1", "2", "5", "10", "20", "30"). Synced; the notices follow at once. Returns what the pane says
     * (an unknown kind or key changes nothing). Strings only, so the Mac calls it as is.
     */
    suspend fun setTradingAlert(kind: String, key: String): String = onCore {
        val now = tradingAlertSetting()
        val next = os.meka.core.domain.TradingAlerts.with(now, kind, key)
            ?: return@onCore os.meka.core.domain.TradingAlerts.line(now)
        if (next != now || replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.TradingAlerts.ENTITY_ID) == null) {
            replica.commitLocal(
                os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.TradingAlerts.ENTITY_ID,
                os.meka.core.domain.TradingAlerts.settingFields(next, nowMs()),
            )
        }
        refresh()
        refreshMoney()
        os.meka.core.domain.TradingAlerts.switchedLine(kind, next)
    }

    /**
     * Trading tracker: a Hyperliquid public address typed as [text] ("Main 0x…"; the same address again renames it), on
     * both devices; Navilife's server reads it from Hyperliquid's public data. Undo by [undoMoneyCategory]. Only while
     * Money is open. Never a key: anything key-length is refused.
     */
    suspend fun tradingWalletAdd(text: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else trading.add(text).also { refreshMoney() }
    }

    /** Trading tracker: stops following [id] on every device, its figures cleared; Undo by [undoMoneyCategory]. Only while Money is open. */
    suspend fun tradingWalletRemove(id: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else trading.remove(id).also { refreshMoney() }
    }

    /**
     * Trading tracker, slice 8: EXPORT FOR TAX — the tax year [yearKey] ("2025-26") of every followed account's perp
     * orders and daily funding, read by Navilife's server from Hyperliquid's public data, as a CSV to save. Asks again
     * while the server is still reading (up to about two minutes). Only while Money is open (and still open when the
     * answer comes); never throws for the network.
     */
    suspend fun tradingExport(yearKey: String): os.meka.core.domain.TradingExportResult {
        val R = os.meka.core.domain.TradingExportRules
        val start = onCore {
            if (!moneyOpen) null else Triple(os.meka.core.domain.TradingTaxYear.parse(yearKey), trading.wallets(), ZoneCalendar(timeZone))
        } ?: return os.meka.core.domain.TradingExportResult(moneyLocked())
        val (year, wallets, cal) = start
        if (year == null) return os.meka.core.domain.TradingExportResult(os.meka.core.domain.MoneySaved("That isn't a tax year", refused = true))
        if (wallets.isEmpty()) return os.meka.core.domain.TradingExportResult(os.meka.core.domain.MoneySaved(R.NONE, refused = true))
        val api = tradingExportApi ?: return os.meka.core.domain.TradingExportResult(os.meka.core.domain.MoneySaved(R.OFFLINE, refused = true))
        val codec = os.meka.core.wire.TradingExportCodec
        var answer: os.meka.core.wire.TradingExportCodec.Response? = null
        for (attempt in 0 until EXPORT_ASKS) {
            val r = try {
                api.tradingExport(year.fromMs(cal), year.toMs(cal), codec.MAX_WAIT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return os.meka.core.domain.TradingExportResult(os.meka.core.domain.MoneySaved(R.OFFLINE, refused = true))
            }
            if (r.state != os.meka.core.wire.TradingExportCodec.Response.PENDING) { answer = r; break }
        }
        val said = when (answer?.state) {
            null -> R.SLOW
            os.meka.core.wire.TradingExportCodec.Response.OFF -> R.OFF
            os.meka.core.wire.TradingExportCodec.Response.FAILED -> R.FAILED
            else -> null
        }
        if (said != null) return os.meka.core.domain.TradingExportResult(os.meka.core.domain.MoneySaved(said, refused = true))
        val ids = wallets.map { it.id }.toSet()
        // Slice 10: HMRC's monthly dollars to £1, so the file gives pounds too (none from an older server: left blank).
        val rates = answer!!.rates.associate { it.month to it.usdPerGbpMicros }
        val accounts = answer.accounts.filter { it.id in ids }.map { a ->
            os.meka.core.domain.TradingExportAccount(
                a.id,
                a.orders.map { o -> os.meka.core.domain.TradingExportOrder(o.atMs, o.coin, o.direction, o.size, o.price, o.valueCents, o.closedCents, o.feeCents) },
                a.funding.map { f -> os.meka.core.domain.TradingExportFunding(f.day, f.coin, f.cents) },
                a.partial, a.failed,
            )
        }
        return onCore {
            when {
                !moneyOpen -> os.meka.core.domain.TradingExportResult(moneyLocked())
                accounts.isEmpty() || accounts.all { it.failed } -> os.meka.core.domain.TradingExportResult(os.meka.core.domain.MoneySaved(R.FAILED, refused = true))
                accounts.all { it.failed || (it.orders.isEmpty() && it.funding.isEmpty()) } -> os.meka.core.domain.TradingExportResult(R.saidEmpty(year))
                else -> os.meka.core.domain.TradingExportResult(R.saidReady(year, accounts, rates),
                    R.csv(year, accounts, wallets.associate { it.id to it.label }, rates), R.fileName(year))
            }
        }
    }

    /**
     * Bank sync: Refresh — asks Navilife's server to fetch his banks through Lunch Flow now (it syncs every 6 hours by
     * itself). Only while Money is open; refused while one asked a moment ago is still waiting.
     */
    suspend fun moneyBankRefresh(): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) return@onCore moneyLocked()
        val s = moneyBank.sync()
        val t = nowMs()
        if (os.meka.core.domain.MoneyBank.refreshing(s, moneyBank.askedAtMs(), t) ||
            s?.lastTryMs?.let { t - it in 0 until os.meka.core.domain.MoneyBank.MIN_GAP_MS } == true
        ) return@onCore os.meka.core.domain.MoneySaved(os.meka.core.domain.MoneyBank.TOO_SOON, refused = true)
        moneyBank.refresh(t)
        refreshMoney()
        os.meka.core.domain.MoneySaved(os.meka.core.domain.MoneyBank.REFRESH_SAID)
    }

    /** Bank sync: the bank accounts he removed are synced again (the server fetches them at once). Only while Money is open. */
    suspend fun moneyBankRestore(): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) return@onCore moneyLocked()
        val n = moneyBank.restore(nowMs())
        refreshMoney()
        if (n == 0) os.meka.core.domain.MoneySaved(os.meka.core.domain.MoneyRules.GONE, refused = true)
        else os.meka.core.domain.MoneySaved("Syncing $n bank account${if (n == 1) "" else "s"} again · they come back in a minute")
    }

    /**
     * Slice 8: one thing for his net worth typed as [text] ("Pension £42,000", "Car loan £3,200"; the same name again
     * updates it), on both devices; Undo by [undoMoneyCategory]. Only while Money is open.
     */
    suspend fun moneyWorthAdd(text: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.addWorth(text, todayEpochDay()).also { refreshMoney() }
    }

    /** Slice 8: takes a typed thing off his net worth on both devices; Undo by [undoMoneyCategory]. Only while Money is open. */
    suspend fun moneyWorthRemove(id: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.removeWorth(id).also { refreshMoney() }
    }

    /**
     * Slice 7: marks [accountId] as a card ([card] false: no longer one), a charge card when [charge]; on both devices,
     * Undo by [undoMoneyCategory]. Only while Money is open.
     */
    suspend fun moneyCardKind(accountId: String, card: Boolean, charge: Boolean): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.setCardKind(accountId, card, charge).also { refreshMoney() }
    }

    /**
     * Slice 7: one card field typed off its statement ([field]: `statement` · `minimum` · `due` · `limit` · `promo`;
     * blank clears it), on both devices; Undo by [undoMoneyCategory]. Only while Money is open.
     */
    suspend fun moneyCardField(accountId: String, field: String, text: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.setCardField(accountId, field, text, todayEpochDay()).also { refreshMoney() }
    }

    /** Slice 7: Paid on a card's next payment: its reminder stops until the next one. Undo by [undoMoneyCategory]. */
    suspend fun moneyCardPaid(accountId: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.cardPaid(accountId, todayEpochDay()).also { refreshMoney() }
    }

    /**
     * Slice 13: Side hustle account on [accountId] ([on] false: not any more) — every line on it counts as the side
     * hustle's, except transfers; on both devices, Undo by [undoMoneyCategory]. Only while Money is open.
     */
    suspend fun moneyBusinessAccount(accountId: String, on: Boolean): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.setBusinessAccount(accountId, on).also { refreshMoney() }
    }

    /**
     * Money slice 13b: YEAR-END EXPORT — the side hustle's lines for the tax year [yearKey] ("2025-26") with totals by
     * category, as a CSV to save for an accountant. Made here from his own statements and bank lines; nothing is sent
     * or stored. Only while Money is open.
     */
    suspend fun moneyBusinessExport(yearKey: String): os.meka.core.domain.TradingExportResult = onCore {
        if (!moneyOpen) os.meka.core.domain.TradingExportResult(moneyLocked())
        else os.meka.core.domain.MoneyBusinessExport.result(
            yearKey, money.accounts(), money.txns(), money.rules(), money.businessAccounts(), money.businessMerchants(),
        )
    }

    /**
     * Slice 13: Business on a payment — every line to or from [txnId]'s payer counts as the side hustle's ([on]), or is
     * kept out of it, on every account and both devices; Undo by [undoMoneyCategory]. Only while Money is open.
     */
    suspend fun moneyBusinessMerchant(txnId: String, on: Boolean): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.setBusinessMerchant(txnId, on).also { refreshMoney() }
    }

    /** Slice 13: the share of the side hustle's profit to put aside ([key] "10" · "20" · "25" · "30" · "40"), synced; Undo by [undoMoneyCategory]. */
    suspend fun moneySetAside(key: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.setSetAside(key).also { refreshMoney() }
    }

    private fun moneyPartner(): String = os.meka.core.domain.MoneyShared.partnerName(aboutYou.profile().partnerName)

    /**
     * Slice 14: Shared on one payment ([on] false keeps it out, even of an always-shared payer) — counted in who paid
     * what with his partner; on both devices, Undo by [undoMoneyCategory]. Only while Money is open.
     */
    suspend fun moneySharedTxn(txnId: String, on: Boolean): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.setSharedTxn(txnId, on).also { refreshMoney() }
    }

    /** Slice 14: Always shared on [txnId]'s payer (every payment to them, on any account); Undo by [undoMoneyCategory]. Only while Money is open. */
    suspend fun moneySharedMerchant(txnId: String, on: Boolean): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.setSharedMerchant(txnId, on).also { refreshMoney() }
    }

    /** Slice 14: his share of the shared costs ([key] "40" · "50" · "60" · "70"), synced; Undo by [undoMoneyCategory]. */
    suspend fun moneySharedShare(key: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.setSharedShare(key).also { refreshMoney() }
    }

    /** Slice 14: what his partner paid ([text] "Tesco £42.50"), today, synced; Undo by [undoMoneyCategory]. */
    suspend fun moneySharedAdd(text: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.addShared(text, todayEpochDay(), moneyPartner()).also { refreshMoney() }
    }

    /** Slice 14: takes one of his partner's payments off; Undo by [undoMoneyCategory]. */
    suspend fun moneySharedRemove(id: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.removeShared(id).also { refreshMoney() }
    }

    /** Slice 14: Settled up — the running who-paid-what starts again from tomorrow's payments; Undo by [undoMoneyCategory]. */
    suspend fun moneySharedSettle(): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.settleShared(todayEpochDay(), moneyPartner()).also { refreshMoney() }
    }

    /** Slice 15: counting no-spend days on or off, synced; Undo by [undoMoneyCategory]. Only while Money is open. */
    suspend fun moneyNoSpend(on: Boolean): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.setNoSpend(on).also { refreshMoney() }
    }

    /** Slice 15: fun money a week ([text] "£50"; blank stops it), synced; Undo by [undoMoneyCategory]. Only while Money is open. */
    suspend fun moneyFunMoney(text: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.setFunMoney(text).also { refreshMoney() }
    }

    /** Slice 15: a monthly cap on a category ([text] "Eating out £150"), synced; Undo by [undoMoneyCategory]. Only while Money is open. */
    suspend fun moneyCap(text: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.setCap(text).also { refreshMoney() }
    }

    /** Slice 15: takes a cap ([key] its category's) or fun money ("fun") off; Undo by [undoMoneyCategory]. Only while Money is open. */
    suspend fun moneyChallengeRemove(key: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.removeChallenge(key).also { refreshMoney() }
    }

    /** Slice 6: the radar's bills, subscriptions and club fees with a cost, for what's still to go out before payday. */
    private fun moneyBills(): List<os.meka.core.domain.MoneyBill> = renewals.items()
        .filter { it.costPence != null && (it.kind == os.meka.core.domain.ObligationKind.BILL || it.kind == os.meka.core.domain.ObligationKind.SUBSCRIPTION || it.forChild != null) }
        .map { os.meka.core.domain.MoneyBill(it.title, it.dueDay, it.costPence!!) }

    /**
     * Slice 6: the pay account's balance as he typed it ("£1,240", "−£50"; blank clears it), on both devices, so the
     * payday view can say what's safe to spend; Undo by [undoMoneyCategory]. Only while Money is open.
     */
    suspend fun moneyBalance(accountId: String, text: String): os.meka.core.domain.MoneySaved = onCore {
        if (!moneyOpen) moneyLocked() else money.setBalance(accountId, text, todayEpochDay()).also { if (!it.refused) syncMoneyGoals(); refreshMoney() }
    }

    private val _kids = MutableStateFlow(os.meka.core.domain.KidsView.EMPTY)
    /**
     * Kids' admin (V1, slice 1; Ask → Life → Kids on both apps): a card per child from About you with their sizes and
     * when they were last checked, lit three months on. Synced; the children's names stay in his data.
     */
    val kidsView: StateFlow<os.meka.core.domain.KidsView> = _kids.asStateFlow()

    /**
     * Keeps [text] as [child]'s size [size] (`shoes` · `boots` · `tops` · `trousers` · `coat` · `jumper`); blank clears
     * it with Undo ([os.meka.core.domain.KidSaved.undo]). Refused, nothing written, for a child not in About you or text
     * that can't be a size. Any save counts as checking their sizes.
     */
    suspend fun setKidSize(child: String, size: String, text: String): os.meka.core.domain.KidSaved = onCore {
        if (aboutYou.profile().children.none { it.name == child }) os.meka.core.domain.KidSaved(os.meka.core.domain.KidsRules.NOT_CHILD, refused = true)
        else kids.setSize(child, size, text)
    }

    /** "All still fit": [child]'s sizes count as checked today; the line to show, or null when nothing is kept for them. */
    suspend fun kidSizesFit(child: String): String? = onCore {
        if (aboutYou.profile().children.none { it.name == child }) null
        else if (kids.allFit(child)) os.meka.core.domain.KidsRules.fitLine(child) else null
    }

    /**
     * Kids' admin, slice 6: All still fit on the size-check heads-up [key] ([os.meka.core.domain.KidsRules.notices]).
     * Returns the line for the shade ("Marked Alex's sizes as still fitting · …"), or null when the key isn't one, the
     * child has left About you or nothing is kept for them. The heads-up then goes on both devices (the rules re-run).
     */
    suspend fun kidsStillFit(key: String): String? = onCore {
        val id = os.meka.core.domain.KidsRules.childIdOf(key) ?: return@onCore null
        val child = aboutYou.profile().children.firstOrNull { os.meka.core.domain.KidsRules.idFor(it.name) == id } ?: return@onCore null
        if (!kids.allFit(child.name)) return@onCore null
        refresh()
        os.meka.core.domain.KidsRules.fitLine(child.name)
    }

    // Kids' admin, slice 2: what Undo puts back per fee id — a Paid (its before) or a Remove (null). In memory only.
    private val kidFeeUndo = mutableMapOf<String, os.meka.core.domain.RenewalDoneUndo?>()

    /**
     * Kids' admin, slice 2: adds a club fee for [child] from one typed line ("Football £35 monthly from 1 Nov"). It goes
     * on the radar as a bill (subject the child, [os.meka.core.domain.ObligationFields.FOR_CHILD] their id), so Bills
     * due's single reminder covers it and Lists → Renewals shows it. Refused, nothing written, when the line names no
     * date or what it's for, or the child isn't in About you.
     */
    suspend fun addKidFee(child: String, text: String): os.meka.core.domain.KidSaved = onCore {
        val id = os.meka.core.domain.KidsRules.idFor(child)
        if (id == null || aboutYou.profile().children.none { it.name == child }) {
            return@onCore os.meka.core.domain.KidSaved(os.meka.core.domain.KidsRules.NOT_CHILD, refused = true)
        }
        val today = todayEpochDay()
        val (draft, why) = os.meka.core.domain.KidsRules.readFee(text, today)
        if (draft == null) return@onCore os.meka.core.domain.KidSaved(why ?: os.meka.core.domain.KidsRules.FEE_NO_DATE, refused = true)
        try {
            renewals.add(
                title = draft.title, kind = os.meka.core.domain.ObligationKind.BILL, dueDay = draft.dueDay, repeat = draft.repeat,
                cost = draft.costPence?.let { os.meka.core.domain.RenewalRules.formatPence(it).removePrefix("£").replace(",", "") },
                subject = child.trim(), forChild = id,
            )
        } catch (e: os.meka.core.domain.ValidationException) {
            return@onCore os.meka.core.domain.KidSaved(e.message ?: os.meka.core.domain.KidsRules.FEE_NO_DATE, refused = true)
        }
        os.meka.core.domain.KidSaved(os.meka.core.domain.KidsRules.feeAddedLine(draft, today))
    }

    /** Kids' admin, slice 2: [feeId] paid (a repeating fee moves to its next time, a one-off closes); Undo by [undoKidFee]. */
    suspend fun kidFeePaid(feeId: String): os.meka.core.domain.KidSaved = onCore {
        val item = renewals.items().firstOrNull { it.id == feeId && it.forChild != null }
            ?: return@onCore os.meka.core.domain.KidSaved(os.meka.core.domain.KidsRules.FEE_GONE, refused = true)
        val before = try { renewals.doneFromAsk(feeId, item.dueDay) } catch (_: os.meka.core.domain.ValidationException) {
            return@onCore os.meka.core.domain.KidSaved(os.meka.core.domain.KidsRules.FEE_GONE, refused = true)
        }
        kidFeeUndo[feeId] = before
        os.meka.core.domain.KidSaved(os.meka.core.domain.KidsRules.feePaidLine(item.title, before.afterDueDay), feeUndo = feeId)
    }

    /** Kids' admin, slice 2: takes [feeId] off the child's card and the radar; Undo by [undoKidFee]. */
    suspend fun removeKidFee(feeId: String): os.meka.core.domain.KidSaved = onCore {
        val item = renewals.items().firstOrNull { it.id == feeId && it.forChild != null }
            ?: return@onCore os.meka.core.domain.KidSaved(os.meka.core.domain.KidsRules.FEE_GONE, refused = true)
        renewals.delete(feeId)
        kidFeeUndo[feeId] = null
        os.meka.core.domain.KidSaved(os.meka.core.domain.KidsRules.feeRemovedLine(item.title), feeUndo = feeId)
    }

    /** Undo for [kidFeePaid] or [removeKidFee] on [feeId]; false when there's nothing to take back (changed since). */
    suspend fun undoKidFee(feeId: String): Boolean = onCore {
        if (!kidFeeUndo.containsKey(feeId)) return@onCore false
        val before = kidFeeUndo.remove(feeId)
        if (before == null) renewals.restore(feeId) else renewals.undoDone(before)
    }

    // Kids' admin, slice 5: what Undo puts back per token — a removed chore, a pocket money change or a Paid. In memory only.
    private val kidMoneyUndo = mutableMapOf<String, os.meka.core.domain.KidMoneyUndo>()

    private fun kidMissing(child: String): Boolean =
        os.meka.core.domain.KidsRules.idFor(child) == null || aboutYou.profile().children.none { it.name == child }

    private fun keepKidUndo(r: Pair<os.meka.core.domain.KidSaved, os.meka.core.domain.KidMoneyUndo?>, token: String): os.meka.core.domain.KidSaved {
        val u = r.second ?: return r.first
        kidMoneyUndo[token] = u
        return r.first.copy(moneyUndo = token)
    }

    /**
     * Kids' admin, slice 5: adds a chore for [child] from one typed line ("Tidy room £1", "Feed the cat", "Bins 50p");
     * the money, if said, is earned each time it's ticked. Refused, nothing written, with no title, bad money, 12 chores
     * already, or a child not in About you.
     */
    suspend fun addKidChore(child: String, text: String): os.meka.core.domain.KidSaved = onCore {
        if (kidMissing(child)) os.meka.core.domain.KidSaved(os.meka.core.domain.KidsRules.NOT_CHILD, refused = true)
        else kidChores.addChore(child, text)
    }

    /** Slice 5: takes chore [choreId] off the card (its ticks stay in his data); Undo by [undoKidMoney]. */
    suspend fun removeKidChore(choreId: String): os.meka.core.domain.KidSaved = onCore {
        keepKidUndo(kidChores.removeChore(choreId), "chore:$choreId")
    }

    /** Slice 5: ticks (or unticks) [choreId] on [day] (epoch day; this week up to today only). */
    suspend fun tickKidChore(choreId: String, day: Long): os.meka.core.domain.KidSaved = onCore {
        kidChores.tick(choreId, day, todayEpochDay())
    }

    /** Slice 5: [child]'s pocket money a week ("£5"); blank stops it. Undo by [undoKidMoney]. */
    suspend fun setKidPocketMoney(child: String, text: String): os.meka.core.domain.KidSaved = onCore {
        if (kidMissing(child)) os.meka.core.domain.KidSaved(os.meka.core.domain.KidsRules.NOT_CHILD, refused = true)
        else keepKidUndo(kidChores.setPocket(child, text), "pocket:${os.meka.core.domain.KidsRules.idFor(child)}")
    }

    /** Slice 5: notes this week's pocket money and chore money for [child] as given (Navilife never pays); Undo by [undoKidMoney]. */
    suspend fun kidPocketMoneyPaid(child: String): os.meka.core.domain.KidSaved = onCore {
        if (kidMissing(child)) os.meka.core.domain.KidSaved(os.meka.core.domain.KidsRules.NOT_CHILD, refused = true)
        else keepKidUndo(kidChores.markPaid(child, todayEpochDay()), "pay:${os.meka.core.domain.KidsRules.idFor(child)}")
    }

    /** Undo for [removeKidChore], [setKidPocketMoney] or [kidPocketMoneyPaid] by its token; false when there's nothing to take back. */
    suspend fun undoKidMoney(token: String): Boolean = onCore {
        val u = kidMoneyUndo.remove(token) ?: return@onCore false
        kidChores.undo(u)
    }

    private val _emergency = MutableStateFlow(os.meka.core.domain.EmergencyView.EMPTY)
    /**
     * The emergency card (V1, slice 1): who to call, where important documents are and key accounts by name only
     * (Ask → More → Emergency card). Synced; his wife sees it read-only on her family page. Never a password, PIN or
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

    private val _reading = MutableStateFlow(os.meka.core.domain.ReadingView.EMPTY)
    /**
     * Reading and learning (V1, slice 1): the books, podcasts and courses he wants to get to, on the go with how far,
     * up next, and finished or put down lately (Ask → Daily → Reading). Synced; nothing is fetched.
     */
    val readingView: StateFlow<os.meka.core.domain.ReadingView> = _reading.asStateFlow()

    private val _errands = MutableStateFlow(os.meka.core.domain.ErrandView.EMPTY)
    /** Errands by location (V1, slice 1): what waits at each place, and what was ticked off lately. */
    val errandsView: StateFlow<os.meka.core.domain.ErrandView> = _errands.asStateFlow()

    private val _appointments = MutableStateFlow(os.meka.core.domain.AppointmentView.EMPTY)
    /**
     * Appointment nudges (V1, slice 1): the dentist, the optician, a haircut… each due from its last visit, and the
     * cards Needs you shows for the ones that have come due.
     */
    val appointmentsView: StateFlow<os.meka.core.domain.AppointmentView> = _appointments.asStateFlow()

    private val _lifeLines = MutableStateFlow(os.meka.core.domain.LifeLines.EMPTY)
    /** Ask's Life grid (Fold review 2026-10-12, item 7): each tile's live line ("Chilli tonight", "Next Fri 23 Oct"). */
    val lifeLines: StateFlow<os.meka.core.domain.LifeLines> = _lifeLines.asStateFlow()

    private val _dailyLines = MutableStateFlow(os.meka.core.domain.DailyLines.EMPTY)
    /** Ask's Daily rows (Ask polish, item 3): each row's live line ("Tomorrow 06:40 · 5 things", "2 saved"). */
    val dailyLines: StateFlow<os.meka.core.domain.DailyLines> = _dailyLines.asStateFlow()

    private val _dateNight = MutableStateFlow(os.meka.core.domain.DateNightView.EMPTY)
    /**
     * Date night (V1, slice 1): the fortnightly evening the planner and Gym bookings keep clear, its next few nights
     * and which can be skipped (Ask → More → Date night). Synced.
     */
    val dateNightView: StateFlow<os.meka.core.domain.DateNightView> = _dateNight.asStateFlow()

    private val _lifeAdmin = MutableStateFlow(os.meka.core.domain.LifeAdminView.EMPTY)
    /**
     * Life admin hour (V1, slice 1): the weekly hour MEKA books around the calendar and the admin jobs it gathers into
     * it (Ask → More → Life admin hour). Synced.
     */
    val lifeAdminView: StateFlow<os.meka.core.domain.LifeAdminView> = _lifeAdmin.asStateFlow()

    private val _protected = MutableStateFlow(os.meka.core.domain.ProtectedView.EMPTY)
    /**
     * Protected time (V1, slice 1): evenings and weekends the planner and Move to later keep tasks out of (Ask → More →
     * Protected time). Synced.
     */
    val protectedView: StateFlow<os.meka.core.domain.ProtectedView> = _protected.asStateFlow()

    private val _leaving = MutableStateFlow(os.meka.core.domain.LeavingView.EMPTY)
    /** "Leaving now" (V1): the draft to his wife when an office day's work ends (Work mode → Leaving now). Synced. */
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

    private val _chatSummaries = MutableStateFlow<List<os.meka.core.domain.ChatSummary>>(emptyList())
    /**
     * Busy chats' one-line summaries (messages grouped per chat, slice 3c), made on the Fold and synced (never the
     * messages); both apps fold them into the chat cards with [os.meka.core.domain.ChatSummaryRules.withSummaries].
     */
    val chatSummaries: StateFlow<List<os.meka.core.domain.ChatSummary>> = _chatSummaries.asStateFlow()

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

    private val _archivedTick = MutableStateFlow(0)

    /**
     * "Archived for you" shows which emails, slice 3: moves on whenever the synced tidies may have changed (a sync, an
     * ask, Put back), so an open All tidied mail reads [archivedAll] again.
     */
    val archivedTick: StateFlow<Int> = _archivedTick.asStateFlow()

    private val _archivedForYou = MutableStateFlow<os.meka.core.domain.ArchivedForYouCard?>(null)
    /**
     * Inbox autopilot, slice 4: Needs you's "Archived for you" card, due at the 12:30 and 18:30 digests while Autopilot
     * tidied something Meka hasn't seen (Got it on either app); null otherwise. Follows sync and the minute tick.
     */
    val archivedForYou: StateFlow<os.meka.core.domain.ArchivedForYouCard?> = _archivedForYou.asStateFlow()

    /** What Got it replaced, for its Undo (this device only; null after an Undo). */
    private var archivedSeenBefore: Long? = null
    private var archivedSeenUndoable = false

    private val _mailUnopened = MutableStateFlow<os.meka.core.domain.MailUnopenedCard?>(null)
    /**
     * Inbox autopilot, slice 5c: Needs you's weekly "Not opened in 30 days · unsubscribe from 11?", from Saturday 09:00
     * until answered that week (on either app); null otherwise. Follows sync and the minute tick.
     */
    val mailUnopened: StateFlow<os.meka.core.domain.MailUnopenedCard?> = _mailUnopened.asStateFlow()

    /** Puts back the offer's last answer on this device (Unsubscribe from all, Not now or Keep); null after an Undo. */
    private var unopenedUndo: (() -> Unit)? = null

    private val _mailNeeds = MutableStateFlow<os.meka.core.domain.MailNeedsCard?>(null)
    /**
     * Inbox autopilot, slice 6a: Needs you's "From your mail" card — the emails the server sorted as needing Meka, still
     * in the inbox, newest first, each with Done and Snooze; null when none. Follows sync and the minute tick (a snooze
     * ending brings its email back).
     */
    val mailNeeds: StateFlow<os.meka.core.domain.MailNeedsCard?> = _mailNeeds.asStateFlow()

    /** Takes back the last Done or Snooze on this device; null after an Undo. */
    private var mailNeedsUndo: (() -> Unit)? = null

    private val _mailChases = MutableStateFlow<os.meka.core.domain.MailChaseCard?>(null)
    /**
     * Inbox autopilot, slice 6b-3: Needs you's "Time to chase" card — each Waiting for item made from an email whose
     * chase day has come, with a chase drafted for him to send (only on his tap), Got it and Not yet; null when none.
     */
    val mailChases: StateFlow<os.meka.core.domain.MailChaseCard?> = _mailChases.asStateFlow()

    /** Takes back the last Send, Got it or Not yet on "Time to chase" on this device; null after an Undo. */
    private var mailChaseUndo: (() -> Unit)? = null

    private val _mailFinds = MutableStateFlow<os.meka.core.domain.MailFindCard?>(null)
    /**
     * Promises, receipts and renewals from email, slice 1: Needs you's "Renewals from your mail" card — each renewal the
     * server read from a renewal email, with Add (onto Lists → Renewals) and Not needed; null when none are open.
     */
    val mailFinds: StateFlow<os.meka.core.domain.MailFindCard?> = _mailFinds.asStateFlow()

    /** Takes back the last Add or Not needed on "Renewals from your mail" on this device; null after an Undo. */
    private var mailFindUndo: (() -> Unit)? = null

    private val _discord = MutableStateFlow<os.meka.core.domain.DiscordCards?>(null)
    /**
     * Discord channel watch, slice 1: Needs you's Discord block — one card per channel he follows with the posts the
     * Fold's listener kept since he last looked, each with Done; null when nothing is waiting. Information only.
     */
    val discord: StateFlow<os.meka.core.domain.DiscordCards?> = _discord.asStateFlow()

    private val _discordWatch = MutableStateFlow(os.meka.core.domain.DiscordWatchView(emptyList(), "Nothing from Discord yet", os.meka.core.domain.DiscordRules.EMPTY))
    /** Work mode → Discord on both apps: every channel Discord notified about, with Follow / Stop. */
    val discordWatch: StateFlow<os.meka.core.domain.DiscordWatchView> = _discordWatch.asStateFlow()

    /** Posts just Done on this device, hidden at once and written after the Undo window; Undo takes them back. */
    private var discordUndo: List<String>? = null

    private val _mailMoment = MutableStateFlow<os.meka.core.domain.MailMomentCard?>(null)
    /**
     * Inbox autopilot, slice 7: Needs you's "Ten-minute inbox" card — from the moment's time (morning or evening, his
     * choice, synced) while emails need him, until Not today; null otherwise. Start runs [mailMomentSession].
     */
    val mailMoment: StateFlow<os.meka.core.domain.MailMomentCard?> = _mailMoment.asStateFlow()

    private val _mailMomentSession = MutableStateFlow<os.meka.core.domain.MailMomentSession?>(null)
    /**
     * Slice 7: the ten-minute inbox under way on this device — one email at a time with the card's own answers and Skip;
     * its end says "Inbox clear" with the streak. Null when it isn't running. Not synced (each device runs its own).
     */
    val mailMomentSession: StateFlow<os.meka.core.domain.MailMomentSession?> = _mailMomentSession.asStateFlow()

    /** The session's state on this device: running, how many emails there were at Start, what was skipped (oldest first). */
    private var mailMomentRunning = false
    private var mailMomentStarted = 0
    private val mailMomentSkipped = mutableListOf<String>()

    /** Takes back the last Not today on this device; null after an Undo. */
    private var mailMomentUndo: (() -> Unit)? = null

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
        val kept = protectedSlots(day.epochDay) + listOfNotNull(
            os.meka.core.domain.DateNightRules.slot(dateNight.setting(), day.epochDay, cal),
            os.meka.core.domain.LifeAdminRules.keptSlot(lifeAdmin.setting(), lifeAdminWeek, day.epochDay),
        )
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
        // The life admin hour (slice 1) too.
        val kept = fasting.plannerMeals(day) + listOfNotNull(
            os.meka.core.domain.DateNightRules.block(dateNight.setting(), day.epochDay, ZoneCalendar(timeZone)),
            os.meka.core.domain.LifeAdminRules.block(lifeAdmin.setting(), lifeAdminWeek, day.epochDay),
        )
        // Protected time (slice 1): no tasks in the protected evening or on a protected day.
        val shield = protectedTime.setting()
        val schedule = work.schedule()
        val holidays = bankHolidays.calendar()
        val slept = os.meka.core.domain.SleepRules.lastNight(sleepSessions, day.startMs)
        // The commute counts everywhere, slice 4: on an office day nothing is planned in the commute.
        val commute = os.meka.core.domain.CommuteRules.bands(
            hoursOf(work.state(localClock(), day.epochDay), day.epochDay), day.epochDay, ZoneCalendar(timeZone),
        )
        DayPlanner.plan(
            all, visibleEvents(all), now, day, habits = goals.plannerHabits(), meals = kept.sortedBy { it.startMs }, sessions = sessions,
            protected = protectedSlots(day.epochDay),
            protectedNote = os.meka.core.domain.ProtectedRules.planNote(shield, day.epochDay, schedule, holidays),
            waitLabel = os.meka.core.domain.ProtectedRules.waitLabel(shield, day.epochDay, schedule, holidays),
            // Health Connect slice 3: a short night (read on the Fold only) plans a lighter day.
            taskCapMin = os.meka.core.domain.SleepRules.taskCap(slept),
            restNote = os.meka.core.domain.SleepRules.planNote(slept),
            commute = commute.map { DayPlanner.Slot(it.startMs, it.endMs) },
            commuteNote = os.meka.core.domain.CommuteRules.plannerNote(commute, now),
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

    /**
     * Train-time queue (4), 2026-10-18: the departures this device saw on today's live board, kept as a device-local
     * value ([os.meka.core.domain.TrainRideRules.KEY], never synced) because a train drops off the board once it has
     * left; read on first use, written back when it changes.
     */
    private var trainRideSeen: os.meka.core.domain.TrainRideSeen? = null
    private var trainRideLoaded = false

    private fun trainRideNow(office: os.meka.core.domain.OfficeWindow?, cal: os.meka.core.domain.LocalCalendar): os.meka.core.domain.TrainRide? {
        if (!trainRideLoaded) {
            trainRideLoaded = true
            trainRideSeen = os.meka.core.domain.TrainRideRules.decode(
                runCatching { localStore.transaction { localStore.localValue(os.meka.core.domain.TrainRideRules.KEY) } }.getOrNull(),
            )
        }
        val now = nowMs()
        val snap = lineStatus.snapshot()
        val commute = os.meka.core.domain.RouteRules.commute(office, now, cal)
        val leave = if (office != null && commute != null) {
            os.meka.core.domain.RouteRules.liveBoard(snap, commute, now)?.let { os.meka.core.domain.RouteRules.leaveBy(it, commute, office, now, cal) }
        } else null
        val seen = os.meka.core.domain.TrainRideRules.merge(trainRideSeen, snap.board, leave, now, cal)
        if (seen != trainRideSeen) {
            trainRideSeen = seen
            val text = os.meka.core.domain.TrainRideRules.encode(seen)
            runCatching { localStore.transaction { localStore.setLocalValue(os.meka.core.domain.TrainRideRules.KEY, text) } }
        }
        // Done for the day, the first train: this morning's train (the leave-by's pick) joins the recent mornings.
        val trains = os.meka.core.domain.FirstTrainRules.record(morningTrains(), seen)
        if (trains != morningTrainsList) {
            morningTrainsList = trains
            val text = os.meka.core.domain.FirstTrainRules.encode(trains)
            runCatching { localStore.transaction { localStore.setLocalValue(os.meka.core.domain.FirstTrainRules.KEY, text) } }
        }
        val names = os.meka.core.domain.Household.names
        return os.meka.core.domain.TrainRideRules.ride(seen, office, names.route?.change, names.homeStation, now, cal)
    }

    /**
     * Done for the day, the first train (2026-10-18): the trains the morning leave-by picked on recent office mornings,
     * a device-local value ([os.meka.core.domain.FirstTrainRules.KEY], never synced); read on first use.
     */
    private var morningTrainsList: List<os.meka.core.domain.MorningTrain>? = null

    private fun morningTrains(): List<os.meka.core.domain.MorningTrain> = morningTrainsList ?: os.meka.core.domain.FirstTrainRules.decode(
        runCatching { localStore.transaction { localStore.localValue(os.meka.core.domain.FirstTrainRules.KEY) } }.getOrNull(),
    ).also { morningTrainsList = it }

    /** Tomorrow's first train for Shut down, on an office day only. */
    private fun firstTrainTomorrow(state: WorkModeState, tomorrow: Long, cal: os.meka.core.domain.LocalCalendar): os.meka.core.domain.FirstTrainLine? {
        val band = os.meka.core.domain.CommuteRules.bands(hoursOf(state, tomorrow), tomorrow, cal).firstOrNull { it.toWork } ?: return null
        return os.meka.core.domain.FirstTrainRules.line(morningTrains(), tomorrow, band.startMinute, os.meka.core.domain.Household.names.walkHomeMin)
    }
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
            zoneLine = eventZoneLine(event),
            editable = canEditEvent(event),
            editNote = os.meka.core.domain.CalendarEditRules.notEditableReason(event) { p, a -> _editAccounts.value.any { it.provider == p && it.email == a } },
            edit = os.meka.core.domain.EditEventRules.note(event.id, _editsSeen.value, nowMs(), ZoneCalendar(timeZone)),
        )

    /**
     * Completeness slice 6: [event]'s time in its own zone when that clock differs from this device's
     * ([os.meka.core.domain.EventZoneRules]); null when the zone is unknown here or the clocks agree.
     */
    fun eventZoneLine(event: os.meka.core.domain.CalendarEvent): String? {
        val id = event.timeZone ?: return null
        val zone = runCatching { TimeZone.of(id) }.getOrNull() ?: return null
        return os.meka.core.domain.EventZoneRules.line(event, ZoneCalendar(timeZone), ZoneCalendar { zone })
    }

    /** Whether MEKA may change [event] in its calendar (a Google/Outlook account where editing is allowed). */
    private fun accountEditable(provider: String, account: String): Boolean =
        _editAccounts.value.any { it.provider == provider && it.email == account }

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

    /**
     * Fold review 17:56, slice 3: whose club session [event]'s whole series is ([child] "Theo"; null clears it), so the
     * Calendar and Today name him and a clash with work asks "who's taking Theo?". Returns the undo bar's line and who
     * it was before ([undoClubChild]), or null when the session names its child itself.
     */
    suspend fun setClubChild(event: os.meka.core.domain.CalendarEvent, child: String?): os.meka.core.domain.ClubChildSaved? = onCore {
        if (!os.meka.core.domain.ClubChildRules.canSet(event)) return@onCore null
        val before = eventActions.setClubChild(event, child)
        os.meka.core.domain.ClubChildSaved(event, os.meka.core.domain.ClubChildRules.savedLine(event, child, ZoneCalendar(timeZone)), before)
    }

    /** Undo for [setClubChild]: whoever was set before comes back (nobody, if nobody was). */
    suspend fun undoClubChild(saved: os.meka.core.domain.ClubChildSaved) = onCore {
        eventActions.setClubChild(saved.event, saved.previous)
        Unit
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
    /**
     * Done on the after-work summary: cleared on every device; chat texts blanked, calls keep their words and
     * recording for Activity → Calls. WhatsApp and Messages are untouched.
     */
    suspend fun clearAfterWork(): Int = onCore { afterWorkHiding.clear(); held.clear() }

    /**
     * Done on one card, or Clear all ([os.meka.core.domain.AfterWorkDone]): [ids] leave [afterWork] at once (the
     * Needs you card's counts follow) and are cleared on both devices after
     * [os.meka.core.domain.AfterWorkDone.UNDO_MS] unless [undoClearAfterWork] took them back first.
     */
    suspend fun clearAfterWorkSoon(ids: List<String>) {
        if (ids.isEmpty()) return
        onCore { afterWorkHiding += ids; refresh() }
        scope.launch {
            delay(os.meka.core.domain.AfterWorkDone.UNDO_MS)
            runCatching { commitAfterWorkClear(ids) }
        }
    }

    /** Undo on the bar after a card's Done: those items come back, nothing was written. */
    suspend fun undoClearAfterWork(ids: List<String>) = onCore { afterWorkHiding -= ids.toSet(); refresh() }

    /** Writes a waiting Done now (the delay ending, or the app going to the background); returns how many cleared. */
    suspend fun commitAfterWorkClear(ids: List<String>): Int = onCore {
        val still = ids.filter { it in afterWorkHiding }
        afterWorkHiding -= still.toSet()
        val n = held.clear(still)
        if (n == 0) refresh()
        n
    }

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
            val raw = rules.allowed(reply.proposals.map {
                os.meka.core.domain.RawRequestProposal(it.kind, it.title, it.date, it.time, it.words, it.type, it.repeat, it.cost, it.body, it.ref, it.until)
            })
            val message = os.meka.core.domain.RequestMessage(messageId, rules.PERSON, rules.quoteText(text, subject), atMs)
            val checked = os.meka.core.domain.MessageRequestRules.check(raw, today, cal.minuteOfDay(atMs), allowRenewal = true, allowReply = true, allowBooking = true)
            // Travel mode, slice 2b: a booking names the trip it falls on (one with no trip yet becomes an event).
            requestCards.save(message, rules.withTrips(checked, trips.items(), today))
        }
        return if (cards.isEmpty()) HandleOutcome.NothingToDo(rules.NOTHING_LINE) else HandleOutcome.Cards(cards, rules.readyLine(cards.size))
    }

    /**
     * Voice notes into actions, slice 2: the words of one voice note (never its recording) go to
     * `POST /v1/ai/voice-note` with its [subject] ("Voice note · 14:02"), today's date and his work days. The AI's tidy
     * note and the people it names come back for the phone to keep with the note; its proposals (task · event ·
     * reminder · renewal · an idea for Someday) are checked again here and saved as request cards that say "You
     * shared · 14:02", exactly as "Handle this" does (same lines, same Needs you, never a notification). Nothing is
     * added until Meka taps Add. The same words already read today make no second call: their open cards come back,
     * with no tidy note (the phone already keeps it).
     */
    suspend fun tidyVoiceNote(words: String, subject: String): VoiceNoteRead {
        val rules = os.meka.core.domain.HandleThisRules
        val vn = os.meka.core.domain.VoiceNoteRules
        if (!rules.worthAsking(words, null)) return VoiceNoteRead("", emptyList(), HandleOutcome.Unavailable(rules.NOTHING_LINE))
        val api = aiApi ?: return VoiceNoteRead("", emptyList(), HandleOutcome.Unavailable(os.meka.core.domain.AskRules.NOT_CONNECTED_LINE))
        val atMs = nowMs()
        val cal = ZoneCalendar(timeZone)
        val today = cal.epochDayOf(atMs)
        val messageId = rules.messageId(words, subject, today)
        if (onCore { requestCards.known(messageId) }) {
            val open = onCore { requestCards.open() }.filter { it.messageId == messageId }
            return VoiceNoteRead("", emptyList(),
                if (open.isEmpty()) HandleOutcome.NothingToDo(rules.ALREADY_LINE) else HandleOutcome.Cards(open, rules.readyLine(open.size)))
        }
        val request = onCore {
            val max = os.meka.core.wire.VoiceNoteCodec.MAX_WORK
            os.meka.core.wire.VoiceNoteCodec.Request(
                date = os.meka.core.domain.AskRules.isoDate(today),
                now = "${os.meka.core.domain.CivilDate.longLabel(today)} ${os.meka.core.domain.CivilDate.fromEpochDay(today).year} · ${LocalClock.formatMinute(cal.minuteOfDay(atMs))}",
                work = work.schedule().plainLine.let { if (it.length <= max) it else it.take(max).substringBeforeLast(", ") },
                subject = rules.outgoingSubject(subject), text = rules.outgoingText(words),
            )
        }
        val reply = try { api.voiceNote(request) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return VoiceNoteRead("", emptyList(), HandleOutcome.Unavailable(os.meka.core.domain.AskRules.OFFLINE_LINE))
        }
        if (reply.state != os.meka.core.wire.AskCodec.Response.ANSWERED) {
            return VoiceNoteRead("", emptyList(), HandleOutcome.Unavailable(os.meka.core.domain.AskRules.unavailableLine(reply.state, reply.reason)))
        }
        val tidy = vn.tidyNote(reply.note, words)
        val people = vn.people(reply.people)
        val cards = onCore {
            val raw = vn.allowed(reply.proposals.map {
                os.meka.core.domain.RawRequestProposal(it.kind, it.title, it.date, it.time, it.words, it.type, it.repeat, it.cost, it.body, it.ref, it.until)
            })
            val message = os.meka.core.domain.RequestMessage(messageId, rules.PERSON, rules.quoteText(words, subject), atMs)
            val checked = os.meka.core.domain.MessageRequestRules.check(raw, today, cal.minuteOfDay(atMs), allowRenewal = true, allowIdea = true)
            requestCards.save(message, checked)
        }
        val outcome = if (cards.isEmpty()) HandleOutcome.NothingToDo(if (tidy.isEmpty()) rules.NOTHING_LINE else vn.tidiedLine(0))
        else HandleOutcome.Cards(cards, if (tidy.isEmpty()) rules.readyLine(cards.size) else vn.tidiedLine(cards.size))
        return VoiceNoteRead(tidy, people, outcome)
    }

    /**
     * Voice notes into actions, slice 3 (the Mac keeps its own notes, like the phone): [notes] newest first as Voice
     * notes shows them, days and times in this device's time zone; [onMac] for the Mac's wording.
     */
    fun voiceNoteRows(notes: List<os.meka.core.domain.VoiceNote>, onMac: Boolean): List<os.meka.core.domain.VoiceNoteRow> {
        val vn = os.meka.core.domain.VoiceNoteRules
        val cal = ZoneCalendar(timeZone)
        val today = cal.epochDayOf(nowMs())
        return vn.ordered(notes).map { n ->
            vn.row(n, cal.epochDayOf(n.atMs), cal.minuteOfDay(n.atMs), vn.expiresAtMs(n)?.let { cal.epochDayOf(it) }, today, onMac)
        }
    }

    /** "Voice note · 14:02" for a note made at [atMs] (the subject its words are read under). */
    fun voiceNoteSubject(atMs: Long): String =
        os.meka.core.domain.VoiceNoteRules.subject(ZoneCalendar(timeZone).minuteOfDay(atMs))

    /** Daily's Voice notes row for the notes this device keeps (null: the row's fixed words). */
    fun voiceNotesLine(notes: List<os.meka.core.domain.VoiceNote>): os.meka.core.domain.DailyLine? {
        val cal = ZoneCalendar(timeZone)
        return os.meka.core.domain.DailyRules.voiceNotes(notes.size, notes.maxOfOrNull { it.atMs }?.let { cal.epochDayOf(it) }, cal.epochDayOf(nowMs()))
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
            // A question from someone Meka watches for in a group (Fold review 2026-10-12, bug 3): "Jordan asked".
            val watched = (route as? os.meka.core.domain.TriageRoute.AskAi)?.watched == true
            TriageRead.Read(triageCards.save(item, checked, watched = watched), requests)
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
     * A busy chat's summary (messages grouped per chat, slice 3c): the Fold calls this after triaging new messages and
     * when Needs you shows. The open chats with [os.meka.core.domain.ChatSummaryRules.MIN_MESSAGES] or more messages that
     * have no summary for their messages yet (or news and a summary older than ten minutes) go together in **one** call
     * to `POST /v1/ai/chat-summary` — each chat's name, whether it is a group, and each message's sender, time and MEKA's
     * gist (never the message's own text, a number or a message kept from MEKA's AI) — and come back with one line each,
     * checked again here and synced so the Mac's card shows it too. Nothing is sent, replied to or marked read. Nothing
     * due → [ChatSummaryRead.NotDue]; offline or a failed call writes nothing, so the next call tries again; with MEKA's
     * AI off or the month's budget spent nothing is written and the cards keep their top lines.
     */
    suspend fun summariseChats(settings: os.meka.core.domain.TriageSettings = os.meka.core.domain.TriageSettings()): ChatSummaryRead {
        val rules = os.meka.core.domain.ChatSummaryRules
        val cal = ZoneCalendar(timeZone)
        val due = onCore {
            val chats = os.meka.core.domain.TriageChatRules.chats(triageCards.open())
            rules.toSummarise(chats, chatSummaryStore.all(), settings, nowMs())
        }
        if (due.isEmpty()) return ChatSummaryRead.NotDue
        val api = aiApi ?: return ChatSummaryRead.Unavailable(os.meka.core.domain.AskRules.NOT_CONNECTED_LINE)
        val codec = os.meka.core.wire.ChatSummaryCodec
        val request = onCore {
            val now = nowMs()
            val today = cal.epochDayOf(now)
            os.meka.core.wire.ChatSummaryCodec.Request(
                date = os.meka.core.domain.AskRules.isoDate(today),
                now = "${os.meka.core.domain.CivilDate.longLabel(today)} ${os.meka.core.domain.CivilDate.fromEpochDay(today).year} · ${LocalClock.formatMinute(cal.minuteOfDay(now))}",
                chats = due.map { c ->
                    os.meka.core.wire.ChatSummaryCodec.Chat(
                        c.name.take(codec.MAX_NAME), c.isGroup,
                        c.lines.map { os.meka.core.wire.ChatSummaryCodec.Line(it.from.take(os.meka.core.wire.MessageRequestCodec.MAX_SENDER), it.at, it.text.take(codec.MAX_LINE)) },
                    )
                },
            )
        }
        val reply = try { api.chatSummary(request) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return ChatSummaryRead.Unavailable(os.meka.core.domain.AskRules.OFFLINE_LINE)
        }
        if (reply.state != os.meka.core.wire.AskCodec.Response.ANSWERED) {
            return ChatSummaryRead.Unavailable(os.meka.core.domain.AskRules.unavailableLine(reply.state, reply.reason))
        }
        return onCore {
            val checked = rules.check(due, reply.chats.map { os.meka.core.domain.ChatSummaryRules.Raw(it.name, it.summary) })
            due.forEach { c -> chatSummaryStore.save(c, checked[c.chatKey]) }
            ChatSummaryRead.Read(checked.filterValues { it != null }.keys.toList())
        }
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
     * Done on a chat's card (V1, messages grouped per chat, slice 1): every message card of that chat
     * ([os.meka.core.domain.TriageChat.ids]) leaves Needs you on every device as seen, in one go. Nothing is sent or
     * marked read anywhere. Returns how many were still open.
     */
    suspend fun seenTriageChat(messageIds: List<String>): Int =
        onCore { messageIds.count { triageCards.resolve(it, os.meka.core.domain.TriageResolution.SEEN) } }

    /**
     * WhatsApp replies, slice 2 (the Fold): which open reply cards the phone can still send itself ([live]: the message
     * ids whose notification Reply it holds), synced so the Mac offers Send (via your phone). Writes only changes.
     */
    suspend fun notePhoneLive(live: Set<String>): Int = onCore { triageCards.markPhoneLive(live) }

    /**
     * Send (via your phone) on the Mac (WhatsApp replies, slice 2), Meka's tap: asks the Fold to send [text] as the
     * reply to [messageId] through the notification it still holds. Nothing is sent here; the Fold sends on its next
     * sync (within [os.meka.core.domain.TriageCards.PHONE_SEND_TTL_MS], else never) and answers Sent or why not.
     */
    suspend fun requestPhoneSend(messageId: String, text: String): Boolean =
        onCore { triageCards.requestPhoneSend(messageId, text) }

    /** The Fold: the Mac's Send (via your phone) requests to answer now, oldest first. */
    suspend fun phoneSendsDue(): List<os.meka.core.domain.PhoneSendRequest> = onCore { triageCards.phoneSendsDue() }

    /**
     * The Fold answers the request asked at [atMs]: [sent] true when the notification's Reply took the words (the card
     * resolves as Sent on both devices), false when the notification had gone (the Mac says so and offers Reply in
     * WhatsApp). False when that request was answered or replaced already.
     */
    suspend fun phoneSendDone(messageId: String, atMs: Long, sent: Boolean): Boolean = onCore {
        triageCards.phoneSendDone(
            messageId, atMs,
            if (sent) os.meka.core.domain.PhoneSendOutcome.SENT else os.meka.core.domain.PhoneSendOutcome.GONE,
        )
    }

    /**
     * Step one of Approve on an approval card (Trading approvals, slice 2): what this device must sign
     * ([os.meka.core.domain.ApprovalPrep.message]) with its approval key, which only Meka's fingerprint unlocks, and
     * the decision key and time that text names; or [os.meka.core.domain.ApprovalPrep.refusal] (gone, answered,
     * expired), shown without asking for the fingerprint. Nothing is written.
     */
    suspend fun prepareApproval(approvalId: String): os.meka.core.domain.ApprovalPrep =
        onCore { approvalStore.prepare(approvalId, ids.next(), replica.deviceId) }

    /**
     * Step two: the yes, once the app's fingerprint prompt has unlocked this device's approval key and it signed
     * [prepareApproval]'s message. [decisionKey] and [decidedAtMs] are the prep's; [publicKeyBase64] is the key's public
     * half (registered for this device with [keyLabel] if it isn't already) and [signatureBase64] the DER signature. A
     * missing signature is refused ([os.meka.core.domain.ApprovalRefusal.NEEDS_BIOMETRIC]); a prep older than five
     * minutes too. The server and Kestrel check the signature; the yes is recorded once and in Activity.
     */
    suspend fun approve(
        approvalId: String, decisionKey: String, decidedAtMs: Long, publicKeyBase64: String, signatureBase64: String, keyLabel: String,
    ): ApprovalAnswer = onCore {
        if (publicKeyBase64.isNotBlank() && signatureBase64.isNotBlank()) approvalKeys.register(publicKeyBase64, keyLabel)
        answerApproval(
            approvalId, approve = true, os.meka.core.domain.ApprovalConfirmation.BIOMETRIC, decisionKey,
            os.meka.core.domain.ApprovalSignature(publicKeyBase64, signatureBase64), decidedAtMs,
        )
    }

    /**
     * Records this device's approval key (its public half; "Fold" or "Mac" as [label]) so it is known before the first
     * yes, and a renewed key (after a fingerprint change) replaces the old one. True when something was written.
     */
    suspend fun registerApprovalKey(publicKeyBase64: String, label: String): Boolean = onCore { approvalKeys.register(publicKeyBase64, label) }

    /** Decline on an approval card: no fingerprint needed (saying no is always safe); in Activity. */
    suspend fun declineApproval(approvalId: String): ApprovalAnswer =
        onCore { answerApproval(approvalId, approve = false, os.meka.core.domain.ApprovalConfirmation.TAP) }

    private fun answerApproval(
        id: String, approve: Boolean, confirmation: os.meka.core.domain.ApprovalConfirmation, key: String = ids.next(),
        signature: os.meka.core.domain.ApprovalSignature? = null, decidedAtMs: Long? = null,
    ): ApprovalAnswer {
        val before = approvalStore.find(id)
        return when (val d = approvalStore.decide(id, approve, confirmation, key, replica.deviceId, signature, decidedAtMs)) {
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
        done.bookingId?.let { id -> if (trips.removeBooking(id)) undone = true }
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
            // Travel mode, slice 2b: into the trip its day falls on now; a planned task when that trip is gone.
            is os.meka.core.domain.RequestPlan.AddBooking -> {
                val trip = os.meka.core.domain.TripRules.tripFor(os.meka.core.domain.TripRules.open(trips.items(), today), plan.entry.day)
                if (trip == null) {
                    val asTask = rules.bookingAsTask(plan)
                    val id = tasks.create(NewTask(asTask.title))
                    rules.taskDay(asTask, today)?.let { day -> tasks.setWhen(id, day, asTask.minute) }
                    followBlocks(listOf(id))
                    RequestDone(rules.doneLine(asTask, card.proposal.kind, today), id, null)
                } else {
                    val entry = plan.entry.copy(tripId = trip.id)
                    if (os.meka.core.domain.TripAskRules.already(trip, entry)) {
                        RequestDone(os.meka.core.domain.TripAskRules.ALREADY, null, null)
                    } else {
                        val id = trips.addBooking(entry)
                        RequestDone(rules.doneLine(plan.copy(entry = entry, tripName = trip.name), card.proposal.kind, today), null, null, bookingId = id)
                    }
                }
            }
            // Voice notes, slice 2: an idea into Someday → Ideas; Undo deletes it (a Someday item is a task).
            is os.meka.core.domain.RequestPlan.AddIdea -> {
                val id = lists.addSomeday(plan.title, os.meka.core.domain.SomedayKind.IDEA)
                RequestDone(rules.doneLine(plan, card.proposal.kind, today), id, null)
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

    /**
     * People, slice 4: the Fold passes its own lists (Work mode → People's Family and Always notify, and "Watch for
     * requests from") so those people have a People row before any promise names them. Kept in memory on this device
     * only: the lists stay on the phone and are never synced or sent. The Mac never calls this.
     */
    suspend fun setListedPeople(family: List<String>, alwaysNotify: List<String>, watched: List<String>) = onCore {
        val next = os.meka.core.domain.ListedPeople(family.toSet(), alwaysNotify.toSet(), watched.toSet())
        if (next != listedPeople) {
            listedPeople = next
            refresh()
        }
    }

    /** People, slice 1: something you promised [to] someone, by [byInDays] from today (null: no date). Nothing is sent. */
    suspend fun addPromise(title: String, to: String?, byInDays: Int?): String = onCore { lists.addPromise(title, to, byInDays) }
    suspend fun setPromiseBy(id: String, days: Int?) = onCore { lists.setPromiseBy(id, days) }
    /** "Kept": the promise is done; it leaves the list. */
    suspend fun keptPromise(id: String) = onCore { lists.kept(id) }
    suspend fun editPromise(id: String, title: String?, to: String?, notes: String?) = onCore { lists.editPromise(id, title, to, notes) }
    suspend fun deletePromise(id: String) = onCore { lists.deletePromise(id) }

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
    suspend fun renewalDone(id: String) = onCore {
        // Returns and warranties, completeness: "Sent it back" on a return reminder also waits for the refund.
        val item = renewals.items().firstOrNull { it.id == id }
        renewals.done(id)
        if (item != null && item.repeats == RenewalRepeat.NONE && os.meka.core.domain.MailReturnRules.isReturn(item.title)) {
            val shop = os.meka.core.domain.MailReturnRules.merchantOf(item.title)
            lists.addWaiting(
                os.meka.core.domain.MailReturnRules.refundTitle(shop, item.costPence), shop,
                os.meka.core.domain.MailReturnRules.REFUND_CHASE_DAYS, os.meka.core.domain.MailReturnRules.refundWaitId(id),
            )
        }
    }
    suspend fun setRenewalDue(id: String, dueDay: Long) = onCore { renewals.setDue(id, dueDay) }
    suspend fun setRenewalRepeat(id: String, repeats: RenewalRepeat) = onCore { renewals.setRepeat(id, repeats) }
    suspend fun setRenewalCost(id: String, cost: String?) = onCore { renewals.setCost(id, cost) }
    /**
     * Bills due: where Meka pays it ("octopus.energy"); blank clears it. False (nothing written) when it isn't a web
     * address or the item is gone; never throws, so the Mac can call it. MEKA only opens the link, never pays.
     */
    suspend fun setRenewalPayLink(id: String, link: String?): Boolean = onCore {
        try { renewals.setPayLink(id, link) } catch (_: os.meka.core.domain.ValidationException) { false }
    }
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
    suspend fun setCarRegistration(text: String): Boolean = setCarRegistration(os.meka.core.domain.CarRules.ENTITY_ID, text)

    /**
     * Two cars: sets car [carId]'s registration (blank clears it). That car's dates on the radar whose subject was its
     * old key (or none, for the first car) take the new one, so they stay its own. Never throws.
     */
    suspend fun setCarRegistration(carId: String, text: String): Boolean = onCore {
        rekeyCar(carId) { try { car.setRegistration(carId, text) } catch (_: os.meka.core.domain.ValidationException) { false } }
    }

    /** Two cars: names car [carId] ("the Golf"; blank clears it); its dates follow when the name is their key. Never throws. */
    suspend fun setCarName(carId: String, text: String): Boolean = onCore { rekeyCar(carId) { car.setName(carId, text) } }

    /** Runs [change] on car [carId]; when it changed, the car's own dates carrying its old key (or none) take the new one. */
    private fun rekeyCar(carId: String, change: () -> Boolean): Boolean {
        val before = car.cars()
        val old = before.firstOrNull { it.id == carId } ?: return false
        val own = os.meka.core.domain.CarRules.itemsOf(carId, renewals.items(), before)
        if (!change()) return false
        val now = car.car(carId)?.key
        if (now != old.key) {
            own.filter { it.subject.isNullOrBlank() || (old.key != null && it.subject?.trim().equals(old.key, ignoreCase = true)) }
                .forEach { renewals.edit(it.id, subject = now ?: "") }
        }
        return true
    }

    /** Two cars: adds another car named [name] (blank for none). Its id, or null at the limit. Never throws. */
    suspend fun addCar(name: String): String? = onCore { car.addCar(name) }

    /**
     * Two cars: removes car [carId] (never the first); its dates stay on the radar under no car. [carRemovedLine] says
     * it; [undoRemoveCar] puts it back with its dates. False when nothing changes.
     */
    suspend fun removeCar(carId: String): Boolean = onCore { car.setRemoved(carId, true) }

    suspend fun undoRemoveCar(carId: String): Boolean = onCore { car.setRemoved(carId, false) }

    /**
     * Car and parking, slice 2: starts (or replaces) car [carId]'s parking timer from typed text ("2 h", "until 15:30",
     * "till 6pm RingGo 81234"). The line to show ("Parking timer until 15:30 · reminds you at 15:20"), or null when it
     * doesn't read (say [os.meka.core.domain.ParkingTimerRules.NOT_READ]). Never throws.
     */
    suspend fun setParkingTimer(carId: String, text: String): String? = onCore {
        val now = nowMs()
        val cal = ZoneCalendar(timeZone)
        val t = parkingTimers.set(carId, text, now, cal)
            // Slice 5: a pasted confirmation whose time has gone says so rather than "doesn't read".
            ?: return@onCore os.meka.core.domain.ParkingConfirmationRules.read(text, null, now, cal)?.takeIf { it.ended }
                ?.let { os.meka.core.domain.ParkingConfirmationRules.endedLine(it, now, cal) }
        os.meka.core.domain.ParkingTimerRules.setLine(t, os.meka.core.domain.ParkingRules.titleOf(carId, _cars.value), now, cal)
    }

    /**
     * Car and parking, slice 5: what the share sheet offers for shared text that is a parking confirmation (RingGo,
     * JustPark, PayByPhone…), read on the device: "Parking timer until 15:30 · RingGo 81234", the car (from its
     * registration in the text, or the only car) or the choices, or "That parking ended at 15:30 …". Null when the
     * text isn't one. Nothing starts until [startParkingFromShared]. Never throws.
     */
    suspend fun parkingFromShared(text: String, subject: String?): os.meka.core.domain.ParkingShareView? = onCore {
        runCatching {
            os.meka.core.domain.ParkingConfirmationRules.view(text, subject, nowMs(), ZoneCalendar(timeZone), _cars.value)
        }.getOrNull()
    }

    /**
     * Starts car [carId]'s parking timer from a shared confirmation (on his tap in the share sheet). The line
     * ("Parking timer until 15:30 · reminds you at 15:20"), or null when it doesn't read or has already ended.
     */
    suspend fun startParkingFromShared(carId: String, text: String, subject: String?): String? = onCore {
        if (carId !in os.meka.core.domain.CarRules.CAR_IDS) return@onCore null
        val now = nowMs()
        val cal = ZoneCalendar(timeZone)
        val p = os.meka.core.domain.ParkingConfirmationRules.read(text, subject, now, cal, _cars.value)?.takeIf { !it.ended }
            ?: return@onCore null
        parkingTimers.put(os.meka.core.domain.ParkingConfirmationRules.timer(p, carId, now))
        val t = parkingTimers.timer(carId) ?: return@onCore null
        os.meka.core.domain.ParkingTimerRules.setLine(t, os.meka.core.domain.ParkingRules.titleOf(carId, _cars.value), now, cal)
    }

    /** Adds [minutes] to car [carId]'s timer (+15 min, +30 min, +1 h); the new line, or null without a timer. */
    suspend fun extendParkingTimer(carId: String, minutes: Int): String? = onCore {
        val now = nowMs()
        val cal = ZoneCalendar(timeZone)
        val t = parkingTimers.extend(carId, minutes, now) ?: return@onCore null
        os.meka.core.domain.ParkingTimerRules.setLine(t, os.meka.core.domain.ParkingRules.titleOf(carId, _cars.value), now, cal)
    }

    /** Car and parking, slice 6: car [carId] takes [fuelCode] ("E10", "E5", "B7", "SDV"); synced. False when nothing changes. */
    suspend fun setCarFuel(carId: String, fuelCode: String): Boolean = onCore {
        val k = os.meka.core.domain.FuelKind.of(fuelCode) ?: return@onCore false
        car.setFuel(carId, k).also { if (it) refresh() }
    }

    /**
     * Car and parking, slice 6: the cheapest fuel near [lat],[lon] for every fuel the cars take. The point is rounded to
     * about 1 km here, before anything is sent ([os.meka.core.domain.HereRules.round]); the answer is neither stored nor
     * synced. [lat]/[lon] NaN (no location) gives the "allow location" state. Never throws.
     */
    suspend fun fuelNearby(lat: Double, lon: Double): os.meka.core.domain.FuelView {
        val rules = os.meka.core.domain.FuelRules
        val point = os.meka.core.domain.HereRules.round(lat, lon)
            ?: return os.meka.core.domain.FuelView(rules.NO_LOCATION, rules.NO_LOCATION_LINE, emptyList())
        val api = fuelApi ?: return os.meka.core.domain.FuelView(rules.OFF, rules.OFF_LINE, emptyList())
        val fuels = onCore { rules.fuelsFor(_cars.value) }
        val answers = fuels.map { (fuel, cars) ->
            val a = try {
                val r = api.fuelNearby(point.lat, point.lon, fuel.code)
                r.state to r.stations.map {
                    os.meka.core.domain.FuelStation(it.name, it.brand, it.postcode, it.lat, it.lon, it.pence, it.updatedMs, it.distanceM)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A server's refusal or error ("HTTP 500 from …") is "not available"; no network is "offline".
                (if (e.message?.startsWith("HTTP ") == true || e !is os.meka.core.sync.TransportException) rules.FAILED else rules.OFFLINE) to emptyList()
            }
            Triple(fuel, cars, a)
        }
        return onCore { rules.view(answers, nowMs(), ZoneCalendar(timeZone)) }
    }

    private val endedTimers = mutableMapOf<String, os.meka.core.domain.ParkingTimer>()

    /** Ends car [carId]'s timer ([undoEndParkingTimer] puts it back); "Parking timer ended · the Golf", or null without one. */
    suspend fun endParkingTimer(carId: String): String? = onCore {
        val t = parkingTimers.clear(carId) ?: return@onCore null
        endedTimers[carId] = t
        os.meka.core.domain.ParkingTimerRules.clearedLine(os.meka.core.domain.ParkingRules.titleOf(carId, _cars.value))
    }

    suspend fun undoEndParkingTimer(carId: String): Boolean = onCore {
        val t = endedTimers.remove(carId) ?: return@onCore false
        if (parkingTimers.timer(carId) != null) return@onCore false
        parkingTimers.put(t)
        true
    }

    /** "Removed the Golf · its dates stay on the radar". */
    fun carRemovedLine(title: String): String = os.meka.core.domain.CarRules.removedLine(title)

    /**
     * Car on autopilot: adds one of [os.meka.core.domain.CarRules.PRESETS] to the radar (a placeholder date a month
     * ahead for Meka to set, reminders four weeks and a week before). The new item's id, or null when it is already
     * there or isn't one (never throws).
     */
    suspend fun addCarDate(presetId: String): String? = addCarDate(os.meka.core.domain.CarRules.ENTITY_ID, presetId)

    /** Two cars: the same for car [carId]; the new date carries the car's key so it stays that car's. */
    suspend fun addCarDate(carId: String, presetId: String): String? = onCore {
        val cars = car.cars()
        val c = cars.firstOrNull { it.id == carId && !it.removed } ?: return@onCore null
        val own = os.meka.core.domain.CarRules.itemsOf(carId, renewals.items(), cars)
        try { renewals.addCarDate(presetId, todayEpochDay(), c.registration, carCheck(), own, c.key) } catch (_: os.meka.core.domain.ValidationException) { null }
    }

    /**
     * Car on autopilot, slice 2: moves the radar's MOT or tax date ([presetId] "mot" / "tax") to the DVLA's day (the Car
     * row's "Use the DVLA's date"). Only on Meka's tap; false when there is no DVLA day, no such date on the radar, or
     * it already matches. Never throws.
     */
    suspend fun useDvlaDate(presetId: String): Boolean = useDvlaDate(os.meka.core.domain.CarRules.ENTITY_ID, presetId)

    /** Two cars: the same for car [carId] (the DVLA's answer must be for its registration). */
    suspend fun useDvlaDate(carId: String, presetId: String): Boolean = onCore {
        val p = os.meka.core.domain.CarRules.preset(presetId) ?: return@onCore false
        val cars = car.cars()
        val c = cars.firstOrNull { it.id == carId } ?: return@onCore false
        val check = os.meka.core.domain.CarCheckRules.forRegistration(carCheck(), c.registration)
        val day = os.meka.core.domain.CarCheckRules.dayFor(p.id, check) ?: return@onCore false
        val item = os.meka.core.domain.CarRules.itemsOf(carId, renewals.items(), cars).sortedBy { it.dueDay }
            .firstOrNull { os.meka.core.domain.CarRules.matches(p, it) } ?: return@onCore false
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
     * Adds one typed line to the card under [section] (`call` · `documents` · `accounts`): "GP: Dr Patel, 01632 123456".
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
    suspend fun queueDone(id: String, done: Boolean): Boolean = onCore {
        val changed = trainQueue.setDone(id, done)
        // Completeness: an article's offline words go once it's done (Back fetches them again on Wi-Fi).
        if (changed && done) { dropQueuePages(setOf(id)); setQueueFailures(queuePageFailures) }
        changed
    }

    /** Takes a saved link off the queue for good (both apps); its saved words go from this device too. */
    suspend fun removeFromQueue(id: String): Boolean = onCore {
        val removed = trainQueue.remove(id)
        if (removed) { dropQueuePages(setOf(id)); setQueueFailures(queuePageFailures) }
        removed
    }

    // ---- Reading and learning (V1, slice 1; Ask → Daily → Reading on both apps) ----

    /**
     * Adds the book, podcast or course [text] names ("Atomic Habits by James Clear, 320 pages", "podcast The Rest Is
     * History", a link) under up next, or on the go when it starts "reading …". Refused when it names nothing.
     */
    suspend fun addReading(text: String, subject: String? = null): os.meka.core.domain.ReadingSaid =
        onCore { reading.add(text, subject) }

    /** Starts one from up next (or picks one up again). */
    suspend fun startReading(id: String): os.meka.core.domain.ReadingSaid = onCore { reading.start(id) }

    /** How far he's got: "120", "p 120 of 320", "45%", "episode 4"; the end finishes it. Refused when it isn't one. */
    suspend fun readingProgress(id: String, text: String): os.meka.core.domain.ReadingSaid = onCore { reading.progress(id, text) }

    suspend fun finishReading(id: String): os.meka.core.domain.ReadingSaid = onCore { reading.finish(id) }

    suspend fun putDownReading(id: String): os.meka.core.domain.ReadingSaid = onCore { reading.putDown(id) }

    suspend fun removeReading(id: String): os.meka.core.domain.ReadingSaid = onCore { reading.remove(id) }

    /**
     * Reading and learning, slice 3: what the share sheet offers for a shared book, podcast or course (a link from a
     * book, podcast or course site, or a line like "Dune by Frank Herbert"); null when it isn't one. Read here, by
     * rules; nothing is fetched. Pure and cheap.
     */
    fun readingFromShared(text: String, subject: String?): os.meka.core.domain.ReadingShareView? =
        runCatching { os.meka.core.domain.ReadingShareRules.offer(text, subject) }.getOrNull()

    /** Adds a share to Reading's UP NEXT on his tap (Add to Reading in the share sheet, or the Mac's Services entry). */
    suspend fun addReadingFromShared(text: String, subject: String? = null): os.meka.core.domain.ReadingSaid =
        onCore { reading.addShared(text, subject) }

    /**
     * Reading and learning, slice 4: the reading goal, books a year ("12", "24 books", "none" clears it); synced, so Goals
     * on both apps tracks it. Undo puts the one before back.
     */
    suspend fun setReadingGoal(text: String): os.meka.core.domain.ReadingSaid =
        onCore { reading.setGoal(text, ZoneCalendar(timeZone)) }

    /** Takes back the last reading command (its [os.meka.core.domain.ReadingSaid.undo] token); false once too late. */
    suspend fun undoReading(token: String): Boolean = onCore { reading.undo(token) }

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
    /**
     * Pages that couldn't be fetched or had too little to read: how often and when last (device-local, so it outlasts a
     * restart; tried again after 6 hours, left online for good after three tries).
     */
    private var queuePageFailures: Map<String, os.meka.core.domain.QueueFailure> = emptyMap()
    private val _queueOnline = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Train-time queue completeness: the waiting articles whose words this device couldn't keep, each with its row line
     * ("Online only for now · tries again on Wi-Fi later", "Online only · this page can't be kept to read offline").
     */
    val queueOnline: StateFlow<Map<String, String>> = _queueOnline.asStateFlow()

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
        queuePageFailures = os.meka.core.domain.ReaderCodec.decodeFailures(
            runCatching { localStore.transaction { localStore.localValue(os.meka.core.domain.ReaderCodec.FAILED_KEY) } }.getOrNull(),
        )
        refreshQueueOnline()
    }

    /** Keeps the failed tries for links still waiting (on this device) and redraws the rows' "online only" lines. */
    private fun setQueueFailures(failures: Map<String, os.meka.core.domain.QueueFailure>) {
        val live = os.meka.core.domain.ReaderRules.liveFailures(failures, trainQueue.items())
        if (live != queuePageFailures) {
            runCatching {
                localStore.transaction {
                    localStore.setLocalValue(
                        os.meka.core.domain.ReaderCodec.FAILED_KEY,
                        live.takeIf { it.isNotEmpty() }?.let(os.meka.core.domain.ReaderCodec::encodeFailures),
                    )
                }
            }
            queuePageFailures = live
        }
        refreshQueueOnline()
    }

    private fun refreshQueueOnline() {
        _queueOnline.value = os.meka.core.domain.ReaderRules.onlineLines(trainQueue.items(), _queuePages.value.keys, queuePageFailures)
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
        setQueueFailures(queuePageFailures)
        os.meka.core.domain.ReaderRules.toFetch(items, _queuePages.value.keys, queuePageFailures, nowMs())
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
            if (item != null) setQueueFailures(queuePageFailures + (id to os.meka.core.domain.ReaderRules.failed(queuePageFailures[id], nowMs())))
            false
        } else {
            val index = _queuePages.value + (id to os.meka.core.domain.ReaderRules.rowLine(page))
            runCatching {
                localStore.transaction {
                    localStore.setLocalValue(os.meka.core.domain.ReaderCodec.key(id), os.meka.core.domain.ReaderCodec.encode(page))
                    localStore.setLocalValue(os.meka.core.domain.ReaderCodec.INDEX_KEY, os.meka.core.domain.ReaderCodec.encodeIndex(index))
                }
            }.onSuccess { _queuePages.value = index; setQueueFailures(queuePageFailures - id) }.isSuccess
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

    // ---- Weekly reflection (V1, slice 1; the Review tab) ----

    /**
     * Saves the reflection for the week Review shows. With [keepWins] each line of what went well is also kept in the
     * work wins log (the same line once); a [goalId] from the one thing's suggestion, while its title is still in
     * [matters], makes that goal one of the following week's goals (up to three). The week is marked reviewed too.
     * Refused (nothing written) when all three are empty.
     */
    suspend fun saveReflection(wentWell: String, drop: String, matters: String, goalId: String?, keepWins: Boolean): os.meka.core.domain.ReflectionSaved = onCore {
        val ws = _review.value.weekStart
        if (!reflections.save(ws, wentWell, drop, matters)) {
            return@onCore os.meka.core.domain.ReflectionSaved(os.meka.core.domain.ReflectionRules.EMPTY_REFUSED, true)
        }
        val winsAdded = if (!keepWins) 0 else os.meka.core.domain.ReflectionRules.winLines(wentWell).count { line ->
            wins.add(line, ws).let { !it.refused && it.id != null }
        }
        val today = todayEpochDay()
        val goal = os.meka.core.domain.ReflectionRules.goalToPick(matters, goalId, _goals.value.goals)
            ?.takeIf { g -> g.weekOf == ws + 7 || runCatching { goals.pickForWeek(g.id, ws + 7, true) }.getOrDefault(false) }
        if (!_review.value.reviewed) review.markReviewed(ws)
        refresh()
        os.meka.core.domain.ReflectionSaved(
            os.meka.core.domain.ReflectionRules.savedSay(winsAdded, goal?.title, ws + 7 > today), false,
        )
    }

    /** The week "reflect on my week" was said for (slice 2): its reflection stays open and lit until saved. */
    private var reflectRequestedWeek: Long? = null

    /**
     * Weekly reflection, slice 2: "reflect on my week" said to Talk. Review moves to the week Today's card is about
     * (this week, or last week on a Monday), a skip for it is undone, and its reflection opens lit as on Sunday
     * evening; nothing is written until Save. The apps then show Review.
     */
    suspend fun openReflection() = onCore {
        reviewOffset = _review.value.card.offset
        refresh()
        val ws = _review.value.weekStart
        if (reflections.all().firstOrNull { it.weekStart == ws }?.skipped == true) reflections.skip(ws, false)
        reflectRequestedWeek = ws
        refresh()
    }

    /**
     * Weekly reflection, slice 3: when on Sunday the review card, its heads-up and the reflection come ([minute], a
     * quarter hour from 12:00 to 22:00; synced, shared by both). Returns what to say, or null when nothing changed.
     */
    suspend fun setReviewTime(minute: Int): String? = onCore {
        if (!review.setStartMinute(minute)) return@onCore null
        refresh()
        os.meka.core.domain.ReviewRules.startSaid(minute)
    }

    /**
     * Weekly reflection, slice 4: "Later tonight" on Sunday's review, from Today's card ([key] null) or its heads-up
     * ([key] that notice's key, which must still be about the card's week). The card leaves Today and the heads-up comes
     * back two hours on (never after 22:00) on both devices. Returns "Your weekly review comes back at 20:15", or null
     * when it can't be put off now (not Sunday, too late, or reviewed already).
     */
    suspend fun reviewLater(key: String?): String? = onCore {
        val card = _review.value.card
        if (card.weekStart <= 0 || card.reviewed) return@onCore null
        val mine = os.meka.core.domain.ReviewRules.NOTICE_PREFIX + card.weekStart
        if (key != null && key != mine && !key.startsWith("$mine:")) return@onCore null
        val cal = ZoneCalendar(timeZone)
        val now = nowMs()
        val today = cal.epochDayOf(now)
        if (!os.meka.core.domain.ReviewRules.cardWindow(today, cal.minuteOfDay(now), card.startMinute)) return@onCore null
        val m = os.meka.core.domain.ReviewRules.laterMinute(today, cal.minuteOfDay(now)) ?: return@onCore null
        review.putOff(card.weekStart, cal.toEpochMs(today, m))
        refresh()
        os.meka.core.domain.ReviewRules.laterSaid(m)
    }

    /** "Skip this week" ([on]) or, after a skip, "Reflect now" for the week Review shows. */
    suspend fun skipReflection(on: Boolean) = onCore { reflections.skip(_review.value.weekStart, on); refresh() }

    /** Takes one week's reflection out of the journal ([removed]), or puts it back (Undo). */
    suspend fun removeReflection(weekStart: Long, removed: Boolean): Boolean = onCore {
        reflections.remove(weekStart, removed).also { refresh() }
    }

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

    /**
     * Protected time, slice 4: "Not protected tonight" ([open] true) lifts today's protected time just this once; false
     * (Undo) puts it back. Returns the line to show, or null when nothing changed (or today has nothing to lift).
     */
    suspend fun setProtectedOpenToday(open: Boolean): String? = onCore {
        val today = ZoneCalendar(timeZone).epochDayOf(nowMs())
        val lifted = os.meka.core.domain.ProtectedRules.lifted(protectedTime.setting(), today, work.schedule(), bankHolidays.calendar())
            ?: return@onCore null
        if (!protectedTime.setOpenDay(if (open) today else null)) return@onCore null
        refresh()
        if (open) lifted.done else lifted.again
    }

    // ---- "Leaving now" (V1, slice 1; Work mode → Leaving now) ----

    /**
     * Sets "Leaving now": on or off, who the draft goes to (a Family entry's name, e.g. "Jordan") and the usual
     * journey home in minutes. Returns the summary line, or null when nothing changed or the values don't make sense.
     */
    suspend fun setLeavingNow(on: Boolean, to: String, journeyMin: Int): String? = onCore {
        if (!leavingNow.set(on, to, journeyMin)) return@onCore null
        refresh()
        os.meka.core.domain.LeavingRules.summary(leavingSetting())
    }

    /**
     * Send or Edit tapped on a "Leaving now" notification [key] that showed [shown] (Level 3: only from Meka's tap).
     * [os.meka.core.domain.LeavingTap.Send] carries exactly the shown words; Changed carries the updated notice to show
     * instead (nothing is sent); null when it no longer applies.
     */
    suspend fun leavingTap(key: String, shown: String, edit: Boolean): os.meka.core.domain.LeavingTap? = onCore {
        os.meka.core.domain.LeavingRules.tap(
            key, shown, leavingSetting(), officeToday(), lineStatus.snapshot(), nowMs(), ZoneCalendar(timeZone), edit,
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

    // ---- Life admin hour (V1, slice 1; Ask → More → Life admin hour) ----

    /** Books the hour every week on [weekday] (ISO 1..7) from [startMin]. Returns the summary line, or null when nothing changed. */
    suspend fun setLifeAdmin(weekday: Int, startMin: Int): String? = onCore {
        if (!lifeAdmin.set(weekday, startMin)) return@onCore null
        os.meka.core.domain.LifeAdminRules.everyLine(lifeAdmin.setting()!!)
    }

    /** Turns the life admin hour off: nothing is booked or gathered. */
    suspend fun lifeAdminOff(): Boolean = onCore { lifeAdmin.off() }

    /** Skips this week's hour (it's free again), or keeps it; returns the line to show, or null when nothing changed. */
    suspend fun skipLifeAdmin(skip: Boolean): String? = onCore {
        if (!lifeAdmin.skipThisWeek(skip)) return@onCore null
        os.meka.core.domain.LifeAdminRules.skipLine(lifeAdminWeek, skip)
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
     * Bin days, completeness: the evening reminder's time (a chip, 17:00 to 21:00; synced, so both devices remind at
     * it). Returns the line to show ("Reminder at 20:00 · on both devices"), or null when nothing changed.
     */
    suspend fun setBinReminder(minute: Int): String? = onCore {
        if (bins.setRemind(minute)) os.meka.core.domain.BinRules.remindSaid(minute) else null
    }

    // ---- Travel mode (V1, slice 1; Ask → More → Trips) ----

    /**
     * Adds one typed line ([os.meka.core.domain.TripRules.read]): a trip ("Barcelona 14–18 Nov"; one not over yet by
     * the same name has its dates updated) or a booking on the trip it falls on ("Flight BA478 Sat 14 Nov 07:40 ref
     * ABC123", "Hotel Arts 14–18 Nov"). Refused (nothing written) when MEKA couldn't read it.
     */
    suspend fun addTrip(text: String): os.meka.core.domain.TripAdded = onCore { trips.add(text) }

    /** Removes a trip and its bookings for good. */
    suspend fun removeTrip(id: String): Boolean = onCore { trips.remove(id) }

    /** Removes one booking for good (its id is a timeline line's `bookingId`). */
    suspend fun removeTripBooking(id: String): Boolean = onCore { trips.removeBooking(id) }

    /**
     * Travel mode completeness slice 4: × and Remove trip with Undo on both apps. Removes a trip and its bookings and
     * returns "Removed Barcelona and its 3 bookings" with the token [undoTripRemoval] takes; null when it isn't there.
     */
    suspend fun removeTripForUndo(id: String): os.meka.core.domain.TripRemoved? = onCore { trips.removeForUndo(id) }

    /** Removes one booking: "Removed Flight BA478 from Barcelona" with Undo's token; null when it isn't there. */
    suspend fun removeTripBookingForUndo(id: String): os.meka.core.domain.TripRemoved? = onCore { trips.removeBookingForUndo(id) }

    /** Undo after [removeTripForUndo] or [removeTripBookingForUndo]: exactly what was removed comes back. */
    suspend fun undoTripRemoval(token: String): Boolean = onCore { trips.undoRemove(token) }

    // ---- The Vault (V2, slice 1) ----

    /**
     * Adds one typed document ([os.meka.core.domain.DocumentRules.read]): "Passport expires 3 Mar 2029", "Railcard
     * 05/2027"; one with the same title takes the new date. Refused (nothing written) when MEKA couldn't read it.
     */
    suspend fun addDocument(text: String): os.meka.core.domain.DocumentAdded = onCore { documents.add(text) }

    /** Removes a document for good, and its encrypted copy (slice 2b) from every device and Navilife's server. */
    suspend fun removeDocument(id: String): Boolean {
        val gone = onCore {
            val file = documents.item(id)?.file
            val removed = documents.remove(id)
            if (removed && file != null) forgetVaultCopy(file.id, deleteOnServer = true)
            removed to file
        }
        gone.second?.takeIf { gone.first }?.let { deleteVaultCopiesGone() }
        return gone.first
    }

    /**
     * Screenshots and photos, slice 3: what the capture sheet (or the Mac's bar) offers for shared text or a picture's
     * words that are a document with an expiry ([os.meka.core.domain.DocumentShareRules]): "Add to the Vault · Alex
     * passport · expires Thu 20 May 2027", "Update the Vault · …" or "Already in the Vault · …". Read on the device;
     * nothing is written until [addDocumentFromShared]. Null when it isn't one. Never throws.
     */
    suspend fun documentFromShared(text: String, subject: String?): os.meka.core.domain.DocumentShareView? = onCore {
        runCatching {
            os.meka.core.domain.DocumentShareRules.view(text, subject, ZoneCalendar(timeZone).epochDayOf(nowMs()), documents.items())
        }.getOrNull()
    }

    /**
     * Adds the document [text] (and [subject]) show to the Vault, on his tap: its name and expiry only, never a number
     * or the picture. Null when it doesn't read as one (or is already there with this date).
     */
    suspend fun addDocumentFromShared(text: String, subject: String?): os.meka.core.domain.DocumentAdded? = onCore {
        val today = ZoneCalendar(timeZone).epochDayOf(nowMs())
        val v = runCatching { os.meka.core.domain.DocumentShareRules.view(text, subject, today, documents.items()) }.getOrNull()
        if (v == null || v.already) return@onCore null
        documents.addEntry(v.entry)
    }

    // ---- Gift planner (V1, slice 1; Ask → More → Gifts) ----

    /**
     * Adds one typed birthday or anniversary ("Mum's birthday 3 Nov", "Wedding anniversary 20 Aug 2015", "… delivery
     * 5 days"; [os.meka.core.domain.GiftRules.read]); the same person and kind again updates it. Refused (nothing
     * written) when MEKA couldn't read a day and month, or no one for a birthday.
     */
    suspend fun addOccasion(text: String): os.meka.core.domain.GiftAdded = onCore { gifts.add(text) }

    /** Removes a birthday or anniversary for good. */
    suspend fun removeOccasion(id: String): Boolean = onCore { gifts.remove(id) }

    /** Notes a gift idea against a birthday or anniversary. */
    suspend fun addGiftIdea(occasionId: String, text: String): Boolean = onCore { gifts.addIdea(occasionId, text) }

    suspend fun removeGiftIdea(id: String): Boolean = onCore { gifts.removeIdea(id) }

    /** Marks the gift for its next occurrence sorted (the reminders stop) or not. */
    suspend fun setGiftSorted(id: String, sorted: Boolean): Boolean = onCore { gifts.setSorted(id, sorted) }

    /**
     * Bin days, slice 3: takes back a one-off ("Black bin Sat 2 Jan instead of 29 Dec", typed through [addBin]), so
     * that collection goes on its usual day again. Its id is a row's in [os.meka.core.domain.BinsView.oneOffs].
     */
    suspend fun removeBinMove(id: String): Boolean = onCore { bins.removeMove(id) }

    // ---- School rhythm (V1, slice 1; Ask → More → School, and Needs you's cover questions) ----

    /**
     * Adds one typed line ("INSET 27 Oct", "Half term 26–30 Oct", "Theo PE Tue"; [os.meka.core.domain.SchoolRules.read]).
     * Returns the line to show ("Added INSET day · Tue 27 Oct · Theo and Finn"), or null when MEKA couldn't read a date
     * or weekday in it (nothing is written; the pane says [os.meka.core.domain.SchoolRules.NOT_READ]).
     */
    suspend fun addSchool(text: String): String? = onCore {
        school.add(text)?.let { (_, e) -> os.meka.core.domain.SchoolRules.addedLine(e) }
            // School logistics from email: a line he typed hides the school's find for the same thing.
            .also { mailFindsChanged() }
    }

    /** Removes a school line for good (typed by mistake). */
    suspend fun removeSchool(id: String): Boolean = onCore { school.remove(id).also { mailFindsChanged() } }

    /**
     * School dates filled in (slice 1): fills in [councilId]'s holidays still to come (the council card's choice,
     * [os.meka.core.domain.SchoolTerms.councils], from his profile) as days off for both boys, marked with the council and the day its page
     * was checked. Returns the line to show ("Filled in 8 holidays from …", or that they're in already); null for a
     * council not in the table. Days off he typed win: a holiday overlapping one isn't added. Undo is [removeSchoolTerms].
     */
    suspend fun fillSchoolTerms(councilId: String): String? = onCore { schoolTerms.fill(councilId)?.first }

    /**
     * School dates filled in (slice 2): the council card's **Add them** — only [councilId]'s new dates (a later Navilife's
     * table; a holiday he removed stays removed); null when there are none. Undo is [undoNewSchoolTerms] with the result.
     */
    suspend fun addNewSchoolTerms(councilId: String): os.meka.core.domain.SchoolTermsAdded? = onCore { schoolTerms.addNew(councilId) }

    /** Undo for [addNewSchoolTerms]: takes exactly those holidays out again. */
    suspend fun undoNewSchoolTerms(added: os.meka.core.domain.SchoolTermsAdded): Int = onCore { schoolTerms.removeIds(added.ids) }

    /** Remove all on the council card (and Undo after filling in): every holiday from [councilId] goes; the line to show. */
    suspend fun removeSchoolTerms(councilId: String): String = onCore {
        os.meka.core.domain.SchoolTerms.removedLine(schoolTerms.removeAll(councilId))
    }

    /**
     * The INSET days pasted from the school's website or letter ("2 Nov, 4 Jan and 12–13 Apr"): each a day off for both
     * boys. The line to show ("Added 3 INSET days · …"), or null when there's no date in it
     * ([os.meka.core.domain.SchoolTerms.INSET_NONE]).
     */
    suspend fun addSchoolInset(text: String): String? = onCore { schoolTerms.addInset(text) }

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
    /**
     * Health Connect, slice 1: today's [workouts] (read on the Fold, never sent anywhere) tick the habits they match
     * ([os.meka.core.domain.WorkoutRules.ticks]): a booked session is answered Went with its label and the note
     * "52-min workout · from Health Connect", any other habit is ticked. Each tick is an automatic action in Activity
     * (Undo there leaves the day answered, un-ticked, so the same workout never ticks it again). Nothing happens while
     * "What MEKA may do" is paused. Returns what was ticked, for the Fold's quiet line.
     */
    suspend fun tickFromWorkouts(workouts: List<os.meka.core.domain.Workout>): List<os.meka.core.domain.WorkoutTickDone> = onCore {
        if (workouts.isEmpty() || autonomy.paused()) return@onCore emptyList()
        val cal = ZoneCalendar(timeZone)
        val habits = goals.habits().map {
            os.meka.core.domain.WorkoutHabit(it.id, it.title, it.minutes, it.booked, goals.answeredToday(it.id))
        }
        val ticks = os.meka.core.domain.WorkoutRules.ticks(workouts, habits, cal.epochDayOf(nowMs()), nowMs()) { cal.epochDayOf(it) }
        ticks.mapNotNull { t ->
            val booked = habits.first { it.id == t.habitId }.booked
            val label = if (booked) _sessions.value.cards.firstOrNull { it.habitId == t.habitId }?.label else null
            goals.openToday(t.habitId)
            val summary = os.meka.core.domain.WorkoutRules.summary(t)
            val (_, id) = activity.actRecorded(
                summary, os.meka.core.domain.WorkoutRules.WHY, os.meka.core.domain.WorkoutRules.SOURCE,
                AutonomyLevel.AUTO_UNDER_RULE.name, detail = os.meka.core.domain.WorkoutRules.note(t),
            ) {
                if (booked) goals.answerSession(t.habitId, went = true, label = label, note = os.meka.core.domain.WorkoutRules.note(t))
                else goals.setHabitDone(t.habitId, true)
            }
            id?.let { os.meka.core.domain.WorkoutTickDone(t.habitId, summary, it) }
        }
    }
    /**
     * Health Connect slice 2: this device reads workouts in the background ([on]), so a booked session's "Did you go?"
     * waits [os.meka.core.domain.WorkoutRules.ASK_GRACE_MIN] minutes after the slot for the watch's workout to tick it
     * first. Kept on this device only (the Fold says it at start and whenever the switch or the permission changes).
     */
    suspend fun setWorkoutsReadInBackground(on: Boolean) = onCore { workoutsInBackground = on }

    /**
     * Health Connect slice 3: the sleep sessions the Fold read since yesterday evening (empty when Sleep is off). Kept
     * in memory on this device only, never synced; Plan my day plans lighter after a short night. Returns last night's
     * minutes asleep, or null when none was logged.
     */
    suspend fun setSleep(sessions: List<os.meka.core.domain.SleepSession>): Int? = onCore {
        sleepSessions = sessions
        os.meka.core.domain.SleepRules.lastNight(sessions, dayWindow(nowMs()).startMs)
    }
    /** Notices as the governor would see them now (tests). */
    internal suspend fun noticesNow(): List<os.meka.core.domain.Notice> = onCore { refresh(); currentNotices() }
    /** The habits a workout could tick, for the Fold's Workouts line. */
    fun workoutHabits(): List<String> = os.meka.core.domain.WorkoutRules.tickable(
        goalsView.value.habits.map { os.meka.core.domain.WorkoutHabit(it.id, it.title, it.minutes, it.booked, it.doneToday) },
    )
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

    /**
     * Done for the day, completeness: "Later" on the evening card ([key] null) or its heads-up ([key] that notice's
     * key, which must be today's). The card leaves Today and comes back an hour on (never after 23:00) on every device.
     * Returns "Shut down the day comes back at 21:15", or null when it can't be put off now.
     */
    suspend fun shutdownLater(key: String?): String? = onCore {
        val v = _shutdown.value
        if (!v.laterOffered) return@onCore null
        val cal = ZoneCalendar(timeZone)
        val now = nowMs()
        val today = cal.epochDayOf(now)
        val mine = os.meka.core.domain.ShutdownRules.NOTICE_PREFIX + today
        if (key != null && key != mine && !key.startsWith("$mine:")) return@onCore null
        val m = os.meka.core.domain.ShutdownRules.laterMinute(cal.minuteOfDay(now)) ?: return@onCore null
        shutdown.putOff(today, cal.toEpochMs(today, m))
        refresh()
        os.meka.core.domain.ShutdownRules.laterSaid(m)
    }

    /** Switches the evening card and its heads-up off ([on] false) or back on, on every device; returns what it says. */
    suspend fun setShutdownCard(on: Boolean): String = onCore {
        shutdown.setCard(on)
        refresh()
        os.meka.core.domain.ShutdownRules.cardSaid(on)
    }

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
    /**
     * The commute counts everywhere, slice 7: "Wake 10 min earlier" for tomorrow's trains, from Shut down's button
     * ([key] null) or the heads-up's ([key] its notice key, which must still be tomorrow's offer). Moves the wake alarm
     * (setting it when there was none) to the offer's time on every device; returns "Wake alarm 06:30 · 10 min early
     * for the trains", or null when the offer no longer stands (moved already, or the trains came good).
     */
    suspend fun wakeEarlierForTrains(key: String?): String? = onCore {
        // Live trains, slice 5: this morning's usual train in trouble ("Wake at 06:20 instead of 06:40?").
        if (key != null && os.meka.core.domain.TrainWakeRules.isKey(key)) {
            val tw = currentTrainWake() ?: return@onCore null
            val m = tw.wakeMinute ?: return@onCore null
            if (key != os.meka.core.domain.TrainWakeRules.key(tw.epochDay, tw.leave.train.depMin)) return@onCore null
            if (!alarms.setWakeOn(tw.epochDay, m, os.meka.core.domain.TrainWakeRules.note(tw))) return@onCore null
            return@onCore os.meka.core.domain.TrainWakeRules.movedLine(tw)
        }
        val w = _wake.value
        val t = w.trains ?: return@onCore null
        if (key != null && key != os.meka.core.domain.CommuteDisruptionRules.key(t.epochDay)) return@onCore null
        val m = t.earlierMinute ?: return@onCore null
        if (alarms.wakeDay() != t.epochDay) return@onCore null
        val what = w.suggestion?.commitment?.line ?: "Work"
        if (!alarms.setWake(m, "$what · ${os.meka.core.domain.CommuteDisruptionRules.EARLIER_MIN} min early for the trains")) return@onCore null
        "Wake alarm ${os.meka.core.domain.LocalClock.formatMinute(m)} · ${os.meka.core.domain.CommuteDisruptionRules.EARLIER_MIN} min early for the trains"
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
    /**
     * "A morning brief worth listening to", slice 3: how long the spoken brief is ("short", "standard", "full";
     * [os.meka.core.domain.BriefLength]); synced. An unknown id is Standard.
     */
    suspend fun setBriefLength(id: String) = onCore { brief.setLength(os.meka.core.domain.BriefLength.of(id)) }
    /** Shows or hides a news topic in the brief and the News place ([os.meka.core.domain.NewsTopics]); synced. */
    suspend fun setNewsTopic(topicId: String, on: Boolean) = onCore { news.setTopic(topicId, on) }

    /** News → Spanish sources (Fold review 2026-10-09 07:26 item 1): off = English only everywhere news shows; synced. */
    suspend fun setNewsSpanish(on: Boolean) = onCore { news.setSpanish(on) }

    /**
     * Full news hub, slice 2: switches one source on or off (a [os.meka.core.domain.NewsSourceChoice.name] from
     * `newsPlace.sources`); off leaves its stories out of News, the ticker and the brief on both apps. Synced; false
     * when it already was.
     */
    suspend fun setNewsSource(name: String, on: Boolean): Boolean = onCore { news.setSource(name, on) }

    /**
     * Full news hub, slice 3: follows what he typed under News → Topics → Follow ("Arsenal, Lamine Yamal": commas
     * separate); stories naming one, whatever their topic, lead News in a Following lane and reach the ticker and the
     * brief on both apps. Synced. The line to show: "Following Arsenal and Lamine Yamal", or why nothing was added.
     */
    suspend fun newsFollow(typed: String): String = onCore {
        val phrases = os.meka.core.domain.NewsRules.parseFollows(typed)
        if (phrases.isEmpty()) return@onCore "Type a word or name to follow, at least two letters"
        val added = news.setFollows(phrases, true)
        when {
            added.isNotEmpty() -> "Following " + humanJoin(added) + " · stories naming " + (if (added.size == 1) "it" else "them") + " lead News"
            news.follows().size >= os.meka.core.domain.NewsRules.MAX_FOLLOWS -> "You follow ${os.meka.core.domain.NewsRules.MAX_FOLLOWS} already · stop following one first"
            else -> "Already following " + humanJoin(phrases)
        }
    }

    /** Stops following one word (a [os.meka.core.domain.NewsFollowChoice.name]); Undo is [newsFollow] with it. False when it wasn't followed. */
    suspend fun newsUnfollow(name: String): Boolean = onCore { news.setFollows(listOf(name), false).isNotEmpty() }

    private fun humanJoin(xs: List<String>): String = when (xs.size) {
        0 -> ""
        1 -> xs[0]
        else -> xs.dropLast(1).joinToString(", ") + " and " + xs.last()
    }

    // ---- Full news hub (V2, slice 1: read and new, Save for the train) ----

    /** The stories the last Mark all read put away, for its Undo (this device, this session). */
    private var newsLastMarked: List<String> = emptyList()

    /**
     * Opening a story in News marks it read on both apps (synced read list, [os.meka.core.domain.NewsFields.READ]);
     * false when it already was or isn't in the News place now.
     */
    suspend fun newsOpened(id: String): Boolean = onCore {
        val item = _newsPlace.value.items.firstOrNull { it.id == id } ?: return@onCore false
        news.markRead(listOf(item.readKey))
    }

    /** Mark all read: every new story in the News place now; the count put away (0 when none). Undo with [newsUndoAllRead]. */
    suspend fun newsMarkAllRead(): Int = onCore {
        val keys = _newsPlace.value.items.filter { !it.read }.map { it.readKey }
        // Nothing new: the last Mark all read's Undo still stands.
        if (keys.isEmpty() || !news.markRead(keys)) return@onCore 0
        newsLastMarked = keys
        keys.size
    }

    /** Puts the last Mark all read back: those stories show as new again. False when there is nothing to undo. */
    suspend fun newsUndoAllRead(): Boolean = onCore {
        val keys = newsLastMarked
        newsLastMarked = emptyList()
        keys.isNotEmpty() && news.markUnread(keys)
    }

    /**
     * Save for the train from a story in News: its link and title into the train queue (both apps, offline words on
     * Wi-Fi like any saved link), and the story marked read. A story without a link is refused, nothing written.
     */
    suspend fun newsForTrain(id: String): os.meka.core.domain.QueueAdded = onCore {
        val item = _newsPlace.value.items.firstOrNull { it.id == id }
        val url = item?.url ?: return@onCore os.meka.core.domain.QueueAdded(os.meka.core.domain.NewsRules.NO_LINK_TO_SAVE, true)
        val added = trainQueue.add(url, item.title)
        if (!added.refused) news.markRead(listOf(item.readKey))
        added
    }

    // ---- Weekly review ----

    /** Shows the week [offset] weeks from this one (0 this week, -1 last week, back to -12). */
    suspend fun showReviewWeek(offset: Int) = onCore { reviewOffset = offset; refresh() }

    /**
     * Balance view, slice 1: sets [area]'s weekly target ("WORK", "FAMILY", "HEALTH", "SELF") to [minutes] (one of its
     * choices; 0 for work's "your own hours", or the default, clears it). Returns the line to show, or null when nothing
     * changed. Synced.
     */
    suspend fun setBalanceTarget(area: String, minutes: Int): String? = onCore {
        val a = os.meka.core.domain.BalanceArea.entries.firstOrNull { it.name == area } ?: return@onCore null
        balanceTargets.set(a, minutes)?.also { refresh() }
    }

    /**
     * Balance view, slice 5: "Count as …" for an event with no clear area: every event titled like [title] counts as
     * [area] ("FAMILY", "HEALTH", "SELF", "WORK"; "NONE" = never counted; "CLEAR" forgets the choice), past weeks too.
     * Synced. Returns the line to show ("“Swim club” counts as health from now on · on both devices"), or null when
     * nothing changed; [balanceUndoCountAs] puts it back.
     */
    suspend fun balanceCountAs(title: String, area: String): String? = onCore {
        val before = balanceTargets.choicesText()
        balanceTargets.countAs(title, area)?.also { balanceCountUndo = before to true; refresh() }
    }

    /** Slice 5: Undo for the last [balanceCountAs]; false when there's nothing to undo. */
    suspend fun balanceUndoCountAs(): Boolean = onCore {
        val (before, ok) = balanceCountUndo ?: return@onCore false
        if (!ok) return@onCore false
        balanceCountUndo = null
        balanceTargets.writeChoices(before)
        refresh()
        true
    }
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
        val undid = outcome != os.meka.core.domain.UndoOutcome.NOT_UNDOABLE && outcome != os.meka.core.domain.UndoOutcome.TOO_LATE
        // Earned trust: undoing something MEKA did on its own sends that kind back to asking first.
        if (kind != null && undid) trust.autoUndone(kind)
        refresh()
        activityChanged()
        if (kind != null && undid) os.meka.core.domain.TrustRules.autoUndoneLine(kind, outcome)
        else outcome.line
    }

    /**
     * Activity log completeness: **Export** — every entry the log holds (not just the 30 days shown), oldest first, as a
     * CSV to save where Meka likes; made here from his own data, nothing sent or stored, message words left out.
     */
    suspend fun activityExport(): os.meka.core.domain.ActivityExportFile = onCore {
        os.meka.core.domain.ActivityExportRules.file(activity.items(), nowMs(), ZoneCalendar(timeZone))
    }

    /** The line under Activity's days: how long entries are kept and shown, and what Export saves. */
    suspend fun activityKeptLine(): String? = onCore {
        activity.items().size.takeIf { it > 0 }?.let { os.meka.core.domain.ActivityExportRules.keptLine(it) }
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
        // Inbox autopilot (slice 3b-2): its row's Turn off / Turn on switches it.
        if (kindKey == os.meka.core.domain.AutonomyRules.MAIL_AUTOPILOT) {
            val on = !mailAutopilotSetting().on
            writeMailAutopilot(on)
            refresh()
            return@onCore os.meka.core.domain.MailAutopilotPanelRules.switchedLine(on)
        }
        // Mail finds, slice 3: "Renewals and promises from mail" — Turn on / Turn off.
        if (kindKey == os.meka.core.domain.MailFindAutoRules.ENTITY_ID) {
            val on = !mailFindsAutoOn()
            replica.commitLocal(
                os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailFindAutoRules.ENTITY_ID,
                os.meka.core.domain.MailFindAutoRules.switchFields(on, nowMs()),
            )
            refresh()
            return@onCore os.meka.core.domain.MailFindAutoRules.switchedLine(on)
        }
        // "The mic should just add it", slice 1: "Add what you ask for" — Ask first / Just add it.
        if (kindKey == os.meka.core.domain.DirectAddRules.ENTITY_ID) {
            val askFirst = !directAddSetting().askFirst
            replica.commitLocal(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.DirectAddRules.ENTITY_ID, os.meka.core.domain.DirectAddRules.fields(askFirst))
            refresh()
            return@onCore os.meka.core.domain.DirectAddRules.switchedLine(askFirst)
        }
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
     * "Keep callers' recordings" (call assistant polish 8c): 7 days, or 0 for don't keep. Synced; MEKA's server
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

    /**
     * Activity → Calls with this device's names for numbers ([names], [lists]: the Fold's contacts and lists; pure, so
     * it can run as the screen draws).
     */
    fun callLogOf(
        calls: List<os.meka.core.domain.HeldCall>,
        names: os.meka.core.domain.CallerNames = os.meka.core.domain.CallerNames.NONE,
        lists: os.meka.core.domain.PeopleLists = os.meka.core.domain.PeopleLists(),
        /** Slice 6: the calls this phone screened (rang through, sent to the assistant, stopped), kept on the phone only. */
        phone: List<os.meka.core.domain.PhoneCall> = emptyList(),
    ): os.meka.core.domain.CallLogView =
        os.meka.core.domain.CallLogRules.view(calls, _blockedCallers.value, nowMs(), ZoneCalendar(timeZone), names, lists, phone)

    /**
     * Block in a call's detail (Activity → Calls, call notifications slice 4): the held call [id]'s number goes on the
     * list with why ("Left a message · today"). False, saving nothing, for a withheld number or one he knows.
     */
    suspend fun blockLoggedCaller(id: String): Boolean = onCore {
        val call = held.calls().firstOrNull { it.item.id == id } ?: return@onCore false
        val number = os.meka.core.domain.CallNoticeRules.numberOf(call.item) ?: return@onCore false
        val why = os.meka.core.domain.BlockedCallerRules.whyFromHeld(call.item.kind, call.item.atMs, nowMs(), ZoneCalendar(timeZone))
        blockList.block(number, why).also { refresh() }
    }

    /**
     * Delete recording in a call's detail (Meka 2026-10-20 17:34): the call shows "Recording deleted" at once and the
     * deletion is written after [os.meka.core.domain.VoiceRecordingRules.DELETE_UNDO_MS] unless
     * [undoDeleteCallRecording] took it back; then the server deletes the recording for good (the words stay).
     */
    suspend fun deleteCallRecordingSoon(id: String) {
        if (!os.meka.core.domain.VoiceRecordingRules.isHeldId(id)) return
        val token = onCore { (++recordingDeleteSeq).also { recordingDeleting[id] = it; refresh() } }
        scope.launch {
            delay(os.meka.core.domain.VoiceRecordingRules.DELETE_UNDO_MS)
            // Only this tap's own timer writes it: an Undo then a second Delete waits its own 5 s.
            runCatching { onCore { recordingDeleting[id] == token }.let { if (it) commitDeleteCallRecording(id) } }
        }
    }

    /** Undo on the bar after Delete recording: the recording comes back, nothing was written. */
    suspend fun undoDeleteCallRecording(id: String) = onCore { recordingDeleting -= id; refresh() }

    /** Writes a waiting Delete recording now (the delay ending, or the app going to the background). */
    suspend fun commitDeleteCallRecording(id: String): Boolean = onCore {
        if (recordingDeleting.remove(id) == null) return@onCore false
        held.deleteRecording(id).also { refresh() }
    }

    /** Takes a number ([key], from [blockedCallers]' rows) off the block list; its calls ring again. */
    suspend fun unblockCaller(key: String): Boolean = onCore { blockList.unblock(key).also { refresh() } }

    /**
     * Block again on an unblocked row of Activity → Blocked calls ([blockedCallers]' `stopped`; spam calls still ring,
     * slice 4): back on the list with the reason it had. False when it is already blocked or unknown.
     */
    suspend fun reblockCaller(key: String): Boolean = onCore { blockList.reblock(key).also { refresh() } }

    /**
     * Block on a Suspected spam row ([blockedCallers]' `suspects`; call assistant polish 8b c): the number goes on the
     * block list with the AI's reason. False when [key] isn't a suspect any more.
     */
    suspend fun confirmSuspectedCaller(key: String): Boolean = onCore { blockList.confirmSuspect(key).also { refresh() } }

    /** Not spam on a Suspected spam row: its calls are screened as before and MEKA never flags the number again. */
    suspend fun dismissSuspectedCaller(key: String): Boolean = onCore { blockList.dismissSuspect(key).also { refresh() } }

    /**
     * Spam protection's switch (spam calls still ring, slice 1): on unless Meka turns it off in Work mode → Spam
     * protection → Advanced. Synced. Returns false when nothing changed.
     */
    suspend fun setSpamProtection(on: Boolean): Boolean = onCore { work.setSpamProtection(on).also { refresh() } }

    /**
     * Spam calls still ring, slice 1: the Fold, after a call from an unknown number it watched (not a contact, not
     * called lately, not household, not claiming to be an official body), says how many incoming calls the number made
     * in the last [os.meka.core.domain.AutoBlockRules.REPEAT_DAYS] days ([callsInWindow], this one included, from the
     * phone's own call log). With spam protection on and no voice message left by the number in that time, it goes on
     * the block list as an automatic action with Undo in Activity. Returns that entry's id, or null when nothing was
     * blocked.
     */
    suspend fun autoBlockRepeatCaller(number: String, callsInWindow: Int, atMs: Long): String? = onCore {
        val key = os.meka.core.domain.BlockedCallerRules.keyOf(number) ?: return@onCore null
        if (!work.spamProtection() || key in blockList.view().keys) return@onCore null
        if (key in os.meka.core.domain.SpamProtectionRules.householdKeys(aboutYou.profile())) return@onCore null
        val leftMessage = leftVoiceMessageLately(key)
        if (!os.meka.core.domain.AutoBlockRules.repeatCaller(callsInWindow, leftMessage)) return@onCore null
        autoBlock(number, os.meka.core.domain.AutoBlockTrigger.REPEAT_CALLER, atMs, callsInWindow)
    }

    /** Whether the number keyed [key] left a voice message with the call assistant in the last week. */
    private fun leftVoiceMessageLately(key: String): Boolean {
        val since = nowMs() - os.meka.core.domain.AutoBlockRules.REPEAT_DAYS * 86_400_000L
        return replica.entities(os.meka.core.domain.EntityTypes.HELD_MESSAGE).any { e ->
            e[os.meka.core.domain.HeldMessageFields.KIND].textOrNull == os.meka.core.domain.CaptureKind.VOICE_MESSAGE.name &&
                (e[os.meka.core.domain.HeldMessageFields.AT].longOrNull ?: 0L) >= since &&
                e[os.meka.core.domain.HeldMessageFields.PERSON].textOrNull?.let { os.meka.core.domain.BlockedCallerRules.keyOf(it) } == key
        }
    }

    /**
     * Spam calls still ring, slice 3: callers who reached the call assistant and hung up without a message (the server
     * records them), not yet looked at by the Fold. The Fold checks each against what only it knows and calls
     * [autoBlockAssistantHangup].
     */
    suspend fun assistantHangups(): List<os.meka.core.domain.AssistantHangup> = onCore { blockList.hangups() }

    /**
     * The Fold's decision on one [assistantHangups] entry: [known] when the phone recognises the caller (a contact,
     * called in 90 days, family/always-notify, its own number, a claimed official body), [lookups] whether it could check
     * at all. Marks the hang-up looked at either way; with spam protection on, a stranger who left no voice message
     * lately and isn't household goes on the block list as an automatic action with Undo. Returns that Activity entry's
     * id, or null when nothing was blocked.
     */
    suspend fun autoBlockAssistantHangup(hangup: os.meka.core.domain.AssistantHangup, known: Boolean, lookups: Boolean): String? = onCore {
        blockList.markHangupHandled(hangup.key, hangup.atMs)
        val household = hangup.key in os.meka.core.domain.SpamProtectionRules.householdKeys(aboutYou.profile())
        val block = os.meka.core.domain.AssistantHangupRules.shouldBlock(
            work.spamProtection(), lookups, known, household, leftVoiceMessageLately(hangup.key),
        ) && hangup.key !in blockList.view().keys
        if (!block) {
            refresh()
            return@onCore null
        }
        autoBlock(hangup.number, os.meka.core.domain.AutoBlockTrigger.ASSISTANT_HANGUP, hangup.atMs)
    }

    /**
     * Call notifications, slice 5: the call assistant's messages that read as automated, from real numbers, not blocked
     * and not looked at yet (one per caller). The Fold checks each like a hang-up and calls [autoBlockAutomatedCall].
     */
    suspend fun automatedCalls(): List<os.meka.core.domain.AssistantHangup> = onCore {
        val keys = blockList.view().keys
        os.meka.core.domain.AutomatedCallRules.pending(held.calls(), blockList::automatedHandledAt, keys, nowMs())
    }

    /**
     * The Fold's decision on one [automatedCalls] entry, with the same never-block checks as a hang-up ([known]:
     * contact, called in 90 days, listed, its own number, a claimed official body; [lookups]: it could check at all).
     * Marks it looked at either way; with spam protection on, a stranger who isn't household and left no real message
     * lately goes on the block list as an automatic action with Undo. Returns that Activity entry's id, or null.
     */
    suspend fun autoBlockAutomatedCall(call: os.meka.core.domain.AssistantHangup, known: Boolean, lookups: Boolean): String? = onCore {
        blockList.markAutomatedHandled(call.number, call.atMs)
        val household = call.key in os.meka.core.domain.SpamProtectionRules.householdKeys(aboutYou.profile())
        val real = os.meka.core.domain.AutomatedCallRules.leftRealMessage(held.calls(), call.key, nowMs())
        val block = os.meka.core.domain.AssistantHangupRules.shouldBlock(work.spamProtection(), lookups, known, household, real) &&
            call.key !in blockList.view().keys
        if (!block) {
            refresh()
            return@onCore null
        }
        autoBlock(call.number, os.meka.core.domain.AutoBlockTrigger.AUTOMATED, call.atMs)
    }

    /** Blocks [number] as an automatic action (Activity, with Undo); the entry's id, or null when nothing changed. */
    private fun autoBlock(number: String, trigger: os.meka.core.domain.AutoBlockTrigger, atMs: Long, calls: Int = 0, reported: String? = null): String? {
        val why = os.meka.core.domain.AutoBlockRules.why(trigger, atMs, ZoneCalendar(timeZone))
        val (_, id) = activity.actRecorded(
            os.meka.core.domain.AutoBlockRules.blockedLine(number), os.meka.core.domain.AutoBlockRules.ACTIVITY_WHY,
            os.meka.core.domain.AutoBlockRules.SOURCE, level = null, detail = os.meka.core.domain.AutoBlockRules.detail(trigger, calls, reported),
        ) { blockList.block(number, why) }
        refresh()
        activityChanged()
        return id
    }

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
        /** The name the network sent with the call (read on the phone); a claimed police/bank/NHS/school gets the heads-up. */
        networkName: String? = null,
        /** Spam-call lookup: what the web's report sites say about the number ([numberReputation]), when known. */
        reputation: os.meka.core.domain.NumberReputation? = null,
    ): os.meka.core.domain.UnknownCallOffer? = onCore {
        val view = blockList.view()
        os.meka.core.domain.UnknownCallRules.offer(
            number, outcome, durationS, atMs, nowMs(), view.keys, offered, ZoneCalendar(timeZone),
            claim = os.meka.core.domain.OfficialCallerRules.claim(networkName),
            suspected = view.suspectedKeys,
            reputation = os.meka.core.domain.NumberLookupRules.line(reputation),
        )
    }

    /**
     * Spam-call lookup (2026-10-17): what the web's report sites say about [number], from Navilife's server, waiting at
     * most [waitMs] for a fresh search. Null when the number isn't one to look up, the server has no search key, the
     * search failed or is still going, or the phone is offline: all "no lookup", never spam. Never throws. The Fold asks
     * only about callers it doesn't know (never contacts, anyone he called lately or the household).
     */
    suspend fun numberReputation(number: String?, waitMs: Long): os.meka.core.domain.NumberReputation? {
        if (!os.meka.core.domain.NumberLookupRules.lookUpable(number)) return null
        val api = numberLookupApi ?: return null
        val r = try {
            api.lookUpNumber(number!!.trim(), waitMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (r.state != os.meka.core.wire.NumberLookupCodec.Response.OK) return null
        val verdict = when (r.verdict) {
            "scam" -> os.meka.core.domain.NumberVerdict.SCAM
            "nuisance" -> os.meka.core.domain.NumberVerdict.NUISANCE
            "legit" -> os.meka.core.domain.NumberVerdict.LEGIT
            "unknown" -> os.meka.core.domain.NumberVerdict.UNKNOWN
            else -> return null
        }
        return os.meka.core.domain.NumberReputation(verdict, r.reports, r.sure, r.why)
    }

    /**
     * Spam-call lookup: whether the Fold should ask the server about this caller before it rings. Only a number worth
     * looking up from someone nobody knows: never a contact, anyone he called lately, a caller claiming an official
     * body, the family and always-notify lists, About you's numbers or a number already on the block list (so none of
     * theirs is ever searched for).
     */
    suspend fun callerNeedsLookup(
        number: String?, lists: os.meka.core.domain.PeopleLists, signals: os.meka.core.domain.CallSignals,
    ): Boolean = onCore {
        if (!os.meka.core.domain.NumberLookupRules.lookUpable(number)) return@onCore false
        if (signals.knownContact || signals.calledRecently || signals.household) return@onCore false
        if (os.meka.core.domain.OfficialCallerRules.claim(signals.networkName) != null) return@onCore false
        if (lists.nameForNumber(number!!) != null) return@onCore false
        val key = os.meka.core.domain.CallScreeningRules.callerKey(number)
        key !in blockList.view().keys && key !in os.meka.core.domain.SpamProtectionRules.householdKeys(aboutYou.profile())
    }

    /**
     * Spam-call lookup, after a call from a number the Fold didn't know (its screening watched it): when the web's
     * report sites call it a scam, surely, and spam protection is on, it goes on the block list as an automatic action
     * with Undo ("Rang · 14:02 · reported 23× as a scam"). Never the household's numbers or one already listed; the
     * Fold has already ruled out contacts, anyone he called lately and a caller claiming an official body. Returns
     * the Activity entry's id, or null when nothing was blocked.
     */
    suspend fun autoBlockReportedCaller(number: String, reputation: os.meka.core.domain.NumberReputation?, atMs: Long): String? = onCore {
        if (!os.meka.core.domain.NumberLookupRules.shouldBlock(reputation)) return@onCore null
        val key = os.meka.core.domain.BlockedCallerRules.keyOf(number) ?: return@onCore null
        if (!work.spamProtection() || key in blockList.view().keys) return@onCore null
        if (key in os.meka.core.domain.SpamProtectionRules.householdKeys(aboutYou.profile())) return@onCore null
        autoBlock(number, os.meka.core.domain.AutoBlockTrigger.REPORTED_AFTER_CALL, atMs,
            reported = os.meka.core.domain.NumberLookupRules.line(reputation))
    }

    /**
     * Call notifications, slice 3: the automatic block in the caller's own notification ("Blocked · and blocked from
     * now on" with Undo), worded in the phone's time zone.
     */
    fun autoBlockNotice(number: String, trigger: os.meka.core.domain.AutoBlockTrigger, atMs: Long, callsInWindow: Int = 0, reported: String? = null) =
        os.meka.core.domain.CallNoticeRules.autoBlockNotice(number, trigger, atMs, ZoneCalendar(timeZone), callsInWindow, reported)

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
        val profile = aboutYou.profile()
        val household = os.meka.core.domain.CallScreeningRules.callerKey(number) in
            os.meka.core.domain.SpamProtectionRules.householdKeys(profile)
        // Item (c) slice 2: the call assistant's own number is putting an urgent caller through, so it rings.
        val fromAssistant = os.meka.core.domain.PutThroughRules.fromAssistant(number, profile.assistantNumber)
        val decided = os.meka.core.domain.CallScreeningRules.decide(
            state.callAssistant, state.atWork, number, lists, recent, now, list.keys,
            signals.copy(quietHours = quiet, household = signals.household || household, fromAssistant = signals.fromAssistant || fromAssistant), list.suspectedKeys,
            paused = callCredit.current()?.paused == true,
            spamProtection = state.spamProtection,
        )
        // Spam calls still ring, slice 1: a number the network couldn't verify goes on the block list (Undo in Activity).
        val decision = if (decided.reason == os.meka.core.domain.CallReason.AUTO_BLOCKED && number != null) {
            decided.copy(autoBlockId = autoBlock(number, os.meka.core.domain.AutoBlockTrigger.NETWORK_CHECK, now))
        } else if (decided.reason == os.meka.core.domain.CallReason.REPORTED_SCAM && number != null) {
            // Spam-call lookup: reported online as a scam, surely; blocked before it rang (Undo in Activity).
            decided.copy(autoBlockId = autoBlock(number, os.meka.core.domain.AutoBlockTrigger.REPORTED, now,
                reported = os.meka.core.domain.NumberLookupRules.line(signals.reputation)))
        } else decided
        os.meka.core.domain.CallScreeningRules.activityLine(decision, number)?.let { (summary, why) ->
            activity.recordScreened(decision.callerKey, now, summary, why)
            activityChanged()
        }
        // Spam calls still ring, slice 4: each call the list stopped counts in Activity → Blocked calls.
        if (decision.verdict == os.meka.core.domain.CallVerdict.BLOCK) {
            blockList.recordStopped(decision.callerKey, now)
            refresh()
        }
        decision
    }

    /**
     * Where the forecast is for (Weather place setting, synced): a town name; blank or About you's home follows About you. Returns
     * false, saving nothing, for a name that can't be a place. The server follows it at its next poll (a few minutes);
     * [weatherView]'s `placeChoice` says when it has.
     */
    suspend fun setWeatherPlace(name: String): Boolean = onCore { weatherPlace.set(name).also { refresh() } }

    /**
     * Where work is (Places item 2, synced): a town or district name; blank or About you's work follows About you. Returns
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

    /**
     * The door-to-door commute each way (the commute counts everywhere, slice 1), synced; counted on office days only
     * (the wake alarm: work start − commute − time to get ready). Returns false for a time outside 10 min–4 h in
     * fives or no change.
     */
    suspend fun setCommute(toWorkMin: Int, homeMin: Int): Boolean = onCore {
        work.setCommute(toWorkMin, homeMin).also {
            if (!it) return@also
            refresh()
            // Slice 8: Setup's Commute step ticks at once (Today's Setup card drops it).
            _setup.value?.let { v ->
                val step = os.meka.core.domain.SetupRules.commuteStep(work.commute())
                if (v.steps.any { s -> s.key == step.key }) _setup.value = os.meka.core.domain.SetupRules.replaceStep(v, step)
            }
        }
    }

    /**
     * The commute, slice 6 ("Just one day"): [epochDay]'s own door-to-door times; the usual times put it back. Synced,
     * so the other app, the wake alarm, Today and the brief follow. Returns whether anything changed.
     */
    suspend fun setCommuteDay(epochDay: Long, toWorkMin: Int, homeMin: Int): Boolean =
        onCore { work.setCommuteDay(epochDay, toWorkMin, homeMin, todayEpochDay()).also { if (it) refresh() } }

    /** [epochDay] back to the usual commute times. */
    suspend fun clearCommuteDay(epochDay: Long): Boolean =
        onCore { work.setCommuteDay(epochDay, null, null, todayEpochDay()).also { if (it) refresh() } }

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
    suspend fun tick() = onCore {
        refresh()
        // Inbox autopilot, slice 7: a clear inbox at or after the moment counts towards the streak.
        noteInboxClear()
    }

    /**
     * The bedside clock for the half-folded Fold (Fold modes): the time, the phone's next alarm ([nextAlarmMs], from the
     * platform; null when none is set) and one short section for this part of the day, read from the current views.
     * Pure and cheap; the Fold calls it every few seconds while it stands half folded. Nothing is stored.
     */
    fun bedside(nextAlarmMs: Long?): os.meka.core.domain.BedsideView =
        os.meka.core.domain.FoldModeRules.bedside(
            nowMs(), ZoneCalendar(timeZone), nextAlarmMs, _notifySettings.value.quiet, _today.value, _brief.value, _shutdown.value,
        )

    /** The bedside clock as the charging screen saver (bedside clock, slice 3): the same view, nothing to tap, drifting. */
    fun bedsideDream(nextAlarmMs: Long?): os.meka.core.domain.BedsideDreamView =
        os.meka.core.domain.BedsideDreamRules.view(bedside(nextAlarmMs), nowMs())

    /**
     * The "now" card (Fold modes, slice 3): the one thing that matters now (an event or booked session starting or just
     * started, a session asking "Did you go?", Up next, or clear) with its one-tap actions, for the closed Fold's cover screen and the Mac's menu bar. Pure and
     * cheap, read from the current Today (which refreshes every minute). Nothing is stored.
     */
    fun coverNow(): os.meka.core.domain.NowView =
        os.meka.core.domain.CoverNowRules.now(_today.value, nowMs(), ZoneCalendar(timeZone), _sessions.value.cards, weekGoalTitles())

    /**
     * "Hello, Navilife": the greeting the orb says when tapped at rest ([os.meka.core.domain.HelloRules]), built by
     * rules from what the apps already show: his name in About you, Needs you's urgent cards, today's next event or the
     * next live train on an office commute, what his partner asked for that is still open (the open request cards she's
     * named on, [os.meka.core.domain.HelloRules.partnerAsks]; [partnerAsks] overrides it when given) and the tasks due
     * today with no time; quiet hours keep it to one thing. Instant and free: no AI call, nothing fetched, sent or stored.
     */
    suspend fun hello(partnerAsks: Int = -1, welcome: Boolean = false): os.meka.core.domain.HelloGreeting = onCore {
        val cal = ZoneCalendar(timeZone)
        val now = nowMs()
        val minute = cal.minuteOfDay(now)
        val profile = aboutYou.profile()
        // One name everywhere: no name in About you, and not asked before on either app → ask once, out loud.
        if (os.meka.core.domain.CallNameRules.asks(profile.shownName, nameAskedHere || aboutYou.nameAsked())) {
            nameAskedHere = true
            nameAskedAt = now
            aboutYou.markNameAsked()
            return@onCore os.meka.core.domain.CallNameRules.askGreeting(minute, welcome)
        }
        val cards = _needsYouStack.value.cards
        val event = _today.value.upcomingEvents(now).firstOrNull()?.let { e ->
            os.meka.core.domain.HelloEvent(
                e.title, cal.minuteOfDay(e.startAtMs), cal.minuteOfDay(e.endAtMs),
                minutesAway = ((e.startAtMs - now) / 60_000L).toInt().let { if (e.startAtMs <= now) minOf(it, 0) else maxOf(it, 1) },
            )
        }
        val route = os.meka.core.domain.RouteRules
        val board = route.commute(officeToday(), now, cal)?.let { route.liveBoard(lineStatus.snapshot(), it, now) }
        val train = board?.let { route.upcoming(it, now, cal, n = 1).firstOrNull() }
        val trainAway = train?.let { d -> ((if (d.cancelled) d.depMin else (d.expMin ?: d.depMin)) - minute).mod(1440) }
        // The orb says hello like a person: a different opener each tap, with his name, chosen here (no AI wait).
        val opener = helloOpener(now, cal, welcome)
        os.meka.core.domain.HelloRules.greeting(
            os.meka.core.domain.HelloFacts(
                minuteOfDay = minute,
                name = profile.shownName,
                urgent = cards.filter { it.urgent }.map { it.title },
                needsYou = cards.size,
                event = event,
                train = train,
                trainMinutesAway = trainAway,
                partnerName = profile.partnerName,
                partnerAsks = if (partnerAsks >= 0) partnerAsks
                else os.meka.core.domain.HelloRules.partnerAsks(profile.partnerName, _requests.value.map { it.person }),
                dueToday = cards.filter { it.kind == os.meka.core.domain.DecisionKind.DUE_TODAY }.map { it.title },
                quiet = _notifySettings.value.quiet.isQuietAt(now, cal),
                welcome = welcome,
                opener = opener,
            ),
        )
    }

    /**
     * This device's [os.meka.core.domain.HelloOpenerMemory], its recent openers merged with the ones the other app said
     * (hello slice 2, the synced profile's list); empty when none or unreadable. Caller is on the core.
     */
    private fun helloOpenerMemory(): os.meka.core.domain.HelloOpenerMemory {
        val o = os.meka.core.domain.HelloOpeners
        val local = o.decode(runCatching { localStore.transaction { localStore.localValue(o.KEY) } }.getOrNull())
        val shared = runCatching { aboutYou.helloRecent() }.getOrDefault(emptyList())
        return local.copy(recent = o.merged(local.recent, shared))
    }

    /** Hello slice 2: home from work, after the football, rain outside, from what the core holds now. Caller is on the core. */
    private fun helloMoments(now: Long, cal: ZoneCalendar): os.meka.core.domain.HelloMoments = runCatching {
        os.meka.core.domain.HelloMomentRules.of(officeToday(), _today.value.events, weather.forecast(), now, cal)
    }.getOrDefault(os.meka.core.domain.HelloMoments())

    /** Whether Ada already introduced herself today on this device (the hello's or the companion's first). Caller is on the core. */
    private fun adaIntroducedToday(day: Long): Boolean = os.meka.core.domain.AdaRules.dayOf(
        runCatching { localStore.transaction { localStore.localValue(os.meka.core.domain.AdaRules.INTRO_KEY) } }.getOrNull(),
    ) == day

    /**
     * The orb says hello like a person: picks this tap's opener ([os.meka.core.domain.HelloOpeners.pick]), keeps it
     * among the recent ones on this device, marks Ada's introduction for the day when it is the first hello, and makes
     * the next openers ready in Ada's voice in the background. Caller is on the core.
     */
    private fun helloOpener(now: Long, cal: ZoneCalendar, welcome: Boolean): os.meka.core.domain.HelloOpener {
        val o = os.meka.core.domain.HelloOpeners
        val day = cal.epochDayOf(now)
        val memory = helloOpenerMemory()
        val moment = o.moment(cal.minuteOfDay(now), day, now, memory, adaIntroducedToday(day), welcome, helloMoments(now, cal))
        val opener = o.pick(moment, memory.recent, day)
        val after = o.said(memory, opener, now)
        runCatching {
            localStore.transaction {
                localStore.setLocalValue(o.KEY, o.encode(after))
                if (moment.first) localStore.setLocalValue(os.meka.core.domain.AdaRules.INTRO_KEY, day.toString())
            }
        }
        // Hello slice 2: the last openers shared through the synced profile, so the other app doesn't echo this one.
        runCatching { aboutYou.setHelloRecent(after.recent) }
        if (speechApi != null && warmedName != null) {
            val w = warmedWelcome
            scope.launch { runCatching { warmHello(w) } }
        }
        return opener
    }

    /**
     * One name everywhere: when this device's hello asked what to call him (null: it hasn't). Kept here too, so a
     * profile that doesn't exist yet (nothing written) still asks only once per run; the reply is read for
     * [os.meka.core.domain.CallNameRules.PENDING_MS] after it.
     */
    private var nameAskedAt: Long? = null
    private var nameAskedHere = false

    /**
     * One name everywhere: his answer to the hello's "what should I call you?" (or "call me Alex" any time) comes back
     * as the name card, Save on his tap; "no thanks" just after the ask is answered kindly and nothing is asked again.
     * Null: not about his name (the question goes on as usual). No AI, nothing sent.
     */
    private suspend fun nameAsk(q: String): os.meka.core.domain.AskOutcome? = onCore {
        val rules = os.meka.core.domain.CallNameRules
        val pending = rules.pending(nameAskedAt, nowMs())
        val name = rules.heard(q, pending)
        when {
            name != null -> {
                nameAskedAt = null
                os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.AskAnswer(rules.answer(name), listOf(rules.card(name))))
            }
            pending && rules.declined(q) -> {
                nameAskedAt = null
                os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.AskAnswer(rules.DECLINED, emptyList()))
            }
            else -> {
                // Something else entirely (a question): the ask is over and the question goes on as usual.
                if (pending) nameAskedAt = null
                null
            }
        }
    }

    /**
     * "Hello, Navilife" slice 3, the Mac's welcome back: whether coming back after [awayMin] minutes away greets him
     * ([os.meka.core.domain.WelcomeBackRules.greets]), with quiet hours and protected time read here. Pure, nothing stored.
     */
    fun welcomeBackGreets(on: Boolean, awayMin: Int, minutesSinceHello: Int, micAllowed: Boolean): Boolean {
        val now = nowMs()
        val cal = ZoneCalendar(timeZone)
        val protectedNow = os.meka.core.domain.ProtectedRules.protectedUntil(
            protectedTime.setting(), now, work.schedule(), bankHolidays.calendar(), cal,
        ) != null
        return os.meka.core.domain.WelcomeBackRules.greets(
            on, awayMin, minutesSinceHello, _notifySettings.value.quiet.isQuietAt(now, cal), protectedNow, micAllowed,
        )
    }

    /**
     * "Hello, Navilife" slice 7: whether the short morning entrance plays on this open ([os.meka.core.domain.HelloEntrance.morningPlays]):
     * the first open of the morning, once a day ([lastDay] the epoch day it last played, kept on the device), never in
     * quiet hours or with [reduced] motion. Pure, nothing stored.
     */
    fun morningEntrancePlays(lastDay: Long?, reduced: Boolean): Boolean {
        val now = nowMs()
        val cal = ZoneCalendar(timeZone)
        return os.meka.core.domain.HelloEntrance.morningPlays(
            lastDay, todayEpochDay(), cal.minuteOfDay(now), _notifySettings.value.quiet.isQuietAt(now, cal), reduced,
        )
    }

    /** How many cards Needs you holds now: the Hello orb's warm halo while any do. */
    fun needsYouCount(): Int = _needsYouStack.value.cards.size

    /**
     * Up next as the closed Fold's card on every screen (Fold review 2026-10-09, item 3): "UP NEXT", the title, its line
     * and Done · Tomorrow · Open, for the open Fold's Today and the Mac's window. Null when nothing is up next. Pure.
     */
    fun upNextCard(): os.meka.core.domain.NowView? =
        os.meka.core.domain.CoverNowRules.upNext(_today.value, nowMs(), ZoneCalendar(timeZone), weekGoalTitles())

    /** This week's open goals (goal id → title): Up next's why says "On this week's goal" (Fold review 2026-10-12). */
    private fun weekGoalTitles(): Map<String, String> =
        _goals.value.week.arcs.filter { !it.done }.associate { it.goalId to it.title }

    /**
     * The Galaxy Watch's one screen (Galaxy Watch, slice 2): the cover screen's "now" card with only the taps a wrist can
     * make (Done · Tomorrow, Went · Didn't go) and the fast with Start or End. Pure and cheap. Nothing is stored.
     */
    fun watchHome(): os.meka.core.domain.WatchHomeView =
        os.meka.core.domain.WatchHomeRules.view(
            coverNow(), _fasting.value, nowMs(), watchInbox(), watchParking(),
            // Barça match day, slice 4: the match, worked out again at this minute from the last synced score.
            os.meka.core.domain.WatchMatchRules.view(_barcaNear.value, nowMs(), ZoneCalendar(timeZone)),
            // Meeting prep, slice 3: Today's prep (worked out each minute), its countdown at this minute.
            os.meka.core.domain.WatchPrepRules.view(_today.value.meetingPrep, nowMs(), ZoneCalendar(timeZone)),
            // Drafted replies, slice 4: the newest draft, as last worked out on the core's thread.
            watchDraftNow,
            // Done for the day, completeness slice 2: the evening glance from this watch's own shutdown view.
            os.meka.core.domain.WatchEveningRules.view(_shutdown.value),
            // Approve message drafts from the wrist, slice 1: the newest drafted message reply from the synced cards.
            os.meka.core.domain.WatchMessageDraftRules.view(_triage.value),
            // The commute counts everywhere, completeness: when to leave, as last worked out on the core's thread.
            watchCommuteNow,
            // Bin days, completeness: tonight's bins, as last worked out on the core's thread.
            watchBinsNow,
        )

    /** The watch's bins row, worked out with each refresh (each minute while the watch's screen is on). */
    private var watchBinsNow: os.meka.core.domain.WatchBins? = null
    private var watchBinRemind: Int = os.meka.core.domain.BinRules.NOTICE_MIN

    /** The watch's leave-by row, worked out with each refresh (each minute while the watch's screen is on). */
    private var watchCommuteNow: os.meka.core.domain.WatchCommute? = null

    /**
     * Approve message drafts from the wrist, slice 1: **Send** on the watch's message draft, Meka's tap. Asks the Fold to
     * send Navilife's draft, exactly as the wrist showed it whole, through the message's own notification Reply (the
     * Mac's Send (via your phone), [requestPhoneSend]); nothing is sent here. Only while the phone still holds the
     * message and no request waits; else nothing is written. Returns the line to show.
     */
    suspend fun watchSendMessageDraft(messageId: String): String {
        val R = os.meka.core.domain.WatchMessageDraftRules
        val asked = onCore {
            val words = R.sendable(triageCards.open(), messageId) ?: return@onCore false
            triageCards.requestPhoneSend(messageId, words)
        }
        return if (asked) R.ASKED else R.NOT_NOW
    }

    /** Done for the day, completeness slice 2: **Shut down** on the watch (the same as the pane's); what it says. */
    suspend fun watchShutDown(): String {
        shutDown()
        return os.meka.core.domain.WatchEveningRules.SHUT
    }

    /** Done for the day, completeness slice 2: **Later** on the watch's evening glance; "… comes back at 21:15" or null. */
    suspend fun watchShutdownLater(): String? = shutdownLater(null)

    /**
     * Car and parking, slice 7: the cars' parking timers for the wrist, worked out again at this minute from the synced
     * timers (so "40 min left" counts down between syncs). No location: where the car is stays on the Fold.
     */
    private fun watchParking(): List<os.meka.core.domain.WatchParking> {
        val now = nowMs()
        val timers = os.meka.core.domain.ParkingTimerRules.view(
            _parkingTimers.value.rows.map { it.timer }, _cars.value, now, ZoneCalendar(timeZone),
        )
        return os.meka.core.domain.WatchHomeRules.parking(timers, now)
    }

    /** Car and parking, slice 7: the watch's +15 min on car [carId]'s timer; the new line, or null without a timer. */
    suspend fun watchParkingExtend(carId: String): String? =
        extendParkingTimer(carId, os.meka.core.domain.WatchHomeRules.EXTEND_MIN)

    /**
     * Inbox autopilot, slice 7b-2: the watch's inbox count — the rows of "From your mail" (linked emails once, as in
     * the ten-minute inbox), lit while the ten-minute inbox's moment is up. Only the count reaches the wrist.
     */
    private fun watchInbox(): os.meka.core.domain.WatchInbox? =
        os.meka.core.domain.WatchHomeRules.inbox(mailWaitingRows, _mailMoment.value)

    /** The rows of "From your mail" as last counted on the core's thread (read by [watchHome] from any thread). */
    @kotlin.concurrent.Volatile
    private var mailWaitingRows = 0

    /** Drafted replies, slice 4: the newest draft for the wrist, as last worked out on the core's thread. */
    @kotlin.concurrent.Volatile
    private var watchDraftNow: os.meka.core.domain.WatchDraft? = null

    /**
     * Drafted replies, slice 4: **Send** on the watch's draft. Sends Navilife's draft exactly as it shows whole on the
     * wrist, through the phone's reply path ([replyToMailItem]: the server holds it 20 s, then sends it in the thread from
     * the account it came to). Only a draft that is still showing and short enough to have been read whole goes; else
     * nothing is written. Returns the line to show ("Sending to Sam · Undo stops it") and whether it is sending.
     */
    suspend fun watchSendDraft(mailId: String): Pair<String, Boolean> {
        val R = os.meka.core.domain.WatchDraftRules
        val found = onCore {
            val items = activity.mailItems()
            R.sendable(items, mailId, nowMs())?.let { it to items.first { i -> i.id == mailId }.from }
        } ?: return R.GONE to false
        // A sendable draft is never empty or too long, so the only other answer is that the email has just gone.
        val line = replyToMailItem(mailId, found.first)
        if (line == os.meka.core.domain.MailNeedsRules.UNKNOWN) return R.GONE to false
        return R.sendingLine(found.second) to true
    }

    /** Drafted replies, slice 4: **Not needed** on the watch's draft (the same as the phone's; the email stays). */
    suspend fun watchDismissDraft(mailId: String): String {
        val line = dismissMailDraft(mailId)
        return if (line == os.meka.core.domain.MailAutoDraftRules.NOT_NEEDED_LINE) os.meka.core.domain.WatchDraftRules.PUT_AWAY else line
    }

    /** Drafted replies, slice 4: Undo on the watch after Send or Not needed (this device's last mail change). */
    suspend fun watchDraftUndo() = undoMailItem()

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
    fun watchTile(): os.meka.core.domain.WatchTileView {
        val home = watchHome()
        // Galaxy Watch completeness slice 2: quiet hours drop the inbox count and say so at the foot.
        val t = os.meka.core.domain.WatchQuietRules.tile(os.meka.core.domain.WatchTileRules.tile(home), home, watchQuietLine())
        // Galaxy Watch completeness slice 1: "Waiting to sync" while changes made here wait to be sent.
        val waiting = watchPending() > 0
        val spokenMark = os.meka.core.domain.WatchSyncRules.spokenMark(waiting)
        return t.copy(
            syncMark = os.meka.core.domain.WatchSyncRules.tileMark(waiting),
            spoken = if (spokenMark == null) t.spoken else "${t.spoken} $spokenMark",
        )
    }

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

    /**
     * Voice notes into actions, slice 4: a voice note on the watch, started at [startedAtMs], whose recogniser wrote
     * down [words] (nothing recorded). They go once to the AI's tidy-up as the phone's do ([tidyVoiceNote]; the cards
     * sync to Needs you). When the AI can't answer, or the note is a few words, they are kept as one task instead
     * ("Voice note · 14:02" with the words in its notes; a few words are the title) so nothing is lost. Null when
     * nothing was heard.
     */
    suspend fun watchVoiceNote(words: String, startedAtMs: Long): os.meka.core.domain.WatchVoiceNoted? {
        val text = words.trim().takeIf { it.isNotEmpty() } ?: return null
        val rules = os.meka.core.domain.WatchVoiceNoteRules
        val subject = voiceNoteSubject(startedAtMs)
        val short = !os.meka.core.domain.HandleThisRules.worthAsking(text, null)
        if (!short) {
            val read = tidyVoiceNote(text, subject)
            when (val c = read.cards) {
                is HandleOutcome.Cards -> return rules.tidied(c.cards.size, read.tidy, read.people)
                is HandleOutcome.NothingToDo -> return rules.tidied(0, read.tidy, read.people)
                is HandleOutcome.Unavailable -> Unit
            }
        }
        val id = (if (short) capture(text, null) else capture(text, subject)) ?: return null
        return rules.keptAsTask(id, offline = !short)
    }

    /** Undo on the watch after a voice note kept as a task: the task is deleted on every device. */
    suspend fun watchVoiceNoteUndo(v: os.meka.core.domain.WatchVoiceNoted) {
        v.taskId?.let { delete(it) }
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
    fun watchRefreshAfterMs(): Long {
        // Galaxy Watch completeness slice 2: calmer in quiet hours, never past their end.
        val now = nowMs()
        val cal = ZoneCalendar(timeZone)
        val quiet = _notifySettings.value.quiet
        val on = quiet.isQuietAt(now, cal)
        val after = os.meka.core.domain.WatchQuietRules.refreshAfterMs(watchHome(), now, on, if (on) quiet.endAfter(now, cal) else null)
        // Bin days completeness 2: the tile's bins line lights at his reminder's time and goes at midnight, on time.
        val bins = os.meka.core.domain.BinRules.watchChangeInMin(watchBinsNow, cal.minuteOfDay(now), watchBinRemind)
            ?.let { it * 60_000L - now % 60_000L } ?: return after
        return minOf(after, bins.coerceAtLeast(1_000L))
    }

    /** Galaxy Watch completeness slice 2: "Quiet hours · until 07:00" while Navilife's quiet hours are on; else null. */
    fun watchQuietLine(): String? =
        os.meka.core.domain.WatchQuietRules.line(_notifySettings.value.quiet, nowMs(), ZoneCalendar(timeZone))

    /**
     * Galaxy Watch completeness slice 2: whether the watch's background sync should reach the server now, given the
     * last run that went through at [lastSyncMs] (kept by the watch). At most hourly in quiet hours unless changes made
     * here wait or something short is counting; always by day.
     */
    suspend fun watchSyncDue(lastSyncMs: Long?): Boolean = onCore {
        refresh() // a fresh background process: the quiet hours and the day as synced
        os.meka.core.domain.WatchQuietRules.syncDue(
            lastSyncMs, nowMs(), watchQuietLine() != null, replica.pendingPushCount() > 0,
            os.meka.core.domain.WatchQuietRules.urgent(watchHome()),
        )
    }

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
            os.meka.core.domain.CoverNowRules.now(today, nowMs(), cal, _sessions.value.cards, weekGoalTitles()), today, _lists.value.dueCount,
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
        /** Passes [refresh] makes at most when commits keep arriving while it runs (each pass sees them all). */
        private const val MAX_REFRESH_PASSES = 4
        const val FOREGROUND_SYNC_MS: Long = 30_000L
        /** Full news hub, slice 5: how often opening News asks the server which feeds aren't coming through. */
        const val NEWS_DOWN_EVERY_MS: Long = 10 * 60_000L
        /** AI layer completeness slice 3: how long an AI feature's line reuses the AI status last asked. */
        const val AI_SEEN_MS: Long = 2 * 60_000L
        const val SIGNED_OUT_MESSAGE = "This device was signed out of your server. Reconnect it with the enrolment code; nothing is lost."
        /** The key a platform reports among posted keys when the digest went out ([notificationsPostedKeys]). */
        const val DIGEST_KEY = "meka.digest"
        /** Trading export: how many times to ask while the server is still reading (each waits up to 20 s). */
        const val EXPORT_ASKS = 6
        /** Money manager (17): UK time, for whether he's abroad. */
        const val MONEY_HOME_ZONE = "Europe/London"
    }

    fun stopSync() { syncLoop?.cancel(); syncLoop = null }

    /** Attaches sync after enrolment (or swaps it), without restarting the app. Local data is kept and pushed. */
    suspend fun connect(transport: SyncTransport) = withContext(confined) {
        syncMutex.withLock { 
            syncClient = SyncClient(replica, transport); accountsApi = transport as? AccountsApi; releasesApi = transport as? ReleasesApi
            pushApi = transport as? PushApi; newsImagesApi = transport as? NewsImagesApi; aiApi = transport as? AiApi
            talkWarmApi = transport as? TalkWarmApi
            speechApi = transport as? SpeechApi; hereApi = transport as? HereApi; fuelApi = transport as? FuelApi; healthApi = transport as? HealthApi
            numberLookupApi = transport as? NumberLookupApi; tradingExportApi = transport as? TradingExportApi
            updateFailureApi = transport as? UpdateFailureApi; foldPostureApi = transport as? FoldPostureApi
            notifyDiagnosticsApi = transport as? NotifyDiagnosticsApi
            voiceMessageApi = transport as? VoiceMessageApi; familyApi = transport as? FamilyApi; deviceLinkApi = transport as? DeviceLinkApi
            recoveryApi = transport as? RecoveryApi; vaultFileApi = transport as? VaultFileApi
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

    private suspend fun rememberNewsDown(list: List<ConnectedAccount>) {
        val down = list.flatMap { it.newsDown }
        onCore {
            newsDownAskedMs = nowMs()
            if (down != newsDown) {
                newsDown = down
                refresh()
            }
        }
    }

    /**
     * Full news hub, slice 5: News asks the server which feeds aren't coming through when it opens (at most every
     * [NEWS_DOWN_EVERY_MS]); a source not read at its last tries is marked under Topics → Sources and named after the
     * lanes. Offline keeps what was known. Returns whether it asked.
     */
    suspend fun refreshNewsSources(): Boolean {
        val api = accountsApi ?: return false
        val last = onCore { newsDownAskedMs }
        if (last != null && nowMs() - last < NEWS_DOWN_EVERY_MS) return false
        val list = try { api.accounts() } catch (e: CancellationException) { throw e } catch (e: Exception) { null } ?: return false
        rememberNewsDown(list)
        return true
    }

    /** Accounts connected for this household, for the Calendars screen. Empty when offline or not connected. */
    suspend fun connectedAccounts(): List<ConnectedAccount> {
        val list = try { accountsApi?.accounts() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        // Offline keeps what was known: the server checks again before sending anything.
        list?.let(::rememberEditing)
        list?.let { rememberNewsDown(it) }
        // Mail accounts have their own pane (Inbox autopilot); Calendars lists calendars and feeds.
        val accounts = list?.filter { !os.meka.core.domain.MailProviders.isMail(it.provider) } ?: return emptyList()
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

    // ---- Mail (Inbox autopilot, slice 1) ----

    private fun archivedSeen(): Long? = os.meka.core.domain.ArchivedForYouRules.readSeen(
        replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.ArchivedForYouRules.ENTITY_ID)?.fields,
    )

    private fun archivedChanged() {
        _archivedTick.value = _archivedTick.value + 1
        _archivedForYou.value = os.meka.core.domain.ArchivedForYouRules.card(
            activity.mailTidyBatches(), archivedSeen(), nowMs(), ZoneCalendar(timeZone),
        )
    }

    /** Got it on "Archived for you": folds it away on both apps (synced) until the next digest finds new tidies. */
    suspend fun seenArchivedForYou(): String = onCore {
        archivedSeenBefore = archivedSeen()
        archivedSeenUndoable = true
        replica.commitLocal(
            os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.ArchivedForYouRules.ENTITY_ID,
            os.meka.core.domain.ArchivedForYouRules.seenFields(nowMs()),
        )
        archivedChanged()
        os.meka.core.domain.ArchivedForYouRules.GOT_IT_LINE
    }

    /** Undo on Got it: the card comes back as it was. */
    suspend fun undoSeenArchivedForYou() = onCore {
        if (!archivedSeenUndoable) return@onCore
        archivedSeenUndoable = false
        replica.commitLocal(
            os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.ArchivedForYouRules.ENTITY_ID,
            os.meka.core.domain.ArchivedForYouRules.seenFields(archivedSeenBefore ?: 1L),
        )
        archivedChanged()
    }

    private fun tidyBatch(batchId: String): os.meka.core.domain.MailTidyBatch? =
        replica.entity(os.meka.core.domain.EntityTypes.MAIL_TIDY, batchId)?.let { s ->
            os.meka.core.domain.MailAutopilotRules.readBatch(batchId, s.fields)
        }

    /**
     * "Archived for you" shows which emails, slice 2: the preview of one email of a tidy (tap its row), or null when the
     * tidy or the email isn't there any more.
     */
    suspend fun archivedEmail(batchId: String, key: String): os.meka.core.domain.ArchivedEmailPreview? = onCore {
        val facts = lastMailFacts
        tidyBatch(batchId)?.let { b ->
            val cal = ZoneCalendar(timeZone)
            os.meka.core.domain.MailTidiedAskRules.preview(b, key, nowMs(), cal)?.copy(
                // Slice 4: the button always; its line once the account list is known (Mail's, or a tap's).
                openLabel = os.meka.core.domain.MailOpenRules.label(b, facts),
                openHint = facts?.let { os.meka.core.domain.MailOpenRules.link(b, key, it, cal)?.hint },
                vault = b.emails.firstOrNull { it.key == key }?.let { os.meka.core.domain.VaultReceiptRules.offer(it, documents.items()) },
            )
        }
    }

    // ---- Receipts → Vault ----

    /**
     * Receipts → Vault: **Keep in the Vault** on a receipt's preview — a Vault document made from what the tidy holds
     * (sender, subject, day, price; the warranty's end when the email says or Renewals has it). Returns the line to
     * show, or null when the email isn't a receipt or has gone.
     */
    suspend fun keepReceiptInVault(batchId: String, key: String): os.meka.core.domain.DocumentAdded? = onCore {
        val b = tidyBatch(batchId) ?: return@onCore null
        val e = b.emails.firstOrNull { it.key == key } ?: return@onCore null
        val level = os.meka.core.domain.MailWarrantyRules.read(
            replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailWarrantyRules.ENTITY_ID)?.fields,
        )
        val (entry, receipt) = os.meka.core.domain.VaultReceiptRules.entry(b, e, ZoneCalendar(timeZone), level) ?: return@onCore null
        documents.keepReceipt(entry, receipt).also { archivedChanged() }
    }

    /** Receipts → Vault: **Take it out of the Vault** on the same preview (its copy too). Returns the line, or null. */
    suspend fun takeReceiptOutOfVault(key: String): String? {
        val d = onCore { documents.byEmail(key) } ?: return null
        if (!removeDocument(d.id)) return null
        onCore { archivedChanged() }
        return os.meka.core.domain.VaultReceiptRules.takenOutLine(d.title)
    }

    /**
     * Receipts → Vault: **Open the email** on a kept receipt's row: the same link Archived for you makes, while the tidy
     * is kept; after that, what to search for ([os.meka.core.domain.MailOpenResult.problem]).
     */
    suspend fun vaultReceiptLink(docId: String): os.meka.core.domain.MailOpenResult {
        val r = onCore { documents.item(docId)?.receipt }
            ?: return os.meka.core.domain.MailOpenResult(null, os.meka.core.domain.VaultReceiptRules.GONE)
        val res = archivedEmailLink(r.batchId, r.emailKey)
        return if (res.link == null && res.problem == os.meka.core.domain.MailTidiedAskRules.UNKNOWN)
            os.meka.core.domain.MailOpenResult(null, os.meka.core.domain.VaultReceiptRules.searchLine(r))
        else res
    }

    /**
     * Read the full email inside Navilife (2026-10-20), slice 1: a "From your mail" card's email read whole from its
     * provider now (through the server, by the card's id), made safe, for the reader. [images]: Show images was tapped.
     * When it can't be read the reader shows the card's own preview with the reason. Nothing is kept; not marked read.
     */
    suspend fun readMailItem(id: String, images: Boolean = false): os.meka.core.domain.MailReaderView {
        val item = onCore { activity.mailItems().firstOrNull { it.id == id } }
        val fallback = os.meka.core.domain.MailReaderFallback(
            item?.from ?: "Unknown sender", item?.subject ?: "", item?.preview, item?.receivedAtMs ?: nowMs(), item?.account ?: "your",
        )
        val view = readMail(os.meka.core.wire.MailMessageCodec.Request(os.meka.core.wire.MailMessageCodec.Request.ITEM, id, images = images), fallback, images)
        item ?: return view
        // Slice 3: Open in Gmail/Outlook at the reader's foot, made now from the card and the account list (never kept).
        val facts = lastMailFacts ?: if (item.account == os.meka.core.domain.MailLabel.OUTLOOK.label) emptyList() else try {
            accountsApi?.mailAccounts()?.also { lastMailFacts = it }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val address = view.fromAddress ?: view.from.takeIf { it.contains('@') }
        val link = os.meka.core.domain.MailOpenRules.itemLink(
            item.account, item.from, address.takeIf { view.problem == null }, item.subject, item.receivedAtMs,
            done = item.doneAtMs != null && item.undoneAtMs == null, accounts = facts.orEmpty(), cal = ZoneCalendar(timeZone),
        )
        return view.copy(openLink = link)
    }

    /**
     * Read the full email, slice 2: the reader's attachment [index] (of [view]) fetched from the provider now and made
     * ready to open — a picture or PDF for Navilife's own viewer (only when its bytes are one), another file for another
     * app (after the reader's confirm), or a line why not. A file that could run something, or over 15 MB, isn't
     * fetched at all. Nothing is kept; the AI never sees it.
     */
    suspend fun openMailAttachment(view: os.meka.core.domain.MailReaderView, index: Int): os.meka.core.domain.MailAttachmentOpened {
        val rules = os.meka.core.domain.MailAttachmentRules
        val row = view.attachments.firstOrNull { it.index == index }
            ?: return os.meka.core.domain.MailAttachmentOpened("Attachment", rules.PROBLEM, line = rules.GONE_LINE)
        val src = view.source ?: return rules.opened(row, os.meka.core.domain.MailMessageAnswer.GONE, null) { "" }
        return openMailAttachmentAt(src.kind, src.id, src.batch, row.index, row.name, row.type, row.bytes)
    }

    /**
     * [openMailAttachment] from plain values (the Mac hands only Strings and numbers to the core): the email's handle
     * ([kind], [id], [batch] from the reader's source) and the row's place, name, type and size.
     */
    suspend fun openMailAttachmentAt(
        kind: String, id: String, batch: String?, index: Int, name: String, type: String?, size: Long,
    ): os.meka.core.domain.MailAttachmentOpened {
        val rules = os.meka.core.domain.MailAttachmentRules
        val row = os.meka.core.domain.MailAttachmentRow(name, "", index, rules.plan(name, type, size), type, size)
        if (row.plan == rules.REFUSED || row.plan == rules.TOO_BIG) return rules.opened(row, null, null) { "" }
        val fetched = try {
            accountsApi?.mailAttachment(os.meka.core.wire.MailMessageCodec.AttachmentRequest(kind, id, batch, index, name))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        return rules.opened(row, fetched?.state, fetched?.bytes) { kotlin.io.encoding.Base64.encode(it) }
    }

    /** The same for one email of a tidy ("Archived for you", All tidied mail): archived, in Junk or in Trash. */
    suspend fun readArchivedMail(batchId: String, key: String, images: Boolean = false): os.meka.core.domain.MailReaderView {
        val found = onCore { tidyBatch(batchId)?.let { b -> b.emails.firstOrNull { it.key == key }?.let { b to it } } }
        val e = found?.second
        val fallback = os.meka.core.domain.MailReaderFallback(
            e?.from ?: "Unknown sender", e?.subject ?: "", e?.preview, e?.receivedAtMs ?: nowMs(), found?.first?.account ?: "your",
        )
        return readMail(os.meka.core.wire.MailMessageCodec.Request(os.meka.core.wire.MailMessageCodec.Request.TIDIED, key, batchId, images), fallback, images)
    }

    private suspend fun readMail(
        request: os.meka.core.wire.MailMessageCodec.Request, fallback: os.meka.core.domain.MailReaderFallback, images: Boolean,
    ): os.meka.core.domain.MailReaderView {
        val source = os.meka.core.domain.MailReaderSource(request.kind, request.id, request.batch)
        val r = try { accountsApi?.mailMessage(request) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val answer = r?.let { x ->
            fun p(q: os.meka.core.wire.MailMessageCodec.Person) = os.meka.core.domain.MailPerson(q.name, q.address)
            os.meka.core.domain.MailMessageAnswer(
                state = x.state.takeIf { it in os.meka.core.domain.MailMessageAnswer.STATES } ?: os.meka.core.domain.MailMessageAnswer.FAILED,
                from = p(x.from), to = x.to.map(::p), cc = x.cc.map(::p), atMs = x.atMs, subject = x.subject, html = x.html, text = x.text,
                attachments = x.attachments.map { os.meka.core.domain.MailAttachmentFacts(it.name, it.bytes, it.type) },
                blockedImages = x.blockedImages,
            )
        }
        // Slice 3: the server marks it read when Mail's "Mark an email read when I open it" is on; the note says so.
        val marksRead = onCore { mailAutopilotSetting().readerMarksRead }
        return os.meka.core.domain.MailReaderRules.view(answer, fallback, nowMs(), ZoneCalendar(timeZone), images, source, marksRead)
    }

    /** A link tapped in the reader: where it really goes, for the confirm, or null when Navilife won't open it. */
    fun mailLink(url: String): os.meka.core.domain.MailLinkCheck? = os.meka.core.domain.MailReaderLinks.check(url)

    /**
     * "Archived for you" shows which emails, slice 4: **Open in Gmail / Open in Outlook** on an email's preview. The
     * link is made now from the tidy and the server's account list (asked for when this device hasn't got it yet), handed
     * to the browser and never kept; [os.meka.core.domain.MailOpenResult.problem] says why there's none.
     */
    suspend fun archivedEmailLink(batchId: String, key: String): os.meka.core.domain.MailOpenResult {
        val facts = lastMailFacts ?: try {
            accountsApi?.mailAccounts()?.also { lastMailFacts = it }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        return onCore {
            val unknown = os.meka.core.domain.MailOpenResult(null, os.meka.core.domain.MailTidiedAskRules.UNKNOWN)
            val b = tidyBatch(batchId)?.takeIf { t -> t.emails.any { it.key == key } } ?: return@onCore unknown
            when {
                facts == null && b.account != os.meka.core.domain.MailLabel.OUTLOOK.label ->
                    os.meka.core.domain.MailOpenResult(null, os.meka.core.domain.MailOpenRules.OFFLINE)
                else -> os.meka.core.domain.MailOpenRules.link(b, key, facts.orEmpty(), ZoneCalendar(timeZone))
                    ?.let { os.meka.core.domain.MailOpenResult(it, null) }
                    ?: os.meka.core.domain.MailOpenResult(null, os.meka.core.domain.MailOpenRules.NO_ACCOUNT)
            }
        }
    }

    /**
     * Slice 2: Put back, Always keep from this sender or Unsubscribe on one email ([action] a [MailTidiedAction] name):
     * synced, done by the server at its next check. Returns the bar's line, or null when it can't be asked (already
     * asked, too old, gone).
     */
    suspend fun askArchivedEmail(batchId: String, key: String, action: String): String? = onCore {
        val a = os.meka.core.domain.MailTidiedAction.of(action) ?: return@onCore null
        val b = tidyBatch(batchId) ?: return@onCore null
        val fields = os.meka.core.domain.MailTidiedAskRules.ask(b, key, a, nowMs()) ?: return@onCore null
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_TIDY, batchId, fields)
        archivedChanged()
        val e = b.emails.first { it.key == key }
        os.meka.core.domain.MailTidiedAskRules.barLine(a, e.from, junk = e.tidy == os.meka.core.domain.MailTidy.JUNK_TRASH || e.tidy == os.meka.core.domain.MailTidy.RESCUE)
    }

    /**
     * "Archived for you" shows which emails, slice 3: **All tidied mail** — every email tidied in the last 30 days, newest
     * first by day, filtered to one sender when [sender] (an [os.meka.core.domain.ArchivedAllSender.id]) is given. Read
     * again after an ask or as tidies sync; each row opens [archivedEmail].
     */
    suspend fun archivedAll(sender: String?): os.meka.core.domain.ArchivedAllList = onCore {
        os.meka.core.domain.ArchivedAllRules.list(activity.mailTidyBatches(), sender, nowMs(), ZoneCalendar(timeZone))
    }

    /** Undo on the bar after [askArchivedEmail]: the ask comes off while the server hasn't acted on it yet. */
    suspend fun undoArchivedEmail(batchId: String, key: String, action: String) = onCore {
        val a = os.meka.core.domain.MailTidiedAction.of(action) ?: return@onCore
        val b = tidyBatch(batchId) ?: return@onCore
        val fields = os.meka.core.domain.MailTidiedAskRules.unask(b, key, a) ?: return@onCore
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_TIDY, batchId, fields)
        archivedChanged()
    }

    private fun unopenedSeen(): Long? = os.meka.core.domain.MailUnopenedRules.readSeen(
        replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailUnopenedRules.ENTITY_ID)?.fields,
    )

    private fun unopenedChanged() {
        _mailUnopened.value = os.meka.core.domain.MailUnopenedRules.card(activity.mailSenders(), unopenedSeen(), nowMs(), ZoneCalendar(timeZone))
    }

    private fun writeUnopenedSeen(atMs: Long) = replica.commitLocal(
        os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailUnopenedRules.ENTITY_ID,
        os.meka.core.domain.MailUnopenedRules.seenFields(atMs),
    )

    /**
     * Inbox autopilot, slice 5c: **Unsubscribe from all** on the weekly offer: asks the server to unsubscribe from each
     * offered sender at its next check (as Mail's own Unsubscribe does) and folds the offer away on both apps until next
     * Saturday. Undo (before the server's check) takes the asks back, so nothing is sent. Returns the line to show.
     */
    suspend fun unsubscribeUnopened(): String = onCore {
        val now = nowMs()
        val card = os.meka.core.domain.MailUnopenedRules.card(activity.mailSenders(), unopenedSeen(), now, ZoneCalendar(timeZone))
            ?: return@onCore os.meka.core.domain.MailUnopenedRules.NONE_LEFT
        val senders = activity.mailSenders().associateBy { it.id }
        val before = unopenedSeen()
        val asked = card.ids.filter { id ->
            val ask = os.meka.core.domain.MailSenderPanelRules.ask(senders[id], now) ?: return@filter false
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_SENDER, id, ask)
            true
        }
        writeUnopenedSeen(now)
        unopenedUndo = {
            val nowSenders = activity.mailSenders().associateBy { it.id }
            asked.forEach { id ->
                os.meka.core.domain.MailUnopenedRules.undoAsk(nowSenders[id])?.let { replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_SENDER, id, it) }
            }
            writeUnopenedSeen(before ?: 1L)
        }
        activityChanged()
        unopenedChanged()
        os.meka.core.domain.MailUnopenedRules.askedLine(asked.size)
    }

    /** Not now on the weekly offer: folded away on both apps until next Saturday (with Undo). */
    suspend fun notNowUnopened(): String = onCore {
        val before = unopenedSeen()
        writeUnopenedSeen(nowMs())
        unopenedUndo = { writeUnopenedSeen(before ?: 1L) }
        unopenedChanged()
        os.meka.core.domain.MailUnopenedRules.NOT_NOW_LINE
    }

    /** Keep on one sender of the weekly offer: never offered again (synced, with Undo); Mail still lists it. */
    suspend fun keepMailSender(id: String): String = onCore {
        val s = activity.mailSenders().firstOrNull { it.id == id } ?: return@onCore os.meka.core.domain.MailSenderPanelRules.UNKNOWN
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_SENDER, id, os.meka.core.domain.MailUnopenedRules.keepFields(nowMs()))
        unopenedUndo = { replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_SENDER, id, os.meka.core.domain.MailUnopenedRules.keepFields(0L)) }
        unopenedChanged()
        os.meka.core.domain.MailUnopenedRules.keptLine(s.name)
    }

    // ---- Protected time, slice 3: work mail waits until morning ----

    /** When the business mail held now comes back (the morning after protected time), or null when nothing is held. */
    private fun mailHeldUntil(): Long? {
        val s = protectedTime.setting() ?: return null
        if (!s.holdMail) return null
        return os.meka.core.domain.ProtectedRules.protectedUntil(s, nowMs(), work.schedule(), bankHolidays.calendar(), ZoneCalendar(timeZone))
    }

    /** The emails "From your mail" shows now: the synced ones less the business mail protected time holds. */
    private fun mailShown(items: List<os.meka.core.domain.MailItem> = activity.mailItems()): List<os.meka.core.domain.MailItem> {
        val held = os.meka.core.domain.ProtectedMailRules.held(items, protectedTime.setting(), mailHeldUntil())
        return if (held.isEmpty()) items else items.filterNot { it.id in held }
    }

    /** How many emails that would be on the card are held now. */
    private fun mailHeldCount(items: List<os.meka.core.domain.MailItem> = activity.mailItems()): Int {
        val held = os.meka.core.domain.ProtectedMailRules.held(items, protectedTime.setting(), mailHeldUntil())
        if (held.isEmpty()) return 0
        return os.meka.core.domain.MailNeedsRules.showingSorted(items.filter { it.id in held }, nowMs()).size
    }

    /** Protected time → Work mail: [hold] true waits until morning, false shows it as it comes. Null when nothing changed. */
    suspend fun setProtectedMail(hold: Boolean): String? = onCore {
        if (!protectedTime.setHoldMail(hold)) return@onCore null
        refresh()
        if (hold) os.meka.core.domain.ProtectedMailRules.ON else os.meka.core.domain.ProtectedMailRules.OFF
    }

    private fun mailNeedsChanged() {
        val all = activity.mailItems()
        val items = mailShown(all)
        val cal = ZoneCalendar(timeZone)
        _mailNeeds.value = os.meka.core.domain.MailNeedsRules.card(items, nowMs(), cal)?.copy(
            heldLine = os.meka.core.domain.ProtectedMailRules.cardLine(mailHeldCount(all), mailHeldUntil(), nowMs(), cal),
        )
        // Drafted replies, slice 2: Today's count follows Send, Not needed, Done and Snooze at once on this app.
        val replies = os.meka.core.domain.MailAutoDraftRules.todayLine(items, nowMs())
        if (_today.value.mailReplies != replies) _today.value = _today.value.copy(mailReplies = replies)
        mailMomentChanged(items)
        _mailChases.value = os.meka.core.domain.MailChaseRules.card(
            items, lists.waitingItems(), aboutYou.profile().shownName, nowMs(), ZoneCalendar(timeZone), activity.mailFinds(),
        )
        mailFindsChanged()
        // Drafted replies, slice 3: a reply sent or taken back shows in Activity at once.
        activityChanged()
    }

    private fun radarFields(): List<Map<String, FieldValue>> =
        replica.entities(os.meka.core.domain.EntityTypes.OBLIGATION).map { it.fields }

    private fun mailFindsChanged() {
        _mailFinds.value = os.meka.core.domain.MailRenewalRules.card(
            activity.mailFinds(), radarFields(), nowMs(), ZoneCalendar(timeZone), lists.waitingItems(), followUpWaits(),
            school.items(),
        )
    }

    /** Follow-up radar, slice 3: his own waits per person (absent = the default 3 days). */
    private fun followUpWaits(): List<os.meka.core.domain.FollowUpWait> =
        replica.entities(os.meka.core.domain.EntityTypes.FOLLOW_UP_WAIT)
            .mapNotNull { os.meka.core.domain.MailFollowUpRules.readWait(it.ref.entityId, it.fields) }

    /**
     * Follow-up radar, slice 3: a chip under **Wait 3 days ▾** on a No reply yet row — how many days he waits for that
     * person's replies before Navilife offers a chase ([os.meka.core.domain.MailFollowUpRules.WAIT_CHOICES]), for this
     * email and every later one to them, on both apps. The row waits until then if that day hasn't come. Undo puts the
     * last wait back. Nothing is sent. Returns the line to show.
     */
    suspend fun setFollowUpWait(findId: String, days: Int): String = onCore {
        val U = os.meka.core.domain.MailFollowUpRules
        val f = activity.mailFinds().firstOrNull { it.id == findId && it.kind == U.KIND_FOLLOW_UP }
            ?: return@onCore os.meka.core.domain.MailRenewalRules.UNKNOWN
        if (days !in U.WAIT_CHOICES) return@onCore os.meka.core.domain.MailRenewalRules.UNKNOWN
        val who = U.title(f)
        val wid = U.waitId(f.merchant)
        val before = replica.entity(os.meka.core.domain.EntityTypes.FOLLOW_UP_WAIT, wid)?.fields
        replica.commitLocal(os.meka.core.domain.EntityTypes.FOLLOW_UP_WAIT, wid, U.waitFields(who, days, nowMs()))
        mailFindUndo = {
            replica.commitLocal(
                os.meka.core.domain.EntityTypes.FOLLOW_UP_WAIT, wid,
                before?.let { it + mapOf(os.meka.core.domain.FollowUpWaitFields.AT to nowMs().fv()) } ?: U.waitFields(who, 0, nowMs()),
            )
        }
        mailFindsChanged()
        U.waitSetLine(f, days, ZoneCalendar(timeZone).epochDayOf(nowMs()))
    }

    // ---- Follow-up radar, slice 4: his per-person waits in Mail settings ----

    private var followUpWaitUndo: (() -> Unit)? = null

    /** Mail → Chase waits on both apps: everyone he set his own wait for, by name, and the usual for everyone else. */
    suspend fun followUpWaitsPanel(): os.meka.core.domain.FollowUpWaitsPanel = onCore {
        os.meka.core.domain.FollowUpWaitsRules.panel(followUpWaits(), ZoneCalendar(timeZone))
    }

    /**
     * Life audit, slice 2: Mail → Life audit on both apps — what 60 days of mail turned up as one review (subscriptions
     * and their monthly cost, emails waiting on him, waiting on others, warranties, bills due), counted from the synced
     * data; null while no mail is connected. Read-only: nothing is fetched, changed or sent.
     */
    suspend fun lifeAuditReview(): os.meka.core.domain.LifeAuditReview? = onCore {
        val items = activity.mailItems()
        val finds = activity.mailFinds()
        val accounts = os.meka.core.domain.LifeAuditReviewRules.accounts(lastMailFacts, items.map { it.account } + finds.map { it.account })
        os.meka.core.domain.LifeAuditReviewRules.review(
            accounts, items, finds, renewals.items(), lists.waitingItems(), radarFields(), followUpWaits(), nowMs(), ZoneCalendar(timeZone),
        )
    }

    /**
     * Mail → Chase waits: a chip on one person's row ([days] one of [os.meka.core.domain.MailFollowUpRules.WAIT_CHOICES])
     * or **Back to usual** ([days] 0). Synced, so both apps' rows and Needs you follow; [undoFollowUpWaitChange] puts it
     * back. Nothing is sent. Returns the line to show.
     */
    suspend fun changeFollowUpWait(waitId: String, days: Int): String = onCore {
        val U = os.meka.core.domain.MailFollowUpRules
        val R = os.meka.core.domain.FollowUpWaitsRules
        val w = followUpWaits().firstOrNull { it.id == waitId } ?: return@onCore os.meka.core.domain.MailRenewalRules.UNKNOWN
        if (days != 0 && days !in U.WAIT_CHOICES) return@onCore os.meka.core.domain.MailRenewalRules.UNKNOWN
        // Undo writes the person's wait back as it was, with the day he first set it.
        val before = U.waitFields(w.who, w.days, w.atMs)
        replica.commitLocal(os.meka.core.domain.EntityTypes.FOLLOW_UP_WAIT, waitId, U.waitFields(w.who, days, nowMs()))
        followUpWaitUndo = { replica.commitLocal(os.meka.core.domain.EntityTypes.FOLLOW_UP_WAIT, waitId, before) }
        mailFindsChanged()
        if (days == 0) R.resetLine(w.who) else R.changedLine(w.who, days)
    }

    /** Undo on Mail → Chase waits' last change on this device. Returns the line to show, or null with nothing to undo. */
    suspend fun undoFollowUpWaitChange(): String? = onCore {
        val undo = followUpWaitUndo ?: return@onCore null
        followUpWaitUndo = null
        undo()
        mailFindsChanged()
        os.meka.core.domain.FollowUpWaitsRules.UNDONE
    }

    /**
     * Promises, receipts and renewals from email, slice 1: **Add** on a renewal in "Renewals from your mail": it goes on
     * Lists → Renewals (one reminder per find, so Add on both apps lands on the same one) with the day, price, kind and
     * repeat the email gave, and the find is answered on both apps. Undo takes the reminder off and brings the find back.
     * Nothing is paid or cancelled. Returns the line to show.
     */
    suspend fun addMailFind(id: String): String = onCore {
        val R = os.meka.core.domain.MailRenewalRules
        val f = activity.mailFinds().firstOrNull { it.id == id } ?: return@onCore R.UNKNOWN
        val cal = ZoneCalendar(timeZone)
        if (f.kind == os.meka.core.domain.MailPromiseRules.KIND_PROMISE) return@onCore addMailPromise(f, cal)
        if (f.kind == os.meka.core.domain.MailFollowUpRules.KIND_FOLLOW_UP) return@onCore addMailFollowUp(f)
        if (f.kind == os.meka.core.domain.SchoolMailRules.KIND_SCHOOL) return@onCore addMailSchool(f, cal)
        if (f.kind == os.meka.core.domain.MailRefundRules.KIND_REFUND) return@onCore closeMailRefund(f, cal)
        val oid = R.obligationId(id)
        val before = replica.entity(os.meka.core.domain.EntityTypes.OBLIGATION, oid)
        // Slice 3: a renewal added on its own and undone (its item deleted) comes back on this tap.
        val revive = if (before?.deleted == true) mapOf(os.meka.core.sync.DELETED_FIELD to false.fv()) else emptyMap()
        replica.commitLocal(os.meka.core.domain.EntityTypes.OBLIGATION, oid, R.obligationFields(f, nowMs(), cal) + revive)
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, id, R.stateFields(os.meka.core.domain.MailFindState.ADDED, nowMs()))
        mailFindUndo = {
            if (before == null) {
                replica.commitLocal(os.meka.core.domain.EntityTypes.OBLIGATION, oid, mapOf(
                    os.meka.core.domain.ActionableFields.LIFECYCLE to os.meka.core.domain.Lifecycle.CANCELLED.name.fv(),
                    os.meka.core.domain.ActionableFields.COMPLETED_AT to nowMs().fv(),
                ))
            } else {
                replica.commitLocal(os.meka.core.domain.EntityTypes.OBLIGATION, oid, before.fields)
            }
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, id, R.stateFields(os.meka.core.domain.MailFindState.OPEN, nowMs()))
        }
        refresh()
        R.addedLine(f, cal.epochDayOf(nowMs()))
    }

    /**
     * Promises, receipts and renewals from email, slice 2: **Add** on a promise — a Waiting for item from them (one per
     * find, so Add on both apps lands on the same item), chased the day after the day they gave (else on the find's
     * default day), with a note saying which email it came from; the find is answered on both apps. Undo removes the
     * item and brings the find back. Nothing is sent to anyone.
     */
    private fun addMailPromise(f: os.meka.core.domain.MailFind, cal: ZoneCalendar): String {
        val P = os.meka.core.domain.MailPromiseRules
        val today = cal.epochDayOf(nowMs())
        val wid = lists.addWaiting(
            f.what ?: "What ${P.title(f)} promised", f.merchant, P.chaseInDays(f, today), withId = P.waitingId(f.id),
        )
        lists.editWaiting(wid, notes = P.note(f))
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, f.id, os.meka.core.domain.MailRenewalRules.stateFields(os.meka.core.domain.MailFindState.ADDED, nowMs()))
        mailFindUndo = {
            runCatching { lists.deleteWaiting(wid) }
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, f.id, os.meka.core.domain.MailRenewalRules.stateFields(os.meka.core.domain.MailFindState.OPEN, nowMs()))
        }
        refresh()
        return P.addedLine(f, today)
    }

    /**
     * Returns and warranties, completeness slice 2: **Close it** on a refund from the shop's email — the refund row it
     * matches on Waiting for ([os.meka.core.domain.MailRefundRules.match]) leaves the list as received, and the find is
     * answered on both apps. Undo puts the row back as it was and brings the find back. Nothing is sent to anyone.
     */
    private fun closeMailRefund(f: os.meka.core.domain.MailFind, cal: ZoneCalendar): String {
        val F = os.meka.core.domain.MailRefundRules
        val w = F.match(f, lists.waitingItems(), cal) ?: return F.GONE
        val before = chaseState(w.id)
        lists.received(w.id)
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, f.id, os.meka.core.domain.MailRenewalRules.stateFields(os.meka.core.domain.MailFindState.ADDED, nowMs()))
        mailFindUndo = {
            replica.commitLocal(os.meka.core.domain.EntityTypes.COMMITMENT, w.id, before)
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, f.id, os.meka.core.domain.MailRenewalRules.stateFields(os.meka.core.domain.MailFindState.OPEN, nowMs()))
        }
        refresh()
        return F.closedLine(f, w, cal.epochDayOf(nowMs()))
    }

    /**
     * Follow-up radar, slice 1: **Add** on an email of his with no reply — a Waiting for item from them (one per find,
     * so Add on both apps lands on the same item), to chase today, with a note saying which email; the find is answered
     * on both apps. Undo removes the item and brings the find back. Nothing is sent to anyone.
     */
    private fun addMailFollowUp(f: os.meka.core.domain.MailFind): String {
        val U = os.meka.core.domain.MailFollowUpRules
        val wid = lists.addWaiting(
            f.what ?: "A reply", f.merchant, 0, withId = os.meka.core.domain.MailPromiseRules.waitingId(f.id),
        )
        lists.editWaiting(wid, notes = U.note(f))
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, f.id, os.meka.core.domain.MailRenewalRules.stateFields(os.meka.core.domain.MailFindState.ADDED, nowMs()))
        mailFindUndo = {
            runCatching { lists.deleteWaiting(wid) }
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, f.id, os.meka.core.domain.MailRenewalRules.stateFields(os.meka.core.domain.MailFindState.OPEN, nowMs()))
        }
        refresh()
        return U.addedLine(f)
    }

    /**
     * School and family logistics from email, slice 1: **Add** on a school email's find — its line goes into School (one
     * per find, so Add on both apps lands on the same line) as he'd have typed it, marked "From the school's email", so
     * School's reminders, Today, the week-ahead question and Kids' week follow; the find is answered on both apps. Undo
     * removes the line and brings the find back. Nothing is paid, signed or sent.
     */
    private fun addMailSchool(f: os.meka.core.domain.MailFind, cal: ZoneCalendar): String {
        val S = os.meka.core.domain.SchoolMailRules
        if (S.isAsk(f)) {
            // Slice 2: an ask ("Please send in a named coat") becomes a task due the school morning, never a School line.
            val taskId = tasks.createWithId(S.taskId(f.id), os.meka.core.domain.NewTask(S.taskTitle(f), notes = S.taskNotes(f), dueAtMs = S.taskDueMs(f, nowMs(), cal)))
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, f.id, os.meka.core.domain.MailRenewalRules.stateFields(os.meka.core.domain.MailFindState.ADDED, nowMs()))
            mailFindUndo = {
                runCatching { tasks.delete(taskId) }
                replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, f.id, os.meka.core.domain.MailRenewalRules.stateFields(os.meka.core.domain.MailFindState.OPEN, nowMs()))
            }
            refresh()
            return S.askAddedLine(f, nowMs(), cal)
        }
        val sid = S.schoolItemId(f.id)
        replica.commitLocal(os.meka.core.domain.EntityTypes.SCHOOL_ITEM, sid, S.schoolFields(f, nowMs()))
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, f.id, os.meka.core.domain.MailRenewalRules.stateFields(os.meka.core.domain.MailFindState.ADDED, nowMs()))
        mailFindUndo = {
            school.remove(sid)
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, f.id, os.meka.core.domain.MailRenewalRules.stateFields(os.meka.core.domain.MailFindState.OPEN, nowMs()))
        }
        refresh()
        return S.addedLine(f, cal.epochDayOf(nowMs()))
    }

    /** **Not needed** on a renewal in "Renewals from your mail": gone on both apps, never offered again (with Undo). */
    suspend fun notNeededMailFind(id: String): String = onCore {
        val R = os.meka.core.domain.MailRenewalRules
        val f = activity.mailFinds().firstOrNull { it.id == id } ?: return@onCore R.UNKNOWN
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, id, R.stateFields(os.meka.core.domain.MailFindState.DISMISSED, nowMs()))
        mailFindUndo = { replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, id, R.stateFields(os.meka.core.domain.MailFindState.OPEN, nowMs())) }
        mailFindsChanged()
        if (f.kind == os.meka.core.domain.MailRefundRules.KIND_REFUND) os.meka.core.domain.MailRefundRules.NOT_THIS_LINE else R.NOT_NEEDED_LINE
    }

    /** Undo on the bar after Add or Not needed: the find comes back (and an added reminder comes off). */
    // ---- Discord channel watch, slice 1 ----

    private fun discordChanged() {
        val cal = ZoneCalendar(timeZone)
        val watched = discordStore.watched()
        val posts = discordStore.posts()
        _discordWatch.value = os.meka.core.domain.DiscordRules.view(
            discordStore.seen(), watched, nowMs(), cal, discordStore.people(), discordStore.words(), posts,
        )
        _discord.value = os.meka.core.domain.DiscordRules.cards(posts, watched, nowMs(), cal, summaries = chatSummaryStore.all())
    }

    /**
     * The Fold's listener: Discord posts read from Discord's own notifications. Every channel is noted for Work mode →
     * Discord; only posts from channels he follows are kept (synced, so the Mac shows them). Nothing goes to the AI or
     * back to Discord. Returns how many posts were new and (slice 2) the channels whose alert notification the Fold
     * posts or updates now: a fresh post from a watched person or with a watched word.
     */
    suspend fun noteDiscord(posts: List<os.meka.core.domain.DiscordCaptured>): os.meka.core.domain.DiscordNoted = onCore {
        val n = discordStore.note(posts, nowMs())
        discordChanged()
        n
    }

    /**
     * Discord channel watch, slice 2: Work mode → Discord's "Alert me straight away" — add or remove a person ([person])
     * or a word (a ticker, "entry", "stop"); synced, so the Fold's listener uses it. Returns the line for the bar.
     */
    suspend fun setDiscordAlert(text: String, person: Boolean, add: Boolean): String = onCore {
        val line = discordStore.setAlert(text, person, add, nowMs())
        discordChanged()
        line
    }

    /**
     * Discord channel watch, slice 3: the AI's one line on each followed channel's card. The Fold calls this after its
     * listener keeps new posts and when Needs you shows; the channels with three or more open posts and no summary for
     * them (or news and a summary older than ten minutes) go in **one** call to `POST /v1/ai/chat-summary` — each
     * channel's label and its posts' author, time and words as the card shows them — and come back as one line each,
     * checked again ([os.meka.core.domain.DiscordSummaryRules.check]) and synced so the Mac's card shows it. Nothing is
     * posted to Discord, traded or acted on. Nothing due → [ChatSummaryRead.NotDue]; offline, AI off or a failed call
     * writes nothing, so the next call tries again, and the card keeps its posts.
     */
    suspend fun summariseDiscord(settings: os.meka.core.domain.TriageSettings = os.meka.core.domain.TriageSettings()): ChatSummaryRead {
        val cal = ZoneCalendar(timeZone)
        val due = onCore { os.meka.core.domain.DiscordSummaryRules.due(discordStore, chatSummaryStore.all(), settings, nowMs(), cal) }
        if (due.isEmpty()) return ChatSummaryRead.NotDue
        val api = aiApi ?: return ChatSummaryRead.Unavailable(os.meka.core.domain.AskRules.NOT_CONNECTED_LINE)
        val codec = os.meka.core.wire.ChatSummaryCodec
        val request = onCore {
            val now = nowMs()
            val today = cal.epochDayOf(now)
            os.meka.core.wire.ChatSummaryCodec.Request(
                date = os.meka.core.domain.AskRules.isoDate(today),
                now = "${os.meka.core.domain.CivilDate.longLabel(today)} ${os.meka.core.domain.CivilDate.fromEpochDay(today).year} · ${LocalClock.formatMinute(cal.minuteOfDay(now))}",
                chats = due.map { c ->
                    os.meka.core.wire.ChatSummaryCodec.Chat(
                        c.name.take(codec.MAX_NAME), c.isGroup,
                        c.lines.map { os.meka.core.wire.ChatSummaryCodec.Line(it.from.take(os.meka.core.wire.MessageRequestCodec.MAX_SENDER), it.at, it.text.take(codec.MAX_LINE)) },
                    )
                },
            )
        }
        val reply = try { api.chatSummary(request) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return ChatSummaryRead.Unavailable(os.meka.core.domain.AskRules.OFFLINE_LINE)
        }
        if (reply.state != os.meka.core.wire.AskCodec.Response.ANSWERED) {
            return ChatSummaryRead.Unavailable(os.meka.core.domain.AskRules.unavailableLine(reply.state, reply.reason))
        }
        return onCore {
            val checked = os.meka.core.domain.DiscordSummaryRules.check(due, reply.chats.map { os.meka.core.domain.ChatSummaryRules.Raw(it.name, it.summary) })
            due.forEach { c -> chatSummaryStore.save(c, checked[c.chatKey]) }
            discordChanged()
            ChatSummaryRead.Read(checked.filterValues { it != null }.keys.toList())
        }
    }

    /** Work mode → Discord: Follow (or Stop) a channel Discord notified about; synced. Returns the line for the bar. */
    suspend fun setDiscordWatched(key: String, on: Boolean): String = onCore {
        val (line, _) = discordStore.setWatched(key, on, nowMs())
        discordChanged()
        line
    }

    /** Done on a channel's card: its posts leave Needs you on both apps; Undo on the bar brings them back. */
    suspend fun discordDone(key: String): String = onCore {
        val card = _discord.value?.cards?.firstOrNull { it.key == key } ?: return@onCore "Already done"
        discordStore.markDone(card.ids, true, nowMs())
        discordUndo = card.ids
        discordChanged()
        os.meka.core.domain.DiscordRules.doneLine(card.title, card.ids.size)
    }

    /** Undo after Done on a Discord card: those posts are back on the card. */
    suspend fun undoDiscord() = onCore {
        val ids = discordUndo ?: return@onCore
        discordUndo = null
        discordStore.markDone(ids, false, nowMs())
        discordChanged()
    }

    private fun liveTask(id: String?): Boolean {
        val e = id?.let { replica.entity(os.meka.core.domain.EntityTypes.TASK, it) } ?: return false
        return !e.deleted
    }

    /**
     * Discord channel watch, slice 4: "Keep as idea" on a post — a note in Lists → Someday → Trading ideas with the
     * post's words, who said it, the channel and when. Information only: nothing is traded, posted or acted on; the note
     * carries Discord's words as untrusted text (ADR-006). Kept once per post; Undo takes it out again.
     */
    suspend fun keepDiscordIdea(postId: String): String = onCore {
        val p = discordStore.post(postId) ?: return@onCore os.meka.core.domain.DiscordRules.CANT_KEEP
        if (liveTask(p.kept)) return@onCore os.meka.core.domain.DiscordRules.ALREADY_KEPT
        if (p.text.isBlank()) return@onCore os.meka.core.domain.DiscordRules.CANT_KEEP
        val id = lists.addSomeday(
            os.meka.core.domain.DiscordRules.ideaTitle(p.author, p.channel.channel, p.text), SomedayKind.TRADING,
        )
        replica.commitLocal(os.meka.core.domain.EntityTypes.TASK, id, mapOf(
            os.meka.core.domain.ActionableFields.NOTES to os.meka.core.domain.DiscordRules.ideaNotes(p, ZoneCalendar(timeZone)).fv(),
            os.meka.core.domain.ActionableFields.PROVENANCE_SOURCE to "discord".fv(),
            os.meka.core.domain.ActionableFields.PROVENANCE_TRUST to os.meka.core.domain.Trust.UNTRUSTED_EXTERNAL.name.fv(),
        ))
        discordStore.setKept(postId, id)
        discordChanged()
        os.meka.core.domain.DiscordRules.KEPT_LINE
    }

    /** Undo after Keep as idea: the note leaves Trading ideas and the post reads "Keep as idea" again. */
    suspend fun undoKeepDiscordIdea(postId: String) = onCore {
        val p = discordStore.post(postId) ?: return@onCore
        p.kept?.takeIf { liveTask(it) }?.let { tasks.delete(it) }
        discordStore.setKept(postId, null)
        discordChanged()
    }

    suspend fun undoMailFind() = onCore {
        val undo = mailFindUndo ?: return@onCore
        mailFindUndo = null
        undo()
        refresh()
    }

    /** The Waiting for item's chase fields as they are, so an Undo can put them back exactly. */
    private fun chaseState(waitingId: String): Map<String, FieldValue> {
        val s = replica.entity(os.meka.core.domain.EntityTypes.COMMITMENT, waitingId)
        return listOf(
            os.meka.core.domain.CommitmentFields.FOLLOW_UP_AT, os.meka.core.domain.CommitmentFields.LAST_INTERACTION_AT,
            os.meka.core.domain.ActionableFields.LIFECYCLE, os.meka.core.domain.ActionableFields.COMPLETED_AT,
        ).associateWith { f -> s?.get(f) ?: FieldValue.Null }
    }

    private fun chaseRow(id: String): os.meka.core.domain.MailChaseRow? = _mailChases.value?.rows?.firstOrNull { it.id == id }
        ?: os.meka.core.domain.MailChaseRules.card(activity.mailItems(), lists.waitingItems(), aboutYou.profile().shownName, nowMs(), ZoneCalendar(timeZone), activity.mailFinds())
            ?.rows?.firstOrNull { it.id == id }

    /**
     * Follow-up radar, slice 2: a chase row is an email he received (its `mail_item`) or one he sent with no reply (its
     * `mail_find`); the chase's fields go on whichever it is.
     */
    private fun chaseEntity(id: String): String =
        if (activity.mailFinds().any { it.id == id }) os.meka.core.domain.EntityTypes.MAIL_FIND else os.meka.core.domain.EntityTypes.MAIL_ITEM

    private fun chaseMarks(id: String): os.meka.core.domain.ChaseMarks? =
        activity.mailFinds().firstOrNull { it.id == id }?.chase
            ?: activity.mailItems().firstOrNull { it.id == id }?.let(os.meka.core.domain.MailChaseRules::marks)

    /**
     * Inbox autopilot, slice 6b-3: **Send** on a chase in "Time to chase". Nothing is sent here: his words go on the
     * email's card for the server, which sends them from the account the email came to, in its thread, at its next
     * check once [os.meka.core.domain.MailNeedsRules.REPLY_HOLD_MS] have passed; the Waiting for item records the chase
     * (next one in 3 days). Undo on the bar stops it (until the server has sent it) and puts the chase date back.
     * Returns the line to show, or what's wrong with the text with nothing written.
     */
    suspend fun chaseMailItem(id: String, text: String): String = onCore {
        val C = os.meka.core.domain.MailChaseRules
        val row = chaseRow(id) ?: return@onCore C.UNKNOWN
        val entity = chaseEntity(id)
        val (to, account) = activity.mailItems().firstOrNull { it.id == id }?.let { it.from to it.account }
            ?: activity.mailFinds().firstOrNull { it.id == id }?.let { row.from to it.account }
            ?: return@onCore C.UNKNOWN
        os.meka.core.domain.MailNeedsRules.replyProblem(text)?.let { return@onCore it }
        val before = chaseState(row.waitingId)
        replica.commitLocal(entity, id, C.sendFields(text, nowMs()))
        lists.chased(row.waitingId)
        mailChaseUndo = {
            val now = chaseMarks(id)
            // Too late once the server sent it: the chase stands as recorded.
            if (now == null || !C.sent(now)) {
                replica.commitLocal(entity, id, C.undoFields(nowMs()))
                replica.commitLocal(os.meka.core.domain.EntityTypes.COMMITMENT, row.waitingId, before)
            }
        }
        mailNeedsChanged()
        C.sendingLine(to, account)
    }

    /** **Got it** on "Time to chase": the thing arrived, off Waiting for (Undo puts it back). Nothing is sent. */
    suspend fun gotMailChase(id: String): String = onCore {
        val C = os.meka.core.domain.MailChaseRules
        val row = chaseRow(id) ?: return@onCore C.UNKNOWN
        val before = chaseState(row.waitingId)
        replica.commitLocal(chaseEntity(id), id, C.seenFields(nowMs()))
        lists.received(row.waitingId)
        mailChaseUndo = { replica.commitLocal(os.meka.core.domain.EntityTypes.COMMITMENT, row.waitingId, before) }
        mailNeedsChanged()
        C.GOT_LINE
    }

    /** **Not yet** on "Time to chase": chase again in 3 days, nothing sent (Undo puts the date back). */
    suspend fun notYetMailChase(id: String): String = onCore {
        val C = os.meka.core.domain.MailChaseRules
        val row = chaseRow(id) ?: return@onCore C.UNKNOWN
        val before = chaseState(row.waitingId)
        replica.commitLocal(chaseEntity(id), id, C.seenFields(nowMs()))
        lists.setChase(row.waitingId, os.meka.core.domain.ListRules.DEFAULT_CHASE_DAYS)
        mailChaseUndo = { replica.commitLocal(os.meka.core.domain.EntityTypes.COMMITMENT, row.waitingId, before) }
        mailNeedsChanged()
        C.NOT_YET_LINE
    }

    /** Undo on the last Send, Got it or Not yet in "Time to chase" on this device. */
    suspend fun undoMailChase() = onCore {
        val undo = mailChaseUndo ?: return@onCore
        mailChaseUndo = null
        undo()
        mailNeedsChanged()
    }

    /**
     * Inbox autopilot, slice 6a: **Done** on an email in "From your mail": its card goes on both apps and the server
     * archives it at its next check (Gmail: out of the inbox; Outlook: to Archive). Undo brings the card back (and the
     * email back to the inbox if the server already archived it). Returns the line to show.
     */
    suspend fun doneMailItem(id: String): String = onCore {
        activity.mailItems().firstOrNull { it.id == id } ?: return@onCore os.meka.core.domain.MailNeedsRules.UNKNOWN
        // Slice 6c: the emails linked to it go with it, and come back with one Undo.
        val group = mailGroup(id)
        group.forEach { replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, it, os.meka.core.domain.MailNeedsRules.doneFields(nowMs())) }
        mailNeedsUndo = { group.forEach { replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, it, os.meka.core.domain.MailNeedsRules.undoDoneFields(nowMs())) } }
        mailNeedsChanged()
        os.meka.core.domain.MailLinkRules.doneLine(group.size)
    }

    /**
     * Slice 10b: **Always tidy** on an email in "From your mail": it is Done (archived at the server's next check, with
     * the emails linked to it), and the server gives its sender the always-tidy rule ([os.meka.core.domain.MailSenderRule.TIDY]),
     * so Autopilot archives their later emails as they arrive (never a bank, school, public body, his partner or a thread
     * he wrote in). Undo brings the card back and takes the rule off again. Returns the bar's line.
     */
    suspend fun alwaysTidyMailItem(id: String): String = onCore {
        val item = activity.mailItems().firstOrNull { it.id == id } ?: return@onCore os.meka.core.domain.MailNeedsRules.UNKNOWN
        val group = mailGroup(id)
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, os.meka.core.domain.MailSenderRuleRules.cardFields(nowMs()))
        val linked = doneLinked(group)
        mailNeedsUndo = {
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, os.meka.core.domain.MailNeedsRules.undoDoneFields(nowMs()))
            linked()
        }
        mailNeedsChanged()
        os.meka.core.domain.MailSenderRuleRules.cardLine(item.from)
    }

    /**
     * Junk fix slice 2 (Meka, 2026-10-17): **Block sender** (on a possible scam, **Bin & block**) on an email in "From
     * your mail": it is Done, and at the server's next check this email goes to Trash and its sender gets the block
     * rule ([os.meka.core.domain.MailSenderRule.BLOCK]), so their later mail goes straight to Trash too (never people he
     * writes to, his partner, banks, schools or public bodies: those are only archived). The linked emails are Done with
     * it. Undo brings the card (and the email) back and takes the block off again. Returns the bar's line.
     */
    suspend fun blockMailItem(id: String): String = onCore {
        val item = activity.mailItems().firstOrNull { it.id == id } ?: return@onCore os.meka.core.domain.MailNeedsRules.UNKNOWN
        val scam = os.meka.core.domain.MailScamRules.scamLine(item.warning)
        val group = mailGroup(id)
        replica.commitLocal(
            os.meka.core.domain.EntityTypes.MAIL_ITEM, id,
            os.meka.core.domain.MailSenderRuleRules.cardFields(nowMs(), os.meka.core.domain.MailSenderRule.BLOCK),
        )
        val linked = doneLinked(group)
        mailNeedsUndo = {
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, os.meka.core.domain.MailNeedsRules.undoDoneFields(nowMs()))
            linked()
        }
        mailNeedsChanged()
        if (scam) os.meka.core.domain.MailSenderRuleRules.binLine(item.from) else os.meka.core.domain.MailSenderRuleRules.blockLine(item.from)
    }

    /**
     * Junk fix slice 2: **Unsubscribe** on an email in "From your mail" that carries an unsubscribe link: it is Done (with
     * its linked emails) and the server unsubscribes the sender its own way at its next check. Undo before then calls it
     * off and brings the card back. Returns the bar's line.
     */
    suspend fun unsubscribeMailItem(id: String): String = onCore {
        val item = activity.mailItems().firstOrNull { it.id == id } ?: return@onCore os.meka.core.domain.MailNeedsRules.UNKNOWN
        if (!item.canUnsubscribe) return@onCore os.meka.core.domain.UnsubscribeState.FAILED.note
        val group = mailGroup(id)
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, os.meka.core.domain.MailCardUnsubRules.askFields(nowMs()))
        val linked = doneLinked(group)
        mailNeedsUndo = {
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, os.meka.core.domain.MailCardUnsubRules.undoFields(nowMs()))
            linked()
        }
        mailNeedsChanged()
        os.meka.core.domain.MailCardUnsubRules.line(item.from)
    }

    /** Slice 6c: [id] and the emails linked to it on "From your mail" ([id] first). */
    private fun mailGroup(id: String): List<String> = os.meka.core.domain.MailLinkRules.groupIds(activity.mailItems(), id, nowMs())

    /** Slice 6c: the linked emails (all but [id]) are Done with it; Undo brings them back. */
    private fun doneLinked(group: List<String>): () -> Unit {
        val rest = group.drop(1)
        rest.forEach { replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, it, os.meka.core.domain.MailNeedsRules.doneFields(nowMs())) }
        return { rest.forEach { replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, it, os.meka.core.domain.MailNeedsRules.undoDoneFields(nowMs())) } }
    }

    /**
     * Drafted replies: **Not needed** on Navilife's draft of a reply — the draft goes and the email no longer counts as
     * needing a reply on both apps; the email itself stays in "From your mail". Undo brings the draft back.
     */
    suspend fun dismissMailDraft(id: String): String = onCore {
        activity.mailItems().firstOrNull { it.id == id } ?: return@onCore os.meka.core.domain.MailNeedsRules.UNKNOWN
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, os.meka.core.domain.MailAutoDraftRules.dismissFields(nowMs()))
        mailNeedsUndo = { replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, os.meka.core.domain.MailAutoDraftRules.dismissFields(0L)) }
        mailNeedsChanged()
        os.meka.core.domain.MailAutoDraftRules.NOT_NEEDED_LINE
    }

    /** **Snooze** on an email in "From your mail": its card leaves both apps until tomorrow 08:00 (with Undo). */
    suspend fun snoozeMailItem(id: String): String = onCore {
        activity.mailItems().firstOrNull { it.id == id } ?: return@onCore os.meka.core.domain.MailNeedsRules.UNKNOWN
        val group = mailGroup(id)
        val items = activity.mailItems().associateBy { it.id }
        val before = group.associateWith { items[it]?.snoozedUntilMs ?: 0L }
        val until = os.meka.core.domain.MailNeedsRules.snoozeUntil(nowMs(), ZoneCalendar(timeZone))
        group.forEach { replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, it, os.meka.core.domain.MailNeedsRules.snoozeFields(until)) }
        mailNeedsUndo = { before.forEach { (g, b) -> replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, g, os.meka.core.domain.MailNeedsRules.snoozeFields(b)) } }
        mailNeedsChanged()
        os.meka.core.domain.MailLinkRules.snoozedLine(group.size)
    }

    /**
     * Inbox autopilot, slice 6b-1: **Task** on an email in "From your mail": a task titled from the subject (notes say
     * whose email and which account) and the email is Done (archived at the server's next check). One task per email,
     * so a tap on both apps makes one. Undo deletes the task and brings the card back. Returns the line to show.
     */
    suspend fun taskFromMailItem(id: String): String = onCore {
        val R = os.meka.core.domain.MailNeedsRules
        val item = activity.mailItems().firstOrNull { it.id == id } ?: return@onCore R.UNKNOWN
        val group = mailGroup(id)
        val taskId = tasks.createWithId(R.taskId(id), os.meka.core.domain.NewTask(R.taskTitle(item), notes = R.taskNotes(item, ZoneCalendar(timeZone))))
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, R.taskFields(taskId, nowMs()))
        val undoLinked = doneLinked(group)
        mailNeedsUndo = {
            runCatching { tasks.delete(taskId) }
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, R.undoMadeFields(nowMs()))
            undoLinked()
        }
        mailNeedsChanged()
        os.meka.core.domain.MailLinkRules.taskLine(group.size)
    }

    /**
     * Inbox autopilot, slice 6b-1: **Waiting for** on an email in "From your mail": a Waiting for item (the subject,
     * from the sender, chase in 3 days) and the email is Done. One per email. Undo removes it and brings the card back.
     */
    suspend fun waitingFromMailItem(id: String): String = onCore {
        val R = os.meka.core.domain.MailNeedsRules
        val item = activity.mailItems().firstOrNull { it.id == id } ?: return@onCore R.UNKNOWN
        val group = mailGroup(id)
        val waitingId = lists.addWaiting(R.taskTitle(item), item.from, os.meka.core.domain.ListRules.DEFAULT_CHASE_DAYS, withId = R.waitingId(id))
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, R.waitingFields(waitingId, nowMs()))
        val undoLinked = doneLinked(group)
        mailNeedsUndo = {
            runCatching { lists.deleteWaiting(waitingId) }
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, R.undoMadeFields(nowMs()))
            undoLinked()
        }
        mailNeedsChanged()
        os.meka.core.domain.MailLinkRules.waitingLine(group.size)
    }

    /**
     * Inbox autopilot, slice 6b-2a: **Send** on a reply he wrote on an email in "From your mail". Nothing is sent here:
     * the card goes on both apps and the server sends his words from the account the email came to, in its thread, at
     * its next check once [os.meka.core.domain.MailNeedsRules.REPLY_HOLD_MS] have passed, then archives the email. Undo
     * on the bar stops it (until the server has sent it). Returns the line to show, or what's wrong with the text
     * ([os.meka.core.domain.MailNeedsRules.replyProblem]) with nothing written.
     */
    suspend fun replyToMailItem(id: String, text: String): String = onCore {
        val R = os.meka.core.domain.MailNeedsRules
        val item = activity.mailItems().firstOrNull { it.id == id } ?: return@onCore R.UNKNOWN
        R.replyProblem(text)?.let { return@onCore it }
        val group = mailGroup(id)
        replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, R.replyFields(text, nowMs()))
        // Slice 6c: the reply answers the newest; the emails linked to it are archived with it.
        val undoLinked = doneLinked(group)
        mailNeedsUndo = {
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, R.undoDoneFields(nowMs()))
            undoLinked()
        }
        mailNeedsChanged()
        R.replySendingLine(item.account)
    }

    /**
     * Email triage completeness (2026-10-15): **Send all** on "From your mail" — the replies he read in each email's
     * editor ([ids] and [texts] side by side, only Strings cross from the apps), each sent exactly as [replyToMailItem]
     * sends one: nothing is sent here; each goes from the account its email came to, in its thread, at the server's next
     * check once [os.meka.core.domain.MailNeedsRules.REPLY_HOLD_MS] have passed, and its email (with the ones linked to it)
     * is archived. One Undo on the bar stops them all (until the server has sent them). A reply that couldn't be sent
     * ([os.meka.core.domain.MailNeedsRules.replyProblem]) or an email no longer here is left out; with none left, the
     * problem is returned and nothing is written.
     */
    suspend fun replyToMailItems(ids: List<String>, texts: List<String>): String = onCore {
        val R = os.meka.core.domain.MailNeedsRules
        val items = activity.mailItems().associateBy { it.id }
        val ready = ids.zip(texts).filter { (id, text) -> items[id] != null && R.replyProblem(text) == null }
        if (ready.isEmpty()) return@onCore texts.firstNotNullOfOrNull { R.replyProblem(it) } ?: R.UNKNOWN
        val undos = ready.map { (id, text) ->
            val group = mailGroup(id)
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, R.replyFields(text, nowMs()))
            val undoLinked = doneLinked(group)
            val undo: () -> Unit = {
                replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, R.undoDoneFields(nowMs()))
                undoLinked()
            }
            undo
        }
        mailNeedsUndo = { undos.forEach { it() } }
        mailNeedsChanged()
        if (ready.size == 1) R.replySendingLine(items.getValue(ready[0].first).account)
        else os.meka.core.domain.MailSendAllRules.sendingLine(ready.size)
    }

    /**
     * Inbox autopilot, slice 6b-2b: **Draft it** in the reply editor on an email in "From your mail". One call to MEKA's
     * AI (`POST /v1/ai/mail-draft`, metered `mail.draft`) with what the card shows — the account's label, the sender's
     * name, the subject and the preview — and up to three of his own earlier sent replies for his tone
     * ([os.meka.core.domain.MailDraftRules.examples]). Nothing is written or sent: the checked draft is handed back for
     * the editor, and it goes only when he taps Send ([replyToMailItem]). AI off, over budget, offline or an unusable
     * draft: no text and a line saying so.
     */
    suspend fun draftMailReply(id: String): os.meka.core.domain.MailDraft {
        val R = os.meka.core.domain.MailDraftRules
        val codec = os.meka.core.wire.MailDraftCodec
        val cal = ZoneCalendar(timeZone)
        val request = onCore {
            val items = activity.mailItems()
            val item = items.firstOrNull { it.id == id } ?: return@onCore null
            val now = nowMs()
            val today = cal.epochDayOf(now)
            os.meka.core.wire.MailDraftCodec.Request(
                date = os.meka.core.domain.AskRules.isoDate(today),
                now = "${os.meka.core.domain.CivilDate.longLabel(today)} ${os.meka.core.domain.CivilDate.fromEpochDay(today).year} · ${LocalClock.formatMinute(cal.minuteOfDay(now))}"
                    .take(os.meka.core.wire.MessageRequestCodec.MAX_NOW),
                account = item.account.take(codec.MAX_ACCOUNT),
                business = item.account == os.meka.core.domain.MailLabel.BUSINESS.label,
                from = item.from.take(codec.MAX_FROM),
                subject = item.subject.take(codec.MAX_SUBJECT),
                preview = item.preview?.take(codec.MAX_PREVIEW),
                examples = R.examples(items).map { it.take(codec.MAX_EXAMPLE) }.take(codec.MAX_EXAMPLES),
            )
        } ?: return os.meka.core.domain.MailDraft(null, os.meka.core.domain.MailNeedsRules.UNKNOWN)
        val api = aiApi ?: return os.meka.core.domain.MailDraft(null, os.meka.core.domain.AskRules.NOT_CONNECTED_LINE)
        val reply = try { api.mailDraft(request) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return os.meka.core.domain.MailDraft(null, os.meka.core.domain.AskRules.OFFLINE_LINE)
        }
        if (reply.state != os.meka.core.wire.AskCodec.Response.ANSWERED) {
            return os.meka.core.domain.MailDraft(null, os.meka.core.domain.AskRules.unavailableLine(reply.state, reply.reason))
        }
        val text = R.check(reply.draft) ?: return os.meka.core.domain.MailDraft(null, R.UNUSABLE_LINE)
        return os.meka.core.domain.MailDraft(text, R.DRAFTED_LINE)
    }

    /**
     * Scheduling assistant, slice 1: **Suggest times** on an email that asks to meet. Up to three free times from his
     * own calendars ([os.meka.core.domain.MeetingRequestRules.slots]: work days, inside the day's work hours, clear of
     * every event on any calendar and of protected time, a morning and an afternoon in turn), written as a reply for the
     * editor with his name from About you. Worked out on the device; no AI and nothing written, booked or sent: it goes
     * only when he taps Send ([replyToMailItem]). No free time: no text and a line saying so. Slice 3: on their answer
     * that turned his times down, new times away from those days ("No problem. How about…"); with a zone their email
     * names, the times also in their time and inside their working day.
     */
    suspend fun suggestMeetingTimes(id: String): os.meka.core.domain.MailDraft = onCore {
        val R = os.meka.core.domain.MeetingRequestRules
        val items = activity.mailItems()
        val item = items.firstOrNull { it.id == id } ?: return@onCore os.meka.core.domain.MailDraft(null, R.UNKNOWN)
        val cal = ZoneCalendar(timeZone)
        val now = nowMs()
        // Slice 3: times he offered before in this conversation (they couldn't make them) are left out, and a zone
        // their emails name ("I'm in New York") keeps the times inside their working day and writes them in their time.
        val offer = os.meka.core.domain.MeetingPickRules.offerFor(item, items, cal)
        val named = os.meka.core.domain.MeetingZoneRules.named(listOf(item.subject, item.preview, offer?.first?.subject, offer?.first?.preview))
        val theirs = named?.let { n ->
            runCatching { TimeZone.of(n.id) }.getOrNull()?.takeIf { it.id != timeZone().id }
                ?.let { z -> os.meka.core.domain.TheirZone(ZoneCalendar { z }, n.label) }
        }
        val slots = R.slots(
            now, work.hours(localClock(), cal.epochDayOf(now)), currentEvents(), ::protectedSlots, cal,
            R.minutes(item.subject, item.preview), offer?.second.orEmpty(), theirs?.cal,
        )
        val text = R.draft(item.from, slots, cal, R.zoneLabel(timeZone().id), aboutYou.profile().shownName, theirs, again = offer != null)
            ?: return@onCore os.meka.core.domain.MailDraft(null, R.NONE_FREE_LINE)
        os.meka.core.domain.MailDraft(text, R.SUGGESTED_LINE)
    }

    /**
     * Scheduling assistant, slice 2: **Book it** on an email whose words pick one of the times he offered
     * ([os.meka.core.domain.MeetingPickRules.pickFor]). The event ("Meeting with Sam Taylor", the offered time, the email
     * named in the notes) goes to the calendar account he last added to, as a calendar edit that waits five seconds for
     * Undo before it reaches Google/Outlook; the email records it so the row says "Booked · …" on every device. Nothing
     * is sent to them. Undo on the bar takes it back while the edit is still waiting. Refused in words.
     */
    suspend fun bookMeetingPick(id: String): String = onCore {
        val P = os.meka.core.domain.MeetingPickRules
        val items = activity.mailItems()
        val item = items.firstOrNull { it.id == id } ?: return@onCore os.meka.core.domain.MailNeedsRules.UNKNOWN
        val cal = ZoneCalendar(timeZone)
        val offer = P.offerFor(item, items, cal) ?: return@onCore P.NO_PICK
        val pick = P.pickFor(item, items, nowMs(), cal)
            ?: return@onCore if (P.pick(item.preview, offer.second, cal) != null) P.GONE else P.NO_PICK
        if (P.booked(item, pick)) return@onCore P.bookedLine(pick.slot, cal)
        val accounts = _editAccounts.value
        val account = accounts.firstOrNull { it.key == os.meka.core.domain.AddEventRules.lastUsedKey(calendarEdits.all()) }
            ?: accounts.firstOrNull() ?: return@onCore P.NO_ACCOUNT
        when (val r = calendarEdits.add(account.provider, account.email, P.draft(pick, item.account, item.subject))) {
            is os.meka.core.domain.EventEditResult.Refused -> r.reason
            is os.meka.core.domain.EventEditResult.Made -> {
                replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, P.bookedFields(r.id, pick.slot.startMs))
                mailNeedsUndo = {
                    if (calendarEdits.undo(r.id)) replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_ITEM, id, P.unbookedFields())
                }
                mailNeedsChanged()
                P.bookingLine(pick, account.provider, cal)
            }
        }
    }

    /**
     * Follow-up radar, slice 5: **the chase written by the AI** in "Time to chase". One call to MEKA's AI
     * (`POST /v1/ai/mail-draft` with a `chase`, metered `mail.chase`) with what the row knows: who, the email's subject
     * (and, for an email he received, its preview), what he's waiting for, since when, whether he chased before, his
     * name from About you to sign with, and up to three of his own sent replies for tone. Nothing is written or sent:
     * the checked chase goes back to the editor, and only his Send sends it ([chaseMailItem]). AI off, over budget,
     * offline or an unusable chase: no text (the editor keeps the plain chase) and a line saying so.
     */
    suspend fun draftMailChase(id: String): os.meka.core.domain.MailDraft {
        val R = os.meka.core.domain.MailDraftRules
        val D = os.meka.core.domain.MailChaseDraftRules
        val codec = os.meka.core.wire.MailDraftCodec
        val cal = ZoneCalendar(timeZone)
        val request = onCore {
            val row = chaseRow(id) ?: return@onCore null
            val waiting = lists.waitingItems().firstOrNull { it.id == row.waitingId } ?: return@onCore null
            val items = activity.mailItems()
            val now = nowMs()
            val today = cal.epochDayOf(now)
            val name = aboutYou.profile().shownName?.trim()?.takeIf { it.isNotEmpty() }?.take(codec.MAX_SIGN)
            val base = os.meka.core.wire.MailDraftCodec.Request(
                date = os.meka.core.domain.AskRules.isoDate(today),
                now = "${os.meka.core.domain.CivilDate.longLabel(today)} ${os.meka.core.domain.CivilDate.fromEpochDay(today).year} · ${LocalClock.formatMinute(cal.minuteOfDay(now))}"
                    .take(os.meka.core.wire.MessageRequestCodec.MAX_NOW),
                account = "", business = false, from = row.from.take(codec.MAX_FROM), subject = "",
                examples = R.examples(items).map { it.take(codec.MAX_EXAMPLE) }.take(codec.MAX_EXAMPLES),
                what = waiting.title.trim().takeIf { it.isNotEmpty() }?.take(codec.MAX_SUBJECT),
                chasedBefore = waiting.lastChasedMs != null, sign = name,
            )
            val find = activity.mailFinds().firstOrNull { it.id == id }
            val req = if (find != null) {
                base.copy(
                    account = find.account.take(codec.MAX_ACCOUNT), business = row.business,
                    subject = (find.subject ?: "").trim().take(codec.MAX_SUBJECT), chase = codec.CHASE_FOLLOW_UP,
                    since = os.meka.core.domain.CivilDate.shortLabel(find.receivedDay).take(codec.MAX_SINCE),
                )
            } else {
                val item = items.firstOrNull { it.id == id } ?: return@onCore null
                base.copy(
                    account = item.account.take(codec.MAX_ACCOUNT), business = row.business,
                    from = item.from.take(codec.MAX_FROM), subject = item.subject.trim().take(codec.MAX_SUBJECT),
                    preview = item.preview?.trim()?.takeIf { it.isNotEmpty() }?.take(codec.MAX_PREVIEW), chase = codec.CHASE_WAITING,
                    since = os.meka.core.domain.CivilDate.shortLabel(cal.epochDayOf(waiting.sinceMs)).take(codec.MAX_SINCE),
                )
            }
            req.takeIf { it.account.isNotEmpty() && it.from.isNotBlank() }
        } ?: return os.meka.core.domain.MailDraft(null, os.meka.core.domain.MailChaseRules.UNKNOWN)
        val api = aiApi ?: return os.meka.core.domain.MailDraft(null, os.meka.core.domain.AskRules.NOT_CONNECTED_LINE)
        val reply = try { api.mailDraft(request) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return os.meka.core.domain.MailDraft(null, os.meka.core.domain.AskRules.OFFLINE_LINE)
        }
        if (reply.state != os.meka.core.wire.AskCodec.Response.ANSWERED) {
            return os.meka.core.domain.MailDraft(null, os.meka.core.domain.AskRules.unavailableLine(reply.state, reply.reason))
        }
        val text = R.check(reply.draft) ?: return os.meka.core.domain.MailDraft(null, D.UNUSABLE_LINE)
        return os.meka.core.domain.MailDraft(text, D.DRAFTED_LINE)
    }

    // ---- Inbox autopilot, slice 7: the ten-minute inbox ----

    private fun mailMomentSetting(): os.meka.core.domain.MailMomentSetting = os.meka.core.domain.MailMomentRules.read(
        replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailMomentRules.ENTITY_ID)?.fields,
    )

    private fun mailMomentChanged(items: List<os.meka.core.domain.MailItem> = mailShown()) {
        val cal = ZoneCalendar(timeZone)
        val setting = mailMomentSetting()
        val rows = os.meka.core.domain.MailNeedsRules.allRows(items, nowMs(), cal)
        mailWaitingRows = rows.size
        watchDraftNow = os.meka.core.domain.WatchDraftRules.view(items, nowMs())
        _mailMoment.value = if (mailMomentRunning) null else os.meka.core.domain.MailMomentRules.card(setting, rows.size, nowMs(), cal)
        _mailMomentSession.value = if (!mailMomentRunning) null else
            os.meka.core.domain.MailMomentRules.session(rows, mailMomentSkipped, mailMomentStarted, setting, cal.epochDayOf(nowMs()))
    }

    /**
     * Notes today as an "Inbox clear" day once the moment has come and nothing in "From your mail" waits (synced, so the
     * streak is the same on both apps). Runs with the minute tick; writes at most once a day.
     */
    private fun noteInboxClear() {
        val cal = ZoneCalendar(timeZone)
        val items = mailShown()
        val setting = mailMomentSetting()
        val waiting = os.meka.core.domain.MailNeedsRules.showingSorted(items, nowMs()).size
        if (!os.meka.core.domain.MailMomentRules.shouldNoteClear(setting, items.isNotEmpty(), waiting, nowMs(), cal)) return
        replica.commitLocal(
            os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailMomentRules.ENTITY_ID,
            os.meka.core.domain.MailMomentRules.clearFields(setting, cal.epochDayOf(nowMs())),
        )
    }

    /** Slice 7: **Start** on the ten-minute inbox: the emails that need him, one at a time, newest first. */
    suspend fun startMailMoment() = onCore {
        mailMomentRunning = true
        mailMomentSkipped.clear()
        mailMomentStarted = os.meka.core.domain.MailNeedsRules.allRows(mailShown(), nowMs(), ZoneCalendar(timeZone)).size
        mailMomentChanged()
    }

    /** **Skip** in the ten-minute inbox: this email goes to the back of the queue (nothing written). */
    suspend fun skipMailMoment(id: String) = onCore {
        if (!mailMomentRunning) return@onCore
        mailMomentSkipped.remove(id)
        mailMomentSkipped.add(id)
        mailMomentChanged()
    }

    /** **Finish** or Close: the session ends on this device; anything left stays on "From your mail". */
    suspend fun endMailMoment() = onCore {
        mailMomentRunning = false
        mailMomentSkipped.clear()
        mailMomentStarted = 0
        noteInboxClear()
        mailMomentChanged()
    }

    /** **Not today** on the ten-minute inbox's card: it goes on both apps until tomorrow's moment (with Undo). */
    suspend fun notTodayMailMoment(): String = onCore {
        val before = mailMomentSetting().putOffDay ?: 0L
        val today = ZoneCalendar(timeZone).epochDayOf(nowMs())
        replica.commitLocal(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailMomentRules.ENTITY_ID, os.meka.core.domain.MailMomentRules.putOffFields(today))
        mailMomentUndo = {
            replica.commitLocal(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailMomentRules.ENTITY_ID, os.meka.core.domain.MailMomentRules.putOffFields(before))
        }
        mailMomentChanged()
        os.meka.core.domain.MailMomentRules.PUT_OFF_LINE
    }

    /** Undo on the last Not today on this device. */
    suspend fun undoMailMoment() = onCore {
        val undo = mailMomentUndo ?: return@onCore
        mailMomentUndo = null
        undo()
        mailMomentChanged()
    }

    /** When the ten-minute inbox comes (Mail pane). */
    suspend fun mailMomentTime(): os.meka.core.domain.MailMomentTime = onCore { mailMomentSetting().time }

    /** Mail pane: morning, evening or off (synced). Returns what the pane says. */
    suspend fun setMailMomentTime(time: os.meka.core.domain.MailMomentTime): String = onCore {
        if (mailMomentSetting().time != time ||
            replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailMomentRules.ENTITY_ID)?.get(os.meka.core.domain.MailMomentFields.WHEN) == null
        ) {
            replica.commitLocal(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailMomentRules.ENTITY_ID, os.meka.core.domain.MailMomentRules.timeFields(time, nowMs()))
        }
        mailMomentChanged()
        os.meka.core.domain.MailMomentRules.switchedLine(time)
    }

    /** The Mac's way in (only Strings cross): the time's key ("morning", "evening", "off"). */
    suspend fun mailMomentTimeKey(): String = mailMomentTime().key

    /** The Mac's way in: [key] is a [os.meka.core.domain.MailMomentTime] key; an unknown one changes nothing. */
    suspend fun setMailMomentTimeKey(key: String): String {
        val time = os.meka.core.domain.MailMomentTime.of(key) ?: return os.meka.core.domain.MailMomentRules.switchedLine(mailMomentTime())
        return setMailMomentTime(time)
    }

    // ---- Meeting prep, slice 2: how early, or off ----

    private fun meetingPrepLead(): os.meka.core.domain.MeetingPrepLead = os.meka.core.domain.MeetingPrepRules.read(
        replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MeetingPrepRules.ENTITY_ID)?.fields,
    )

    /** Mail pane: how long before a meeting the prep shows (absent = 15 min). */
    suspend fun meetingPrepLeadSetting(): os.meka.core.domain.MeetingPrepLead = onCore { meetingPrepLead() }

    /** Mail pane: Off · 5 · 10 · 15 · 30 min (synced). Today and the heads-up follow at once. Returns what the pane says. */
    suspend fun setMeetingPrepLead(lead: os.meka.core.domain.MeetingPrepLead): String = onCore {
        if (meetingPrepLead() != lead ||
            replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MeetingPrepRules.ENTITY_ID)?.get(os.meka.core.domain.MeetingPrepFields.LEAD_MIN) == null
        ) {
            replica.commitLocal(
                os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MeetingPrepRules.ENTITY_ID,
                os.meka.core.domain.MeetingPrepRules.settingFields(lead, nowMs()),
            )
        }
        refresh()
        os.meka.core.domain.MeetingPrepRules.switchedLine(lead)
    }

    /** The Mac's way in (only Strings cross): the lead's key ("off", "5", "10", "15", "30"). */
    suspend fun meetingPrepLeadKey(): String = meetingPrepLeadSetting().key

    /** The Mac's way in: [key] is a [os.meka.core.domain.MeetingPrepLead] key; an unknown one changes nothing. */
    suspend fun setMeetingPrepLeadKey(key: String): String {
        val lead = os.meka.core.domain.MeetingPrepLead.of(key) ?: return os.meka.core.domain.MeetingPrepRules.switchedLine(meetingPrepLeadSetting())
        return setMeetingPrepLead(lead)
    }

    // ---- Inbox autopilot, slice 3f: warranty reminders from receipts ----

    /** Mail pane: the lowest order price that puts a warranty's end on Renewals (synced; absent = £100). */
    suspend fun mailWarranty(): os.meka.core.domain.MailWarrantyLevel = onCore {
        os.meka.core.domain.MailWarrantyRules.read(
            replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailWarrantyRules.ENTITY_ID)?.fields,
        )
    }

    /** Mail pane: Off · £50 · £100 · £250 · £500 (synced). Returns what the pane says. */
    suspend fun setMailWarranty(level: os.meka.core.domain.MailWarrantyLevel): String = onCore {
        val stored = replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailWarrantyRules.ENTITY_ID)?.fields
        if (stored?.get(os.meka.core.domain.MailWarrantyFields.MIN_PENCE) == null || os.meka.core.domain.MailWarrantyRules.read(stored) != level) {
            replica.commitLocal(
                os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailWarrantyRules.ENTITY_ID,
                os.meka.core.domain.MailWarrantyRules.settingFields(level, nowMs()),
            )
        }
        os.meka.core.domain.MailWarrantyRules.switchedLine(level)
    }

    /** The Mac's way in (only Strings cross): the level's key ("off", "50", "100", "250", "500"). */
    suspend fun mailWarrantyKey(): String = mailWarranty().key

    /** The Mac's way in: [key] is a [os.meka.core.domain.MailWarrantyLevel] key; an unknown one changes nothing. */
    suspend fun setMailWarrantyKey(key: String): String {
        val level = os.meka.core.domain.MailWarrantyLevel.of(key) ?: return os.meka.core.domain.MailWarrantyRules.switchedLine(mailWarranty())
        return setMailWarranty(level)
    }

    /** Undo on the last Done or Snooze in "From your mail" on this device. */
    suspend fun undoMailItem() = onCore {
        val undo = mailNeedsUndo ?: return@onCore
        mailNeedsUndo = null
        undo()
        mailNeedsChanged()
    }

    /** Undo on the offer's last answer on this device. */
    suspend fun undoUnopened() = onCore {
        val undo = unopenedUndo ?: return@onCore
        unopenedUndo = null
        undo()
        activityChanged()
        unopenedChanged()
    }

    /**
     * The Mail pane: the connected mail accounts with their labels and what's left to connect. Null when the server
     * couldn't be reached or this device isn't connected (the pane says so; nothing changed).
     */
    suspend fun mailAccounts(): os.meka.core.domain.MailAccountsView? {
        val list = try { accountsApi?.mailAccounts() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        return list?.let { mailView(it) }
    }

    /** The accounts as the server last listed them, so the switch and Undo answer without asking it again. */
    private var lastMailFacts: List<os.meka.core.domain.MailAccountFacts>? = null

    /**
     * The pane with the household's sign-ins (slice 1b: an account whose Google sign-in is ending says so) and, since
     * slice 3b-2, Autopilot's switch and each account's tidies with Undo (read from the synced data).
     */
    private suspend fun mailView(list: List<os.meka.core.domain.MailAccountFacts>): os.meka.core.domain.MailAccountsView {
        lastMailFacts = list
        return onCore {
            os.meka.core.domain.MailAccountRules.view(
                list, nowMs(), signIns.all(), mailAutopilotSetting(), autonomy.paused(), activity.mailTidyBatches(), ZoneCalendar(timeZone),
                activity.mailSenders(), activity.mailBacklogs(),
            ).let { v ->
                // Drafted replies, slice 2: "3 emails need a reply · 2 drafted", or "No emails waiting on you ✓".
                v.copy(repliesLine = os.meka.core.domain.MailAutoDraftRules.paneLine(activity.mailItems(), nowMs(), v.rows.isNotEmpty()))
            }
        }
    }

    private fun mailAutopilotSetting(): os.meka.core.domain.MailAutopilotSetting = os.meka.core.domain.MailAutopilotRules.read(
        replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailAutopilotRules.ENTITY_ID)?.fields,
    )

    /** Mail finds, slice 3: "Renewals and promises from mail" in What Navilife may do (synced; absent = off). */
    /** "The mic should just add it", slice 1: whether his adds run at once (absent = yes) or wait as a card. */
    private fun directAddSetting(): os.meka.core.domain.DirectAddSetting = os.meka.core.domain.DirectAddRules.read(
        replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.DirectAddRules.ENTITY_ID)?.fields,
    )

    /**
     * ADR-006 addendum 2026-10-17: his own words asking to add are his request, executed under the rule [directAddSetting]
     * keeps (provenance USER, reversible, CREATE_TASK in TASKS); the kill switch holds it at a card like everything else.
     */
    private fun directAddAllowed(p: os.meka.core.domain.AskProposal): Boolean {
        if (directAddSetting().askFirst) return false
        val (type, domain) = AskPolicy.action(p)
        if (type in AskPolicy.NEVER || type.external) return false
        val rule = os.meka.core.domain.DirectAddRules.RULE_ID
        val config = PolicyConfig(
            levels = mapOf((domain to type) to AutonomyLevel.AUTO_UNDER_RULE), autoRules = mapOf(rule to type),
            killSwitch = autonomy.paused(),
        )
        val decision = PolicyEngine(config).decide(
            ActionRequest(type, domain, provenance = Provenance.USER, reversible = true, requestedLevel = AutonomyLevel.AUTO_UNDER_RULE, ruleId = rule),
        )
        return decision is PolicyDecision.Permit
    }

    /**
     * "The mic should just add it", slice 1: [q] (typed in Ask or said to Talk) plainly asks to add a task or shopping
     * ([os.meka.core.domain.DirectAddRules.request]): it is added now, no AI and no card, shown with Undo and kept in
     * Activity; with Ask first (or paused) it is the card he taps. Null for anything else.
     */
    private suspend fun directAdd(q: String): os.meka.core.domain.AskOutcome? {
        val add = os.meka.core.domain.DirectAddRules.request(q) ?: return null
        return onCore {
            val cal = ZoneCalendar(timeZone)
            val today = cal.epochDayOf(nowMs())
            val p = os.meka.core.domain.DirectAddRules.proposal(add, today, cal.minuteOfDay(nowMs())) ?: return@onCore null
            val card = os.meka.core.domain.AskRules.cardOf(p, today)
            if (!directAddAllowed(p)) return@onCore os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.AskAnswer("", listOf(card)))
            val line = os.meka.core.domain.DirectAddRules.doneLine(p, today)
            val id = try {
                activity.actRecorded(
                    line, os.meka.core.domain.DirectAddRules.WHY, os.meka.core.domain.DirectAddRules.SOURCE,
                    AutonomyLevel.AUTO_UNDER_RULE.name, detail = os.meka.core.domain.DirectAddRules.DETAIL,
                ) { performAsk(card) }.second
            } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (id == null) return@onCore os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.AskAnswer("", listOf(card)))
            activityChanged()
            refresh()
            os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.AskAnswer("", emptyList(), did = listOf(os.meka.core.domain.AskAutoDone(p, line, id))))
        }
    }

    /**
     * "What Navilife may do" completeness: [q] (typed in Ask or said to Talk) pauses, resumes or asks about the pause
     * switch ([os.meka.core.domain.AutonomyAskRules.request]); done now through the same synced setting as the page's
     * button, so it holds on every device. Null for anything else.
     */
    private suspend fun autonomyAsk(q: String): os.meka.core.domain.AskOutcome? {
        val want = os.meka.core.domain.AutonomyAskRules.request(q) ?: return null
        return onCore {
            val changed = when (want) {
                os.meka.core.domain.AutonomyAsk.PAUSE -> autonomy.setPaused(true)
                os.meka.core.domain.AutonomyAsk.RESUME -> autonomy.setPaused(false)
                os.meka.core.domain.AutonomyAsk.STATUS -> false
            }
            if (changed) refresh()
            val cal = ZoneCalendar(timeZone)
            val view = os.meka.core.domain.AutonomyRules.view(
                trust.states(), autonomy.setting(), cal, nowMs(), mailAutopilotSetting(), mailFindsAutoOn(), directAddSetting(),
            )
            os.meka.core.domain.AskOutcome.Answered(
                os.meka.core.domain.AskAnswer(
                    os.meka.core.domain.AutonomyAskRules.answer(want, changed, view), emptyList(),
                    help = os.meka.core.domain.AutonomyAskRules.link,
                ),
            )
        }
    }

    /**
     * AI layer completeness: [q] (typed in Ask or said to Talk) ends, resumes or asks about his fast
     * ([os.meka.core.domain.FastAskRules.request]); done now, as End fast and Resume on Today, and answered from the
     * fast on this device, no AI and no card, with Open Goals and habits under it. Null for anything else.
     */
    private suspend fun fastAsk(q: String): os.meka.core.domain.AskOutcome? = onCore {
        val before = fasting.view()
        val want = os.meka.core.domain.FastAskRules.request(q, before.isFasting) ?: return@onCore null
        val now = nowMs()
        val text = when (want) {
            os.meka.core.domain.FastAsk.STATUS -> os.meka.core.domain.FastAskRules.status(before, now)
            os.meka.core.domain.FastAsk.END ->
                if (before.current == null) os.meka.core.domain.FastAskRules.nothingToEnd(before)
                else {
                    fasting.end()
                    refresh()
                    os.meka.core.domain.FastAskRules.ended(fasting.view())
                }
            os.meka.core.domain.FastAsk.RESUME -> {
                val last = before.last
                if (before.current == null && last != null && last.canResume) {
                    fasting.resume(last.id)
                    refresh()
                    os.meka.core.domain.FastAskRules.resumed(fasting.view(), now)
                } else os.meka.core.domain.FastAskRules.cantResume(before, now)
            }
        }
        os.meka.core.domain.AskOutcome.Answered(
            os.meka.core.domain.AskAnswer(text, emptyList(), help = os.meka.core.domain.FastAskRules.link),
        )
    }

    /**
     * The Vault, slice 3: [q] asks about his documents ([os.meka.core.domain.VaultAskRules.request]); answered from
     * the Vault on this device by rules, no AI. Null when it isn't about them, or names a kind the Vault doesn't hold
     * that is the renewals radar's (insurance, a warranty).
     */
    private suspend fun vaultAsk(q: String): os.meka.core.domain.AskOutcome? {
        val r = os.meka.core.domain.VaultAskRules.request(q) ?: return null
        return onCore {
            val cal = ZoneCalendar(timeZone)
            val text = os.meka.core.domain.VaultAskRules.answer(r, documents.items(), cal.epochDayOf(nowMs()), cal, vaultName())
                ?: return@onCore null
            os.meka.core.domain.AskOutcome.Answered(
                os.meka.core.domain.AskAnswer(text, emptyList(), help = os.meka.core.domain.VaultAskRules.link),
            )
        }
    }

    /**
     * Travel mode × the Vault: [q] asks whether the documents last a trip ([os.meka.core.domain.TripDocRules.askAnswer]);
     * answered from Trips and the Vault on this device, no AI, with Open Trips under the answer. Null when it isn't.
     */
    private suspend fun tripDocsAsk(q: String): os.meka.core.domain.AskOutcome? = onCore {
        val text = os.meka.core.domain.TripDocRules.askAnswer(q, trips.items(), documents.items(), ZoneCalendar(timeZone).epochDayOf(nowMs()))
            ?: return@onCore null
        os.meka.core.domain.AskOutcome.Answered(
            os.meka.core.domain.AskAnswer(text, emptyList(), help = os.meka.core.domain.TripDocRules.link),
        )
    }

    /**
     * Deliveries completeness: [q] asks about his parcels ([os.meka.core.domain.ParcelAskRules.answer]); answered from the
     * parcels the server read from shipping emails, on this device, no AI. Null when it isn't about parcels.
     */
    private suspend fun parcelAsk(q: String): os.meka.core.domain.AskOutcome? {
        if (!os.meka.core.domain.ParcelAskRules.isAbout(q)) return null
        return onCore {
            val text = os.meka.core.domain.ParcelAskRules.answer(q, activity.parcels(), ZoneCalendar(timeZone).epochDayOf(nowMs()))
                ?: return@onCore null
            os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.AskAnswer(text, emptyList()))
        }
    }

    /**
     * Reading and learning, slice 2: [q] asks about his reading list ([os.meka.core.domain.ReadingAskRules.request]).
     * A question is answered from the list here, no AI; a change (progress, finished, started, added) is his own direct
     * request, done now with Undo and kept in Activity under the same rule as [directAdd] (Ask first: the card he taps).
     * Null when it names nothing on his list (the AI's, as before).
     */
    private suspend fun readingAsk(q: String): os.meka.core.domain.AskOutcome? {
        val ask = os.meka.core.domain.ReadingAskRules.request(q) ?: return null
        return onCore {
            val cal = ZoneCalendar(timeZone)
            when (val r = os.meka.core.domain.ReadingAskRules.resolve(ask, reading.items(), nowMs(), cal)) {
                null -> null
                is os.meka.core.domain.ReadingAskResult.Answer ->
                    os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.AskAnswer(r.text, emptyList()))
                is os.meka.core.domain.ReadingAskResult.Do -> {
                    val p = r.proposal
                    val today = cal.epochDayOf(nowMs())
                    val card = os.meka.core.domain.AskRules.cardOf(p, today)
                    if (!directAddAllowed(p)) return@onCore os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.AskAnswer("", listOf(card)))
                    val id = try {
                        activity.actRecorded(
                            p.line, os.meka.core.domain.DirectAddRules.WHY, os.meka.core.domain.DirectAddRules.SOURCE,
                            AutonomyLevel.AUTO_UNDER_RULE.name, detail = os.meka.core.domain.DirectAddRules.DETAIL,
                        ) { performAsk(card) }.second
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                    if (id == null) return@onCore os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.AskAnswer("", listOf(card)))
                    activityChanged()
                    refresh()
                    os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.AskAnswer("", emptyList(), did = listOf(os.meka.core.domain.AskAutoDone(p, p.line, id))))
                }
            }
        }
    }

    /**
     * "The mic should just add it", slice 2: what Meka said into the capture sheet's 🎙 goes straight in, no ↑ Add tap
     * and no AI. "Timer 10 min" sets the timer; otherwise a task in his words with its day read from them, or (for
     * "add milk and eggs to the shopping") the shopping, through the same rule as Ask's direct add, so it is in Activity
     * and Undo puts it back ([spokenCaptureUndo]). With **Ask first** on, Navilife paused, or only day words said, the
     * result's `fill` puts the words in the field for his ↑ Add as before. Null when nothing was heard.
     */
    suspend fun spokenCapture(heard: String?): os.meka.core.domain.SpokenCaptured? {
        val rules = os.meka.core.domain.SpokenCaptureRules
        val text = rules.clean(heard) ?: return null
        val cal = ZoneCalendar(timeZone)
        val fill = os.meka.core.domain.SpokenCaptured(line = "", words = text, fill = true)
        os.meka.core.domain.QuickAlarmRules.parse(text, nowMs(), cal)?.let { q ->
            return onCore {
                val id = alarms.setQuick(q) ?: return@onCore fill
                os.meka.core.domain.SpokenCaptured(os.meka.core.domain.QuickAlarmRules.setLine(q, nowMs(), cal), text, alarmId = id)
            }
        }
        return onCore {
            val today = cal.epochDayOf(nowMs())
            val p = rules.proposal(text, today, cal.minuteOfDay(nowMs())) ?: return@onCore fill
            if (!directAddAllowed(p)) return@onCore fill
            val card = os.meka.core.domain.AskRules.cardOf(p, today)
            val line = os.meka.core.domain.DirectAddRules.doneLine(p, today)
            val id = try {
                activity.actRecorded(
                    line, os.meka.core.domain.DirectAddRules.WHY, os.meka.core.domain.DirectAddRules.SOURCE,
                    AutonomyLevel.AUTO_UNDER_RULE.name, detail = os.meka.core.domain.DirectAddRules.SPOKEN_DETAIL,
                ) { performAsk(card) }.second
            } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (id == null) return@onCore fill
            activityChanged()
            refresh()
            os.meka.core.domain.SpokenCaptured(line, text, activityId = id)
        }
    }

    /** Undo (or Edit) after [spokenCapture]: the task or shopping is put back, or the alarm cancelled, on every device. */
    suspend fun spokenCaptureUndo(c: os.meka.core.domain.SpokenCaptured): Boolean {
        c.alarmId?.let { return cancelAlarm(it) }
        val id = c.activityId ?: return false
        undoActivity(id)
        return true
    }

    private fun mailFindsAutoOn(): Boolean = os.meka.core.domain.MailFindAutoRules.read(
        replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailFindAutoRules.ENTITY_ID)?.fields,
    )

    /**
     * ADR-006: may the finds add themselves? Only when the policy engine permits Level 4 under the rule Meka's switch made
     * (the finds come from the server's fixed rules, never a model; reversible; the kill switch holds them at asking).
     */
    private fun mailFindAutoAllowed(): Boolean {
        val rule = os.meka.core.domain.MailFindAutoRules.RULE_ID
        val config = PolicyConfig(
            levels = mapOf((PolicyDomain.TASKS to ActionType.CREATE_TASK) to AutonomyLevel.AUTO_UNDER_RULE),
            autoRules = mapOf(rule to ActionType.CREATE_TASK),
            killSwitch = autonomy.paused(),
        )
        val decision = PolicyEngine(config).decide(
            ActionRequest(
                ActionType.CREATE_TASK, PolicyDomain.TASKS, provenance = Provenance.DETERMINISTIC_RULE, reversible = true,
                requestedLevel = AutonomyLevel.AUTO_UNDER_RULE, ruleId = rule,
            ),
        )
        return decision is PolicyDecision.Permit
    }

    /**
     * Mail finds, slice 3: with "Renewals and promises from mail" on, each new renewal goes onto Lists → Renewals and
     * each promise onto Waiting for exactly as Add puts them there, each one an Activity entry ("Done for you") whose
     * Undo takes it off and brings the card back (never added on its own again: the find keeps its mark). One entry per
     * find, so the Fold and the Mac adding the same find write the same items and the same entry. Nothing is sent.
     */
    private fun autoAddMailFinds() {
        if (replica.isRecording || !mailFindsAutoOn()) return
        val A = os.meka.core.domain.MailFindAutoRules
        val cal = ZoneCalendar(timeZone)
        val now = nowMs()
        val finds = activity.mailFinds()
        val due = A.due(finds, radarFields(), now, cal, lists.waitingItems(), A.addedToday(finds, now, cal))
        if (due.isEmpty() || !mailFindAutoAllowed()) return
        val today = cal.epochDayOf(now)
        var added = 0
        for (f in due) {
            val entry = A.activityId(f.id)
            // The mark first, outside the recorded change, so an Undo never makes it add itself again.
            replica.commitLocal(os.meka.core.domain.EntityTypes.MAIL_FIND, f.id, A.markFields(now))
            if (activity.has(entry)) continue
            runCatching {
                activity.actRecorded(
                    A.summary(f, today), A.WHY, A.SOURCE, AutonomyLevel.AUTO_UNDER_RULE.name, A.detail(f), entry,
                ) {
                    if (f.kind == os.meka.core.domain.MailPromiseRules.KIND_PROMISE) {
                        val P = os.meka.core.domain.MailPromiseRules
                        val wid = lists.addWaiting(f.what ?: "What ${P.title(f)} promised", f.merchant, P.chaseInDays(f, today), withId = P.waitingId(f.id))
                        lists.editWaiting(wid, notes = A.promiseNote(f))
                    } else {
                        replica.commitLocal(
                            os.meka.core.domain.EntityTypes.OBLIGATION, os.meka.core.domain.MailRenewalRules.obligationId(f.id),
                            A.obligationFields(f, now, cal),
                        )
                    }
                    replica.commitLocal(
                        os.meka.core.domain.EntityTypes.MAIL_FIND, f.id,
                        os.meka.core.domain.MailRenewalRules.stateFields(os.meka.core.domain.MailFindState.ADDED, now),
                    )
                }
                added++
            }
        }
        if (added > 0) activityChanged()
    }

    /** Writes Autopilot's switch (synced; the server reads it at its next check). False when it already was. */
    private fun writeMailAutopilot(on: Boolean): Boolean {
        val current = replica.entity(os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailAutopilotRules.ENTITY_ID)
        if (current?.get(os.meka.core.domain.MailAutopilotFields.ON) != null && mailAutopilotSetting().on == on) return false
        replica.commitLocal(
            os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailAutopilotRules.ENTITY_ID,
            os.meka.core.domain.MailAutopilotRules.fields(on, nowMs()),
        )
        return true
    }

    /**
     * Inbox autopilot, slice 3b-2: Mail's switch. Synced (the server tidies only while it is on, after each account's
     * dry-run day); What Navilife may do shows the same. Returns what Mail says ([MailAutopilotPanelRules.switchedLine])
     * and the pane as it is now (null when the accounts were never listed on this device).
     */
    suspend fun setMailAutopilot(on: Boolean): MailSwitched {
        onCore { if (writeMailAutopilot(on)) refresh() }
        return MailSwitched(os.meka.core.domain.MailAutopilotPanelRules.switchedLine(on), lastMailFacts?.let { mailView(it) })
    }

    /**
     * Inbox fixes, slice 5: Mail's "Mark tidied mail as read" switch (synced on the Autopilot entity, absent = on; the
     * server reads it at its next check). Returns what Mail says and the pane as it is now.
     */
    suspend fun setMailMarkRead(on: Boolean): MailSwitched {
        onCore {
            if (mailAutopilotSetting().markRead != on) {
                replica.commitLocal(
                    os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailAutopilotRules.ENTITY_ID,
                    os.meka.core.domain.MailAutopilotRules.markReadFields(on),
                )
                refresh()
            }
        }
        return MailSwitched(os.meka.core.domain.MailAutopilotPanelRules.markReadSwitchedLine(on), lastMailFacts?.let { mailView(it) })
    }

    /**
     * Read the full email, slice 3: Mail's "Mark an email read when I open it" switch (synced on the Autopilot entity,
     * absent = off; the server follows it when it reads an email for the reader). Returns Mail's line and the pane.
     */
    suspend fun setMailReaderMarksRead(on: Boolean): MailSwitched {
        onCore {
            if (mailAutopilotSetting().readerMarksRead != on) {
                replica.commitLocal(
                    os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.MailAutopilotRules.ENTITY_ID,
                    os.meka.core.domain.MailAutopilotRules.readerMarksReadFields(on),
                )
                refresh()
            }
        }
        return MailSwitched(os.meka.core.domain.MailAutopilotPanelRules.readerReadSwitchedLine(on), lastMailFacts?.let { mailView(it) })
    }

    /**
     * Undo on one of Autopilot's tidies (Mail or Activity): asks the server to put the emails back in the inbox at its
     * next check (they are never tidied again). Returns what was said and the pane as it is now.
     */
    suspend fun undoMailTidy(id: String): MailSwitched {
        val line = onCore { activity.undo(id).line.also { activityChanged(); archivedChanged() } }
        return MailSwitched(line, lastMailFacts?.let { mailView(it) })
    }

    /**
     * Inbox autopilot, slice 8b: **Approve** on an account's backlog clean-up in Mail (synced; the server applies the
     * plan from its next check as one tidy whose Undo puts everything back for 30 days). Returns the bar's line and the
     * pane as it is now.
     */
    suspend fun approveMailBacklog(id: String): MailSwitched {
        val line = onCore { activity.approveBacklog(id).also { refresh() } }
        return MailSwitched(line, lastMailFacts?.let { mailView(it) })
    }

    /**
     * Review opens its own list (Meka, 2026-10-17), slice 1: ↑ Review on an Empty Junk card opens this sheet — who the
     * emails going to Trash are from, grouped by sender with a couple of subjects and a scam chip, the rescues first
     * (who and why), a tick per sender and Approve inside. Null when the card has nothing left to approve.
     */
    suspend fun junkReview(cardId: String): os.meka.core.domain.JunkReview? = onCore { activity.junkReview(cardId) }

    /**
     * Slice 1: the tick on a sender in the Empty Junk review ([keep] true: their mail stays in Junk). Synced; returns the
     * bar's line and the sheet as it is now (null line: nothing changed, e.g. the clean-out already started).
     */
    suspend fun keepJunkSender(cardId: String, key: String, keep: Boolean): JunkReviewChange = onCore {
        val line = activity.keepJunkSender(cardId, key, keep)
        if (line != null) refresh()
        JunkReviewChange(line, activity.junkReview(cardId))
    }

    /** Slice 8b: Cancel after Approve (only while nothing has moved). Returns Mail's line and the pane. */
    suspend fun unapproveMailBacklog(id: String): MailSwitched {
        val undone = onCore { activity.unapproveBacklog(id).also { if (it) refresh() } }
        return MailSwitched(
            when {
                os.meka.core.domain.MailMarkReadRules.isCard(id) ->
                    if (undone) os.meka.core.domain.MailMarkReadRules.UNAPPROVED_BAR else os.meka.core.domain.MailMarkReadRules.ALREADY_STARTED
                undone -> os.meka.core.domain.MailBacklogRules.UNAPPROVED_BAR
                else -> os.meka.core.domain.MailBacklogRules.ALREADY_STARTED
            },
            lastMailFacts?.let { mailView(it) },
        )
    }

    /**
     * Inbox autopilot, slice 5a: Unsubscribe on one newsletter or promo sender in Mail. Synced; the server does it at its
     * next check (one-click, else an email to the list's unsubscribe address from that account, else the sender's page
     * handed back to open) and writes the outcome, which Mail and Activity show. Returns what Mail says and the pane.
     */
    suspend fun unsubscribeMailSender(id: String): MailSwitched {
        val line = onCore { activity.askUnsubscribe(id).also { activityChanged() } }
        return MailSwitched(line, lastMailFacts?.let { mailView(it) })
    }

    /**
     * Inbox autopilot, completeness: Meka's rule for one sender in Mail — "KEEP" (Keep in inbox: Autopilot never tidies
     * its emails), "TIDY" (always tidied), or "" (Let Autopilot decide). Synced; the server follows it from its next
     * check. Returns what Mail says and the pane as it is now.
     */
    suspend fun setMailSenderRule(id: String, rule: String): MailSwitched {
        val line = onCore { activity.setSenderRule(id, rule) }
        return MailSwitched(line, lastMailFacts?.let { mailView(it) })
    }

    /**
     * Disconnects a mail account (slice 1b): the server stops reading it and forgets its sign-in (and asks Google to
     * withdraw the permission); nothing in the mailbox changes. The accounts as they are now, or null when the server
     * couldn't be reached (it stays connected; the pane says so). Setup's Mail step follows.
     */
    suspend fun disconnectMail(provider: String, email: String): os.meka.core.domain.MailAccountsView? {
        val list = (try { accountsApi?.disconnectMail(provider, email) } catch (e: CancellationException) { throw e } catch (e: Exception) { null })
            ?: return null
        noteMailForSetup(list)
        return mailView(list)
    }

    /**
     * Delete mail data per account (Inbox autopilot, completeness): disconnects the account as [disconnectMail] does
     * and the server deletes what it published from that mail (its cards, tidies, senders and his rules for them, the
     * clean-up plan, parcels) from both apps; Renewals, Waiting for and tasks stay. Null when the server couldn't be
     * reached (nothing changed).
     */
    suspend fun disconnectMailAndDeleteData(provider: String, email: String): os.meka.core.domain.MailAccountsView? {
        val list = (try { accountsApi?.disconnectMailAndDeleteData(provider, email) } catch (e: CancellationException) { throw e } catch (e: Exception) { null })
            ?: return null
        noteMailForSetup(list)
        return mailView(list)
    }

    /** Puts Setup's Mail step right after Mail changed, without asking the server again. */
    private suspend fun noteMailForSetup(list: List<os.meka.core.domain.MailAccountFacts>?) {
        val step = os.meka.core.domain.MailAccountRules.setupStep(list, nowMs())
        onCore {
            val v = _setup.value
            if (v != null && v.steps.any { it.key == step.key }) _setup.value = os.meka.core.domain.SetupRules.replaceStep(v, step)
        }
    }

    /** Names what a mail account is for (Personal · Business · Outlook); the accounts as they are now, or null offline. */
    suspend fun setMailLabel(provider: String, email: String, label: os.meka.core.domain.MailLabel): os.meka.core.domain.MailAccountsView? {
        val list = try { accountsApi?.setMailLabel(provider, email, label.name) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        list?.let { noteMailForSetup(it) }
        return list?.let { mailView(it) }
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
        val facts = onCore { healthFacts(device, connected, server.server, server.accounts, server.ai, server.mail) }
        val view = os.meka.core.domain.HealthRules.view(facts, ZoneCalendar(timeZone))
        _health.value = view
        return view
    }

    /** Between server checks, Today's open re-reads this device's own facts against what the server last said. */
    private suspend fun refreshHealthLocal(device: os.meka.core.domain.DeviceHealth, last: os.meka.core.domain.HealthView): os.meka.core.domain.HealthView {
        lastHealthServer ?: return last
        val facts = onCore { healthFacts(device, isConnected, lastHealthServer?.server, lastHealthServer?.accounts, lastHealthServer?.ai, lastHealthServer?.mail) }
        val view = os.meka.core.domain.HealthRules.view(facts, ZoneCalendar(timeZone)).copy(checkedAtMs = last.checkedAtMs)
        _health.value = view
        return view
    }

    private class HealthServerFacts(
        val server: os.meka.core.domain.ServerHealth?, val accounts: List<os.meka.core.domain.HealthAccount>?,
        val ai: os.meka.core.domain.AiStatusView?, val atMs: Long,
        /** The mail accounts (Inbox autopilot, slice 1b: Setup's Mail step); null when they couldn't be read. */
        val mail: List<os.meka.core.domain.MailAccountFacts>? = null,
    )
    private var lastHealthServer: HealthServerFacts? = null

    /** The server's three answers (household health, the calendar list, the AI's status), asked together; kept for Health and Setup. */
    private suspend fun fetchHealthServer(): HealthServerFacts {
        var mail: List<os.meka.core.domain.MailAccountFacts>? = null
        val (server, accounts, ai) = kotlinx.coroutines.coroutineScope {
            val s = async { runCatchingNotCancel { healthApi?.householdHealth() } }
            val a = async { runCatchingNotCancel { accountsApi?.accounts() } }
            val i = async { if (aiApi == null) null else aiStatus().takeIf { it != os.meka.core.domain.AskRules.STATUS_UNKNOWN } }
            val m = async { runCatchingNotCancel { accountsApi?.mailAccounts() } }
            mail = m.await()
            Triple(s.await(), a.await(), i.await())
        }
        accounts?.let(::rememberEditing)
        val names = onCore { eventActions.calendarNames() }
        val facts = HealthServerFacts(
            server?.let { os.meka.core.domain.ServerHealth(it.push, it.calls, it.speech, it.atMs, it.macs, it.lastPushAtMs, it.lastPushResult, it.speechGoogle, it.speechGoogleFailedAtMs, it.speechGooglePollyForMs, it.speechGooglePollyNow) },
            accounts?.filter { !os.meka.core.domain.MailProviders.isMail(it.provider) }?.map { os.meka.core.domain.HealthAccount(it.provider, it.email, os.meka.core.domain.CalendarAccountRules.title(it.provider, it.email, names), it.status, it.lastSyncAtMs) },
            ai, nowMs(), mail,
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
        mail: List<os.meka.core.domain.MailAccountFacts>? = null,
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
            callAssistantOn = _workMode.value.callAssistant, spamProtectionOn = _workMode.value.spamProtection, lastVoiceMessageMs = lastVoice, nowMs = nowMs(),
            callCredit = callCredit.current(),
            aboutYou = aboutYou.held(),
            bankSync = moneyBank.sync(),
            trains = lineStatus.snapshot().board,
            mail = mail,
            mailBacklogs = if (mail == null) emptyList() else activity.mailBacklogs(),
            mailTidies = if (mail == null) emptyList() else activity.mailTidyBatches(),
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
    suspend fun refreshSetup(device: os.meka.core.domain.SetupDevice, force: Boolean = true): os.meka.core.domain.SetupView =
        refreshSetup(device, null, force)

    /**
     * The Fold's Setup with its screen saver step (bedside clock, slice 3): [screenSaver] is whether MEKA is Android's
     * screen saver (null wrapper: no step).
     */
    suspend fun refreshSetup(
        device: os.meka.core.domain.SetupDevice, screenSaver: os.meka.core.domain.SetupScreenSaver?, force: Boolean = true,
    ): os.meka.core.domain.SetupView {
        val cached = onCore { lastHealthServer }
        val server = if (!force && cached != null && nowMs() - cached.atMs < os.meka.core.domain.HealthRules.TODAY_REFRESH_MS) cached
        else fetchHealthServer()
        val connected = isConnected
        val facts = onCore {
            os.meka.core.domain.SetupFacts(
                device = device, connected = connected, server = server.server, accounts = server.accounts,
                signIns = signIns.all(), ai = server.ai, callAssistantOn = _workMode.value.callAssistant,
                voiceMessageSeen = lastVoiceMessageMs() != null, voiceChosen = mekaVoice.chosen(),
                homePlace = os.meka.core.domain.WeatherPlaceRules.effective(weatherPlace.wanted(), os.meka.core.domain.WeatherPlaceRules.aboutHome),
                workPlace = os.meka.core.domain.PlacesRules.effectiveWork(workPlace.wanted(), os.meka.core.domain.PlacesRules.aboutWork),
                nowMs = nowMs(),
            )
        }
        // Recovery key (slice 2): asked again with the server's other answers, else the last answer is reused.
        val recovery = if (!connected) os.meka.core.domain.SetupRecovery(null)
        else os.meka.core.domain.SetupRecovery(
            if (!force && onCore { recoveryReadAtMs }?.let { nowMs() - it < os.meka.core.domain.HealthRules.TODAY_REFRESH_MS } == true) onCore { recoveryStatus }
            else readRecoveryStatus(),
        )
        // The commute in onboarding (the commute counts everywhere, slice 8): MEKA's guess until either app sets it.
        val view = os.meka.core.domain.SetupRules.view(facts, recovery, screenSaver, onCore { work.commute() })
            // About you (rule 13, slice 1): its own step first, to do while nothing is filled in.
            .let { v -> os.meka.core.domain.AboutYouRules.withSetupStep(v, onCore { aboutYou.profile() }) }
            // Mail (Inbox autopilot, slice 1b): the three accounts he named, once this device reaches MEKA's server.
            .let { v -> if (!connected) v else os.meka.core.domain.SetupRules.replaceStep(v, os.meka.core.domain.MailAccountRules.setupStep(server.mail, nowMs())) }
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
        val cal = ZoneCalendar(timeZone)
        // Completeness slice 1: on a repeating event, Every event changes the whole series; slice 2: This and
        // following changes this one and the ones after.
        calendarEdits.change(
            event, os.meka.core.domain.EditEventRules.draft(form, event, nowMs(), cal), every = form.every && event.repeats, calendar = cal,
            following = form.following && !form.every && event.repeats,
        )
    }

    /**
     * Delete [event] from its calendar after the five-second Undo. [guestsOk] is Meka's second tap after the server
     * said it cancels the event for its guests (Delete anyway); never set otherwise. [everyEvent]: every event of its
     * repeating series (completeness slice 1; the Edit form's choice, or the refused edit's own on Delete anyway);
     * [fromThisOne]: this event and the ones after it (completeness slice 2, the same way).
     */
    suspend fun deleteEvent(
        event: os.meka.core.domain.CalendarEvent, guestsOk: Boolean, everyEvent: Boolean = false, fromThisOne: Boolean = false,
    ): os.meka.core.domain.EventEditResult =
        onCore { calendarEdits.delete(event, guestsOk, every = everyEvent && event.repeats, following = fromThisOne && !everyEvent && event.repeats) }

    /**
     * Clash chooser (slice 2c-iii), Keep mine: the clashed edit [clashId] is sent again against Google's version, with
     * five seconds' Undo (undoing it asks the clash again). Refused in words.
     */
    suspend fun keepMyVersion(clashId: String): os.meka.core.domain.EventEditResult = onCore { calendarEdits.keepMine(clashId) }

    /** Clash chooser, Keep theirs: nothing is sent and Google's version stays. False when it was already answered. */
    suspend fun keepTheirVersion(clashId: String): Boolean = onCore { calendarEdits.keepTheirs(clashId) }

    // ---- Calendar editing, completeness slice 7: the Mac's drag to move and keyboard shortcuts ----

    /** Whether [event] may be dragged onto another day or nudged from the keyboard ([os.meka.core.domain.EventMoveRules]). */
    fun canMoveEvent(event: os.meka.core.domain.CalendarEvent): Boolean =
        os.meka.core.domain.EventMoveRules.movable(event, nowMs(), ZoneCalendar(timeZone), ::accountEditable)

    /** Why [event] can't be dragged or nudged (the pointer's help), or null when it can. */
    fun moveEventNote(event: os.meka.core.domain.CalendarEvent): String? =
        os.meka.core.domain.EventMoveRules.whyNot(event, nowMs(), ZoneCalendar(timeZone), ::accountEditable)

    /** The drop's hint while [event] is held over [day] ("Move to Fri 23 Oct · 15:00"). */
    fun moveEventHint(event: os.meka.core.domain.CalendarEvent, day: Long): String {
        val cal = ZoneCalendar(timeZone)
        return os.meka.core.domain.EventMoveRules.dropHint(os.meka.core.domain.EditEventRules.start(event, nowMs(), cal), day, cal.epochDayOf(nowMs()))
    }

    /**
     * Dropped on [day]: the event [eventId] (as it is now) moves there keeping its time and length, this one only, with
     * the five-second Undo. Refused in words. Only the id crosses from the app.
     */
    suspend fun moveEventToDay(eventId: String, day: Long): os.meka.core.domain.EventEditResult =
        onCore { moveEvent(eventId) { os.meka.core.domain.EventMoveRules.toDay(it, day) } }

    /** ⌥⌘ and an arrow: [eventId] moved by [days] and [minutes], this one only, with the five-second Undo. Refused in words. */
    suspend fun moveEventBy(eventId: String, days: Int, minutes: Int): os.meka.core.domain.EventEditResult =
        onCore { moveEvent(eventId) { os.meka.core.domain.EventMoveRules.by(it, days, minutes) } }

    /** [eventId] as it shows now (Meka's own edits laid over), so an open detail can follow a move; null once gone. */
    suspend fun currentEvent(eventId: String): os.meka.core.domain.CalendarEvent? = onCore { currentEvents().firstOrNull { it.id == eventId } }

    private fun moveEvent(
        eventId: String, move: (os.meka.core.domain.AddEventForm) -> os.meka.core.domain.AddEventForm,
    ): os.meka.core.domain.EventEditResult {
        val cal = ZoneCalendar(timeZone)
        val event = currentEvents().firstOrNull { it.id == eventId }
            ?: return os.meka.core.domain.EventEditResult.Refused("That event isn't in your calendar any more")
        os.meka.core.domain.EventMoveRules.whyNot(event, nowMs(), cal, ::accountEditable)
            ?.let { return os.meka.core.domain.EventEditResult.Refused(it) }
        val opened = os.meka.core.domain.EditEventRules.start(event, nowMs(), cal)
        val moved = move(opened)
        os.meka.core.domain.EventMoveRules.refusal(moved, opened, nowMs(), cal)?.let { return os.meka.core.domain.EventEditResult.Refused(it) }
        return calendarEdits.change(event, os.meka.core.domain.EditEventRules.draft(moved, event, nowMs(), cal), calendar = cal)
    }

    /** The undo bar's line for a delete ("Deleting “Dentist” from Google"). */
    fun deletingLine(event: os.meka.core.domain.CalendarEvent, everyEvent: Boolean = false, fromThisOne: Boolean = false): String =
        os.meka.core.domain.EditEventRules.deletingLine(event, everyEvent, fromThisOne)

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
        if (_afterWork.value.people.none { p -> p.items.any { it.id == id && it.hasAudio } } &&
            _heldCalls.value.none { it.item.id == id && it.item.hasAudio && !it.wordsGone && !it.recordingDeleted }) return null
        val api = voiceMessageApi ?: return null
        val bytes = try { api.voiceMessageAudio(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        return bytes?.takeIf { it.isNotEmpty() && it.size <= os.meka.core.domain.VoiceRecordingRules.MAX_BYTES }
    }

    /** [voiceMessageAudio] as base64, for the Mac (Swift turns it into `Data`). */
    suspend fun voiceMessageAudioBase64(id: String): String? =
        voiceMessageAudio(id)?.let { kotlin.io.encoding.Base64.encode(it) }

    /**
     * The player's waveform (call notifications slice 7) for a recording the Mac fetched with [voiceMessageAudioBase64]:
     * [os.meka.core.domain.VoiceWaveformRules.BARS] heights in 0..1, empty when it can't be read. Pure, in memory.
     */
    fun voiceWaveformOfBase64(base64: String): List<Float> {
        val bytes = runCatching { kotlin.io.encoding.Base64.decode(base64) }.getOrNull() ?: return emptyList()
        return os.meka.core.domain.VoiceWaveformRules.of(bytes)
    }

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
        if (read.kind == os.meka.core.domain.ReadOut.Kind.BRIEF) briefScriptNow()
        else os.meka.core.domain.ReadOutRules.text(read, _brief.value, _newsPlace.value)

    /** The AI's checked sentences for the brief's facts last asked ([briefScript]): facts → kept (empty: fell short). */
    private var briefAi: Pair<String, List<String>>? = null

    /**
     * "A morning brief worth listening to", slice 2: the brief as MEKA reads it, written by MEKA's AI from the brief's
     * facts ([os.meka.core.domain.BriefAiRules]; `POST /v1/ai/brief-script`, metered `ask.brief`). One call per brief: the
     * answer is kept for as long as the facts are the same. Only the facts are sent; the answer is checked sentence by
     * sentence (anything naming what isn't in them is dropped) and the news, the one-liner and the close are added here.
     * No AI, offline, no answer within [os.meka.core.domain.BriefAiRules.WAIT_MS] or too little surviving the check: the
     * deterministic brief ([os.meka.core.domain.BriefScriptRules.script]). Never throws.
     */
    suspend fun briefScript(name: String? = null): String {
        // One name everywhere: About you's (none: the greeting leaves it out).
        @Suppress("NAME_SHADOWING") val name = name ?: (_aboutYou.value.callName ?: "")
        val script = os.meka.core.domain.BriefScriptRules
        var v = _brief.value
        if (v.dateLabel.isBlank()) return script.script(v, name)
        val rules = os.meka.core.domain.BriefAiRules
        val facts = rules.facts(v, name)
        if (facts.isEmpty()) return script.script(lineSaid(v, null), name)
        val key = facts.joinToString("\n")
        briefAi?.let { (k, kept) ->
            if (k == key) { v = lineSaid(v, null); return rules.script(kept, v) ?: script.script(v, name) }
        }
        val api = aiApi ?: return script.script(lineSaid(v, null), name)
        val atMs = nowMs()
        val cal = ZoneCalendar(timeZone)
        val today = cal.epochDayOf(atMs)
        val request = os.meka.core.wire.BriefScriptCodec.Request(
            date = os.meka.core.domain.AskRules.isoDate(today),
            now = "${os.meka.core.domain.CivilDate.longLabel(today)} ${os.meka.core.domain.CivilDate.fromEpochDay(today).year} · ${LocalClock.formatMinute(cal.minuteOfDay(atMs))}",
            weekend = v.story.weekend,
            facts = facts,
            length = v.story.length.id,
            // Slice 6: the month's one-liners, so the AI's new one is unlike them (none once today has one).
            recent = if (v.story.oneLiner == null) v.story.recentLines.take(os.meka.core.wire.BriefScriptCodec.MAX_RECENT) else emptyList(),
        )
        val reply = (
            try {
                withTimeoutOrNull(rules.WAIT_MS) { api.briefScript(request) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            ) ?: return script.script(lineSaid(v, null), name)
        // Off, over budget or failed: read MEKA's own and ask again next time.
        if (reply.state != os.meka.core.wire.AskCodec.Response.ANSWERED) return script.script(lineSaid(v, null), name)
        val kept = rules.check(reply.script, facts, v.story.length.maxChars)
        briefAi = key to kept
        v = lineSaid(v, os.meka.core.domain.BriefOneLinerRules.check(reply.line, facts, v.story.recentLines))
        return rules.script(kept, v) ?: script.script(v, name)
    }

    /**
     * Slice 6: the brief [v] with today's one-liner fixed: the one already said today (on either device), else [ai] (the
     * AI's, checked) or MEKA's own, remembered for 30 days on every device ([os.meka.core.domain.MorningBrief.rememberLine]).
     */
    private suspend fun lineSaid(v: os.meka.core.domain.MorningBriefView, ai: String?): os.meka.core.domain.MorningBriefView {
        if (v.story.oneLiner != null) return v
        val text = ai ?: os.meka.core.domain.BriefOneLinerRules.pick(v.story, v.story.recentLines)
        val said = try {
            onCore { brief.rememberLine(v.story.epochDay, text) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { text }
        return v.copy(story = v.story.copy(oneLiner = said))
    }

    /**
     * The brief as [briefScript] last had it for today's facts, without asking (Talk's "read my brief" on the Mac's main
     * actor): the AI's when it was kept for these facts, else the deterministic brief. Pure and cheap.
     */
    fun briefScriptNow(name: String? = null): String {
        @Suppress("NAME_SHADOWING") val name = name ?: (_aboutYou.value.callName ?: "")
        val v = _brief.value
        val fallback = os.meka.core.domain.BriefScriptRules.script(v, name)
        val (k, kept) = briefAi ?: return fallback
        val rules = os.meka.core.domain.BriefAiRules
        if (k != rules.facts(v, name).joinToString("\n")) return fallback
        return rules.script(kept, v) ?: fallback
    }

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
     * Ask knows how Navilife itself works, slice 2: "Still stuck? Ask Navilife" under a help answer. The same
     * [question] goes to the AI past the help index, with the matching entries as context; nothing else differs from
     * [askMeka] (AI off or offline come back as Unavailable).
     */
    suspend fun askBeyondHelp(question: String): os.meka.core.domain.AskOutcome =
        ask(question, emptyList(), voice = false, beyondHelp = true)

    /**
     * Talk to MEKA (build plan V1, slice 1): asks [question] like [askMeka], with the conversation so far ([history],
     * oldest first; only the last [os.meka.core.domain.TalkRules.MAX_HISTORY] are sent) so "move it to Friday" means
     * something, and asks for an answer that reads well aloud. The cards are checked exactly as for typed questions;
     * a spoken yes runs them through [doAsk] like a tap ([os.meka.core.domain.TalkFlow]).
     */
    suspend fun talk(question: String, history: List<os.meka.core.domain.TalkTurn>): os.meka.core.domain.AskOutcome =
        ask(question, history.takeLast(os.meka.core.domain.TalkRules.MAX_HISTORY), voice = true, onEarly = {})

    /**
     * The companion, slice 9 (speech that starts within about a second): [talk], with the answer's first sentence
     * handed to [onEarly] as soon as the server has said it, while the rest is still being written; its clip is
     * already in the speech cache, so saying it starts at once ([os.meka.core.domain.TalkFlow.early]). [onEarly] runs
     * at most once, before this returns. The Fold uses this; the Mac reads [talkEarlyPiece] while it waits.
     */
    suspend fun talkStreamed(
        question: String, history: List<os.meka.core.domain.TalkTurn>, onEarly: (String) -> Unit,
    ): os.meka.core.domain.AskOutcome =
        ask(question, history.takeLast(os.meka.core.domain.TalkRules.MAX_HISTORY), voice = true, onEarly = onEarly)

    /**
     * The companion, slice 9: the first sentence of the spoken answer on its way now, once the server has sent it
     * ahead ([talkStreamed]); null before it comes, and from the start of the next spoken question.
     */
    fun talkEarlyPiece(): String? = talkEarly

    @kotlin.concurrent.Volatile
    private var talkEarly: String? = null

    /**
     * Notifications never seen (slice 4): how this device's notifications stand (the Fold's NotifyTraceStore),
     * read when Meka asks about notifications. Null (the Mac) means the device keeps no trace.
     */
    var notificationHealthSource: (() -> os.meka.core.domain.NotificationHealth?)? = null

    /**
     * Ask knows how Navilife itself works: true on the Mac, so help answers give the Mac's paths and Take me there opens
     * the Mac's sheets (the Fold leaves it false).
     */
    var helpOnMac: Boolean = false

    private suspend fun ask(
        question: String, history: List<os.meka.core.domain.TalkTurn>, voice: Boolean, beyondHelp: Boolean = false,
        /** The companion, slice 9: a spoken question's first sentence, sent ahead (null: not a streamed ask). */
        onEarly: ((String) -> Unit)? = null,
    ): os.meka.core.domain.AskOutcome {
        if (onEarly != null) talkEarly = null
        val q = os.meka.core.domain.AskRules.question(question)
            ?: return os.meka.core.domain.AskOutcome.Unavailable("Ask something first")
        // One name everywhere: the answer to "what should I call you?", or "call me Alex", becomes the name card.
        if (!beyondHelp) nameAsk(q)?.let { return it }
        // Reading and learning, slice 2: "what am I reading?" is answered from his list, and "I'm on page 200",
        // "I've finished Dune", "add Dune to my reading list" are done now, before the plain adds below take them.
        // "What Navilife may do" completeness: "pause Navilife", "resume Navilife", "is Navilife paused?" are done and
        // answered here, no AI and no card, with Open What Navilife may do under the answer.
        if (!beyondHelp) autonomyAsk(q)?.let { return it }
        // AI layer completeness: "end my fast", "resume my fast", "how long left on my fast?" are done and answered here.
        if (!beyondHelp) fastAsk(q)?.let { return it }
        if (!beyondHelp) readingAsk(q)?.let { return it }
        // "The mic should just add it", slice 1: "add …", "remind me to …", "put … on the shopping" are done now.
        if (!beyondHelp) directAdd(q)?.let { return it }
        // Money manager (16): "how much did I spend on takeaways?", "can I afford £300 on Saturday?" are answered from
        // his own Money here, behind his fingerprint, and never go to the AI.
        if (!beyondHelp && os.meka.core.domain.MoneyAskRules.isAbout(q)) return onCore { moneyAnswer(q) }
        // The Vault, slice 3: "when does Alex's passport run out?", "do I have a copy of my licence?", "what's in my
        // Vault?" are answered from the Vault here, never sent to the AI, with Open the Vault under the answer.
        // Travel mode × the Vault: "are our passports OK for Barcelona?" is answered from the trip and the Vault here.
        if (!beyondHelp) tripDocsAsk(q)?.let { return it }
        // Deliveries completeness: "any parcels coming?", "where's my Amazon parcel?" answered from the parcels here.
        if (!beyondHelp) parcelAsk(q)?.let { return it }
        if (!beyondHelp) vaultAsk(q)?.let { return it }
        val cal = ZoneCalendar(timeZone)
        // A question about notifications carries the phone's trace; without the AI the device answers from it itself.
        val notifyAsked = os.meka.core.domain.NotifyAskRules.isAbout(q)
        val notifyHealth = if (!notifyAsked) null else try { notificationHealthSource?.invoke() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val notifySettings = _notifySettings.value
        // The commute counts everywhere, slice 5: "when do I need to leave?" / "what time should I get up?" answered on
        // the device from the same numbers when the AI can't be reached (on the train, say).
        val commuteLocal = if (!os.meka.core.domain.CommuteAskRules.isAbout(q)) null else onCore {
            os.meka.core.domain.CommuteAskRules.localAnswer(q, hoursOf(_workMode.value, cal.epochDayOf(nowMs())), cal, nowMs(), currentWake())
        }
        // Inbox autopilot, slice 9a: "anything important in my email?" / "what did the school say?" carry the emails
        // that need him (other people's words: the context turns untrusted); without the AI the device answers itself.
        val mailAsked = os.meka.core.domain.MailAskRules.isAbout(q)
        val mailItems = if (!mailAsked) emptyList() else onCore { activity.mailItems() }
        // The companion, slice 7: a question naming a sender ("did anything come from Amazon?") also carries what
        // Navilife's autopilot tidied from them, read here from the tidies' own lists (the server is asked nothing).
        val mailTidied = if (!mailAsked) emptyList() else onCore { os.meka.core.domain.MailAskRules.tidiedHits(activity.mailTidyBatches(), q, nowMs()) }
        // Ask knows how Navilife itself works: a question plainly about one of Navilife's own features ("flex mode",
        // "how do I set up the bedside clock?") is answered here from the bundled help, with Take me there, and no AI
        // call; one that only mentions a feature takes the matching entries to the AI. Questions the rules above answer
        // from his own data (notifications, email, the commute) are theirs.
        // Barça match day, slice 3: "what's the score?" / "what channel is Barça on?" carry the matches' notes, and are
        // answered from them rather than from the help entry, while his calendar holds a Barça match.
        val barcaMarks = _eventMarks.value
        val barcaEvents = if (!os.meka.core.domain.BarcaAskRules.isAbout(q)) emptyList() else onCore { currentEvents() }
        val barcaAsked = os.meka.core.domain.BarcaBriefRules.shownMatches(barcaEvents, barcaMarks).isNotEmpty()
        val helpMatch = if (notifyAsked || mailAsked || barcaAsked || os.meka.core.domain.CommuteAskRules.isAbout(q)) null else os.meka.core.domain.HelpRules.lookup(q)
        if (helpMatch?.strong == true && !beyondHelp) {
            return os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.HelpRules.answer(helpMatch.entry, helpOnMac))
        }
        val helpLines = if (helpMatch == null) emptyList() else os.meka.core.domain.HelpRules.askLines(q, helpOnMac)
        // Kids' admin, slice 4: "what size shoes is Alex?" / "what do the boys need this week?" carry the Kids pane.
        val kidsNow = _kids.value
        val kidsAsked = !kidsNow.isEmpty && os.meka.core.domain.KidAskRules.isAbout(q, kidsNow.kids.map { it.name })
        fun unavailable(line: String): os.meka.core.domain.AskOutcome {
            val local = (if (notifyAsked) os.meka.core.domain.NotifyAskRules.localAnswer(notifyHealth, notifySettings, nowMs(), cal) else null)
                ?: (if (mailAsked) os.meka.core.domain.MailAskRules.localAnswer(q, mailItems, nowMs(), cal, mailTidied) else null)
                ?: commuteLocal
                ?: (if (kidsAsked) os.meka.core.domain.KidAskRules.localAnswer(q, kidsNow) else null)
                ?: (if (barcaAsked) os.meka.core.domain.BarcaAskRules.localAnswer(q, barcaEvents, barcaMarks, nowMs(), cal) else null)
            return if (local != null) os.meka.core.domain.AskOutcome.Answered(os.meka.core.domain.AskAnswer(local, emptyList()))
            else os.meka.core.domain.AskOutcome.Unavailable(line)
        }
        val api = aiApi ?: return unavailable(os.meka.core.domain.AskRules.NOT_CONNECTED_LINE)
        val context = onCore {
            // Work's lines (Places item 2) are kept: home's hourly lines make room for them.
            val workLines = os.meka.core.domain.PlacesRules.workAskLines(workWeather.forecast(), nowMs(), cal)
            // Where Meka is (Places item 3): only for a question about "here", from a fix taken in the last 30 minutes.
            val hereLines = os.meka.core.domain.HereRules.askLinesFor(q, hereFix, nowMs(), cal)
            // The route's train lines (Places item 4): one line while TfL's status is fresh.
            val routeLines = os.meka.core.domain.RouteRules.askLines(lineStatus.snapshot(), nowMs(), cal, officeToday())
            // Travel mode completeness, slice 3: the trip destination's days, while away there or the day before.
            val tripLines = os.meka.core.domain.TripWeatherRules.askLines(tripWeather.forecast(), trips.items(), cal.epochDayOf(nowMs()), tripClocks)
            // The companion, slice 4: the days the question names, with their events and tasks.
            val askDays = os.meka.core.domain.DayAskRules.range(q, cal.epochDayOf(nowMs()))
            val askDayEvents = askDays?.let { os.meka.core.domain.DayAskRules.events(it, currentEvents(), cal.epochDayOf(nowMs()), cal) }.orEmpty()
            val askDayTasks = askDays?.let { os.meka.core.domain.DayAskRules.tasks(it, tasks.all(), cal.epochDayOf(nowMs()), cal) }.orEmpty()
            os.meka.core.domain.AskRules.context(_today.value, nowMs(), cal,
                os.meka.core.domain.WeatherRules.askLines(weather.forecast(), nowMs(), cal)
                    .take(os.meka.core.domain.WeatherRules.MAX_ASK_LINES - workLines.size - hereLines.size - routeLines.size - tripLines.size) +
                    hereLines + workLines + routeLines + tripLines,
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
                os.meka.core.domain.CarAskRules.askLines(os.meka.core.domain.CarRules.views(car.cars(), renewals.items(), carCheck(), cal.epochDayOf(nowMs()), cal), cal.epochDayOf(nowMs())),
                // Life admin hour, slice 3: the hour and its jobs, so "when's my admin hour?" has an answer.
                lifeAdmin = os.meka.core.domain.LifeAdminRules.askLine(_lifeAdmin.value, nowMs()),
                // Gift planner, slice 2: each birthday and anniversary with its ideas, so "when's Mum's birthday?" has an answer.
                gifts = os.meka.core.domain.GiftAskRules.askLines(gifts.view()),
                // People, slice 3: promises both ways, birthdays and last contact, so "what did I promise Jordan?" has an answer.
                people = os.meka.core.domain.PeopleAskRules.askLines(_people.value),
                // Travel mode, slice 2a: each trip on now or coming with its bookings, so "when do we fly?" has an answer.
                trips = os.meka.core.domain.TripAskRules.askLines(trips.items(), cal.epochDayOf(nowMs()), tripClocks),
                // Notifications never seen, slice 4: only for a question about notifications, so "why didn't I get a notification?" has an answer.
                notifications = if (notifyAsked) os.meka.core.domain.NotifyAskRules.askLines(notifyHealth, notifySettings, nowMs(), cal) else emptyList(),
                // The commute counts everywhere, slice 5: the commute, today, tomorrow and the wake alarm, so "when do I need to leave?" has an answer.
                commute = os.meka.core.domain.CommuteAskRules.askLines(hoursOf(_workMode.value, cal.epochDayOf(nowMs())), cal, nowMs(), currentWake()),
                // Inbox autopilot, slice 9a: only for a question about his email, so "what did the school say?" has an answer.
                mail = if (mailAsked) os.meka.core.domain.MailAskRules.askLines(mailItems, q, nowMs(), cal) +
                    os.meka.core.domain.MailAskRules.tidiedLines(mailTidied, nowMs(), cal) else emptyList(),
                // Ask knows how Navilife itself works: the help entries the question mentions.
                help = helpLines,
                // Kids' admin, slice 4: only for a question about the children, so "what size shoes is Alex?" has an answer.
                kids = if (kidsAsked) os.meka.core.domain.KidAskRules.askLines(kidsNow) else emptyList(),
                kidsFromOthers = kidsAsked && os.meka.core.domain.KidAskRules.fromOthers(kidsNow),
                // Barça match day, slice 3: only for a question about Barça, so "what's the score?" has an answer.
                barca = if (barcaAsked) os.meka.core.domain.BarcaAskRules.askLines(barcaEvents, barcaMarks, nowMs(), cal) else emptyList(),
                // Edit your calendars, completeness slice 4: events Navilife may move get a handle, and "move the dentist
                // to 4pm" carries the coming events it names, so the answer can offer a Move card.
                movable = { e -> os.meka.core.domain.EventAskRules.movable(e, nowMs(), cal, ::accountEditable) },
                ahead = os.meka.core.domain.EventAskRules.ahead(q, currentEvents(), nowMs(), cal, ::accountEditable),
                // The companion, slice 4: the days the question names ("what's on Thursday?", "am I free next weekend?"),
                // looked up here in his calendar and tasks, so the answer comes from his own data without the server reading it.
                // The companion, slice 8: trains on another day ("train or drive to the match on Saturday?") carry
                // whether each is an office day and TfL's planned status for that morning when it's been read.
                days = askDays, dayLines = askDays?.let { r -> os.meka.core.domain.DayAskRules.askLines(r, askDayEvents, askDayTasks, cal.epochDayOf(nowMs()), cal) +
                    (if (os.meka.core.domain.TrainDayAskRules.isAbout(q)) os.meka.core.domain.TrainDayAskRules.askLines(r, lineStatus.snapshot(), hoursOf(_workMode.value, cal.epochDayOf(nowMs())), nowMs(), cal) else emptyList()) }.orEmpty(),
                dayEvents = askDayEvents, dayTasks = askDayTasks)
                // The companion, slice 3: "sir" when he chose it in Settings → Voice.
                // The companion, slice 5: the notes of earlier chats, only with an open question or one about an earlier chat.
                // One name everywhere, slice 2: the companion calls him what Today and the orb do (About you → Your name).
                .copy(
                    callSir = mekaVoice.callsSir(), recall = companionRecall(q, history, cal),
                    callName = os.meka.core.domain.CallNameRules.called(aboutYou.profile().shownName),
                    // The companion is called Ada, slice 1: she may say "Ada here" in the day's first conversation.
                    introduce = adaIntroduces(history, cal),
                )
        }
        // Voice completeness, slice 3: a spoken question whose answer Navilife's voice will say asks for the first piece
        // with the answer (only Navilife's words are said; nothing of his question is spoken or kept).
        val speakVoice = if (voice && speechApi != null) onCore { speechVoiceNow() } else null
        // The orb speed pass, slice 2: a spoken question's trace marks when it left and when the answer began.
        if (voice) onCore { openTurn()?.sent(nowMs()) }
        val reply = try {
            // The companion, slice 9: the first sentence comes ahead, said, while the model writes the rest.
            if (speakVoice != null && onEarly != null) api.askStreamed(q, context, history.map(::sendable), speakVoice.name) { text, audio, _ ->
                if (text.isNotBlank() && audio.isNotEmpty() && talkEarly == null) {
                    onCore {
                        openTurn()?.firstPiece(nowMs())
                        speechWithAnswer(speakVoice.name, text, audio)
                    }
                    talkEarly = text
                    onEarly(text)
                }
            }
            else if (speakVoice != null) api.askSpoken(q, context, history.map(::sendable), speakVoice.name)
            else api.ask(q, context, history.map(::sendable), voice)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return unavailable(os.meka.core.domain.AskRules.OFFLINE_LINE)
        }
        if (voice) onCore { openTurn()?.firstPiece(nowMs()) }
        if (speakVoice != null && reply is AskReply.Answered) {
            val text = reply.firstText
            val audio = reply.firstAudio
            if (!text.isNullOrBlank() && !audio.isNullOrEmpty()) onCore { speechWithAnswer(speakVoice.name, text, audio) }
        }
        return when (reply) {
            is AskReply.Unavailable -> unavailable(os.meka.core.domain.AskRules.unavailableLine(reply.state, reply.reason))
            is AskReply.Answered -> onCore {
                val titles = context.taskIds.values.mapNotNull { tasks.get(it) }.associate { it.id to it.title }
                val answer = os.meka.core.domain.AskRules.answer(reply.text, reply.actions, context, titles, nowMs(), cal, mealPlan.meals(), dateNight.setting(), renewals.items(), wins.all(), trainQueue.items(),
                    os.meka.core.domain.LeaveAskData(leave.items(), leaveWork(), leave.settings()), lifeFacts.all(), errands.items(), tasks.all(), goals.goals(tasks.all()).first, _lifeAdmin.value, gifts.items(),
                    _people.value, lists.promisedItems(), trips.items())
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
                // The companion, slice 5: an open chat's note is kept in his own data (never from a question that carried
                // other people's words, nor from an answer that read the web: the server sends none then).
                if (!context.untrusted && reply.sources.isEmpty()) companionMemory.keep(reply.note, cal.epochDayOf(nowMs()))
                // The companion, slice 2: the web results the answer drew on, as source cards (https only, no site twice).
                os.meka.core.domain.AskOutcome.Answered(answer.copy(cards = cards, did = did, sources = os.meka.core.domain.LiveFactsRules.sources(reply.sources)))
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
            AutonomyLevel.AUTO_UNDER_RULE.name, detail = "Asked in Navilife",
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
            is os.meka.core.domain.AskProposal.MoveEvent -> {
                // Edit your calendars, completeness slice 4: the event as it is now (it may have moved or gone since the
                // card was made), moved like Save on its Edit sheet would (this one only), with the five-second Undo.
                val event = currentEvents().firstOrNull { it.id == p.eventId }
                    ?: throw os.meka.core.domain.ValidationException("That event isn't in your calendar any more")
                val form = os.meka.core.domain.EventAskRules.moved(os.meka.core.domain.EditEventRules.start(event, nowMs(), cal), p)
                when (val r = calendarEdits.change(event, os.meka.core.domain.EditEventRules.draft(form, event, nowMs(), cal), calendar = cal)) {
                    is os.meka.core.domain.EventEditResult.Made -> os.meka.core.domain.AskUndo.TakeBackEventEdit(r.id)
                    is os.meka.core.domain.EventEditResult.Refused -> throw os.meka.core.domain.ValidationException(r.reason)
                }
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
            is os.meka.core.domain.AskProposal.SkipLifeAdmin -> {
                // Checked again now: only this week's hour, while it isn't skipped yet (here or on the other device).
                if (!lifeAdmin.skipThisWeek(true, p.weekStart)) throw os.meka.core.domain.ValidationException("That life admin hour can't be skipped any more")
                os.meka.core.domain.AskUndo.KeepLifeAdmin(p.weekStart)
            }
            // Checked again now: the occasion still there, the idea not noted meanwhile (here or on the other device).
            is os.meka.core.domain.AskProposal.AddGiftIdea -> {
                if (gifts.items().none { it.id == p.occasionId }) throw os.meka.core.domain.ValidationException(os.meka.core.domain.GiftAskRules.GONE)
                val id = gifts.addIdeaFromAsk(p.occasionId, p.idea) ?: throw os.meka.core.domain.ValidationException(os.meka.core.domain.GiftAskRules.ALREADY)
                os.meka.core.domain.AskUndo.RemoveGiftIdea(id)
            }
            // A promise to Meka's own Lists (nothing is sent); the same one added meanwhile (here or on the other device) is refused.
            is os.meka.core.domain.AskProposal.AddPromise -> {
                val whoKey = p.to?.let(os.meka.core.domain.People::key)
                if (lists.promisedItems().any { it.title.equals(p.title, ignoreCase = true) && it.to?.let(os.meka.core.domain.People::key) == whoKey }) {
                    throw os.meka.core.domain.ValidationException("That promise is already in Lists")
                }
                val days = p.byDay?.let { (it - todayEpochDay()).coerceAtLeast(0L).toInt() }
                val id = lists.addPromise(p.title, p.to, days)
                os.meka.core.domain.AskUndo.RemovePromise(id)
            }
            // Checked again now: the trip still there, the same booking not added meanwhile (here or on the other device).
            is os.meka.core.domain.AskProposal.AddBooking -> {
                val trip = trips.items().firstOrNull { it.id == p.entry.tripId } ?: throw os.meka.core.domain.ValidationException(os.meka.core.domain.TripAskRules.GONE)
                if (os.meka.core.domain.TripAskRules.already(trip, p.entry)) throw os.meka.core.domain.ValidationException(os.meka.core.domain.TripAskRules.ALREADY)
                os.meka.core.domain.AskUndo.RemoveBooking(trips.addBooking(p.entry))
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
            // Reading and learning, slice 2: checked again now by Reading itself (gone meanwhile, or past the end, is refused).
            is os.meka.core.domain.AskProposal.ReadingUpdate -> {
                val id = p.itemId
                val said = when (p.change) {
                    os.meka.core.domain.ReadingChange.ADD -> reading.add(p.text)
                    os.meka.core.domain.ReadingChange.START -> if (id == null) null else reading.start(id)
                    os.meka.core.domain.ReadingChange.PROGRESS -> if (id == null) null else reading.progress(id, p.text)
                    os.meka.core.domain.ReadingChange.FINISH -> if (id == null) null else reading.finish(id)
                }
                val token = said?.takeIf { !it.refused }?.undo
                    ?: throw os.meka.core.domain.ValidationException(said?.line ?: os.meka.core.domain.ReadingRules.GONE)
                os.meka.core.domain.AskUndo.PutBackReading(token)
            }
            // One name everywhere: About you's own save (its checks), so everything calls him by it from now on.
            is os.meka.core.domain.AskProposal.SetName -> {
                val before = aboutYou.profile().shownName
                val saved = aboutYou.set(os.meka.core.domain.AboutField.SHOWN_NAME, p.name)
                if (saved.refused) throw os.meka.core.domain.ValidationException(saved.line)
                refreshAboutYouStep()
                os.meka.core.domain.AskUndo.PutBackName(before, p.name)
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
            // The move-event card's edit is taken back while it still waits (false once it may be on its way).
            is os.meka.core.domain.AskUndo.TakeBackEventEdit -> calendarEdits.undo(undo.editId)
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
            // Kept again only in the same week while it is still skipped.
            is os.meka.core.domain.AskUndo.KeepLifeAdmin -> lifeAdmin.skipThisWeek(false, undo.weekStart)
            // The idea the card noted leaves Gifts (false when it was already removed, here or on the other device).
            is os.meka.core.domain.AskUndo.RemoveGiftIdea -> gifts.removeIdea(undo.ideaId)
            // The promise the card added leaves Lists while it is still open (kept or deleted since, it stays as it is).
            is os.meka.core.domain.AskUndo.RemovePromise ->
                if (lists.promisedItems().any { it.id == undo.promiseId }) { lists.deletePromise(undo.promiseId); true } else false
            // The booking the card added leaves the trip (false when it was already removed, here or on the other device).
            is os.meka.core.domain.AskUndo.RemoveBooking -> trips.removeBooking(undo.bookingId)
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
            // Reading's own Undo: only while it is still the list's last change.
            is os.meka.core.domain.AskUndo.PutBackReading -> reading.undo(undo.token)
            // Your name goes back only while it still reads what the card saved.
            is os.meka.core.domain.AskUndo.PutBackName ->
                if (aboutYou.profile().shownName == undo.after) {
                    !aboutYou.set(os.meka.core.domain.AboutField.SHOWN_NAME, undo.before ?: "").refused
                } else false
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
     * The companion is called Ada, slice 1: whether this question may let her introduce herself (the first conversation
     * of the day on this device, [os.meka.core.domain.AdaRules.introduces]); marks the day when it does. Caller is on the core.
     */
    private fun adaIntroduces(history: List<os.meka.core.domain.TalkTurn>, cal: ZoneCalendar): Boolean {
        val today = cal.epochDayOf(nowMs())
        val last = os.meka.core.domain.AdaRules.dayOf(
            runCatching { localStore.transaction { localStore.localValue(os.meka.core.domain.AdaRules.INTRO_KEY) } }.getOrNull(),
        )
        if (!os.meka.core.domain.AdaRules.introduces(last, today, history.size)) return false
        runCatching { localStore.transaction { localStore.setLocalValue(os.meka.core.domain.AdaRules.INTRO_KEY, today.toString()) } }
        return true
    }

    /** The companion, slice 5: the notes sent with [q], or none. Caller is on the core. */
    private fun companionRecall(q: String, history: List<os.meka.core.domain.TalkTurn>, cal: ZoneCalendar): List<String> {
        val today = cal.epochDayOf(nowMs())
        val notes = companionMemory.notes(today)
        return if (!os.meka.core.domain.CompanionMemoryRules.sends(q, history.map { it.question }, companionMemory.remembers(), notes)) emptyList()
        else os.meka.core.domain.CompanionMemoryRules.recall(notes, today)
    }

    /** The companion, slice 5: whether Navilife remembers conversations (Settings → Navilife's voice; on by default). Synced. */
    suspend fun companionRemembers(): Boolean = onCore { companionMemory.remembers() }

    /** The companion, slice 5: turns remembering on or off on every device; off forgets the notes too. */
    suspend fun setCompanionRemembers(on: Boolean) { onCore { companionMemory.setRemembers(on) } }

    /** The companion, slice 5: what Navilife remembers, newest first ("Yesterday · Talked through …"). */
    suspend fun companionMemoryRows(): List<String> = onCore {
        val today = ZoneCalendar(timeZone).epochDayOf(nowMs())
        os.meka.core.domain.CompanionMemoryRules.rows(companionMemory.notes(today), today)
    }

    /** The companion, slice 5: forgets every note on every device. */
    suspend fun forgetCompanionMemory() { onCore { companionMemory.forgetAll() } }

    /** The companion, slice 3: whether Navilife calls him "sir" (Settings → Voice; false: by name). Synced. */
    suspend fun companionCallsSir(): Boolean = onCore { mekaVoice.callsSir() }

    /** The companion, slice 3: chooses "sir" or by name on every device. */
    suspend fun setCompanionCallsSir(sir: Boolean) { onCore { mekaVoice.callSir(sir) } }

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
        val started = nowMs()
        val clip = fetchSpeechClip(text, first, reading, hold)
        // The orb speed pass, slice 2: the open turn's voice starts with its first clip (or the device's own voice).
        onCore { openTurn()?.clip(nowMs(), nowMs() - started, audio = clip != null) }
        return clip
    }

    private suspend fun fetchSpeechClip(text: String, first: Boolean, reading: Boolean, hold: Boolean): String? {
        if (speechApi == null) return null
        val words = text.trim().takeIf { it.isNotEmpty() && it.length <= os.meka.core.domain.SpeechRules.MAX_PIECE } ?: return null
        val voice = onCore { speechVoiceNow() } ?: return null
        onCore {
            speechCached(voice.name, words)?.also {
                // A first piece that came with the answer: counted once, as such, in Activity's timing line.
                if (speechAnswerClips.remove(os.meka.core.domain.SpeechRules.cacheKey(voice.name, words))) {
                    speechTimed(nowMs(), 0L, os.meka.core.domain.SpeechTiming.Outcome.WITH_ANSWER)
                    speechUnreachable = false
                    speechOfflineSaid = false
                }
            }
        }?.let { return it }
        val wait = os.meka.core.domain.SpeechRules.budgetMs(first, reading, hold)
        // The orb speed pass, slice 3: asked for ahead and still on its way: wait for that request, not a second one.
        onCore { speechInFlight[os.meka.core.domain.SpeechRules.cacheKey(voice.name, words)] }?.let { ahead ->
            return withTimeoutOrNull(wait) { ahead.await() }
        }
        return requestSpeechClip(words, voice, wait)
    }

    /** Asks the server to say [words] in [voice] within [wait]; a clip that comes back is kept in memory. Never throws. */
    private suspend fun requestSpeechClip(words: String, voice: SpeechVoice, wait: Long): String? {
        val api = speechApi ?: return null
        val started = nowMs()
        // Set here and kept on the core below: no core hop inside the timeout (a test's virtual clock would fire it).
        var unreachable = false
        val r = withTimeoutOrNull(wait) {
            try { api.speak(words, voice.name) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                unreachable = true
                os.meka.core.wire.SpeechCodec.Response(os.meka.core.wire.SpeechCodec.Response.FAILED)
            }
        }
        if (unreachable) onCore { speechUnreachable = true }
        if (r == null) { // slow this time: the device speaks, and the next line asks again
            onCore { speechTimed(started, wait, os.meka.core.domain.SpeechTiming.Outcome.LATE) }
            return null
        }
        return onCore {
            val audio = r.audio
            if (r.state == os.meka.core.wire.SpeechCodec.Response.SPOKEN && !audio.isNullOrEmpty()) {
                speechTimed(started, nowMs() - started, os.meka.core.domain.SpeechTiming.Outcome.SPOKEN)
                speechUnreachable = false
                speechOfflineSaid = false
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

    // ---- Family (family sharing with his wife, slice 4) ----

    /**
     * Ask → More → Family: who can use the shopping list ("Jordan can see and add to the shopping list") and each
     * link's state. Null until [refreshFamily] has run once.
     */
    val familyView: StateFlow<os.meka.core.domain.FamilyView?> = _family.asStateFlow()

    /**
     * Reads the links from MEKA's server and logs, once across both devices, the day each was opened ("Jordan joined
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
     * Makes a link for [name] ("Jordan"): the whole address and what the share sheet sends with it. Null when it
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
        os.meka.core.domain.FamilyRules.view(
            familyMembers, nowMs(), ZoneCalendar(timeZone), problem, aboutYou.profile().partnerName,
        ).also { _family.value = it }

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

    /**
     * Clean-ups (2026-10-19): Allow ([allow] true) or Not this one on a new phone or Mac asking to join (Needs you's
     * card, id the ask's request id). The server enrols it or refuses it and writes the ask's state, which syncs the card
     * away on every device; noted in Activity. Returns the undo bar's line (nothing to undo: an allowed device is revoked
     * from the server instead).
     */
    suspend fun answerDeviceJoin(requestId: String, allow: Boolean): String {
        val api = deviceLinkApi ?: return os.meka.core.domain.DeviceJoinRules.OFFLINE_BAR
        val state = try {
            api.answerDeviceJoin(requestId, allow)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkRefusedException) {
            os.meka.core.wire.DeviceJoinCodec.GONE
        } catch (e: Exception) {
            null
        }
        if (state != null) onCore {
            val join = deviceJoins.all().firstOrNull { it.id == requestId }
            if (join != null && state != os.meka.core.wire.DeviceJoinCodec.GONE) {
                activity.recordDevice(
                    os.meka.core.domain.DeviceJoinRules.activityId(requestId), nowMs(),
                    os.meka.core.domain.DeviceJoinRules.activitySummary(join.name, state == os.meka.core.wire.DeviceJoinCodec.ALLOWED),
                    os.meka.core.domain.DeviceJoinRules.WHY_YOU,
                )
            }
            refresh()
        }
        return os.meka.core.domain.DeviceJoinRules.answeredLine(state)
    }

    // ---- Galaxy Watch, completeness slice 1: changes waiting and unlinking from the watch itself ----

    /** Field changes made on this device that wait to be sent, as the last sync round left them (0 when synced). */
    fun watchPending(): Int = when (val s = _sync.value) {
        is SyncStatus.Offline -> s.pending
        is SyncStatus.Failing -> s.pending
        else -> 0
    }

    /** The watch's day-screen foot: "Offline · your changes here wait to sync"; null when there's nothing to say. */
    fun watchSyncLine(): String? {
        val s = _sync.value
        return os.meka.core.domain.WatchSyncRules.line(s is SyncStatus.Offline, s is SyncStatus.Failing, watchPending() > 0)
    }

    /** The confirm row for "Unlink this watch". */
    fun watchUnlinkAsk(): os.meka.core.domain.WatchUnlinkAsk =
        os.meka.core.domain.WatchUnlinkRules.ask(_sync.value is SyncStatus.Offline, watchPending() > 0)

    /**
     * Unlinks this watch from the watch itself: one last sync sends what's waiting (best effort), then Navilife's
     * server revokes this device's key at once. True when it's unlinked (or the server had already let it go); the
     * watch then forgets its day and key. False when it couldn't reach the server: nothing changes.
     */
    suspend fun unlinkThisWatch(): Boolean {
        val api = deviceLinkApi ?: return false
        runCatching { syncNow() }.onFailure { if (it is CancellationException) throw it }
        return try {
            api.unlinkWatch(replica.deviceId); true
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkRefusedException) {
            true // the server didn't know it any more: already unlinked
        } catch (e: AuthRejectedException) {
            true // already signed out
        } catch (e: Exception) {
            false
        }
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

    // ---- The Vault key (V2, Vault slice 2a; ADR-005 addendum 2026-10-18) ----


    /** Settings → Vault's VAULT KEY block: the key's id and where it is, devices asking for it, or what this one waits for. */
    val vaultKeyView: StateFlow<os.meka.core.domain.VaultKeyView> = _vaultKey.asStateFlow()

    private fun vaultName(): String? = when (aboutYouDevice) {
        os.meka.core.domain.AboutYouDevice.FOLD -> "Fold"
        os.meka.core.domain.AboutYouDevice.MAC -> "Mac"
        null -> null
    }

    private fun vaultLocal(key: String): String? = runCatching { localStore.transaction { localStore.localValue(key) } }.getOrNull()

    private fun setVaultLocal(key: String, value: String?) {
        runCatching { localStore.transaction { localStore.setLocalValue(key, value) } }
    }

    /** The Vault key this device holds: its id and the key's bytes. Caller is on the core. */
    private fun heldVaultKey(): Pair<String, ByteArray>? {
        val (id, b64) = os.meka.core.domain.VaultKeyRules.readLocal(vaultLocal(os.meka.core.domain.VaultKeyRules.LOCAL_KEY)) ?: return null
        val bytes = runCatching { kotlin.io.encoding.Base64.decode(b64) }.getOrNull() ?: return null
        return if (bytes.size == os.meka.core.domain.VaultKeyRules.KEY_BYTES && os.meka.core.domain.VaultKeyRules.keyId(bytes) == id) id to bytes else null
    }

    private fun holdVaultKey(bytes: ByteArray): String {
        val id = os.meka.core.domain.VaultKeyRules.keyId(bytes)
        setVaultLocal(os.meka.core.domain.VaultKeyRules.LOCAL_KEY, os.meka.core.domain.VaultKeyRules.local(id, kotlin.io.encoding.Base64.encode(bytes)))
        return id
    }

    /** The agreement key's public half, asked of the platform once per run. Caller is on the core. */
    private fun vaultPublic(crypto: VaultCrypto): String? =
        vaultPublicKey ?: runCatching { crypto.agreementPublicKey() }.getOrNull()?.takeIf { it.isNotBlank() }?.also { vaultPublicKey = it }

    /**
     * After a clean sync (caller on the core): publishes this device's agreement key, makes the household's key when
     * there is none, and opens a key sealed for this device or (after recovering) for the recovery words.
     */
    private fun vaultAfterSync() {
        val crypto = vaultCrypto ?: return
        val name = vaultName() ?: return
        val pub = vaultPublic(crypto) ?: return
        vaultKeys.publish(pub, name)
        val canonical = vaultKeys.canonical()
        var held = heldVaultKey()
        if (canonical == null) {
            // The first phone or Mac to finish a clean sync with no key in the household makes it.
            val id = held?.first ?: holdVaultKey(keyRandom.nextBytes(os.meka.core.domain.VaultKeyRules.KEY_BYTES))
            vaultKeys.setCanonical(id, name)
            vaultKeys.markHolds(id)
            return
        }
        if (held != null && held.first != canonical) {
            // Two devices made one at once: the household's (the latest write) wins; this one is kept aside, unused.
            setVaultLocal(os.meka.core.domain.VaultKeyRules.LOCAL_FORMER, vaultLocal(os.meka.core.domain.VaultKeyRules.LOCAL_KEY))
            setVaultLocal(os.meka.core.domain.VaultKeyRules.LOCAL_KEY, null)
            held = null
        }
        if (held == null) held = openVaultWrap(crypto, canonical)
        if (held != null) {
            vaultKeys.markHolds(held.first)
            setVaultLocal(os.meka.core.domain.VaultKeyRules.LOCAL_RECOVERY, null)
        }
    }

    /** The household's key from a wrap sealed for this device, else for the recovery words this device was given. */
    private fun openVaultWrap(crypto: VaultCrypto, canonical: String): Pair<String, ByteArray>? {
        fun accept(plainB64: String?): Pair<String, ByteArray>? {
            val bytes = plainB64?.let { runCatching { kotlin.io.encoding.Base64.decode(it) }.getOrNull() } ?: return null
            if (bytes.size != os.meka.core.domain.VaultKeyRules.KEY_BYTES || os.meka.core.domain.VaultKeyRules.keyId(bytes) != canonical) return null
            return holdVaultKey(bytes) to bytes
        }
        vaultKeys.wrap(replica.deviceId)?.takeIf { it.keyId == canonical && it.ephemeralKey != null }?.let { w ->
            val shared = runCatching { crypto.agree(w.ephemeralKey!!) }.getOrNull()
                ?.let { runCatching { kotlin.io.encoding.Base64.decode(it) }.getOrNull() }
            if (shared != null) {
                val k = os.meka.core.domain.VaultKeyRules.wrapKey(shared, replica.deviceId, canonical)
                accept(runCatching { crypto.openBox(kotlin.io.encoding.Base64.encode(k), w.sealed) }.getOrNull())?.let { return it }
            }
        }
        val rk = vaultLocal(os.meka.core.domain.VaultKeyRules.LOCAL_RECOVERY) ?: return null
        val w = vaultKeys.wrap(os.meka.core.domain.VaultKeyRules.RECOVERY)?.takeIf { it.keyId == canonical } ?: return null
        return accept(runCatching { crypto.openBox(rk, w.sealed) }.getOrNull())
    }

    private fun vaultKeyNow() {
        // Before the app sets the crypto (init's first refresh, the watch, tests) nothing local is read.
        val crypto = vaultCrypto ?: run {
            _vaultKey.value = os.meka.core.domain.VaultKeyRules.view(
                os.meka.core.domain.VaultState(replica.deviceId, vaultName(), false, null, null, null, emptyList(), emptyList()),
            )
            return
        }
        _vaultKey.value = os.meka.core.domain.VaultKeyRules.view(
            os.meka.core.domain.VaultState(
                me = replica.deviceId,
                myName = vaultName(),
                available = vaultPublic(crypto) != null,
                myPublicKey = vaultPublicKey,
                heldKeyId = heldVaultKey()?.first,
                canonical = vaultKeys.canonical(),
                members = vaultKeys.members(),
                wraps = vaultKeys.wraps(),
            ),
        )
    }

    /**
     * Allow on a device asking for the Vault key ([deviceId]): the key is sealed for that device's agreement key and
     * synced. Only after Meka checked the six digits match; false when this device can't (no key, no crypto).
     */
    suspend fun allowVaultDevice(deviceId: String): Boolean {
        val done = onCore {
            val crypto = vaultCrypto ?: return@onCore false
            val name = vaultName() ?: return@onCore false
            val (id, key) = heldVaultKey() ?: return@onCore false
            val m = vaultKeys.member(deviceId)?.takeIf { it.deviceId != replica.deviceId } ?: return@onCore false
            val once = runCatching { crypto.agreeOnce(m.publicKey) }.getOrNull() ?: return@onCore false
            val shared = runCatching { kotlin.io.encoding.Base64.decode(once.shared) }.getOrNull() ?: return@onCore false
            val k = os.meka.core.domain.VaultKeyRules.wrapKey(shared, deviceId, id)
            val sealed = runCatching { crypto.sealBox(kotlin.io.encoding.Base64.encode(k), kotlin.io.encoding.Base64.encode(key)) }.getOrNull()
                ?: return@onCore false
            vaultKeys.writeWrap(deviceId, id, once.publicKey, sealed, name)
            val at = nowMs()
            activity.recordDevice(
                os.meka.core.domain.VaultKeyRules.activityId(deviceId, at), at,
                "Let the ${m.name} open your Vault documents", "You allowed it after checking the code",
            )
            refresh()
            true
        }
        if (done) requestSync()
        return done
    }

    /** Not this one: the device's ask is put away (it can ask again). */
    suspend fun refuseVaultDevice(deviceId: String): Boolean {
        val done = onCore { vaultKeys.refuse(deviceId).also { if (it) refresh() } }
        if (done) requestSync()
        return done
    }

    /** Ask again, on a device a holder said no to. */
    suspend fun askVaultAgain(): Boolean {
        val done = onCore { vaultKeys.askAgain().also { if (it) refresh() } }
        if (done) requestSync()
        return done
    }

    /**
     * A device just recovered with the 24 words ([RecoveryEnrolment.Result.Enrolled.vaultRecovery]): kept on this
     * device only until the recovery wrap opens the household's Vault key, then forgotten.
     */
    suspend fun rememberVaultRecovery(key: String?) {
        if (key.isNullOrBlank()) return
        onCore { setVaultLocal(os.meka.core.domain.VaultKeyRules.LOCAL_RECOVERY, key) }
    }

    /** A new recovery key's words also open the Vault: the key is sealed for them (caller on the core). */
    private fun sealVaultForRecovery(words: List<String>) {
        val crypto = vaultCrypto ?: return
        val name = vaultName() ?: return
        val (id, key) = heldVaultKey() ?: return
        if (vaultKeys.canonical() != id) return
        val read = os.meka.core.domain.RecoveryKeyRules.read(words.joinToString(" "))
        val entropy = (read as? os.meka.core.domain.RecoveryKeyRules.Reading.Ok)?.entropy ?: return
        val rk = os.meka.core.domain.VaultKeyRules.recoveryKey(entropy)
        val sealed = runCatching { crypto.sealBox(kotlin.io.encoding.Base64.encode(rk), kotlin.io.encoding.Base64.encode(key)) }.getOrNull() ?: return
        vaultKeys.writeWrap(os.meka.core.domain.VaultKeyRules.RECOVERY, id, null, sealed, name)
    }

    // ---- The Vault, slice 2b: the document itself (a photo or PDF sealed with the Vault key) ----

    private fun localIds(key: String): List<String> = os.meka.core.domain.VaultFileRules.decodeIds(vaultLocal(key))

    private fun setLocalIds(key: String, ids: List<String>) = setVaultLocal(key, os.meka.core.domain.VaultFileRules.encodeIds(ids))

    /** Keeps [sealed] on this device for offline (caller on the core). */
    private fun keepVaultCopy(id: String, sealed: String) {
        setVaultLocal(os.meka.core.domain.VaultFileRules.localKey(id), sealed)
        setLocalIds(os.meka.core.domain.VaultFileRules.LOCAL_KEPT, localIds(os.meka.core.domain.VaultFileRules.LOCAL_KEPT) + id)
    }

    /** Lets this device's copy [id] go; with [deleteOnServer] its server copy is deleted too (now or after a sync). */
    private fun forgetVaultCopy(id: String, deleteOnServer: Boolean) {
        val r = os.meka.core.domain.VaultFileRules
        setVaultLocal(r.localKey(id), null)
        setLocalIds(r.LOCAL_KEPT, localIds(r.LOCAL_KEPT) - id)
        setLocalIds(r.LOCAL_PENDING, localIds(r.LOCAL_PENDING) - id)
        if (deleteOnServer) setLocalIds(r.LOCAL_GONE, localIds(r.LOCAL_GONE) + id)
    }

    /** A copy's random id: 32 hex characters from the secure random. */
    private fun newVaultFileId(): String = keyRandom.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /**
     * Adds (or replaces) the copy of document [docId]: [base64] is the file (a photo the app already re-encoded to
     * [os.meka.core.domain.VaultFileRules.PHOTO_LONG_SIDE]), [type] what the system called it ([name] its file name,
     * read only for its ending). Sealed here with the household's Vault key, kept on this device and sent up as sealed
     * bytes only; offline it goes up after the next sync. Refused (nothing kept) without the key, too big, or not a photo or PDF.
     */
    suspend fun addVaultCopy(docId: String, type: String?, base64: String, name: String? = null): os.meka.core.domain.VaultFileSaid {
        val r = os.meka.core.domain.VaultFileRules
        fun no(line: String) = os.meka.core.domain.VaultFileSaid(line, refused = true)
        class Made(val ref: os.meka.core.domain.VaultFileRef, val sealed: String, val old: os.meka.core.domain.VaultFileRef?)
        val made = onCore {
            val doc = documents.item(docId) ?: return@onCore no(r.GONE)
            val kind = r.typeOf(type, name)
            val bytes = runCatching { kotlin.io.encoding.Base64.decode(base64) }.getOrNull() ?: return@onCore no(r.COULDNT_READ)
            r.refusal(kind, bytes.size.toLong())?.let { return@onCore no(it) }
            val crypto = vaultCrypto ?: return@onCore no(r.NO_KEY)
            val by = vaultName() ?: return@onCore no(r.NO_KEY)
            val (keyId, key) = heldVaultKey()?.takeIf { it.first == vaultKeys.canonical() } ?: return@onCore no(r.NO_KEY)
            val id = newVaultFileId()
            val sealed = runCatching { crypto.sealBox(kotlin.io.encoding.Base64.encode(key), kotlin.io.encoding.Base64.encode(r.frame(id, bytes))) }
                .getOrNull() ?: return@onCore no(r.COULDNT_SEAL)
            if (sealed.length / 4 * 3 > r.MAX_SEALED_BYTES) return@onCore no(r.TOO_BIG)
            val ref = os.meka.core.domain.VaultFileRef(id, kind!!, bytes.size.toLong(), keyId, nowMs(), by)
            keepVaultCopy(id, sealed)
            setLocalIds(r.LOCAL_PENDING, localIds(r.LOCAL_PENDING) + id)
            documents.setFile(docId, ref)
            doc.file?.let { forgetVaultCopy(it.id, deleteOnServer = true) }
            refresh()
            Made(ref, sealed, doc.file)
        }
        if (made !is Made) return made as os.meka.core.domain.VaultFileSaid
        requestSync()
        val uploaded = uploadVaultCopy(made.ref.id, made.sealed)
        if (made.old != null) deleteVaultCopiesGone()
        return os.meka.core.domain.VaultFileSaid(r.addedLine(made.ref, uploaded, replaced = made.old != null), refused = false)
    }

    /** Puts one sealed copy on the server; true when it's there (and no longer pending). Never throws but cancellation. */
    private suspend fun uploadVaultCopy(id: String, sealed: String): Boolean {
        val api = vaultFileApi ?: return false
        val ok = runCatchingNotCancel { api.vaultFilePut(id, sealed) } == true
        if (ok) onCore { setLocalIds(os.meka.core.domain.VaultFileRules.LOCAL_PENDING, localIds(os.meka.core.domain.VaultFileRules.LOCAL_PENDING) - id) }
        return ok
    }

    /** Deletes the server copies this device let go of; those that fail stay listed for the next sync. */
    private suspend fun deleteVaultCopiesGone() {
        val api = vaultFileApi ?: return
        val r = os.meka.core.domain.VaultFileRules
        for (id in onCore { localIds(r.LOCAL_GONE) }) {
            if (runCatchingNotCancel { api.vaultFileDelete(id) } == true) onCore { setLocalIds(r.LOCAL_GONE, localIds(r.LOCAL_GONE) - id) }
        }
    }

    /**
     * After a clean sync: copies added offline go up, server copies let go of are deleted, sealed copies no document
     * points at any more are let go here, and up to [os.meka.core.domain.VaultFileRules.PREFETCH_PER_ROUND] copies the
     * other devices added are fetched (still sealed) so they open offline.
     */
    private suspend fun vaultFilesAfterSync() {
        val r = os.meka.core.domain.VaultFileRules
        val api = vaultFileApi
        class Plan(val upload: List<Pair<String, String>>, val fetch: List<String>)
        val plan = onCore {
            val live = documents.items().mapNotNull { it.file?.id }.toSet()
            localIds(r.LOCAL_KEPT).filter { it !in live }.forEach { forgetVaultCopy(it, deleteOnServer = false) }
            val upload = localIds(r.LOCAL_PENDING).mapNotNull { id -> vaultLocal(r.localKey(id))?.let { id to it } }
            // Only a device that can open them keeps them (a watch, or a device still waiting for the key, doesn't).
            val canOpen = vaultCrypto != null && vaultName() != null && heldVaultKey() != null
            val kept = localIds(r.LOCAL_KEPT).toSet()
            val fetch = if (canOpen) live.filter { it !in kept }.sorted().take(r.PREFETCH_PER_ROUND) else emptyList()
            Plan(upload, fetch)
        }
        for ((id, sealed) in plan.upload) uploadVaultCopy(id, sealed)
        deleteVaultCopiesGone()
        if (api == null) return
        for (id in plan.fetch) {
            val sealed = runCatchingNotCancel { api.vaultFileGet(id) } ?: continue
            if (sealed.length / 4 * 3 > r.MAX_SEALED_BYTES) continue
            onCore { if (documents.items().any { it.file?.id == id }) keepVaultCopy(id, sealed) }
        }
    }

    // The Vault, slice 4: the copies behind the fingerprint (VaultLockRules). Memory only, per device.
    private var vaultUnlockedAtMs = 0L

    /** After the fingerprint's (or Touch ID's) yes: copies open for [os.meka.core.domain.VaultLockRules.WINDOW_MS] at most. */
    suspend fun unlockVault() = onCore { vaultUnlockedAtMs = nowMs() }

    /** Leaving the Vault, or the app going away: copies lock again. */
    suspend fun lockVault() = onCore { vaultUnlockedAtMs = 0L }

    /** Whether Open copy needs the fingerprint first (the apps ask, then [unlockVault]). */
    suspend fun vaultCopiesLocked(): Boolean = onCore { !os.meka.core.domain.VaultLockRules.unlocked(vaultUnlockedAtMs, nowMs()) }

    /**
     * Opens document [docId]'s copy for the screen: from this device's sealed copy, else fetched from the server (and
     * kept, sealed, for offline), opened with the Vault key and checked it is this copy. The bytes go only to the
     * app's memory; a [os.meka.core.domain.VaultFileOpened.problem] says why it didn't open. Slice 4: refused
     * ([os.meka.core.domain.VaultFileOpened.locked]) until [unlockVault].
     */
    suspend fun openVaultCopy(docId: String): os.meka.core.domain.VaultFileOpened {
        val r = os.meka.core.domain.VaultFileRules
        val found = onCore { documents.item(docId)?.let { d -> d.file?.let { d.title to it } } }
            ?: return os.meka.core.domain.VaultFileOpened.problem("", r.GONE)
        val (title, ref) = found
        // Slice 4: nothing is read, fetched or opened until his fingerprint unlocked the copies.
        if (vaultCopiesLocked()) return os.meka.core.domain.VaultFileOpened.locked(title)
        var sealed = onCore { vaultLocal(r.localKey(ref.id)) }
        var fetched = false
        if (sealed == null) {
            val api = vaultFileApi ?: return os.meka.core.domain.VaultFileOpened.problem(title, r.OFFLINE)
            sealed = try {
                api.vaultFileGet(ref.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return os.meka.core.domain.VaultFileOpened.problem(title, r.OFFLINE)
            } ?: return os.meka.core.domain.VaultFileOpened.problem(title, r.notUploaded(ref.by))
            fetched = true
        }
        val box: String = sealed ?: return os.meka.core.domain.VaultFileOpened.problem(title, r.OFFLINE)
        return onCore {
            val crypto = vaultCrypto ?: return@onCore os.meka.core.domain.VaultFileOpened.problem(title, r.NO_KEY)
            val held = heldVaultKey()
            val res = r.open(ref, held?.first) {
                runCatching { crypto.openBox(kotlin.io.encoding.Base64.encode(held!!.second), box) }.getOrNull()
                    ?.let { runCatching { kotlin.io.encoding.Base64.decode(it) }.getOrNull() }
            }
            when (res) {
                is os.meka.core.domain.VaultFileOpen.Refused -> os.meka.core.domain.VaultFileOpened.problem(title, res.line)
                is os.meka.core.domain.VaultFileOpen.Opened -> {
                    if (fetched) keepVaultCopy(ref.id, box)
                    os.meka.core.domain.VaultFileOpened(title, ref.type, kotlin.io.encoding.Base64.encode(res.bytes), null)
                }
            }
        }
    }

    /** Removes document [docId]'s copy (the document stays) from every device and Navilife's server. */
    suspend fun removeVaultCopy(docId: String): os.meka.core.domain.VaultFileSaid {
        val r = os.meka.core.domain.VaultFileRules
        val ok = onCore {
            val file = documents.item(docId)?.file ?: return@onCore false
            documents.setFile(docId, null)
            forgetVaultCopy(file.id, deleteOnServer = true)
            refresh()
            true
        }
        if (!ok) return os.meka.core.domain.VaultFileSaid(r.GONE, refused = true)
        requestSync()
        deleteVaultCopiesGone()
        return os.meka.core.domain.VaultFileSaid(r.REMOVED, refused = false)
    }

    // ---- Recovery key (V1, slice 2: Settings → Recovery key) ----

    /** Settings → Recovery key as last read ([refreshRecoveryKey]); null until asked once. */
    val recoveryKeyView: StateFlow<os.meka.core.domain.RecoveryKeyView?> = _recovery.asStateFlow()

    /** Asks MEKA's server about the household's key; when it can't, the row says so and the problem why. */
    suspend fun refreshRecoveryKey(): os.meka.core.domain.RecoveryKeyView {
        val api = recoveryApi ?: return showRecovery(os.meka.core.domain.RecoveryKeyRules.NOT_CONNECTED)
        return try {
            val s = api.recoveryStatus().toDomain()
            onCore { recoveryStatus = s; recoveryReadAtMs = nowMs(); recoveryNow(null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showRecovery(recoveryProblem(e))
        }
    }

    /** A new key's words for the screen (made on this device; nothing is sent until [saveRecoveryKey]). */
    fun makeRecoveryKey(): os.meka.core.domain.RecoveryKeyDraft =
        os.meka.core.domain.RecoveryKeyRules.draft(keyRandom.nextBytes(os.meka.core.domain.RecoveryKeyRules.ENTROPY_BYTES)) { keyRandom.nextInt(it) }

    /**
     * Saves [draft] once [answers] (the words at its confirm positions, as typed) match: only its verifier is sent, and
     * the household's old key stops working. False when the words don't match or the server couldn't take it (the
     * view's problem says why); nothing is sent while the words don't match.
     */
    suspend fun saveRecoveryKey(draft: os.meka.core.domain.RecoveryKeyDraft, answers: List<String>): Boolean {
        if (!os.meka.core.domain.RecoveryKeyRules.confirms(draft, answers)) {
            showRecovery(os.meka.core.domain.RecoveryKeyRules.NOT_MATCHING); return false
        }
        val api = recoveryApi ?: run { showRecovery(os.meka.core.domain.RecoveryKeyRules.NOT_CONNECTED); return false }
        val saved = try {
            api.setRecoveryKey(draft.verifier).toDomain()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showRecovery(recoveryProblem(e)); return false
        }
        onCore {
            recoveryStatus = saved; recoveryReadAtMs = nowMs()
            // The Vault, slice 2a: the new words open the Vault key too (the old recovery seal is replaced).
            sealVaultForRecovery(draft.words)
            val at = nowMs()
            activity.recordDevice(
                os.meka.core.domain.RecoveryKeyRules.activityId(at), at,
                os.meka.core.domain.RecoveryKeyRules.MADE_SUMMARY, os.meka.core.domain.RecoveryKeyRules.WHY_YOU,
            )
            refresh()
            recoveryNow(null)
        }
        _setup.value?.let { v ->
            // Setup's step ticks at once (the rest of the page is as it was).
            val step = os.meka.core.domain.SetupRules.recoveryStep(true, saved)
            if (v.steps.any { it.key == step.key }) _setup.value = os.meka.core.domain.SetupRules.replaceStep(v, step)
        }
        return true
    }

    private suspend fun readRecoveryStatus(): os.meka.core.domain.RecoveryKeyRules.Status? {
        val api = recoveryApi ?: return null
        val s = runCatchingNotCancel { api.recoveryStatus().toDomain() } ?: return null
        onCore { recoveryStatus = s; recoveryReadAtMs = nowMs() }
        return s
    }

    private fun os.meka.core.wire.RecoveryCodec.Status.toDomain() =
        os.meka.core.domain.RecoveryKeyRules.Status(state, createdAtMs, createdBy, usedAtMs, usedBy)

    private fun recoveryProblem(e: Exception): String = when {
        e is FamilyUnavailableException && e.reason == FamilyUnavailableException.NO_KEY -> os.meka.core.domain.RecoveryKeyRules.NO_KEY
        e is FamilyUnavailableException -> os.meka.core.domain.RecoveryKeyRules.NO_ROUTE
        e is LinkRefusedException -> os.meka.core.domain.RecoveryKeyRules.refusal(e.reason)
        e is AuthRejectedException -> SIGNED_OUT_MESSAGE
        else -> os.meka.core.domain.RecoveryKeyRules.OFFLINE
    }

    private suspend fun showRecovery(problem: String?): os.meka.core.domain.RecoveryKeyView = onCore { recoveryNow(problem) }

    /** On the core thread. A problem leaves the last known status in place (offline before any answer: "Can't check"). */
    private fun recoveryNow(problem: String?): os.meka.core.domain.RecoveryKeyView =
        os.meka.core.domain.RecoveryKeyRules.pane(recoveryStatus, nowMs(), ZoneCalendar(timeZone), problem).also { _recovery.value = it }

    /** The settings row (Ask → More → Settings → Where I am now); the switch itself is kept on each device. */
    fun hereSetting(on: Boolean, permitted: Boolean): os.meka.core.domain.HereSettingView =
        os.meka.core.domain.HereRules.setting(on, permitted)

    suspend fun voiceTimingLine(): String? = onCore { os.meka.core.domain.SpeechRules.timingLine(speechTimings.toList()) }

    /**
     * The mic fix, step 3: something he said was added [ms] after his words ended, heard by [engine]. Kept in memory
     * only (the last [os.meka.core.domain.HeardTimingRules.KEPT]), never synced or sent; Activity shows
     * [heardTimingLine]. A negative or absurd time (a clock jump) is dropped.
     */
    suspend fun recordHeardTiming(ms: Long, engine: os.meka.core.domain.ListenEngine): Unit = onCore {
        if (ms < 0 || ms > 60_000) return@onCore
        heardTimings.addLast(os.meka.core.domain.HeardTiming(ms, engine))
        while (heardTimings.size > os.meka.core.domain.HeardTimingRules.KEPT) heardTimings.removeFirst()
    }

    /** Activity's "Speech to Added · 0.6 s · 0.9 s · middle 0.7 s"; null before the first. */
    suspend fun heardTimingLine(): String? = onCore { os.meka.core.domain.HeardTimingRules.timingLine(heardTimings.toList()) }

    // ---- The orb speed pass, slice 2: a timing trace of each Hello and Talk turn ----

    /**
     * "Hello, Navilife" tapped: times the turn from now to Navilife's voice ([os.meka.core.domain.TurnStopwatch]).
     * Ends any turn still open. Device only: nothing said is kept, only the times.
     */
    suspend fun turnHelloBegan(): Unit = onCore { beginTurn(os.meka.core.domain.TurnKind.HELLO, 0L) }

    /** A spoken question: his words ended [agoMs] ago; times the turn from then to the answer's voice. */
    suspend fun turnTalkBegan(agoMs: Long): Unit = onCore { beginTurn(os.meka.core.domain.TurnKind.TALK, agoMs.coerceIn(0L, 10_000L)) }

    /** The turn's speaking is over, or the conversation stopped: its trace is kept (when its voice started). */
    suspend fun turnEnded(): Unit = onCore { endTurn() }

    /** Settings → Navilife's voice → Speed: the last 20 turns, newest first, with a summary ([device]: "phone" or "Mac"). */
    suspend fun turnSpeed(device: String = "phone"): os.meka.core.domain.TurnSpeedView = onCore {
        turnWatch?.let { if (!it.open(nowMs())) endTurn() }
        os.meka.core.domain.TurnSpeedRules.view(loadTurnTraces(), ZoneCalendar(timeZone), device)
    }

    /** Speed's Clear: forgets the kept turns on this device. */
    suspend fun clearTurnSpeed(): Unit = onCore {
        turnTraces = emptyList()
        runCatching { localStore.transaction { localStore.setLocalValue(os.meka.core.domain.TurnSpeedRules.KEY, "") } }
    }

    private fun beginTurn(kind: os.meka.core.domain.TurnKind, agoMs: Long) {
        endTurn()
        val now = nowMs()
        turnWatch = os.meka.core.domain.TurnStopwatch(kind, now - agoMs)
        turnWallStart = now - agoMs
    }

    private fun endTurn() {
        val w = turnWatch ?: return
        turnWatch = null
        val trace = w.finish(turnWallStart) ?: return
        val next = os.meka.core.domain.TurnSpeedRules.add(loadTurnTraces(), trace)
        turnTraces = next
        runCatching { localStore.transaction { localStore.setLocalValue(os.meka.core.domain.TurnSpeedRules.KEY, os.meka.core.domain.TurnSpeedRules.encode(next)) } }
    }

    private fun loadTurnTraces(): List<os.meka.core.domain.TurnTrace> = turnTraces ?: os.meka.core.domain.TurnSpeedRules.decode(
        runCatching { localStore.transaction { localStore.localValue(os.meka.core.domain.TurnSpeedRules.KEY) } }.getOrNull(),
    ).also { turnTraces = it }

    /** The open turn, while it still takes marks. */
    private fun openTurn(): os.meka.core.domain.TurnStopwatch? = turnWatch?.takeIf { it.open(nowMs()) }

    /**
     * Voice completeness (2026-10-19): what the device's own voice says first when it takes over because Navilife's voice
     * couldn't be reached ([os.meka.core.domain.SpeechRules.offlineNote]), once until Navilife's voice is heard again;
     * null otherwise (slow, refused, the device's voice chosen, or already said). [mac]: the Mac's wording.
     */
    suspend fun speechOfflineNote(mac: Boolean): String? = onCore {
        val deviceChosen = mekaVoice.chosen() == os.meka.core.domain.MekaVoiceRules.DEVICE
        if (!os.meka.core.domain.SpeechRules.saysOfflineNote(speechUnreachable, deviceChosen, speechOfflineSaid)) return@onCore null
        speechOfflineSaid = true
        os.meka.core.domain.SpeechRules.offlineNote(mac)
    }

    /** The device's audio goes to Bluetooth headphones or the car now, so the next clips' timings say so (Activity). */
    suspend fun speechRoute(bluetooth: Boolean) = onCore { speechOverBluetooth = bluetooth }

    private fun speechTimed(atMs: Long, ms: Long, outcome: os.meka.core.domain.SpeechTiming.Outcome) {
        speechTimings.addLast(os.meka.core.domain.SpeechTiming(atMs, ms, outcome, speechOverBluetooth))
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
        os.meka.core.domain.SpeechRules.COMMON.flatMap { os.meka.core.domain.SpeechRules.pieces(it) }.forEach { if (!speechPrefetch(it)) return }
    }

    /**
     * The orb speed pass, slice 3: asks for [text]'s first piece ([os.meka.core.domain.SpeechRules.pieces]) in
     * Navilife's voice now, ahead of being said, and keeps it in memory, so [speechClip] plays it at once (or joins the
     * request while it is still on its way). Nothing when the device's voice is chosen, the server is resting, it is
     * already here or already asked for. Not counted in the speed log. True when the clip is here. Never throws.
     */
    suspend fun speechPrefetch(text: String): Boolean =
        speechPrefetchPiece(os.meka.core.domain.SpeechRules.pieces(text).firstOrNull() ?: return false)

    /**
     * [speechPrefetch] for one piece exactly as the speaker will ask for it ([os.meka.core.domain.SpeechRules.pieces]
     * of a whole line: a later piece may hold several sentences, which [speechPrefetch] would cut to its first).
     */
    private suspend fun speechPrefetchPiece(piece: String): Boolean {
        if (speechApi == null) return false
        val words = piece.takeIf { it.isNotBlank() && it.length <= os.meka.core.domain.SpeechRules.MAX_PIECE } ?: return false
        val voice = onCore { speechVoiceNow() } ?: return false
        val key = os.meka.core.domain.SpeechRules.cacheKey(voice.name, words)
        val mine = kotlinx.coroutines.CompletableDeferred<String?>()
        val other = onCore {
            if (speechClips.containsKey(key)) return@onCore null
            speechInFlight[key] ?: run { speechInFlight[key] = mine; mine }
        } ?: return true
        if (other !== mine) return other.await() != null
        try {
            val clip = requestSpeechClip(words, voice, os.meka.core.domain.SpeechRules.AHEAD_AUDIO_MS)
            mine.complete(clip)
            return clip != null
        } finally {
            mine.complete(null)
            onCore { if (speechInFlight[key] === mine) speechInFlight.remove(key) }
        }
    }

    /**
     * The orb speed pass, slice 3: the hello's spoken openers ([os.meka.core.domain.HelloRules.warmLines]: this part of
     * the day's and the next hour's, the clear-day line and, on the Mac, [welcome] back) made ahead in Navilife's voice,
     * so a tap on the orb speaks at once with no voice fetch. Already here: nothing is asked. Never throws.
     */
    suspend fun warmHello(welcome: Boolean = false) {
        if (speechApi == null) return
        val lines = onCore {
            val name = aboutYou.profile().shownName
            warmedName = os.meka.core.domain.CallNameRules.called(name) ?: ""
            warmedWelcome = welcome
            val minute = ZoneCalendar(timeZone).minuteOfDay(nowMs())
            // One name everywhere: the hello that asks his name is ready too while it would still ask.
            val ask = if (!os.meka.core.domain.CallNameRules.asks(name, nameAskedHere || aboutYou.nameAsked())) emptyList()
            else os.meka.core.domain.SpeechRules.pieces(os.meka.core.domain.CallNameRules.askGreeting(minute, welcome).spoken)
            // The orb says hello like a person: the openers the next taps would say (now, in an hour, a quick second tap).
            val now = nowMs()
            val day = ZoneCalendar(timeZone).epochDayOf(now)
            ask + os.meka.core.domain.HelloOpeners.warm(
                minute, day, now, helloOpenerMemory(), adaIntroducedToday(day), welcome, name, helloMoments(now, ZoneCalendar(timeZone)),
            )
        }
        // Each piece as the speaker asks for it (a later piece may join two sentences: "Good day? What can I do for you?").
        lines.forEach { if (!speechPrefetchPiece(it)) return }
        // The orb says hello like a person, slice 3: Ada's answers to "good thanks, you?" are ready too (no name in them).
        os.meka.core.domain.HelloReplyRules.warmLines().forEach { if (!speechPrefetchPiece(it)) return }
        // The orb speed pass, slice 4: the holding lines too, so a slow answer is bridged from memory.
        onCore { os.meka.core.domain.AnswerWaitRules.holdLines(aboutYou.profile().shownName) }
            .forEach { if (!speechPrefetch(it)) return }
    }

    /**
     * One name everywhere: the name [warmHello] last made the openers with ("" for none; null before it first ran) and
     * whether with the welcome back. When Your name changes (a card, About you, the other app) the openers are made
     * again at once ([rewarmHelloIfRenamed]).
     */
    private var warmedName: String? = null
    private var warmedWelcome = false

    /** Caller is on the core: the name changed since the openers were made, so they're made again now. */
    private fun rewarmHelloIfRenamed(callName: String?) {
        val was = warmedName ?: return
        if (was == (callName ?: "")) return
        warmedName = callName ?: ""
        val welcome = warmedWelcome
        scope.launch { runCatching { warmHello(welcome) } }
    }

    /** When this device last asked the server to get ready ([warmTalk]); null: not yet. */
    private var talkWarmedAt: Long? = null

    /**
     * The orb speed pass, slice 5: tells Navilife's server he's about to talk (the orb tapped, Talk opened, listening
     * started), so it opens its connections to the AI and to Navilife's voice before his question arrives
     * ([os.meka.core.domain.TalkWarmRules]: at most once every 20 s from this device). Nothing he said is sent. True
     * when the server started a warm-up. Not connected, an older server, offline: false. Never throws.
     */
    suspend fun warmTalk(): Boolean {
        val api = talkWarmApi ?: return false
        val go = onCore {
            val now = nowMs()
            os.meka.core.domain.TalkWarmRules.due(talkWarmedAt, now).also { if (it) talkWarmedAt = now }
        }
        if (!go) return false
        return try { api.talkWarm() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
    }

    /** The holding line said last ([answerHoldClip]), so the next one differs. */
    private var answerHoldLast: Int? = null

    /**
     * The orb speed pass, slice 4: the next holding line ([os.meka.core.domain.AnswerWaitRules.holdLines]: "One sec,
     * Meka…") in Navilife's voice, only when it is already on the device ([warmHello] makes them ahead); the next one
     * in turn that is here, so never the same twice running. Null otherwise (nothing is said). Never asks the server.
     */
    suspend fun answerHoldClip(): String? = onCore {
        val voice = speechVoiceNow() ?: return@onCore null
        val lines = os.meka.core.domain.AnswerWaitRules.holdLines(aboutYou.profile().shownName)
        val start = os.meka.core.domain.AnswerWaitRules.nextIndex(answerHoldLast, lines.size)
        for (k in lines.indices) {
            val i = (start + k) % lines.size
            val clip = speechCached(voice.name, lines[i]) ?: continue
            answerHoldLast = i
            return@onCore clip
        }
        null
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
        val offered = v.voices.map { os.meka.core.domain.OfferedVoice(it.id, it.gender, it.engine, it.provider) }
        val speaking = v.voices.firstOrNull { it.id.equals(chosen, ignoreCase = true) }?.id ?: v.defaultVoice
        val usage = os.meka.core.domain.SpeechRules.usageLine(
            v.state, v.month, v.usedChars, v.capChars, speaking, deviceChosen = chosen == os.meka.core.domain.MekaVoiceRules.DEVICE,
        )
        val g = v.google
        val failedClock = g?.failedAtMs?.let { at ->
            val cal = ZoneCalendar(timeZone)
            at.takeIf { cal.epochDayOf(it) == cal.epochDayOf(nowMs()) }?.let { LocalClock.formatMinute(cal.minuteOfDay(it)) }
        }
        val google = os.meka.core.domain.VoicePickerRules.googleLine(g?.state, g?.usedChars ?: 0, g?.freeChars ?: 0, g?.fallbackAtChars ?: 0, failedClock, v.month)
        return os.meka.core.domain.VoicePickerRules.view(v.state, offered, v.defaultVoice, chosen, usage, mac)
            .copy(googleLine = google, sampleLine = os.meka.core.domain.VoicePickerRules.sampleLine(speechSampleWords()))
    }

    /**
     * What ▶ Sample says now (natural voice, slice 2, [os.meka.core.domain.VoicePickerRules.sampleFrom]): the first two
     * sentences of today's brief as Navilife reads it ([briefScriptNow]: the AI's when kept, else its own), or the stock
     * line before there's a brief. The device's own voice says the same words when a clip can't be had. Pure and cheap.
     */
    fun speechSampleWords(): String {
        val v = _brief.value
        val script = if (v.dateLabel.isBlank()) null else runCatching { briefScriptNow() }.getOrNull()
        return os.meka.core.domain.VoicePickerRules.sampleFrom(script)
    }

    /** Slice 2: each voice's last sample (voice → words to base64 MP3), apart from Talk's clips, so replays are free. */
    private val sampleClips = LinkedHashMap<String, Pair<String, String>>()

    /**
     * ▶ Sample in the voice picker: [os.meka.core.domain.VoicePickerRules.SAMPLE] said by the server's [voice] (a Polly
     * name), as base64 MP3, or null when it can't be (not connected, refused, slower than
     * [os.meka.core.domain.SpeechRules.NEXT_AUDIO_MS]); the device then says it in its own voice. Kept in memory like
     * every clip. Never throws.
     */
    suspend fun speechSample(voice: String): String? {
        val api = speechApi ?: return null
        val name = os.meka.core.domain.MekaVoiceRules.normalize(voice)?.takeIf { it != os.meka.core.domain.MekaVoiceRules.DEVICE } ?: return null
        val words = speechSampleWords()
        onCore { sampleClips[name]?.takeIf { it.first == words }?.second }?.let { return it }
        val r = withTimeoutOrNull(os.meka.core.domain.SpeechRules.NEXT_AUDIO_MS) {
            try { api.speak(words, name) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        } ?: return null
        val audio = r.audio
        if (r.state != os.meka.core.wire.SpeechCodec.Response.SPOKEN || audio.isNullOrEmpty()) return null
        return onCore {
            // One per voice: a new brief's words replace that voice's old sample; the oldest voices go past the cap.
            sampleClips.remove(name)
            sampleClips[name] = words to audio
            // Room for every voice the picker lists (Google's British and American, Polly's).
            while (sampleClips.size > 48) sampleClips.remove(sampleClips.keys.first())
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

    /** Keeps a first piece the server said with a spoken answer ([AskReply.Answered.firstAudio]) ready for [speechClip]. */
    private fun speechWithAnswer(voice: String?, words: String, audio: String) {
        val key = os.meka.core.domain.SpeechRules.cacheKey(voice, words.trim())
        speechClips.remove(key)
        speechClips[key] = audio
        speechAnswerClips += key
        while (speechClips.size > os.meka.core.domain.SpeechRules.CACHE_CLIPS) speechAnswerClips.remove(speechClips.keys.first().also { speechClips.remove(it) })
        while (speechAnswerClips.size > os.meka.core.domain.SpeechRules.CACHE_CLIPS) speechAnswerClips.remove(speechAnswerClips.first())
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
    suspend fun aiStatus(): os.meka.core.domain.AiStatusView = aiStatusAsked().also { aiSeen = nowMs() to it }

    private suspend fun aiStatusAsked(): os.meka.core.domain.AiStatusView {
        val api = aiApi ?: return os.meka.core.domain.AskRules.STATUS_NOT_CONNECTED
        val r = try { api.aiStatus() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            return os.meka.core.domain.AskRules.STATUS_UNKNOWN
        } ?: return os.meka.core.domain.AskRules.STATUS_UNKNOWN
        return os.meka.core.domain.AskRules.statusView(r.state, r.reason, r.spentCents, r.budgetCents, r.level, r.month, r.keyRefused)
    }

    /** The AI's status as last asked, and when; [aiFeatureLine] reuses it for [AI_SEEN_MS]. */
    private var aiSeen: Pair<Long, os.meka.core.domain.AiStatusView>? = null

    /**
     * AI layer completeness slice 3: the line an AI feature shows while Navilife's AI can't answer ("Navilife's AI is
     * off · read from your day as it is, in Navilife's own words"), from the status asked in the last two minutes or
     * asked now. Null while it answers. Never throws.
     */
    suspend fun aiFeatureLine(feature: os.meka.core.domain.AiFeature): String? {
        val seen = aiSeen?.takeIf { nowMs() - it.first in 0 until AI_SEEN_MS }?.second ?: aiStatus()
        return os.meka.core.domain.AiFeatureRules.line(feature, seen)
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

    /** ADR-006: a model's proposal is a suggestion the policy engine must allow; it can never run without a tap ([AskPolicy]). */
    private fun askAllowed(p: os.meka.core.domain.AskProposal, untrusted: Boolean): Boolean = AskPolicy.allowed(p, untrusted)

    /** Earned trust: may [p] run on its own under Meka's "just do it" for [kind]? ([AskPolicy.auto]; the kill switch holds it.) */
    private fun askAuto(p: os.meka.core.domain.AskProposal, kind: os.meka.core.domain.TrustKind, untrusted: Boolean): Boolean =
        AskPolicy.auto(p, kind, untrusted, autonomy.paused())

    // ---- Self-updating phone app (build plan M1) ----

    /** The newest published build for [platform]; null when there is none, offline, or not connected. */
    suspend fun latestRelease(platform: String = ReleaseTransfer.ANDROID): AppRelease? =
        try { releasesApi?.latestRelease(platform) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /**
     * Downloads [release] into [sink] chunk by chunk, calling [progress] with the chunks done. Throws on network
     * errors; the caller checks the whole file's hash before installing. [fromChunk] resumes after chunks already held.
     */
    suspend fun downloadRelease(release: AppRelease, sink: suspend (ByteArray) -> Unit, progress: (Int) -> Unit, fromChunk: Int = 0) {
        val api = releasesApi ?: throw os.meka.core.sync.TransportException("not connected")
        ReleaseTransfer.download(api, release, sink, progress, fromChunk)
    }

    /**
     * A phone update that didn't go in (build plan: phone updates sometimes fail to install): written to Activity
     * ("Build 430 didn't install", with Android's words), then reported to the server so a build run can read why.
     * Returns whether the server took it (false offline, not connected or on an older server; the Fold keeps the
     * failure and reports it again later).
     */
    suspend fun recordUpdateFailure(f: os.meka.core.domain.UpdateFailure): Boolean {
        val rules = os.meka.core.domain.UpdateFailureRules
        onCore {
            activity.recordDevice(rules.activityId(f), f.atMs, rules.activitySummary(f), rules.activityWhy(f))
            refresh()
        }
        return reportUpdateFailure(f)
    }

    /**
     * The phone app's first run of a build (build plan: smaller phone updates): Activity says "Updated to build 471 ·
     * 18.2 MB" with the change from the build before, so the size of each update is visible on the phone.
     */
    suspend fun recordInstalledBuild(versionCode: Long, sizeBytes: Long, previousCode: Long?, previousSize: Long?, atMs: Long = nowMs()) {
        val rules = os.meka.core.domain.AppUpdateRules
        onCore {
            activity.recordDevice(
                rules.installedActivityId(versionCode), atMs,
                rules.installedSummary(versionCode, sizeBytes, previousCode), rules.installedWhy(sizeBytes, previousCode, previousSize),
            )
            refresh()
        }
    }

    /** The app icon changed with this update (app icon → option B): Activity says so once, with what to do. */
    suspend fun recordIconChanged(atMs: Long = nowMs()) {
        val rules = os.meka.core.domain.AppUpdateRules
        onCore {
            activity.recordDevice(rules.iconActivityId(), atMs, rules.ICON_SUMMARY, rules.ICON_WHY)
            refresh()
        }
    }

    /** Sends [f] to the server only (a report that couldn't go earlier). */
    suspend fun reportUpdateFailure(f: os.meka.core.domain.UpdateFailure): Boolean {
        val api = updateFailureApi ?: return false
        val report = os.meka.core.wire.UpdateFailureCodec.Report(
            f.stage, f.status, f.message, f.fromCode, f.toCode, f.atMs, f.storage, f.network, f.attempts,
        )
        return try { api.reportUpdateFailure(report) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
    }

    /**
     * The Fold's last posture changes (bedside clock, slice 2), newest first, sent to the server so a build run can read
     * what the phone reports. [model] is the phone's model name (e.g. "SM-F966B"). Returns whether the server took them
     * (false offline, not connected or on an older server; the Fold tries again later).
     */
    suspend fun reportFoldPostures(model: String?, changes: List<os.meka.core.domain.FoldPostureLog.Change>): Boolean {
        val api = foldPostureApi ?: return false
        if (changes.isEmpty()) return false
        val report = os.meka.core.wire.FoldPostureCodec.Report(
            model, nowMs(),
            changes.take(os.meka.core.wire.FoldPostureCodec.CHANGES_MAX).map { os.meka.core.wire.FoldPostureCodec.Change(it.atMs, it.entry) },
        )
        return try { api.reportFoldPostures(report) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
    }

    /**
     * Notifications never seen (slice 2): the phone's notification state and trace, sent to the server so a build run
     * can read where notifications stop. [phone] is MEKA's own "This phone" setting ("ALL" · "DIGESTS" · "OFF").
     * Returns whether the server took it (false offline, not connected or on an older server; the phone tries later).
     */
    suspend fun reportNotifyTrace(
        model: String?, health: os.meka.core.domain.NotificationHealth, phone: String,
        calls: os.meka.core.domain.CallScreenReport? = null,
    ): Boolean {
        val api = notifyDiagnosticsApi ?: return false
        // Spam calls still ring, slice 2: the call screening's diagnosis rides along, and is reason enough to send.
        if (health.trace.isEmpty() && calls?.calls.isNullOrEmpty()) return false
        val report = os.meka.core.wire.NotifyDiagnosticsCodec.Report(
            model, nowMs(), health.allowed, phone,
            health.channels.map { os.meka.core.wire.NotifyDiagnosticsCodec.Channel(it.id, it.name, it.on, it.lastPostMs) },
            health.trace.take(os.meka.core.wire.NotifyDiagnosticsCodec.TRACE_MAX).map { os.meka.core.wire.NotifyDiagnosticsCodec.Entry(it.atMs, it.kind.name, it.detail) },
            calls?.let { c ->
                os.meka.core.wire.NotifyDiagnosticsCodec.Calls(
                    c.roleHeld, c.protection, c.lookups,
                    c.calls.take(os.meka.core.wire.NotifyDiagnosticsCodec.CALLS_MAX).map { os.meka.core.wire.NotifyDiagnosticsCodec.Entry(it.atMs, it.verdict, it.detail) },
                )
            },
        )
        return try { api.reportNotifyTrace(report) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
    }

    /** Send a test (slice 2): asks the server to push a test to this device; what it said, or UNREACHABLE. */
    suspend fun testPush(): os.meka.core.domain.PushTestResult {
        val api = notifyDiagnosticsApi ?: return os.meka.core.domain.PushTestResult.UNREACHABLE
        val word = try { api.testPush() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        return os.meka.core.domain.PushTestResult.of(word)
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
            // About you, release 1: the Fold copies what MEKA held into the profile once, after a clean round.
            if (seedsAboutYou && report.rejected.isEmpty()) aboutYou.seedOnce(aboutYouBefore())
            // Release 2 slice 1: a profile from before About you had Clubs gets the clubs MEKA knew, once.
            if (seedsAboutYou && report.rejected.isEmpty()) aboutYouBefore().clubs?.let { aboutYou.seedClubsOnce(it) }
            // Release 2 slice 5: the council's holidays the code carried go into the profile once.
            if (seedsAboutYou && report.rejected.isEmpty()) releaseOneCouncil?.let { aboutYou.seedCouncilOnce(it) }
            // Release 2 slice 6: the train route the code carried goes into the profile once.
            if (seedsAboutYou && report.rejected.isEmpty()) releaseOneRoute?.let { aboutYou.seedRouteOnce(it) }
            // Slice 2 (release 2's gate): this app says, once, that it holds the filled-in profile.
            if (report.rejected.isEmpty()) aboutYouDevice?.let { aboutYou.markHeld(it) }
            // The Vault, slice 2a: this phone's or Mac's key is published, the household's made or opened, and what
            // that wrote goes up at once (so a second device sees the household's key rather than making its own).
            if (report.rejected.isEmpty()) {
                val waiting = replica.pendingPushCount()
                runCatching { vaultAfterSync() }
                if (replica.pendingPushCount() > waiting) runCatchingNotCancel { client.syncOnce() }
                // Slice 2b: copies added offline go up, removed ones come off the server, and copies the other
                // devices added are fetched (still sealed) so they open offline here too.
                runCatchingNotCancel { vaultFilesAfterSync() }
            }
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

    private var refreshing = false
    private var refreshAgain = false

    /**
     * Every view from the replica. Never nested (nightly Mac build 2026-10-18): a commit made while refreshing (mail
     * finds adding themselves, a goal following its balance…) used to call refresh again from inside itself, one
     * refresh deep per commit, on Kotlin/Native's smaller worker stacks; now it asks for one more pass after this one,
     * which also means the views never end on a pass that read the state from before that commit.
     */
    private fun refresh() {
        if (refreshing) { refreshAgain = true; return }
        refreshing = true
        try {
            var passes = 0
            do {
                refreshAgain = false
                refreshOnce()
            } while (refreshAgain && ++passes < MAX_REFRESH_PASSES)
        } finally {
            refreshing = false
            refreshAgain = false
        }
    }

    private fun refreshOnce() {
        // Rule 13, release 2: the children and clubs the rules name come from About you, never the code.
        if (os.meka.core.domain.Household.use(aboutYou.profile()) && _family.value != null) familyNow(_family.value?.problem)
        // Mail finds, slice 3: with "Renewals and promises from mail" on, new finds add themselves first.
        autoAddMailFinds()
        // Inbox autopilot, slice 4: "Archived for you" follows the synced tidies, Got it and the digest times.
        archivedChanged()
        // Slice 5c: the weekly "Not opened in 30 days" follows the synced senders, its answers and Saturday 09:00.
        unopenedChanged()
        // Slice 6a: "From your mail" follows the synced emails, Done/Snooze on either app and the minute (a snooze ending).
        mailNeedsChanged()
        // Discord channel watch: the cards follow the Fold's kept posts, Done on either app and the channels he follows.
        discordChanged()
        val all = tasks.all()
        val marks = eventActions.marks(all)
        _eventMarks.value = marks
        val allEvents = currentEvents()
        val dayEvents = marks.visible(allEvents)
        _barcaNear.value = os.meka.core.domain.WatchMatchRules.near(allEvents, marks, nowMs())
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
        // Life admin hour (slice 1): this week's and next week's hour, booked around events, work and date night.
        val lifeAdminNow = lifeAdmin.setting()
        val adminBusy: (Long) -> List<os.meka.core.domain.DayPlanner.Slot> = { day ->
            os.meka.core.domain.SessionRules.busyOn(day, dayEvents, workState.schedule, workHolidays, cal) +
                listOfNotNull(os.meka.core.domain.DateNightRules.slot(dateNightNow, day, cal))
        }
        val adminWeek = os.meka.core.domain.GoalRules.weekStart(todayEpochDay())
        val adminThis = os.meka.core.domain.LifeAdminRules.slot(lifeAdminNow, adminWeek, cal, adminBusy)
        val adminNext = os.meka.core.domain.LifeAdminRules.slot(lifeAdminNow, adminWeek + 7, cal, adminBusy)
        lifeAdminWeek = adminThis
        _sessions.value = if (sessionHabits.isEmpty()) os.meka.core.domain.SessionsView.EMPTY
        else os.meka.core.domain.SessionRules.book(sessionHabits, todayEpochDay(), nowMs(), cal) { day ->
            os.meka.core.domain.SessionRules.busyOn(day, dayEvents, workState.schedule, workHolidays, cal) +
                listOfNotNull(
                    os.meka.core.domain.DateNightRules.slot(dateNightNow, day, cal),
                    os.meka.core.domain.LifeAdminRules.keptSlot(lifeAdminNow, adminThis, day),
                    os.meka.core.domain.LifeAdminRules.keptSlot(lifeAdminNow, adminNext, day),
                )
        }
        val listsNow = lists.view(all, renewals.view(), shopping.view())
        val lifeAdminViewNow = os.meka.core.domain.LifeAdminRules.view(
            lifeAdminNow, adminThis, adminNext,
            os.meka.core.domain.LifeAdminRules.jobs(all, listsNow.renewals.attention, todayEpochDay(), adminThis, cal),
            nowMs(),
        )
        _lifeAdmin.value = lifeAdminViewNow
        val fastingNow = fasting.view()
        val goalsNow = goals.view(all).withSessions(_sessions.value).copy(money = moneyStreaks(), reading = reading.goalView(cal))
        val projected = project(all, dayEvents, marks.clubChildren)
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
        // Gift planner, slice 1: the birthdays and anniversaries with their ideas.
        val occasionItems = gifts.items()
        // Travel mode, slice 1: the trips with their bookings.
        val tripItems = trips.items()
        // The Sunday week ahead, slice 1: the coming week from what MEKA already knows.
        val weekAheadNow = os.meka.core.domain.WeekAheadRules.view(
            os.meka.core.domain.WeekAheadInputs(
                // Appointment nudges, slice 2c: a booked visit is said once, as its Google event when it has one.
                events = dayEvents.filterNot { os.meka.core.domain.AppointmentCalendarRules.isAppointment(it) },
                work = os.meka.core.domain.WorkHours(workState.schedule, holidays, homeDays = work.homeDays(todayDay), leaveDays = leaveDays,
                    commute = workState.commute, away = trips.awayDays()),
                holidays = holidays,
                school = schoolItems,
                dateNight = dateNightNow,
                renewals = listsNow.renewals.all,
                appointments = os.meka.core.domain.AppointmentCalendarRules.notInCalendar(appointments.items(), dayEvents, cal),
                forecast = forecast,
                goals = goalsNow.goals,
                bins = binItems,
                occasions = occasionItems,
                trips = tripItems,
                children = marks.clubChildren,
                // Inbox autopilot, slice 9b: the sorted mail and the chases of emails made into Waiting for.
                mail = activity.mailItems(),
                waiting = lists.waitingItems(),
                // Deliveries, slice 2: each parcel on the day it's due (lit on an office day).
                parcels = activity.parcels(),
                // Protected time, slice 5: each day's protected time and the week's line.
                protectedSetting = protectedTime.setting(),
            ),
            nowMs(), cal, ::dayWindowOf,
        )
        _weekAhead.value = weekAheadNow
        val shutdownRaw = shutdown.view(all, dayEvents, workState.schedule, workState.atWork, today, dayWindow(today.endMs), workHolidays, onLeave = today.epochDay in leaveDays)
        val shutdownNow = shutdownRaw.copy(
            // Daily receipt: what MEKA handled today, from the activity log (Today's evening line opens Activity).
            receipt = os.meka.core.domain.ReceiptRules.receipt(activity.items(), nowMs(), cal),
            tomorrow = shutdownRaw.tomorrow.copy(
                weatherLine = os.meka.core.domain.WeatherRules.dayGlance(forecast, todayDay + 1, cal),
                school = os.meka.core.domain.SchoolRules.tomorrowLine(schoolItems, todayDay, holidays),
                // Done for the day, the first train: "First train · the 07:42 · leave by 07:32" before an office day.
                train = firstTrainTomorrow(workState, todayDay + 1, cal),
                // Annual leave, slice 2: "Annual leave · no work · back Mon 2 Nov" on the evening before leave.
                // The commute counts everywhere, slice 4: "Work 09:00–17:30 · leave 07:40 · home about 18:50" before an office day.
                workLine = os.meka.core.domain.CommuteRules.withDayLine(
                    os.meka.core.domain.LeaveRules.workLine(shutdownRaw.tomorrow.workLine, leaveDays, leaveHours, dayWindow(today.endMs).epochDay),
                    hoursOf(workState, todayDay + 1), todayDay + 1, cal,
                ),
            ),
        )
        // The live tiles under the Day ring: next event, a running fast, habits today, renewals due.
        // A running fast also shows as the Day ring's inner arc (Living Today, slice 3); once the day is shut down the
        // ring looks ahead to tomorrow's first commitment (Living Today, item 1).
        val rideNow = trainRideNow(office, cal)
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
            lifeAdmin = os.meka.core.domain.LifeAdminRules.todayLine(lifeAdminViewNow, todayDay, nowMs(), cal)
                // Slice 2: once it's over, how it went ("✓ Life admin hour done · 3 jobs ticked").
                ?: os.meka.core.domain.LifeAdminRules.closeLine(lifeAdminNow, adminThis, all, lifeAdminViewNow.jobCount, todayDay, nowMs()),
            // Train-time queue, slice 1: on an office day's commute, what's saved for the train.
            // Train-time queue (4) and Reading's commute slots (2026-10-18): on the train, how long until he gets off
            // and what's on the go to read ("On the 07:42 · 15 min to … · 2 articles · Dune at 37%").
            trainQueue = os.meka.core.domain.QueueRules.trainLine(
                trainQueue.items(), office, nowMs(), cal, rideNow,
                os.meka.core.domain.ReadingRules.trainPart(reading.items()),
            ),
            weekAhead = os.meka.core.domain.WeekAheadRules.todayLine(weekAheadNow, nowMs(), cal, weekAheadSettings.setting()),
            bins = os.meka.core.domain.BinRules.tonightLine(binItems, todayDay, cal.minuteOfDay(nowMs()), holidays),
            gifts = os.meka.core.domain.GiftRules.todayLine(occasionItems, todayDay),
            trip = os.meka.core.domain.TripRules.todayLine(tripItems, todayDay, cal.minuteOfDay(nowMs())),
            // Inbox autopilot, slice 3g: parcels arriving today, read by the server from the shipping emails.
            // Deliveries completeness: lit with "you're at the office" on an office day, and yesterday's that didn't show.
            parcels = os.meka.core.domain.ParcelRules.todayLine(activity.parcels(), todayDay, officeDay = office != null),
            // Inbox fixes, slice 7: each account's unread mail and how much of it Navilife can tidy, counted by the server.
            mailUnread = os.meka.core.domain.MailUnreadRules.todayLine(activity.mailBacklogs(), nowMs(), lastHealthServer?.mail ?: lastMailFacts),
            // Drafted replies, slice 2: the emails from people waiting on his reply, and how many Navilife has drafted.
            mailReplies = os.meka.core.domain.MailAutoDraftRules.todayLine(activity.mailItems(), nowMs()),
            // Weekly reflection, slice 2: the one thing last week's reflection said matters this week.
            reflection = os.meka.core.domain.ReflectionRules.weekLine(reflections.all(), todayDay, cal.minuteOfDay(nowMs()), review.startMinute()),
            comingUp = os.meka.core.domain.NeedsYouMeanwhileRules.comingUp(all, nowMs(), cal),
            // Meeting prep, slice 1: from 15 min before a meeting with other people, who's coming and what's open.
            meetingPrep = os.meka.core.domain.MeetingPrepRules.card(
                dayEvents, marks, activity.mailItems(), listsNow.promised, listsNow.waiting, nowMs(), cal, meetingPrepLead().minutes,
            ),
            // Trading tracker, slice 6: up or down today and how many positions are open, never an amount.
            trading = os.meka.core.domain.TradingTodayRules.todayLine(trading.wallets(), tradingAlertSetting(), nowMs()),
        )
        _dateNight.value = os.meka.core.domain.DateNightRules.view(dateNightNow, todayDay, cal.minuteOfDay(nowMs()))
        _protected.value = os.meka.core.domain.ProtectedRules.view(
            protectedTime.setting(), todayDay, workState.schedule, holidays,
            os.meka.core.domain.ProtectedMailRules.paneLine(mailHeldCount(), mailHeldUntil(), nowMs(), cal),
        )
        _leaving.value = os.meka.core.domain.LeavingRules.view(leavingSetting())
        _weekAheadSetting.value = os.meka.core.domain.WeekAheadRules.settingView(weekAheadSettings.setting())
        _autonomy.value = os.meka.core.domain.AutonomyRules.view(trust.states(), autonomy.setting(), cal, nowMs(), mailAutopilotSetting(), mailFindsAutoOn(), directAddSetting())
        _lists.value = listsNow
        _meals.value = mealPlan.view(listsNow.shopping.toBuy.map { os.meka.core.domain.ShoppingRules.key(it.title) }.toSet())
        _emergency.value = emergency.view()
        _aboutYou.value = aboutYou.view(cal)
        rewarmHelloIfRenamed(_aboutYou.value.callName)
        val kidsChildren = aboutYou.profile().children
        val kidRecords = kids.records()
        // Kids' admin, slice 5: each child's chores and pocket money this week.
        val kidMoney = kidsChildren.mapNotNull { c ->
            os.meka.core.domain.KidsRules.idFor(c.name)?.let { id -> id to kidChores.money(id, kidRecords[id], todayDay) }
        }.toMap()
        _kids.value = os.meka.core.domain.KidsRules.view(kidsChildren, kidRecords, todayDay, cal, listsNow.renewals.all, kidMoney).let { v ->
            // Kids' admin, slice 3: what they need in the next 7 days, from School, club sessions and their fees.
            if (kidsChildren.isEmpty()) v else v.copy(week = os.meka.core.domain.KidsWeekRules.week(
                kidsChildren, schoolItems, dayEvents, marks.clubChildren, listsNow.renewals.all, nowMs(), cal, holidays,
            ))
        }
        _facts.value = lifeFacts.view()
        refreshMoney()
        _queue.value = trainQueue.view()
        _reading.value = reading.view(ZoneCalendar(timeZone))
        _errands.value = errands.view()
        _appointments.value = appointments.view()
        _needsYouStack.value = os.meka.core.domain.NeedsYouStackRules.build(
            _today.value, _lists.value.dueLine, nowMs(), ZoneCalendar(timeZone),
            credit = os.meka.core.domain.CallCreditRules.card(credit, workState.callAssistant),
            // Inbox fixes, slice 2: each account's backlog clean-up ready for his Approve.
            mail = os.meka.core.domain.MailBacklogRules.cards(activity.mailBacklogs(), autonomy.paused(), nowMs()),
            // Clean-ups: a new phone or Mac asking to join, on top with Allow and Not this one.
            devices = os.meka.core.domain.DeviceJoinRules.cards(deviceJoins.all(), nowMs()),
        )
        _fasting.value = fastingNow
        _goals.value = goalsNow
        _workMode.value = workState
        _shutdown.value = shutdownNow
        _wake.value = alarms.wakeView(dayEvents, hoursOf(workState, todayEpochDay())).let {
            it.copy(trains = os.meka.core.domain.CommuteDisruptionRules.offer(lineStatus.snapshot(), it, nowMs(), ZoneCalendar(timeZone)))
        }
        // Live trains, slice 5: this morning's wake alarm rings with the train to leave for.
        _nextAlarm.value = os.meka.core.domain.TrainWakeRules.ring(alarms.next(), currentTrainWake())
        _quickAlarms.value = alarms.quickItems()
        val notifySettings = notifyPrefs.settings()
        _notifySettings.value = notifySettings
        _brief.value = brief.view(all, dayEvents, workState.schedule, notifySettings.quiet, _lists.value, _goals.value, _fasting.value, today,
            news.all(), news.choices(), workHolidays, placesLine ?: os.meka.core.domain.WeatherRules.nowLine(forecast, nowMs(), cal), schoolToday,
            upNextId = _today.value.upNext?.id, work = hoursOf(workState, todayEpochDay()), children = marks.clubChildren,
            newsFollows = news.follows(), newsRead = news.readKeys().toHashSet()).let {
                // Annual leave, slice 2: "… · last day before leave · back Mon 2 Nov" and "Annual leave · no work · back …".
                val workLine = os.meka.core.domain.LeaveRules.workLine(it.workLine, leaveDays, leaveHours, today.epochDay)
                val t = _today.value
                val awayDays = trips.awayDays()
                // Travel mode completeness, slice 3: the destination's weather while away there, and the day before.
                val tripSky = os.meka.core.domain.TripWeatherRules.brief(tripWeather.forecast(), awayDays[todayDay], awayDays[todayDay + 1], todayDay, tripClocks)
                it.copy(
                    weatherLine = tripSky.paneLine ?: os.meka.core.domain.WeatherRules.dayGlance(forecast, todayDay, cal),
                    workLine = workLine,
                    // A morning brief worth listening to, slice 1: the commute, both places and home and family.
                    story = it.story.copy(
                        officeDay = office != null && workLine?.startsWith("Work ") == true,
                        // Travel mode completeness: a trip day says where he is with the kind of day.
                        away = awayDays[todayDay],
                        awayWeather = tripSky.today,
                        tripTomorrowWeather = tripSky.tomorrow,
                        placesLine = placesLine,
                        route = os.meka.core.domain.RouteRules.todayLine(lineStatus.snapshot(), office, nowMs(), cal)?.brief,
                        // A morning brief worth listening to: the train to leave for, from the live board.
                        leaveBy = os.meka.core.domain.RouteRules.briefLeave(lineStatus.snapshot(), office, nowMs(), cal),
                        needsYou = _needsYouStack.value.cards.count {
                            it.kind == os.meka.core.domain.DecisionKind.CONFLICT || it.kind == os.meka.core.domain.DecisionKind.CREDIT
                        },
                        // Deliveries completeness: today's parcels (and yesterday's that didn't show) with home and family.
                        family = listOfNotNull(t.bins?.spoken, t.parcels?.spoken, t.dateNight?.spoken, t.gifts?.spoken, t.trip?.spoken),
                        // Slice 4: urgent voice messages and requests from watched people, waiting on you.
                        asks = os.meka.core.domain.BriefScriptRules.asks(requestCards.open(), held.items(), today) { cal.minuteOfDay(it) },
                        // Inbox autopilot, slice 9a: how many emails need him and who from.
                        mail = os.meka.core.domain.MailAskRules.brief(activity.mailItems(), nowMs()),
                        // Weekly reflection, slice 2: the one thing that matters this week.
                        weekMatters = os.meka.core.domain.ReflectionRules.briefSentence(t.reflection, it.story.length, todayDay),
                        // Barça match day, slice 3: last night's result and scorers, and where today's match is on.
                        barca = os.meka.core.domain.BarcaBriefRules.briefSentences(allEvents, marks, nowMs(), ZoneCalendar(timeZone)),
                        // Trading tracker, slice 6: up or down on Hyperliquid, no amounts (Short: only when lit).
                        trading = os.meka.core.domain.TradingTodayRules.briefSentence(t.trading, it.story.length),
                        // Protected time, slice 5: "Your evening is protected from 7 o'clock."
                        protectedTime = os.meka.core.domain.ProtectedRules.briefSentence(protectedTime.setting(), todayDay, workState.schedule, holidays),
                    ),
                    // Protected time, slice 5: "Protected from 19:00 tonight" under the date.
                    protectedLine = os.meka.core.domain.ProtectedRules.briefLine(protectedTime.setting(), todayDay, workState.schedule, holidays),
                )
            }
        _newsPlace.value = news.place(nowMs(), dayEvents, ZoneCalendar(timeZone), trainQueue.items(), newsDown)
        val lineSnap = lineStatus.snapshot()
        val routeNow = os.meka.core.domain.RouteRules.todayLine(lineSnap, office, nowMs(), cal)
        // The commute counts everywhere, completeness: the watch's leave-by row from the same board and commute.
        watchCommuteNow = run {
            val commute = os.meka.core.domain.RouteRules.commute(office, nowMs(), cal)
            val leave = if (office != null && commute != null) {
                os.meka.core.domain.RouteRules.liveBoard(lineSnap, commute, nowMs())?.let { os.meka.core.domain.RouteRules.leaveBy(it, commute, office, nowMs(), cal) }
            } else null
            os.meka.core.domain.WatchCommuteRules.view(
                office, os.meka.core.domain.CommuteRules.bands(hoursOf(workState, todayDay), todayDay, cal), leave, routeNow, rideNow, nowMs(), cal,
            )
        }
        val weatherNow = os.meka.core.domain.WeatherRules.view(forecast, nowMs(), cal, weatherPlace.wanted(), workForecast, workPlace.wanted(), office)
            // The trains on an office day's commute (Places item 4), under the weather line.
            .copy(route = routeNow)
        // Away from home and work with a fresh fix (Places item 3): Today's line says where Meka is.
        val hereLine = os.meka.core.domain.HereRules.todayLine(hereFix, nowMs(), cal)
        _signIn.value = listOfNotNull(os.meka.core.domain.SignInRules.line(signIns.all(), nowMs(), cal))
        _weather.value = if (hereLine == null) weatherNow
        else weatherNow.copy(nowLine = hereLine, nowSpoken = hereLine.replace("°", " degrees"))
        _review.value = review.view(reviewOffset, all, dayEvents, _goals.value, fasting.ended()) { day ->
            dayWindow(ZoneCalendar(timeZone).toEpochMs(day, 12 * 60))
        }
        _wins.value = wins.view(_review.value.weekStart)
        // Balance view, slice 1: the reviewed week's time across work, family, health and self.
        run {
            val balanceHours = hoursOf(workState, todayEpochDay())
            val childOf: (os.meka.core.domain.CalendarEvent) -> String? = { e ->
                os.meka.core.domain.ClubChildRules.child(e, marks.clubChildren, ZoneCalendar(timeZone)) ?: os.meka.core.domain.FootballRules.child(e)
            }
            // Slice 2: the reviewed week and the three before it, counted the same way, for each area's trend.
            // Slice 5: his "Count as …" choices, by title.
            val chosen = balanceTargets.choices()
            val trendWeeks = os.meka.core.domain.BalanceRules.trendMinutes(
                _review.value.weekStart, nowMs(), ZoneCalendar(timeZone), balanceHours, dayEvents, review::habitDays, childOf, chosen,
            )
            _balance.value = os.meka.core.domain.BalanceRules.view(
                _review.value.weekStart, nowMs(), ZoneCalendar(timeZone), balanceHours, dayEvents,
                _review.value.habits.map { it.title to it.days }, balanceTargets.targets(), childOf, trendWeeks, chosen,
            )
            // Slice 4: this week as planned, for Thursday evening's one nudge.
            val thisWeek = os.meka.core.domain.GoalRules.weekStart(todayEpochDay())
            balanceNudge = os.meka.core.domain.BalanceRules.nudge(
                thisWeek, ZoneCalendar(timeZone), hoursOf(workState, thisWeek), dayEvents, review.habitDays(thisWeek),
                balanceTargets.targets(), childOf, chosen,
            )
            // Slice 3: the week ahead's balance line, the week it shows counted as planned.
            val ahead = _weekAhead.value
            if (ahead.weekStart > 0) {
                _weekAhead.value = ahead.withBalance(
                    os.meka.core.domain.BalanceRules.weekAheadLine(
                        ahead.weekStart, ahead.title == "Next week", ZoneCalendar(timeZone), hoursOf(workState, ahead.weekStart),
                        dayEvents, review.habitDays(ahead.weekStart), balanceTargets.targets(), childOf, chosen,
                    ),
                )
            }
        }
        _reflection.value = ZoneCalendar(timeZone).let { cal ->
            os.meka.core.domain.ReflectionRules.view(
                _review.value, reflections.all(), _wins.value.week.map { it.text }, _goals.value.goals,
                cal.epochDayOf(nowMs()), cal.minuteOfDay(nowMs()), cal, reflectRequestedWeek,
            )
        }
        _search.value = runSearch(all)
        _activity.value = activity.view()
        _calendar.value = CalendarAgenda.build(
            all, marks.forCalendarTab(allEvents), nowMs(), ZoneCalendar(timeZone), hidden = marks.hidden,
            work = hoursOf(workState, todayEpochDay()), children = marks.clubChildren,
        )
        _calendarsOnToday.value = os.meka.core.domain.CalendarRules.choices(allEvents, marks.hiddenCalendars, marks.shownCalendars)
        val editsNow = calendarEdits.all()
        _editsSeen.value = editsNow
        _editLines.value = os.meka.core.domain.EditLineRules.lines(editsNow, nowMs())
        _afterWork.value = held.summary().without(afterWorkHiding)
        _requests.value = requestCards.open()
        _approvals.value = approvalStore.cards()
        _school.value = school.view(hoursOf(workState, todayDay))
            .copy(inset = os.meka.core.domain.SchoolTerms.insetLink(aboutYou.profile().schoolWebsite))
        val binRemind = bins.remindMinute()
        _bins.value = os.meka.core.domain.BinRules.view(binItems, todayDay, holidays, binRemind)
        // Bin days, completeness: tonight's bins on the watch, lit from his reminder's time.
        watchBinsNow = os.meka.core.domain.BinRules.watch(binItems, todayDay, cal.minuteOfDay(nowMs()), holidays, binRemind)
        watchBinRemind = binRemind
        _gifts.value = os.meka.core.domain.GiftRules.view(occasionItems, todayDay)
        val docItems = documents.items()
        _trips.value = os.meka.core.domain.TripRules.view(tripItems, todayDay, docItems, tripClocks)
        _documents.value = os.meka.core.domain.DocumentRules.view(docItems, todayDay, cal, vaultName())
        vaultKeyNow()
        val heldNow = _afterWork.value
        _people.value = os.meka.core.domain.PeopleRules.view(
            listsNow.promised, listsNow.waiting, occasionItems,
            triageCards.contacts() + os.meka.core.domain.PeopleRules.heldContacts(heldNow), todayDay, nowMs(), cal,
            listedPeople,
        )
        _cars.value = os.meka.core.domain.CarRules.views(car.cars(), listsNow.renewals.all, carCheck(), todayDay, cal)
        _car.value = _cars.value.first
        _parkingTimers.value = os.meka.core.domain.ParkingTimerRules.view(parkingTimers.all(), _cars.value, nowMs(), cal)
        _leave.value = leave.view(hoursOf(workState, todayDay), schoolItems)
        // Ask's Life grid (Fold review 2026-10-12, item 7): each tile's live line, from the views just made.
        _lifeLines.value = os.meka.core.domain.LifeTileRules.lines(os.meka.core.domain.LifeTileRules.inputs(
            todayDay, _today.value, _lists.value, _goals.value, _meals.value, _bins.value, _dateNight.value, _school.value,
            _appointments.value, _leave.value, _gifts.value, _trips.value, _errands.value, _emergency.value, _facts.value,
            _people.value,
        ).copy(
            // Kids' admin, slice 1: a due size check (lit), else the first child's shoe size.
            kidsLine = _kids.value.tile,
            // Money manager, slice 1: the accounts and the last import, never an amount.
            moneyLine = os.meka.core.domain.MoneyRules.tile(money.accounts(), ZoneCalendar(timeZone), money.cards(), todayDay),
            // Car and parking, slice 1: the Car tile's next date (where it's parked is the Fold's own, laid over it there).
            // Slice 2: a running parking timer leads it on both apps ("Paid until 15:30 · 40 min left").
            // Two cars (2026-10-14): each car on its own line, its timer or next date.
            carLine = os.meka.core.domain.ParkingRules.carsLine(_cars.value, _parkingTimers.value, emptyList(), nowMs())
                ?: _parkingTimers.value.tile ?: os.meka.core.domain.ParkingRules.carLine(car.cars(), listsNow.renewals.all, todayDay),
        ))
        // Ask polish, item 3: Daily's rows say what they hold now ("Tomorrow 06:40 · 5 things", "2 left", "2 saved").
        val fixtureNow = os.meka.core.domain.DailyRules.fixture(dayEvents, nowMs(), cal)
        val briefNow = _brief.value
        val dailyShutdown = _shutdown.value
        val weekSetting = _weekAheadSetting.value
        val dailyAdmin = _lifeAdmin.value.next
        _dailyLines.value = os.meka.core.domain.DailyRules.lines(os.meka.core.domain.DailyInputs(
            nowMinute = cal.minuteOfDay(nowMs()),
            briefOffered = briefNow.offered,
            briefStartMinute = briefNow.startMinute,
            todayThings = briefNow.eventCount + briefNow.taskCount,
            tomorrowThings = dailyShutdown.tomorrow.eventCount + dailyShutdown.tomorrow.taskCount,
            weekAheadReady = _today.value.weekAhead != null,
            weekAheadOn = weekSetting.on,
            weekAheadTime = weekSetting.timeLabel,
            fixture = fixtureNow?.first,
            fixtureToday = fixtureNow?.second == true,
            stories = _newsPlace.value.items.size,
            shutdownDoneLine = dailyShutdown.doneLine,
            shutdownOffered = dailyShutdown.offered,
            shutdownLeft = dailyShutdown.left.size,
            queueWaiting = _queue.value.waiting.size,
            reading = os.meka.core.domain.ReadingRules.dailyLine(reading.items()),
            lifeAdminOn = _lifeAdmin.value.on,
            lifeAdminDay = dailyAdmin?.day,
            lifeAdminMinute = dailyAdmin?.let { cal.minuteOfDay(it.startMs) },
            lifeAdminJobs = _lifeAdmin.value.jobCount,
            todayDay = todayDay,
        ))
        _triage.value = triageCards.open()
        _groupGists.value = gistStore.open()
        _chatSummaries.value = chatSummaryStore.all()
        _blockedCallers.value = blockList.view()
        _heldCalls.value = held.calls().map { if (it.item.id in recordingDeleting) it.copy(recordingDeleted = true) else it }
        _callLog.value = os.meka.core.domain.CallLogRules.view(_heldCalls.value, _blockedCallers.value, nowMs(), ZoneCalendar(timeZone))
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
            promised = _lists.value.promised,
            // Ask knows how Navilife itself works, slice 2: Search finds the help entries too.
            help = os.meka.core.domain.HelpRules.entries,
            helpOnMac = helpOnMac,
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
            leaving = leavingSetting(),
            weekAhead = _weekAhead.value,
            weekAheadSetting = weekAheadSettings.setting(),
            bins = bins.items(), holidays = bankHolidays.calendar(), binRemindMinute = bins.remindMinute(),
            carRegistration = car.registration(),
            lifeAdmin = _lifeAdmin.value,
            occasions = gifts.items(),
            trips = trips.items(),
            documents = documents.items(),
            sessionAskGraceMin = if (workoutsInBackground) os.meka.core.domain.WorkoutRules.ASK_GRACE_MIN else 0,
            wake = currentWake(),
            trainWake = currentTrainWake(),
            trainWakeAlarmMinute = alarms.alarm(os.meka.core.domain.AlarmRules.wakeId(todayEpochDay()))?.minute,
            mailMoment = mailMomentSetting(),
            mailWaiting = os.meka.core.domain.MailNeedsRules.allRows(mailShown(), nowMs(), ZoneCalendar(timeZone)).size,
            protectedFromMin = protectedFromToday(),
            // Deliveries, slice 2: "Parcel out for delivery · Amazon" when a shipping email says so.
            parcels = activity.parcels(),
            // Car and parking, slice 2: ten minutes before a parking timer ends.
            parkingTimers = parkingTimers.all(), cars = _cars.value,
            // Kids' admin, slice 6: the Saturday heads-up three months after a child's sizes were checked.
            kids = aboutYou.profile().children, kidRecords = kids.records(),
            // Meeting prep, slice 1: each guest's last email for the heads-up 15 min before.
            mail = activity.mailItems(),
            // Meeting prep, slice 2: how early (or off), his synced choice.
            prepLeadMin = meetingPrepLead().minutes,
            // Money manager, slice 7: card payments three days before (until Paid) and 0% periods ending.
            cards = money.cards(),
            // Trading tracker, slice 3: near liquidation (even in quiet hours) and a drop from the month's high.
            trading = trading.wallets(), tradingAlerts = tradingAlertSetting(),
            // Money manager, slice 18: on 2 January, last year's review is ready (read only in January).
            moneyYear = moneyYearReady(),
            moneyMonth = moneyMonthReady(),
            // Money manager, nudges: a heads-up when recent payments make something worth a look in Money.
            moneyLook = moneyLookItems(),
            // Balance view, slice 4: Thursday evening's nudge when an area is well short as planned.
            balance = balanceNudge,
            // Protected time, slice 6: at 07:00 the morning it ends, one note for the business mail that waited.
            protectedTime = protectedTime.setting(), workSchedule = work.schedule(),
        )

    /**
     * When today's protected time starts (local minutes; 0 for a wholly protected day), or null: the ten-minute inbox's
     * heads-up doesn't call him into it (Inbox autopilot, slice 7b).
     */
    private fun protectedFromToday(): Int? {
        val cal = ZoneCalendar(timeZone)
        val day = todayEpochDay()
        val slot = os.meka.core.domain.ProtectedRules.slots(protectedTime.setting(), day, work.schedule(), bankHolidays.calendar(), cal).firstOrNull()
            ?: return null
        return if (slot.startMs <= cal.toEpochMs(day, 0)) 0 else cal.minuteOfDay(slot.startMs)
    }

    /** Work hours on [day] from the work-mode [state]: bank holidays, work-from-home days and booked leave. */
    private fun hoursOf(state: WorkModeState, day: Long): os.meka.core.domain.WorkHours =
        os.meka.core.domain.WorkHours.of(state, bankHolidays.calendar(), day, work.homeDays(day), leave.days(), trips.awayDays())

    /** The wake view as the shutdown shows it, or null before the first refresh worked it out. */
    private fun currentWake(): os.meka.core.domain.WakeView? = _wake.value.takeIf { it != os.meka.core.domain.WakeView.EMPTY }

    /** Leaving now with the journey home = Work mode's commute home (the commute counts everywhere, slice 4). */
    private fun leavingSetting(): os.meka.core.domain.LeavingSetting =
        os.meka.core.domain.LeavingRules.withCommute(leavingNow.setting(), work.commute().forDay(todayEpochDay()))

    /** Today's office window (Places item 2): a work day that isn't a work-from-home day, or null. */
    /** Live trains, slice 5: this morning's wake alarm anchored on the live board's train, or null. */
    private fun currentTrainWake(): os.meka.core.domain.TrainWake? = os.meka.core.domain.TrainWakeRules.view(
        lineStatus.snapshot(), alarms.alarm(os.meka.core.domain.AlarmRules.wakeId(todayEpochDay())), officeToday(),
        alarms.bufferMin(), nowMs(), ZoneCalendar(timeZone),
    )

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

    private fun project(
        all: List<os.meka.core.domain.Task> = tasks.all(), dayEvents: List<os.meka.core.domain.CalendarEvent> = visibleEvents(all),
        // Read here, not from _eventMarks: the first projection runs while the core is still being built.
        children: Map<String, String> = eventActions.clubChildren(),
    ): Today {
        val now = nowMs()
        val day = dayWindow(now)
        return TodayProjection.project(
            all, now, day, dayEvents, ZoneCalendar(timeZone), _sessions.value.todayBlocks(day.epochDay, now),
            work = work.hours(localClock(), day.epochDay), children = children,
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

/**
 * Inbox autopilot, slice 3b-2: what Mail's switch or a tidy's Undo said ([line]) and the Mail pane as it is now
 * ([view], null when this device hasn't listed the accounts yet: the pane reads them again).
 */
data class MailSwitched(val line: String, val view: os.meka.core.domain.MailAccountsView?)

/** Review opens its own list, slice 1: what a tick in the Empty Junk review said ([line] null: nothing changed) and the sheet now. */
data class JunkReviewChange(val line: String?, val review: os.meka.core.domain.JunkReview?)
