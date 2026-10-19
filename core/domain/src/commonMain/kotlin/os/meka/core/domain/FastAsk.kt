package os.meka.core.domain

/** What Meka asked Ask or Talk about his fast ([FastAskRules.request]). */
enum class FastAsk { END, RESUME, STATUS }

/**
 * AI layer completeness: his fast by voice and in Ask's field — "end my fast", "I've broken my fast", "resume my
 * fast" (the few minutes End fast can be taken back), "how long left on my fast?", "am I fasting?", "when can I eat?"
 * — answered and done on the device by rules, no AI and no card: his own direct request about his own timer, the same
 * as tapping End fast on Today, and ending can be taken back for [FastingRules.RESUME_WINDOW_MS]. Starting a fast
 * stays with the AI's card (a goal and a start to read). A time in the words ("end my fast at 6pm") is a different
 * request and is left to the AI. Plain, non-AI rules.
 */
object FastAskRules {
    const val BUTTON = "Open Goals and habits"
    val link: HelpLink get() = HelpLink("goals", HelpPlace.GOALS, BUTTON)

    private fun words(q: String): String = q.lowercase()
        .replace('’', '\'')
        .replace(Regex("[^a-z0-9' ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun clean(w: String): String = w
        .removePrefix("hey navilife ").removePrefix("navilife ").removePrefix("please ").removePrefix("ok ").removePrefix("okay ")
        .removeSuffix(" please").removeSuffix(" now").trim()

    private const val FAST = "(my |the |this )?(fast|fasting)"

    private val END = listOf(
        Regex("^(end|stop|finish|break) $FAST$"),
        Regex("^(i'm|i am|im) (ending|stopping|breaking|finishing) $FAST$"),
        Regex("^(i've|i have|ive|i) (ended|stopped|broken|broke|finished) $FAST$"),
        Regex("^(i'm|i am|im) (done|finished) (with )?(fasting|my fast|the fast)$"),
        Regex("^(i'm|i am|im) (no longer|not) fasting( any ?more)?$"),
    )

    private val RESUME = listOf(
        Regex("^(resume|restart|continue|carry on with|carry on|undo ending|undo end) $FAST$"),
        Regex("^(i )?(didn't|did not|didnt) mean to (end|stop|break) $FAST$"),
        Regex("^(put|start) $FAST (back on|going again|again)$"),
    )

    private val STATUS = listOf(
        Regex("^how (long|much) (is |time is )?(left|to go|remaining) (on|of|in|for|until the end of) $FAST$"),
        Regex("^how (long|much) (have i got|do i have) (left )?(on|of|in) $FAST$"),
        Regex("^how long (have i been|am i|have i) (been )?fasting( for)?$"),
        Regex("^how long (until|till|til|before) (i can eat|my fast ends|the fast ends|the end of my fast|my fast is over|i break my fast)$"),
        Regex("^when (does|will|do) (my fast|the fast|i) (end|finish|break my fast)$"),
        Regex("^when can i (eat|eat again|break my fast|start eating)$"),
        Regex("^(am i|are we) fasting( now| right now| at the moment)?$"),
        Regex("^how('s| is) (my |the )fast( going)?$"),
        Regex("^(where am i|where's|where is) (with |on )?(my |the )fast$"),
        Regex("^(my |the )?fast (status|progress)$"),
    )

    /** A bare "how long left?" is about the fast only while one runs (nothing else answers it on the device). */
    private val BARE = Regex("^(how long|how much time) (is )?(left|to go|remaining)$")

    /** A time or day in the words makes it a change to the goal or a reminder: the AI's. */
    private val TIMED = Regex("\\b(at|until|till|by|in|tomorrow|tonight|today|on (mon|tue|wed|thu|fri|sat|sun))\\b \\S|\\b\\d")

    fun request(q: String, fasting: Boolean): FastAsk? {
        val t = clean(words(q))
        if (t.isEmpty() || t.length > 80) return null
        if (fasting && BARE.matches(t)) return FastAsk.STATUS
        if (STATUS.any { it.matches(t) }) return FastAsk.STATUS
        if (TIMED.containsMatchIn(t)) return null
        if (END.any { it.matches(t) }) return FastAsk.END
        if (RESUME.any { it.matches(t) }) return FastAsk.RESUME
        return null
    }

    private const val RESUME_HINT = "Ended by mistake? Say “resume my fast” in the next 10 minutes."

    /** Where his fast stands: running (how long in, how long to go) or not (the last one and the eating window). */
    fun status(view: FastingView, nowMs: Long): String {
        val cur = view.current ?: return notFasting(view)
        val elapsed = nowMs - cur.startedAtMs
        val left = cur.goalAtMs - nowMs
        if (cur.extended) {
            val day = FastingRules.dayOf(cur.startedAtMs, cur.goalAtMs, nowMs)
            return if (left > 0) "Yes · ${cur.title} · $day · ${FastingRules.longDuration(elapsed)} in · ${FastingRules.longDuration(left)} to go, until ${cur.goalWhen}."
            else "Yes · ${cur.title} · ${FastingRules.longDuration(elapsed)} in · goal reached ${cur.goalWhen}. Say “end my fast” when you eat."
        }
        return if (left > 0) "Yes · ${FastingRules.duration(elapsed)} in · ${FastingRules.duration(left)} to go to your ${cur.targetHours} h goal, at ${cur.goalWhen}."
        else "Yes · ${FastingRules.duration(elapsed)} in · you reached your ${cur.targetHours} h goal at ${cur.goalWhen}. Say “end my fast” when you eat."
    }

    /** "You're not fasting · last fast 16 h 20 m · goal reached · eating window open until 20:00." */
    fun notFasting(view: FastingView): String = listOfNotNull(
        "You're not fasting",
        view.last?.line?.removePrefix("Last fast ")?.let { "last fast $it" },
        view.windowLine.takeIf { it.isNotBlank() }?.replaceFirstChar { it.lowercaseChar() },
    ).joinToString(" · ") + "."

    /** After ending: what the fast came to, and how to take it back. */
    fun ended(after: FastingView): String {
        val last = after.last ?: return "Ended your fast."
        return "Ended your fast · ${last.line.removePrefix("Last fast ")}. $RESUME_HINT"
    }

    /** "End my fast" with none running. */
    fun nothingToEnd(view: FastingView): String = notFasting(view).removeSuffix(".") + " · nothing to end."

    /** After resuming: it runs again from its own start. */
    fun resumed(after: FastingView, nowMs: Long): String {
        val cur = after.current ?: return "Your fast is running again."
        return "Your fast is running again · ${FastingRules.longDuration(nowMs - cur.startedAtMs)} in, from ${cur.startedLine.removePrefix("Started ")}."
    }

    /** "Resume my fast" when it can't be: already running, or it ended too long ago. */
    fun cantResume(view: FastingView, nowMs: Long): String = when {
        view.current != null -> "You're still fasting · " + status(view, nowMs).removePrefix("Yes · ")
        view.last != null -> "Your last fast ended more than 10 minutes ago, so it can't be resumed · start a new one in Goals and habits, or say “start a 16-hour fast”."
        else -> "There's no fast to resume · start one in Goals and habits, or say “start a 16-hour fast”."
    }
}
