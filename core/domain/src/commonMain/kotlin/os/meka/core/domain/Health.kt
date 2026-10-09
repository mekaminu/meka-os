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
enum class HealthFix { SYNC_NOW, RECONNECT, CALENDARS, BATTERY, NOTIFICATION_ACCESS, CALL_ROLE, WORK, INSTALL_UPDATE }

data class HealthRow(
    /** Stable: "server", "push", "calendar:<provider>:<account>", "feeds", "messages", "calls", "ai", "voice", "battery", "update". */
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
)

/** What the server said (`/v1/health/household`); null in [HealthFacts.server] when it couldn't be reached. */
data class ServerHealth(
    val push: String,
    val calls: Boolean,
    val speech: Boolean,
    val atMs: Long,
    /** Macs connected to the household (Setup's "Mac" step); null from a server that doesn't say. */
    val macs: Int? = null,
) {
    companion object {
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
    val lastVoiceMessageMs: Long? = null,
    val nowMs: Long,
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

    /** Keys whose trouble Today already shows on its own line (battery, sign-ins). */
    private fun ownLine(key: String) = key == "battery" || key.startsWith("calendar:")

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
            if (!f.device.mac) add(push(f))
            addAll(calendars(f, cal))
            feeds(f, cal)?.let(::add)
            messages(f, cal)?.let(::add)
            add(calls(f, cal))
            add(ai(f))
            add(voice(f))
            battery(f)?.let(::add)
            update(f)?.let(::add)
        }
        val attention = rows.count { it.needsLook }
        val critical = rows.any { it.state == HealthState.BAD }
        val summary = when (attention) {
            0 -> if (rows.any { it.state == HealthState.UNKNOWN }) "Working, as far as MEKA can tell" else "Everything's working"
            1 -> "1 thing needs a look"
            else -> "$attention things need a look"
        }
        val others = rows.filter { it.needsLook && !ownLine(it.key) }
        val todayLine = when (others.size) {
            0 -> null
            1 -> "${others[0].title} · ${others[0].line}"
            else -> "MEKA health · ${others.size} things need a look"
        }
        return HealthView(rows, summary, attention, critical, todayLine, f.nowMs)
    }

    private fun server(f: HealthFacts, cal: LocalCalendar): HealthRow {
        val title = "MEKA's server"
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

    private fun push(f: HealthFacts): HealthRow {
        val title = "Instant updates"
        return when (f.server?.push) {
            null -> HealthRow("push", title, "Couldn't check", HealthState.UNKNOWN)
            ServerHealth.PUSH_ON -> HealthRow("push", title, "On · changes from the Mac arrive in seconds", HealthState.OK)
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
        val feeds = f.accounts?.filter { !isCalendar(it.provider) } ?: return null
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
        if (!access) return HealthRow("messages", title, "MEKA can't see notifications · messages aren't captured", HealthState.BAD, HealthFix.NOTIFICATION_ACCESS, "Allow")
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
            f.device.callRoleHeld == false -> HealthRow("calls", title, "MEKA isn't screening calls on this phone", HealthState.BAD, HealthFix.CALL_ROLE, "Allow")
            else -> HealthRow("calls", title, "On · $last", HealthState.OK)
        }
    }

    private fun ai(f: HealthFacts): HealthRow {
        val ai = f.ai ?: return HealthRow("ai", "MEKA's AI", "Couldn't check", HealthState.UNKNOWN)
        val state = when {
            ai == AskRules.STATUS_UNKNOWN -> HealthState.UNKNOWN
            ai.canAsk && !ai.lit -> HealthState.OK
            else -> HealthState.WARN
        }
        return HealthRow("ai", "MEKA's AI", ai.line, state)
    }

    private fun voice(f: HealthFacts): HealthRow = when (f.server?.speech) {
        null -> HealthRow("voice", "MEKA's voice", "Couldn't check", HealthState.UNKNOWN)
        true -> HealthRow("voice", "MEKA's voice", "On · Amazon Polly", HealthState.OK)
        false -> HealthRow("voice", "MEKA's voice", "Not set up · the device's own voice speaks", HealthState.WARN)
    }

    private fun battery(f: HealthFacts): HealthRow? {
        val exempt = f.device.batteryExempt ?: return null
        return when {
            f.device.batteryStopped -> HealthRow("battery", "Battery", "MEKA was stopped today, likely by Samsung's battery saver", HealthState.BAD, HealthFix.BATTERY, "Keep MEKA awake")
            !exempt -> HealthRow("battery", "Battery", "Samsung may put MEKA to sleep", HealthState.WARN, HealthFix.BATTERY, "Keep MEKA awake")
            else -> HealthRow("battery", "Battery", "Allowed to run in the background", HealthState.OK)
        }
    }

    private fun update(f: HealthFacts): HealthRow? {
        val v = f.device.version ?: return null
        return if (f.device.updateReady) HealthRow("update", "Updates", "Version $v · a newer build is ready", HealthState.WARN, HealthFix.INSTALL_UPDATE, "Install")
        else HealthRow("update", "Updates", "Version $v · up to date", HealthState.OK)
    }
}
