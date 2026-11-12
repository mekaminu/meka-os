package os.meka.core.domain

/**
 * The Ask hub (approved by Meka 2026-11-11 22:19, `docs/design/look/AskHub.dc.html`, look review batch 1): under the
 * field and the "wants a look" row, one **Life · Daily · Settings** switch in place of one long page. Life is the tiles,
 * a page at a time (eight on the phone: two columns, four rows; twelve on the open Fold), today's first, with
 * "Places to set up" folded under them; Daily is one card of rows; Settings is four boxes that fold open
 * ([SettingsBox]). Both apps draw it from these rules; the places themselves are the apps' own (a [HelpPlace] names each
 * row, so the Fold maps it by name and the Mac by key).
 */
enum class AskTab(val label: String) {
    LIFE("Life"),
    DAILY("Daily"),
    SETTINGS("Settings");

    companion object {
        /** The tab remembered on this device ([stored] is a tab's name), else Life. */
        fun of(stored: String?): AskTab = entries.firstOrNull { it.name == stored } ?: LIFE
    }
}

/** Settings' four boxes, each with its rows in order (the design's grouping; every setting is in exactly one). */
enum class SettingsBox(val title: String, val rows: List<HelpPlace>) {
    YOU(
        "You and your day",
        listOf(HelpPlace.ABOUT_YOU, HelpPlace.WORK, HelpPlace.PROTECTED, HelpPlace.CALENDARS, HelpPlace.MAIL, HelpPlace.FAMILY),
    ),
    OBI("Obi", listOf(HelpPlace.VOICE, HelpPlace.TALK, HelpPlace.SPEAK_UP, HelpPlace.AUTONOMY, HelpPlace.AI)),
    DEVICE("Phone, watch and look", listOf(HelpPlace.APPEARANCE, HelpPlace.NOTIFICATIONS, HelpPlace.WATCH)),
    DATA(
        "Privacy and data",
        listOf(HelpPlace.VAULT, HelpPlace.YOUR_DATA, HelpPlace.RECOVERY, HelpPlace.ACTIVITY, HelpPlace.HEALTH, HelpPlace.SETUP),
    ),
}

object AskHubRules {
    /** The switch's tabs, in order. */
    val TABS: List<AskTab> = AskTab.entries.toList()

    /** Settings' boxes, in order. */
    val BOXES: List<SettingsBox> = SettingsBox.entries.toList()

    /** Life's tiles come four rows to a page: eight with two columns, twelve with three. */
    const val ROWS_A_PAGE = 4

    fun perPage(columns: Int): Int = ROWS_A_PAGE * columns.coerceAtLeast(1)

    /** How many pages [count] tiles take (always at least one). */
    fun pages(count: Int, columns: Int): Int = maxOf(1, (count + perPage(columns) - 1) / perPage(columns))

    /** The page to show: the one asked for, kept inside the pages there are (tiles can leave while it's open). */
    fun page(asked: Int, count: Int, columns: Int): Int = asked.coerceIn(0, pages(count, columns) - 1)

    /** The first tile on [page] (an index into the ordered tiles). */
    fun pageStart(page: Int, count: Int, columns: Int): Int = page(page, count, columns) * perPage(columns)

    /** One past the last tile on [page]. */
    fun pageEnd(page: Int, count: Int, columns: Int): Int = minOf(count, pageStart(page, count, columns) + perPage(columns))

    /** "1–8 of 15 · today’s first" under the tiles, only when there is more than one page. */
    fun pageLine(page: Int, count: Int, columns: Int): String? {
        if (pages(count, columns) <= 1) return null
        return "${pageStart(page, count, columns) + 1}–${pageEnd(page, count, columns)} of $count · today’s first"
    }

    /** The page after [page] moving [by] (‹ is −1, › is +1, a swipe the same), kept inside the pages. */
    fun turn(page: Int, by: Int, count: Int, columns: Int): Int = page(page + by, count, columns)

    /** Whether ‹ or › can turn (dimmed at the ends). */
    fun canTurn(page: Int, by: Int, count: Int, columns: Int): Boolean =
        page(page, count, columns) != turn(page, by, count, columns)

    /** Folded "Places to set up · 5" shows their names after it: "Bins, Gifts, Annual leave". */
    fun setUpPeek(names: List<String>): String = names.joinToString(", ")

    /** The box a setting sits in (null for anything that isn't a setting). */
    fun boxOf(place: HelpPlace): SettingsBox? = SettingsBox.entries.firstOrNull { place in it.rows }

    /** The box open the first time Settings shows. */
    val FIRST_OPEN: SettingsBox = SettingsBox.YOU

    /** A folded box's peek: the names of what's in it, "About you · Work mode · Protected time". */
    fun peek(labels: List<String>): String = labels.joinToString(" · ")

    /** Remembered open boxes from their stored names ("YOU,DATA"); null (never chosen) opens [FIRST_OPEN]. */
    fun openBoxes(stored: String?): Set<SettingsBox> =
        if (stored == null) setOf(FIRST_OPEN)
        else stored.split(',').mapNotNull { n -> SettingsBox.entries.firstOrNull { it.name == n.trim() } }.toSet()

    /** [open] with [box] folded or unfolded, as stored. */
    fun toggle(open: Set<SettingsBox>, box: SettingsBox): Set<SettingsBox> = if (box in open) open - box else open + box

    fun stored(open: Set<SettingsBox>): String = SettingsBox.entries.filter { it in open }.joinToString(",") { it.name }

    /** Whether [box] is open in a stored string (Swift's convenience; same as [openBoxes]). */
    fun isOpen(stored: String?, box: SettingsBox): Boolean = box in openBoxes(stored)

    /** [stored] with [box] toggled (Swift's convenience). */
    fun toggled(stored: String?, box: SettingsBox): String = stored(toggle(openBoxes(stored), box))

    /**
     * Where a help answer's Take me there lands on the hub: Settings itself and Navilife's AI and Appearance (which
     * open nothing of their own) switch to the Settings tab with their box open; null for everything else, which opens
     * its own place.
     */
    fun settingsTarget(place: HelpPlace): SettingsBox? = when (place) {
        HelpPlace.SETTINGS -> FIRST_OPEN
        HelpPlace.AI, HelpPlace.APPEARANCE -> boxOf(place)
        else -> null
    }
}
