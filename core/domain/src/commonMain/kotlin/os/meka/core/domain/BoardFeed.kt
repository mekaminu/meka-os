package os.meka.core.domain

/** One day he can send something to on the Board: [label] on the chip ("Today", "Tomorrow", "Fri", "Mon 26"). */
data class FeedDay(val day: Long, val label: String, val spoken: String)

/**
 * Week board, item (5) part 1 (Meka, 2026-10-19 20:36: "emails that need a reply … can be sent to a day as tasks"):
 * **To a day…** on an email in From your mail unfolds a row of days (Today, Tomorrow, the rest of this week by name, and
 * into next week until there are five); his tap makes the email's task exactly like Task (titled from the subject, the
 * email archived at the next check) and puts it on that day's Board column, last in his order, with Undo. Nothing is
 * sent or written anywhere but his own tasks.
 */
object BoardFeedRules {
    const val TO_DAY = "To a day…"
    const val PICK = "Which day?"
    /** At least this many chips, even late in the week. */
    const val MIN_DAYS = 5

    /** The chips from local [today]: every day to this Sunday, then next week's (labelled with the date) until [MIN_DAYS]. */
    fun days(today: Long): List<FeedDay> {
        val sunday = today + (7 - CivilDate.isoDayOfWeek(today))
        val last = maxOf(sunday, today + MIN_DAYS - 1)
        return (today..last).map { d ->
            val wd = LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(d) - 1]
            val label = when {
                d == today -> "Today"
                d == today + 1 -> "Tomorrow"
                d <= sunday -> wd
                else -> "$wd ${CivilDate.fromEpochDay(d).day}"
            }
            FeedDay(d, label, "On ${if (d <= today + 1) label.lowercase() else CivilDate.longLabel(d)}")
        }
    }

    /** The day a chip asks for, never before [today] (a chip left open past midnight means today). */
    fun dayFor(chosen: Long, today: Long): Long = maxOf(chosen, today)

    /** "Task “Book MOT” on Fri 23 Oct · email archived at Navilife's next check" (Today / Tomorrow by name). */
    fun mailLine(title: String, day: Long, today: Long, emails: Int = 1): String {
        val t = TaskTitleRules.display(title)
        val on = when (day) {
            today -> "for today"
            today + 1 -> "for tomorrow"
            else -> "on ${CivilDate.shortLabel(day)}"
        }
        val archived = if (emails < 2) "email archived" else "$emails emails archived"
        return "Task “$t” $on · $archived at Navilife's next check"
    }

    /** What a screen reader says for the To a day… answer on an email. */
    fun spoken(subject: String): String = "Put $subject on a day of your week as a task, and archive the email"

    // ---- Item (5) part 2: a Discord post to a day (a task to look at it, never an order) ----

    /**
     * "Idea on Fri 23 Oct · a task to look at it, nothing is traded" (for today / for tomorrow by name). The task's title
     * is the post's idea title ([DiscordRules.ideaTitle]); its words stay Discord's (untrusted, ADR-006).
     */
    fun ideaLine(day: Long, today: Long): String {
        val on = when (day) {
            today -> "for today"
            today + 1 -> "for tomorrow"
            else -> "on ${CivilDate.shortLabel(day)}"
        }
        return "Idea $on · a task to look at it, nothing is traded"
    }

    /** Undo after To a day… on a post: the task leaves the Board and the post reads "Keep as idea" again. */
    const val IDEA_UNDONE = "Taken off your week"

    /** What a screen reader says for To a day… under a Discord post. */
    fun ideaSpoken(author: String): String =
        "Put $author's post on a day of your week as a task to look at. A note only, nothing is traded"

    // ---- Item (5) part 3: an overdue or due-today card in Needs you to a later day ----

    /** Only a task's overdue or due-today card offers To a day… (a conflict is chosen first; the others aren't tasks). */
    fun canSendToDay(card: DecisionCard): Boolean =
        card.taskId != null && (card.kind == DecisionKind.OVERDUE || card.kind == DecisionKind.DUE_TODAY)

    /** The chips under such a card: from tomorrow on, since Done (or the Board's Today column) covers today. */
    fun needsDays(today: Long): List<FeedDay> = days(today).drop(1)

    /** The day a Needs you chip asks for: never today or before (a chip left open past midnight means tomorrow). */
    fun needsDayFor(chosen: Long, today: Long): Long = maxOf(chosen, today + 1)

    /**
     * "Moved “Pay council tax” to Fri 23 Oct · still due today" (to tomorrow by name). The due date is a deadline and
     * stays; the line says so when the task now sits after it.
     */
    fun needsLine(title: String, day: Long, dueDay: Long?, today: Long): String {
        val t = TaskTitleRules.display(title)
        val to = if (day == today + 1) "tomorrow" else CivilDate.shortLabel(day)
        val due = when {
            dueDay == null || dueDay >= day -> ""
            dueDay == today -> " · still due today"
            dueDay == today - 1 -> " · still due yesterday"
            else -> " · still due ${CivilDate.shortLabel(dueDay)}"
        }
        return "Moved “$t” to $to$due"
    }

    /** What a screen reader says for To a day… on a Needs you card. */
    fun needsSpoken(title: String): String = "Put $title on a later day of your week. Its due date stays"

    /** A card whose task can't go to another day (finished meanwhile, or not on the Board). */
    const val CANT_MOVE = "That one can't go to another day"

    // ---- Item (5) part 4: a school date or a family date (a birthday, an anniversary) to a day ----

    /**
     * The chips To a day… offers under a dated thing on [date] (a school trip, a birthday): today's chips up to and
     * including that day, since getting ready for it after it is no use. None once it has passed.
     */
    fun dateDays(today: Long, date: Long): List<FeedDay> = if (date < today) emptyList() else days(today).filter { it.day <= date }

    /** The day a chip asks for: never before today (a chip left open past midnight), never after the date itself. */
    fun dateDayFor(chosen: Long, today: Long, date: Long): Long = minOf(maxOf(chosen, today), maxOf(date, today))

    /** "Ready for Trip to the zoo · Alex · Fri 20 Nov" (a date for both children names no one). */
    fun schoolTitle(title: String, who: String, date: Long): String =
        listOfNotNull("Ready for ${title.trim()}", who.trim().takeIf { it.isNotEmpty() }, CivilDate.shortLabel(date)).joinToString(" · ")

    /** The school task's notes: where it came from. */
    fun schoolNotes(date: Long): String = "From School · the date is ${CivilDate.longLabel(date)}"

    /** "Gift for Mum's birthday · Sat 31 Oct". */
    fun giftTitle(occasion: String, date: Long): String = "Gift for ${occasion.trim()} · ${CivilDate.shortLabel(date)}"

    /** The gift task's notes: the order-by day and his ideas, so the task holds what Gifts knows. */
    fun giftNotes(orderBy: Long, today: Long, ideas: List<String>): String = listOfNotNull(
        if (orderBy >= today) "Order by ${CivilDate.longLabel(orderBy)} for delivery in time" else "The order-by day has passed",
        ideas.map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Ideas: "),
        "From Gifts",
    ).joinToString("\n")

    /** "Task “Ready for Trip to the zoo · Alex · Fri 20 Nov” on Thu 19 Nov · on your Board" (today / tomorrow by name). */
    fun dateLine(title: String, day: Long, today: Long): String {
        val on = when (day) {
            today -> "for today"
            today + 1 -> "for tomorrow"
            else -> "on ${CivilDate.shortLabel(day)}"
        }
        return "Task “${TaskTitleRules.display(title)}” $on · on your Board"
    }

    /**
     * The id of the task To a day… makes for [kind] ("s" school, "g" gift) [sourceId] on [date], so the Fold and the
     * Mac tapping at once make one; [n] counts past ones Undo removed (a removed id never comes back, ADR-003).
     */
    fun dateTaskId(kind: String, sourceId: String, date: Long, n: Int = 1): String =
        "tb$kind$sourceId-$date" + (if (n > 1) "-$n" else "")

    /** What a screen reader says for To a day… on a school date or a birthday. */
    fun dateSpoken(title: String): String = "Put getting ready for $title on a day of your week as a task"

    const val DATE_ALREADY = "Already on your week"
    const val DATE_PASSED = "That date has passed"
    const val DATE_UNKNOWN = "That date isn't there any more"
}
