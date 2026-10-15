package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kids' admin, slice 1: sizes per child (invented children), the three-monthly check, the tile line. */
class KidsTest {
    private val cal = LocalCalendar.UTC
    private val today = CivilDate.toEpochDay(2026, 10, 15)
    private fun ms(day: Long) = cal.toEpochMs(day, 12 * 60)
    private val alex = AboutChild("Alex", 7, "Oakfield School")
    private val sam = AboutChild("Sam", 10, null)

    @Test
    fun idsFoldTheNameSoBothAppsWriteTheSameChild() {
        assertEquals("c-alex", KidsRules.idFor(" Alex "))
        assertEquals("c-mary-jane", KidsRules.idFor("Mary  Jane"))
        assertEquals("c-o-neil", KidsRules.idFor("O'Neil"))
        assertNull(KidsRules.idFor("  "))
    }

    @Test
    fun sizesAreTidiedOrTurnedAway() {
        assertEquals("7–8 years", KidsRules.tidy(" 7-8  years "))
        assertEquals("7–8", KidsRules.tidy("7 - 8"))
        assertEquals("1½", KidsRules.tidy("1½"))
        assertEquals("XL", KidsRules.tidy("xl"))
        assertEquals("26\"", KidsRules.tidy("26\""))
        assertNull(KidsRules.tidy("big"))
        assertNull(KidsRules.tidy(""))
        assertNull(KidsRules.tidy("12345678901234567"))
    }

    @Test
    fun noChildrenSendsHimToAboutYou() {
        val v = KidsRules.view(emptyList(), emptyMap(), today, cal)
        assertTrue(v.isEmpty)
        assertEquals(KidsRules.NO_CHILDREN, v.empty)
        assertNull(v.tile)
    }

    @Test
    fun cardsFollowAboutYouAndSayWhenSizesWereChecked() {
        val r = KidRecord("c-alex", mapOf(KidSize.SHOES to "13", KidSize.TOPS to "7–8"), ms(today - 10))
        val v = KidsRules.view(listOf(alex, sam), mapOf("c-alex" to r, "c-gone" to KidRecord("c-gone", mapOf(KidSize.SHOES to "2"), null)), today, cal)
        assertEquals(listOf("Alex", "Sam"), v.kids.map { it.name })
        val a = v.kids[0]
        assertEquals("Under 7s · Oakfield School", a.line)
        assertEquals("Shoes 13 · Tops 7–8", a.sizesLine)
        assertEquals("Sizes checked ${CivilDate.shortLabel(today - 10)}", a.checkedLine)
        assertFalse(a.due)
        assertEquals(KidSize.entries.map { it.label }, a.sizes.map { it.label })
        assertEquals(KidsRules.NO_SIZES, v.kids[1].checkedLine)
        assertEquals("Sizes for Alex · add Sam's", v.summary)
        assertEquals(LifeTileLine("Alex · shoes 13", false), v.tile)
    }

    @Test
    fun threeMonthsOnTheCheckIsDueAndTheTileLights() {
        val old = KidRecord("c-alex", mapOf(KidSize.BOOTS to "12"), ms(today - KidsRules.CHECK_DAYS))
        val fresh = KidRecord("c-sam", mapOf(KidSize.COAT to "11–12"), ms(today))
        val v = KidsRules.view(listOf(alex, sam), mapOf("c-alex" to old, "c-sam" to fresh), today, cal)
        assertTrue(v.kids[0].due)
        assertEquals("Last checked ${CivilDate.shortLabel(today - KidsRules.CHECK_DAYS)} · do they still fit?", v.kids[0].checkedLine)
        assertEquals("Sizes checked today", v.kids[1].checkedLine)
        assertEquals(LifeTileLine("Check Alex's sizes", true), v.tile)
        assertTrue(v.summary.startsWith("Time to check Alex's sizes"))
        // A day short of three months isn't due; both due names both.
        assertFalse(KidsRules.due(old.copy(checkedAtMs = ms(today - KidsRules.CHECK_DAYS + 1)), today, cal))
        val both = KidsRules.view(listOf(alex, sam), mapOf("c-alex" to old, "c-sam" to fresh.copy(checkedAtMs = null)), today, cal)
        assertEquals("Check Alex's and Sam's sizes", both.tile?.text)
    }

    @Test
    fun theTileUsesTheFirstKeptSizeWhenThereAreNoShoes() {
        val v = KidsRules.view(listOf(alex, sam), mapOf(
            "c-alex" to KidRecord("c-alex", mapOf(KidSize.JUMPER to "26\""), ms(today)),
            "c-sam" to KidRecord("c-sam", mapOf(KidSize.SHOES to "2"), ms(today)),
        ), today, cal)
        assertEquals("Alex · school jumper 26\" · +1", v.tile?.text)
        assertEquals("Sizes for Alex and Sam · checked every 3 months", v.summary)
        assertEquals("Alex's sizes", LifeTileRules.lines(LifeTileInputs(kidsLine = LifeTileLine("Alex's sizes", false))).of(LifeTile.KIDS)?.text)
        assertTrue(LifeTileRules.lines(LifeTileInputs()).needsSetUp(LifeTile.KIDS))
    }
}
