package os.meka.core.domain

/**
 * The Fasting screen's plan, goal and start as sheets (walk-through week 5, A17: no open chip rows on a page). The plan
 * chip unfolded a row of four plan chips under the header, and the Started and Goal tiles each unfolded a captioned
 * chip row ("Move the start" −1 h · −30 min · +30 min; "Goal" 12 h … 24 h, or 24 h … 7 days on a longer fast). The chip
 * and the tiles stay as the approved design has them; each now opens a sheet of the same choices, the one in force
 * lit, each saying what it does. A choice saves as the chip did and closes the sheet.
 *
 * Reuses Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure; nothing
 * stored. Tracking only: no food, calorie or weight advice.
 */
object FastingChoiceRules {
    const val PLAN_HEAD = "Plan"
    const val GOAL_HEAD = "Goal"
    const val START_HEAD = "Started"

    const val PLAN_TITLE = "Your fasting plan"
    const val GOAL_TITLE = "This fast's goal"
    const val START_TITLE = "Move the start"

    const val PLAN_NOTE = "Your usual fast and eating window. A fast that's running keeps its own goal."
    const val GOAL_NOTE = "Changes this fast's goal only. Your plan stays as it is."
    const val START_NOTE = "Started a little off? This moves when the fast counts from. A start can't be in the future."
    const val REACHED = "Reached already"

    private const val HOUR_MS = 3_600_000L

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")

    // ---- Plan ----

    /** "Plan" · "16:8", spoken as the chip's own opener. */
    fun planRow(plan: FastingPlan): NotifyChoiceRow =
        NotifyChoiceRow(PLAN_HEAD, FastingRules.planLabel(plan), "Fasting plan: ${FastingRules.planLabel(plan)}. Opens its choices")

    /** "Fast 16 h · eat 12:00–20:00". */
    fun planLine(targetHours: Int, eatingStartMin: Int, eatingEndMin: Int): String =
        "Fast $targetHours h · eat ${FastingRules.hm(eatingStartMin)}–${FastingRules.hm(eatingEndMin)}"

    private fun isPreset(plan: FastingPlan, c: FastingPlanChoice) =
        c.targetHours == plan.targetHours && c.eatingStartMin == plan.eatingStartMin && c.eatingEndMin == plan.eatingEndMin

    /** The four presets; a plan set another way (not one of them) leads the sheet, lit. */
    fun planSheet(plan: FastingPlan): NotifyChoiceSheet {
        val own = if (FastingRules.PLAN_CHOICES.none { isPreset(plan, it) })
            listOf(choice(OWN_PLAN, FastingRules.planLabel(plan), planLine(plan.targetHours, plan.eatingStartMin, plan.eatingEndMin), true))
        else emptyList()
        return NotifyChoiceSheet(PLAN_TITLE, PLAN_NOTE, own + FastingRules.PLAN_CHOICES.map { c ->
            choice(c.label, c.label, planLine(c.targetHours, c.eatingStartMin, c.eatingEndMin), isPreset(plan, c))
        })
    }

    /** The preset's index in [FastingRules.PLAN_CHOICES] a choice picks; null when it isn't one or is in force. */
    fun planNamed(plan: FastingPlan, name: String): Int? {
        val i = FastingRules.PLAN_CHOICES.indexOfFirst { it.label == name }
        return i.takeIf { it >= 0 && !isPreset(plan, FastingRules.PLAN_CHOICES[it]) }
    }

    private const val OWN_PLAN = "own"

    // ---- Goal (a fast that's running) ----

    /** "12:05" → 725; null for anything else (a longer fast's goalWhen is "Sat 20:00"). */
    private fun minuteOf(hm: String): Int? {
        val parts = hm.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    /** A fast "until" a moment (not a number of hours). */
    private fun isUntil(cur: FastNow) = cur.extended && cur.title.startsWith("Fast until")

    /** "Ends 14:05 · 4 h 20 m to go", a longer fast "1 d 04 h to go", or [REACHED]. */
    fun goalLine(cur: FastNow, hours: Int, nowMs: Long): String {
        val goal = cur.startedAtMs + hours * HOUR_MS
        if (goal <= nowMs) return REACHED
        val left = "${FastingRules.longDuration(goal - nowMs)} to go"
        val base = if (cur.extended) null else minuteOf(cur.goalWhen)
        return base?.let { "Ends ${FastingRules.hm(it + (hours - cur.targetHours) * 60)} · $left" } ?: left
    }

    /** The daily goals (12 h … 24 h), a longer fast's (24 h … 7 days); a fast "until" a moment leads with its own, lit. */
    fun goalSheet(cur: FastNow, nowMs: Long): NotifyChoiceSheet {
        val until = isUntil(cur)
        val own = if (until) listOf(choice(OWN_GOAL, cur.title.removePrefix("Fast ").replaceFirstChar { it.uppercase() },
            if (cur.reachedGoal) REACHED else "${FastingRules.longDuration(cur.goalAtMs - nowMs)} to go", true)) else emptyList()
        val choices = if (cur.extended) FastingRules.EXTENDED_CHOICES.map { c ->
            choice(c.hours.toString(), c.label, goalLine(cur, c.hours, nowMs), !until && c.hours == cur.targetHours)
        } else FastingRules.TARGET_CHOICES.map { h ->
            choice(h.toString(), "$h h", goalLine(cur, h, nowMs), h == cur.targetHours)
        }
        return NotifyChoiceSheet(GOAL_TITLE, GOAL_NOTE, own + choices)
    }

    /** The hours a goal choice sets; null when it isn't one or is already in force. */
    fun goalNamed(cur: FastNow, name: String): Int? {
        val h = name.toIntOrNull() ?: return null
        val offered = if (cur.extended) FastingRules.EXTENDED_CHOICES.map { it.hours } else FastingRules.TARGET_CHOICES
        if (h !in offered) return null
        return if (h == cur.targetHours && !isUntil(cur)) null else h
    }

    private const val OWN_GOAL = "own"

    // ---- Move the start ----

    /** "an hour", "30 min". */
    private fun span(min: Int): String {
        val a = kotlin.math.abs(min)
        return if (a == 60) "an hour" else if (a >= 60) "${a / 60} h" else "$a min"
    }

    /** "Counts from 02:45 · goal 18:45" (daily), "Counts an hour earlier · goal an hour sooner" (longer fast). */
    fun startLine(cur: FastNow, deltaMinutes: Int): String {
        val goalMin = if (cur.extended) null else minuteOf(cur.goalWhen)
        if (goalMin != null) {
            val startMin = goalMin - cur.targetHours * 60
            return "Counts from ${FastingRules.hm(startMin + deltaMinutes)} · goal ${FastingRules.hm(goalMin + deltaMinutes)}"
        }
        val earlier = deltaMinutes < 0
        return "Counts ${span(deltaMinutes)} ${if (earlier) "earlier" else "later"} · goal ${span(deltaMinutes)} ${if (earlier) "sooner" else "later"}"
    }

    /** Whether a move keeps the start in the past. */
    fun canMove(cur: FastNow, deltaMinutes: Int, nowMs: Long): Boolean = cur.startedAtMs + deltaMinutes * 60_000L <= nowMs

    /** Only the moves this fast can make ([FastingRules.MOVE_START_CHOICES]); none is lit, the start is where it is. */
    fun startSheet(cur: FastNow, nowMs: Long): NotifyChoiceSheet = NotifyChoiceSheet(START_TITLE, START_NOTE,
        FastingRules.MOVE_START_CHOICES.filter { canMove(cur, it, nowMs) }.map { m ->
            choice(m.toString(), FastingRules.moveLabel(m), startLine(cur, m), false)
        })

    /** The minutes a move choice shifts the start by; null when it isn't one or can't be made now. */
    fun startNamed(cur: FastNow, nowMs: Long, name: String): Int? {
        val m = name.toIntOrNull() ?: return null
        return m.takeIf { it in FastingRules.MOVE_START_CHOICES && canMove(cur, it, nowMs) }
    }

    // ---- More options: a fast started earlier, a longer fast (no fast running) ----

    const val EARLIER_HEAD = "Started earlier"
    const val EARLIER_VALUE = "30 min to 3 h ago"
    const val EARLIER_TITLE = "When did you start?"
    const val EARLIER_NOTE = "Starts your fast from then, on your plan's goal."
    const val LONGER_HEAD = "Longer fast"
    const val LONGER_VALUE = "24 h to 7 days"
    const val LONGER_TITLE = "Start a longer fast"

    /** "Started earlier" · "30 min to 3 h ago" ›. */
    fun earlierRow(): NotifyChoiceRow =
        NotifyChoiceRow(EARLIER_HEAD, EARLIER_VALUE, "Started earlier: $EARLIER_VALUE. Opens its choices")

    /** The minutes-ago choices past Now ([FastingRules.STARTED_AGO_CHOICES]). */
    fun earlierMinutes(): List<Int> = FastingRules.STARTED_AGO_CHOICES.filter { it > 0 }

    /** "Goal 16 h · 15 h 30 m to go" for a fast on [plan] started [minutesAgo] ago. */
    fun earlierLine(plan: FastingPlan, minutesAgo: Int): String =
        "Goal ${plan.targetHours} h · ${FastingRules.duration((plan.targetHours * 60L - minutesAgo) * 60_000L)} to go"

    /** "30 min ago" … "3 h ago", each saying its goal and what's left; none lit, nothing is running. */
    fun earlierSheet(plan: FastingPlan): NotifyChoiceSheet = NotifyChoiceSheet(EARLIER_TITLE, EARLIER_NOTE,
        earlierMinutes().map { m -> choice(m.toString(), FastingRules.startedAgoLabel(m), earlierLine(plan, m), false) })

    /** The minutes ago a choice starts the fast from; null when it isn't one. */
    fun earlierNamed(name: String): Int? = name.toIntOrNull()?.takeIf { it in earlierMinutes() }

    /** "Longer fast" · "24 h to 7 days" ›. */
    fun longerRow(): NotifyChoiceRow =
        NotifyChoiceRow(LONGER_HEAD, LONGER_VALUE, "Longer fast: $LONGER_VALUE. Opens its choices")

    /** "Starts now · ends in 36 h"; over the GP line it says so first. */
    private fun longerLine(hours: Long, ms: Long): String {
        val base = "Starts now · ends in ${FastingRules.longDuration(ms)}"
        return if (hours > FastingScreenRules.GP_NOTE_OVER_HOURS) "Check with your GP first · $base" else base
    }

    /**
     * The lengths ([FastingRules.EXTENDED_CHOICES]) then the "Until …" days ([until]); the GP note is the sheet's note,
     * so it's read before any longer fast can be started. Names: "h:<hours>" and "u:<index into until>".
     */
    fun longerSheet(until: List<FastUntilChoice>, nowMs: Long): NotifyChoiceSheet = NotifyChoiceSheet(
        LONGER_TITLE, FastingScreenRules.GP_NOTE,
        FastingRules.EXTENDED_CHOICES.map { c -> choice("h:${c.hours}", c.label, longerLine(c.hours.toLong(), c.hours * HOUR_MS), false) } +
            until.mapIndexed { i, u ->
                val ms = (u.untilMs - nowMs).coerceAtLeast(0)
                choice("u:$i", u.label, longerLine(ms / HOUR_MS, ms), false)
            },
    )

    /** What a longer-fast choice starts: hours, or an until moment. */
    sealed interface LongerPick {
        data class Hours(val hours: Int) : LongerPick
        data class Until(val untilMs: Long) : LongerPick
    }

    /** The fast a longer choice starts; null when it isn't one. */
    fun longerNamed(until: List<FastUntilChoice>, name: String): LongerPick? {
        val n = name.substringAfter(':').toIntOrNull() ?: return null
        return when {
            name.startsWith("h:") -> FastingRules.EXTENDED_CHOICES.firstOrNull { it.hours == n }?.let { LongerPick.Hours(it.hours) }
            name.startsWith("u:") -> until.getOrNull(n)?.let { LongerPick.Until(it.untilMs) }
            else -> null
        }
    }

    /** Swift: the hours a longer choice starts, or null (an until moment, or not a choice). */
    fun longerHours(until: List<FastUntilChoice>, name: String): Int? = (longerNamed(until, name) as? LongerPick.Hours)?.hours

    /** Swift: the moment a longer "Until …" choice runs to, or null. */
    fun longerUntilMs(until: List<FastUntilChoice>, name: String): Long? = (longerNamed(until, name) as? LongerPick.Until)?.untilMs
}
