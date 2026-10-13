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

/** One notification channel as Android reports it. */
data class NotifyChannelState(val id: String, val name: String, val on: Boolean)

/** What the Fold tells Health about notifications ([NotifyTrace]); null on the Mac until it has the same. */
data class NotificationHealth(
    /** POST_NOTIFICATIONS granted and the app's switch on. */
    val allowed: Boolean,
    val channels: List<NotifyChannelState>,
    /** Newest first. */
    val trace: List<NotifyTraceEntry>,
    /** MEKA's own "This phone" setting is Off (Notifications → This phone), so the governor posts nothing here. */
    val phoneOff: Boolean = false,
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
        if (!allowed) return NotifyTraceEntry(atMs, NotifyTraceKind.BLOCKED, "notifications are off for MEKA$next")
        if (phoneOff) return NotifyTraceEntry(atMs, NotifyTraceKind.BLOCKED, "this phone is set to Off in MEKA's Notifications$next")
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
            NotifyTraceKind.STARTED -> "MEKA started${if (e.detail.isBlank()) "" else " · ${e.detail}"}"
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
        if (!n.allowed) {
            return HealthRow("notifications", title, "Off for MEKA · nothing can reach you", HealthState.BAD, HealthFix.NOTIFICATIONS_ON, "Turn on")
        }
        if (n.phoneOff) {
            return HealthRow("notifications", title, "This phone is set to Off in MEKA's Notifications · nothing posts here", HealthState.BAD)
        }
        val off = n.channels.filter { !it.on }
        if (n.channels.isNotEmpty() && off.size == n.channels.size) {
            return HealthRow("notifications", title, "Every MEKA category is off in Android's settings", HealthState.BAD, HealthFix.NOTIFICATIONS_ON, "Open")
        }
        val lastLook = n.trace.firstOrNull { it.kind == NotifyTraceKind.POSTED || it.kind == NotifyTraceKind.LOOKED || it.kind == NotifyTraceKind.BLOCKED || it.kind == NotifyTraceKind.FAILED }
        if (lastLook?.kind == NotifyTraceKind.FAILED) {
            return HealthRow("notifications", title, "Couldn't post · ${lastLook.detail}", HealthState.BAD)
        }
        val lastPost = n.trace.firstOrNull { it.kind == NotifyTraceKind.POSTED }
        val posted = lastPost?.let { "last sent ${HealthRules.at(it.atMs, nowMs, cal)}" } ?: "none sent yet"
        if (off.isNotEmpty()) {
            return HealthRow("notifications", title, "Off in Android's settings: ${off.joinToString(", ") { it.name }} · $posted", HealthState.WARN, HealthFix.NOTIFICATIONS_ON, "Open")
        }
        if (lastLook == null) {
            return HealthRow("notifications", title, "On · MEKA hasn't looked for anything to send yet", HealthState.WARN)
        }
        return HealthRow("notifications", title, "On · $posted · last looked ${HealthRules.at(lastLook.atMs, nowMs, cal)}", HealthState.OK)
    }
}
