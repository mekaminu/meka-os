package os.meka.core.domain

/**
 * "A morning brief worth listening to" (Meka, 2026-10-12 07:41: the old brief "just tells me the date, weather, that
 * I have 5 tasks and 1 habit, then the headlines… should tell me what the tasks are, anything to focus on, a
 * motivational one-liner"). Slice 1, non-AI, pure: the facts the spoken brief needs beyond what the pane shows, and
 * [BriefScriptRules.script], the brief as a ~90-second chief-of-staff briefing in natural sentences.
 *
 * The facade fills [BriefStory] from what MEKA already knows (it reads nothing new and sends nothing anywhere). Every
 * name and time in the script comes from these inputs; nothing is invented.
 */

/** A timed calendar event today, by local minute of the day ([endMin] may pass midnight: 24 × 60 and more). */
data class BriefEvent(val title: String, val startMin: Int, val endMin: Int)

/** A task named in the brief's focus, and why it matters ("it's overdue"); [why] is null for a plain Up next. */
data class BriefFocus(val title: String, val why: String?)

data class BriefStory(
    /** Today's local epoch day (picks the one-liner, [BriefScriptRules.oneLiner]). */
    val epochDay: Long = 0,
    /** Saturday or Sunday with no work: the lighter weekend brief. */
    val weekend: Boolean = false,
    /** A work day at the office (Places item 1), so the commute is said. */
    val officeDay: Boolean = false,
    /** When today's work ends (local minute), on a work day. */
    val workEndMin: Int? = null,
    /** Both places on an office day: "Biggleswade 9° now · Canary Wharf 14°, rain from 15:00 — take a coat". */
    val placesLine: String? = null,
    /** The trains on the route, as TfL says (already spoken): "Thameslink, Elizabeth line, good service". */
    val route: String? = null,
    /** Today's timed events, by start. */
    val events: List<BriefEvent> = emptyList(),
    /** The top 1–3 tasks by name ([BriefScriptRules.focus]). */
    val focus: List<BriefFocus> = emptyList(),
    /** Today's other open tasks, only counted ("and 4 smaller ones"). */
    val smallerCount: Int = 0,
    /** Decisions waiting in Needs you. */
    val needsYou: Int = 0,
    /** Today's habits still to do, by name with the week so far: "gym, 1 of 3 this week". */
    val habits: List<String> = emptyList(),
    /** "This week: 2 of 3 goals moving". */
    val weekGoals: String? = null,
    /** Home and family, each already a spoken sentence (bins tonight, date night, a birthday, a trip). */
    val family: List<String> = emptyList(),
    /** One of this week's goals still open, for the one-liner. */
    val goalTitle: String? = null,
) {
    companion object {
        val EMPTY = BriefStory()
    }
}

object BriefScriptRules {
    /** Tasks named in the focus on a weekday; the weekend names one. */
    const val MAX_FOCUS = 3
    const val MAX_FOCUS_WEEKEND = 1
    /** Headlines read; the rest stay on screen (Barça first, as the pane lists them). */
    const val MAX_HEADLINES = 3
    /** About 90 seconds before the news, the one-liner and the close (whole sentences only). */
    const val MAX_CHARS = 1_400

    const val CLOSE = "Say “plan my day” or “more news”."

    /**
     * The brief [v] read to [name], in this order: greeting and what kind of day it is; getting there (an office day's
     * trains and both places' weather) or the weather; the calendar's first meeting, its shape and any clash; the focus
     * by name with why, and the smaller ones counted; what's waiting on him; habits and the week's goals; home and
     * family; three headlines; the one-liner; and the close. The weekend version drops the commute, names one focus.
     */
    fun script(v: MorningBriefView, name: String = "Meka"): String = (body(v, name) + tail(v)).joinToString(" ")

    /**
     * Slice 2: the brief's facts, the sentences before the news ([script] without [tail]), fitted to [MAX_CHARS]. These
     * are what MEKA's AI rewrites ([BriefAiRules]); every name and time it may say is in them.
     */
    fun body(v: MorningBriefView, name: String = "Meka"): List<String> = fit(sentences(v, name))

    /** The end every brief has, AI-written or not: three headlines, the one-liner and [CLOSE]. */
    fun tail(v: MorningBriefView): List<String> = news(v.headlines) + listOf(oneLiner(v.story), CLOSE)

    private fun sentences(v: MorningBriefView, name: String): List<String> {
        val s = v.story
        val out = mutableListOf<String>()
        out += "${v.greeting}, $name."
        dayKind(v)?.let { out += it }

        // Getting there and the weather.
        if (s.officeDay && !s.weekend) {
            s.route?.let { out += sentence("On the trains: $it") }
            val weather = s.placesLine ?: v.weatherLine
            weather?.let { out += sentence(if (s.placesLine != null) it else "Today, $it") }
        } else {
            v.weatherLine?.let { out += sentence("Today, $it") }
        }

        // The calendar.
        out += calendar(v, s)

        // Focus.
        out += focusLines(s, v)

        // Waiting on him.
        if (s.needsYou > 0) out += if (s.needsYou == 1) "One decision is waiting for you in Needs you." else "${s.needsYou} decisions are waiting for you in Needs you."
        val chases = v.waiting.filter { it.state == DueState.DUE }.take(2)
        if (chases.isNotEmpty()) out += sentence("Chase " + listing(chases.map { w -> w.who?.let { "$it about ${w.title}" } ?: w.title }))
        if (!s.weekend && v.attention.isNotEmpty()) out += sentence("On your lists: " + listing(v.attention.take(2).map { it.title }))

        // Habits and goals.
        when (s.habits.size) {
            0 -> {}
            1 -> out += sentence("Today's habit: ${s.habits[0]}")
            else -> out += sentence("Habits today: " + listing(s.habits.take(3)))
        }
        if (!s.weekend) s.weekGoals?.let { out += sentence(it) }
        v.fastingLine?.let { out += sentence(it) }

        // Home and family.
        v.school?.let { out += it.spoken }
        out += s.family.map { sentence(it) }

        return out.filter { it.isNotBlank() }
    }

    /**
     * "It's Monday 12 October, an office day, finishing at half past five." · "…, working from home, finishing at …" ·
     * "It's Saturday 17 October, no work today." · a bank holiday or leave as its line says ("Christmas Day, no work").
     */
    fun dayKind(v: MorningBriefView): String? {
        if (v.dateLabel.isBlank()) return null
        val date = "It's " + BriefSpeech.spokenDate(v.dateLabel)
        val s = v.story
        val work = v.workLine
        return when {
            work != null && work.startsWith("Work ") -> {
                val kind = if (s.officeDay) "an office day" else "a work day"
                val end = s.workEndMin?.let { ", finishing at " + clock(it) } ?: ""
                "$date, $kind$end."
            }
            work != null -> "$date. " + sentence(work)
            s.weekend -> "$date, no work today."
            else -> "$date."
        }
    }

    /** The first meeting, the day's shape and the first clash; all-day things and the kids' matches by name. */
    fun calendar(v: MorningBriefView, s: BriefStory): List<String> {
        val out = mutableListOf<String>()
        val allDay = v.day.filter { it.isEvent && it.time == "All day" }
        if (allDay.isNotEmpty()) out += sentence("All day: " + listing(allDay.take(3).map { FootballRules.spokenFixture(it.title) ?: it.title }))
        val events = s.events
        if (events.isEmpty()) {
            if (v.workLine?.startsWith("Work ") == true) out += "No meetings today."
            return out
        }
        val first = events.first()
        val firstSaid = FootballRules.spokenFixture(first.title)?.let { "$it at ${clock(first.startMin)}" }
            ?: "${first.title} at ${clock(first.startMin)}"
        out += sentence(if (events.size == 1) "One thing in the calendar: $firstSaid" else "First up, $firstSaid")
        if (events.size > 1) {
            val rest = events.drop(1).take(2).map { e ->
                FootballRules.spokenFixture(e.title)?.let { "$it at ${clock(e.startMin)}" } ?: "${e.title} at ${clock(e.startMin)}"
            }
            val more = events.size - 1 - rest.size
            out += sentence("Then " + listing(rest) + if (more > 0) ", and $more more" else "")
        }
        shape(events, s.workEndMin)?.let { out += sentence(it) }
        clash(events)?.let { out += sentence(it) }
        return out
    }

    /**
     * The day's shape from its timed events: "A busy morning, then free from 1 o'clock", "A clear morning, busy after
     * lunch", "A full day of meetings", "Free after 3 o'clock" (when the last one ends two hours or more before work
     * ends, or 17:00); null when none of those fits.
     */
    fun shape(events: List<BriefEvent>, workEndMin: Int?): String? {
        if (events.isEmpty()) return null
        val morning = events.count { it.startMin < NOON }
        val afternoon = events.size - morning
        val lastEnd = events.maxOf { it.endMin }
        val end = workEndMin ?: (17 * 60)
        return when {
            events.size >= 5 -> "A full day of meetings"
            morning >= 3 && afternoon == 0 -> "A busy morning, then free from ${clock(lastEnd)}"
            afternoon >= 3 && morning == 0 -> "A clear morning, busy after lunch"
            lastEnd <= end - 120 && lastEnd < 24 * 60 -> "Free after ${clock(lastEnd)}"
            else -> null
        }
    }

    /** "Heads up: Standup and Dentist overlap at 10 o'clock." for the first two that overlap; null when none do. */
    fun clash(events: List<BriefEvent>): String? {
        val sorted = events.sortedBy { it.startMin }
        for (i in sorted.indices) for (j in i + 1 until sorted.size) {
            val a = sorted[i]
            val b = sorted[j]
            if (b.startMin < a.endMin && b.startMin >= a.startMin) return "Heads up: ${a.title} and ${b.title} overlap at ${clock(b.startMin)}"
        }
        return null
    }

    /**
     * The focus from today's open tasks ([ShutdownRules.left]'s order): overdue first, then due today, then one that
     * moves one of this week's goals ([weekGoals]: goal id → title), then Up next ([upNextId]). At most [max] are
     * named; every other open task is counted as smaller. Repeating chores rank last among their kind.
     */
    fun focus(
        tasks: List<Task>, today: DayWindow, nowMs: Long, weekGoals: Map<String, String>, upNextId: String?,
        max: Int = MAX_FOCUS, clockOf: (Long) -> Int,
    ): Pair<List<BriefFocus>, Int> {
        data class Ranked(val rank: Int, val order: Int, val f: BriefFocus)
        val ranked = tasks.mapIndexedNotNull { i, t ->
            val goal = t.goalId?.let { weekGoals[it] }
            val due = t.dueAtMs
            val r = when {
                due != null && due < nowMs -> Ranked(0, i, BriefFocus(t.title, "it's overdue"))
                due != null && due < today.endMs -> Ranked(1, i, BriefFocus(t.title, "it's due at ${clock(clockOf(due))}"))
                goal != null -> Ranked(2, i, BriefFocus(t.title, "it moves your goal, $goal"))
                t.id == upNextId -> Ranked(3, i, BriefFocus(t.title, t.scheduledAtMs?.takeIf { it >= today.startMs }?.let { "it's up next at ${clock(clockOf(it))}" } ?: "it's up next"))
                else -> null
            } ?: return@mapIndexedNotNull null
            if (t.isRepeating) r.copy(order = r.order + 10_000) else r
        }.sortedWith(compareBy<Ranked> { it.rank }.thenBy { it.order })
        val named = ranked.take(max).map { it.f }
        return named to (tasks.size - named.size).coerceAtLeast(0)
    }

    /**
     * Today's habits still to do, behind first: "gym, 1 of 3 this week" for a weekly one, "reading, a 12-day streak" for
     * a daily one with a streak, else the name alone. Done today, or the week already met, are left out.
     */
    fun habits(items: List<HabitItem>): List<String> = items
        .filter { !it.doneToday && (it.pace == HabitPace.BEHIND || it.pace == HabitPace.DUE) }
        .sortedBy { if (it.pace == HabitPace.BEHIND) 0 else 1 }
        .map { h ->
            val name = h.title.trim().replaceFirstChar { it.lowercase() }
            when {
                h.streakUnit != "day" && h.weekTarget > 0 -> "$name, ${h.doneThisWeek} of ${h.weekTarget} this week"
                h.streak >= 2 -> "$name, a ${h.streak}-day streak"
                else -> name
            }
        }

    private fun focusLines(s: BriefStory, v: MorningBriefView): List<String> {
        val max = if (s.weekend) MAX_FOCUS_WEEKEND else MAX_FOCUS
        val named = s.focus.take(max)
        val smaller = s.smallerCount + (s.focus.size - named.size)
        if (named.isEmpty()) {
            return if (smaller > 0) listOf(sentence("Nothing urgent on your tasks, just ${count(smaller, "small one")}"))
            else if (v.day.isEmpty() && s.events.isEmpty()) listOf("Nothing's planned yet.") else emptyList()
        }
        val out = mutableListOf<String>()
        fun said(f: BriefFocus) = f.why?.let { "${f.title}, $it" } ?: f.title
        when (named.size) {
            1 -> out += sentence("One thing to focus on: " + said(named[0]))
            else -> {
                out += "${if (named.size == 2) "Two" else "Three"} things to focus on."
                out += sentence("First, " + said(named[0]))
                named.drop(1).dropLast(1).forEach { out += sentence("Then " + said(it)) }
                out += sentence("And " + said(named.last()))
            }
        }
        if (smaller > 0) out += sentence(if (smaller == 1) "Plus one smaller one" else "Plus $smaller smaller ones")
        return out
    }

    /** At most [MAX_HEADLINES], one sentence each: "In the news." then "From BBC Sport: Barça win again." */
    fun news(headlines: List<BriefHeadline>): List<String> = BriefSpeech.news(headlines.take(MAX_HEADLINES))

    /**
     * The closing one-liner: short, about his day where the facts allow (the first focus, a goal, a habit, the
     * weekend), never a quote. Picked by [BriefStory.epochDay] from [LINES] (more than 30), so no line comes back within
     * 30 days on either device, and both devices say the same one.
     */
    fun oneLiner(s: BriefStory): String {
        val i = (((s.epochDay % LINES.size) + LINES.size) % LINES.size).toInt()
        return LINES[i](s)
    }

    /** "half past five", "9 o'clock", "quarter to ten", "10:40" for the minute of the day [min] (12-hour, said). */
    fun clock(min: Int): String {
        val m = ((min % (24 * 60)) + 24 * 60) % (24 * 60)
        val h = m / 60
        val mm = m % 60
        fun h12(x: Int) = ((x + 11) % 12) + 1
        return when (mm) {
            0 -> "${h12(h)} o'clock"
            15 -> "quarter past ${h12(h)}"
            30 -> "half past ${h12(h)}"
            45 -> "quarter to ${h12(h + 1)}"
            else -> "${h12(h)}:${if (mm < 10) "0" else ""}$mm"
        }
    }

    private const val NOON = 12 * 60

    private fun first(s: BriefStory) = s.focus.firstOrNull()?.title
    private fun habit(s: BriefStory) = s.habits.firstOrNull()?.substringBefore(",")?.trim()?.takeIf { it.isNotEmpty() }

    /** 31 lines (one per day of a month and one more, so a month never repeats one). */
    /** How many one-liners there are (more than 30). */
    val lineCount: Int get() = LINES.size

    private val LINES: List<(BriefStory) -> String> = listOf(
        { s -> first(s)?.let { "$it first. Everything else can wait its turn." } ?: "Pick the one thing that matters and do it first." },
        { s -> s.goalTitle?.let { "Small steps on $it still count." } ?: "Small steps still count." },
        { _ -> "Done beats perfect. Ship something today." },
        { s -> habit(s)?.let { "${it.replaceFirstChar { c -> c.uppercase() }} today keeps the week honest." } ?: "Turn up today; the streak looks after itself." },
        { _ -> "You don't need more time, just fewer things. Drop one." },
        { _ -> "Make the hard call before lunch." },
        { s -> if (s.weekend) "No inbox today. Be where your feet are." else "Finish on time tonight; the boys are the best meeting of the day." },
        { _ -> "Reply to the one you've been avoiding. It's smaller than it looks." },
        { _ -> "Say no to one thing today so the right thing gets a yes." },
        { _ -> "Leave ten minutes unplanned. You'll need them." },
        { s -> first(s)?.let { "Twenty focused minutes on $it beats an hour of half-attention." } ?: "Twenty focused minutes beats an hour of half-attention." },
        { _ -> "Write it down, then stop carrying it around." },
        { s -> s.goalTitle?.let { "Ask yourself tonight: did today move $it?" } ?: "Ask yourself tonight: what did today move?" },
        { _ -> "Start before you feel ready." },
        { s -> if (s.officeDay) "Use the train to think, not just to scroll." else "Step outside for ten minutes. The day will still be there." },
        { _ -> "Clear one small thing before the first meeting. Momentum is cheap." },
        { _ -> "Good enough and on time beats brilliant and late." },
        { s -> habit(s)?.let { "Don't negotiate with $it. Just go." } ?: "Don't negotiate with the plan. Just start." },
        { _ -> "If it takes two minutes, do it now." },
        { _ -> "Be the calm one in the room today." },
        { s -> first(s)?.let { "By lunch, $it should be off your mind." } ?: "By lunch, one thing should be off your mind." },
        { _ -> "Plan the day, then let it surprise you a little." },
        { s -> if (s.weekend) "Rest is part of the plan, not a break from it." else "Protect one hour today for the work only you can do." },
        { _ -> "Ask for the help. People like being asked." },
        { _ -> "Energy first: water, daylight, then the inbox." },
        { s -> s.goalTitle?.let { "$it won't finish itself. Give it an hour this week." } ?: "The big goal needs an hour this week. Book it." },
        { _ -> "Finish what you start today, even if it's small." },
        { _ -> "One thing at a time. It's faster." },
        { s -> if (s.weekend) "Make a memory with the boys today." else "Leave work at work tonight." },
        { _ -> "Decide once, then stop re-deciding." },
        { _ -> "Make today a day you'd happily repeat." },
    )

    /** The sentences kept while they fit in [MAX_CHARS]. */
    internal fun fit(sentences: List<String>): List<String> {
        val kept = mutableListOf<String>()
        var length = 0
        for (s in sentences) {
            if (length + 1 + s.length > MAX_CHARS) break
            kept += s
            length += 1 + s.length
        }
        return kept
    }

    private fun listing(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    private fun sentence(text: String): String {
        val c = BriefSpeech.clean(text).trimEnd(',', ';', ' ')
        return if (c.isEmpty() || c.last() in ".!?…") c else "$c."
    }
}
