package os.meka.core.domain

/**
 * Obi's voice page's on/off choices as rows opening sheets (walk-through week 5, A17: no open chip rows on a page; the
 * page unfolded four pairs: Obi calls you By name · Sir, Face On · Off, What Obi remembers Remember · Don't remember,
 * Live On · Off). Each is now one card row saying what's chosen ("Obi calls you" · "By name" ›) opening a sheet of the
 * same two choices, the chosen one lit, each saying what it does. A choice saves as the chip did and closes the sheet;
 * the line under each row (the call line, the memory line and notes, Live's meter and sharing) stays as it was.
 *
 * Every choice here is a yes/no: [callNamed], [faceNamed], [memoryNamed] and [liveNamed] give the value a choice sets,
 * or null when the name isn't a choice or is already in force. Reuses Notifications' row and sheet shapes
 * ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure; nothing stored.
 */
object VoiceChoiceRules {
    const val NAME = "name"
    const val SIR = "sir"
    const val ON = "on"
    const val OFF = "off"

    const val CALL_NOTE = "In Ask, Talk and Hello, the same on every device."
    const val FACE_NOTE = "On this device only."
    const val MEMORY_NOTE = "The same on every device. Forget all, under the row, clears what's kept."
    const val LIVE_NOTE = "The same on every device. While Live is on, your voice and today's Navilife context go to Google; the line under the row says what."

    private data class Pick(val name: String, val label: String, val line: String, val value: Boolean)

    private val CALL = listOf(
        Pick(NAME, CompanionRules.CALL_BY_NAME, "Obi uses your name now and then", false),
        Pick(SIR, CompanionRules.CALL_SIR_LABEL, "Obi says sir now and then", true),
    )
    private val FACE = listOf(
        Pick(ON, FaceLook.ON, "Obi's face shows while you talk", true),
        Pick(OFF, FaceLook.OFF, "The gold orb shows while you talk", false),
    )
    private val MEMORY = listOf(
        Pick(ON, CompanionMemoryRules.ON_LABEL, "A line about each proper chat, kept 30 days, so Obi picks up where you left off", true),
        Pick(OFF, CompanionMemoryRules.OFF_LABEL, "Each conversation starts fresh; nothing about it is kept", false),
    )
    private val LIVE = listOf(
        Pick(ON, LiveTalkRules.ON_LABEL, "Talk with Obi in real time and talk over him any time", true),
        Pick(OFF, LiveTalkRules.OFF_LABEL, "Obi answers a turn at a time, as he does now", false),
    )

    private fun row(head: String, picks: List<Pick>, value: Boolean): NotifyChoiceRow {
        val label = picks.first { it.value == value }.label
        return NotifyChoiceRow(head, label, "$head, $label. Opens the choices")
    }

    private fun sheet(head: String, note: String, picks: List<Pick>, value: Boolean) = NotifyChoiceSheet(head, note, picks.map { p ->
        val lit = p.value == value
        NotifyChoice(p.name, p.label, p.line, lit, "${p.label}, ${p.line}" + if (lit) ", chosen" else "")
    })

    private fun named(picks: List<Pick>, value: Boolean, name: String): Boolean? =
        picks.firstOrNull { it.name == name }?.value?.takeIf { it != value }

    // ---- Obi calls you ----

    fun callRow(sir: Boolean): NotifyChoiceRow = row(CompanionRules.CALL_TITLE, CALL, sir)
    fun callSheet(sir: Boolean): NotifyChoiceSheet = sheet(CompanionRules.CALL_TITLE, CALL_NOTE, CALL, sir)
    /** True for Sir, false for by name; null when [name] isn't a choice or is already in force. */
    fun callNamed(sir: Boolean, name: String): Boolean? = named(CALL, sir, name)

    // ---- Face ----

    fun faceRow(on: Boolean): NotifyChoiceRow = row(FaceLook.SETTING_TITLE, FACE, on)
    fun faceSheet(on: Boolean): NotifyChoiceSheet = sheet(FaceLook.SETTING_TITLE, FACE_NOTE, FACE, on)
    fun faceNamed(on: Boolean, name: String): Boolean? = named(FACE, on, name)

    // ---- What Obi remembers ----

    fun memoryRow(on: Boolean): NotifyChoiceRow = row(CompanionMemoryRules.TITLE, MEMORY, on)
    fun memorySheet(on: Boolean): NotifyChoiceSheet = sheet(CompanionMemoryRules.TITLE, MEMORY_NOTE, MEMORY, on)
    fun memoryNamed(on: Boolean, name: String): Boolean? = named(MEMORY, on, name)

    // ---- Live ----

    fun liveRow(on: Boolean): NotifyChoiceRow = row(LiveTalkRules.TITLE, LIVE, on)
    fun liveSheet(on: Boolean): NotifyChoiceSheet = sheet(LiveTalkRules.TITLE, LIVE_NOTE, LIVE, on)
    fun liveNamed(on: Boolean, name: String): Boolean? = named(LIVE, on, name)
}
