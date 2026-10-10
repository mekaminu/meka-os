package os.meka.android.ask

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.UiModeManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import os.meka.android.MainActivity
import os.meka.android.R
import os.meka.core.domain.TalkOnOpenRules
import os.meka.core.domain.TalkStart
import os.meka.core.domain.TalkStartRules

/**
 * Talk without tapping the mic (slice 2): where listening is clearly wanted. Opening MEKA from the launcher with
 * Bluetooth audio connected or in car mode, and the home screen's Talk widget, open Ask already listening (through
 * [MekaApplication.talkNow], as the side button does). The decision is the core's [TalkStartRules.onOpen].
 */
object TalkAutoListen {
    /** Output types that mean "something in your ears or the car's speakers", not the phone's own speaker. */
    val BLUETOOTH_TYPES = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    /** A launcher open: Android's MAIN with the LAUNCHER category (a notification or a widget brings its own intent). */
    fun fromLauncher(intent: Intent?): Boolean =
        intent != null && intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
            !intent.hasExtra(MainActivity.EXTRA_OPEN)

    /**
     * Where this launcher open should start talking from, or null (most opens: Ask waits for the mic). Slice 4: with
     * "Listen when I open MEKA" on and the microphone already allowed, any launcher open → [TalkStart.OPEN].
     */
    fun onOpen(context: Context, intent: Intent?): TalkStart? {
        val launcher = fromLauncher(intent)
        if (!launcher) return null
        return TalkStartRules.openStart(launcher, bluetoothAudio(context), carMode(context), listenOnOpen(context), micAllowed(context))
    }

    // ---- "Listen when I open MEKA" (slice 4): kept on this phone, off by default ----

    private const val PREFS = "meka.talk"
    private const val KEY_LISTEN_ON_OPEN = "listen_on_open"

    fun listenOnOpen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LISTEN_ON_OPEN, false)

    fun setListenOnOpen(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LISTEN_ON_OPEN, on).apply()
    }

    // ---- "Talk over MEKA" (voice barge-in): kept on this phone, on by default (BargeInRules.DEFAULT_ON) ----

    private const val KEY_TALK_OVER = "talk_over"

    fun talkOver(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TALK_OVER, os.meka.core.domain.BargeInRules.DEFAULT_ON)

    fun setTalkOver(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TALK_OVER, on).apply()
    }

    fun micAllowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * The room check: reads the microphone for [TalkOnOpenRules.ROOM_CHECK_MS] and returns its level
     * ([TalkOnOpenRules.roomLevel]; a number, never audio, nothing kept). Null when it couldn't be measured.
     */
    @SuppressLint("MissingPermission") // checked first
    suspend fun roomLevel(context: Context): Double? = withContext(Dispatchers.IO) {
        if (!micAllowed(context)) return@withContext null
        runCatching {
            val rate = 16_000
            val want = (rate * TalkOnOpenRules.ROOM_CHECK_MS / 1000).toInt()
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return@runCatching null
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, want * 2))
            try {
                if (rec.state != AudioRecord.STATE_INITIALIZED) return@runCatching null
                rec.startRecording()
                val buf = ShortArray(want)
                var got = 0
                while (got < want) {
                    val n = rec.read(buf, got, want - got)
                    if (n <= 0) break
                    got += n
                }
                rec.stop()
                TalkOnOpenRules.roomLevel(buf, got)
            } finally {
                rec.release()
            }
        }.getOrNull()
    }

    /** The quiet chime that marks listening (a short, soft acknowledgement tone at a low volume). */
    fun chime() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 30)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 400)
        }
    }

    private fun bluetoothAudio(context: Context): Boolean = runCatching {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in BLUETOOTH_TYPES }
    }.getOrDefault(false)

    private fun carMode(context: Context): Boolean = runCatching {
        context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_CAR
    }.getOrDefault(false)

    /** The Talk widget's tap: MEKA's main screen with "talk:WIDGET", the running one if there is one. */
    fun widgetIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, WIDGET_REQUEST_CODE,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, TalkStartRules.openValue(TalkStart.WIDGET)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Clear of the capture widget's (1, 2), the data widgets' (300+) and the news widget's (400+, 410+). */
    const val WIDGET_REQUEST_CODE = 450
}

/**
 * The home screen's Talk widget (1×1, resizable): a brass mic and "Talk to MEKA". One tap opens MEKA on Ask already
 * listening. Plain RemoteViews with no data of its own, so it never needs refreshing. Motion: the launcher's own.
 */
class TalkWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_talk).apply {
            setOnClickPendingIntent(R.id.widget_root, TalkAutoListen.widgetIntent(context))
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
