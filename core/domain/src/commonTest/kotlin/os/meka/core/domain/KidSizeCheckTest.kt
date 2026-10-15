package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kids' admin, slice 6: the Saturday heads-up three months after a child's sizes were checked (invented children). */
class KidSizeCheckTest {
    private val cal = LocalCalendar.UTC
    private val mon12Oct = CivilDate.toEpochDay(2026, 10, 12)
    private val sat16Jan = CivilDate.toEpochDay(2027, 1, 16) // first Saturday once 91 days have gone (Mon 11 Jan)
    private val alex = AboutChild("Alex", 7, "Oakfield School")
    private val sam = AboutChild("Sam", 10, null)
    private fun at(day: Long, min: Int = 8 * 60) = cal.toEpochMs(day, min)
    private val checked = KidRecord("c-alex", mapOf(KidSize.SHOES to "13", KidSize.TOPS to "7–8"), at(mon12Oct, 12 * 60))

    @Test
    fun theFirstSaturdayAfterThreeMonthsAndEveryFourWeeksAfter() {
        assertEquals(6, CivilDate.isoDayOfWeek(sat16Jan))
        assertEquals(sat16Jan, KidsRules.saturdayFrom(mon12Oct + KidsRules.CHECK_DAYS))
        assertEquals(sat16Jan, KidsRules.saturdayFrom(sat16Jan))
        assertEquals(sat16Jan, KidsRules.askDay(mon12Oct, sat16Jan - 5))
        assertEquals(sat16Jan, KidsRules.askDay(mon12Oct, sat16Jan + 1)) // still that weekend on Sunday
        assertEquals(sat16Jan + 28, KidsRules.askDay(mon12Oct, sat16Jan + 2))
        assertEquals(sat16Jan + 28, KidsRules.askDay(mon12Oct, sat16Jan + 29))
        assertEquals(sat16Jan + 56, KidsRules.askDay(mon12Oct, sat16Jan + 30))
    }

    @Test
    fun oneHeadsUpPerChildDueWithAllStillFit() {
        val records = mapOf("c-alex" to checked)
        // Before three months nothing; on the Monday it falls due, the heads-up waits for Saturday at 09:00.
        assertTrue(KidsRules.notices(listOf(alex, sam), records, at(mon12Oct + 90), cal).isEmpty())
        val n = KidsRules.notices(listOf(alex, sam), records, at(mon12Oct + 91), cal).single()
        assertEquals("${KidsRules.NOTICE_PREFIX}c-alex:$sat16Jan", n.key)
        assertEquals(NoticeSource.KIDS, n.source)
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals(NoticeTarget.KIDS, n.target)
        assertEquals("Time to check Alex's sizes", n.title)
        assertEquals("Last checked Mon 12 Oct · Shoes 13 · Tops 7–8 · do they still fit?", n.text)
        assertEquals(at(sat16Jan, 9 * 60), n.atMs)
        assertEquals(cal.toEpochMs(sat16Jan + 2, 0), n.expiresAtMs)
        assertEquals(listOf(NoticeAction.ALL_STILL_FIT), n.actions)
        assertEquals("All still fit", NoticeAction.ALL_STILL_FIT.label)
        assertEquals("c-alex", KidsRules.childIdOf(n.key))
        assertNull(KidsRules.childIdOf("date-night:1:nudge"))
        // Unchecked a month on: asked again under a fresh key.
        assertEquals("${KidsRules.NOTICE_PREFIX}c-alex:${sat16Jan + 28}", KidsRules.notices(listOf(alex), records, at(sat16Jan + 3), cal).single().key)
    }

    @Test
    fun noHeadsUpOnceCheckedOrWithoutAStampOrAChild() {
        val later = at(mon12Oct + 100)
        val fresh = mapOf("c-alex" to checked.copy(checkedAtMs = at(mon12Oct + 99)))
        assertTrue(KidsRules.notices(listOf(alex), fresh, later, cal).isEmpty())
        assertTrue(KidsRules.notices(listOf(alex), mapOf("c-alex" to checked.copy(checkedAtMs = null)), later, cal).isEmpty())
        assertTrue(KidsRules.notices(listOf(alex), mapOf("c-alex" to checked.copy(sizes = emptyMap())), later, cal).isEmpty())
        assertTrue(KidsRules.notices(listOf(sam), mapOf("c-alex" to checked), later, cal).isEmpty()) // Alex left About you
    }

    @Test
    fun theCollectorGathersItAndTheDigestCountsIt() {
        val all = NoticeSources.collect(
            ListsView.EMPTY, FastingView.EMPTY, ShutdownView.EMPTY, Today(emptyList(), null, emptyList(), emptyList()), at(mon12Oct + 92), cal,
            kids = listOf(alex), kidRecords = mapOf("c-alex" to checked),
        )
        assertEquals(1, all.count { it.source == NoticeSource.KIDS })
    }
}
