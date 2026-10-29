package os.meka.core.domain

/**
 * Obi sees everything, slice 4 (Meka 2026-10-28 19:32: "Please ensure he sees everything I have"): the places the
 * coverage registry still had as Later, as Ask lines and Live look-up answers: the emergency card ([emergencyLines]),
 * News ([newsLines]), voice notes ([voiceNoteLines]), Navilife's own health ([healthLines]) and the Vault's documents
 * ([vaultLines]). Asked about one ([emergencyAsked] and the rest) Ask sends all of it; otherwise one short counts line,
 * so Obi knows it's there and can look it up. Nothing is stored; the Vault sends names and dates only (it holds no
 * numbers), the emergency card never holds a password or account number ([EmergencyRules]).
 */
object ObiRestRules {
    const val EMERGENCY_ROWS = 20
    const val EMERGENCY_MAX = 1 + EMERGENCY_ROWS
    /** Stories per lane, and lanes, sent with a news question. */
    const val NEWS_PER_LANE = 4
    const val NEWS_LANES = 4
    const val NEWS_MAX = 1 + NEWS_PER_LANE * NEWS_LANES
    const val VOICE_NOTES_ROWS = 6
    const val VOICE_NOTES_MAX = 1 + VOICE_NOTES_ROWS
    /** A voice note's text is cut here (the tidy note is usually far shorter). */
    const val NOTE_TEXT = 240
    const val HEALTH_ROWS = 14
    const val HEALTH_MAX = 1 + HEALTH_ROWS
    const val VAULT_ROWS = 12
    const val VAULT_MAX = 1 + VAULT_ROWS

    /** Health's line before Health has been checked since the app opened. */
    const val HEALTH_NOT_CHECKED = "Navilife's health · not checked since the app opened; Ask → Settings → Health checks it now"

    private val EMERGENCY_WORDS = Regex("""\b(emergency|emergencies|in case of|who (do|should|would) i (call|ring)|who to (call|ring)|where (are|is|do i keep|did i put) (the|my|our)|passports?|deeds|my will|plumber|electrician|locksmith|gp|doctor|insurer|insurance)\b""")
    private val NEWS_WORDS = Regex("""\b(news|headlines?|stories|story|articles?|happening in the world|what'?s happening)\b""")
    private val VOICE_WORDS = Regex("""\b(voice ?notes?|recordings?|recorded|rambl\w*|dictat\w*|i said in (a|my) note)\b""")
    private val HEALTH_WORDS = Regex("""\b(navilife'?s health|app health|health check|(not|stopped) working|broken|sync(ing|ed)?|connected|sign(ed)?[- ]in|battery|app updates?|notifications?|server|is (navilife|everything|the app) (ok|fine|working))\b""")
    private val VAULT_WORDS = Regex("""\b(vault|documents?|passports?|licen[cs]es?|railcard|visa|expir\w*|runs? out|birth certificate|ehic|ghic)\b""")

    fun emergencyAsked(q: String): Boolean = EMERGENCY_WORDS.containsMatchIn(q.lowercase())
    fun newsAsked(q: String): Boolean = NEWS_WORDS.containsMatchIn(q.lowercase())
    fun voiceNotesAsked(q: String): Boolean = VOICE_WORDS.containsMatchIn(q.lowercase())
    fun healthAsked(q: String): Boolean = HEALTH_WORDS.containsMatchIn(q.lowercase())
    fun vaultAsked(q: String): Boolean = VAULT_WORDS.containsMatchIn(q.lowercase())

    private fun tidy(s: String): String = s.replace(Regex("""\s+"""), " ").trim()
    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    /**
     * The emergency card: "Emergency card · 5 lines: 2 to call, 2 where things are, 1 key account", then "Who to call ·
     * GP · Dr Patel, 01632 123456" per line. Empty when the card is; the head alone when not [full].
     */
    fun emergencyLines(v: EmergencyView, full: Boolean): List<String> {
        val rows = v.groups.flatMap { g -> g.rows.map { g to it } }
        if (rows.isEmpty()) return emptyList()
        val parts = v.groups.filter { it.rows.isNotEmpty() }.map { g ->
            when (g.section) {
                EmergencySection.CALL.key -> "${g.rows.size} to call"
                EmergencySection.DOCUMENTS.key -> "${g.rows.size} where things are"
                EmergencySection.ACCOUNTS.key -> plural(g.rows.size, "key account")
                else -> "${g.rows.size} ${g.label.lowercase()}"
            }
        }
        val head = "Emergency card · ${plural(rows.size, "line")}: ${parts.joinToString(", ")}"
        if (!full) return listOf(head)
        return listOf(head) + rows.take(EMERGENCY_ROWS).map { (g, r) ->
            listOfNotNull(g.label, tidy(r.title), r.detail?.let(::tidy)?.takeIf { it.isNotEmpty() }).joinToString(" · ")
        }
    }

    /**
     * News: "News · 9 new · Barça 4, AI 5" (counts only: no headline, so it carries nobody's words), then up to
     * [NEWS_PER_LANE] stories per lane, new ones first: "News · Barça · <headline> · <source> · <when>" (headlines are
     * other people's words: the area is untrusted). Empty with no stories; the head alone when not [full].
     */
    fun newsLines(place: NewsPlace, full: Boolean): List<String> {
        val lanes = place.lanes.filter { it.items.isNotEmpty() }
        if (lanes.isEmpty()) return emptyList()
        val unread = lanes.sumOf { it.unread }
        val total = lanes.sumOf { it.items.size }
        val counts = lanes.joinToString(", ") { l -> "${tidy(l.label)} ${if (unread > 0) l.unread else l.items.size}" }
        val head = "News · " + (if (unread > 0) "$unread new" else plural(total, "story", "stories") + ", all read") + " · " + counts
        if (!full) return listOf(head)
        return listOf(head) + lanes.take(NEWS_LANES).flatMap { l ->
            l.items.sortedBy { it.read }.take(NEWS_PER_LANE).map { s ->
                listOf("News", tidy(l.label), tidy(s.title), tidy(s.source), tidy(s.meta), if (s.read) "" else "new")
                    .filter { it.isNotEmpty() }.distinct().joinToString(" · ")
            }
        }
    }

    /**
     * Voice notes (his own recordings, kept on this device): "Voice notes · 3 kept · the newest Today 14:02", then
     * "Voice note · Today 14:02 · 1:23 · <the tidy note, else his words> · Mentions Sam · 2 cards offered". [rows] are
     * the notes as the pane shows them, newest first. Empty with none; the head alone when not [full].
     */
    fun voiceNoteLines(rows: List<VoiceNoteRow>, full: Boolean): List<String> {
        if (rows.isEmpty()) return emptyList()
        val head = "Voice notes · ${plural(rows.size, "note")} kept · the newest ${tidy(rows.first().title).substringBefore(" · ")}"
        if (!full) return listOf(head)
        return listOf(head) + rows.take(VOICE_NOTES_ROWS).map { r ->
            val text = tidy(r.text).let { if (it.length > NOTE_TEXT) it.take(NOTE_TEXT - 1).trimEnd() + "…" else it }
            listOfNotNull("Voice note", tidy(r.title), text.takeIf { r.heard && it.isNotEmpty() } ?: "no words heard",
                r.peopleLine?.let(::tidy), r.cardsLine?.let(::tidy)).joinToString(" · ")
        }
    }

    /**
     * Navilife's own health: "Navilife's health · 1 thing needs a look · Checked 14:05", then the rows needing a look
     * first: "Calendar · Google sign-in expired · needs a look · fix: Reconnect", "Server · reached 14:05 · fine".
     * [HEALTH_NOT_CHECKED] before the first check (only when asked: nothing to count yet); the head alone when not [full].
     */
    fun healthLines(v: HealthView?, full: Boolean): List<String> {
        if (v == null) return if (full) listOf(HEALTH_NOT_CHECKED) else emptyList()
        val head = listOf("Navilife's health", tidy(v.summary), tidy(v.checkedLine)).filter { it.isNotEmpty() }.joinToString(" · ")
        if (!full) return listOf(head)
        val order = listOf(HealthState.BAD, HealthState.WARN, HealthState.UNKNOWN, HealthState.OK)
        return listOf(head) + v.rows.sortedBy { order.indexOf(it.state) }.take(HEALTH_ROWS).map { r ->
            val state = when (r.state) {
                HealthState.OK -> "fine"
                HealthState.WARN -> "worth a look"
                HealthState.BAD -> "broken"
                HealthState.UNKNOWN -> "not known yet"
            }
            listOfNotNull(tidy(r.title), tidy(r.line).takeIf { it.isNotEmpty() }, r.detail?.let(::tidy)?.takeIf { it.isNotEmpty() }, state,
                r.fixLabel?.let { "fix: ${tidy(it)}" }).joinToString(" · ")
        }
    }

    /**
     * The Vault: "Vault · 3 documents · 1 to renew", then "Vault · Railcard · <its line> · <status>" per document
     * (names and dates; the Vault holds no numbers, and a copy's contents never leave the device). Empty with none; the
     * head alone when not [full].
     */
    fun vaultLines(v: DocumentsView, full: Boolean): List<String> {
        if (v.isEmpty) return emptyList()
        val head = "Vault · ${tidy(v.summary)}"
        if (!full) return listOf(head)
        return listOf(head) + v.rows.take(VAULT_ROWS).map { r ->
            listOf("Vault", tidy(r.title), tidy(r.line), tidy(r.status), if (r.hasCopy) "copy kept" else "")
                .filter { it.isNotEmpty() }.distinct().joinToString(" · ")
        }
    }
}
