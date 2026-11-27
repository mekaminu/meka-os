package os.meka.core.domain

/**
 * News → Topics without an open switch (walk-through week 5, A17: no open chip rows on a page). Spanish sources was an
 * open "Off · On" chip row with "Off · English only" under it; it is now one card row ("Spanish sources" · "Off" ›)
 * opening the choice sheet of On · Off, the one in force lit, each saying what it does ("Off: English only, wherever
 * news shows"); a choice writes what the chips wrote and closes the sheet. Reuses [NotifyChoiceRow], [NotifyChoice],
 * [NotifyChoiceSheet]. Pure; nothing new stored.
 *
 * Follow (the same rule): the open "Follow a team, person or word…" box is one "+ Follow a name or word" card row
 * opening [followSheet] (Follow lights once the words hold a name [NewsRules.parseFollows] keeps), and the open row of
 * followed chips is one "Following" · "Arsenal, Lamine Yamal" › row opening [followingSheet], each name saying how
 * many stories name it and that choosing it stops following it (Undo stays on the page).
 *
 * Topics and Sources (the same rule): the open wall of topic chips is one "Topics" · "Barça, AI +2" › row and the open
 * block of source chips one "Sources" · "All on" › row, each opening a sheet of ticks ([topicsSheet], [sourcesSheet],
 * several may be lit) that stays open while he picks; a tap writes what the chip wrote.
 */
object NewsChoiceRules {
    const val SPANISH_TITLE = "Spanish sources"
    const val SPANISH_NOTE = "Synced, so the Mac has the same. English stories always show."
    const val SPANISH_ON_LINE = "Adds ${NewsRules.SPANISH_SOURCES} to News, the ticker and the brief"
    const val SPANISH_OFF_LINE = "English only, wherever news shows"

    /** The row: "Spanish sources" · "Off". */
    fun spanishRow(on: Boolean): NotifyChoiceRow {
        val value = if (on) CallChoiceRules.ON_LABEL else CallChoiceRules.OFF_LABEL
        return NotifyChoiceRow(SPANISH_TITLE, value, "$SPANISH_TITLE, $value. Opens the choices")
    }

    /** Its sheet: On names the Spanish sources it adds, Off says English only; the one in force lit. */
    fun spanishSheet(on: Boolean): NotifyChoiceSheet = NotifyChoiceSheet(SPANISH_TITLE, SPANISH_NOTE, listOf(
        choice(CallChoiceRules.ON, CallChoiceRules.ON_LABEL, SPANISH_ON_LINE, on),
        choice(CallChoiceRules.OFF, CallChoiceRules.OFF_LABEL, SPANISH_OFF_LINE, !on),
    ))

    /** True for On, false for Off; null when [name] isn't a choice or is already in force. */
    fun named(on: Boolean, name: String): Boolean? = CallChoiceRules.named(on, name)

    private fun choice(name: String, label: String, line: String, lit: Boolean) =
        NotifyChoice(name, label, line, lit, "$label, $line" + if (lit) ", chosen" else "")

    // ---- Follow ----

    const val FOLLOWING_TITLE = "Following"
    const val FOLLOWING_NOTE = "Choose one to stop following it; Undo on the page brings it back. Synced, so the Mac has the same."
    /** At most this many names on the Following row before "+N". */
    const val FOLLOWING_NAMES = 2

    val followRow: GoalAddRow = GoalAddRow("+ Follow a name or word", "A team, a person, a company or a word: stories naming it lead News")

    val followSheet: GoalAddSheet = GoalAddSheet(
        title = "Follow",
        hint = "Arsenal, Lamine Yamal",
        spokenHint = "Type a team, a person, a company or a word to follow. Commas follow several at once.",
        note = "Stories naming it lead News, the ticker and the brief, whatever their topic. Commas follow several at once.",
        addLabel = "Follow",
    )

    /** Follow lights only once the words hold a name that's kept (two letters or more). */
    fun canFollow(text: String): Boolean = NewsRules.parseFollows(text).isNotEmpty()

    /** "Following" · "Arsenal, Lamine Yamal" (past two names, "Arsenal, Lamine Yamal +1"); null when he follows none. */
    fun followingRow(follows: List<NewsFollowChoice>): NotifyChoiceRow? {
        if (follows.isEmpty()) return null
        val shown = follows.take(FOLLOWING_NAMES).joinToString(", ") { it.name }
        val value = if (follows.size > FOLLOWING_NAMES) "$shown +${follows.size - FOLLOWING_NAMES}" else shown
        return NotifyChoiceRow(FOLLOWING_TITLE, value, "$FOLLOWING_TITLE ${follows.size}: ${follows.joinToString(", ") { it.name }}. Opens the list")
    }

    /** Each followed name, saying its stories and that choosing it stops following it; none lit. */
    fun followingSheet(follows: List<NewsFollowChoice>): NotifyChoiceSheet = NotifyChoiceSheet(FOLLOWING_TITLE, FOLLOWING_NOTE,
        follows.map { f ->
            val line = when (f.stories) {
                0 -> "No stories yet · stop following"
                1 -> "1 story in the last two days · stop following"
                else -> "${f.stories} stories in the last two days · stop following"
            }
            NotifyChoice(f.name, f.name, line, false, "${f.name}, $line")
        })

    /** The followed name [name] picks in [followingSheet]; null when it isn't one he follows. */
    fun unfollowNamed(follows: List<NewsFollowChoice>, name: String): String? = follows.firstOrNull { it.name == name }?.name

    // ---- Topics and sources ----

    const val TOPICS_TITLE = "Topics"
    const val TOPICS_NOTE = "Each one lit is a lane in News and feeds the ticker and the brief. Synced, so the brief and the Mac have the same."
    const val TOPIC_ON_LINE = "In News, the ticker and the brief"
    const val TOPIC_OFF_LINE = "Not in News · choose it to add its lane"
    /** At most this many topic names on the Topics row before "+N". */
    const val TOPICS_NAMES = 2

    /** "Topics" · "Barça, AI +2"; "None chosen" with none. */
    fun topicsRow(topics: List<NewsTopicChoice>): NotifyChoiceRow {
        val chosen = topics.filter { it.chosen }
        if (chosen.isEmpty()) return NotifyChoiceRow(TOPICS_TITLE, "None chosen", "$TOPICS_TITLE, none chosen. Opens the list")
        val shown = chosen.take(TOPICS_NAMES).joinToString(", ") { it.label }
        val value = if (chosen.size > TOPICS_NAMES) "$shown +${chosen.size - TOPICS_NAMES}" else shown
        return NotifyChoiceRow(TOPICS_TITLE, value, "$TOPICS_TITLE ${chosen.size} of ${topics.size}: ${chosen.joinToString(", ") { it.label }}. Opens the list")
    }

    /** Every topic, the chosen ones lit, each saying what being chosen does. */
    fun topicsSheet(topics: List<NewsTopicChoice>): NotifyChoiceSheet = NotifyChoiceSheet(TOPICS_TITLE, TOPICS_NOTE,
        topics.map { t -> choice(t.id, t.label, if (t.chosen) TOPIC_ON_LINE else TOPIC_OFF_LINE, t.chosen) })

    /** The topic [name] picks in [topicsSheet]; null when it isn't one. */
    fun topicNamed(topics: List<NewsTopicChoice>, name: String): NewsTopicChoice? = topics.firstOrNull { it.id == name }

    const val SOURCES_TITLE = "Sources"
    const val SOURCES_NOTE = "A source switched off stays out of News, the ticker and the brief. Synced, so the Mac has the same."

    /** "Sources" · "All on" / "2 off" / "All off"; null with no sources in the chosen topics. */
    fun sourcesRow(sources: List<NewsSourceChoice>): NotifyChoiceRow? {
        if (sources.isEmpty()) return null
        val off = sources.count { !it.on }
        val value = when (off) { 0 -> "All on"; sources.size -> "All off"; else -> "$off off" }
        return NotifyChoiceRow(SOURCES_TITLE, value, "$SOURCES_TITLE ${sources.size}, " + value.lowercase() + ". Opens the list")
    }

    /** Every source, the ones on lit, each saying its stories just now, or that it's off or not coming through. */
    fun sourcesSheet(sources: List<NewsSourceChoice>): NotifyChoiceSheet = NotifyChoiceSheet(SOURCES_TITLE, SOURCES_NOTE,
        sources.map { s ->
            val stories = when (s.stories) { 0 -> "No stories just now"; 1 -> "1 story just now"; else -> "${s.stories} stories just now" }
            val line = when {
                !s.on -> "Off · its stories stay out"
                s.down -> "$stories · not coming through"
                else -> stories
            }
            choice(s.key, s.name, line, s.on)
        })

    /** The source [name] (its key) picks in [sourcesSheet]; null when it isn't one. */
    fun sourceNamed(sources: List<NewsSourceChoice>, name: String): NewsSourceChoice? = sources.firstOrNull { it.key == name }
}
