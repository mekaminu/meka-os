package os.meka.core.domain

import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money redesign, Plan: Obi's money check — ideas from his own payments, Remind me and Not for me. Invented statements only. */
class MoneyCheckTest {
    private val oct16 = CivilDate.toEpochDay(2026, 10, 16)
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private fun line(day: Long, pence: Long, merchant: String, c: MoneyCategory) = MoneyLine(day, pence, merchant, c)

    private val interest = listOf(
        line(d(8, 20), -6_500, "AMEX INTEREST", MoneyCategory.FEES),
        line(d(9, 20), -7_000, "AMEX INTEREST", MoneyCategory.FEES),
        line(d(10, 2), -1_200, "LATE PAYMENT CHARGE", MoneyCategory.FEES),
    )
    private val netflix = listOf(d(6, 6), d(7, 6), d(8, 6), d(9, 6)).map { line(it, -1099, "NETFLIX.COM", MoneyCategory.SUBSCRIPTIONS) } +
        line(d(10, 6), -1249, "NETFLIX.COM", MoneyCategory.SUBSCRIPTIONS)
    private val music = listOf(d(8, 3), d(9, 3), d(10, 3)).map { line(it, -1199, "TUNESTREAM", MoneyCategory.SUBSCRIPTIONS) }
    private val coffees = (0 until 12).map { line(oct16 - it, -400, "COSTA COFFEE", MoneyCategory.COFFEE) }

    @Test
    fun cardInterestIsAYearAtThatPaceWithItsPayments() {
        // £147 in the last 90 days → £147 × 365 / 90 = £596.17 → about £600 a year.
        val i = assertNotNull(MoneyCheck.interest(interest, oct16))
        assertEquals("Card interest and fees", i.title)
        assertEquals("~£600/yr", i.figure)
        assertEquals(60_000L, i.savePence)
        assertEquals("£147 in interest and fees in the last 3 months, 3 charges. Clear my cards on this tab shows how to clear what the cards owe.", i.why)
        assertEquals(listOf("Late Payment Charge", "Amex Interest", "Amex Interest"), i.behind.map { it.label })
        assertEquals("£12", i.behind.first().amount)
        assertEquals(MoneyCheck.ACT_CLEAR, i.actKey)
        // Under £10, or older than 90 days: nothing.
        assertNull(MoneyCheck.interest(listOf(line(d(10, 2), -900, "LATE PAYMENT CHARGE", MoneyCategory.FEES)), oct16))
        assertNull(MoneyCheck.interest(interest.map { it.copy(day = it.day - 100) }, oct16))
    }

    @Test
    fun aRiseAHabitAndSubscriptions() {
        val lines = netflix + music + coffees
        val regular = MoneyTips.regular(lines, oct16)
        val rise = MoneyCheck.rises(lines, regular).single()
        assertEquals("Netflix.com went up · ask for a better price", rise.title)
        assertEquals("~£18/yr", rise.figure)
        assertTrue(rise.why.startsWith("£12.49 a month from Tue 6 Oct, up from £10.99."))
        assertEquals(5, rise.behind.size)
        val habit = MoneyCheck.habits(lines, oct16).single()
        // 12 coffees, £48 in 30 days: halving saves £288 a year → about £290.
        assertEquals("Coffee 12× in 30 days", habit.title)
        assertEquals("~£290/yr", habit.figure)
        assertEquals(MoneyCheck.ACT_CAP, habit.actKey)
        val subs = assertNotNull(MoneyCheck.subs(regular))
        assertEquals("2 subscriptions · still using them all?", subs.title)
        assertEquals("£24.48/mo", subs.figure)
        assertEquals(0L, subs.savePence, "no data says he doesn't use one, so it claims no saving")
        assertTrue(subs.why.contains("£293.76 a year"))
        assertNull(MoneyCheck.subs(MoneyTips.regular(netflix, oct16)), "one subscription is not an idea")
    }

    @Test
    fun theCheckSumsTheSavingsBiggestFirstAndPutsAwayWhatHeAsked() {
        val lines = interest + netflix + music + coffees
        val regular = MoneyTips.regular(lines, oct16)
        val v = assertNotNull(MoneyCheck.view(lines, oct16, regular, MoneyIdeaState()))
        assertEquals(listOf("interest", "habit-coffee", "rise-NETFLIX.COM", "subs"), v.ideas.map { it.id })
        // £600 + £290 + £18 (the subscriptions add nothing).
        assertEquals("£908 a year", v.total)
        assertEquals("from 4 ideas", v.from)
        assertNull(v.away)
        // Not for me on the coffee, Remind me on interest until 30 Oct.
        val away = MoneyIdeaState(notFor = setOf("habit-coffee"), until = mapOf("interest" to oct16 + 14))
        val w = assertNotNull(MoneyCheck.view(lines, oct16, regular, away))
        assertEquals(listOf("rise-NETFLIX.COM", "subs"), w.ideas.map { it.id })
        assertEquals("2 put away", w.away)
        // On the day it comes back it shows again.
        assertEquals(3, MoneyCheck.view(lines, oct16 + 14, regular, away)!!.ideas.size)
        // A kind of tip hidden before counts as put away.
        val hid = MoneyCheck.view(lines, oct16, regular, MoneyIdeaState(), setOf("habit"))!!
        assertTrue(hid.ideas.none { it.kind == "habit" })
        assertEquals("1 put away", hid.away)
        // Nothing found: the empty line.
        val none = MoneyCheck.view(listOf(line(oct16, -500, "TESCO STORES", MoneyCategory.GROCERIES)), oct16, emptyList(), MoneyIdeaState())!!
        assertEquals(MoneyCheck.EMPTY, none.empty)
        assertNull(none.total)
        assertNull(MoneyCheck.view(emptyList(), oct16, emptyList(), MoneyIdeaState()))
    }

    @Test
    fun threeShowThenMore() {
        assertEquals("2 more ›", MoneyCheck.more(5, open = false))
        assertEquals(MoneyRefunds.FEWER, MoneyCheck.more(5, open = true))
        assertNull(MoneyCheck.more(3, open = false))
        // The mockup's lead: the first (biggest) idea as its own card, the next two as rows, the rest behind "2 more ›".
        val lines = interest + netflix + music + coffees
        val ideas = MoneyCheck.view(lines, oct16, MoneyTips.regular(lines, oct16), MoneyIdeaState())!!.ideas
        assertEquals("interest", MoneyCheck.lead(ideas)?.id)
        assertEquals(listOf("habit-coffee", "rise-NETFLIX.COM"), MoneyCheck.rest(ideas, open = false).map { it.id })
        assertEquals(listOf("habit-coffee", "rise-NETFLIX.COM", "subs"), MoneyCheck.rest(ideas, open = true).map { it.id })
        assertEquals("1 more ›", MoneyCheck.more(ideas.size, open = false))
        assertNull(MoneyCheck.lead(emptyList()))
        assertTrue(MoneyCheck.rest(ideas.take(1), open = false).isEmpty())
        assertTrue(MoneyCheck.validId("rise-NETFLIX.COM"))
        assertFalse(MoneyCheck.validId(""))
        assertFalse(MoneyCheck.validId("x|y"))
    }

    @Test
    fun remindAndNotForMeSyncWithUndoAndShowThemAgain() {
        var clockMs = 1L
        var opN = 0
        val replica = Replica("hh", "android", HlcClock("android", { clockMs++ }), InMemoryReplicaStore(), MekaSchema) { "op" + opN++ }
        val money = Money(replica) { 1L }
        val csv = "Date,Description,Amount\n15/09/2026,TESCO STORES,-10.00\n" + (0 until 10).joinToString("") { i ->
            val day = CivilDate.fromEpochDay(oct16 - i)
            "${day.day}/${day.month}/2026,COSTA COFFEE,-4.00\n"
        }
        assertFalse(money.import("Current account", csv).refused)
        fun view() = MoneyRules.view(money.accounts(), money.txns(), money.rules(), oct16, LocalCalendar.UTC, 0, money.hiddenTips(),
            ideaState = money.ideaState()).check!!
        assertEquals(listOf("habit-coffee"), view().ideas.map { it.id })
        val r = money.ideaRemind("habit-coffee", oct16)
        assertEquals("Obi brings it back on Fri 30 Oct", r.line)
        assertTrue(view().ideas.isEmpty())
        assertEquals("1 put away", view().away)
        assertTrue(money.hiddenTips().isEmpty(), "an idea is not a hidden kind of tip")
        assertTrue(money.undoCategory(r.undo!!))
        assertEquals(1, view().ideas.size)
        val n = money.ideaNotFor("habit-coffee")
        assertEquals(MoneyCheck.NOT_FOR_SAID, n.line)
        assertTrue(view().ideas.isEmpty())
        // Show them again brings back ideas and hidden kinds alike.
        money.hideTips("over")
        money.showIdeas()
        assertEquals(1, view().ideas.size)
        assertTrue(money.hiddenTips().isEmpty())
        assertTrue(money.ideaNotFor("bad|id").refused)
        // A month back shows no check (it's about now).
        assertNull(MoneyRules.view(money.accounts(), money.txns(), money.rules(), oct16, LocalCalendar.UTC, 1).check)
    }
}
