package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.core.domain.VoiceRecordingRules
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.transcribe.TranscribeClient
import software.amazon.awssdk.services.transcribe.model.LanguageCode
import software.amazon.awssdk.services.transcribe.model.MediaFormat
import software.amazon.awssdk.services.transcribe.model.TranscriptionJobStatus
import java.util.UUID

/*
 * Better transcripts for callers' messages (call assistant polish 8d i, Meka 2026-10-09: his test message came back
 * from Twilio as "Of the origin."). Once the caller's recording is kept in MEKA's own bucket (8c), Amazon Transcribe in
 * MEKA's own AWS account (en-GB, batch) transcribes it there: the input is the kept recording, the output goes to the
 * same private bucket under voice/ (MEKA's KMS key), is read once and deleted at once, and the job itself is deleted
 * too. Twilio's words are the fallback when Transcribe fails or hears nothing. The words are display only (ADR-006).
 */

/** Turns a kept recording into words. */
fun interface Transcriber {
    /** The words in the recording at [mediaKey] (a [VoiceRecordingRules.key]), or null when it couldn't be had. Blocks. */
    fun transcribe(mediaKey: String): String?
}

/** The few Transcribe calls MEKA makes, so the polling and clean-up are testable without AWS. */
interface TranscribeJobs {
    fun start(job: String, mediaUri: String, outputBucket: String, outputKey: String)
    /** The job's state: "IN_PROGRESS", "QUEUED", "COMPLETED" or "FAILED". */
    fun status(job: String): String
    fun delete(job: String)
}

object Transcripts {
    /** A short message is usually done within a minute; past this, Twilio's words stand. */
    const val DEADLINE_MS = 5 * 60_000L
    const val POLL_MS = 4_000L
    const val JOB_PREFIX = "meka-voice-"

    /** Where Transcribe writes its result for the recording at [mediaKey]: beside it, under voice/ (the 30-day backstop). */
    fun outputKey(mediaKey: String): String {
        require(mediaKey.startsWith(VoiceRecordingRules.PREFIX) && mediaKey.endsWith(".mp3"))
        return mediaKey.removeSuffix(".mp3") + ".transcript.json"
    }

    /** The transcript in Transcribe's result JSON (`results.transcripts[0].transcript`); null when absent or blank. */
    fun text(json: String): String? = runCatching {
        val results = Json.parseToJsonElement(json) as JsonObject
        val transcripts = (results["results"] as JsonObject)["transcripts"] as JsonArray
        transcripts.joinToString(" ") { ((it as JsonObject)["transcript"])!!.jsonPrimitive.content }
    }.getOrNull()?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Amazon Transcribe on MEKA's own bucket: starts a job on the kept recording, waits for it (at most
 * [Transcripts.DEADLINE_MS]), reads the result through [store] and then deletes the result and the job, whatever
 * happened. Nothing leaves MEKA's AWS account.
 */
class BatchTranscriber(
    private val jobs: TranscribeJobs,
    private val bucket: String,
    private val store: RecordingStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val newJobName: () -> String = { Transcripts.JOB_PREFIX + UUID.randomUUID() },
) : Transcriber {
    override fun transcribe(mediaKey: String): String? {
        val outKey = Transcripts.outputKey(mediaKey)
        val job = newJobName()
        var started = false
        try {
            jobs.start(job, "s3://$bucket/$mediaKey", bucket, outKey)
            started = true
            val deadline = now() + Transcripts.DEADLINE_MS
            while (true) {
                when (jobs.status(job)) {
                    "COMPLETED" -> break
                    "FAILED" -> return null
                }
                if (now() >= deadline) return null
                sleep(Transcripts.POLL_MS)
            }
            return store.get(outKey)?.decodeToString()?.let(Transcripts::text)
        } finally {
            // Read once, then gone: the words live on only in the summary.
            runCatching { store.delete(outKey) }
            if (started) runCatching { jobs.delete(job) }
        }
    }
}

/** The AWS side of [TranscribeJobs]: en-GB, MP3, output encrypted with MEKA's KMS key when one is given. */
class AwsTranscribeJobs(private val kmsKeyId: String?) : TranscribeJobs {
    private val client = TranscribeClient.builder().httpClient(UrlConnectionHttpClient.create()).build()

    override fun start(job: String, mediaUri: String, outputBucket: String, outputKey: String) {
        client.startTranscriptionJob { r ->
            r.transcriptionJobName(job)
                .languageCode(LanguageCode.EN_GB)
                .mediaFormat(MediaFormat.MP3)
                .media { it.mediaFileUri(mediaUri) }
                .outputBucketName(outputBucket)
                .outputKey(outputKey)
            kmsKeyId?.let { r.outputEncryptionKMSKeyId(it) }
        }
    }

    override fun status(job: String): String =
        client.getTranscriptionJob { it.transcriptionJobName(job) }.transcriptionJob().transcriptionJobStatus().let {
            if (it == TranscriptionJobStatus.UNKNOWN_TO_SDK_VERSION) "IN_PROGRESS" else it.toString()
        }

    override fun delete(job: String) {
        client.deleteTranscriptionJob { it.transcriptionJobName(job) }
    }
}
