package os.meka.core.domain

import os.meka.core.sync.Conflict
import os.meka.core.sync.EntitySnapshot
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

data class ChecklistItem(val id: String, val taskId: String, val text: String, val checked: Boolean, val position: Long)

/** Typed read model of a Task. Built from an [EntitySnapshot]; never written directly. */
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
) {
    val isDone: Boolean get() = lifecycle == Lifecycle.DONE

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

class ValidationException(message: String) : IllegalArgumentException(message)

/**
 * Task commands and queries over a [Replica]. All writes become ops, so they are offline-first,
 * synced, merge-safe and auditable.
 */
class Tasks(private val replica: Replica, private val ids: () -> String, private val nowMs: () -> Long) {

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

    fun complete(id: String) {
        requireExists(id)
        replica.commitLocal(
            EntityTypes.TASK, id,
            mapOf(ActionableFields.LIFECYCLE to Lifecycle.DONE.name.fv(), ActionableFields.COMPLETED_AT to nowMs().fv()),
        )
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

    private fun EntitySnapshot.toChecklistItem() = ChecklistItem(
        id = ref.entityId,
        taskId = this[ChecklistFields.TASK_ID].textOrNull ?: "",
        text = this[ChecklistFields.TEXT].textOrNull ?: "",
        checked = this[ChecklistFields.CHECKED].boolOrNull ?: false,
        position = this[ChecklistFields.POSITION].longOrNull ?: 0L,
    )
}
