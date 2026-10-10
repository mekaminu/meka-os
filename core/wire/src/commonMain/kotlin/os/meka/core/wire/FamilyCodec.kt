package os.meka.core.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Family sharing's invites on the wire (build plan "Family sharing with Jeanette", slice 4): a keyed device makes, lists
 * and turns off the family page's links (`POST /v1/family/invites/create · list · revoke`, ADR-005 amendment
 * 2026-10-10). The server's JSON has no wire version (the family page reads the same server); unknown keys are ignored.
 * A listing never carries a token or a browser key; the token comes back once, inside [Made.path] ("/family#<hex>").
 */
object FamilyCodec {
    const val MAX_INVITES = 50
    const val PAGE_PATH = "/family"
    private const val TOKEN_HEX = 64
    private val json = Json { ignoreUnknownKeys = true }

    data class Invite(
        val id: String,
        val name: String,
        /** waiting · joined · revoked. */
        val state: String,
        val createdAtMs: Long,
        val claimedAtMs: Long? = null,
        val lastSeenAtMs: Long? = null,
    )

    data class Made(val id: String, val name: String, val path: String)

    fun encodeCreate(name: String): String = buildJsonObject { put("name", name) }.toString()
    fun encodeRevoke(id: String): String = buildJsonObject { put("id", id) }.toString()
    fun encodeList(): String = "{}"

    /** An invite id the server makes: "fam" and 20 lower-case hex digits. */
    fun isInviteId(id: String): Boolean = id.length == 23 && id.startsWith("fam") && id.drop(3).all { it in '0'..'9' || it in 'a'..'f' }

    /** A link's path: "/family#" and the 64-hex-digit token, nothing else (the device puts its own server in front). */
    fun isLinkPath(path: String): Boolean {
        val token = path.removePrefix("$PAGE_PATH#")
        return token.length == TOKEN_HEX && path.length == PAGE_PATH.length + 1 + TOKEN_HEX && token.all { it in '0'..'9' || it in 'a'..'f' }
    }

    /** The listing, newest first as the server sends it; entries that don't read are left out, at most [MAX_INVITES]. */
    fun decodeList(body: String): List<Invite> = wrap("family list") {
        val arr = json.parseToJsonElement(body).jsonObject["invites"] as? JsonArray ?: throw IllegalArgumentException("invites")
        arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf(::isInviteId) ?: return@mapNotNull null
            Invite(
                id = id,
                name = o.str("name")?.takeIf { it.isNotBlank() && it.length <= 30 } ?: return@mapNotNull null,
                state = o.str("state")?.takeIf { it == "waiting" || it == "joined" || it == "revoked" } ?: return@mapNotNull null,
                createdAtMs = o.long("createdAtMs") ?: return@mapNotNull null,
                claimedAtMs = o.long("claimedAtMs"),
                lastSeenAtMs = o.long("lastSeenAtMs"),
            )
        }.take(MAX_INVITES)
    }

    fun decodeMade(body: String): Made = wrap("family invite") {
        val o = json.parseToJsonElement(body).jsonObject
        Made(
            id = o.str("id")?.takeIf(::isInviteId) ?: throw IllegalArgumentException("id"),
            name = o.str("name")?.takeIf { it.isNotBlank() && it.length <= 30 } ?: throw IllegalArgumentException("name"),
            path = o.str("path")?.takeIf(::isLinkPath) ?: throw IllegalArgumentException("path"),
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private inline fun <T> wrap(what: String, block: () -> T): T = try {
        block()
    } catch (e: WireFormatException) {
        throw e
    } catch (e: Exception) {
        throw WireFormatException("bad $what: ${e.message}", e)
    }
}
