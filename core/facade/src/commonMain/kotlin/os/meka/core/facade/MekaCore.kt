package os.meka.core.facade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import os.meka.core.domain.DayWindow
import os.meka.core.domain.EveningShutdown
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
    private val work = WorkMode(replica, { bankHolidays.calendar() }, nowMs)
    private val lists = os.meka.core.domain.Lists(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val renewals = Renewals(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val goals = Goals(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val fasting = Fasting(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val shutdown = EveningShutdown(replica, tasks, nowMs, ZoneCalendar(timeZone))
    private val brief = MorningBrief(replica, nowMs, ZoneCalendar(timeZone))
    private val news = os.meka.core.domain.News(replica)
    private val review = WeeklyReview(replica, nowMs, ZoneCalendar(timeZone))
    /** The week the review screen shows (null: the default for today); a screen choice, not synced. */
    private var reviewOffset: Int? = null
    private val notifyPrefs = NotificationPrefs(replica)
    private val interruptions = os.meka.core.domain.Interruptions(replica, ZoneCalendar(timeZone))
    private val activity = os.meka.core.domain.ActivityLog(replica, ids::next, nowMs, ZoneCalendar(timeZone))
    private val eventActions = os.meka.core.domain.EventActions(replica, tasks, nowMs, ZoneCalendar(timeZone))
    private val held = os.meka.core.domain.HeldMessages(replica, nowMs)
    private var syncClient: SyncClient? = transport?.let { SyncClient(replica, it) }
    private var accountsApi: AccountsApi? = transport as? AccountsApi
    private var releasesApi: ReleasesApi? = transport as? ReleasesApi
    private var pushApi: PushApi? = transport as? PushApi

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

    private val _brief = MutableStateFlow(MorningBriefView.EMPTY)
    /**
     * Morning brief: today at a glance, what you're waiting on, what needs you on your lists, habits and a running
     * fast. Offered from the end of quiet hours until noon; synced "Got it". Moves with the clock.
     */
    val briefView: StateFlow<MorningBriefView> = _brief.asStateFlow()

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

    private val _calendarsOnToday = MutableStateFlow<List<os.meka.core.domain.CalendarChoice>>(emptyList())
    /** Calendars' "On Today" list: every calendar in the mirror and whether it shows on Today (all-day polish). */
    val calendarsOnToday: StateFlow<List<os.meka.core.domain.CalendarChoice>> = _calendarsOnToday.asStateFlow()

    private val _activity = MutableStateFlow(os.meka.core.domain.ActivityView.EMPTY)
    /** What MEKA did and why (V1 activity log): the last 30 days by day, newest first; follows sync. */
    val activityView: StateFlow<os.meka.core.domain.ActivityView> = _activity.asStateFlow()

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
    suspend fun complete(taskId: String) = onCore { tasks.complete(taskId) }
    suspend fun reopen(taskId: String) = onCore { tasks.reopen(taskId) }
    suspend fun rename(taskId: String, title: String) = onCore { tasks.edit(taskId, TaskEdit(title = title)) }
    suspend fun schedule(taskId: String, atMs: Long?) =
        onCore { tasks.edit(taskId, if (atMs == null) TaskEdit(clearScheduledAt = true) else TaskEdit(scheduledAtMs = atMs)) }
    suspend fun delete(taskId: String) = onCore { tasks.delete(taskId) }

    /**
     * A suggested plan for the rest of today (DayPlanner v1), making room first for habits that are behind or due,
     * and keeping meals free around a fast. Changes nothing until [applyPlan].
     */
    suspend fun planDay(): DayPlanner.Plan = onCore {
        val now = nowMs()
        val day = dayWindow(now)
        val all = tasks.all()
        DayPlanner.plan(all, visibleEvents(all), now, day, habits = goals.plannerHabits(), meals = fasting.plannerMeals(day))
    }

    /** Schedules each planned task at its suggested time; everything syncs like a manual edit. */
    suspend fun applyPlan(plan: DayPlanner.Plan) = onCore {
        plan.placements.forEach { tasks.edit(it.task.id, TaskEdit(scheduledAtMs = it.startMs)) }
    }
    suspend fun restore(taskId: String) = onCore { tasks.restore(taskId) }

    // ---- Repeating tasks and routines ----

    /** The Repeat picker for a task: "Doesn't repeat" and the presets for its day, the current one selected. */
    suspend fun repeatChoices(taskId: String): List<RepeatChoice> = onCore { tasks.repeatChoices(taskId) }
    /** Sets a repeat from [repeatChoices] (its `rule`); null stops the task repeating. */
    suspend fun setRepeat(taskId: String, rule: String?) = onCore { tasks.setRepeatRule(taskId, rule) }
    /** Skips this occurrence of a repeating task; the next one is queued for its day. */
    suspend fun skipOccurrence(taskId: String) = onCore { tasks.skipOccurrence(taskId) }
    /** Moves just this occurrence (or a one-off task) out of Today for [days] days. */
    suspend fun snooze(taskId: String, days: Int = 1) = onCore { tasks.snoozeOccurrence(taskId, days) }

    /**
     * Done or Tomorrow from the Needs you stack ([os.meka.core.domain.DecisionEffect.COMPLETE_TASK] /
     * [os.meka.core.domain.DecisionEffect.SNOOZE_TASK]); returns what the undo bar needs, or null when nothing changed
     * (the task was already done, or the effect is one the app handles: opening or setting aside).
     */
    suspend fun decide(taskId: String, effect: os.meka.core.domain.DecisionEffect): os.meka.core.domain.DecisionUndo? =
        onCore { tasks.decide(taskId, effect) }

    /** Undo for [decide]: puts the task back only while it is still as the move left it. */
    suspend fun undoDecision(undo: os.meka.core.domain.DecisionUndo): Boolean = onCore { tasks.undoDecision(undo) }

    /** Steps: a repeating task with steps is a routine, and each new occurrence brings them back unticked. */
    suspend fun addStep(taskId: String, text: String): String = onCore { tasks.addChecklistItem(taskId, text) }
    suspend fun setStepDone(stepId: String, done: Boolean) = onCore { tasks.setChecklistItemChecked(stepId, done) }
    suspend fun removeStep(stepId: String) = onCore { tasks.deleteChecklistItem(stepId) }

    /** The local day today as an epoch day, for [os.meka.core.domain.Task.repeatMeta]. */
    fun todayEpochDay(): Long = ZoneCalendar(timeZone).epochDayOf(nowMs())

    /** Event detail (calendar redesign, slice 3): when, how soon, which calendar, place, notes and a Join link. Pure. */
    fun eventDetail(event: os.meka.core.domain.CalendarEvent): os.meka.core.domain.EventDetailView =
        os.meka.core.domain.EventDetails.build(event, nowMs(), ZoneCalendar(timeZone), _eventMarks.value)

    // ---- Calendar actions (MEKA-only; the real calendars stay read-only) ----

    /** Adds the event's prep task ("Prepare for …", planned 30 min before, due at its start); returns the task id. */
    suspend fun addPrepTask(event: os.meka.core.domain.CalendarEvent): String = onCore { eventActions.addPrep(event) }
    /** Hides an event from my day (timeline, planner, brief, shutdown, review); the Calendar tab still lists it. */
    suspend fun hideEvent(eventId: String) = onCore { eventActions.hide(eventId) }
    /** Shows a hidden event in my day again (also the Undo for [hideEvent]). */
    suspend fun showEvent(eventId: String) = onCore { eventActions.show(eventId) }
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
    /** Remind me [minutes] before the event (a governor heads-up, CLOCK precision); 0 turns it off. */
    /**
     * The Fold's listener held these at work: they join the synced after-work summary (new ones only; a re-post or a
     * cleared one is skipped). [lists] marks family. Returns how many were new.
     */
    suspend fun holdCaptured(items: List<os.meka.core.domain.CapturedItem>, lists: os.meka.core.domain.PeopleLists): Int =
        onCore { held.hold(items, lists) }
    /** Done on the after-work summary: cleared on every device, texts blanked. WhatsApp and Messages are untouched. */
    suspend fun clearAfterWork(): Int = onCore { held.clear() }
    suspend fun setEventReminder(eventId: String, minutes: Int) = onCore { eventActions.setReminder(eventId, minutes) }
    /** Leave by: a heads-up [travelMinutes] before the event starts (how long it takes to get there); 0 turns it off. */
    suspend fun setEventLeaveBy(eventId: String, travelMinutes: Int) = onCore { eventActions.setLeaveBy(eventId, travelMinutes) }

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
    suspend fun moveToSomeday(taskId: String, kind: SomedayKind) = onCore { lists.moveToSomeday(taskId, kind) }
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

    suspend fun addGoal(title: String, target: String?, horizon: GoalHorizon): String = onCore { goals.addGoal(title, target, horizon) }
    suspend fun editGoal(id: String, title: String?, target: String?, horizon: GoalHorizon?) = onCore { goals.editGoal(id, title, target, horizon) }
    suspend fun setGoalHorizon(id: String, horizon: GoalHorizon) = onCore { goals.editGoal(id, horizon = horizon) }
    /** Hand-set progress (used while nothing is linked to the goal). */
    suspend fun setGoalProgress(id: String, pct: Int) = onCore { goals.setGoalProgress(id, pct) }
    suspend fun finishGoal(id: String) = onCore { goals.finishGoal(id) }
    suspend fun deleteGoal(id: String) = onCore { goals.deleteGoal(id) }
    /** Links a task to a goal (null unlinks); done tasks then count towards the goal. */
    suspend fun setTaskGoal(taskId: String, goalId: String?) = onCore { goals.setTaskGoal(taskId, goalId) }

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
    suspend fun carryOver(taskId: String) = onCore { tasks.snoozeOccurrence(taskId, 1) }
    /** "Move the rest to tomorrow": everything still left from today waits for tomorrow. */
    suspend fun carryAllToTomorrow() = onCore { shutdown.carryAllToTomorrow(dayWindow(nowMs())); Unit }
    /** Calls it a day: the shutdown card is put away on every device until tomorrow evening. */
    suspend fun shutDown() = onCore { shutdown.shutDown() }

    // ---- Morning brief ----

    /** "Got it": the brief's card is put away on every device until tomorrow morning. */
    suspend fun briefSeen() = onCore { brief.markSeen() }
    /** Shows or hides a news topic in the brief and the News place ([os.meka.core.domain.NewsTopics]); synced. */
    suspend fun setNewsTopic(topicId: String, on: Boolean) = onCore { news.setTopic(topicId, on) }

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
        _activity.value = activity.view()
    }

    /** The digest from the last [governNotifications] went out on this device: it goes in the activity log. */
    suspend fun digestPosted(digest: os.meka.core.domain.Digest) = onCore {
        activity.recordDigest(digest)
        _activity.value = activity.view()
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
    suspend fun undoActivity(id: String): String = onCore { activity.undo(id).line.also { refresh() } }

    // ---- Work mode ----

    /** The Work switch. Choosing what the schedule already says returns to the schedule. */
    suspend fun setWorkSwitch(on: Boolean) = onCore { work.setSwitch(on, localClock(), todayEpochDay()) }
    suspend fun workBackToSchedule() = onCore { work.backToSchedule() }

    /** The call assistant's one switch (synced; the Fold screens calls during work while it is on). */
    suspend fun setCallAssistant(on: Boolean) = onCore { work.setCallAssistant(on); refresh() }

    /** Work hours. [days] are ISO (1 = Monday); minutes are local minutes of the day. */
    suspend fun setWorkSchedule(days: List<Int>, startMinute: Int, endMinute: Int, enabled: Boolean) =
        onCore { work.setSchedule(WorkSchedule(days.toSet(), startMinute, endMinute, enabled)) }

    /** Fresh work-mode state for background callers (the notification listener), not waiting for a [tick]. */
    suspend fun currentWorkMode(): WorkModeState = onCore { work.state(localClock(), todayEpochDay()).also { _workMode.value = it } }

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
     * The "now" card (Fold modes, slice 3): the one thing that matters now (an event starting or just started, Up
     * next, or clear) with its one-tap actions, for the closed Fold's cover screen and the Mac's menu bar. Pure and
     * cheap, read from the current Today (which refreshes every minute). Nothing is stored.
     */
    fun coverNow(): os.meka.core.domain.NowView =
        os.meka.core.domain.CoverNowRules.now(_today.value, nowMs(), ZoneCalendar(timeZone))

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
            os.meka.core.domain.CoverNowRules.now(today, nowMs(), cal), today, _lists.value.dueCount,
            _needsYouStack.value, _fasting.value, nowMs(), cal,
        )
    }

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
            pushApi = transport as? PushApi
        }
        startSync()
    }

    val isConnected: Boolean get() = syncClient != null

    /** Begins connecting a calendar account ("google" | "microsoft"); the app opens the returned URL in a browser. */
    suspend fun startConnect(provider: String): ConnectStart =
        accountsApi?.startConnect(provider) ?: ConnectStart.Failed("Connect this device to your server first.")

    /** Accounts connected for this household, for the Calendars screen. Empty when offline or not connected. */
    suspend fun connectedAccounts(): List<ConnectedAccount> =
        try { accountsApi?.accounts() ?: emptyList() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }

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
            _sync.value = if (report.rejected.isEmpty()) SyncStatus.Synced(nowMs())
            else SyncStatus.Failing("${report.rejected.size} change(s) were refused by the server", replica.pendingPushCount())
            refresh()
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
        val allEvents = events.all()
        val dayEvents = marks.visible(allEvents)
        _today.value = project(all, dayEvents)
        _lists.value = lists.view(all, renewals.view())
        _needsYouStack.value = os.meka.core.domain.NeedsYouStackRules.build(_today.value, _lists.value.dueLine, nowMs(), ZoneCalendar(timeZone))
        _goals.value = goals.view(all)
        _fasting.value = fasting.view()
        val workState = work.state(localClock(), todayEpochDay())
        val holidays = bankHolidays.calendar()
        _workMode.value = workState
        val today = dayWindow(nowMs())
        _shutdown.value = shutdown.view(all, dayEvents, workState.schedule, workState.atWork, today, dayWindow(today.endMs), holidays)
        val notifySettings = notifyPrefs.settings()
        _notifySettings.value = notifySettings
        _brief.value = brief.view(all, dayEvents, workState.schedule, notifySettings.quiet, _lists.value, _goals.value, _fasting.value, today,
            news.all(), news.choices(), holidays)
        _newsPlace.value = news.place(nowMs())
        _review.value = review.view(reviewOffset, all, dayEvents, _goals.value, fasting.ended()) { day ->
            dayWindow(ZoneCalendar(timeZone).toEpochMs(day, 12 * 60))
        }
        _search.value = runSearch(all)
        _activity.value = activity.view()
        _calendar.value = CalendarAgenda.build(all, allEvents, nowMs(), ZoneCalendar(timeZone), hidden = marks.hidden)
        _calendarsOnToday.value = os.meka.core.domain.CalendarRules.choices(allEvents, marks.hiddenCalendars)
        _afterWork.value = held.summary()
        _notifyPreview.value = Governor.preview(currentNotices(), notifySettings, nowMs(), ZoneCalendar(timeZone))
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
            events = events.all(),
            waiting = _lists.value.waiting,
            decisions = lists.decisionItems(includeSuperseded = true),
            renewals = _lists.value.renewals.all,
            habits = _goals.value.habits,
            goals = _goals.value.goals,
        )
        return Search.run(searchQuery, sources, nowMs(), ZoneCalendar(timeZone))
    }

    /** Notices from the views as they stand (call after [refresh]). */
    private fun currentNotices() =
        NoticeSources.collect(
            _lists.value, _fasting.value, _shutdown.value, _today.value, nowMs(), ZoneCalendar(timeZone), _brief.value, _review.value.card,
            events.all(), _eventMarks.value,
        )

    private fun project(all: List<os.meka.core.domain.Task> = tasks.all(), dayEvents: List<os.meka.core.domain.CalendarEvent> = visibleEvents(all)): Today {
        val now = nowMs()
        return TodayProjection.project(all, now, dayWindow(now), dayEvents, ZoneCalendar(timeZone))
    }

    /** Calendar events minus those hidden from my day. */
    private fun visibleEvents(all: List<os.meka.core.domain.Task>) = eventActions.marks(all).visible(events.all())

    private fun localClock(): LocalClock {
        val t = Instant.fromEpochMilliseconds(nowMs()).toLocalDateTime(timeZone())
        return LocalClock(t.dayOfWeek.isoDayNumber, t.hour * 60 + t.minute)
    }

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
