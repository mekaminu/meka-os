package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Work mode → Discord's Follow pills and alert chips are rows opening sheets. */
class DiscordChoicesTest {
    private fun NotifyChoiceSheet.lit() = choices.filter { it.lit }.map { it.label }
    private val followed = DiscordChannelRow("example traders#signals", "#signals · Example Traders", true, "Following · last post 14:05")
    private val other = DiscordChannelRow("example traders#chat", "#chat · Example Traders", false, "last post yesterday")

    @Test
    fun aChannelIsOneRowOpeningFollowingOrNot() {
        assertEquals(
            NotifyChoiceRow("#signals · Example Traders", "Following", "#signals · Example Traders, Following. Opens the choices"),
            DiscordChoiceRules.channelRow(followed),
        )
        assertEquals("Not followed", DiscordChoiceRules.channelRow(other).value)
        // The line under the row doesn't say Following again.
        assertEquals("Last post 14:05", DiscordChoiceRules.channelLine(followed))
        assertEquals("Last post yesterday", DiscordChoiceRules.channelLine(other))
        val s = DiscordChoiceRules.channelSheet(other)
        assertEquals("#chat · Example Traders", s.title)
        assertEquals(listOf("Following", "Not followed"), s.choices.map { it.label })
        assertEquals(listOf("Not followed"), s.lit())
        assertTrue("Needs you" in s.choices[0].line)
        assertTrue(s.choices[1].spoken.endsWith(", chosen"))
        assertEquals(true, DiscordChoiceRules.followNamed(other, DiscordChoiceRules.FOLLOW))
        assertNull(DiscordChoiceRules.followNamed(other, DiscordChoiceRules.STOP))
        assertEquals(false, DiscordChoiceRules.followNamed(followed, DiscordChoiceRules.STOP))
        assertNull(DiscordChoiceRules.followNamed(followed, "maybe"))
    }

    @Test
    fun anAlertIsARowOpeningAlertOrWait() {
        val sam = DiscordChoiceRules.alertRow("Sam", person = true, alerting = true)
        assertEquals("Sam", sam.title)
        assertEquals("Alerts at once", sam.value)
        assertEquals("Posts from Sam, Alerts at once. Opens the choices", sam.spoken)
        val stop = DiscordChoiceRules.alertRow("stop", person = false, alerting = true)
        assertEquals("“stop”", stop.title)
        assertTrue(stop.spoken.startsWith("Posts saying stop"))
        // Posting lately: someone not on the list yet.
        assertEquals("Waits on the card", DiscordChoiceRules.alertRow("Riley", person = true, alerting = false).value)
        val s = DiscordChoiceRules.alertSheet("Riley", person = true, alerting = false)
        assertEquals("Riley", s.title)
        assertEquals(listOf("Alerts at once", "Waits on the card"), s.choices.map { it.label })
        assertEquals(listOf("Waits on the card"), s.lit())
        assertTrue("A post from Riley" in s.choices[0].line)
        assertTrue("A post saying stop" in DiscordChoiceRules.alertSheet("stop", person = false, alerting = true).choices[1].line)
        assertEquals(true, DiscordChoiceRules.alertNamed(false, DiscordChoiceRules.ALERT))
        assertEquals(false, DiscordChoiceRules.alertNamed(true, DiscordChoiceRules.WAIT))
        assertNull(DiscordChoiceRules.alertNamed(true, DiscordChoiceRules.ALERT))
        assertNull(DiscordChoiceRules.alertNamed(false, "x"))
    }
}
