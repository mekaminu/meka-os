package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixtureMovesTest {
    private val cal = LocalCalendar.fixedOffset(3_600_000L) // BST
    private val tue6 = 20732L
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private fun fixture(start: Long, from: Long?, movedAt: Long? = at(tue6, 9), id: String = "evf") =
        CalendarEvent(id, "Barcelona v Real Madrid", start, start + 2 * 3_600_000L, false, "Camp Nou", "fixtures", null, "FC Barcelona",
            movedFromMs = from, movedAtMs = movedAt)

    @Test
    fun aMovedKickOffIsAHeadsUpOncePerNewTime() {
        val sat = tue6 + 4
        val e = fixture(at(sat, 21), from = at(sat, 18, 30))
        val n = FixtureMoves.notices(listOf(e), EventMarks.NONE, at(tue6, 9, 5), cal).single()
        assertEquals(NoticeSource.FIXTURE_MOVED, n.source)
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals("Kick-off moved: Barcelona v Real Madrid", n.title)
        assertEquals("Now 21:00 · was 18:30 · Sat 10 Oct", n.text)
        assertEquals(at(tue6, 9), n.atMs)
        assertEquals(at(tue6, 9) + FixtureMoves.STALE_MS, n.expiresAtMs)
        assertEquals(NoticeTarget.TODAY, n.target)
        // Moved again: a new key, so it posts again.
        val again = FixtureMoves.notices(listOf(fixture(at(sat + 1, 18, 30), from = at(sat, 21))), EventMarks.NONE, at(tue6, 10), cal).single()
        assertTrue(again.key != n.key)
        assertEquals("Now Sun 11 Oct 18:30 · was Sat 10 Oct 21:00", again.text)

        // Through the governor: posts once.
        val s = NotificationSettings.DEFAULT
        val r = Governor.evaluate(listOf(n), s, DeviceAlerts.ALL, GovernorState(), at(tue6, 9, 5), cal)
        assertEquals(listOf(n.key), r.post.map { it.key })
        assertTrue(Governor.evaluate(listOf(n), s, DeviceAlerts.ALL, r.state, at(tue6, 9, 6), cal).post.isEmpty())
    }

    @Test
    fun onlyRealUpcomingVisibleFixtureMovesNotify() {
        val sat = tue6 + 4
        val now = at(tue6, 9, 5)
        val none = EventMarks.NONE
        // Never moved, moved back to where it was, already kicked off, hidden, not a fixture: nothing.
        assertTrue(FixtureMoves.notices(listOf(fixture(at(sat, 21), from = null, movedAt = null)), none, now, cal).isEmpty())
        assertTrue(FixtureMoves.notices(listOf(fixture(at(sat, 21), from = at(sat, 21))), none, now, cal).isEmpty())
        assertTrue(FixtureMoves.notices(listOf(fixture(at(tue6, 8), from = at(tue6, 7))), none, now, cal).isEmpty())
        assertTrue(FixtureMoves.notices(listOf(fixture(at(sat, 21), from = at(sat, 18))), EventMarks(setOf("evf"), emptyMap()), now, cal).isEmpty())
        val personal = fixture(at(sat, 21), from = at(sat, 18)).copy(provider = "google")
        assertTrue(FixtureMoves.notices(listOf(personal), none, now, cal).isEmpty())
        // A move close to kick-off goes stale at kick-off.
        val soon = fixture(at(tue6, 20), from = at(tue6, 19), movedAt = at(tue6, 9))
        assertEquals(at(tue6, 20), FixtureMoves.notices(listOf(soon), none, now, cal).single().expiresAtMs)
        // In collect, beside the other sources.
        val all = NoticeSources.collect(
            ListsView.EMPTY, FastingView.EMPTY, ShutdownView.EMPTY, Today(emptyList(), null, emptyList(), emptyList()), now, cal,
            events = listOf(fixture(at(sat, 21), from = at(sat, 18))),
        )
        assertEquals(1, all.count { it.source == NoticeSource.FIXTURE_MOVED })
    }
}
