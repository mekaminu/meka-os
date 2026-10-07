package os.meka.core.domain

/**
 * The command bar (build plan M1, Outside the app: the Mac's ⌘K). One field that reaches every place and the everyday
 * actions by typing a few letters: "rev" → Review, "fast" → Start a fast, "exp" → Your data. Whatever is typed can also
 * be searched for or added as a task, so the bar is never a dead end.
 *
 * Non-AI and pure: a fixed catalogue matched with the same word-start rules as Search ([SearchRules]); nothing stored,
 * nothing sent. Asking in words comes with the AI layer (Needs Meka #3). The catalogue only names actions the apps
 * already have; the platform maps each [CommandBarAction] to its own screen or command, so nothing here can do more
 * than a tap on the same button would (no sending, no money, no autonomy, ADR-006).
 */
enum class CommandBarAction {
    GO_TODAY, GO_NEEDS_YOU, GO_CALENDAR, GO_ASK, GO_LISTS, GO_GOALS, GO_REVIEW, GO_VAULT,
    PLAN_DAY, MORNING_BRIEF, SHUT_DOWN, SYNC_NOW, WORK_START, WORK_FINISH, FAST_START, FAST_END,
    WORK_MODE, NOTIFICATIONS, ACTIVITY, YOUR_DATA, CALENDARS,
    APPEARANCE_DARK, APPEARANCE_LIGHT, APPEARANCE_AUTO,

    /** Opens Search everything with what was typed. */
    SEARCH_FOR,

    /** Adds what was typed as a task (the capture rule: Today, no time). */
    ADD_TASK,
}

/** The catalogue's sections, shown as headings while nothing is typed. */
enum class CommandBarSection(val label: String) {
    GO("Go to"),
    DO("Do"),
    OPEN("Open"),
    APPEARANCE("Appearance"),
}

data class CommandBarRow(
    val action: CommandBarAction,
    /** "Review", "Start a fast", "Add “Call Mum” as a task". */
    val title: String,
    /** A line under it when it helps: "Goal 16 h · starts now", "Tasks, calendar, lists and more". */
    val detail: String?,
    /** What was typed, for [CommandBarAction.SEARCH_FOR] and [CommandBarAction.ADD_TASK]; null for the rest. */
    val text: String?,
)

data class CommandBarGroup(
    /** The section heading; null for the ranked results while typing. */
    val label: String?,
    val rows: List<CommandBarRow>,
)

data class CommandBarResults(val groups: List<CommandBarGroup>) {
    /** Every row in order: ↑ ↓ move through these, Return runs the first (or the chosen) one. */
    val rows: List<CommandBarRow> get() = groups.flatMap { it.rows }
}

/** What the bar needs to know to offer the right half of a pair (Start a fast or End the fast). */
data class CommandBarContext(
    val atWork: Boolean,
    val fasting: Boolean,
    /** The usual goal for "Start a fast" (the fasting plan's). */
    val fastGoalHours: Int,
    /** Calendars is offered only once this device is connected to the server. */
    val connected: Boolean,
    /** The current appearance ("dark", "light" or "system"); that one isn't offered. */
    val appearance: String,
)

object CommandBarRules {
    /** Commands shown at most while typing (Search and Add come after them). */
    const val MAX_MATCHES = 8
    /** Typed text shown in a row before it is cut ("Add “…” as a task"). */
    const val ECHO_CHARS = 48

    private class Entry(
        val action: CommandBarAction,
        val section: CommandBarSection,
        val title: String,
        /** Other words people use for it; they match, but count less than the title. */
        val keywords: String,
    )

    private val CATALOGUE: List<Entry> = listOf(
        Entry(CommandBarAction.GO_TODAY, CommandBarSection.GO, "Today", "home day timeline now"),
        Entry(CommandBarAction.GO_NEEDS_YOU, CommandBarSection.GO, "Needs you", "decisions stack overdue conflicts inbox"),
        Entry(CommandBarAction.GO_CALENDAR, CommandBarSection.GO, "Calendar", "agenda week events schedule"),
        Entry(CommandBarAction.GO_ASK, CommandBarSection.GO, "Ask", "more question"),
        Entry(CommandBarAction.GO_LISTS, CommandBarSection.GO, "Lists", "waiting for chase someday decisions renewals bills subscriptions"),
        Entry(CommandBarAction.GO_GOALS, CommandBarSection.GO, "Goals and habits", "habit streak fasting progress"),
        Entry(CommandBarAction.GO_REVIEW, CommandBarSection.GO, "Review", "weekly week metrics"),
        Entry(CommandBarAction.GO_VAULT, CommandBarSection.GO, "Vault", "documents files"),
        Entry(CommandBarAction.PLAN_DAY, CommandBarSection.DO, "Plan my day", "planner schedule blocks"),
        Entry(CommandBarAction.MORNING_BRIEF, CommandBarSection.DO, "Morning brief", "headlines news summary"),
        Entry(CommandBarAction.SHUT_DOWN, CommandBarSection.DO, "Shut down the day", "evening shutdown tomorrow"),
        Entry(CommandBarAction.WORK_START, CommandBarSection.DO, "Start work", "work mode switch on office"),
        Entry(CommandBarAction.WORK_FINISH, CommandBarSection.DO, "Finish work", "work mode switch off done stop"),
        Entry(CommandBarAction.FAST_START, CommandBarSection.DO, "Start a fast", "fasting timer begin"),
        Entry(CommandBarAction.FAST_END, CommandBarSection.DO, "End the fast", "fasting stop break eat"),
        Entry(CommandBarAction.SYNC_NOW, CommandBarSection.DO, "Sync now", "refresh reload update"),
        Entry(CommandBarAction.WORK_MODE, CommandBarSection.OPEN, "Work mode", "hours schedule shift"),
        Entry(CommandBarAction.NOTIFICATIONS, CommandBarSection.OPEN, "Notifications", "quiet hours digest alerts settings"),
        Entry(CommandBarAction.ACTIVITY, CommandBarSection.OPEN, "Activity", "log history what meka did undo"),
        Entry(CommandBarAction.YOUR_DATA, CommandBarSection.OPEN, "Your data", "export backup download json"),
        Entry(CommandBarAction.CALENDARS, CommandBarSection.OPEN, "Calendars", "accounts connect google outlook"),
        Entry(CommandBarAction.APPEARANCE_DARK, CommandBarSection.APPEARANCE, "Dark appearance", "theme mode night"),
        Entry(CommandBarAction.APPEARANCE_LIGHT, CommandBarSection.APPEARANCE, "Light appearance", "theme mode day"),
        Entry(CommandBarAction.APPEARANCE_AUTO, CommandBarSection.APPEARANCE, "Auto appearance", "theme mode system follow"),
    )

    /** Whether [action] makes sense now (only one of each pair; Calendars once connected; not the current appearance). */
    private fun offered(action: CommandBarAction, ctx: CommandBarContext): Boolean = when (action) {
        CommandBarAction.WORK_START -> !ctx.atWork
        CommandBarAction.WORK_FINISH -> ctx.atWork
        CommandBarAction.FAST_START -> !ctx.fasting
        CommandBarAction.FAST_END -> ctx.fasting
        CommandBarAction.CALENDARS -> ctx.connected
        CommandBarAction.SYNC_NOW -> ctx.connected
        CommandBarAction.APPEARANCE_DARK -> ctx.appearance != "dark"
        CommandBarAction.APPEARANCE_LIGHT -> ctx.appearance != "light"
        CommandBarAction.APPEARANCE_AUTO -> ctx.appearance != "system"
        else -> true
    }

    private fun detail(action: CommandBarAction, ctx: CommandBarContext): String? = when (action) {
        CommandBarAction.FAST_START -> "Goal ${ctx.fastGoalHours} h · starts now"
        CommandBarAction.FAST_END -> "You can undo it for 10 minutes"
        CommandBarAction.WORK_START -> "Until your schedule next changes"
        CommandBarAction.WORK_FINISH -> "Until your schedule next changes"
        else -> null
    }

    /** The words of what was typed, from the first letter (Search waits for two; commands don't). */
    private fun words(query: String): List<String> =
        SearchRules.normalize(query.take(SearchRules.MAX_QUERY)).split(' ').filter { it.isNotEmpty() }.distinct()
            .take(SearchRules.MAX_TOKENS)

    /** "Call Mum about Sunday" kept whole up to [ECHO_CHARS], else cut at a word with "…". */
    fun echo(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.length <= ECHO_CHARS) return t
        val cut = t.take(ECHO_CHARS)
        val space = cut.lastIndexOf(' ')
        return (if (space >= ECHO_CHARS / 2) cut.take(space) else cut).trimEnd() + "…"
    }

    fun run(query: String, context: CommandBarContext): CommandBarResults {
        val entries = CATALOGUE.filter { offered(it.action, context) }
        val typed = query.trim()
        if (typed.isEmpty()) {
            return CommandBarResults(
                CommandBarSection.entries.mapNotNull { section ->
                    val rows = entries.filter { it.section == section }.map { row(it, context) }
                    if (rows.isEmpty()) null else CommandBarGroup(section.label, rows)
                },
            )
        }
        val tokens = words(typed)
        val matches = entries.mapIndexedNotNull { i, e ->
            SearchRules.score(tokens, listOf(SearchRules.Field(e.title, 3), SearchRules.Field(e.keywords, 1)))
                ?.let { Triple(e, it, i) }
        }.sortedWith(compareByDescending<Triple<Entry, Int, Int>> { it.second }.thenBy { it.third })
            .take(MAX_MATCHES)
            .map { row(it.first, context) }
        val rows = buildList {
            addAll(matches)
            if (SearchRules.tokens(typed).isNotEmpty()) {
                add(CommandBarRow(CommandBarAction.SEARCH_FOR, "Search for “${echo(typed)}”", "Tasks, calendar, lists and more", typed))
            }
            add(CommandBarRow(CommandBarAction.ADD_TASK, "Add “${echo(typed)}” as a task", "In Today, no time set", typed))
        }
        return CommandBarResults(listOf(CommandBarGroup(null, rows)))
    }

    private fun row(e: Entry, ctx: CommandBarContext) = CommandBarRow(e.action, e.title, detail(e.action, ctx), null)
}
