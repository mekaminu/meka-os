package os.meka.core.domain

/*
 * Notifications never seen (Meka, 2026-10-12 21:03: "I have never seen one"; build plan "Take first: notifications
 * never seen", slice 1; non-AI, pure). Samsung's page for MEKA listed no categories at all, so no code path that posts
 * had ever run on his Fold. From this slice every channel is made at app start, and the phone keeps a short trace of
 * what MEKA's notification paths did — each look the governor took and what came of it (posted, held for a digest,
 * blocked because notifications are off, or failed), each push that arrived — so Health can say where they stop and a
 * build run can read it. The trace holds no message text: counts, channel names, reasons and error class names only.
 */

/** What one trace entry records. */
enum class NotifyTraceKind {
    /** The governor looked and posted something: [NotifyTraceEntry.detail] says what ("2 · Heads-ups"). */
    POSTED,
    /** The governor looked and nothing was due (or it was held for a digest / quiet hours). */
    LOOKED,
    /** Nothing could post: notifications are off for MEKA (the permission, the app switch or every channel). */
    BLOCKED,
    /** A notification path threw: [NotifyTraceEntry.detail] is the error's class and a short message. */
    FAILED,
    /** A push from MEKA's server arrived (data-only "sync now"). */
    PUSH,
    /** The app started and made its notification channels. */
    STARTED,
    /** Send a test (slice 2): the local test posted (or not), the server's answer, or the test push arriving. */
    TEST,
}

data class NotifyTraceEntry(val atMs: Long, val kind: NotifyTraceKind, val detail: String = "")

/**
 * One notification channel as Android reports it, and (slice 3) when MEKA last posted on it from this phone — every
 * post, the governor's and the paths outside it (work alerts, the after-work nudge, updates, alarms…).
 */
data class NotifyChannelState(val id: String, val name: String, val on: Boolean, val lastPostMs: Long? = null)

/**
 * Slice 3: when MEKA last posted on each channel, kept on the phone as "channelId=atMs" lines. Ids are MEKA's own
 * stable channel ids; nothing about what was posted.
 */
object NotifyChannelPosts {
    fun record(posts: Map<String, Long>, channelId: String?, atMs: Long): Map<String, Long> {
        val id = channelId?.trim()?.takeIf { it.isNotEmpty() && '=' !in it && '\n' !in it } ?: return posts
        return posts + (id to maxOf(atMs, posts[id] ?: 0L))
    }

    fun encode(posts: Map<String, Long>): String = posts.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }

    fun decode(text: String?): Map<String, Long> = text.orEmpty().lines().mapNotNull { l ->
        val i = l.lastIndexOf('=')
        if (i <= 0) return@mapNotNull null
        val at = l.substring(i + 1).toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        l.substring(0, i) to at
    }.toMap()

    /** [channels] with each one's last post filled in from [posts]. */
    fun withPosts(channels: List<NotifyChannelState>, posts: Map<String, Long>): List<NotifyChannelState> =
        channels.map { c -> posts[c.id]?.let { c.copy(lastPostMs = it) } ?: c }

    /**
     * Health's line per channel, the ones posted on most recently first, then the rest by name:
     * "Heads-ups · on · last sent 20:41" · "Digests · on · nothing sent yet" · "Alarms · off in Android's settings".
     */
    fun lines(channels: List<NotifyChannelState>, nowMs: Long, cal: LocalCalendar): List<String> =
        channels.sortedWith(compareByDescending<NotifyChannelState> { it.lastPostMs ?: 0L }.thenBy { it.name }).map { c ->
            val state = if (c.on) "on" else "off in Android's settings"
            val last = c.lastPostMs?.let { "last sent ${HealthRules.at(it, nowMs, cal)}" } ?: "nothing sent yet"
            "${c.name} · $state · $last"
        }
}

/** What a device tells Health about MEKA's own notifications ([NotifyTrace]); the Mac's comes from [MacNotifyRules.health]. */
data class NotificationHealth(
    /** POST_NOTIFICATIONS granted and the app's switch on. */
    val allowed: Boolean,
    val channels: List<NotifyChannelState>,
    /** Newest first. */
    val trace: List<NotifyTraceEntry>,
    /** MEKA's own "This phone" setting is Off (Notifications → This phone), so the governor posts nothing here. */
    val phoneOff: Boolean = false,
    /** From the Mac: macOS has no channels or governor trace, so the wording names System Settings and "this Mac". */
    val mac: Boolean = false,
)

object NotifyTrace {
    const val KEEP = 40
    /** An error's detail is cut to this many characters (class name first; never message text). */
    const val DETAIL_MAX = 120

    /**
     * [log] with [entry] added, newest first, cut to [KEEP]. A LOOKED entry straight after another LOOKED with the same
     * detail replaces it (the governor looks often; one line per quiet stretch is enough to read).
     */
    fun add(log: List<NotifyTraceEntry>, entry: NotifyTraceEntry): List<NotifyTraceEntry> {
        val e = entry.copy(detail = clean(entry.detail))
        val head = log.firstOrNull()
        val rest = if (head != null && head.kind == NotifyTraceKind.LOOKED && e.kind == NotifyTraceKind.LOOKED && head.detail == e.detail) log.drop(1) else log
        return (listOf(e) + rest).take(KEEP)
    }

    /** What the governor's look came to, as an entry: posted, blocked, or looked (with when it looks next). */
    fun look(
        atMs: Long,
        allowed: Boolean,
        posted: Int,
        digest: Boolean,
        channelNames: List<String>,
        nextLookMs: Long?,
        cal: LocalCalendar,
        phoneOff: Boolean = false,
    ): NotifyTraceEntry {
        val next = nextLookMs?.let { " · next look ${LocalClock.formatMinute(cal.minuteOfDay(it))}" }.orEmpty()
        if (!allowed) return NotifyTraceEntry(atMs, NotifyTraceKind.BLOCKED, "notifications are off for Navilife$next")
        if (phoneOff) return NotifyTraceEntry(atMs, NotifyTraceKind.BLOCKED, "this phone is set to Off in Navilife's Notifications$next")
        if (posted == 0 && !digest) return NotifyTraceEntry(atMs, NotifyTraceKind.LOOKED, "nothing due$next")
        val what = buildList {
            if (posted > 0) add("$posted ${if (posted == 1) "notice" else "notices"}")
            if (digest) add("the digest")
        }.joinToString(" and ")
        val on = channelNames.distinct().takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " on ").orEmpty()
        return NotifyTraceEntry(atMs, NotifyTraceKind.POSTED, "$what$on$next")
    }

    /** A failure as an entry: the error's class (and a short message), never data. */
    fun failure(atMs: Long, where: String, errorClass: String?, message: String?): NotifyTraceEntry {
        val cls = errorClass?.substringAfterLast('.') ?: "Error"
        val msg = message?.lineSequence()?.firstOrNull()?.take(60)?.let { ": $it" }.orEmpty()
        return NotifyTraceEntry(atMs, NotifyTraceKind.FAILED, "$where · $cls$msg")
    }

    /** Health's line for an entry: "12 Oct 20:41 · Posted 2 notices on Heads-ups · next look 21:10". */
    fun line(e: NotifyTraceEntry, cal: LocalCalendar): String {
        val at = "${CivilDate.shortLabel(cal.epochDayOf(e.atMs))} ${LocalClock.formatMinute(cal.minuteOfDay(e.atMs))}"
        val what = when (e.kind) {
            NotifyTraceKind.POSTED -> "Posted ${e.detail}"
            NotifyTraceKind.LOOKED -> "Looked · ${e.detail}"
            NotifyTraceKind.BLOCKED -> "Blocked · ${e.detail}"
            NotifyTraceKind.FAILED -> "Failed · ${e.detail}"
            NotifyTraceKind.PUSH -> "Push arrived${if (e.detail.isBlank()) "" else " · ${e.detail}"}"
            NotifyTraceKind.STARTED -> "Navilife started${if (e.detail.isBlank()) "" else " · ${e.detail}"}"
            NotifyTraceKind.TEST -> "Test · ${e.detail}"
        }
        return "$at · $what"
    }

    /** One entry per line, for the phone's own storage: "atMs|KIND|detail". */
    fun encode(log: List<NotifyTraceEntry>): String = log.joinToString("\n") { "${it.atMs}|${it.kind.name}|${clean(it.detail)}" }

    fun decode(text: String?): List<NotifyTraceEntry> = text.orEmpty().lines().mapNotNull { l ->
        val parts = l.split('|', limit = 3)
        if (parts.size < 2) return@mapNotNull null
        val at = parts[0].toLongOrNull() ?: return@mapNotNull null
        val kind = NotifyTraceKind.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
        NotifyTraceEntry(at, kind, parts.getOrElse(2) { "" })
    }.take(KEEP)

    /**
     * Whether MEKA asks for the notification permission as it opens: Android 13+ (API 33) only, not already allowed,
     * and not asked before on this install (a reinstall clears that, so it asks again).
     */
    fun askOnFirstOpen(sdk: Int, granted: Boolean, askedBefore: Boolean): Boolean = sdk >= 33 && !granted && !askedBefore

    private fun clean(s: String): String = s.replace('\n', ' ').replace('|', '/').take(DETAIL_MAX)

    /**
     * Health's Notifications row: red when nothing can post (off for MEKA, or every channel off) or the last look
     * failed; amber when some channels are off or MEKA hasn't looked yet; otherwise when the last one went and when
     * MEKA last looked. Null when the device doesn't report notifications.
     */
    fun healthRow(n: NotificationHealth?, nowMs: Long, cal: LocalCalendar): HealthRow? {
        n ?: return null
        val title = "Notifications"
        if (n.mac) return MacNotifyRules.healthRow(n, nowMs, cal)
        if (!n.allowed) {
            return HealthRow("notifications", title, "Off for Navilife · nothing can reach you", HealthState.BAD, HealthFix.NOTIFICATIONS_ON, "Turn on")
        }
        if (n.phoneOff) {
            return HealthRow("notifications", title, "This phone is set to Off in Navilife's Notifications · nothing posts here", HealthState.BAD)
        }
        val off = n.channels.filter { !it.on }
        if (n.channels.isNotEmpty() && off.size == n.channels.size) {
            return HealthRow("notifications", title, "Every Navilife category is off in Android's settings", HealthState.BAD, HealthFix.NOTIFICATIONS_ON, "Open")
        }
        val lastLook = n.trace.firstOrNull { it.kind == NotifyTraceKind.POSTED || it.kind == NotifyTraceKind.LOOKED || it.kind == NotifyTraceKind.BLOCKED || it.kind == NotifyTraceKind.FAILED }
        if (lastLook?.kind == NotifyTraceKind.FAILED) {
            return HealthRow("notifications", title, "Couldn't post · ${lastLook.detail}", HealthState.BAD)
        }
        // The newest post anywhere: the governor's (in the trace) or any channel's (paths outside the governor too).
        val lastPostMs = listOfNotNull(n.trace.firstOrNull { it.kind == NotifyTraceKind.POSTED }?.atMs, n.channels.mapNotNull { it.lastPostMs }.maxOrNull()).maxOrNull()
        val posted = lastPostMs?.let { "last sent ${HealthRules.at(it, nowMs, cal)}" } ?: "none sent yet"
        if (off.isNotEmpty()) {
            return HealthRow("notifications", title, "Off in Android's settings: ${off.joinToString(", ") { it.name }} · $posted", HealthState.WARN, HealthFix.NOTIFICATIONS_ON, "Open")
        }
        if (lastLook == null) {
            return HealthRow("notifications", title, "On · Navilife hasn't looked for anything to send yet", HealthState.WARN)
        }
        return HealthRow("notifications", title, "On · $posted · last looked ${HealthRules.at(lastLook.atMs, nowMs, cal)}", HealthState.OK)
    }
}

/**
 * Notifications never seen, the Mac's half (non-AI, pure): Health's Notifications row and Today's off-state line on the
 * Mac, and Send a test there. macOS has no notification channels and the Mac's governor keeps no trace, so the Mac
 * reports only whether macOS lets MEKA notify, MEKA's own "This Mac" setting and when it last posted. The Mac has no
 * push (it syncs on its own timer), so its test is the local half only.
 */
object MacNotifyRules {
    /** What the Mac hands Health: built here so Swift never calls [NotificationHealth]'s constructor. */
    fun health(allowed: Boolean, macOff: Boolean, lastPostMs: Long): NotificationHealth = NotificationHealth(
        allowed = allowed,
        channels = emptyList(),
        trace = if (lastPostMs > 0) listOf(NotifyTraceEntry(lastPostMs, NotifyTraceKind.POSTED, "posted on this Mac")) else emptyList(),
        phoneOff = macOff,
        mac = true,
    )

    /** Health's row (and through it Today's line): red with Turn on when macOS won't let MEKA notify. */
    fun healthRow(n: NotificationHealth, nowMs: Long, cal: LocalCalendar): HealthRow {
        val title = "Notifications"
        if (!n.allowed) {
            return HealthRow("notifications", title, "Off for Navilife in System Settings · nothing can reach this Mac", HealthState.BAD, HealthFix.NOTIFICATIONS_ON, "Turn on")
        }
        if (n.phoneOff) {
            return HealthRow("notifications", title, "This Mac is set to Off in Navilife's Notifications · nothing posts here", HealthState.BAD)
        }
        val last = n.trace.firstOrNull { it.kind == NotifyTraceKind.POSTED }?.atMs
        val posted = last?.let { "last sent ${HealthRules.at(it, nowMs, cal)}" } ?: "none sent yet"
        return HealthRow("notifications", title, "On · $posted", HealthState.OK)
    }

    /** The red line at the top of the Mac's Notifications sheet when nothing can reach this Mac; null when it can. */
    fun offLine(allowed: Boolean, macOff: Boolean): String? = when {
        !allowed -> "Notifications are off for Navilife in System Settings, so nothing can reach this Mac."
        macOff -> "This Mac is set to Off below, so Navilife posts nothing here."
        else -> null
    }

    const val TEST_TITLE = "Navilife test · from this Mac"
    const val TEST_TEXT = "If you can see this, Navilife can reach you on this Mac."

    /** Send a test on the Mac while macOS is being asked. */
    fun testSending(): NotifyTestView = NotifyTestView("On this Mac · sending…", null, "", null, null, false)

    /** Send a test on the Mac once macOS answered: arrived, or what to check. */
    fun testView(shown: Boolean): NotifyTestView = NotifyTestView(
        localLine = if (shown) "On this Mac · arrived ✓" else "On this Mac · didn't show",
        localOk = shown,
        pushLine = "",
        pushOk = null,
        hint = if (shown) null else
            "Turn on notifications for Navilife in System Settings → Notifications → Navilife (Banners or Alerts), and check a Focus isn't on.",
        done = true,
    )
}
