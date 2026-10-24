package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Plan your week, part 2 (Week board item (6); Meka 2026-10-19 20:36): the Sunday nudge and "plan my week" said or
 * typed to Obi. Non-AI and pure.
 *
 * - **When:** Sunday from the week ahead's time (18:00 unless he changed it in Week ahead) until Monday 12:00, for the
 *   week starting that Monday; none when the week ahead is off.
 * - **The card:** in Needs you, "Plan next week" (Monday morning: "Plan this week") with why ("Sunday evening · 3 tasks
 *   to place · about 5 minutes"), Plan now / Later / Open. Plan now and Open open the guided flow; Later sets it aside on
 *   that screen. Only when there is something to place: a week with nothing left over has nothing to decide.
 * - **Gone for the week** once a plan for that week is accepted on any device ([PlanWeekMarks], synced), and back if
 *   that Accept is undone.
 * - **"Plan my week"** ([TalkOpenRules], [TalkPlace.PLAN_WEEK]) opens the same flow from Talk and the hello; typed in
 *   Ask it is answered here with the suggested spread and where the flow is ([answer]). Nothing moves from the words.
 */
object PlanWeekNudgeRules {
    const val CARD_ID = "plan_week"
    const val ENTITY_ID = "plan_week"
    const val FIELD_DONE_WEEK = "doneWeek"
    const val YES = "Plan now"
    const val LATER = "Later"
    const val OPEN = "Open"
    /** The card leaves at Monday noon if he hasn't planned. */
    const val END_MIN = 12 * 60
    const val ABOUT = "about 5 minutes"

    /** What Obi says before the flow opens (Talk and the hello). */
    const val OPENING = "Here's your week. Nothing moves till you accept the plan."

    /** What the conversation remembers. */
    const val OPENED = "Opened Plan your week."

    /** The Monday of the week the nudge is for, or null outside Sunday from [setting]'s time to Monday noon (or off). */
    fun weekDue(nowMs: Long, cal: LocalCalendar, setting: WeekAheadSetting): Long? {
        if (!setting.on) return null
        val today = cal.epochDayOf(nowMs)
        val minute = cal.minuteOfDay(nowMs)
        return when (CivilDate.isoDayOfWeek(today)) {
            7 -> if (minute >= setting.minute) today + 1 else null
            1 -> if (minute < END_MIN) today else null
            else -> null
        }
    }

    /**
     * The Needs you card, or null: outside [weekDue], when [doneWeek] is that week, when [plan] is for another week, or
     * when it has nothing to place.
     */
    fun card(plan: WeekPlan?, nowMs: Long, cal: LocalCalendar, setting: WeekAheadSetting, doneWeek: Long?): DecisionCard? {
        val week = weekDue(nowMs, cal, setting) ?: return null
        if (doneWeek == week || plan == null || plan.weekStart != week || plan.rows.isEmpty()) return null
        val sunday = CivilDate.isoDayOfWeek(cal.epochDayOf(nowMs)) == 7
        val why = listOf(if (sunday) "Sunday evening" else "Monday morning", plan.leftLine, ABOUT).joinToString(" · ")
        return DecisionCard(
            id = CARD_ID, kind = DecisionKind.PLAN_WEEK, title = plan.title, why = why, taskId = null,
            yesLabel = YES, laterLabel = LATER, openLabel = OPEN,
            yes = DecisionEffect.OPEN_PLAN_WEEK, later = DecisionEffect.SET_ASIDE, open = DecisionEffect.OPEN_PLAN_WEEK,
            urgent = false,
        )
    }

    /** "Plan my week" and its kin, typed in Ask (the same words Talk opens the flow with). */
    fun isRequest(q: String): Boolean = TalkOpenRules.request(q, setOf(TalkPlace.PLAN_WEEK)) == TalkPlace.PLAN_WEEK

    /** Where the flow is on this device. */
    fun where(mac: Boolean): String =
        if (mac) "Calendar → Board → ${PlanWeekRules.OPEN} (⇧⌘P)" else "Calendar → Board → ${PlanWeekRules.OPEN}"

    /**
     * Typed "plan my week": the plan in a line and where to finish it. "Plan next week, 26 Oct – 1 Nov: 3 tasks to
     * place, suggested 3 tasks over 2 days · office evenings kept light. Change any day and accept in Calendar → Board →
     * Plan your week; nothing moves till then."
     */
    fun answer(plan: WeekPlan, mac: Boolean): String {
        val head = "${plan.title}, ${plan.range}"
        val body = if (plan.rows.isEmpty()) plan.leftLine.replaceFirstChar { it.lowercaseChar() }
        else "${plan.leftLine.replaceFirstChar { it.lowercaseChar() }}, suggested ${plan.spreadLine}"
        val tail = if (plan.rows.isEmpty()) "See what's fixed and your goals in ${where(mac)}."
        else "Change any day and accept in ${where(mac)}; nothing moves till then."
        return "$head: $body. $tail"
    }

    fun read(fields: Map<String, FieldValue>?): Long? =
        fields?.get(FIELD_DONE_WEEK)?.longOrNull?.takeIf { it >= 0 }
}

/** The week last planned with Accept plan, synced so the nudge leaves on every device. */
class PlanWeekMarks(private val replica: Replica) {
    fun doneWeek(): Long? = PlanWeekNudgeRules.read(replica.entity(EntityTypes.CONTEXT_MODE, PlanWeekNudgeRules.ENTITY_ID)?.fields)

    /** Marks [week] planned (null clears it). Returns what it was, for Undo. */
    fun mark(week: Long?): Long? {
        val before = doneWeek()
        if (before == week) return before
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, PlanWeekNudgeRules.ENTITY_ID,
            mapOf(PlanWeekNudgeRules.FIELD_DONE_WEEK to (week ?: -1L).fv()),
        )
        return before
    }
}
