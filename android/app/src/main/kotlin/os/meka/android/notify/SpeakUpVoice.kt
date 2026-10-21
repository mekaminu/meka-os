package os.meka.android.notify

import android.content.Context
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
import os.meka.core.domain.GovernorResult
import os.meka.core.domain.SpeakUpRules
import os.meka.core.domain.TalkPhase

/**
 * Speaking up on the Fold (the JARVIS step, slice 1): after the governor posts, the core picks at most one notice that
 * is one of his Speaking up moments ([SpeakUpRules]) and this says it — a soft chime, the docked orb lit as speaking,
 * then the line in Ada's voice ([MekaSpeaker]) — only through headphones, earbuds or the car, or on the bedside clock
 * in the evening; never out of the phone's speaker, on a call, or while a Talk conversation is running.
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
        val minute = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }
        val place = SpeakUpRules.place(TalkBargeIn.output(context), bedsideShowing && app.isOnScreen, minute)
        val pick = runCatching {
            app.core.speakUp(result, postedKeys, place, onCall = onCall(), talking = app.talkPhase.value != TalkPhase.ENDED)
        }.getOrNull() ?: return
        withContext(Dispatchers.Main) {
            _speaking.value = true
            chime()
            main.launch {
                delay(CHIME_MS)
                speaker.say(pick.line) { _speaking.value = false }
            }
        }
    }

    /** "shh" while she speaks up: stop now (Talk mutes for 2 hours through the core). */
    fun stop() {
        main.launch { speaker.stop(); _speaking.value = false }
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

    private companion object {
        const val CHIME_MS = 450L
    }
}
