package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Inbox autopilot, slice 7: **the ten-minute inbox** (Meka, 2026-10-12: "a daily Inbox moment — morning or evening, his
 * choice — '6 need you · 2 min each' — swipe through cards (Reply/Done/Snooze/Task); the goal line 'Inbox clear 4 days
 * running'"). Non-AI, pure, unit-tested.
 *
 * The setting lives in one synced `context_mode` entity ([ENTITY_ID]): when the moment comes ([MailMomentTime], absent =
 * [DEFAULT]), the day he said Not today, and the days his inbox was clear ([MailMomentFields.CLEAR_DAYS], the last
 * [KEEP_DAYS]). A day counts as clear when, at or after that day's moment, nothing in "From your mail" is waiting (done,
 * snoozed, made a task or replied to all count); the apps note it, so the streak is the same on both. From the moment's
 * time Needs you shows the moment's card while emails need him (until Not today); Start goes through them one at a time,
 * newest first, with the card's own answers and Skip (to the back of the queue); nothing is sent or archived but by
 * those answers.
 */
enum class MailMomentTime(val key: String, val label: String, val minute: Int?) {
    MORNING("morning", "Morning", 8 * 60),
    EVENING("evening", "Evening", 20 * 60),
    OFF("off", "Off", null),
    ;

    companion object {
        fun of(key: String?): MailMomentTime? = entries.firstOrNull { it.key == key }
    }
}

object MailMomentFields {
    const val WHEN = "when"
    const val CHANGED_AT = "changedAtMs"
    /** The days (epoch days, comma-separated) his inbox was clear at or after the moment. */
    const val CLEAR_DAYS = "clearDays"
    /** The day he said Not today (0 clears it). */
    const val PUT_OFF_DAY = "putOffDay"
}

data class MailMomentSetting(
    val time: MailMomentTime,
    val clearDays: Set<Long>,
    val putOffDay: Long?,
)

/** Needs you's card while the moment is due. */
data class MailMomentCard(
    /** "Ten-minute inbox". */
    val title: String,
    /** "6 need you · 2 min each". */
    val headline: String,
    /** "Inbox clear 4 days running", or null. */
    val streakLine: String?,
    val spoken: String,
)

/** The moment under way: one email at a time. */
data class MailMomentSession(
    /** The email up now, or null once none is left. */
    val row: MailNeedsRow?,
    /** "2 of 6", or null when none is left. */
    val position: String?,
    /** "1 done · 5 to go", or the end's "Inbox clear". */
    val line: String,
    val done: Int,
    val left: Int,
    /** "Inbox clear 4 days running" once none is left, else null. */
    val streakLine: String?,
    val spoken: String,
)

object MailMomentRules {
    const val ENTITY_ID = "mail_moment"
    const val TITLE = "Ten-minute inbox"
    const val START = "Start"
    const val NOT_TODAY = "Not today"
    const val SKIP = "Skip"
    const val FINISH = "Finish"
    const val CLOSE = "Close"
    /** The minutes each email is reckoned to take. */
    const val MIN_EACH = 2
    const val KEEP_DAYS = 60
    val DEFAULT = MailMomentTime.MORNING
    const val CLEAR_LINE = "Inbox clear"
    const val PUT_OFF_LINE = "Ten-minute inbox · not today"
    const val NOTHING_LINE = "Nothing needs you in your mail"

    fun read(fields: Map<String, FieldValue>?): MailMomentSetting = MailMomentSetting(
        time = MailMomentTime.of(fields?.get(MailMomentFields.WHEN)?.textOrNull) ?: DEFAULT,
        clearDays = DateNightRules.decodeDays(fields?.get(MailMomentFields.CLEAR_DAYS)?.textOrNull),
        putOffDay = fields?.get(MailMomentFields.PUT_OFF_DAY)?.longOrNull?.takeIf { it > 0 },
    )

    fun timeFields(time: MailMomentTime, nowMs: Long): Map<String, FieldValue> =
        mapOf(MailMomentFields.WHEN to time.key.fv(), MailMomentFields.CHANGED_AT to nowMs.fv())

    /** Notes [day] as clear, keeping the last [KEEP_DAYS]. */
    fun clearFields(s: MailMomentSetting, day: Long): Map<String, FieldValue> =
        mapOf(MailMomentFields.CLEAR_DAYS to DateNightRules.encodeDays((s.clearDays + day).filter { it > day - KEEP_DAYS }.toSet()).fv())

    /** Not today ([day]), or 0 to take it back. */
    fun putOffFields(day: Long): Map<String, FieldValue> = mapOf(MailMomentFields.PUT_OFF_DAY to day.fv())

    /** The moment has come today: on, its time reached. */
    fun reached(s: MailMomentSetting, nowMs: Long, cal: LocalCalendar): Boolean {
        val minute = s.time.minute ?: return false
        return cal.minuteOfDay(nowMs) >= minute
    }

    /**
     * The apps note today as clear when the moment has come, his mail is in Navilife ([anyMail]: the server has
     * published at least one email) and nothing is waiting ([waiting] = 0), unless it already is.
     */
    fun shouldNoteClear(s: MailMomentSetting, anyMail: Boolean, waiting: Int, nowMs: Long, cal: LocalCalendar): Boolean =
        anyMail && waiting == 0 && reached(s, nowMs, cal) && cal.epochDayOf(nowMs) !in s.clearDays

    /** Days running his inbox was clear, ending today (or yesterday while today isn't yet). */
    fun streak(clearDays: Set<Long>, today: Long): Int {
        var day = if (today in clearDays) today else today - 1
        var n = 0
        while (day in clearDays) { n++; day-- }
        return n
    }

    /** "Inbox clear 4 days running"; "Inbox clear yesterday · keep it going" for one day; null for none. */
    fun streakLine(clearDays: Set<Long>, today: Long): String? = when (val n = streak(clearDays, today)) {
        0 -> null
        1 -> if (today in clearDays) "Inbox clear today · keep it going" else "Inbox clear yesterday · keep it going"
        else -> "Inbox clear $n days running"
    }

    fun headline(n: Int): String = if (n == 1) "1 needs you · $MIN_EACH min" else "$n need you · $MIN_EACH min each"

    /** Needs you's card: the moment has come, he hasn't said Not today, and [waiting] emails need him. */
    fun card(s: MailMomentSetting, waiting: Int, nowMs: Long, cal: LocalCalendar): MailMomentCard? {
        if (waiting <= 0 || !reached(s, nowMs, cal)) return null
        val today = cal.epochDayOf(nowMs)
        if (s.putOffDay == today) return null
        val headline = headline(waiting)
        val streak = streakLine(s.clearDays, today)
        return MailMomentCard(TITLE, headline, streak, listOfNotNull("$TITLE. $headline", streak).joinToString(". "))
    }

    /**
     * The session: [rows] are the emails still needing him (newest first), [skipped] the ones he skipped in this
     * session, oldest skip first (they go to the back), [started] how many there were at Start.
     */
    fun session(rows: List<MailNeedsRow>, skipped: List<String>, started: Int, s: MailMomentSetting, today: Long): MailMomentSession {
        val back = skipped.filter { id -> rows.any { it.id == id } }
        val order = rows.filter { it.id !in back } + back.mapNotNull { id -> rows.firstOrNull { it.id == id } }
        val left = order.size
        val total = maxOf(started, left)
        val done = total - left
        val row = order.firstOrNull()
        if (row == null) {
            val streak = streakLine(s.clearDays + today, today)
            return MailMomentSession(null, null, CLEAR_LINE, done, 0, streak, listOfNotNull(CLEAR_LINE, streak).joinToString(". "))
        }
        val line = when {
            done == 0 -> if (left == 1) "1 to go · about $MIN_EACH min" else "$left to go · about ${left * MIN_EACH} min"
            else -> "$done done · $left to go"
        }
        val position = "${done + 1} of $total"
        return MailMomentSession(row, position, line, done, left, null, "$position. $line. ${row.spoken}")
    }

    /** A heads-up older than this (after the moment's time) is stale: the card still waits in Needs you. */
    const val NOTICE_STALE_MS = 4 * 60 * 60_000L

    /**
     * Slice 7b: the heads-up at the moment's time (source [NoticeSource.MAIL_MOMENT], through the governor, so quiet
     * hours hold it like any heads-up): "Ten-minute inbox · 6 need you" · "About 12 min · Inbox clear 4 days running",
     * opening Needs you. None when the moment is off, he said Not today, today is already clear, nothing needs him, or
     * the moment falls in protected time ([protectedFromMin]: when today's starts, 0 for a wholly protected day): the
     * card still waits in Needs you, it just doesn't call him. Stale [NOTICE_STALE_MS] after the moment.
     */
    fun notices(s: MailMomentSetting, waiting: Int, protectedFromMin: Int?, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val minute = s.time.minute ?: return emptyList()
        val today = cal.epochDayOf(nowMs)
        if (waiting <= 0 || s.putOffDay == today || today in s.clearDays) return emptyList()
        if (protectedFromMin != null && minute >= protectedFromMin) return emptyList()
        val at = cal.toEpochMs(today, minute)
        val about = if (waiting == 1) "About $MIN_EACH min" else "About ${waiting * MIN_EACH} min"
        val text = listOfNotNull(about, streakLine(s.clearDays, today)).joinToString(" · ")
        val need = if (waiting == 1) "1 needs you" else "$waiting need you"
        return listOf(Notice(
            key = "mail-moment:$today", source = NoticeSource.MAIL_MOMENT, tier = NoticeTier.HEADS_UP,
            title = "$TITLE · $need", text = text, atMs = at, target = NoticeTarget.NEEDS_YOU,
            expiresAtMs = at + NOTICE_STALE_MS,
        ))
    }

    /** The Mail pane's line for the setting. */
    fun settingLine(time: MailMomentTime): String = when (time) {
        MailMomentTime.OFF -> "Off · From your mail still lists what needs you"
        else -> "${time.label}s from ${LocalClock.formatMinute(time.minute!!)} · Needs you offers a few minutes to clear what needs you"
    }

    fun switchedLine(time: MailMomentTime): String = when (time) {
        MailMomentTime.OFF -> "Ten-minute inbox off"
        else -> "Ten-minute inbox · ${time.label.lowercase()}s from ${LocalClock.formatMinute(time.minute!!)}"
    }
}
