package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * "The mic should just add it" (Meka, 2026-10-17), slice 1: his own words asking to add a task or a shopping item are
 * done at once, never a card and never the AI. Non-AI and pure.
 *
 * "Add call the garage tomorrow", "remind me to ring Sam at 3pm", "put the bins out on my list", "I need to book the
 * MOT", "add milk and eggs to the shopping": the command words come off, the trailing (or leading) day and time are
 * read as Add event's title does ([EventTypingRules.parse]), and what's left is the title. Anything that isn't plainly
 * an add (a question, an event, a calendar, a checklist, errands, a goal, a pronoun on its own) is null and goes to
 * the AI exactly as before.
 *
 * Only Meka's own question reaches this (typed in Ask or said to Talk): other people's words never add anything
 * (ADR-006). What Navilife may do keeps **Ask first** to switch it back to a card he taps ([DirectAddSetting]).
 */

/** What an add asks for, before the day is read: a task's words, or shopping items. */
data class DirectAdd(val shopping: Boolean, val words: String)

/** Whether direct adds run at once (the default) or wait as a card ("Ask first" on What Navilife may do). */
data class DirectAddSetting(val askFirst: Boolean)

object DirectAddRules {
    const val ENTITY_ID = "direct_add"
    const val ASK_FIRST_FIELD = "askFirst"
    /** The policy rule his request is executed under (ADR-006 addendum 2026-10-17). */
    const val RULE_ID = "direct.add"
    /** Activity's source for a direct add (not "trust:", so an Undo leaves earned trust alone). */
    const val SOURCE = "direct:add"
    const val WHY = "You asked for it"
    const val DETAIL = "Asked in Navilife"
    /** Slice 2: said into the capture sheet's 🎙. */
    const val SPOKEN_DETAIL = "Said into Capture"

    const val ROW_TITLE = "Add what you ask for"
    const val ON_LINE = "“Add …”, “remind me to …”, “put … on the shopping” are added at once · Undo on screen and in Activity"
    const val OFF_LINE = "Asks first · a card you tap"
    const val JUST_ADD = "Just add it"
    const val ON_SAID = "Navilife will add what you ask for straight away, with Undo."
    const val OFF_SAID = "Navilife will show a card for each add you ask for."

    private const val MAX_WORDS = 200
    private const val MAX_ITEMS = 12

    private val opt = setOf(RegexOption.IGNORE_CASE)
    private val space = Regex("""\s+""")
    /** Politeness and the app's name in front ("hey Navilife, can you please …"). */
    private val polite = Regex(
        """^(?:(?:hey|ok|okay|oh|um|erm|right|so)[,\s]+)*(?:(?:meka|navilife|navi life|navy life)[,\s]+)?(?:(?:can|could|would|will) you\s+)?(?:please\s+)?""", opt,
    )
    private val tailPolite = Regex("""[\s,]+(?:please|thanks|thank you|for me)$""", opt)

    private const val LIST = """(?:my|the)\s+(?:shopping(?:\s+list)?|shopping|groceries|grocery\s+list)"""
    private val shoppingTo = Regex("""^(?:add|put|pop|stick|chuck)\s+(.+?)\s+(?:on|onto|to|in|into)\s+$LIST$""", opt)
    private val shoppingFor = Regex("""^(?:add\s+to|on)\s+$LIST[:,]?\s+(.+)$""", opt)

    private const val TASKS = """(?:my|the)\s+(?:to-?\s?do(?:\s+list)?|todo(?:\s+list)?|to-?dos|todos|tasks?(?:\s+list)?|list)"""
    private val taskOnList = Regex("""^(?:add|put|pop|stick)\s+(.+?)\s+(?:on|onto|to|in)\s+$TASKS(.*)$""", opt)
    private val addTask = Regex("""^add\s+(?:a\s+|new\s+|a\s+new\s+)?(?:task|to-?do|reminder)(?:\s+to|:|,)?\s+(.+)$""", opt)
    private val remindLead = Regex(
        """^remind\s+me\s+((?:on\s+)?(?:today|tonight|tomorrow|this\s+\w+|next\s+\w+|mon|tue|wed|thu|fri|sat|sun)\w*)\s+to\s+(.+)$""", opt,
    )
    private val remind = Regex("""^remind\s+me\s+(?:to\s+)?(.+)$""", opt)
    private val needTo = Regex("""^i\s+(?:need|have|must|ought|got)\s+to\s+(.+)$""", opt)
    private val iMust = Regex("""^i\s+must\s+(.+)$""", opt)
    private val plainAdd = Regex("""^(?:add|put down)\s+(.+)$""", opt)
    /** "Add a meeting…", "add an event…": not a task (the calendar is always a card, ADR-006). */
    private val notTask = Regex(
        """^(?:an?\s+|the\s+|my\s+|our\s+)?(?:event|meeting|appointment|alarm|timer|goal|promise|errand|booking|flight|leave|holiday|fact|win|gift|dinner|date\s+night|it|that|this|them|those|these|one|something|another|more|some)\b""", opt,
    )
    /** "… to my calendar", "… to the party checklist", "… to my errands": somewhere else, for the AI to propose. */
    private val elsewhere = Regex(
        """\s(?:to|on|in|into|onto)\s+(?:my|the|our)\s+(?:[\w']+\s+)?(?:calendar|diary|checklist|errands?|wins?|goals?|queue|train\s+queue|trip|ideas?|notes?|gifts?|vault|lists?\s+of\s+\w+)\b""", opt,
    )
    /** "Remind me what …", "remind me when …": a question. */
    private val remindQuestion = Regex("""^(?:what|when|where|who|why|how|if|whether|about)\b""", opt)
    private val leadingDay = Regex(
        """^((?:on\s+)?(?:today|tonight|tomorrow|tmrw|(?:this|next)\s+(?:mon|tue|wed|thu|fri|sat|sun)\w*|(?:mon|tue|wed|thu|fri)\w*))[,\s]+(.+)$""", opt,
    )
    private val byDay = Regex(
        """\s+by\s+((?:this\s+|next\s+)?(?:mon|tue|wed|thu|fri|sat|sun)\w*|today|tonight|tomorrow)$""", opt,
    )
    private val shoppingSplit = Regex("""\s*(?:,\s*(?:and\s+)?|\s+and\s+|\s*&\s*)""", opt)

    fun read(fields: Map<String, FieldValue>?): DirectAddSetting =
        DirectAddSetting(askFirst = fields?.get(ASK_FIRST_FIELD)?.boolOrNull == true)

    fun fields(askFirst: Boolean): Map<String, FieldValue> = mapOf(ASK_FIRST_FIELD to askFirst.fv())

    /** The row under Your lists on What Navilife may do; its button goes to the facade's `resetTrust`. */
    fun row(s: DirectAddSetting, paused: Boolean) = AutonomyRow(
        key = ENTITY_ID,
        title = ROW_TITLE,
        line = when {
            s.askFirst -> OFF_LINE
            paused -> AutonomyRules.PAUSED_LINE
            else -> ON_LINE
        },
        locked = false,
        action = if (s.askFirst) JUST_ADD else AutonomyRules.ASK_FIRST,
        onItsOwn = !s.askFirst && !paused,
    )

    fun switchedLine(askFirst: Boolean): String = if (askFirst) OFF_SAID else ON_SAID

    /** Whether [question] plainly asks to add something, and what; null for anything else (the AI's). */
    fun request(question: String): DirectAdd? {
        var q = question.replace('’', '\'').replace(space, " ").trim()
        if (q.isEmpty() || q.length > MAX_WORDS) return null
        val politeLead = polite.find(q)?.value.orEmpty()
        q = q.substring(politeLead.length).trim()
        // A question mark is only politeness after "can you …" ("can you add milk to the shopping?").
        if (q.endsWith('?')) {
            if (!Regex("""(?:can|could|would|will) you""", opt).containsMatchIn(politeLead)) return null
            q = q.dropLast(1).trimEnd()
        }
        q = q.trimEnd('.', '!').trim().replace(tailPolite, "").trim()
        if (q.isEmpty() || q.contains('?')) return null

        shoppingTo.find(q)?.let { return shopping(it.groupValues[1]) }
        shoppingFor.find(q)?.let { return shopping(it.groupValues[1]) }
        taskOnList.find(q)?.let { m ->
            // "Put the bins out on my list for tomorrow": what follows the list is still when.
            val after = m.groupValues[2].trim().removePrefix("for ").trim()
            return task(listOf(m.groupValues[1], after).filter { it.isNotEmpty() }.joinToString(" "))
        }
        addTask.find(q)?.let { return task(it.groupValues[1]) }
        remindLead.find(q)?.let { m -> return task(m.groupValues[2] + " " + m.groupValues[1]) }
        remind.find(q)?.let { m ->
            if (remindQuestion.containsMatchIn(m.groupValues[1])) return null
            return task(m.groupValues[1])
        }
        needTo.find(q)?.let { return task(it.groupValues[1]) }
        iMust.find(q)?.let { return task(it.groupValues[1]) }
        plainAdd.find(q)?.let { return task(it.groupValues[1]) }
        return null
    }

    private fun shopping(items: String): DirectAdd? {
        val w = items.trim().trimEnd(',', ':')
        if (w.isEmpty() || notTask.matches(w) || Regex("""^(?:it|that|this|them|those|these|some)$""", opt).matches(w)) return null
        return DirectAdd(shopping = true, words = w)
    }

    private fun task(words: String): DirectAdd? {
        val w = words.trim().trimEnd(',', ':', ';').removePrefix("to ").trim()
        if (w.isEmpty()) return null
        if (notTask.containsMatchIn(w) && notTask.find(w)?.range?.first == 0) return null
        if (elsewhere.containsMatchIn(" $w")) return null
        return DirectAdd(shopping = false, words = w)
    }

    /**
     * What [add] comes to on [today] at [nowMinute]: a task with its day and time read from its words ("tomorrow",
     * "Friday 3pm", "by Thursday"; a time already past today means tomorrow), or the shopping items. Null when nothing
     * would be left to add.
     */
    fun proposal(add: DirectAdd, today: Long, nowMinute: Int): AskProposal? {
        if (add.shopping) {
            val items = add.words.split(shoppingSplit).map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }
                .map { it.removePrefix("some ").removePrefix("a ").removePrefix("an ").trim() }.filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }.take(MAX_ITEMS)
            return if (items.isEmpty()) null else AskProposal.AddShopping(items)
        }
        // A day in front ("tomorrow call the garage") reads the same as at the end.
        var words = leadingDay.find(add.words)?.let { it.groupValues[2] + " " + it.groupValues[1] } ?: add.words
        words = byDay.replace(words) { " " + it.groupValues[1] }
        val typed = EventTypingRules.parse(words, today, nowMinute)
        // Only when-words ("add tomorrow"): nothing to add.
        if (typed == null && EventTypingRules.parse("x $words", today, nowMinute)?.title == "x") return null
        val title0 = (typed?.title ?: words).trim().trimEnd('.', ',', ';', ':').replace(Regex("""\s+(?:on|at|for|by)$""", opt), "").trim()
        if (title0.isEmpty()) return null
        val title = title(title0)
        var day = typed?.day
        val minute = typed?.minute?.takeIf { typed.allDay.not() }
        // A time with no day that has passed today means tomorrow (as Add event reads it).
        if (day == null && minute != null) day = if (minute <= nowMinute) today + 1 else today
        return AskProposal.AddTask(title, day, minute)
    }

    /** First letter up, at most [QuickCapture.MAX_TITLE] characters (cut at a word). */
    fun title(words: String): String {
        val t = words.trim()
        val cut = if (t.length <= QuickCapture.MAX_TITLE) t
        else t.substring(0, t.lastIndexOf(' ', QuickCapture.MAX_TITLE - 1).takeIf { it >= QuickCapture.MAX_TITLE / 2 } ?: QuickCapture.MAX_TITLE).trimEnd()
        return cut.replaceFirstChar { it.uppercaseChar() }
    }

    /** The answer's line with Undo: "Added “Call the garage” · Tomorrow" / "Added milk and eggs to shopping". */
    fun doneLine(p: AskProposal, today: Long): String = when (p) {
        is AskProposal.AddTask -> AskRules.doneLine(p, today) + (p.day?.let { " · " + TaskWhenRules.label(it, p.minute, today) } ?: "")
        else -> AskRules.doneLine(p, today)
    }
}
