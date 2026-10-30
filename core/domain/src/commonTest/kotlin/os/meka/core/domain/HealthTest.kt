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
            listOf("server", "push", "calendar:google:meka@gmail.com", "feeds", "messages", "calls", "spam", "ai", "voice", "battery", "update"),
            v.rows.map { it.key },
        )
        assertTrue(v.rows.all { it.state == HealthState.OK }, v.rows.filter { it.state != HealthState.OK }.toString())
        assertEquals("Everything's working", v.summary)
        assertEquals(0, v.attention)
        assertFalse(v.critical)
        assertNull(v.todayLine)
        assertEquals("Connected · last synced 14:03", v.row("server").line)
        assertEquals("Google · synced 14:02", v.row("calendar:google:meka@gmail.com").line)
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
        assertEquals("Navilife's server · Can't reach it right now · last synced 14:03", v.todayLine)
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
        assertEquals("Messages capture · Navilife can't see notifications · messages aren't captured", off.todayLine)
        val quiet = HealthRules.view(facts(device = fold.copy(lastNotificationMs = now - 13 * HealthRules.HOUR)), cal).row("messages")
        assertEquals("Nothing read since 01:05 · Android may have stopped it" to HealthState.WARN, quiet.line to quiet.state)
        assertEquals("Listening · nothing read yet", HealthRules.view(facts(device = fold.copy(lastNotificationMs = null)), cal).row("messages").line)
    }

    @Test
    fun theCallAssistantNeedsTheServiceAndTheRoleOnlyWhileOn() {
        val off = HealthRules.view(facts(callAssistantOn = false, device = fold.copy(callRoleHeld = false), server = server.copy(calls = false)), cal).row("calls")
        // The walk-through's A7: switched off by his choice is its own grey state, not the working green, and never counted.
        assertEquals(HealthState.OFF to HealthFix.WORK, off.state to off.fix)
        assertFalse(off.needsLook)
        val offOnly = HealthRules.view(facts(callAssistantOn = false), cal)
        assertEquals(HealthState.OFF, offOnly.row("calls").state)
        assertEquals(0, offOnly.attention)
        assertEquals("Everything's working", offOnly.summary)
        val noService = HealthRules.view(facts(server = server.copy(calls = false)), cal).row("calls")
        assertEquals(HealthState.BAD, noService.state)
        // Spam calls still ring, slice 1: the lost role's Fix (and Today's red line) is on the Spam calls row.
        val noRoleView = HealthRules.view(facts(device = fold.copy(callRoleHeld = false)), cal)
        assertEquals(HealthState.WARN, noRoleView.row("calls").state)
        assertEquals(HealthFix.CALL_ROLE to HealthState.BAD, noRoleView.row("spam").fix to noRoleView.row("spam").state)
        // With the assistant off too, a lost role still shows: spam protection is always on.
        val offNoRole = HealthRules.view(facts(callAssistantOn = false, device = fold.copy(callRoleHeld = false)), cal)
        assertEquals("Spam calls · can ring · Navilife isn't your Caller ID & spam app", offNoRole.todayLine)
        assertTrue(offNoRole.critical)
        // Slice 5: without contacts and the call log, Today says so (worth a look, not critical) with Allow.
        val noLookups = HealthRules.view(facts(device = fold.copy(callRoleHeld = true, callerLookups = false)), cal)
        assertEquals(HealthFix.CALLER_LOOKUPS to HealthState.WARN, noLookups.row("spam").fix to noLookups.row("spam").state)
        assertEquals("Spam calls · " + SpamProtectionRules.LOOKUPS_LINE, noLookups.todayLine)
    }

    @Test
    fun aiVoiceBatteryAndUpdates() {
        val over = HealthRules.view(facts(ai = AiStatusView("Used up · $20 of $20 this month", lit = true, canAsk = false)), cal).row("ai")
        assertEquals(HealthState.WARN, over.state)
        assertEquals(HealthState.UNKNOWN, HealthRules.view(facts(ai = AskRules.STATUS_UNKNOWN), cal).row("ai").state)
        // AI layer completeness slice 2: a key Anthropic refuses is red, with what to do; a rate limit stays amber.
        val refused = HealthRules.view(facts(ai = AskRules.statusView("failing", "Anthropic refused the key", 0, 2000, "ok", keyRefused = true)), cal)
        assertEquals(HealthState.BAD, refused.row("ai").state)
        assertEquals(AiSpendRules.REFUSED_LINE, refused.row("ai").line)
        val limited = HealthRules.view(facts(ai = AskRules.statusView("failing", "Anthropic is limiting requests", 0, 2000, "ok")), cal)
        assertEquals(HealthState.WARN, limited.row("ai").state)
        assertEquals(HealthState.WARN, HealthRules.view(facts(server = server.copy(speech = false)), cal).row("voice").state)
        // Natural voice, slice 3: which voices the server has, and a Google failure noted for a day (Polly said it).
        assertEquals("On · Amazon Polly", HealthRules.view(facts(), cal).row("voice").line)
        val g = server.copy(speechGoogle = ServerHealth.GOOGLE_ON)
        assertEquals("On · Google Chirp 3 HD and Amazon Polly", HealthRules.view(facts(server = g), cal).row("voice").line)
        val googleFailed = HealthRules.view(facts(server = g.copy(speechGoogleFailedAtMs = now - 2 * HealthRules.HOUR)), cal).row("voice")
        assertEquals("On · Google Chirp 3 HD and Amazon Polly · Google voice failed 12:05 · used Polly", googleFailed.line)
        assertEquals(HealthState.OK, googleFailed.state)
        // The session latch: how long Polly stood in, or still does.
        val held = g.copy(speechGoogleFailedAtMs = now - 2 * HealthRules.HOUR, speechGooglePollyForMs = 15 * 60_000L)
        assertEquals("On · Google Chirp 3 HD and Amazon Polly · Google voice failed 12:05 · used Polly for 15 min", HealthRules.view(facts(server = held), cal).row("voice").line)
        assertEquals("On · Google Chirp 3 HD and Amazon Polly · Google voice failed 12:05 · on Polly for 15 min so far", HealthRules.view(facts(server = held.copy(speechGooglePollyNow = true)), cal).row("voice").line)
        assertEquals("On · Google Chirp 3 HD and Amazon Polly", HealthRules.view(facts(server = g.copy(speechGoogleFailedAtMs = now - 25 * HealthRules.HOUR)), cal).row("voice").line)
        val down = HealthRules.view(facts(server = server.copy(speechGoogle = ServerHealth.GOOGLE_FAILED)), cal).row("voice")
        assertEquals("On · Amazon Polly · Google's voices can't be reached" to HealthState.OK, down.line to down.state)

        val sleepy = HealthRules.view(facts(device = fold.copy(batteryExempt = false)), cal)
        assertEquals(HealthFix.BATTERY to HealthState.WARN, sleepy.row("battery").fix to sleepy.row("battery").state)
        // The walk-through's A7: a short button, so the row's own words keep their width on the closed Fold.
        assertEquals("Keep awake", sleepy.row("battery").fixLabel)
        // Battery has its own line on Today.
        assertNull(sleepy.todayLine)
        assertEquals(HealthState.BAD, HealthRules.view(facts(device = fold.copy(batteryStopped = true)), cal).row("battery").state)

        val update = HealthRules.view(facts(device = fold.copy(updateReady = true)), cal)
        assertEquals("Version 0.1.214 · a newer build is ready", update.row("update").line)
        assertEquals(HealthFix.INSTALL_UPDATE, update.row("update").fix)
        // Hidden on Today with Later: Health still offers Install, and says it was hidden.
        val hidden = HealthRules.view(facts(device = fold.copy(updateReady = true, updateHidden = true)), cal)
        assertEquals("Version 0.1.214 · a newer build is ready (hidden on Today)", hidden.row("update").line)
        assertEquals(HealthFix.INSTALL_UPDATE to "Install", hidden.row("update").fix to hidden.row("update").fixLabel)
        // A build that didn't install shows Android's words until it (or a newer one) is in.
        val failure = UpdateFailure(UpdateFailureRules.STAGE_INSTALL, UpdateFailureRules.STATUS_FAILURE_BLOCKED, null, 214, 230, now - 60 * 60_000L)
        val failed = HealthRules.view(facts(device = fold.copy(installedCode = 214, updateFailure = failure)), cal)
        assertEquals(HealthState.WARN, failed.row("update").state)
        assertEquals(HealthFix.INSTALL_UPDATE, failed.row("update").fix)
        assertEquals("Try again", failed.row("update").fixLabel)
        assertTrue(failed.row("update").line.startsWith("Version 0.1.214 · Build 230 didn't install "), failed.row("update").line)
        assertTrue(failed.row("update").line.endsWith(" · Android: STATUS_FAILURE_BLOCKED"), failed.row("update").line)
        val installed = HealthRules.view(facts(device = fold.copy(installedCode = 230, updateFailure = failure)), cal)
        assertEquals("Version 0.1.214 · up to date", installed.row("update").line)
        // Smaller phone updates: the row says the app's size, and the build before's when it changed.
        val sized = HealthRules.view(facts(device = fold.copy(sizeBytes = 10_300_000, previousSizeBytes = 55_100_000)), cal)
        assertEquals("Version 0.1.214 · 10.3 MB · was 55.1 MB · up to date", sized.row("update").line)
        val same = HealthRules.view(facts(device = fold.copy(updateReady = true, sizeBytes = 10_300_000, previousSizeBytes = 10_320_000)), cal)
        assertEquals("Version 0.1.214 · 10.3 MB · a newer build is ready", same.row("update").line)
    }

    @Test
    fun theSummaryCountsWhatNeedsALook() {
        val v = HealthRules.view(facts(device = fold.copy(notificationAccess = false, batteryExempt = false), server = server.copy(speech = false)), cal)
        assertEquals(3, v.attention)
        assertEquals("3 things need a look", v.summary)
        assertEquals("Navilife health · 2 things need a look", v.todayLine)
        assertEquals("Working, as far as Navilife can tell", HealthRules.view(facts(ai = null), cal).summary)
    }

    @Test
    fun timesReadNaturally() {
        assertEquals("just now", HealthRules.at(now - 20_000, now, cal))
        assertEquals("09:30", HealthRules.at(day * CivilDate.DAY_MS + 570 * HealthRules.MIN, now, cal))
        assertEquals("Thu 8 Oct 23:59", HealthRules.at(day * CivilDate.DAY_MS - HealthRules.MIN, now, cal))
    }

    /** The quality gate's Health shots: "Checked …" reads the calendar's zone the core hands in, not the device's. */
    @Test
    fun theCheckedLineReadsTheCoresZone() {
        val v = HealthRules.view(facts(), cal)
        assertEquals("Checked 14:05", v.checkedLine)
        assertEquals("Checked 15:05", HealthRules.view(facts(), LocalCalendar.fixedOffset(60 * HealthRules.MIN)).checkedLine)
        assertEquals("Checked 14:05", HealthRules.checkedShown(v, checking = false))
        assertEquals("Checking… · Checked 14:05", HealthRules.checkedShown(v, checking = true))
        // A view made before the line existed still reads as a check.
        assertEquals("Checked", HealthRules.checkedShown(v.copy(checkedLine = ""), checking = false))
    }
}
