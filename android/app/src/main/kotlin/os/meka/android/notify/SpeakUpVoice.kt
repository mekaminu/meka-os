package os.meka.android.notify

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import os.meka.android.MekaApplication
import os.meka.android.ask.MekaSpeaker
import os.meka.android.ask.TalkBargeIn
import os.meka.core.domain.CapturedItem
import os.meka.core.domain.GovernorResult
import os.meka.core.domain.SpeakPlace
import os.meka.core.domain.SpeakUpRules
import os.meka.core.domain.TalkPhase

/**
 * Speaking up on the Fold (the JARVIS step, slice 1): after the governor posts, the core picks at most one notice that
 * is one of his Speaking up moments ([SpeakUpRules]) and this says it — a soft chime, the docked orb lit as speaking,
 * then the line in Ada's voice ([MekaSpeaker]) — only through headphones, earbuds or the car, or on the bedside clock
 * in the evening; never out of the phone's speaker, on a call, or while a Talk conversation is running.
 *
 * Slice 5: Android Auto (wired or wireless) counts as the car ([inCar]: the phone is in car mode, which Android Auto
 * sets while it drives the car's screen), and while she speaks up the music or podcast playing is turned down under
 * her (transient audio focus, "may duck") and comes back as she finishes, is stopped, or after [FOCUS_MAX_MS] at most.
 */
class SpeakUpVoice(private val context: Context, private val app: MekaApplication) {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val speaker by lazy { MekaSpeaker(context, app.core, main) }
    private val _speaking = MutableStateFlow(false)
    /** Ada is speaking up now: the docked orb shows Speaking. */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** The bedside clock is on screen (set by the shell). */
    @Volatile var bedsideShowing: Boolean = false

    suspend fun consider(result: GovernorResult, postedKeys: List<String>) {
        if (postedKeys.isEmpty()) return
        val pick = runCatching {
            app.core.speakUp(result, postedKeys, place(), onCall = onCall(), talking = app.talkPhase.value != TalkPhase.ENDED)
        }.getOrNull() ?: return
        say(pick.line)
    }

    /**
     * Slice 2: an urgent alert work mode just posted ([WorkAlerts]): said when it is from family or Always notify and
     * says it's urgent, under the same rules (the core decides; once per message).
     */
    fun considerUrgent(item: CapturedItem) {
        main.launch {
            val lists = runCatching { app.captures.lists.value }.getOrNull() ?: return@launch
            val pick = runCatching {
                app.core.speakUpUrgent(item, lists, place(), onCall = onCall(), talking = app.talkPhase.value != TalkPhase.ENDED)
            }.getOrNull() ?: return@launch
            say(pick.line)
        }
    }

    /**
     * Scam call fix, slice 2: an unknown caller is ringing through. Ada says [line] once, only into headphones or
     * earbuds ([os.meka.core.domain.InCallWarningRules.speak]), never out of the speaker and never over a Talk
     * conversation; no chime (the ringtone is already playing).
     */
    fun sayCallWarning(line: String) {
        val headphones = TalkBargeIn.output(context) == os.meka.core.domain.BargeInOutput.HEADPHONES
        if (!os.meka.core.domain.InCallWarningRules.speak(headphones)) return
        if (app.talkPhase.value != TalkPhase.ENDED) return
        main.launch {
            _speaking.value = true
            val id = duckOthers()
            speaker.say(line) {
                _speaking.value = false
                if (id == focusLine) giveBackFocus()
            }
        }
    }

    private fun place(): SpeakPlace {
        val minute = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
        return SpeakUpRules.place(TalkBargeIn.output(context), bedsideShowing && app.isOnScreen, minute, inCar = inCar(context))
    }

    private var focus: AudioFocusRequest? = null
    /** Bumped by every line, so an old line's safety timer never hands back a newer line's focus. */
    private var focusLine = 0

    /** Turns other audio down under Ada while she speaks up (never pauses it: the music carries on, quieter). */
    private fun duckOthers(): Int {
        val id = ++focusLine
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return id
            focus?.let { audio.abandonAudioFocusRequest(it) }
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setWillPauseWhenDucked(false)
                .build()
            audio.requestAudioFocus(request)
            focus = request
        }
        main.launch {
            delay(FOCUS_MAX_MS)
            if (id == focusLine) giveBackFocus()
        }
        return id
    }

    /** The music comes back up. */
    private fun giveBackFocus() {
        val request = focus ?: return
        focus = null
        runCatching { context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
    }

    private suspend fun say(line: String) {
        withContext(Dispatchers.Main) {
            _speaking.value = true
            val id = duckOthers()
            chime()
            main.launch {
                delay(CHIME_MS)
                speaker.say(line) {
                    _speaking.value = false
                    if (id == focusLine) giveBackFocus()
                }
            }
        }
    }

    /** "shh" while she speaks up: stop now (Talk mutes for 2 hours through the core). */
    fun stop() {
        main.launch { speaker.stop(); _speaking.value = false; giveBackFocus() }
    }

    private fun onCall(): Boolean = runCatching {
        val mode = context.getSystemService(AudioManager::class.java)?.mode
        mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE
    }.getOrDefault(false)

    private fun chime() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 35)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 160)
            main.launch { delay(400); tone.release() }
        }
    }

    companion object {
        private const val CHIME_MS = 450L
        /** The longest she keeps the music down for one speaking up (a line is one or two sentences). */
        const val FOCUS_MAX_MS = 30_000L

        /** Android Auto is driving the car's screen: the phone is in car mode. */
        fun inCar(context: Context): Boolean = runCatching {
            isCarMode(context.getSystemService(UiModeManager::class.java)?.currentModeType)
        }.getOrDefault(false)

        /** Pure: the phone's UI mode type is the car's. */
        fun isCarMode(modeType: Int?): Boolean = modeType == Configuration.UI_MODE_TYPE_CAR
    }
}
