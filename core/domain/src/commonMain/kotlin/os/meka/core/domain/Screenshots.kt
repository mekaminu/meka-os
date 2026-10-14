package os.meka.core.domain

/*
 * Screenshots and photos that do something, slice 2 (build plan V1): **new screenshots, noticed**. With the switch on
 * (off until he turns it on; the photo permission is asked only then), each screenshot he takes is read **on the
 * device** (ML Kit on the Fold, Vision on the Mac, the same reading path as a shared picture) and, when it looks like
 * something Navilife can act on — a parking confirmation, a ticket, an appointment, a booking, an order, a receipt, a
 * letter, or anything with a date and time — one quiet notification offers it: "Screenshot · looks like a booking" with
 * its telling line and **Handle this**, which opens the capture sheet reading it (the parking timer, Handle this's
 * cards, Add). Nothing leaves the device unless he taps Handle this and the AI is needed (the words only, as for shared
 * text); the picture is never kept, copied or sent. A screenshot of anything else (a chat, a meme, a game) says nothing.
 *
 * Pure and shared by both apps: which pictures count as new screenshots, what a screenshot looks like, and the lines.
 */

/** What a new screenshot looks like, for the notification's title ("Screenshot · looks like a booking"). */
enum class ScreenshotKind(val label: String) {
    PARKING("a parking confirmation"),
    TICKET("a ticket"),
    APPOINTMENT("an appointment"),
    BOOKING("a booking"),
    ORDER("an order"),
    RECEIPT("a receipt"),
    LETTER("a letter or form"),
    DATED("something with a date"),
}

/** A picture the device's library listed: its id there, when it was taken (ms) and whether it's a screenshot. */
data class ScreenshotCandidate(val id: String, val takenAtMs: Long, val screenshot: Boolean)

/** The notification for one screenshot. */
data class ScreenshotNotice(val title: String, val text: String, val action: String)

object ScreenshotRules {
    /** Only screenshots taken this recently are offered (a library catching up after a restore stays quiet). */
    const val WINDOW_MS = 10 * 60_000L
    /** At most this many screenshots are read per look (a burst of ten says three things, not ten). */
    const val MAX_AT_ONCE = 3
    /** A clock a little ahead of ours still counts as now. */
    private const val SKEW_MS = 60_000L
    /** The telling line in the notification is cut to this. */
    const val MAX_LINE = 90

    const val CHANNEL_NAME = "Screenshots"
    const val CHANNEL_ABOUT = "When a new screenshot looks like something Navilife can act on (read on this phone, never kept)"
    /** What the lock screen shows instead of the screenshot's words. */
    const val PUBLIC_TITLE = "Navilife · a screenshot"

    const val SWITCH_LABEL = "Notice new screenshots"
    const val ACTION = "Handle this"
    /** The capture sheet's line while it's off (a tap turns it on, asking for photo access on the Fold). */
    const val OFF_LINE = "🖼 Notice new screenshots · read on this phone, never kept"
    const val OFF_LINE_MAC = "Notice new screenshots"
    /** While on. */
    const val ON_LINE = "🖼 Noticing new screenshots · read on this phone, never kept · Turn off"
    const val TURNED_ON = "Navilife will offer what it can do with each new screenshot · read on this phone, never kept"
    const val TURNED_ON_MAC = "Navilife will offer what it can do with each new screenshot · read on this Mac, never kept"
    const val TURNED_OFF = "Navilife won't look at new screenshots"
    /** Photo access refused on the Fold. */
    const val DENIED_LINE = "🖼 Navilife can't see your photos · Allow on its app page (Permissions → Photos and videos → Allow all) to notice screenshots"
    /** Android 14+'s "Select photos": new screenshots aren't among them. */
    const val PARTIAL_LINE = "🖼 Navilife can see only the photos you picked · choose Allow all so it notices new screenshots"
    /** The Mac's notifications are off, so a noticed screenshot couldn't be offered. */
    const val MAC_NO_NOTIFY = "Turn on Navilife's notifications (System Settings → Notifications) so it can offer them"
    /** The capture sheet's line once the switch is on, for TalkBack. */
    const val SWITCH_HELP = "Navilife reads each new screenshot on this phone and offers what it can do; nothing is kept or sent"

    private val SCREENSHOT_NAME = Regex("""^screen\s?shot""", RegexOption.IGNORE_CASE)

    /**
     * Whether a picture in the library is a screenshot: Android files them under …/Screenshots/ (Samsung's DCIM/
     * Screenshots, Pixel's Pictures/Screenshots) and names them "Screenshot_…"; either says so.
     */
    fun isScreenshot(relativePath: String?, displayName: String?): Boolean {
        val path = relativePath.orEmpty().lowercase().replace('\\', '/')
        if (path.split('/').any { it == "screenshots" || it == "screenshot" }) return true
        return SCREENSHOT_NAME.containsMatchIn(displayName.orEmpty().trim())
    }

    /**
     * Which of [candidates] to read now: screenshots taken after [sinceMs] (when the switch went on, or the newest
     * already looked at) and within [WINDOW_MS] of [nowMs], newest first, at most [MAX_AT_ONCE]; each id once.
     */
    fun pick(candidates: List<ScreenshotCandidate>, sinceMs: Long, nowMs: Long): List<ScreenshotCandidate> =
        candidates.asSequence()
            .filter { it.screenshot && it.takenAtMs > sinceMs && it.takenAtMs >= nowMs - WINDOW_MS && it.takenAtMs <= nowMs + SKEW_MS }
            .distinctBy { it.id }
            .sortedByDescending { it.takenAtMs }
            .take(MAX_AT_ONCE)
            .toList()

    /** The next look's [pick] `sinceMs`: the newest screenshot seen (read or skipped), never earlier than [sinceMs]. */
    fun nextSince(candidates: List<ScreenshotCandidate>, sinceMs: Long, nowMs: Long): Long =
        maxOf(sinceMs, candidates.filter { it.screenshot && it.takenAtMs <= nowMs + SKEW_MS }.maxOfOrNull { it.takenAtMs } ?: sinceMs)

    private val TIME = Regex("""(?<![\d£$.])(?:[01]?\d|2[0-3])[:.][0-5]\d(?!\d)|\b(?:1[0-2]|0?[1-9])(?:[:.][0-5]\d)?\s?(?:am|pm)\b""")
    private val DATE = Regex(
        """\b\d{1,2}/\d{1,2}(?:/\d{2,4})?\b|\b\d{4}-\d{2}-\d{2}\b|\b\d{1,2}(?:st|nd|rd|th)?\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\b|\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\s+\d{1,2}(?:st|nd|rd|th)?\b|\b(?:mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)(?:day)?\b|\b(?:today|tomorrow)\b"""
    )
    private val MONEY = Regex("""[£$€]\s?\d+(?:[.,]\d{2})?|\b\d+[.,]\d{2}\s?(?:gbp|eur|usd)\b""")

    private val TICKET = Regex("""\b(?:boarding pass|e-?ticket|tickets?|seat|coach \w|carriage|platform|gate|departs?|departure|flight|admit one|entry|row \w+ seat)\b""")
    private val APPOINTMENT = Regex("""\b(?:appointment|appt|dentist|dental|doctor|gp|surgery|clinic|hospital|optician|eye test|hairdresser|haircut|barber|vet|physio|consultation|check-?up|vaccination|jab)\b""")
    private val BOOKING = Regex("""\b(?:booking|booked|reservation|reserved|your table|table for|check-?in|check-?out|confirmation number|booking reference|reference|ref|confirmed)\b""")
    private val ORDER = Regex("""\b(?:order (?:number|no|#)|your order|order confirmed|dispatched|shipped|out for delivery|arriving|delivery|parcel|tracking)\b""")
    private val RECEIPT = Regex("""\b(?:receipt|subtotal|sub-total|total|vat|amount paid|paid|card ending|contactless|refund)\b""")
    private val LETTER = Regex("""\b(?:dear|parents|carers|please return|reply slip|deadline|due by|by no later than|sign and return|permission|consent|reminder|invoice|bill|statement|renewal|expires|expiry|policy)\b""")

    /**
     * What the tidied [text] of a screenshot looks like, or null when there's nothing Navilife could act on (a chat, a
     * meme, a home screen): then no notification at all. A parking confirmation is read by [ParkingConfirmationRules];
     * a ticket, appointment, booking or order needs a date or time too; a receipt needs an amount; a letter needs a
     * date, or two of its words; anything else with both a date and a time is [ScreenshotKind.DATED].
     */
    fun kind(text: String?): ScreenshotKind? {
        val t = text.orEmpty().trim()
        if (!PictureTextRules.worthOffering(t)) return null
        val s = t.lowercase()
        val time = TIME.containsMatchIn(s)
        val date = DATE.containsMatchIn(s)
        val money = MONEY.containsMatchIn(s)
        val when_ = time || date
        if (ParkingConfirmationRules.looksLikeOne(t)) return ScreenshotKind.PARKING
        if (TICKET.containsMatchIn(s) && when_) return ScreenshotKind.TICKET
        if (APPOINTMENT.containsMatchIn(s) && when_) return ScreenshotKind.APPOINTMENT
        if (ORDER.containsMatchIn(s) && (when_ || money)) return ScreenshotKind.ORDER
        if (BOOKING.containsMatchIn(s) && when_) return ScreenshotKind.BOOKING
        if (RECEIPT.containsMatchIn(s) && money) return ScreenshotKind.RECEIPT
        if (LETTER.findAll(s).map { it.value }.distinct().count() >= 2 || (LETTER.containsMatchIn(s) && date)) return ScreenshotKind.LETTER
        if (time && date) return ScreenshotKind.DATED
        return null
    }

    /** The line that says most about it: the first line naming what it is (or with a date or time), else the first. */
    fun tellingLine(text: String, kind: ScreenshotKind): String {
        val lines = text.lines().map { it.trim() }.filter { PictureTextRules.worthOffering(it) || it.count { c -> c.isLetter() } >= 3 }
        if (lines.isEmpty()) return ""
        val word = when (kind) {
            ScreenshotKind.PARKING -> Regex("""\b(?:expir\w*|until|ends?|valid|parking)\b""")
            ScreenshotKind.TICKET -> TICKET
            ScreenshotKind.APPOINTMENT -> APPOINTMENT
            ScreenshotKind.BOOKING -> BOOKING
            ScreenshotKind.ORDER -> ORDER
            ScreenshotKind.RECEIPT -> Regex("""\b(?:total|amount paid|paid)\b""")
            ScreenshotKind.LETTER -> LETTER
            ScreenshotKind.DATED -> DATE
        }
        val best = lines.firstOrNull { l -> l.lowercase().let { word.containsMatchIn(it) && (TIME.containsMatchIn(it) || DATE.containsMatchIn(it) || MONEY.containsMatchIn(it)) } }
            ?: lines.firstOrNull { l -> word.containsMatchIn(l.lowercase()) }
            ?: lines.first()
        return cut(best)
    }

    private fun cut(s: String): String = if (s.length <= MAX_LINE) s else s.take(MAX_LINE - 1).trimEnd(' ', ',', '·', '-') + "…"

    /** "Screenshot · looks like a booking" · its telling line (or "Tap to see what Navilife can do with it") · Handle this. */
    fun notice(text: String, kind: ScreenshotKind): ScreenshotNotice {
        val line = tellingLine(text, kind)
        return ScreenshotNotice(
            title = "Screenshot · looks like ${kind.label}",
            text = line.ifEmpty { "Tap to see what Navilife can do with it" },
            action = if (kind == ScreenshotKind.PARKING) ParkingConfirmationRules.ACTION else ACTION,
        )
    }

    /** The notice for [text] read off a new screenshot, or null when it doesn't look like anything to act on. */
    fun noticeFor(text: String?): ScreenshotNotice? {
        val t = PictureTextRules.tidy(text)
        val k = kind(t) ?: return null
        return notice(t, k)
    }
}
