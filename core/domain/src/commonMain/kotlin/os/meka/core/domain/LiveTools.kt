package os.meka.core.domain

/**
 * Ada's Live mode, slice 5 (non-AI, pure): **Navilife's own tools in a Live conversation**.
 *
 * Besides the web search ([LiveTalkRules.SEARCH_TOOL]) a Live session has two tools, both answered on the device
 * where his data lives (nothing of his calendar or tasks is read on Navilife's server):
 * - [LOOK_UP_TOOL] (read-only): the day or days he asks about ("Thursday", "next week", "the 23rd", understood as Ask
 *   understands them, [DayAskRules.range]) from his own calendar and tasks, with that day's trains when it's an office
 *   day; their tasks and movable events keep handles (t1, e1) so a move or a done can still be offered.
 * - [PROPOSE_TOOL] (proposing): one of Ask's own actions ([AskRules.KINDS]) with Ask's fields; it becomes Ask's
 *   usual card exactly as a typed answer's proposal does ([AskRules.card]) and only when the policy gate allows it
 *   ([os.meka.core.facade.AskPolicy] on the facade). It never runs by itself, not even under earned trust: he taps it.
 *
 * Handles stay on the device. The day the session started with carries today's; a look-up
 * adds more, and [merge] keeps every handle meaning the same task or event for the whole session.
 */
object LiveToolRules {
    const val LOOK_UP_TOOL = "look_up_my_days"
    const val PROPOSE_TOOL = "propose_action"

    /** Cards one Live turn offers at most (Ask's own limit). */
    const val MAX_CARDS = AskRules.MAX_CARDS

    /** The look-up's argument: the day or days in his words. */
    const val WHEN_ARG = "when"

    /** What the model is told when a look-up names no day it can read (or only today, which it already has). */
    const val NO_DAYS = "Navilife couldn't tell which day that is. Today is already in the <today> block; for another day ask with a day such as \"tomorrow\", \"Thursday\", \"next week\" or \"23 Oct\"."
    /** ... when the device couldn't read his data just now. */
    const val LOOK_UP_FAILED = "Navilife couldn't read his calendar just now. Say you can't see that day at the moment."
    /** ... when a proposal isn't one Navilife can offer (unknown kind, a handle it never sent, a bad day or time). */
    const val PROPOSE_UNUSABLE = "Navilife couldn't turn that into a card: use one of the listed kinds with its fields, a task or event only by its ref (t1, e1) from the <today> block or a look-up, dates as YYYY-MM-DD and times as HH:MM. Nothing was offered."
    /** ... when the policy gate refuses it (never: send, pay, trade, share, file email). */
    const val PROPOSE_REFUSED = "Navilife doesn't offer that: it never sends messages or emails, pays, books, trades or shares anything. Nothing was offered; tell him plainly."
    /** ... when this turn already has its cards. */
    const val PROPOSE_FULL = "This turn already has three cards on screen. Don't offer more until he has looked at those."

    /** What the model is told for a card now on his screen. Nothing has happened: he taps it. */
    fun offered(line: String): String =
        "Shown on his screen as a card for him to tap: \"${line.replace(Regex("""\s+"""), " ").trim().take(AskRules.MAX_LINE)}\". " +
            "Nothing has been done yet. Say in a few words that it's there to tap; never say it's done."

    /** One proposal as the tool's arguments gave it (ints may arrive as numbers or strings), or null without a known kind. */
    fun raw(args: Map<String, String>): AskRawAction? {
        val kind = args["kind"]?.trim()?.lowercase()?.takeIf { it in AskRules.KINDS } ?: return null
        fun s(k: String) = args[k]?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() }
        fun n(k: String) = s(k)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= 100_000 }?.toInt()
        return AskRawAction(kind, ref = s("ref"), title = s("title")?.take(AskRules.MAX_TITLE * 2), date = s("date"), time = s("time"), hours = n("hours"), minutes = n("minutes"))
    }

    /** The days a look-up's [whenText] names (never only today), as Ask reads a question. */
    fun range(whenText: String, today: Long): AskDays? = DayAskRules.range(whenText.take(AskRules.MAX_QUESTION), today)

    /**
     * [extra] (a context built with a looked-up day) folded into [base] (the session's so far): a task or event already
     * known keeps its handle, a new one gets the next free one ("t7", "e4"), and the look-up's own lines come back
     * with those handles ([Merged.lines]: its days, events and tasks; [extra] is built without today's own lines). [Merged.context] is
     * untrusted when either is (event titles are other people's words).
     */
    fun merge(base: AskContext, extra: AskContext): Merged {
        val tasks = LinkedHashMap(base.taskIds)
        val events = LinkedHashMap(base.eventRefs)
        fun taskRef(ref: String): String {
            val id = extra.taskIds[ref] ?: return ""
            tasks.entries.firstOrNull { it.value == id }?.let { return it.key }
            var n = tasks.size + 1
            while ("t$n" in tasks) n++
            if (n > 999) return ""
            return "t$n".also { tasks[it] = id }
        }
        fun eventRef(ref: String): String {
            val e = extra.eventRefs[ref] ?: return ""
            events.entries.firstOrNull { it.value.eventId == e.eventId }?.let { return it.key }
            var n = events.size + 1
            while ("e$n" in events) n++
            if (n > 99) return ""
            return "e$n".also { events[it] = e }
        }
        val lines = extra.items
            .filter { it.kind == AskItemKind.DAYS || it.kind == AskItemKind.EVENT || it.kind == AskItemKind.TASK || it.kind == AskItemKind.DONE }
            .map { i ->
                val ref = when {
                    i.ref.startsWith("t") -> taskRef(i.ref)
                    i.ref.startsWith("e") -> eventRef(i.ref)
                    else -> ""
                }
                i.copy(ref = ref)
            }
        val context = base.copy(
            taskIds = tasks, eventRefs = events,
            items = base.items + lines.filter { l -> base.items.none { it.kind == l.kind && it.line == l.line } },
            untrusted = base.untrusted || extra.untrusted,
        )
        return Merged(context, lines)
    }

    data class Merged(val context: AskContext, val lines: List<AskItem>)

    /** The look-up's answer to the model: one line each, as the <today> block writes them ("event e3: …"). */
    fun lookUpAnswer(lines: List<AskItem>): String =
        if (lines.isEmpty()) "Nothing in his calendar or tasks for that day."
        else lines.joinToString("\n") { i -> "${i.kind.wire}${if (i.ref.isNotEmpty()) " ${i.ref}" else ""}: ${i.line}" }

    /**
     * One Live turn as Ask shows it: what Ada said, its sources, and the cards she offered (at most [MAX_CARDS], no two
     * alike). Null when she said nothing and offered nothing.
     */
    fun turnAnswer(said: String, sources: List<AskSource>, cards: List<AskCard>): AskOutcome? {
        val kept = cards.distinctBy { it.proposal }.take(MAX_CARDS)
        val text = said.replace(Regex("""\s+"""), " ").trim().take(AskRules.MAX_ANSWER)
        if (text.isEmpty() && kept.isEmpty()) return null
        return AskOutcome.Answered(AskAnswer(text.ifEmpty { CARDS_ONLY }, kept, sources = sources))
    }

    /** A turn with cards and no words heard of hers. */
    const val CARDS_ONLY = "Here it is to tap."
}

/** What one Live proposal came to: the [card] now on his screen (null: none), and what the model is told ([reply]). */
data class LiveOffer(val card: AskCard?, val reply: String)
