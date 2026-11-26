package os.meka.core.domain

/**
 * Appearance's choices as rows opening sheets (walk-through week 5, A17: no open chip rows on a page; g5a 10:
 * "Appearance (Dark/Light/Auto, Expressive/Subtle/Off, Folded/Hidden/Shown, Calm/Always moving/Off)" stayed open on the
 * page). Each is now one card row saying what's chosen ("Theme" · "Dark" ›) opening a sheet of the same choices, the
 * chosen one lit, each saying what it does. A choice saves as the chip did and closes the sheet.
 *
 * The theme is per device and each app keeps its own store; here it is named by id ("dark", "light", "system": the
 * Mac's raw values, the Fold's ThemeChoice names lower-cased). Reuses Notifications' row and sheet shapes
 * ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure; nothing stored.
 */
object AppearanceChoiceRules {
    const val THEME_HEAD = "Theme"
    const val MOTION_HEAD = "Motion"
    const val TICKER_HEAD = "News ticker"

    const val DARK = "dark"
    const val LIGHT = "light"
    const val SYSTEM = "system"

    const val THEME_NOTE = "On this device only. Every colour blends across as it changes."
    const val MOTION_NOTE = "On this device only. The Motion check under the row says what plays."
    const val DONE_NOTE = "The same on the Fold and the Mac."
    const val TICKER_NOTE = "On this device only. Topics picks what it carries."

    private data class Theme(val id: String, val label: String, val line: String)

    private val THEMES = listOf(
        Theme(DARK, "Dark", "Navilife's own look · calm in a dark room"),
        Theme(LIGHT, "Light", "Light pages for a bright room"),
        Theme(SYSTEM, "Auto", "Follows this device's own light or dark setting"),
    )

    private fun spoken(label: String, line: String, lit: Boolean) = "$label, $line" + if (lit) ", chosen" else ""

    private fun row(head: String, value: String) = NotifyChoiceRow(head, value, "$head, $value. Opens the choices")

    // ---- Theme ----

    /** The theme in force: an unknown or missing id is Dark, the default. */
    fun themeId(id: String?): String = THEMES.firstOrNull { it.id == id?.lowercase() }?.id ?: DARK

    fun themeLabel(id: String?): String = THEMES.first { it.id == themeId(id) }.label

    fun themeRow(id: String?): NotifyChoiceRow = row(THEME_HEAD, themeLabel(id))

    fun themeSheet(id: String?): NotifyChoiceSheet {
        val now = themeId(id)
        return NotifyChoiceSheet(THEME_HEAD, THEME_NOTE, THEMES.map { t ->
            NotifyChoice(t.id, t.label, t.line, t.id == now, spoken(t.label, t.line, t.id == now))
        })
    }

    /** The theme id a choice sets; null when the name isn't a choice or is already in force. */
    fun themeNamed(id: String?, name: String): String? =
        THEMES.firstOrNull { it.id == name }?.id?.takeIf { it != themeId(id) }

    // ---- Motion ----

    /** Nothing chosen reads as Expressive: it is what plays (Meka, 2026-10-08). */
    fun motionRow(stored: MotionChoice?): NotifyChoiceRow = row(MOTION_HEAD, MotionRules.lit(stored).label)

    fun motionSheet(stored: MotionChoice?): NotifyChoiceSheet {
        val lit = MotionRules.lit(stored)
        return NotifyChoiceSheet(MOTION_HEAD, MOTION_NOTE, MotionChoice.entries.map { m ->
            NotifyChoice(m.id, m.label, m.line, m == lit, spoken(m.label, m.line, m == lit))
        })
    }

    /**
     * The motion a choice sets; null when the name isn't a choice or is already what's stored. Expressive with nothing
     * chosen is still stored when picked, so the choice is his, not the default.
     */
    fun motionNamed(stored: MotionChoice?, name: String): MotionChoice? =
        MotionRules.choice(name)?.takeIf { it != stored }

    // ---- Done tasks on Today ----

    fun doneRow(show: DoneShow): NotifyChoiceRow = row(DoneTodayRules.HEADING, show.label)

    fun doneSheet(show: DoneShow): NotifyChoiceSheet = NotifyChoiceSheet(DoneTodayRules.HEADING, DONE_NOTE, DoneShow.entries.map { s ->
        NotifyChoice(s.code, s.label, s.line, s == show, spoken(s.label, s.line, s == show))
    })

    fun doneNamed(show: DoneShow, name: String): DoneShow? =
        DoneShow.entries.firstOrNull { it.code == name }?.takeIf { it != show }

    // ---- News ticker ----

    fun tickerRow(mode: TickerMode): NotifyChoiceRow = row(TICKER_HEAD, mode.label)

    fun tickerSheet(mode: TickerMode): NotifyChoiceSheet = NotifyChoiceSheet(TICKER_HEAD, TICKER_NOTE, TickerMode.entries.map { m ->
        NotifyChoice(m.id, m.label, m.line, m == mode, spoken(m.label, m.line, m == mode))
    })

    fun tickerNamed(mode: TickerMode, name: String): TickerMode? =
        TickerMode.entries.firstOrNull { it.id == name }?.takeIf { it != mode }
}
