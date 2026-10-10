package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The approvals inbox, slice 2: a Needs a decision notice when a request arrives, Critical in its last 15 minutes (in
 * the same place, so it replaces the first), never adjustable, and taken down once answered or run out.
 */
class ApprovalNoticesTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    private val cal = LocalCalendar.fixedOffset(hour)
    private val sun = CivilDate.toEpochDay(2026, 10, 11)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private val world = SyncWorld()
    private val fold = world.device("android")
    private val store = Approvals(fold.replica, { world.clock.nowMs }, cal)
    private val settings = NotificationSettings.DEFAULT // quiet hours 22:00–07:00

    init { world.clock.nowMs = at(sun, 20, 0) }

    private fun ask(ref: String, expires: Long?): String = assertNotNull(
        store.request(ApprovalKind.TRADE, "Kestrel", ref, "Buy 0.05 BTC at market", listOf("About £2,410"), expires),
    )

    private fun notices() = ApprovalRules.notices(ApprovalRules.pending(store.all(), world.clock.nowMs), cal)

    private fun govern(state: GovernorState = GovernorState(), s: NotificationSettings = settings) =
        Governor.evaluate(notices(), s, DeviceAlerts.ALL, state, world.clock.nowMs, cal)

    @Test
    fun aRequestIsANeedsADecisionNoticeThenCriticalInItsLast15MinutesInTheSamePlace() {
        val id = ask("sig-1", at(sun, 21, 30))
        val (first, ending) = notices()
        assertEquals(NoticeTier.ACTION, first.tier)
        assertEquals(NoticeSource.APPROVAL, first.source)
        assertEquals("Trade · Kestrel", first.title)
        assertEquals("Buy 0.05 BTC at market · answer by 21:30", first.text)
        assertEquals(at(sun, 20, 0), first.atMs)
        assertEquals(at(sun, 21, 15), first.expiresAtMs)
        assertEquals(NoticeTarget.NEEDS_YOU, first.target)
        assertTrue(first.actions.isEmpty()) // nothing approves from the notification

        assertEquals(NoticeTier.CRITICAL, ending.tier)
        assertEquals("Trade · Kestrel · expires 21:30", ending.title)
        assertEquals("Buy 0.05 BTC at market", ending.text)
        assertEquals(at(sun, 21, 15), ending.atMs)
        assertEquals(at(sun, 21, 30), ending.expiresAtMs)
        assertEquals("approval:$id", first.postId)
        assertEquals(first.postId, ending.postId)

        // Posted at once; the governor wakes for the last 15 minutes.
        val now = govern()
        assertEquals(listOf("approval:$id"), now.post.map { it.key })
        assertEquals(at(sun, 21, 15), now.nextWakeMs)
        world.clock.nowMs = at(sun, 21, 16)
        val later = govern(now.state)
        assertEquals(listOf("approval:$id:ending"), later.post.map { it.key })
        assertEquals("approval:$id", later.post.single().postId)
        assertTrue(later.withdraw.isEmpty())
    }

    @Test
    fun quietHoursHoldTheFirstNoticeButNotTheLast15Minutes() {
        world.clock.nowMs = at(sun, 23, 0)
        ask("night", at(sun + 1, 6, 0))
        assertTrue(govern().post.isEmpty())
        world.clock.nowMs = at(sun + 1, 5, 46)
        val r = govern()
        assertEquals(listOf(NoticeTier.CRITICAL), r.post.map { it.tier })
        assertEquals("Trade · Kestrel · expires 06:00", r.post.single().title)

        // Held overnight, the first notice names the day rather than saying "tomorrow" when read.
        world.clock.nowMs = at(sun, 23, 30)
        val long = ask("long", at(sun + 1, 22, 0))
        assertEquals("Buy 0.05 BTC at market · answer by Mon 12 Oct 22:00", notices().single { it.key == "approval:$long" }.text)
    }

    @Test
    fun aRequestAskedInItsLast15MinutesIsCriticalAtOnce() {
        val id = ask("short", at(sun, 20, 10))
        val only = notices().single()
        assertEquals(NoticeTier.CRITICAL, only.tier)
        assertEquals("approval:$id:ending", only.key)
        assertEquals(at(sun, 20, 0), only.atMs)
        assertEquals(listOf("approval:$id"), govern().post.map { it.postId })
    }

    @Test
    fun answeredOrRunOutTheNotificationComesDownOnce() {
        val id = ask("sig-2", at(sun, 21, 30))
        val posted = govern()
        assertEquals(1, posted.post.size)

        store.decide(id, approve = false, confirmation = ApprovalConfirmation.TAP, key = "k1", deviceId = "mac")
        val gone = govern(posted.state)
        assertEquals(listOf("approval:$id"), gone.withdraw)
        assertTrue(gone.post.isEmpty())
        assertFalse(gone.state.delivered.containsKey("approval:$id"))
        assertTrue(govern(gone.state).withdraw.isEmpty())

        // One that runs out without an answer comes down too; one never posted has nothing to take down.
        val other = ask("sig-3", at(sun, 20, 30))
        val shown = govern(gone.state)
        assertEquals(listOf("approval:$other"), shown.post.map { it.postId })
        world.clock.nowMs = at(sun, 20, 31)
        assertEquals(listOf("approval:$other"), govern(shown.state).withdraw)
        assertTrue(govern().withdraw.isEmpty())
    }

    @Test
    fun approvalsCantBeLoweredOrSilenced() {
        assertFalse(NoticeSource.APPROVAL.adjustable)
        assertFalse(NoticeSource.APPROVAL in NoticeSource.ADJUSTABLE)
        assertEquals(NoticeSource.ADJUSTABLE.size, NotifyRules.sourceCount)
        assertTrue(NotificationSettings.decodeTiers("APPROVAL=SILENT,SHUTDOWN=DIGEST").keys == setOf(NoticeSource.SHUTDOWN))
        ask("sig-4", at(sun, 21, 30))
        // Even a settings value written by hand doesn't lower it.
        val forced = settings.copy(tiers = mapOf(NoticeSource.APPROVAL to NoticeTier.SILENT))
        assertEquals(listOf(NoticeTier.ACTION), govern(s = forced).post.map { it.tier })
    }
}
