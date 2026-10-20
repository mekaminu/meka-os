package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/**
 * Read the full email inside Navilife (2026-10-20), on the wire: an app asks `POST /v1/mail/message` for one email by
 * Navilife's own handle — a "From your mail" card's id ([Request.ITEM]) or a tidy's id and the email's key
 * ([Request.TIDIED]) — and whether remote pictures may be shown. The server reads it from the provider at that moment
 * and answers with the email made safe (its HTML already sanitised). Nothing of it is stored or logged on either side.
 * States: ok · gone · unknown · reconnect · failed (the domain's `MailMessageAnswer`).
 */
object MailMessageCodec {
    const val PATH = "/v1/mail/message"
    private const val MAX_ID = 80
    const val MAX_HTML = 750_000
    const val MAX_TEXT = 200_000
    private const val MAX_PEOPLE = 50
    private const val MAX_ATTACHMENTS = 40
    private const val MAX_LINE = 400

    private val json = Json { ignoreUnknownKeys = true }

    data class Request(val kind: String, val id: String, val batch: String? = null, val images: Boolean = false) {
        companion object {
            const val ITEM = "item"
            const val TIDIED = "tidied"
        }
    }

    data class Person(val name: String?, val address: String?)
    data class Attachment(val name: String, val bytes: Long, val type: String?)

    data class Response(
        val state: String,
        val from: Person = Person(null, null),
        val to: List<Person> = emptyList(),
        val cc: List<Person> = emptyList(),
        val atMs: Long = 0,
        val subject: String? = null,
        val html: String? = null,
        val text: String? = null,
        val attachments: List<Attachment> = emptyList(),
        val blockedImages: Int = 0,
    ) {
        // The email's words never go into a log line by accident.
        override fun toString() = "MailMessageCodec.Response(state=$state, attachments=${attachments.size})"
    }

    private fun handle(s: String?): String? = s?.trim()?.takeIf { it.length in 3..MAX_ID && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }

    fun encodeRequest(r: Request): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("kind", r.kind)
        put("id", r.id)
        r.batch?.let { put("batch", it) }
        put("images", r.images)
    }.toString()

    fun decodeRequest(body: String): Request = wrap("mail message request") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val kind = str(o, "kind")?.takeIf { it == Request.ITEM || it == Request.TIDIED } ?: throw IllegalArgumentException("kind")
        val id = handle(str(o, "id")) ?: throw IllegalArgumentException("id")
        val batch = handle(str(o, "batch"))
        if (kind == Request.TIDIED && batch == null) throw IllegalArgumentException("batch")
        Request(kind, id, batch, (o["images"] as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    fun encodeResponse(r: Response): String = buildJsonObject {
        put("w", WireCodec.VERSION)
        put("state", r.state)
        if (r.state == "ok") {
            putPerson("from", r.from)
            putJsonArray("to") { r.to.take(MAX_PEOPLE).forEach { p -> addJsonObject { p.name?.let { put("n", it.take(MAX_LINE)) }; p.address?.let { put("a", it.take(MAX_LINE)) } } } }
            putJsonArray("cc") { r.cc.take(MAX_PEOPLE).forEach { p -> addJsonObject { p.name?.let { put("n", it.take(MAX_LINE)) }; p.address?.let { put("a", it.take(MAX_LINE)) } } } }
            put("at", r.atMs)
            r.subject?.let { put("subject", it.take(MAX_LINE * 2)) }
            r.html?.takeIf { it.length <= MAX_HTML }?.let { put("html", it) }
            r.text?.let { put("text", it.take(MAX_TEXT)) }
            putJsonArray("files") {
                r.attachments.take(MAX_ATTACHMENTS).forEach { a ->
                    addJsonObject { put("name", a.name.take(MAX_LINE)); put("bytes", a.bytes.coerceAtLeast(0)); a.type?.let { put("type", it.take(100)) } }
                }
            }
            put("blocked", r.blockedImages.coerceIn(0, 9_999))
        }
    }.toString()

    private fun kotlinx.serialization.json.JsonObjectBuilder.putPerson(k: String, p: Person) {
        put(k, buildJsonObject { p.name?.let { put("n", it.take(MAX_LINE)) }; p.address?.let { put("a", it.take(MAX_LINE)) } })
    }

    private fun person(e: Any?): Person? {
        val o = e as? JsonObject ?: return null
        val n = str(o, "n")?.take(MAX_LINE)
        val a = str(o, "a")?.take(MAX_LINE)
        return if (n == null && a == null) null else Person(n, a)
    }

    fun decodeResponse(body: String): Response = wrap("mail message response") {
        val o = json.parseToJsonElement(body).jsonObject
        checkVersion(o)
        val state = str(o, "state") ?: throw IllegalArgumentException("state")
        if (state != "ok") return@wrap Response(state)
        Response(
            state = state,
            from = person(o["from"]) ?: Person(null, null),
            to = (o["to"] as? JsonArray).orEmpty().take(MAX_PEOPLE).mapNotNull { person(it) },
            cc = (o["cc"] as? JsonArray).orEmpty().take(MAX_PEOPLE).mapNotNull { person(it) },
            atMs = (o["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L,
            subject = str(o, "subject"),
            html = str(o, "html")?.takeIf { it.length <= MAX_HTML },
            text = str(o, "text")?.take(MAX_TEXT),
            attachments = (o["files"] as? JsonArray).orEmpty().take(MAX_ATTACHMENTS).mapNotNull { f ->
                val fo = f as? JsonObject ?: return@mapNotNull null
                val name = str(fo, "name") ?: return@mapNotNull null
                Attachment(name.take(MAX_LINE), ((fo["bytes"] as? JsonPrimitive)?.longOrNull ?: 0L).coerceAtLeast(0), str(fo, "type"))
            },
            blockedImages = ((o["blocked"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 9_999),
        )
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun checkVersion(o: JsonObject) {
        val w = (o["w"] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("w")
        if (w != WireCodec.VERSION) throw IllegalArgumentException("unsupported wire version $w")
    }

    private inline fun <T> wrap(what: String, block: () -> T): T = try {
        block()
    } catch (e: WireFormatException) {
        throw e
    } catch (e: Exception) {
        throw WireFormatException("bad $what: ${e.message}", e)
    }
}
