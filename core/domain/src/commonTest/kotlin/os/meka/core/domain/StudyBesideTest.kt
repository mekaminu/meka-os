package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudyBesideTest {
    @Test
    fun aNarrowCardDropsTheWeekdayFromAFinishDate() {
        assertEquals("23 Nov · 4 wks", StudyBesideRules.endChipLabel("Mon 23 Nov · 4 wks", narrow = true))
        assertEquals("Mon 23 Nov · 4 wks", StudyBesideRules.endChipLabel("Mon 23 Nov · 4 wks", narrow = false))
    }

    @Test
    fun aLabelWithoutAWeekdayStaysAsItIs() {
        assertEquals("45 min", StudyBesideRules.endChipLabel("45 min", narrow = true))
        assertEquals("Monday", StudyBesideRules.endChipLabel("Monday", narrow = true))
    }

    @Test
    fun theRealEndLabelShortensToFitTwoToARow() {
        val today = 20_390L
        val label = StudyRules.endLabel(today + 28, today)
        val short = StudyBesideRules.endChipLabel(label, narrow = true)
        assertTrue(short.length < label.length, short)
        assertTrue(short.endsWith("· 4 wks"), short)
        assertFalse(short.substringBefore(' ') in setOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), short)
    }

    @Test
    fun theClosedFoldsCardIsNarrowTheOpenFoldsIsNot() {
        // The cover is 344 dp: 16 dp page gutters and 16 dp inside the card leave 280.
        assertTrue(StudyBesideRules.narrow(344f - 2 * 16 - 2 * StudyBesideRules.CARD_PAD_DP))
        assertFalse(StudyBesideRules.narrow(390f - 2 * 16 - 2 * StudyBesideRules.CARD_PAD_DP + 30))
    }

    @Test
    fun theOutlineFieldOpensForAnEmptyCourseATapOrWhatIsInIt() {
        assertTrue(StudyBesideRules.fieldOpen(lessons = 0, tapped = false, typed = "", scanning = false))
        assertFalse(StudyBesideRules.fieldOpen(lessons = 8, tapped = false, typed = "", scanning = false))
        assertTrue(StudyBesideRules.fieldOpen(lessons = 8, tapped = true, typed = "", scanning = false))
        assertTrue(StudyBesideRules.fieldOpen(lessons = 8, tapped = false, typed = "1. Welcome", scanning = false))
        assertTrue(StudyBesideRules.fieldOpen(lessons = 8, tapped = false, typed = " ", scanning = true))
        assertFalse(StudyBesideRules.fieldOpen(lessons = 8, tapped = false, typed = "  ", scanning = false))
    }

    @Test
    fun theEmptyCourseLinePointsDownToTheField() {
        assertTrue("below" in StudyRules.EMPTY_COURSE)
    }
}
