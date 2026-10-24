package os.meka.backend

import os.meka.core.wire.AskCodec
import os.meka.core.wire.HelloOpenersCodec

/**
 * Fresh hello openers (the orb says hello like a person, slice 4). The device asks at most once a week for a few openers
 * per part of the day; nothing about Meka is sent or used (no name, no date, no data). The small model writes them, with
 * "{n}" where his name goes; the device checks every line again before it is ever said. Nothing is stored or logged;
 * the meter counts the call under `chat.hello` (in Activity's "Chat"), inside the month's AI budget.
 */
class HelloOpenersService(private val provider: LanguageModelProvider?) {

    fun write(r: HelloOpenersCodec.Request): HelloOpenersCodec.Response {
        val p = provider ?: return HelloOpenersCodec.Response(AskCodec.Response.OFF, reason = "Navilife's AI isn't set up")
        val outcome = p.complete(ModelRequest(FEATURE, ModelTier.SMALL, SYSTEM, listOf(ModelTurn(ModelTurn.Role.USER, userTurn(r))), MAX_TOKENS))
        return when (outcome) {
            is ModelOutcome.Answered -> {
                // At most what was asked for, per part.
                val openers = HelloOpenersCodec.parseModelAnswer(outcome.text)
                    .groupBy { it.part }.values.flatMap { it.take(r.perPart) }
                HelloOpenersCodec.Response(AskCodec.Response.ANSWERED, openers)
            }
            ModelOutcome.Off -> HelloOpenersCodec.Response(AskCodec.Response.OFF, reason = "Navilife's AI is off")
            ModelOutcome.OverBudget -> HelloOpenersCodec.Response(AskCodec.Response.OVER, reason = "This month's AI budget is used up; it's back on the 1st")
            is ModelOutcome.Failed -> HelloOpenersCodec.Response(AskCodec.Response.FAILED, reason = outcome.reason)
        }
    }

    companion object {
        /** Matches the core's HelloFreshRules.FEATURE. */
        const val FEATURE = "chat.hello"
        const val MAX_TOKENS = 1200

        val SYSTEM = """
            You are Obi, a personal assistant: warm, calm, a little dry wit, British English. You write short spoken hellos for when the user taps your orb, so your greeting is different each time.
            Each hello has two parts: "open", a greeting that contains {n} exactly once where the user's first name goes, written with a space before it ("Evening, {n}. Feet up yet?", "Hey {n}, how's the morning treating you?"), never at the very start; and "ask", a short question that hands back to him ("What can I do for you?", "Anything on your mind?").
            Rules: open at most 60 characters, ask at most 40 and ending with "?"; plain letters and , . ? ! ' - only (no digits, emoji or quotes); no names other than {n}, no capitalised words except at a sentence's start; nothing about family, work, money, health, food or drink, weather, sport or days of the week; never name yourself; never "sir". Fit the part of the day: morning, afternoon, evening (17:00 to 23:00) or late (after 23:00, a night owl). Make each one different from the others and from the plain ones above.
            Reply with one JSON object and nothing else: {"openers":[{"part":"morning","open":"…","ask":"…"}]}.
        """.trimIndent()

        fun userTurn(r: HelloOpenersCodec.Request): String =
            "Write ${r.perPart} hellos for each part of the day: ${HelloOpenersCodec.PARTS.joinToString(", ")}."
    }
}
