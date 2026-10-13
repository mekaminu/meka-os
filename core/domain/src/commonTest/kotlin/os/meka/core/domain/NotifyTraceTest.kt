package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Notifications never seen, slice 1: the phone's notification trace and Health's Notifications row. */
class NotifyTraceTest {
    private val cal = LocalCalendar.UTC
    // Mon 12 Oct 2026 20:41 UTC.
    private val day = CivilDate.toEpochDay(2026, 10, 12)
    private val now = day * CivilDate.DAY_MS + (20 * 60 + 41) * HealthRules.MIN
    private val channels = listOf(
        NotifyChannelState("meka_heads_up", "Heads-ups", true),
        NotifyChannelState("meka_digest", "Digests", true),
    )

    @Test
    fun aLookSaysWhatWentWhereAndWhenItLooksNext() {
        val e = NotifyTrace.look(now, allowed = true, posted = 2, digest = false, channelNames = listOf("Heads-ups", "Heads-ups"), nextLookMs = now + 29 * HealthRules.MIN, cal = cal)
        assertEquals(NotifyTraceKind.POSTED, e.kind)
        assertEquals("2 notices on Heads-ups · next look 21:10", e.detail)
        assertEquals("${CivilDate.shortLabel(day)} 20:41 · Posted 2 notices on Heads-ups · next look 21:10", NotifyTrace.line(e, cal))

        val digest = NotifyTrace.look(now, true, 0, true, listOf("Digests"), null, cal)
        assertEquals("the digest on Digests", digest.detail)
        val quiet = NotifyTrace.look(now, true, 0, false, emptyList(), now + HealthRules.HOUR, cal)
        assertEquals(NotifyTraceKind.LOOKED, quiet.kind)
        assertEquals("nothing due · next look 21:41", quiet.detail)
        val off = NotifyTrace.look(now, false, 0, false, emptyList(), null, cal)
        assertEquals(NotifyTraceKind.BLOCKED, off.kind)
        assertEquals("notifications are off for Navilife", off.detail)
    }

    @Test
    fun eachChannelsLastPostIsKeptAndShown() {
        var posts = NotifyChannelPosts.record(emptyMap(), "meka_heads_up", now - HealthRules.HOUR)
        posts = NotifyChannelPosts.record(posts, "meka_heads_up", now - 2 * HealthRules.HOUR) // an older time never wins
        posts = NotifyChannelPosts.record(posts, "meka_work", now)
        posts = NotifyChannelPosts.record(posts, null, now)
        posts = NotifyChannelPosts.record(posts, "bad=id", now)
        assertEquals(mapOf("meka_heads_up" to now - HealthRules.HOUR, "meka_work" to now), posts)
        assertEquals(posts, NotifyChannelPosts.decode(NotifyChannelPosts.encode(posts)))
        assertEquals(emptyMap(), NotifyChannelPosts.decode("junk\n=5\nx=notanumber"))

        val all = NotifyChannelPosts.withPosts(channels + NotifyChannelState("meka_work", "Urgent while at work", true) + NotifyChannelState("meka_alarm", "Alarms", false), posts)
        assertEquals(
            listOf(
                "Urgent while at work · on · last sent ${HealthRules.at(now, now, cal)}",
                "Heads-ups · on · last sent ${HealthRules.at(now - HealthRules.HOUR, now, cal)}",
                "Alarms · off in Android's settings · nothing sent yet",
                "Digests · on · nothing sent yet",
            ),
            NotifyChannelPosts.lines(all, now, cal),
        )
    }

    @Test
    fun healthCountsPostsOutsideTheGovernor() {
        // The governor only looked, but a work alert posted on its own channel: Health says something was sent.
        val looked = NotifyTraceEntry(now - 5 * HealthRules.MIN, NotifyTraceKind.LOOKED, "nothing due")
        val withPost = channels.map { if (it.id == "meka_heads_up") it.copy(lastPostMs = now - 10 * HealthRules.MIN) else it }
        val row = NotifyTrace.healthRow(NotificationHealth(true, withPost, listOf(looked)), now, cal)!!
        assertEquals(HealthState.OK, row.state)
        assertTrue(row.line.contains("last sent ${HealthRules.at(now - 10 * HealthRules.MIN, now, cal)}"), row.line)
        val none = NotifyTrace.healthRow(NotificationHealth(true, channels, listOf(looked)), now, cal)!!
        assertTrue(none.line.contains("none sent yet"), none.line)
    }

    @Test
    fun quietLooksCollapseAndTheLogIsCapped() {
        val quiet = NotifyTraceEntry(now, NotifyTraceKind.LOOKED, "nothing due · next look 21:10")
        var log = NotifyTrace.add(emptyList(), quiet)
        log = NotifyTrace.add(log, quiet.copy(atMs = now + 1))
        assertEquals(1, log.size)
        assertEquals(now + 1, log[0].atMs)
        log = NotifyTrace.add(log, NotifyTraceEntry(now + 2, NotifyTraceKind.PUSH, "sync"))
        log = NotifyTrace.add(log, quiet.copy(atMs = now + 3))
        assertEquals(3, log.size, "a look after a push is a new line")
        repeat(100) { log = NotifyTrace.add(log, NotifyTraceEntry(now + 10 + it, NotifyTraceKind.PUSH)) }
        assertEquals(NotifyTrace.KEEP, log.size)
        assertEquals(now + 109, log.first().atMs)
    }

    @Test
    fun theTraceRoundTripsAndKeepsNoLineBreaksOrBars() {
        val log = listOf(
            NotifyTraceEntry(now, NotifyTraceKind.FAILED, "governor · IllegalStateException: bad\nline | here"),
            NotifyTraceEntry(now - 1, NotifyTraceKind.STARTED, "9 channels"),
        )
        val back = NotifyTrace.decode(NotifyTrace.encode(log))
        assertEquals(2, back.size)
        assertEquals("governor · IllegalStateException: bad line / here", back[0].detail)
        assertEquals(NotifyTraceKind.STARTED, back[1].kind)
        assertTrue(NotifyTrace.decode("junk\n12|NOPE|x").isEmpty())
        assertTrue(NotifyTrace.decode(null).isEmpty())
    }

    @Test
    fun aFailureKeepsTheClassAndAShortFirstLineOnly() {
        val e = NotifyTrace.failure(now, "governor", "android.database.sqlite.SQLiteException", "no such table: x\nat line 2")
        assertEquals(NotifyTraceKind.FAILED, e.kind)
        assertEquals("governor · SQLiteException: no such table: x", e.detail)
        assertEquals("governor · Error", NotifyTrace.failure(now, "governor", null, null).detail)
    }

    @Test
    fun theHealthRowSaysWhereNotificationsStop() {
        assertNull(NotifyTrace.healthRow(null, now, cal))

        val off = NotifyTrace.healthRow(NotificationHealth(false, channels, emptyList()), now, cal)!!
        assertEquals(HealthState.BAD to HealthFix.NOTIFICATIONS_ON, off.state to off.fix)
        assertEquals("Off for Navilife · nothing can reach you", off.line)

        val allOff = NotifyTrace.healthRow(NotificationHealth(true, channels.map { it.copy(on = false) }, emptyList()), now, cal)!!
        assertEquals(HealthState.BAD, allOff.state)

        val never = NotifyTrace.healthRow(NotificationHealth(true, channels, emptyList()), now, cal)!!
        assertEquals(HealthState.WARN, never.state)
        assertEquals("On · Navilife hasn't looked for anything to send yet", never.line)

        val looked = listOf(NotifyTraceEntry(now - 5 * HealthRules.MIN, NotifyTraceKind.LOOKED, "nothing due"))
        val quiet = NotifyTrace.healthRow(NotificationHealth(true, channels, looked), now, cal)!!
        assertEquals(HealthState.OK, quiet.state)
        assertEquals("On · none sent yet · last looked 20:36", quiet.line)

        val sent = listOf(NotifyTraceEntry(now - 2 * HealthRules.MIN, NotifyTraceKind.POSTED, "1 notice on Heads-ups")) + looked
        assertEquals("On · last sent 20:39 · last looked 20:39", NotifyTrace.healthRow(NotificationHealth(true, channels, sent), now, cal)!!.line)

        val digestsOff = NotifyTrace.healthRow(NotificationHealth(true, listOf(channels[0], channels[1].copy(on = false)), sent), now, cal)!!
        assertEquals(HealthState.WARN, digestsOff.state)
        assertEquals("Off in Android's settings: Digests · last sent 20:39", digestsOff.line)

        val failed = listOf(NotifyTraceEntry(now, NotifyTraceKind.FAILED, "governor · IllegalStateException")) + sent
        val bad = NotifyTrace.healthRow(NotificationHealth(true, channels, failed), now, cal)!!
        assertEquals(HealthState.BAD, bad.state)
        assertEquals("Couldn't post · governor · IllegalStateException", bad.line)

        // A push after a good look doesn't hide it.
        val pushed = listOf(NotifyTraceEntry(now, NotifyTraceKind.PUSH, "sync")) + sent
        assertEquals(HealthState.OK, NotifyTrace.healthRow(NotificationHealth(true, channels, pushed), now, cal)!!.state)
    }

    @Test
    fun theRowShowsInHealthOnlyWhenTheDeviceReportsIt() {
        val device = DeviceHealth(mac = false, notifications = NotificationHealth(false, channels, emptyList()))
        val facts = HealthFacts(device, connected = true, lastSyncedMs = now, server = null, accounts = null, ai = null, callAssistantOn = false, nowMs = now)
        val v = HealthRules.view(facts, cal)
        val row = v.rows.first { it.key == "notifications" }
        assertEquals(HealthState.BAD, row.state)
        assertTrue(v.critical)
        assertFalse(HealthRules.view(facts.copy(device = DeviceHealth(mac = true)), cal).rows.any { it.key == "notifications" })
    }

    @Test
    fun theFirstOpenAsksOnlyOnAndroid13OnceAndWhenNotAllowed() {
        assertTrue(NotifyTrace.askOnFirstOpen(34, granted = false, askedBefore = false))
        assertFalse(NotifyTrace.askOnFirstOpen(34, granted = false, askedBefore = true))
        assertFalse(NotifyTrace.askOnFirstOpen(34, granted = true, askedBefore = false))
        assertFalse(NotifyTrace.askOnFirstOpen(32, granted = false, askedBefore = false))
    }
}
