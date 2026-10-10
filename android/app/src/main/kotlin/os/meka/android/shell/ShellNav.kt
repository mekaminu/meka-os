package os.meka.android.shell

/**
 * The app shell's rules (build plan M1, App shell; Four tabs, one front door), kept free of Compose so they are
 * unit-tested and match the Mac (macos/MekaOS/Shell/ShellNav.swift) rule for rule.
 *
 * The bar holds four tabs (Today · Needs you · Calendar · Ask). Everything else stays a destination but is reached
 * from Ask's "More" list, cards on Today, search and notifications; it sits "behind" Ask, so Ask stays lit there and
 * back returns to Ask. Enum order is the order content slides in: the places behind Ask come after it.
 */
enum class ShellDestination(val label: String) {
    TODAY("Today"),
    NEEDS_YOU("Needs you"),
    CALENDAR("Calendar"),
    ASK("Ask"),
    LISTS("Lists"),
    GOALS("Goals"),
    REVIEW("Review"),
    VAULT("Vault"),
}

/**
 * The tab icons (motion pass 2: tab icons morph outline → filled with a spring), drawn by the app (no icon library):
 * Today a sun on the horizon, Needs you a circle with "!", Calendar a calendar page, Ask a speech bubble. The Mac's
 * sidebar uses the matching SF Symbols (sun.horizon, exclamationmark.circle, calendar, bubble.left).
 */
enum class TabGlyph { DAY, NEEDS, CALENDAR, ASK }

enum class ShellLayout {
    /** Closed Fold: content above a bottom bar. */
    BOTTOM_BAR,

    /** Open Fold and wide windows: a rail on the left, content (Today keeps its own two panes) on the right. */
    RAIL,
}

/**
 * The sections of Ask's More list (Fold review 2026-10-08, item 10: twelve rows in one flat list were hard to scan).
 * Each has a small section label over its rows; the rows are the same.
 */
enum class MoreGroup(val label: String) {
    PLACES("Places"),
    DAILY("Daily"),
    SETTINGS("Settings"),
}

/**
 * A row in Ask's "More" list: a place behind Ask ([destination]), a pane that springs up over Ask (null), or
 * Appearance, which unfolds its choices in place ([ShellNav.unfoldsInPlace]).
 * Lines are fixed words, not counts, except Lists when something on it is due ([ShellNav.moreLine]).
 * Today's header keeps only Search and Plan my day (Today clarity, slice 2): the brief, the shutdown, work mode and
 * the theme moved here.
 */
enum class MoreItem(val label: String, val line: String, val destination: ShellDestination?, val group: MoreGroup) {
    LISTS("Lists", "Waiting for · Someday · Decisions · Renewals", ShellDestination.LISTS, MoreGroup.PLACES),
    GOALS("Goals and habits", "Habits, goals and fasting", ShellDestination.GOALS, MoreGroup.PLACES),
    REVIEW("Review", "Your week, looked back on", ShellDestination.REVIEW, MoreGroup.PLACES),
    VAULT("Vault", "Your data now; documents later", ShellDestination.VAULT, MoreGroup.PLACES),
    BRIEF("Morning brief", "Your day, who you're waiting on and headlines", null, MoreGroup.DAILY),
    NEWS("News", "Barça, AI and the headlines · topics and sources", null, MoreGroup.DAILY),
    SHUTDOWN("Shut down the day", "Tick off, carry over and see tomorrow", null, MoreGroup.DAILY),
    SCHOOL("School", "Rex and Logan's days off, PE and trips", null, MoreGroup.DAILY),
    DINNERS("Dinners", "Favourites, the week's dinners and their shopping", null, MoreGroup.DAILY),
    DATE_NIGHT("Date night", "An evening every two weeks, kept clear", null, MoreGroup.DAILY),
    WORK("Work mode", "Work hours and the Work switch", null, MoreGroup.SETTINGS),
    NOTIFICATIONS("Notifications", "Quiet hours, digests and what reaches you", null, MoreGroup.SETTINGS),
    APPEARANCE("Appearance", "Dark, Light or Auto", null, MoreGroup.SETTINGS),
    VOICE("MEKA's voice", "How MEKA sounds in Talk, the brief and calls", null, MoreGroup.SETTINGS),
    TALK("Talk", "The side button and your headphones", null, MoreGroup.SETTINGS),
    CALENDARS("Calendars", "Connected accounts and feeds", null, MoreGroup.SETTINGS),
    FAMILY("Family", "Share the shopping list with Jeanette", null, MoreGroup.SETTINGS),
    WATCH("Watch", "Link your Galaxy Watch to MEKA", null, MoreGroup.SETTINGS),
    SETUP("Setup", "Everything MEKA can do, and what's left to set up", null, MoreGroup.SETTINGS),
    HEALTH("Health", "Is everything MEKA needs working?", null, MoreGroup.SETTINGS),
    ACTIVITY("Activity", "What MEKA did and why", null, MoreGroup.SETTINGS),
    YOUR_DATA("Your data", "Export everything as one file", null, MoreGroup.SETTINGS),
}

/** One section of More: its label and its rows, in order. */
data class MoreSection(val group: MoreGroup, val items: List<MoreItem>)

object ShellNav {
    /** Same breakpoint Today uses for its two panes. */
    const val WIDE_DP = 600f

    /** The four tabs, in the bar, the rail and the top of the Mac sidebar. */
    val TABS = listOf(ShellDestination.TODAY, ShellDestination.NEEDS_YOU, ShellDestination.CALENDAR, ShellDestination.ASK)

    fun layoutFor(widthDp: Float): ShellLayout = if (widthDp >= WIDE_DP) ShellLayout.RAIL else ShellLayout.BOTTOM_BAR

    /** Destinations shown in the bar or rail: the same four either way. */
    @Suppress("UNUSED_PARAMETER")
    fun destinations(layout: ShellLayout): List<ShellDestination> = TABS

    /** The tab a destination sits behind: Ask for the places in More, null for the tabs themselves. */
    fun parent(d: ShellDestination): ShellDestination? = if (d in TABS) null else ShellDestination.ASK

    /** Which tab is lit for [current]: itself, or Ask for a place reached from More. */
    fun barSelection(current: ShellDestination): ShellDestination = parent(current) ?: current

    /** Content slides the way you moved: +1 forward (from the right), -1 back, 0 for no change. */
    fun direction(from: ShellDestination, to: ShellDestination): Int = to.ordinal.compareTo(from.ordinal).coerceIn(-1, 1)

    /**
     * Ask's More list; Calendars and Family only once this device is connected (before that, Today offers Connect;
     * Family's links live on MEKA's server).
     */
    fun more(connected: Boolean): List<MoreItem> =
        MoreItem.entries.filter { connected || (it != MoreItem.CALENDARS && it != MoreItem.FAMILY && it != MoreItem.WATCH) }

    /** More in its sections (Places · Daily · Settings), each with its rows; a section with no rows is left out. */
    fun moreSections(connected: Boolean): List<MoreSection> =
        MoreGroup.entries.map { g -> MoreSection(g, more(connected).filter { it.group == g }) }.filter { it.items.isNotEmpty() }

    /**
     * Stagger steps for More (40 ms apart, after the title 0, field 1): each section starts one step after the one
     * before began rather than after its last row, so the list settles quickly; a label leads its rows by one step.
     * Places: label 2, rows 3–6 · Daily: label 4, rows 5–9 · Settings: label 6, rows 7–18.
     */
    fun moreLabelStep(section: Int): Int = 2 + 2 * section

    fun moreRowStep(section: Int, row: Int): Int = moreLabelStep(section) + 1 + row

    /**
     * A More row's line: Lists says what's due when something is ("2 need you"), Health its summary once checked
     * ("2 things need a look"), Setup its own ("3 steps left · 12 of 15 done"), else the fixed words.
     */
    fun moreLine(item: MoreItem, listsDue: Int, atWork: Boolean = false, health: String? = null, setup: String? = null): String = when {
        item == MoreItem.LISTS && listsDue > 0 -> "$listsDue need${if (listsDue == 1) "s" else ""} you · ${item.line}"
        item == MoreItem.WORK -> workLine(atWork)
        item == MoreItem.HEALTH && health != null -> health
        item == MoreItem.SETUP && setup != null -> setup
        else -> item.line
    }

    /** Appearance unfolds its three choices in the row itself; every other row opens something. */
    fun unfoldsInPlace(item: MoreItem): Boolean = item == MoreItem.APPEARANCE

    /** Work mode's line says where you are now ("At work · Work hours and the Work switch"), as the header did. */
    fun workLine(atWork: Boolean): String = "${if (atWork) "At work" else "Off work"} · ${MoreItem.WORK.line}"

    /** Whether a More row's line is lit in the accent colour. */
    fun moreLit(item: MoreItem, listsDue: Int, healthAttention: Int = 0, setupLeft: Int = 0): Boolean =
        (item == MoreItem.LISTS && listsDue > 0) || (item == MoreItem.HEALTH && healthAttention > 0) ||
            (item == MoreItem.SETUP && setupLeft > 0)

    /** A tab's icon; the places behind Ask aren't in the bar, so they have none. */
    fun glyph(d: ShellDestination): TabGlyph? = when (d) {
        ShellDestination.TODAY -> TabGlyph.DAY
        ShellDestination.NEEDS_YOU -> TabGlyph.NEEDS
        ShellDestination.CALENDAR -> TabGlyph.CALENDAR
        ShellDestination.ASK -> TabGlyph.ASK
        else -> null
    }

    /** Badge text on Needs you: nothing at zero, the count up to 9, then "9+". */
    fun badge(count: Int): String? = when {
        count <= 0 -> null
        count > 9 -> "9+"
        else -> count.toString()
    }

    /** Screen-reader label for a destination, including the badge. */
    fun accessibilityLabel(d: ShellDestination, needsYouCount: Int): String {
        val n = if (d == ShellDestination.NEEDS_YOU) needsYouCount else 0
        return if (n > 0) "${d.label}, $n waiting" else d.label
    }
}
