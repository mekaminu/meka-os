package os.meka.core.domain

/**
 * Obi sees everything, slice 3 (Meka 2026-10-28 19:32: "Please ensure he sees everything I have"): the areas typed Ask
 * didn't carry yet, as Ask lines and Live look-up answers: the Week board's No day yet and Someday ([boardLines]),
 * decisions ([decisionLines]) and Reading ([readingLines]); the Kids pane and Needs you join [ObiArea] from their own
 * rules. Asked about one ([boardAsked], [decisionsAsked], [readingAsked]) Ask sends all of it; otherwise one short
 * counts line, so Obi knows it's there and can look it up. Titles are his own words; nothing is stored.
 */
object ObiMoreRules {
    /** Titles named on one line. */
    const val ASK_TITLES = 6
    /** Weeks whose No day yet lane is described. */
    const val ASK_WEEKS = 3
    /** The most `board` lines: a head, the weeks' lanes and Someday's groups. */
    const val BOARD_MAX = 1 + ASK_WEEKS + 5
    /** Decisions described. */
    const val ASK_DECISIONS = 6
    const val DECISIONS_MAX = 1 + ASK_DECISIONS
    /** Reading rows per part. */
    const val ASK_READING = 4
    const val READING_MAX = 2 + 3 * ASK_READING

    private val BOARD_WORDS = Regex("""\b(some ?day|no day( yet)?|backlog|unscheduled|undated|parked|week ?board|board|ideas?|bucket list|wish ?list)\b""")
    private val DECISION_WORDS = Regex("""\b(decisions?|decided|decide|revisit(ing)?)\b""")
    private val READING_WORDS = Regex("""\b(read|reading|books?|novels?|audiobooks?|chapters?|pages?|authors?|podcasts?)\b""")

    fun boardAsked(q: String): Boolean = BOARD_WORDS.containsMatchIn(q.lowercase())
    fun decisionsAsked(q: String): Boolean = DECISION_WORDS.containsMatchIn(q.lowercase())
    fun readingAsked(q: String): Boolean = READING_WORDS.containsMatchIn(q.lowercase())

    private fun tidy(s: String): String = s.replace(Regex("""\s+"""), " ").trim()
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
    private fun titles(all: List<String>): String {
        val shown = all.take(ASK_TITLES).joinToString(", ") { "“${tidy(it)}”" }
        return if (all.size > ASK_TITLES) "$shown and ${all.size - ASK_TITLES} more" else shown
    }

    /**
     * The Week board's trays: "Week board · 3 with no day yet · 5 on Someday", then "No day yet · This week (26 Oct–1
     * Nov) · 2: “Fix the shed”, “Call the bank”" per week with any, and "Someday · Ideas · 3: …" per group. Empty when
     * both trays are empty; just the head when not [full].
     */
    fun boardLines(board: WeekBoard, someday: List<SomedayGroup>, full: Boolean): List<String> {
        val lanes = board.weeks.filter { it.lane.isNotEmpty() }
        val noDay = lanes.sumOf { it.lane.size }
        val later = someday.sumOf { it.items.size }
        if (noDay == 0 && later == 0) return emptyList()
        val head = "Week board · ${noDay} with no day yet · ${later} on Someday"
        if (!full) return listOf(head)
        return listOf(head) +
            lanes.take(ASK_WEEKS).map { w -> "No day yet · ${w.title} (${w.range}) · ${w.lane.size}: ${titles(w.lane.map { it.title })}" } +
            someday.filter { it.items.isNotEmpty() }.map { g -> "Someday · ${g.label} · ${g.items.size}: ${titles(g.items.map { TaskTitleRules.display(it.title) })}" }
    }

    /**
     * His decisions log: "Decisions · 4 active · 1 to review", then "“Keep the old car another year” · Decided Mon 5 Oct
     * · review 5 Jan · why: …". Empty with none; the head alone when not [full].
     */
    fun decisionLines(decisions: List<DecisionItem>, full: Boolean): List<String> {
        if (decisions.isEmpty()) return emptyList()
        val due = decisions.count { it.state == DueState.DUE }
        val head = "Decisions · ${decisions.size} active" + (if (due > 0) " · $due to review" else "")
        if (!full) return listOf(head)
        return listOf(head) + decisions.take(ASK_DECISIONS).map { d ->
            listOfNotNull("“${tidy(d.statement)}”", d.meta.takeIf { it.isNotBlank() }, d.rationale?.let(::tidy)?.takeIf { it.isNotEmpty() }?.let { "why: $it" })
                .joinToString(" · ")
        }
    }

    /**
     * Reading: "Reading · 2 on the go · 3 up next · finished 4 this year", his goal's line, then "On the go · Dune · Book
     * · Frank Herbert · Page 120 of 320 · 38%", "Up next · …" and "Finished · …". Empty when the list is; the head alone
     * when not [full].
     */
    fun readingLines(v: ReadingView, full: Boolean): List<String> {
        if (v.isEmpty) return emptyList()
        val head = "Reading · ${tidy(v.summary)}"
        if (!full) return listOf(head)
        fun row(part: String, r: ReadingRow) = listOf(part, tidy(r.title), r.meta, r.progress).filter { it.isNotBlank() }.joinToString(" · ")
        return listOf(head) +
            listOfNotNull(v.goal?.let { "Reading goal · ${it.title} · ${it.count} · ${it.line}" }) +
            v.onTheGo.take(ASK_READING).map { row("On the go", it) } +
            v.upNext.take(ASK_READING).map { row("Up next", it) } +
            v.finished.take(ASK_READING).map { row("Finished", it) }
    }

    /** Kids' one counts line when the question isn't about the children: the head of [KidAskRules.askLines]. */
    fun kidsLines(v: KidsView, full: Boolean): List<String> = KidAskRules.askLines(v).let { if (full) it else it.take(1) }
}

/** Where an area of Navilife reaches Obi ([ObiCoverage.reach]). */
sealed interface ObiReach {
    /** Its lines are in Ask's context and Live's [LiveToolRules.NAVILIFE_TOOL] look-up. */
    data class Areas(val areas: List<ObiArea>) : ObiReach
    /** It has its own look-up: the day's calendar and tasks, mail or money (money behind his unlock). */
    data class Tool(val tool: String) : ObiReach
    /** A setting or a page about Navilife itself: Obi answers from the built-in help (and the day's own lines). */
    data object Help : ObiReach
    /** Not readable by Obi yet: what remains of "Obi sees everything" (the build board says what). */
    data class Later(val why: String) : ObiReach
}

/**
 * The coverage registry (slice 3): every place in Navilife ([HelpPlace], the Fold's Ask rows and the Mac's) says how
 * Obi reaches it. The `when` is exhaustive, so a new screen doesn't compile until it says; a test pins the [ObiReach.Later]
 * ones so none slips in quietly.
 */
object ObiCoverage {
    fun reach(place: HelpPlace): ObiReach = when (place) {
        HelpPlace.LISTS -> ObiReach.Areas(listOf(ObiArea.PEOPLE, ObiArea.BOARD, ObiArea.DECISIONS, ObiArea.RENEWALS, ObiArea.SHOPPING))
        HelpPlace.GOALS -> ObiReach.Areas(listOf(ObiArea.GOALS, ObiArea.STUDY))
        HelpPlace.PEOPLE -> ObiReach.Areas(listOf(ObiArea.PEOPLE))
        HelpPlace.DINNERS -> ObiReach.Areas(listOf(ObiArea.MEALS))
        HelpPlace.BINS -> ObiReach.Areas(listOf(ObiArea.BINS))
        HelpPlace.DATE_NIGHT -> ObiReach.Areas(listOf(ObiArea.DATE_NIGHT))
        HelpPlace.SCHOOL -> ObiReach.Areas(listOf(ObiArea.SCHOOL))
        HelpPlace.KIDS -> ObiReach.Areas(listOf(ObiArea.KIDS))
        HelpPlace.APPOINTMENTS -> ObiReach.Areas(listOf(ObiArea.APPOINTMENTS))
        HelpPlace.LEAVE -> ObiReach.Areas(listOf(ObiArea.LEAVE))
        HelpPlace.GIFTS -> ObiReach.Areas(listOf(ObiArea.GIFTS))
        HelpPlace.TRIPS -> ObiReach.Areas(listOf(ObiArea.TRIPS))
        HelpPlace.ERRANDS -> ObiReach.Areas(listOf(ObiArea.ERRANDS))
        HelpPlace.CAR -> ObiReach.Areas(listOf(ObiArea.CAR))
        HelpPlace.FACTS -> ObiReach.Areas(listOf(ObiArea.FACTS))
        HelpPlace.TRAIN_QUEUE -> ObiReach.Areas(listOf(ObiArea.QUEUE))
        HelpPlace.READING -> ObiReach.Areas(listOf(ObiArea.READING))
        HelpPlace.LIFE_ADMIN -> ObiReach.Areas(listOf(ObiArea.LIFE_ADMIN))
        HelpPlace.WORK -> ObiReach.Areas(listOf(ObiArea.COMMUTE, ObiArea.WINS))
        HelpPlace.PROTECTED -> ObiReach.Areas(listOf(ObiArea.PROTECTED))
        HelpPlace.REVIEW -> ObiReach.Areas(listOf(ObiArea.WINS, ObiArea.GOALS))
        HelpPlace.MONEY -> ObiReach.Tool(LiveToolRules.MONEY_TOOL)
        HelpPlace.MAIL -> ObiReach.Tool(LiveToolRules.MAIL_TOOL)
        HelpPlace.BRIEF, HelpPlace.WEEK_AHEAD, HelpPlace.SHUTDOWN, HelpPlace.CALENDARS -> ObiReach.Tool(LiveToolRules.LOOK_UP_TOOL)
        // Slice 4 ([ObiRestRules]): the places that were Later.
        HelpPlace.EMERGENCY -> ObiReach.Areas(listOf(ObiArea.EMERGENCY))
        HelpPlace.NEWS -> ObiReach.Areas(listOf(ObiArea.NEWS))
        HelpPlace.VOICE_NOTES -> ObiReach.Areas(listOf(ObiArea.VOICE_NOTES))
        HelpPlace.HEALTH -> ObiReach.Areas(listOf(ObiArea.HEALTH))
        HelpPlace.VAULT -> ObiReach.Areas(listOf(ObiArea.VAULT))
        HelpPlace.BEDSIDE, HelpPlace.ABOUT_YOU, HelpPlace.NOTIFICATIONS, HelpPlace.SPEAK_UP, HelpPlace.APPEARANCE,
        HelpPlace.VOICE, HelpPlace.TALK, HelpPlace.FAMILY, HelpPlace.WATCH, HelpPlace.RECOVERY, HelpPlace.AUTONOMY,
        HelpPlace.AI, HelpPlace.ACTIVITY, HelpPlace.SETUP, HelpPlace.YOUR_DATA, HelpPlace.SETTINGS,
        HelpPlace.SYSTEM_SCREEN_SAVER, HelpPlace.SYSTEM_DEFAULT_APPS, HelpPlace.SYSTEM_APP_NOTIFICATIONS,
        HelpPlace.SYSTEM_APP_DETAILS -> ObiReach.Help
    }

    /** The areas each place reaches, every one of them: an area no place reaches is a test failure too. */
    val reachedAreas: Set<ObiArea> get() = HelpPlace.entries.flatMap { (reach(it) as? ObiReach.Areas)?.areas.orEmpty() }.toSet()
}
