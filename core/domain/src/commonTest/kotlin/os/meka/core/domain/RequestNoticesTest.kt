package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Requests from people Meka watches: the digest item, or a heads-up straight away for the people he chose. */
class RequestNoticesTest {
    private val cal = LocalCalendar.UTC
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun at(minute: Int, day: Long = fri) = cal.toEpochMs(day, minute)

    private fun card(person: String, id: String, minute: Int) = MessageRequestRules.card(
        RequestMessage(id, person, "can you pick up the dry cleaning tomorrow?", at(minute)), 0,
        RequestProposal(RequestKind.TASK, "Pick up dry cleaning", fri + 1, null), fri, cal,
    )

    private val wife = card("Wife", "wa-1", 14 * 60 + 2)
    private val mum = card("Mum", "wa-2", 14 * 60 + 5)

    private fun eval(notices: List<Notice>, nowMs: Long, s: NotificationSettings, state: GovernorState = GovernorState()) =
        Governor.evaluate(notices, s, DeviceAlerts.ALL, state, nowMs, cal)

    @Test
    fun aRequestIsADigestItemWithTheProposalNeverTheQuote() {
        val n = NoticeSources.requestNotices(listOf(wife), NotificationSettings.DEFAULT).single()
        assertEquals(NoticeSource.REQUEST, n.source)
        assertEquals(NoticeTier.DIGEST, n.tier)
        assertEquals("request:wa-1#0", n.key)
        assertEquals("From Wife · 14:02", n.title)
        assertEquals("Add task: Pick up dry cleaning · Tomorrow", n.text)
        assertEquals(wife.atMs, n.atMs)
        assertEquals(NoticeTarget.NEEDS_YOU, n.target)
        assertFalse("dry cleaning tomorrow?" in n.title + n.text, "the message itself stays in the app")

        val notices = NoticeSources.requestNotices(listOf(wife, mum), NotificationSettings.DEFAULT)
        assertTrue(eval(notices, at(14 * 60 + 10), NotificationSettings.DEFAULT).post.isEmpty(), "no buzz at the time")
        val digest = assertNotNull(eval(notices, at(18 * 60 + 1), NotificationSettings.DEFAULT).digest)
        assertEquals("2 requests", digest.summary)
        assertEquals("From Wife · 14:02 · Add task: Pick up dry cleaning · Tomorrow", digest.lines.first())
        assertEquals(NoticeTarget.NEEDS_YOU, digest.target)
    }

    @Test
    fun notifyStraightAwayMakesThatPersonsRequestsAHeadsUp() {
        val s = NotificationSettings(requestNow = setOf("wife"))
        val notices = NoticeSources.requestNotices(listOf(wife, mum), s)
        assertEquals(listOf(NoticeTier.HEADS_UP, NoticeTier.DIGEST), notices.map { it.tier })
        // The 12:30 digest already went out.
        val r = eval(notices, at(14 * 60 + 6), s, GovernorState(lastDigestSlotMs = at(12 * 60 + 30)))
        assertEquals(listOf("request:wa-1#0"), r.post.map { it.key })
        assertTrue(eval(notices, at(14 * 60 + 8), s, r.state).post.isEmpty(), "never twice")
        // Seen too late (the phone was off): it waits in Needs you, no stale buzz.
        assertTrue(eval(notices, wife.atMs + NoticeSources.REQUEST_HEADS_UP_STALE_MS + 1, s).post.isEmpty())
        // In quiet hours it rides in the next digest like any heads-up.
        val late = card("Wife", "wa-3", 23 * 60)
        val quiet = NoticeSources.requestNotices(listOf(late), s)
        assertTrue(eval(quiet, at(23 * 60 + 1), s).post.isEmpty())
    }

    @Test
    fun loweringTheSourceStillWinsOverNotifyStraightAway() {
        val s = NotificationSettings(tiers = mapOf(NoticeSource.REQUEST to NoticeTier.SILENT), requestNow = setOf("Wife"))
        val notices = NoticeSources.requestNotices(listOf(wife), s)
        assertTrue(eval(notices, at(14 * 60 + 3), s).post.isEmpty())
        assertNull(eval(notices, at(18 * 60 + 1), s).digest)
        // Heads-up is above the source's default, so it can't be chosen as the source's tier.
        assertEquals(emptyMap(), NotificationSettings.decodeTiers("REQUEST=HEADS_UP"))
    }

    @Test
    fun notifyStraightAwaySyncsAndMatchesAnySpelling() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        val prefs = NotificationPrefs(fold.replica)
        prefs.setRequestNow("  Wife ", true)
        prefs.setRequestNow("WIFE", true) // no second entry
        prefs.setRequestNow("Mum", true)
        assertEquals(setOf("Wife", "Mum"), prefs.settings().requestNow)
        fold.sync(); mac.sync()
        val onMac = NotificationPrefs(mac.replica).settings()
        assertTrue(onMac.notifiesNow("wife"))
        assertTrue(onMac.notifiesNow("Mum"))
        assertFalse(onMac.notifiesNow("Ada"))
        prefs.setRequestNow("wife", false)
        assertEquals(setOf("Mum"), prefs.settings().requestNow)
        assertEquals("", NotificationSettings.encodeRequestNow(emptySet()))
        assertEquals(emptySet(), NotificationSettings.decodeRequestNow(""))
        assertEquals(emptySet(), NotificationSettings.decodeRequestNow(null))
    }

    @Test
    fun collectIncludesOpenRequestCards() {
        val notices = NoticeSources.collect(
            ListsView.EMPTY, FastingView.EMPTY, ShutdownView.EMPTY, Today(emptyList(), null, emptyList(), emptyList()), at(15 * 60), cal,
            requests = listOf(wife), settings = NotificationSettings.DEFAULT,
        )
        assertEquals(listOf("request:wa-1#0"), notices.filter { it.source == NoticeSource.REQUEST }.map { it.key })
    }
}
