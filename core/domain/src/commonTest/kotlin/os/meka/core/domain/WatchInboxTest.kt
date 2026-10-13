package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Inbox autopilot, slice 7b-2: the watch shows only how many emails need him. */
class WatchInboxTest {
    private val cal = LocalCalendar.UTC
    private val hour = 3_600_000L
    private val sat = CivilDate.toEpochDay(2026, 10, 10) * CivilDate.DAY_MS
    private val day = DayWindow(sat, sat + 24 * hour, 0)
    private val at = sat + 10 * hour

    private val moment = MailMomentCard("Ten-minute inbox", "6 need you · 2 min each", "Inbox clear 4 days running", "spoken")

    private fun home(inbox: WatchInbox?, fasting: FastingView = FastingView.EMPTY) =
        WatchHomeRules.view(CoverNowRules.now(TodayProjection.project(emptyList(), at, day, emptyList(), cal), at, cal), fasting, at, inbox)

    @Test
    fun nothingWaitingShowsNothing() {
        assertNull(WatchHomeRules.inbox(0, null))
        assertNull(WatchHomeRules.inbox(0, moment))
        val t = WatchTileRules.tile(home(null))
        assertNull(t.inboxLine)
        assertFalse(t.spoken.contains("Inbox"))
    }

    @Test
    fun theCountIsCalmUntilTheMomentAndNeverSaysWhatTheEmailsAre() {
        val calm = WatchHomeRules.inbox(1, null)!!
        assertEquals("INBOX", calm.label)
        assertFalse(calm.lit)
        assertEquals("1 need you", calm.line)
        assertNull(calm.streakLine)
        assertEquals("Inbox · 1 need you", calm.short)
        assertEquals("Inbox. 1 email needs you.", calm.spoken)

        val lit = WatchHomeRules.inbox(6, moment)!!
        assertEquals("TEN-MINUTE INBOX", lit.label)
        assertTrue(lit.lit)
        assertEquals("6 need you · on your phone", lit.line)
        assertEquals("Inbox clear 4 days running", lit.streakLine)
        assertEquals("Ten-minute inbox · 6 need you", lit.short)
        assertEquals("Ten-minute inbox. 6 emails need you. Go through them on your phone.", lit.spoken)
    }

    @Test
    fun theTileGivesTheCountItsLastLineUnlessAFastHasIt() {
        val h = home(WatchHomeRules.inbox(3, moment))
        assertEquals("3 need you · on your phone", h.inbox?.line)
        val t = WatchTileRules.tile(h)
        assertEquals("Ten-minute inbox · 3 need you", t.inboxLine)
        assertTrue(t.inboxLit)
        assertTrue(t.spoken.endsWith("Ten-minute inbox. 3 emails need you. Go through them on your phone."))

        val started = at - 14 * hour
        val fasting = FastingView.EMPTY.copy(current = FastNow("f", started, 16, started + 16 * hour, false, "Started", "Goal 16 h"))
        val busy = WatchTileRules.tile(home(WatchHomeRules.inbox(3, null), fasting))
        assertTrue(busy.fastLine != null)
        assertNull(busy.inboxLine) // the fast's clock keeps the tile's last line; the watch's screen shows both
        assertTrue(busy.spoken.endsWith("Inbox. 3 emails need you."))
    }
}
