package os.meka.core.domain

/**
 * Routines, slice 4 (2026-11-07): New routine said or typed in one sentence (the approved mockup's New routine view).
 * "Every Sunday at 7pm, show my bills for the week, plan next week, tidy my inbox and remind me about bins" becomes
 * WHEN Sundays 19:00 · STARTS WITH A nudge and four steps, each with its Navilife place and a label he can tap to change
 * (Just show · Asks you · Obi does it), then Save routine.
 *
 * Plain, non-AI rules on the device: nothing he says leaves his own synced data. **The hard rule holds:** a part of the
 * sentence that would send a message, pay, book or trade ([RoutineRules.refusal]) never becomes a step; it is named
 * under "Left out" so he knows. Obi does it is offered only where Navilife already acts by itself (Inbox autopilot).
 *
 * The draft is pure ([draft]); the apps hold only the words and the labels he chose (codes, comma-separated), and Save
 * hands those two strings to the core ([Routines.addFromSentence]), which reads the sentence again the same way.
 */
data class RoutineDraftStep(
    val index: Int,
    /** "1", "2". */
    val number: String,
    val title: String,
    /** The Navilife place it uses ("Money", "Inbox autopilot"). */
    val place: String,
    val mode: RoutineStepMode,
    val badge: String,
    /** The code a tap on the label moves to ("ask"); null when it has only one choice. */
    val nextCode: String?,
    val spoken: String,
) {
    val modeCode: String get() = mode.code
}

data class RoutineDraft(
    /** The words he said or typed, as given. */
    val text: String,
    /** The name it will be saved with ("Sunday evening", or what he called it). */
    val name: String,
    val schedule: RoutineSchedule?,
    /** The WHEN tile: "Sundays 19:00", "Weekdays 07:00", "Every day 21:00", "Payday 09:00". */
    val whenTile: String,
    /** The STARTS WITH tile. */
    val startsWith: String,
    val steps: List<RoutineDraftStep>,
    /** "Left out: Text Sam I'm late · routines never send messages, pay, book or trade", or null. */
    val leftOut: String?,
    /** What's missing before it can be saved ("Say when it runs…"), or null when Save is ready. */
    val problem: String?,
    /** The labels as codes, comma-separated, for Save ("show,ask,does,show"). */
    val modes: String,
) {
    val canSave: Boolean get() = problem == null
}

object RoutineSentence {
    const val NEW = "+ New"
    const val NEW_SPOKEN = "New routine"
    const val TITLE = "New routine"
    const val HINT = "Every Sunday at 7pm, show my bills for the week, plan next week, tidy my inbox and remind me about bins"
    const val FOOT = "Say it or type it. Obi turns it into steps you can check."
    const val MADE = "Obi made these steps"
    const val WHEN_TILE = "When"
    const val STARTS_TILE = "Starts with"
    const val NUDGE = "A nudge"
    const val NOTE = "Tap a step's label to change it: Just show · Asks you · Obi does it. Sending messages or paying is never offered."
    const val SAVE = "Save routine"
    const val MIC_SPOKEN = "Say the routine"
    const val MIC_DONE = "Done speaking"
    const val HEARD_NOTHING = "Didn't catch that. Try again, or type it."
    const val NEED_WORDS = "Say or type what it should do and when, like the example above."
    const val NEED_WHEN = "Say when it runs, like \"every Sunday at 7pm\" or \"weekdays at 7\"."
    const val NEED_DAY = "Say which day, like \"every Sunday\"."
    const val NEED_STEPS = "Say what it should do, like \"show my bills and plan next week\"."
    const val ONLY_REFUSED = "Routines never send messages, pay, book or trade, so there's nothing left to save."
    const val MAX_TEXT = 400
    const val MAX_TITLE = 80

    // ---- When ----

    private const val DAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    private val dayWord = Regex("""\b(?:(?:every|each|on)\s+)?($DAYS)s?\b""", RegexOption.IGNORE_CASE)
    private val weekdays = Regex(
        """\b(?:(?:every|each|on)\s+)?(?:week\s?days?|weekdays|work\s?days|working\s+days)\b|\bmonday\s+(?:to|-|through|till|until)\s+friday\b|\bmon\s*-\s*fri\b""",
        RegexOption.IGNORE_CASE,
    )
    private val weekends = Regex("""\b(?:(?:every|each|on|at)\s+(?:the\s+)?)?weekends?\b""", RegexOption.IGNORE_CASE)
    private val daily = Regex("""\b(?:daily|every\s?day|each\s+day|every\s+single\s+day)\b""", RegexOption.IGNORE_CASE)
    private val payday = Regex("""\b(?:(?:on|every)\s+)?pay\s?days?\b|\bwhen\s+i\s+(?:get|am|'m)\s+paid\b|\bwhen\s+i'm\s+paid\b""", RegexOption.IGNORE_CASE)
    private val weekly = Regex("""\b(?:weekly|every\s+week|once\s+a\s+week)\b""", RegexOption.IGNORE_CASE)
    private val period = Regex(
        """\b(?:(?:every|each|in\s+the|on|at)\s+)?(morning|mornings|afternoon|afternoons|evening|evenings|night|nights|tonight|lunchtime|bedtime)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val clockAmPm = Regex("""\b(?:(?:at|from|around|by)\s+)?(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)(?!\w)""", RegexOption.IGNORE_CASE)
    private val clock24 = Regex("""\b(?:(?:at|from|around|by)\s+)?([01]?\d|2[0-3])[:.]([0-5]\d)\b""", RegexOption.IGNORE_CASE)
    private val clockAt = Regex(
        """\b(?:at|around)\s+(\d{1,2})(?:\s*o'?clock)?\b(?!\s*(?:minutes?|mins?|hours?|days?|lessons?|things?|steps?|%|pounds?|quid))""",
        RegexOption.IGNORE_CASE,
    )
    private val noon = Regex("""\b(?:(?:at|around)\s+)?(noon|midday|midnight)\b""", RegexOption.IGNORE_CASE)
    private val called = Regex(
        """\b(?:called|named)\s+(?:["“‘']([^"”’']{1,60})["”’']|([A-Za-z][A-Za-z0-9' ]{0,40}?))(?=\s*(?:,|\.|:|;|$|\s+(?:every|each|on|at|that|which|to|and)\b))""",
        RegexOption.IGNORE_CASE,
    )

    private data class When(val days: Set<Int>, val payday: Boolean, val minute: Int?, val periodMinute: Int?, val weekly: Boolean)

    private fun findWhen(text: String): When {
        val isPayday = payday.containsMatchIn(text)
        val days = mutableSetOf<Int>()
        if (daily.containsMatchIn(text)) days += 1..7
        if (weekdays.containsMatchIn(text)) days += Recurrence.WEEKDAYS
        if (weekends.containsMatchIn(text)) days += setOf(6, 7)
        dayWord.findAll(text).forEach { m ->
            val d = CivilDate.DAY_LONG.indexOfFirst { it.equals(m.groupValues[1], ignoreCase = true) } + 1
            if (d in 1..7) days += d
        }
        val p = period.find(text)?.groupValues?.get(1)?.lowercase()
        val periodMinute = when (p) {
            null -> null
            "morning", "mornings" -> 7 * 60
            "afternoon", "afternoons" -> 15 * 60
            "lunchtime" -> 12 * 60 + 30
            "evening", "evenings" -> 19 * 60
            else -> 21 * 60 // night, tonight, bedtime
        }
        val evening = p != null && p !in setOf("morning", "mornings")
        val morning = p == "morning" || p == "mornings"
        val minute = clockAmPm.find(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            if (h !in 1..12 || min > 59) null else {
                val pm = m.groupValues[3].lowercase().startsWith("p")
                (h % 12 + if (pm) 12 else 0) * 60 + min
            }
        } ?: clock24.find(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            hourGuess(h, evening, morning) * 60 + min
        } ?: noon.find(text)?.let { m ->
            if (m.groupValues[1].equals("midnight", ignoreCase = true)) 0 else 12 * 60
        } ?: clockAt.find(text)?.let { m ->
            val h = m.groupValues[1].toInt()
            if (h > 23) null else hourGuess(h, evening, morning) * 60
        }
        return When(days, isPayday, minute, periodMinute, weekly.containsMatchIn(text))
    }

    /** A bare hour: "at 7" in the evening is 19:00, in the morning 07:00; 1–6 without either is the afternoon/evening. */
    private fun hourGuess(h: Int, evening: Boolean, morning: Boolean): Int = when {
        h >= 13 || h == 0 -> h
        h == 12 -> 12
        evening && h < 12 -> h + 12
        morning -> h
        h in 1..6 -> h + 12
        else -> h
    }

    /** Takes every "when" part out of [text], so only the jobs are left. */
    private fun withoutWhen(text: String): String = listOf(called, payday, weekdays, weekends, daily, weekly, dayWord, clockAmPm, clock24, noon, clockAt, period)
        .fold(text) { t, r -> r.replace(t, " ") }

    // ---- Steps ----

    private val leadIn = Regex(
        """^(?:(?:hey|ok|okay)\s+)?(?:obi[,:]?\s+)?(?:(?:please|can you|could you|i want(?: you)? to|i'd like(?: you)? to|i would like(?: you)? to|let's)\s+)?(?:(?:make|create|add|set up|start)\s+(?:me\s+)?(?:a\s+|an\s+)?(?:new\s+)?routine\s*(?:that|which|to|where|for)?\s*)?""",
        RegexOption.IGNORE_CASE,
    )
    private val connectors = setOf("and", "then", "also", "and then", "after that", "to", "on", "at", "in", "every", "each", "the", "a", "please", "obi", "routine", "new routine", "that", "which", ",", "-", "of", "for", "from", "around", "by")

    /** The words a job can start with, so "… plan next week and tidy my inbox" splits at "and" but "bins and car dates" doesn't. */
    private val VERBS = setOf(
        "show", "see", "look", "list", "give", "read", "tell", "remind", "check", "plan", "tidy", "clean", "clear", "sort",
        "archive", "file", "tick", "move", "review", "go", "open", "update", "prepare", "prep", "pick", "choose", "decide",
        "set", "put", "write", "note", "brief", "summarise", "summarize", "walk", "stretch", "start", "do", "take", "make",
        "water", "empty", "pack", "lay", "charge", "study", "revise", "practise", "practice", "drink", "meditate", "log",
    )
    private val SHOW_VERBS = setOf("show", "see", "look", "list", "give", "read", "tell", "remind", "brief", "summarise", "summarize", "open", "what", "how", "any", "my", "the", "weather", "bins", "bin", "trains", "bills")
    private val TIDY_VERBS = setOf("tidy", "clean", "clear", "sort", "archive", "file", "empty")

    private fun clauses(text: String): List<String> {
        val parts = text.split(Regex("""[,;:\n]|\.\s|\.$|\s+then\s+|\s+after\s+that\s+|\s+and\s+then\s+""", RegexOption.IGNORE_CASE))
        return parts.flatMap { part ->
            // "plan next week and tidy my inbox" splits; "bins and car dates" stays one.
            val out = mutableListOf<String>()
            var rest = part
            while (true) {
                val m = Regex("""\s+and\s+(\w+)""", RegexOption.IGNORE_CASE).findAll(rest)
                    .firstOrNull { it.groupValues[1].lowercase() in VERBS } ?: break
                out += rest.substring(0, m.range.first)
                rest = rest.substring(m.range.first).replaceFirst(Regex("""^\s+and\s+""", RegexOption.IGNORE_CASE), "")
            }
            out + rest
        }
    }

    /** One clause tidied into a step's title: connectors off the ends, the first letter capitalised. */
    private fun tidyTitle(raw: String): String? {
        var t = raw.replace(Regex("""\s+"""), " ").trim().trim(',', '.', ';', ':', '-', '!', '?').trim()
        var changed = true
        while (changed && t.isNotEmpty()) {
            changed = false
            val lower = t.lowercase()
            for (c in connectors) {
                if (lower == c) { t = ""; changed = true; break }
                if (lower.startsWith("$c ")) { t = t.substring(c.length).trim(); changed = true; break }
                if (lower.endsWith(" $c")) { t = t.substring(0, t.length - c.length).trim(); changed = true; break }
            }
            t = t.trim(',', '.', ';', ':', '-', '!', '?').trim()
        }
        if (t.isEmpty() || t.none { it.isLetter() }) return null
        val first = t.first().uppercaseChar() + t.substring(1)
        return if (first.length > MAX_TITLE) first.take(MAX_TITLE).trimEnd() else first
    }

    /** "check my emails" is about the inbox, not sending one: the possessive noun reads as the inbox before the rule. */
    private fun inboxWords(text: String): String =
        Regex("""\b(my|the)\s+(e-?mails?|mail|mailbox)\b""", RegexOption.IGNORE_CASE).replace(text) { "${it.groupValues[1]} inbox" }

    /** A word in [t]: short words whole (with a plural "s"), so "car" isn't "card" and "bin" isn't "binder"; longer ones as a start. */
    private fun has(t: String, vararg words: String): Boolean = words.any { w ->
        val e = Regex.escape(w)
        Regex(if (w.length <= 3) """\b${e}s?\b""" else """\b$e""", RegexOption.IGNORE_CASE).containsMatchIn(t)
    }

    /** The Navilife place a step uses, from its words. */
    fun placeOf(title: String): RoutinePlace = when {
        has(title, "study", "lesson", "course", "module", "revis", "homework") -> RoutinePlace.STUDY
        has(title, "inbox", "newsletter", "unsubscribe") -> RoutinePlace.INBOX
        has(title, "saving", "goal", "budget", "pot") -> RoutinePlace.PLAN
        has(title, "bill", "money", "spend", "balance", "account", "bank", "card", "came in", "paid", "income", "salary") -> RoutinePlace.MONEY
        has(title, "plan", "week", "board", "someday", "next week") -> RoutinePlace.WEEK_BOARD
        has(title, "calendar", "diary", "meeting", "event", "appointment", "schedule", "agenda") -> RoutinePlace.CALENDAR
        has(title, "bin", "car", "mot", "house", "home", "garden", "laundry", "washing", "dishwasher", "plants") -> RoutinePlace.HOME
        has(title, "due", "needs you", "approval", "waiting", "overdue", "to-do", "todo", "tasks") -> RoutinePlace.NEEDS_YOU
        else -> RoutinePlace.TODAY
    }

    /** How a step runs, from its first word: show-words just show, tidying the inbox is Obi's, the rest ask. */
    fun modeOf(title: String, place: RoutinePlace): RoutineStepMode {
        val first = title.lowercase().split(' ').firstOrNull().orEmpty()
        return when {
            place.obiMayDo && first in TIDY_VERBS -> RoutineStepMode.DOES
            first in SHOW_VERBS -> RoutineStepMode.SHOW
            else -> RoutineStepMode.ASK
        }
    }

    // ---- The draft ----

    private val PLURAL = listOf("Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays")

    /** The WHEN tile: "Sundays 19:00", "Weekdays 07:00", "Every day 21:00", "Weekends 10:00", "Payday 09:00", "Mon · Thu 18:30". */
    fun whenTile(s: RoutineSchedule): String {
        val at = LocalClock.formatMinute(s.minute)
        val day = when {
            s.payday -> "Payday"
            s.days.size == 7 -> "Every day"
            s.days == Recurrence.WEEKDAYS -> "Weekdays"
            s.days == setOf(6, 7) -> "Weekends"
            s.days.size == 1 -> PLURAL[s.days.first() - 1]
            else -> s.days.sorted().joinToString(" · ") { LocalClock.DAY_SHORT[it - 1] }
        }
        return "$day $at"
    }

    /** The name it's saved with when he didn't give one: "Sunday evening", "Weekday morning", "Payday morning". */
    fun nameFor(s: RoutineSchedule): String {
        val part = when {
            s.minute < 12 * 60 -> "morning"
            s.minute < 17 * 60 -> "afternoon"
            s.minute < 21 * 60 -> "evening"
            else -> "night"
        }
        val day = when {
            s.payday -> "Payday"
            s.days.size == 7 -> "Every"
            s.days == Recurrence.WEEKDAYS -> "Weekday"
            s.days == setOf(6, 7) -> "Weekend"
            s.days.size == 1 -> CivilDate.DAY_LONG[s.days.first() - 1]
            else -> s.days.sorted().joinToString(" and ") { LocalClock.DAY_SHORT[it - 1] }
        }
        return "$day $part"
    }

    /**
     * The draft for [text] (what he said or typed), with [modes] the labels he chose by tapping (codes, comma-separated,
     * by step; blank or unknown codes keep Obi's guess; a label a step can't have is ignored). Pure.
     */
    fun draft(text: String, modes: String = ""): RoutineDraft {
        val words = text.trim().take(MAX_TEXT)
        if (words.isBlank()) return RoutineDraft(text, "", null, "", NUDGE, emptyList(), null, NEED_WORDS, "")
        val w = findWhen(words)
        val minute = w.minute ?: w.periodMinute ?: if (w.days.isNotEmpty() || w.payday) 9 * 60 else null
        val days = when {
            w.payday -> emptySet()
            w.days.isNotEmpty() -> w.days
            w.weekly -> emptySet()
            minute != null -> (1..7).toSet() // "at 9pm, tidy my inbox": every day
            else -> emptySet()
        }
        val schedule = if (minute == null || (!w.payday && days.isEmpty())) null else RoutineSchedule(days, minute, w.payday)
        val givenName = called.find(words)?.let { (it.groupValues[1].ifBlank { it.groupValues[2] }).trim() }?.takeIf { it.isNotEmpty() }
        val body = leadIn.replace(inboxWords(words).trim(), "")
        val chosen = modes.split(',').map { it.trim() }
        val steps = mutableListOf<RoutineStep>()
        val refused = mutableListOf<String>()
        for (c in clauses(withoutWhen(body))) {
            val title = tidyTitle(c) ?: continue
            if (RoutineRules.refusal(title) != null) { refused += title; continue }
            if (steps.size >= RoutineRules.MAX_STEPS) break
            val place = placeOf(title)
            steps += RoutineStep(title, place, modeOf(title, place))
        }
        val labelled = steps.mapIndexed { i, st ->
            val want = RoutineStepMode.entries.firstOrNull { it.code == chosen.getOrNull(i) }
            if (want != null && want in RoutineRules.modesFor(st.place)) st.copy(mode = want) else st
        }
        val rows = labelled.mapIndexed { i, st ->
            val next = RoutineRules.nextMode(st)
            RoutineDraftStep(
                i, "${i + 1}", st.title, st.place.label, st.mode, st.mode.label, next?.code,
                spoken = "Step ${i + 1}: ${st.title}, in ${st.place.label}. ${st.mode.label}" + (next?.let { ". Tap to change to ${it.label}" } ?: ""),
            )
        }
        val problem = when {
            labelled.isEmpty() && refused.isNotEmpty() -> ONLY_REFUSED
            schedule == null && w.weekly -> NEED_DAY
            schedule == null -> NEED_WHEN
            labelled.isEmpty() -> NEED_STEPS
            else -> null
        }
        val name = (givenName ?: schedule?.let { nameFor(it) } ?: "").take(RoutineRules.MAX_NAME)
        return RoutineDraft(
            text, name, schedule, schedule?.let { whenTile(it) } ?: "—", NUDGE, rows,
            leftOut = refused.takeIf { it.isNotEmpty() }?.let { "Left out: ${it.joinToString(" · ")}. ${RoutineRules.REFUSED_LINE}." },
            problem = problem,
            modes = labelled.joinToString(",") { it.mode.code },
        )
    }

    /** The labels after a tap on step [index]'s label in [d], for the next [draft] call. */
    fun relabel(d: RoutineDraft, index: Int): String {
        val codes = d.steps.map { it.modeCode }.toMutableList()
        val next = d.steps.getOrNull(index)?.nextCode ?: return d.modes
        codes[index] = next
        return codes.joinToString(",")
    }

    /** The steps a saved draft writes. */
    internal fun steps(d: RoutineDraft): List<RoutineStep> = d.steps.map { st ->
        RoutineStep(st.title, RoutinePlace.entries.first { it.label == st.place }, st.mode)
    }

    /** "Saved Sunday evening · first run Sun 8 Nov at 19:00", "… first run tonight at 21:00", "… first run on payday". */
    fun savedLine(name: String, s: RoutineSchedule, today: Long, nowMinute: Int, payday: Long? = null): String {
        val at = LocalClock.formatMinute(s.minute)
        val day = RoutineRules.nextDay(s, today, nowMinute, payday) ?: return "Saved $name · first run on payday at $at"
        val word = when (day - today) {
            0L -> if (s.minute >= 18 * 60) "tonight" else "today"
            1L -> "tomorrow"
            else -> CivilDate.shortLabel(day)
        }
        return "Saved $name · first run $word at $at"
    }
}
