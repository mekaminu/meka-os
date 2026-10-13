package os.meka.core.domain

import os.meka.core.sync.Replica

/**
 * "Prevented misses", a north-star metric (ADR-013): items with a due time that were completed after a MEKA reminder
 * and before their due time. Non-AI and counted, never inferred from text: the activity log's reminder entries carry
 * what they were about ([ActivityFields.ABOUT], written from the notice's key since 2026-10-10), and the item's own
 * stored fields say when it was done.
 *
 * What counts, each item once in the week it was done:
 * - a **task** with a due time, ticked off after a task reminder (Remind me) reached you and no later than its due time;
 * - a **renewal** with a cancel-by date, renewed, paid, cancelled or closed after its "Cancel or keep?" heads-up and by
 *   the end of that cancel-by day (the day in the reminder, so a renewal that has since moved on still counts).
 *
 * Event reminders and other heads-ups are about things that happen rather than things you finish, so they don't count.
 * When counting began is the first reminder logged with what it was about; weeks before that say "Counted from …".
 * Nothing new is stored beyond that one field on the reminder's entry.
 */
object PreventedMissRules {
    const val KEY = "prevented"
    const val LABEL = "Prevented misses"
    const val PENDING_LINE = "Counted from Navilife's next reminder about a task or renewal"

    /** A reminder's subject from its notice key: `task:<id>` or `renewal:<id>:<cancel-by day>`; null for other notices. */
    fun aboutOf(noticeKey: String): String? = when {
        noticeKey.startsWith("task:") && ":remind:" in noticeKey ->
            noticeKey.removePrefix("task:").substringBeforeLast(":remind:").takeIf { it.isNotEmpty() }?.let { "task:$it" }
        noticeKey.startsWith("renewal:") && ":cancel:" in noticeKey -> {
            val id = noticeKey.removePrefix("renewal:").substringBeforeLast(":cancel:")
            val day = noticeKey.substringAfterLast(":cancel:").toLongOrNull()
            if (id.isEmpty() || day == null) null else "renewal:$id:$day"
        }
        else -> null
    }

    /** A reminder that reached you: what it was about ([aboutOf]) and when. */
    data class Reminder(val about: String, val atMs: Long)

    /** Something finished: a task (`task:<id>`) or a renewal (`renewal:<id>`), when, and its due time (tasks only). */
    data class Finished(val ref: String, val doneAtMs: Long, val dueAtMs: Long? = null)

    /** The week's prevented misses, by kind. */
    data class Week(val tasks: Int, val renewals: Int) {
        val total: Int get() = tasks + renewals
    }

    /**
     * Counts the items finished in [startMs, endMs) after a reminder about them and in time. [cal] turns a renewal's
     * cancel-by day into its deadline (the end of that day).
     */
    fun count(reminders: List<Reminder>, finished: List<Finished>, startMs: Long, endMs: Long, cal: LocalCalendar): Week {
        val byRef = reminders.groupBy { refOf(it.about) }
        var tasks = 0
        var renewals = 0
        for (f in finished.distinctBy { it.ref }) {
            if (f.doneAtMs < startMs || f.doneAtMs >= endMs) continue
            val rs = byRef[f.ref] ?: continue
            if (f.ref.startsWith("task:")) {
                val due = f.dueAtMs ?: continue
                if (f.doneAtMs <= due && rs.any { it.atMs <= f.doneAtMs }) tasks++
            } else if (f.ref.startsWith("renewal:")) {
                val inTime = rs.any { r ->
                    val day = r.about.substringAfterLast(':').toLongOrNull() ?: return@any false
                    r.atMs <= f.doneAtMs && f.doneAtMs < cal.toEpochMs(day + 1, 0)
                }
                if (inTime) renewals++
            }
        }
        return Week(tasks, renewals)
    }

    /** `task:<id>` stays; `renewal:<id>:<day>` becomes `renewal:<id>`. */
    internal fun refOf(about: String): String =
        if (about.startsWith("renewal:")) about.substringBeforeLast(':') else about

    /**
     * The review's metric for the week starting [weekStart]; [since] is the local day counting began (null: no reminder
     * logged with its subject yet). "3" with "2 tasks done before they were due · 1 renewal sorted by its cancel-by date".
     */
    fun metric(weekStart: Long, since: Long?, week: Week): NorthStarMetric {
        if (since == null) return NorthStarMetric(KEY, LABEL, null, PENDING_LINE)
        if (since > weekStart + 6) return NorthStarMetric(KEY, LABEL, null, "Counted from ${CivilDate.shortLabel(since)}")
        val parts = listOfNotNull(
            week.tasks.takeIf { it > 0 }?.let { ShutdownRules.count(it, "task") + if (it == 1) " done before it was due" else " done before they were due" },
            week.renewals.takeIf { it > 0 }?.let { (if (it == 1) "1 renewal" else "$it renewals") + " sorted by the cancel-by date" },
        ).ifEmpty { listOf("Nothing a reminder caught in time this week") }
        val counting = if (since > weekStart) listOf("counting since ${CivilDate.shortLabel(since)}") else emptyList()
        return NorthStarMetric(KEY, LABEL, week.total.toString(), (parts + counting).joinToString(" · "))
    }
}

/** Reads the reminders and finished renewals from the replica; tasks come from the caller (as in the review). */
class PreventedMisses(private val replica: Replica, private val calendar: LocalCalendar = LocalCalendar.UTC) {
    fun reminders(): List<PreventedMissRules.Reminder> = replica.entities(EntityTypes.AGENT_ACTION).mapNotNull { s ->
        if (s[ActivityFields.KIND].textOrNull != ActivityKind.REMINDED.name) return@mapNotNull null
        val about = s[ActivityFields.ABOUT].textOrNull ?: return@mapNotNull null
        val at = s[ActivityFields.AT].longOrNull ?: return@mapNotNull null
        PreventedMissRules.Reminder(about, at)
    }

    /** The local day the first reminder with its subject was logged; null before any. */
    fun since(reminders: List<PreventedMissRules.Reminder> = reminders()): Long? =
        reminders.minOfOrNull { it.atMs }?.let(calendar::epochDayOf)

    private fun finished(tasks: List<Task>): List<PreventedMissRules.Finished> {
        val done = tasks.filter { it.isDone && it.completedAtMs != null }
            .map { PreventedMissRules.Finished("task:${it.id}", it.completedAtMs!!, it.dueAtMs) }
        val renewals = replica.entities(EntityTypes.OBLIGATION).filter { !it.deleted }.mapNotNull { s ->
            val at = listOfNotNull(s[ObligationFields.LAST_DONE_AT].longOrNull, s[ActionableFields.COMPLETED_AT].longOrNull).maxOrNull()
                ?: return@mapNotNull null
            PreventedMissRules.Finished("renewal:${s.ref.entityId}", at)
        }
        return done + renewals
    }

    fun metric(weekStart: Long, tasks: List<Task>): NorthStarMetric {
        val rs = reminders()
        val week = PreventedMissRules.count(
            rs, finished(tasks), calendar.toEpochMs(weekStart, 0), calendar.toEpochMs(weekStart + 7, 0), calendar,
        )
        return PreventedMissRules.metric(weekStart, since(rs), week)
    }
}
