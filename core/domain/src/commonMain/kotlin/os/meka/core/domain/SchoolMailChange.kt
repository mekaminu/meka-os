package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * School emails first, slice 4c (Meka 2026-11-24: "auto create stuff in my calendar … so I remember"; non-AI, pure):
 * **a changed date or a cancellation updates what was made, and says so**. The server marks a school find whose own
 * sentence says the date was called off ("The Christmas fair on Saturday 28 November has been cancelled") or moved ("has
 * been moved to Sunday 29 November", "will now be on") ([SchoolBodyRules.change], `mail_find.schoolChange`). When such a
 * find is about one he already has (added on its own or by his Add, with its day still to come: the same thing, child and
 * name, from an email no later), and "School events from the school's emails" is on:
 *
 * - **cancelled**: the earlier date's School line and its tasks are taken out, and its calendar entry is taken back;
 * - **moved**: the same, and the new date is done for him as any school date is (School line, tasks, reminders, calendar).
 *
 * Either is one Activity entry on the change's find ("Done for you · from the school": "School fair cancelled · was Sat
 * 28 Nov · taken out of School, your calendar and 2 tasks"), whose Undo puts the earlier date's line and tasks back. The
 * earlier date's own Done for you row gives way to it. A cancellation is never added and never offered with Add; a later
 * email that calls off or moves a date read in the same check (the catch-up) stops the earlier one being added at all.
 *
 * Every word is other people's text (ADR-006): the change only ever takes out or moves what Navilife itself made from the
 * school's emails; nothing is paid, signed or replied to.
 */
object SchoolChangeRules {
    const val CANCELLED = "cancelled"
    const val MOVED = "moved"

    private val CHANGE_WORDS = Regex("""\b(?:cancel+ed|postponed|moved|rescheduled|new|date|now|off)\b""")

    private fun title(s: String?): String = SchoolAutoRules.normTitle((s ?: "").lowercase().replace(CHANGE_WORDS, " "))

    /**
     * Whether change [b] is about the earlier find [a]: both the school's, the same kind of thing (an event, a payment or
     * form, an ask), the same child (or either for all of them), the same name give or take "Reminder:" and the change's
     * own words, [a]'s email no later than [b]'s; a cancellation is about [a]'s own day, a move about another day.
     */
    fun about(b: MailFind, a: MailFind): Boolean {
        // Slice 4e: a cancellation whose date can't be read for sure (or that gives none) never acts on its own.
        if (b.unclearDate) return false
        return sameThing(b, a) && when (b.schoolChange) {
            CANCELLED -> a.renewDay == b.renewDay
            MOVED -> a.renewDay != b.renewDay
            else -> false
        }
    }

    /** [about] without the day: both the school's, the same kind, child and name, [a]'s email no later than [b]'s. */
    private fun sameThing(b: MailFind, a: MailFind): Boolean {
        if (a.id == b.id || a.kind != SchoolMailRules.KIND_SCHOOL || b.kind != SchoolMailRules.KIND_SCHOOL) return false
        if (a.schoolChange == CANCELLED || a.receivedDay > b.receivedDay) return false
        if (SchoolRules.isDue(a.what ?: "") != SchoolRules.isDue(b.what ?: "")) return false
        if (SchoolMailRules.isAsk(a) != SchoolMailRules.isAsk(b)) return false
        if ((a.schoolKind ?: "") != (b.schoolKind ?: "") || SchoolMailRules.isWeekly(a)) return false
        val wa = a.who.orEmpty(); val wb = b.who.orEmpty()
        if (wa.isNotEmpty() && wb.isNotEmpty() && !wa.equals(wb, ignoreCase = true)) return false
        val ta = title(a.what); val tb = title(b.what)
        if (ta.length < 3 || tb.length < 3) return false
        return ta == tb || ta.contains(tb) || tb.contains(ta)
    }

    // ---- Slice 4e: a cancellation to check ----

    /** Slice 4e: [b] is a cancellation that waits for his check: its date can't be read for sure, or it gives none. */
    fun toCheck(b: MailFind): Boolean =
        b.kind == SchoolMailRules.KIND_SCHOOL && b.schoolChange == CANCELLED && b.unclearDate && b.state == MailFindState.OPEN

    /** The finds a standing change took out or moved, read from the finds alone (an undone change's find is open again). */
    fun replacedByAnswered(finds: List<MailFind>): Set<String> =
        finds.filter { it.replacesId != null && it.state == MailFindState.ADDED }.mapNotNull { it.replacesId }.toSet()

    /**
     * Slice 4e: what cancellation [b] to check would take out: the soonest date he has (answered Added, still to come)
     * of the same thing, any day; null when he has none (the email's card says it; nothing to take out).
     */
    fun checkTarget(b: MailFind, finds: List<MailFind>, today: Long, replaced: Set<String> = replacedByAnswered(finds)): MailFind? {
        if (!toCheck(b)) return null
        return finds.filter { a -> a.state == MailFindState.ADDED && a.renewDay >= today && a.id !in replaced && sameThing(b, a) }
            .minByOrNull { it.renewDay }
    }

    const val TAKE_OUT = "Take it out"
    const val KEEP = "Keep it"
    const val SAYS_CANCELLED = "the school says it's cancelled"
    const val CHECK = "check it's this one"

    /**
     * Slice 4e: the card's row for cancellation [b] to check, about [a]: "School fair · Sat 28 Nov · the school says it's
     * cancelled · check it's this one", with Take it out (his confirmation) and Keep it.
     */
    fun checkRow(b: MailFind, a: MailFind, today: Long): MailFindRow {
        val title = SchoolMailRules.title(b)
        val what = a.what ?: b.what ?: "School date"
        val line = listOf(what, RenewalRules.dayWord(a.renewDay, today), SAYS_CANCELLED, CHECK).joinToString(" · ")
        return MailFindRow(
            id = b.id, title = title, line = line, from = MailRenewalRules.fromLine(b),
            business = b.account == MailLabel.BUSINESS.label,
            spoken = "$title. $line. From your ${b.account} mail.",
            addSpoken = "Take $what out of School, your tasks and your calendar",
            notNeededSpoken = "Keep $what",
            addLabel = TAKE_OUT, notNeededLabel = KEEP,
        )
    }

    /**
     * The find change [b] takes out or moves: one he has (answered Added, by Navilife or his Add), its day still to come
     * (from [today]), not already taken out or moved by another change ([replaced]); the soonest.
     */
    fun target(b: MailFind, finds: List<MailFind>, today: Long, replaced: Set<String> = emptySet()): MailFind? =
        finds.filter { a -> a.state == MailFindState.ADDED && a.renewDay >= today && a.id !in replaced && about(b, a) }
            .minByOrNull { it.renewDay }

    /** The open changes not yet acted on (no auto mark), each with what it takes out or moves; oldest email first. */
    fun pending(finds: List<MailFind>, today: Long, replaced: Set<String> = emptySet()): List<Pair<MailFind, MailFind>> =
        finds.filter { b ->
            b.kind == SchoolMailRules.KIND_SCHOOL && b.schoolChange != null && b.state == MailFindState.OPEN && b.autoAtMs == null &&
                (b.schoolChange == CANCELLED || b.renewDay >= today)
        }.sortedWith(compareBy<MailFind> { it.receivedDay }.thenBy { it.id })
            .mapNotNull { b -> target(b, finds, today, replaced)?.let { b to it } }

    /** A later find in [finds] that calls off or moves [a] (both read in the same check); null when none. */
    fun supersededBy(a: MailFind, finds: List<MailFind>): MailFind? =
        if (a.schoolChange == CANCELLED) null else finds.firstOrNull { b -> b.schoolChange != null && about(b, a) }

    /** The finds taken out or moved by a change whose Activity entry stands (an undone change gives them back). */
    fun replaced(finds: List<MailFind>, entries: Map<String, ActivityItem>): Set<String> =
        finds.filter { it.replacesId != null && it.autoAtMs != null }
            .filter { b -> entries[SchoolAutoRules.activityId(b.id)]?.let { it.undoneAtMs == null } == true }
            .mapNotNull { it.replacesId }.toSet()

    /** The change's link to what it took out or moved, written with its auto mark (outside the recorded change). */
    fun replacesFields(targetId: String): Map<String, FieldValue> = mapOf(MailFindFields.REPLACES to targetId.fv())

    /** Slice 4e: the bar's line after Take it out: "Taken out: School fair · Sat 28 Nov · from School, your tasks and calendar". */
    fun takenOutLine(a: MailFind, today: Long): String =
        "Taken out: ${a.what ?: "School date"} · ${RenewalRules.dayWord(a.renewDay, today)} · from School, your tasks and calendar"

    /** What a cancellation took out: the School line, how many tasks, whether a calendar entry is being taken back. */
    data class Removed(val school: Boolean, val tasks: Int, val calendar: Boolean)

    private fun and(parts: List<String>): String =
        if (parts.size <= 1) parts.joinToString() else parts.dropLast(1).joinToString(", ") + " and " + parts.last()

    /** "From the school: School fair cancelled · was Sat 28 Nov · taken out of School, your calendar and 2 tasks". */
    fun cancelledSummary(b: MailFind, a: MailFind, removed: Removed, today: Long): String {
        val what = a.what ?: b.what ?: "School date"
        val out = listOfNotNull(
            "School".takeIf { removed.school },
            "your calendar".takeIf { removed.calendar },
            when (removed.tasks) { 0 -> null; 1 -> "a task"; else -> "${removed.tasks} tasks" },
        )
        val tail = if (out.isEmpty()) "nothing was left to take out" else "taken out of " + and(out)
        return "From the school: $what cancelled · was ${RenewalRules.dayWord(a.renewDay, today)} · $tail"
    }

    /**
     * "From the school: School fair moved to Sun 29 Nov, 12:00–16:00 (was Sat 28 Nov) · in School, reminders Thu and Sat,
     * tasks Sat and Sun" (the calendar's part is added once the entry is made, as for any school date).
     */
    fun movedSummary(b: MailFind, a: MailFind, p: SchoolAutoPlan, nowMs: Long, cal: LocalCalendar): String {
        val (what, whenText, made) = SchoolAutoRules.parts(b, p, nowMs, cal)
        val was = RenewalRules.dayWord(a.renewDay, cal.epochDayOf(nowMs))
        return "From the school: $what moved to $whenText (was $was)" + if (made.isEmpty()) "" else " · $made"
    }
}
