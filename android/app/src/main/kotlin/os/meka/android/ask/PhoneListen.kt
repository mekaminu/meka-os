package os.meka.android.ask

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import os.meka.core.domain.ListenEngine
import os.meka.core.domain.ListenRules

/**
 * The phone's recognisers for Talk and the capture sheet's 🎙 ("the mic should just add it, fast, and understand him",
 * steps 3–4; ADR-006 addendum 2026-10-17). With signal, Google's online recogniser ([ListenEngine.ONLINE]: Google's
 * recognition service when it's installed, else the phone's default; en-GB, partial results): Meka's words go to Google
 * to be written down, which he agreed to on 2026-10-17. Without signal, Android's on-device recogniser, as before.
 * Nothing is recorded or kept by Navilife either way. [ListenRules] decides; this only asks the phone.
 */
object PhoneListen {
    private const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    private const val GOOGLE_TTS = "com.google.android.tts"

    /** A working internet connection now (validated, so a captive Wi-Fi page doesn't count as signal). */
    fun hasSignal(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun onDeviceAvailable(context: Context): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Which recogniser listens now; null: none can (no signal and no on-device English). */
    fun choose(context: Context): ListenEngine? = ListenRules.engine(
        hasSignal(context),
        SpeechRecognizer.isRecognitionAvailable(context),
        onDeviceAvailable(context),
    )

    /** Google's own recognition service, when the phone has it (Samsung's default may be its own). */
    private fun googleService(context: Context): ComponentName? = runCatching {
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }
            .firstOrNull { it.packageName == GOOGLE_APP || it.packageName == GOOGLE_TTS }
            ?.let { ComponentName(it.packageName, it.name) }
    }.getOrNull()

    fun create(context: Context, engine: ListenEngine): SpeechRecognizer = when (engine) {
        ListenEngine.ON_DEVICE -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        ListenEngine.ONLINE -> googleService(context)?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
            ?: SpeechRecognizer.createSpeechRecognizer(context)
    }

    fun intent(engine: ListenEngine): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, engine == ListenEngine.ON_DEVICE)
        // Asked for, though recognisers may keep their own; Navilife also ends it itself ([TalkEndpoint]).
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, ListenRules.END_PAUSE_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, ListenRules.END_PAUSE_MS)
    }

    /** A failure that an on-device retry can get past (no network, the server, too many asks). */
    fun networkFailure(error: Int): Boolean = error == SpeechRecognizer.ERROR_NETWORK ||
        error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT || error == SpeechRecognizer.ERROR_SERVER ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS))
}
