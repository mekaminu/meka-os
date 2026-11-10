package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The approved Needs you look's compact TO DECIDE cards: icon, what, from whom, Add · Change · No. */
class NeedsYouDecideTest {
    private val cal = LocalCalendar.UTC
    private val day = CivilDate.toEpochDay(2026, 12, 14) // Mon 14 Dec 2026

    private fun card(person: String, text: String, p: RequestProposal, shared: Boolean = false) = MessageRequestRules.card(
        RequestMessage(if (shared) HandleThisRules.MESSAGE_PREFIX + "m1" else "m1", person, text, cal.toEpochMs(day, 9 * 60)), 0, p, day, cal,
    )

    @Test
    fun workFromHomeReadsAsTheMockup() {
        val c = NeedsYouDecideRules.card(card("Alex", "the Christmas show is that day",
            RequestProposal(RequestKind.WORK_FROM_HOME, "Work from home", day + 2, null)))
        assertEquals(NeedsYouDecideIcon.HOME, c.icon)
        assertEquals("Work from home · Wed 16 Dec", c.what)
        assertEquals("Alex · “the Christmas show is that day”", c.from)
        assertEquals("Add", c.yesLabel)
        assertNull(c.changeLabel) // nothing to edit on a work-from-home day
        assertEquals("No", c.noLabel)
        assertTrue(c.spoken.endsWith("Add, Not needed."))
    }

    @Test
    fun aTaskSaysTaskNotAddTask() {
        val c = NeedsYouDecideRules.card(card("Sam", "can you check the algorithm?",
            RequestProposal(RequestKind.TASK, "Check the algorithm", day, null)))
        assertEquals(NeedsYouDecideIcon.TASK, c.icon)
        assertEquals("Task: Check the algorithm · Today", c.what)
        assertEquals("Change", c.changeLabel)
        assertTrue(c.spoken.contains("Add, Change, Not a task."))
    }

    @Test
    fun eventsRemindersAndIdeasReadAsNouns() {
        assertTrue(NeedsYouDecideRules.card(card("Sam", "x", RequestProposal(RequestKind.EVENT, "Parents' evening", day + 1, 18 * 60))).what
            .startsWith("Event: Parents' evening"))
        assertTrue(NeedsYouDecideRules.card(card("Sam", "x", RequestProposal(RequestKind.REMINDER, "Call the garage", day, null))).what
            .startsWith("Reminder: Call the garage"))
        assertEquals(NeedsYouDecideIcon.REMINDER, NeedsYouDecideRules.icon(RequestKind.REMINDER))
    }

    @Test
    fun aSharedCardSaysYouShared() {
        val c = NeedsYouDecideRules.card(card("You", "Return the slip by Friday",
            RequestProposal(RequestKind.TASK, "Return the slip", day, null), shared = true))
        assertTrue(c.from.startsWith(HandleThisRules.PERSON + " · “"))
    }

    @Test
    fun aReplyKeepsItsFullCardForTheDraft() {
        val reply = card("You", "Can you confirm?", RequestProposal(RequestKind.REPLY, "Reply", null, null, reply = "Yes, that works."), shared = true)
        assertFalse(NeedsYouDecideRules.compact(reply))
        assertTrue(NeedsYouDecideRules.compact(card("Sam", "x", RequestProposal(RequestKind.TASK, "Check", day, null))))
    }

    @Test
    fun everyIconHasAPath() {
        RequestKind.entries.forEach { assertTrue(NeedsYouDecideRules.icon(it).path.startsWith("M")) }
    }
}
