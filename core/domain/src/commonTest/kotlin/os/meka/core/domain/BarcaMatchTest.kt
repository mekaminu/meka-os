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
}
