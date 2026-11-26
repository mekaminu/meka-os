package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Work mode → Messages' pills and chip rows are rows opening sheets. */
class MessagesChoicesTest {
    private fun NotifyChoiceSheet.lit() = choices.filter { it.lit }.map { it.label }
    private val alex = MessagesSetupRow("Alex", group = false, private = false, mode = null)
    private val parents = MessagesSetupRow("Football parents", group = true, private = false, mode = GroupMode.DIGEST)

    @Test
    fun aPersonIsOneRowOpeningSentOrKept() {
        assertEquals(NotifyChoiceRow("Alex", "Sent to AI", "Alex, Sent to AI. Opens the choices"), MessagesChoiceRules.personRow(alex))
        assertEquals("Kept from AI", MessagesChoiceRules.personRow(alex.copy(private = true)).value)
        val s = MessagesChoiceRules.personSheet(alex)
        assertEquals("Alex", s.title)
        assertEquals(listOf("Sent to AI", "Kept from AI"), s.choices.map { it.label })
        assertEquals(listOf("Sent to AI"), s.lit())
        assertTrue("FYI" in s.choices[1].line)
        assertTrue(s.choices[0].spoken.endsWith(", chosen"))
        assertEquals(true, MessagesChoiceRules.privateNamed(alex, MessagesChoiceRules.KEPT))
        assertNull(MessagesChoiceRules.privateNamed(alex, MessagesChoiceRules.SENT))
        assertEquals(false, MessagesChoiceRules.privateNamed(alex.copy(private = true), MessagesChoiceRules.SENT))
        assertNull(MessagesChoiceRules.privateNamed(alex, "maybe"))
    }

    @Test
    fun aGroupsModeIsARowOpeningDigestNormalIgnore() {
        assertEquals("Digest", MessagesChoiceRules.groupRow(parents).value)
        assertEquals("Digest", MessagesChoiceRules.groupRow(parents.copy(mode = null)).value)
        assertEquals("Ignore", MessagesChoiceRules.groupRow(parents.copy(mode = GroupMode.IGNORE)).value)
        val s = MessagesChoiceRules.groupSheet(parents.copy(mode = GroupMode.NORMAL))
        assertEquals(listOf("Digest", "Normal", "Ignore"), s.choices.map { it.label })
        assertEquals(listOf("Normal"), s.lit())
        assertEquals(GroupDigestRules.modeHint(GroupMode.IGNORE), s.choices[2].line)
        assertEquals(GroupMode.IGNORE, MessagesChoiceRules.modeNamed(parents, GroupMode.IGNORE.name))
        assertNull(MessagesChoiceRules.modeNamed(parents, GroupMode.DIGEST.name))
        assertNull(MessagesChoiceRules.modeNamed(parents.copy(mode = null), GroupMode.DIGEST.name))
        assertNull(MessagesChoiceRules.modeNamed(parents, "LOUD"))
    }

    @Test
    fun aGroupsAiIsItsOwnRowUnderTheMode() {
        val r = MessagesChoiceRules.groupAiRow(parents)
        assertEquals("Navilife's AI", r.title)
        assertEquals("Reads it", r.value)
        assertTrue("Football parents" in r.spoken)
        assertEquals("Kept from it", MessagesChoiceRules.groupAiRow(parents.copy(private = true)).value)
        val s = MessagesChoiceRules.groupAiSheet(parents.copy(private = true))
        assertEquals(listOf("Kept from it"), s.lit())
        assertTrue("no gist" in s.choices[1].line)
        assertEquals(false, MessagesChoiceRules.privateNamed(parents.copy(private = true), MessagesChoiceRules.SENT))
    }

    @Test
    fun markReadIsARowOpeningOnAndOff() {
        assertEquals("Off", MessagesChoiceRules.markReadRow(false).value)
        assertEquals(ChatShadeRules.SETTING_TITLE, MessagesChoiceRules.markReadRow(true).title)
        val s = MessagesChoiceRules.markReadSheet(false)
        assertEquals(listOf("On", "Off"), s.choices.map { it.label })
        assertEquals(listOf("Off"), s.lit())
        assertTrue("blue ticks" in s.choices[0].line)
        // The Off line doesn't repeat "Off" after its label.
        assertFalse(s.choices[1].line.startsWith("Off"), s.choices[1].line)
        assertTrue(s.choices[1].line.startsWith("Done only clears"), s.choices[1].line)
        assertEquals(true, MessagesChoiceRules.markReadNamed(false, MessagesChoiceRules.ON))
        assertNull(MessagesChoiceRules.markReadNamed(true, MessagesChoiceRules.ON))
        assertNull(MessagesChoiceRules.markReadNamed(false, "x"))
    }
}
