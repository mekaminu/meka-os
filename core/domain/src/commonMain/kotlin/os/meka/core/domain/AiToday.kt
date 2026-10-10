package os.meka.core.domain

/** One AI feature's calls on the household's day, as MEKA's server counted them ("ask.talk" · 6 · 4,200 µ$). */
data class AiDayUse(val feature: String, val calls: Long, val microUsd: Long)

/**
 * Activity's daily count of MEKA's AI (build plan V1, MEKA as the default assistant: "each turn is one AI call
 * (pennies), inside the cap; show a daily count in Activity"). Non-AI, pure.
 *
 * "MEKA's AI today · Talk 6 · Ask 2 · Messages 4 · 3¢": Talk's spoken answers first, then typed questions, then what
 * MEKA read for Meka (messages, groups' gists, callers' messages checked for spam), then anything else, and what the
 * day cost at list price. Nothing asked today → no line.
 */
object AiTodayRules {
    const val PREFIX = "MEKA's AI today"

    /** The groups, in the order they are said, and the server's feature names they gather. */
    enum class Group(val label: String) { TALK("Talk"), ASK("Ask"), MESSAGES("Messages"), CALLS("Calls"), OTHER("Other") }

    fun groupOf(feature: String): Group = when {
        feature == "ask.talk" -> Group.TALK
        feature == "ask" -> Group.ASK
        feature == "extract.message" || feature == "triage.message" || feature.startsWith("digest.") -> Group.MESSAGES
        feature == "extract.callscam" -> Group.CALLS
        else -> Group.OTHER
    }

    fun line(today: List<AiDayUse>): String? {
        val used = today.filter { it.calls > 0 }
        if (used.isEmpty()) return null
        val parts = Group.entries.mapNotNull { g ->
            used.filter { groupOf(it.feature) == g }.takeIf { it.isNotEmpty() }?.let { rows -> "${g.label} ${rows.sumOf { it.calls }}" }
        }
        return (listOf(PREFIX) + parts + cost(used.sumOf { it.microUsd })).joinToString(" · ")
    }

    /** List price of the day: "under 1¢" below a cent, "3¢" (rounded) below a dollar, else "$1.20". */
    fun cost(microUsd: Long): String {
        val m = microUsd.coerceAtLeast(0)
        return when {
            m < 10_000 -> "under 1¢"
            m < 995_000 -> "${(m + 5_000) / 10_000}¢"
            else -> AskRules.dollars((m + 5_000) / 10_000)
        }
    }
}
