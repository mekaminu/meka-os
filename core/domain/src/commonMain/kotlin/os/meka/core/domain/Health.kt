package os.meka.core.domain

/**
 * The Health screen (Reliability first, item 3; Meka approved 2026-10-09 22:29): Ask → More → Health on both apps lists
 * everything MEKA depends on with a tick or the next step, and Today shows one quiet line when something needs a look.
 * Non-AI, pure, unit-tested. The facts come from three places: this device (battery, notification access, call
 * screening role, its version), the synced replica (sync, sign-ins, voice messages, the call assistant's switch) and
 * the server (`POST /v1/health/household`, the calendar list and the AI's status). A fact that couldn't be read is
 * [HealthState.UNKNOWN] ("Couldn't check"), never a tick.
 */
enum class HealthState { OK, WARN, BAD, UNKNOWN }

/** What a row's button does; the apps map each to their own screen or call. */
enum class HealthFix {
    SYNC_NOW, RECONNECT, CALENDARS, BATTERY, NOTIFICATION_ACCESS, CALL_ROLE, WORK, INSTALL_UPDATE,
    /** MEKA's own notifications are off (the permission, the app switch or a category): ask, else open the app's page. */
    NOTIFICATIONS_ON,
    /** Spam calls still ring, slice 5: ask for the contacts and call-log permissions (else open the app's page). */
    CALLER_LOOKUPS,
}

data class HealthRow(
    /** Stable: "server", "push", "notifications", "calendar:<provider>:<account>", "feeds", "messages", "calls", "ai", "voice", "battery", "update". */
    val key: String,
    val title: String,
    val line: String,
    val state: HealthState,
    val fix: HealthFix? = null,
    val fixLabel: String? = null,
    /** For [HealthFix.RECONNECT]: which account, and whether Reconnect asks for editing again. */
    val provider: String? = null,
    val account: String? = null,
    val editing: Boolean = false,
) {
    val needsLook: Boolean get() = state == HealthState.WARN || state == HealthState.BAD
}

data class HealthView(
    val rows: List<HealthRow>,
    /** "Everything's working" · "1 thing needs a look" · "3 things need a look". */
    val summary: String,
    val attention: Int,
    /** Something is broken (not just worth a look): the summary and Today's line take the critical colour. */
    val critical: Boolean,
    /** Today's line, or null when nothing new needs a look (battery and sign-ins have their own lines). */
    val todayLine: String?,
    /** When this was worked out (the screen says "Checked 14:05"). */
    val checkedAtMs: Long,
    /**
     * "Checked 14:05" in the core's own zone (the quality gate's Health shots: each app formatted [checkedAtMs] in the
     * device's default zone, so a check read differently from every other time on screen when the two differed).
     */
    val checkedLine: String = "",
)

/** What the server said (`/v1/health/household`); null in [HealthFacts.server] when it couldn't be reached. */
data class ServerHealth(
    val push: String,
    val calls: Boolean,
    val speech: Boolean,
    val atMs: Long,
    /** Macs connected to the household (Setup's "Mac" step); null from a server that doesn't say. */
    val macs: Int? = null,
    /** The server's last push to this device since it started ([LastPushRules]); null when it doesn't say. */
    val lastPushAtMs: Long? = null,
    /** "sent" · "failed" · "gone" · "off". */
    val lastPushResult: String? = null,
    /** Google's natural voices: [GOOGLE_ON] · [GOOGLE_FAILED] · null (no key, or the server doesn't say). */
    val speechGoogle: String? = null,
    /** When a chosen Google voice last failed and Amazon Polly said it instead (natural voice, slice 3). */
    val speechGoogleFailedAtMs: Long? = null,
    /** How long Polly has stood in for Google's voice, now or the last time (the session latch); null: never, or not said. */
    val speechGooglePollyForMs: Long? = null,
    /** Polly still has Google's job now. */
    val speechGooglePollyNow: Boolean = false,
) {
    companion object {
        const val GOOGLE_ON = "on"
        const val GOOGLE_FAILED = "failed"
        const val PUSH_ON = "on"
        const val PUSH_MISSING = "missing"
        const val PUSH_OFF = "off"
    }
}

/** One connected account or feed as `/v1/integrations/list` gives it. */
data class HealthAccount(val provider: String, val email: String, val title: String, val status: String, val lastSyncAtMs: Long?)

/** What only this device can see. Null = not on this device (the Mac has no battery exemption or listener). */
data class DeviceHealth(
    val mac: Boolean,
    val batteryExempt: Boolean? = null,
    /** MEKA was stopped during the day ([BatteryCareView.critical]). */
    val batteryStopped: Boolean = false,
    val notificationAccess: Boolean? = null,
    /** The notification listener last read something (any app). */
    val lastNotificationMs: Long? = null,
    val callRoleHeld: Boolean? = null,
    /** "0.1.214"; null leaves the Updates row out. */
    val version: String? = null,
    val updateReady: Boolean = false,
    /** The ready build was hidden on Today with Later ([AppUpdateRules.newer]); Health still offers Install. */
    val updateHidden: Boolean = false,
    /** This build's number, for [updateFailure]; null leaves the failure out. */
    val installedCode: Long? = null,
    /** The last phone update that didn't go in ([UpdateFailureRules]); shown until that build is installed. */
    val updateFailure: UpdateFailure? = null,
    /** The installed app's size and the build before's (smaller phone updates); null leaves them out. */
    val sizeBytes: Long? = null,
    val previousSizeBytes: Long? = null,
    /** MEKA's own notifications: allowed, each channel, and the trace ([NotifyTrace]); null leaves the row out. */
    val notifications: NotificationHealth? = null,
    /**
     * Spam calls still ring, slice 5: Navilife may read contacts and the call log here (Work mode → Spam protection →
     * Recognise callers). Without them spam protection can't tell a stranger, so repeat callers and hang-ups aren't
     * blocked. Null: not on this device (the Mac).
     */
    val callerLookups: Boolean? = null,
)

data class HealthFacts(
    val device: DeviceHealth,
    val connected: Boolean,
    /** Last good sync on this device, if any this run. */
    val lastSyncedMs: Long?,
    /** The sync loop's current trouble: "offline" with changes waiting, or the reason it is failing; null when fine. */
    val syncTrouble: String? = null,
    val syncFailing: Boolean = false,
    val server: ServerHealth?,
    /** Null: the list couldn't be fetched. */
    val accounts: List<HealthAccount>?,
    val signIns: List<SignIn> = emptyList(),
    /** Null: couldn't ask. */
    val ai: AiStatusView?,
    val callAssistantOn: Boolean,
    /** Spam protection's switch ([WorkModeState.spamProtection]); on unless turned off in Advanced. */
    val spamProtectionOn: Boolean = true,
    val lastVoiceMessageMs: Long? = null,
    val nowMs: Long,
    /** The call assistant's credit as the server last wrote it ([CallCreditRules]); null when not known yet. */
    val callCredit: CallCredit? = null,
    /** About you, slice 2: whether the Fold and the Mac each hold the profile; null leaves the row out. */
    val aboutYou: AboutYouHeld? = null,
    /** Money's bank sync through Lunch Flow as the server last wrote it ([MoneyBank]); null leaves the row out. */
    val bankSync: MoneyBankSync? = null,
    /** Live trains: the departure board as the server last wrote it ([RouteRules.healthRow]); null leaves the row out. */
    val trains: TrainBoard? = null,
    /** The unread count went up (2026-10-18): the mail accounts as the server listed them ([MailHealthRules]); null leaves the rows out. */
    val mail: List<MailAccountFacts>? = null,
    val mailBacklogs: List<MailBacklog> = emptyList(),
    val mailTidies: List<MailTidyBatch> = emptyList(),
)

object HealthRules {
    const val MIN = 60_000L
    const val HOUR = 60 * MIN
    /** Calendars are polled every 5 minutes; half an hour without one is worth a look. */
    const val CALENDAR_STALE_MS = 30 * MIN
    /** The notification listener reads something most hours; half a day of nothing usually means Android stopped it. */
    const val LISTENER_QUIET_MS = 12 * HOUR
    /** A device clock this far from the server's breaks signed requests and timing. */
    const val CLOCK_SKEW_MS = 5 * MIN
    /** How often Today asks the server (the screen itself asks every time it opens). */
    const val TODAY_REFRESH_MS = 15 * MIN

    /** How long each feed may go without updating before it's worth a look (their own refresh periods, with room). */
    fun feedStaleMs(provider: String): Long = when (provider) {
        "weather" -> 2 * HOUR
        "news", "news_more" -> 3 * HOUR
        // Line status is only polled 05:00–23:00.
        "lines" -> 12 * HOUR
        "fixtures" -> 24 * HOUR
        "bank_holidays" -> 15 * 24 * HOUR
        else -> 24 * HOUR
    }

    private fun isCalendar(provider: String) = provider == "google" || provider == "microsoft"

    /** Keys whose trouble Today already shows on its own line (battery, sign-ins), or Health keeps to itself (About you: Setup's card asks). */
    private fun ownLine(key: String) = key == "battery" || key == AboutYouRules.HEALTH_KEY || key.startsWith("calendar:") ||
        // Mail: Today's unread line names the accounts Navilife can't read.
        key.startsWith(MailHealthRules.KEY_PREFIX)

    /** "just now" · "14:05" (today) · "Thu 8 Oct 14:05". */
    fun at(ms: Long, nowMs: Long, cal: LocalCalendar): String {
        if (nowMs - ms in 0 until MIN) return "just now"
        val time = LocalClock.formatMinute(cal.minuteOfDay(ms))
        val day = cal.epochDayOf(ms)
        return if (day == cal.epochDayOf(nowMs)) time else CivilDate.shortLabel(day) + " " + time
    }

    fun view(f: HealthFacts, cal: LocalCalendar): HealthView {
        val rows = buildList {
            add(server(f, cal))
            if (!f.device.mac) add(push(f, cal))
            NotifyTrace.healthRow(f.device.notifications, f.nowMs, cal)?.let(::add)
            addAll(calendars(f, cal))
            feeds(f, cal)?.let(::add)
            messages(f, cal)?.let(::add)
            add(calls(f, cal))
            SpamProtectionRules.healthRow(f.device.callRoleHeld, f.spamProtectionOn, f.device.mac, f.device.callerLookups)?.let(::add)
            add(ai(f))
            add(voice(f, cal))
            battery(f)?.let(::add)
            update(f, cal)?.let(::add)
            AboutYouRules.healthRow(f.aboutYou, f.nowMs, cal)?.let(::add)
            MoneyBank.healthRow(f.bankSync, f.nowMs, cal)?.let(::add)
            f.trains?.let { RouteRules.healthRow(it, f.nowMs, cal) }?.let(::add)
            f.mail?.let { addAll(MailHealthRules.healthRows(it, f.mailBacklogs, f.mailTidies, f.nowMs, cal)) }
        }
        val attention = rows.count { it.needsLook }
        val critical = rows.any { it.state == HealthState.BAD }
        val summary = when (attention) {
            0 -> if (rows.any { it.state == HealthState.UNKNOWN }) "Working, as far as Navilife can tell" else "Everything's working"
            1 -> "1 thing needs a look"
            else -> "$attention things need a look"
        }
        val others = rows.filter { it.needsLook && !ownLine(it.key) }
        val todayLine = when (others.size) {
            0 -> null
            1 -> "${others[0].title} · ${others[0].line}"
            else -> "Navilife health · ${others.size} things need a look"
        }
        return HealthView(rows, summary, attention, critical, todayLine, f.nowMs, checkedLine(f.nowMs, cal))
    }

    /** "Checked 14:05" in [cal]'s zone. */
    fun checkedLine(atMs: Long, cal: LocalCalendar): String = "Checked " + LocalClock.formatMinute(cal.minuteOfDay(atMs))

    /** The line under Health's summary: "Checked 14:05", or "Checking… · Checked 14:05" while a check runs. */
    fun checkedShown(view: HealthView, checking: Boolean): String =
        (if (checking) "Checking… · " else "") + view.checkedLine.ifEmpty { "Checked" }

    private fun server(f: HealthFacts, cal: LocalCalendar): HealthRow {
        val title = "Navilife's server"
        val synced = f.lastSyncedMs?.let { "last synced ${at(it, f.nowMs, cal)}" }
        return when {
            !f.connected -> HealthRow("server", title, "This device isn't connected yet", HealthState.BAD)
            f.syncFailing -> HealthRow("server", title, "Sync is failing · ${f.syncTrouble ?: "the server refused it"}", HealthState.BAD, HealthFix.SYNC_NOW, "Sync now")
            f.server == null -> HealthRow("server", title, listOfNotNull("Can't reach it right now", synced).joinToString(" · "), HealthState.BAD, HealthFix.SYNC_NOW, "Try again")
            kotlin.math.abs(f.server.atMs - f.nowMs) > CLOCK_SKEW_MS -> {
                val off = kotlin.math.abs(f.server.atMs - f.nowMs) / MIN
                HealthRow("server", title, "This device's clock is $off min off · set the time automatically", HealthState.WARN)
            }
            f.syncTrouble != null -> HealthRow("server", title, listOfNotNull(f.syncTrouble, synced).joinToString(" · "), HealthState.WARN, HealthFix.SYNC_NOW, "Sync now")
            else -> HealthRow("server", title, listOfNotNull("Connected", synced).joinToString(" · "), HealthState.OK)
        }
    }

    private fun push(f: HealthFacts, cal: LocalCalendar): HealthRow {
        val title = "Instant updates"
        return when (f.server?.push) {
            null -> HealthRow("push", title, "Couldn't check", HealthState.UNKNOWN)
            ServerHealth.PUSH_ON -> {
                val last = LastPushRules.suffix(f.server.lastPushAtMs, f.server.lastPushResult, f.nowMs, cal)
                if (LastPushRules.failed(f.server.lastPushResult)) HealthRow("push", title, "On$last · changes arrive within 15 min until it works", HealthState.WARN)
                else HealthRow("push", title, "On · changes from the Mac arrive in seconds$last", HealthState.OK)
            }
            ServerHealth.PUSH_MISSING -> HealthRow("push", title, "This phone hasn't registered · changes arrive within 15 min", HealthState.WARN)
            else -> HealthRow("push", title, "Not set up on the server · changes arrive within 15 min", HealthState.WARN)
        }
    }

    private fun calendars(f: HealthFacts, cal: LocalCalendar): List<HealthRow> {
        val accounts = f.accounts ?: return listOf(HealthRow("calendar:", "Calendars", "Couldn't check", HealthState.UNKNOWN))
        val signedIn = accounts.filter { isCalendar(it.provider) }
        if (signedIn.isEmpty()) return listOf(HealthRow("calendar:", "Calendars", "None connected", HealthState.WARN, HealthFix.CALENDARS, "Connect"))
        return signedIn.map { a ->
            val key = "calendar:${a.provider}:${a.email}"
            val who = CalendarAccountRules.providerLabel(a.provider)
            val title = "Calendar · ${a.title}"
            val synced = a.lastSyncAtMs?.let { "synced ${at(it, f.nowMs, cal)}" }
            val signIn = f.signIns.firstOrNull { it.provider == a.provider && it.account.equals(a.email, ignoreCase = true) }
            val editing = signIn?.canEdit ?: false
            fun reconnect(line: String, state: HealthState) =
                HealthRow(key, title, line, state, HealthFix.RECONNECT, "Reconnect", a.provider, a.email, editing)
            when {
                a.status == "needs_reconnect" -> reconnect(listOfNotNull("$who sign-in expired", a.lastSyncAtMs?.let { "not updating since ${at(it, f.nowMs, cal)}" }).joinToString(" · "), HealthState.BAD)
                signIn?.state == SignInState.ENDING && (signIn.untilMs ?: 0L) > f.nowMs ->
                    reconnect("$who sign-in ends ${at(signIn.untilMs!!, f.nowMs, cal)}", HealthState.WARN)
                a.status == "error" -> HealthRow(key, title, listOfNotNull("The last check failed", synced).joinToString(" · "), HealthState.WARN)
                a.lastSyncAtMs == null -> HealthRow(key, title, "$who · ${a.email} · not synced yet", HealthState.WARN)
                f.nowMs - a.lastSyncAtMs > CALENDAR_STALE_MS -> HealthRow(key, title, "Not updated since ${at(a.lastSyncAtMs, f.nowMs, cal)}", HealthState.WARN)
                else -> HealthRow(key, title, "$who · ${a.email} · $synced", HealthState.OK)
            }
        }
    }

    private fun feeds(f: HealthFacts, cal: LocalCalendar): HealthRow? {
        // A feed switched off on the server (travel times without the Google key) is Setup's to mention, not trouble.
        val feeds = f.accounts?.filter { !isCalendar(it.provider) && it.status != TravelRules.STATUS_OFF } ?: return null
        if (feeds.isEmpty()) return null
        // One row for every feed; "news" and "news_more" are both Headlines.
        val byLabel = feeds.groupBy { CalendarAccountRules.providerLabel(it.provider) }
        val trouble = byLabel.mapNotNull { (label, list) ->
            val newest = list.mapNotNull { it.lastSyncAtMs }.maxOrNull()
            val failing = list.all { it.status != "ok" }
            val stale = newest == null || list.all { a -> a.lastSyncAtMs == null || f.nowMs - a.lastSyncAtMs > feedStaleMs(a.provider) }
            when {
                failing -> "$label failing"
                stale -> if (newest == null) "$label not fetched yet" else "$label not updated since ${at(newest, f.nowMs, cal)}"
                else -> null
            }
        }
        return if (trouble.isEmpty()) HealthRow("feeds", "Feeds", byLabel.keys.joinToString(", ") + " · up to date", HealthState.OK)
        else HealthRow("feeds", "Feeds", trouble.joinToString(" · "), HealthState.WARN)
    }

    private fun messages(f: HealthFacts, cal: LocalCalendar): HealthRow? {
        val access = f.device.notificationAccess ?: return null
        val title = "Messages capture"
        if (!access) return HealthRow("messages", title, "Navilife can't see notifications · messages aren't captured", HealthState.BAD, HealthFix.NOTIFICATION_ACCESS, "Allow")
        val last = f.device.lastNotificationMs
            ?: return HealthRow("messages", title, "Listening · nothing read yet", HealthState.OK)
        return if (f.nowMs - last > LISTENER_QUIET_MS) {
            HealthRow("messages", title, "Nothing read since ${at(last, f.nowMs, cal)} · Android may have stopped it", HealthState.WARN, HealthFix.NOTIFICATION_ACCESS, "Check access")
        } else HealthRow("messages", title, "Listening · last read ${at(last, f.nowMs, cal)}", HealthState.OK)
    }

    private fun calls(f: HealthFacts, cal: LocalCalendar): HealthRow {
        val title = "Call assistant"
        val last = f.lastVoiceMessageMs?.let { "last voice message ${at(it, f.nowMs, cal)}" } ?: "no voice messages yet"
        return when {
            !f.callAssistantOn -> HealthRow("calls", title, "Off · switch it on in Work mode", HealthState.OK, HealthFix.WORK, "Work mode")
            f.server == null -> HealthRow("calls", title, "On · couldn't check the phone service", HealthState.UNKNOWN)
            !f.server.calls -> HealthRow("calls", title, "On, but the phone service isn't set up on the server", HealthState.BAD)
            // The Spam calls row below carries the Fix (and Today's red line), so this one only says why.
            f.device.callRoleHeld == false -> HealthRow("calls", title, "Navilife isn't screening calls on this phone · see Spam calls", HealthState.WARN)
            else -> CallCreditRules.healthLine(f.callCredit)?.let { (line, state) -> HealthRow("calls", title, line, state) }
                ?: HealthRow("calls", title, "On · $last", HealthState.OK)
        }
    }

    private fun ai(f: HealthFacts): HealthRow {
        val ai = f.ai ?: return HealthRow("ai", "Navilife's AI", "Couldn't check", HealthState.UNKNOWN)
        val state = when {
            ai == AskRules.STATUS_UNKNOWN -> HealthState.UNKNOWN
            ai.refused -> HealthState.BAD // a revoked or expired key never fixes itself: red, with what to do
            ai.canAsk && !ai.lit -> HealthState.OK
            else -> HealthState.WARN
        }
        return HealthRow("ai", "Navilife's AI", ai.line, state)
    }

    private fun voice(f: HealthFacts, cal: LocalCalendar): HealthRow = when (f.server?.speech) {
        null -> HealthRow("voice", "Ada's voice", "Couldn't check", HealthState.UNKNOWN)
        true -> HealthRow("voice", "Ada's voice", voiceLine(f.server, f.nowMs, cal), HealthState.OK)
        false -> HealthRow("voice", "Ada's voice", "Not set up · the device's own voice speaks", HealthState.WARN)
    }

    /**
     * Which voices the server speaks with (natural voice, slice 3). A Google failure in the last day is noted, never
     * flagged: Amazon Polly already said those words, so nothing needs a look. Since the session latch (2026-10-19) it
     * says how long Polly stood in: "· used Polly for 15 min", or "· on Polly for 4 min so far" while it still does.
     */
    fun voiceLine(s: ServerHealth, nowMs: Long, cal: LocalCalendar): String {
        val polly = s.speechGooglePollyForMs?.let { EngineLatchRules.duration(it) }?.let {
            if (s.speechGooglePollyNow) "on Polly for $it so far" else "used Polly for $it"
        } ?: "used Polly"
        val failed = s.speechGoogleFailedAtMs?.takeIf { nowMs - it in 0 until GOOGLE_FAILED_NOTE_MS }
            ?.let { " · Google voice failed ${at(it, nowMs, cal)} · $polly" }.orEmpty()
        return when (s.speechGoogle) {
            ServerHealth.GOOGLE_ON -> "On · Google Chirp 3 HD and Amazon Polly$failed"
            ServerHealth.GOOGLE_FAILED -> "On · Amazon Polly · Google's voices can't be reached$failed"
            else -> "On · Amazon Polly"
        }
    }

    /** How long Health mentions a Google voice failure. */
    const val GOOGLE_FAILED_NOTE_MS = 24 * HOUR

    private fun battery(f: HealthFacts): HealthRow? {
        val exempt = f.device.batteryExempt ?: return null
        return when {
            f.device.batteryStopped -> HealthRow("battery", "Battery", "Navilife was stopped today, likely by Samsung's battery saver", HealthState.BAD, HealthFix.BATTERY, "Keep Navilife awake")
            !exempt -> HealthRow("battery", "Battery", "Samsung may put Navilife to sleep", HealthState.WARN, HealthFix.BATTERY, "Keep Navilife awake")
            else -> HealthRow("battery", "Battery", "Allowed to run in the background", HealthState.OK)
        }
    }

    private fun update(f: HealthFacts, cal: LocalCalendar): HealthRow? {
        val v = f.device.version ?: return null
        val installed = f.device.installedCode
        val failed = installed?.let { UpdateFailureRules.healthLine(f.device.updateFailure, it, at(f.device.updateFailure?.atMs ?: 0L, f.nowMs, cal)) }
        if (failed != null) return HealthRow("update", "Updates", "Version $v · $failed", HealthState.WARN, HealthFix.INSTALL_UPDATE, "Try again")
        val size = AppUpdateRules.healthSize(f.device.sizeBytes, f.device.previousSizeBytes)?.let { " · $it" } ?: ""
        return if (f.device.updateReady) HealthRow("update", "Updates", "Version $v$size · a newer build is ready" + (if (f.device.updateHidden) " (hidden on Today)" else ""), HealthState.WARN, HealthFix.INSTALL_UPDATE, "Install")
        else HealthRow("update", "Updates", "Version $v$size · up to date", HealthState.OK)
    }
}
