package os.meka.core.domain

/*
 * Ask MEKA (build plan V1, AI layer slice 3; ADR-006 §2–3), the device's side. Non-AI and pure.
 *
 * A question goes to MEKA's server with a short, plain-text picture of today ([AskContext]): Needs you, the day's tasks,
 * what's done and the calendar. Tasks are named by short handles ("t1", "t2") that stay on this device; no id, note or
 * address leaves it. The model answers in words and may propose a few of MEKA's own actions ([AskRawAction]); this
 * file turns each proposal into a card ([AskCard]) only when it is one MEKA knows, about a task it sent, on a sensible
 * day and time. Nothing a model proposes runs by itself: a card does its thing only when Meka taps it, like any button.
 */

/** What a line of the context is about. The wire names are what the server and the model see. */
enum class AskItemKind(val wire: String) { NEEDS_YOU("needs_you"), TASK("task"), DONE("done"), EVENT("event"), WEATHER("weather"), SHOPPING("shopping") }

/** One line of what MEKA sends with a question. [ref] is a task's handle on this device ("t1"), empty for others. */
data class AskItem(val ref: String, val kind: AskItemKind, val line: String)

/**
 * What MEKA sends with a question: the date (ISO, so the model can write dates back), a readable "now", and the lines.
 * [taskIds] maps each handle back to its task and never leaves the device. [untrusted] says whether any line came from
 * someone else (a calendar can hold other people's invitations), so the policy treats proposals as untrusted (ADR-006).
 */
data class AskContext(
    val dateIso: String,
    val nowLine: String,
    val items: List<AskItem>,
    val taskIds: Map<String, String>,
    val untrusted: Boolean,
) {
    companion object {
        val EMPTY = AskContext("", "", emptyList(), emptyMap(), untrusted = false)
    }
}

/** A proposal exactly as the model wrote it (shape only; [AskRules.card] decides whether it becomes a card). */
data class AskRawAction(
    val kind: String,
    val ref: String? = null,
    val title: String? = null,
    val date: String? = null,
    val time: String? = null,
    val hours: Int? = null,
    val minutes: Int? = null,
)

/** One of MEKA's own actions, checked and ready to run on Meka's tap. Days are local epoch days. */
sealed interface AskProposal {
    data class AddTask(val title: String, val day: Long?, val minute: Int?) : AskProposal
    data class CompleteTask(val taskId: String, val title: String) : AskProposal
    data class MoveTask(val taskId: String, val title: String, val day: Long, val minute: Int?) : AskProposal
    data class StartFast(val hours: Int) : AskProposal
    data class Timer(val minutes: Int) : AskProposal
    data class Alarm(val minute: Int) : AskProposal
    /** Adds [items] to the shopping list (the list's own add: a name already to buy stays one row, a got one comes back). */
    data class AddShopping(val items: List<String>) : AskProposal
}

/** A proposal as Ask shows it: "Add “Call the dentist” · Tomorrow · 09:00" with its button ("Add"). */
data class AskCard(val proposal: AskProposal, val line: String, val button: String)

/** An answer as Ask shows it: the words, and up to [AskRules.MAX_CARDS] cards. */
data class AskAnswer(val text: String, val cards: List<AskCard>)

/** What asking came to: an answer, or why there is none (AI off, the month's budget spent, offline, a failure). */
sealed interface AskOutcome {
    data class Answered(val answer: AskAnswer) : AskOutcome
    data class Unavailable(val line: String) : AskOutcome
}

/**
 * How to take back what an Ask card did (the undo bar's Undo): the task it added goes, a ticked-off or moved task gets
 * its state back (only while it is still as the card left it), the fast it started is thrown away while it still runs,
 * the timer or alarm is cancelled.
 */
sealed interface AskUndo {
    data class RemoveTask(val taskId: String) : AskUndo
    data class PutBack(val taskId: String, val before: TaskTiming, val after: TaskTiming) : AskUndo
    data class DiscardFast(val fastId: String) : AskUndo
    data class CancelAlarm(val alarmId: String) : AskUndo
    /** The shopping card's things: [added] ones leave the list, [revived] ones (back from Got) go back under Got. */
    data class TakeBackShopping(val added: List<String>, val revived: List<String>) : AskUndo
}

/** What a tapped card did: the undo bar's line ("Added “Milk”") and how to take it back. */
data class AskDone(val line: String, val undo: AskUndo?)

/**
 * What Ask shows about MEKA's AI under its field (`POST /v1/ai/status`): "On · $1.20 of $20 this month". [lit] when it
 * needs a look (most of the month's budget used, used up, not answering); [canAsk] false when asking can't work (off,
 * used up, not connected), so Ask leads with Search instead.
 */
data class AiStatusView(val line: String, val lit: Boolean, val canAsk: Boolean)

object AskRules {
    const val MAX_QUESTION = 500
    const val MAX_ITEMS = 60
    const val MAX_LINE = 160
    const val MAX_ANSWER = 1200
    const val MAX_CARDS = 3
    const val MAX_TITLE = 120
    /** Proposals may plan up to two years ahead, like When. */
    const val MAX_DAYS_AHEAD = 730
    const val FAST_MIN_HOURS = 12
    const val FAST_MAX_HOURS = FastingRules.MAX_TARGET_HOURS
    const val TIMER_MAX_MIN = 24 * 60
    /** Things one shopping card adds at most. */
    const val MAX_SHOPPING = 10

    /** The kinds of proposal MEKA understands; anything else is dropped. Wire names, never renamed. */
    val KINDS = listOf("add_task", "complete_task", "move_task", "start_fast", "set_timer", "set_alarm", "add_shopping")

    /** The question as sent: one line, trimmed, at most [MAX_QUESTION] characters; null when there's nothing to ask. */
    fun question(text: String?): String? =
        text?.replace(Regex("""\s+"""), " ")?.trim()?.take(MAX_QUESTION)?.takeIf { it.isNotEmpty() }

    /**
     * Today as MEKA describes it to the model: Needs you first, then Up next and the day's other tasks, what's done and
     * the calendar (finished events included, marked). At most [MAX_ITEMS] lines of at most [MAX_LINE] characters.
     */
    fun context(today: Today, nowMs: Long, cal: LocalCalendar, weather: List<String> = emptyList(), shopping: ShoppingView? = null): AskContext {
        val day = cal.epochDayOf(nowMs)
        val ymd = CivilDate.fromEpochDay(day)
        val dateIso = isoDate(day)
        val nowLine = "${CivilDate.longLabel(day)} ${ymd.year} · ${LocalClock.formatMinute(cal.minuteOfDay(nowMs))}"
        val items = mutableListOf<AskItem>()
        val ids = linkedMapOf<String, String>()
        fun ref(t: Task): String {
            ids.entries.firstOrNull { it.value == t.id }?.let { return it.key }
            return "t${ids.size + 1}".also { ids[it] = t.id }
        }
        fun whenOf(t: Task): String? {
            val at = t.scheduledAtMs
            val due = t.dueAtMs
            return when {
                at != null && cal.epochDayOf(at) == day -> "planned ${LocalClock.formatMinute(cal.minuteOfDay(at))}"
                at != null -> "planned ${TaskWhenRules.label(cal.epochDayOf(at), cal.minuteOfDay(at), day)}"
                due != null -> "due ${TaskWhenRules.label(cal.epochDayOf(due), null, day)}"
                else -> null
            }
        }
        today.needsYou.forEach { n ->
            val why = when (n.reason) {
                NeedsYouReason.CONFLICT -> "changed on two devices"
                NeedsYouReason.OVERDUE -> "overdue"
                NeedsYouReason.DUE_TODAY_UNSCHEDULED -> "due today, no time yet"
            }
            items += AskItem(ref(n.task), AskItemKind.NEEDS_YOU, line(n.task.title, why))
        }
        (listOfNotNull(today.upNext) + today.yourDay).distinctBy { it.id }.forEach { t ->
            items += AskItem(ref(t), AskItemKind.TASK, line(t.title, if (t == today.upNext) "up next" else null, whenOf(t) ?: "anytime today"))
        }
        today.doneToday.forEach { t -> items += AskItem("", AskItemKind.DONE, line(t.title, "done")) }
        today.events.forEach { e ->
            val time = if (e.allDay) "all day" else "${LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))}–${LocalClock.formatMinute(cal.minuteOfDay(e.endAtMs))}"
            val ended = !e.allDay && e.endAtMs <= nowMs
            items += AskItem("", AskItemKind.EVENT, line(time, e.title, if (e.isFixture) e.calendarName else CalendarRules.name(e), e.location, if (ended) "over" else null))
        }
        // The shopping list (one line: what's to buy, oldest first), so "what's on the shopping list?" can be answered.
        val shop = shopping?.takeIf { it.toBuy.isNotEmpty() || it.got.isNotEmpty() }?.let { shoppingLine(it) }
        // The forecast (weather item): numbers MEKA wrote into words itself, so never untrusted; kept whatever the day holds.
        val forecast = weather.take(WeatherRules.MAX_ASK_LINES).map { AskItem("", AskItemKind.WEATHER, it.take(MAX_LINE)) }
        val tail = listOfNotNull(shop) + forecast
        val kept = items.take(MAX_ITEMS - tail.size) + tail
        val keptRefs = kept.map { it.ref }.toSet()
        // Things Jeanette added are someone else's words, like a calendar invitation's title.
        val fromOthers = shop != null && shopping.toBuy.any { ShoppingRules.byName(it.by) != null }
        return AskContext(dateIso, nowLine, kept, ids.filterKeys { it in keptRefs }, untrusted = today.events.isNotEmpty() || fromOthers)
    }

    /** "Shopping list · 3 to buy: milk, eggs, bread" / "Shopping list · nothing to buy". */
    fun shoppingLine(v: ShoppingView): AskItem {
        val head = if (v.toBuy.isEmpty()) "Shopping list · nothing to buy" else "Shopping list · ${v.toBuy.size} to buy: "
        val names = v.toBuy.joinToString(", ") { it.title.replace(Regex("""\s+"""), " ").trim() }
        val text = (head + names).let { if (it.length > MAX_LINE) it.take(MAX_LINE - 1).trimEnd(' ', ',') + "…" else it }
        return AskItem("", AskItemKind.SHOPPING, text)
    }

    private fun line(vararg parts: String?): String =
        parts.mapNotNull { p -> p?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() } }
            .joinToString(" · ").take(MAX_LINE)

    /** The answer's words: trimmed, at most [MAX_ANSWER] characters, "No answer" when empty. */
    fun answerText(raw: String?): String =
        raw?.trim()?.let { if (it.length > MAX_ANSWER) it.take(MAX_ANSWER - 1).trimEnd() + "…" else it }?.takeIf { it.isNotEmpty() } ?: "No answer"

    /** The answer as shown: its words and the proposals that check out, at most [MAX_CARDS], no two alike. */
    fun answer(text: String?, actions: List<AskRawAction>, context: AskContext, titles: Map<String, String>, nowMs: Long, cal: LocalCalendar): AskAnswer =
        AskAnswer(
            answerText(text),
            actions.mapNotNull { card(it, context, titles, nowMs, cal) }.distinctBy { it.proposal }.take(MAX_CARDS),
        )

    /**
     * One proposal as a card, or null when MEKA can't use it: an unknown kind, a task handle it didn't send, a title
     * that's empty or too long, a day in the past or more than two years ahead, a time that isn't "HH:MM", a fast
     * outside [FAST_MIN_HOURS]…[FAST_MAX_HOURS] hours, a timer outside a minute to a day. [titles] maps task id → title.
     */
    fun card(a: AskRawAction, context: AskContext, titles: Map<String, String>, nowMs: Long, cal: LocalCalendar): AskCard? {
        val today = cal.epochDayOf(nowMs)
        fun task(): Pair<String, String>? {
            val id = context.taskIds[a.ref?.trim()] ?: return null
            return id to (titles[id] ?: return null)
        }
        fun day(): Long? = a.date?.let { parseDay(it) }?.takeIf { it in today..today + MAX_DAYS_AHEAD }
        val minute = a.time?.let { parseTime(it) }
        // Where a kind uses a day or time, one that is there must read right (a bad one isn't guessed at).
        if (a.kind == "add_task" || a.kind == "move_task") {
            if (a.time != null && minute == null) return null
            if (a.date != null && day() == null) return null
        }
        val proposal: AskProposal = when (a.kind) {
            "add_task" -> {
                val title = a.title?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE } ?: return null
                // A time with no day means today; a time already gone today isn't offered.
                val d = day() ?: if (minute != null) today else null
                if (d == today && minute != null && cal.toEpochMs(d, minute) <= nowMs) return null
                AskProposal.AddTask(title, d, minute)
            }
            "complete_task" -> task()?.let { (id, t) -> AskProposal.CompleteTask(id, t) } ?: return null
            "move_task" -> {
                val (id, t) = task() ?: return null
                val d = day() ?: return null
                if (d == today && minute != null && cal.toEpochMs(d, minute) <= nowMs) return null
                AskProposal.MoveTask(id, t, d, minute)
            }
            "start_fast" -> AskProposal.StartFast(a.hours?.takeIf { it in FAST_MIN_HOURS..FAST_MAX_HOURS } ?: return null)
            "set_timer" -> AskProposal.Timer(a.minutes?.takeIf { it in 1..TIMER_MAX_MIN } ?: return null)
            "set_alarm" -> AskProposal.Alarm(minute ?: return null)
            "add_shopping" -> AskProposal.AddShopping(
                ShoppingRules.split(a.title ?: return null).take(MAX_SHOPPING).takeIf { it.isNotEmpty() } ?: return null,
            )
            else -> return null
        }
        return cardOf(proposal, today)
    }

    /** The line Ask shows when the server answered without an answer: off, over budget, or failed with its reason. */
    fun unavailableLine(state: String, reason: String?): String = when (state) {
        "off" -> "MEKA's AI is off"
        "over" -> "This month's AI budget is used up · back on the 1st"
        else -> reason?.trim()?.take(120)?.takeIf { it.isNotEmpty() }?.let { "Couldn't ask: $it" } ?: "Couldn't ask just now"
    }

    /**
     * Ask's status line from the server's AI status: [state] on · off · failing (anything else reads as failing),
     * [level] ok · alert · over for the month's budget, [spentCents]/[budgetCents] when the server meters (null on an
     * older server). Money is in US dollars, as Anthropic bills it.
     */
    fun statusView(state: String, reason: String?, spentCents: Long?, budgetCents: Long?, level: String?): AiStatusView {
        val spend = if (spentCents != null && budgetCents != null && budgetCents > 0) "${dollars(spentCents)} of ${dollars(budgetCents)} this month" else null
        return when {
            state == "off" -> AiStatusView("Off · Search still finds everything", lit = false, canAsk = false)
            level == "over" -> AiStatusView("This month's budget is used up · back on the 1st", lit = true, canAsk = false)
            state == "on" && level == "alert" -> AiStatusView(listOfNotNull("On", spend, "most of the month's budget used").joinToString(" · "), lit = true, canAsk = true)
            state == "on" -> AiStatusView(listOfNotNull("On", spend).joinToString(" · "), lit = false, canAsk = true)
            else -> AiStatusView(
                "Not answering" + (reason?.trim()?.take(80)?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                lit = true, canAsk = true,
            )
        }
    }

    /** Before the server has said (offline, or this device isn't connected). */
    val STATUS_UNKNOWN = AiStatusView("Couldn't check just now", lit = false, canAsk = true)
    val STATUS_NOT_CONNECTED = AiStatusView("Ask works once this device is connected", lit = false, canAsk = false)

    /** Cents → "$1.20"; whole dollars → "$20". */
    fun dollars(cents: Long): String {
        val c = cents.coerceAtLeast(0)
        return if (c % 100 == 0L) "\$${c / 100}" else "\$${c / 100}.${(c % 100).toString().padStart(2, '0')}"
    }

    /**
     * The answer as lines that fade in one after another (the catalogue's Assistant row): its paragraphs and list
     * lines, and long paragraphs split after their sentences. Never empty.
     */
    fun answerLines(text: String): List<String> {
        val out = mutableListOf<String>()
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { para ->
            if (para.length <= 140 || para.startsWith("-") || para.startsWith("•")) { out += para; return@forEach }
            var rest = para
            while (rest.length > 140) {
                val cut = Regex("""[.!?](\s)""").findAll(rest).map { it.range.first + 1 }.lastOrNull { it in 40..140 }
                    ?: Regex("""[.!?](\s)""").find(rest)?.range?.first?.plus(1)
                    ?: break
                out += rest.take(cut).trim()
                rest = rest.drop(cut).trim()
            }
            if (rest.isNotEmpty()) out += rest
        }
        return out.ifEmpty { listOf(answerText(text)) }
    }

    const val OFFLINE_LINE = "Couldn't reach MEKA · try again when you're online"
    const val NOT_CONNECTED_LINE = "Ask works once this device is connected"

    /** The undo bar's line once a card is done ("Added “Milk”", "Timer set · 20 min"). */
    fun doneLine(p: AskProposal, today: Long): String = when (p) {
        is AskProposal.AddTask -> "Added “${p.title}”"
        is AskProposal.CompleteTask -> "Ticked off “${p.title}”"
        is AskProposal.MoveTask -> "Moved “${p.title}” to ${TaskWhenRules.label(p.day, p.minute, today)}"
        is AskProposal.StartFast -> "Started a ${fastLabel(p.hours)} fast"
        is AskProposal.Timer -> "Timer set · ${durationLabel(p.minutes)}"
        is AskProposal.Alarm -> "Alarm set · ${LocalClock.formatMinute(p.minute)}"
        is AskProposal.AddShopping -> "Added ${shoppingNames(p.items)} to shopping"
    }

    /** The card's words for [p]. */
    fun cardOf(p: AskProposal, today: Long): AskCard = when (p) {
        is AskProposal.AddTask -> AskCard(p, "Add “${p.title}”" + (p.day?.let { " · " + TaskWhenRules.label(it, p.minute, today) } ?: ""), "Add")
        is AskProposal.CompleteTask -> AskCard(p, "Tick off “${p.title}”", "Done")
        is AskProposal.MoveTask -> AskCard(p, "Move “${p.title}” to ${TaskWhenRules.label(p.day, p.minute, today)}", "Move")
        is AskProposal.StartFast -> AskCard(p, "Start a ${fastLabel(p.hours)} fast", "Start")
        is AskProposal.Timer -> AskCard(p, "Timer · ${durationLabel(p.minutes)}", "Set")
        is AskProposal.Alarm -> AskCard(p, "Alarm · ${LocalClock.formatMinute(p.minute)}", "Set")
        is AskProposal.AddShopping -> AskCard(p, "Add to shopping · ${shoppingNames(p.items)}", "Add")
    }

    /** "milk", "milk and eggs", "milk, eggs and bread", as typed. */
    fun shoppingNames(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    /** "36 h", "2 days" (the extended fast's own names where they match). */
    fun fastLabel(hours: Int): String =
        FastingRules.EXTENDED_CHOICES.firstOrNull { it.hours == hours }?.label ?: "$hours h"

    /** "20 min", "1 h", "1 h 30". */
    fun durationLabel(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
    }

    /**
     * What a timer or alarm card types into capture, so it is set exactly like "timer 20 min" typed by hand
     * ([QuickAlarmRules.parse]).
     */
    fun captureLine(p: AskProposal): String? = when (p) {
        is AskProposal.Timer -> if (p.minutes % 60 == 0) "timer ${p.minutes / 60} h" else "timer ${p.minutes} min"
        is AskProposal.Alarm -> "alarm ${LocalClock.formatMinute(p.minute)}"
        else -> null
    }

    /** Local epoch day → "2026-10-09". */
    fun isoDate(day: Long): String {
        val d = CivilDate.fromEpochDay(day)
        return "${d.year.toString().padStart(4, '0')}-${d.month.toString().padStart(2, '0')}-${d.day.toString().padStart(2, '0')}"
    }

    /** "2026-10-09" → its epoch day; null for anything else. */
    fun parseDay(s: String): Long? {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(s.trim()) ?: return null
        val (y, mo, d) = m.destructured.toList().map { it.toInt() }
        if (mo !in 1..12 || d !in 1..CivilDate.lengthOfMonth(y, mo)) return null
        return CivilDate.toEpochDay(y, mo, d)
    }

    /** "09:00" or "9:00" → minute of the day; null for anything else. */
    fun parseTime(s: String): Int? {
        val m = Regex("""(\d{1,2}):(\d{2})""").matchEntire(s.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) h * 60 + mi else null
    }
}
