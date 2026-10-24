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
enum class AskItemKind(val wire: String) { NEEDS_YOU("needs_you"), TASK("task"), DONE("done"), EVENT("event"), WEATHER("weather"), SHOPPING("shopping"), SCHOOL("school"), MEALS("meals"), DATE_NIGHT("date_night"), RENEWALS("renewals"), WINS("wins"), QUEUE("queue"), LEAVE("leave"), FACTS("facts"), PROTECTED("protected"), ERRANDS("errands"), APPOINTMENTS("appointments"), CHECKLISTS("checklists"), GOALS("goals"), BINS("bins"), CAR("car"), LIFE_ADMIN("life_admin"), GIFTS("gifts"), PEOPLE("people"), TRIPS("trips"), NOTIFICATIONS("notifications"), COMMUTE("commute"), MAIL("mail"), HELP("help"), KIDS("kids"), BARCA("barca"), DAYS("days") }

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
    /**
     * Edit your calendars, completeness slice 4: each event handle ("e1") with the event as a card needs it, for the
     * events Navilife may move by itself ([EventAskRules.movable]). Never leaves the device.
     */
    val eventRefs: Map<String, AskEventRef> = emptyMap(),
    /** The companion, slice 3: he'd rather be called "sir" (Settings → Voice); sent as the request's `callMe`. */
    val callSir: Boolean = false,
    /**
     * The companion, slice 5: the notes of earlier chats sent back with an open question ([CompanionMemoryRules.recall]),
     * empty for none; sent as the request's `recall`.
     */
    val recall: List<String> = emptyList(),
    /**
     * One name everywhere, slice 2: what he is called ([CallNameRules.called], About you → Your name), null when empty;
     * sent as the request's `name` so the companion calls him what Today and the orb do.
     */
    val callName: String? = null,
    /**
     * The companion is called Obi, slice 1: the first conversation of the day on this device ([ObiRules.introduces]),
     * when he may say "Obi here" once; sent as the request's `introduce`.
     */
    val introduce: Boolean = false,
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
    /**
     * Edit your calendars, completeness slice 4: moves one of Meka's own calendar events ([eventId], called [title],
     * on [provider]) to [day] at [minute], keeping its length; an ordinary edit with the five-second Undo, on his tap only.
     */
    data class MoveEvent(val eventId: String, val title: String, val day: Long, val minute: Int, val provider: String) : AskProposal
    data class StartFast(val hours: Int) : AskProposal
    data class Timer(val minutes: Int) : AskProposal
    data class Alarm(val minute: Int) : AskProposal
    /** Adds [items] to the shopping list (the list's own add: a name already to buy stays one row, a got one comes back). */
    data class AddShopping(val items: List<String>) : AskProposal
    /**
     * Plans the dinner called [title] for [day] (Meal plan slice 2: "plan chilli for Friday"): the favourite [mealId],
     * or, when [mealId] is null, a new favourite with that name and no ingredients yet.
     */
    data class PlanDinner(val day: Long, val title: String, val mealId: String?) : AskProposal
    /** Skips the date night on [day] (Date night slice 3: "skip date night next week"): that evening is free to plan. */
    data class SkipDateNight(val day: Long) : AskProposal
    /**
     * Skips this week's life admin hour (Life admin hour slice 3: "skip admin this week"), the week of Monday [weekStart],
     * booked on [day] at [whenLine] ("Sat 17 Oct 10:00–11:00"): that hour is free again.
     */
    data class SkipLifeAdmin(val weekStart: Long, val day: Long, val whenLine: String) : AskProposal
    /** Notes [idea] against the birthday or anniversary [occasionId], called [title] ("Mum's birthday") (Gift planner slice 2). */
    data class AddGiftIdea(val occasionId: String, val title: String, val idea: String) : AskProposal
    /** Adds a promise Meka made [to] someone (null: nobody named), by [byDay] or no date (People slice 3). */
    data class AddPromise(val to: String?, val title: String, val byDay: Long?) : AskProposal
    /** Adds the booking [entry] to the trip called [tripName] (Travel mode slice 2a: "add the flight to Barcelona"). */
    data class AddBooking(val entry: BookingEntry, val tripName: String) : AskProposal
    /**
     * Marks the radar item [id] done (Home upkeep slice 2: "I've tested the smoke alarms", "the boiler's been
     * serviced"), as its own button would: a repeating one moves on from [dueDay] to [nextDay], a one-off ([nextDay]
     * null) leaves the radar. [doneLabel] is its button ("Done", "Serviced", "Paid"). Nothing is paid or booked.
     */
    data class RenewalDone(val id: String, val title: String, val doneLabel: String, val dueDay: Long, val nextDay: Long?) : AskProposal
    /** Keeps [text] in this week's work wins (Work wins slice 2: "add a win: closed the Henley deal"), dated today. */
    data class AddWin(val text: String) : AskProposal
    /** Marks one waiting link in the train queue read or listened to (train-time queue slice 3: "I've read the BBC one"). */
    data class QueueDone(val id: String, val title: String) : AskProposal
    /** Books [startDay]..[endDay] as annual leave under [title] ([days] work days new; annual leave slice 2b: "book the 2nd of November off"). */
    data class BookLeave(val title: String, val startDay: Long, val endDay: Long, val days: Int) : AskProposal
    /**
     * Keeps a fact on What MEKA knows (slice 2: "remember that my shoe size is 10"): [label] · [value], a new fact when
     * [factId] is null, else the newly said value for the fact already on the page under that name.
     */
    data class AddFact(val label: String, val value: String, val factId: String?) : AskProposal
    /** Adds [things] to the errands waiting at [place] (errands by location slice 2b: "add batteries to Tesco"). */
    data class AddErrand(val place: String, val things: List<String>) : AskProposal
    /** Adds [items] to the checklist of the task [taskId] called [title] (Checklists slice 3b: "add eggs to the party checklist"). */
    data class AddChecklist(val taskId: String, val title: String, val items: List<String>) : AskProposal
    /** Adds the goal [title] with its [horizon] and done-by [dueDay] (Goals, properly, slice 8: "add a goal to run 5k by December"). */
    data class AddGoal(val title: String, val horizon: GoalHorizon, val dueDay: Long?) : AskProposal
    /**
     * Changes his reading list (Reading and learning slice 2: "I'm on page 200", "I've finished Dune", "add Dune to my
     * reading list"): [change] on the item [itemId] called [title] (null for an add), with [text] as Reading's own
     * field takes it (the add's line, or "page 200"); [line] is what it comes to ("Dune · Page 200 of 412 · 48%").
     */
    data class ReadingUpdate(val change: ReadingChange, val itemId: String?, val title: String, val text: String, val line: String) : AskProposal
    /** One name everywhere: saves [name] as About you → Your name ("Call you “Alex”", [CallNameRules]). */
    data class SetName(val name: String) : AskProposal
}

/** A proposal as Ask shows it: "Add “Call the dentist” · Tomorrow · 09:00" with its button ("Add"). */
data class AskCard(val proposal: AskProposal, val line: String, val button: String)

/**
 * An answer as Ask shows it: the words, and up to [AskRules.MAX_CARDS] cards. [did]: what MEKA already did on its own
 * under earned trust ([TrustRules]; each with Undo), so those proposals are not cards.
 */
data class AskAnswer(
    val text: String, val cards: List<AskCard>, val did: List<AskAutoDone> = emptyList(),
    /** Ask knows how Navilife itself works: Take me there under an answer from the help index ([HelpRules.answer]). */
    val help: HelpLink? = null,
    /**
     * Ask knows how Navilife itself works, slice 2: "Still stuck? Ask Navilife" under an answer from the help; a tap
     * asks the same question of the AI, past the help (the facade's askBeyondHelp). Null for every other answer.
     */
    val stillStuck: String? = null,
    /**
     * Money manager (16): a money question while Money is locked — the button's words ([MoneyAskRules.UNLOCK]); the
     * app asks for the fingerprint, then the facade's unlockMoneyAsk, then the same question again. Null otherwise.
     */
    val unlockMoney: String? = null,
    /**
     * The companion, slice 2 (live world facts): the web results the answer drew on, at most
     * [LiveFactsRules.MAX_SOURCES], each a card that opens in the browser on his tap. Empty for every other answer.
     */
    val sources: List<AskSource> = emptyList(),
    /**
     * The assistant becomes Obi, slice 3 (spoken vs shown): a spoken answer's detail, shown under its words and never
     * said ([ObiCharacter.shown]); at most [ObiCharacter.MAX_SHOWN] lines. Empty for a typed answer and most spoken ones.
     */
    val shown: List<String> = emptyList(),
)

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
    /**
     * The dinner card: [day] goes back to the dinner it had ([before], null for none) while it still has [after], and
     * the favourite the card made ([createdMealId]) is taken off again.
     */
    data class PutBackDinner(val day: Long, val before: String?, val after: String, val createdMealId: String?) : AskUndo
    /** The skip card: [day]'s date night is kept clear again (only while it is still skipped). */
    data class KeepDateNight(val day: Long) : AskUndo
    /** The life admin skip card: the week of Monday [weekStart] keeps its hour again (only while it is still that week and skipped). */
    data class KeepLifeAdmin(val weekStart: Long) : AskUndo
    /** The gift idea card: the idea it noted leaves Gifts (only while it is still there). */
    data class RemoveGiftIdea(val ideaId: String) : AskUndo
    /** The promise card: the promise it added leaves Lists (only while it is still open). */
    data class RemovePromise(val promiseId: String) : AskUndo
    /** The booking card: the booking it added leaves the trip (only while it is still there). */
    data class RemoveBooking(val bookingId: String) : AskUndo
    /** The radar card: the item goes back to how it was (only while it is still as Done left it). */
    data class PutBackRenewal(val before: RenewalDoneUndo) : AskUndo
    /** The win card: the win it kept leaves the log. */
    data class RemoveWin(val winId: String) : AskUndo
    /** The train queue card: the link goes back to waiting (only while it is still marked done). */
    data class PutBackQueue(val itemId: String) : AskUndo
    /** The leave card: the stretch it booked comes off again. */
    data class RemoveLeave(val leaveId: String) : AskUndo
    /** The fact card that added a fact: it comes off the page again. */
    data class RemoveFact(val factId: String) : AskUndo
    /** The fact card that changed a fact: [before] comes back while it still reads [after]. */
    data class PutBackFact(val factId: String, val before: String, val after: String) : AskUndo
    /** The errand card's things: [added] ones leave the list, [revived] ones (back from Done) go back under Done. */
    data class TakeBackErrands(val added: List<String>, val revived: List<String>) : AskUndo
    /** The checklist card's items: [added] ones still unticked come off, [revived] ones still unticked are ticked again. */
    data class TakeBackChecklist(val taskId: String, val added: List<String>, val revived: List<String>) : AskUndo
    /** The goal card's goal comes off while nothing has been added to it since. */
    data class RemoveGoal(val goalId: String) : AskUndo
    /** The reading card: the list's last change ([token], Reading's own Undo) is taken back while it is still the last. */
    data class PutBackReading(val token: String) : AskUndo
    /** The move-event card: its calendar edit ([editId]) is taken back while it still waits (the five-second Undo). */
    data class TakeBackEventEdit(val editId: String) : AskUndo
    /** The name card: Your name goes back to [before] (null: empty) while it still reads [after]. */
    data class PutBackName(val before: String?, val after: String) : AskUndo
}

/**
 * What a tapped card did: the undo bar's line ("Added “Milk”") and how to take it back. [offer]: earned trust's
 * "want me to just do these?" when this yes made [TrustRules.STREAK] in a row.
 */
data class AskDone(val line: String, val undo: AskUndo?, val offer: TrustOffer? = null)

/**
 * What Ask shows about MEKA's AI under its field (`POST /v1/ai/status`): "On · $1.20 of $20 this month". [lit] when it
 * needs a look (most of the month's budget used, used up, not answering); [canAsk] false when asking can't work (off,
 * used up, not connected), so Ask leads with Search instead.
 */
data class AiStatusView(
    val line: String, val lit: Boolean, val canAsk: Boolean,
    /** Anthropic refused the key itself (revoked or expired): Health shows it red with what to do. */
    val refused: Boolean = false,
    /** This month's spend per feature, most first ([AiSpendRules.lines]); empty from an older server or before any call. */
    val spend: List<AiSpendLine> = emptyList(),
    /** Over [spend]: "This month by feature · $2.05 in all"; null when there's nothing to show. */
    val spendHeading: String? = null,
    /** Where the AI stands ([AiFeatureRules.mode]); each AI feature's own line follows it ([AiFeatureRules.line]). */
    val mode: AiMode = AiMode.ON,
    /** Over [fallbacks]: "While it's off"; null while it answers. */
    val fallbackHeading: String? = null,
    /** What each AI feature does instead while it can't answer ([AiFeatureRules.fallbacks]); empty while it answers. */
    val fallbacks: List<AiFallbackLine> = emptyList(),
)

object AskRules {
    const val MAX_QUESTION = 500
    const val MAX_ITEMS = 60
    /** Lines kept for today's tasks, Needs you and calendar however much else goes with a question. */
    const val TODAY_ROOM = 16
    const val MAX_LINE = 160
    const val MAX_ANSWER = 1200
    const val MAX_CARDS = 3
    const val MAX_TITLE = 120
    /** Event handles one question carries at most ("e1"…"e20"). */
    const val MAX_EVENT_REFS = 20
    /** Proposals may plan up to two years ahead, like When. */
    const val MAX_DAYS_AHEAD = 730
    const val FAST_MIN_HOURS = 12
    const val FAST_MAX_HOURS = FastingRules.MAX_TARGET_HOURS
    const val TIMER_MAX_MIN = 24 * 60
    /** Things one shopping card adds at most. */
    const val MAX_SHOPPING = 10

    /** The kinds of proposal MEKA understands; anything else is dropped. Wire names, never renamed. */
    val KINDS = listOf("add_task", "complete_task", "move_task", "start_fast", "set_timer", "set_alarm", "add_shopping", "plan_dinner", "skip_date_night", "done_renewal", "add_win", "queue_done", "book_leave", "add_fact", "add_errand", "add_checklist", "add_goal", "skip_life_admin", "add_gift_idea", "add_promise", "add_booking", "move_event")

    /** The question as sent: one line, trimmed, at most [MAX_QUESTION] characters; null when there's nothing to ask. */
    fun question(text: String?): String? =
        text?.replace(Regex("""\s+"""), " ")?.trim()?.take(MAX_QUESTION)?.takeIf { it.isNotEmpty() }

    /**
     * Today as MEKA describes it to the model: Needs you first, then Up next and the day's other tasks, what's done and
     * the calendar (finished events included, marked). At most [MAX_ITEMS] lines of at most [MAX_LINE] characters.
     */
    fun context(today: Today, nowMs: Long, cal: LocalCalendar, weather: List<String> = emptyList(), shopping: ShoppingView? = null,
                school: List<String> = emptyList(), meals: String? = null, mealsFromOthers: Boolean = false,
                dateNight: String? = null, renewals: List<String> = emptyList(), wins: List<String> = emptyList(),
                queue: List<String> = emptyList(), leave: List<String> = emptyList(), facts: List<String> = emptyList(),
                protected: List<String> = emptyList(), errands: List<String> = emptyList(),
                appointments: List<String> = emptyList(), checklists: List<String> = emptyList(),
                goals: List<String> = emptyList(), bins: List<String> = emptyList(),
                car: List<String> = emptyList(), lifeAdmin: String? = null, gifts: List<String> = emptyList(),
                people: List<String> = emptyList(), trips: List<String> = emptyList(),
                notifications: List<String> = emptyList(), commute: List<String> = emptyList(),
                mail: List<String> = emptyList(), help: List<String> = emptyList(),
                kids: List<String> = emptyList(), kidsFromOthers: Boolean = false,
                barca: List<String> = emptyList(),
                days: AskDays? = null, dayLines: List<String> = emptyList(), dayEvents: List<CalendarEvent> = emptyList(), dayTasks: List<Task> = emptyList(),
                movable: (CalendarEvent) -> Boolean = { false }, ahead: List<CalendarEvent> = emptyList()): AskContext {
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
        // Edit your calendars, completeness slice 4: an event Navilife may move gets a handle ("e1"), so "move the
        // dentist to 4pm" can come back as a card; a move question also carries the coming events it names ([ahead]).
        val eventRefs = linkedMapOf<String, AskEventRef>()
        fun eventRef(e: CalendarEvent): String {
            if (!movable(e)) return ""
            eventRefs.entries.firstOrNull { it.value.eventId == e.id }?.let { return it.key }
            if (eventRefs.size >= MAX_EVENT_REFS) return ""
            return "e${eventRefs.size + 1}".also { eventRefs[it] = EventAskRules.ref(e, cal) }
        }
        today.events.forEach { e ->
            val time = if (e.allDay) "all day" else "${LocalClock.formatMinute(cal.minuteOfDay(e.startAtMs))}–${LocalClock.formatMinute(cal.minuteOfDay(e.endAtMs))}"
            val ended = !e.allDay && e.endAtMs <= nowMs
            items += AskItem(eventRef(e), AskItemKind.EVENT, line(time, e.title, if (e.isFixture) e.calendarName else CalendarRules.name(e), e.location, if (ended) "over" else null))
        }
        ahead.filter { a -> today.events.none { it.id == a.id } }.forEach { e ->
            items += AskItem(eventRef(e), AskItemKind.EVENT, line(EventAskRules.aheadLine(e, cal), e.location))
        }
        // The shopping list (one line: what's to buy, oldest first), so "what's on the shopping list?" can be answered.
        val shop = shopping?.takeIf { it.toBuy.isNotEmpty() || it.got.isNotEmpty() }?.let { shoppingLine(it) }
        // The forecast (weather item): numbers MEKA wrote into words itself, so never untrusted; kept whatever the day holds.
        val forecast = weather.take(WeatherRules.MAX_ASK_LINES).map { AskItem("", AskItemKind.WEATHER, it.take(MAX_LINE)) }
        // The children's school year (school items, [SchoolRules.askLines]): Meka's own typed lines, never untrusted.
        val schoolYear = school.take(SchoolRules.ASK_OFF + SchoolRules.ASK_DATES + SchoolRules.ASK_WEEKLY).map { AskItem("", AskItemKind.SCHOOL, line(it)) }
        // The week's dinners and the favourites (Meal plan slice 2, [MealRules.askLine]): Meka's and his wife's own
        // dinner names; his wife's favourites make the context untrusted like her shopping (below).
        val dinners = meals?.let { AskItem("", AskItemKind.MEALS, line(it)) }
        // Date night (slice 3, [DateNightRules.askLine]): Meka's own setting in MEKA's words, never untrusted.
        val evening = dateNight?.let { AskItem("", AskItemKind.DATE_NIGHT, line(it)) }
        // Life admin hour (slice 3, [LifeAdminRules.askLine]): Meka's own setting and the jobs gathered, never untrusted
        // (task titles are his own; renewals are his radar's).
        val adminHour = lifeAdmin?.let { AskItem("", AskItemKind.LIFE_ADMIN, line(it)) }
        // The renewals radar (Home upkeep slice 2, [RenewalAskRules.askLines]): Meka's own entries, titles and dates
        // only, never untrusted.
        val radar = renewals.take(RenewalAskRules.ASK_ITEMS).map { AskItem("", AskItemKind.RENEWALS, line(it)) }
        // The work wins log (Work wins slice 2, [WinAskRules.askLines]): Meka's own lines, never untrusted.
        val winLog = wins.take(WinAskRules.ASK_ITEMS + 1).map { AskItem("", AskItemKind.WINS, line(it)) }
        // The train queue (Train-time queue slice 3, [QueueAskRules.askLines]): the saved links' titles came from the
        // pages and apps they were shared from, so they make the context untrusted (below).
        val saved = queue.take(QueueAskRules.ASK_ITEMS + QueueAskRules.ASK_DONE + 1).map { AskItem("", AskItemKind.QUEUE, line(it)) }
        // Annual leave (slice 2b, [LeaveAskRules.askLines]): Meka's own allowance, stretches and typed holidays, never untrusted.
        val leaveLines = leave.take(LeaveAskRules.MAX_LINES).map { AskItem("", AskItemKind.LEAVE, line(it)) }
        // What MEKA knows (facts page, [FactAskRules.askLines]): only the facts that bear on the question, Meka's own
        // typed lines, never one kept from the AI; never untrusted.
        val factLines = facts.take(FactAskRules.ASK_ALL + 1).map { AskItem("", AskItemKind.FACTS, line(it)) }
        // Protected time (slice 4, [ProtectedRules.askLines]): Meka's own setting and work schedule in MEKA's words,
        // never untrusted.
        val shield = protected.take(ProtectedRules.ASK_LINES).map { AskItem("", AskItemKind.PROTECTED, line(it)) }
        // Errands by location (slice 2b, [ErrandAskRules.askLines]): Meka's own typed places and things, never untrusted.
        val errandLines = errands.take(ErrandAskRules.ASK_PLACES + 1).map { AskItem("", AskItemKind.ERRANDS, line(it)) }
        // Appointment nudges (slice 2a, [AppointmentAskRules.askLines]): Meka's own typed appointments, never untrusted.
        val visits = appointments.take(AppointmentAskRules.ASK_ITEMS + 1).map { AskItem("", AskItemKind.APPOINTMENTS, line(it)) }
        // Checklists in tasks (slice 3b, [ChecklistAskRules.askLines]): Meka's own tasks and items, never untrusted.
        val listLines = checklists.take(ChecklistAskRules.ASK_LISTS + 1).map { AskItem("", AskItemKind.CHECKLISTS, line(it)) }
        // Goals, properly (slice 8, [GoalAskRules.askLines]): Meka's own goals, never untrusted.
        val goalLines = goals.take(GoalAskRules.ASK_GOALS + 1).map { AskItem("", AskItemKind.GOALS, line(it)) }
        // Bin days (slice 2, [BinRules.askLines]): Meka's own typed bins in MEKA's words, never untrusted.
        val binLines = bins.take(1 + BinRules.ASK_BINS + BinRules.ASK_DAYS).map { AskItem("", AskItemKind.BINS, line(it)) }
        // Car on autopilot (slice 3, [CarAskRules.askLines]): the registration, the DVLA's answer and the four dates, never untrusted.
        val carLines = car.take(CarAskRules.ASK_LINES_ALL).map { AskItem("", AskItemKind.CAR, line(it)) }
        // Gift planner (slice 2, [GiftAskRules.askLines]): Meka's own birthdays, anniversaries and ideas, never untrusted.
        val giftLines = gifts.take(1 + GiftAskRules.ASK_ITEMS).map { AskItem("", AskItemKind.GIFTS, line(it)) }
        // People (slice 3, [PeopleAskRules.askLines]): Meka's own promises, waiting-fors and birthdays; a contact only
        // joins a row he made, and carries no message words, so never untrusted.
        val peopleLines = people.take(PeopleAskRules.ASK_LINES).map { AskItem("", AskItemKind.PEOPLE, line(it)) }
        // Travel mode (slice 2a, [TripAskRules.askLines]): Meka's own typed trips and bookings, never untrusted.
        val tripLines = trips.take(TripAskRules.ASK_LINES).map { AskItem("", AskItemKind.TRIPS, line(it)) }
        // Notifications never seen (slice 4, [NotifyAskRules.askLines]): only with a question about notifications; the
        // phone's own trace and MEKA's settings in MEKA's words (no notice's text), never untrusted. First in the tail, so
        // the lines that answer the question are the last to be cut.
        val notifyLines = notifications.take(NotifyAskRules.ASK_LINES).map { AskItem("", AskItemKind.NOTIFICATIONS, line(it)) }
        // The commute counts everywhere (slice 5, [CommuteAskRules.askLines]): Meka's own setting, work schedule and wake
        // alarm in MEKA's words, never untrusted; early in the tail, so "when do I need to leave?" keeps its answer.
        val commuteLines = commute.take(CommuteAskRules.ASK_LINES).map { AskItem("", AskItemKind.COMMUTE, line(it)) }
        // Inbox autopilot, slice 9a ([MailAskRules.askLines]): only with a question about his email; senders, subjects and
        // previews are other people's words, so they make the context untrusted (below). First in the tail with the
        // notifications, so the lines that answer the question are the last to be cut.
        val mailLines = mail.take(MailAskRules.ASK_LINES).map { AskItem("", AskItemKind.MAIL, line(it)) }
        // Ask knows how Navilife itself works ([HelpRules.askLines]): only with a question that mentions a feature; the
        // help index's own words, never untrusted; first, so the lines that answer the question are the last to be cut.
        val helpLines = help.take(HelpRules.ASK_ENTRIES * 4).map { AskItem("", AskItemKind.HELP, it.take(MAX_LINE)) }
        // Kids' admin, slice 4 ([KidAskRules.askLines]): only with a question about the children; sizes and fees are
        // Meka's own, but a club session's title comes from a club's calendar, so [kidsFromOthers] makes it untrusted.
        val kidLines = kids.take(KidAskRules.ASK_LINES).map { AskItem("", AskItemKind.KIDS, line(it)) }
        // Barça match day, slice 3 ([BarcaAskRules.askLines]): only with a question about Barça or the match; the kick-off,
        // the UK channels and the score were read from public web pages, so they make the context untrusted.
        val barcaLines = barca.take(BarcaAskRules.ASK_LINES).map { AskItem("", AskItemKind.BARCA, line(it)) }
        // The companion, slice 4 ([DayAskRules]): the days the question names, looked up on the device in his calendar
        // and tasks; their events and tasks keep their handles, so a move or a done still comes back as a card. Event
        // titles can be other people's words, so they make the context untrusted. First in the tail: they answer it.
        val dayItems = if (days == null) emptyList() else
            dayLines.map { AskItem("", AskItemKind.DAYS, line(it)) } +
                dayEvents.filter { d -> today.events.none { it.id == d.id } && ahead.none { it.id == d.id } }
                    .map { e -> AskItem(eventRef(e), AskItemKind.EVENT, line(DayAskRules.eventLine(e, days, cal))) } +
                dayTasks.map { t ->
                    if (t.lifecycle == Lifecycle.DONE) AskItem("", AskItemKind.DONE, line(t.title, DayAskRules.taskWhen(t, day, cal)))
                    else AskItem(ref(t), AskItemKind.TASK, line(t.title, DayAskRules.taskWhen(t, day, cal)))
                }
        val tail = dayItems + helpLines + notifyLines + mailLines + barcaLines + kidLines + commuteLines + listOfNotNull(shop, dinners, evening, adminHour) + shield + errandLines + listLines + goalLines + binLines + carLines + giftLines + peopleLines + tripLines + visits + factLines + radar + winLog + saved + leaveLines + schoolYear + forecast
        // Today's own lines keep room for at least [TODAY_ROOM] (the tail's last lines, the forecast's, go first), so a
        // full tail never pushes the context past [MAX_ITEMS].
        val tailKept = tail.take(MAX_ITEMS - minOf(items.size, TODAY_ROOM))
        val kept = items.take(MAX_ITEMS - tailKept.size) + tailKept
        val keptRefs = kept.map { it.ref }.toSet()
        // Things his wife added are someone else's words, like a calendar invitation's title.
        val fromOthers = shop != null && shopping.toBuy.any { ShoppingRules.byName(it.by) != null }
        return AskContext(dateIso, nowLine, kept, ids.filterKeys { it in keptRefs }, eventRefs = eventRefs.filterKeys { it in keptRefs }, untrusted = today.events.isNotEmpty() || ahead.isNotEmpty() || fromOthers || (dinners != null && mealsFromOthers) || saved.isNotEmpty() || mailLines.isNotEmpty() || (kidLines.isNotEmpty() && kidsFromOthers) || barcaLines.isNotEmpty() || dayItems.any { it.kind == AskItemKind.EVENT })
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
    fun answer(text: String?, actions: List<AskRawAction>, context: AskContext, titles: Map<String, String>, nowMs: Long, cal: LocalCalendar,
               meals: List<Meal> = emptyList(), dateNight: DateNightSetting? = null, renewals: List<RenewalItem> = emptyList(),
               wins: List<Win> = emptyList(), queue: List<QueueItem> = emptyList(), leave: LeaveAskData? = null,
               facts: List<LifeFact> = emptyList(), errands: List<ErrandItem> = emptyList(),
               tasks: List<Task> = emptyList(), goals: List<GoalItem> = emptyList(), lifeAdmin: LifeAdminView? = null,
               occasions: List<Occasion> = emptyList(), people: PeopleView = PeopleView.EMPTY,
               promised: List<PromiseItem> = emptyList(), trips: List<TripItem> = emptyList()): AskAnswer =
        AskAnswer(
            answerText(text),
            actions.mapNotNull { card(it, context, titles, nowMs, cal, meals, dateNight, renewals, wins, queue, leave, facts, errands, tasks, goals, lifeAdmin, occasions, people, promised, trips) }.distinctBy { it.proposal }.take(MAX_CARDS),
        )

    /**
     * One proposal as a card, or null when MEKA can't use it: an unknown kind, a task handle it didn't send, a title
     * that's empty or too long, a day in the past or more than two years ahead, a time that isn't "HH:MM", a fast
     * outside [FAST_MIN_HOURS]…[FAST_MAX_HOURS] hours, a timer outside a minute to a day. [titles] maps task id → title.
     */
    fun card(a: AskRawAction, context: AskContext, titles: Map<String, String>, nowMs: Long, cal: LocalCalendar,
             meals: List<Meal> = emptyList(), dateNight: DateNightSetting? = null, renewals: List<RenewalItem> = emptyList(),
             wins: List<Win> = emptyList(), queue: List<QueueItem> = emptyList(), leave: LeaveAskData? = null,
             facts: List<LifeFact> = emptyList(), errands: List<ErrandItem> = emptyList(), tasks: List<Task> = emptyList(),
             goals: List<GoalItem> = emptyList(), lifeAdmin: LifeAdminView? = null, occasions: List<Occasion> = emptyList(),
             people: PeopleView = PeopleView.EMPTY, promised: List<PromiseItem> = emptyList(), trips: List<TripItem> = emptyList()): AskCard? {
        val today = cal.epochDayOf(nowMs)
        fun task(): Pair<String, String>? {
            val id = context.taskIds[a.ref?.trim()] ?: return null
            return id to (titles[id] ?: return null)
        }
        fun day(): Long? = a.date?.let { parseDay(it) }?.takeIf { it in today..today + MAX_DAYS_AHEAD }
        val minute = a.time?.let { parseTime(it) }
        // Where a kind uses a day or time, one that is there must read right (a bad one isn't guessed at).
        if (a.kind == "add_task" || a.kind == "move_task" || a.kind == "plan_dinner" || a.kind == "skip_date_night" || a.kind == "add_goal" || a.kind == "add_promise" || a.kind == "add_booking") {
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
            "plan_dinner" -> {
                // A day the week's plan takes (today up to two weeks on); no date means tonight. The dinner is a
                // favourite matched by name, else a new favourite with that name (a name only, never ingredients).
                val d = (if (a.date == null) today else day())?.takeIf { it <= today + MealRules.PLAN_AHEAD_DAYS } ?: return null
                val name = MealRules.title(a.title?.replace(Regex("""\s+"""), " ") ?: return null) ?: return null
                val fav = meals.firstOrNull { MealRules.key(it.title) == MealRules.key(name) }
                AskProposal.PlanDinner(d, fav?.title ?: name, fav?.id)
            }
            "skip_date_night" -> AskProposal.SkipDateNight(
                DateNightRules.skippable(dateNight, a.date?.let { day() }, today, cal.minuteOfDay(nowMs)) ?: return null,
            )
            "done_renewal" -> RenewalAskRules.find(renewals, a.title)?.let {
                AskProposal.RenewalDone(it.id, it.title, it.doneLabel, it.dueDay, it.nextDueDay)
            } ?: return null
            "add_win" -> AskProposal.AddWin(WinAskRules.text(wins, a.title, today) ?: return null)
            "queue_done" -> QueueAskRules.find(queue, a.title)?.let { AskProposal.QueueDone(it.id, it.title) } ?: return null
            "book_leave" -> LeaveAskRules.proposal(a.title, day(), leave ?: return null, today) ?: return null
            "add_fact" -> FactAskRules.proposal(facts, a.title) ?: return null
            "add_errand" -> ErrandAskRules.proposal(errands, a.title) ?: return null
            "add_checklist" -> ChecklistAskRules.proposal(tasks, a.title, today) ?: return null
            "add_goal" -> GoalAskRules.proposal(goals, a.title, day(), today) ?: return null
            "skip_life_admin" -> LifeAdminRules.skippable(lifeAdmin, nowMs) ?: return null
            "add_gift_idea" -> GiftAskRules.proposal(occasions, a.title) ?: return null
            "add_promise" -> PeopleAskRules.proposal(people.rows, promised, a.title, day()) ?: return null
            "add_booking" -> TripAskRules.proposal(trips, a.title, day(), minute, today, cal.minuteOfDay(nowMs)) ?: return null
            // Edit your calendars, completeness slice 4: only an event MEKA sent a handle for; a bad day or time isn't guessed at.
            "move_event" -> {
                if ((a.time != null && minute == null) || (a.date != null && day() == null)) return null
                EventAskRules.proposal(context.eventRefs[a.ref?.trim()], a.date?.let { day() }, minute, nowMs, cal) ?: return null
            }
            else -> return null
        }
        return cardOf(proposal, today)
    }

    /** The line Ask shows when the server answered without an answer: off, over budget, or failed with its reason. */
    fun unavailableLine(state: String, reason: String?): String = when (state) {
        "off" -> "Navilife's AI is off"
        "over" -> "This month's AI budget is used up · back on the 1st"
        else -> reason?.trim()?.take(120)?.takeIf { it.isNotEmpty() }?.let { "Couldn't ask: $it" } ?: "Couldn't ask just now"
    }

    /**
     * Ask's status line from the server's AI status: [state] on · off · failing (anything else reads as failing),
     * [level] ok · alert · over for the month's budget, [spentCents]/[budgetCents] when the server meters (null on an
     * older server). Money is in US dollars, as Anthropic bills it.
     */
    fun statusView(
        state: String, reason: String?, spentCents: Long?, budgetCents: Long?, level: String?,
        month: List<AiMonthUse> = emptyList(), keyRefused: Boolean = false,
    ): AiStatusView {
        val base = statusLine(state, reason, spentCents, budgetCents, level)
        val lines = AiSpendRules.lines(month)
        val heading = AiSpendRules.heading(lines, spentCents)
        val view = when {
            keyRefused && state != "off" -> AiStatusView(AiSpendRules.REFUSED_LINE, lit = true, canAsk = true, refused = true, spend = lines, spendHeading = heading)
            else -> base.copy(spend = lines, spendHeading = heading)
        }
        return AiFeatureRules.withMode(view, AiFeatureRules.mode(state, level, keyRefused))
    }

    private fun statusLine(state: String, reason: String?, spentCents: Long?, budgetCents: Long?, level: String?): AiStatusView {
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
    val STATUS_UNKNOWN = AiFeatureRules.withMode(AiStatusView("Couldn't check just now", lit = false, canAsk = true), AiMode.OFFLINE)
    val STATUS_NOT_CONNECTED = AiFeatureRules.withMode(AiStatusView("Ask works once this device is connected", lit = false, canAsk = false), AiMode.NOT_CONNECTED)

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

    const val OFFLINE_LINE = "Couldn't reach Navilife · try again when you're online"
    const val NOT_CONNECTED_LINE = "Ask works once this device is connected"

    /** The undo bar's line once a card is done ("Added “Milk”", "Timer set · 20 min"). */
    fun doneLine(p: AskProposal, today: Long): String = when (p) {
        is AskProposal.AddTask -> "Added “${p.title}”"
        is AskProposal.CompleteTask -> "Ticked off “${p.title}”"
        is AskProposal.MoveTask -> "Moved “${p.title}” to ${TaskWhenRules.label(p.day, p.minute, today)}"
        is AskProposal.MoveEvent -> "Moving “${p.title}” to ${TaskWhenRules.label(p.day, p.minute, today)} in ${CalendarEditRules.providerName(p.provider)}"
        is AskProposal.StartFast -> "Started a ${fastLabel(p.hours)} fast"
        is AskProposal.Timer -> "Timer set · ${durationLabel(p.minutes)}"
        is AskProposal.Alarm -> "Alarm set · ${LocalClock.formatMinute(p.minute)}"
        is AskProposal.AddShopping -> "Added ${shoppingNames(p.items)} to shopping"
        is AskProposal.PlanDinner -> "Planned " + MealRules.plannedLine(p.day, today, p.title)
        is AskProposal.SkipDateNight -> DateNightRules.skipLine(p.day, today, skip = true)
        is AskProposal.SkipLifeAdmin -> LifeAdminRules.skipDoneLine(p)
        is AskProposal.AddGiftIdea -> GiftAskRules.doneLine(p)
        is AskProposal.AddPromise -> PeopleAskRules.doneLine(p, today)
        is AskProposal.AddBooking -> TripAskRules.doneLine(p)
        is AskProposal.RenewalDone -> RenewalAskRules.doneLine(p.doneLabel, p.title, p.nextDay, today)
        is AskProposal.AddWin -> WinAskRules.doneLine(p.text)
        is AskProposal.QueueDone -> QueueAskRules.doneLine(p.title)
        is AskProposal.BookLeave -> LeaveAskRules.doneLine(p)
        is AskProposal.AddFact -> FactAskRules.doneLine(p)
        is AskProposal.AddErrand -> ErrandAskRules.doneLine(p)
        is AskProposal.AddChecklist -> ChecklistAskRules.doneLine(p)
        is AskProposal.AddGoal -> GoalAskRules.doneLine(p)
        is AskProposal.ReadingUpdate -> p.line
        is AskProposal.SetName -> CallNameRules.doneLine(p.name)
    }

    /** The card's words for [p]. */
    fun cardOf(p: AskProposal, today: Long): AskCard = when (p) {
        is AskProposal.AddTask -> AskCard(p, "Add “${p.title}”" + (p.day?.let { " · " + TaskWhenRules.label(it, p.minute, today) } ?: ""), "Add")
        is AskProposal.CompleteTask -> AskCard(p, "Tick off “${p.title}”", "Done")
        is AskProposal.MoveTask -> AskCard(p, "Move “${p.title}” to ${TaskWhenRules.label(p.day, p.minute, today)}", "Move")
        is AskProposal.MoveEvent -> AskCard(p, "Move “${p.title}” to ${TaskWhenRules.label(p.day, p.minute, today)} · ${CalendarEditRules.providerName(p.provider)}", "Move")
        is AskProposal.StartFast -> AskCard(p, "Start a ${fastLabel(p.hours)} fast", "Start")
        is AskProposal.Timer -> AskCard(p, "Timer · ${durationLabel(p.minutes)}", "Set")
        is AskProposal.Alarm -> AskCard(p, "Alarm · ${LocalClock.formatMinute(p.minute)}", "Set")
        is AskProposal.AddShopping -> AskCard(p, "Add to shopping · ${shoppingNames(p.items)}", "Add")
        is AskProposal.PlanDinner -> AskCard(
            p, "Dinner · " + MealRules.plannedLine(p.day, today, p.title) + (if (p.mealId == null) " · new favourite" else ""), "Plan",
        )
        is AskProposal.SkipDateNight -> AskCard(p, "Skip date night · " + DateNightRules.whenLabel(p.day, today), "Skip")
        is AskProposal.SkipLifeAdmin -> AskCard(p, "Skip this week's life admin hour · " + p.whenLine, "Skip")
        is AskProposal.AddGiftIdea -> AskCard(p, GiftAskRules.cardLine(p), "Note")
        is AskProposal.AddPromise -> AskCard(p, PeopleAskRules.cardLine(p, today), "Add")
        is AskProposal.AddBooking -> AskCard(p, TripAskRules.cardLine(p), "Add")
        is AskProposal.RenewalDone -> AskCard(p, RenewalAskRules.cardLine(p.doneLabel, p.title, p.nextDay, today), p.doneLabel)
        is AskProposal.AddWin -> AskCard(p, WinAskRules.cardLine(p.text), "Keep")
        is AskProposal.QueueDone -> AskCard(p, QueueAskRules.cardLine(p.title), "Done")
        is AskProposal.BookLeave -> AskCard(p, LeaveAskRules.cardLine(p), "Book")
        is AskProposal.AddFact -> AskCard(p, FactAskRules.cardLine(p), if (p.factId == null) "Keep" else "Update")
        is AskProposal.AddErrand -> AskCard(p, ErrandAskRules.cardLine(p), "Add")
        is AskProposal.AddChecklist -> AskCard(p, ChecklistAskRules.cardLine(p), "Add")
        is AskProposal.AddGoal -> AskCard(p, GoalAskRules.cardLine(p, today), "Add")
        is AskProposal.ReadingUpdate -> AskCard(p, ReadingAskRules.cardLine(p), ReadingAskRules.button(p))
        is AskProposal.SetName -> CallNameRules.card(p.name)
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
