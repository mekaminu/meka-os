package os.meka.core.domain

/*
 * The orb says hello like a person, slice 3 (Fix first, Meka 2026-10-20 18:13, item (4)). Non-AI and pure.
 *
 * Obi's hello often asks how he is ("Evening, Alex. Good day? What can I do for you?"). When the very next thing he says
 * is only the answer to that ("good thanks, you?", "not bad", "shattered, long day"), Obi answers like a person, at once
 * and on the device (no AI wait), then hands back with "What can I do for you?". Before this, "I'm good, thanks" was
 * taken as a goodbye and Talk ended. Anything more than small talk ("good thanks, what's on tomorrow?") is a question
 * as before, with the hello in the conversation so the companion knows what he's answering. The lines name nobody and
 * hold nothing from his day, so they are made ahead in Obi's voice with the hello's openers ([warmLines]).
 */

enum class HelloMood { GOOD, OK, LOW }

/** What his reply to the hello said: how he is, and whether he asked after Obi ("you?"). */
data class HelloSmallTalk(val mood: HelloMood, val askedBack: Boolean)

object HelloReplyRules {
    private fun phrases(vararg p: String): List<List<String>> = p.map { it.split(' ') }

    private val GOOD = phrases(
        "good", "great", "very good", "really good", "pretty good", "fine", "very well", "really well", "doing well",
        "doing good", "brilliant", "fab", "fantastic", "lovely", "excellent", "amazing", "all good", "good good",
        "decent", "grand", "happy", "nice", "busy but good", "good day", "a good day", "a great day", "not bad at all",
        "can't complain", "cant complain", "mustn't grumble", "mustnt grumble", "well",
    )
    private val OK = phrases(
        "ok", "okay", "alright", "all right", "so so", "not bad", "not too bad", "could be worse", "surviving",
        "getting there", "same old", "meh", "fair", "steady", "average", "managing",
    )
    private val LOW = phrases(
        "tired", "knackered", "shattered", "exhausted", "stressed", "busy", "long day", "a long day", "rough", "rubbish",
        "not great", "not good", "not so good", "not very good", "not the best", "been better", "bad", "awful",
        "terrible", "hectic", "manic", "sleepy", "drained", "frazzled", "fed up", "a bit much",
    )
    private val BACK = phrases(
        "you", "and you", "how about you", "what about you", "how are you", "how are you doing", "how're you",
        "yourself", "and yourself", "how about yourself", "you ok", "you okay", "you good", "you alright", "you all right",
        "how's yours", "how was yours", "how's your day", "how was your day", "how's your evening", "how's your morning",
        "how's it going", "how about obi", "and obi", "how about you obi",
    )
    /** Words around the answer that change nothing ("I'm", "thanks", "really"). */
    private val AROUND = phrases(
        "i'm", "im", "i am", "i've", "ive", "i have", "i've been", "doing", "it's", "its", "it is", "it was", "it's been",
        "been", "was", "thanks", "thank you", "thanks for asking", "thank you for asking", "cheers", "ta", "really",
        "very", "pretty", "quite", "a bit", "bit", "a little", "kind of", "kinda", "but", "though", "actually", "today",
        "day", "the day", "evening", "morning", "afternoon", "night", "so far", "yeah", "yes", "yep", "yup", "ah", "aye",
        "too", "as well", "honestly", "to be fair", "tbh", "thanks obi", "cheers obi", "obi", "mate", "lol",
    )

    private val VOCAB: List<List<String>> by lazy { (GOOD + OK + LOW + BACK + AROUND).sortedByDescending { it.size } }
    private val FILLERS = setOf("meka", "navilife", "hey", "um", "umm", "uh", "er", "erm", "oh", "well", "so", "right", "and", "hmm", "sir")

    /**
     * [utterance] read as only an answer to "how are you?": his mood and whether he asked back; null when it holds
     * anything else (a question, a request, a goodbye) or says no mood at all.
     */
    fun smallTalk(utterance: String): HelloSmallTalk? {
        val ws = TalkRules.words(utterance)
        if (ws.isEmpty()) return null
        val used = mutableListOf<List<String>>()
        var i = 0
        while (i < ws.size) {
            val p = VOCAB.firstOrNull { p -> i + p.size <= ws.size && ws.subList(i, i + p.size) == p }
            if (p == null) {
                if (ws[i] in FILLERS) { i++; continue }
                return null
            }
            used += p
            i += p.size
        }
        val mood = when {
            used.any { it in LOW } -> HelloMood.LOW
            used.any { it in OK } -> HelloMood.OK
            used.any { it in GOOD } -> HelloMood.GOOD
            else -> return null
        }
        return HelloSmallTalk(mood, askedBack = used.any { it in BACK })
    }

    /**
     * Whether the hello [greeting] asked how he is: a question before its last one ("Good day? … What can I do for
     * you?"). The old template and the name ask end with their only question, so "I'm good, thanks" there still ends.
     */
    fun askedAfterHim(greeting: String): Boolean =
        SpeechRules.pieces(greeting).flatMap { sentences(it) }.dropLast(1).any { it.trimEnd().endsWith("?") }

    private fun sentences(text: String): List<String> =
        Regex("""[^.?!]+[.?!]*""").findAll(text).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()

    const val HAND_BACK = "What can I do for you?"

    private val LINES: Map<Pair<HelloMood, Boolean>, List<String>> = mapOf(
        (HelloMood.GOOD to false) to listOf("Glad to hear it. $HAND_BACK", "Good stuff. $HAND_BACK"),
        (HelloMood.GOOD to true) to listOf(
            "Glad to hear it. I'm very well, thanks for asking. $HAND_BACK",
            "Good to hear. I'm good too, thank you. $HAND_BACK",
        ),
        (HelloMood.OK to false) to listOf("Fair enough. Anything I can do to help?", "Steady's not bad. $HAND_BACK"),
        (HelloMood.OK to true) to listOf(
            "Fair enough. I'm well, thanks for asking. Anything I can do to help?",
            "Steady's not bad. I'm good, thank you. $HAND_BACK",
        ),
        (HelloMood.LOW to false) to listOf(
            "Sorry to hear that. Anything I can take off your plate?",
            "Ah, one of those. Let's keep it easy. $HAND_BACK",
        ),
        (HelloMood.LOW to true) to listOf(
            "Sorry to hear that. I'm fine, thanks, but let's look after you. Anything I can take off your plate?",
            "Ah, one of those. I'm well, thank you. Let's keep it easy. $HAND_BACK",
        ),
    )

    /** Obi's answer to [talk], one of two by [seed] so it isn't always the same: warm, short, and it hands back. */
    fun answer(talk: HelloSmallTalk, seed: Int): String {
        val pool = LINES.getValue(talk.mood to talk.askedBack)
        return pool[seed.mod(pool.size)]
    }

    /**
     * Obi's answer when [utterance] is the first thing said after the hello [greeting] and only small talk: when the
     * hello asked how he is, or Meka asked after Obi ("fine, you?"). Null otherwise (Talk reads it as before).
     */
    fun reply(greeting: String, utterance: String): String? {
        val talk = smallTalk(utterance) ?: return null
        if (!talk.askedBack && !askedAfterHim(greeting)) return null
        return answer(talk, greeting.length)
    }

    /** Every line [answer] can say, to be made ahead in Obi's voice with the hello's openers (each piece as said). */
    fun warmLines(): List<String> = LINES.values.flatten().flatMap { SpeechRules.pieces(it) }.distinct()
}
