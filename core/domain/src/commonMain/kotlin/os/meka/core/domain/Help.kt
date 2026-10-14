package os.meka.core.domain

/**
 * Ask knows how Navilife itself works (build plan, Meka 2026-10-12 18:07: "flex mode" got "I don't have a flex mode in
 * your data…"). A short built-in help index, one entry per feature, written in docs/help (one .md per feature) and bundled into both
 * apps by tools/gen-help.py ([HelpIndex]), so it works offline and costs nothing.
 *
 * Ask and Talk look it up on the device first ([HelpRules.lookup]): a question that is plainly about Navilife's own
 * features ("how do I…", "where is…", "flex mode", "why isn't the bedside clock showing?") is answered from the entry,
 * with **Take me there** to the screen it lives on ([HelpLink]); no AI call. A question that only mentions a feature
 * ("can the call assistant take messages?") goes to the AI as before, with the matching entries as context
 * ([HelpRules.askLines]). Questions about his own data ("when are the bins?", "what's my commute?") are left alone.
 *
 * Pure and non-AI. The entries hold no personal details (the repository is public, build plan rule 13).
 */

/**
 * Where Take me there goes. The names match the Fold's Ask rows (`MoreItem`), [key] the Mac's (`MoreItem` case
 * names); `SYSTEM_` places are Android's own settings pages (Fold only).
 */
enum class HelpPlace(val key: String) {
    LISTS("lists"),
    GOALS("goals"),
    PEOPLE("people"),
    DINNERS("dinners"),
    BINS("bins"),
    DATE_NIGHT("dateNight"),
    SCHOOL("school"),
    APPOINTMENTS("appointments"),
    LEAVE("leave"),
    GIFTS("gifts"),
    TRIPS("trips"),
    ERRANDS("errands"),
    EMERGENCY("emergency"),
    FACTS("facts"),
    BRIEF("brief"),
    WEEK_AHEAD("weekAhead"),
    NEWS("news"),
    SHUTDOWN("shutdown"),
    TRAIN_QUEUE("trainQueue"),
    LIFE_ADMIN("lifeAdmin"),
    BEDSIDE("bedside"),
    REVIEW("review"),
    ABOUT_YOU("aboutYou"),
    WORK("work"),
    PROTECTED("protectedTime"),
    NOTIFICATIONS("notifications"),
    APPEARANCE("appearance"),
    VOICE("voice"),
    TALK("talk"),
    CALENDARS("calendars"),
    MAIL("mail"),
    FAMILY("family"),
    WATCH("watch"),
    RECOVERY("recovery"),
    AUTONOMY("autonomy"),
    AI("ai"),
    ACTIVITY("activity"),
    SETUP("setup"),
    HEALTH("health"),
    VAULT("vault"),
    YOUR_DATA("yourData"),
    SETTINGS("settings"),
    /** Android → Settings → Display → Screen saver. */
    SYSTEM_SCREEN_SAVER(""),
    /** Android → Settings → Apps → Default apps (the Caller ID & spam app). */
    SYSTEM_DEFAULT_APPS(""),
    /** Android's notification page for Navilife. */
    SYSTEM_APP_NOTIFICATIONS(""),
    /** Android's app page for Navilife (permissions). */
    SYSTEM_APP_DETAILS(""),
    ;

    /** One of Android's own settings pages rather than a Navilife screen. */
    val system: Boolean get() = name.startsWith("SYSTEM_")
}

/** One feature's help: what it does, how to turn it on, where it lives on each app, and the common gotchas. */
data class HelpEntry(
    val id: String,
    val title: String,
    /** Names and phrasings that point at it ("flex mode", "bedside clock"); matched as whole words. */
    val keywords: List<String>,
    val what: String,
    val how: String,
    val tips: List<String>,
    /** Where Take me there goes on the Fold; null when there is no one screen. */
    val fold: HelpPlace?,
    /** "Ask → Daily → Bedside clock". */
    val foldWhere: String,
    /** Where it goes on the Mac; null with no [macWhere] means the feature is Fold-only. */
    val mac: HelpPlace?,
    val macWhere: String?,
    /** How it works on the Mac when that differs from [how]. */
    val macHow: String?,
) {
    val foldOnly: Boolean get() = mac == null && macWhere == null
}

/** Take me there under an answer: the screen it opens and the entry it came from. */
data class HelpLink(val entryId: String, val place: HelpPlace, val button: String = HelpRules.BUTTON)

/** What the lookup found: the entry and whether the question was plainly about it (answered here) or only mentions it. */
data class HelpMatch(val entry: HelpEntry, val strong: Boolean, val score: Int)

object HelpRules {
    const val BUTTON = "Take me there"
    /** At most this many entries go to the AI with a question that only mentions features. */
    const val ASK_ENTRIES = 2
    const val MAX_LINE = 160

    /** Every entry, in file order. */
    val entries: List<HelpEntry> get() = HelpIndex.ENTRIES

    fun entry(id: String): HelpEntry? = HelpIndex.ENTRIES.firstOrNull { it.id == id }

    /** Phrases that make a question about how Navilife works ("how do I…", "where is…", "isn't working"). */
    private val INTENT = listOf(
        "how do i", "how can i", "how does", "how do you", "how to", "how do", "where is", "wheres", "where do i",
        "where can i", "where are", "where do", "turn on", "turn off", "switch on", "switch off", "set up", "setup",
        "enable", "disable", "not working", "doesnt work", "dont work", "isnt working", "isnt showing", "isnt", "wont",
        "cant find", "cant get", "cant see", "stopped working", "not showing", "doesnt show", "doesnt appear",
        "help with", "help me", "explain", "tell me about", "is there", "do you have", "does navilife have",
        "show me how", "what happened to", "get rid of",
    )

    /** "What is X" / "what does X do": only when X ends the question ("what's on the meal plan this week" is data). */
    private val WHAT = listOf("what is", "whats", "what does", "what do", "what are")

    /** Questions about his own data or the day, never a help question on their own. */
    private val DATA_STARTS = listOf("when", "which", "who", "how many", "how much", "how long", "what time", "did ")

    /** Words that carry no feature: dropped before checking a bare feature name ("the bedside clock please"). */
    private val FILLER = setOf(
        "navilife", "meka", "the", "a", "an", "my", "please", "help", "in", "on", "app", "feature", "settings", "setting",
        "about", "for", "me", "is", "it", "do", "does", "work", "works", "working", "use", "using", "mode", "screen",
    )

    private fun tokens(text: String): List<String> = SearchRules.normalize(text).split(' ').filter { it.isNotEmpty() }

    private fun has(q: List<String>, phrase: String): Boolean = find(q, tokens(phrase)) >= 0

    /** Where [kw] starts in [q] (whole words; a plural "clocks" matches "clock"), or -1. */
    private fun find(q: List<String>, kw: List<String>): Int {
        if (kw.isEmpty() || kw.size > q.size) return -1
        for (i in 0..q.size - kw.size) {
            if (kw.indices.all { j -> q[i + j] == kw[j] || q[i + j] == kw[j] + "s" || q[i + j] == kw[j] + "es" }) return i
        }
        return -1
    }

    /**
     * The entry [question] is about, if any. Strong (answered on the device) when it asks how something in Navilife
     * works, where it is or why it isn't working, or is just a feature's name; weak (context for the AI) when it only
     * mentions one. The longest matching phrase wins; null when nothing matches.
     */
    fun lookup(question: String): HelpMatch? {
        val q = tokens(question)
        if (q.isEmpty()) return null
        var best: HelpMatch? = null
        HelpIndex.ENTRIES.forEach { e ->
            (e.keywords + e.title).forEach { k ->
                val kw = tokens(k)
                val at = find(q, kw)
                if (at < 0) return@forEach
                val score = kw.sumOf { it.length } + kw.size
                if (best == null || score > best!!.score) best = HelpMatch(e, strong(q, kw, at), score)
            }
        }
        return best
    }

    private fun strong(q: List<String>, kw: List<String>, at: Int): Boolean {
        val joined = q.joinToString(" ")
        val after = q.drop(at + kw.size)
        val rest = (q.take(at) + after).filter { it !in FILLER }
        // Just the feature's name: "flex mode", "the bedside clock?".
        if (rest.isEmpty()) return true
        if (DATA_STARTS.any { joined.startsWith(it) }) return false
        if (INTENT.any { has(q, it) }) return true
        // "What's the call assistant?" / "what does mark read do?": the feature ends the question.
        if (WHAT.any { joined.startsWith(it) } && after.all { it in FILLER }) return true
        return false
    }

    /** The answer for [entry] on the Fold ([mac] false) or the Mac: its words and Take me there when it has a screen. */
    fun answer(entry: HelpEntry, mac: Boolean): AskAnswer {
        val lines = mutableListOf<String>()
        val link: HelpLink?
        if (!mac) {
            lines += "${entry.title}: ${entry.what}"
            lines += entry.how
            lines += "On the Fold: ${entry.foldWhere}."
            lines += entry.tips
            link = entry.fold?.let { HelpLink(entry.id, it) }
        } else if (entry.foldOnly) {
            lines += "${entry.title} is on the Fold only: ${entry.what}"
            lines += "On the Fold: ${entry.foldWhere}."
            link = null
        } else {
            lines += "${entry.title}: ${entry.what}"
            lines += entry.macHow ?: entry.how
            entry.macWhere?.let { lines += "On the Mac: $it." }
            link = entry.mac?.takeIf { !it.system }?.let { HelpLink(entry.id, it) }
        }
        return AskAnswer(lines.joinToString("\n"), emptyList(), help = link)
    }

    /**
     * What the AI gets with a question that only mentions features: the best [ASK_ENTRIES] entries, each "Help · Title:
     * what" and "Help · Title · where: …" (and its first tip), at most [MAX_LINE] characters each. Navilife's own words.
     */
    fun askLines(question: String, mac: Boolean): List<String> {
        val q = tokens(question)
        if (q.isEmpty()) return emptyList()
        val scored = HelpIndex.ENTRIES.mapNotNull { e ->
            val s = (e.keywords + e.title).maxOfOrNull { k -> val kw = tokens(k); if (find(q, kw) >= 0) kw.sumOf { it.length } + kw.size else -1 } ?: -1
            if (s < 0) null else e to s
        }.sortedByDescending { it.second }.take(ASK_ENTRIES)
        return scored.flatMap { (e, _) ->
            val where = when {
                !mac -> "on the Fold: ${e.foldWhere}"
                e.foldOnly -> "Fold only: ${e.foldWhere}"
                else -> "on the Mac: ${e.macWhere}"
            }
            listOfNotNull(
                clip("Help · ${e.title}: ${e.what}"),
                clip("Help · ${e.title} · $where"),
                (if (mac && !e.foldOnly) e.macHow ?: e.how else e.how).let { clip("Help · ${e.title} · $it") },
                e.tips.firstOrNull()?.takeIf { !mac }?.let { clip("Help · ${e.title} · $it") },
            )
        }
    }

    private fun clip(s: String): String = if (s.length <= MAX_LINE) s else s.take(MAX_LINE - 1).trimEnd() + "…"
}
