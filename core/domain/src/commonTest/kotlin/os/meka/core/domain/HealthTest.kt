package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Health screen's rules (Reliability first, item 3). */
class HealthTest {
    private val cal = LocalCalendar.UTC
    // Fri 9 Oct 2026 14:05 UTC.
    private val day = CivilDate.toEpochDay(2026, 10, 9)
    private val now = day * CivilDate.DAY_MS + (14 * 60 + 5) * HealthRules.MIN
    private val ai = AiStatusView("On · $1.20 of $20 this month", lit = false, canAsk = true)
    private val server = ServerHealth(ServerHealth.PUSH_ON, calls = true, speech = true, atMs = now)
    private val google = HealthAccount("google", "meka@gmail.com", "Personal", "ok", now - 3 * HealthRules.MIN)
    private val feeds = listOf(
        HealthAccount("weather", "home", "Weather", "ok", now - 20 * HealthRules.MIN),
        HealthAccount("news", "bbc", "Headlines", "ok", now - 50 * HealthRules.MIN),
        HealthAccount("news_more", "more", "Headlines", "ok", now - 50 * HealthRules.MIN),
        HealthAccount("fixtures", "barca", "Fixtures", "ok", now - 5 * HealthRules.MIN),
    )
    private val fold = DeviceHealth(mac = false, batteryExempt = true, notificationAccess = true, lastNotificationMs = now - 10 * HealthRules.MIN, callRoleHeld = true, version = "0.1.214")

    private fun facts(
        device: DeviceHealth = fold,
        server: ServerHealth? = this.server,
        accounts: List<HealthAccount>? = listOf(google) + feeds,
        signIns: List<SignIn> = emptyList(),
        ai: AiStatusView? = this.ai,
        callAssistantOn: Boolean = true,
        connected: Boolean = true,
        syncTrouble: String? = null,
        syncFailing: Boolean = false,
    ) = HealthFacts(
        device = device, connected = connected, lastSyncedMs = now - 2 * HealthRules.MIN, syncTrouble = syncTrouble, syncFailing = syncFailing,
        server = server, accounts = accounts, signIns = signIns, ai = ai, callAssistantOn = callAssistantOn,
        lastVoiceMessageMs = now - 26 * HealthRules.HOUR, nowMs = now,
    )

    private fun HealthView.row(key: String) = rows.first { it.key == key }

    @Test
    fun everythingWorkingIsAllTicksAndNothingOnToday() {
        val v = HealthRules.view(facts(), cal)
        assertEquals(
            listOf("server", "push", "calendar:google:meka@gmail.com", "feeds", "messages", "calls", "ai", "voice", "battery", "update"),
            v.rows.map { it.key },
        )
        assertTrue(v.rows.all { it.state == HealthState.OK }, v.rows.filter { it.state != HealthState.OK }.toString())
        assertEquals("Everything's working", v.summary)
        assertEquals(0, v.attention)
        assertFalse(v.critical)
        assertNull(v.todayLine)
        assertEquals("Connected · last synced 14:03", v.row("server").line)
        assertEquals("Google · meka@gmail.com · synced 14:02", v.row("calendar:google:meka@gmail.com").line)
        assertEquals("Weather, Headlines, Fixtures · up to date", v.row("feeds").line)
        assertEquals("On · last voice message Thu 8 Oct 12:05", v.row("calls").line)
        assertEquals("Version 0.1.214 · up to date", v.row("update").line)
    }

    @Test
    fun theMacLeavesOutWhatOnlyThePhoneHas() {
        val v = HealthRules.view(facts(device = DeviceHealth(mac = true, version = "0.1.9")), cal)
        assertEquals(listOf("server", "calendar:google:meka@gmail.com", "feeds", "calls", "ai", "voice", "update"), v.rows.map { it.key })
        assertEquals("Everything's working", v.summary)
    }

    @Test
    fun theServerOutOfReachIsBrokenAndWhatItKnowsIsUnknownNotTicked() {
        val v = HealthRules.view(facts(server = null, accounts = null, ai = null), cal)
        assertEquals(HealthState.BAD, v.row("server").state)
        assertEquals("Can't reach it right now · last synced 14:03", v.row("server").line)
        assertEquals(HealthFix.SYNC_NOW, v.row("server").fix)
        listOf("push", "calendar:", "calls", "ai", "voice").forEach { assertEquals(HealthState.UNKNOWN, v.row(it).state, it) }
        assertEquals(1, v.attention)
        assertTrue(v.critical)
        assertEquals("MEKA's server · Can't reach it right now · last synced 14:03", v.todayLine)
        // Not connected at all.
        assertEquals("This device isn't connected yet", HealthRules.view(facts(connected = false), cal).row("server").line)
        // Failing (signed out) and offline with changes waiting.
        val failing = HealthRules.view(facts(syncTrouble = "signed out", syncFailing = true), cal).row("server")
        assertEquals("Sync is failing · signed out" to HealthState.BAD, failing.line to failing.state)
        val offline = HealthRules.view(facts(syncTrouble = "Offline · 3 changes waiting"), cal).row("server")
        assertEquals("Offline · 3 changes waiting · last synced 14:03" to HealthState.WARN, offline.line to offline.state)
    }

    @Test
    fun aClockFarFromTheServersIsWorthALook() {
        val v = HealthRules.view(facts(server = server.copy(atMs = now + 7 * HealthRules.MIN)), cal)
        assertEquals("This device's clock is 7 min off · set the time automatically", v.row("server").line)
        assertEquals(HealthState.WARN, v.row("server").state)
        assertEquals(HealthState.OK, HealthRules.view(facts(server = server.copy(atMs = now + 2 * HealthRules.MIN)), cal).row("server").state)
    }

    @Test
    fun pushSaysWhetherThisPhoneGetsWakeUps() {
        assertEquals(HealthState.WARN, HealthRules.view(facts(server = server.copy(push = ServerHealth.PUSH_MISSING)), cal).row("push").state)
        assertEquals(
            "Not set up on the server · changes arrive within 15 min",
            HealthRules.view(facts(server = server.copy(push = ServerHealth.PUSH_OFF)), cal).row("push").line,
        )
    }

    @Test
    fun calendarsSayExpiredEndingFailingAndStaleWithReconnect() {
        val expired = HealthRules.view(facts(accounts = listOf(google.copy(status = "needs_reconnect", lastSyncAtMs = now - 3 * HealthRules.HOUR)) + feeds), cal)
            .row("calendar:google:meka@gmail.com")
        assertEquals(HealthState.BAD, expired.state)
        assertEquals("Google sign-in expired · not updating since 11:05", expired.line)
        assertEquals(HealthFix.RECONNECT to "google", expired.fix to expired.provider)
        assertEquals("meka@gmail.com", expired.account)

        val ending = SignIn("google", "meka@gmail.com", SignInState.ENDING, now + 20 * HealthRules.HOUR, canEdit = true)
        val e = HealthRules.view(facts(signIns = listOf(ending)), cal).row("calendar:google:meka@gmail.com")
        assertEquals("Google sign-in ends Sat 10 Oct 10:05" to HealthState.WARN, e.line to e.state)
        assertTrue(e.editing)

        val stale = HealthRules.view(facts(accounts = listOf(google.copy(lastSyncAtMs = now - 45 * HealthRules.MIN)) + feeds), cal)
        assertEquals("Not updated since 13:20", stale.row("calendar:google:meka@gmail.com").line)
        // Calendar trouble has Today's sign-in line already, so the health line stays away.
        assertNull(stale.todayLine)
        assertEquals("The last check failed · synced 14:02",
            HealthRules.view(facts(accounts = listOf(google.copy(status = "error")) + feeds), cal).row("calendar:google:meka@gmail.com").line)

        val none = HealthRules.view(facts(accounts = feeds), cal).row("calendar:")
        assertEquals(HealthFix.CALENDARS to HealthState.WARN, none.fix to none.state)
    }

    @Test
    fun feedsGoStaleOnTheirOwnClocks() {
        val oldWeather = feeds.map { if (it.provider == "weather") it.copy(lastSyncAtMs = now - 3 * HealthRules.HOUR) else it }
        val v = HealthRules.view(facts(accounts = listOf(google) + oldWeather), cal)
        assertEquals("Weather not updated since 11:05" to HealthState.WARN, v.row("feeds").line to v.row("feeds").state)
        assertEquals("Feeds · Weather not updated since 11:05", v.todayLine)
        // Headlines are fine while either feed is.
        val oneNews = feeds.map { if (it.provider == "news") it.copy(status = "error") else it }
        assertEquals(HealthState.OK, HealthRules.view(facts(accounts = listOf(google) + oneNews), cal).row("feeds").state)
        val bothNews = feeds.map { if (it.provider.startsWith("news")) it.copy(status = "error") else it }
        assertEquals("Headlines failing", HealthRules.view(facts(accounts = listOf(google) + bothNews), cal).row("feeds").line)
        // Fixtures may sit a day.
        val oldFixtures = feeds.map { if (it.provider == "fixtures") it.copy(lastSyncAtMs = now - 20 * HealthRules.HOUR) else it }
        assertEquals(HealthState.OK, HealthRules.view(facts(accounts = listOf(google) + oldFixtures), cal).row("feeds").state)
    }

    @Test
    fun messagesCaptureNeedsAccessAndNoticesALongSilence() {
        val off = HealthRules.view(facts(device = fold.copy(notificationAccess = false)), cal)
        assertEquals(HealthFix.NOTIFICATION_ACCESS to HealthState.BAD, off.row("messages").fix to off.row("messages").state)
        assertTrue(off.critical)
        assertEquals("Messages capture · MEKA can't see notifications · messages aren't captured", off.todayLine)
        val quiet = HealthRules.view(facts(device = fold.copy(lastNotificationMs = now - 13 * HealthRules.HOUR)), cal).row("messages")
        assertEquals("Nothing read since 01:05 · Android may have stopped it" to HealthState.WARN, quiet.line to quiet.state)
        assertEquals("Listening · nothing read yet", HealthRules.view(facts(device = fold.copy(lastNotificationMs = null)), cal).row("messages").line)
    }

    @Test
    fun theCallAssistantNeedsTheServiceAndTheRoleOnlyWhileOn() {
        val off = HealthRules.view(facts(callAssistantOn = false, device = fold.copy(callRoleHeld = false), server = server.copy(calls = false)), cal).row("calls")
        assertEquals(HealthState.OK to HealthFix.WORK, off.state to off.fix)
        val noService = HealthRules.view(facts(server = server.copy(calls = false)), cal).row("calls")
        assertEquals(HealthState.BAD, noService.state)
        val noRole = HealthRules.view(facts(device = fold.copy(callRoleHeld = false)), cal).row("calls")
        assertEquals(HealthFix.CALL_ROLE to HealthState.BAD, noRole.fix to noRole.state)
    }

    @Test
    fun aiVoiceBatteryAndUpdates() {
        val over = HealthRules.view(facts(ai = AiStatusView("Used up · $20 of $20 this month", lit = true, canAsk = false)), cal).row("ai")
        assertEquals(HealthState.WARN, over.state)
        assertEquals(HealthState.UNKNOWN, HealthRules.view(facts(ai = AskRules.STATUS_UNKNOWN), cal).row("ai").state)
        assertEquals(HealthState.WARN, HealthRules.view(facts(server = server.copy(speech = false)), cal).row("voice").state)

        val sleepy = HealthRules.view(facts(device = fold.copy(batteryExempt = false)), cal)
        assertEquals(HealthFix.BATTERY to HealthState.WARN, sleepy.row("battery").fix to sleepy.row("battery").state)
        // Battery has its own line on Today.
        assertNull(sleepy.todayLine)
        assertEquals(HealthState.BAD, HealthRules.view(facts(device = fold.copy(batteryStopped = true)), cal).row("battery").state)

        val update = HealthRules.view(facts(device = fold.copy(updateReady = true)), cal)
        assertEquals("Version 0.1.214 · a newer build is ready", update.row("update").line)
        assertEquals(HealthFix.INSTALL_UPDATE, update.row("update").fix)
    }

    @Test
    fun theSummaryCountsWhatNeedsALook() {
        val v = HealthRules.view(facts(device = fold.copy(notificationAccess = false, batteryExempt = false), server = server.copy(speech = false)), cal)
        assertEquals(3, v.attention)
        assertEquals("3 things need a look", v.summary)
        assertEquals("MEKA health · 2 things need a look", v.todayLine)
        assertEquals("Working, as far as MEKA can tell", HealthRules.view(facts(ai = null), cal).summary)
    }

    @Test
    fun timesReadNaturally() {
        assertEquals("just now", HealthRules.at(now - 20_000, now, cal))
        assertEquals("09:30", HealthRules.at(day * CivilDate.DAY_MS + 570 * HealthRules.MIN, now, cal))
        assertEquals("Thu 8 Oct 23:59", HealthRules.at(day * CivilDate.DAY_MS - HealthRules.MIN, now, cal))
    }
}
