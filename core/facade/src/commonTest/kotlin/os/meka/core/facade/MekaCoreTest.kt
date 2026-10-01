package os.meka.core.facade

import kotlinx.coroutines.test.runTest
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

    private fun core(name: String, transport: FaultyTransport = FaultyTransport(service)) = MekaCore(
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
}
