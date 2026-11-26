package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: What Navilife may do's pills are rows opening sheets of two choices. */
class AutonomyChoicesTest {
    private val rules = AutonomyChoiceRules
    private fun NotifyChoiceSheet.lit() = choices.filter { it.lit }.map { it.label }
    private fun NotifyChoiceSheet.other() = choices.single { !it.lit }

    @Test
    fun theKillSwitchIsARowOpeningOffAndOn() {
        assertEquals("Off", rules.pauseRow(false).value)
        assertEquals("On", rules.pauseRow(true).value)
        assertEquals("${AutonomyRules.PAUSE_TITLE}, Off. Opens the choices", rules.pauseRow(false).spoken)
        val s = rules.pauseSheet(paused = false)
        assertEquals(listOf("Off", "On"), s.choices.map { it.label })
        assertEquals(listOf("Off"), s.lit())
        assertEquals(AutonomyRules.PAUSE_LINE, s.choices[1].line)
        assertEquals(true, rules.pauseNamed(false, CallChoiceRules.ON))
        assertEquals(false, rules.pauseNamed(true, CallChoiceRules.OFF))
        assertNull(rules.pauseNamed(true, CallChoiceRules.ON))
    }

    @Test
    fun aTrustedKindOffersAskFirstAndATurnedDownOneOffersAgain() {
        val trusted = AutonomyRules.trustRow(TrustKind.ADD_TASK, TrustState(trusted = true), paused = false)
        val s = rules.sheet(trusted, paused = false)!!
        assertEquals(TrustKind.ADD_TASK.label, s.title)
        assertEquals(listOf(AutonomyChoiceRules.DOES_IT, AutonomyRules.ASK_FIRST), s.choices.map { it.label })
        assertEquals(listOf(AutonomyChoiceRules.DOES_IT), s.lit())
        assertTrue(rules.changes(s.other().name))
        assertFalse(rules.changes(s.choices.single { it.lit }.name))

        val declined = AutonomyRules.trustRow(TrustKind.ADD_TASK, TrustState(declined = true), paused = false)
        val d = rules.sheet(declined, paused = false)!!
        assertEquals(listOf(AutonomyRules.OFFER_AGAIN, AutonomyChoiceRules.KEEP_ASKING), d.choices.map { it.label })
        assertEquals(listOf(AutonomyChoiceRules.KEEP_ASKING), d.lit())
        assertEquals(AutonomyRules.OFFER_AGAIN, d.other().label)
    }

    @Test
    fun aKindStillCountingYesesOpensNothingAndLockedRowsNeither() {
        val counting = AutonomyRules.trustRow(TrustKind.ADD_TASK, TrustState(streak = 2), paused = false)
        assertFalse(rules.opens(counting))
        assertNull(rules.sheet(counting, paused = false))
        AutonomyRules.LOCKED.forEach { r ->
            assertFalse(rules.opens(r), r.title)
            assertNull(rules.sheet(r, paused = false), r.title)
            assertTrue(rules.spoken(r).endsWith(". Locked"), r.title)
        }
        assertTrue(rules.spoken(AutonomyRules.trustRow(TrustKind.ADD_TASK, TrustState(trusted = true), false)).endsWith(". Opens the choices"))
    }

    @Test
    fun switchRowsAreOnAndOffWithTheirOwnWordsAndNoOffTwice() {
        val on = AutonomyRules.mailRow(MailAutopilotSetting(on = true, changedAtMs = null), paused = false)
        val s = rules.sheet(on, paused = false)!!
        assertEquals(listOf("On", "Off"), s.choices.map { it.label })
        assertEquals(listOf("On"), s.lit())
        assertEquals(AutonomyRules.MAIL_ON_LINE, s.choices[0].line)
        assertEquals("New mail stays where it lands", s.choices[1].line)
        assertTrue(rules.changes(s.choices[1].name))

        val finds = rules.sheet(MailFindAutoRules.row(on = false, paused = false), paused = false)!!
        assertEquals(listOf("Off"), finds.lit())
        assertEquals("Each waits in Needs you for your Add", finds.choices[1].line)
        assertTrue(rules.changes(finds.choices[0].name))

        val school = rules.sheet(SchoolAutoRules.row(on = true, paused = true), paused = true)!!
        assertEquals(listOf("On"), school.lit())
        assertEquals(AutonomyChoiceRules.PAUSED_NOTE, school.note)
        // No line in any sheet starts with Off or On: the choice's label says it.
        listOf(s, finds, school).flatMap { it.choices }.forEach { c ->
            assertFalse(c.line.startsWith("Off") || c.line.startsWith("On "), c.line)
        }
    }

    @Test
    fun addWhatYouAskForIsJustAddItOrAskFirst() {
        val on = rules.sheet(DirectAddRules.row(DirectAddSetting(askFirst = false), paused = false), paused = false)!!
        assertEquals(listOf(DirectAddRules.JUST_ADD, AutonomyRules.ASK_FIRST), on.choices.map { it.label })
        assertEquals(listOf(DirectAddRules.JUST_ADD), on.lit())
        assertEquals(AutonomyRules.ASK_FIRST, on.other().label)
        assertTrue(rules.changes(on.other().name))
        val off = rules.sheet(DirectAddRules.row(DirectAddSetting(askFirst = true), paused = false), paused = false)!!
        assertEquals(listOf(AutonomyRules.ASK_FIRST), off.lit())
        assertEquals(DirectAddRules.JUST_ADD, off.other().label)
    }

    @Test
    fun everyChangeableRowOnTheRealPageOpensASheetWithOneLitChoice() {
        val states = mapOf(TrustKind.ADD_TASK to TrustState(trusted = true), TrustKind.ADD_WIN to TrustState(declined = true))
        val sections = AutonomyRules.sections(states, false, MailAutopilotSetting(on = true, changedAtMs = null), mailFinds = false)
        sections.flatMap { it.rows }.filter { rules.opens(it) }.forEach { r ->
            val s = rules.sheet(r, paused = false) ?: error("${r.title} opens no sheet")
            assertEquals(1, s.choices.count { it.lit }, r.title)
            assertEquals(2, s.choices.size, r.title)
        }
    }
}
