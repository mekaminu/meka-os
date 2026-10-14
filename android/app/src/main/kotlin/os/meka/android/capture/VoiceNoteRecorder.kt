package os.meka.android.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import os.meka.core.domain.VoiceNoteRules
import java.io.File
import java.io.OutputStream
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Voice notes into actions, slice 1: records one voice note on the Fold and turns it into words **on the phone**.
 *
 * One microphone stream (16 kHz mono) feeds two things at once on a background thread: an AAC encoder writing the
 * recording to [file] (an .m4a, kept by [VoiceNoteStore]) and, on Android 13+, Android's on-device recogniser through a
 * pipe in a segmented session ([RecognizerIntent.EXTRA_AUDIO_SOURCE] + [RecognizerIntent.EXTRA_SEGMENTED_SESSION]), so
 * a five-minute ramble is written down as it goes and nothing leaves the phone. If the recogniser can't run (none on
 * the device, Android 12, it fails part way) the recording carries on regardless and the note says so.
 *
 * Pause keeps the microphone open but drops what it hears (neither kept nor written down), so Resume is instant.
 * Recording stops by itself at [VoiceNoteRules.MAX_MS]. Call everything from the main thread.
 */
class VoiceNoteRecorder(private val context: Context, private val file: File) {
    /** What the recorder tells the sheet (always on the main thread). */
    interface Listener {
        /** Recorded time (pauses not counted) and the voice's level, 0..1, about 20 times a second. */
        fun onProgress(elapsedMs: Long, level: Float)
        /** Recording stopped by itself at the length limit. */
        fun onLimit()
        /** The recording and the words are done: what was heard (segments), whether the recogniser failed. */
        fun onFinished(segments: List<String>, recognizerFailed: Boolean, recordingOk: Boolean, durationMs: Long)
    }

    private val main = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var recordedSamples = 0L
    private var thread: Thread? = null
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var pipeOut: OutputStream? = null
    @Volatile private var recognizerFailed = false
    @Volatile private var recognizerDone = false
    @Volatile private var recordingOk = true
    @Volatile private var recordingDone = false
    private val segments = mutableListOf<String>()
    private var finished = false

    val isPaused: Boolean get() = paused

    companion object {
        private const val RATE = 16_000
        private const val BIT_RATE = 32_000
        /** How long the words may take after the microphone stops before the note is kept with what was heard. */
        private const val WORDS_WAIT_MS = 8_000L

        fun micAllowed(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** The phone can turn a recording into words on itself while it records (Android 13+, an on-device recogniser). */
        fun canTranscribe(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    }

    /** Starts recording; false when the microphone can't be opened. */
    @SuppressLint("MissingPermission") // checked by the caller: micAllowed
    fun start(listener: Listener): Boolean {
        if (running || !micAllowed(context)) return false
        this.listener = listener
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
        }.getOrNull() ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return false }
        val writer = runCatching { AacFileWriter(file, RATE, BIT_RATE) }.getOrNull()
        if (writer == null) { record.release(); return false }
        startRecognizer()
        running = true
        runCatching { record.startRecording() }.onFailure {
            running = false
            record.release(); writer.abandon(); stopRecognizer(); return false
        }
        thread = Thread({ loop(record, writer) }, "voice-note").apply { start() }
        return true
    }

    fun pause() { paused = true }
    fun resume() { paused = false }

    /** Stops recording; [Listener.onFinished] follows once the words are in (or [WORDS_WAIT_MS] has passed). */
    fun stop() {
        if (!running) return
        running = false
    }

    /** Stops and throws the recording away (Cancel). No [Listener.onFinished]. */
    fun cancel() {
        listener = null
        running = false
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }
        recognizer = null
        runCatching { pipeOut?.close() }
        pipeOut = null
    }

    private fun elapsedMs(): Long = recordedSamples * 1000L / RATE

    private fun loop(record: AudioRecord, writer: AacFileWriter) {
        val buf = ShortArray(RATE / 20) // 50 ms
        val bytes = ByteArray(buf.size * 2)
        var lastTick = 0L
        try {
            while (running) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) { if (n < 0) break else continue }
                var sum = 0.0
                for (i in 0 until n) {
                    val s = buf[i].toInt()
                    sum += (s * s).toDouble()
                    bytes[2 * i] = (s and 0xff).toByte()
                    bytes[2 * i + 1] = ((s shr 8) and 0xff).toByte()
                }
                if (!paused) {
                    runCatching { writer.write(bytes, n * 2) }.onFailure { recordingOk = false }
                    pipeOut?.let { out -> runCatching { out.write(bytes, 0, n * 2) }.onFailure { recognizerFailed = true; pipeOut = null } }
                    recordedSamples += n
                }
                val rms = sqrt(sum / n)
                val level = if (paused) 0f else ((20 * log10(rms.coerceAtLeast(1.0)) - 30.0) / 50.0).coerceIn(0.0, 1.0).toFloat()
                val now = SystemClock.elapsedRealtime()
                if (now - lastTick >= 50) {
                    lastTick = now
                    val ms = elapsedMs()
                    main.post { listener?.onProgress(ms, level) }
                }
                if (VoiceNoteRules.atLimit(elapsedMs())) {
                    running = false
                    main.post { listener?.onLimit() }
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            if (!writer.finish()) recordingOk = false
            main.post { endWords() }
        }
    }

    // ---- the words, on the phone ----

    private fun startRecognizer() {
        if (Build.VERSION.SDK_INT < 33 || !canTranscribe(context)) { recognizerFailed = true; recognizerDone = true; return }
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            r.setRecognitionListener(words)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
            r.startListening(intent)
            // The recogniser has its own copy of the read end now.
            runCatching { pipe[0].close() }
            pipeOut = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            recognizer = r
        } catch (e: Exception) {
            recognizerFailed = true
            recognizerDone = true
        }
    }

    private fun stopRecognizer() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        runCatching { pipeOut?.close() }
        pipeOut = null
    }

    private val words = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onSegmentResults(segmentResults: Bundle) {
            segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { segments += it }
        }
        override fun onEndOfSegmentedSession() { recognitionOver() }
        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { segments += it }
            recognitionOver()
        }
        override fun onError(error: Int) {
            // No match / silence at the very end isn't a failure: what was heard stands.
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) recognizerFailed = true
            recognitionOver()
        }
    }

    private fun recognitionOver() {
        recognizerDone = true
        if (recordingDone) deliver()
    }

    /** The microphone has stopped: end the recogniser's audio and wait (briefly) for its last words. */
    private fun endWords() {
        recordingDone = true
        runCatching { pipeOut?.close() }
        pipeOut = null
        if (recognizerDone || recognizer == null) { deliver(); return }
        main.postDelayed({ deliver() }, WORDS_WAIT_MS)
    }

    private fun deliver() {
        if (finished) return
        finished = true
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
        val l = listener ?: return
        listener = null
        l.onFinished(segments.toList(), recognizerFailed, recordingOk && file.exists() && file.length() > 0, elapsedMs())
    }
}

/**
 * Writes 16-bit mono PCM to an AAC-LC .m4a (MediaCodec + MediaMuxer): about 240 KB a minute at 32 kbit/s. Used on the
 * recorder's thread only.
 */
private class AacFileWriter(private val file: File, private val rate: Int, bitRate: Int) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    private var samples = 0L
    private var wroteAny = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            runCatching { codec.release() }
            runCatching { muxer.release() }
            file.delete()
            throw e
        }
    }

    private fun ptsUs(): Long = samples * 1_000_000L / rate

    fun write(pcm: ByteArray, length: Int) {
        var off = 0
        while (off < length) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val input = codec.getInputBuffer(idx) ?: continue
                input.clear()
                val n = minOf(input.remaining(), length - off) and 1.inv() // whole samples
                input.put(pcm, off, n)
                codec.queueInputBuffer(idx, 0, n, ptsUs(), 0)
                samples += n / 2
                off += n
            }
            drain(false)
        }
    }

    private fun drain(end: Boolean) {
        var waits = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                // At the end, wait up to about two seconds for the encoder's last frames.
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || ++waits > 200) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                idx >= 0 -> {
                    val out = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started && out != null) {
                        out.position(info.offset); out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                        wroteAny = true
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Ends the stream and closes the file; false when nothing usable was written. */
    fun finish(): Boolean {
        val ended = runCatching {
            var queued = false
            for (i in 0 until 50) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) { codec.queueInputBuffer(idx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM); queued = true; break }
                drain(false)
            }
            if (queued) drain(true)
        }.isSuccess
        runCatching { codec.stop() }
        runCatching { codec.release() }
        val stopped = started && runCatching { muxer.stop() }.isSuccess
        runCatching { muxer.release() }
        if (!stopped || !wroteAny) file.delete()
        return ended && stopped && wroteAny && file.exists()
    }

    /** Gives up before anything was recorded. */
    fun abandon() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.release() }
        file.delete()
    }
}
