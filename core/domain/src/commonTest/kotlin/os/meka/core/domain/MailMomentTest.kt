package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox autopilot, slice 7: the ten-minute inbox — when it comes, the session, and the "Inbox clear" streak. */
class MailMomentTest {
    private val cal = LocalCalendar.UTC
    private val now = 1_792_225_800_000L // Sat 17 Oct 2026, 08:30 UTC
    private val today = cal.epochDayOf(now)
    private val hour = 3_600_000L

    private fun setting(time: MailMomentTime = MailMomentTime.MORNING, clear: Set<Long> = emptySet(), putOff: Long? = null) =
        MailMomentSetting(time, clear, putOff)

    private fun row(id: String) = MailNeedsRow(id, "Sam Taylor", "Personal · 08:00", "Subject $id", null, false, "Sam Taylor, Subject $id")

    @Test
    fun absentReadsAsMorningAndTheFieldsReadBack() {
        val s = MailMomentRules.read(null)
        assertEquals(MailMomentTime.MORNING, s.time)
        assertTrue(s.clearDays.isEmpty())
        assertNull(s.putOffDay)
        val f = MailMomentRules.timeFields(MailMomentTime.EVENING, now) +
            MailMomentRules.clearFields(s, today) + MailMomentRules.putOffFields(today - 1)
        val back = MailMomentRules.read(f)
        assertEquals(MailMomentTime.EVENING, back.time)
        assertEquals(setOf(today), back.clearDays)
        assertEquals(today - 1, back.putOffDay)
        assertNull(MailMomentRules.read(MailMomentRules.putOffFields(0)).putOffDay)
        assertEquals(MailMomentTime.MORNING, MailMomentRules.read(mapOf(MailMomentFields.WHEN to FieldValue.Text("noon"))).time)
    }

    @Test
    fun clearDaysKeepOnlyTheLastSixty() {
        val old = setting(clear = setOf(today - 70, today - 59, today - 1))
        assertEquals(setOf(today - 59, today - 1, today), MailMomentRules.read(MailMomentRules.clearFields(old, today)).clearDays)
    }

    @Test
    fun theCardComesAtTheMomentWhileEmailsWaitUntilNotToday() {
        val c = assertNotNull(MailMomentRules.card(setting(), 6, now, cal))
        assertEquals("Ten-minute inbox", c.title)
        assertEquals("6 need you · 2 min each", c.headline)
        assertNull(c.streakLine)
        assertEquals("1 needs you · 2 min", MailMomentRules.card(setting(), 1, now, cal)!!.headline)
        // Before 08:00, with nothing waiting, put off today, or off: none.
        assertNull(MailMomentRules.card(setting(), 6, now - 2 * hour, cal))
        assertNull(MailMomentRules.card(setting(), 0, now, cal))
        assertNull(MailMomentRules.card(setting(putOff = today), 6, now, cal))
        assertNotNull(MailMomentRules.card(setting(putOff = today - 1), 6, now, cal))
        assertNull(MailMomentRules.card(setting(MailMomentTime.OFF), 6, now, cal))
        // The evening moment comes at 20:00.
        assertNull(MailMomentRules.card(setting(MailMomentTime.EVENING), 6, now, cal))
        assertNotNull(MailMomentRules.card(setting(MailMomentTime.EVENING), 6, now + 12 * hour, cal))
    }

    @Test
    fun theStreakCountsDaysRunningEndingTodayOrYesterday() {
        assertEquals(0, MailMomentRules.streak(emptySet(), today))
        assertEquals(3, MailMomentRules.streak(setOf(today - 1, today - 2, today - 3, today - 5), today))
        assertEquals(4, MailMomentRules.streak(setOf(today, today - 1, today - 2, today - 3), today))
        assertEquals(0, MailMomentRules.streak(setOf(today - 2), today))
        assertEquals("Inbox clear 4 days running", MailMomentRules.streakLine(setOf(today, today - 1, today - 2, today - 3), today))
        assertEquals("Inbox clear yesterday · keep it going", MailMomentRules.streakLine(setOf(today - 1), today))
        assertEquals("Inbox clear today · keep it going", MailMomentRules.streakLine(setOf(today), today))
        assertNull(MailMomentRules.streakLine(setOf(today - 3), today))
        val c = MailMomentRules.card(setting(clear = setOf(today - 1, today - 2)), 2, now, cal)!!
        assertEquals("Inbox clear 2 days running", c.streakLine)
    }

    @Test
    fun aDayIsNotedClearOnlyAfterTheMomentWithMailAndNothingWaiting() {
        assertTrue(MailMomentRules.shouldNoteClear(setting(), anyMail = true, waiting = 0, nowMs = now, cal = cal))
        assertFalse(MailMomentRules.shouldNoteClear(setting(), anyMail = false, waiting = 0, nowMs = now, cal = cal))
        assertFalse(MailMomentRules.shouldNoteClear(setting(), anyMail = true, waiting = 1, nowMs = now, cal = cal))
        assertFalse(MailMomentRules.shouldNoteClear(setting(), anyMail = true, waiting = 0, nowMs = now - 2 * hour, cal = cal))
        assertFalse(MailMomentRules.shouldNoteClear(setting(clear = setOf(today)), anyMail = true, waiting = 0, nowMs = now, cal = cal))
        assertFalse(MailMomentRules.shouldNoteClear(setting(MailMomentTime.OFF), anyMail = true, waiting = 0, nowMs = now, cal = cal))
    }

    @Test
    fun theSessionGoesThroughThemOneAtATimeAndSkippedOnesGoToTheBack() {
        val rows = listOf(row("a"), row("b"), row("c"))
        val s0 = MailMomentRules.session(rows, emptyList(), 3, setting(), today)
        assertEquals("a", s0.row?.id)
        assertEquals("1 of 3", s0.position)
        assertEquals("3 to go · about 6 min", s0.line)
        // Skip a: b is up, a waits at the back.
        val s1 = MailMomentRules.session(rows, listOf("a"), 3, setting(), today)
        assertEquals("b", s1.row?.id)
        assertEquals("1 of 3", s1.position)
        // b done (gone from the rows): c, then a.
        val s2 = MailMomentRules.session(listOf(row("a"), row("c")), listOf("a"), 3, setting(), today)
        assertEquals("c", s2.row?.id)
        assertEquals("2 of 3", s2.position)
        assertEquals("1 done · 2 to go", s2.line)
        // Only skipped ones left: they come round again.
        val s3 = MailMomentRules.session(listOf(row("a")), listOf("a"), 3, setting(), today)
        assertEquals("a", s3.row?.id)
        assertEquals("3 of 3", s3.position)
        // A new email during the session counts in.
        val s4 = MailMomentRules.session(rows + row("d"), emptyList(), 3, setting(), today)
        assertEquals("1 of 4", s4.position)
    }

    @Test
    fun theEndSaysInboxClearWithTheStreakCountingToday() {
        val end = MailMomentRules.session(emptyList(), listOf("a"), 3, setting(clear = setOf(today - 1, today - 2, today - 3)), today)
        assertNull(end.row)
        assertNull(end.position)
        assertEquals("Inbox clear", end.line)
        assertEquals(3, end.done)
        assertEquals("Inbox clear 4 days running", end.streakLine)
    }

    @Test
    fun allRowsListsEveryGroupNotOnlyTheFiveShown() {
        val items = (1..8).map { n ->
            MailNeedsRules.read("mi$n", MailNeedsRules.serverFields("Personal", "Person " + ('A' + n), null, "About the " + listOf("", "garden", "car", "roof", "party", "club", "trip", "bike", "dog")[n], null, now - n * hour, 1))!!
        }
        assertEquals(5, MailNeedsRules.card(items, now, cal)!!.rows.size)
        assertEquals(8, MailNeedsRules.allRows(items, now, cal).size)
        assertEquals("mi1", MailNeedsRules.allRows(items, now, cal).first().id)
    }

    @Test
    fun theSettingLines() {
        assertEquals("Mornings from 08:00 · Needs you offers a few minutes to clear what needs you", MailMomentRules.settingLine(MailMomentTime.MORNING))
        assertEquals("Ten-minute inbox · evenings from 20:00", MailMomentRules.switchedLine(MailMomentTime.EVENING))
        assertEquals("Ten-minute inbox off", MailMomentRules.switchedLine(MailMomentTime.OFF))
    }
}
