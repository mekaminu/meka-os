package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.TripRules
import os.meka.core.domain.WeekAheadKind
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Travel mode through the facade (slice 1): typed on the Fold, on the Mac too; Today's line and the week ahead. */
class TripsFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()
    private val day = 24 * 3_600_000L

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aTripTypedOnTheFoldShowsOnBothAppsTodayAndInTheWeekAhead() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertEquals(TripRules.EMPTY_LINE, fold.tripsView.value.summary)
        assertEquals(TripRules.NO_TRIP, fold.addTrip("Flight BA478 Wed 14 Oct 07:40").line)
        assertEquals("Added Barcelona · Wed 14 – Fri 16 Oct · in 4 days", fold.addTrip("Barcelona 14–16 Oct").line)
        assertEquals("Added to Barcelona · Flight BA478 · Wed 14 Oct 07:40 · ref ABC123", fold.addTrip("Flight BA478 Wed 14 Oct 07:40 ref ABC123").line)
        assertEquals("Next: Barcelona · Wed 14 Oct · in 4 days", fold.tripsView.value.summary)
        assertEquals("Barcelona in 4 days · Flight BA478 Wed 07:40", fold.today.value.trip?.text)

        fold.syncNow(); mac.syncNow()
        val row = mac.tripsView.value.rows.single()
        // Slice 2 of completeness: Barcelona's clock is an hour ahead; the way there is UK time, so nothing beside it.
        assertEquals("Wed 14 – Fri 16 Oct · 3 days · 1 booking · 1 h ahead of UK time", row.line)
        assertEquals(listOf("07:40"), row.timeline.single().lines.map { it.lead })
        assertEquals(listOf("Flight BA478 · ref ABC123"), row.timeline.single().lines.map { it.text })
        // The week ahead (from Saturday, next week) shows the trip and the flight on Wednesday.
        val wed = mac.weekAheadView.value.days.first { it.label.startsWith("Wed") }
        assertEquals(
            listOf("Barcelona trip · until Fri 16 Oct", "Flight BA478 · ref ABC123"),
            wed.lines.filter { it.kind == WeekAheadKind.TRIP }.map { it.text },
        )

        // Wednesday 06:00: day 1, the flight lit.
        now += 4 * day - 4 * 3_600_000L
        mac.tick()
        assertEquals("Barcelona · day 1 of 3 · Flight BA478 07:40", mac.today.value.trip?.text)
        assertEquals(true, mac.today.value.trip?.lit)
        assertEquals("On a trip: Barcelona · day 1 of 3", mac.tripsView.value.summary)

        // Removed on the Mac: gone on the Fold, and Today's line with it.
        assertTrue(mac.removeTrip(row.id))
        mac.syncNow(); fold.syncNow()
        fold.tick()
        assertTrue(fold.tripsView.value.isEmpty)
        assertNull(fold.today.value.trip)
    }
}
