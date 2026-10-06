package os.meka.core.facade

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import os.meka.core.sync.SyncTransport
import kotlinx.datetime.TimeZone
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncStatus
import os.meka.core.testing.FaultyTransport
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MekaCoreTest {
    private val service = SyncService(InMemoryServerOpStore())
    private var now = 1_790_000_000_000L

    private fun core(name: String, transport: SyncTransport = FaultyTransport(service)) = MekaCore(
        householdId = "hh", deviceId = name, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(name.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun todayUpdatesAfterCommands() = runTest {
        val c = core("android")
        assertTrue(c.today.value.isClear)
        val id = c.addTask("Call James about football")
        assertEquals(id, c.today.value.upNext?.id)
        c.complete(id)
        assertTrue(c.today.value.isClear)
        assertEquals(listOf(id), c.today.value.doneToday.map { it.id })
    }

    @Test
    fun offlineSyncReportsPendingThenRecovers() = runTest {
        val t = FaultyTransport(service).apply { online = false }
        val c = core("android", t)
        c.addTask("Buy boots")
        assertEquals(false, c.syncNow())
        val s = assertIs<SyncStatus.Offline>(c.syncStatus.value)
        assertEquals(7, s.pending) // title, lifecycle, createdAt, priority, visibility, provenance source + trust

        t.online = true
        assertEquals(true, c.syncNow())
        assertIs<SyncStatus.Synced>(c.syncStatus.value)
    }

    @Test
    fun connectAttachesSyncAndPushesLocalWorkMadeBeforeEnrolment() = runTest {
        val c = MekaCore("hh", "android", InMemoryReplicaStore(), null, Random(1), { TimeZone.of("Europe/London") }, { now })
        c.addTask("Made before enrolment")
        assertEquals(false, c.isConnected)
        c.connect(FaultyTransport(service))
        assertEquals(true, c.syncNow())
        c.stopSync()
        val other = core("mac"); other.syncNow()
        assertEquals("Made before enrolment", other.today.value.upNext?.title)
    }

    @Test
    fun conflictsAreExposedAsChoicesAndResolve() = runTest {
        val a = core("android"); val m = core("mac")
        val id = a.addTask("Call school"); a.syncNow(); m.syncNow()
        a.rename(id, "Call school re trip"); m.rename(id, "Email school re trip")
        a.syncNow(); m.syncNow(); a.syncNow()
        val choice = a.conflicts.value.single()
        assertEquals(setOf("Call school re trip", "Email school re trip"), choice.options.toSet())
        a.resolve(choice, "Email school re trip")
        a.syncNow(); m.syncNow()
        assertTrue(m.conflicts.value.isEmpty())
        assertEquals("Email school re trip", m.today.value.upNext?.title)
    }

    /** One lock for the (non-thread-safe) in-memory server, since devices run on their own dispatchers here. */
    private val serverLock = Mutex()

    /** Server-side long-poll stand-in: answers once the household has ops after the cursor, else empty after 2 s. */
    private inner class LongPollTransport(private val inner: FaultyTransport = FaultyTransport(service)) : SyncTransport {
        override suspend fun push(request: PushRequest) = serverLock.withLock { inner.push(request) }
        override suspend fun pull(request: PullRequest) = serverLock.withLock { inner.pull(request) }
        override suspend fun awaitChanges(request: PullRequest): Boolean {
            repeat(200) {
                if (serverLock.withLock { service.pull(request).ops.isNotEmpty() }) return true
                delay(10)
            }
            return false
        }
    }

    @Test
    fun anOpenAppSeesTheOtherDevicesEditWithinASecondViaLongPoll() = runTest {
        val mac = core("mac", LongPollTransport())
        // Fallback period far beyond the test: only the long-poll can deliver the change.
        mac.startSync(periodMs = 10 * 60_000)
        val phone = core("android", LongPollTransport())
        phone.addTask("From phone")
        assertTrue(phone.syncNow())
        val seen = withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000) { mac.today.first { it.upNext?.title == "From phone" } }
        }
        mac.stopSync()
        assertTrue(seen != null, "the Mac did not pick up the phone's edit via long-poll")
    }

    @Test
    fun workModeFollowsTheClockAndTheSwitchSyncs() = runTest {
        // 1_790_000_000_000 ms is Monday 21 Sept 2026, 15:13 in London (BST): inside default work hours.
        val fold = core("fold"); val mac = core("mac")
        assertTrue(fold.workMode.value.atWork)
        assertEquals("At work until 17:30", fold.workMode.value.line)

        now += 3 * 3_600_000L // 18:13
        fold.tick()
        assertEquals(false, fold.workMode.value.atWork)

        mac.setWorkSwitch(true) // evening work on the Mac
        mac.syncNow(); fold.syncNow()
        assertTrue(fold.currentWorkMode().atWork)
        assertTrue(fold.workMode.value.switchedManually)

        fold.workBackToSchedule()
        assertEquals(false, fold.workMode.value.atWork)

        fold.setWorkSchedule(listOf(1, 2, 3, 4, 5), 9 * 60, 20 * 60, true)
        assertTrue(fold.workMode.value.atWork)
        assertEquals("At work until 20:00", fold.workMode.value.line)
    }
}
