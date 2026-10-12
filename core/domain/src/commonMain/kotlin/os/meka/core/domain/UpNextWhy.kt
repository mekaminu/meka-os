package os.meka.core.domain

/**
 * Task titles as screens show them (Fold review 2026-10-12, polish 6): the first letter capitalised ("renew passport"
 * reads "Renew passport"), stored as typed. Non-AI, pure.
 */
object TaskTitleRules {
    /**
     * "renew passport" → "Renew passport"; a title starting with anything but a lower-case letter is unchanged, and so
     * is a name written with a capital second letter ("iPhone case", "eBay return").
     */
    fun display(title: String): String {
        val first = title.firstOrNull() ?: return title
        if (!first.isLowerCase() || title.getOrNull(1)?.isUpperCase() == true) return title
        return first.uppercaseChar() + title.substring(1)
    }
}

/**
 * Why an anytime task is Up next (Fold review 2026-10-12, polish 6: "Up next under an anytime task says why it's next
 * — carried over twice · on this week's goal — instead of Anytime today"). Non-AI, pure. At most [MAX_PARTS] reasons,
 * most pressing first: overdue, due today at a time, on one of this week's goals, carried over from earlier days.
 * Null when there is no reason to give (the card then says "Anytime today").
 */
object UpNextWhyRules {
    const val MAX_PARTS = 2

    /**
     * [today] the local day; [weekGoals] this week's open goals (goal id → title); [minuteOf] reads a local minute;
     * [dayOf] a local epoch day.
     */
    fun why(
        t: Task, today: DayWindow, nowMs: Long, weekGoals: Map<String, String>,
        minuteOf: (Long) -> Int, dayOf: (Long) -> Long,
    ): String? {
        val parts = buildList {
            val due = t.dueAtMs
            when {
                due != null && due < nowMs -> add("Overdue")
                due != null && due < today.endMs -> add("Due ${LocalClock.formatMinute(minuteOf(due))}")
            }
            if (t.goalId != null && weekGoals.containsKey(t.goalId)) add("On this week's goal")
            if (t.createdAtMs > 0) carriedLabel(today.epochDay - dayOf(t.createdAtMs), t.isRepeating)?.let { add(it) }
        }
        return parts.take(MAX_PARTS).joinToString(" · ").ifEmpty { null }
    }

    /**
     * "Carried over from yesterday" · "Carried over 3 days" · "Open over a month" (from [MONTH_DAYS]); null on its
     * first day and for a repeating task.
     */
    fun carriedLabel(days: Long, repeating: Boolean): String? = when {
        repeating || days < 1 -> null
        days == 1L -> "Carried over from yesterday"
        days >= MONTH_DAYS -> "Open over a month"
        else -> "Carried over $days days"
    }

    const val MONTH_DAYS = 30L
}
