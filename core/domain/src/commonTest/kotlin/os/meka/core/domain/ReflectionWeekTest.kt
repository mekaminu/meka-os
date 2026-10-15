package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Weekly reflection, slice 2: the one thing through the week (Today and the brief) and "reflect on my week" to Talk. */
class ReflectionWeekTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon12 = d(10, 12)
    private val mon19 = d(10, 19)

    private fun r(week: Long, matters: String, skipped: Boolean = false) =
        Reflection(week, "Shipped it", "", matters, skipped, 1L)

    @Test
    fun theOneThingShowsThroughTheWeekItWasFor() {
        val all = listOf(r(mon12, "run 5k.\nCall Sam"))
        // Saved on Sunday 18 Oct for the week of 12 Oct: nothing that evening (next week's starts tomorrow).
        assertNull(ReflectionRules.weekLine(all, d(10, 18), 19 * 60))
        val monday = ReflectionRules.weekLine(all, mon19, 8 * 60)!!
        assertEquals("This week's one thing · run 5k", monday.text)
        assertEquals("The one thing that matters this week: run 5k.", monday.spoken)
        assertTrue(monday.lit)
        val thursday = ReflectionRules.weekLine(all, d(10, 22), 8 * 60)!!
        assertFalse(thursday.lit)
        // Sunday before 18:00 still shows it; from 18:00 the new week's reflection is asked instead.
        assertTrue(ReflectionRules.weekLine(all, d(10, 25), 17 * 60) != null)
        assertNull(ReflectionRules.weekLine(all, d(10, 25), 18 * 60))
        // The week after, nothing (unless that week was reflected on too).
        assertNull(ReflectionRules.weekLine(all, d(10, 26), 8 * 60))
    }

    @Test
    fun noLineWithoutAOneThingOrAfterASkip() {
        assertNull(ReflectionRules.weekLine(listOf(r(mon12, "")), mon19, 600))
        assertNull(ReflectionRules.weekLine(listOf(r(mon12, "Run 5k", skipped = true)), mon19, 600))
        assertNull(ReflectionRules.weekLine(emptyList(), mon19, 600))
        val long = ReflectionRules.weekLine(listOf(r(mon12, "word ".repeat(30))), mon19, 600)!!
        assertTrue(long.text.endsWith("…"))
        assertTrue(long.text.length <= ReflectionRules.WEEK_LINE.length + 3 + ReflectionRules.MAX_WEEK_LINE + 1)
    }

    @Test
    fun theBriefSaysItOnMondayAndOnLongerBriefsInTheWeek() {
        val line = ReflectionWeekLine("This week's one thing · Run 5k", "The one thing that matters this week: Run 5k.", true)
        assertEquals(line.spoken, ReflectionRules.briefSentence(line, BriefLength.SHORT, mon19))
        assertNull(ReflectionRules.briefSentence(line, BriefLength.SHORT, d(10, 21)))
        assertEquals(line.spoken, ReflectionRules.briefSentence(line, BriefLength.STANDARD, d(10, 21)))
        assertNull(ReflectionRules.briefSentence(line, BriefLength.FULL, d(10, 24)))
        assertNull(ReflectionRules.briefSentence(null, BriefLength.FULL, mon19))
        val v = MorningBriefView.EMPTY.copy(story = BriefStory(weekMatters = line.spoken))
        assertTrue(BriefScriptRules.script(v).contains(line.spoken))
        val weekend = MorningBriefView.EMPTY.copy(story = BriefStory(weekend = true, weekMatters = line.spoken))
        assertFalse(BriefScriptRules.script(weekend).contains(line.spoken))
    }

    @Test
    fun reflectOnMyWeekOpensTheReflection() {
        listOf(
            "reflect on my week", "let's reflect on the week", "ok Navilife, reflect on my week please", "weekly reflection",
            "do my weekly reflection", "start my reflection", "look back on my week", "reflect", "open my weekly reflection",
        ).forEach {
            assertEquals(TalkPlace.REFLECT, TalkOpenRules.request(it, TalkOpenRules.FOLD), it)
            assertEquals(TalkPlace.REFLECT, TalkOpenRules.request(it, TalkOpenRules.MAC), it)
        }
        listOf("how did my week go", "reflection", "reflect on my week at work and the gym").forEach {
            assertNull(TalkOpenRules.request(it, TalkOpenRules.FOLD), it)
        }
        assertNull(TalkOpenRules.request("reflect on my week", TalkOpenRules.WATCH))
        assertEquals(ReflectionRules.OPENING, TalkOpenRules.opening(TalkPlace.REFLECT))
        assertEquals(ReflectionRules.OPENED, TalkOpenRules.remembered(TalkPlace.REFLECT))
    }

    @Test
    fun aRequestedWeekOpensUntilSaved() {
        val v = WeeklyReviewView.EMPTY.copy(weekStart = mon12, isCurrent = true)
        val cal = LocalCalendar.fixedOffset(0)
        val wed = d(10, 14)
        assertFalse(ReflectionRules.view(v, emptyList(), emptyList(), emptyList(), wed, 600, cal).asking)
        assertTrue(ReflectionRules.view(v, emptyList(), emptyList(), emptyList(), wed, 600, cal, requestedWeek = mon12).asking)
        assertFalse(ReflectionRules.view(v, listOf(r(mon12, "Run 5k")), emptyList(), emptyList(), wed, 600, cal, requestedWeek = mon12).asking)
        assertFalse(ReflectionRules.view(v, emptyList(), emptyList(), emptyList(), wed, 600, cal, requestedWeek = mon19).asking)
    }

    @Test
    fun theSundayTimeIsASettingSharedByTheCardAndTheOneThing() {
        assertEquals(ReviewRules.CARD_START_MIN, ReviewRules.readStart(null))
        assertEquals(19 * 60 + 30, ReviewRules.readStart(mapOf(ReviewFields.START_MIN to os.meka.core.sync.FieldValue.Int64(19L * 60 + 30))))
        assertEquals(ReviewRules.CARD_START_MIN, ReviewRules.readStart(mapOf(ReviewFields.START_MIN to os.meka.core.sync.FieldValue.Int64(3L * 60))))
        assertTrue(ReviewRules.validStart(12 * 60))
        assertTrue(ReviewRules.validStart(22 * 60))
        assertFalse(ReviewRules.validStart(22 * 60 + 15))
        assertFalse(ReviewRules.validStart(19 * 60 + 5))
        val sun = d(10, 18)
        assertFalse(ReviewRules.cardWindow(sun, 19 * 60, 20 * 60))
        assertTrue(ReviewRules.cardWindow(sun, 20 * 60, 20 * 60))
        assertTrue(ReviewRules.cardWindow(mon19, 0, 21 * 60))
        assertEquals(listOf(16 * 60, 17 * 60, 18 * 60, 18 * 60 + 30, 19 * 60, 20 * 60, 21 * 60), ReviewRules.timeChoices(18 * 60 + 30))
        assertEquals("Comes on Sundays at 19:30 · through Monday", ReviewRules.whenLine(19 * 60 + 30))
        // The week's one thing stays on Today until the set time on Sunday.
        val all = listOf(r(mon12, "Run 5k"))
        assertTrue(ReflectionRules.weekLine(all, d(10, 25), 19 * 60, 20 * 60) != null)
        assertNull(ReflectionRules.weekLine(all, d(10, 25), 20 * 60, 20 * 60))
    }
}
