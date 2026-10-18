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
 * The parts of Ask's More (Fold review 2026-10-12, item 7: the 30-row list was too long to scan). Life is a 2-column
 * grid of tiles with a live line each; Daily is a short list of rows; Settings is one row that opens the Settings pane,
 * where every setting lives. Setup and Health show as one status row at the top only when something needs a look.
 */
enum class MoreGroup(val label: String) {
    LIFE("Life"),
    DAILY("Daily"),
    SETTINGS("Settings"),
}

/**
 * A row or tile in Ask's More: a place behind Ask ([destination]), a pane that springs up over Ask (null), Appearance,
 * which unfolds its choices in place ([ShellNav.unfoldsInPlace]), or MEKA's AI, which only says how it is ([ShellNav.opens]).
 * Life tiles carry their [tile] (the core's live line). Lines are fixed words, used when there's nothing live to say.
 */
enum class MoreItem(
    val label: String, val line: String, val destination: ShellDestination?, val group: MoreGroup,
    val tile: os.meka.core.domain.LifeTile? = null,
) {
    LISTS("Lists", "Waiting for · Someday · Decisions · Renewals", ShellDestination.LISTS, MoreGroup.LIFE, os.meka.core.domain.LifeTile.LISTS),
    GOALS("Goals and habits", "Habits, goals and fasting", ShellDestination.GOALS, MoreGroup.LIFE, os.meka.core.domain.LifeTile.GOALS),
    PEOPLE("People", "Promises both ways, birthdays and last contact", null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.PEOPLE),
    DINNERS("Dinners", "Favourites, the week's dinners and their shopping", null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.DINNERS),
    BINS("Bins", "Which bins go out, the evening before", null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.BINS),
    DATE_NIGHT("Date night", "An evening every two weeks, kept clear", null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.DATE_NIGHT),
    SCHOOL("School", "The children's days off, PE and trips", null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.SCHOOL),
    KIDS(os.meka.core.domain.KidsRules.TITLE, os.meka.core.domain.KidsRules.ROW_LINE, null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.KIDS),
    APPOINTMENTS("Appointments", "Dentist, optician, haircut · a nudge when one's due", null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.APPOINTMENTS),
    LEAVE("Annual leave", "Days booked, days left and the school holidays", null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.LEAVE),
    GIFTS("Gifts", "Birthdays, anniversaries and gift ideas", null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.GIFTS),
    TRIPS("Trips", "Trips and bookings, day by day", null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.TRIPS),
    ERRANDS("Errands", "Things to get or do when you're at a place", null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.ERRANDS),
    CAR(os.meka.core.domain.ParkingRules.TITLE, os.meka.core.domain.ParkingRules.ROW_LINE, null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.CAR),
    MONEY(os.meka.core.domain.MoneyRules.TITLE, os.meka.core.domain.MoneyRules.ROW_LINE, null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.MONEY),
    EMERGENCY("Emergency card", "Who to call and where things are", null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.EMERGENCY),
    FACTS("What Navilife knows", "Car reg, GP, sizes · the facts Navilife answers from", null, MoreGroup.LIFE, os.meka.core.domain.LifeTile.FACTS),
    BRIEF("Morning brief", "Your day, who you're waiting on and headlines", null, MoreGroup.DAILY),
    WEEK_AHEAD("Week ahead", "The coming week day by day · Sunday at 18:00", null, MoreGroup.DAILY),
    NEWS("News", "Barça, AI and the headlines · topics and sources", null, MoreGroup.DAILY),
    SHUTDOWN("Shut down the day", "Tick off, carry over and see tomorrow", null, MoreGroup.DAILY),
    TRAIN_QUEUE("Train queue", "Links and podcasts saved for the commute", null, MoreGroup.DAILY),
    READING(os.meka.core.domain.ReadingRules.TITLE, os.meka.core.domain.ReadingRules.ROW_LINE, null, MoreGroup.DAILY),
    VOICE_NOTES(os.meka.core.domain.VoiceNoteRules.TITLE, os.meka.core.domain.VoiceNoteRules.ROW_LINE, null, MoreGroup.DAILY),
    LIFE_ADMIN("Life admin hour", "One hour a week for forms, calls and renewals", null, MoreGroup.DAILY),
    BEDSIDE(os.meka.core.domain.BedsideEntryRules.ROW_LABEL, os.meka.core.domain.BedsideEntryRules.ROW_LINE, null, MoreGroup.DAILY),
    REVIEW("Review", "Your week, looked back on", ShellDestination.REVIEW, MoreGroup.DAILY),
    ABOUT_YOU(os.meka.core.domain.AboutYouRules.TITLE, os.meka.core.domain.AboutYouRules.ROW_LINE, null, MoreGroup.SETTINGS),
    WORK("Work mode", "Work hours and the Work switch", null, MoreGroup.SETTINGS),
    PROTECTED("Protected time", "Evenings and weekends, kept free of tasks", null, MoreGroup.SETTINGS),
    NOTIFICATIONS("Notifications", "Quiet hours, digests and what reaches you", null, MoreGroup.SETTINGS),
    APPEARANCE("Appearance", "Dark, Light or Auto", null, MoreGroup.SETTINGS),
    VOICE("Navilife's voice", "How Navilife sounds in Talk, the brief and calls", null, MoreGroup.SETTINGS),
    TALK("Talk", "The side button and your headphones", null, MoreGroup.SETTINGS),
    CALENDARS("Calendars", "Connected accounts and feeds", null, MoreGroup.SETTINGS),
    MAIL(os.meka.core.domain.MailAccountRules.TITLE, os.meka.core.domain.MailAccountRules.ROW_LINE, null, MoreGroup.SETTINGS),
    FAMILY("Family", "Share the shopping list with your partner", null, MoreGroup.SETTINGS),
    WATCH("Watch", "Link your Galaxy Watch to Navilife", null, MoreGroup.SETTINGS),
    RECOVERY("Recovery key", os.meka.core.domain.RecoveryKeyRules.ROW_LINE, null, MoreGroup.SETTINGS),
    AUTONOMY("What Navilife may do", "What it does on its own · the pause switch", null, MoreGroup.SETTINGS),
    AI("Navilife's AI", "Whether it's on and what it costs this month", null, MoreGroup.SETTINGS),
    ACTIVITY("Activity", "What Navilife did and why", null, MoreGroup.SETTINGS),
    SETUP("Setup", "Everything Navilife can do, and what's left to set up", null, MoreGroup.SETTINGS),
    HEALTH("Health", "Is everything Navilife needs working?", null, MoreGroup.SETTINGS),
    VAULT("Vault", "Documents and when they run out; your data", ShellDestination.VAULT, MoreGroup.SETTINGS),
    YOUR_DATA("Your data", "Export everything as one file", null, MoreGroup.SETTINGS),

    /** The one row on Ask that opens the Settings pane. */
    SETTINGS("Settings", "Work mode, notifications, appearance, voice and Navilife's AI", null, MoreGroup.SETTINGS),
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

    /**
     * Everything reachable from Ask's More (the tiles, Daily, the Settings row and what's in the Settings pane);
     * Calendars, Mail, Family, Watch and Recovery key only once this device is connected (they live on MEKA's server).
     */
    fun more(connected: Boolean): List<MoreItem> =
        MoreItem.entries.filter { connected || it !in SERVER_ONLY }

    private val SERVER_ONLY = setOf(MoreItem.CALENDARS, MoreItem.MAIL, MoreItem.FAMILY, MoreItem.WATCH, MoreItem.RECOVERY)

    /** The Life grid's tiles, in the core's order (both apps). */
    fun lifeTiles(): List<MoreItem> =
        os.meka.core.domain.LifeTileRules.ORDER.map { t -> MoreItem.entries.first { it.tile == t } }

    /** Life's tiles for these lines: today's first, the rest in the usual order (Fold review 17:56, item 2). */
    fun lifeTiles(lines: os.meka.core.domain.LifeLines): List<MoreItem> =
        os.meka.core.domain.LifeTileRules.ordered(lines).map { t -> MoreItem.entries.first { it.tile == t } }

    /** Life's grid without the tiles still to set up (Ask polish, slice 1); today's first. */
    fun lifeLive(lines: os.meka.core.domain.LifeLines): List<MoreItem> =
        os.meka.core.domain.LifeTileRules.live(lines).map { t -> MoreItem.entries.first { it.tile == t } }

    /** The tiles under "To set up", in the usual order. */
    fun lifeToSetUp(lines: os.meka.core.domain.LifeLines): List<MoreItem> =
        os.meka.core.domain.LifeTileRules.toSetUp(lines).map { t -> MoreItem.entries.first { it.tile == t } }

    /** Daily's rows on Ask. */
    fun daily(): List<MoreItem> = MoreItem.entries.filter { it.group == MoreGroup.DAILY }

    /** The core's row for a Daily item (Review has none: it keeps its words). */
    fun dailyRow(item: MoreItem): os.meka.core.domain.DailyRow? = when (item) {
        MoreItem.BRIEF -> os.meka.core.domain.DailyRow.BRIEF
        MoreItem.WEEK_AHEAD -> os.meka.core.domain.DailyRow.WEEK_AHEAD
        MoreItem.NEWS -> os.meka.core.domain.DailyRow.NEWS
        MoreItem.SHUTDOWN -> os.meka.core.domain.DailyRow.SHUTDOWN
        MoreItem.TRAIN_QUEUE -> os.meka.core.domain.DailyRow.TRAIN_QUEUE
        MoreItem.READING -> os.meka.core.domain.DailyRow.READING
        MoreItem.VOICE_NOTES -> os.meka.core.domain.DailyRow.VOICE_NOTES
        MoreItem.LIFE_ADMIN -> os.meka.core.domain.DailyRow.LIFE_ADMIN
        MoreItem.BEDSIDE -> os.meka.core.domain.DailyRow.BEDSIDE
        else -> null
    }

    /**
     * A Daily row's line (Ask polish, item 3): the core's live one, the Bedside clock's from this phone's screen saver
     * ([screenSaver], null when it couldn't be read), else the row's fixed words.
     */
    fun dailyLine(
        item: MoreItem, lines: os.meka.core.domain.DailyLines, screenSaver: Boolean? = null,
        voiceNotes: os.meka.core.domain.DailyLine? = null,
    ): os.meka.core.domain.DailyLine {
        val live = when (item) {
            MoreItem.BEDSIDE -> os.meka.core.domain.DailyRules.bedside(screenSaver)
            // Voice notes, slice 1: the notes live on this phone, so their line comes from its store, not the core.
            MoreItem.VOICE_NOTES -> voiceNotes
            else -> dailyRow(item)?.let { lines.of(it) }
        }
        return live ?: os.meka.core.domain.DailyLine(item.line, false)
    }

    /** What the Settings pane lists, in order. */
    fun settings(connected: Boolean): List<MoreItem> =
        more(connected).filter { it.group == MoreGroup.SETTINGS && it != MoreItem.SETTINGS }

    /**
     * The status row at the top of More: Health when something needs a look, else Setup while steps are left, else
     * nothing (both stay in Settings either way).
     */
    fun statusItem(healthAttention: Int, setupLeft: Int): MoreItem? = when {
        healthAttention > 0 -> MoreItem.HEALTH
        setupLeft > 0 -> MoreItem.SETUP
        else -> null
    }

    /**
     * Stagger steps for Ask (40 ms apart; the title 0, the field 1, the status row 2): each part starts one step after
     * the one before began, its label leading. Life: label 3, tiles a row at a time from 4 · Daily: label 5, rows 6–12 ·
     * the Settings row 7. The Settings pane's rows: 1, 2, 3… after its title.
     */
    const val STATUS_STEP = 2

    fun moreLabelStep(section: Int): Int = 3 + 2 * section

    fun moreRowStep(section: Int, row: Int): Int = moreLabelStep(section) + 1 + row

    fun tileStep(index: Int): Int = os.meka.core.domain.LifeTileRules.step(moreRowStep(0, 0), index)

    /** Tiles arrive a row at a time in a grid of [columns]. */
    fun tileStep(index: Int, columns: Int): Int = os.meka.core.domain.LifeTileRules.step(moreRowStep(0, 0), index, columns)

    val SETTINGS_STEP: Int get() = moreLabelStep(2)

    /** Every item opens something except MEKA's AI, which only says how it is. */
    fun opens(item: MoreItem): Boolean = item != MoreItem.AI

    /** A Life tile's line: the core's live one, else (before the core's first lines arrive) the tile's short prompt. */
    fun tileLine(item: MoreItem, live: os.meka.core.domain.LifeTileLine?): os.meka.core.domain.LifeTileLine =
        live ?: os.meka.core.domain.LifeTileLine(item.tile?.let { os.meka.core.domain.LifeTileRules.prompt(it) } ?: item.line, false)

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
