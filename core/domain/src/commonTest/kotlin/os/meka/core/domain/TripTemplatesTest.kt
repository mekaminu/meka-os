package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trip lists, slice 2e: "Save as a template for next time", offered on a new trip's empty lists, on both devices. */
class TripTemplatesTest {
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(10, 26)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val sFold = Shopping(fold.replica, { "s${n++}" }, { world.clock.nowMs }, cal)
    private val sMac = Shopping(mac.replica, { "s${n++}" }, { world.clock.nowMs }, cal)
    private val lFold = TripLists(fold.replica, { "i${n++}" }, { world.clock.nowMs }, sFold)
    private val lMac = TripLists(mac.replica, { "i${n++}" }, { world.clock.nowMs }, sMac)

    init { world.clock.nowMs = cal.toEpochMs(mon, 10 * 60) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private val lisbon = TripItem("t", "Lisbon", d(10, 28), d(11, 1), 0)
    private val rome = TripItem("r", "Rome", d(12, 20), d(12, 23), 0)
    private val paris = TripItem("f", "Paris", d(2, 10, 2027), d(2, 14, 2027), 0)
    private val trips = listOf(lisbon, rome, paris)

    private fun fill() {
        lFold.add("t", "pack 5 t-shirts for the kids", TripList.PACK, tripExists = true, tripLabel = "Lisbon trip")
        lFold.add("t", "buy sun cream", TripList.PACK, tripExists = true, tripLabel = "Lisbon trip")
        lFold.add("t", "before we leave: put the bins out", TripList.PACK, tripExists = true, tripLabel = "Lisbon trip")
    }

    @Test
    fun savingKeepsEveryThingUntickedAndOffersItOnANewTrip() {
        fill()
        lFold.tick(lFold.items().first().id, true)
        val saved = lFold.saveTemplate(lisbon)
        assertFalse(saved.refused)
        assertEquals("Saved as a template · 3 things", saved.line)
        // The template's things are never on a trip, nor on the shopping list twice.
        assertEquals(3, lFold.items().size)
        assertEquals(1, sFold.view().toBuy.size)
        val tpl = lFold.templates().single()
        assertEquals("Lisbon", tpl.name)
        assertEquals(3, tpl.count)
        // Lisbon's own button now updates it; Rome's empty lists offer it first.
        val lv = TripListRules.view(lisbon, lFold.items(), mon, trips, templates = lFold.templates())
        assertEquals("Update the Lisbon template", lv.saveTemplate)
        assertTrue(lv.startOptions.isEmpty())
        val rv = TripListRules.view(rome, lFold.items(), mon, trips, templates = lFold.templates())
        val offer = rv.startOptions.single()
        assertEquals("Start from your Lisbon template", offer.line)
        assertEquals("3 things, all unticked", offer.sub)
        assertEquals(tpl.id, offer.templateId)
        assertEquals(offer, rv.startFrom)
        assertNull(rv.saveTemplate)
    }

    @Test
    fun startingFromATemplateCopiesItUntickedOnBothDevicesWithUndo() {
        fill()
        lFold.items().forEach { lFold.tick(it.id, true) }
        sFold.clearGot()
        val tpl = lFold.saveTemplate(lisbon).let { lFold.templates().single() }
        sync()
        assertEquals(tpl, lMac.templates().single())
        val copied = lMac.copyFromTemplate(tpl.id, rome)
        assertFalse(copied.refused)
        assertEquals("Copied 3 things from your Lisbon template", copied.line)
        val mine = lMac.items().filter { it.tripId == "r" }
        assertEquals(listOf("T-shirts", "Sun cream", "Put the bins out"), mine.map { it.text })
        assertTrue(mine.none { it.done })
        assertEquals(5, mine.first().qty)
        assertEquals(TripWho.KIDS, mine.first().who)
        assertEquals("For Rome trip", sMac.view().toBuy.single { it.id == mine[1].shopId }.meta)
        // Once Rome has lists, it is refused; Undo takes them (and the shopping item) off again.
        assertTrue(lMac.copyFromTemplate(tpl.id, rome).refused)
        sync()
        assertEquals(3, lFold.items().count { it.tripId == "r" })
        assertTrue(lMac.undoCopy(copied.token))
        assertTrue(lMac.items().none { it.tripId == "r" })
        assertTrue(sMac.view().toBuy.none { it.meta == "For Rome trip" })
        // The template is still there.
        assertEquals(3, lMac.templates().single().count)
    }

    @Test
    fun savingTheSameTripAgainReplacesItsTemplate() {
        fill()
        lFold.saveTemplate(lisbon)
        lFold.add("t", "pack passports × 4", TripList.PACK, tripExists = true, tripLabel = "Lisbon trip")
        world.clock.nowMs += 1_000
        val again = lFold.saveTemplate(lisbon)
        assertEquals("Lisbon template updated · 4 things", again.line)
        assertEquals(4, lFold.templates().single().count)
    }

    @Test
    fun newerTemplatesComeFirstAndTheLastTripStillShowsUnlessItIsATemplate() {
        fill()
        lFold.saveTemplate(lisbon)
        world.clock.nowMs += 1_000
        lFold.add("r", "pack ski gloves", TripList.PACK, tripExists = true, tripLabel = "Rome trip")
        lFold.saveTemplate(rome)
        val offers = TripListRules.view(paris, lFold.items(), mon, trips, templates = lFold.templates()).startOptions
        // Rome is newest; Rome (the last trip) is the Rome template already, so it isn't offered twice.
        assertEquals(listOf("Start from your Rome template", "Start from your Lisbon template"), offers.map { it.line })
        // Forgotten, Rome comes back as the last trip.
        assertTrue(lFold.forgetTemplate(lFold.templates().first().id))
        assertFalse(lFold.forgetTemplate("nope"))
        val after = TripListRules.view(paris, lFold.items(), mon, trips, templates = lFold.templates()).startOptions
        assertEquals(listOf("Start from your Lisbon template", "Start from Rome trip"), after.map { it.line })
        assertNull(after.last().templateId)
    }

    @Test
    fun anEmptyOrGoneTripSavesNothing() {
        assertEquals(TripListRules.TEMPLATE_EMPTY, lFold.saveTemplate(lisbon).line)
        assertEquals(TripListRules.GONE, lFold.saveTemplate(null).line)
        assertTrue(lFold.templates().isEmpty())
        assertTrue(lFold.copyFromTemplate("nope", rome).refused)
        assertNull(TripListRules.view(lisbon, lFold.items(), mon, trips).saveTemplate)
    }
}
