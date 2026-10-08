package os.meka.core.domain

import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Edit your calendars (slice 2e): **Plan my day's blocks in your calendar**. Non-AI, pure rules plus a small store.
 *
 * A setting, off by default, synced (`context_mode/plan.planToCalendar`, LWW, ADR-008 addendum): when it's on and an
 * account allows editing, Apply in Plan my day schedules the tasks as before **and** adds one event per task block to
 * that account's main calendar, so the time shows as busy to anyone looking at Meka's Google calendar. Each block is an
 * ordinary calendar add (`event_edit`, five-second Undo, sent by the server, listed in Activity) carrying the task it
 * was made for ([EventEditFields.FOR_TASK]). The account is the one Add event remembers, else the first that can edit.
 *
 * Inside MEKA the task already stands for that time on Today and in the Calendar tab, so the block itself (provisional
 * while on its way, then the mirrored event) is left out of every view ([withoutTaskBlocks]): it is never shown twice
 * and the planner never plans around its own blocks. Habit blocks and meals are shown, not applied, as before.
 *
 * It's Meka's tap on Apply that writes the blocks (ADR-006): MEKA never adds blocks on its own, and nothing is sent
 * while the setting is off.
 */
object PlanCalendarFields {
    /** Bool, absent = off. */
    const val ON = "planToCalendar"
}

/** The setting as Plan my day shows it. */
data class PlanCalendarSetting(
    /** An account allows editing, so the switch is offered at all. */
    val available: Boolean,
    val on: Boolean,
    /** Where the blocks go while on ("Google · meka@gmail.com"), or null. */
    val accountLabel: String?,
    /** "Also add the blocks to Google" (the switch's label). */
    val label: String,
    /** The line under it: what Apply will do. */
    val line: String,
) {
    companion object {
        val OFF = PlanCalendarSetting(false, false, null, "Also add the blocks to your calendar", "")
    }
}

/** What Apply did with the calendar: the adds made (for Undo) and the undo bar's line. */
data class PlanApplied(
    val editIds: List<String>,
    /** "Adding 3 blocks to Google"; null when nothing was sent to a calendar. */
    val line: String?,
    /** Blocks that couldn't be added, in words ("Give it a title"), for the rare refusal. */
    val refused: List<String> = emptyList(),
)

object PlanCalendarRules {
    /** Written as each block's notes, so it reads as MEKA's in Google. */
    const val NOTE = "Planned with MEKA"

    /** The account the blocks go to: Add event's remembered one while it can edit, else the first that can. */
    fun target(accounts: List<EditAccount>, lastUsedKey: String?): EditAccount? =
        accounts.firstOrNull { it.key == lastUsedKey } ?: accounts.firstOrNull()

    fun setting(on: Boolean, accounts: List<EditAccount>, lastUsedKey: String?): PlanCalendarSetting {
        val target = target(accounts, lastUsedKey) ?: return PlanCalendarSetting.OFF.copy(on = on)
        val p = CalendarEditRules.providerName(target.provider)
        return PlanCalendarSetting(
            available = true,
            on = on,
            accountLabel = target.label.takeIf { on },
            label = "Also add the blocks to $p",
            line = if (on) "Apply adds each task's block to ${target.email} too, so the time shows as busy"
            else "Apply plans the tasks in MEKA only",
        )
    }

    /** The event a task's block becomes. */
    fun draft(p: DayPlanner.Placement): EventDraft =
        EventDraft(p.task.title.trim().ifEmpty { "Task" }, p.startMs, p.endMs, allDay = false, notes = NOTE)

    /** "Adding 1 block to Google" · "Adding 3 blocks to Google". */
    fun line(count: Int, provider: String): String =
        "Adding $count ${if (count == 1) "block" else "blocks"} to ${CalendarEditRules.providerName(provider)}"

    /**
     * [events] without the blocks Plan my day added for tasks: the provisional event of such an add, the mirrored
     * event it became (same account, title and time), and an event a block's follow-up moved (slice 2f: a change or
     * delete made for a task names its event). Undone edits sent nothing, so they hide nothing.
     */
    fun withoutTaskBlocks(events: List<CalendarEvent>, edits: List<EventEdit>): List<CalendarEvent> {
        val forTasks = edits.filter { it.forTask != null && !it.undone }
        if (forTasks.isEmpty()) return events
        val blocks = forTasks.filter { it.kind == EventEditKind.ADD && it.draft != null }
        val ids = blocks.mapTo(HashSet()) { it.id }
        val followed = forTasks.mapNotNullTo(HashSet()) { e -> e.eventId.takeIf { e.kind != EventEditKind.ADD } }
        return events.filterNot { e ->
            val pending = PendingEditRules.editIdOf(e.id)
            if (pending != null) pending in ids
            else e.id in followed || blocks.any { b ->
                val d = b.draft!!
                b.status != EventEditStatus.REFUSED && b.status != EventEditStatus.FAILED &&
                    e.provider == b.provider && e.account == b.account && !e.allDay &&
                    e.startAtMs == d.startAtMs && e.endAtMs == d.endAtMs && e.title.trim() == d.title.trim()
            }
        }
    }

    // ---- Slice 2f: a block keeps in step with its task ----

    /**
     * Where [taskId]'s block stands: the latest edit made for it (undone, refused and failed ones don't count) and the
     * calendar event it is now in [events] (the mirror with Meka's edits laid over, **before** [withoutTaskBlocks]),
     * or null while it can't be found (on its way, or changed in the provider since). Null when the task never had one.
     */
    fun blockOf(taskId: String, events: List<CalendarEvent>, edits: List<EventEdit>): TaskBlock? {
        val latest = edits.filter {
            it.forTask == taskId && !it.undone && it.status != EventEditStatus.REFUSED && it.status != EventEditStatus.FAILED
        }.maxWithOrNull(compareBy<EventEdit>({ it.createdAtMs }, { it.id })) ?: return null
        val event = when (latest.kind) {
            EventEditKind.DELETE -> null
            EventEditKind.CHANGE -> events.firstOrNull { it.id == latest.eventId }
            EventEditKind.ADD -> {
                val d = latest.draft
                events.firstOrNull { it.id == PendingEditRules.PROVISIONAL_PREFIX + latest.id }
                    ?: events.firstOrNull { e ->
                        d != null && !e.isProvisional && e.provider == latest.provider && e.account == latest.account && !e.allDay &&
                            e.startAtMs == d.startAtMs && e.endAtMs == d.endAtMs && e.title.trim() == d.title.trim()
                    }
            }
        }
        return TaskBlock(taskId, latest.provider, latest.account, latest, event, writtenTitle(taskId, edits))
    }

    /**
     * Slice 2g: the title MEKA last wrote for [taskId]'s block (its add, or a follow-up that renamed it); undone,
     * refused and failed edits don't count. A block whose title isn't this any more was renamed in the calendar by
     * Meka, so MEKA leaves its title alone.
     */
    fun writtenTitle(taskId: String, edits: List<EventEdit>): String? = edits
        .filter {
            it.forTask == taskId && !it.undone && it.status != EventEditStatus.REFUSED && it.status != EventEditStatus.FAILED &&
                it.draft != null && (it.kind == EventEditKind.ADD || EventEditChange.TITLE in it.changes)
        }
        .maxWithOrNull(compareBy<EventEdit>({ it.createdAtMs }, { it.id }))?.draft?.title?.trim()

    /** The title a task's block carries: the task's own, trimmed ("Task" when blank). */
    fun blockTitle(task: Task): String = task.title.trim().ifEmpty { "Task" }

    /**
     * What the block should do now that its task changed: moving the task (When, Tomorrow, Plan again, carrying it
     * over) moves the block to its new start, keeping its length; renaming the task renames the block (slice 2g),
     * unless Meka renamed the block in the calendar himself ([TaskBlock.writtenTitle]); Done leaves it where it was;
     * Someday, Skip, Delete or no time any more removes it; a block removed that way comes back when the task is
     * planned again with the setting on ([settingOn]), e.g. Undo after Delete. Nothing is done while a clash waits for
     * Meka's choice.
     */
    fun follow(task: Task?, block: TaskBlock?, settingOn: Boolean): BlockStep {
        if (block == null || task?.lifecycle == Lifecycle.DONE) return BlockStep.None
        if (block.latest.status == EventEditStatus.CLASH) return BlockStep.None
        val start = task?.takeIf { !it.lifecycle.isTerminal && it.lifecycle != Lifecycle.SOMEDAY }?.scheduledAtMs
        if (block.removed) {
            val was = block.latest.base ?: return BlockStep.None
            if (task == null || start == null || !settingOn) return BlockStep.None
            val length = (was.endAtMs - was.startAtMs).takeIf { it > 0 } ?: DEFAULT_LENGTH_MS
            return BlockStep.Add(
                block.provider, block.account,
                EventDraft(blockTitle(task), start, start + length, allDay = false, notes = NOTE),
            )
        }
        val ev = block.event
        if (start == null) return if (ev == null || ev.isProvisional) BlockStep.OnItsWay(onItsWayLine(block)) else BlockStep.Remove(ev)
        val title = blockTitle(task)
        if (ev == null) return BlockStep.OnItsWay(onItsWayLine(block))
        val written = block.writtenTitle
        val rename = written != null && ev.title.trim() == written && written != title
        val move = ev.startAtMs != start
        return when {
            !move && !rename -> BlockStep.None
            ev.isProvisional -> BlockStep.OnItsWay(onItsWayLine(block))
            rename -> BlockStep.Change(ev, moved(ev, start).copy(title = title))
            else -> BlockStep.Move(ev, start)
        }
    }

    /** The draft a follow-up move writes: the block as it is, starting at [startAtMs], the same length. */
    fun moved(event: CalendarEvent, startAtMs: Long): EventDraft =
        CalendarEditRules.draftOf(event).copy(startAtMs = startAtMs, endAtMs = startAtMs + (event.endAtMs - event.startAtMs))

    /**
     * "The block is still on its way to Google · it follows once it's there" (nothing sent yet; the device that made
     * the change follows once the calendar has it, see [WaitingFollow]).
     */
    fun onItsWayLine(block: TaskBlock): String {
        val p = CalendarEditRules.providerName(block.provider)
        return "The block is still on its way to $p · it follows once it's there"
    }

    /** How long a device keeps trying to catch up a block that was still on its way (the mirror takes about a minute). */
    const val WAIT_FOLLOW_MS = 30 * 60_000L

    /** A re-added block's length when the old one's is unknown. */
    const val DEFAULT_LENGTH_MS = 30 * 60_000L
}

/** A task's block in a calendar (see [PlanCalendarRules.blockOf]). */
data class TaskBlock(
    val taskId: String,
    val provider: String,
    val account: String,
    /** The latest edit made for the task (its add, a follow-up move or its removal). */
    val latest: EventEdit,
    /** The event it is now; null when removed, or not to be found yet. */
    val event: CalendarEvent?,
    /** The title MEKA last wrote for it ([PlanCalendarRules.writtenTitle]). */
    val writtenTitle: String? = null,
) {
    val removed: Boolean get() = latest.kind == EventEditKind.DELETE
}

/** What a task's block does after the task changed ([PlanCalendarRules.follow]). */
sealed class BlockStep {
    data object None : BlockStep()
    /** Move it to start at [startAtMs] (same length). */
    data class Move(val event: CalendarEvent, val startAtMs: Long) : BlockStep()
    /** Rename it (and move it when the time changed too): [draft] is the whole event as it should be. */
    data class Change(val event: CalendarEvent, val draft: EventDraft) : BlockStep()
    /** Take it out of the calendar. */
    data class Remove(val event: CalendarEvent) : BlockStep()
    /** Add it again (it was removed with the task, and the task is planned again). */
    data class Add(val provider: String, val account: String, val draft: EventDraft) : BlockStep()
    /** It should follow, but the provider doesn't have it yet, so nothing can be sent. */
    data class OnItsWay(val line: String) : BlockStep()
}

/**
 * Slice 2g: a task change whose block was still on its way to the calendar ([BlockStep.OnItsWay]), kept on the device
 * where Meka made it (never synced, so only that device follows) and tried again after each sync until the calendar has
 * the block. Dropped when the task has changed since (that newer change has its own follow-up, possibly on the other
 * device) or after [PlanCalendarRules.WAIT_FOLLOW_MS].
 */
data class WaitingFollow(
    val taskId: String,
    /** False when the task was deleted. */
    val present: Boolean,
    val scheduledAtMs: Long?,
    val title: String,
    val lifecycle: Lifecycle?,
    val sinceMs: Long,
) {
    /** Whether to try again now: the task is as it was when it waited, and it hasn't waited too long. */
    fun stillWanted(task: Task?, nowMs: Long): Boolean =
        nowMs - sinceMs <= PlanCalendarRules.WAIT_FOLLOW_MS && this == of(taskId, task, sinceMs)

    companion object {
        fun of(taskId: String, task: Task?, nowMs: Long) =
            WaitingFollow(taskId, task != null, task?.scheduledAtMs, task?.title.orEmpty(), task?.lifecycle, nowMs)
    }
}

/** The synced setting (`context_mode/plan`). */
class PlanCalendar(private val replica: Replica) {
    fun on(): Boolean = replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(PlanCalendarFields.ON)?.boolOrNull == true

    fun set(on: Boolean) {
        if (on == on() && replica.entity(EntityTypes.CONTEXT_MODE, ENTITY_ID)?.get(PlanCalendarFields.ON)?.boolOrNull != null) return
        replica.commitLocal(EntityTypes.CONTEXT_MODE, ENTITY_ID, mapOf(PlanCalendarFields.ON to on.fv()))
    }

    companion object {
        const val ENTITY_ID = "plan"
    }
}
