package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Settings → Notifications' quiet hours and digest switches are rows opening sheets. */
class NotifyQuietChoicesTest {
    private val rules = NotifyQuietChoiceRules
    private fun NotifyChoiceSheet.lit() = choices.filter { it.lit }.map { it.label }

    @Test
    fun quietHoursIsARowSayingItsTimes() {
        assertEquals(NotifyChoiceRow("Quiet hours", "22:00–07:00", "Quiet hours, 22:00–07:00. Opens the choices"), rules.quietRow(true, "22:00–07:00"))
        assertEquals("Off", rules.quietRow(false, "22:00–07:00").value)
        val s = rules.quietSheet(enabled = false, digestsOn = true)
        assertEquals(listOf("On", "Off"), s.choices.map { it.label })
        assertEquals(listOf("Off"), s.lit())
        assertTrue("next digest" in s.choices[0].line, s.choices[0].line)
        assertTrue("any hour" in s.choices[1].line, s.choices[1].line)
        assertTrue(s.choices[1].spoken.endsWith(", chosen"))
        assertEquals(listOf("On"), rules.quietSheet(enabled = true, digestsOn = true).lit())
    }

    @Test
    fun quietHoursNeverPromisesADigestWhenBothAreOff() {
        val on = rules.quietSheet(enabled = true, digestsOn = false).choices[0].line
        assertTrue("digest" !in on, on)
        assertTrue("until they end" in on, on)
    }

    @Test
    fun eachDigestIsARowAtItsTime() {
        assertEquals(NotifyChoiceRow("Midday · 12:30", "On", "Midday · 12:30, On. Opens the choices"), rules.digestRow(NotificationSettings.MIDDAY, true))
        assertEquals("Evening · 18:00", rules.digestRow(NotificationSettings.EVENING, false).title)
        assertEquals("Off", rules.digestRow(NotificationSettings.EVENING, false).value)
        val s = rules.digestSheet(NotificationSettings.EVENING, false)
        assertEquals("Evening · 18:00", s.title)
        assertEquals(listOf("Off"), s.lit())
        assertEquals("One round-up of what's due at 18:00", s.choices[0].line)
        assertEquals("No round-up at 18:00", s.choices[1].line)
    }

    @Test
    fun aChoiceWritesOnlyWhatChanges() {
        assertEquals(true, rules.named(false, CallChoiceRules.ON))
        assertEquals(false, rules.named(true, CallChoiceRules.OFF))
        assertNull(rules.named(true, CallChoiceRules.ON))
        assertNull(rules.named(false, "later"))
    }
}
