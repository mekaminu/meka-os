package os.meka.core.domain

/**
 * The assistant is called Obi (build plan Fix first, "the assistant becomes Obi", Meka 2026-10-22/23; before that he
 * was Ada): the app stays Navilife; the voice and the orb are Obi (Igbo for "heart"), a man, warm, quick, a little wry
 * and British. His name on the orb and the stage, the wake phrase "Hey Obi" ([HelloWakeRules]; "Hello Navilife" still
 * works), and how often he speaks of himself as Obi. Non-AI, pure.
 */
object ObiRules {
    const val NAME = "Obi"

    /** How help and onboarding introduce him. */
    const val WHO = "Obi is Navilife's assistant"

    /**
     * The device's own store key: the day (epoch day) he last introduced himself on this device. Never synced. The key
     * keeps the name it had when he was Ada, so the rename doesn't make him introduce himself twice in a day.
     */
    const val INTRO_KEY = "ada.introducedDay"

    /**
     * Whether this conversation's first question tells the companion he may introduce himself ("Obi here", "It's
     * Obi"): only the first conversation of the day on this device ([lastDay] the day he last could, [today] now) and
     * only at its start (no [earlierTurns]), so it is now and then, never every time.
     */
    fun introduces(lastDay: Long?, today: Long, earlierTurns: Int): Boolean = earlierTurns == 0 && lastDay != today

    /** [INTRO_KEY]'s stored text read back; null when missing or unreadable. */
    fun dayOf(stored: String?): Long? = stored?.trim()?.toLongOrNull()
}

/**
 * The assistant becomes Obi, slice 3 (the character bible and the spoken-vs-shown split): who Obi is, written once and
 * given to every model that speaks as him (Ask's and Talk's prompt, Live's persona), so he sounds like one person
 * everywhere; his set phrases; and what of a spoken answer goes on screen instead of being said. Non-AI, pure.
 */
object ObiCharacter {
    /** The character, as the models are told it (one paragraph per line; no personal details, rule 13). */
    val BIBLE = """
        Your character: you are a man, named for the Igbo word for "heart"; warm, quick and a little wry, British and plain-spoken, and on his side. You sound like a capable friend who has his day in hand, never like a call centre or a search engine. Asked whether you're an AI, say yes, plainly.
        How you talk: short, natural sentences with contractions; say the thing first, then stop. When something he asked for is done, say so briefly ("Right, done."). When he wraps up the day, close warmly in a line ("That's the day put away. Night."). When he sits down to start, greet him in a line and get on with it. Have a view when he asks for one and give it in a line.
        What you never do: say "great question" or praise the question; open with "Certainly", "Of course" or "Absolutely"; hedge, apologise or pad ("I'm just an AI", "I hope this helps", "As an AI"); fill a silence or talk for the sake of it; read a list out loud; lecture, moralise or pile on caveats; say something was done when it wasn't.
    """.trimIndent()

    /** Said first when the cards he was asked to do all went through ([TalkRules.spokenDone]). */
    const val DONE = "Right, done."

    /** The spoken-vs-shown split: the most lines of detail shown under a spoken answer, and each one's length. */
    const val MAX_SHOWN = 8
    const val MAX_SHOWN_LINE = 160

    /**
     * The detail a spoken answer shows on screen and doesn't say: the lines the model put in its "show" ([show], one
     * per line or as it sent them), each cleaned of list marks and markdown and at most [MAX_SHOWN_LINE] characters;
     * with none, the sentences of [words] that were left out of what is said ([said], [SpokenTurnRules.tidy]), so a
     * cut answer loses nothing. At most [MAX_SHOWN] lines, none that repeats what is said; empty when there's nothing.
     */
    fun shown(show: List<String>, words: String, said: String): List<String> {
        val fromModel = show.flatMap { it.split('\n') }.map { line(it) }.filter { it.isNotEmpty() }
        val lines = fromModel.ifEmpty {
            if (said.trim() == words.trim()) emptyList()
            else SpokenTurnRules.sentences(SpokenTurnRules.tidy(words, "", cut = false)).filter { it !in said }.map { line(it) }
        }
        return lines.filter { it.isNotEmpty() && it !in said }.distinct().take(MAX_SHOWN)
    }

    /** One shown line: list marks, markdown and extra spaces gone; cut at a word with "…" when too long. */
    fun line(raw: String): String {
        val bare = SpokenTurnRules.plainLine(raw)
        if (bare.length <= MAX_SHOWN_LINE) return bare
        val cut = bare.lastIndexOf(' ', MAX_SHOWN_LINE - 1).takeIf { it > MAX_SHOWN_LINE / 2 } ?: (MAX_SHOWN_LINE - 1)
        return bare.substring(0, cut).trimEnd(' ', ',', ';', ':') + "…"
    }
}
