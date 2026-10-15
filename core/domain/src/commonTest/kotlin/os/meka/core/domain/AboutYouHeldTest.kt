package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** About you, slice 2: each app marks once that it holds the profile; Health's row and the publish job's gate line. */
class AboutYouHeldTest {
    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 09:00 UTC
    private var n = 0
    private fun store() = AboutYouStore(
        Replica("hh", "android", HlcClock("android", { now }), InMemoryReplicaStore(), MekaSchema) { "op" + n++ }, { now })

    private fun utc(ms: Long) = "t$ms"

    @Test
    fun markingNeverCreatesOrFillsAProfile() {
        val s = store()
        assertFalse(s.markHeld(AboutYouDevice.MAC))
        assertFalse(s.exists())
        assertNull(s.held())
        s.set(AboutField.HOME, "Hilltown")
        s.set(AboutField.HOME, "")
        // A profile with nothing in it: still no mark.
        assertFalse(s.markHeld(AboutYouDevice.FOLD))
        assertEquals(AboutYouHeld(false, null, null, clubs = false, council = false, route = false), s.held())
    }

    @Test
    fun eachAppMarksOnceAndTheMarkIsNotADetail() {
        val s = store()
        s.set(AboutField.HOME, "Hilltown")
        assertTrue(s.markHeld(AboutYouDevice.FOLD))
        val first = now
        now += 60_000
        assertFalse(s.markHeld(AboutYouDevice.FOLD))
        assertTrue(s.markHeld(AboutYouDevice.MAC))
        assertEquals(AboutYouHeld(true, first, now, clubs = false, council = false, route = false), s.held())
        assertTrue(s.held()!!.onBoth)
        // The marks don't make an otherwise empty profile count as filled, nor show in the pane.
        assertTrue(AboutYouRules.read(mapOf(AboutYouFields.HELD_FOLD to FieldValue.Int64(1))).isEmpty)
        assertEquals(AboutYouFields.HELD_MAC, AboutYouRules.heldField(AboutYouDevice.MAC))
    }

    @Test
    fun healthRowSaysWhichAppIsMissingIt() {
        val cal = LocalCalendar.UTC
        assertNull(AboutYouRules.healthRow(null, now, cal))
        assertEquals(HealthState.WARN, AboutYouRules.healthRow(AboutYouHeld(false, now, now), now, cal)!!.state)
        val fold = AboutYouRules.healthRow(AboutYouHeld(true, now, null), now + 7_200_000, cal)!!
        assertEquals("On the Fold since 09:00 · not on the Mac yet · open Navilife on the Mac", fold.line)
        assertEquals(HealthState.WARN, fold.state)
        assertEquals("On the Mac since 09:00 · not on the Fold yet · open Navilife on the Fold",
            AboutYouRules.healthRow(AboutYouHeld(true, null, now), now + 7_200_000, cal)!!.line)
        assertEquals("On the Fold · not on the Mac yet · open Navilife on the Mac", AboutYouRules.healthRow(AboutYouHeld(true, now, null), now, cal)!!.line)
        assertEquals(HealthState.UNKNOWN, AboutYouRules.healthRow(AboutYouHeld(true, null, null), now, cal)!!.state)
        val both = AboutYouRules.healthRow(AboutYouHeld(true, 1L, 2L), now, cal)!!
        assertEquals(HealthState.OK, both.state)
        assertEquals(AboutYouRules.TITLE, both.title)
    }

    @Test
    fun theGateLineForABuildRun() {
        assertEquals("no profile yet (release 1's copy hasn't run)", AboutYouRules.gateLine(null, ::utc))
        assertEquals("the profile is empty", AboutYouRules.gateLine(AboutYouHeld(false, 1, 2), ::utc))
        assertEquals("on the Fold since t1 · NOT on the Mac yet · release 2 waits", AboutYouRules.gateLine(AboutYouHeld(true, 1, null), ::utc))
        assertEquals("on the Fold since t1 · on the Mac since t2 · release 2 can start", AboutYouRules.gateLine(AboutYouHeld(true, 1, 2), ::utc))
        assertEquals("on the Fold since t1 · on the Mac since t2 · release 2 can start · clubs copied · council dates NOT copied yet",
            AboutYouRules.gateLine(AboutYouHeld(true, 1, 2, clubs = true, council = false), ::utc))
    }

    @Test
    fun healthKeepsTheRowOffToday() {
        val f = HealthFacts(
            device = DeviceHealth(mac = true), connected = true, lastSyncedMs = now, server = null, accounts = emptyList(),
            ai = null, callAssistantOn = false, nowMs = now, aboutYou = AboutYouHeld(true, now, null),
        )
        val v = HealthRules.view(f, LocalCalendar.UTC)
        assertEquals(HealthState.WARN, v.rows.first { it.key == AboutYouRules.HEALTH_KEY }.state)
        assertFalse(v.todayLine.orEmpty().startsWith(AboutYouRules.TITLE))
    }
}
