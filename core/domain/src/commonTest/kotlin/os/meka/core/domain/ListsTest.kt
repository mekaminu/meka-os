package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListsTest {
    private val world = SyncWorld()
    private val day = CivilDate.DAY_MS
    private var n = 0
    private fun ids(): String = "L${n++}"
    private fun lists(d: Device) = Lists(d.replica, ::ids, { world.clock.nowMs })

    private val d = world.device("android")
    private val l = lists(d)

    private fun today() = world.clock.nowMs.floorDiv(day)
    private fun window() = DayWindow(today() * day, (today() + 1) * day)

    @Test
    fun waitingForGetsAChaseDateAndComesDueOnThatDay() {
        val id = l.addWaiting("  Refund for boots ", who = "Sports Direct")
        val item = l.waitingItems().single()
        assertEquals(id, item.id)
        assertEquals("Refund for boots", item.title)
        assertEquals(today() + 3, item.chaseDay)
        assertEquals(DueState.LATER, item.state)
        assertEquals("Sports Direct · chase ${CivilDate.shortLabel(today() + 3)}", item.meta)
        assertNull(l.view(d.tasks.all()).dueLine)

        world.clock.advance(3 * day)
        val due = l.waitingItems().single()
        assertEquals(DueState.DUE, due.state)
        assertEquals("Sports Direct · chase today", due.meta)
        assertEquals("1 to chase", l.view(d.tasks.all()).dueLine)

        world.clock.advance(day)
        assertEquals("Sports Direct · chase was due ${CivilDate.shortLabel(today() - 1)}", l.waitingItems().single().meta)
    }

    @Test
    fun chasedMovesTheNextChaseAndGotItRemovesTheItem() {
        val id = l.addWaiting("Signed form", who = "School", chaseInDays = 0)
        assertEquals(DueState.DUE, l.waitingItems().single().state)
        l.chased(id, 7)
        val after = l.waitingItems().single()
        assertEquals(today() + 7, after.chaseDay)
        assertEquals(world.clock.nowMs, after.lastChasedMs)
        l.received(id)
        assertTrue(l.waitingItems().isEmpty())
    }

    @Test
    fun undatedItemsSaySinceWhenAndSortAfterDatedOnes() {
        val a = l.addWaiting("Quote from plumber", chaseInDays = null)
        world.clock.advance(day)
        val b = l.addWaiting("Parcel", chaseInDays = 2)
        val c = l.addWaiting("Reply from Ada", who = "Ada", chaseInDays = 0)
        assertEquals(listOf(c, b, a), l.waitingItems().map { it.id })
        assertEquals("since ${CivilDate.shortLabel(today() - 1)}", l.waitingItems().last().meta)
        l.setChase(a, 1)
        assertEquals(today() + 1, l.waitingItems().first { it.id == a }.chaseDay)
        l.setChase(a, null)
        assertNull(l.waitingItems().first { it.id == a }.chaseDay)
    }

    @Test
    fun aChaseOnOneDeviceAndGotItOnAnotherEndsWithItReceived() {
        val mac = world.device("mac")
        val ml = lists(mac)
        val id = l.addWaiting("Passport back", chaseInDays = 0)
        d.sync(); mac.sync()
        d.goOffline(); mac.goOffline()
        l.chased(id)
        ml.received(id)
        d.goOnline(); mac.goOnline()
        d.syncWithRetry(); mac.syncWithRetry(); d.syncWithRetry()
        assertTrue(l.waitingItems().isEmpty())
        assertTrue(ml.waitingItems().isEmpty())
    }

    @Test
    fun somedayIsGroupedByKindAndStaysOutOfToday() {
        val trip = l.addSomeday("Lisbon long weekend", SomedayKind.TRIP)
        val idea = l.addSomeday("Garden bench from pallets")
        val book = l.addSomeday("Project Hail Mary", SomedayKind.BOOK)
        val groups = l.somedayGroups(d.tasks.all())
        assertEquals(listOf("Ideas", "Trips", "To read"), groups.map { it.label })
        assertEquals(listOf(idea), groups[0].items.map { it.id })
        val today = TodayProjection.project(d.tasks.all(), world.clock.nowMs, window())
        assertTrue(today.isClear)
        assertTrue(DayPlanner.plan(d.tasks.all(), emptyList(), world.clock.nowMs, window()).placements.isEmpty())

        l.setSomedayKind(trip, SomedayKind.PROJECT)
        assertEquals(listOf("Ideas", "Projects", "To read"), l.somedayGroups(d.tasks.all()).map { it.label })
        l.promote(book)
        assertEquals(book, TodayProjection.project(d.tasks.all(), world.clock.nowMs, window()).upNext?.id)
        assertEquals(2, l.view(d.tasks.all()).somedayCount)
    }

    @Test
    fun anOpenTaskCanMoveToSomedayButARepeatingOneCant() {
        val id = d.tasks.create(NewTask("Learn Portuguese", scheduledAtMs = world.clock.nowMs + 3_600_000))
        l.moveToSomeday(id, SomedayKind.RESEARCH)
        val t = d.tasks.get(id)!!
        assertEquals(Lifecycle.SOMEDAY, t.lifecycle)
        assertNull(t.scheduledAtMs)
        assertEquals("To look into", l.somedayGroups(d.tasks.all()).single().label)

        val r = d.tasks.create(NewTask("Vitamins"))
        d.tasks.setRepeat(r, Recurrence.Daily())
        assertFailsWith<ValidationException> { l.moveToSomeday(r) }
        assertFailsWith<ValidationException> { l.promote(r) }
    }

    @Test
    fun decisionsComeUpForReviewAndCanBeKeptRevisitedOrReplaced() {
        val id = l.recordDecision("Logan stays at Norwich U10 this season", "Coach is great; travel is fine", reviewInDays = 30)
        val item = l.decisionItems().single()
        assertEquals("Coach is great; travel is fine", item.rationale)
        assertEquals(DueState.LATER, item.state)
        assertEquals("Decided ${CivilDate.shortLabel(today())} · review ${CivilDate.shortLabel(today() + 30)}", item.meta)

        world.clock.advance(30 * day)
        assertEquals("Review due", l.decisionItems().single().meta)
        assertEquals("1 decision to review", l.view(d.tasks.all()).dueLine)

        l.keepDecision(id, 91)
        assertEquals(DueState.LATER, l.decisionItems().single().state)
        l.revisit(id)
        assertEquals("Revisiting", l.decisionItems().single().meta)
        assertEquals(DueState.DUE, l.decisionItems().single().state)

        val newId = l.replaceDecision(id, "Logan moves to the U11 squad", reviewInDays = null)
        val shown = l.decisionItems().single()
        assertEquals(newId, shown.id)
        assertEquals(id, shown.supersedesId)
        assertEquals(DecisionStatus.SUPERSEDED, l.decisionItems(includeSuperseded = true).first { it.id == id }.status)
        assertNull(l.view(d.tasks.all()).dueLine)
    }

    @Test
    fun dueLineCountsBoth() {
        l.addWaiting("A", chaseInDays = 0)
        l.addWaiting("B", chaseInDays = 0)
        l.recordDecision("C", reviewInDays = 0)
        l.recordDecision("D", reviewInDays = 0)
        assertEquals("2 to chase · 2 decisions to review", l.view(d.tasks.all()).dueLine)
        assertEquals(4, l.view(d.tasks.all()).dueCount)
    }

    @Test
    fun listsSyncToTheOtherDevice() {
        val mac = world.device("mac")
        l.addWaiting("Invoice paid", who = "Acme")
        l.addSomeday("New bike", SomedayKind.PURCHASE)
        l.recordDecision("No new car this year")
        d.sync(); mac.sync()
        val v = lists(mac).view(mac.tasks.all())
        assertEquals(listOf("Invoice paid"), v.waiting.map { it.title })
        assertEquals(listOf("To buy"), v.someday.map { it.label })
        assertEquals(listOf("No new car this year"), v.decisions.map { it.statement })
    }

    @Test
    fun validation() {
        assertFailsWith<ValidationException> { l.addWaiting("   ") }
        assertFailsWith<ValidationException> { l.addWaiting("x", chaseInDays = -1) }
        assertFailsWith<ValidationException> { l.recordDecision("") }
        assertFailsWith<ValidationException> { l.chased("nope") }
        val t = d.tasks.create(NewTask("Plain task"))
        assertFailsWith<ValidationException> { l.promote(t) }
        val dec = l.recordDecision("x")
        l.deleteDecision(dec)
        assertTrue(l.decisionItems().isEmpty())
        val w = l.addWaiting("y")
        l.editWaiting(w, who = "", notes = "called twice")
        assertNull(l.waitingItems().single().who)
        assertEquals("called twice", l.waitingItems().single().notes)
        l.deleteWaiting(w)
        assertTrue(l.waitingItems().isEmpty())
    }
}
