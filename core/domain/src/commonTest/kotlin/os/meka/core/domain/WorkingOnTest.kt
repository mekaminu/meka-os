package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ada plans with you, part 3 slice 1: the standing errands' cards, switches and Ask lines ([WorkingOnRules]). */
class WorkingOnTest {
    private val today = 20_400L

    private fun gift(title: String, inDays: Long, ideas: Int = 0, sorted: Boolean = false) = GiftRow(
        id = title, title = title, line = "in $inDays days", note = "Order by Sat 31 Oct · 3 days' delivery", noteLit = true,
        sorted = sorted, ideas = (1..ideas).map { GiftIdeaRow("i$it", "Idea $it") }, spoken = title, day = today + inDays,
    )

    private fun waiting(title: String, who: String?, state: DueState, chaseDay: Long?) = WaitingItem(
        id = title, title = title, who = who, notes = null, sinceMs = 0L, lastChasedMs = null, chaseDay = chaseDay,
        state = state, meta = "", hasConflict = false,
    )

    @Test
    fun theBirthdayKeeperSpeaksUpTenDaysAheadAndOnTheDay() {
        assertNull(WorkingOnRules.birthdays(GiftsView.EMPTY, today))
        val far = WorkingOnRules.birthdays(GiftsView(listOf(gift("Sam's birthday", 20)), "x"), today)!!
        assertEquals("Keeping 1 birthday or anniversary · next: Sam's birthday, in 20 days", far.did)
        assertNull(far.need)
        val soon = WorkingOnRules.birthdays(GiftsView(listOf(gift("Sam's birthday", 4, ideas = 2), gift("Alex's birthday", 9)), "x"), today)!!
        assertEquals("Sam's birthday · in 4 days · 2 gift ideas noted · and 1 more soon", soon.did)
        assertEquals("Choose from your ideas · order by Sat 31 Oct · 3 days' delivery", soon.need)
        assertEquals("It's today · send your wishes", WorkingOnRules.birthdays(GiftsView(listOf(gift("Sam's birthday", 0)), "x"), today)!!.need)
        // A gift already sorted needs nothing.
        assertNull(WorkingOnRules.birthdays(GiftsView(listOf(gift("Sam's birthday", 3, sorted = true)), "x"), today)!!.need)
    }

    @Test
    fun theFollowUpChaserNamesTheFirstChaseDue() {
        assertNull(WorkingOnRules.followUps(emptyList(), today))
        val calm = WorkingOnRules.followUps(listOf(waiting("Refund", "Shop", DueState.LATER, today + 1)), today)!!
        assertEquals("Waiting on 1 thing for you · next chase tomorrow", calm.did)
        assertNull(calm.need)
        val due = WorkingOnRules.followUps(listOf(waiting("Refund", "Shop", DueState.DUE, today), waiting("Photos", null, DueState.DUE, today),
            waiting("Keys", null, DueState.NONE, null)), today)!!
        assertEquals("Waiting on 3 things for you · 2 to chase now", due.did)
        assertEquals("Time to chase: Refund from Shop · and 1 more", due.need)
        assertEquals("Waiting on 1 thing for you · no chase dates set",
            WorkingOnRules.followUps(listOf(waiting("Keys", null, DueState.NONE, null)), today)!!.did)
    }

    @Test
    fun cardsThatNeedHimComeFirstAndAnErrandOffIsLeftOut() {
        val gifts = GiftsView(listOf(gift("Sam's birthday", 30)), "x")
        val w = listOf(waiting("Refund", "Shop", DueState.DUE, today))
        val v = WorkingOnRules.view(WorkingOnSetting(), RenewalsView.EMPTY, gifts, w, today)
        assertEquals(listOf(StandingErrand.FOLLOW_UPS, StandingErrand.BIRTHDAYS), v.cards.map { it.errand })
        assertEquals("Keeping an eye on 2 things · 1 needs you", v.summary)
        val off = WorkingOnRules.view(WorkingOnSetting(setOf(StandingErrand.FOLLOW_UPS)), RenewalsView.EMPTY, gifts, w, today)
        assertEquals(listOf(StandingErrand.BIRTHDAYS), off.cards.map { it.errand })
        assertEquals("Off · Obi isn't watching this", off.switches.single { it.errand == StandingErrand.FOLLOW_UPS }.line)
        val allOff = WorkingOnRules.view(WorkingOnSetting(StandingErrand.entries.toSet()), RenewalsView.EMPTY, gifts, w, today)
        assertEquals(WorkingOnRules.ALL_OFF_LINE, allOff.summary)
        assertEquals(listOf("Obi is working on · 0 standing errands on · nothing to watch yet", "Renewals watcher · off",
            "Birthday keeper · off", "Follow-up chaser · off"), WorkingOnRules.askLines(allOff, full = true))
        // Nothing to watch and nothing off: no lines at all.
        assertEquals(emptyList(), WorkingOnRules.askLines(WorkingOnRules.view(WorkingOnSetting(), RenewalsView.EMPTY, GiftsView.EMPTY, emptyList(), today), full = true))
        assertEquals(listOf("Obi is working on · 3 standing errands on · 1 needs you"), WorkingOnRules.askLines(v, full = false))
    }

    @Test
    fun whatAreYouWorkingOnIsHeardAndErrandsByPlaceAreNot() {
        listOf("What are you working on?", "what're you watching", "Anything on your standing errands?", "What's Obi doing",
            "How's the birthday keeper", "what are you keeping an eye on for me").forEach { assertTrue(WorkingOnRules.asked(it), it) }
        listOf("What errands do I have at Tesco?", "I'm working on the report", "Why is the sky blue?").forEach { assertFalse(WorkingOnRules.asked(it), it) }
    }

    @Test
    fun theSwitchesReadAsOnUntilTurnedOff() {
        assertTrue(WorkingOnRules.read(null).isOn(StandingErrand.RENEWALS))
        assertEquals(StandingErrand.FOLLOW_UPS, StandingErrand.of(" follow_ups"))
        assertNull(StandingErrand.of("boiler"))
    }

    @Test
    fun dailysRowSaysWhatNeedsHim() {
        val w = listOf(waiting("Refund", "Shop", DueState.DUE, today))
        assertEquals(DailyLine("1 needs you", true), WorkingOnRules.dailyLine(WorkingOnRules.view(WorkingOnSetting(), RenewalsView.EMPTY, GiftsView.EMPTY, w, today)))
        val calm = listOf(waiting("Refund", "Shop", DueState.LATER, today + 2))
        assertEquals(DailyLine("Watching 1 thing", false), WorkingOnRules.dailyLine(WorkingOnRules.view(WorkingOnSetting(), RenewalsView.EMPTY, GiftsView.EMPTY, calm, today)))
        assertNull(WorkingOnRules.dailyLine(WorkingOnView.EMPTY))
        assertEquals(DailyLine("All off", false), WorkingOnRules.dailyLine(WorkingOnRules.view(WorkingOnSetting(StandingErrand.entries.toSet()), RenewalsView.EMPTY, GiftsView.EMPTY, w, today)))
        val i = DailyInputs(nowMinute = 600, workingOn = DailyLine("1 needs you", true))
        assertEquals(DailyLine("1 needs you", true), DailyRules.lines(i).of(DailyRow.WORKING_ON))
    }
}
