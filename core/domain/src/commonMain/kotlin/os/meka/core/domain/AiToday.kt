package os.meka.core.domain

/** One AI feature's calls on the household's day, as MEKA's server counted them ("ask.talk" · 6 · 4,200 µ$). */
data class AiDayUse(val feature: String, val calls: Long, val microUsd: Long)

/**
 * Activity's daily count of MEKA's AI (build plan V1, MEKA as the default assistant: "each turn is one AI call
 * (pennies), inside the cap; show a daily count in Activity"). Non-AI, pure.
 *
 * "MEKA's AI today · Talk 6 · Ask 2 · Messages 4 · 3¢": Talk's spoken answers first, then typed questions, then what
 * MEKA read for Meka (messages, groups' gists, callers' messages checked for spam, emails sorted), then anything else, and what the
 * day cost at list price (Navilife's voice too, "Voice 12" clips). Nothing asked today → no line.
 */
object AiTodayRules {
    const val PREFIX = "Navilife's AI today"

    /**
     * Navilife's own voice (Amazon Polly) counted in the same budget (voice completeness, 2026-10-19): the server meters
     * each clip as "voice.polly" at Polly's list price, so Settings and Activity show it as **Voice** (clips, not calls).
     */
    const val VOICE_PREFIX = "voice."

    /** The groups, in the order they are said, and the server's feature names they gather. */
    enum class Group(val label: String) { TALK("Talk"), ASK("Ask"), BRIEF("Brief"), MESSAGES("Messages"), CALLS("Calls"), MAIL("Mail"), VOICE("Voice"), OTHER("Other") }

    fun groupOf(feature: String): Group = when {
        feature == "ask.talk" -> Group.TALK
        feature == "ask" -> Group.ASK
        feature == "ask.brief" -> Group.BRIEF
        feature == "extract.message" || feature == "triage.message" || feature.startsWith("digest.") ||
            feature.startsWith("messages.") -> Group.MESSAGES
        feature == "extract.callscam" -> Group.CALLS
        feature.startsWith("mail.") -> Group.MAIL
        feature.startsWith(VOICE_PREFIX) -> Group.VOICE
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

/** One AI feature's calls and cost (whole cents) this month, as Navilife's server metered them. */
data class AiMonthUse(val feature: String, val calls: Long, val cents: Long)

/** One row of the month's spend: "Mail" · "340 calls · 85¢" with its [share] of the month (0–1) for a thin bar. */
data class AiSpendLine(val label: String, val detail: String, val share: Float)

/**
 * Settings → Navilife's AI, unfolded (build plan V1, AI layer completeness: "per-feature spend in Settings"): the month's
 * calls and cost by what they were for, in [AiTodayRules]' groups, the costliest first ("Mail · 340 calls · 85¢",
 * "Talk · 120 calls · $1.20"). Non-AI, pure; nothing is stored on the device.
 */
object AiSpendRules {
    /** Health and Settings when Anthropic refuses the key itself (revoked, expired or not allowed to use the API). */
    const val REFUSED_LINE = "Key refused by Anthropic · make a new key in Anthropic's Console and paste it into Secrets Manager"

    fun lines(month: List<AiMonthUse>): List<AiSpendLine> {
        val used = month.filter { it.calls > 0 || it.cents > 0 }
        if (used.isEmpty()) return emptyList()
        val total = used.sumOf { it.cents }
        return used.groupBy { AiTodayRules.groupOf(it.feature) }
            .map { (g, rows) -> Triple(g, rows.sumOf { it.calls }, rows.sumOf { it.cents }) }
            .sortedWith(compareByDescending<Triple<AiTodayRules.Group, Long, Long>> { it.third }.thenByDescending { it.second }.thenBy { it.first.ordinal })
            .map { (g, calls, cents) ->
                val unit = if (g == AiTodayRules.Group.VOICE) "clip" else "call"
                val c = if (calls == 1L) "1 $unit" else "$calls ${unit}s"
                AiSpendLine(g.label, "$c · ${cost(cents)}", if (total > 0) (cents.toFloat() / total).coerceIn(0f, 1f) else 0f)
            }
    }

    /** "under 1¢" for nothing metered, "85¢" below a dollar, else "$1.20". */
    fun cost(cents: Long): String = when {
        cents <= 0 -> "under 1¢"
        cents < 100 -> "$cents¢"
        else -> AskRules.dollars(cents)
    }

    /** The unfolded row's heading line: "This month by feature · $2.05 in all". */
    fun heading(lines: List<AiSpendLine>, spentCents: Long?): String? {
        if (lines.isEmpty()) return null
        return listOfNotNull("This month by feature", spentCents?.let { "${cost(it)} in all" }).joinToString(" · ")
    }
}
