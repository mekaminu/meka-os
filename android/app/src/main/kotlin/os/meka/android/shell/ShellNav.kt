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

enum class ShellLayout {
    /** Closed Fold: content above a bottom bar. */
    BOTTOM_BAR,

    /** Open Fold and wide windows: a rail on the left, content (Today keeps its own two panes) on the right. */
    RAIL,
}

/**
 * A row in Ask's "More" list: a place behind Ask ([destination]), a pane that springs up over Ask (null), or
 * Appearance, which unfolds its choices in place ([ShellNav.unfoldsInPlace]).
 * Lines are fixed words, not counts, except Lists when something on it is due ([ShellNav.moreLine]).
 * Today's header keeps only Search and Plan my day (Today clarity, slice 2): the brief, the shutdown, work mode and
 * the theme moved here.
 */
enum class MoreItem(val label: String, val line: String, val destination: ShellDestination?) {
    LISTS("Lists", "Waiting for · Someday · Decisions · Renewals", ShellDestination.LISTS),
    GOALS("Goals and habits", "Habits, goals and fasting", ShellDestination.GOALS),
    REVIEW("Review", "Your week, looked back on", ShellDestination.REVIEW),
    VAULT("Vault", "Your data now; documents later", ShellDestination.VAULT),
    BRIEF("Morning brief", "Your day, who you're waiting on and headlines", null),
    SHUTDOWN("Shut down the day", "Tick off, carry over and see tomorrow", null),
    WORK("Work mode", "Work hours and the Work switch", null),
    NOTIFICATIONS("Notifications", "Quiet hours, digests and what reaches you", null),
    APPEARANCE("Appearance", "Dark, Light or Auto", null),
    ACTIVITY("Activity", "What MEKA did and why", null),
    YOUR_DATA("Your data", "Export everything as one file", null),
    CALENDARS("Calendars", "Connected accounts and feeds", null),
}

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

    /** Ask's More list; Calendars only once this device is connected (before that, Today offers Connect). */
    fun more(connected: Boolean): List<MoreItem> = MoreItem.entries.filter { connected || it != MoreItem.CALENDARS }

    /** A More row's line: Lists says what's due when something is ("2 need you"), else the fixed words. */
    fun moreLine(item: MoreItem, listsDue: Int, atWork: Boolean = false): String = when {
        item == MoreItem.LISTS && listsDue > 0 -> "$listsDue need${if (listsDue == 1) "s" else ""} you · ${item.line}"
        item == MoreItem.WORK -> workLine(atWork)
        else -> item.line
    }

    /** Appearance unfolds its three choices in the row itself; every other row opens something. */
    fun unfoldsInPlace(item: MoreItem): Boolean = item == MoreItem.APPEARANCE

    /** Work mode's line says where you are now ("At work · Work hours and the Work switch"), as the header did. */
    fun workLine(atWork: Boolean): String = "${if (atWork) "At work" else "Off work"} · ${MoreItem.WORK.line}"

    /** Whether a More row's line is lit in the accent colour. */
    fun moreLit(item: MoreItem, listsDue: Int): Boolean = item == MoreItem.LISTS && listsDue > 0

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
