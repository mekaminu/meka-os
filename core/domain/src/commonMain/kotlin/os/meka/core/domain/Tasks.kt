package os.meka.core.domain

import os.meka.core.sync.Conflict
import os.meka.core.sync.EntitySnapshot
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

data class ChecklistItem(val id: String, val taskId: String, val text: String, val checked: Boolean, val position: Long)

/**
 * Typed read model of a Task. Built from an [EntitySnapshot]; never written directly.
 * Exported to Swift as `MekaTask` so it never shadows Swift Concurrency's `Task`.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName("MekaTask", exact = true)
data class Task(
    val id: String,
    val title: String,
    val notes: String?,
    val lifecycle: Lifecycle,
    val dueAtMs: Long?,
    val scheduledAtMs: Long?,
    val estimateMinutes: Int?,
    val priority: Int,
    val goalId: String?,
    val somedayKind: SomedayKind?,
    val createdAtMs: Long,
    val completedAtMs: Long?,
    val hasConflict: Boolean,
    val checklist: List<ChecklistItem> = emptyList(),
    /** The raw repeat rule as stored; kept even when this version can't read it ([recurrence] is then null). */
    val recurrenceRule: String? = null,
    val seriesId: String? = null,
    /** Local epoch day of this occurrence in its series. */
    val occurrenceDay: Long? = null,
    /** Local epoch day a snoozed occurrence waits for. */
    val deferredToDay: Long? = null,
    /** The calendar event a prep task is for (calendar actions); null for other tasks. */
    val eventId: String? = null,
    /** Remind me (task detail): when to remind, epoch ms; null for none (see [TaskReminderRules]). */
    val remindAtMs: Long? = null,
    /** The day the three-carry prompt was answered ([RollOverRules]); null while it may still ask. */
    val carryAskedDay: Long? = null,
    /** Its place within its day on the Week board ([WeekBoardRules]); null until he orders that day. */
    val boardRank: Long? = null,
    /** The Monday of the week it is parked in on the Board's week lane ([WeekBoardRules.parked]); null otherwise. */
    val boardWeek: Long? = null,
) {
    val isDone: Boolean get() = lifecycle == Lifecycle.DONE

    val recurrence: Recurrence? get() = Recurrence.decode(recurrenceRule)
    val isRepeating: Boolean get() = recurrenceRule != null

    /** "Every weekday"; "Repeats" for a rule written by a newer version. */
    val repeatLabel: String? get() = recurrenceRule?.let { recurrence?.describe() ?: "Repeats" }

    /** The local day this item shows from: a snoozed day, else its occurrence day; null for one-off tasks. */
    val showsFromDay: Long? get() = deferredToDay ?: occurrenceDay

    /**
     * The repeat line under a task: "Every weekday", plus "· since Mon 5 Oct" when an earlier day's occurrence is
     * still open; "Snoozed" is not shown because snoozed items are out of sight until their day.
     */
    fun repeatMeta(todayEpochDay: Long): String? {
        val label = repeatLabel ?: return null
        val day = showsFromDay
        return if (day != null && day < todayEpochDay) "$label · since ${CivilDate.shortLabel(day)}" else label
    }

    /** A future occurrence (or a snoozed one) stays out of Today and the planner until its day. */
    fun waitsForItsDay(todayEpochDay: Long): Boolean = showsFromDay?.let { it > todayEpochDay } ?: false

    companion object {
        fun from(s: EntitySnapshot, hasConflict: Boolean, checklist: List<ChecklistItem>): Task {
            val lifecycle = s[ActionableFields.LIFECYCLE].textOrNull?.let { enumOrNull<Lifecycle>(it) } ?: Lifecycle.INBOX
            return Task(
                id = s.ref.entityId,
                title = s[ActionableFields.TITLE].textOrNull ?: "",
                notes = s[ActionableFields.NOTES].textOrNull,
                lifecycle = lifecycle,
                dueAtMs = s[ActionableFields.DUE_AT].longOrNull,
                scheduledAtMs = s[TaskFields.SCHEDULED_AT].longOrNull,
                estimateMinutes = s[TaskFields.ESTIMATE_MINUTES].longOrNull?.toInt(),
                priority = s[ActionableFields.PRIORITY].longOrNull?.toInt() ?: 0,
                goalId = s[ActionableFields.GOAL_ID].textOrNull,
                somedayKind = s[ActionableFields.SOMEDAY_KIND].textOrNull?.let { enumOrNull<SomedayKind>(it) },
                createdAtMs = s[ActionableFields.CREATED_AT].longOrNull ?: 0L,
                // completedAt is only meaningful while DONE: a concurrent reopen/complete resolves to DONE (TerminalWins)
                completedAtMs = if (lifecycle == Lifecycle.DONE) s[ActionableFields.COMPLETED_AT].longOrNull else null,
                hasConflict = hasConflict,
                checklist = checklist,
                recurrenceRule = s[TaskFields.RECURRENCE].textOrNull,
                seriesId = s[TaskFields.SERIES_ID].textOrNull,
                occurrenceDay = s[TaskFields.OCCURRENCE_DAY].longOrNull,
                deferredToDay = s[TaskFields.DEFERRED_TO_DAY].longOrNull,
                eventId = s[TaskFields.EVENT_ID].textOrNull,
                remindAtMs = s[TaskFields.REMIND_AT].longOrNull,
                carryAskedDay = s[TaskFields.CARRY_ASKED_DAY].longOrNull,
                boardRank = s[TaskFields.BOARD_RANK].longOrNull,
                boardWeek = s[TaskFields.BOARD_WEEK].longOrNull,
            )
        }
    }
}

inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }

/** Draft for creating a task. Title is required and trimmed; everything else is optional. */
data class NewTask(
    val title: String,
    val notes: String? = null,
    val lifecycle: Lifecycle = Lifecycle.ACTIVE,
    val dueAtMs: Long? = null,
    val scheduledAtMs: Long? = null,
    val estimateMinutes: Int? = null,
    val priority: Int = 0,
    val goalId: String? = null,
    val somedayKind: SomedayKind? = null,
    val ownerPersonId: String? = null,
)

/** Partial edit. `null` means "leave unchanged"; use [Clear] semantics via the explicit flags. */
data class TaskEdit(
    val title: String? = null,
    val notes: String? = null,
    val dueAtMs: Long? = null,
    val clearDueAt: Boolean = false,
    val scheduledAtMs: Long? = null,
    val clearScheduledAt: Boolean = false,
    val estimateMinutes: Int? = null,
    val priority: Int? = null,
)

/** One row of the Repeat picker. [rule] is null for "Doesn't repeat". */
data class RepeatChoice(val label: String, val rule: String?, val selected: Boolean)

class ValidationException(message: String) : IllegalArgumentException(message)

/**
 * Task commands and queries over a [Replica]. All writes become ops, so they are offline-first,
 * synced, merge-safe and auditable.
 */
class Tasks(
    private val replica: Replica,
    private val ids: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {

    fun create(draft: NewTask): String = createWithId(ids(), draft)

    /**
     * Creates (or, if [id] was deleted or finished, brings back) a task under a chosen id, so two devices making
     * the same thing offline converge on one task. [extra] adds fields such as [TaskFields.EVENT_ID].
     */
    fun createWithId(id: String, draft: NewTask, extra: Map<String, FieldValue> = emptyMap()): String {
        val title = draft.title.trim()
        if (title.isEmpty()) throw ValidationException("A task needs a title")
        if (title.length > 500) throw ValidationException("Title is too long")
        val fields = linkedMapOf<String, FieldValue>(
            ActionableFields.TITLE to title.fv(),
            ActionableFields.LIFECYCLE to draft.lifecycle.name.fv(),
            ActionableFields.CREATED_AT to nowMs().fv(),
            ActionableFields.PRIORITY to draft.priority.fv(),
            ActionableFields.VISIBILITY to Visibility.PRIVATE.name.fv(),
            ActionableFields.PROVENANCE_SOURCE to "user".fv(),
            ActionableFields.PROVENANCE_TRUST to Trust.TRUSTED_USER.name.fv(),
        )
        draft.notes?.let { fields[ActionableFields.NOTES] = it.fv() }
        draft.dueAtMs?.let { fields[ActionableFields.DUE_AT] = it.fv() }
        draft.scheduledAtMs?.let { fields[TaskFields.SCHEDULED_AT] = it.fv() }
        draft.estimateMinutes?.let {
            if (it <= 0 || it > 24 * 60) throw ValidationException("Estimate must be between 1 minute and 24 hours")
            fields[TaskFields.ESTIMATE_MINUTES] = it.fv()
        }
        draft.goalId?.let { fields[ActionableFields.GOAL_ID] = it.fv() }
        draft.ownerPersonId?.let { fields[ActionableFields.OWNER_PERSON_ID] = it.fv() }
        if (draft.lifecycle == Lifecycle.SOMEDAY) {
            fields[ActionableFields.SOMEDAY_KIND] = (draft.somedayKind ?: SomedayKind.IDEA).name.fv()
        }
        fields.putAll(extra)
        replica.entity(EntityTypes.TASK, id)?.let { old ->
            // Coming back: undo a deletion and clear what the old one finished with.
            if (old.deleted) fields[ActionableFields.DELETED] = false.fv()
            if (old[ActionableFields.COMPLETED_AT].longOrNull != null) fields[ActionableFields.COMPLETED_AT] = FieldValue.Null
            if (draft.scheduledAtMs == null && old[TaskFields.SCHEDULED_AT].longOrNull != null) fields[TaskFields.SCHEDULED_AT] = FieldValue.Null
            if (old[TaskFields.DEFERRED_TO_DAY].longOrNull != null) fields[TaskFields.DEFERRED_TO_DAY] = FieldValue.Null
            if (old[TaskFields.REMIND_AT].longOrNull != null) fields[TaskFields.REMIND_AT] = FieldValue.Null
        }
        replica.commitLocal(EntityTypes.TASK, id, fields)
        return id
    }

    fun edit(id: String, edit: TaskEdit) {
        requireExists(id)
        val changes = linkedMapOf<String, FieldValue>()
        edit.title?.let {
            val t = it.trim()
            if (t.isEmpty()) throw ValidationException("A task needs a title")
            changes[ActionableFields.TITLE] = t.fv()
        }
        edit.notes?.let { changes[ActionableFields.NOTES] = it.fv() }
        if (edit.clearDueAt) changes[ActionableFields.DUE_AT] = FieldValue.Null
        else edit.dueAtMs?.let { changes[ActionableFields.DUE_AT] = it.fv() }
        if (edit.clearScheduledAt) changes[TaskFields.SCHEDULED_AT] = FieldValue.Null
        else edit.scheduledAtMs?.let { changes[TaskFields.SCHEDULED_AT] = it.fv() }
        edit.estimateMinutes?.let { changes[TaskFields.ESTIMATE_MINUTES] = it.fv() }
        edit.priority?.let { changes[ActionableFields.PRIORITY] = it.fv() }
        if (changes.isNotEmpty()) replica.commitLocal(EntityTypes.TASK, id, changes)
    }

    /**
     * Sets how long [id] is thought to take (Week board slice 3: one of [TaskEstimateRules.CHOICES] from a card's menu),
     * or clears it with null. Returns the estimate it had before, for Undo.
     */
    fun setEstimate(id: String, minutes: Int?): Int? {
        if (!TaskEstimateRules.valid(minutes)) throw ValidationException("Estimate must be between 1 minute and 24 hours")
        val before = (get(id) ?: throw ValidationException("Task not found")).estimateMinutes
        if (before != minutes) {
            replica.commitLocal(EntityTypes.TASK, id, mapOf(TaskFields.ESTIMATE_MINUTES to (minutes?.fv() ?: FieldValue.Null)))
        }
        return before
    }

    /** Completes a task. Completing an open occurrence of a repeating task also creates the next one. */
    fun complete(id: String) {
        requireExists(id)
        val before = get(id)
        replica.commitLocal(
            EntityTypes.TASK, id,
            mapOf(ActionableFields.LIFECYCLE to Lifecycle.DONE.name.fv(), ActionableFields.COMPLETED_AT to nowMs().fv()),
        )
        if (before != null && !before.lifecycle.isTerminal) spawnNext(before)
    }

    // ---- Repeating tasks ----

    /**
     * Makes [id] repeat by [rule] (or stops it repeating when null). The task becomes the current occurrence: it moves
     * to the rule's first day on or after its own day (its scheduled/due day, else today), keeping its times of day.
     */
    fun setRepeat(id: String, rule: Recurrence?) {
        val t = get(id) ?: throw ValidationException("Task not found")
        if (rule == null) {
            if (t.recurrenceRule != null) replica.commitLocal(EntityTypes.TASK, id, mapOf(TaskFields.RECURRENCE to FieldValue.Null))
            return
        }
        val today = calendar.epochDayOf(nowMs())
        val ownDay = t.occurrenceDay ?: (t.scheduledAtMs ?: t.dueAtMs)?.let(calendar::epochDayOf) ?: today
        val day = rule.firstOnOrAfter(ownDay)
        val changes = linkedMapOf<String, FieldValue>(
            TaskFields.RECURRENCE to rule.encode().fv(),
            TaskFields.SERIES_ID to (t.seriesId ?: id).fv(),
            TaskFields.OCCURRENCE_DAY to day.fv(),
        )
        if (t.deferredToDay != null) changes[TaskFields.DEFERRED_TO_DAY] = FieldValue.Null
        val from = t.showsFromDay ?: ownDay
        t.scheduledAtMs?.let { changes[TaskFields.SCHEDULED_AT] = shifted(it, from, day).fv() }
        t.dueAtMs?.let { changes[ActionableFields.DUE_AT] = shifted(it, from, day).fv() }
        t.remindAtMs?.let { changes[TaskFields.REMIND_AT] = shifted(it, from, day).fv() }
        replica.commitLocal(EntityTypes.TASK, id, changes)
    }

    /** [setRepeat] with a stored rule string (from [repeatChoices]); null stops the repeat. */
    fun setRepeatRule(id: String, rule: String?) {
        if (rule == null) return setRepeat(id, null)
        setRepeat(id, Recurrence.decode(rule) ?: throw ValidationException("That repeat isn't supported"))
    }

    /**
     * The Repeat picker for [id]: "Doesn't repeat", then the presets for the task's day (its occurrence, scheduled or
     * due day, else today). A current rule that isn't a preset (set elsewhere, or by a newer version) is listed too.
     */
    fun repeatChoices(id: String): List<RepeatChoice> {
        val t = get(id) ?: return emptyList()
        val day = t.occurrenceDay ?: (t.scheduledAtMs ?: t.dueAtMs)?.let(calendar::epochDayOf) ?: calendar.epochDayOf(nowMs())
        val current = t.recurrence
        val presets = Recurrence.presets(day)
        return buildList {
            add(RepeatChoice("Doesn't repeat", null, t.recurrenceRule == null))
            presets.forEach { add(RepeatChoice(it.describe(), it.encode(), it == current)) }
            if (t.recurrenceRule != null && (current == null || current !in presets)) add(RepeatChoice(t.repeatLabel!!, t.recurrenceRule, true))
        }
    }

    /** Skips this occurrence of a repeating task (it is cancelled, not done) and creates the next one. */
    fun skipOccurrence(id: String) {
        val t = get(id) ?: throw ValidationException("Task not found")
        if (t.recurrenceRule == null) throw ValidationException("Only a repeating task can skip an occurrence")
        if (t.lifecycle.isTerminal) return
        replica.commitLocal(EntityTypes.TASK, id, mapOf(ActionableFields.LIFECYCLE to Lifecycle.CANCELLED.name.fv()))
        spawnNext(t)
    }

    /**
     * Snoozes one occurrence by [days] (from today, or from its snoozed day if later): it leaves Today and comes back
     * on that day with its times of day. The series keeps its rhythm; the next occurrence still follows the rule.
     */
    fun snoozeOccurrence(id: String, days: Int = 1) {
        if (days !in 1..365) throw ValidationException("Snooze must be 1 to 365 days")
        val t = get(id) ?: throw ValidationException("Task not found")
        if (t.lifecycle.isTerminal) return
        val today = calendar.epochDayOf(nowMs())
        val from = maxOf(today, t.showsFromDay ?: today)
        val to = from + days
        val anchor = t.showsFromDay ?: today
        val changes = linkedMapOf<String, FieldValue>(TaskFields.DEFERRED_TO_DAY to to.fv())
        t.scheduledAtMs?.let { changes[TaskFields.SCHEDULED_AT] = shifted(it, anchor, to).fv() }
        t.dueAtMs?.let { changes[ActionableFields.DUE_AT] = shifted(it, anchor, to).fv() }
        t.remindAtMs?.let { changes[TaskFields.REMIND_AT] = shifted(it, anchor, to).fv() }
        replica.commitLocal(EntityTypes.TASK, id, changes)
    }

    /**
     * When (task detail): puts [id] on local [day] (today or later), at [minuteOfDay] or with no time. A time plans it
     * on that day's timeline; no time leaves it under Anytime. A later day keeps it out of Today until then (the same
     * snooze as Tomorrow); today brings a snoozed one back. A repeating occurrence keeps its place in the series.
     * The due date (a deadline) is left alone.
     */
    fun setWhen(id: String, day: Long, minuteOfDay: Int?) {
        val t = get(id) ?: throw ValidationException("Task not found")
        if (t.lifecycle.isTerminal) return
        val today = calendar.epochDayOf(nowMs())
        if (day < today) throw ValidationException("Pick today or a later day")
        if (day > today + TaskWhenRules.MAX_DAYS_AHEAD) throw ValidationException("That's too far ahead")
        if (minuteOfDay != null && minuteOfDay !in 0 until 24 * 60) throw ValidationException("That isn't a time of day")
        val changes = linkedMapOf<String, FieldValue>()
        // Shown from: its occurrence day (or today) unless snoozed to another; a day that is already where it shows
        // from needs no snooze, and today never moves an overdue occurrence's "since" day.
        val base = t.occurrenceDay ?: today
        val deferTo = if ((day == today && base <= today) || day == base) null else day
        if (deferTo != t.deferredToDay) changes[TaskFields.DEFERRED_TO_DAY] = deferTo?.fv() ?: FieldValue.Null
        val at = minuteOfDay?.let { calendar.toEpochMs(day, it) }
        if (at != t.scheduledAtMs) changes[TaskFields.SCHEDULED_AT] = at?.fv() ?: FieldValue.Null
        // A reminder moves with the task: by the same amount between two times ("15 min before" stays 15 min before),
        // else to the same time of day on the new day.
        t.remindAtMs?.let { r ->
            val oldDay = TaskWhenRules.dayOf(t, today, calendar)
            val oldAt = TaskWhenRules.minuteOf(t, today, calendar)?.let { calendar.toEpochMs(oldDay, it) }
            val moved = if (oldAt != null && at != null) r + (at - oldAt) else shifted(r, oldDay, day)
            if (moved != r) changes[TaskFields.REMIND_AT] = moved.fv()
        }
        if (changes.isNotEmpty()) replica.commitLocal(EntityTypes.TASK, id, changes)
    }

    /**
     * Remind me (task detail): a heads-up at [atMs] ([TaskReminderRules]); null turns it off. Must be in the future and
     * at most two years ahead. Moves with the task's When, Tomorrow and its repeats.
     */
    fun setReminder(id: String, atMs: Long?) {
        val t = get(id) ?: throw ValidationException("Task not found")
        if (atMs != null) {
            val now = nowMs()
            if (atMs <= now) throw ValidationException("That time has gone")
            if (atMs > now + TaskWhenRules.MAX_DAYS_AHEAD * CivilDate.DAY_MS + CivilDate.DAY_MS) throw ValidationException("That's too far ahead")
        }
        if (atMs == t.remindAtMs) return
        replica.commitLocal(EntityTypes.TASK, id, mapOf(TaskFields.REMIND_AT to (atMs?.fv() ?: FieldValue.Null)))
    }

    /** Notes (task detail): any length up to 10,000 characters; blank clears them. */
    fun setNotes(id: String, notes: String) {
        val t = get(id) ?: throw ValidationException("Task not found")
        val text = notes.trimEnd().takeIf { it.isNotBlank() }
        if (text != null && text.length > MAX_NOTES) throw ValidationException("Notes are too long")
        if (text == t.notes?.takeIf { it.isNotBlank() }) return
        replica.commitLocal(EntityTypes.TASK, id, mapOf(ActionableFields.NOTES to (text?.fv() ?: FieldValue.Null)))
    }

    /**
     * Creates the occurrence after [t] (if [t] repeats with a rule this version understands). Missed occurrences are
     * not back-filled: the next one is the first on or after today. Its id is derived from the series and day, so two
     * devices completing the same occurrence offline write the same task, and completing again after a reopen
     * doesn't overwrite edits already made to it. Steps (the checklist) come along unticked: that is a routine.
     */
    private fun spawnNext(t: Task) {
        val rule = t.recurrence ?: return
        val series = t.seriesId ?: t.id
        val today = calendar.epochDayOf(nowMs())
        val from = t.occurrenceDay ?: rule.firstOnOrAfter((t.scheduledAtMs ?: t.dueAtMs)?.let(calendar::epochDayOf) ?: today)
        val shown = t.showsFromDay ?: from
        var next = rule.next(from)
        var guard = 0
        while ((next <= shown || next < today) && guard++ < MAX_CATCH_UP) next = rule.next(next)
        val nextId = occurrenceId(series, next)
        if (replica.entity(EntityTypes.TASK, nextId) != null) return

        val fields = linkedMapOf<String, FieldValue>(
            ActionableFields.TITLE to t.title.fv(),
            ActionableFields.LIFECYCLE to Lifecycle.ACTIVE.name.fv(),
            ActionableFields.CREATED_AT to nowMs().fv(),
            ActionableFields.PRIORITY to t.priority.fv(),
            ActionableFields.VISIBILITY to Visibility.PRIVATE.name.fv(),
            ActionableFields.PROVENANCE_SOURCE to "repeat:$series".fv(),
            ActionableFields.PROVENANCE_TRUST to Trust.TRUSTED_USER.name.fv(),
            TaskFields.RECURRENCE to t.recurrenceRule!!.fv(),
            TaskFields.SERIES_ID to series.fv(),
            TaskFields.OCCURRENCE_DAY to next.fv(),
        )
        t.notes?.let { fields[ActionableFields.NOTES] = it.fv() }
        t.estimateMinutes?.let { fields[TaskFields.ESTIMATE_MINUTES] = it.fv() }
        t.goalId?.let { fields[ActionableFields.GOAL_ID] = it.fv() }
        t.scheduledAtMs?.let { fields[TaskFields.SCHEDULED_AT] = shifted(it, shown, next).fv() }
        t.dueAtMs?.let { fields[ActionableFields.DUE_AT] = shifted(it, shown, next).fv() }
        t.remindAtMs?.let { fields[TaskFields.REMIND_AT] = shifted(it, shown, next).fv() }
        replica.entity(EntityTypes.TASK, t.id)?.get(ActionableFields.OWNER_PERSON_ID)?.textOrNull
            ?.let { fields[ActionableFields.OWNER_PERSON_ID] = it.fv() }
        replica.commitLocal(EntityTypes.TASK, nextId, fields)

        t.checklist.forEachIndexed { i, item ->
            replica.commitLocal(
                EntityTypes.CHECKLIST_ITEM, "$nextId.$i",
                mapOf(
                    ChecklistFields.TASK_ID to nextId.fv(),
                    ChecklistFields.TEXT to item.text.fv(),
                    ChecklistFields.CHECKED to false.fv(),
                    ChecklistFields.POSITION to item.position.fv(),
                ),
            )
        }
    }

    /** Moves [ms] from local day [fromDay] to [toDay] at the same local time, keeping any days it sat after [fromDay]. */
    private fun shifted(ms: Long, fromDay: Long, toDay: Long): Long {
        val extra = (calendar.epochDayOf(ms) - fromDay).coerceAtLeast(0)
        return calendar.toEpochMs(toDay + extra, calendar.minuteOfDay(ms))
    }

    fun reopen(id: String) {
        requireExists(id)
        replica.commitLocal(EntityTypes.TASK, id, mapOf(ActionableFields.LIFECYCLE to Lifecycle.ACTIVE.name.fv()))
    }

    /**
     * Done or Tomorrow from the Needs you stack, returning what the undo bar needs. Done on a repeating occurrence
     * creates the next one, as anywhere else.
     */
    fun decide(id: String, effect: DecisionEffect): DecisionUndo? {
        val before = get(id) ?: throw ValidationException("Task not found")
        if (before.lifecycle.isTerminal) return null
        when (effect) {
            DecisionEffect.COMPLETE_TASK -> complete(id)
            DecisionEffect.SNOOZE_TASK -> snoozeOccurrence(id, 1)
            else -> return null
        }
        val after = get(id) ?: return null
        return DecisionUndo(id, effect, TaskTiming.of(before), TaskTiming.of(after))
    }

    /**
     * Puts back what [decide] changed, only while the task is still as it was left (a change made since, here or on
     * another device, wins). Returns false when there was nothing to put back. A repeating task's next occurrence,
     * created by Done, stays (it waits for its own day), as with Reopen.
     */
    fun undoDecision(u: DecisionUndo): Boolean {
        val now = get(u.taskId) ?: return false
        if (TaskTiming.of(now) != u.after) return false
        val changes = linkedMapOf<String, FieldValue>()
        when (u.effect) {
            DecisionEffect.COMPLETE_TASK -> changes[ActionableFields.LIFECYCLE] = u.before.lifecycle.name.fv()
            DecisionEffect.SNOOZE_TASK -> {
                changes[TaskFields.DEFERRED_TO_DAY] = u.before.deferredToDay?.fv() ?: FieldValue.Null
                changes[TaskFields.SCHEDULED_AT] = u.before.scheduledAtMs?.fv() ?: FieldValue.Null
                changes[ActionableFields.DUE_AT] = u.before.dueAtMs?.fv() ?: FieldValue.Null
                if (u.before.remindAtMs != u.after.remindAtMs) changes[TaskFields.REMIND_AT] = u.before.remindAtMs?.fv() ?: FieldValue.Null
            }
            else -> return false
        }
        replica.commitLocal(EntityTypes.TASK, u.taskId, changes)
        return true
    }

    /**
     * Puts a task's state and timing back to [before] (an Ask card's Undo: ticked off or moved), only while it is
     * still exactly as [after] left it; a change made since, here or on another device, wins. False when nothing was
     * put back. A repeating task's next occurrence, created by Done, stays, as with Reopen.
     */
    fun putBack(id: String, before: TaskTiming, after: TaskTiming): Boolean {
        val now = get(id) ?: return false
        if (TaskTiming.of(now) != after) return false
        val changes = linkedMapOf<String, FieldValue>()
        if (before.lifecycle != after.lifecycle) changes[ActionableFields.LIFECYCLE] = before.lifecycle.name.fv()
        if (before.deferredToDay != after.deferredToDay) changes[TaskFields.DEFERRED_TO_DAY] = before.deferredToDay?.fv() ?: FieldValue.Null
        if (before.scheduledAtMs != after.scheduledAtMs) changes[TaskFields.SCHEDULED_AT] = before.scheduledAtMs?.fv() ?: FieldValue.Null
        if (before.dueAtMs != after.dueAtMs) changes[ActionableFields.DUE_AT] = before.dueAtMs?.fv() ?: FieldValue.Null
        if (before.remindAtMs != after.remindAtMs) changes[TaskFields.REMIND_AT] = before.remindAtMs?.fv() ?: FieldValue.Null
        if (changes.isEmpty()) return false
        replica.commitLocal(EntityTypes.TASK, id, changes)
        return true
    }

    /**
     * The three-carry prompt's answer ([RollOverRules]): marks it answered today, then Someday moves the task there and
     * Drop cancels it; Break it down and Keep leave it where it is. Returns the task as it was, for Undo.
     */
    fun answerCarry(id: String, choice: CarryChoice): Task {
        val t = get(id) ?: throw ValidationException("Task not found")
        val fields = linkedMapOf<String, FieldValue>(TaskFields.CARRY_ASKED_DAY to calendar.epochDayOf(nowMs()).fv())
        when (choice) {
            CarryChoice.SOMEDAY -> {
                if (t.isRepeating) throw ValidationException("A repeating task can't go to Someday")
                fields[ActionableFields.LIFECYCLE] = Lifecycle.SOMEDAY.name.fv()
                fields[ActionableFields.SOMEDAY_KIND] = SomedayKind.OTHER.name.fv()
            }
            CarryChoice.DROP -> {
                if (t.isRepeating) throw ValidationException("Skip a repeating task instead")
                fields[ActionableFields.LIFECYCLE] = Lifecycle.CANCELLED.name.fv()
            }
            CarryChoice.BREAK_DOWN, CarryChoice.KEEP -> Unit
        }
        replica.commitLocal(EntityTypes.TASK, id, fields)
        return t
    }

    /** Undo for [answerCarry]: puts back the task's state and lets the prompt ask again. */
    fun undoCarry(before: Task) {
        val fields = linkedMapOf<String, FieldValue>(
            ActionableFields.LIFECYCLE to before.lifecycle.name.fv(),
            TaskFields.CARRY_ASKED_DAY to (before.carryAskedDay?.fv() ?: FieldValue.Null),
            ActionableFields.SOMEDAY_KIND to (before.somedayKind?.name?.fv() ?: FieldValue.Null),
        )
        replica.commitLocal(EntityTypes.TASK, before.id, fields)
    }

    /**
     * A move on the Week board ([WeekBoardRules.move]): the task goes to [BoardMove.toDay] (keeping its time, through
     * [setWhen]) and the day's cards take their new places. Returns what Undo needs.
     */
    fun placeOnBoard(move: BoardMove): BoardUndo {
        val t = get(move.taskId) ?: throw ValidationException("Task not found")
        if (t.lifecycle.isTerminal) throw ValidationException("That task is finished")
        val before = TaskTiming.of(t)
        val oldRanks = move.ranks.keys.associateWith { get(it)?.boardRank }
        // Off the Someday tray or out of a week's lane (slice 4): an open task again, no longer parked.
        val back = linkedMapOf<String, FieldValue>()
        if (t.lifecycle == Lifecycle.SOMEDAY) back[ActionableFields.LIFECYCLE] = Lifecycle.ACTIVE.name.fv()
        if (t.boardWeek != null) back[TaskFields.BOARD_WEEK] = FieldValue.Null
        if (back.isNotEmpty()) replica.commitLocal(EntityTypes.TASK, t.id, back)
        if (move.dayChanged) setWhen(t.id, move.toDay, move.minuteOfDay)
        val after = TaskTiming.of(get(t.id)!!)
        move.ranks.forEach { (id, rank) -> replica.commitLocal(EntityTypes.TASK, id, mapOf(TaskFields.BOARD_RANK to rank.fv())) }
        return BoardUndo(t.id, before, after, oldRanks, oldWeek = t.boardWeek, newWeek = null, oldKind = t.somedayKind)
    }

    /**
     * Parks a task in a week's lane on the Board ([WeekBoardRules.park], slice 4): no day and no time, kept for that
     * week ([TaskFields.BOARD_WEEK]); off Someday if it was there. A repeating task can't be parked.
     */
    fun parkOnBoard(park: BoardPark): BoardUndo {
        val t = get(park.taskId) ?: throw ValidationException("Task not found")
        if (t.lifecycle.isTerminal) throw ValidationException("That task is finished")
        if (t.isRepeating) throw ValidationException("A repeating task belongs to its days")
        val before = TaskTiming.of(t)
        val oldRanks = park.ranks.keys.associateWith { get(it)?.boardRank }
        val fields = linkedMapOf<String, FieldValue>()
        if (t.lifecycle == Lifecycle.SOMEDAY) fields[ActionableFields.LIFECYCLE] = Lifecycle.ACTIVE.name.fv()
        if (t.scheduledAtMs != null) fields[TaskFields.SCHEDULED_AT] = FieldValue.Null
        if (t.deferredToDay != null) fields[TaskFields.DEFERRED_TO_DAY] = FieldValue.Null
        if (t.boardWeek != park.weekStart) fields[TaskFields.BOARD_WEEK] = park.weekStart.fv()
        if (fields.isNotEmpty()) replica.commitLocal(EntityTypes.TASK, t.id, fields)
        val after = TaskTiming.of(get(t.id)!!)
        park.ranks.forEach { (id, rank) -> replica.commitLocal(EntityTypes.TASK, id, mapOf(TaskFields.BOARD_RANK to rank.fv())) }
        return BoardUndo(t.id, before, after, oldRanks, oldWeek = t.boardWeek, newWeek = park.weekStart, oldKind = t.somedayKind)
    }

    /** The Board's Someday tray (slice 4): the task goes to Someday (kept as Other unless it had a kind), no day, no week. */
    fun somedayOnBoard(id: String): BoardUndo {
        val t = get(id) ?: throw ValidationException("Task not found")
        if (t.lifecycle.isTerminal) throw ValidationException("That task is finished")
        if (t.isRepeating) throw ValidationException("A repeating task can't go to Someday")
        val before = TaskTiming.of(t)
        val fields = linkedMapOf<String, FieldValue>(ActionableFields.LIFECYCLE to Lifecycle.SOMEDAY.name.fv())
        if (t.somedayKind == null) fields[ActionableFields.SOMEDAY_KIND] = SomedayKind.OTHER.name.fv()
        if (t.scheduledAtMs != null) fields[TaskFields.SCHEDULED_AT] = FieldValue.Null
        if (t.deferredToDay != null) fields[TaskFields.DEFERRED_TO_DAY] = FieldValue.Null
        if (t.boardWeek != null) fields[TaskFields.BOARD_WEEK] = FieldValue.Null
        replica.commitLocal(EntityTypes.TASK, id, fields)
        val after = TaskTiming.of(get(id)!!)
        return BoardUndo(id, before, after, emptyMap(), oldWeek = t.boardWeek, newWeek = null, oldKind = t.somedayKind)
    }

    /**
     * Undo for [placeOnBoard], [parkOnBoard] and [somedayOnBoard]: the task's day, time and Someday state come back
     * (unless changed since), the week it was parked in, and the old order.
     */
    fun undoBoard(u: BoardUndo): Boolean {
        val timing = if (u.before != u.after) putBack(u.taskId, u.before, u.after) else false
        get(u.taskId)?.let { now ->
            val fields = linkedMapOf<String, FieldValue>()
            if (now.boardWeek == u.newWeek && u.oldWeek != u.newWeek) fields[TaskFields.BOARD_WEEK] = u.oldWeek?.fv() ?: FieldValue.Null
            if (timing && now.somedayKind != u.oldKind) {
                fields[ActionableFields.SOMEDAY_KIND] = u.oldKind?.name?.fv() ?: FieldValue.Null
            }
            if (fields.isNotEmpty()) replica.commitLocal(EntityTypes.TASK, u.taskId, fields)
        }
        var ranks = false
        u.oldRanks.forEach { (id, rank) ->
            if (get(id) == null) return@forEach
            replica.commitLocal(EntityTypes.TASK, id, mapOf(TaskFields.BOARD_RANK to (rank?.fv() ?: FieldValue.Null)))
            ranks = true
        }
        return timing || ranks
    }

    fun moveToSomeday(id: String, kind: SomedayKind = SomedayKind.IDEA) {
        requireExists(id)
        replica.commitLocal(
            EntityTypes.TASK, id,
            mapOf(ActionableFields.LIFECYCLE to Lifecycle.SOMEDAY.name.fv(), ActionableFields.SOMEDAY_KIND to kind.name.fv()),
        )
    }

    fun delete(id: String) {
        requireExists(id)
        replica.commitLocal(EntityTypes.TASK, id, mapOf(ActionableFields.DELETED to true.fv()))
    }

    fun restore(id: String) {
        replica.commitLocal(EntityTypes.TASK, id, mapOf(ActionableFields.DELETED to false.fv()))
    }

    fun addChecklistItem(taskId: String, text: String): String {
        requireExists(taskId)
        val t = text.trim()
        if (t.isEmpty()) throw ValidationException("Checklist item needs text")
        val id = ids()
        replica.commitLocal(
            EntityTypes.CHECKLIST_ITEM, id,
            mapOf(
                ChecklistFields.TASK_ID to taskId.fv(),
                ChecklistFields.TEXT to t.fv(),
                ChecklistFields.CHECKED to false.fv(),
                ChecklistFields.POSITION to nowMs().fv(),
            ),
        )
        return id
    }

    /**
     * A step under a chosen id (unticked, at [position]), so two devices laying down the same list offline converge
     * on one; a deleted one under that id comes back.
     */
    fun putChecklistItem(itemId: String, taskId: String, text: String, position: Long) {
        requireExists(taskId)
        val t = text.trim()
        if (t.isEmpty()) throw ValidationException("Checklist item needs text")
        val fields = linkedMapOf(
            ChecklistFields.TASK_ID to taskId.fv(),
            ChecklistFields.TEXT to t.fv(),
            ChecklistFields.CHECKED to false.fv(),
            ChecklistFields.POSITION to position.fv(),
        )
        if (replica.entity(EntityTypes.CHECKLIST_ITEM, itemId)?.deleted == true) fields[ActionableFields.DELETED] = false.fv()
        replica.commitLocal(EntityTypes.CHECKLIST_ITEM, itemId, fields)
    }

    fun renameChecklistItem(itemId: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) throw ValidationException("Checklist item needs text")
        replica.commitLocal(EntityTypes.CHECKLIST_ITEM, itemId, mapOf(ChecklistFields.TEXT to t.fv()))
    }

    fun deleteChecklistItem(itemId: String) {
        replica.commitLocal(EntityTypes.CHECKLIST_ITEM, itemId, mapOf(ActionableFields.DELETED to true.fv()))
    }

    fun setChecklistItemChecked(itemId: String, checked: Boolean) {
        replica.commitLocal(EntityTypes.CHECKLIST_ITEM, itemId, mapOf(ChecklistFields.CHECKED to checked.fv()))
    }

    /**
     * Checklists (2026-10-11): one typed or pasted line adds each of its items ([ChecklistRules.split]) at the end of
     * the list, unticked. Returns the new ids in order (empty when the line held nothing).
     */
    fun addChecklistItems(taskId: String, text: String): List<String> = addChecklistTexts(taskId, ChecklistRules.split(text))

    /** These items, as they are (a template's, never split again), at the end of the list, unticked. Returns their ids. */
    fun addChecklistTexts(taskId: String, texts: List<String>): List<String> {
        requireExists(taskId)
        if (texts.isEmpty()) return emptyList()
        val positions = ChecklistRules.positionsAfter(checklistFor(taskId), texts.size, nowMs())
        return texts.mapIndexed { i, t ->
            val id = ids()
            replica.commitLocal(
                EntityTypes.CHECKLIST_ITEM, id,
                mapOf(
                    ChecklistFields.TASK_ID to taskId.fv(),
                    ChecklistFields.TEXT to t.fv(),
                    ChecklistFields.CHECKED to false.fv(),
                    ChecklistFields.POSITION to positions[i].fv(),
                ),
            )
            id
        }
    }

    /** Undo for [deleteChecklistItem]: the item comes back as it was (text, tick and place). */
    fun restoreChecklistItem(itemId: String) {
        replica.commitLocal(EntityTypes.CHECKLIST_ITEM, itemId, mapOf(ActionableFields.DELETED to false.fv()))
    }

    /** Untick all, to reuse the list: returns the ids it unticked (for Undo). */
    fun untickAllChecklistItems(taskId: String): List<String> {
        val ticked = checklistFor(taskId).filter { it.checked }.map { it.id }
        ticked.forEach { setChecklistItemChecked(it, false) }
        return ticked
    }

    /** Moves an item to [toIndex] in its list's order ([ChecklistRules.reorder]). */
    fun moveChecklistItem(taskId: String, itemId: String, toIndex: Int) {
        ChecklistRules.reorder(checklistFor(taskId), itemId, toIndex).forEach { (id, p) ->
            replica.commitLocal(EntityTypes.CHECKLIST_ITEM, id, mapOf(ChecklistFields.POSITION to p.fv()))
        }
    }

    /** Drag to reorder (Checklists, slice 2): moves the item to [toGroupIndex] among the rows of its own group. */
    fun moveChecklistItemInGroup(taskId: String, itemId: String, toGroupIndex: Int) {
        val items = checklistFor(taskId)
        val to = ChecklistRules.listIndexForGroupMove(items, itemId, toGroupIndex) ?: return
        moveChecklistItem(taskId, itemId, to)
    }

    /** Resolves a conflicting field by choosing a value; the resulting op dominates all concurrent heads. */
    fun resolveConflict(conflict: Conflict, chosen: FieldValue) {
        replica.commitLocal(conflict.key.entityType, conflict.key.entityId, mapOf(conflict.key.field to chosen))
    }

    fun get(id: String): Task? {
        val s = replica.entity(EntityTypes.TASK, id) ?: return null
        if (s.deleted) return null
        return Task.from(s, replica.conflictsFor(EntityTypes.TASK, id).isNotEmpty(), checklistFor(id))
    }

    fun all(): List<Task> {
        val items = replica.entities(EntityTypes.CHECKLIST_ITEM).map { it.toChecklistItem() }.groupBy { it.taskId }
        return replica.entities(EntityTypes.TASK).map { s ->
            Task.from(
                s,
                replica.conflictsFor(EntityTypes.TASK, s.ref.entityId).isNotEmpty(),
                items[s.ref.entityId].orEmpty().sortedBy { it.position },
            )
        }
    }

    fun conflicts(): List<Conflict> = replica.conflicts(EntityTypes.TASK, EntityTypes.CHECKLIST_ITEM)

    private fun checklistFor(taskId: String) = replica.entities(EntityTypes.CHECKLIST_ITEM)
        .map { it.toChecklistItem() }.filter { it.taskId == taskId }.sortedBy { it.position }

    private fun requireExists(id: String) {
        val s = replica.entity(EntityTypes.TASK, id)
        if (s == null || s.deleted) throw ValidationException("Task not found")
    }

    companion object {
        /** Upper bound on occurrences skipped while catching up (a daily task untouched for ~27 years). */
        private const val MAX_CATCH_UP = 10_000
        const val MAX_NOTES = 10_000

        /** Deterministic id of a series' occurrence on a local day. */
        fun occurrenceId(seriesId: String, epochDay: Long) = "$seriesId.d$epochDay"
    }

    private fun EntitySnapshot.toChecklistItem() = ChecklistItem(
        id = ref.entityId,
        taskId = this[ChecklistFields.TASK_ID].textOrNull ?: "",
        text = this[ChecklistFields.TEXT].textOrNull ?: "",
        checked = this[ChecklistFields.CHECKED].boolOrNull ?: false,
        position = this[ChecklistFields.POSITION].longOrNull ?: 0L,
    )
}
