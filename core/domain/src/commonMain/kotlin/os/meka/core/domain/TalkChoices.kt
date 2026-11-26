package os.meka.core.domain

/**
 * Settings → Talk's switches as rows opening sheets (walk-through week 5, A17: no open buttons or chip rows on a page).
 * Each section with a Turn on / Turn off pill (When I open Navilife, Say “Hey Obi”, Talk over Navilife; on the Mac also
 * From any app and Welcome back) now shows one card row saying what's chosen ("Talk over Navilife" · "On" ›) under its
 * label, opening a sheet of On and Off, the chosen one lit, each saying what it does (the section's own On / Off line,
 * so the page and the sheet never disagree). A choice saves as the pill did and closes the sheet; the status line and
 * the steps under the row stay. The side button's Open default apps is an action, not a switch, and stays a button.
 *
 * [named] gives the value a choice sets, or null when the name isn't a choice or is already in force. Reuses
 * Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure; nothing stored.
 */
object TalkChoiceRules {
    const val ON = "on"
    const val OFF = "off"
    const val ON_LABEL = "On"
    const val OFF_LABEL = "Off"
    /** Every switch's pill said one of these ([TalkOnOpenRules.TURN_ON] and the rest are the same words). */
    private val SWITCH_WORDS = setOf(TalkOnOpenRules.TURN_ON, TalkOnOpenRules.TURN_OFF)

    /** Whether [s] is an on/off switch (its pill said Turn on or Turn off), shown as a row rather than a button. */
    fun isSwitch(s: TalkSetupSection): Boolean = s.action in SWITCH_WORDS

    /** Whether [s]'s switch is on now (its pill offered Turn off). */
    fun isOn(s: TalkSetupSection): Boolean = s.action == TalkOnOpenRules.TURN_OFF

    /** The row for a switch section ("Talk over Navilife" · "On"), or null for a section without a switch. */
    fun row(s: TalkSetupSection): NotifyChoiceRow? {
        if (!isSwitch(s)) return null
        val value = if (isOn(s)) ON_LABEL else OFF_LABEL
        return NotifyChoiceRow(s.label, value, "${s.label}, $value. Opens the choices")
    }

    /**
     * The sheet for the switch called [label] ([on]: chosen now), on the Fold ([mac] false) or the Mac; null for a label
     * that isn't one of Talk's switches. Each choice's line is that section's On or Off line without its "On: " /
     * "Off: ", and the note says where it's kept (and, for the two that listen, that turning it on asks for the mic).
     */
    fun sheet(label: String, on: Boolean, mac: Boolean): NotifyChoiceSheet? {
        val onLine = section(label, true, mac)?.status ?: return null
        val offLine = section(label, false, mac)?.status ?: return null
        val picks = listOf(
            Triple(ON, ON_LABEL, plain(onLine, "On: ")) to true,
            Triple(OFF, OFF_LABEL, plain(offLine, "Off: ")) to false,
        )
        return NotifyChoiceSheet(label, note(label, mac), picks.map { (p, value) ->
            val lit = value == on
            NotifyChoice(p.first, p.second, p.third, lit, "${p.second}, ${p.third}" + if (lit) ", chosen" else "")
        })
    }

    /** True for On, false for Off; null when [name] isn't a choice or is already in force. */
    fun named(on: Boolean, name: String): Boolean? = when (name) {
        ON -> true
        OFF -> false
        else -> null
    }?.takeIf { it != on }

    /** Where the switch is kept, and whether turning it on asks for the microphone. */
    fun note(label: String, mac: Boolean): String {
        val kept = "Kept on this ${if (mac) "Mac" else "phone"} only."
        return when (label) {
            TalkOnOpenRules.LABEL, HelloWakeRules.LABEL -> "$kept Turning it on asks for the microphone if it isn't allowed yet."
            else -> kept
        }
    }

    /** The switch's section as it reads on or off (From any app as if ⌥Space were free; Hey Obi as if speech works). */
    private fun section(label: String, on: Boolean, mac: Boolean): TalkSetupSection? = when (label) {
        TalkOnOpenRules.LABEL -> TalkOnOpenRules.section(on, mac)
        HelloWakeRules.LABEL -> HelloWakeRules.section(on, mac)
        BargeInRules.LABEL -> BargeInRules.section(on, mac)
        TalkAnywhereRules.LABEL -> if (mac) TalkAnywhereRules.section(on, taken = false) else null
        WelcomeBackRules.LABEL -> if (mac) WelcomeBackRules.section(on) else null
        else -> null
    }

    private fun plain(line: String, prefix: String): String =
        line.removePrefix(prefix).replaceFirstChar { it.uppercaseChar() }
}
