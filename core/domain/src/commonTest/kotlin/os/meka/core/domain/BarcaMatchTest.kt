package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Barça match day, slice 1: the heads-up an hour before kick-off, with where to watch it in the UK. */
class BarcaMatchTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_746L // Tue 20 Oct 2026
    private fun at(h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val noMarks = EventMarks(hidden = emptySet(), prepTasks = emptyMap())

    private fun match(
        title: String = "PSG v Barça", h: Int = 20, notes: String? = null, provider: String = "fixtures",
        competition: String? = "Champions League", id: String = "espn-7",
    ) = CalendarEvent(
        id = id, title = title, startAtMs = at(h), endAtMs = at(h + 2), allDay = false, location = null,
        provider = provider, account = null, calendarName = competition, description = notes,
    )

    @Test
    fun anHourBeforeKickOffItSaysWhereToWatch() {
        val e = match(notes = "Kick-off 20:00 UK time\nOn UK TV: TNT Sports 2 · HBO Max")
        val n = BarcaMatchRules.notices(listOf(e), noMarks, at(9), cal).single()
        assertEquals("PSG v Barça · 20:00", n.title)
        assertEquals("Champions League · kick-off in an hour · on TNT Sports 2 · HBO Max", n.text)
        assertEquals(at(19), n.atMs)
        assertEquals(at(20, 15), n.expiresAtMs)
        assertEquals(NoticeSource.BARCA, n.source)
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals(NoticeTarget.TODAY, n.target)
        assertEquals("barca:espn-7:${at(20)}", n.key)
        assertEquals("Kick-off 20:00 UK time", BarcaMatchRules.kickOffLine(e))
    }

    @Test
    fun saysWhenItIsNotOnUkTvOrNotListedYet() {
        assertEquals("not on UK TV", BarcaMatchRules.watchLine(match(notes = "Kick-off 20:00 UK time\nNot on UK TV, per the listings")))
        assertEquals("UK TV not listed yet", BarcaMatchRules.watchLine(match(notes = "Kick-off 20:00 UK time\nUK TV not listed yet")))
        assertEquals("UK TV not listed yet", BarcaMatchRules.watchLine(match(notes = null)))
        assertEquals("UK TV channel to be confirmed", BarcaMatchRules.watchLine(match(notes = "Kick-off 20:00 UK time\nUK TV: channel to be confirmed")))
        val n = BarcaMatchRules.notices(listOf(match(competition = null)), noMarks, at(9), cal).single()
        assertEquals("Kick-off in an hour · UK TV not listed yet", n.text)
    }

    @Test
    fun nothingForOtherCalendarsUnsetKickOffsHiddenOrPastMatches() {
        assertTrue(BarcaMatchRules.notices(listOf(match(provider = "google")), noMarks, at(9), cal).isEmpty())
        assertTrue(BarcaMatchRules.notices(listOf(match(title = "Sevilla v Barça (kick-off TBC)")), noMarks, at(9), cal).isEmpty())
        assertTrue(BarcaMatchRules.notices(listOf(match()), noMarks.copy(hidden = setOf("espn-7")), at(9), cal).isEmpty())
        assertTrue(BarcaMatchRules.notices(listOf(match()), noMarks, at(20, 15), cal).isEmpty())
        // A moved kick-off is a new heads-up.
        val moved = BarcaMatchRules.notices(listOf(match(h = 21)), noMarks, at(9), cal).single()
        assertEquals("barca:espn-7:${at(21)}", moved.key)
    }

    @Test
    fun theGovernorsCollectionCarriesIt() {
        assertTrue(NoticeSource.BARCA in NoticeSource.ADJUSTABLE)
        assertEquals("Barça match day", NoticeSource.BARCA.label)
    }

    // ---- Slice 2: the live score, goal alerts and full time ----

    private val liveNotes = "Live: Barça 2–1 Getafe · 67'\nGoals: 23' Lewandowski (Barça) · 40' Mayoral (Getafe) · 51' Yamal (Barça)\nKick-off 20:00 UK time"

    @Test
    fun readsTheScoreTheServerWrote() {
        val s = BarcaMatchRules.score(match(title = "Barça v Getafe", competition = "LaLiga", notes = liveNotes))!!
        assertTrue(s.live)
        assertEquals("Barça 2–1 Getafe", s.line)
        assertEquals("67'", s.detail)
        assertEquals(listOf("Lewandowski 23'", "Mayoral 40'", "Yamal 51'"), s.goals.map { it.short })
        assertEquals("Getafe", s.goals[1].team)
        assertEquals("Barça won", s.outcome)
        assertEquals(null, BarcaMatchRules.score(match(notes = "Kick-off 20:00 UK time\nOn UK TV: TNT Sports 2")))
        val away = BarcaMatchRules.score(match(notes = "Full time: PSG 1–1 Barça · after extra time"))!!
        assertEquals("A draw", away.outcome)
        assertEquals(1, away.barcaGoals)
        assertTrue(BarcaMatchRules.scoreUnavailable(match(notes = "Live score unavailable\nKick-off 20:00 UK time")))
    }

    @Test
    fun eachGoalIsAFreshHeadsUpInTheMatchsOnePlace() {
        val e = match(title = "Barça v Getafe", competition = "LaLiga", notes = liveNotes)
        val n = BarcaMatchRules.goalNotices(listOf(e), noMarks, at(21, 10)).single()
        assertEquals("Goal · Barça 2–1 Getafe", n.title)
        assertEquals("Yamal 51' · LaLiga · 67'", n.text)
        assertEquals("barca-goal:espn-7:2-1", n.key)
        assertEquals("barca-live:espn-7", n.postId)
        assertEquals(NoticeSource.BARCA_GOALS, n.source)
        // Theirs says who scored; 0–0 says nothing; a hidden match says nothing; three hours on it's stale.
        val theirs = match(notes = "Live: PSG 1–0 Barça · 12'\nGoals: 12' Dembélé (PSG)")
        assertEquals("PSG score · PSG 1–0 Barça", BarcaMatchRules.goalNotices(listOf(theirs), noMarks, at(20, 15)).single().title)
        assertTrue(BarcaMatchRules.goalNotices(listOf(match(notes = "Live: PSG 0–0 Barça · 5'")), noMarks, at(20, 5)).isEmpty())
        assertTrue(BarcaMatchRules.goalNotices(listOf(e), noMarks.copy(hidden = setOf("espn-7")), at(21)).isEmpty())
        assertTrue(BarcaMatchRules.goalNotices(listOf(e), noMarks, at(23)).isEmpty())
        // No goal list from the feed: the score alone.
        val bare = BarcaMatchRules.goalNotices(listOf(match(notes = "Live: PSG 0–1 Barça · half time")), noMarks, at(20, 50)).single()
        assertEquals("PSG 0–1 Barça", bare.title)
        assertEquals("Champions League · half time", bare.text)
    }

    @Test
    fun fullTimeSaysTheResultAndBarcasScorers() {
        val e = match(title = "Barça v Getafe", competition = "LaLiga", notes = liveNotes.replace("Live: Barça 2–1 Getafe · 67'", "Full time: Barça 2–1 Getafe"))
        assertTrue(BarcaMatchRules.goalNotices(listOf(e), noMarks, at(22)).isEmpty())
        val n = BarcaMatchRules.fullTimeNotices(listOf(e), noMarks, at(22)).single()
        assertEquals("Full time · Barça 2–1 Getafe", n.title)
        assertEquals("Barça won · Lewandowski 23', Yamal 51' · LaLiga", n.text)
        assertEquals("barca-ft:espn-7", n.key)
        assertEquals("barca-live:espn-7", n.postId)
        assertEquals(NoticeSource.BARCA_RESULT, n.source)
        assertTrue(BarcaMatchRules.fullTimeNotices(listOf(e), noMarks, at(20) + 360 * 60_000L).isEmpty())
        assertTrue(NoticeSource.BARCA_GOALS in NoticeSource.ADJUSTABLE && NoticeSource.BARCA_RESULT in NoticeSource.ADJUSTABLE)
    }

    @Test
    fun theNewsMatchdayLeadsWithTheScore() {
        val e = match(title = "Barça v Getafe", competition = "LaLiga", notes = liveNotes)
        val live = NewsRules.matchday(listOf(e), at(21, 7), cal)!!
        assertEquals("Barça 2–1 Getafe · 67'", live.line)
        assertTrue(live.live)
        val ft = NewsRules.matchday(listOf(e.copy(description = "Full time: Barça 2–1 Getafe")), at(21, 55), cal)!!
        assertEquals("Barça 2–1 Getafe · full time", ft.line)
        assertTrue(!ft.live)
        assertEquals("Barça v Getafe · 20:00 · on now", NewsRules.matchday(listOf(e.copy(description = null)), at(20, 5), cal)!!.line)
    }
}
