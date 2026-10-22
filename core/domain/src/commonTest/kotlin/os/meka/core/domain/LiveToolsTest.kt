package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ada's Live mode, slice 5: Navilife's own tools in Live — the look-up's handles and the proposal's shape. */
class LiveToolsTest {
    private val event = AskEventRef("ev-dentist", "Dentist", 20_749, 600, 30, "google")

    @Test
    fun aLookUpKeepsEveryHandleMeaningTheSameThingForTheSession() {
        val base = AskContext(
            "2026-10-22", "Thursday 22 October 2026 · 08:00",
            listOf(AskItem("t1", AskItemKind.TASK, "Buy milk · anytime today"), AskItem("t2", AskItemKind.TASK, "Book the MOT · planned Fri 10:00")),
            mapOf("t1" to "task-milk", "t2" to "task-mot"), untrusted = false,
        )
        // The look-up's own context numbers from t1 again: the MOT keeps t2, the new task gets t3, the event e1.
        val extra = AskContext(
            "2026-10-22", "",
            listOf(
                AskItem("", AskItemKind.DAYS, "Fri 23 Oct · 1 event · 2 tasks"),
                AskItem("e1", AskItemKind.EVENT, "Fri 23 Oct 10:00–10:30 · Dentist"),
                AskItem("t1", AskItemKind.TASK, "Book the MOT · Fri 10:00"),
                AskItem("t2", AskItemKind.TASK, "Ring the garage · Fri"),
            ),
            mapOf("t1" to "task-mot", "t2" to "task-garage"), untrusted = true, eventRefs = mapOf("e1" to event),
        )
        val m = LiveToolRules.merge(base, extra)
        assertEquals(listOf("", "e1", "t2", "t3"), m.lines.map { it.ref })
        assertEquals("task-mot", m.context.taskIds["t2"])
        assertEquals("task-garage", m.context.taskIds["t3"])
        assertEquals("task-milk", m.context.taskIds["t1"])
        assertEquals(event, m.context.eventRefs["e1"])
        assertTrue(m.context.untrusted) // event titles are other people's words
        // Looking the same day up again changes nothing and still answers in full.
        val again = LiveToolRules.merge(m.context, extra)
        assertEquals(m.lines, again.lines)
        assertEquals(m.context.taskIds, again.context.taskIds)
        assertEquals(m.context.items.size, again.context.items.size)
        assertEquals(
            "days: Fri 23 Oct · 1 event · 2 tasks\nevent e1: Fri 23 Oct 10:00–10:30 · Dentist\ntask t2: Book the MOT · Fri 10:00\ntask t3: Ring the garage · Fri",
            LiveToolRules.lookUpAnswer(m.lines),
        )
        assertEquals("Nothing in his calendar or tasks for that day.", LiveToolRules.lookUpAnswer(emptyList()))
    }

    @Test
    fun aProposalIsOneOfAsksKindsWithAsksFields() {
        assertEquals(
            AskRawAction("set_timer", minutes = 20),
            LiveToolRules.raw(mapOf("kind" to "set_timer", "minutes" to "20.0")),
        )
        assertEquals(
            AskRawAction("add_task", title = "Ring the garage", date = "2026-10-23"),
            LiveToolRules.raw(mapOf("kind" to " Add_Task ", "title" to "Ring  the garage", "date" to "2026-10-23", "ref" to " ")),
        )
        assertNull(LiveToolRules.raw(mapOf("kind" to "send_email", "title" to "hi")))
        assertNull(LiveToolRules.raw(mapOf("title" to "no kind")))
        assertNull(LiveToolRules.raw(mapOf("kind" to "start_fast", "hours" to "-3"))?.hours)
    }

    @Test
    fun aLiveTurnShowsHerWordsAndTheCardsSheOffered() {
        val card = AskRules.cardOf(AskProposal.Timer(20), 20_748)
        val out = LiveToolRules.turnAnswer("Twenty minutes, on your screen to tap.", emptyList(), listOf(card, card)) as AskOutcome.Answered
        assertEquals(listOf(card), out.answer.cards) // no two alike
        val quiet = LiveToolRules.turnAnswer("  ", emptyList(), listOf(card)) as AskOutcome.Answered
        assertEquals(LiveToolRules.CARDS_ONLY, quiet.answer.text)
        assertNull(LiveToolRules.turnAnswer("", emptyList(), emptyList()))
        assertTrue(LiveToolRules.offered("Timer · 20 min").contains("Nothing has been done yet"))
    }
}
