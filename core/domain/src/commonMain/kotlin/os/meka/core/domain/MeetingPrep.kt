package os.meka.core.domain

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
) {
    val hasSomething: Boolean get() = lastEmail != null || owed.isNotEmpty()
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
)

object MeetingPrepRules {
    const val LEAD_MIN = 15
    const val AFTER_START_MIN = 10
    const val MAX_LINES = 4
    /** Names written out in [MeetingPrep.whoLine] before "and N more". */
    const val NAMED = 2
    /** Notices are made for meetings starting within this many hours. */
    const val NOTICE_HORIZON_H = 24
    const val NOTHING_LINE = "No recent emails or anything open with them"

    /** A timed event with other people on it (not a fixture, not all-day). */
    fun isMeeting(e: CalendarEvent): Boolean =
        !e.allDay && !e.isFixture && e.guests.isNotEmpty() && e.endAtMs >= e.startAtMs

    /** The meeting to prepare for now: the soonest one between [LEAD_MIN] before its start and [AFTER_START_MIN] after. */
    fun next(events: List<CalendarEvent>, marks: EventMarks, nowMs: Long): CalendarEvent? =
        events.filter { e ->
            isMeeting(e) && !marks.isHidden(e.id) &&
                nowMs >= e.startAtMs - LEAD_MIN * 60_000L && nowMs < e.startAtMs + AFTER_START_MIN * 60_000L
        }.minWithOrNull(compareBy<CalendarEvent> { it.startAtMs }.thenBy { it.id })

    /** Today's card, or null when no meeting with other people is about to start. */
    fun card(
        events: List<CalendarEvent>,
        marks: EventMarks,
        mail: List<MailItem>,
        promised: List<PromiseItem>,
        waiting: List<WaitingItem>,
        nowMs: Long,
        cal: LocalCalendar,
    ): MeetingPrep? = next(events, marks, nowMs)?.let { prep(it, mail, promised, waiting, nowMs, cal) }

    fun prep(
        e: CalendarEvent,
        mail: List<MailItem>,
        promised: List<PromiseItem>,
        waiting: List<WaitingItem>,
        nowMs: Long,
        cal: LocalCalendar,
    ): MeetingPrep {
        val people = e.guests.map { person(it, mail, promised, waiting, nowMs, cal) }
        val lines = lines(people)
        val headline = headline(e, nowMs, cal)
        val who = whoLine(e.guests)
        val spoken = buildString {
            append(headline.replace(" · ", ". ")).append(". ").append(who).append('.')
            people.filter { it.hasSomething }.forEach { p ->
                append(' ').append(p.name).append('.')
                p.lastEmail?.let { append(' ').append(it.replace(" · ", ", ")).append('.') }
                p.owed.forEach { append(' ').append(it.replace(" · ", ", ")).append('.') }
            }
        }
        return MeetingPrep(e.id, e.startAtMs, headline, who, people, lines, spoken, e.joinUrl)
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
    ): PrepPerson {
        val key = People.key(name)
        val theirs = mail.filter { same(it.from, key) && it.receivedAtMs <= nowMs }
        val last = theirs.maxWithOrNull(compareBy<MailItem> { latest(it, nowMs) }.thenBy { it.id })
        val lastEmail = last?.let { m ->
            val replied = m.replySentAtMs?.takeIf { it in m.receivedAtMs..nowMs }
            val subject = m.subject.trim().take(50).takeIf { it.isNotEmpty() }?.let { " · “$it”" }.orEmpty()
            if (replied != null) "You replied ${HeldPreviewRules.timeLabel(replied, nowMs, cal)}$subject"
            else "Last email ${HeldPreviewRules.timeLabel(m.receivedAtMs, nowMs, cal)}$subject"
        }
        val owed = promised.filter { p -> p.to?.let { same(it, key) } == true }
            .sortedWith(compareBy<PromiseItem> { if (it.state == DueState.DUE) 0 else 1 }.thenBy { it.byDay ?: Long.MAX_VALUE }.thenBy { it.id })
            .map { "You promised: ${it.title}" + (bare(it.meta, key)?.let { m -> " · $m" } ?: "") } +
            waiting.filter { w -> w.who?.let { same(it, key) } == true }
                .sortedWith(compareBy<WaitingItem> { if (it.state == DueState.DUE) 0 else 1 }.thenBy { it.chaseDay ?: Long.MAX_VALUE }.thenBy { it.id })
                .map { "Waiting for: ${it.title}" + (bare(it.meta, key)?.let { m -> " · $m" } ?: "") }
        return PrepPerson(name, lastEmail, owed)
    }

    /** Today's lines: each person's last email, then what's owed, named ("Sam Taylor · …"); capped with "+N more". */
    fun lines(people: List<PrepPerson>): List<String> {
        val all = people.flatMap { p ->
            listOfNotNull(p.lastEmail?.let { "${p.name} · ${lower(it)}" }) + p.owed.map { "${p.name} · ${lower(it)}" }
        }
        if (all.isEmpty()) return listOf(NOTHING_LINE)
        if (all.size <= MAX_LINES) return all
        return all.take(MAX_LINES - 1) + "+${all.size - (MAX_LINES - 1)} more"
    }

    /**
     * One heads-up per meeting with other people, [LEAD_MIN] before it starts: "Prep · Quote review at 14:00" ·
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
    ): List<Notice> = events.filter { e ->
        isMeeting(e) && !marks.isHidden(e.id) && e.startAtMs + AFTER_START_MIN * 60_000L > nowMs &&
            e.startAtMs - nowMs <= NOTICE_HORIZON_H * 3_600_000L
    }.map { e ->
        val people = e.guests.map { person(it, mail, promised, waiting, nowMs, cal) }
        val at = LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))
        Notice(
            key = "prep:${e.id}:${e.startAtMs}", source = NoticeSource.MEETING_PREP, tier = NoticeTier.HEADS_UP,
            title = "Prep · ${e.title.trim().take(60)} at $at", text = noticeText(e.guests, people),
            atMs = e.startAtMs - LEAD_MIN * 60_000L, target = NoticeTarget.TODAY,
            expiresAtMs = e.startAtMs + AFTER_START_MIN * 60_000L,
        )
    }

    /** "With Sam Taylor · last email Tue 13:02 · you promised 1 · waiting for 1". */
    fun noticeText(guests: List<String>, people: List<PrepPerson>): String {
        val newest = people.mapNotNull { it.lastEmail }.firstOrNull()?.substringBefore(" · “")?.let(::lower)
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
