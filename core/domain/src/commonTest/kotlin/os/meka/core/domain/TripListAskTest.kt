package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trip lists, slice 2c: Ask and Talk hear the trips' lists, and "add sun cream to my trip list" is a card. */
class TripListAskTest {
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(10, 26)
    private var n = 0
    private val fold = world.device("android")
    private val lists = TripLists(fold.replica, { "i${n++}" }, { world.clock.nowMs })

    init { world.clock.nowMs = cal.toEpochMs(mon, 10 * 60) }

    private val lisbon = TripItem("t1", "Lisbon", d(10, 28), d(11, 1), 0)
    private val rome = TripItem("t2", "Rome", d(12, 10), d(12, 14), 1)
    private val past = TripItem("t0", "Porto", d(9, 1), d(9, 5), 0)
    private val trips = listOf(rome, past, lisbon)

    private fun card(title: String?) =
        AskRules.card(AskRawAction("add_trip_item", title = title), AskContext.EMPTY, emptyMap(), world.clock.nowMs, cal, trips = trips, tripItems = lists.items())

    private fun proposal(title: String?) = card(title)?.proposal as AskProposal.AddTripItems?

    @Test
    fun askHearsEachComingTripsListsWithWhatIsLeft() {
        assertEquals(emptyList(), TripListAskRules.askLines(listOf(past), lists.items(), mon))
        lists.add("t1", "buy sun cream", TripList.PACK, tripExists = true)
        lists.add("t1", "buy plasters", TripList.PACK, tripExists = true)
        lists.add("t1", "pack 2 chargers", TripList.PACK, tripExists = true)
        lists.add("t0", "pack hats", TripList.PACK, tripExists = true)
        lists.tick(lists.items().first { it.text == "Plasters" }.id, true)
        val lines = TripListAskRules.askLines(trips, lists.items(), mon)
        assertEquals(
            listOf(
                "Trip lists · Lisbon trip · Wed 28 Oct – Sun 1 Nov · bought 1 of 2 · packed 0 of 1",
                "Lisbon trip · To buy · 1 left: Sun cream",
                "Lisbon trip · To pack · 1 left: Chargers × 2",
            ),
            lines,
        )
        lists.tick(lists.items().first { it.text == "Sun cream" }.id, true)
        assertTrue("Lisbon trip · To buy · all 2 done" in TripListAskRules.askLines(trips, lists.items(), mon))
        // In Ask's picture as trips lines, Meka's own words, so never untrusted.
        val ctx = AskRules.context(Today(emptyList(), null, emptyList(), emptyList()), world.clock.nowMs, cal, trips = TripListAskRules.askLines(trips, lists.items(), mon))
        assertTrue(ctx.items.any { it.kind == AskItemKind.TRIPS && it.line.startsWith("Trip lists · Lisbon trip") })
        assertTrue(!ctx.untrusted)
    }

    @Test
    fun addTripItemReadsEachThingLikeTheAddFieldForTheNextTrip() {
        assertTrue("add_trip_item" in AskRules.KINDS)
        val p = proposal("buy sun cream, plasters, pack 2 chargers, my sunglasses")!!
        assertEquals("t1", p.tripId)
        assertEquals(
            listOf(
                TripListEntry(TripList.BUY, "Sun cream", "Pharmacy", null, TripWho.EVERYONE),
                TripListEntry(TripList.BUY, "Plasters", "Pharmacy", null, TripWho.EVERYONE),
                TripListEntry(TripList.PACK, "Chargers", "Tech", 2, TripWho.EVERYONE),
                TripListEntry(TripList.PACK, "Sunglasses", TripListRules.groupOf(TripList.PACK, "Sunglasses", TripWho.ME), null, TripWho.ME),
            ),
            p.entries,
        )
        assertEquals("Add to Lisbon trip · To buy: sun cream and plasters · To pack: 2 chargers and sunglasses", card("buy sun cream, plasters, pack 2 chargers, my sunglasses")!!.line)
        assertEquals("Added sun cream, plasters, 2 chargers and sunglasses to Lisbon trip", AskRules.doneLine(p, mon))
        // A thing with no list word goes on To pack; "before we leave:" is a list, not a trip.
        assertEquals(TripList.PACK, proposal("passports × 4")!!.entries.single().list)
        assertEquals(TripList.LEAVE, proposal("before we leave: water the plants")!!.entries.single().list)
    }

    @Test
    fun aNamedTripIsMatchedAndAnUnknownOneIsNotGuessed() {
        assertEquals("t2", proposal("Rome: pack hats")!!.tripId)
        assertEquals("t2", proposal("the Rome trip: pack hats")!!.tripId)
        // A trip already over, or one he never typed, makes no card.
        assertNull(proposal("Porto: pack hats"))
        assertNull(proposal("Spain: pack hats"))
        assertNull(proposal(""))
        assertNull(proposal(null))
        // No trip on or coming: no card.
        assertNull(AskRules.card(AskRawAction("add_trip_item", title = "buy sun cream"), AskContext.EMPTY, emptyMap(), world.clock.nowMs, cal, trips = listOf(past)))
    }

    @Test
    fun aThingAlreadyWaitingIsLeftOffAndATickedOneComesBack() {
        lists.add("t1", "buy sun cream", TripList.PACK, tripExists = true)
        lists.add("t1", "pack hats", TripList.PACK, tripExists = true)
        lists.tick(lists.items().first { it.text == "Hats" }.id, true)
        assertNull(proposal("buy sun cream"))
        val p = proposal("buy sun cream, pack hats, pack towels")!!
        assertEquals(listOf("Hats", "Towels"), p.entries.map { it.text })
        val plan = TripListAskRules.plan("t1", lists.items(), p.entries)
        assertEquals(listOf("Towels"), plan.add.map { it.text })
        assertEquals(listOf(lists.items().first { it.text == "Hats" }.id), plan.revive)
        // At most ten things on one card.
        assertEquals(TripListAskRules.MAX_ITEMS, proposal((1..14).joinToString(", ") { "pack thing $it" })!!.entries.size)
    }

    @Test
    fun talkSaysWhatTheCardAdds() {
        val p = proposal("Lisbon: buy sun cream, pack 2 chargers")!!
        assertEquals("add sun cream and 2 chargers to the Lisbon trip's lists", TalkRules.phrase(p, mon, past = false))
    }
}
