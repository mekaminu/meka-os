package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Leaving now" to Jordan (Meka approved 2026-10-10): the draft when an office day's work ends, sent only on his tap. */
class LeavingNowTest {
    // Friday 9 October 2026, British Summer Time (UTC+1); work 09:00–17:30.
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val office = PlacesRules.officeWindow(WorkHours(WorkSchedule.DEFAULT), fri, cal)!!
    private val on = LeavingSetting(true, "Jordan", LeavingRules.DEFAULT_JOURNEY)

    // Rule 13: the partner's name comes from About you (an invented one here), never the code.
    @BeforeTest
    fun partner() { Household.useTyped("", "", "Jordan Lane") }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    private fun snap(tl: Int?, gn: Int?, el: Int?, checked: Long = at(fri, 17)) = LineStatusSnapshot(
        listOfNotNull(tl?.let { LineState(RouteRules.THAMESLINK, it) }, gn?.let { LineState(RouteRules.GREAT_NORTHERN, it) },
            el?.let { LineState(RouteRules.ELIZABETH, it) }), checked,
    )
    private val good = snap(10, 10, 10)

    @Test
    fun theDraftIsDueWhenWorkEndsWithTheJourneyHome() {
        assertEquals(at(fri, 17, 30), office.endMs)
        // Planned from the morning, so the governor wakes for it at 17:30.
        val early = LeavingRules.notices(on, office, LineStatusSnapshot.EMPTY, at(fri, 12), cal).single()
        assertEquals(office.endMs, early.atMs)
        assertEquals(NoticeSource.LEAVING, early.source)
        assertEquals("Tell Jordan you're leaving?", early.title)
        // 17:30 + 70 min = 18:40, word for word what Send sends.
        val n = LeavingRules.notices(on, office, good, at(fri, 17, 30), cal).single()
        assertEquals("Leaving now, home about 18:40", n.text)
        assertEquals(listOf(NoticeAction.SEND_MESSAGE, NoticeAction.EDIT_MESSAGE), NotifyRules.actionsOn(n, send = true))
        assertEquals(listOf(NoticeAction.COPY_MESSAGE), NotifyRules.actionsOn(n, send = false))
        assertEquals("leaving:$fri", n.postId)
        // Tapped later than work's end: leaving now, rounded up to 5 minutes.
        assertEquals("Leaving now, home about 18:55", LeavingRules.notices(on, office, good, at(fri, 17, 42), cal).single().text)
        // After the journey home, on a work-from-home day, or switched off: nothing.
        assertTrue(LeavingRules.notices(on, office, good, at(fri, 19), cal).isEmpty())
        assertTrue(LeavingRules.notices(on, null, good, at(fri, 17, 30), cal).isEmpty())
        assertTrue(LeavingRules.notices(on.copy(on = false), office, good, at(fri, 17, 30), cal).isEmpty())
        // A longer journey set in Work mode.
        assertEquals("Leaving now, home about 19:00", LeavingRules.notices(on.copy(journeyMin = 90), office, good, at(fri, 17, 30), cal).single().text)
    }

    @Test
    fun aDelayOnTheRouteGivesALaterHonestEta() {
        val minor = LeavingRules.notices(on, office, snap(9, 10, 10), at(fri, 17, 30), cal).single()
        assertEquals("Leaving now, home about 18:50 — Thameslink has minor delays", minor.text)
        assertEquals("Trains delayed · tell Jordan?", minor.title)
        val viaKx = LeavingRules.draft(on, office.endMs, snap(6, 10, 10), at(fri, 17, 30), cal)
        assertEquals("Leaving now, home about 19:00 — Thameslink has severe delays, so I'm coming via King's Cross", viaKx.text)
        val both = LeavingRules.draft(on, office.endMs, snap(2, 6, 9), at(fri, 17, 30), cal)
        assertEquals("Leaving now, home about 19:15 — Thameslink is suspended", both.text)
        val el = LeavingRules.draft(on, office.endMs, snap(10, 10, 6), at(fri, 17, 30), cal)
        assertEquals("Leaving now, home about 18:55 — the Elizabeth line has severe delays", el.text)
        // A stale status (the server stopped reading TfL) is left out rather than guessed.
        assertEquals("Leaving now, home about 18:40", LeavingRules.draft(on, office.endMs, snap(6, 10, 10, checked = at(fri, 9)), at(fri, 17, 30), cal).text)
        // Each line level posts once, in the same place: good at 17:30, then the delay once more.
        val a = LeavingRules.notices(on, office, good, at(fri, 17, 30), cal).single()
        val b = LeavingRules.notices(on, office, snap(6, 10, 10, at(fri, 17, 40)), at(fri, 17, 40), cal).single()
        assertFalse(a.key == b.key)
        assertEquals(a.postId, b.postId)
    }

    @Test
    fun sendSendsOnlyTheWordsMekaWasShown() {
        val n = LeavingRules.notices(on, office, good, at(fri, 17, 30), cal).single()
        val send = LeavingRules.tap(n.key, n.text, on, office, good, at(fri, 17, 31), cal)
        assertEquals(LeavingTap.Send("Jordan", "Leaving now, home about 18:40"), send)
        // A few minutes late the words still stand (the ETA is "about", within 10 minutes).
        assertEquals(LeavingTap.Send("Jordan", n.text), LeavingRules.tap(n.key, n.text, on, office, good, at(fri, 17, 39), cal))
        // The trains went bad meanwhile: the new draft is shown instead.
        val worse = LeavingRules.tap(n.key, n.text, on, office, snap(6, 10, 10, at(fri, 17, 35)), at(fri, 17, 36), cal) as LeavingTap.Changed
        assertTrue(worse.notice.text.endsWith("so I'm coming via King's Cross"))
        // Tapped at 17:50: the draft moved on, so the notification is updated and nothing is sent.
        val late = LeavingRules.tap(n.key, n.text, on, office, good, at(fri, 17, 50), cal) as LeavingTap.Changed
        assertEquals("Leaving now, home about 19:00", late.notice.text)
        assertEquals(n.postId, late.notice.postId)
        // Edit opens the chat with the shown words for Meka to change: no check needed.
        assertEquals(LeavingTap.Send("Jordan", n.text), LeavingRules.tap(n.key, n.text, on, office, good, at(fri, 17, 50), cal, edit = true))
        // Yesterday's notice, after the journey home, switched off, or not a leaving key: nothing.
        assertNull(LeavingRules.tap("leaving:${fri - 1}:good", n.text, on, office, good, at(fri, 17, 31), cal))
        assertNull(LeavingRules.tap(n.key, n.text, on, office, good, at(fri, 19, 5), cal))
        assertNull(LeavingRules.tap(n.key, n.text, on.copy(on = false), office, good, at(fri, 17, 31), cal))
        assertNull(LeavingRules.tap("gym:1", n.text, on, office, good, at(fri, 17, 31), cal))
    }

    @Test
    fun whatsAppsLinkAndTheSyncedSetting() {
        assertEquals("https://wa.me/447700900123?text=Leaving%20now%2C%20home%20about%2018%3A40", LeavingRules.waLink("07700 900123", "Leaving now, home about 18:40"))
        assertEquals("https://wa.me/447700900123?text=hi", LeavingRules.waLink("+44 7700 900123", "hi"))
        assertEquals("https://wa.me/447700900123?text=hi", LeavingRules.waLink("0044 7700 900123", "hi"))
        assertNull(LeavingRules.waLink("123", "hi"))
        assertNull(LeavingRules.waLink(null, "hi"))
        assertEquals("%E2%80%94", LeavingRules.percentEncode("—"))
        assertTrue(LeavingRules.samePerson("Jordan", "jordan lane"))
        assertFalse(LeavingRules.samePerson("Jordan", "Jean"))
        assertEquals("Jordan A", LeavingRules.familyEntry("Jordan", listOf("Rex", "Jordan A")))
        assertEquals("Wife", LeavingRules.familyEntry("wife", listOf("Wife", "Wife's sister")))
        assertNull(LeavingRules.familyEntry("Jordan", listOf("Rex")))

        val world = SyncWorld()
        val fold = world.device("fold")
        val mac = world.device("mac")
        val a = LeavingNow(fold.replica)
        assertEquals(LeavingSetting(true, "Jordan Lane", LeavingRules.DEFAULT_JOURNEY), a.setting())
        assertEquals("On · a draft to Jordan Lane when work ends on office days · about 1 h 10 home", LeavingRules.summary(a.setting()))
        assertTrue(a.set(true, " Wife ", 80))
        assertFalse(a.set(true, "Wife", 80), "nothing changed")
        assertFalse(a.set(true, "", 80))
        assertFalse(a.set(true, "Wife", 5))
        fold.sync(); mac.sync(); fold.sync()
        assertEquals(LeavingSetting(true, "Wife", 80), LeavingNow(mac.replica).setting())
        assertTrue(LeavingNow(mac.replica).set(false, "Wife", 80))
        fold.sync(); mac.sync(); fold.sync()
        assertEquals("Off · no draft when work ends", LeavingRules.summary(a.setting()))
    }

    @Test
    fun withNoPartnerInAboutYouNothingIsDraftedAndTheLineSaysSo() {
        Household.set(HouseholdNames.EMPTY)
        val world = SyncWorld()
        val fold = world.device("fold")
        val a = LeavingNow(fold.replica)
        assertEquals("", a.setting().to)
        assertEquals(LeavingRules.NO_ONE, LeavingRules.summary(a.setting()))
        assertTrue(LeavingRules.notices(a.setting(), office, good, at(fri, 17, 31), cal).isEmpty())
        assertNull(LeavingRules.tap(LeavingRules.key(fri, LineLevel.GOOD), "Leaving now", a.setting(), office, good, at(fri, 17, 31), cal))
        // The switch and journey can still be set with no name; the draft then follows About you once a partner is added.
        assertTrue(a.set(false, "", 80))
        assertEquals(LeavingSetting(false, "", 80), a.setting())
        assertTrue(a.set(true, "", 80))
        Household.useTyped("", "", "Jordan")
        assertEquals("Jordan", a.setting().to)
        assertEquals(1, LeavingRules.notices(a.setting(), office, good, at(fri, 17, 31), cal).size)
        // A name typed in Work mode wins over About you's.
        assertTrue(a.set(true, "Robin", 80))
        assertEquals("Robin", a.setting().to)
    }
}
