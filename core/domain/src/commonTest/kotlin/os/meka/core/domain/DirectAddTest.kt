package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectAddTest {
    // Saturday 17 October 2026, 10:00.
    private val today = CivilDate.toEpochDay(2026, 10, 17)
    private val now = 10 * 60

    private fun task(q: String): AskProposal.AddTask? =
        DirectAddRules.request(q)?.let { DirectAddRules.proposal(it, today, now) } as? AskProposal.AddTask

    private fun shopping(q: String): List<String>? =
        (DirectAddRules.request(q)?.let { DirectAddRules.proposal(it, today, now) } as? AskProposal.AddShopping)?.items

    @Test fun addWithTomorrow() {
        assertEquals(AskProposal.AddTask("Call the garage", today + 1, null), task("add call the garage tomorrow"))
        assertEquals(AskProposal.AddTask("Call the garage", today + 1, null), task("Add call the garage tomorrow."))
    }

    @Test fun remindMeWithDayAndTime() {
        assertEquals(AskProposal.AddTask("Ring Sam", today, 15 * 60), task("remind me to ring Sam at 3pm"))
        // Friday 23 October.
        assertEquals(AskProposal.AddTask("Book the MOT", today + 6, null), task("remind me on Friday to book the MOT"))
        assertEquals(AskProposal.AddTask("Pay the window cleaner", today + 1, null), task("remind me tomorrow to pay the window cleaner"))
    }

    @Test fun aPastTimeMeansTomorrow() {
        assertEquals(AskProposal.AddTask("Take the bins in", today + 1, 9 * 60), task("remind me to take the bins in at 9am"))
    }

    @Test fun leadingDayAndBy() {
        assertEquals(AskProposal.AddTask("Call the garage", today + 1, null), task("add tomorrow call the garage"))
        assertEquals(AskProposal.AddTask("Send the form", today + 5, null), task("I need to send the form by Thursday"))
    }

    @Test fun otherWaysOfSayingIt() {
        assertEquals(AskProposal.AddTask("Book the MOT", null, null), task("I need to book the MOT"))
        assertEquals(AskProposal.AddTask("Call the plumber", today + 1, null), task("put call the plumber on my list for tomorrow"))
        assertEquals(AskProposal.AddTask("Renew the passport", null, null), task("add a task to renew the passport"))
        assertEquals(AskProposal.AddTask("Call mum", null, null), task("hey Navilife, can you please add call mum?"))
        assertEquals(AskProposal.AddTask("Fix the gate", null, null), task("add fix the gate to my to do list"))
    }

    @Test fun shoppingItems() {
        assertEquals(listOf("milk", "eggs"), shopping("add milk and eggs to the shopping"))
        assertEquals(listOf("bread", "butter", "jam"), shopping("put bread, butter and jam on my shopping list"))
        assertEquals(listOf("bananas"), shopping("can you add some bananas to the shopping list?"))
        assertEquals(listOf("oat milk"), shopping("add to the shopping: oat milk"))
    }

    @Test fun notAnAdd() {
        listOf(
            "what's on today?", "add it", "add that", "add a meeting with Sam on Friday", "add an alarm for 6",
            "add dentist to my calendar", "add eggs to the party checklist", "add batteries to my errands",
            "remind me what I said to Sam", "is the garage open?", "add?", "read my brief", "add the goal run 5k",
            "add milk to the shopping?", "put it on my list", "Add our flight BA478 on the 14th at 7:40, ref ABC123",
            "Add a win: ran the planning session",
        ).forEach { assertNull(DirectAddRules.request(it), it) }
    }

    @Test fun nothingLeftIsNothing() {
        assertNull(task("add tomorrow"))
    }

    @Test fun lines() {
        val p = AskProposal.AddTask("Call the garage", today + 1, null)
        assertEquals("Added “Call the garage” · Tomorrow", DirectAddRules.doneLine(p, today))
        assertEquals("Added milk and eggs to shopping", DirectAddRules.doneLine(AskProposal.AddShopping(listOf("milk", "eggs")), today))
        assertEquals("added Call the garage for tomorrow", TalkRules.phrase(p, today, past = true))
    }

    @Test fun theRowAndItsSetting() {
        assertEquals(DirectAddSetting(false), DirectAddRules.read(null))
        assertEquals(DirectAddSetting(true), DirectAddRules.read(DirectAddRules.fields(true)))
        val on = DirectAddRules.row(DirectAddSetting(false), paused = false)
        assertEquals(AutonomyRules.ASK_FIRST, on.action)
        assertTrue(on.onItsOwn)
        val off = DirectAddRules.row(DirectAddSetting(true), paused = false)
        assertEquals(DirectAddRules.JUST_ADD, off.action)
        assertEquals(DirectAddRules.OFF_LINE, off.line)
        assertEquals(AutonomyRules.PAUSED_LINE, DirectAddRules.row(DirectAddSetting(false), paused = true).line)
    }
}
