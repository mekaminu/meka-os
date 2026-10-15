package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kids' admin, slice 5: chores and pocket money (invented children): reading lines, the week's sums, Paid, the tile. */
class KidChoresTest {
    private val cal = LocalCalendar.UTC
    private val thu = CivilDate.toEpochDay(2026, 10, 15) // Thu 15 Oct 2026
    private val mon = CivilDate.toEpochDay(2026, 10, 12)
    private val sun = CivilDate.toEpochDay(2026, 10, 18)
    private val alex = AboutChild("Alex", 7, "Oakfield School")

    private val tidy = KidChore("c-alex~tidy-room", "c-alex", "Tidy room", 100)
    private val cat = KidChore("c-alex~feed-the-cat", "c-alex", "Feed the cat", null)

    @Test
    fun choresAndAmountsAreReadOrTurnedAway() {
        assertEquals(KidChoreDraft("Tidy room", 100), KidChoreRules.readChore("tidy room £1").first)
        assertEquals(KidChoreDraft("Bins", 50), KidChoreRules.readChore("Bins 50p").first)
        assertEquals(KidChoreDraft("Hoover the stairs", 150), KidChoreRules.readChore("Hoover the stairs - £1.50 each").first)
        assertEquals(KidChoreDraft("Feed the cat", null), KidChoreRules.readChore("Feed the cat").first)
        assertEquals(KidChoreRules.NO_TITLE, KidChoreRules.readChore("£1").second)
        assertEquals(KidChoreRules.BAD_REWARD, KidChoreRules.readChore("Wash the car £50").second)
        assertEquals(KidChoreRules.BAD_REWARD, KidChoreRules.readChore("Wash the car £abc").second)
        assertEquals("c-alex~tidy-room", KidChoreRules.choreId("c-alex", "Tidy room"))

        assertEquals(500L, KidChoreRules.readPocket("£5").first)
        assertEquals(250L, KidChoreRules.readPocket("2.50 a week").first)
        assertEquals(400L, KidChoreRules.readPocket("4 pounds").first)
        assertEquals(null to null, KidChoreRules.readPocket(" "))
        assertEquals(KidChoreRules.BAD_POCKET, KidChoreRules.readPocket("lots").second)
        assertEquals(KidChoreRules.BAD_POCKET, KidChoreRules.readPocket("£500").second)
    }

    @Test
    fun nothingSetShowsNothing() {
        assertFalse(KidChoreRules.money(emptyList(), emptySet(), null, thu).shown)
        assertEquals(KidMoney.NONE, KidChoreRules.money(emptyList(), emptySet(), KidRecord("c-alex", emptyMap(), null), thu))
    }

    @Test
    fun theWeekAddsUpFromMondayWithDotsUpToToday() {
        val ticks = setOf(
            KidChoreRules.tickId(tidy.id, mon), KidChoreRules.tickId(tidy.id, mon + 2), KidChoreRules.tickId(tidy.id, thu),
            KidChoreRules.tickId(cat.id, mon + 1),
            KidChoreRules.tickId(tidy.id, mon - 1), // last Sunday: not this week
        )
        val r = KidRecord("c-alex", emptyMap(), null, pocketPence = 500)
        val m = KidChoreRules.money(listOf(tidy, cat), ticks, r, thu)
        assertTrue(m.shown)
        assertEquals("£5", m.pocketValue)
        assertEquals(listOf("Feed the cat", "Tidy room"), m.chores.map { it.title })
        assertEquals("1 this week", m.chores[0].line)
        assertEquals("£1 each · 3 this week", m.chores[1].line)
        val days = m.chores[1].days
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), days.map { it.letter })
        assertEquals(listOf(true, false, true, true, false, false, false), days.map { it.done })
        assertEquals(listOf(true, true, true, true, false, false, false), days.map { it.canTick })
        assertTrue(days[3].today)
        assertEquals("This week · £5 pocket money + £3 for chores = £8 · 4 chores done", m.weekLine)
        assertEquals("Paid £8", m.payLabel)
        assertEquals(800L, m.toPayPence)
        assertNull(m.paidLine)
        assertFalse(m.lit) // Thursday: not pocket money day

        // Paid £8 this week; a later tick adds £1 more.
        val paid = r.copy(paidWeek = mon, paidPence = 800)
        assertEquals("Paid £8 this week", KidChoreRules.money(listOf(tidy, cat), ticks, paid, thu).paidLine)
        assertNull(KidChoreRules.money(listOf(tidy, cat), ticks, paid, thu).payLabel)
        val more = KidChoreRules.money(listOf(tidy, cat), ticks + KidChoreRules.tickId(tidy.id, mon + 4), paid, sun)
        assertEquals("Paid £8 · £1 more since", more.paidLine)
        assertTrue(more.lit) // Sunday, £1 still to pay
        // Last week's payment doesn't count this week.
        assertEquals("Paid £8", KidChoreRules.money(listOf(tidy, cat), ticks, r.copy(paidWeek = mon - 7, paidPence = 800), thu).payLabel)
        // Chores with no money: just how many were done.
        assertEquals("This week · 1 chore done", KidChoreRules.money(listOf(cat), ticks, null, thu).weekLine)
        assertEquals("Not done yet this week", KidChoreRules.money(listOf(cat), emptySet(), null, thu).chores.single().line)
    }

    @Test
    fun sundayWithMoneyOwedLightsTheTileAfterFeesAndSizeChecks() {
        val owed = KidChoreRules.money(listOf(tidy), setOf(KidChoreRules.tickId(tidy.id, mon)), KidRecord("c-alex", emptyMap(), null, pocketPence = 500), sun)
        val v = KidsRules.view(listOf(alex), emptyMap(), sun, cal, money = mapOf("c-alex" to owed))
        assertEquals(LifeTileLine("Alex's pocket money · £6 to pay", true), v.tile)
        assertEquals(owed, v.kids.single().money)
        // Ask hears it.
        val lines = KidAskRules.askLines(v)
        assertTrue(lines.any { it == "Alex's chores and pocket money · pocket money £5 a week · Tidy room 1 this week · this week £5 pocket money + £1 for chores = £6 · 1 chore done · £6 still to pay" }, lines.toString())
        assertTrue(KidAskRules.isAbout("how much pocket money do I owe?", emptyList()))
    }
}
