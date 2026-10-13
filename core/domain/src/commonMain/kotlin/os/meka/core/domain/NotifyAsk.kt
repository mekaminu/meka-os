package os.meka.core.domain

/*
 * Notifications never seen, slice 4 (build plan; non-AI, pure): Ask and Talk answer "why didn't I get a notification?"
 * from the phone's own trace ([NotifyTrace]) and MEKA's notification settings. Only a question about notifications
 * carries these lines (the trace is long and rarely wanted), and when MEKA's AI can't answer (off, over budget,
 * offline) the device answers in plain words from the same facts. Nothing here holds a notice's words: the trace has
 * counts, channel names, reasons and error class names only, so the lines are MEKA's own and never untrusted.
 */
object NotifyAskRules {
    /** Trace entries sent with a question, newest first. */
    const val ASK_TRACE = 6
    /** Every notifications line, at most. */
    const val ASK_LINES = 6 + ASK_TRACE

    private val WORDS = listOf("notification", "notif", "notified", "notify", "heads-up", "heads up", "headsup", "alerts", "digest")

    /** Whether [question] is about MEKA's notifications ("why didn't I get a notification?", "are my alerts on?"). */
    fun isAbout(question: String): Boolean {
        val q = question.lowercase()
        return WORDS.any { it in q }
    }

    /**
     * The Ask lines (kind `notifications`): whether notifications can reach him on this device, categories switched
     * off, the last one sent, the digest times, quiet hours (and whether it's quiet now), then the newest trace
     * entries. [health] is null on a device that keeps no trace (the Mac): the lines say so and keep the settings.
     */
    fun askLines(health: NotificationHealth?, settings: NotificationSettings, nowMs: Long, cal: LocalCalendar): List<String> = buildList {
        if (health == null) {
            add("Notifications · this device keeps no notification trace (the Fold does) · ask on the Fold to see what happened there")
        } else {
            add(
                when {
                    !health.allowed -> "Notifications on this phone · off for MEKA (Android's permission or the app's switch) · nothing can reach Meka until he turns them on"
                    health.phoneOff -> "Notifications on this phone · set to Off in MEKA's Notifications (This phone) · nothing posts here"
                    else -> "Notifications on this phone · on for MEKA"
                },
            )
            val off = health.channels.filter { !it.on }
            if (off.isNotEmpty()) {
                val all = off.size == health.channels.size
                add("${if (all) "Every category is" else "Categories"} off in Android's settings: ${off.joinToString(", ") { it.name }}")
            }
            add(lastSentLine(health, nowMs, cal))
        }
        add("Digests · ${Governor.explainLine(settings)}")
        add(quietLine(settings.quiet, nowMs, cal))
        health?.trace?.take(ASK_TRACE)?.forEach { add("Trace · ${NotifyTrace.line(it, cal)}") }
    }.take(ASK_LINES)

    /**
     * The device's own answer when MEKA's AI can't answer a notifications question: what stops them, else when the
     * last one went and why things may be quiet. Null when the device keeps no trace (nothing better to say than
     * the usual "AI is off" line).
     */
    fun localAnswer(health: NotificationHealth?, settings: NotificationSettings, nowMs: Long, cal: LocalCalendar): String? {
        health ?: return null
        if (!health.allowed) return "Notifications are off for MEKA on this phone, so nothing can reach you. Turn them on in Settings → Notifications (or Health's Turn on)."
        if (health.phoneOff) return "This phone is set to Off in MEKA's Notifications, so MEKA posts nothing here. Pick Everything or Digests only under This phone."
        val off = health.channels.filter { !it.on }
        if (off.isNotEmpty() && off.size == health.channels.size) {
            return "Every MEKA category is switched off in Android's settings, so nothing shows. Open Settings → Apps → MEKA → Notifications and turn the categories back on."
        }
        val failed = health.trace.firstOrNull { it.kind != NotifyTraceKind.PUSH && it.kind != NotifyTraceKind.STARTED && it.kind != NotifyTraceKind.TEST }
            ?.takeIf { it.kind == NotifyTraceKind.FAILED }
        return buildList {
            if (failed != null) add("The last try to post failed ${at(failed.atMs, nowMs, cal)} (${failed.detail}). Send a test from Settings → Notifications to see if it's still failing.")
            add(lastSentSentence(health, nowMs, cal))
            if (off.isNotEmpty()) add("Switched off in Android's settings: ${off.joinToString(", ") { it.name }}.")
            if (settings.quiet.isQuietAt(nowMs, cal)) add("It's quiet hours until ${LocalClock.formatMinute(settings.quiet.endMinute)}, so only urgent things come now.")
            add(Governor.explainLine(settings))
        }.joinToString(" ")
    }

    private fun lastPostMs(health: NotificationHealth): Long? = listOfNotNull(
        health.trace.firstOrNull { it.kind == NotifyTraceKind.POSTED }?.atMs,
        health.channels.mapNotNull { it.lastPostMs }.maxOrNull(),
    ).maxOrNull()

    private fun lastChannel(health: NotificationHealth): String? = health.channels.filter { it.lastPostMs != null }.maxByOrNull { it.lastPostMs!! }?.name

    private fun lastSentLine(health: NotificationHealth, nowMs: Long, cal: LocalCalendar): String {
        val last = lastPostMs(health) ?: return "Last notification · none sent from this phone yet"
        return "Last notification · sent ${at(last, nowMs, cal)}${lastChannel(health)?.let { " on $it" }.orEmpty()}"
    }

    private fun lastSentSentence(health: NotificationHealth, nowMs: Long, cal: LocalCalendar): String {
        val last = lastPostMs(health) ?: return "MEKA hasn't sent a notification from this phone yet."
        return "The last one went ${at(last, nowMs, cal)}${lastChannel(health)?.let { ", on $it" }.orEmpty()}."
    }

    /** "Quiet hours · 22:00–07:00 · quiet now until 07:00" / "Quiet hours · off". */
    fun quietLine(q: QuietHours, nowMs: Long, cal: LocalCalendar): String = when {
        !q.enabled -> "Quiet hours · off"
        q.isQuietAt(nowMs, cal) -> "Quiet hours · ${q.summary} · quiet now until ${LocalClock.formatMinute(q.endMinute)} (only urgent things come)"
        else -> "Quiet hours · ${q.summary}"
    }

    /** "today 20:41" · "yesterday 07:10" · "Sat 10 Oct 09:00". */
    private fun at(ms: Long, nowMs: Long, cal: LocalCalendar): String {
        val d = cal.epochDayOf(ms)
        val today = cal.epochDayOf(nowMs)
        val time = LocalClock.formatMinute(cal.minuteOfDay(ms))
        return when (d) {
            today -> "today at $time"
            today - 1 -> "yesterday at $time"
            else -> "${CivilDate.shortLabel(d)} at $time"
        }
    }
}
