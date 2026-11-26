package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Work mode → Leaving now's pill and chip rows are rows opening sheets. */
class LeavingChoicesTest {
    private fun NotifyChoiceSheet.lit() = choices.filter { it.lit }.map { it.label }
    private fun view(on: Boolean = true, to: String = "Alex", byArea: Boolean = false) =
        LeavingRules.view(LeavingSetting(on, to, 80, byArea))
    private val lists = PeopleLists(family = setOf("Alex", "Sam Lane"), alwaysNotify = setOf("Riley"))

    @Test
    fun theSwitchIsARowOpeningOnAndOff() {
        assertEquals(NotifyChoiceRow("Leaving now", "On", "Leaving now, On. Opens the choices"), LeavingChoiceRules.switchRow(view()))
        assertEquals("Off", LeavingChoiceRules.switchRow(view(on = false)).value)
        val s = LeavingChoiceRules.switchSheet(view())
        assertEquals(listOf("On", "Off"), s.choices.map { it.label })
        assertEquals(listOf("On"), s.lit())
        assertTrue("when work ends" in s.choices[0].line, s.choices[0].line)
        assertTrue("leave the work area" in LeavingChoiceRules.switchSheet(view(byArea = true)).choices[0].line)
        assertTrue(s.choices[0].spoken.endsWith(", chosen"))
        assertEquals(false, LeavingChoiceRules.switchNamed(view(), LeavingChoiceRules.OFF))
        assertNull(LeavingChoiceRules.switchNamed(view(), LeavingChoiceRules.ON))
        assertEquals(true, LeavingChoiceRules.switchNamed(view(on = false), LeavingChoiceRules.ON))
        assertNull(LeavingChoiceRules.switchNamed(view(), "maybe"))
    }

    @Test
    fun theToRowSaysWhoAndItsSheetOffersTheLists() {
        assertEquals("Alex", LeavingChoiceRules.toRow(view(), lists).value)
        val s = LeavingChoiceRules.toSheet(view(), lists)
        assertEquals(listOf("Alex", "Sam Lane", "Riley"), s.choices.map { it.label })
        assertEquals(listOf("Alex"), s.lit())
        assertTrue(s.choices[0].line.startsWith("Family"))
        assertTrue(s.choices[2].line.startsWith("Always notify"))
        assertEquals("Riley", LeavingChoiceRules.toNamed(view(), lists, "Riley"))
        assertNull(LeavingChoiceRules.toNamed(view(), lists, "Alex"))
        assertNull(LeavingChoiceRules.toNamed(view(), lists, "Nobody"))
    }

    @Test
    fun aFirstNameLightsItsFamilyEntryAndATypedNameComesFirst() {
        // "Sam" (typed on the Mac) is Family's "Sam Lane": that entry is lit, and the row says the full name.
        val sam = view(to = "Sam")
        assertEquals(listOf("Sam Lane"), LeavingChoiceRules.toSheet(sam, lists).lit())
        assertEquals("Sam Lane", LeavingChoiceRules.toRow(sam, lists).value)
        // A name on neither list is offered first, lit, saying it's the one in use.
        val jo = view(to = "Jo")
        val s = LeavingChoiceRules.toSheet(jo, lists)
        assertEquals("Jo", s.choices.first().label)
        assertEquals(listOf("Jo"), s.lit())
        assertTrue(s.choices.first().line.startsWith("In use now"))
    }

    @Test
    fun withNobodyTheToRowSaysSoAndOffersNothing() {
        val none = view(to = "")
        assertTrue(LeavingChoiceRules.names(none, PeopleLists()).isEmpty())
        val r = LeavingChoiceRules.toRow(none, PeopleLists())
        assertEquals(LeavingChoiceRules.NO_ONE_YET, r.value)
        assertTrue("Add someone" in r.spoken)
        // Someone on the lists but no one in use yet (no partner in About you): the row asks to choose.
        assertEquals(LeavingChoiceRules.CHOOSE, LeavingChoiceRules.toRow(none, lists).value)
        assertTrue(LeavingChoiceRules.toSheet(none, lists).lit().isEmpty())
        assertEquals("Alex", LeavingChoiceRules.toNamed(none, lists, "Alex"))
    }

    @Test
    fun theWhenRowOpensWorkEndsOrLeavingTheArea() {
        assertEquals("When work ends", LeavingChoiceRules.whenRow(view()).value)
        assertEquals("When I leave work", LeavingChoiceRules.whenRow(view(byArea = true)).value)
        val s = LeavingChoiceRules.whenSheet(view())
        assertEquals(listOf("When work ends"), s.lit())
        assertTrue("location" in s.choices[1].line)
        assertEquals(true, LeavingChoiceRules.whenNamed(view(), LeavingChoiceRules.LEAVE_AREA))
        assertNull(LeavingChoiceRules.whenNamed(view(), LeavingChoiceRules.WORK_ENDS))
        assertEquals(false, LeavingChoiceRules.whenNamed(view(byArea = true), LeavingChoiceRules.WORK_ENDS))
    }
}
