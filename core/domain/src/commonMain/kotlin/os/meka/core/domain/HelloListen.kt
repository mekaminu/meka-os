package os.meka.core.domain

// ---- The orb speed pass, slice 7: the mic is open while the hello is still speaking (non-AI, pure) ----

/**
 * "Just start talking" (Meka 2026-10-20 19:57): after a tap on the orb he shouldn't have to wait for the hello to
 * finish before answering it.
 *
 * - **Headphones** (wired, Bluetooth, the car: little of Navilife's voice reaches the microphone) with Talk over Navilife
 *   on: the recogniser opens as the hello starts ([opensMic]), so his first word is never lost. Whatever the microphone
 *   catches of the hello itself is taken out ([his]); once he has said [MIN_WORDS_OVER] words of his own the hello
 *   stops and those words are the start of his question ([isHim]). The hello ending with nothing of his heard leaves
 *   the recogniser running and the silence timer starts then.
 * - **The speaker**: the recogniser would mostly hear Navilife, so the microphone's level is watched as for any line
 *   (barge-in), with a quicker trigger while the hello plays ([tuning]: it asks him a question, so talking over it is
 *   expected); the hello stops and listening starts.
 *
 * Nothing new is kept or sent: the recogniser is the same one Talk listens with, only opened sooner.
 */
object HelloListenRules {
    /** While the hello is still playing, this many words of his own stop it (one could be the hello misheard). */
    const val MIN_WORDS_OVER = 2

    /** Once the hello has ended, one word of his own is enough. */
    const val MIN_WORDS_AFTER = 1

    /** Barge-in on the speaker during the hello: as [BargeInRules.SPEAKER], a little quicker. */
    val HELLO_SPEAKER = BargeInTuning(marginDb = 12f, minDbfs = -40f, sustainMs = 240L)

    /** The recogniser opens with the hello: headphones and Talk over Navilife on. */
    fun opensMic(talkOver: Boolean, output: BargeInOutput): Boolean = talkOver && output == BargeInOutput.HEADPHONES

    /** The barge-in detector's numbers: quicker on the speaker while the hello plays. */
    fun tuning(output: BargeInOutput, hello: Boolean): BargeInTuning =
        if (hello && output == BargeInOutput.SPEAKER) HELLO_SPEAKER else BargeInRules.tuning(output)

    /**
     * What [heard] holds of his own words. The microphone catches the hello in the order it is said, so the words up
     * front that follow the hello ([hello]) in order are its echo and are taken out: a run of at least two, or all of
     * [heard] (one word on its own that is also in the hello, "good" in "good thanks", stays his). From his first word
     * of his own everything is kept as he said it. A blank [hello] keeps [heard] as it is.
     */
    fun his(heard: String, hello: String): String {
        val words = heard.trim().split(SPACE).filter { it.isNotEmpty() }
        if (hello.isBlank() || words.isEmpty()) return words.joinToString(" ")
        val own = hello.split(SPACE).map(::norm).filter { it.isNotEmpty() }
        var at = 0
        var echo = 0
        for (w in words) {
            val n = norm(w)
            if (n.isEmpty()) { echo++; continue }
            val i = (at until own.size).firstOrNull { same(n, own[it]) } ?: break
            at = i + 1
            echo++
        }
        val drop = if (echo >= 2 || echo == words.size) echo else 0
        return words.drop(drop).joinToString(" ")
    }

    /** He is answering: at least [MIN_WORDS_OVER] words of his own while the hello [playing], else [MIN_WORDS_AFTER]. */
    fun isHim(heard: String, hello: String, playing: Boolean): Boolean {
        val n = his(heard, hello).split(SPACE).count { norm(it).isNotEmpty() }
        return n >= if (playing) MIN_WORDS_OVER else MIN_WORDS_AFTER
    }

    /** The recogniser heard only the hello: listen again rather than take it as silence. */
    fun onlyEcho(heard: String, hello: String): Boolean = heard.isNotBlank() && his(heard, hello).isBlank()

    private val SPACE = Regex("\\s+")

    /** Lower case, letters and digits only ("Meka," → "meka", "you'd" → "youd"). */
    internal fun norm(w: String): String = w.lowercase().filter { it.isLetterOrDigit() }

    /** The same word, or one letter off for longer words (the recogniser mishearing "evening" as "evenin"). */
    internal fun same(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 5 || b.length < 5 || kotlin.math.abs(a.length - b.length) > 1) return false
        return oneEdit(a, b)
    }

    private fun oneEdit(a: String, b: String): Boolean {
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
