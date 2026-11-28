package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: an event's Whose is it? and Ring as an alarm are rows opening sheets, not chips or a pill. */
class EventDetailChoicesTest {
    private val R = EventDetailChoiceRules
    private val names = listOf("Alex", "Sam")

    @Test fun whoseRowSaysWhoIsSetOrNotSet() {
        assertEquals("Whose is it?", R.whoseRow(null).title)
        assertEquals("Not set", R.whoseRow(null).value)
        assertEquals("Alex", R.whoseRow("Alex").value)
        assertTrue(R.whoseRow("Alex").spoken.endsWith("Opens its choices"))
    }

    @Test fun whoseSheetPutsNotSetFirstAndEachSaysWhatTheSeriesReads() {
        val s = R.whoseSheet(names, "Sam", "Wed 16:55")
        assertEquals(listOf("Not set", "Alex", "Sam"), s.choices.map { it.label })
        assertEquals("Every Wed 16:55 asks Alex or Sam?", s.choices[0].line)
        assertEquals("Every Wed 16:55 reads Alex", s.choices[1].line)
        assertEquals(listOf("Sam"), s.choices.filter { it.lit }.map { it.label })
        assertTrue(s.choices[2].spoken.endsWith(", chosen"))
        assertEquals(listOf("Not set"), R.whoseSheet(names, null, null).choices.filter { it.lit }.map { it.label })
        assertEquals("Every session reads Sam", R.whoseSheet(names, null, null).choices[2].line)
    }

    @Test fun aWhoseChoiceSetsOrClearsButNotTheOneInForce() {
        assertEquals(EventDetailChoiceRules.Pick("Alex"), R.whoseNamed(names, null, "Alex"))
        assertEquals(EventDetailChoiceRules.Pick(null), R.whoseNamed(names, "Alex", R.NOT_SET_NAME))
        assertNull(R.whoseNamed(names, "Alex", "Alex"))
        assertNull(R.whoseNamed(names, null, R.NOT_SET_NAME))
        assertNull(R.whoseNamed(names, null, "Jordan"), "not a child")
    }

    @Test fun ringAsAnAlarmIsAnOnOffRowWhoseChoicesSayWhatHappens() {
        assertEquals("Ring as an alarm", R.alarmRow(false).title)
        assertEquals("Off", R.alarmRow(false).value)
        assertEquals("On", R.alarmRow(true).value)
        val s = R.alarmSheet(true)
        assertEquals(listOf("On", "Off"), s.choices.map { it.label })
        assertEquals(listOf("On"), s.choices.filter { it.lit }.map { it.label })
        assertEquals(R.ALARM_ON_LINE, s.choices[0].line)
        assertEquals(R.ALARM_OFF_LINE, s.choices[1].line)
        assertEquals(false, R.alarmNamed(true, CallChoiceRules.OFF))
        assertNull(R.alarmNamed(true, CallChoiceRules.ON))
    }
}
