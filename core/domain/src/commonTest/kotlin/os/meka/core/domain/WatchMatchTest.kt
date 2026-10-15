package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Barça match day, slice 4: the match on the Galaxy Watch (screen, tile and complication). */
class WatchMatchTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_746L // Tue 20 Oct 2026
    private fun at(h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val noMarks = EventMarks(hidden = emptySet(), prepTasks = emptyMap())

    private fun match(notes: String? = "Kick-off 20:00 UK time\nOn UK TV: TNT Sports 2 · HBO Max", provider: String = "fixtures", id: String = "espn-7") =
        CalendarEvent(
            id = id, title = "Barça v Getafe", startAtMs = at(20), endAtMs = at(22), allDay = false, location = null,
            provider = provider, account = null, calendarName = "LaLiga", description = notes,
        )

    private fun view(e: CalendarEvent, now: Long, marks: EventMarks = noMarks) =
        WatchMatchRules.view(WatchMatchRules.near(listOf(e), marks, now), now, cal)

    private fun home(match: WatchMatch?, inbox: WatchInbox? = null) =
        WatchHomeView("YOU'RE CLEAR", false, "Nothing else today", null, emptyList(), null,
            WatchHomeRules.fast(FastingView.EMPTY, at(14)), inbox, emptyList(), match)

    @Test
    fun fromTheHourBeforeKickOffItSaysWhenAndWhereItIsOn() {
        assertNull(view(match(), at(18, 59)))
        val m = view(match(), at(19, 10))!!
        assertEquals("BARÇA · 20:00", m.label)
        assertTrue(m.lit)
        assertEquals("Barça v Getafe", m.line)
        assertEquals("On TNT Sports 2 · HBO Max", m.note)
        assertEquals("Barça v Getafe · 20:00", m.short)
        assertFalse(m.live)
        assertEquals("Kick-off at 20:00. Barça v Getafe. On TNT Sports 2 · HBO Max.", m.spoken)
        // Kicked off with no score yet; the feed down says so.
        assertEquals("BARÇA · ON NOW", view(match(), at(20, 5))!!.label)
        assertEquals("Live score unavailable", view(match("Kick-off 20:00 UK time\nLive score unavailable"), at(20, 30))!!.note)
    }

    @Test
    fun liveItIsTheScoreAndTheLastGoalAndTheRingIsTheScore() {
        val e = match("Live: Barça 2–1 Getafe · 67'\nGoals: 23' Lewandowski (Barça) · 40' Mayoral pen (Getafe) · 51' Yamal (Barça)\nKick-off 20:00 UK time")
        val m = view(e, at(21, 10))!!
        assertEquals("BARÇA · LIVE · 67'", m.label)
        assertTrue(m.lit && m.live)
        assertEquals("Barça 2–1 Getafe", m.line)
        assertEquals("Last goal · Yamal 51'", m.note)
        assertEquals("Barça 2–1 Getafe · 67'", m.short)
        assertEquals("2–1", m.score)
        assertTrue(kotlin.math.abs(67f / 90f - m.played) < 0.001f)
        assertEquals("Live. Barça 2–1 Getafe. 67 minutes. Last goal · Yamal 51'.", m.spoken)

        val theirs = view(match("Live: Barça 1–1 Getafe · 40'\nGoals: 23' Lewandowski (Barça) · 40' Mayoral (Getafe)"), at(20, 45))!!
        assertEquals("Last goal · Mayoral 40' (Getafe)", theirs.note)
        val half = view(match("Live: Barça 1–1 Getafe · half time"), at(20, 50))!!
        assertEquals("BARÇA · HALF TIME", half.label)
        assertEquals(0.5f, half.played)
        assertEquals(1f, WatchMatchRules.played("90+3'"))

        val c = WatchTileRules.complication(home(m), 1, 4)
        assertEquals("2–1", c.text)
        assertEquals("Barça", c.title)
        val t = WatchTileRules.tile(home(m))
        assertEquals("Barça 2–1 Getafe · 67'", t.matchLine)
        assertTrue(t.matchLit)
        assertTrue(WatchTileRules.refreshAfterMs(home(m), at(21, 10)) <= 60_000L)
    }

    @Test
    fun fullTimeStaysForTheEveningThenGoes() {
        val e = match("Full time: Barça 2–1 Getafe\nGoals: 23' Lewandowski (Barça) · 40' Mayoral (Getafe) · 51' Yamal (Barça)")
        val m = view(e, at(22, 10))!!
        assertEquals("BARÇA · FULL TIME", m.label)
        assertFalse(m.lit)
        assertEquals("Barça won · Lewandowski 23', Yamal 51'", m.note)
        assertEquals("FT · Barça 2–1 Getafe", m.short)
        // The ring goes back to the day, and the tile stays calm.
        assertEquals("Done", WatchTileRules.complication(home(m), 1, 4).title)
        assertEquals(WatchTileRules.CALM_REFRESH_MS, WatchTileRules.refreshAfterMs(home(m), at(22, 10)))
        assertEquals("Barça won after extra time", view(match("Full time: Barça 2–1 Getafe · after extra time"), at(22, 30))!!.note)
        assertTrue(view(e, at(23, 59)) != null)
        assertNull(view(e, at(20) + WatchMatchRules.FULL_TIME_SHOWS_MIN * 60_000L))
    }

    @Test
    fun aLiveScoreTheFeedNeverFinishedGoesAfterThreeHours() {
        val e = match("Live: Barça 2–1 Getafe · 88'")
        assertTrue(view(e, at(22, 59)) != null)
        assertNull(view(e, at(23)))
    }

    @Test
    fun nothingForHiddenMatchesOtherCalendarsOrNoMatch() {
        assertNull(view(match(), at(19, 30), noMarks.copy(hidden = setOf("espn-7"))))
        assertNull(view(match(provider = "google"), at(19, 30)))
        assertNull(WatchMatchRules.view(emptyList(), at(19, 30), cal))
        val t = WatchTileRules.tile(home(null))
        assertNull(t.matchLine)
    }

    @Test
    fun theMatchTakesTheInboxsPlaceOnTheTile() {
        val inbox = WatchInbox("INBOX", false, "6 need you", null, "Inbox · 6 need you", "6 emails need you.")
        assertEquals("Inbox · 6 need you", WatchTileRules.tile(home(null, inbox)).inboxLine)
        val m = view(match(), at(19, 30))!!
        val t = WatchTileRules.tile(home(m, inbox))
        assertNull(t.inboxLine)
        assertEquals("Barça v Getafe · 20:00", t.matchLine)
        assertTrue(t.spoken.contains("Kick-off at 20:00"))
    }

    @Test
    fun theEarliestMatchDueLeads() {
        val first = match("Full time: Barça 1–0 Getafe").copy(id = "a")
        val next = match().copy(id = "b", startAtMs = at(23), endAtMs = at(23, 59))
        val near = WatchMatchRules.near(listOf(next, first), noMarks, at(22, 30))
        assertEquals(listOf("a", "b"), near.map { it.id })
        assertEquals("BARÇA · FULL TIME", WatchMatchRules.view(near, at(22, 30), cal)!!.label)
        // Once full time has gone, the next one leads.
        val later = at(20) + WatchMatchRules.FULL_TIME_SHOWS_MIN * 60_000L
        assertEquals("BARÇA · ON NOW", WatchMatchRules.view(WatchMatchRules.near(listOf(next, first), noMarks, later), later, cal)!!.label)
    }
}
