package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Meeting prep, slice 3: the prep on the Galaxy Watch's screen and tile. Invented people only (rule 13). */
class WatchPrepTest {
    private val cal = LocalCalendar.UTC
    private val day = CivilDate.toEpochDay(2026, 10, 15)
    private fun at(h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private val meeting = CalendarEvent(
        "e1", "Quote review", at(14), at(15), false, null, "google", "a", "Work", guests = listOf("Sam Taylor", "Alex Green"),
    )
    private val samMail = MailItem(
        id = "m1", account = "Business", from = "Sam Taylor", subject = "Re: quote", preview = null, receivedAtMs = at(9, 30),
        threadCount = 1, goneAtMs = null, doneAtMs = null, undoneAtMs = null, doneAskedAtMs = null, undoAskedAtMs = null,
        snoozedUntilMs = null,
    )
    private val promise = PromiseItem("p1", "Send the figures", "Sam", null, 0L, day, DueState.DUE, "Sam · due today", false)
    private val waitingFor = WaitingItem("w1", "Signed contract", "Alex Green", null, 0L, null, day + 1, DueState.LATER,
        "Alex Green · chase Fri 16 Oct", false)

    private fun prep(now: Long, mail: List<MailItem> = listOf(samMail), promised: List<PromiseItem> = listOf(promise),
        waiting: List<WaitingItem> = listOf(waitingFor)) =
        MeetingPrepRules.prep(meeting, mail, promised, waiting, now, cal)

    private fun home(prep: WatchPrep?, inbox: WatchInbox? = null) =
        WatchHomeView("YOU'RE CLEAR", false, "Nothing else today", null, emptyList(), null,
            WatchHomeRules.fast(FastingView.EMPTY, at(13)), inbox, emptyList(), null, prep)

    @Test
    fun beforeTheMeetingItCountsDownWithWhoAndTwoLines() {
        val v = WatchPrepRules.view(prep(at(13, 48)), at(13, 48), cal)!!
        assertEquals("PREP · IN 12 MIN", v.label)
        assertEquals("Quote review · 14:00", v.title)
        assertEquals("With Sam Taylor and Alex Green", v.who)
        assertEquals(2, v.lines.size)
        assertTrue(v.lines[0].startsWith("Sam Taylor · last email"), v.lines[0])
        // Three lines on Today (email, promise, waiting): the wrist shows one and counts the rest.
        assertEquals("+2 more", v.lines[1])
        assertEquals("Prep · Quote review · in 12 min", v.short)
        assertTrue(v.spoken.startsWith("Prep. Quote review at 14:00, in 12 min. With Sam Taylor and Alex Green."), v.spoken)
        assertTrue(" · " !in v.spoken)
    }

    @Test
    fun theCountdownIsWorkedOutAtTheMinuteNotWhenTodayWasBuilt() {
        val p = prep(at(13, 45))
        assertEquals("PREP · IN 5 MIN", WatchPrepRules.view(p, at(13, 55), cal)!!.label)
        assertEquals("PREP · NOW", WatchPrepRules.view(p, at(14, 0), cal)!!.label)
        assertEquals("PREP · STARTED 14:00", WatchPrepRules.view(p, at(14, 5), cal)!!.label)
        // Gone ten minutes after the start, like Today's card.
        assertNull(WatchPrepRules.view(p, at(14, 10), cal))
        assertNull(WatchPrepRules.view(null, at(13, 50), cal))
    }

    @Test
    fun withNothingKnownItSaysSoAndAHiddenMoreCountsWhatTodayHid() {
        val bare = WatchPrepRules.view(prep(at(13, 50), emptyList(), emptyList(), emptyList()), at(13, 50), cal)!!
        assertEquals(listOf(MeetingPrepRules.NOTHING_LINE), bare.lines)
        // Today already cut to "+2 more" after three lines (five in all): the watch says "+4 more" after one.
        val today = MeetingPrep("e1", at(14), "Prep · Quote review at 14:00 · in 10 min", "With Sam Taylor", emptyList(),
            listOf("a", "b", "c", "+2 more"), "", null, "Quote review")
        assertEquals(listOf("a", "+4 more"), WatchPrepRules.view(today, at(13, 50), cal)!!.lines)
    }

    @Test
    fun theTileCarriesItLitAndTheInboxGivesWay() {
        val v = WatchPrepRules.view(prep(at(13, 48)), at(13, 48), cal)!!
        val inbox = WatchInbox(label = "INBOX", lit = false, line = "6 need you", streakLine = null, short = "Inbox · 6 need you", spoken = "6 emails need you")
        val tile = WatchTileRules.tile(home(v, inbox))
        assertEquals("Prep · Quote review · in 12 min", tile.prepLine)
        assertNull(tile.inboxLine)
        assertTrue(tile.spoken.contains("Prep. Quote review at 14:00"))
        // It counts down, so the tile looks again the next minute.
        assertTrue(WatchTileRules.refreshAfterMs(home(v), at(13, 48) + 5_000) <= 60_000L)
        assertNull(WatchTileRules.tile(home(null)).prepLine)
    }
}
