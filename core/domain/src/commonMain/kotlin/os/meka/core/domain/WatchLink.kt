package os.meka.core.domain

import kotlin.random.Random

/**
 * Linking a Galaxy Watch to MEKA (build plan "Galaxy Watch", slice 1; ADR-005 amendment 2026-10-10). The watch is a
 * device of its own (its own hardware key, its own copy of the day, synced through MEKA's server like the Fold and the
 * Mac), so nothing goes through anyone else's service. It joins without the enrolment code: on first open it makes its
 * key and shows an 8-digit code; Meka types the code in Ask → More → Watch on the Fold or the Mac, and the server
 * enrols the watch with the key that asked. Unlink revokes it at once. Pure rules, no AI; the linked watches live on
 * the server, so nothing here is synced except the Activity entries ([ActivityKind.DEVICE]).
 */
data class LinkedWatch(val id: String, val name: String, val linkedAtMs: Long)

/** A row in Watch: "Galaxy Watch" and "Linked today"; Unlink beside it. */
data class LinkedWatchRow(val id: String, val title: String, val line: String)

/**
 * The Watch screen. [summary] says what is linked; [how] says what to do on the watch; [rows] are the linked watches,
 * newest first; [problem] says why the last step didn't work (the rows are the last ones read).
 */
data class WatchLinkView(
    val summary: String,
    val how: String,
    val rows: List<LinkedWatchRow>,
    val problem: String? = null,
)

/** What the watch shows while it waits to be linked: the code in two fours, what to do, and how long is left. */
data class WatchCodeScreen(val title: String, val code: String, val line: String, val left: String, val expired: Boolean)

object WatchLinkRules {
    const val CODE_DIGITS = 8
    const val DEFAULT_NAME = "Galaxy Watch"
    /** No "-": the device id is the HLC node id (`HlcClock`), which refuses one. */
    const val ID_PREFIX = "watch"
    /** How long a code lasts on the server. */
    const val CODE_MINUTES = 10
    const val CODE_MS = CODE_MINUTES * 60_000L

    const val HOW = "Open Navilife on the watch: it shows an 8-digit code. Tap + Link a watch and type it within 10 minutes."
    const val NOT_CONNECTED = "Connect this device to your server first."
    const val NO_KEY = "This device's key isn't registered with Navilife's server yet · try again in a minute"
    const val NO_ROUTE = "Navilife's server can't link watches yet · it arrives with the next deploy"
    const val OFFLINE = "Couldn't reach Navilife's server · try again"
    const val NOT_A_CODE = "The code is 8 digits, as the watch shows it"
    const val WRONG_CODE = "That code doesn't match a watch waiting to link · check the watch and type it again"
    const val TOO_MANY = "Too many wrong codes · wait 10 minutes and try again"
    const val UNLINKED_BEFORE = "That watch was unlinked before · clear Navilife's storage on the watch and open it again"

    /** "1234 5678", "1234-5678" or " 12345678 " → "12345678"; null when it isn't 8 digits. */
    fun normaliseCode(raw: String): String? {
        val digits = raw.filter { it != ' ' && it != '-' && it != ' ' }
        return digits.takeIf { it.length == CODE_DIGITS && it.all { c -> c in '0'..'9' } }
    }

    /** "12345678" → "1234 5678" (how the watch shows it, and how the field formats what was typed). */
    fun showCode(code: String): String =
        if (code.length == CODE_DIGITS) code.take(4) + " " + code.drop(4) else code

    /** A new watch's device id: "watch" and 16 lower-case hex digits ([DeviceLinkCodec.isWatchId] on the server). */
    fun newDeviceId(random: Random = Random.Default): String =
        ID_PREFIX + (1..16).joinToString("") { "0123456789abcdef"[random.nextInt(16)].toString() }

    fun isWatchId(id: String): Boolean = id.startsWith(ID_PREFIX)

    /** "Linked today" · "Linked yesterday" · "Linked Sat 10 Oct". */
    fun line(w: LinkedWatch, nowMs: Long, cal: LocalCalendar): String {
        val label = SearchRules.dayLabel(cal.epochDayOf(w.linkedAtMs), cal.epochDayOf(nowMs))
        return "Linked " + if (label == "Today" || label == "Yesterday") label.lowercase() else label
    }

    fun view(watches: List<LinkedWatch>, nowMs: Long, cal: LocalCalendar, problem: String? = null): WatchLinkView {
        val newest = watches.sortedWith(compareByDescending<LinkedWatch> { it.linkedAtMs }.thenBy { it.id })
        val summary = when (newest.size) {
            0 -> "No watch linked yet"
            1 -> "${newest[0].name} is linked · it shows Up next and takes Done"
            else -> "${newest.size} watches are linked"
        }
        return WatchLinkView(
            summary = summary,
            how = HOW,
            rows = newest.map { LinkedWatchRow(it.id, it.name, line(it, nowMs, cal)) },
            problem = problem,
        )
    }

    /**
     * The line under the code field: a wrong code is said only while the field still holds a code, so it never sits
     * under an empty field (walk-through A11); every other problem shows as it is.
     */
    fun problemShown(problem: String?, typed: String): String? =
        if (problem == WRONG_CODE && typed.isBlank()) null else problem

    // ---- linking from a sheet (walk-through week 5, A17: no open input boxes on a page) ----

    const val LINK_ROW = "+ Link a watch"
    const val LINK_ANOTHER_ROW = "+ Link another watch"
    const val CODE_SPOKEN = "Code from the watch"
    const val LINK = "Link"

    /**
     * Watch's card row where the open code field was: "+ Link a watch" (or "+ Link another watch" once one is linked)
     * with what it asks for under it; it opens [linkSheet].
     */
    fun linkRow(linked: Int): GoalAddRow = GoalAddRow(
        if (linked > 0) LINK_ANOTHER_ROW else LINK_ROW,
        "Type the $CODE_DIGITS-digit code the watch shows",
    )

    /**
     * The sheet: the code with an example, where the watch shows it, Close · Link. Unlike an add sheet it stays open
     * while the server checks the code, closing once the watch is linked and saying a refusal inside it.
     */
    val linkSheet = GoalAddSheet(
        title = "Link a watch",
        hint = "1234 5678",
        spokenHint = CODE_SPOKEN,
        note = "Open Navilife on the watch to see its code. A code lasts $CODE_MINUTES minutes; the watch shows a new one when it runs out.",
        addLabel = LINK,
    )

    /** What the code field keeps of what was typed: digits and spaces, at most "1234 5678"'s nine characters. */
    fun typedCode(raw: String): String = raw.filter { it in '0'..'9' || it == ' ' }.take(CODE_DIGITS + 1)

    /** Link lights once something is typed (a short code is answered with [NOT_A_CODE], as the field's Link was). */
    fun canLink(typed: String, busy: Boolean): Boolean = !busy && typed.isNotBlank()

    /** The server's refusal (`code` · `wait` · `revoked`) in words. */
    fun refusal(reason: String?): String = when (reason) {
        "wait" -> TOO_MANY
        "revoked" -> UNLINKED_BEFORE
        else -> WRONG_CODE
    }

    // ---- the watch's own screen while it waits (slice 2 draws it) ----

    /** "Link to MEKA", "1234 5678", "On your phone: Ask → More → Watch", "9 min left" (or expired: tap for a new code). */
    fun codeScreen(code: String, expiresAtMs: Long, nowMs: Long): WatchCodeScreen {
        val leftMs = expiresAtMs - nowMs
        val expired = leftMs <= 0
        val minutes = ((leftMs + 59_999) / 60_000).coerceAtLeast(0)
        return WatchCodeScreen(
            title = "Link to Navilife",
            code = showCode(code),
            line = if (expired) "That code ran out" else "On your phone: Ask → More → Watch",
            left = when {
                expired -> "Tap for a new code"
                minutes <= 1 -> "Less than a minute left"
                else -> "$minutes min left"
            },
            expired = expired,
        )
    }

    // ---- Activity (ActivityKind.DEVICE): ids from the watch and the time, so both devices write the same entry once ----

    fun linkedId(deviceId: String): String = "d" + ActivityRules.fnv64("device:linked:$deviceId")
    fun unlinkedId(deviceId: String): String = "d" + ActivityRules.fnv64("device:unlinked:$deviceId")

    fun linkedSummary(name: String) = "Linked $name"
    fun unlinkedSummary(name: String) = "Unlinked $name"
    const val WHY_YOU = "You did this in Watch"
}
