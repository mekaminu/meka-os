package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * "Leaving now" to Jeanette (build plan, Meka approved 2026-10-10), slice 1. When an office day's work ends, MEKA drafts
 * "Leaving now, home about 18:40" from the journey home and TfL's line status (until the National Rail key, Needs Meka
 * #16) and puts it in one heads-up ([NoticeSource.LEAVING]) with **Send** and **Edit** on the Fold, **Copy** on the Mac.
 *
 * Personal messages stay at "ask me, then send" (autonomy Level 3, hard constraint): nothing is sent unless Meka taps
 * Send, and Send sends exactly the words he was shown — if the draft has moved on since (he tapped more than 10 minutes
 * late, or the trains changed) the notification is updated with the new words and he taps again ([LeavingRules.tap]). Send goes through the
 * messages assistant's way back (a live WhatsApp notification from her, its own Reply action), else WhatsApp's official
 * click-to-chat link with the words filled in, where Meka taps send. No WhatsApp library; nothing leaves the phone
 * except through WhatsApp itself.
 *
 * The ETA: leaving at the end of work (or now, if later), plus the usual journey home (setting, default 70 minutes:
 * Elizabeth line to Farringdon, Thameslink to Biggleswade, the walk), plus a rough allowance for what TfL says about
 * the route; rounded up to 5 minutes and always "about". A line going bad during the journey-home window offers an
 * updated draft once ("Leaving now, home about 19:00 — Thameslink has severe delays, so I'm coming via King's Cross").
 *
 * Stored as one `context_mode` entity, id [LeavingRules.ENTITY_ID] (ADR-008 addendum 2026-10-11): `on` (Bool), `to`
 * (the family entry's name), `journeyMin` (Int64). All LWW. On by default, to "Jeanette". Plain, non-AI rules.
 *
 * Later: "when he leaves the work place, if location is on" (MEKA's location is on demand only, never background, so
 * that waits on a decision), and live departures once the Darwin key lands.
 */
object LeavingFields {
    const val ON = "on"
    const val TO = "to"
    const val JOURNEY = "journeyMin"
}

data class LeavingSetting(val on: Boolean, val to: String, val journeyMin: Int) {
    companion object {
        val DEFAULT = LeavingSetting(true, LeavingRules.DEFAULT_TO, LeavingRules.DEFAULT_JOURNEY)
    }
}

/** The words, when home is expected, the delay allowed for and the line problem behind it (null when all is well). */
data class LeavingDraft(val text: String, val etaMs: Long, val delayMin: Int, val level: LineLevel, val reason: String?)

/** What Work mode shows: the switch, who it goes to, the journey, and the line under them. */
data class LeavingView(val on: Boolean, val to: String, val journeyMin: Int, val summary: String) {
    companion object {
        val EMPTY = LeavingRules.view(null)
    }
}

/** What Send on the Fold does with what Meka was shown. */
sealed interface LeavingTap {
    /** Send exactly [text] to [to] (the words on the notification). */
    data class Send(val to: String, val text: String) : LeavingTap
    /** The draft moved on since it was shown: show [notice] (same place, new words) and wait for another tap. */
    data class Changed(val notice: Notice) : LeavingTap
}

/** Non-AI, pure. */
object LeavingRules {
    const val ENTITY_ID = "leaving_now"
    const val DEFAULT_TO = "Jeanette"
    const val DEFAULT_JOURNEY = 70
    val JOURNEY_CHOICES: List<Int> = listOf(50, 60, 70, 80, 90)
    private const val MAX_TO = 40
    /** From the end of work until the journey home is over ([RouteRules.EVENING_AFTER_MS]). */
    const val WINDOW_MS = 90 * 60_000L
    private const val STEP_MIN = 5
    const val KEY_PREFIX = "leaving:"
    /** Shown words stay sendable while they are the draft for leaving at most this long ago. */
    const val STILL_RIGHT_MS = 10 * 60_000L

    const val TITLE = "Leaving now"
    const val NOTE = "When an office day's work ends, MEKA drafts the message from your journey home and the trains. " +
        "Nothing is sent until you tap Send; Edit opens the chat with the words in."

    fun read(fields: Map<String, FieldValue>?): LeavingSetting {
        if (fields == null) return LeavingSetting.DEFAULT
        val to = cleanName(fields[LeavingFields.TO]?.textOrNull) ?: DEFAULT_TO
        val j = fields[LeavingFields.JOURNEY]?.longOrNull?.toInt()?.takeIf { it in 10..240 } ?: DEFAULT_JOURNEY
        return LeavingSetting(fields[LeavingFields.ON]?.boolOrNull ?: true, to, j)
    }

    fun cleanName(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TO && it.none { c -> c == '\n' } }

    fun view(s: LeavingSetting?): LeavingView {
        val set = s ?: LeavingSetting.DEFAULT
        return LeavingView(set.on, set.to, set.journeyMin, summary(set))
    }

    /** "On · a draft to Jeanette when work ends on office days · about 70 min home" / "Off". */
    fun summary(s: LeavingSetting): String =
        if (s.on) "On · a draft to ${s.to} when work ends on office days · about ${s.journeyMin} min home"
        else "Off · no draft when work ends"

    fun journeyLabel(min: Int): String = if (min % 60 == 0) "${min / 60} h" else if (min > 60) "${min / 60} h ${min % 60}" else "$min min"

    /** The extra minutes and the words for what TfL says now (nothing without a fresh status). */
    fun allowance(lines: LineStatusSnapshot, nowMs: Long): Triple<Int, LineLevel, String?> {
        if (!RouteRules.fresh(lines, nowMs)) return Triple(0, LineLevel.GOOD, null)
        val tl = lines.severityOf(RouteRules.THAMESLINK)
        val gn = lines.severityOf(RouteRules.GREAT_NORTHERN)
        val el = lines.severityOf(RouteRules.ELIZABETH)
        val tlLevel = tl?.let(RouteRules::level) ?: LineLevel.GOOD
        val elLevel = el?.let(RouteRules::level) ?: LineLevel.GOOD
        val tlMin = when (tlLevel) {
            LineLevel.GOOD -> 0
            LineLevel.MINOR -> 10
            LineLevel.MAJOR -> if (gn != null && RouteRules.level(gn) == LineLevel.GOOD) 20 else 30
        }
        val elMin = when (elLevel) { LineLevel.GOOD -> 0; LineLevel.MINOR -> 5; LineLevel.MAJOR -> 15 }
        val level = if (tlLevel.ordinal >= elLevel.ordinal) tlLevel else elLevel
        if (level == LineLevel.GOOD) return Triple(0, level, null)
        val reason = when {
            tlLevel == LineLevel.MAJOR && gn != null && RouteRules.level(gn) == LineLevel.GOOD ->
                "${phrase("Thameslink", tl!!)}, so I'm coming via King's Cross"
            tlLevel != LineLevel.GOOD && tlLevel.ordinal >= elLevel.ordinal -> phrase("Thameslink", tl!!)
            else -> phrase("the Elizabeth line", el!!)
        }
        return Triple(tlMin + elMin, level, reason)
    }

    /** "Thameslink has severe delays", "Thameslink is suspended", "the Elizabeth line has a part closure". */
    fun phrase(line: String, severity: Int): String {
        val w = RouteRules.words(severity)
        return when (severity) {
            1, 2, 3, 11, 15, 16, 20 -> "$line is $w"
            4, 5, 8 -> "$line has a $w"
            else -> "$line has $w"
        }
    }

    /** Rounds [ms] up to the next 5 minutes (an ETA is always "about"). */
    fun roundUp(ms: Long): Long {
        val step = STEP_MIN * 60_000L
        return (ms + step - 1).floorDiv(step) * step
    }

    /**
     * The draft for leaving at [leaveMs]: "Leaving now, home about 18:40", or with the trains' trouble after a dash:
     * "Leaving now, home about 19:10 — Thameslink has severe delays".
     */
    fun draft(s: LeavingSetting, leaveMs: Long, lines: LineStatusSnapshot, nowMs: Long, cal: LocalCalendar): LeavingDraft {
        val (extra, level, reason) = allowance(lines, nowMs)
        val eta = roundUp(leaveMs + (s.journeyMin + extra) * 60_000L)
        val at = LocalClock.formatMinute(cal.minuteOfDay(eta))
        val text = "Leaving now, home about $at" + (reason?.let { " — $it" } ?: "")
        return LeavingDraft(text, eta, extra, level, reason)
    }

    fun key(day: Long, level: LineLevel): String = "$KEY_PREFIX$day:${level.name.lowercase()}"
    fun postId(day: Long): String = "$KEY_PREFIX$day"
    fun isKey(key: String): Boolean = key.startsWith(KEY_PREFIX)

    private fun dayOf(key: String): Long? = key.removePrefix(KEY_PREFIX).substringBefore(':').toLongOrNull()

    /**
     * Today's heads-up on an office day, due when work ends and stale once the journey home is over. One per day and
     * line level, all in one place: a line going bad on the way offers the updated draft once; going good again after
     * starting good doesn't buzz again. Its text is the draft itself, word for word what Send sends.
     */
    fun notices(s: LeavingSetting, office: OfficeWindow?, lines: LineStatusSnapshot, nowMs: Long, cal: LocalCalendar): List<Notice> {
        if (!s.on || office == null) return emptyList()
        val until = office.endMs + WINDOW_MS
        if (nowMs >= until) return emptyList()
        val d = draft(s, maxOf(nowMs, office.endMs), lines, nowMs, cal)
        val day = cal.epochDayOf(office.endMs)
        return listOf(
            Notice(
                key = key(day, d.level), source = NoticeSource.LEAVING, tier = NoticeTier.HEADS_UP,
                title = if (d.level == LineLevel.GOOD) "Tell ${s.to} you're leaving?" else "Trains delayed · tell ${s.to}?",
                text = d.text, atMs = office.endMs, target = NoticeTarget.TODAY, expiresAtMs = until,
                actions = listOf(NoticeAction.SEND_MESSAGE, NoticeAction.EDIT_MESSAGE, NoticeAction.COPY_MESSAGE),
                postAs = postId(day),
            ),
        )
    }

    /**
     * Send (or Edit) tapped on notice [key] that showed [shown]: [LeavingTap.Send] with exactly those words while they
     * are still today's draft; [LeavingTap.Changed] with the new notice when the draft moved on (the words are never
     * swapped behind Meka's back); null when it no longer applies (another day, after the journey home, switched off).
     * Edit opens the chat with the words for Meka to change, so it never needs the check: [edit] skips it.
     */
    fun tap(
        key: String, shown: String, s: LeavingSetting, office: OfficeWindow?, lines: LineStatusSnapshot, nowMs: Long, cal: LocalCalendar,
        edit: Boolean = false,
    ): LeavingTap? {
        if (!isKey(key) || office == null || !s.on) return null
        if (dayOf(key) != cal.epochDayOf(office.endMs)) return null
        val now = notices(s, office, lines, nowMs, cal).firstOrNull() ?: return null
        if (edit || now.text == shown) return LeavingTap.Send(s.to, shown)
        // Still right if it is the draft for leaving up to [STILL_RIGHT_MS] ago (the ETA steps every 5 minutes).
        val stillRight = (0..STILL_RIGHT_MS step 5 * 60_000L).any { back ->
            draft(s, maxOf(office.endMs, nowMs - back), lines, nowMs, cal).text == shown
        }
        return if (stillRight) LeavingTap.Send(s.to, shown) else LeavingTap.Changed(now)
    }

    /**
     * Whether [name] (a WhatsApp sender or a Family entry, "Jeanette Akunebuni") is the person the draft goes to ([to],
     * "Jeanette"): the same name, or the same first name.
     */
    fun samePerson(to: String, name: String): Boolean {
        val a = People.key(to)
        val b = People.key(name)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.substringBefore(' ') == b.substringBefore(' ')
    }

    /** The Family entry [to] means, from [names] (an exact match first). */
    fun familyEntry(to: String, names: Collection<String>): String? =
        names.firstOrNull { People.key(it) == People.key(to) } ?: names.firstOrNull { samePerson(to, it) }

    /** The quiet note after Send: "Sent to Jeanette" · the words. */
    fun sentTitle(to: String) = "Sent to $to"
    const val OPENED_LINE = "Opened the chat with the words in · tap send in WhatsApp"

    /**
     * WhatsApp's official click-to-chat link for [number] with [text] filled in: "https://wa.me/447700900123?text=…".
     * A UK number written 07… becomes 447…; null when it can't be a phone number.
     */
    fun waLink(number: String?, text: String): String? {
        val raw = number?.trim() ?: return null
        val digits = raw.filter { it.isDigit() }
        val intl = when {
            raw.startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith("0") -> "44" + digits.drop(1)
            else -> digits
        }
        if (intl.length !in 8..15 || intl.startsWith("0")) return null
        return "https://wa.me/$intl?text=" + percentEncode(text)
    }

    /** RFC 3986 percent-encoding of UTF-8 (unreserved characters kept), no platform library needed. */
    fun percentEncode(s: String): String {
        val hex = "0123456789ABCDEF"
        val out = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch == '-' || ch == '.' || ch == '_' || ch == '~')) out.append(ch)
            else out.append('%').append(hex[c shr 4]).append(hex[c and 0x0F])
        }
        return out.toString()
    }
}

/** The setting, synced. */
class LeavingNow(private val replica: Replica) {
    fun setting(): LeavingSetting = LeavingRules.read(replica.entity(EntityTypes.CONTEXT_MODE, LeavingRules.ENTITY_ID)?.fields)

    /** False (nothing written) when the name is unusable, the journey is out of range, or nothing changes. */
    fun set(on: Boolean, to: String, journeyMin: Int): Boolean {
        val name = LeavingRules.cleanName(to) ?: return false
        if (journeyMin !in 10..240) return false
        val next = LeavingSetting(on, name, journeyMin)
        if (replica.entity(EntityTypes.CONTEXT_MODE, LeavingRules.ENTITY_ID) != null && setting() == next) return false
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, LeavingRules.ENTITY_ID,
            mapOf(LeavingFields.ON to on.fv(), LeavingFields.TO to name.fv(), LeavingFields.JOURNEY to journeyMin.toLong().fv()),
        )
        return true
    }
}
