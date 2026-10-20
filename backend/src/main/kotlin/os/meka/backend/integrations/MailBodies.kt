package os.meka.backend.integrations

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import os.meka.core.domain.MailAttachmentFacts
import os.meka.core.domain.MailMessageFacts
import os.meka.core.domain.MailPerson

/*
 * Read the full email inside Navilife (2026-10-20), slice 1: one email read whole from the provider at the moment Meka
 * taps it, for the reader only. The answer is parsed here, made safe by the core's MailHtmlSanitizer and handed back;
 * it is never stored, logged or given to the AI. Attachments are listed by name, size and type only (never opened).
 */

private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.l(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

/** Gmail's `users.messages.get?format=full`: headers, the MIME tree's first HTML and plain part, and the files. */
internal object GmailMessageBody {
    /** How deep the MIME tree is followed (forwarded mail nests). */
    private const val MAX_DEPTH = 12

    private fun headers(part: JsonObject): Map<String, String> =
        (part["headers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { h -> h["name"].s()?.lowercase()?.let { n -> n to (h["value"].s() ?: "") } }
            .fold(LinkedHashMap<String, String>()) { m, (k, v) -> if (k !in m) m[k] = v; m }

    fun read(m: JsonObject): MailMessageFacts {
        val payload = m["payload"] as? JsonObject ?: JsonObject(emptyMap())
        val hs = headers(payload)
        var html: String? = null
        var text: String? = null
        val files = mutableListOf<MailAttachmentFacts>()
        fun walk(p: JsonObject, depth: Int) {
            if (depth > MAX_DEPTH) return
            val mime = p["mimeType"].s()?.lowercase().orEmpty()
            val ph = headers(p)
            val parts = p["parts"] as? JsonArray
            if (parts != null && parts.isNotEmpty()) {
                parts.forEach { c -> (c as? JsonObject)?.let { walk(it, depth + 1) } }
                return
            }
            val filename = p["filename"].s().orEmpty().trim()
            val disposition = ph["content-disposition"]?.lowercase().orEmpty()
            val body = p["body"] as? JsonObject
            if (filename.isNotEmpty() || disposition.startsWith("attachment")) {
                // A picture that is part of the HTML (inline with a Content-ID) isn't an attachment he'd look for.
                val inlinePicture = disposition.startsWith("inline") && ph["content-id"] != null && mime.startsWith("image/")
                if (!inlinePicture) files += MailAttachmentFacts(filename.ifEmpty { "Attachment" }, body?.get("size").l() ?: 0L, mime.ifEmpty { null })
                return
            }
            val data = body?.get("data").s() ?: return
            val decoded = MailBodyText.decode(data, MailBodyText.charset(ph["content-type"]))
            when (mime) {
                "text/html" -> if (html == null) html = decoded
                "text/plain" -> if (text == null) text = decoded
            }
        }
        walk(payload, 0)
        val (fromName, fromAddress) = MailAddress.parse(hs["from"])
        return MailMessageFacts(
            from = MailPerson(fromName, fromAddress),
            to = MailAddress.parseList(hs["to"]).map { (n, a) -> MailPerson(n, a) },
            cc = MailAddress.parseList(hs["cc"]).map { (n, a) -> MailPerson(n, a) },
            atMs = m["internalDate"].l() ?: 0L,
            subject = hs["subject"]?.take(MailAddress.MAX_SUBJECT),
            html = html, text = text, attachments = files,
        )
    }
}

/** Graph's `GET /me/messages/{id}` with its body and attachments (names, sizes, types; inline pictures left out). */
internal object GraphMessageBody {
    const val SELECT = "subject,from,toRecipients,ccRecipients,receivedDateTime,body,hasAttachments"
    const val EXPAND = "attachments(\$select=name,size,contentType,isInline)"

    private fun person(e: JsonElement?): MailPerson? {
        val a = (e as? JsonObject)?.get("emailAddress") as? JsonObject ?: return null
        val name = a["name"].s()?.trim()?.takeIf { it.isNotEmpty() }
        val address = a["address"].s()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return if (name == null && address == null) null else MailPerson(name, address)
    }

    fun read(m: JsonObject): MailMessageFacts {
        val body = m["body"] as? JsonObject
        val type = body?.get("contentType").s()?.lowercase()
        val content = body?.get("content").s()
        val files = (m["attachments"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { (it["isInline"] as? JsonPrimitive)?.contentOrNull != "true" }
            .map { a -> MailAttachmentFacts(a["name"].s()?.trim()?.ifEmpty { null } ?: "Attachment", a["size"].l() ?: 0L, a["contentType"].s()) }
        return MailMessageFacts(
            from = person(m["from"]) ?: MailPerson(null, null),
            to = (m["toRecipients"] as? JsonArray).orEmpty().mapNotNull(::person),
            cc = (m["ccRecipients"] as? JsonArray).orEmpty().mapNotNull(::person),
            atMs = m["receivedDateTime"].s()?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
            subject = m["subject"].s()?.take(MailAddress.MAX_SUBJECT),
            html = if (type == "html") content else null,
            text = if (type != "html") content else null,
            attachments = files,
        )
    }
}

/** A MIME part's text: Gmail's base64url data in the part's own character set (UTF-8 when unsaid or unknown). */
internal object MailBodyText {
    fun charset(contentType: String?): java.nio.charset.Charset {
        val name = contentType?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("charset", ignoreCase = true) }
            ?.substringAfter('=')?.trim()?.trim('"', '\'')
        return name?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    fun decode(data: String, charset: java.nio.charset.Charset): String {
        val bytes = runCatching { java.util.Base64.getUrlDecoder().decode(data.trim().trimEnd('=')) }
            .recoverCatching { java.util.Base64.getMimeDecoder().decode(data) }.getOrDefault(ByteArray(0))
        return String(bytes, charset)
    }
}
