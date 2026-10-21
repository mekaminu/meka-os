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

/**
 * A task named in the brief's focus, and why it matters ("it's overdue"); [why] is null for a plain Up next. [taskId]
 * (slice 3) lets the brief's Focus rows tick it off.
 */
data class BriefFocus(val title: String, val why: String?, val taskId: String? = null)

/**
 * Slice 3: how long the spoken brief is (Meka's setting, synced on the brief's `context_mode` entity as
 * [BriefFields.LENGTH]): [SHORT] about 45 seconds (one focus, the first meeting, one headline), [STANDARD] about 90
 * seconds, [FULL] about three minutes (up to five tasks named, the whole calendar, what's on your lists, five headlines).
 */
enum class BriefLength(
    val id: String,
    val label: String,
    /** The body ([BriefScriptRules.body]) in characters at most, whole sentences only. */
    val maxChars: Int,
    val maxFocus: Int,
    val maxFocusWeekend: Int,
    val maxHeadlines: Int,
    /** Events named after the first one. */
    val moreEvents: Int,
    val maxChases: Int,
    /** Renewals and decisions from your lists named. */
    val maxAttention: Int,
    /** The day's shape, the week's goals, a running fast and more than one home line. */
    val extras: Boolean,
) {
    SHORT("short", "Short", 700, 1, 1, 1, 0, 1, 0, false),
    STANDARD("standard", "Standard", 1_400, 3, 1, 3, 2, 2, 2, true),
    FULL("full", "Full", 2_800, 5, 2, 5, 5, 4, 4, true);

    fun focusFor(weekend: Boolean) = if (weekend) maxFocusWeekend else maxFocus

    companion object {
        val DEFAULT = STANDARD
        fun of(id: String?): BriefLength = entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: DEFAULT
    }
}

/**
 * Slice 4: someone waiting on Meka, as the brief names them: a request MEKA read from a watched person's message (its
 * Needs you card), or an urgent voice message the call assistant took. Never the message's own words (untrusted,
 * ADR-006): a request is said through its card's checked action, a voice message by who left it and when.
 */
data class BriefAsk(
    val id: String,
    /** "Wife", "Dave", or "A caller" for someone known only by number. */
    val who: String,
    /** The pane's line: "Wife · Pick up dry cleaning · Tomorrow", "Urgent · Voice message from Dave · 16:10". */
    val line: String,
    /** The brief's sentence: "Wife asked you to pick up dry cleaning, tomorrow." */
    val spoken: String,
    /** An urgent voice message (said first, lit in the pane). */
    val urgent: Boolean,
)

/** One of the brief's length choices as the apps list them ("Short", lit when chosen). */
data class BriefLengthChoice(val id: String, val label: String, val chosen: Boolean)

data class BriefStory(
    /** Today's local epoch day (picks the one-liner, [BriefScriptRules.oneLiner]). */
    val epochDay: Long = 0,
    /** Saturday or Sunday with no work: the lighter weekend brief. */
    val weekend: Boolean = false,
    /** A work day at the office (Places item 1), so the commute is said. */
    val officeDay: Boolean = false,
    /** Travel mode completeness: the trip he's away on today ([TripRules.awayDays]), said with the kind of day. */
    val away: String? = null,
    /**
     * Travel mode completeness, slice 3: "In Barcelona today, 18–24°, clear." said in place of home's weather while away
     * there ([TripWeatherRules.brief]); null without the destination's forecast.
     */
    val awayWeather: String? = null,
    /** … and the day before the trip: "Tomorrow in Barcelona, 17–22°, light rain from 15:00 — take a coat.", after home's weather. */
    val tripTomorrowWeather: String? = null,
    /** When today's work ends (local minute), on a work day. */
    val workEndMin: Int? = null,
    /** The commute counts everywhere, slice 4: when he leaves for work on an office day (local minute), or null. */
    val leaveMin: Int? = null,
    /** … and when he's home (local minute), or null. */
    val homeMin: Int? = null,
    /** Both places on an office day: "Home 9° now · work 14°, rain from 15:00 — take a coat". */
    val placesLine: String? = null,
    /** The trains on the route, as TfL says (already spoken): "South Rail, Metro, good service". */
    val route: String? = null,
    /**
     * A morning brief worth listening to, the train to leave for ([RouteRules.briefLeave]): said in place of the
     * schedule's "Leave about …" ([leaveMin]); null without a fresh live board or the time to the train.
     */
    val leaveBy: LeaveBy? = null,
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
    /** Slice 3: Short · Standard · Full. */
    val length: BriefLength = BriefLength.DEFAULT,
    /** Slice 4: requests from watched people and urgent voice messages ([BriefScriptRules.asks]), urgent first. */
    val asks: List<BriefAsk> = emptyList(),
    /** Slice 6: today's one-liner once one was said (synced, [BriefOneLinerRules]); null until then. */
    val oneLiner: String? = null,
    /** Slice 6: the one-liners said in the 30 days before today, newest first (never said again). */
    val recentLines: List<String> = emptyList(),
    /**
     * Fold review 2026-10-12, bug 4: today's events during work, each already spoken ([WorkClash.spoken]): "Heads up:
     * Theo's under-sevens training at 4:55, while you're at work. Who's taking Theo?".
     */
    val workClashes: List<String> = emptyList(),
    /** Inbox autopilot, slice 9a: the emails that need him now ([MailAskRules.brief]); null when none (or no mail). */
    val mail: BriefMail? = null,
    /**
     * Weekly reflection, slice 2: "The one thing that matters this week: Run 5k." from last week's reflection
     * ([ReflectionRules.briefSentence]); said after the week's goals on weekdays; null when there is none.
     */
    val weekMatters: String? = null,
    /**
     * Barça match day, slice 3: last night's result with Barça's scorers, and where today's match is on, each already a
     * spoken sentence ([BarcaBriefRules.briefSentences]); said after home and family, in every length.
     */
    val barca: List<String> = emptyList(),
    /**
     * Trading tracker, slice 6: whether he's up or down on Hyperliquid over the last day and how many positions are
     * open, never an amount ([TradingTodayRules.briefSentence]); said after habits and goals; null when there's nothing.
     */
    val trading: String? = null,
    /** Protected time, slice 5: "Your evening is protected from 7 o'clock." ([ProtectedRules.briefSentence]), after the kind of day. */
    val protectedTime: String? = null,
    /** Week board slice 5: "Two carried over from earlier days and five planned, about 4 hours in all." ([DayPlan.sentence]), before the focus. */
    val plan: String? = null,
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

    /** Events during work said at most (Standard and Full; Short says one). */
    const val MAX_WORK_CLASHES = 2

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
    fun body(v: MorningBriefView, name: String = "Meka"): List<String> = fit(sentences(v, name), v.story.length.maxChars)

    /** The end every brief has, AI-written or not: three headlines, the one-liner and [CLOSE]. */
    fun tail(v: MorningBriefView): List<String> = news(v.headlines, v.story.length.maxHeadlines) + listOf(oneLiner(v.story), CLOSE)

    private fun sentences(v: MorningBriefView, name: String): List<String> {
        val s = v.story
        val len = s.length
        val out = mutableListOf<String>()
        out += CallNameRules.opening(v.greeting, name)
        dayKind(v)?.let { out += it }
        s.protectedTime?.let { out += it }

        // Getting there and the weather.
        if (s.officeDay && !s.weekend) {
            (s.leaveBy?.let { RouteRules.briefSentence(it, s.homeMin, ::clock) } ?: CommuteRules.briefLine(s.leaveMin, s.homeMin, ::clock))
                ?.let { out += it }
            s.route?.let { out += sentence("On the trains: $it") }
            val weather = s.placesLine ?: v.weatherLine
            weather?.let { out += sentence(if (s.placesLine != null) it else "Today, $it") }
        } else if (s.away != null && s.awayWeather != null) {
            out += sentence(s.awayWeather)
        } else {
            v.weatherLine?.let { out += sentence("Today, $it") }
        }
        s.tripTomorrowWeather?.let { out += sentence(it) }

        // The calendar.
        out += calendar(v, s)

        // How the day looks (Week board slice 5), then the focus.
        s.plan?.let { out += it }
        out += focusLines(s, v)

        // Waiting on him: urgent voice messages, then what people asked for, then decisions and chases.
        out += askLines(s)
        // Inbox autopilot, slice 9a: how many emails need him and who from (never a subject: someone else's words).
        s.mail?.let { out += MailAskRules.briefSentence(it) }
        if (s.needsYou > 0) out += if (s.needsYou == 1) "One decision is waiting for you in Needs you." else "${s.needsYou} decisions are waiting for you in Needs you."
        val chases = v.waiting.filter { it.state == DueState.DUE }.take(len.maxChases)
        if (chases.isNotEmpty()) out += sentence("Chase " + listing(chases.map { w -> w.who?.let { "$it about ${w.title}" } ?: w.title }))
        if (!s.weekend && len.maxAttention > 0 && v.attention.isNotEmpty()) out += sentence("On your lists: " + listing(v.attention.take(len.maxAttention).map { it.title }))

        // Habits and goals.
        when (s.habits.size) {
            0 -> {}
            1 -> out += sentence("Today's habit: ${s.habits[0]}")
            else -> out += if (len.extras) sentence("Habits today: " + listing(s.habits.take(3))) else sentence("Today's habit: ${s.habits[0]}")
        }
        if (!s.weekend && len.extras) s.weekGoals?.let { out += sentence(it) }
        if (!s.weekend) s.weekMatters?.let { out += it }
        if (len.extras) v.fastingLine?.let { out += sentence(it) }
        // Trading tracker, slice 6: up or down on Hyperliquid, no amounts.
        s.trading?.let { out += sentence(it) }

        // Home and family.
        v.school?.let { out += it.spoken }
        out += s.family.take(if (len.extras) s.family.size else 1).map { sentence(it) }
        // Barça match day, slice 3: last night's result and today's channel.
        out += s.barca

        return out.filter { it.isNotBlank() }
    }

    /**
     * "It's Monday 12 October, an office day, finishing at half past five." · "…, working from home, finishing at …" ·
     * "It's Saturday 17 October, no work today." · a bank holiday or leave as its line says ("Christmas Day, no work") ·
     * on a trip "It's Monday 16 November, away in Barcelona, a work day, finishing at …" / "…, away in Barcelona.".
     */
    fun dayKind(v: MorningBriefView): String? {
        if (v.dateLabel.isBlank()) return null
        val date = "It's " + BriefSpeech.spokenDate(v.dateLabel)
        val s = v.story
        val work = v.workLine
        s.away?.let { place ->
            return when {
                work != null && work.startsWith("Work ") ->
                    "$date, away in $place, a work day" + (s.workEndMin?.let { ", finishing at " + clock(it) } ?: "") + "."
                work != null -> "$date, away in $place. " + sentence(work)
                else -> "$date, away in $place."
            }
        }
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

    /**
     * The first meeting, the day's shape and the first clash; then what falls during work ([BriefStory.workClashes]);
     * all-day things and the kids' matches by name.
     */
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
        val len = s.length
        if (events.size > 1 && len.moreEvents > 0) {
            val rest = events.drop(1).take(len.moreEvents).map { e ->
                FootballRules.spokenFixture(e.title)?.let { "$it at ${clock(e.startMin)}" } ?: "${e.title} at ${clock(e.startMin)}"
            }
            val more = events.size - 1 - rest.size
            out += sentence("Then " + listing(rest) + if (more > 0) ", and $more more" else "")
        }
        if (len.extras) shape(events, s.workEndMin)?.let { out += sentence(it) }
        clash(events)?.let { out += sentence(it) }
        out += s.workClashes.take(if (len.extras) MAX_WORK_CLASHES else 1).map { sentence(it) }
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
                due != null && due < nowMs -> Ranked(0, i, BriefFocus(t.title, "it's overdue", t.id))
                due != null && due < today.endMs -> Ranked(1, i, BriefFocus(t.title, "it's due at ${clock(clockOf(due))}", t.id))
                goal != null -> Ranked(2, i, BriefFocus(t.title, "it moves your goal, $goal", t.id))
                t.id == upNextId -> Ranked(3, i, BriefFocus(t.title, t.scheduledAtMs?.takeIf { it >= today.startMs }?.let { "it's up next at ${clock(clockOf(it))}" } ?: "it's up next", t.id))
                else -> null
            } ?: return@mapIndexedNotNull null
            if (t.isRepeating) r.copy(order = r.order + 10_000) else r
        }.sortedWith(compareBy<Ranked> { it.rank }.thenBy { it.order })
        val named = ranked.take(max).map { it.f }
        return named to (tasks.size - named.size).coerceAtLeast(0)
    }

    /** Urgent voice messages said, at most (the rest are on the pane and in the after-work summary). */
    const val MAX_URGENT = 2
    /** How far back an urgent voice message is still news in the morning. */
    const val ASK_LOOKBACK_MS = 24L * 60 * 60 * 1000

    /**
     * Slice 4: what people are waiting on Meka for, urgent first. Urgent voice messages from the last day (newest
     * first; someone known only by number is "A caller"), then the open request cards from watched people (newest
     * first; a card Meka shared himself with "Handle this", a drafted reply and a renewal are his own lists' business,
     * not someone waiting). [today] is the brief's day; [minuteOf] reads a local minute.
     */
    fun asks(cards: List<RequestCard>, held: List<CapturedItem>, today: DayWindow, minuteOf: (Long) -> Int): List<BriefAsk> {
        val urgent = held
            .filter { it.kind == CaptureKind.VOICE_MESSAGE && it.urgent && it.isUrgent && it.atMs >= today.startMs - ASK_LOOKBACK_MS }
            .sortedByDescending { it.atMs }
            .map { m ->
                val number = People.key(m.personName).startsWith("tel:")
                val who = if (number) "A caller" else m.personName.trim().take(40)
                val minute = minuteOf(m.atMs)
                val yesterday = m.atMs < today.startMs
                val said = (if (yesterday) "yesterday at " else "at ") + clock(minute)
                val time = (if (yesterday) "Yesterday " else "") + TaskWhenRules.timeLabel(minute)
                BriefAsk(
                    id = "v-" + m.id, who = who,
                    line = "Urgent · Voice message from ${m.personName.trim().take(40)} · $time",
                    spoken = "$who left an urgent voice message $said.",
                    urgent = true,
                )
            }
        val requests = cards
            .filter { it.person.isNotBlank() && it.proposal.kind != RequestKind.REPLY && it.proposal.kind != RequestKind.RENEWAL &&
                it.proposal.kind != RequestKind.BOOKING }
            .sortedByDescending { it.atMs }
            .map { c -> BriefAsk("r-" + c.id, c.person, c.person + " · " + c.action.substringAfter(": "), askSaid(c.person, c.proposal, today.epochDay), false) }
        return urgent + requests
    }

    /** "Wife asked you to pick up dry cleaning, tomorrow." · "Wife asked you to work from home on Thu 15 Oct." */
    fun askSaid(who: String, p: RequestProposal, today: Long): String {
        val day = p.day?.let { d -> TaskWhenRules.label(d, null, today).let { if (it == "Today" || it == "Tomorrow") it.lowercase() else it } }
        val time = p.minute?.let { "at " + clock(it) }
        val whenSaid = listOfNotNull(day, time).joinToString(" ").ifEmpty { null }
        val title = p.title.trim().let { t -> if (t.length > 1 && t[1].isLowerCase()) t.replaceFirstChar { it.lowercase() } else t }
        return sentence(when {
            // A voice note's card ([MessageRequestRules.voiceNoteProposal]) is said as what it is.
            p.kind == RequestKind.REMINDER && p.title == MessageRequestRules.voiceNoteProposal(who).title -> "$who sent you a voice note to listen to"
            else -> when (p.kind) {
            RequestKind.WORK_FROM_HOME -> "$who asked you to work from home" + (day?.let { if (it == "today" || it == "tomorrow") " $it" else " on $it" } ?: "")
            RequestKind.EVENT -> "$who mentioned ${p.title.trim()}" + (whenSaid?.let { ", $it" } ?: "")
            RequestKind.REMINDER -> "$who asked you to remember to $title" + (whenSaid?.let { ", $it" } ?: "")
            else -> "$who asked you to $title" + (whenSaid?.let { ", $it" } ?: "")
            }
        })
    }

    private fun askLines(s: BriefStory): List<String> {
        val urgent = s.asks.filter { it.urgent }
        val requests = s.asks.filter { !it.urgent }
        val out = mutableListOf<String>()
        out += urgent.take(MAX_URGENT).map { it.spoken }
        if (urgent.size > MAX_URGENT) out += sentence("And ${count(urgent.size - MAX_URGENT, "more urgent voice message")} in your messages")
        val max = s.length.maxChases
        out += requests.take(max).map { it.spoken }
        if (requests.size > max) out += sentence("Plus ${count(requests.size - max, "more request")} in Needs you")
        return out
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
        val max = s.length.focusFor(s.weekend)
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

    /** At most [max] ([MAX_HEADLINES]), one sentence each: "In the news." then "From BBC Sport: Barça win again." */
    fun news(headlines: List<BriefHeadline>, max: Int = MAX_HEADLINES): List<String> = BriefSpeech.news(headlines.take(max))

    /**
     * The closing one-liner: short, about his day where the facts allow (the first focus, a goal, a habit, the
     * weekend), never a quote. Slice 6: today's remembered line ([BriefStory.oneLiner], the AI's or MEKA's own, the same
     * on both devices) once one was said; until then MEKA's own ([BriefOneLinerRules.pick]), skipping the last 30 days'.
     */
    fun oneLiner(s: BriefStory): String = s.oneLiner ?: BriefOneLinerRules.pick(s, s.recentLines)

    /**
     * MEKA's own line for [day] from [LINES] (more than 30, picked by the day, so the rotation never comes back within
     * 30 days).
     */
    fun ownLine(s: BriefStory, day: Long = s.epochDay): String {
        val i = (((day % LINES.size) + LINES.size) % LINES.size).toInt()
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

    /** The sentences kept while they fit in [max] ([MAX_CHARS]). */
    internal fun fit(sentences: List<String>, max: Int = MAX_CHARS): List<String> {
        val kept = mutableListOf<String>()
        var length = 0
        for (s in sentences) {
            if (length + 1 + s.length > max) break
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

/**
 * Slice 3: the brief's pane and Today's card match the script. The Focus rows name the same tasks with the same why
 * (as a caption: "Overdue", "Due at 3 o'clock", "Moves your goal, Q4 plan", "Up next"), the smaller ones are counted,
 * Needs you is said, and the one-liner closes the pane. Pure.
 */
object BriefCardRules {
    /** The focus as the pane draws it (the same rows as the brief's Today: a tick circle to complete each). */
    fun focusLines(s: BriefStory): List<BriefDayLine> = s.focus.take(s.length.focusFor(s.weekend)).mapIndexed { i, f ->
        val caption = caption(f.why)
        BriefDayLine(
            id = "f-" + (f.taskId ?: i.toString()), taskId = f.taskId, title = f.title, caption = caption,
            lit = f.why == OVERDUE,
            spoken = listOfNotNull("Focus", f.title, caption).joinToString(", "),
        )
    }

    /** "+ 4 smaller ones on Today" under the focus; "4 small ones on Today, nothing urgent" with none named; null for none. */
    fun focusMore(s: BriefStory): String? {
        val named = s.focus.take(s.length.focusFor(s.weekend)).size
        val smaller = s.smallerCount + (s.focus.size - named)
        if (smaller <= 0) return null
        val ones = if (smaller == 1) "1 smaller one" else "$smaller smaller ones"
        return if (named == 0) "Nothing urgent · ${ones.replace("smaller", "small")} on Today" else "+ $ones on Today"
    }

    /** Slice 4: who's waiting on you, urgent first (the pane's "Waiting on you" rows; [BriefAsk.urgent] lit). */
    fun askLines(s: BriefStory): List<BriefAsk> = s.asks

    /** "1 decision waiting in Needs you"; null for none. */
    fun needsYouLine(s: BriefStory): String? = when {
        s.needsYou <= 0 -> null
        s.needsYou == 1 -> "1 decision waiting in Needs you"
        else -> "${s.needsYou} decisions waiting in Needs you"
    }

    /** Today's card's focus line: "Focus · Renew passport · overdue", "+ 2 more" when more are named; null with none. */
    fun cardLine(s: BriefStory): String? {
        val named = s.focus.take(s.length.focusFor(s.weekend))
        val first = named.firstOrNull() ?: return null
        val why = caption(first.why)?.replaceFirstChar { it.lowercase() }
        return listOfNotNull("Focus", first.title, why, (named.size - 1).takeIf { it > 0 }?.let { "+ $it more" }).joinToString(" · ")
    }

    /** "it's overdue" → "Overdue", "it moves your goal, Q4" → "Moves your goal, Q4"; null for none. */
    fun caption(why: String?): String? {
        val w = why?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bare = when {
            w.startsWith("it's ") -> w.removePrefix("it's ")
            w.startsWith("it ") -> w.removePrefix("it ")
            else -> w
        }
        return bare.replaceFirstChar { it.uppercase() }
    }

    /** The lengths to choose from, [chosen] lit. */
    fun lengths(chosen: BriefLength): List<BriefLengthChoice> = BriefLength.entries.map { BriefLengthChoice(it.id, it.label, it == chosen) }

    private const val OVERDUE = "it's overdue"
}
