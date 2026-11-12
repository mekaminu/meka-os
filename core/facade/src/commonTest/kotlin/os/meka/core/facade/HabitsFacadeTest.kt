package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.HabitBoardRules
import os.meka.core.domain.HabitMark
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Habits screen through the facade: a habit added on the Fold into a list, marked, and seen on the Mac. */
class HabitsFacadeTest {
    private var now = 1_794_477_600_000L // Thu 12 Nov 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aHabitAddedIntoAListIsMarkedAndShowsOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val coffee = fold.addHabitToList("No shop coffee", avoid = true, list = "All day")
        val teeth = fold.addHabitToList("Brush teeth", avoid = false, list = "Night time")
        val walk = fold.addHabitToList("Long walk", avoid = false, list = "Weekend", perWeek = 2)
        val today = fold.goalsView.value.today
        fold.setHabitMark(coffee, today, HabitMark.DONE)
        fold.setHabitMark(teeth, today, HabitMark.MISSED)
        val b = fold.habitBoard()
        assertEquals(listOf("All day", "Night time", "Weekend"), b.groups.map { it.title })
        assertEquals("1 of 3 done today", b.doneLine)
        assertEquals("Avoid · 1 day streak", b.groups[0].rows.single().meta)
        assertEquals(HabitMark.MISSED, b.groups[1].rows.single().mark)
        assertEquals("before bed", b.groups[1].whenLine)
        assertEquals(walk, b.groups[2].rows.single().id)

        fold.syncNow(); mac.syncNow()
        val onMac = mac.habitBoard(list = "Night time")
        assertEquals(HabitMark.MISSED, onMac.groups.single().rows.single().mark)
        mac.setHabitMark(teeth, today, HabitMark.CLEAR)
        mac.setHabitList(walk, "Morning")
        mac.syncNow(); fold.syncNow()
        val after = fold.habitBoard()
        assertEquals(HabitMark.CLEAR, after.groups.first { it.title == "Night time" }.rows.single().mark)
        assertTrue(after.groups.none { it.title == "Weekend" })
        assertEquals(HabitBoardRules.MORNING, after.groups.first().title)
    }
}
