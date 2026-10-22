package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Errands by location, slice 1: "when I'm near Tesco: milk, batteries", grouped by place, kept once, synced. */
class ErrandsTest {
    private val world = SyncWorld()
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun errands(dev: Device) = Errands(dev.replica, { "e${n++}" }, { world.clock.nowMs })
    private val eFold = errands(fold)
    private val eMac = errands(mac)

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun aPlaceAndItsThingsAreReadFromHowMekaSaysIt() {
        assertEquals(ErrandParse("Tesco", listOf("milk", "batteries")), ErrandRules.parse("when I'm near Tesco: milk, batteries"))
        assertEquals(ErrandParse("Tesco", listOf("milk", "batteries")), ErrandRules.parse("When I’m near tesco, milk, batteries."))
        assertEquals(ErrandParse("Tesco", listOf("milk", "batteries")), ErrandRules.parse("Tesco: milk, and batteries"))
        assertEquals(ErrandParse("Post office", listOf("parcel")), ErrandRules.parse("at the post office - parcel"))
        assertEquals(ErrandParse("Boots", listOf("plasters")), ErrandRules.parse("near Boots get plasters"))
        assertEquals(ErrandParse("Tesco", listOf("milk")), ErrandRules.parse("milk when I'm at Tesco"))
        assertEquals(ErrandParse("B&Q", listOf("fish and chips")), ErrandRules.parse("next time im passing B&Q: fish and chips"))
        // No place: a plain list, a sentence with a colon, or a place with nothing to do there.
        assertNull(ErrandRules.parse("milk, batteries"))
        assertNull(ErrandRules.parse("Remember to tell the school this: Theo is off"))
        assertNull(ErrandRules.parse("Tesco:"))
        assertNull(ErrandRules.parse("near Tesco"))
        assertNull(ErrandRules.parse("   "))
        assertEquals("Tesco", ErrandRules.placeKey("the tesco.").replaceFirstChar { it.uppercase() })
        assertEquals("milk, batteries and stamps", ErrandRules.joined(listOf("milk", "batteries", "stamps")))
    }

    @Test
    fun errandsGroupByPlaceKeepOnceAndSync() {
        val a = eFold.add("Tesco: milk, batteries")
        assertFalse(a.refused)
        assertEquals("At Tesco: milk and batteries", a.line)
        world.clock.advance(1_000)
        eFold.add("near the post office: parcel")
        world.clock.advance(1_000)
        // The same thing at the same place stays one errand; a new one joins its place.
        val again = eFold.add("when I'm at tesco: Milk, bread")
        assertEquals("At Tesco: bread", again.line)
        assertEquals("Already waiting at Tesco: milk", eFold.add("tesco: milk").line)
        val refused = eFold.add("milk, eggs")
        assertTrue(refused.refused)
        assertEquals(ErrandRules.REFUSED, refused.line)

        sync()
        val v = eMac.view()
        assertEquals("4 errands at 2 places", v.summary)
        assertEquals(listOf("Tesco", "Post office"), v.places.map { it.place })
        assertEquals("milk, batteries, bread", v.places[0].line)

        // Done on the Mac: it leaves its place for "done lately"; an empty place goes.
        val parcel = v.places[1].rows.single().id
        assertTrue(eMac.setDone(parcel, true))
        assertFalse(eMac.setDone(parcel, true))
        sync()
        val after = eFold.view()
        assertEquals("3 errands at Tesco", after.summary)
        assertEquals(listOf("parcel"), after.done.map { it.title })
        assertEquals("Post office · done today", after.done.single().meta)
        // Typing it again brings the done one back rather than making a second.
        assertEquals("At Post office: parcel", eFold.add("post office: parcel").line)
        assertEquals(parcel, eFold.view().places[1].rows.single().id)

        assertTrue(eFold.remove(parcel))
        assertFalse(eFold.remove(parcel))
        sync()
        assertEquals("3 errands at Tesco", eMac.view().summary)
        assertTrue(eMac.view().done.isEmpty())
    }

    @Test
    fun aRowReadsAsASentence() {
        assertEquals("Buy first-class stamps", ErrandRules.shown("buy first-class stamps"))
        assertEquals("Milk", ErrandRules.shown("milk"))
        assertEquals("iPhone charger", ErrandRules.shown("iPhone charger"))
        assertEquals("Return the parcel", ErrandRules.shown("Return the parcel"))
        assertEquals("2 pints of milk", ErrandRules.shown("2 pints of milk"))
        assertEquals("", ErrandRules.shown(""))
        assertEquals("Buy stamps", ErrandRow("e1", "buy stamps", null, false).shown)
    }
}
