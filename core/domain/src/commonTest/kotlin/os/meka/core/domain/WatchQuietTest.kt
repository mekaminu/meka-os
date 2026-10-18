package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Galaxy Watch completeness slice 2: quiet hours on the watch and a battery-friendly refresh. */
class WatchQuietTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_744L // Sun 18 Oct 2026
    private fun at(h: Int, m: Int = 0, d: Long = day) = cal.toEpochMs(d, h * 60 + m)
    private val quiet = QuietHours(true, 22 * 60, 7 * 60)

    private fun home(parking: List<WatchParking> = emptyList(), inbox: WatchInbox? = null, lit: Boolean = false) =
        WatchHomeView("YOU'RE CLEAR", lit, "Nothing else today", null, emptyList(), null,
            WatchHomeRules.fast(FastingView.EMPTY, at(23)), inbox, parking)

    private fun parking(now: Long): List<WatchParking> {
        val cars = CarsView(listOf(CarView.EMPTY.copy(carId = CarRules.CAR_IDS[0], title = ParkingRules.TITLE)), true)
        val t = ParkingTimer(CarRules.CAR_IDS[0], now - 30 * 60_000L, now + 60 * 60_000L, null)
        return WatchHomeRules.parking(ParkingTimerRules.view(listOf(t), cars, now, cal), now)
    }

    @Test
    fun theQuietLineShowsOnlyInQuietHoursAndNamesTheirEnd() {
        assertEquals("Quiet hours · until 07:00", WatchQuietRules.line(quiet, at(23), cal))
        assertEquals("Quiet hours · until 07:00", WatchQuietRules.line(quiet, at(6, 30, day + 1), cal))
        assertNull(WatchQuietRules.line(quiet, at(7, 0, day + 1), cal))
        assertNull(WatchQuietRules.line(quiet, at(14), cal))
        assertNull(WatchQuietRules.line(quiet.copy(enabled = false), at(23), cal))
    }

    @Test
    fun quietHoursRefreshHourlyWhenCalmAndNeverPastTheirEnd() {
        val now = at(23)
        val end = quiet.endAfter(now, cal)
        // By day: the usual quarter hour.
        assertEquals(WatchTileRules.CALM_REFRESH_MS, WatchQuietRules.refreshAfterMs(home(), at(14), false))
        // At night: an hour.
        assertEquals(WatchQuietRules.QUIET_REFRESH_MS, WatchQuietRules.refreshAfterMs(home(), now, true, end))
        // Twenty minutes before the end: then, so the morning tile is drawn on time.
        val late = at(6, 40, day + 1)
        assertEquals(20 * 60_000L, WatchQuietRules.refreshAfterMs(home(), late, true, quiet.endAfter(late, cal)))
        // Something lit but not short: a quarter hour, not each minute.
        assertEquals(WatchQuietRules.QUIET_COUNTING_MS, WatchQuietRules.refreshAfterMs(home(lit = true), now, true, end))
        // A parking timer still counts each minute.
        val p = home(parking(now + 30_000L))
        assertTrue(WatchQuietRules.urgent(p))
        assertEquals(30_000L, WatchQuietRules.refreshAfterMs(p, now + 30_000L, true, end))
    }

    @Test
    fun theBackgroundSyncRestsToHourlyAtNightUnlessSomethingWaitsOrCounts() {
        val now = at(23)
        assertTrue(WatchQuietRules.syncDue(now - 15 * 60_000L, now, quiet = false, waiting = false, urgent = false))
        assertTrue(WatchQuietRules.syncDue(null, now, quiet = true, waiting = false, urgent = false))
        assertFalse(WatchQuietRules.syncDue(now - 15 * 60_000L, now, quiet = true, waiting = false, urgent = false))
        assertFalse(WatchQuietRules.syncDue(now - 45 * 60_000L, now, quiet = true, waiting = false, urgent = false))
        // WorkManager drifts: 59 minutes counts as the hour.
        assertTrue(WatchQuietRules.syncDue(now - 59 * 60_000L, now, quiet = true, waiting = false, urgent = false))
        assertTrue(WatchQuietRules.syncDue(now - 15 * 60_000L, now, quiet = true, waiting = true, urgent = false))
        assertTrue(WatchQuietRules.syncDue(now - 15 * 60_000L, now, quiet = true, waiting = false, urgent = true))
        // A clock that moved backwards: sync.
        assertTrue(WatchQuietRules.syncDue(now + 60_000L, now, quiet = true, waiting = false, urgent = false))
    }

    @Test
    fun theQuietTileDropsTheInboxAndItsLightAndSaysSo() {
        val inbox = WatchHomeRules.inbox(6, null)
        val h = home(inbox = inbox, lit = true)
        val dayTile = WatchTileRules.tile(h)
        assertEquals(inbox?.short, dayTile.inboxLine)
        val night = WatchQuietRules.tile(dayTile, h, "Quiet hours · until 07:00")
        assertNull(night.inboxLine)
        assertFalse(night.lit)
        assertEquals("Quiet hours · until 07:00", night.quietLine)
        assertTrue(night.spoken.endsWith(WatchQuietRules.SPOKEN))
        assertFalse(night.spoken.contains(inbox!!.spoken))
        // By day, or unlinked, nothing changes.
        assertEquals(dayTile, WatchQuietRules.tile(dayTile, h, null))
        val unlinked = WatchTileRules.tile(null)
        assertEquals(unlinked, WatchQuietRules.tile(unlinked, null, "Quiet hours · until 07:00"))
    }
}
