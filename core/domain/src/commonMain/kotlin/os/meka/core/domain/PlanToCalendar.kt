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
     * [events] without the blocks Plan my day added for tasks: the provisional event of such an add, and the mirrored
     * event it became (same account, title and time). Undone adds sent nothing, so they hide nothing.
     */
    fun withoutTaskBlocks(events: List<CalendarEvent>, edits: List<EventEdit>): List<CalendarEvent> {
        val blocks = edits.filter { it.forTask != null && it.kind == EventEditKind.ADD && !it.undone && it.draft != null }
        if (blocks.isEmpty()) return events
        val ids = blocks.mapTo(HashSet()) { it.id }
        return events.filterNot { e ->
            val pending = PendingEditRules.editIdOf(e.id)
            if (pending != null) pending in ids
            else blocks.any { b ->
                val d = b.draft!!
                b.status != EventEditStatus.REFUSED && b.status != EventEditStatus.FAILED &&
                    e.provider == b.provider && e.account == b.account && !e.allDay &&
                    e.startAtMs == d.startAtMs && e.endAtMs == d.endAtMs && e.title.trim() == d.title.trim()
            }
        }
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
