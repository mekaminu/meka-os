package os.meka.core.domain

import os.meka.core.sync.DELETED_FIELD
import os.meka.core.sync.FieldValue
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EarnedTrustTest {
    private val world = SyncWorld()
    private var n = 0
    private val a = world.device("android")
    private val m = world.device("mac")
    private val log = ActivityLog(a.replica, { "a${n++}" }, { world.clock.nowMs })
    private val tasks = Tasks(a.replica, { "t${n++}" }, { world.clock.nowMs })

    // ---- Rules ----

    @Test
    fun fiveYesesInARowBringTheOfferOnce() {
        var s = TrustState()
        repeat(TrustRules.STREAK - 1) { s = TrustRules.approved(s); assertNull(TrustRules.offer(TrustKind.ADD_TASK, s)) }
        s = TrustRules.approved(s)
        val offer = assertNotNull(TrustRules.offer(TrustKind.ADD_TASK, s))
        assertEquals("add_task", offer.kindKey)
        assertEquals("Just do it", offer.yes)
        assertEquals("Keep asking", offer.no)
        assertTrue(offer.line.startsWith("That's 5 “Add a task” cards in a row"), offer.line)
        // Answered either way, it never comes back by itself.
        assertNull(TrustRules.offer(TrustKind.ADD_TASK, TrustRules.approved(TrustRules.answered(yes = false))))
        assertNull(TrustRules.offer(TrustKind.ADD_TASK, TrustRules.approved(TrustRules.answered(yes = true))))
    }

    @Test
    fun anUndoStartsTheRunAgainAndAnAutoUndoTakesTrustAway() {
        var s = TrustState()
        repeat(4) { s = TrustRules.approved(s) }
        s = TrustRules.takenBack(s)
        assertEquals(0, s.streak)
        val trusted = TrustRules.answered(yes = true)
        assertEquals(trusted, TrustRules.takenBack(trusted))
        assertEquals(TrustState(), TrustRules.autoUndone(trusted))
    }

    @Test
    fun onlyMekasOwnListsCanEverRunOnTheirOwn() {
        assertEquals(TrustKind.ADD_TASK, TrustRules.kindOf(AskProposal.AddTask("Milk", null, null)))
        assertEquals(TrustKind.ADD_SHOPPING, TrustRules.kindOf(AskProposal.AddShopping(listOf("Milk"))))
        assertEquals(TrustKind.COMPLETE_TASK, TrustRules.kindOf(AskProposal.CompleteTask("t1", "Milk")))
        assertEquals(TrustKind.ADD_WIN, TrustRules.kindOf(AskProposal.AddWin("Shipped it")))
        assertNull(TrustRules.kindOf(AskProposal.QueueDone("q1", "BBC")))
        assertNull(TrustRules.kindOf(AskProposal.MoveTask("t1", "Milk", 3, 600)))
        assertNull(TrustRules.kindOf(AskProposal.StartFast(16)))
        assertNull(TrustRules.kindOf(AskProposal.Timer(20)))
        assertNull(TrustRules.kindOf(AskProposal.Alarm(420)))
        assertNull(TrustRules.kindOf(AskProposal.BookLeave("Annual leave", 3, 3, 1)))
        assertNull(TrustRules.kindOf(AskProposal.SkipDateNight(3)))
    }

    @Test
    fun linesReadWell() {
        assertEquals("Navilife will add tasks without asking. Undo any one to go back to asking.", TrustRules.answerLine(TrustKind.ADD_TASK, true))
        assertEquals("Navilife will keep asking before it adds to the shopping list.", TrustRules.answerLine(TrustKind.ADD_SHOPPING, false))
        assertEquals("Undone. Navilife will ask before it ticks off tasks again.", TrustRules.autoUndoneLine(TrustKind.COMPLETE_TASK, UndoOutcome.UNDONE))
        assertEquals("Added “Milk” · done for you", TrustRules.autoLine("Added “Milk”"))
        assertEquals("You said “just do it” for “Keep a win” after 5 in a row", TrustRules.why(TrustKind.ADD_WIN))
    }

    @Test
    fun talkSaysWhatMekaAlreadyDidBeforeAnythingItOffers() {
        val did = AskAutoDone(AskProposal.AddTask("Milk", null, null), "Added “Milk” · done for you", "a1")
        val timer = AskRules.cardOf(AskProposal.Timer(20), 0)
        assertEquals("Sure. Added Milk. Shall I set a timer for 20 minutes?", TalkRules.spoken(AskAnswer("Sure.", listOf(timer), listOf(did)), 0))
    }

    // ---- On a replica ----

    @Test
    fun theStreakAndTheYesSyncToTheOtherDevice() {
        val ta = EarnedTrust(a.replica)
        repeat(4) { assertNull(ta.approved(TrustKind.ADD_WIN)) }
        assertNotNull(ta.approved(TrustKind.ADD_WIN))
        ta.answer(TrustKind.ADD_WIN, yes = true)
        a.sync(); m.sync()
        val tm = EarnedTrust(m.replica)
        assertEquals(setOf(TrustKind.ADD_WIN), tm.trusted())
        tm.autoUndone(TrustKind.ADD_WIN)
        m.sync(); a.sync()
        assertTrue(ta.trusted().isEmpty())
        assertEquals(0, ta.state(TrustKind.ADD_WIN).streak)
    }

    @Test
    fun anActionRecordedAsItRunsIsUndoneExactly() {
        val (id, entry) = log.actRecorded("Added “Milk”", TrustRules.why(TrustKind.ADD_TASK), "trust:add_task", "AUTO_UNDER_RULE") {
            tasks.create(NewTask("Milk"))
        }
        val entryId = assertNotNull(entry)
        val item = log.items().single { it.id == entryId }
        assertEquals(ActivityKind.CHANGED, item.kind)
        assertEquals("trust:add_task", item.source)
        assertTrue(item.canUndo)
        // The task it made carries an explicit "not deleted", and undo deletes it.
        assertEquals(FieldValue.Bool(false), a.replica.entity(EntityTypes.TASK, id)?.get(DELETED_FIELD))
        assertEquals(UndoOutcome.UNDONE, log.undo(entryId))
        assertNull(tasks.get(id))
    }

    @Test
    fun aRecordedChangeToSomethingThatExistedPutsItsFieldsBack() {
        val id = tasks.create(NewTask("Milk"))
        val (_, entry) = log.actRecorded("Ticked off “Milk”", "why", "trust:tick_off", null) { tasks.complete(id) }
        assertEquals(Lifecycle.DONE, tasks.get(id)?.lifecycle)
        assertEquals(UndoOutcome.UNDONE, log.undo(assertNotNull(entry)))
        assertFalse(tasks.get(id)!!.lifecycle.isTerminal)
    }

    @Test
    fun recordingKeepsBeforeAndAfterButNotWhatListenersWrite() {
        val id = tasks.create(NewTask("Milk"))
        val echo = os.meka.core.sync.ReplicaListener { refs ->
            if (refs.any { it.entityId == id }) a.replica.commitLocal(EntityTypes.CONTEXT_MODE, "echo", mapOf("seen" to FieldValue.Bool(true)))
        }
        a.replica.addListener(echo)
        val (_, recorded) = a.replica.recording { tasks.edit(id, TaskEdit(title = "Oat milk")) }
        a.replica.removeListener(echo)
        val title = recorded.single { it.field == ActionableFields.TITLE }
        assertEquals(FieldValue.Text("Milk"), title.before)
        assertEquals(FieldValue.Text("Oat milk"), title.after)
        assertFalse(title.created)
        assertTrue(recorded.none { it.entityId == "echo" })
        assertEquals(FieldValue.Bool(true), a.replica.entity(EntityTypes.CONTEXT_MODE, "echo")?.get("seen"))
    }

    @Test
    fun aBlockThatChangesNothingWritesNoEntry() {
        val before = log.items().size
        val (r, entry) = log.actRecorded("Nothing", "why", "trust:win", null) { 42 }
        assertEquals(42, r)
        assertNull(entry)
        assertEquals(before, log.items().size)
    }
}
