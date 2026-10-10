package os.meka.core.domain

/*
 * Talk to MEKA (build plan V1, "MEKA as the phone's default assistant + voice", slice 1; ADR-006). Non-AI and pure.
 *
 * A spoken back-and-forth built on Ask MEKA: the apps listen on the device (nothing recorded or kept as audio), send
 * each question with the last few exchanges so "and move it to Friday" means something, speak a short answer on the
 * device and listen again until Meka goes quiet or says "that's all". A proposal is still a card that does nothing by
 * itself: a spoken "yes" (or "the second one", "both") is Meka's own confirmation, exactly like tapping the card, and
 * every change shows its undo chip on screen. This file decides what an utterance means ([TalkRules.reply]), what MEKA
 * says ([TalkRules.spoken] and friends), and the turn-taking ([TalkFlow]); the apps only run the effects.
 */

/** One finished exchange: Meka's question, MEKA's answer, and what Meka then confirmed ("Added “Milk”"). */
data class TalkTurn(val question: String, val answer: String, val done: List<String> = emptyList())

/** The conversation so far, oldest first. Only the last [TalkRules.MAX_HISTORY] exchanges go with a question. */
data class Conversation(val turns: List<TalkTurn> = emptyList()) {
    val history: List<TalkTurn> get() = turns.takeLast(TalkRules.MAX_HISTORY)

    fun answered(question: String, answer: String): Conversation =
        Conversation(turns + TalkTurn(question, answer))

    /** Records what Meka confirmed after the latest answer (at most [TalkRules.MAX_DONE] lines are kept per turn). */
    fun did(lines: List<String>): Conversation {
        val last = turns.lastOrNull() ?: return this
        if (lines.isEmpty()) return this
        return Conversation(turns.dropLast(1) + last.copy(done = (last.done + lines).takeLast(TalkRules.MAX_DONE)))
    }
}

/** What an utterance means while MEKA is listening. */
sealed interface TalkReply {
    /** Nothing usable was heard (the same as silence). */
    data object Silence : TalkReply
    /** "That's all", "thanks, bye", "stop": the conversation ends quietly. */
    data object End : TalkReply
    /** "Yes", "do it", "the second one", "both": the cards at these positions (0-based), as if tapped. */
    data class Confirm(val indices: List<Int>) : TalkReply
    /** "Yes" while there are several cards: MEKA asks which. */
    data object Which : TalkReply
    /** "No", "leave it": the cards stay on screen untouched and MEKA stops offering them aloud. */
    data object Decline : TalkReply
    /** "Play my messages", "any voice messages?", "who called?" ([VoicePlaylistRules.isPlayRequest]): played on the device, no AI. */
    data class PlayMessages(val question: String) : TalkReply
    /** Anything else is a new question. */
    data class Ask(val question: String) : TalkReply
}

object TalkRules {
    /** Exchanges sent with each question (the server refuses more). */
    const val MAX_HISTORY = 6
    /** Confirmed lines kept per exchange. */
    const val MAX_DONE = 3
    /** Questions in one conversation before MEKA stops listening by itself (a TV in the background can't run up calls). */
    const val MAX_QUESTIONS = 12
    /** What MEKA says aloud from an answer's words: at most this many sentences and characters. */
    const val MAX_SPOKEN_SENTENCES = 3
    const val MAX_SPOKEN_CHARS = 320

    const val ANYTHING_ELSE = "Anything else?"
    const val LEFT_IT = "OK, I've left it. Anything else?"
    const val TOO_MANY = "That's a lot for one go. Tap the mic to carry on."
    const val NOT_HEARD_CARD = "That one couldn't be done any more."

    /** Words that carry no meaning on their own and are ignored around the rest. */
    private val FILLERS = setOf("meka", "hey", "um", "umm", "uh", "er", "erm", "oh", "well", "so", "right", "then", "just", "and")

    private val ENDINGS = phrases(
        "that's all", "thats all", "that is all", "that's it", "thats it", "that is it", "that's everything",
        "that'll be all", "thanks", "thank you", "thanks a lot", "cheers", "ta", "bye", "goodbye", "bye bye", "see you",
        "stop", "stop listening", "nothing", "nothing else", "no", "nope", "no more", "cancel", "never mind", "nevermind",
        "i'm done", "im done", "i'm good", "im good", "all good", "done", "for now", "that's fine", "thats fine",
        "ok", "okay", "great", "lovely", "perfect", "brilliant",
    )
    /** An ending needs one of these; "ok" or "great" on its own isn't a goodbye. */
    private val ENDING_CORE = phrases(
        "that's all", "thats all", "that is all", "that's it", "thats it", "that is it", "that's everything",
        "that'll be all", "thanks", "thank you", "thanks a lot", "cheers", "ta", "bye", "goodbye", "bye bye", "see you",
        "stop", "stop listening", "nothing", "nothing else", "no", "nope", "no more", "cancel", "never mind", "nevermind",
        "i'm done", "im done", "i'm good", "im good", "all good", "done",
    )
    private val YES = phrases(
        "yes", "yeah", "yep", "yup", "yes please", "please", "sure", "ok", "okay", "alright", "all right", "do it",
        "do that", "go ahead", "go for it", "add it", "set it", "start it", "move it", "tick it off", "sounds good",
        "perfect", "great", "confirm", "yes do it", "please do",
    )
    private val NO = phrases(
        "no", "nope", "no thanks", "no thank you", "don't", "dont", "do not", "don't do it", "leave it", "not now",
        "skip it", "skip", "cancel", "never mind", "nevermind", "not that",
    )
    private val ORDINALS = listOf(
        phrases("the first", "the first one", "first", "first one", "number one", "one"),
        phrases("the second", "the second one", "second", "second one", "number two", "two"),
        phrases("the third", "the third one", "third", "third one", "number three", "three"),
    )
    private val LAST = phrases("the last", "the last one", "last one", "last")
    private val ALL: List<List<String>> = phrases("all", "all of them", "all three", "do all", "do them all", "all of it", "everything", "both", "both of them", "do both")

    private val SELECTING: List<List<String>> by lazy { (YES + ORDINALS.flatten() + LAST + ALL).sortedByDescending { it.size } }

    private fun phrases(vararg p: String): List<List<String>> = p.map { it.split(' ') }.sortedByDescending { it.size }

    /** Lower case words without punctuation; "That's all, thanks!" → [that's, all, thanks]. */
    fun words(utterance: String): List<String> =
        utterance.lowercase().replace('’', '\'').replace(Regex("""[^a-z0-9' ]"""), " ")
            .split(' ').map { it.trim('\'') }.filter { it.isNotEmpty() }

    /** Whether [ws] is made up entirely of [vocabulary] phrases (fillers allowed), and returns the phrases used. */
    private fun cover(ws: List<String>, vocabulary: List<List<String>>): List<List<String>>? {
        val used = mutableListOf<List<String>>()
        var i = 0
        while (i < ws.size) {
            if (ws[i] in FILLERS) { i++; continue }
            val p = vocabulary.firstOrNull { p -> i + p.size <= ws.size && ws.subList(i, i + p.size) == p } ?: return null
            used += p
            i += p.size
        }
        return used
    }

    /** "That's all", "no thanks, bye", "ok cheers": the conversation is over. */
    fun isEnding(utterance: String): Boolean {
        val used = cover(words(utterance), ENDINGS) ?: return false
        return used.any { it in ENDING_CORE }
    }

    /**
     * What [utterance] means with [pendingCards] cards on offer from the latest answer. With cards on offer a yes (with
     * or without "the second one", "both", "all of them") confirms, a no leaves them; otherwise a goodbye ends and
     * anything else is a new question. "Both" needs exactly two cards; a position past the last card is a question.
     */
    fun reply(utterance: String, pendingCards: Int): TalkReply {
        val question = AskRules.question(utterance) ?: return TalkReply.Silence
        val ws = words(question)
        if (ws.isEmpty() || ws.all { it in FILLERS }) return TalkReply.Silence
        if (pendingCards > 0) {
            selection(ws, pendingCards)?.let { return it }
            if (cover(ws, NO) != null) return TalkReply.Decline
        }
        if (VoicePlaylistRules.isPlayRequest(question)) return TalkReply.PlayMessages(question)
        if (isEnding(question)) return TalkReply.End
        return TalkReply.Ask(question)
    }

    private fun selection(ws: List<String>, n: Int): TalkReply? {
        val used = cover(ws, SELECTING)?.takeIf { it.isNotEmpty() } ?: return null
        val picks = mutableSetOf<Int>()
        var all = false
        used.forEach { p ->
            when {
                p in ALL -> { if (p.contains("both") && n != 2) return null; all = true }
                p in LAST -> picks += n - 1
                else -> ORDINALS.indexOfFirst { p in it }.takeIf { it >= 0 }?.let { if (it >= n) return null; picks += it }
            }
        }
        return when {
            all -> TalkReply.Confirm((0 until n).toList())
            picks.isNotEmpty() -> TalkReply.Confirm(picks.sorted())
            n == 1 -> TalkReply.Confirm(listOf(0))
            else -> TalkReply.Which
        }
    }

    // ---- What MEKA says ----

    /**
     * An answer as MEKA says it: the words made speakable (no list marks, symbols or quotes; "19:00–20:00" → "19:00 to
     * 20:00"), at most [MAX_SPOKEN_SENTENCES] sentences, then the offer: "Shall I add Call the dentist for tomorrow at
     * 09:00?" for one card, or the choices and "Say which, or all of them." for several.
     */
    fun spoken(answer: AskAnswer, today: Long): String {
        val words = speakable(answer.text)
        val offer = when (answer.cards.size) {
            0 -> null
            1 -> "Shall I ${phrase(answer.cards[0].proposal, today, past = false)}?"
            else -> {
                val list = answer.cards.map { phrase(it.proposal, today, past = false) }
                val choices = list.dropLast(1).joinToString(", ") + " or " + list.last()
                "I can ${choices}. Say which, or " + (if (answer.cards.size == 2) "both." else "all of them.")
            }
        }
        return listOfNotNull(words.takeIf { it.isNotEmpty() }, offer).joinToString(" ")
    }

    /** After Meka's yes: "Added Call the dentist for tomorrow at 09:00. Anything else?" (failures said plainly). */
    fun spokenDone(done: List<AskProposal>, failed: Int, today: Long): String {
        val said = done.map { phrase(it, today, past = true).replaceFirstChar { c -> c.uppercase() } + "." }
        val fail = if (failed > 0) listOf(if (failed == 1 && done.isEmpty()) "That couldn't be done any more." else "$failed couldn't be done any more.") else emptyList()
        return (said + fail + ANYTHING_ELSE).joinToString(" ")
    }

    /** "Which one? Say the first, the second or both." */
    fun spokenWhich(cards: Int): String {
        val names = listOf("the first", "the second", "the third").take(cards.coerceIn(2, 3))
        return "Which one? Say " + names.dropLast(1).joinToString(", ") + " or " + names.last() + ", or " +
            (if (cards == 2) "both." else "all of them.")
    }

    /** One of MEKA's actions in words: "add Milk for tomorrow at 09:00" / "added Milk for tomorrow at 09:00". */
    fun phrase(p: AskProposal, today: Long, past: Boolean): String = when (p) {
        is AskProposal.AddTask -> (if (past) "added " else "add ") + clean(p.title) + (p.day?.let { " for " + spokenWhen(it, p.minute, today) } ?: "")
        is AskProposal.CompleteTask -> (if (past) "ticked off " else "tick off ") + clean(p.title)
        is AskProposal.MoveTask -> (if (past) "moved " else "move ") + clean(p.title) + " to " + spokenWhen(p.day, p.minute, today)
        is AskProposal.StartFast -> (if (past) "started a " else "start a ") + fastWords(p.hours) + " fast"
        is AskProposal.Timer -> "set a timer for " + durationWords(p.minutes)
        is AskProposal.Alarm -> "set an alarm for " + LocalClock.formatMinute(p.minute)
    }

    /** "today at 14:30", "tomorrow", "Friday 9 October at 09:00". */
    fun spokenWhen(day: Long, minute: Int?, today: Long): String {
        val d = when (day) {
            today -> "today"
            today + 1 -> "tomorrow"
            else -> CivilDate.longLabel(day)
        }
        return if (minute == null) d else "$d at ${LocalClock.formatMinute(minute)}"
    }

    /** "36-hour", "5-day". */
    fun fastWords(hours: Int): String = if (hours >= 48 && hours % 24 == 0) "${hours / 24}-day" else "$hours-hour"

    /** "20 minutes", "1 hour", "1 hour 30 minutes". */
    fun durationWords(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        fun unit(n: Int, w: String) = "$n $w" + if (n == 1) "" else "s"
        return listOfNotNull(h.takeIf { it > 0 }?.let { unit(it, "hour") }, m.takeIf { it > 0 || h == 0 }?.let { unit(it, "minute") }).joinToString(" ")
    }

    /**
     * Text made to be read aloud: list marks and markdown gone, "·" a comma, an en dash between times "to", quotes and
     * emoji dropped, whitespace collapsed; cut to [MAX_SPOKEN_SENTENCES] sentences and [MAX_SPOKEN_CHARS] characters
     * (at a sentence or word end). Also used for read-outs, which are plain text and never acted on.
     */
    fun speakable(text: String): String = speakable(text, MAX_SPOKEN_SENTENCES, MAX_SPOKEN_CHARS)

    /** [speakable] with its own limits: a caller's words read out ("play my messages") are longer than an answer. */
    fun speakable(text: String, maxSentences: Int, maxChars: Int): String {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map { l ->
            val bare = l.removePrefix("- ").removePrefix("• ").removePrefix("* ").let { Regex("""^\d+[.)]\s+""").replace(it, "") }
            if (bare.isNotEmpty() && bare.last() !in ".!?:") "$bare." else bare
        }
        var s = lines.joinToString(" ")
        s = Regex("""(\d{1,2}:\d{2})\s*[–-]\s*(\d{1,2}:\d{2})""").replace(s) { "${it.groupValues[1]} to ${it.groupValues[2]}" }
        s = s.replace(" · ", ", ").replace("·", ", ").replace(Regex("""[*_#`“”"]"""), "").replace(" — ", ", ").replace(" – ", ", ")
        s = buildString {
            // Emoji and pictographs (outside the basic plane, the symbol blocks, the variation selector) aren't said.
            s.forEach { c -> if (!c.isSurrogate() && c.code !in 0x2600..0x27BF && c.code != 0xFE0F) append(c) }
        }
        s = s.replace(Regex("""\s+"""), " ").replace(Regex(""" ([,.!?])"""), "$1").replace(",,", ",").trim()
        // Sentences end at . ! ? followed by a space ("$1.20" stays whole).
        val sentences = s.split(Regex("""(?<=[.!?])\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        var out = sentences.take(maxSentences).joinToString(" ")
        if (out.length > maxChars) {
            val cut = out.take(maxChars)
            out = cut.substring(0, cut.lastIndexOf(' ').takeIf { it > maxChars / 2 } ?: cut.length).trimEnd(',', ' ') + "."
        }
        return out
    }

    private fun clean(title: String) = speakable(title).trimEnd('.')
}

/**
 * What a spoken yes came to ([TalkEffect.Do] run through `MekaCore.doTalk`): the proposals [done] with the undo bar's
 * [lines] and how to take each back ([undos]), and how many [failed] (the task went meanwhile).
 */
data class TalkDid(val done: List<AskProposal>, val lines: List<String>, val undos: List<AskUndo>, val failed: Int) {
    /** One undo bar for the lot: "Added “Milk” · Set a timer for 20 min", or why nothing was done. */
    val barLine: String get() = lines.joinToString(" · ").ifEmpty { TalkRules.NOT_HEARD_CARD }
}

/** Where a conversation is. */
enum class TalkPhase { LISTENING, THINKING, DOING, SPEAKING, ENDED }

/** What the app has to do next. */
sealed interface TalkEffect {
    /** Listen on the device (on-device recognition; nothing kept as audio). */
    data object Listen : TalkEffect
    /** Ask MEKA [question] with [history] (`MekaCore.talk`). */
    data class Ask(val question: String, val history: List<TalkTurn>) : TalkEffect
    /** Do these cards exactly as tapping them would (`MekaCore.doAsk`), then report with [TalkFlow.did]. */
    data class Do(val cards: List<AskCard>) : TalkEffect
    /**
     * Play the voice messages callers left ("play my messages"): the app builds the playlist from the summary it shows
     * (`MekaCore.voicePlaylist`), says and plays its steps in order, then reports with [TalkFlow.spoke]; stopping
     * speech ([StopSpeaking], [End]) stops the playlist too.
     */
    data object PlayMessages : TalkEffect
    /** Say [text] on the device, then report with [TalkFlow.spoke]. */
    data class Speak(val text: String) : TalkEffect
    /** Stop speaking at once (Meka talked over MEKA). */
    data object StopSpeaking : TalkEffect
    /** The conversation is over: stop listening and speaking, put the orb away. Cards and undo chips stay on screen. */
    data object End : TalkEffect
}

/**
 * A conversation's state: its [phase], the [conversation] so far, the cards on offer from the latest answer, how many
 * questions were asked, and whether to stop once the current line is said (AI off, too many questions).
 */
data class TalkSession(
    val phase: TalkPhase,
    val conversation: Conversation = Conversation(),
    val pending: List<AskCard> = emptyList(),
    val questions: Int = 0,
    val endAfterSpeaking: Boolean = false,
)

data class TalkStep(val session: TalkSession, val effects: List<TalkEffect>)

/**
 * The turn-taking (pure): listen → think → speak → listen again, until silence, a goodbye, a stop or
 * [TalkRules.MAX_QUESTIONS]. Talking over MEKA stops it and listens (barge-in). An event that doesn't fit the phase
 * (a late answer after Stop) changes nothing.
 */
object TalkFlow {
    fun start(): TalkStep = TalkStep(TalkSession(TalkPhase.LISTENING), listOf(TalkEffect.Listen))

    /** A final transcript while listening. */
    fun heard(s: TalkSession, utterance: String): TalkStep {
        if (s.phase != TalkPhase.LISTENING) return TalkStep(s, emptyList())
        return when (val r = TalkRules.reply(utterance, s.pending.size)) {
            TalkReply.Silence -> silence(s)
            TalkReply.End -> end(s)
            is TalkReply.Confirm -> TalkStep(s.copy(phase = TalkPhase.DOING), listOf(TalkEffect.Do(r.indices.map { s.pending[it] })))
            TalkReply.Which -> say(s, TalkRules.spokenWhich(s.pending.size))
            TalkReply.Decline -> say(s.copy(pending = emptyList()), TalkRules.LEFT_IT)
            is TalkReply.PlayMessages -> TalkStep(
                s.copy(phase = TalkPhase.SPEAKING, pending = emptyList(), conversation = s.conversation.answered(r.question, VoicePlaylistRules.PLAYED)),
                listOf(TalkEffect.PlayMessages),
            )
            is TalkReply.Ask -> {
                if (s.questions >= TalkRules.MAX_QUESTIONS) return say(s.copy(endAfterSpeaking = true), TalkRules.TOO_MANY)
                TalkStep(
                    s.copy(phase = TalkPhase.THINKING, pending = emptyList(), questions = s.questions + 1),
                    listOf(TalkEffect.Ask(r.question, s.conversation.history)),
                )
            }
        }
    }

    /** Nothing heard before the recogniser gave up: the conversation ends quietly. */
    fun silence(s: TalkSession): TalkStep =
        if (s.phase != TalkPhase.LISTENING) TalkStep(s, emptyList()) else end(s)

    /** MEKA's answer to [question]. An unavailable answer is said and the conversation ends after it. */
    fun answered(s: TalkSession, question: String, outcome: AskOutcome, today: Long): TalkStep {
        if (s.phase != TalkPhase.THINKING) return TalkStep(s, emptyList())
        return when (outcome) {
            is AskOutcome.Answered -> say(
                s.copy(conversation = s.conversation.answered(question, outcome.answer.text), pending = outcome.answer.cards),
                TalkRules.spoken(outcome.answer, today),
            )
            is AskOutcome.Unavailable -> say(s.copy(endAfterSpeaking = true), TalkRules.speakable(outcome.line))
        }
    }

    /**
     * What the confirmed cards came to: [done] the proposals that went through (with the undo bar's [lines]),
     * [failed] how many couldn't be done. The conversation remembers them and MEKA says so.
     */
    fun did(s: TalkSession, done: List<AskProposal>, lines: List<String>, failed: Int, today: Long): TalkStep {
        if (s.phase != TalkPhase.DOING) return TalkStep(s, emptyList())
        return say(
            s.copy(conversation = s.conversation.did(lines), pending = emptyList()),
            TalkRules.spokenDone(done, failed, today),
        )
    }

    /** MEKA finished saying its line: listen again (or end, when that was the last word). */
    fun spoke(s: TalkSession): TalkStep {
        if (s.phase != TalkPhase.SPEAKING) return TalkStep(s, emptyList())
        if (s.endAfterSpeaking) return end(s)
        return TalkStep(s.copy(phase = TalkPhase.LISTENING), listOf(TalkEffect.Listen))
    }

    /** Meka started talking while MEKA spoke: stop and listen (a closing line is cut short and the conversation ends). */
    fun bargeIn(s: TalkSession): TalkStep {
        if (s.phase != TalkPhase.SPEAKING) return TalkStep(s, emptyList())
        if (s.endAfterSpeaking) return TalkStep(s.copy(phase = TalkPhase.ENDED), listOf(TalkEffect.StopSpeaking, TalkEffect.End))
        return TalkStep(s.copy(phase = TalkPhase.LISTENING), listOf(TalkEffect.StopSpeaking, TalkEffect.Listen))
    }

    /** The orb tapped, the screen left, a call coming in: everything stops at once. */
    fun stop(s: TalkSession): TalkStep =
        if (s.phase == TalkPhase.ENDED) TalkStep(s, emptyList())
        else TalkStep(s.copy(phase = TalkPhase.ENDED), listOfNotNull(TalkEffect.StopSpeaking.takeIf { s.phase == TalkPhase.SPEAKING }, TalkEffect.End))

    private fun say(s: TalkSession, text: String): TalkStep =
        TalkStep(s.copy(phase = TalkPhase.SPEAKING), listOf(TalkEffect.Speak(text)))

    private fun end(s: TalkSession): TalkStep = TalkStep(s.copy(phase = TalkPhase.ENDED), listOf(TalkEffect.End))
}

// ---- On the device: the voice, the orb and what goes wrong (build plan V1, voice slice 2; non-AI, pure) ----

/**
 * A text-to-speech voice the device offers, reduced to what choosing one needs: its [language] tag ("en-GB"), a
 * [quality] rank where higher is better (Android's `Voice.QUALITY_*`, the Mac's default · enhanced · premium), whether
 * it [needsNetwork] (the text would be sent away to be spoken) and whether it is [installed].
 */
data class VoiceCandidate(val name: String, val language: String, val quality: Int, val needsNetwork: Boolean = false, val installed: Boolean = true)

/**
 * Why MEKA can't listen, said on screen (not aloud) and the conversation ends: [line] on the Fold, [macLine] on the Mac
 * (System Settings, Dictation's on-device English, "click").
 */
enum class TalkProblem(val line: String, val macLine: String) {
    NO_PERMISSION(
        "MEKA needs the microphone to hear you. Allow it when asked, or in Settings → Apps → Meka → Permissions.",
        "MEKA needs the microphone and speech recognition to hear you. Allow both in System Settings → Privacy & Security.",
    ),
    NO_ON_DEVICE(
        "This phone can't recognise speech on the device, so MEKA won't listen: nothing is sent away to be transcribed.",
        "This Mac can't recognise English on the device yet, so MEKA won't listen: nothing is sent away to be transcribed. " +
            "Turning on Dictation in System Settings → Keyboard fetches it.",
    ),
    LANGUAGE_MISSING(
        "This phone is fetching English for speech on the device. Try again in a minute.",
        "This Mac is fetching English for speech on the device. Try again in a minute.",
    ),
    BUSY("The microphone is busy. Try again in a moment.", "The microphone is busy. Try again in a moment."),
    FAILED("Couldn't hear that. Tap the mic to try again.", "Couldn't hear that. Click the mic to try again."),
    /** "Listen when I open MEKA" found the room too loud (TalkOnOpenRules), so MEKA didn't listen. */
    TOO_NOISY("Too noisy — tap to talk", "Too noisy — click to talk"),
}

object TalkVoice {
    /** Languages in the order MEKA prefers them (Meka is in the UK). */
    val PREFERRED = listOf("en-GB", "en-IE", "en-AU", "en-US")

    /**
     * The voice MEKA speaks with: only installed voices that speak on the device (none that sends the text away),
     * English only, British first ([PREFERRED], then any other English), the highest [VoiceCandidate.quality], then by
     * name so both runs agree. Null when none fits: the engine's own default is used.
     */
    fun best(voices: List<VoiceCandidate>): VoiceCandidate? {
        val usable = voices.filter { it.installed && !it.needsNetwork && tag(it.language).startsWith("en") }
        fun rank(v: VoiceCandidate): Int = PREFERRED.indexOfFirst { tag(it) == tag(v.language) }.let { if (it < 0) PREFERRED.size else it }
        return usable.sortedWith(compareBy<VoiceCandidate>({ rank(it) }, { -it.quality }, { it.name })).firstOrNull()
    }

    /** "en_GB" and "EN-gb" read as "en-gb". */
    private fun tag(language: String) = language.replace('_', '-').lowercase()
}

/**
 * The voice orb's numbers (catalogue "Assistant · Talk"), the same on the Fold and the Mac: it breathes while idle or
 * thinking, swells with Meka's voice while listening and ripples while MEKA speaks.
 */
object TalkOrb {
    /** The quietest and loudest speech levels the recogniser reports (Android's `onRmsChanged`, in dB). */
    const val QUIET_DB = -2f
    const val LOUD_DB = 10f
    /** How much bigger the orb grows at full voice. */
    const val SWELL = 0.22f
    /** One ripple's life while MEKA speaks, and how many are out at once. */
    const val RIPPLE_MS = 1_400L
    const val RIPPLES = 3

    /** A reported level in dB as 0 (silence) … 1 (loud). */
    fun level(db: Float): Float = ((db - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0f, 1f)

    /** The shown level moves towards [target]: quickly up as Meka speaks, slowly down after (so it doesn't flicker). */
    fun smooth(shown: Float, target: Float): Float {
        val k = if (target > shown) 0.6f else 0.15f
        return (shown + (target - shown) * k).coerceIn(0f, 1f)
    }

    /** The orb's size: swelling with [level] while listening, else with the breath (0 → 1, the empty states' breath). */
    fun scale(phase: TalkPhase, level: Float, breath: Float): Float = when (phase) {
        TalkPhase.LISTENING -> 1f + SWELL * level.coerceIn(0f, 1f)
        TalkPhase.SPEAKING -> 1f
        else -> 0.92f + 0.08f * breath.coerceIn(0f, 1f)
    }

    /** Ripple [i] (0 until [RIPPLES]) at [elapsedMs] while speaking: how far out it is, 0 (at the orb) → 1 (gone). */
    fun ripple(i: Int, elapsedMs: Long): Float {
        val offset = RIPPLE_MS * i / RIPPLES
        return ((elapsedMs.coerceAtLeast(0L) + offset) % RIPPLE_MS).toFloat() / RIPPLE_MS
    }

    /** What the orb says under it (and to screen readers). */
    fun label(phase: TalkPhase): String = label(phase, mac = false)

    /** The same, worded for the Mac when [mac] ("Click to interrupt"). */
    fun label(phase: TalkPhase, mac: Boolean): String {
        val press = if (mac) "Click" else "Tap"
        return when (phase) {
            TalkPhase.LISTENING -> "Listening…"
            TalkPhase.THINKING -> "Thinking…"
            TalkPhase.DOING -> "Doing it…"
            TalkPhase.SPEAKING -> "$press to interrupt"
            TalkPhase.ENDED -> "$press the mic to talk"
        }
    }

    /**
     * The Mac's microphone level: the recogniser there reports none, so the app measures each audio buffer's loudness
     * in dB full scale (0 is the loudest a sample can be) and this reads it as 0 (quiet room) … 1 (speaking up close).
     */
    const val QUIET_DBFS = -50f
    const val LOUD_DBFS = -14f

    fun levelDbfs(db: Float): Float =
        if (db.isNaN()) 0f else ((db - QUIET_DBFS) / (LOUD_DBFS - QUIET_DBFS)).coerceIn(0f, 1f)

    /** A buffer's RMS ([rms], 0 … 1 linear) in dB full scale, never below -160 (silence). */
    fun dbfs(rms: Float): Float = if (rms <= 1e-8f || rms.isNaN()) -160f else (20.0 * kotlin.math.log10(rms.toDouble())).toFloat().coerceAtLeast(-160f)
}

/** What the Mac does with the microphone at each moment while listening ([TalkEndpoint.step]). */
enum class ListenStep {
    /** Keep listening. */
    KEEP,
    /** Meka has stopped talking: take what was heard as the question. */
    FINISH,
    /** Nothing was said: the conversation ends quietly (as [TalkFlow.silence]). */
    SILENCE,
}

/**
 * When a spoken question is over, for the Mac (non-AI, pure): Android's recogniser decides that itself, the Mac's
 * doesn't, so the app asks this a few times a second. Nothing heard for [NOTHING_MS] is silence; once words came, a
 * pause of [PAUSE_MS] (no new words) ends the question, and a question is never longer than [LONGEST_MS] (the on-device
 * recogniser's own limit is about a minute).
 */
object TalkEndpoint {
    const val PAUSE_MS = 1_500L
    const val NOTHING_MS = 8_000L
    const val LONGEST_MS = 55_000L
    /** How often the Mac asks. */
    const val TICK_MS = 100L

    /** [startedMs] when listening began, [lastWordsMs] when the transcript last changed (ignored until [heardAnything]). */
    fun step(startedMs: Long, heardAnything: Boolean, lastWordsMs: Long, nowMs: Long): ListenStep = when {
        !heardAnything -> if (nowMs - startedMs >= NOTHING_MS) ListenStep.SILENCE else ListenStep.KEEP
        nowMs - lastWordsMs >= PAUSE_MS || nowMs - startedMs >= LONGEST_MS -> ListenStep.FINISH
        else -> ListenStep.KEEP
    }
}
