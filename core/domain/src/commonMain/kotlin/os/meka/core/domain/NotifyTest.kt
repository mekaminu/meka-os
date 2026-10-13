package os.meka.core.domain

/*
 * Notifications never seen, slice 2 (non-AI, pure). **Send a test** in Settings → Notifications proves both paths at
 * once: a local notification posted by the phone itself, and a push the server sends through Firebase to this phone,
 * which posts its own notification when it arrives. Each half says "arrived ✓" or what went wrong and what to check.
 * Also here: when the phone sends its notification trace to MEKA's server (so a build run can read it, like the
 * Fold's posture report), and the server's last push for Health's Instant updates row.
 */

/** What the server said when asked to push a test to this phone, or that it couldn't be asked. */
enum class PushTestResult {
    /** Firebase took it; it should arrive within seconds. */
    SENT,
    /** Push isn't set up on the server (no Firebase key). */
    OFF,
    /** Firebase refused it or couldn't be reached. */
    FAILED,
    /** The server's address for this phone was stale and is forgotten; reopening MEKA registers a fresh one. */
    GONE,
    /** The server has no push address for this phone. */
    NO_ADDRESS,
    /** MEKA's server couldn't be reached (offline, not connected, or an older server). */
    UNREACHABLE;

    companion object {
        /** The wire's word ("sent", "no_address" …); anything unknown reads as failed. */
        fun of(wire: String?): PushTestResult = when (wire) {
            "sent" -> SENT
            "off" -> OFF
            "gone" -> GONE
            "no_address" -> NO_ADDRESS
            null -> UNREACHABLE
            else -> FAILED
        }
    }
}

/** One run of Send a test. */
data class NotifyTestRun(
    val startedAtMs: Long,
    /** Null while posting; true when the local test reached a live channel; false when it couldn't show. */
    val localShown: Boolean? = null,
    /** Null while asking the server. */
    val push: PushTestResult? = null,
    /** When the test push arrived on this phone. */
    val pushArrivedAtMs: Long? = null,
)

data class NotifyTestView(
    val localLine: String,
    val localOk: Boolean?,
    val pushLine: String,
    val pushOk: Boolean?,
    /** What to check, when something didn't arrive; null when both arrived or it's still going. */
    val hint: String?,
    /** Both halves have an answer (arrived, failed, or the push's wait ran out). */
    val done: Boolean,
)

object NotifyTestRules {
    /** How long the push half waits before saying it didn't arrive. */
    const val PUSH_WAIT_MS = 60_000L

    const val LOCAL_TITLE = "Navilife test · from this phone"
    const val LOCAL_TEXT = "If you can see this, Navilife can reach you on this phone."
    const val PUSH_TITLE = "Navilife test · from Navilife's server"
    const val PUSH_TEXT = "Push works: Navilife's server reached this phone."

    fun view(r: NotifyTestRun, nowMs: Long): NotifyTestView {
        val localLine = when (r.localShown) {
            null -> "On this phone · sending…"
            true -> "On this phone · arrived ✓"
            false -> "On this phone · didn't show"
        }
        val waitedOut = r.push == PushTestResult.SENT && r.pushArrivedAtMs == null && nowMs - r.startedAtMs >= PUSH_WAIT_MS
        val pushOk: Boolean? = when {
            r.pushArrivedAtMs != null -> true
            r.push == null -> null
            r.push == PushTestResult.SENT -> if (waitedOut) false else null
            else -> false
        }
        val pushLine = when {
            r.pushArrivedAtMs != null -> {
                val secs = ((r.pushArrivedAtMs - r.startedAtMs) / 1000).coerceAtLeast(0)
                "From Navilife's server · arrived in ${if (secs < 1) "under a second" else "$secs s"} ✓"
            }
            r.push == null -> "From Navilife's server · asking…"
            r.push == PushTestResult.SENT -> if (waitedOut) "From Navilife's server · sent, but didn't arrive within a minute" else "From Navilife's server · sent · waiting…"
            r.push == PushTestResult.OFF -> "From Navilife's server · push isn't set up on the server"
            r.push == PushTestResult.NO_ADDRESS -> "From Navilife's server · this phone hasn't registered for push"
            r.push == PushTestResult.GONE -> "From Navilife's server · its address for this phone was out of date"
            r.push == PushTestResult.UNREACHABLE -> "From Navilife's server · couldn't reach the server"
            else -> "From Navilife's server · Firebase didn't take it"
        }
        val hint = when {
            r.localShown == false ->
                "Turn on notifications for Navilife and its Heads-ups category (Settings → Apps → Navilife → Notifications), and check Do not disturb is off."
            pushOk == false -> when (r.push) {
                PushTestResult.SENT -> "Check Navilife's battery is Unrestricted (Settings → Apps → Navilife → Battery) and that the phone has data; then try again."
                PushTestResult.NO_ADDRESS, PushTestResult.GONE -> "Close Navilife and open it again so it registers for push, then try again."
                PushTestResult.UNREACHABLE -> "Check the phone is online, then try again."
                PushTestResult.OFF -> "Changes still arrive within 15 minutes; push needs the server's Firebase key."
                else -> "Try again in a few minutes."
            }
            else -> null
        }
        val done = r.localShown != null && (pushOk != null)
        return NotifyTestView(localLine, r.localShown, pushLine, pushOk, hint, done)
    }

    /** The trace's line for each step ("local posted on Heads-ups", "the server sent the push", "push arrived in 2 s"). */
    fun localTrace(shown: Boolean): String = if (shown) "local posted on Heads-ups" else "local couldn't show"

    fun serverTrace(r: PushTestResult): String = when (r) {
        PushTestResult.SENT -> "the server sent the push"
        PushTestResult.OFF -> "push isn't set up on the server"
        PushTestResult.FAILED -> "Firebase didn't take the push"
        PushTestResult.GONE -> "the server's push address was out of date"
        PushTestResult.NO_ADDRESS -> "the server has no push address for this phone"
        PushTestResult.UNREACHABLE -> "couldn't reach the server"
    }

    fun arrivedTrace(startedAtMs: Long?, atMs: Long): String =
        startedAtMs?.let { "push arrived in ${((atMs - it) / 1000).coerceAtLeast(0)} s" } ?: "push arrived"
}

/** When the phone sends its notification trace to MEKA's server. */
object NotifyTraceReportRules {
    /** At most this often, unless a test just ran. */
    const val EVERY_MS = 30 * 60_000L
    /** After a failed send, wait this long. */
    const val RETRY_MS = 15 * 60_000L

    /**
     * Whether to send now: there is an entry newer than the last one sent, and either [force] (a test just ran) or the
     * last send was at least [EVERY_MS] ago; never within [RETRY_MS] of a failure unless forced.
     */
    fun due(newestAtMs: Long?, sentThroughMs: Long, lastSentAtMs: Long?, failedAtMs: Long?, nowMs: Long, force: Boolean = false): Boolean {
        if (newestAtMs == null || newestAtMs <= sentThroughMs) return false
        if (force) return true
        if (failedAtMs != null && nowMs - failedAtMs < RETRY_MS) return false
        return lastSentAtMs == null || nowMs - lastSentAtMs >= EVERY_MS
    }
}

/** Health's Instant updates row: what the server says about its last push to this device. */
object LastPushRules {
    /** " · last push 20:41 · sent" or "" when the server doesn't say (none since it started, or an older server). */
    fun suffix(atMs: Long?, result: String?, nowMs: Long, cal: LocalCalendar): String {
        atMs ?: return ""
        val what = when (result) {
            "sent" -> "sent"
            "gone" -> "address out of date"
            "off" -> "push off"
            else -> "failed"
        }
        return " · last push ${HealthRules.at(atMs, nowMs, cal)} · $what"
    }

    /** A last push that failed (or found a stale address) is worth a look. */
    fun failed(result: String?): Boolean = result == "failed" || result == "gone"
}
