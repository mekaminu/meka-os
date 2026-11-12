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
data class PrepStep(
    val title: String,
    val day: Long,
    val remindDay: Long?,
    val remindMinute: Int?,
    /** What remains (Take first 2026-11-11): one of Obi's best steps for a kind with no fixed steps, which he can untick. */
    val suggested: Boolean = false,
)

/**
 * One of Obi's suggested steps on a Done for you row (a task it made, still open): a tap unticks it, which deletes that
 * task ([HandleForMeRules.suggestions]).
 */
data class HandleSuggestion(val taskId: String, val title: String, val spoken: String)

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
    /**
     * Slice 3: the words drafted for him to send himself, each copied with one tap and never sent by Navilife: a reply
     * to her, and for a work-from-home day a note to his manager ([HandleForMeRules.drafts]).
     */
    val drafts: List<HandleDraft> = emptyList(),
    /** Obi's suggested steps still on his list, each unticked with one tap ([HandleForMeRules.suggestions]). */
    val suggestions: List<HandleSuggestion> = emptyList(),
) {
    val undoLabel: String get() = HandleForMeRules.UNDO
    val changeLabel: String? get() = if (taskIds.isEmpty()) null else HandleForMeRules.CHANGE
    /** The task Change opens: the request's own, else the first step. */
    val changeTaskId: String? get() = taskIds.firstOrNull()
}

/**
 * Slice 3: one drafted message on a Done for you row. [label] is its pill ("Copy reply to Ada"); a tap copies [text] to
 * the clipboard and shows [COPIED][HandleForMeRules.COPIED]. Navilife never sends it, opens a chat with it or messages
 * anyone (hard rule: personal sending is "ask me, then send" at most, and here he sends it himself).
 */
data class HandleDraft(val label: String, val text: String, val spoken: String)

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

    /** Shown after a draft is copied: he sends it himself. */
    const val COPIED = "Copied · paste it and send it yourself"
    const val MANAGER_LABEL = "Copy note to your manager"
    /** The longest title a draft quotes. */
    const val DRAFT_TITLE_MAX = 80

    /** The steps to get ready for a work-from-home day (Meka's words, 2026-11-11). */
    val WORK_FROM_HOME_STEPS = listOf("Tell your manager", "Get approval", "Put the request in at work")

    /**
     * Whether a message's sender is his partner: the notification's name is About you's partner name, or starts with
     * her first name ("Ada", "Ada Smith", "Ada 💛"). Case and spacing don't matter. False with no partner named.
     */
    fun isPartner(person: String, about: AboutYou): Boolean = isPartnerName(person, about.partnerName)

    /** [isPartner] from the partner's name alone (the Fold's watch list asks it per row). */
    fun isPartnerName(person: String, partnerName: String?): Boolean {
        val name = partnerName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val who = People.key(person)
        if (who.isEmpty() || who.startsWith("tel:")) return false
        if (who == People.key(name)) return true
        val first = People.key(name).substringBefore(' ')
        return first.length >= 2 && who.substringBefore(' ') == first
    }

    /**
     * Whether [person]'s requests are handled for him: his partner, watched, and About you's switch on; or anyone else
     * he turned "Handle their requests for me" on for beside their name ([others], Work mode → Watch for requests
     * from). His partner follows About you's switch only, whatever [others] holds.
     */
    fun handles(person: String, about: AboutYou, others: Set<String> = emptySet()): Boolean {
        if (isPartner(person, about)) return about.partnerWatch && about.partnerHandle
        val k = People.key(person)
        if (k.isEmpty() || k.startsWith("tel:")) return false
        return others.any { People.key(it) == k }
    }

    /** The pill beside a watched person: lit "Handle for me", else "Ask me" (a card waits in Needs you). */
    const val PERSON_ON = "Handle for me"
    const val PERSON_OFF = "Ask me"
    const val PERSON_HINT = "\"Handle for me\" does what that person asks at once (a task on its day, a reminder, the steps " +
        "to get ready) and tells you what was done, with Undo. Nothing is ever sent to them. Off: their requests wait in " +
        "Needs you. For your partner it's the same switch as About you's."

    fun personLabel(on: Boolean): String = if (on) PERSON_ON else PERSON_OFF

    /** For TalkBack / VoiceOver: "Handle Sam's requests for me, on". */
    fun personSpoken(name: String, on: Boolean): String = "Handle ${name.trim()}'s requests for me, ${if (on) "on" else "off"}"

    /**
     * Whether [p] is certain enough to act on without asking: a task, reminder, event or work-from-home day with a day
     * that hasn't gone. Anything else (no clear day, a reply, a renewal, a booking…) stays a card.
     */
    fun certain(p: RequestProposal, today: Long): Boolean =
        p.kind in HANDLED_KINDS && p.day != null && p.day >= today

    private val HANDLED_KINDS = setOf(RequestKind.TASK, RequestKind.REMINDER, RequestKind.EVENT, RequestKind.WORK_FROM_HOME)

    /** The prep steps a kind of request has (only a work-from-home day for now). */
    fun stepsFor(kind: RequestKind): List<String> = if (kind == RequestKind.WORK_FROM_HOME) WORK_FROM_HOME_STEPS else emptyList()

    /** The most suggested steps one request gets. */
    const val SUGGESTION_MAX = 3
    const val SUGGESTED_HEAD = "Obi's steps to get ready · tap one to take it off"
    const val SUGGESTION_GONE = "Taken off your list"

    /** One entry in Obi's step library: what in a request's title it answers, and its steps (each skipped when the title already says it). */
    private class StepRule(val match: Regex, val steps: List<Pair<String, Regex?>>, val needsChildren: Boolean = false)

    private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)

    /**
     * Obi's best steps for the common kinds of request (generic, no names; words from the title only pick a rule, they
     * never become a step). Kept small on purpose: a request that matches nothing gets no steps rather than a guess.
     */
    private val STEP_LIBRARY = listOf(
        StepRule(rx("\\b(wedding|christening|baby shower|engagement)\\b"), listOf(
            "RSVP" to rx("\\brsvp"), "Buy a gift" to rx("\\b(gift|present)"),
        )),
        StepRule(rx("\\b(birthday|bday|party)\\b"), listOf(
            "Buy a present" to rx("\\b(gift|present)"), "Get a card" to rx("\\bcard"),
        )),
        StepRule(rx("\\b(gift|present)s?\\b"), listOf(
            "Get a card" to rx("\\bcard"), "Wrap it" to rx("\\bwrap"),
        )),
        StepRule(rx("\\b(dinner|restaurant|brunch|lunch out|date night|meal out)\\b"), listOf(
            "Book a table" to rx("\\b(book|booked|reservation|table)"),
        )),
        StepRule(rx("\\b(date night|night out|theatre|cinema|concert|gig|wedding|dinner)\\b"), listOf(
            "Sort a babysitter" to rx("\\b(babysit|sitter|kids|boys|children)"),
        ), needsChildren = true),
        StepRule(rx("\\b(school trip|trip form|permission slip|consent form)\\b"), listOf(
            "Sign the form" to rx("\\bsign"), "Pay for it online" to rx("\\bpa(y|id)"),
        )),
        StepRule(rx("\\bparents'? ?evening\\b|\\bparent[- ]teacher\\b"), listOf(
            "Book a slot" to rx("\\b(book|booked|slot)"),
        )),
        StepRule(rx("\\b(world book day|dress[- ]up|non[- ]uniform|costume)\\b"), listOf(
            "Sort the outfit" to rx("\\b(outfit|costume)"),
        )),
        StepRule(rx("\\b(bake sale|bake|cake)\\b"), listOf(
            "Buy the ingredients" to rx("\\b(buy|ingredients)"),
        )),
        StepRule(rx("\\b(holiday|flight|airport|abroad)\\b"), listOf(
            "Check the passports" to rx("\\bpassport"), "Pack" to rx("\\bpack"),
        )),
        StepRule(rx("\\b(guests|visitors|coming over|coming to stay|staying with us|hosting)\\b"), listOf(
            "Do a food shop" to rx("\\b(shop|groceries|food)"),
        )),
    )

    /**
     * What remains of Take first 2026-11-11 ("a new kind gets Obi's best steps, shown as suggestions he can untick"):
     * the steps for a task, reminder or event with no fixed steps, from [STEP_LIBRARY] by its title (a babysitter only
     * when About you has children), each skipped when the title already covers it, at most [SUGGESTION_MAX], in order.
     * A work-from-home day keeps its own three; any other kind gets none. Never the message's text: only its title.
     */
    fun suggestedSteps(kind: RequestKind, title: String, hasChildren: Boolean): List<String> {
        if (kind !in SUGGESTING_KINDS) return emptyList()
        val t = title.trim()
        if (t.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        STEP_LIBRARY.forEach { r ->
            if (r.needsChildren && !hasChildren) return@forEach
            if (!r.match.containsMatchIn(t)) return@forEach
            r.steps.forEach { (step, skip) ->
                if (skip == null || !skip.containsMatchIn(t)) out += step
            }
        }
        return out.take(SUGGESTION_MAX)
    }

    private val SUGGESTING_KINDS = setOf(RequestKind.TASK, RequestKind.REMINDER, RequestKind.EVENT)

    /**
     * The suggestion chips on a row: of the steps it made (a row that isn't a work-from-home day: every task after the
     * request's own), the ones still on his list and not done, by [open] (id → title, null when gone or done).
     */
    fun suggestions(row: DoneForYou, open: (String) -> String?): List<HandleSuggestion> {
        if (row.homeDay != null) return emptyList()
        return row.taskIds.drop(1).mapNotNull { id ->
            open(id)?.trim()?.takeIf { it.isNotEmpty() }?.let { HandleSuggestion(id, it, "Take “$it” off your list") }
        }
    }

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
    fun plan(p: RequestProposal, today: Long, nowMinute: Int, isWorkDay: (Long) -> Boolean, hasChildren: Boolean = false): HandlePlan {
        val day = p.day ?: today
        val fixed = stepsFor(p.kind)
        val names = fixed.ifEmpty { suggestedSteps(p.kind, p.title, hasChildren) }
        val steps = if (names.isEmpty()) emptyList() else {
            val sd = stepDay(day, today, isWorkDay)
            val r = remindAt(sd, STEP_REMIND_MINUTE, today, nowMinute, day)
            // Too late tonight for a step due today: it moves to the morning it reminds on (never past the day itself).
            val on = r?.first?.coerceAtMost(day) ?: sd
            names.map { PrepStep(it, on, r?.first, r?.second, suggested = fixed.isEmpty()) }
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
        val fixedSteps = plan.steps.filterNot { it.suggested }
        fixedSteps.firstOrNull()?.let { s ->
            val n = fixedSteps.size
            parts += "$n ${if (n == 1) "step" else "steps"} for ${dayWord(s.day, today).let { if (it == "Today" || it == "Tomorrow") it.lowercase() else it }}"
        }
        val nSuggested = plan.steps.size - fixedSteps.size
        if (nSuggested > 0) parts += "Obi suggests $nSuggested ${if (nSuggested == 1) "step" else "steps"}"
        val remind = fixedSteps.firstOrNull()?.let { s -> s.remindDay?.let { d -> d to s.remindMinute!! } }
            ?: plan.task?.let { t -> t.remindDay?.let { d -> d to t.remindMinute!! } }
        remind?.let { (d, m) ->
            parts += "reminder " + (if (d == today) "" else dayWord(d, today) + " ") + TaskWhenRules.timeLabel(m)
        }
        return "$head · ${parts.joinToString(", ")}"
    }

    /**
     * Slice 3 (Meka 2026-11-11: "a reply to her and a note to his manager are drafted for his tap, never sent"): the
     * drafts on a handled request's row. Dates are said as dates ("Thu 12 Nov"), never "tomorrow", since the row lives
     * a day and a half. A work-from-home day gets a reply that promises only the asking ("Okay, I'll ask work about
     * working from home on Thu 12 Nov.") and a note to his manager; a task or reminder "Okay, I've put “…” on my list
     * for Thu 12 Nov."; an event "Okay, “…” is in my diary for Thu 12 Nov at 18:00.". Only the request's title is
     * quoted, never the message's text. Nothing when the kind isn't handled or the day is unknown.
     */
    fun drafts(kind: RequestKind, title: String, day: Long?, minute: Int?, person: String): List<HandleDraft> {
        val d = day ?: return emptyList()
        if (kind !in HANDLED_KINDS) return emptyList()
        val first = person.trim().substringBefore(' ').take(20).ifEmpty { "her" }
        val date = CivilDate.shortLabel(d)
        val what = title.trim().replace(Regex("\\s+"), " ").take(DRAFT_TITLE_MAX).trimEnd('.', ' ')
        val reply = when (kind) {
            RequestKind.WORK_FROM_HOME -> "Okay, I'll ask work about working from home on $date."
            RequestKind.EVENT -> "Okay, “$what” is in my diary for $date" + (minute?.let { " at ${TaskWhenRules.timeLabel(it)}" } ?: "") + "."
            else -> "Okay, I've put “$what” on my list for $date."
        }
        val replyLabel = "Copy reply to $first"
        val out = mutableListOf(HandleDraft(replyLabel, reply, "$replyLabel: $reply"))
        if (kind == RequestKind.WORK_FROM_HOME) {
            val note = "Hi, would it be okay if I work from home on $date? I'll be online and reachable as usual."
            out += HandleDraft(MANAGER_LABEL, note, "$MANAGER_LABEL: $note")
        }
        return out
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

    /** The Done for you tile quotes a task's title up to this long. */
    const val TILE_TITLE_MAX = 40

    /**
     * What remains of Take first 2026-11-11 ("on Today's heads-up"): what was done for him as one Also today tile, from
     * the rows still shown in Needs you ([shown]), newest first. "Work from home tomorrow" · "from Alex · 2 steps to do
     * today" (lit: there's something to do today), "Work from home Thursday" · "from Alex · steps done", "Pick up the
     * dry cleaning" · "from Alex · on your list for Thursday"; with "· 1 more" when more were done. A request whose day
     * has gone and left nothing open isn't shown. A tap opens Needs you. Never the message's own words: only the title.
     */
    fun alsoTile(items: List<DoneForYouBrief>, today: Long): AlsoTile? {
        val live = items
            .filter { b -> (b.homeDay != null && b.homeDay >= today) || (b.title != null && (b.day ?: today) >= today) || b.openToday.isNotEmpty() }
            .sortedByDescending { it.atMs }
        val b = live.firstOrNull() ?: return null
        val who = b.who.trim().take(40)
        val dayLow = { d: Long -> dayWord(d, today).let { if (it == "Today" || it == "Tomorrow") it.lowercase() else it } }
        val value = when {
            b.homeDay != null && b.homeDay >= today -> "Work from home ${dayLow(b.homeDay)}"
            b.title != null -> b.title.trim().let { if (it.length > TILE_TITLE_MAX) it.take(TILE_TITLE_MAX - 1).trimEnd() + "…" else it }
            else -> "Work from home"
        }
        val n = b.openToday.size
        val state = when {
            n > 0 -> "$n ${if (n == 1) "step" else "steps"} to do today"
            b.homeDay != null -> "steps done"
            b.title != null -> (b.day ?: today).let { d -> if (d == today) "on today's list" else "on your list for ${dayLow(d)}" }
            else -> "done"
        }
        val more = live.size - 1
        val line = listOfNotNull(who.takeIf { it.isNotEmpty() }?.let { "from $it" }, state, more.takeIf { it > 0 }?.let { "$it more" })
            .joinToString(" · ")
        val spoken = buildString {
            append("Done for you")
            if (who.isNotEmpty()) append(", from $who")
            append(": $value, ")
            append(state.replace(" · ", ", "))
            append(".")
            if (more > 0) append(" $more more ${if (more == 1) "request" else "requests"} done for you.")
            append(" Opens Needs you.")
        }
        return AlsoTile(AlsoKind.DONE_FOR_YOU, AlsoTodayRules.key(AlsoKind.DONE_FOR_YOU), value, line, spoken, lit = n > 0)
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

    /** The end of work when his hours don't say (no schedule, or hours that cross midnight). */
    const val END_OF_WORK_DEFAULT_MINUTE = 17 * 60 + 30
    const val END_OF_WORK_TITLE = "Before you finish work"

    /**
     * Slice 4 (Meka 2026-11-11: "a reminder at a sensible hour and again at the end of work if still open"): on a work
     * day, one heads-up at the end of his work hours (his hours that day, else 17:30) naming the prep steps still open
     * for a home day that hasn't come yet: "Still open for working from home Thu 12 Nov: tell your manager and get
     * approval." Only steps that show today, only while the row is shown, and only when that time is at least
     * [LEAD_MIN] after the steps' own reminder and after the request was handled (a late request isn't nagged twice in
     * one evening). Gone once the steps are done or deleted, or after [LATEST_MINUTE]. Never the message's text.
     */
    fun endOfWorkNotices(
        rows: List<DoneForYou>,
        tasks: List<Task>,
        schedule: WorkSchedule?,
        holidays: HolidayCalendar,
        nowMs: Long,
        cal: LocalCalendar,
    ): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        if (schedule != null && !WorkModeRules.isWorkDay(schedule, holidays, today)) return emptyList()
        val end = schedule?.hoursOn(CivilDate.isoDayOfWeek(today))?.takeUnless { it.crossesMidnight }?.endMinute
            ?: END_OF_WORK_DEFAULT_MINUTE
        if (end > LATEST_MINUTE) return emptyList()
        val atMs = cal.toEpochMs(today, end)
        val byId = tasks.associateBy { it.id }
        return shown(rows, nowMs).mapNotNull { r ->
            val home = r.homeDay ?: return@mapNotNull null
            if (home <= today || atMs < r.atMs + LEAD_MIN * 60_000L) return@mapNotNull null
            val open = r.taskIds.mapNotNull { byId[it] }
                .filter { !it.lifecycle.isTerminal && TaskWhenRules.dayOf(it, today, cal) == today }
            if (open.isEmpty()) return@mapNotNull null
            if (open.any { t -> t.remindAtMs?.let { atMs < it + LEAD_MIN * 60_000L } == true }) return@mapNotNull null
            val steps = open.map { t -> t.title.trim().replaceFirstChar { it.lowercase() } }
            Notice(
                key = "handled-eow:${r.id}:$today", source = NoticeSource.REQUEST, tier = NoticeTier.HEADS_UP,
                title = END_OF_WORK_TITLE,
                text = "Still open for working from home ${CivilDate.shortLabel(home)}: ${listing(steps)}.",
                atMs = atMs, target = NoticeTarget.NEEDS_YOU, expiresAtMs = cal.toEpochMs(today, LATEST_MINUTE),
                precision = NoticePrecision.CLOCK,
            )
        }
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
