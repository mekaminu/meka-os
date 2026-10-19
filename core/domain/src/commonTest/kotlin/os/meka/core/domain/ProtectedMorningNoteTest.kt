package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Protected time, slice 6: one morning note for the business mail that waited through protected time. */
class ProtectedMorningNoteTest {
    private val cal = LocalCalendar.UTC
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private val fri = d(10, 16)
    private val sat = d(10, 17)
    private val mon = d(10, 19)
    private val tue = d(10, 20)
    private val wed = d(10, 21)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val nineToFive = WorkSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30)
    private val both = ProtectedSetting(evenings = true, fromMin = 19 * 60, weekends = true)
    private val none = HolidayCalendar.NONE

    private fun mail(id: String, from: String, atMs: Long, account: String = "Business", security: String? = null) = MailItem(
        id = id, account = account, from = from, subject = "Quote", preview = null, receivedAtMs = atMs,
        threadCount = 1, goneAtMs = null, doneAtMs = null, undoneAtMs = null, doneAskedAtMs = null, undoAskedAtMs = null,
        snoozedUntilMs = null, security = security,
    )

    private fun note(items: List<MailItem>, now: Long, s: ProtectedSetting? = both) =
        ProtectedMailRules.morningNotice(items, s, now, nineToFive, none, cal)

    @Test
    fun theWindowRunsFromTheEveningOrTheFirstWholeDay() {
        assertEquals(at(tue, 19), ProtectedMailRules.windowStart(both, wed, nineToFive, none, cal))
        // Monday morning after the weekend: from Friday's evening.
        assertEquals(at(fri, 19), ProtectedMailRules.windowStart(both, mon, nineToFive, none, cal))
        // Weekends only: from Saturday's midnight; a Tuesday morning ends nothing.
        val weekends = both.copy(evenings = false)
        assertEquals(at(sat, 0), ProtectedMailRules.windowStart(weekends, mon, nineToFive, none, cal))
        assertNull(ProtectedMailRules.windowStart(weekends, tue, nineToFive, none, cal))
        // A lifted evening ("not protected tonight") means the next morning ends nothing.
        assertNull(ProtectedMailRules.windowStart(both.copy(openDay = tue), wed, nineToFive, none, cal))
        assertEquals("overnight", ProtectedMailRules.sinceLabel(at(tue, 19), wed, cal))
        assertEquals("since Fri 19:00", ProtectedMailRules.sinceLabel(at(fri, 19), mon, cal))
        assertEquals("since Sat", ProtectedMailRules.sinceLabel(at(sat, 0), mon, cal))
    }

    @Test
    fun oneNoteAtSevenForTheBusinessMailThatWaited() {
        val items = listOf(
            mail("a", "Sam Taylor", at(tue, 20)),
            mail("b", "Alex Green", at(tue, 22)),
            mail("c", "Sam Taylor", at(wed, 1)),
            mail("d", "Jordan Lee", at(wed, 5)),
            mail("early", "Before Start", at(tue, 18)), // came before the evening: it was never held
            mail("p", "Personal Friend", at(tue, 21), account = "Personal"),
            mail("sec", "Google", at(tue, 23), security = "Google"),
        )
        val n = assertNotNull(note(items, at(wed, 7, 5)))
        assertEquals(NoticeSource.PROTECTED_MAIL, n.source)
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals(NoticeTarget.NEEDS_YOU, n.target)
        assertEquals("4 work emails waited overnight", n.title)
        assertEquals("Jordan Lee, Sam Taylor and 1 more · in From your mail", n.text)
        assertEquals(at(wed, 7), n.atMs)
        assertEquals(at(wed, 12), n.expiresAtMs)
        assertEquals("protected-mail:$wed", n.key)
        // Not before 07:00, not from midday, not while it's still protected.
        assertNull(note(items, at(wed, 6, 59)))
        assertNull(note(items, at(wed, 12)))
        assertNull(note(listOf(mail("s", "Sam Taylor", at(fri, 21))), at(sat, 8)))
        // Answered or tidied mail doesn't count; with nothing left, no note.
        assertNull(note(items.map { it.copy(goneAtMs = at(wed, 6)) }, at(wed, 7, 5)))
        // Shows as it comes, or protected time off: no note.
        assertNull(note(items, at(wed, 7, 5), both.copy(holdMail = false)))
        assertNull(note(items, at(wed, 7, 5), null))
    }

    @Test
    fun afterTheWeekendItSaysSinceWhen() {
        val items = listOf(mail("x", "Sam Taylor", at(sat, 11)))
        val n = assertNotNull(note(items, at(mon, 8)))
        assertEquals("1 work email waited since Fri 19:00", n.title)
        assertEquals("Sam Taylor · in From your mail", n.text)
    }
}
