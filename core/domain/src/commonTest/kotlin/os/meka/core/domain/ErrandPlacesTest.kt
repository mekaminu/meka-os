package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Errands by location, slice 2: a place's spot saved on the Fold, what Android watches, and what the reminder says. */
class ErrandPlacesTest {
    private val world = SyncWorld()
    private var n = 0
    private val fold = world.device("android")
    private val errands = Errands(fold.replica, { "e${n++}" }, { world.clock.nowMs })

    @Test
    fun aSpotIsSavedFromOneCloseFixAndKeptOnThePhoneAcrossARestart() {
        val none = emptyMap<String, SavedPlace>()
        val ok = ErrandPlaceRules.save(none, "the tesco", 52.0861, -0.2614, 18.0, 1_000L)
        assertTrue(ok.saved)
        assertEquals("Saved Tesco here · reminds you within 150 m", ok.line)
        assertEquals(SavedPlace("tesco", 52.0861, -0.2614, 1_000L), ok.places["tesco"])
        // Too vague (indoors), no fix at all, or a blank fix: nothing saved.
        assertFalse(ErrandPlaceRules.save(none, "Tesco", 52.0, -0.2, 450.0, 1L).saved)
        assertEquals(ErrandPlaceRules.NO_FIX, ErrandPlaceRules.save(none, "Tesco", null, null, null, 1L).line)
        assertFalse(ErrandPlaceRules.save(none, "Tesco", 0.0, 0.0, 5.0, 1L).saved)
        // Saving again replaces it; the stored text reads back the same, and junk lines are skipped.
        val moved = ErrandPlaceRules.save(ok.places, "Tesco", 52.1, -0.27, 9.0, 2_000L).places
        val boots = ErrandPlaceRules.save(moved, "Boots", 52.09, -0.26, 30.0, 3_000L).places
        assertEquals(2, boots.size)
        assertEquals(boots, ErrandPlaceRules.decode(ErrandPlaceRules.encode(boots.values)))
        assertEquals(boots, ErrandPlaceRules.decode(ErrandPlaceRules.encode(boots.values) + "\nbad line\nx\t91\t0\t1"))
        assertTrue(ErrandPlaceRules.decode(null).isEmpty())
    }

    @Test
    fun onlySavedPlacesWithSomethingWaitingAreWatchedAndTheReminderSaysWhatWaits() {
        errands.add("Tesco: milk, batteries")
        world.clock.advance(1_000)
        errands.add("Boots: plasters")
        world.clock.advance(1_000)
        errands.add("post office: parcel")
        val saved = ErrandPlaceRules.decode("tesco\t52.08\t-0.26\t1\npost office\t52.09\t-0.25\t1\nhalfords\t52.1\t-0.2\t1")
        val v = errands.view()
        // Boots has no spot; Halfords has a spot but nothing waiting.
        assertEquals(listOf("Tesco", "Post office"), ErrandPlaceRules.toArm(v, saved).map { it.place })

        val near = ErrandPlaceRules.near(v, "tesco")!!
        assertEquals("Near Tesco", near.title)
        assertEquals("milk, batteries", near.text)
        assertEquals(2, near.ids.size)
        assertNull(ErrandPlaceRules.near(v, "halfords"))

        // Both ticked off: nothing to say, and Tesco is no longer watched.
        near.ids.forEach { errands.setDone(it, true) }
        val after = errands.view()
        assertNull(ErrandPlaceRules.near(after, "tesco"))
        assertEquals(listOf("Post office"), ErrandPlaceRules.toArm(after, saved).map { it.place })
        assertEquals("Done at Tesco · milk and batteries", ErrandPlaceRules.doneLine("Tesco", listOf("milk", "batteries")))
    }

    @Test
    fun aPlaceRemindsOnceInAFewHoursAndTheSwitchSaysWhatItNeeds() {
        assertTrue(ErrandPlaceRules.shouldRemind(null, 5_000L))
        assertFalse(ErrandPlaceRules.shouldRemind(5_000L, 5_000L + ErrandPlaceRules.REMIND_AGAIN_MS - 1))
        assertTrue(ErrandPlaceRules.shouldRemind(5_000L, 5_000L + ErrandPlaceRules.REMIND_AGAIN_MS))
        assertTrue(ErrandPlaceRules.shouldRemind(9_000L, 5_000L)) // the clock went back: don't stay silent
        val reminded = mapOf("tesco" to 12L, "boots" to 40L)
        assertEquals(reminded, ErrandPlaceRules.decodeReminded(ErrandPlaceRules.encodeReminded(reminded)))

        assertEquals("Turn on", ErrandPlaceRules.setting(false, false, false, 0).actionLabel)
        assertFalse(ErrandPlaceRules.setting(false, true, true, 2).lit)
        assertTrue(ErrandPlaceRules.setting(true, false, false, 0).lit)
        assertTrue(ErrandPlaceRules.setting(true, true, false, 0).statusLine.contains("Allow all the time"))
        assertEquals("On · watching 2 places", ErrandPlaceRules.setting(true, true, true, 2).statusLine)
        assertEquals("Forget spot", ErrandPlaceRules.placeAction(true))
        assertEquals("I'm here", ErrandPlaceRules.placeAction(false))
        assertEquals("No spot saved · tap I'm here when you're there", ErrandPlaceRules.placeLine(false))
    }
}
