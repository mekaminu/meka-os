package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: an event's Remind me and Leave by are rows opening sheets, not open chip rows. */
class EventReminderChoicesTest {
    private val R = EventReminderChoiceRules
    private val start = 14 * 60

    @Test fun theRowsSayWhatIsSetAndThatTheyOpenChoices() {
        assertEquals("Remind me", R.remindRow(0).title)
        assertEquals("Off", R.remindRow(0).value)
        assertEquals("10 min before", R.remindRow(10).value)
        assertTrue(R.remindRow(10).spoken.endsWith("Opens its choices"))
        assertEquals("Leave by", R.leaveRow(30).title)
        assertEquals("30 min away", R.leaveRow(30).value)
        assertEquals("1 h away", R.leaveRow(60).value)
    }

    @Test fun theRemindSheetPutsOffFirstAndEachSaysWhenItsHeadsUpComes() {
        val s = R.remindSheet(start, listOf(5, 10, 15, 30), 10)
        assertEquals(listOf("Off", "5 min before", "10 min before", "15 min before", "30 min before"), s.choices.map { it.label })
        assertEquals(listOf("10 min before"), s.choices.filter { it.lit }.map { it.label })
        assertEquals(R.REMIND_OFF_LINE, s.choices[0].line)
        assertEquals("Heads-up at 13:50", s.choices[2].line)
        assertTrue(s.choices[2].spoken.endsWith(", chosen"))
    }

    @Test fun aSetChoiceNoLongerOfferedStaysLitAmongThem() {
        val s = R.remindSheet(start, listOf(5, 10), 30)
        assertEquals(listOf("0", "5", "10", "30"), s.choices.map { it.name })
        assertEquals("30", s.choices.single { it.lit }.name)
        assertEquals(listOf("Off"), R.remindSheet(start, emptyList(), 0).choices.filter { it.lit }.map { it.label })
    }

    @Test fun theLeaveSheetSaysWhenToLeaveAndTheAlarmOnTheSetOne() {
        val s = R.leaveSheet(start, listOf(10, 30, 60), 30, alarm = true)
        assertEquals(listOf("Off", "10 min away", "30 min away", "1 h away"), s.choices.map { it.label })
        assertEquals("Leave at 13:30 · rings as an alarm", s.choices[2].line)
        assertEquals("Leave at 13:00", s.choices[3].line)
        assertEquals(R.LEAVE_OFF_LINE, s.choices[0].line)
    }

    @Test fun anEarlyEventWrapsBackToTheDayBefore() {
        assertEquals("Heads-up at 23:55", R.remindLine(0, 5))
    }

    @Test fun aChoiceSetsItsMinutesButNotTheOneInForce() {
        val offered = listOf(5, 10, 15, 30)
        assertEquals(15, R.named(offered, 10, "15"))
        assertEquals(0, R.named(offered, 10, "0"))
        assertNull(R.named(offered, 10, "10"))
        assertNull(R.named(offered, 0, "0"))
        assertNull(R.named(offered, 10, "45"), "not offered")
        assertNull(R.named(offered, 10, "soon"))
    }
}
