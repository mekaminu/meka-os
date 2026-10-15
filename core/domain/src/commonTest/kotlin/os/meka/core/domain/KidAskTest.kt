package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kids' admin, slice 4: Ask and Talk know the children (invented children, school and club). */
class KidAskTest {
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val today = CivilDate.toEpochDay(2026, 10, 15) // Thu 15 Oct 2026
    private val alex = AboutChild("Alex", 7, "Oakfield School")
    private val sam = AboutChild("Sam", 10, null)

    private fun view(): KidsView {
        val checked = cal.toEpochMs(today - 1, 600)
        val records = mapOf("c-alex" to KidRecord("c-alex", mapOf(KidSize.SHOES to "13", KidSize.TOPS to "7–8"), checked))
        val fee = RenewalItem(
            id = "f1", title = "Football", kind = ObligationKind.BILL, subject = null, notes = null, dueDay = CivilDate.toEpochDay(2026, 11, 1),
            cancelByDay = null, leadDays = 3, costPence = 3500, repeats = RenewalRepeat.MONTHLY, repeatLabel = RenewalRules.repeatLabel(RenewalRepeat.MONTHLY),
            state = RenewalRules.state(CivilDate.toEpochDay(2026, 11, 1), null, 3, today), meta = "", doneLabel = "Paid", stopLabel = "Stop tracking",
            hasConflict = false, forChild = "c-sam",
        )
        val week = KidWeek("2 things in the next 7 days · 1 today or tomorrow", listOf(
            KidWeekDay(today + 1, "Tomorrow", listOf(KidWeekItem("Alex: PE kit", true, "Tomorrow. Alex: PE kit"))),
            KidWeekDay(today + 3, "Sun 18 Oct", listOf(KidWeekItem("Sam: Match U10s 10:00", false, "", fromCalendar = true))),
        ))
        return KidsRules.view(listOf(alex, sam), records, today, cal, listOf(fee)).copy(week = week)
    }

    @Test
    fun onlyQuestionsAboutTheChildrenCarryThem() {
        val names = listOf("Alex", "Sam")
        assertTrue(KidAskRules.isAbout("What size shoes is Alex?", names))
        assertTrue(KidAskRules.isAbout("what do the boys need this week", names))
        assertTrue(KidAskRules.isAbout("when's Sam's football fee due?", names))
        assertFalse(KidAskRules.isAbout("When's the MOT?", names))
        assertFalse(KidAskRules.isAbout("Alexander Road traffic?", names))
        // The bundled help doesn't swallow them before the AI (or the device's own answer) sees them.
        listOf("What size shoes is Alex?", "what coat size does Sam take", "What do the boys need this week?").forEach { q ->
            assertTrue(HelpRules.lookup(q)?.strong != true, q)
        }
    }

    @Test
    fun thePaneAsLines() {
        val lines = KidAskRules.askLines(view())
        assertEquals(listOf(
            "Kids · 2 children: Alex and Sam",
            "Alex · ${AboutYouRules.childLine(alex)} · sizes: Shoes 13 · Tops 7–8 · Sizes checked Wed 14 Oct",
            "Sam · ${AboutYouRules.childLine(sam)} · no sizes kept",
            "Sam's club fee · Football · £35 · every month · next Sun 1 Nov",
            "This week · 2 things in the next 7 days · 1 today or tomorrow",
            "Tomorrow (Fri 16 Oct) · Alex: PE kit",
            "Sun 18 Oct · Sam: Match U10s 10:00",
        ), lines)
        assertTrue(KidAskRules.fromOthers(view()))
        assertEquals(emptyList(), KidAskRules.askLines(KidsView.EMPTY))
    }

    @Test
    fun theDeviceAnswersSizesAndTheWeekItself() {
        val v = view()
        assertEquals("Alex's shoes: 13 · sizes checked Wed 14 Oct.", KidAskRules.localAnswer("What size shoes is Alex?", v))
        assertEquals("Alex's football boots size isn't kept yet · add it in Ask → Life → Kids.", KidAskRules.localAnswer("what size football boots does Alex take", v))
        assertEquals(
            "2 things in the next 7 days · 1 today or tomorrow. Tomorrow: Alex: PE kit; Sun 18 Oct: Sam: Match U10s 10:00.",
            KidAskRules.localAnswer("What do the boys need this week?", v),
        )
        assertNull(KidAskRules.localAnswer("When's the MOT?", v))
        assertNull(KidAskRules.localAnswer("What size shoes is Alex?", KidsView.EMPTY))
    }
}
