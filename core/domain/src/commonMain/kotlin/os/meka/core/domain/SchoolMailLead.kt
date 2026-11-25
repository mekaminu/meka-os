package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * School emails first, slice 4d (Meka 2026-11-24 17:38; the board's "(4) … the lead times are settings"): **what each
 * school event added for him makes is his choice**. Settings → Family → School emails has four switches, all on until he
 * turns one off:
 *
 * - **Task on the day** (at the event's time), with **Reminder 3 days before** (09:00) riding on it;
 * - **Task the day before** ("Get ready for …"), with **Reminder the day before** (09:00 that day) riding on it.
 *
 * A reminder rides on its task, so with the task off its row says so and can't be switched. The School line (Today's
 * header, Kids' week, School's 19:00 heads-up the evening before) and the calendar entry stay whatever he picks, and
 * deadlines and dated asks keep their own task and reminder. A change applies to the next school dates; ones already added
 * stay as they were made (each has Undo and Change).
 *
 * Stored on the same `context_mode` entity as the on/off switch ([SchoolAutoRules.ENTITY_ID]), one Bool per switch, LWW,
 * so a change on the Fold is the Mac's too. Absent = on.
 */

/** The four switches; all on by default. */
data class SchoolLead(
    val dayTask: Boolean = true,
    val early: Boolean = true,
    val prepTask: Boolean = true,
    val dayBefore: Boolean = true,
)

/** One switch row on Family's School emails: [enabled] false when its task is off (a reminder rides on it) or the whole thing is off. */
data class SchoolLeadRow(val key: String, val title: String, val line: String, val on: Boolean, val enabled: Boolean)

/** Family's School emails block: its title, a line under it, and the rows. */
data class SchoolLeadView(val title: String, val line: String, val rows: List<SchoolLeadRow>)

object SchoolLeadRules {
    const val DAY_TASK = "leadDayTask"
    const val EARLY = "leadEarly"
    const val PREP_TASK = "leadPrepTask"
    const val DAY_BEFORE = "leadDayBefore"
    val KEYS = listOf(DAY_TASK, EARLY, PREP_TASK, DAY_BEFORE)

    const val TITLE = "School emails"
    const val ON_LINE = "What Navilife makes for each school event it adds for you. A change applies to the next ones; " +
        "those already added keep theirs."
    const val OFF_LINE = "“School events from the school's emails” is off in What Navilife may do, so each school date " +
        "waits for your Add."

    fun read(fields: Map<String, FieldValue>?): SchoolLead {
        fun on(k: String) = fields?.get(k)?.boolOrNull != false
        return SchoolLead(dayTask = on(DAY_TASK), early = on(EARLY), prepTask = on(PREP_TASK), dayBefore = on(DAY_BEFORE))
    }

    /** The fields one switch writes; null for a key that isn't one of [KEYS]. */
    fun fields(key: String, on: Boolean, nowMs: Long): Map<String, FieldValue>? =
        if (key in KEYS) mapOf(key to on.fv(), SchoolAutoFields.CHANGED_AT to nowMs.fv()) else null

    private fun state(on: Boolean) = if (on) "On" else "Off"

    fun view(s: SchoolLead, autoOn: Boolean): SchoolLeadView {
        fun reminder(key: String, title: String, on: Boolean, carrier: Boolean, carrierName: String, whenText: String) =
            SchoolLeadRow(
                key, title,
                if (!carrier) "Needs $carrierName (it rides on that task)" else state(on) + " · $whenText",
                on = on && carrier, enabled = autoOn && carrier,
            )
        return SchoolLeadView(
            TITLE,
            if (autoOn) ON_LINE else OFF_LINE,
            listOf(
                SchoolLeadRow(DAY_TASK, "Task on the day", state(s.dayTask) + " · at the event's time, in that day's list", s.dayTask, autoOn),
                reminder(EARLY, "Reminder 3 days before", s.early, s.dayTask, "the task on the day", "09:00"),
                SchoolLeadRow(PREP_TASK, "Task the day before", state(s.prepTask) + " · “Get ready for …” with what to bring or pay", s.prepTask, autoOn),
                reminder(DAY_BEFORE, "Reminder the day before", s.dayBefore, s.prepTask, "the task the day before", "09:00"),
            ),
        )
    }

    /** What a switch's tap says. */
    fun switchedLine(key: String, on: Boolean): String {
        val what = when (key) {
            DAY_TASK -> "a task on the day"
            EARLY -> "a reminder 3 days before"
            PREP_TASK -> "a task the day before"
            DAY_BEFORE -> "a reminder the day before"
            else -> return ""
        }
        return (if (on) "The next school events get $what" else "The next school events get no ${what.removePrefix("a ")}")
    }
}
