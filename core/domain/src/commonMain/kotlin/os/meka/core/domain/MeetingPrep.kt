package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Meeting prep, slice 1 (build plan V1, "Meeting prep"; Meka's email assistant set, Fri 16 Oct): fifteen minutes
 * before a meeting with other people, who's coming, the last email MEKA holds from each of them and what's open
 * between them (what Meka promised them, what he's waiting on from them). Shown on Today from [LEAD_MIN] before the
 * start until [AFTER_START_MIN] after it, and as one heads-up at [LEAD_MIN] before (source [NoticeSource.MEETING_PREP]).
 *
 * The guests come from the calendar (the server writes their names only, never addresses, without Meka himself, rooms or
 * anyone who declined: [EventFields.GUESTS]). The emails are the synced `mail_item`s (sender's name, subject, when;
 * never a body) and the promises and waiting-fors come from Lists. Nothing new is read or sent; non-AI and pure.
 * Guests' names and email subjects are other people's words (ADR-006): shown as text only, never acted on.
 */
data class PrepPerson(
    val name: String,
    /** "Last email Tue 13:02 · “Re: quote”", "You replied 09:40 · “Re: quote”"; null when MEKA holds none. */
    val lastEmail: String?,
    /** "You promised: Send the figures · by today", "Waiting for: Signed contract · chase Fri 16 Oct". */
    val owed: List<String>,
    /**
     * Slice 2: "Last met Tue 6 Oct · “Quote kickoff”" — the latest earlier meeting in the calendar with this person on it
     * (only meetings mirrored since guests were written, slice 1); null when there is none.
     */
    val lastMet: String? = null,
) {
    val hasSomething: Boolean get() = lastEmail != null || owed.isNotEmpty() || lastMet != null
}

data class MeetingPrep(
    val eventId: String,
    val startAtMs: Long,
    /** "Prep · Quote review at 14:00 · in 12 min", "Prep · Quote review · started 14:00". */
    val headline: String,
    /** "With Sam Taylor and Alex Green", "With Sam Taylor, Alex Green and 3 more". */
    val whoLine: String,
    val people: List<PrepPerson>,
    /** What Today shows under the headline, at most [MeetingPrepRules.MAX_LINES] (the last may be "+2 more"). */
    val lines: List<String>,
    val spoken: String,
    /** The provider's call link, when the meeting has one. */
    val joinUrl: String? = null,
    /** Slice 3: the meeting's title (trimmed to 60), for the watch's own headline. */
    val title: String = "",
)

/**
 * Slice 2: how long before a meeting the prep shows (Today's card and the heads-up), or Off. Synced on one `context_mode`
 * entity, id [MeetingPrepRules.ENTITY_ID] (ADR-008 addendum 2026-10-15): `leadMin` (Int64, 0 = off) and `changedAtMs`,
 * LWW, either app writes. Absent = [MeetingPrepRules.LEAD_MIN] (15 min), so nothing changes until he picks.
 */
enum class MeetingPrepLead(val key: String, val label: String, val minutes: Int) {
    OFF("off", "Off", 0),
    FIVE("5", "5 min", 5),
    TEN("10", "10 min", 10),
    FIFTEEN("15", "15 min", 15),
    THIRTY("30", "30 min", 30),
    ;

    companion object {
        fun of(key: String?): MeetingPrepLead? = entries.firstOrNull { it.key == key }
        /** A stored lead back to its chip: 0 or less is Off, else the nearest chip at or above it (never Off by mistake). */
        fun ofMinutes(min: Long): MeetingPrepLead =
            if (min <= 0) OFF else entries.filter { it != OFF && it.minutes >= min }.minByOrNull { it.minutes } ?: THIRTY
    }
}

object MeetingPrepFields {
    const val LEAD_MIN = "leadMin"
    const val CHANGED_AT = "changedAtMs"
}

object MeetingPrepRules {
    const val ENTITY_ID = "meeting_prep"
    const val TITLE = "Meeting prep"
    val DEFAULT = MeetingPrepLead.FIFTEEN
    /** How far back "Last met" looks. */
    const val MET_LOOKBACK_DAYS = 365
    const val LEAD_MIN = 15
    const val AFTER_START_MIN = 10
    const val MAX_LINES = 4
    /** Names written out in [MeetingPrep.whoLine] before "and N more". */
    const val NAMED = 2
    /** Notices are made for meetings starting within this many hours. */
    const val NOTICE_HORIZON_H = 24
    const val NOTHING_LINE = "No recent emails or anything open with them"

    fun read(fields: Map<String, FieldValue>?): MeetingPrepLead =
        fields?.get(MeetingPrepFields.LEAD_MIN)?.longOrNull?.let { MeetingPrepLead.ofMinutes(it) } ?: DEFAULT

    fun settingFields(lead: MeetingPrepLead, nowMs: Long): Map<String, FieldValue> =
        mapOf(MeetingPrepFields.LEAD_MIN to lead.minutes.toLong().fv(), MeetingPrepFields.CHANGED_AT to nowMs.fv())

    /** Under the title in Mail. */
    fun settingLine(lead: MeetingPrepLead): String =
        if (lead == MeetingPrepLead.OFF) "Off · no prep on Today and no heads-up before meetings"
        else "${lead.label} before a meeting with other people · on Today and as a heads-up: who's coming, when you last met, " +
            "your last emails and what's open"

    /** What Mail says after a change. */
    fun switchedLine(lead: MeetingPrepLead): String =
        if (lead == MeetingPrepLead.OFF) "Meeting prep off" else "Meeting prep ${lead.label} before each meeting with other people"

    /** A timed event with other people on it (not a fixture, not all-day). */
    fun isMeeting(e: CalendarEvent): Boolean =
        !e.allDay && !e.isFixture && e.guests.isNotEmpty() && e.endAtMs >= e.startAtMs

    /**
     * The meeting to prepare for now: the soonest one between [leadMin] (his setting, slice 2) before its start and
     * [AFTER_START_MIN] after; none when prep is off.
     */
    fun next(events: List<CalendarEvent>, marks: EventMarks, nowMs: Long, leadMin: Int = LEAD_MIN): CalendarEvent? {
        if (leadMin <= 0) return null
        return events.filter { e ->
            isMeeting(e) && !marks.isHidden(e.id) &&
                nowMs >= e.startAtMs - leadMin * 60_000L && nowMs < e.startAtMs + AFTER_START_MIN * 60_000L
        }.minWithOrNull(compareBy<CalendarEvent> { it.startAtMs }.thenBy { it.id })
    }

    /** Today's card, or null when no meeting with other people is about to start. */
    fun card(
        events: List<CalendarEvent>,
        marks: EventMarks,
        mail: List<MailItem>,
        promised: List<PromiseItem>,
        waiting: List<WaitingItem>,
        nowMs: Long,
        cal: LocalCalendar,
        leadMin: Int = LEAD_MIN,
    ): MeetingPrep? = next(events, marks, nowMs, leadMin)?.let { prep(it, mail, promised, waiting, nowMs, cal, events, marks) }

    fun prep(
        e: CalendarEvent,
        mail: List<MailItem>,
        promised: List<PromiseItem>,
        waiting: List<WaitingItem>,
        nowMs: Long,
        cal: LocalCalendar,
        /** Slice 2: the calendar, for when he last met each guest. */
        events: List<CalendarEvent> = emptyList(),
        marks: EventMarks? = null,
    ): MeetingPrep {
        val people = e.guests.map { person(it, mail, promised, waiting, nowMs, cal, lastMet(it, e, events, marks, nowMs, cal), mailbox(e, it)) }
        val lines = lines(people)
        val headline = headline(e, nowMs, cal)
        val who = whoLine(e.guests)
        val spoken = buildString {
            append(headline.replace(" · ", ". ")).append(". ").append(who).append('.')
            people.filter { it.hasSomething }.forEach { p ->
                append(' ').append(p.name).append('.')
                p.lastMet?.let { append(' ').append(it.replace(" · ", ", ")).append('.') }
                p.lastEmail?.let { append(' ').append(it.replace(" · ", ", ")).append('.') }
                p.owed.forEach { append(' ').append(it.replace(" · ", ", ")).append('.') }
            }
        }
        return MeetingPrep(e.id, e.startAtMs, headline, who, people, lines, spoken, e.joinUrl, e.title.trim().take(60))
    }

    /** "Prep · Quote review at 14:00 · in 12 min", "… · now", "Prep · Quote review · started 14:00". */
    fun headline(e: CalendarEvent, nowMs: Long, cal: LocalCalendar): String {
        val at = LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))
        val title = e.title.trim().take(60)
        if (nowMs >= e.startAtMs) return "Prep · $title · started $at"
        val mins = ((e.startAtMs - nowMs + 59_999L) / 60_000L).coerceAtLeast(1)
        return "Prep · $title at $at · in $mins min"
    }

    /** "With Sam Taylor", "With Sam Taylor and Alex Green", "With Sam Taylor, Alex Green and 3 more". */
    fun whoLine(guests: List<String>): String = when {
        guests.isEmpty() -> ""
        guests.size == 1 -> "With ${guests[0]}"
        guests.size == 2 -> "With ${guests[0]} and ${guests[1]}"
        guests.size == NAMED + 1 -> "With ${guests[0]}, ${guests[1]} and ${guests[2]}"
        else -> "With ${guests.take(NAMED).joinToString(", ")} and ${guests.size - NAMED} more"
    }

    fun person(
        name: String,
        mail: List<MailItem>,
        promised: List<PromiseItem>,
        waiting: List<WaitingItem>,
        nowMs: Long,
        cal: LocalCalendar,
        lastMet: String? = null,
        /** Slice 4: their newest email in the mailboxes themselves (the server's lookup), when newer than any synced one. */
        mailbox: GuestMail? = null,
    ): PrepPerson {
        val key = People.key(name)
        val theirs = mail.filter { same(it.from, key) && it.receivedAtMs <= nowMs }
        val last = theirs.maxWithOrNull(compareBy<MailItem> { latest(it, nowMs) }.thenBy { it.id })
        val fromBox = mailbox?.takeIf { it.receivedAtMs <= nowMs && (last == null || it.receivedAtMs > latest(last, nowMs)) }
        val lastEmail = if (fromBox != null) {
            "Last email ${HeldPreviewRules.timeLabel(fromBox.receivedAtMs, nowMs, cal)}${quoted(fromBox.subject)}"
        } else last?.let { m ->
            val replied = m.replySentAtMs?.takeIf { it in m.receivedAtMs..nowMs }
            val subject = quoted(m.subject)
            if (replied != null) "You replied ${HeldPreviewRules.timeLabel(replied, nowMs, cal)}$subject"
            else "Last email ${HeldPreviewRules.timeLabel(m.receivedAtMs, nowMs, cal)}$subject"
        }
        val owed = promised.filter { p -> p.to?.let { same(it, key) } == true }
            .sortedWith(compareBy<PromiseItem> { if (it.state == DueState.DUE) 0 else 1 }.thenBy { it.byDay ?: Long.MAX_VALUE }.thenBy { it.id })
            .map { "You promised: ${it.title}" + (bare(it.meta, key)?.let { m -> " · $m" } ?: "") } +
            waiting.filter { w -> w.who?.let { same(it, key) } == true }
                .sortedWith(compareBy<WaitingItem> { if (it.state == DueState.DUE) 0 else 1 }.thenBy { it.chaseDay ?: Long.MAX_VALUE }.thenBy { it.id })
                .map { "Waiting for: ${it.title}" + (bare(it.meta, key)?.let { m -> " · $m" } ?: "") }
        return PrepPerson(name, lastEmail, owed, lastMet)
    }

    /**
     * Slice 2: "Last met Tue 6 Oct · “Quote kickoff”" ("Last met yesterday", "Last met earlier today") — the latest
     * meeting before [meeting] (started before it, already begun by now, not hidden, within [MET_LOOKBACK_DAYS]) with this
     * person on it; null when the calendar holds none.
     */
    fun lastMet(
        name: String, meeting: CalendarEvent, events: List<CalendarEvent>, marks: EventMarks?, nowMs: Long, cal: LocalCalendar,
    ): String? {
        val key = People.key(name)
        val since = meeting.startAtMs - MET_LOOKBACK_DAYS * 86_400_000L
        val met = events.filter { e ->
            e.id != meeting.id && isMeeting(e) && marks?.isHidden(e.id) != true && e.startAtMs < meeting.startAtMs &&
                e.startAtMs <= nowMs && e.startAtMs >= since && e.guests.any { same(it, key) }
        }.maxWithOrNull(compareBy<CalendarEvent> { it.startAtMs }.thenBy { it.id }) ?: return null
        val day = cal.epochDayOf(met.startAtMs)
        val today = cal.epochDayOf(nowMs)
        val whenLabel = when (day) {
            today -> "earlier today"
            today - 1 -> "yesterday"
            else -> CivilDate.shortLabel(day)
        }
        val title = met.title.trim().take(50).takeIf { it.isNotEmpty() }?.let { " · “$it”" }.orEmpty()
        return "Last met $whenLabel$title"
    }

    /**
     * Today's lines: each person's last email, then what's owed, then (slice 2) when you last met, named ("Sam Taylor ·
     * …"); capped with "+N more".
     */
    fun lines(people: List<PrepPerson>): List<String> {
        val all = people.flatMap { p ->
            listOfNotNull(p.lastEmail?.let { "${p.name} · ${lower(it)}" }) + p.owed.map { "${p.name} · ${lower(it)}" } +
                listOfNotNull(p.lastMet?.let { "${p.name} · ${lower(it)}" })
        }
        if (all.isEmpty()) return listOf(NOTHING_LINE)
        if (all.size <= MAX_LINES) return all
        return all.take(MAX_LINES - 1) + "+${all.size - (MAX_LINES - 1)} more"
    }

    /**
     * One heads-up per meeting with other people, [leadMin] (his setting, slice 2; none when off) before it starts: "Prep · Quote review at 14:00" ·
     * "With Sam Taylor · last email Tue 13:02 · you promised 1". Opens Today; gone [AFTER_START_MIN] after the start.
     */
    fun notices(
        events: List<CalendarEvent>,
        marks: EventMarks,
        mail: List<MailItem>,
        promised: List<PromiseItem>,
        waiting: List<WaitingItem>,
        nowMs: Long,
        cal: LocalCalendar,
        leadMin: Int = LEAD_MIN,
    ): List<Notice> = if (leadMin <= 0) emptyList() else events.filter { e ->
        isMeeting(e) && !marks.isHidden(e.id) && e.startAtMs + AFTER_START_MIN * 60_000L > nowMs &&
            e.startAtMs - nowMs <= NOTICE_HORIZON_H * 3_600_000L
    }.map { e ->
        val people = e.guests.map { person(it, mail, promised, waiting, nowMs, cal, lastMet(it, e, events, marks, nowMs, cal), mailbox(e, it)) }
        val at = LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))
        Notice(
            key = "prep:${e.id}:${e.startAtMs}", source = NoticeSource.MEETING_PREP, tier = NoticeTier.HEADS_UP,
            title = "Prep · ${e.title.trim().take(60)} at $at", text = noticeText(e.guests, people),
            atMs = e.startAtMs - leadMin * 60_000L, target = NoticeTarget.TODAY,
            expiresAtMs = e.startAtMs + AFTER_START_MIN * 60_000L,
        )
    }

    /**
     * "With Sam Taylor · last email Tue 13:02 · you promised 1 · waiting for 1"; with no email held, when they last met
     * ("With Sam Taylor · last met Tue 6 Oct", slice 2).
     */
    fun noticeText(guests: List<String>, people: List<PrepPerson>): String {
        val newest = (people.mapNotNull { it.lastEmail }.firstOrNull() ?: people.mapNotNull { it.lastMet }.firstOrNull())
            ?.substringBefore(" · “")?.let(::lower)
        val promised = people.sumOf { p -> p.owed.count { it.startsWith("You promised") } }
        val waiting = people.sumOf { p -> p.owed.count { it.startsWith("Waiting for") } }
        return listOfNotNull(
            whoLine(guests),
            newest,
            promised.takeIf { it > 0 }?.let { "you promised $it" },
            waiting.takeIf { it > 0 }?.let { "waiting for $it" },
        ).joinToString(" · ")
    }

    /** A promise's or waiting-for's meta without the person ("Sam · by Fri 16 Oct" → "by Fri 16 Oct"); null if nothing's left. */
    private fun bare(meta: String, key: String): String? {
        val parts = meta.split(" · ")
        val rest = if (parts.size > 1 && same(parts[0], key)) parts.drop(1) else if (same(meta, key)) emptyList() else parts
        return rest.joinToString(" · ").trim().takeIf { it.isNotEmpty() }
    }

    private fun quoted(subject: String): String = subject.trim().take(50).takeIf { it.isNotEmpty() }?.let { " · “$it”" }.orEmpty()

    /** Slice 4: the server's newest email from this guest of [e] (same name as written in its guests), or null. */
    fun mailbox(e: CalendarEvent, name: String): GuestMail? {
        val key = People.key(name)
        return e.guestMail.firstOrNull { People.key(it.name) == key }
    }

    private fun latest(m: MailItem, nowMs: Long): Long =
        maxOf(m.receivedAtMs, m.replySentAtMs?.takeIf { it <= nowMs } ?: 0L)

    /** The same person: the whole name, or a first name matching a one-word name on either side ("Sam" ~ "Sam Taylor"). */
    private fun same(name: String, key: String): Boolean {
        val k = People.key(name)
        if (k.isEmpty() || key.isEmpty()) return false
        if (k == key) return true
        val a = k.split(' ')
        val b = key.split(' ')
        return (a.size == 1 || b.size == 1) && a[0] == b[0]
    }

    private fun lower(s: String): String =
        if (s.startsWith("You ")) "you " + s.removePrefix("You ") else s.replaceFirstChar { it.lowercase() }
}
