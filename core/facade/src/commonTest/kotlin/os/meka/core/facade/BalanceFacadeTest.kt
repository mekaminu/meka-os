package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.BalanceArea
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Balance view through the facade (slices 1–3): the review's week counted, targets set on one app reach the other. */
class BalanceFacadeTest {
    private var now = 1_792_163_100_000L // Fri 16 Oct 2026, 16:05 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theReviewsWeekIsCountedAndATargetSetOnTheFoldReachesTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        fold.tick()
        val v = fold.balanceView.value
        assertEquals(fold.reviewView.value.weekStart, v.weekStart)
        assertTrue(v.isCurrent)
        // Mon–Wed 8 h 30, Thursday's short day 6 h 30, Friday so far 7 h 05.
        val work = v.rows.first { it.area == BalanceArea.WORK }
        assertEquals("39 h 05 of your 40 h 30", work.line)
        assertEquals("0 of 3 targets met so far", v.line)
        // Slice 2: four weeks of trend, this one so far; the three before were his hours each.
        assertEquals(4, work.trend.size)
        assertTrue(work.trend.last().current)
        assertEquals("Last 4 weeks · never over · about 40 h 30 a week", work.trendLine)
        assertNull(v.rows.first { it.area == BalanceArea.FAMILY }.trendLine)
        // Slice 3: the week ahead (the rest of this week on a Friday) says what the plan leaves short.
        assertEquals(
            "Balance with what's planned · family 0 h of 8 h · health 0 h of 3 h · self 0 h of 2 h",
            fold.weekAheadView.value.balance,
        )
        assertTrue(fold.weekAheadView.value.spoken.contains("Balance with what's planned, family 0 hours of 8 hours, health 0 hours of 3 hours, self 0 hours of 2 hours."))

        assertEquals("Family: 10 h a week · on both devices", fold.setBalanceTarget("FAMILY", 600))
        assertNull(fold.setBalanceTarget("FAMILY", 600))
        assertNull(fold.setBalanceTarget("SLEEP", 600))
        assertEquals("0 h of 10 h", fold.balanceView.value.rows.first { it.area == BalanceArea.FAMILY }.line)
        assertTrue(fold.weekAheadView.value.balance!!.contains("family 0 h of 10 h"))

        fold.syncNow(); mac.syncNow()
        assertEquals("0 h of 10 h", mac.balanceView.value.rows.first { it.area == BalanceArea.FAMILY }.line)

        // Last week in the review: the balance follows it.
        mac.showReviewWeek(-1)
        assertEquals(mac.reviewView.value.weekStart, mac.balanceView.value.weekStart)
        assertEquals("40 h 30 of your 40 h 30", mac.balanceView.value.rows.first { it.area == BalanceArea.WORK }.line)
    }

    @Test
    fun thursdaysNudgeNamesTheAreaMostShortAndPostsOnce() = runTest {
        val fold = core("android")
        fun balance(r: os.meka.core.domain.GovernorResult) = r.post.filter { it.source == os.meka.core.domain.NoticeSource.BALANCE }
        // Thu 15 Oct, 16:55: the week has nothing for family, health or self, but the nudge waits for 17:00.
        now -= 86_400_000L - 50 * 60_000L
        fold.tick()
        val before = fold.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)
        assertTrue(balance(before).isEmpty())
        // 17:05: one heads-up naming the area most short.
        now += 10 * 60_000L
        fold.tick()
        val gov = fold.governNotifications(before.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL)
        val n = balance(gov).single()
        assertEquals("Family time is short this week", n.title)
        assertEquals("0 h of 8 h with what's planned · 8 h to find by Sunday · health and self short too", n.text)
        assertEquals(os.meka.core.domain.NoticeTarget.REVIEW, n.target)
        assertTrue(balance(fold.governNotifications(gov.stateEncoded, os.meka.core.domain.DeviceAlerts.ALL)).isEmpty()) // once a week
        // Saturday: gone.
        now += 2 * 86_400_000L
        fold.tick()
        assertTrue(balance(fold.governNotifications(null, os.meka.core.domain.DeviceAlerts.ALL)).isEmpty())
    }

    @Test
    fun countAsAnEventWithNoClearAreaCountsItOnBothAppsWithUndo() = runTest {
        val fold = core("android")
        val mac = core("mac")
        // A server-written event with no clear area: Wed 14 Oct, 19:00–20:00 in London (after his hours).
        val clock = os.meka.core.sync.HlcClock("server", { now })
        val start = now - 172_800_000L + 10_500_000L
        var k = 0
        mapOf(
            os.meka.core.domain.EventFields.TITLE to os.meka.core.sync.FieldValue.Text("Choir practice"),
            os.meka.core.domain.EventFields.START_AT to os.meka.core.sync.FieldValue.Int64(start),
            os.meka.core.domain.EventFields.END_AT to os.meka.core.sync.FieldValue.Int64(start + 3_600_000L),
            os.meka.core.domain.EventFields.ALL_DAY to os.meka.core.sync.FieldValue.Bool(false),
            os.meka.core.domain.EventFields.PROVIDER to os.meka.core.sync.FieldValue.Text("google"),
            os.meka.core.domain.EventFields.ACCOUNT to os.meka.core.sync.FieldValue.Text("a@example.com"),
            os.meka.core.domain.EventFields.CALENDAR to os.meka.core.sync.FieldValue.Text("Personal"),
            os.meka.core.domain.EventFields.REMOVED to os.meka.core.sync.FieldValue.Bool(false),
        ).forEach { (f, v) ->
            ops.append(os.meka.core.sync.Op("srvsw${k++}", "hh", os.meka.core.domain.EntityTypes.EVENT, "sw1", f, v, clock.now(), emptyList(), "server"))
        }
        fold.syncNow(); mac.syncNow()
        fold.tick()
        val before = fold.balanceView.value
        assertEquals("Choir practice", before.unclear.single().title)
        assertEquals("Wed · 1 h", before.unclear.single().line)
        assertEquals("1 event not counted · no clear area", before.uncounted)
        assertTrue(before.choices.isEmpty())

        assertEquals("“Choir practice” counts as health from now on · on both devices", fold.balanceCountAs("Choir practice", "HEALTH"))
        assertNull(fold.balanceCountAs("Choir practice", "HEALTH"))
        assertNull(fold.balanceCountAs("Choir practice", "SLEEP"))
        val after = fold.balanceView.value
        assertEquals("1 h of 3 h", after.rows.first { it.area == BalanceArea.HEALTH }.line)
        assertTrue(after.unclear.isEmpty())
        assertNull(after.uncounted)
        assertEquals("Choir practice · Health", after.choices.single().let { "${it.title} · ${it.label}" })

        fold.syncNow(); mac.syncNow()
        assertEquals("1 h of 3 h", mac.balanceView.value.rows.first { it.area == BalanceArea.HEALTH }.line)

        // Undo puts it back to no clear area; Don't count leaves it out without asking; Forget asks again.
        assertTrue(fold.balanceUndoCountAs())
        assertEquals(false, fold.balanceUndoCountAs())
        assertEquals("0 h of 3 h", fold.balanceView.value.rows.first { it.area == BalanceArea.HEALTH }.line)
        assertEquals(1, fold.balanceView.value.unclear.size)
        assertEquals("“Choir practice” won't be counted · on both devices", fold.balanceCountAs("choir  PRACTICE", "NONE"))
        assertTrue(fold.balanceView.value.unclear.isEmpty())
        assertNull(fold.balanceView.value.uncounted)
        assertEquals("Not counted", fold.balanceView.value.choices.single().label)
        assertEquals("“Choir practice” is back to no clear area · on both devices", fold.balanceCountAs("Choir practice", "CLEAR"))
        assertEquals(1, fold.balanceView.value.unclear.size)
    }
}
