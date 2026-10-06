package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationsTest {
    private val cal = LocalCalendar.UTC
    private val dayMs = CivilDate.DAY_MS
    private val hourMs = 3_600_000L
    private val minMs = 60_000L
    private val day = 20_000L // a fixed local day
    private fun at(h: Int, m: Int = 0, d: Long = day) = d * dayMs + h * hourMs + m * minMs
    private val settings = NotificationSettings.DEFAULT // quiet 22:00–07:00, digests 12:30 and 18:00

    private fun heads(key: String, atMs: Long, source: NoticeSource = NoticeSource.SHUTDOWN, expires: Long? = null) =
        Notice(key, source, NoticeTier.HEADS_UP, "Title $key", "text", atMs, NoticeTarget.TODAY, expires)
    private fun digestItem(key: String, source: NoticeSource = NoticeSource.CHASE) =
        Notice(key, source, NoticeTier.DIGEST, "Chase: $key", "Ada", at(0), NoticeTarget.LISTS)

    private fun eval(
        notices: List<Notice>, nowMs: Long, state: GovernorState = GovernorState(),
        s: NotificationSettings = settings, device: DeviceAlerts = DeviceAlerts.ALL,
    ) = Governor.evaluate(notices, s, device, state, nowMs, cal)

    @Test
    fun quietHoursCrossMidnightAndEndWhereTheySay() {
        val q = QuietHours.DEFAULT
        assertTrue(q.isQuiet(23 * 60)); assertTrue(q.isQuiet(3 * 60)); assertFalse(q.isQuiet(7 * 60)); assertFalse(q.isQuiet(12 * 60))
        assertEquals(at(7, d = day + 1), q.endAfter(at(23), cal))
        assertEquals(at(7), q.endAfter(at(2), cal))
        assertEquals(at(9), q.endAfter(at(9), cal))
        assertFalse(q.copy(enabled = false).isQuiet(23 * 60))
        val afternoon = QuietHours(true, 13 * 60, 15 * 60)
        assertTrue(afternoon.isQuiet(14 * 60)); assertFalse(afternoon.isQuiet(15 * 60))
        assertEquals("22:00–07:00", q.summary)
        assertEquals(q, QuietHours.decode(q.encode()))
        assertNull(QuietHours.decode("1;9999;0"))
    }

    @Test
    fun aHeadsUpPostsOnceAtItsTime() {
        val n = heads("shutdown", at(17, 30))
        assertTrue(eval(listOf(n), at(17)).post.isEmpty())
        val r = eval(listOf(n), at(17, 31))
        assertEquals(listOf("shutdown"), r.post.map { it.key })
        assertTrue(eval(listOf(n), at(17, 45), r.state).post.isEmpty(), "never twice")
    }

    @Test
    fun aHeadsUpInQuietHoursRidesInTheNextDigestInstead() {
        val n = heads("fast", at(23, d = day - 1), NoticeSource.FAST_GOAL)
        assertTrue(eval(listOf(n), at(23, 5, day - 1)).post.isEmpty())
        val morning = eval(listOf(n), at(8))
        assertTrue(morning.post.isEmpty(), "quiet hours ended, but it waits for the digest")
        val next = eval(listOf(n), at(12, 31), morning.state)
        val d = assertNotNull(next.digest)
        assertEquals("Midday digest · 1 thing", d.title)
        assertTrue(d.lines.single().startsWith("Title fast"))
        assertTrue("fast" in next.state.delivered)
    }

    @Test
    fun withDigestsOffAQuietHeadsUpPostsWhenQuietEnds() {
        val off = settings.copy(digestMinutes = emptyList())
        val n = heads("a", at(23, d = day - 1))
        assertTrue(eval(listOf(n), at(6), s = off).post.isEmpty())
        assertEquals(listOf("a"), eval(listOf(n), at(7, 1), s = off).post.map { it.key })
    }

    @Test
    fun aStaleHeadsUpIsDropped() {
        val n = heads("fast", at(9), NoticeSource.FAST_GOAL, expires = at(12))
        assertTrue(eval(listOf(n), at(12, 1)).post.isEmpty())
    }

    @Test
    fun criticalBreaksThroughQuietHoursAndActionWaitsForTheirEnd() {
        val critical = Notice("c", NoticeSource.SHUTDOWN, NoticeTier.CRITICAL, "c", "", at(2), NoticeTarget.NEEDS_YOU)
        val action = Notice("a", NoticeSource.SHUTDOWN, NoticeTier.ACTION, "a", "", at(2), NoticeTarget.NEEDS_YOU)
        val r = eval(listOf(critical, action), at(2, 1))
        assertEquals(listOf("c"), r.post.map { it.key })
        assertEquals(at(7), r.nextWakeMs, "wakes when quiet hours end")
        assertEquals(listOf("a"), eval(listOf(critical, action), at(7, 2), r.state).post.map { it.key })
    }

    @Test
    fun theDigestSumsUpWhatIsDueAtItsTimeOnly() {
        val items = listOf(digestItem("x"), digestItem("y"), digestItem("r", NoticeSource.RENEWAL))
        assertNull(eval(items, at(12)).digest)
        val r = eval(items, at(12, 35))
        val d = assertNotNull(r.digest)
        assertEquals("Midday digest · 3 things", d.title)
        assertEquals("1 renewal due · 2 to chase", d.summary)
        assertEquals("Chase: x · Ada", d.lines.first { it.startsWith("Chase: x") })
        assertEquals(NoticeTarget.LISTS, d.target)
        assertTrue(r.post.isEmpty())
        assertNull(eval(items, at(12, 50), r.state).digest, "once per slot")
        assertNotNull(eval(items, at(18, 1), r.state).digest, "the evening digest repeats what's still due")
        assertEquals("Evening digest · 3 things", eval(items, at(18, 1), r.state).digest!!.title)
    }

    @Test
    fun aMissedDigestGoesOutLateButNotHoursLate() {
        val items = listOf(digestItem("x"))
        assertNotNull(eval(items, at(14, 0)).digest)
        assertNull(eval(items, at(15, 0)).digest)
    }

    @Test
    fun nothingDueMeansNoDigestButTheSlotIsUsed() {
        val r = eval(emptyList(), at(12, 31))
        assertNull(r.digest)
        assertEquals(at(12, 30), r.state.lastDigestSlotMs)
        assertEquals(at(18), r.nextWakeMs)
    }

    @Test
    fun aHeadsUpDueAtDigestTimeRidesInIt() {
        val r = eval(listOf(heads("shutdown", at(18)), digestItem("x")), at(18, 2))
        assertTrue(r.post.isEmpty())
        assertEquals(2, r.digest!!.count)
        assertEquals("Title shutdown · text", r.digest!!.lines.first())
        assertTrue("shutdown" in r.state.delivered)
    }

    @Test
    fun aBurstOfHeadsUpsFoldsIntoOne() {
        val many = (1..4).map { heads("h$it", at(9)) }
        val r = eval(many, at(9, 1))
        assertTrue(r.post.isEmpty())
        assertEquals("4 things need you", r.digest!!.title)
        assertEquals(4, r.state.delivered.size)
        assertEquals(3, eval(many.take(3), at(9, 1)).post.size)
    }

    @Test
    fun theOwnerCanLowerASourceButNeverRaiseIt() {
        val s = settings.copy(tiers = mapOf(NoticeSource.SHUTDOWN to NoticeTier.SILENT))
        assertTrue(eval(listOf(heads("s", at(17, 30))), at(17, 31), s = s).post.isEmpty())
        val digestOnly = settings.copy(tiers = mapOf(NoticeSource.SHUTDOWN to NoticeTier.DIGEST))
        val r = eval(listOf(heads("s", at(17, 30))), at(18, 1), s = digestOnly)
        assertTrue(r.post.isEmpty()); assertEquals(1, r.digest!!.count)
        // Decoding refuses a raise (someone trying to make digests interrupt).
        assertEquals(emptyMap(), NotificationSettings.decodeTiers("CHASE=HEADS_UP,NOPE=SILENT"))
        assertEquals(mapOf(NoticeSource.CHASE to NoticeTier.SILENT), NotificationSettings.decodeTiers("CHASE=SILENT"))
    }

    @Test
    fun thisDeviceCanTakeDigestsOnlyOrNothing() {
        val n = heads("s", at(17, 30))
        assertTrue(eval(listOf(n), at(17, 31), device = DeviceAlerts.DIGESTS).post.isEmpty())
        val r = eval(listOf(n), at(18, 1), device = DeviceAlerts.DIGESTS)
        assertEquals(1, r.digest!!.count)
        val off = eval(listOf(n, digestItem("x")), at(18, 1), device = DeviceAlerts.OFF)
        assertTrue(off.post.isEmpty()); assertNull(off.digest); assertNull(off.nextWakeMs)
    }

    @Test
    fun theNextWakeIsTheSoonestOfDigestQuietEndAndAComingHeadsUp() {
        assertEquals(at(17, 30), eval(listOf(heads("s", at(17, 30))), at(13)).nextWakeMs)
        assertEquals(at(12, 30), eval(listOf(heads("s", at(17, 30))), at(9)).nextWakeMs)
        assertEquals(NoticePrecision.SOFT, eval(emptyList(), at(9)).nextWakePrecision)
        // Late evening: tomorrow's midday digest, unless quiet hours end first.
        assertEquals(at(7, d = day + 1), eval(emptyList(), at(23)).nextWakeMs)
    }

    @Test
    fun aDigestTimeInsideQuietHoursIsSkipped() {
        val s = settings.copy(quiet = QuietHours(true, 12 * 60, 13 * 60))
        assertNull(eval(listOf(digestItem("x")), at(12, 31), s = s).digest)
        assertEquals(at(18), Governor.nextDigestSlot(s, at(11), cal))
    }

    @Test
    fun stateRoundTripsAndForgetsOldKeys() {
        val st = GovernorState(mapOf("renewal:a:1" to 5L, "fast:b" to 9L), 42L)
        assertEquals(st, GovernorState.decode(st.encode()))
        assertEquals(GovernorState(), GovernorState.decode(null))
        val old = GovernorState(mapOf("old" to at(0) - Governor.KEEP_MS - 1))
        assertFalse("old" in eval(emptyList(), at(9), old).state.delivered)
    }

    @Test
    fun previewSaysWhatHappensNext() {
        val p = Governor.preview(listOf(digestItem("x"), heads("s", at(17))), settings, at(9), cal)
        assertEquals("Quiet hours 22:00–07:00", p.quietLine)
        assertEquals("Next digest 12:30 · 1 thing so far", p.digestLine)
        assertEquals("Quiet until 07:00", Governor.preview(emptyList(), settings, at(23), cal).quietLine)
        assertEquals("Next digest tomorrow 12:30 · nothing yet", Governor.preview(emptyList(), settings, at(19), cal).digestLine)
        assertEquals("No digests", Governor.preview(emptyList(), settings.copy(digestMinutes = emptyList()), at(9), cal).digestLine)
    }

    @Test
    fun sourcesTurnListsFastingAndShutdownIntoNotices() {
        val renewal = RenewalItem(
            id = "r1", title = "Car insurance", kind = ObligationKind.INSURANCE, subject = null, notes = null,
            dueDay = day + 5, cancelByDay = day + 1, leadDays = 21, costPence = 41200, repeats = RenewalRepeat.YEARLY,
            repeatLabel = "Every year", state = RenewalState.CANCEL_BY, meta = "renews …", doneLabel = "Renewed",
            stopLabel = "Cancelled it", hasConflict = false,
        )
        val lists = ListsView(
            waiting = emptyList(), someday = emptyList(), decisions = emptyList(),
            renewals = RenewalsView(listOf(renewal), emptyList(), emptyList(), 0, 0),
        )
        val fast = FastingView.EMPTY.copy(current = FastNow("f1", at(20, d = day - 1), 16, at(12), false, "", ""))
        val shutdown = ShutdownView.EMPTY.copy(startMinute = 17 * 60 + 30)
        val today = Today(emptyList(), null, emptyList(), emptyList())
        val notices = NoticeSources.collect(lists, fast, shutdown, today, at(9), cal)
        val cancel = notices.single { it.source == NoticeSource.RENEWAL_CANCEL_BY }
        assertEquals("Cancel or keep Car insurance?", cancel.title)
        assertEquals("Cancel by tomorrow", cancel.text)
        assertEquals(at(9), cancel.atMs)
        assertEquals(NoticeTier.DIGEST, notices.single { it.source == NoticeSource.RENEWAL }.tier)
        assertEquals(at(12), notices.single { it.source == NoticeSource.FAST_GOAL }.atMs)
        assertEquals(at(17, 30), notices.single { it.source == NoticeSource.SHUTDOWN }.atMs)
        // Shut down already: no nudge.
        val done = NoticeSources.collect(lists, fast, shutdown.copy(doneToday = true), today, at(9), cal)
        assertTrue(done.none { it.source == NoticeSource.SHUTDOWN })
        // The whole day end to end: 09:00 heads-up, 12:30 digest with the renewal, fasting goal rides in it.
        val r9 = eval(notices, at(9, 1))
        assertEquals(listOf(NoticeSource.RENEWAL_CANCEL_BY), r9.post.map { it.source })
        val r12 = eval(NoticeSources.collect(lists, fast, shutdown, today, at(12, 31), cal), at(12, 31), r9.state)
        assertEquals(2, r12.digest!!.count)
    }

    @Test
    fun settingsSyncBetweenDevices() {
        val world = SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        val pa = NotificationPrefs(a.replica)
        assertEquals(NotificationSettings.DEFAULT, pa.settings())
        pa.setQuietHours(QuietHours(true, 23 * 60, 6 * 60 + 30))
        pa.setDigest(NotificationSettings.MIDDAY, false)
        pa.setTier(NoticeSource.SHUTDOWN, NoticeTier.DIGEST)
        a.sync(); m.sync()
        val sm = NotificationPrefs(m.replica).settings()
        assertEquals("23:00–06:30", sm.quiet.summary)
        assertEquals(listOf(NotificationSettings.EVENING), sm.digestMinutes)
        assertEquals(NoticeTier.DIGEST, sm.tierFor(NoticeSource.SHUTDOWN))
        NotificationPrefs(m.replica).setTier(NoticeSource.SHUTDOWN, NoticeTier.HEADS_UP)
        NotificationPrefs(m.replica).setDigest(NotificationSettings.EVENING, false)
        m.sync(); a.sync()
        assertEquals(NoticeTier.HEADS_UP, pa.settings().tierFor(NoticeSource.SHUTDOWN))
        assertFalse(pa.settings().digestsOn)
    }
}
