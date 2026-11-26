package os.meka.core.domain

/**
 * Work mode → Leaving now as rows opening sheets (walk-through week 5, A17: no open chip rows or pills on a page). Its
 * On/Off pill, its row of name chips (To) and its two When chips are each one card row saying what's chosen
 * ("Leaving now" · "On" ›, "To" · "Sam" ›, "When" · "When work ends" ›) opening a sheet of the choices, the one in use
 * lit, each saying what it does; a choice writes what the chip wrote and closes the sheet.
 *
 * The To sheet's names are the Family and always-notify lists ([names]), with the name in use first when it is on
 * neither (typed on the Mac, or About you's partner); one is lit as the chip was ([lit]). With nobody to choose the To row
 * says "No one yet" and opens nothing; the line above it says to add someone. The Mac already sets these with a toggle,
 * a text field and a toggle. Reuses Notifications' row and sheet shapes ([NotifyChoiceRow], [NotifyChoice],
 * [NotifyChoiceSheet]). Pure; nothing stored.
 */
object LeavingChoiceRules {
    const val ON = "on"
    const val OFF = "off"
    const val ON_LABEL = "On"
    const val OFF_LABEL = "Off"
    const val TO = "To"
    const val WHEN = "When"
    const val NO_ONE_YET = "No one yet"
    const val CHOOSE = "Choose someone"
    const val WORK_ENDS = "work-ends"
    const val LEAVE_AREA = "leave-area"
    const val WORK_ENDS_LABEL = "When work ends"
    const val LEAVE_AREA_LABEL = "When I leave work"
    const val TO_SHEET = "Send the draft to"
    const val WHEN_SHEET = "Draft it"
    const val SYNC_NOTE = "Synced, so the Mac has the same. Nothing is sent until you tap Send."
    const val TO_NOTE = "From your Family and Always notify lists. Nothing is sent until you tap Send."
    const val WHEN_NOTE = "Office days only. Synced, so the Mac has the same."

    /** The switch's row: "Leaving now" · "On". */
    fun switchRow(v: LeavingView): NotifyChoiceRow {
        val value = if (v.on) ON_LABEL else OFF_LABEL
        return NotifyChoiceRow(LeavingRules.TITLE, value, "${LeavingRules.TITLE}, $value. Opens the choices")
    }

    /** The switch's sheet: On says what it drafts and when, Off what goes; the one in force lit. */
    fun switchSheet(v: LeavingView): NotifyChoiceSheet {
        val onLine = if (v.byArea) "A draft to say you're on your way, when you leave the work area on office days"
            else "A draft to say you're on your way, when work ends on office days"
        val picks = listOf(Triple(ON, ON_LABEL, onLine) to true, Triple(OFF, OFF_LABEL, "No draft when work ends; nothing else changes") to false)
        return NotifyChoiceSheet(LeavingRules.TITLE, SYNC_NOTE, picks.map { (p, value) -> choice(p.first, p.second, p.third, value == v.on) })
    }

    /** True for On, false for Off; null when [name] isn't a choice or is already in force. */
    fun switchNamed(v: LeavingView, name: String): Boolean? = when (name) {
        ON -> true
        OFF -> false
        else -> null
    }?.takeIf { it != v.on }

    /** The names on offer: Family, then Always notify, with the name in use first when it is on neither. */
    fun names(v: LeavingView, lists: PeopleLists): List<String> {
        val n = (lists.family + lists.alwaysNotify).toList().distinct()
        return if (v.to.isBlank() || n.any { LeavingRules.samePerson(v.to, it) }) n else listOf(v.to) + n
    }

    /** Whether [name] is the one in use: the same person, and the exact name when that one is on offer. */
    fun lit(v: LeavingView, names: List<String>, name: String): Boolean =
        LeavingRules.samePerson(v.to, name) && (name == v.to || names.none { it == v.to })

    /**
     * The To row: "To" · the lit name (the name in use), "Choose someone" with no one in use yet, or "No one yet" with
     * nobody to choose (no sheet then).
     */
    fun toRow(v: LeavingView, lists: PeopleLists): NotifyChoiceRow {
        val names = names(v, lists)
        val value = names.firstOrNull { lit(v, names, it) } ?: v.to.takeIf { it.isNotBlank() } ?: if (names.isEmpty()) NO_ONE_YET else CHOOSE
        val tail = if (names.isEmpty()) "Add someone to Family in Work mode first" else "Opens the choices"
        return NotifyChoiceRow(TO, value, "Send the draft to, $value. $tail")
    }

    /** The To sheet: each name, saying where it is from, the one in use lit. */
    fun toSheet(v: LeavingView, lists: PeopleLists): NotifyChoiceSheet {
        val names = names(v, lists)
        val family = lists.family.map(People::key).toSet()
        return NotifyChoiceSheet(TO_SHEET, TO_NOTE, names.map { name ->
            val from = when {
                People.key(name) in family -> "Family"
                lists.alwaysNotify.any { People.key(it) == People.key(name) } -> "Always notify"
                else -> "In use now"
            }
            choice(name, name, "$from · the draft goes to $name on WhatsApp", lit(v, names, name))
        })
    }

    /** The name a To choice picks, or null when it isn't on offer or is the one in use. */
    fun toNamed(v: LeavingView, lists: PeopleLists, name: String): String? {
        val names = names(v, lists)
        return name.takeIf { it in names && !lit(v, names, it) }
    }

    /** The When row: "When" · "When work ends" / "When I leave work". */
    fun whenRow(v: LeavingView): NotifyChoiceRow {
        val value = if (v.byArea) LEAVE_AREA_LABEL else WORK_ENDS_LABEL
        return NotifyChoiceRow(WHEN, value, "Draft it, $value. Opens the choices")
    }

    /** The When sheet: at the end of work hours, or on leaving the work area (with what that needs). */
    fun whenSheet(v: LeavingView): NotifyChoiceSheet = NotifyChoiceSheet(WHEN_SHEET, WHEN_NOTE, listOf(
        choice(WORK_ENDS, WORK_ENDS_LABEL, "At the end of your work hours", !v.byArea),
        choice(LEAVE_AREA, LEAVE_AREA_LABEL, "When the phone notices you leave the work area; needs location all the time", v.byArea),
    ))

    /** True for leaving the work area, false for when work ends; null when [name] isn't a choice or is in force. */
    fun whenNamed(v: LeavingView, name: String): Boolean? = when (name) {
        LEAVE_AREA -> true
        WORK_ENDS -> false
        else -> null
    }?.takeIf { it != v.byArea }

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
}
