package os.meka.android.ask

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import os.meka.core.domain.AskCard
import os.meka.core.domain.AskOutcome
import os.meka.core.domain.BargeInOutput
import os.meka.core.domain.BargeInRules
import os.meka.core.domain.BargeInState
import os.meka.core.domain.BriefFollowOnRules
import os.meka.core.domain.TalkDid
import os.meka.core.domain.TalkEffect
import os.meka.core.domain.TalkFlow
import os.meka.core.domain.TalkOnOpenRules
import os.meka.core.domain.TalkOrb
import os.meka.core.domain.TalkPhase
import os.meka.core.domain.TalkProblem
import os.meka.core.domain.TalkSession
import os.meka.core.domain.TalkStep
import os.meka.core.facade.MekaCore

/**
 * Talk to MEKA on the Fold (build plan V1, voice slice 2): runs the core's [TalkFlow] with the phone's own speech.
 *
 * Listening (the mic fix, steps 3–4, 2026-10-17; ADR-006 addendum): Google's online recogniser whenever there's signal,
 * Android's on-device one without it ([PhoneListen]; a network failure online listens again on the device); nothing is
 * recorded or kept by Navilife. Listening ends 0.6 s after his last word or sound ([TalkEndpoint]), not at the
 * recogniser's own longer end, and the time from his words ending to an "Added" is kept for Activity. Speaking uses MEKA's voice (Amazon Polly through MEKA's own server, [MekaCore.speechClip]: only
 * MEKA's own words are sent, never what Meka said), piece by piece, and falls back to `TextToSpeech` with the best
 * installed British voice that speaks on the device ([TalkVoice.best]; never a network voice) when MEKA's voice is off,
 * used up for the month, offline or slow. Every change still goes through `MekaCore.doTalk` exactly as tapping a card
 * would, and its undo bar rises. Tapping the orb while MEKA speaks stops it and listens; tapping it otherwise ends the
 * conversation. With "Talk over MEKA" on (the default, [BargeInRules]) talking over MEKA does the same: while a line is
 * being said [TalkBargeIn] reads only the microphone's level (no recogniser, nothing kept) and the core decides when
 * Meka's voice has clearly risen over MEKA's own; it is closed before the recogniser listens.
 *
 * Everything here runs on the main thread (the recogniser requires it); the speech engine's callbacks are posted back.
 */
class TalkController(
    private val context: Context,
    private val core: MekaCore,
    private val scope: CoroutineScope,
    /** An answer arrived: Ask shows it (the words and the cards, which can still be tapped). */
    private val onAnswer: (question: String, outcome: AskOutcome) -> Unit,
    /** A spoken yes did [cards]: Ask folds them away and raises one undo bar. */
    private val onDid: (did: TalkDid, cards: List<AskCard>) -> Unit,
) {
    var phase by mutableStateOf(TalkPhase.ENDED)
        private set
    /** Meka's voice level, 0 … 1, smoothed ([TalkOrb.smooth]). */
    var level by mutableFloatStateOf(0f)
        private set
    /** The live transcript while listening, then the question as heard. */
    var heard by mutableStateOf("")
        private set
    /** What MEKA last said aloud. */
    var said by mutableStateOf("")
        private set
    /** "Talk over MEKA" is on for this conversation (read from the phone's setting at each start; the orb's line). */
    var talkOver by mutableStateOf(BargeInRules.DEFAULT_ON)
        private set
    /** Why MEKA couldn't listen, shown under the orb until the next start. */
    var problem by mutableStateOf<TalkProblem?>(null)
        private set

    private var session: TalkSession = TalkSession(TalkPhase.ENDED)
    /** Bumped by every start and stop, so a late answer from an earlier conversation changes nothing. */
    private var generation = 0

    private var recognizer: SpeechRecognizer? = null
    /** Which recogniser [recognizer] is (online with signal, else on the device). */
    private var engine: os.meka.core.domain.ListenEngine? = null
    /** This listen: when it began, his last new words and voice, when his words ended (elapsed ms). */
    private var listenAt = 0L
    private var lastWordsAt = 0L
    private var lastVoiceAt: Long? = null
    private var wordsEndedAt: Long? = null
    private var endpoint: kotlinx.coroutines.Job? = null
    /** MEKA's voice (Polly through MEKA's server), else the phone's own. */
    private val speaker = MekaSpeaker(context, core, scope)
    /** Bumped by every line and every stop, so a line that was cut short doesn't move the conversation on. */
    private var utterance = 0
    /** "Talk over MEKA": the microphone's level while MEKA speaks, and the core's detector over it. */
    private val bargeIn = TalkBargeIn(context, ::overheard)

    // The orb speed pass, slice 7 ([os.meka.core.domain.HelloListenRules]): the mic open while the hello speaks.
    /** The hello's words, until [say] starts saying them. */
    private var helloLine: String? = null
    /** The hello is being said now (barge-in on the speaker is quicker meanwhile). */
    private var helloSaying = false
    /** The recogniser is open while the hello still speaks (headphones): his words stop it ([answeredOverHello]). */
    private var micOverHello = false
    /** The hello's words, taken out of what the recogniser hears this turn (its echo); null once his words are taken. */
    private var echoOf: String? = null
    /** The recogniser heard only the hello's echo after it ended once already: the next time is silence as usual. */
    private var echoRetried = false
    private var bargeState: BargeInState = BargeInRules.start()
    private var bargeOutput = BargeInOutput.SPEAKER

    val active: Boolean get() = phase != TalkPhase.ENDED

    /** Starts a conversation (the mic permission is already granted). */
    fun start() = begin(TalkFlow.start())

    /**
     * "Hello, Navilife": the orb tapped at rest. The greeting is built by the core's rules on the device
     * ([MekaCore.hello]: no AI call), said in Navilife's voice, then Talk listens as after any answer.
     */
    fun hello() {
        if (active) return
        val gen = generation
        // The orb speed pass, slice 5: the server opens its AI and voice connections while the hello plays.
        scope.launch { core.warmTalk() }
        scope.launch {
            // The orb speed pass, slice 2: this hello is timed from the tap to Navilife's voice (Settings → Speed).
            core.turnHelloBegan()
            val greeting = runCatching { core.hello() }.getOrNull() ?: return@launch
            if (gen != generation || active) return@launch
            // Slice 8: the stage raises the greeting's cards round its orb as each sentence starts.
            (context.applicationContext as? os.meka.android.MekaApplication)?.helloGreeting?.value = greeting
            helloLine = greeting.spoken // slice 7: the line said next is the hello (the mic may open with it)
            begin(TalkFlow.greet(greeting.spoken))
        }
    }

    /**
     * The orb speed pass, slice 3: keeps the hello's openers ready in Navilife's voice while this screen lives
     * ([MekaCore.warmHello]: this part of the day's and the next hour's), checked every
     * [os.meka.core.domain.HelloRules.WARM_EVERY_MS]; ones already here cost nothing. Runs until cancelled.
     */
    suspend fun keepHelloWarm() {
        while (true) {
            core.warmHello()
            kotlinx.coroutines.delay(os.meka.core.domain.HelloRules.WARM_EVERY_MS)
        }
    }

    /** Starts a conversation at [first] (a fresh one, or listening after the brief). */
    private fun begin(first: TalkStep) {
        if (active) return
        generation++
        problem = null
        speechBegan = false
        heard = ""
        said = ""
        talkOver = TalkAutoListen.talkOver(context)
        speaker.prepare()
        scope.launch { core.warmVoice() } // MEKA's common lines in its voice, fetched once
        scope.launch { core.warmTalk() } // slice 5: the server gets ready for his question (at most every 20 s)
        apply(first)
    }

    /** While set (elapsed ms), listening after the brief: the recogniser giving up on silence listens again. */
    private var afterBriefAt: Long? = null

    /**
     * The brief's Listen ran to its end (slice 7, [BriefFollowOnRules]): the quiet chime, then listening as if Talk had
     * just read the brief, for up to [BriefFollowOnRules.WINDOW_MS] ("plan my day", "more news" or a question); nothing
     * said by then → listening stops and [onLapsed] runs (the screen the brief came from comes back). The microphone is
     * already allowed (the pane checks; nothing asks).
     */
    fun startAfterBrief(onLapsed: () -> Unit) {
        if (active) return
        val gen = ++generation
        scope.launch {
            TalkAutoListen.chime()
            delay(CHIME_GAP_MS) // so the recogniser doesn't hear the chime
            if (gen != generation || active) return@launch
            begin(TalkFlow.afterBrief())
            val started = generation
            val at = android.os.SystemClock.elapsedRealtime()
            afterBriefAt = at
            delay(BriefFollowOnRules.WINDOW_MS)
            if (started != generation) return@launch
            afterBriefAt = null
            if (phase == TalkPhase.LISTENING &&
                BriefFollowOnRules.windowLapsed(at, android.os.SystemClock.elapsedRealtime(), speechBegan)
            ) {
                stop()
                onLapsed()
            }
        }
    }

    /** Speech began (or words were heard) since the last [start]: the open's listening window doesn't close. */
    private var speechBegan = false

    /** Car and parking, slice 8: the car the last thing heard named ("where's the Golf?"), for Find my car. */
    private var namedCar: String? = null

    /**
     * "Listen when I open MEKA" (slice 4): the room check first; too noisy → "Too noisy — tap to talk" under the resting
     * orb (a tap starts as usual); otherwise the quiet chime and listening, for up to [TalkOnOpenRules.WINDOW_MS]: if
     * nothing has been said by then it stops and [onLapsed] runs (Ask hands back to Today).
     */
    fun startOnOpen(onLapsed: () -> Unit) {
        if (active) return
        val gen = ++generation
        scope.launch {
            val room = TalkAutoListen.roomLevel(context)
            if (gen != generation || active) return@launch
            if (TalkOnOpenRules.tooNoisy(room)) {
                problem = TalkProblem.TOO_NOISY
                return@launch
            }
            TalkAutoListen.chime()
            delay(CHIME_GAP_MS) // so the recogniser doesn't hear the chime
            if (gen != generation || active) return@launch
            start()
            val started = generation
            val at = System.currentTimeMillis()
            delay(TalkOnOpenRules.WINDOW_MS)
            if (started == generation && phase == TalkPhase.LISTENING &&
                TalkOnOpenRules.windowLapsed(at, System.currentTimeMillis(), speechBegan)
            ) {
                stop()
                onLapsed()
            }
        }
    }

    /** The orb tapped: while MEKA speaks, stop and listen; otherwise end. */
    fun tapOrb() {
        when (phase) {
            TalkPhase.SPEAKING -> apply(TalkFlow.bargeIn(session))
            TalkPhase.ENDED -> start()
            else -> stop()
        }
    }

    /** The screen left, the app went to the background, a call: everything stops at once. */
    fun stop() {
        generation++ // also drops a room check or chime still on its way ("Listen when I open MEKA")
        afterBriefAt = null
        if (!active) return
        apply(TalkFlow.stop(session))
    }

    /** Meka said no to the microphone: say why MEKA can't listen. */
    fun refused() { problem = TalkProblem.NO_PERMISSION }

    /** A card tapped by hand while talking: it is no longer on offer aloud. */
    fun cardTapped(card: AskCard) {
        session = session.copy(pending = session.pending - card)
    }

    /** Ask left the screen for good: free the recogniser and the speech engine. */
    fun release() {
        stop()
        endpoint?.cancel()
        recognizer?.destroy()
        recognizer = null
        engine = null
        bargeIn.stop()
        speaker.release()
    }

    private companion object {
        /** The chime's length and a little more before the recogniser opens. */
        const val CHIME_GAP_MS = 220L
    }

    private fun apply(step: TalkStep) {
        session = step.session
        phase = session.phase
        PhoneListen.latch.sessionOpen = active // a switch to the phone's own speech lasts the whole conversation
        step.effects.forEach(::run)
        if (phase == TalkPhase.SPEAKING) meterVoice()
    }

    /** Reads Navilife's own voice while it speaks, for the orb ("Hello, Navilife", slice 6). */
    private var voiceMeter: kotlinx.coroutines.Job? = null

    /**
     * While Navilife speaks, the orb's level is its voice's measured loudness ([MekaSpeaker.outputLevel], followed by
     * [os.meka.core.domain.VoiceOut.follow]); 0 when there's no meter, so the orb's own syllable swell plays instead.
     */
    private fun meterVoice() {
        if (voiceMeter?.isActive == true) return
        voiceMeter = scope.launch {
            while (phase == TalkPhase.SPEAKING) {
                level = os.meka.core.domain.VoiceOut.follow(level, speaker.outputLevel())
                kotlinx.coroutines.delay(os.meka.core.domain.VoiceOut.POLL_MS)
            }
            if (phase != TalkPhase.LISTENING) level = 0f
        }
    }

    private fun run(effect: TalkEffect) {
        when (effect) {
            TalkEffect.Listen -> listen()
            is TalkEffect.Ask -> {
                heard = effect.question
                level = 0f
                val gen = generation
                val endedAt = wordsEndedAt
                val heardBy = engine
                // The orb speed pass, slice 6: the same question already asked ahead with the words heard so far.
                val early = ahead?.takeIf { it.gen == gen && os.meka.core.domain.AskAheadRules.use(it.question, effect.question) }
                if (early != null) ahead = null else dropAhead()
                waitCue(gen)
                scope.launch {
                    val onPiece: (String) -> Unit = { piece -> if (gen == generation) apply(TalkFlow.early(session, piece)) }
                    val adopted = early?.let { a ->
                        a.onPiece = onPiece
                        a.piece?.let(onPiece)
                        try {
                            a.job?.await()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            if (!isActive) throw e
                            null
                        }
                    }
                    val out = if (adopted != null) adopted else {
                        // The orb speed pass, slice 2: timed from his last word to the answer's voice (Settings → Speed).
                        core.turnTalkBegan(endedAt?.let { android.os.SystemClock.elapsedRealtime() - it } ?: 0L)
                        os.meka.android.today.HereLocation.refresh(context, core, effect.question)
                        // The companion, slice 9: the answer's first sentence comes ahead, already said; Navilife starts
                        // speaking it while the rest is still being written.
                        core.talkStreamed(effect.question, effect.history) { piece -> scope.launch { onPiece(piece) } }
                    }
                    // The mic fix, step 3: his own "add …" was done at once; how long from his words ending to Added.
                    if (endedAt != null && heardBy != null && (out as? AskOutcome.Answered)?.answer?.did?.isNotEmpty() == true) {
                        core.recordHeardTiming(android.os.SystemClock.elapsedRealtime() - endedAt, heardBy)
                    }
                    if (gen != generation) return@launch
                    onAnswer(effect.question, out)
                    apply(TalkFlow.answered(session, effect.question, out, core.todayEpochDay()))
                }
            }
            is TalkEffect.Do -> {
                val gen = generation
                scope.launch {
                    val did = core.doTalk(effect.cards)
                    onDid(did, effect.cards) // done is done: the undo bar rises even if the talk was stopped meanwhile
                    if (gen != generation) return@launch
                    apply(TalkFlow.did(session, did.done, did.lines, did.failed, core.todayEpochDay()))
                }
            }
            is TalkEffect.Speak -> {
                said = effect.text
                say(effect.text)
            }
            is TalkEffect.SpeakEarly -> {
                said = effect.text
                sayEarly(effect.text)
            }
            is TalkEffect.SpeakRest -> {
                said = listOf(said, effect.text).filter { it.isNotBlank() }.joinToString(" ")
                sayRest(effect.text)
            }
            TalkEffect.PlayMessages -> playMessages()
            is TalkEffect.ReadOut -> readOut(effect.read)
            // "Plan my day": the conversation has ended (End came first); Today opens with the planner up.
            is TalkEffect.Open -> when (effect.place) {
                os.meka.core.domain.TalkPlace.PLAN -> { (context.applicationContext as? os.meka.android.MekaApplication)?.openPlan?.value = true }
                // Done for the day, completeness 2: "shut down the day" lands on Today with Shut down the day open.
                os.meka.core.domain.TalkPlace.SHUTDOWN -> { (context.applicationContext as? os.meka.android.MekaApplication)?.openShutdown?.value = true }
                // Week board item (6) part 2: "plan my week" opens Calendar on the Board with Plan your week up.
                os.meka.core.domain.TalkPlace.PLAN_WEEK -> {
                    val app = context.applicationContext as? os.meka.android.MekaApplication
                    app?.openDestination?.value = os.meka.android.shell.ShellDestination.CALENDAR
                    app?.openPlanWeek?.value = true
                }
                // Bedside clock slice 1: "bedside clock" said to Talk opens it; ‹ Back leaves it.
                os.meka.core.domain.TalkPlace.BEDSIDE -> { (context.applicationContext as? os.meka.android.MekaApplication)?.openBedside?.value = true }
                // Car and parking, slice 1: "where did I park?" opens Ask → Life → Car with Find my car open.
                os.meka.core.domain.TalkPlace.CAR -> {
                    val app = context.applicationContext as? os.meka.android.MekaApplication
                    app?.openDestination?.value = os.meka.android.shell.ShellDestination.ASK
                    app?.openCarFind?.value = namedCar
                    app?.openCar?.value = true
                }
                // Voice notes, slice 1: "take a voice note" opens the capture sheet recording (Talk's listening has ended).
                os.meka.core.domain.TalkPlace.VOICE_NOTE -> {
                    runCatching { context.startActivity(os.meka.android.capture.CaptureIntents.voiceNote(context)) }
                }
                // Weekly reflection, slice 2: "reflect on my week" opens Review on the card's week with the reflection open.
                os.meka.core.domain.TalkPlace.REFLECT -> {
                    scope.launch { runCatching { core.openReflection() } }
                    (context.applicationContext as? os.meka.android.MekaApplication)?.openDestination?.value = os.meka.android.shell.ShellDestination.REVIEW
                }
                // Inbox autopilot, slice 7b: "clear my inbox" starts the ten-minute inbox and Needs you comes forward.
                os.meka.core.domain.TalkPlace.INBOX -> {
                    scope.launch { core.startMailMoment() }
                    (context.applicationContext as? os.meka.android.MekaApplication)?.openDestination?.value = os.meka.android.shell.ShellDestination.NEEDS_YOU
                }
            }
            // Ask knows how Navilife works, slice 3: "take me there" after a help answer opens where it lives, as the
            // answer's Take me there does (Ask's help route: a Navilife pane, or Android's own settings page).
            is TalkEffect.OpenHelp -> {
                (context.applicationContext as? os.meka.android.MekaApplication)?.openItem?.value =
                    os.meka.android.shell.OpenItem(os.meka.core.domain.SearchTarget.HELP, "help:" + effect.link.entryId)
            }
            TalkEffect.StopSpeaking -> stopSaying()
            // Speaking up, slice 2: "shh" to Ada mutes her speaking up for 2 hours on every device.
            TalkEffect.MuteSpeakingUp -> {
                scope.launch { runCatching { core.muteSpeakUp() } }
                (context.applicationContext as? os.meka.android.MekaApplication)?.speakUp?.stop()
            }
            TalkEffect.End -> {
                micOverHello = false
                echoOf = null
                helloLine = null
                dropAhead()
                waitJob?.cancel()
                scope.launch { core.turnEnded() }
                endpoint?.cancel()
                recognizer?.cancel()
                stopSaying()
                level = 0f
            }
        }
    }

    /** The tick and holding line of the question being answered now ([waitCue]). */
    private var waitJob: kotlinx.coroutines.Job? = null

    /**
     * The orb speed pass, slice 6: a quick look-up asked ahead ([askAhead]) for conversation [gen], with its first
     * sentence ([piece]) kept until the flow takes it ([onPiece]).
     */
    private class Ahead(val gen: Int, val question: String) {
        var job: kotlinx.coroutines.Deferred<AskOutcome?>? = null
        var piece: String? = null
        var onPiece: ((String) -> Unit)? = null
    }

    private var ahead: Ahead? = null

    /**
     * The orb speed pass, slice 6 ([os.meka.core.domain.AskAheadRules]): Navilife has just decided he has finished and
     * stopped the recogniser, whose final words take a moment more. A read-only quick look-up is asked now with the
     * words heard so far, exactly as the flow would ask it; when the final words say the same, that answer is used
     * (the Ask effect above), otherwise it is dropped. Nothing it brings back is said or shown until then.
     */
    private fun askAhead() {
        dropAhead()
        val words = heard
        if (words.isBlank() || phase != TalkPhase.LISTENING) return
        if (!os.meka.core.domain.AskAheadRules.worthIt(words, session.conversation.history.map { it.question })) return
        val named = os.meka.core.domain.ParkingRules.talkCar(words, core.carsView.value)
        val ask = TalkFlow.heard(session, named?.utterance ?: words, os.meka.core.domain.TalkOpenRules.FOLD)
            .effects.singleOrNull() as? TalkEffect.Ask ?: return
        val endedAt = wordsEndedAt
        val a = Ahead(generation, ask.question)
        a.job = scope.async {
            // A failure here never reaches the screen: null, and the final words are asked afresh.
            try {
                core.turnTalkBegan(endedAt?.let { android.os.SystemClock.elapsedRealtime() - it } ?: 0L)
                os.meka.android.today.HereLocation.refresh(context, core, ask.question)
                core.talkStreamed(ask.question, ask.history) { piece ->
                    scope.launch {
                        a.piece = piece
                        a.onPiece?.invoke(piece)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        ahead = a
    }

    /** A question asked ahead that the final words didn't match (or the conversation moved on): cancelled, unsaid. */
    private fun dropAhead() {
        ahead?.job?.cancel()
        ahead = null
    }

    /**
     * The orb speed pass, slice 4: while the answer to the question of conversation [gen] is on its way (the orb already
     * shows Thinking), a soft tick after [os.meka.core.domain.AnswerWaitRules.CUE_AFTER_MS] and, still nothing after
     * [os.meka.core.domain.AnswerWaitRules.HOLD_AFTER_MS], a holding line in Navilife's voice from memory
     * ([MekaCore.answerHoldClip]: "One sec, Meka…"), which the answer then follows. Nothing once the answer has begun.
     */
    private fun waitCue(gen: Int) {
        waitJob?.cancel()
        val sent = android.os.SystemClock.elapsedRealtime()
        fun started() = gen != generation || phase != TalkPhase.THINKING || session.early != null
        waitJob = scope.launch {
            delay(os.meka.core.domain.AnswerWaitRules.CUE_AFTER_MS)
            if (!os.meka.core.domain.AnswerWaitRules.cues(android.os.SystemClock.elapsedRealtime() - sent, started())) return@launch
            TalkAutoListen.thinkingTick()
            delay(os.meka.core.domain.AnswerWaitRules.HOLD_AFTER_MS - os.meka.core.domain.AnswerWaitRules.CUE_AFTER_MS)
            if (!os.meka.core.domain.AnswerWaitRules.holds(android.os.SystemClock.elapsedRealtime() - sent, started(), held = false)) return@launch
            val clip = core.answerHoldClip() ?: return@launch
            if (!started()) speaker.holdOn(clip)
        }
    }

    // ---- Listening (online with signal, else on the device: [PhoneListen]) ----

    private fun fail(p: TalkProblem) {
        endpoint?.cancel()
        problem = p
        generation++
        apply(TalkFlow.stop(session))
    }

    private fun listen(force: os.meka.core.domain.ListenEngine? = null) {
        val want = force ?: PhoneListen.choose(context) ?: return fail(TalkProblem.NO_ON_DEVICE)
        if (recognizer == null || engine != want) {
            recognizer?.destroy()
            recognizer = try {
                PhoneListen.create(context, want).also { it.setRecognitionListener(listener) }
            } catch (e: Exception) {
                null
            }
            engine = want
        }
        val r = recognizer ?: return fail(TalkProblem.FAILED)
        dropAhead()
        // The orb speed pass, slice 5: he's about to ask something; the server warms again if its last was a while ago.
        scope.launch { core.warmTalk() }
        heard = ""
        level = 0f
        val now = android.os.SystemClock.elapsedRealtime()
        listenAt = now
        lastWordsAt = now
        lastVoiceAt = null
        wordsEndedAt = null
        try {
            r.startListening(PhoneListen.intent(want))
        } catch (e: Exception) {
            return fail(TalkProblem.FAILED)
        }
        // Step 3: Navilife ends it 0.6 s after his last word or sound rather than waiting for the recogniser's own end.
        endpoint?.cancel()
        val gen = generation
        endpoint = scope.launch {
            while (true) {
                delay(os.meka.core.domain.TalkEndpoint.TICK_MS)
                if (gen != generation) return@launch
                if (micOverHello) continue // slice 7: the hello is still speaking; the silence timer starts when it ends
                if (phase != TalkPhase.LISTENING) return@launch
                val t = android.os.SystemClock.elapsedRealtime()
                val step = os.meka.core.domain.TalkEndpoint.step(
                    listenAt, heard.isNotBlank(), os.meka.core.domain.ListenRules.lastSound(lastWordsAt, lastVoiceAt), t,
                )
                if (step == os.meka.core.domain.ListenStep.FINISH) {
                    if (wordsEndedAt == null) wordsEndedAt = t
                    askAhead() // slice 6: a quick look-up goes now, while the final words are on their way
                    runCatching { recognizer?.stopListening() } // the words come in onResults
                    return@launch
                }
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { speechBegan = true }
        override fun onRmsChanged(rmsdB: Float) {
            if (phase != TalkPhase.LISTENING) return
            val l = TalkOrb.level(rmsdB)
            if (os.meka.core.domain.ListenRules.isVoice(l)) lastVoiceAt = android.os.SystemClock.elapsedRealtime()
            level = TalkOrb.smooth(level, l)
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            level = 0f
            if (wordsEndedAt == null) wordsEndedAt = android.os.SystemClock.elapsedRealtime()
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (phase != TalkPhase.LISTENING && !micOverHello) return
            val raw = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() } ?: return
            val echo = echoOf
            if (micOverHello) {
                // Slice 7: the hello is still speaking; two words of his own (its echo taken out) stop it.
                if (!os.meka.core.domain.HelloListenRules.isHim(raw, echo.orEmpty(), playing = true)) return
                answeredOverHello()
            }
            val text = if (echo != null) os.meka.core.domain.HelloListenRules.his(raw, echo) else raw
            if (text.isBlank()) return
            if (text != heard) lastWordsAt = android.os.SystemClock.elapsedRealtime()
            heard = text
            speechBegan = true
        }

        override fun onResults(results: Bundle?) {
            if (phase != TalkPhase.LISTENING && !micOverHello) return
            endpoint?.cancel()
            val raw = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val echo = echoOf
            if (echo != null) {
                if (micOverHello) {
                    // Slice 7: the recogniser stopped by itself while the hello spoke: his answer, or open it again.
                    if (os.meka.core.domain.HelloListenRules.isHim(raw, echo, playing = true)) answeredOverHello() else return listen()
                } else if (os.meka.core.domain.HelloListenRules.onlyEcho(raw, echo) && !echoRetried) {
                    echoRetried = true // it heard only the hello: listen again once rather than end
                    return listen()
                }
            }
            if (wordsEndedAt == null) wordsEndedAt = android.os.SystemClock.elapsedRealtime()
            val text = if (echo != null) os.meka.core.domain.HelloListenRules.his(raw, echo) else raw
            if (engine == os.meka.core.domain.ListenEngine.ONLINE && text.isNotBlank()) PhoneListen.onlineWorked()
            took(text.ifBlank { heard })
        }

        /** What he said goes to the flow (the final result, or the words so far when the recogniser gave up after them). */
        private fun took(text: String) {
            echoOf = null
            if (text.isNotBlank()) heard = text
            // Car and parking, slice 8: "where's the Golf?" is "where's the car?" for the car it names.
            val named = os.meka.core.domain.ParkingRules.talkCar(text, core.carsView.value)
            namedCar = named?.carId
            apply(TalkFlow.heard(session, named?.utterance ?: text, os.meka.core.domain.TalkOpenRules.FOLD))
            dropAhead() // slice 6: not taken by the question asked now (the Ask effect took it, if it was the same)
        }

        override fun onError(error: Int) {
            if (micOverHello) {
                // Slice 7: while the hello speaks, silence or no match opens it again; anything else leaves it to the
                // hello's end, which listens as before.
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) return listen()
                endpoint?.cancel()
                micOverHello = false
                echoOf = null
                return
            }
            if (phase != TalkPhase.LISTENING) return // a cancel after stopping reports ERROR_CLIENT
            endpoint?.cancel()
            // Words already heard (Navilife ended it, then the recogniser said no match or lost the network): take them.
            if (heard.isNotBlank() && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                if (wordsEndedAt == null) wordsEndedAt = android.os.SystemClock.elapsedRealtime()
                return took(heard)
            }
            // Step 4: Google's online speech failed on the network: listen again with the phone's own, once.
            val current = engine
            if (current == os.meka.core.domain.ListenEngine.ONLINE && PhoneListen.networkFailure(error)) PhoneListen.onlineFailed(error)
            if (current != null) {
                os.meka.core.domain.ListenRules.fallback(current, PhoneListen.networkFailure(error), PhoneListen.onDeviceAvailable(context))
                    ?.let { return listen(force = it) }
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // After the brief the window is the whole 8 s: the recogniser's own earlier timeout listens again.
                    val at = afterBriefAt
                    if (at != null && BriefFollowOnRules.listenAgain(at, android.os.SystemClock.elapsedRealtime(), speechBegan)) listen()
                    else apply(TalkFlow.silence(session))
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail(TalkProblem.NO_PERMISSION)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> fail(TalkProblem.BUSY)
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    // Ask the phone to fetch English for on-device speech (Android 13+), and say so.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        try { recognizer?.triggerModelDownload(PhoneListen.intent(os.meka.core.domain.ListenEngine.ON_DEVICE)) } catch (_: Exception) {}
                    }
                    fail(TalkProblem.LANGUAGE_MISSING)
                }
                else -> fail(TalkProblem.FAILED)
            }
        }
    }

    // ---- Speaking: MEKA's voice from MEKA's own server, else the phone's own ([MekaSpeaker]) ----

    /** Says [text] in MEKA's voice (else the phone's own); the conversation moves on once it has been said. */
    private fun say(text: String) {
        val id = ++utterance
        val hello = helloLine
        helloLine = null
        helloSaying = hello != null
        speaker.say(text) { finishedSaying(id) }
        // Slice 7: with headphones the recogniser opens with the hello, so he can just start talking.
        if (hello != null && os.meka.core.domain.HelloListenRules.opensMic(talkOver, TalkBargeIn.output(context))) listenOverHello(hello)
        else listenOver()
    }

    /**
     * The orb speed pass, slice 7: the recogniser opens while the hello is still speaking (headphones only: on the
     * speaker it would mostly hear Navilife). What it catches of the hello is taken out; his own words stop the hello.
     */
    private fun listenOverHello(hello: String) {
        echoOf = hello
        echoRetried = false
        micOverHello = true
        listen()
    }

    /** He answered the hello while it was still speaking: it stops, and the recogniser already has his words. */
    private fun answeredOverHello() {
        micOverHello = false
        helloSaying = false
        val now = android.os.SystemClock.elapsedRealtime()
        listenAt = now
        lastWordsAt = now
        scope.launch { core.turnEnded() } // the hello's trace is kept (Settings → Speed)
        apply(TalkFlow.overHello(session))
    }

    /** The companion, slice 9: the answer's first sentence, said while the rest comes; its end moves nothing on. */
    private fun sayEarly(text: String) {
        ++utterance
        speaker.say(text)
        listenOver()
    }

    /** The rest of the answer, once the early piece is over; then Talk listens again (barge-in keeps watching). */
    private fun sayRest(text: String) {
        val id = ++utterance
        speaker.follow(text) { finishedSaying(id) }
    }

    /**
     * "Play my messages" (call assistant polish 8c): the voice messages in the summary this phone shows (its family
     * list and contacts name the callers, as in Needs you), each introduced in MEKA's voice, then the caller's own
     * recording from MEKA's server (played from memory) or their words; then MEKA listens again.
     */
    private fun playMessages() {
        val app = context.applicationContext as os.meka.android.MekaApplication
        val summary = core.afterWork.value.withLists(app.captures.lists.value, os.meka.android.work.CallerLookup.names(context))
        val playlist = core.voicePlaylistOf(summary)
        said = playlist.steps.filterIsInstance<os.meka.core.domain.PlayStep.Say>().firstOrNull()?.text.orEmpty()
        val id = ++utterance
        speaker.playlist(playlist.steps, { core.voiceMessageAudio(it) }) { finishedSaying(id) }
        listenOver()
    }

    /**
     * "Read my brief", "the headlines", "Barça news": the words from the brief and the News place this phone shows,
     * said as a reading (short pieces asked for ahead, so MEKA's voice holds throughout); then MEKA listens again.
     * Headlines are only ever read out, never acted on (ADR-006).
     */
    private fun readOut(read: os.meka.core.domain.ReadOut) {
        said = os.meka.core.domain.ReadOutRules.showing(read)
        if (read.kind != os.meka.core.domain.ReadOut.Kind.BRIEF) return readText(core.talkReadOut(read))
        // The brief as MEKA's AI writes it from the facts (at most 6 s, else MEKA's own); stopped meanwhile, nothing is said.
        val gen = generation
        scope.launch {
            val text = core.briefScript()
            if (gen == generation) readText(text)
        }
    }

    private fun readText(text: String) {
        val id = ++utterance
        speaker.say(text, reading = true) { finishedSaying(id) }
        listenOver()
    }

    /** Stops whatever is being said, in either voice. */
    private fun stopSaying() {
        utterance++
        helloSaying = false
        micOverHello = false // a tap or barge-in during the hello: Talk listens afresh (the echo is still taken out)
        bargeIn.stop()
        speaker.stop()
    }

    /** The line [id] finished (or couldn't be said): listen again, unless it was cut short since. */
    private fun finishedSaying(id: Int) {
        if (id != utterance || phase != TalkPhase.SPEAKING) return
        helloSaying = false
        bargeIn.stop() // let go of the microphone before the recogniser takes it
        scope.launch { core.turnEnded() } // the turn's trace is kept (Settings → Speed)
        if (micOverHello) {
            // Slice 7: the hello ended with the recogniser already open: keep listening; the silence timer starts now.
            micOverHello = false
            val now = android.os.SystemClock.elapsedRealtime()
            listenAt = now
            lastWordsAt = now
            return apply(TalkFlow.spokeListening(session))
        }
        apply(TalkFlow.spoke(session))
    }

    // ---- Talking over MEKA (voice barge-in) ----

    /** A line has started: with "Talk over MEKA" on, watch the microphone's level while it is said. */
    private fun listenOver() {
        if (!talkOver) return
        bargeState = BargeInRules.start()
        bargeOutput = TalkBargeIn.output(context)
        bargeIn.start()
    }

    /** One frame's level while MEKA speaks: the core decides whether Meka is talking over it. */
    private fun overheard(db: Float) {
        if (phase != TalkPhase.SPEAKING) return
        // Slice 7: quicker on the speaker while the hello plays (it asks him a question).
        val tuning = os.meka.core.domain.HelloListenRules.tuning(bargeOutput, helloSaying)
        val step = BargeInRules.stepWith(bargeState, db, speaker.audible, tuning, android.os.SystemClock.elapsedRealtime())
        bargeState = step.state
        if (step.interrupt) apply(TalkFlow.bargeIn(session)) // stops MEKA, lets go of the microphone, listens
    }
}
