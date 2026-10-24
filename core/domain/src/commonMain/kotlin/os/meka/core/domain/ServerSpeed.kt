package os.meka.core.domain

/**
 * Talking to Obi like a conversation, slice 2 (Meka, 2026-10-21 20:32: "it takes a while to respond"; find the real
 * wait first): the server times each answer it writes, times only, nothing of what was asked or said.
 *
 * Every mark is ms since the question reached the server: the web looked up ([webMs], a searched question only), the
 * model's first words back ([firstTokenMs]), the answer's first sentence whole ([firstSentenceMs], what Obi says
 * first), its clip ready in Navilife's voice ([clipMs], a spoken answer only) and the whole answer done ([doneMs]).
 * [open]: an open turn (the companion) rather than a quick look-up. [spoken]: Talk, not typed. [model]: the model's
 * name the provider reported (e.g. "claude-haiku-4-5"), so the next slice can compare models; never anything of his.
 */
data class ServerTurnTiming(
    val atMs: Long,
    val open: Boolean,
    val spoken: Boolean,
    val model: String?,
    val webMs: Long? = null,
    val firstTokenMs: Long? = null,
    val firstSentenceMs: Long? = null,
    val clipMs: Long? = null,
    val doneMs: Long? = null,
    val answered: Boolean = true,
)

/**
 * The server's own marks for one answer, on one monotonic clock ([nowMs] in ms). Each mark counts once (the first);
 * [finish] gives the timing, dropping any mark that isn't sane.
 */
class ServerTurnStopwatch(private val startMs: Long, private val open: Boolean, private val spoken: Boolean) {
    private var web: Long? = null
    private var token: Long? = null
    private var sentence: Long? = null
    private var clip: Long? = null

    fun web(nowMs: Long) { if (web == null) web = nowMs }
    fun firstToken(nowMs: Long) { if (token == null) token = nowMs }
    fun firstSentence(nowMs: Long) { if (sentence == null) sentence = nowMs }
    fun clip(nowMs: Long) { if (clip == null) clip = nowMs }

    fun finish(nowMs: Long, wallMs: Long, model: String?, answered: Boolean): ServerTurnTiming {
        fun since(t: Long?): Long? = t?.let { it - startMs }?.takeIf { ServerSpeedRules.sane(it) }
        return ServerTurnTiming(
            wallMs, open, spoken, ServerSpeedRules.cleanModel(model), since(web), since(token), since(sentence), since(clip),
            since(nowMs), answered,
        )
    }
}

/** Speed's "On the server" part, ready to show. */
data class ServerSpeedView(val title: String, val intro: String, val lines: List<String>, val empty: String?)

object ServerSpeedRules {
    /** The server keeps this many timings per household (the newest). */
    const val KEPT = 200
    /** The report and Speed read the last day's. */
    const val WINDOW_MS = 24 * 3_600_000L
    /** A mark later than this isn't this answer's. */
    const val MAX_MS = 120_000L
    /** The aim for the first sentence written, on the server (the conversation item's target). */
    const val FIRST_SENTENCE_AIM_MS = 1_000L

    const val TITLE = "On the server"
    const val INTRO = "How long Navilife's server took over the last day's answers (the middle of each step). Times only."
    const val EMPTY = "No answers in the last day yet."
    const val QUICK = "Quick look-ups"
    const val OPEN = "Conversation"

    private val MODEL = Regex("[a-z0-9][a-z0-9.\\-]{0,39}")

    fun sane(ms: Long): Boolean = ms in 0..MAX_MS

    /** A model name as the provider reported it, lower case; anything that isn't a plain model id reads as none. */
    fun cleanModel(model: String?): String? = model?.trim()?.lowercase()?.takeIf { MODEL.matches(it) }

    /** Adds [t], newest last, keeping the last [KEPT]. */
    fun add(list: List<ServerTurnTiming>, t: ServerTurnTiming): List<ServerTurnTiming> = (list + t).takeLast(KEPT)

    /** The ones within the last [WINDOW_MS] of [nowMs] (none from the future). */
    fun recent(list: List<ServerTurnTiming>, nowMs: Long): List<ServerTurnTiming> =
        list.filter { it.atMs in (nowMs - WINDOW_MS)..nowMs }

    fun middle(xs: List<Long>): Long? = xs.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    /**
     * One kind's line over its answered turns: "Quick look-ups (12, spoken 9): first words 0.6 s · first sentence 0.9 s
     * · voice ready 1.3 s · whole answer 2.1 s · web 0.7 s (3) · claude-haiku-4-5", each step once it has a mark; the
     * first sentence is flagged ", slow" past [FIRST_SENTENCE_AIM_MS]. Null when the kind has no turn.
     */
    fun line(label: String, turns: List<ServerTurnTiming>): String? {
        if (turns.isEmpty()) return null
        val answered = turns.filter { it.answered }
        val head = buildString {
            append(label).append(" (").append(turns.size)
            val spoken = turns.count { it.spoken }
            if (spoken in 1 until turns.size) append(", spoken ").append(spoken)
            else if (spoken == turns.size) append(", all spoken")
            val failed = turns.size - answered.size
            if (failed > 0) append(", ").append(failed).append(" not answered")
            append(")")
        }
        val steps = mutableListOf<String>()
        middle(answered.mapNotNull { it.firstTokenMs })?.let { steps += "first words " + SpeechRules.seconds(it) }
        middle(answered.mapNotNull { it.firstSentenceMs })?.let {
            steps += "first sentence " + SpeechRules.seconds(it) + if (it > FIRST_SENTENCE_AIM_MS) ", slow" else ""
        }
        middle(answered.mapNotNull { it.clipMs })?.let { steps += "voice ready " + SpeechRules.seconds(it) }
        middle(answered.mapNotNull { it.doneMs })?.let { steps += "whole answer " + SpeechRules.seconds(it) }
        val webbed = answered.mapNotNull { it.webMs }
        middle(webbed)?.let { steps += "web " + SpeechRules.seconds(it) + " (" + webbed.size + ")" }
        val models = answered.mapNotNull { it.model }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        if (models.isNotEmpty()) steps += models.take(2).joinToString(", ")
        return if (steps.isEmpty()) head else head + ": " + steps.joinToString(" · ")
    }

    /** The lines over the last day's turns: quick look-ups, then conversation; empty when there were none. */
    fun lines(list: List<ServerTurnTiming>, nowMs: Long): List<String> {
        val day = recent(list, nowMs)
        return listOfNotNull(line(QUICK, day.filter { !it.open }), line(OPEN, day.filter { it.open }))
    }

    fun view(list: List<ServerTurnTiming>, nowMs: Long): ServerSpeedView {
        val lines = lines(list, nowMs)
        return ServerSpeedView(TITLE, INTRO, lines, if (lines.isEmpty()) EMPTY else null)
    }
}
