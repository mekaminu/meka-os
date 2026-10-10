package os.meka.android.ask

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import os.meka.core.domain.BargeInOutput
import os.meka.core.domain.BargeInRules

/**
 * "Talk over MEKA" on the Fold (voice barge-in): while MEKA speaks, reads the microphone's **level** only — one number
 * per 20 ms frame, handed to the main thread, where the core's [BargeInRules.step] decides; no recogniser runs, no
 * audio is kept or sent. It records through Android's voice-call path (`VOICE_COMMUNICATION`) with the phone's echo
 * canceller and noise suppressor attached where it has them, so less of MEKA's own voice reaches it; what still does,
 * the core learns as a floor.
 *
 * Opened when MEKA starts a line and closed before the recogniser listens (the two never hold the microphone at once).
 */
class TalkBargeIn(private val context: Context, private val onLevel: (Float) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var record: AudioRecord? = null
    private var reader: Thread? = null
    private var effects: List<android.media.audiofx.AudioEffect> = emptyList()
    /** Bumped by every start and stop, so a level read just before a stop is dropped. */
    @Volatile private var generation = 0

    /** Starts reading; false when the microphone couldn't be opened (then only a tap interrupts). */
    @SuppressLint("MissingPermission") // a conversation only runs once the microphone is allowed
    fun start(): Boolean {
        stop()
        if (!TalkAutoListen.micAllowed(context)) return false
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 4 * 2))
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        val fx = buildList<android.media.audiofx.AudioEffect> {
            if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId) }.getOrNull()?.let { it.setEnabled(true); add(it) }
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId) }.getOrNull()?.let { it.setEnabled(true); add(it) }
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            fx.forEach { runCatching { it.release() } }
            rec.release()
            return false
        }
        val gen = ++generation
        val t = Thread({
            val buf = ShortArray(FRAME)
            while (gen == generation) {
                val n = try { rec.read(buf, 0, FRAME) } catch (e: Exception) { -1 }
                if (n <= 0) break
                val db = BargeInRules.dbfs(buf, n)
                main.post { if (gen == generation) onLevel(db) }
            }
        }, "meka-talk-over")
        synchronized(lock) {
            record = rec
            reader = t
            effects = fx
        }
        t.start()
        return true
    }

    /** Stops reading and lets go of the microphone at once. */
    fun stop() {
        generation++
        val (rec, t, fx) = synchronized(lock) {
            Triple(record, reader, effects).also {
                record = null
                reader = null
                effects = emptyList()
            }
        }
        if (rec == null) return
        runCatching { rec.stop() } // unblocks the reader's read()
        runCatching { t?.join(JOIN_MS) }
        fx.forEach { runCatching { it.release() } }
        runCatching { rec.release() }
    }

    companion object {
        private const val RATE = 16_000
        /** 20 ms at 16 kHz. */
        private const val FRAME = 320
        private const val JOIN_MS = 100L

        private val HEADPHONE_TYPES = TalkAutoListen.BLUETOOTH_TYPES + setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )

        /** Where MEKA's voice comes out: headphones, buds or the car, else the phone's own speaker. */
        fun output(context: Context): BargeInOutput = runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            if (audio != null && audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }) BargeInOutput.HEADPHONES
            else BargeInOutput.SPEAKER
        }.getOrDefault(BargeInOutput.SPEAKER)
    }
}
