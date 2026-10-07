package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NeedsYouStackTest {
    private val world = SyncWorld()
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val dayMs = CivilDate.DAY_MS
    private val hour = 3_600_000L
    private val cal = LocalCalendar.UTC

    init {
        // Wed 7 Oct 2026, 10:00 UTC.
        world.clock.nowMs = CivilDate.toEpochDay(2026, 10, 7) * dayMs + 10 * hour
    }

    private fun day() = world.clock.nowMs.floorDiv(dayMs)
    private fun window() = DayWindow(day() * dayMs, (day() + 1) * dayMs)
    private fun stack(lists: String? = null, dev: os.meka.core.testing.Device = fold) =
        NeedsYouStackRules.build(TodayProjection.project(dev.tasks.all(), world.clock.nowMs, window()), lists, world.clock.nowMs, cal)

    @Test
    fun cardsCarryTheirWhyAndTheirMoves() {
        val today = day() * dayMs
        fold.tasks.create(NewTask("Pay council tax", dueAtMs = today - dayMs + 17 * hour))
        fold.tasks.create(NewTask("Send invoice", dueAtMs = today + 8 * hour))
        fold.tasks.create(NewTask("Book MOT", dueAtMs = today - 2 * dayMs))
        fold.tasks.create(NewTask("Call the bank", dueAtMs = today + 16 * hour))

        val cards = stack("2 to chase · 1 renewal due").cards
        assertEquals(listOf("Book MOT", "Pay council tax", "Send invoice", "Call the bank", "From your lists"), cards.map { it.title })
        assertEquals("Overdue · was due Mon 5 Oct", cards[0].why)
        assertEquals("Overdue · was due yesterday 17:00", cards[1].why)
        assertEquals("Overdue · was due at 08:00", cards[2].why)
        assertEquals("Due today at 16:00 · no time planned", cards[3].why)
        assertTrue(cards[0].urgent)
        assertFalse(cards[3].urgent)

        val od = cards[0]
        assertEquals(DecisionEffect.COMPLETE_TASK, od.effect(DecisionMove.YES))
        assertEquals(DecisionEffect.SNOOZE_TASK, od.effect(DecisionMove.LATER))
        assertEquals(DecisionEffect.OPEN_TASK, od.effect(DecisionMove.OPEN))
        assertEquals("→ Done · ← Tomorrow · ↑ Open", NeedsYouStackRules.hint(od))
        assertEquals("Done · Book MOT", NeedsYouStackRules.message(od, DecisionMove.YES))
        assertEquals("Tomorrow · Book MOT", NeedsYouStackRules.message(od, DecisionMove.LATER))

        val lists = cards.last()
        assertEquals(NeedsYouStackRules.LISTS_ID, lists.id)
        assertNull(lists.taskId)
        assertEquals("2 to chase · 1 renewal due", lists.why)
        assertEquals(DecisionEffect.OPEN_LISTS, lists.yes)
        assertEquals(DecisionEffect.SET_ASIDE, lists.later)
        assertEquals("Go through", lists.yesLabel)
    }

    @Test
    fun aConflictIsChosenNotDone() {
        val id = fold.tasks.create(NewTask("Dentist"))
        fold.sync(); mac.sync()
        fold.tasks.edit(id, TaskEdit(title = "Dentist Friday"))
        mac.tasks.edit(id, TaskEdit(title = "Dentist Thursday"))
        fold.sync(); mac.sync(); fold.sync()
        val card = stack().cards.single()
        assertEquals(DecisionKind.CONFLICT, card.kind)
        assertEquals("Changed on two devices · choose which to keep", card.why)
        assertEquals("Choose", card.yesLabel)
        assertEquals(DecisionEffect.OPEN_TASK, card.yes)
        assertEquals(DecisionEffect.SET_ASIDE, card.later)
        assertNull(fold.tasks.decide(id, DecisionEffect.OPEN_TASK))
    }

    @Test
    fun setAsideCardsGoToTheBackInTheOrderSetAside() {
        val today = day() * dayMs
        listOf("A", "B", "C").forEach { fold.tasks.create(NewTask(it, dueAtMs = today + 12 * hour + it[0].code)) }
        val s = stack("1 to chase")
        val ids = s.cards.map { it.id }
        val shown = NeedsYouStackRules.ordered(s, listOf(ids[0], "gone", NeedsYouStackRules.LISTS_ID, ids[1]))
        assertEquals(listOf("C", "A", "From your lists", "B"), shown.map { it.title })
        assertEquals("3 more waiting", NeedsYouStackRules.moreLine(shown.size))
        assertNull(NeedsYouStackRules.moreLine(1))
        assertEquals(NeedsYouStack.EMPTY.cards, NeedsYouStackRules.ordered(NeedsYouStack.EMPTY, listOf("x")))
    }

    @Test
    fun doneFromTheStackAndItsUndo() {
        val id = fold.tasks.create(NewTask("Send invoice", dueAtMs = day() * dayMs + 8 * hour))
        val undo = assertNotNull(fold.tasks.decide(id, DecisionEffect.COMPLETE_TASK))
        assertEquals(Lifecycle.DONE, fold.tasks.get(id)!!.lifecycle)
        assertTrue(stack().isEmpty)
        assertTrue(fold.tasks.undoDecision(undo))
        assertEquals(Lifecycle.ACTIVE, fold.tasks.get(id)!!.lifecycle)
        assertEquals(listOf(id), stack().cards.map { it.id })
        // Undoing twice changes nothing more.
        assertFalse(fold.tasks.undoDecision(undo))
        // Done again on a done task gives nothing to undo.
        fold.tasks.complete(id)
        assertNull(fold.tasks.decide(id, DecisionEffect.COMPLETE_TASK))
    }

    @Test
    fun tomorrowFromTheStackSnoozesAndUndoPutsTheTimesBack() {
        val due = day() * dayMs - dayMs + 17 * hour
        val id = fold.tasks.create(NewTask("Pay council tax", dueAtMs = due))
        val undo = assertNotNull(fold.tasks.decide(id, DecisionEffect.SNOOZE_TASK))
        val snoozed = fold.tasks.get(id)!!
        assertEquals(day() + 1, snoozed.deferredToDay)
        assertEquals((day() + 1) * dayMs + 17 * hour, snoozed.dueAtMs)
        assertTrue(stack().isEmpty)

        assertTrue(fold.tasks.undoDecision(undo))
        val back = fold.tasks.get(id)!!
        assertNull(back.deferredToDay)
        assertEquals(due, back.dueAtMs)
        assertNull(back.scheduledAtMs)
        assertEquals(listOf(id), stack().cards.map { it.id })
    }

    @Test
    fun undoLeavesAChangeMadeSinceOnTheOtherDevice() {
        val id = fold.tasks.create(NewTask("Book MOT", dueAtMs = day() * dayMs - dayMs))
        val undo = assertNotNull(fold.tasks.decide(id, DecisionEffect.SNOOZE_TASK))
        fold.sync(); mac.sync()
        // On the Mac, Meka moves it to Friday himself.
        val friday = (day() + 2) * dayMs + 9 * hour
        mac.tasks.edit(id, TaskEdit(dueAtMs = friday))
        mac.sync(); fold.sync()
        assertFalse(fold.tasks.undoDecision(undo))
        assertEquals(friday, fold.tasks.get(id)!!.dueAtMs)
        // Both devices agree.
        assertEquals(fold.tasks.get(id)!!.dueAtMs, mac.tasks.get(id)!!.dueAtMs)
    }

    @Test
    fun aDoneOnBothDevicesOfflineIsOneDoneAndBothStacksClear() {
        val id = fold.tasks.create(NewTask("Send invoice", dueAtMs = day() * dayMs + 8 * hour))
        fold.sync(); mac.sync()
        fold.goOffline(); mac.goOffline()
        fold.tasks.decide(id, DecisionEffect.COMPLETE_TASK)
        mac.tasks.decide(id, DecisionEffect.COMPLETE_TASK)
        fold.goOnline(); mac.goOnline()
        fold.sync(); mac.sync(); fold.sync()
        assertTrue(stack(dev = fold).isEmpty)
        assertTrue(stack(dev = mac).isEmpty)
    }
}
