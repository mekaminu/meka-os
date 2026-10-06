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

    fun create(draft: NewTask): String {
        val title = draft.title.trim()
        if (title.isEmpty()) throw ValidationException("A task needs a title")
        if (title.length > 500) throw ValidationException("Title is too long")
        val id = ids()
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
        replica.commitLocal(EntityTypes.TASK, id, changes)
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
