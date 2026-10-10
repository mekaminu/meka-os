package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared shopping list (family sharing, slice 1): adding, Got, Put back, Remove, Clear, and two devices. */
class ShoppingTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private val sat = CivilDate.toEpochDay(2026, 10, 10)
    private fun at(day: Long, h: Int) = cal.toEpochMs(day, h * 60)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun shopping(d: Device) = Shopping(d.replica, { "shop${99 - n++}" }, { world.clock.nowMs }, cal)
    private val sFold = shopping(fold)
    private val sMac = shopping(mac)

    init { world.clock.nowMs = at(sat, 10) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun oneLineAddsSeveralThingsSplitOnCommasNeverOnAnd() {
        assertEquals(listOf("Milk", "fish and chips", "eggs"), ShoppingRules.split(" Milk,  fish and chips ;eggs,, milk\n"))
        assertEquals(listOf("Fish and chips"), ShoppingRules.split("Fish   and chips"))
        assertEquals(emptyList(), ShoppingRules.split(" , ;\n"))
        assertEquals(ShoppingRules.MAX_TITLE, ShoppingRules.tidy("x".repeat(500)).length)
    }

    @Test
    fun toBuyReadsInTheOrderAddedAndARepeatNeverMakesASecondRow() {
        val first = sFold.add("Milk, eggs")
        world.clock.nowMs += 60_000
        val again = sFold.add("bread, MILK")
        assertEquals(first[0], again[1])
        val v = sFold.view()
        assertEquals(listOf("Milk", "eggs", "bread"), v.toBuy.map { it.title })
        assertEquals("3 to buy", v.line)
        assertNull(v.toBuy[0].meta) // Meka's own: no line
        assertEquals(3, v.count)
    }

    @Test
    fun gotMovesItUnderGotAndPutBackOrAddingAgainBringsItBack() {
        val (milk, eggs) = sFold.add("Milk, eggs")
        world.clock.nowMs += hour
        assertTrue(sFold.got(milk))
        assertFalse(sFold.got(milk)) // already got
        var v = sFold.view()
        assertEquals(listOf("eggs"), v.toBuy.map { it.title })
        assertEquals(listOf("Milk"), v.got.map { it.title })
        assertEquals("Got today", v.got[0].meta)
        assertEquals("1 to buy · 1 got", v.line)
        assertTrue(sFold.putBack(milk))
        assertEquals(listOf("Milk", "eggs"), sFold.view().toBuy.map { it.title }) // back in its first place
        // Got again, then typed again: the same row comes back, now at the end.
        sFold.got(milk)
        world.clock.nowMs += hour
        assertEquals(listOf(milk), sFold.add("milk"))
        v = sFold.view()
        assertEquals(listOf("eggs", "Milk"), v.toBuy.map { it.title })
        assertTrue(v.got.isEmpty())
        sFold.got(eggs)
        sFold.got(milk)
        assertEquals("All got · 2 this week", sFold.view().line)
    }

    @Test
    fun gotItemsDropOffAfterAWeekAndClearRemovesThemForGood() {
        val (milk, eggs, tea) = sFold.add("Milk, eggs, tea")
        sFold.got(milk)
        world.clock.nowMs = at(sat + 3, 9)
        sFold.got(eggs)
        assertEquals("Got Sat 10 Oct", sFold.view().got.last().meta)
        world.clock.nowMs = at(sat + 7, 9)
        assertEquals(listOf("eggs"), sFold.view().got.map { it.title }) // a week on, the first drops off
        assertEquals(1, sFold.clearGot())
        assertTrue(sFold.view().got.isEmpty())
        assertTrue(sFold.remove(tea))
        assertFalse(sFold.remove(tea))
        assertEquals("Nothing to buy", sFold.view().line)
        // A removed item is gone for good: typing it again makes a new one.
        assertFalse(sFold.add("tea").single() == tea)
    }

    @Test
    fun someoneElsesItemSaysWhoAddedItAndBothDevicesAgree() {
        val (bread) = sMac.add("Bread", by = "Jeanette")
        sync()
        val row = sFold.view().toBuy.single()
        assertEquals("Bread", row.title)
        assertEquals("jeanette", row.by)
        assertEquals("From Jeanette · today", row.meta)
        // Got on the Fold while the Mac puts it back later: the latest wins on both.
        sFold.got(bread)
        world.clock.nowMs += 60_000
        sync()
        sMac.putBack(bread)
        sync()
        assertEquals(listOf("Bread"), sFold.view().toBuy.map { it.title })
        assertEquals(sFold.view(), sMac.view())
        // A removal on one device wins over a later tick on the other.
        sFold.remove(bread)
        sMac.got(bread)
        sync()
        assertEquals(ShoppingView.EMPTY, sFold.view())
        assertEquals(ShoppingView.EMPTY, sMac.view())
    }

    @Test
    fun namesReadNaturally() {
        assertNull(ShoppingRules.byName("meka"))
        assertNull(ShoppingRules.byName(" "))
        assertEquals("Jeanette", ShoppingRules.byName("jeanette"))
        assertEquals("Nothing to buy", ShoppingRules.line(0, 0))
        assertEquals("2 to buy · 1 got", ShoppingRules.line(2, 1))
    }
}
