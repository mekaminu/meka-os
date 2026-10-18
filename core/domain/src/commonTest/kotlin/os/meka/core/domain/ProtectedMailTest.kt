package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Protected time, slice 3: the business account's mail waits until the morning after protected time. */
class ProtectedMailTest {
    private val cal = LocalCalendar.UTC
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private val fri = d(10, 16)
    private val sat = d(10, 17)
    private val mon = d(10, 19)
    private val tue = d(10, 20)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val nineToFive = WorkSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30)
    private val both = ProtectedSetting(evenings = true, fromMin = 19 * 60, weekends = true)
    private val none = HolidayCalendar.NONE

    private fun until(s: ProtectedSetting?, now: Long) = ProtectedRules.protectedUntil(s, now, nineToFive, none, cal)

    private fun mail(id: String, account: String, security: String? = null, note: String? = null) = MailItem(
        id = id, account = account, from = "Sam Taylor", subject = "Quote", preview = null, receivedAtMs = at(fri, 20),
        threadCount = 1, goneAtMs = null, doneAtMs = null, undoneAtMs = null, doneAskedAtMs = null, undoAskedAtMs = null,
        snoozedUntilMs = null, security = security, note = note,
    )

    @Test
    fun protectedNowRunsFromTheEveningOrWholeDayToTheNextOpenMorning() {
        // A weekday afternoon isn't protected; from 19:00 it is, until 07:00 the next working day.
        assertNull(until(both, at(tue, 15)))
        assertEquals(at(tue + 1, 7), until(both, at(tue, 19)))
        assertEquals(at(tue + 1, 7), until(both, at(tue, 23, 59)))
        // The small hours after a protected evening still are, until 07:00.
        assertEquals(at(tue, 7), until(both, at(tue, 2)))
        assertNull(until(both, at(tue, 7)))
        // Friday evening through the weekend: back on Monday at 07:00.
        assertEquals(at(mon, 7), until(both, at(fri, 21)))
        assertEquals(at(mon, 7), until(both, at(sat, 10)))
        assertEquals(at(mon, 7), until(both, at(mon - 1, 22)))
        assertEquals(at(mon, 7), until(both, at(mon, 3)))
        // Weekends only: a weekday evening isn't protected, Saturday is, and Monday's small hours after Sunday are.
        val weekends = both.copy(evenings = false)
        assertNull(until(weekends, at(tue, 21)))
        assertEquals(at(mon, 7), until(weekends, at(sat, 10)))
        assertEquals(at(mon, 7), until(weekends, at(mon, 5)))
        assertNull(until(weekends, at(tue, 5)))
        // Off, or never turned on: nothing.
        assertNull(until(null, at(sat, 10)))
        assertNull(until(ProtectedSetting(false, 19 * 60, false), at(sat, 10)))
    }

    @Test
    fun onlyTheBusinessAccountsOrdinaryMailIsHeld() {
        val items = listOf(
            mail("p", "Personal"), mail("b", "Business"), mail("o", "Outlook"),
            mail("sec", "Business", security = "Google"), mail("n", "Business", note = "bbc.co.uk"),
        )
        val u = at(mon, 7)
        assertEquals(setOf("b"), ProtectedMailRules.held(items, both, u))
        assertTrue(ProtectedMailRules.held(items, both, null).isEmpty())
        assertTrue(ProtectedMailRules.held(items, both.copy(holdMail = false), u).isEmpty())
        // A reply of his on the way isn't held.
        val replying = mail("r", "Business").copy(replyText = "Thanks", replyAskedAtMs = at(fri, 21), doneAskedAtMs = at(fri, 21))
        assertFalse(ProtectedMailRules.holdable(replying))
    }

    @Test
    fun theLinesSayHowManyAndUntilWhen() {
        assertEquals("2 business emails wait till Mon 19 Oct 07:00 · protected time", ProtectedMailRules.cardLine(2, at(mon, 7), at(sat, 10), cal))
        assertEquals("1 business email waits till tomorrow 07:00 · protected time", ProtectedMailRules.cardLine(1, at(tue + 1, 7), at(tue, 20), cal))
        assertEquals("Held now · 3 business emails till 07:00", ProtectedMailRules.paneLine(3, at(tue, 7), at(tue, 1), cal))
        assertNull(ProtectedMailRules.cardLine(0, at(mon, 7), at(sat, 10), cal))
        assertNull(ProtectedMailRules.paneLine(2, null, at(sat, 10), cal))
    }

    @Test
    fun theSwitchIsOnByDefaultAndSyncs() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        val pFold = ProtectedTime(fold.replica)
        val pMac = ProtectedTime(mac.replica)
        assertTrue(pFold.set(evenings = true, fromMin = 19 * 60, weekends = true))
        assertTrue(pFold.setting()!!.holdMail)
        assertFalse(pFold.setHoldMail(true)) // already on
        assertTrue(pFold.setHoldMail(false))
        fold.sync(); mac.sync()
        assertFalse(pMac.setting()!!.holdMail)
        assertFalse(ProtectedRules.view(pMac.setting(), sat, nineToFive, none).holdMail)
        // Changing the times keeps his choice.
        assertTrue(pMac.set(evenings = true, fromMin = 20 * 60, weekends = true))
        assertFalse(pMac.setting()!!.holdMail)
        assertTrue(pMac.setHoldMail(true))
        mac.sync(); fold.sync()
        assertTrue(pFold.setting()!!.holdMail)
    }
}
