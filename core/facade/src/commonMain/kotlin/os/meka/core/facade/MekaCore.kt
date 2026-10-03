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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import os.meka.core.domain.CalendarEvents
import os.meka.core.domain.DayPlanner
import os.meka.core.domain.DayWindow
import os.meka.core.domain.IdGenerator
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.NewTask
import os.meka.core.domain.TaskEdit
import os.meka.core.domain.Tasks
import os.meka.core.domain.Today
import os.meka.core.domain.TodayProjection
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
    private val tasks = Tasks(replica, ids::next, nowMs)
    private val events = CalendarEvents(replica)
    private var syncClient: SyncClient? = transport?.let { SyncClient(replica, it) }
    private var accountsApi: AccountsApi? = transport as? AccountsApi

    private val _today = MutableStateFlow(project())
    val today: StateFlow<Today> = _today.asStateFlow()

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
    suspend fun complete(taskId: String) = onCore { tasks.complete(taskId) }
    suspend fun reopen(taskId: String) = onCore { tasks.reopen(taskId) }
    suspend fun rename(taskId: String, title: String) = onCore { tasks.edit(taskId, TaskEdit(title = title)) }
    suspend fun schedule(taskId: String, atMs: Long?) =
        onCore { tasks.edit(taskId, if (atMs == null) TaskEdit(clearScheduledAt = true) else TaskEdit(scheduledAtMs = atMs)) }
    suspend fun delete(taskId: String) = onCore { tasks.delete(taskId) }

    /** A suggested plan for the rest of today (DayPlanner v1). Changes nothing until [applyPlan]. */
    suspend fun planDay(): DayPlanner.Plan = onCore {
        val now = nowMs()
        DayPlanner.plan(tasks.all(), events.all(), now, dayWindow(now))
    }

    /** Schedules each planned task at its suggested time; everything syncs like a manual edit. */
    suspend fun applyPlan(plan: DayPlanner.Plan) = onCore {
        plan.placements.forEach { tasks.edit(it.task.id, TaskEdit(scheduledAtMs = it.startMs)) }
    }
    suspend fun restore(taskId: String) = onCore { tasks.restore(taskId) }

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
    }

    fun stopSync() { syncLoop?.cancel(); syncLoop = null }

    /** Attaches sync after enrolment (or swaps it), without restarting the app. Local data is kept and pushed. */
    suspend fun connect(transport: SyncTransport) = withContext(confined) {
        syncMutex.withLock { syncClient = SyncClient(replica, transport); accountsApi = transport as? AccountsApi }
        startSync()
    }

    val isConnected: Boolean get() = syncClient != null

    /** Begins connecting a calendar account ("google" | "microsoft"); the app opens the returned URL in a browser. */
    suspend fun startConnect(provider: String): ConnectStart =
        accountsApi?.startConnect(provider) ?: ConnectStart.Failed("Connect this device to your server first.")

    /** Accounts connected for this household, for the Calendars screen. Empty when offline or not connected. */
    suspend fun connectedAccounts(): List<ConnectedAccount> =
        try { accountsApi?.accounts() ?: emptyList() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }

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
        _today.value = project()
        _conflicts.value = tasks.conflicts().map { c ->
            ConflictChoice(
                taskId = c.key.entityId,
                field = c.key.field,
                options = (listOf(c.winning) + c.competing).mapNotNull { it.value.textOrNull }.distinct(),
                conflict = c,
            )
        }
    }

    private fun project(): Today {
        val now = nowMs()
        return TodayProjection.project(tasks.all(), now, dayWindow(now), events.all())
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
