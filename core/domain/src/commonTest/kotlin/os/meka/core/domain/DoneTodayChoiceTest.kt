package os.meka.core.domain

import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Calmer Today, slice 1: Done today is his choice (Folded · Hidden · Shown), synced, folded by default. */
class DoneTodayChoiceTest {
    private fun task(id: String) = Task(id, id, null, Lifecycle.DONE, null, null, null, 0, null, null, 0, 1L, false)
    private val two = listOf(task("Reply to audit"), task("Send the follow-up email"))
    private val ten = (1..10).map { task("t$it") }
    private val day = CivilDate.toEpochDay(2026, 10, 26)

    @Test
    fun nothingChosenIsFoldedAndClosed() {
        val v = DoneTodayRules.view(DoneTodayRules.read(null), day)
        assertEquals(DoneShow.FOLDED, v.show)
        assertFalse(v.openToday)
        assertEquals("2 done today ›", DoneTodayRules.foldLine(two, v))
        assertNull(DoneTodayRules.heading(two, v))
        assertTrue(DoneTodayRules.rows(two, v).isEmpty())
        assertNull(DoneTodayRules.moreLine(ten, v))
        assertEquals("2 done today, tap to show them", DoneTodayRules.foldLabel(two, v))
    }

    @Test
    fun foldedOpenedTodayShowsTheRowsUntilTheDayEnds() {
        val s = DoneTodaySetting(DoneShow.FOLDED, openedDay = day)
        val v = DoneTodayRules.view(s, day)
        assertEquals("2 done today · hide", DoneTodayRules.foldLine(two, v))
        assertEquals(two, DoneTodayRules.rows(two, v))
        assertEquals(8, DoneTodayRules.rows(ten, v).size)
        assertEquals("+2 more done", DoneTodayRules.moreLine(ten, v))
        assertEquals(10, DoneTodayRules.rows(ten, v, more = true).size)
        // The next day it's folded again.
        val tomorrow = DoneTodayRules.view(s, day + 1)
        assertFalse(tomorrow.openToday)
        assertTrue(DoneTodayRules.rows(two, tomorrow).isEmpty())
    }

    @Test
    fun hiddenShowsNothingShownKeepsTheHeading() {
        val hidden = DoneTodayRules.view(DoneTodaySetting(DoneShow.HIDDEN, openedDay = day), day)
        assertNull(DoneTodayRules.foldLine(two, hidden))
        assertNull(DoneTodayRules.heading(two, hidden))
        assertTrue(DoneTodayRules.rows(two, hidden).isEmpty())
        val shown = DoneTodayRules.view(DoneTodaySetting(DoneShow.SHOWN), day)
        assertNull(DoneTodayRules.foldLine(two, shown))
        assertEquals("2 done today", DoneTodayRules.heading(two, shown))
        assertEquals(two, DoneTodayRules.rows(two, shown))
    }

    @Test
    fun nothingDoneHasNoLine() {
        val v = DoneTodayChoiceView.DEFAULT
        assertNull(DoneTodayRules.foldLine(emptyList(), v))
        assertTrue(DoneTodayRules.rows(emptyList(), v).isEmpty())
    }

    @Test
    fun unknownCodeIsFolded() {
        assertEquals(DoneShow.FOLDED, DoneShow.of("X"))
        assertEquals(DoneShow.HIDDEN, DoneShow.of("H"))
        assertEquals(DoneShow.SHOWN, DoneShow.of("S"))
    }

    @Test
    fun theStoreWritesOnlyChangesAndSyncsTheFold() {
        var n = 0
        val replica = Replica("hh", "android", HlcClock("android", { 1_000L }), InMemoryReplicaStore(), MekaSchema) { "op" + n++ }
        val store = DoneTodaySettings(replica)
        assertEquals(DoneTodaySetting(), store.setting())
        assertTrue(store.setShow(DoneShow.FOLDED)) // the first write makes the entity
        assertFalse(store.setShow(DoneShow.FOLDED))
        assertTrue(store.setShow(DoneShow.HIDDEN))
        assertEquals(DoneShow.HIDDEN, store.setting().show)
        assertFalse(store.setOpen(false, day))
        assertTrue(store.setOpen(true, day))
        assertFalse(store.setOpen(true, day))
        assertEquals(day, store.setting().openedDay)
        assertEquals(DoneShow.HIDDEN, store.setting().show)
        assertTrue(store.setOpen(false, day))
        assertNull(store.setting().openedDay)
        // An old day's open is cleared by a close.
        assertTrue(store.setOpen(true, day - 1))
        assertTrue(store.setOpen(false, day))
        assertNull(store.setting().openedDay)
    }
}
