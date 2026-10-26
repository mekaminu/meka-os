package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Calmer Today, slice 1 (Meka 2026-10-25 15:05: "an option to not display that and display when needed"; the approved
 * mockup docs/design/today/today-calmer.dc.html): how Today shows the day's done tasks, his choice, synced between the
 * Fold and the Mac. Non-AI and pure.
 *
 * - **Folded** (the default): one quiet line, "2 done today ›", that opens the ticked rows on a tap ("2 done today ·
 *   hide" closes them). Once opened it stays open until the end of that day, on both apps.
 * - **Hidden**: nothing at Today's foot; Undo on the bar after a tick still brings a task back.
 * - **Shown**: the rows as before, under "2 done today".
 */
enum class DoneShow(val code: String, val label: String, val line: String) {
    FOLDED("F", "Folded", "Done tasks fold into one line you can open"),
    HIDDEN("H", "Hidden", "Done tasks aren't shown · Undo after a tick still works"),
    SHOWN("S", "Shown", "Done tasks stay listed at the foot of Today"),
    ;

    companion object {
        /** The choice by its code; anything unknown (or nothing chosen) is [FOLDED]. */
        fun of(code: String?): DoneShow = entries.firstOrNull { it.code == code } ?: FOLDED
    }
}

/** The synced choice: how done tasks show, and the day they were last opened while folded (an epoch day). */
data class DoneTodaySetting(val show: DoneShow = DoneShow.FOLDED, val openedDay: Long? = null)

/** What Today draws for the day's done tasks, worked out for one day. */
data class DoneTodayChoiceView(
    val show: DoneShow,
    /** Folded and opened today: the rows show under the fold line. */
    val openToday: Boolean,
) {
    /** The ticked rows show (Shown, or Folded and opened). */
    val rowsShown: Boolean get() = show == DoneShow.SHOWN || (show == DoneShow.FOLDED && openToday)

    /** Settings' line under the choice. */
    val line: String get() = show.line

    companion object {
        val DEFAULT = DoneTodayChoiceView(DoneShow.FOLDED, openToday = false)
    }
}

object DoneTodayFields {
    const val SHOW = "show"
    const val OPENED_DAY = "openedDay"
}

object DoneTodayRules {
    const val ENTITY_ID = "done_today"

    /** Settings' heading for the choice (Ask → Settings → Appearance on the Fold, Appearance on the Mac). */
    const val HEADING = "Done tasks on Today"

    fun read(fields: Map<String, FieldValue>?): DoneTodaySetting = DoneTodaySetting(
        show = DoneShow.of(fields?.get(DoneTodayFields.SHOW)?.textOrNull),
        openedDay = fields?.get(DoneTodayFields.OPENED_DAY)?.longOrNull,
    )

    fun view(setting: DoneTodaySetting, today: Long): DoneTodayChoiceView =
        DoneTodayChoiceView(setting.show, openToday = setting.openedDay == today)

    /**
     * The folded line: "2 done today ›" closed, "2 done today · hide" open; null when nothing is done yet or the choice
     * isn't Folded.
     */
    fun foldLine(doneToday: List<Task>, view: DoneTodayChoiceView): String? {
        if (view.show != DoneShow.FOLDED) return null
        val label = TodayTickRules.label(doneToday) ?: return null
        return if (view.openToday) "$label · hide" else "$label ›"
    }

    /** The heading over the rows: Shown keeps "2 done today"; Folded's own line already says it; Hidden has none. */
    fun heading(doneToday: List<Task>, view: DoneTodayChoiceView): String? =
        if (view.show == DoneShow.SHOWN) TodayTickRules.label(doneToday) else null

    /** The rows Today draws (latest first), [more] true once "+3 more done" was tapped; empty when they're not shown. */
    fun rows(doneToday: List<Task>, view: DoneTodayChoiceView, more: Boolean = false): List<Task> =
        if (view.rowsShown) TodayTickRules.shown(doneToday, more) else emptyList()

    /** "+3 more done" under the rows, only while they're shown. */
    fun moreLine(doneToday: List<Task>, view: DoneTodayChoiceView, more: Boolean = false): String? =
        if (view.rowsShown) TodayTickRules.moreLine(doneToday, more) else null

    /** The fold line said, for a screen reader: what a tap does. */
    fun foldLabel(doneToday: List<Task>, view: DoneTodayChoiceView): String =
        "${doneToday.size} done today, " + if (view.openToday) "tap to hide them" else "tap to show them"

    /** What opening or closing the fold writes: today's day open, or nothing (closed). */
    fun openedDay(open: Boolean, today: Long): Long? = if (open) today else null
}

/** The choice, synced (one entity, id [DoneTodayRules.ENTITY_ID]). */
class DoneTodaySettings(private val replica: Replica) {
    fun setting(): DoneTodaySetting = DoneTodayRules.read(replica.entity(EntityTypes.CONTEXT_MODE, DoneTodayRules.ENTITY_ID)?.fields)

    /** False (nothing written) when it's already [show]. */
    fun setShow(show: DoneShow): Boolean {
        val exists = replica.entity(EntityTypes.CONTEXT_MODE, DoneTodayRules.ENTITY_ID) != null
        if (exists && setting().show == show) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, DoneTodayRules.ENTITY_ID, mapOf(DoneTodayFields.SHOW to show.code.fv()))
        return true
    }

    /** Opens the fold for [today] or closes it; false when nothing changes. */
    fun setOpen(open: Boolean, today: Long): Boolean {
        val day = DoneTodayRules.openedDay(open, today)
        val now = setting().openedDay
        if ((now == today) == open && (open || now == null)) return false
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, DoneTodayRules.ENTITY_ID,
            mapOf(DoneTodayFields.OPENED_DAY to day.fv()),
        )
        return true
    }
}
