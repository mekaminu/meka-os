package os.meka.core.domain

/*
 * "Play my messages" in Talk (call assistant polish 8c, the last part of "Listen to callers' messages"; non-AI, pure).
 *
 * Asked aloud, MEKA plays the voice messages callers left with the call assistant, one after another: who and when in
 * MEKA's voice, then the caller's own recording (from MEKA's server, over a signed request, played from memory; the
 * same audio as ▶ Play in the after-work summary), or their words when no recording is kept ("Keep callers'
 * recordings: Don't keep") or it can't be played. Recognised on the device: nothing goes to the AI for this, and
 * nothing is marked or cleared (Done in the summary stays Meka's tap).
 */

/** One step of a playlist, in order: MEKA says something, or a caller's recording plays. */
sealed interface PlayStep {
    /** MEKA says [text] (its voice, else the device's). */
    data class Say(val text: String) : PlayStep
    /** The recording of held message [heldId]; when it can't be had or played, MEKA says [otherwise] instead. */
    data class Recording(val heldId: String, val otherwise: String) : PlayStep
}

/** What "play my messages" plays: the [steps] in order, how many messages are in it ([count]) and how many were left for the summary ([more]). */
data class VoicePlaylist(val steps: List<PlayStep>, val count: Int, val more: Int)

object VoicePlaylistRules {
    /** Messages played in one go; the rest wait in the after-work summary. */
    const val MAX = 5
    /** A message's words read out (when there's no recording) are cut at a sentence past this. */
    const val MAX_WORDS_CHARS = 1_500
    /** What the conversation remembers MEKA did, so "and the second one?" has something to go on. */
    const val PLAYED = "Played Meka's voice messages from the call assistant."

    private val DROP = setOf("please", "meka", "hey", "can", "could", "would", "will", "you", "me", "back", "now", "then",
        "so", "just", "um", "umm", "uh", "er", "erm", "and", "ok", "okay", "for", "out")
    private val DETERMINERS = setOf("my", "the", "any", "all", "new", "latest", "those", "them", "of")
    private val NOUNS = listOf(
        listOf("voice", "messages"), listOf("voice", "message"), listOf("voice", "mails"), listOf("voice", "mail"),
        listOf("voicemails"), listOf("voicemail"), listOf("messages"), listOf("message"),
    )
    /** Nouns that are about voice messages only ("read my messages" could mean WhatsApp, so "read" needs one). */
    private val VOICE_NOUNS = NOUNS.filter { it.size == 2 || it[0].startsWith("voicemail") }
    private val ASKING = listOf(
        listOf("do", "i", "have"), listOf("have", "i", "got"), listOf("did", "i", "get"), listOf("are", "there"),
        listOf("is", "there"), listOf("got"),
    )
    private val WHO_CALLED = listOf(
        listOf("who", "called"), listOf("who", "rang"), listOf("who's", "called"), listOf("who", "has", "called"),
        listOf("did", "anyone", "call"), listOf("did", "anybody", "call"), listOf("did", "anyone", "ring"),
        listOf("did", "anybody", "ring"), listOf("has", "anyone", "called"), listOf("has", "anybody", "called"),
        listOf("has", "anyone", "rung"), listOf("any", "calls"), listOf("any", "missed", "calls"),
    )

    /**
     * Whether Meka asked for his voice messages: "play my messages", "play my voice messages", "can you play me the
     * voicemails", "listen to my messages", "read my voice messages", "any voice messages?", "do I have any messages",
     * "who called?", "did anyone ring". "Read my messages" alone isn't (it could mean WhatsApp), nor is anything with
     * more in it ("play my messages from Tom tomorrow" goes to MEKA as a question).
     */
    fun isPlayRequest(utterance: String): Boolean {
        val ws = TalkRules.words(utterance).filter { it !in DROP }
        if (ws.isEmpty()) return false
        if (ws in WHO_CALLED) return true
        // play / hear / listen to / read + [the, my, any, new…] + messages
        val (verb, rest) = when {
            ws[0] == "play" || ws[0] == "hear" -> ws[0] to ws.drop(1)
            ws[0] == "listen" && ws.getOrNull(1) == "to" -> "listen" to ws.drop(2)
            ws[0] == "read" -> "read" to ws.drop(1)
            else -> null to ws
        }
        if (verb != null) {
            val noun = rest.dropWhile { it in DETERMINERS }
            return noun in (if (verb == "read") VOICE_NOUNS else NOUNS)
        }
        // [do I have / have I got / are there] any (voice) messages
        val asked = ASKING.firstOrNull { ws.size >= it.size && ws.subList(0, it.size) == it }?.size ?: 0
        val tail = ws.drop(asked)
        if (tail.firstOrNull() != "any") return false
        return tail.drop(1).dropWhile { it == "new" } in NOUNS
    }

    /**
     * The playlist from the after-work summary the device shows ([summary]; the Fold passes its contacts-named copy):
     * the voice messages in it, urgent first, then oldest first, at most [MAX]; each said as "From Mum at 14:05."
     * ("Urgent, from …", "yesterday at 18:40", "on Thursday 8 October at 09:12"; numbered when there are several), then
     * the recording when one is kept ([CapturedItem.hasAudio]) or the words. With none: "No voice messages waiting."
     * and what else is held. It always ends with "Anything else?".
     */
    fun build(summary: AfterWorkSummary, nowMs: Long, cal: LocalCalendar): VoicePlaylist {
        val all = summary.people.flatMap { p -> p.items.filter { it.kind == CaptureKind.VOICE_MESSAGE }.map { p.personName to it } }
            .sortedWith(compareBy<Pair<String, CapturedItem>>({ !it.second.isUrgent }, { it.second.atMs }, { it.second.id }))
        if (all.isEmpty()) {
            val other = listOfNotNull(
                plural(summary.messages, "message").takeIf { summary.messages > 0 },
                plural(summary.missedCalls, "missed call").takeIf { summary.missedCalls > 0 },
            )
            val line = if (other.isEmpty()) "No voice messages waiting."
            else "No voice messages. There " + (if (summary.messages + summary.missedCalls == 1) "is " else "are ") +
                other.joinToString(" and ") + " in the after-work summary."
            return VoicePlaylist(listOf(PlayStep.Say("$line ${TalkRules.ANYTHING_ELSE}")), 0, 0)
        }
        val played = all.take(MAX)
        val more = all.size - played.size
        val steps = mutableListOf<PlayStep>()
        steps += PlayStep.Say(
            (if (all.size == 1) "You've got one voice message." else "You've got ${all.size} voice messages.") +
                (if (more > 0) " Here are the first $MAX." else ""),
        )
        played.forEachIndexed { i, (who, item) ->
            val number = if (played.size > 1) "${ordinal(i)}, " else ""
            val from = (if (item.isUrgent) "urgent, from " else "from ") + TalkRules.speakable(who).trimEnd('.')
            steps += PlayStep.Say((number + from + " " + spokenWhen(item.atMs, nowMs, cal) + ".").replaceFirstChar { it.uppercase() })
            val words = wordsLine(item)
            steps += if (item.hasAudio) PlayStep.Recording(item.id, if (item.text != null) words else COULDNT_PLAY) else PlayStep.Say(words)
        }
        steps += PlayStep.Say(
            (if (more > 0) "And $more more in the after-work summary." else if (played.size > 1) "That's all of them." else "That's it.") +
                " " + TalkRules.ANYTHING_ELSE,
        )
        return VoicePlaylist(steps, played.size, more)
    }

    const val COULDNT_PLAY = "That one couldn't be played, and no words came through."

    /** The caller's words as MEKA reads them: "They said: …", or why there are none. */
    fun wordsLine(item: CapturedItem): String = when {
        item.text != null -> "They said: " + TalkRules.speakable(item.text, MAX_WORDS_CHARS / 40, MAX_WORDS_CHARS)
        item.transcribing -> "Their words are still on their way."
        else -> "No words came through."
    }

    /** "at 14:05" today, "yesterday at 18:40", else "on Thursday 8 October at 09:12". */
    fun spokenWhen(atMs: Long, nowMs: Long, cal: LocalCalendar): String {
        val hhmm = LocalClock.formatMinute(cal.minuteOfDay(atMs))
        val day = cal.epochDayOf(atMs)
        val today = cal.epochDayOf(nowMs)
        return when (day) {
            today -> "at $hhmm"
            today - 1 -> "yesterday at $hhmm"
            else -> "on ${CivilDate.longLabel(day)} at $hhmm"
        }
    }

    private fun ordinal(i: Int) = listOf("first", "second", "third", "fourth", "fifth").getOrElse(i) { "next" }

    private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
}
