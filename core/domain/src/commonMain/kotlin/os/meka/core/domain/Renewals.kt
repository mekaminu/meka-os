package os.meka.core.domain

import os.meka.core.sync.EntitySnapshot
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Renewals and bills radar, manual entry (build plan M1). Each item is an `obligation` entity (ADR-008): MOT, car tax,
 * insurance, boiler service, subscriptions, bills, licences and passports, warranties.
 *
 * An obligation has a due day, an optional repeat (the task [Recurrence] rules: every month, every 3 months, every
 * year), an optional cost per time, a lead time (how many days before the due day MEKA starts showing it, defaulting
 * by kind) and an optional cancel-by day (the last day to cancel or switch before it renews). Dates are days, stored
 * as 09:00 local on their day, like chase dates.
 *
 * "Renewed" / "Paid" rolls a repeating one forward to its next due day (the cancel-by day moves with it) and closes a
 * one-off. Two devices doing it offline compute the same next day, so they converge without a conflict. No AI and no
 * money moves here: MEKA only says what is coming up and what it costs.
 */

/** How a renewal is repeated (the presets offered); stored as a [Recurrence] anchored on the due day. */
enum class RenewalRepeat { NONE, MONTHLY, QUARTERLY, YEARLY }

/** Where a renewal stands today. */
enum class RenewalState {
    /** The due day has passed. */
    OVERDUE,
    /** The cancel-by day is within a week (and not passed): cancel, switch or keep. */
    CANCEL_BY,
    /** Within its lead time. */
    SOON,
    /** Not yet. */
    LATER,
}

data class RenewalItem(
    val id: String,
    val title: String,
    val kind: ObligationKind,
    val subject: String?,
    val notes: String?,
    /** Local epoch day it is due. */
    val dueDay: Long,
    /** Local epoch day to cancel by, or null. */
    val cancelByDay: Long?,
    val leadDays: Int,
    /** Cost per time in pence, or null when not known. */
    val costPence: Long?,
    /** The preset it repeats on; null when the rule is one this version doesn't offer (kept as is). */
    val repeats: RenewalRepeat?,
    /** "Every year", "Doesn't repeat". */
    val repeatLabel: String,
    val state: RenewalState,
    /** "renews Thu 12 Nov · cancel by Sat 7 Nov · £412 a year". */
    val meta: String,
    /** "Renewed", "Paid", "Serviced". */
    val doneLabel: String,
    /** "Cancelled it" (subscriptions, insurance) or "Stop tracking". */
    val stopLabel: String,
    val hasConflict: Boolean,
) {
    /** Shown in Needs you and counted in its badge. */
    val needsAttention: Boolean get() = state != RenewalState.LATER
}

/** Everything the Renewals list shows, computed for one local day. */
data class RenewalsView(
    /** Overdue, then cancel-by, then within lead time; each by date. */
    val attention: List<RenewalItem>,
    /** Not yet showing, due within about three months; by date. */
    val upcoming: List<RenewalItem>,
    /** Further away; by date. */
    val later: List<RenewalItem>,
    /** Repeating costs normalised to a month and a year, in pence. */
    val monthlyPence: Long,
    val yearlyPence: Long,
) {
    val all: List<RenewalItem> get() = attention + upcoming + later
    val count: Int get() = attention.size + upcoming.size + later.size
    val dueCount: Int get() = attention.size
    private val cancelCount: Int get() = attention.count { it.state == RenewalState.CANCEL_BY }
    private val duesCount: Int get() = attention.size - cancelCount

    /** "2 renewals due · 1 to cancel or keep"; null when nothing needs you. */
    val dueLine: String? get() = listOfNotNull(
        duesCount.takeIf { it > 0 }?.let { "$it ${if (it == 1) "renewal" else "renewals"} due" },
        cancelCount.takeIf { it > 0 }?.let { "$it to cancel or keep" },
    ).joinToString(" · ").ifEmpty { null }

    /** "£54.97 a month · £659.64 a year in repeating costs"; null when no repeating item has a cost. */
    val costLine: String? get() = if (yearlyPence <= 0) null
    else "${RenewalRules.formatPence(monthlyPence)} a month · ${RenewalRules.formatPence(yearlyPence)} a year in repeating costs"

    companion object {
        val EMPTY = RenewalsView(emptyList(), emptyList(), emptyList(), 0, 0)
    }
}

object RenewalRules {
    /** Kinds in the order the pickers offer them. */
    val KINDS: List<ObligationKind> = enumValues<ObligationKind>().toList()
    val REPEATS: List<RenewalRepeat> = enumValues<RenewalRepeat>().toList()

    /** Index access for Swift, where a list of Kotlin enums doesn't arrive as Swift enums. */
    val kindCount: Int get() = KINDS.size
    fun kindAt(index: Int): ObligationKind = KINDS[index]
    val repeatCount: Int get() = REPEATS.size
    fun repeatAt(index: Int): RenewalRepeat = REPEATS[index]

    /** Lead-time presets ("show it … before"). */
    val LEAD_CHOICES = listOf(
        DayChoice("3 days before", 3), DayChoice("A week before", 7), DayChoice("2 weeks before", 14),
        DayChoice("4 weeks before", 28), DayChoice("2 months before", 61),
    )

    /** Cancel-by presets, in days before the due day. */
    val CANCEL_CHOICES = listOf(
        DayChoice("No cancel-by date", null), DayChoice("The day before", 1), DayChoice("3 days before", 3),
        DayChoice("A week before", 7), DayChoice("2 weeks before", 14), DayChoice("30 days before", 30),
    )

    /** A cancel-by day is lit this many days ahead of it. */
    const val CANCEL_NOTICE_DAYS = 7
    /** Items due within this many days (and not yet in their lead time) are "Coming up"; the rest are "Later". */
    const val UPCOMING_DAYS = 92
    /** Suggested due day for a new item, days from today. */
    const val DEFAULT_DUE_IN_DAYS = 30

    fun kindLabel(k: ObligationKind): String = when (k) {
        ObligationKind.MOT -> "MOT"
        ObligationKind.CAR_TAX -> "Car tax"
        ObligationKind.INSURANCE -> "Insurance"
        ObligationKind.BOILER -> "Boiler service"
        ObligationKind.SUBSCRIPTION -> "Subscription"
        ObligationKind.BILL -> "Bill"
        ObligationKind.LICENCE -> "Licence or passport"
        ObligationKind.WARRANTY -> "Warranty"
        ObligationKind.OTHER -> "Other"
    }

    /** Days ahead MEKA shows a new item of this kind: long for things that take booking or paperwork. */
    fun defaultLeadDays(k: ObligationKind): Int = when (k) {
        ObligationKind.MOT, ObligationKind.BOILER -> 28
        ObligationKind.INSURANCE -> 21
        ObligationKind.CAR_TAX, ObligationKind.WARRANTY -> 14
        ObligationKind.SUBSCRIPTION, ObligationKind.BILL -> 3
        ObligationKind.LICENCE -> 61
        ObligationKind.OTHER -> 7
    }

    /** The repeat offered first when adding one of this kind. */
    fun defaultRepeat(k: ObligationKind): RenewalRepeat = when (k) {
        ObligationKind.MOT, ObligationKind.CAR_TAX, ObligationKind.INSURANCE, ObligationKind.BOILER -> RenewalRepeat.YEARLY
        ObligationKind.SUBSCRIPTION, ObligationKind.BILL -> RenewalRepeat.MONTHLY
        ObligationKind.LICENCE, ObligationKind.WARRANTY, ObligationKind.OTHER -> RenewalRepeat.NONE
    }

    /** "Due Thu 5 Nov" for the date button. */
    fun dueButton(day: Long, today: Long): String = "Due " + dayWord(day, today)

    /** The verb before the due day: "renews Thu 12 Nov", "expires 3 Mar 2027". */
    fun verb(k: ObligationKind): String = when (k) {
        ObligationKind.INSURANCE, ObligationKind.SUBSCRIPTION -> "renews"
        ObligationKind.LICENCE -> "expires"
        ObligationKind.WARRANTY -> "ends"
        else -> "due"
    }

    fun doneLabel(k: ObligationKind): String = when (k) {
        ObligationKind.MOT -> "MOT done"
        ObligationKind.CAR_TAX, ObligationKind.BILL -> "Paid"
        ObligationKind.BOILER -> "Serviced"
        ObligationKind.INSURANCE, ObligationKind.SUBSCRIPTION, ObligationKind.LICENCE -> "Renewed"
        ObligationKind.WARRANTY, ObligationKind.OTHER -> "Done"
    }

    fun stopLabel(k: ObligationKind): String =
        if (k == ObligationKind.SUBSCRIPTION || k == ObligationKind.INSURANCE) "Cancelled it" else "Stop tracking"

    fun repeatLabel(r: RenewalRepeat): String = when (r) {
        RenewalRepeat.NONE -> "Doesn't repeat"
        RenewalRepeat.MONTHLY -> "Every month"
        RenewalRepeat.QUARTERLY -> "Every 3 months"
        RenewalRepeat.YEARLY -> "Every year"
    }

    /** The rule for [r] anchored on [dueDay]; null for [RenewalRepeat.NONE]. */
    fun ruleFor(r: RenewalRepeat, dueDay: Long): Recurrence? {
        val d = CivilDate.fromEpochDay(dueDay)
        return when (r) {
            RenewalRepeat.NONE -> null
            RenewalRepeat.MONTHLY -> Recurrence.MonthlyOnDay(1, d.day)
            RenewalRepeat.QUARTERLY -> Recurrence.MonthlyOnDay(3, d.day)
            RenewalRepeat.YEARLY -> Recurrence.Yearly(1, d.month, d.day)
        }
    }

    /** The preset a rule is, or null for any other rule. */
    fun repeatOf(rule: Recurrence?): RenewalRepeat? = when {
        rule == null -> RenewalRepeat.NONE
        rule is Recurrence.MonthlyOnDay && rule.interval == 1 -> RenewalRepeat.MONTHLY
        rule is Recurrence.MonthlyOnDay && rule.interval == 3 -> RenewalRepeat.QUARTERLY
        rule is Recurrence.Yearly && rule.interval == 1 -> RenewalRepeat.YEARLY
        else -> null
    }

    /** The same kind of rule moved onto a new due day (monthly on the 12th becomes monthly on the 20th). */
    fun reanchor(rule: Recurrence, dueDay: Long): Recurrence {
        val d = CivilDate.fromEpochDay(dueDay)
        return when (rule) {
            is Recurrence.MonthlyOnDay -> Recurrence.MonthlyOnDay(rule.interval, d.day)
            is Recurrence.Yearly -> Recurrence.Yearly(rule.interval, d.month, d.day)
            is Recurrence.Weekly -> if (rule.days.size == 1) Recurrence.Weekly(rule.interval, setOf(CivilDate.isoDayOfWeek(dueDay))) else rule
            else -> rule
        }
    }

    /** How many times a year a rule comes round. */
    fun timesPerYear(rule: Recurrence): Double = when (rule) {
        is Recurrence.Daily -> 365.0 / rule.interval
        is Recurrence.Weekly -> 52.0 * rule.days.size / rule.interval
        is Recurrence.MonthlyOnDay -> 12.0 / rule.interval
        is Recurrence.MonthlyOnWeekday -> 12.0 / rule.interval
        is Recurrence.Yearly -> 1.0 / rule.interval
    }

    /** "£9.99", "£412", "£1,240.50". */
    fun formatPence(pence: Long): String {
        val sign = if (pence < 0) "-" else ""
        val p = kotlin.math.abs(pence)
        val pounds = (p / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        val rest = p % 100
        return if (rest == 0L) "$sign£$pounds" else "$sign£$pounds.${rest.toString().padStart(2, '0')}"
    }

    /** "£412 a year", "£9.99 a month", "£30 every 3 months", "£45". */
    fun costLabel(pence: Long, rule: Recurrence?): String {
        val amount = formatPence(pence)
        return when {
            rule == null -> amount
            rule is Recurrence.MonthlyOnDay && rule.interval == 1 -> "$amount a month"
            rule is Recurrence.Yearly && rule.interval == 1 -> "$amount a year"
            rule is Recurrence.MonthlyOnDay -> "$amount every ${rule.interval} months"
            else -> "$amount each time"
        }
    }

    private val COST = Regex("""^(\d{1,7})(?:\.(\d{1,2}))?$""")

    /**
     * Reads a typed cost: "9.99", "£412", "1,240.50", "£ 12". Blank is null (not known). Anything else is a
     * [ValidationException] the apps show as typed.
     */
    fun parseCost(text: String?): Long? {
        val t = text?.trim()?.removePrefix("£")?.replace(",", "")?.replace(" ", "") ?: return null
        if (t.isEmpty()) return null
        val m = COST.matchEntire(t) ?: throw ValidationException("Type the cost in pounds, like 9.99")
        val pounds = m.groupValues[1].toLong()
        val pence = m.groupValues[2].let { if (it.isEmpty()) 0L else it.padEnd(2, '0').toLong() }
        return pounds * 100 + pence
    }

    /** What's wrong with a typed cost, or null when [parseCost] can read it. Never throws (the Mac calls it directly). */
    fun costError(text: String?): String? = try { parseCost(text); null } catch (e: ValidationException) { e.message }

    /** "today", "tomorrow", "Thu 12 Nov", or "12 Mar 2027" when it is most of a year away (or more). */
    fun dayWord(day: Long, today: Long): String = when {
        day == today -> "today"
        day == today + 1 -> "tomorrow"
        kotlin.math.abs(day - today) < 300 -> CivilDate.shortLabel(day)
        else -> CivilDate.fromEpochDay(day).let { "${it.day} ${Recurrence.MONTH_SHORT[it.month - 1]} ${it.year}" }
    }

    fun state(dueDay: Long, cancelByDay: Long?, leadDays: Int, today: Long): RenewalState = when {
        dueDay < today -> RenewalState.OVERDUE
        cancelByDay != null && cancelByDay >= today && cancelByDay - CANCEL_NOTICE_DAYS <= today -> RenewalState.CANCEL_BY
        dueDay - leadDays <= today -> RenewalState.SOON
        else -> RenewalState.LATER
    }

    fun meta(kind: ObligationKind, dueDay: Long, cancelByDay: Long?, costPence: Long?, rule: Recurrence?, today: Long): String {
        val parts = mutableListOf<String>()
        parts += if (dueDay < today) "was due ${dayWord(dueDay, today)}" else "${verb(kind)} ${dayWord(dueDay, today)}"
        if (cancelByDay != null && cancelByDay >= today && dueDay >= today) parts += "cancel by ${dayWord(cancelByDay, today)}"
        costPence?.let { parts += costLabel(it, rule) }
        return parts.joinToString(" · ")
    }
}

/**
 * Renewals commands and the [view] projection over a [Replica]. Every write is an op, so it is offline-first and synced.
 */
class Renewals(
    private val replica: Replica,
    private val ids: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    private fun today(): Long = calendar.epochDayOf(nowMs())
    private fun dayMs(day: Long): Long = calendar.toEpochMs(day, Lists.DATE_MINUTE)

    /**
     * Adds a renewal or bill due on [dueDay] (local epoch day). [cost] is typed text ("9.99"); [cancelByDaysBefore]
     * sets a cancel-by day that many days before the due day. The lead time defaults by kind.
     */
    fun add(
        title: String,
        kind: ObligationKind,
        dueDay: Long,
        repeat: RenewalRepeat = RenewalRepeat.NONE,
        cost: String? = null,
        cancelByDaysBefore: Int? = null,
        subject: String? = null,
    ): String {
        val t = cleanTitle(title)
        checkDay(dueDay)
        checkCancel(cancelByDaysBefore)
        val pence = RenewalRules.parseCost(cost)
        val id = ids()
        val fields = linkedMapOf<String, FieldValue>(
            ActionableFields.TITLE to t.fv(),
            ObligationFields.KIND to kind.name.fv(),
            ActionableFields.DUE_AT to dayMs(dueDay).fv(),
            ObligationFields.LEAD_DAYS to RenewalRules.defaultLeadDays(kind).fv(),
            ActionableFields.LIFECYCLE to Lifecycle.ACTIVE.name.fv(),
            ActionableFields.CREATED_AT to nowMs().fv(),
            ActionableFields.VISIBILITY to Visibility.PRIVATE.name.fv(),
            ActionableFields.PROVENANCE_SOURCE to "user".fv(),
            ActionableFields.PROVENANCE_TRUST to Trust.TRUSTED_USER.name.fv(),
        )
        RenewalRules.ruleFor(repeat, dueDay)?.let { fields[ObligationFields.RECURRENCE] = it.encode().fv() }
        if (pence != null) {
            fields[ObligationFields.COST_MINOR] = pence.fv()
            fields[ObligationFields.CURRENCY] = CURRENCY.fv()
        }
        cancelByDaysBefore?.let { fields[ObligationFields.CANCEL_BY_AT] = dayMs(dueDay - it).fv() }
        subject?.trim()?.takeIf { it.isNotEmpty() }?.let { fields[ObligationFields.SUBJECT_LABEL] = it.take(Lists.MAX_TITLE).fv() }
        replica.commitLocal(EntityTypes.OBLIGATION, id, fields)
        return id
    }

    /**
     * "Renewed" / "Paid" / "Serviced": a repeating one moves to its next due day (the cancel-by day keeps its distance);
     * a one-off is done and leaves the list.
     */
    fun done(id: String) {
        val s = live(id)
        val due = dueDayOf(s) ?: throw ValidationException("Set a due date first")
        val rule = ruleOf(s)
        if (rule == null) {
            replica.commitLocal(
                EntityTypes.OBLIGATION, id,
                mapOf(
                    ActionableFields.LIFECYCLE to Lifecycle.DONE.name.fv(),
                    ActionableFields.COMPLETED_AT to nowMs().fv(),
                    ObligationFields.LAST_DONE_AT to nowMs().fv(),
                ),
            )
            return
        }
        val next = rule.next(due)
        val changes = linkedMapOf<String, FieldValue>(
            ActionableFields.DUE_AT to dayMs(next).fv(),
            ObligationFields.LAST_DONE_AT to nowMs().fv(),
        )
        cancelByDayOf(s)?.let { changes[ObligationFields.CANCEL_BY_AT] = dayMs(it + (next - due)).fv() }
        replica.commitLocal(EntityTypes.OBLIGATION, id, changes)
    }

    /** Moves the due day. A repeat follows it (monthly on the 12th becomes the 20th); so does the cancel-by day. */
    fun setDue(id: String, dueDay: Long) {
        val s = live(id)
        checkDay(dueDay)
        val old = dueDayOf(s)
        val changes = linkedMapOf<String, FieldValue>(ActionableFields.DUE_AT to dayMs(dueDay).fv())
        ruleOf(s)?.let { changes[ObligationFields.RECURRENCE] = RenewalRules.reanchor(it, dueDay).encode().fv() }
        val cancel = cancelByDayOf(s)
        if (cancel != null && old != null) changes[ObligationFields.CANCEL_BY_AT] = dayMs(cancel + (dueDay - old)).fv()
        replica.commitLocal(EntityTypes.OBLIGATION, id, changes)
    }

    fun setRepeat(id: String, repeat: RenewalRepeat) {
        val s = live(id)
        val due = dueDayOf(s) ?: today()
        replica.commitLocal(
            EntityTypes.OBLIGATION, id,
            mapOf(ObligationFields.RECURRENCE to (RenewalRules.ruleFor(repeat, due)?.encode()?.fv() ?: FieldValue.Null)),
        )
    }

    /** Sets the cost from typed text; blank clears it. */
    fun setCost(id: String, cost: String?) {
        live(id)
        val pence = RenewalRules.parseCost(cost)
        replica.commitLocal(
            EntityTypes.OBLIGATION, id,
            mapOf(
                ObligationFields.COST_MINOR to (pence?.fv() ?: FieldValue.Null),
                ObligationFields.CURRENCY to (if (pence == null) FieldValue.Null else CURRENCY.fv()),
            ),
        )
    }

    fun setLead(id: String, days: Int) {
        live(id)
        if (days !in 0..MAX_LEAD_DAYS) throw ValidationException("Pick up to a year ahead")
        replica.commitLocal(EntityTypes.OBLIGATION, id, mapOf(ObligationFields.LEAD_DAYS to days.fv()))
    }

    /** Sets the cancel-by day [daysBefore] the due day, or clears it. */
    fun setCancelBy(id: String, daysBefore: Int?) {
        val s = live(id)
        checkCancel(daysBefore)
        val due = dueDayOf(s) ?: throw ValidationException("Set a due date first")
        replica.commitLocal(
            EntityTypes.OBLIGATION, id,
            mapOf(ObligationFields.CANCEL_BY_AT to (daysBefore?.let { dayMs(due - it).fv() } ?: FieldValue.Null)),
        )
    }

    fun setKind(id: String, kind: ObligationKind) {
        live(id)
        replica.commitLocal(EntityTypes.OBLIGATION, id, mapOf(ObligationFields.KIND to kind.name.fv()))
    }

    fun edit(id: String, title: String? = null, subject: String? = null, notes: String? = null) {
        live(id)
        val changes = linkedMapOf<String, FieldValue>()
        title?.let { changes[ActionableFields.TITLE] = cleanTitle(it).fv() }
        subject?.let { v -> changes[ObligationFields.SUBJECT_LABEL] = v.trim().takeIf { it.isNotEmpty() }?.take(Lists.MAX_TITLE)?.fv() ?: FieldValue.Null }
        notes?.let { n -> changes[ActionableFields.NOTES] = n.takeIf { it.isNotBlank() }?.fv() ?: FieldValue.Null }
        if (changes.isNotEmpty()) replica.commitLocal(EntityTypes.OBLIGATION, id, changes)
    }

    /** "Cancelled it" / "Stop tracking": kept as cancelled, no longer on the radar. */
    fun stop(id: String) {
        live(id)
        replica.commitLocal(
            EntityTypes.OBLIGATION, id,
            mapOf(ActionableFields.LIFECYCLE to Lifecycle.CANCELLED.name.fv(), ActionableFields.COMPLETED_AT to nowMs().fv()),
        )
    }

    fun delete(id: String) {
        live(id)
        replica.commitLocal(EntityTypes.OBLIGATION, id, mapOf(ActionableFields.DELETED to true.fv()))
    }

    // ---- Reads ----

    fun items(): List<RenewalItem> {
        val today = today()
        return replica.entities(EntityTypes.OBLIGATION)
            .filter { s -> s[ActionableFields.LIFECYCLE].textOrNull?.let { enumOrNull<Lifecycle>(it) }?.isTerminal != true }
            .mapNotNull { s ->
                val due = dueDayOf(s) ?: return@mapNotNull null
                val kind = s[ObligationFields.KIND].textOrNull?.let { enumOrNull<ObligationKind>(it) } ?: ObligationKind.OTHER
                val cancel = cancelByDayOf(s)
                val lead = (s[ObligationFields.LEAD_DAYS].longOrNull?.toInt() ?: RenewalRules.defaultLeadDays(kind)).coerceIn(0, MAX_LEAD_DAYS)
                val rawRule = s[ObligationFields.RECURRENCE].textOrNull
                val rule = ruleOf(s)
                val cost = s[ObligationFields.COST_MINOR].longOrNull
                val repeat = if (rawRule != null && rule == null) null else RenewalRules.repeatOf(rule)
                RenewalItem(
                    id = s.ref.entityId,
                    title = s[ActionableFields.TITLE].textOrNull ?: "",
                    kind = kind,
                    subject = s[ObligationFields.SUBJECT_LABEL].textOrNull,
                    notes = s[ActionableFields.NOTES].textOrNull,
                    dueDay = due,
                    cancelByDay = cancel,
                    leadDays = lead,
                    costPence = cost,
                    repeats = repeat,
                    repeatLabel = repeat?.let(RenewalRules::repeatLabel) ?: rule?.describe() ?: "Repeats (set on a newer version)",
                    state = RenewalRules.state(due, cancel, lead, today),
                    meta = RenewalRules.meta(kind, due, cancel, cost, rule, today),
                    doneLabel = RenewalRules.doneLabel(kind),
                    stopLabel = RenewalRules.stopLabel(kind),
                    hasConflict = replica.conflictsFor(EntityTypes.OBLIGATION, s.ref.entityId).isNotEmpty(),
                )
            }
    }

    fun view(): RenewalsView {
        val today = today()
        val all = items()
        val byDate = compareBy<RenewalItem> { it.dueDay }.thenBy { it.title }.thenBy { it.id }
        val attention = all.filter { it.needsAttention }
            .sortedWith(compareBy<RenewalItem> { it.state.ordinal }.thenBy { if (it.state == RenewalState.CANCEL_BY) it.cancelByDay else it.dueDay }.then(byDate))
        val rest = all.filter { !it.needsAttention }.sortedWith(byDate)
        val yearly = replica.entities(EntityTypes.OBLIGATION)
            .filter { s -> s[ActionableFields.LIFECYCLE].textOrNull?.let { enumOrNull<Lifecycle>(it) }?.isTerminal != true }
            .sumOf { s ->
                val cost = s[ObligationFields.COST_MINOR].longOrNull ?: return@sumOf 0L
                val rule = ruleOf(s) ?: return@sumOf 0L
                kotlin.math.round(cost * RenewalRules.timesPerYear(rule)).toLong()
            }
        return RenewalsView(
            attention = attention,
            upcoming = rest.filter { it.dueDay <= today + RenewalRules.UPCOMING_DAYS },
            later = rest.filter { it.dueDay > today + RenewalRules.UPCOMING_DAYS },
            monthlyPence = kotlin.math.round(yearly / 12.0).toLong(),
            yearlyPence = yearly,
        )
    }

    // ---- Helpers ----

    private fun dueDayOf(s: EntitySnapshot): Long? = s[ActionableFields.DUE_AT].longOrNull?.let(calendar::epochDayOf)
    private fun cancelByDayOf(s: EntitySnapshot): Long? = s[ObligationFields.CANCEL_BY_AT].longOrNull?.let(calendar::epochDayOf)
    private fun ruleOf(s: EntitySnapshot): Recurrence? = Recurrence.decode(s[ObligationFields.RECURRENCE].textOrNull)

    private fun live(id: String): EntitySnapshot {
        val s = replica.entity(EntityTypes.OBLIGATION, id)
        if (s == null || s.deleted) throw ValidationException("That renewal isn't on the radar")
        return s
    }

    private fun cleanTitle(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) throw ValidationException("Say what it is")
        if (t.length > Lists.MAX_TITLE) throw ValidationException("That's too long for a title")
        return t
    }

    private fun checkDay(day: Long) {
        if (kotlin.math.abs(day - today()) > Lists.MAX_DAYS) throw ValidationException("Pick a date within ten years")
    }

    private fun checkCancel(daysBefore: Int?) {
        if (daysBefore != null && daysBefore !in 0..MAX_LEAD_DAYS) throw ValidationException("Pick a cancel-by date within a year of it")
    }

    companion object {
        const val CURRENCY = "GBP"
        const val MAX_LEAD_DAYS = 366
    }
}
