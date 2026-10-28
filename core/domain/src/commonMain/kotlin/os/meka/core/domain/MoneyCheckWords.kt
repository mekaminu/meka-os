package os.meka.core.domain

/**
 * Money redesign, Plan: **Obi's wording of the money check** (the AI, approved by Meka 2026-10-24 21:13). The rules
 * ([MoneyCheck]) still find every idea and every number; Obi only ranks them and words each one's title and why. What
 * goes to the AI is the ideas themselves: their rule wording and figure, and the payee names and amounts behind them
 * (never an account, a balance or a date). At most one run a week ([EVERY_MS]), plus whenever he asks ([LOOK_AGAIN]).
 *
 * Nothing Obi writes shows unless it passes [check] against the idea **as the rules see it now**: every number in his
 * words must be one of the idea's own numbers, so a made-up figure, or a week-old one after new payments changed the
 * idea, falls back to the rules' words for that idea. An id Obi didn't get is never read (the wire codec).
 * His words sync, so the Fold and the Mac show the same.
 */
data class MoneyIdeaWords(val title: String, val why: String, val rank: Int)

/** Obi's words by idea id, and when he last wrote them ([atMs], [day]); empty before the first run. */
data class MoneyCheckWordsState(val words: Map<String, MoneyIdeaWords> = emptyMap(), val atMs: Long? = null, val day: Long? = null)

object MoneyCheckWords {
    const val FEATURE = "money.check"
    /** The synced entity (in `money_tips`) holding when Obi last wrote the ideas. */
    const val ENTITY = "obi-words"
    const val EVERY_MS = 7L * 24 * 60 * 60 * 1000
    /** After a run that failed (offline, the AI off), the next automatic try waits this long. */
    const val RETRY_MS = 60L * 60 * 1000
    const val MAX_TITLE = 60
    const val MAX_WHY = 220
    const val MAX_SENT = 12
    const val LOOK_AGAIN = "Ask Obi to look again"
    const val LOOKING = "Obi is reading the ideas…"
    const val WROTE = "Obi wrote the ideas again"
    const val NOTHING = "Obi kept the ideas as they were"
    const val NO_IDEAS = "No ideas to look at just now"

    /** "Written by Obi · Sun 25 Oct" under the ideas when any shows in his words; null otherwise. */
    fun byLine(day: Long): String = "Written by Obi · ${CivilDate.shortLabel(day)}"

    /** Whether the weekly run is due at [nowMs]. */
    fun due(state: MoneyCheckWordsState, nowMs: Long): Boolean = state.atMs?.let { nowMs - it >= EVERY_MS } ?: true

    private val NUMBER = Regex("""\d[\d,]*(?:\.\d+)?""")
    private val BANNED = Regex("""https?:|www\.|[<>{}@]""", RegexOption.IGNORE_CASE)

    /** "1,250.00" → "1250"; "4.50" stays. */
    private fun norm(n: String): String = n.replace(",", "").let { s -> if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s }

    /** Every number in the idea as the rules found it: its title, figure, why and the payments behind it. */
    fun numbersOf(i: MoneyIdea): Set<String> =
        (listOf(i.ruleTitle, i.figure, i.ruleWhy) + i.behind.flatMap { listOf(it.label, it.amount) })
            .flatMap { t -> NUMBER.findAll(t).map { norm(it.value) } }.toSet()

    /** Obi's [title] and [why] for [i], trimmed to one line each, or null when they don't pass (see the class doc). */
    fun check(i: MoneyIdea, title: String, why: String): Pair<String, String>? {
        val t = title.replace(Regex("""\s+"""), " ").trim()
        val w = why.replace(Regex("""\s+"""), " ").trim()
        if (t.length !in 3..MAX_TITLE || w.length !in 10..MAX_WHY) return null
        if (BANNED.containsMatchIn(title) || BANNED.containsMatchIn(why)) return null
        val allowed = numbersOf(i)
        if ((NUMBER.findAll(t) + NUMBER.findAll(w)).any { norm(it.value) !in allowed }) return null
        return t to w
    }

    /**
     * The money check with Obi's words where they pass [check], in his order: ideas he hasn't seen yet (new since his
     * last run) first in the rules' order, then his ranking; an idea whose words don't pass keeps the rules' words but
     * its place. [MoneyCheckView.by] says when he wrote them.
     */
    fun apply(v: MoneyCheckView, state: MoneyCheckWordsState): MoneyCheckView {
        if (state.words.isEmpty() || v.ideas.isEmpty()) return v
        var any = false
        val ideas = v.ideas.withIndex().sortedWith(compareBy({ state.words[it.value.id]?.rank ?: -1 }, { it.index })).map { (_, i) ->
            val w = state.words[i.id] ?: return@map i
            val (t, y) = check(i, w.title, w.why) ?: return@map i
            any = true
            i.copy(title = t, why = y, spoken = "$t, ${i.figure}. $y", byObi = true)
        }
        return v.copy(ideas = ideas, by = if (any) state.day?.let(::byLine) else null)
    }

    /** What goes to Obi: the ideas shown (put away ones stay home), in the rules' own words, at most [MAX_SENT]. */
    fun toSend(v: MoneyCheckView): List<MoneyIdea> = v.ideas.filter { it.id.length <= 60 }.take(MAX_SENT)
}
