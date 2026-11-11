package os.meka.core.domain

/**
 * "Handle her requests for me" (build plan, Take first, Meka 2026-11-11 22:15), slice 1. Non-AI and pure.
 *
 * A request from his partner (whoever About you names as his partner; never a name in code) is acted on at once, with
 * no tap, while About you → Handle her requests for me is on (on by default): the task goes on its day, a
 * work-from-home day is marked, a reminder is set, and for a work-from-home day the steps to get ready are dated ahead
 * of it ("Tell your manager", "Get approval", "Put the request in at work"). Then he is told what was done: one
 * notification and a "Done for you" row at the top of Needs you, with Undo (which removes everything it made) and
 * Change.
 *
 * **Limits (hard rules stay):** only his own tasks, reminders and home days are made. Nothing replies to her, messages
 * his manager, submits anything at work or accepts on his behalf. A request that isn't certain (no clear day, a kind
 * that isn't a task, reminder, event or work-from-home day, or a day already gone) stays as today's Add · Change · No
 * card rather than being guessed. Untrusted content never raises its own autonomy (ADR-006): what can be done here is
 * fixed by these rules, not by the message.
 */
data class PrepStep(val title: String, val day: Long, val remindDay: Long?, val remindMinute: Int?)

/** What handling one request makes: the main task (null for a work-from-home day), the home day and the prep steps. */
data class HandlePlan(
    val task: HandleTask?,
    val homeDay: Long?,
    val steps: List<PrepStep>,
)

/** The request's own task: its title, its day and time, and when it reminds (day and minute; null: no reminder). */
data class HandleTask(val title: String, val day: Long, val minute: Int?, val remindDay: Long?, val remindMinute: Int?)

/**
 * Slice 2: one handled request as the morning brief says it ([HandleForMeRules.briefSentences]): who asked, the home
 * day or the task's title and day, and the steps still open that are due by today.
 */
data class DoneForYouBrief(
    val who: String,
    val atMs: Long,
    /** The work-from-home day it marked, or null. */
    val homeDay: Long?,
    /** The request's own task, while it is still on his list (null for a work-from-home day, or once it's gone or done). */
    val title: String?,
    val day: Long?,
    /** The prep steps still open that are due today or earlier, in order. */
    val openToday: List<String>,
)

/** One "Done for you" row in Needs you (both apps). */
data class DoneForYou(
    /** The request card's id. */
    val id: String,
    /** "From Ada · 22:14". */
    val from: String,
    /** "Work from home Thu 12 Nov · added to Thursday, 3 steps for today, reminder 16:00". */
    val line: String,
    /** One sentence for TalkBack / VoiceOver. */
    val spoken: String,
    /** When it was handled. */
    val atMs: Long,
    /** Everything it made, for Undo: the tasks (the request's own first, then the steps) and the home day. */
    val taskIds: List<String>,
    val homeDay: Long?,
    /** The title of the task Change opens (the Mac finds it by its title and id). */
    val changeTitle: String? = null,
    /** Who asked, as the notification named her ("Ada", "Ada Smith"); said in the morning brief. */
    val person: String = "",
) {
    val undoLabel: String get() = HandleForMeRules.UNDO
    val changeLabel: String? get() = if (taskIds.isEmpty()) null else HandleForMeRules.CHANGE
    /** The task Change opens: the request's own, else the first step. */
    val changeTaskId: String? get() = taskIds.firstOrNull()
}

object HandleForMeRules {
    const val TITLE = "Handle her requests for me"
    const val ON = "On · what she asks for is added at once, with reminders and the steps to get ready; you're told what was done"
    const val OFF = "Off · her requests wait in Needs you for you to add"
    const val HEAD = "Done for you"
    const val UNDO = "Undo"
    const val CHANGE = "Change"
    const val CLEAR = "Clear"
    const val UNDONE_LINE = "Undone · everything it added is gone"

    /** A step's reminder: 16:00 on its day. */
    const val STEP_REMIND_MINUTE = 16 * 60
    /** A task with no time of its own reminds at 09:00 on its day. */
    const val TASK_REMIND_MINUTE = 9 * 60
    /** An event's task reminds this long before it starts. */
    const val EVENT_LEAD_MIN = 60
    /** A reminder is at least this far ahead of now, on the half hour. */
    const val LEAD_MIN = 30
    /** No reminder rings later than this; past it, the next morning at [MORNING_MINUTE]. */
    const val LATEST_MINUTE = 21 * 60
    const val MORNING_MINUTE = 8 * 60
    /** A Done for you row stays this long after it was handled (Clear takes it sooner). */
    const val SHOWN_MS = 36 * 60 * 60_000L
    /** Its heads-up is stale after this. */
    const val NOTICE_STALE_MS = 3 * 60 * 60_000L

    /** The steps to get ready for a work-from-home day (Meka's words, 2026-11-11). */
    val WORK_FROM_HOME_STEPS = listOf("Tell your manager", "Get approval", "Put the request in at work")

    /**
     * Whether a message's sender is his partner: the notification's name is About you's partner name, or starts with
     * her first name ("Ada", "Ada Smith", "Ada 💛"). Case and spacing don't matter. False with no partner named.
     */
    fun isPartner(person: String, about: AboutYou): Boolean {
        val name = about.partnerName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val who = People.key(person)
        if (who.isEmpty() || who.startsWith("tel:")) return false
        if (who == People.key(name)) return true
        val first = People.key(name).substringBefore(' ')
        return first.length >= 2 && who.substringBefore(' ') == first
    }

    /** Whether [person]'s requests are handled for him: his partner, watched, and the switch on. */
    fun handles(person: String, about: AboutYou): Boolean = about.partnerWatch && about.partnerHandle && isPartner(person, about)

    /**
     * Whether [p] is certain enough to act on without asking: a task, reminder, event or work-from-home day with a day
     * that hasn't gone. Anything else (no clear day, a reply, a renewal, a booking…) stays a card.
     */
    fun certain(p: RequestProposal, today: Long): Boolean =
        p.kind in HANDLED_KINDS && p.day != null && p.day >= today

    private val HANDLED_KINDS = setOf(RequestKind.TASK, RequestKind.REMINDER, RequestKind.EVENT, RequestKind.WORK_FROM_HOME)

    /** The prep steps a kind of request has (only a work-from-home day for now). */
    fun stepsFor(kind: RequestKind): List<String> = if (kind == RequestKind.WORK_FROM_HOME) WORK_FROM_HOME_STEPS else emptyList()

    /**
     * The day the prep steps land on for a request on [day]: today when it is today or tomorrow, otherwise the last
     * working day before it ([isWorkDay]), never before today (today when none is found in the week before).
     */
    fun stepDay(day: Long, today: Long, isWorkDay: (Long) -> Boolean): Long {
        if (day <= today + 1) return today
        var d = day - 1
        while (d > today && d >= day - 7) {
            if (isWorkDay(d)) return d
            d--
        }
        return today
    }

    /**
     * When a reminder wanted at [minute] on [day] rings: then, when that is at least [LEAD_MIN] ahead; else, on today,
     * the next half hour at least [LEAD_MIN] ahead; past [LATEST_MINUTE], [MORNING_MINUTE] the next day, as long as
     * that is no later than [lastDay]. Null when nothing fits (the day itself has gone by).
     */
    fun remindAt(day: Long, minute: Int, today: Long, nowMinute: Int, lastDay: Long): Pair<Long, Int>? {
        if (day < today) return null
        if (day > today || minute >= nowMinute + LEAD_MIN) return day to minute
        val next = ((nowMinute + LEAD_MIN + 29) / 30) * 30
        if (next <= LATEST_MINUTE) return day to next
        return if (today + 1 <= lastDay) (today + 1) to MORNING_MINUTE else null
    }

    /**
     * What handling [p] (already [certain]) makes, on [today] at [nowMinute]: a work-from-home day is marked as one and
     * gets its steps (each reminding at 16:00 on the step day, or the next sensible time); a task, reminder or event is
     * a task on its day (an event at its time), reminding at its time, an hour before an event, or 09:00 on its day.
     */
    fun plan(p: RequestProposal, today: Long, nowMinute: Int, isWorkDay: (Long) -> Boolean): HandlePlan {
        val day = p.day ?: today
        val names = stepsFor(p.kind)
        val steps = if (names.isEmpty()) emptyList() else {
            val sd = stepDay(day, today, isWorkDay)
            val r = remindAt(sd, STEP_REMIND_MINUTE, today, nowMinute, day)
            // Too late tonight for a step due today: it moves to the morning it reminds on (never past the day itself).
            val on = r?.first?.coerceAtMost(day) ?: sd
            names.map { PrepStep(it, on, r?.first, r?.second) }
        }
        if (p.kind == RequestKind.WORK_FROM_HOME) return HandlePlan(null, day, steps)
        val wanted = when {
            p.kind == RequestKind.EVENT && p.minute != null -> (p.minute - EVENT_LEAD_MIN).coerceAtLeast(0)
            p.minute != null -> p.minute
            else -> TASK_REMIND_MINUTE
        }
        val r = remindAt(day, wanted, today, nowMinute, day)
            // An event's reminder never rings after it has started.
            ?.takeUnless { (rd, rm) -> p.kind == RequestKind.EVENT && p.minute != null && rd == day && rm > p.minute }
        return HandlePlan(HandleTask(p.title, day, p.minute, r?.first, r?.second), null, steps)
    }

    /** "Today", "Tomorrow", "Thursday" within the week ahead, else "Thu 19 Nov". */
    fun dayWord(day: Long, today: Long): String = when {
        day == today -> "Today"
        day == today + 1 -> "Tomorrow"
        day in today + 2..today + 6 -> DAY_LONG[CivilDate.isoDayOfWeek(day) - 1]
        else -> CivilDate.shortLabel(day)
    }

    private val DAY_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    /**
     * The row's line: "Work from home Thu 12 Nov · added to Thursday, 3 steps for today, reminder 16:00", "“Pick up
     * the dry cleaning” · Tomorrow · added, reminder Tomorrow 09:00".
     */
    fun line(p: RequestProposal, plan: HandlePlan, today: Long): String {
        val day = p.day ?: today
        val head = if (p.kind == RequestKind.WORK_FROM_HOME) "Work from home ${CivilDate.shortLabel(day)}"
        else "“${p.title}” · ${TaskWhenRules.label(day, p.minute, today)}"
        val parts = mutableListOf("added to ${dayWord(day, today)}")
        plan.steps.firstOrNull()?.let { s ->
            val n = plan.steps.size
            parts += "$n ${if (n == 1) "step" else "steps"} for ${dayWord(s.day, today).let { if (it == "Today" || it == "Tomorrow") it.lowercase() else it }}"
        }
        val remind = plan.steps.firstOrNull()?.let { s -> s.remindDay?.let { d -> d to s.remindMinute!! } }
            ?: plan.task?.let { t -> t.remindDay?.let { d -> d to t.remindMinute!! } }
        remind?.let { (d, m) ->
            parts += "reminder " + (if (d == today) "" else dayWord(d, today) + " ") + TaskWhenRules.timeLabel(m)
        }
        return "$head · ${parts.joinToString(", ")}"
    }

    /** "From Ada · 22:14" (her first name as the notification named her, at most 40 characters). */
    fun from(person: String, atMinute: Int): String = "From ${person.trim().take(40)} · ${TaskWhenRules.timeLabel(atMinute)}"

    fun spoken(person: String, line: String): String = "Done for you, from ${person.trim().take(40)}: $line"

    /** The rows still shown at [nowMs]: not cleared or undone, from the last [SHOWN_MS]; newest first. */
    fun shown(rows: List<DoneForYou>, nowMs: Long): List<DoneForYou> =
        rows.filter { it.atMs >= nowMs - SHOWN_MS }.sortedByDescending { it.atMs }

    /** The morning brief names at most this many handled requests; the rest are counted. */
    const val MAX_BRIEF = 2
    const val BRIEF_HEAD = "Done for you"

    /**
     * Slice 2 (Meka 2026-11-11: "obviously it will tell me what it's done"): the morning brief's sentences for what was
     * handled for him since [sinceMs] (the start of yesterday, so last night's requests are said this morning), oldest
     * first, said straight after the requests waiting on him: "Done for you: Alex asked you to work from home tomorrow,
     * and it's marked as a home day. Still to do today: tell your manager, get approval and put the request in at work."
     * · "Alex asked for “Pick up the dry cleaning”, and it's on your list for Thursday." A request whose day has gone,
     * or that left nothing on his list or calendar, isn't said. Never the message's own words: only its title.
     */
    fun briefSentences(items: List<DoneForYouBrief>, today: Long, sinceMs: Long): List<String> {
        val said = items
            .filter { it.atMs >= sinceMs }
            .filter { b -> (b.homeDay != null && b.homeDay >= today) || (b.title != null && (b.day ?: today) >= today) || b.openToday.isNotEmpty() }
            .sortedBy { it.atMs }
        if (said.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        said.take(MAX_BRIEF).forEachIndexed { i, b ->
            val who = b.who.trim().take(40).ifEmpty { "Someone" }
            val what = when {
                b.homeDay != null && b.homeDay >= today -> "$who asked you to work from home ${daySaid(b.homeDay, today)}, and it's marked as a home day"
                b.title != null -> {
                    val day = b.day ?: today
                    "$who asked for “${b.title.trim()}”, and it's on " + if (day == today) "today's list" else "your list for ${daySaid(day, today).removePrefix("on ")}"
                }
                else -> "$who asked you to work from home"
            }
            out += (if (i == 0) "$BRIEF_HEAD: " else "") + what + "."
            if (b.openToday.isNotEmpty()) {
                val steps = b.openToday.mapIndexed { j, t -> if (j == 0) t.trim() else t.trim().replaceFirstChar { it.lowercase() } }
                out += "Still to do today: " + listing(steps) + "."
            }
        }
        if (said.size > MAX_BRIEF) {
            val n = said.size - MAX_BRIEF
            out += "And $n more ${if (n == 1) "request" else "requests"} done for you, in Needs you."
        }
        return out
    }

    /** "today", "tomorrow", "on Thursday" within the week ahead, else "on Thu 19 Nov". */
    fun daySaid(day: Long, today: Long): String = when (val w = dayWord(day, today)) {
        "Today" -> "today"
        "Tomorrow" -> "tomorrow"
        else -> "on $w"
    }

    private fun listing(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    /** One heads-up per row as it is handled: "Done for you · from Ada" · the line. Never the message's text. */
    fun notices(rows: List<DoneForYou>): List<Notice> = rows.map { r ->
        Notice(
            key = "handled:${r.id}", source = NoticeSource.REQUEST, tier = NoticeTier.HEADS_UP,
            title = "$HEAD · ${r.from.removePrefix("From ").substringBefore(" · ").let { "from $it" }}",
            text = r.line, atMs = r.atMs, target = NoticeTarget.NEEDS_YOU,
            expiresAtMs = r.atMs + NOTICE_STALE_MS,
        )
    }
}
