package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import os.meka.backend.integrations.Integrations
import os.meka.core.domain.Approval
import os.meka.core.domain.ApprovalFields
import os.meka.core.domain.ApprovalKeyFields
import os.meka.core.domain.ApprovalKind
import os.meka.core.domain.ApprovalRules
import os.meka.core.domain.ApprovalState
import os.meka.core.domain.EntityTypes
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Someone outside MEKA that may ask Meka for an approval (build plan V1, "Trading approvals from Kestrel"; ADR-006
 * addendum 2026-10-12). An asker is not a household device: it has no bearer secret and is known to the server only by
 * a P-256 public key ([key]; the private half stays with the asker, never in MEKA). It may call only
 * [ApprovalDesk.ROUTES], may ask only for [kinds], and its requests always carry [label] as who asked (it can't name
 * itself as someone else). It never holds, sends or receives any exchange credential: it asks, then reads Meka's answer.
 */
data class ApprovalAsker(val id: String, val label: String, val kinds: Set<ApprovalKind>, val key: PublisherKeySource)

/**
 * The approval desk: where an asker files a request and reads Meka's answer. A request becomes an `approval` entity
 * (ADR-008 addendum 2026-10-11) written once as server ops, so both apps show it in Needs you with the heads-up that is
 * already built, and approving still needs Meka's fingerprint there (ADR-006's hard rule, no setting lowers it). MEKA
 * itself never acts on the answer: the asker reads it with its idempotency key and acts once.
 */
class ApprovalDesk(
    private val ops: ServerOpStore,
    /** Every `approval` of a household, newest op per field, by entity id. */
    private val readAll: (householdId: String) -> Map<String, Map<String, Op>>,
    private val now: () -> Long = System::currentTimeMillis,
    /** Every `approval_key` of a household (slice 2), newest op per field, by device id. */
    private val readKeys: (householdId: String) -> Map<String, Map<String, Op>> = { emptyMap() },
    /** Called after a request was written, so push can wake the household's devices. */
    private val onWritten: (householdId: String) -> Unit = {},
) {
    private val clock = HlcClock(Integrations.SERVER_DEVICE, now)
    private val rng = SecureRandom()

    sealed class Reply {
        data class Ok(val json: String) : Reply()
        data class Refused(val status: Int, val reason: String) : Reply()
    }

    /** Files a request ([body]: {"ref","kind","title","lines"?,"expiresAtMs"?}); the same ref again answers its status. */
    fun request(householdId: String, asker: ApprovalAsker, body: String): Reply {
        val o = parse(body) ?: return Reply.Refused(400, "bad request")
        val ref = o.text("ref")?.takeIf(::validRef) ?: return Reply.Refused(400, "bad ref")
        val kind = ApprovalKind.of(o.text("kind")) ?: return Reply.Refused(400, "unknown kind")
        if (kind !in asker.kinds) return Reply.Refused(403, "kind not allowed")
        val id = ApprovalRules.entityId(asker.label, ref)
        val all = readAll(householdId)
        all[id]?.let { existing -> return status(householdId, id, ref, existing) }
        val t = now()
        val waiting = all.mapNotNull { (eid, f) -> parseApproval(eid, f) }.count { it.source == asker.label && it.state(t) == ApprovalState.PENDING }
        if (waiting >= MAX_WAITING) return Reply.Refused(429, "too many waiting")
        val lines = (o["lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val fields = ApprovalRules.requestFields(kind, asker.label, o.text("title").orEmpty(), lines, t, o.long("expiresAtMs"))
            ?: return Reply.Refused(400, "bad request")
        ops.transaction {
            for ((field, value) in fields) {
                ops.append(
                    Op(
                        opId = "srvappr" + hex(12), householdId = householdId, entityType = EntityTypes.APPROVAL, entityId = id,
                        field = field, value = value, hlc = synchronized(clock) { clock.now() }, baseOpIds = emptyList(),
                        deviceId = Integrations.SERVER_DEVICE,
                    ),
                )
            }
        }
        runCatching { onWritten(householdId) }
        return status(householdId, id, ref, readAll(householdId)[id].orEmpty())
    }

    /** Meka's answer to one of [asker]'s requests ([body]: {"ref"}); 404 for a ref it never filed. */
    fun status(householdId: String, asker: ApprovalAsker, body: String): Reply {
        val ref = parse(body)?.text("ref")?.takeIf(::validRef) ?: return Reply.Refused(400, "bad ref")
        val id = ApprovalRules.entityId(asker.label, ref)
        val f = readAll(householdId)[id] ?: return Reply.Refused(404, "no such request")
        return status(householdId, id, ref, f)
    }

    private fun status(householdId: String, id: String, ref: String, f: Map<String, Op>): Reply {
        val a = parseApproval(id, f) ?: return Reply.Refused(404, "no such request")
        val state = a.state(now())
        return Reply.Ok(
            buildJsonObject {
                put("id", id)
                put("ref", ref)
                put("state", state.name.lowercase())
                put("askedAtMs", a.askedAtMs)
                put("expiresAtMs", a.expiresAtMs)
                // As Meka saw them (cleaned and cut), so Kestrel can rebuild the signed text's hash.
                put("title", a.title)
                put("lines", JsonArray(a.lines.map { JsonPrimitive(it) }))
                if (state == ApprovalState.APPROVED || state == ApprovalState.DECLINED) {
                    a.decidedAtMs?.let { put("decidedAtMs", it) }
                    a.confirmedBy?.let { put("confirmedBy", it) }
                    a.decisionKey?.let { put("decisionKey", it) }
                }
                if (state == ApprovalState.APPROVED) signedAnswer(a, f, householdId)?.let { signed ->
                    put("answerSignature", a.answerSignature!!)
                    put("answerDevice", a.decidedOn!!)
                    put("answerPublicKey", a.answerPublicKey!!)
                    put("answerMessage", signed)
                }
            }.toString(),
        )
    }

    /**
     * Slice 2: the text a yes was signed over, when the signature holds up — every answer field was written by the
     * device that answered (the sync server only takes a device's own ops), that device registered the same approval
     * key itself, and the signature verifies with it over [ApprovalRules.answerMessage]. Null otherwise: the status
     * then carries no signature, and Kestrel treats the yes as no.
     */
    private fun signedAnswer(a: Approval, f: Map<String, Op>, householdId: String): String? {
        val device = a.decidedOn ?: return null
        val sig = a.answerSignature ?: return null
        val pub = a.answerPublicKey ?: return null
        val key = a.decisionKey ?: return null
        val at = a.decidedAtMs ?: return null
        val answerFields = listOf(
            ApprovalFields.APPROVED, ApprovalFields.DECIDED_AT, ApprovalFields.DECISION_KEY, ApprovalFields.DECIDED_ON,
            ApprovalFields.ANSWER_SIGNATURE, ApprovalFields.ANSWER_PUBLIC_KEY,
        )
        if (answerFields.any { f[it]?.deviceId != device }) return null
        val registered = readKeys(householdId)[device] ?: return null
        val keyOp = registered[ApprovalKeyFields.PUBLIC_KEY] ?: return null
        if (keyOp.deviceId != device || keyOp.value.textOrNull != pub) return null
        val message = ApprovalRules.answerMessage(a, key, at, device)
        return message.takeIf { verifyP256(pub, it, sig) }
    }

    /** The household's approval keys, each written by its own device ([body] is ignored): for Kestrel to pin at setup. */
    fun keys(householdId: String): Reply = Reply.Ok(
        buildJsonObject {
            put(
                "keys",
                JsonArray(
                    readKeys(householdId).mapNotNull { (device, f) ->
                        val op = f[ApprovalKeyFields.PUBLIC_KEY]?.takeIf { it.deviceId == device } ?: return@mapNotNull null
                        val pub = op.value.textOrNull?.takeIf(::isP256) ?: return@mapNotNull null
                        buildJsonObject {
                            put("device", device)
                            f[ApprovalKeyFields.LABEL]?.takeIf { it.deviceId == device }?.value?.textOrNull?.let { put("label", it) }
                            put("publicKey", pub)
                            f[ApprovalKeyFields.SINCE]?.takeIf { it.deviceId == device }?.value?.longOrNull?.let { put("sinceMs", it) }
                        }
                    }.sortedBy { it["device"].toString() },
                ),
            )
        }.toString(),
    )

    private fun publicKey(b64: String) =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64)))

    private fun isP256(b64: String): Boolean = runCatching {
        (publicKey(b64) as java.security.interfaces.ECPublicKey).params.curve.field.fieldSize == 256
    }.getOrDefault(false)

    private fun verifyP256(pubB64: String, message: String, sigB64: String): Boolean = runCatching {
        isP256(pubB64) && Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(pubB64)); update(message.toByteArray()); verify(Base64.getDecoder().decode(sigB64))
        }
    }.getOrDefault(false)

    private fun parseApproval(id: String, f: Map<String, Op>): Approval? = ApprovalRules.parse(id, f.mapValues { it.value.value })

    private fun parse(body: String): JsonObject? = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    private fun JsonObject.text(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun hex(bytes: Int): String = ByteArray(bytes).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val AUTH_SCHEME = "Asker"
        const val REQUEST_PATH = "/v1/approvals/request"
        const val STATUS_PATH = "/v1/approvals/status"
        /** Slice 2: the devices' approval keys, for the asker to pin (it then trusts only those). */
        const val KEYS_PATH = "/v1/approvals/keys"
        /** The only routes an asker may call. */
        val ROUTES = setOf(REQUEST_PATH, STATUS_PATH, KEYS_PATH)
        /** An asker can't have more than this many requests waiting for Meka at once (Needs you isn't flooded). */
        const val MAX_WAITING = 10

        /** The asker's own reference: letters, digits and `._:-`, up to 64 characters. */
        fun validRef(ref: String): Boolean = ref.length in 1..64 && ref.all { it.isLetterOrDigit() && it.code < 128 || it in "._:-" }

        /** Kestrel, the trading bot: trades only (build plan V1; approved by Meka 2026-10-07). */
        fun kestrel(key: PublisherKeySource) = ApprovalAsker("kestrel", "Kestrel", setOf(ApprovalKind.TRADE), key)

        /** Every op of every approval, newest per field: for tests and the in-memory store. */
        fun scanning(ops: ServerOpStore, type: String = EntityTypes.APPROVAL): (String) -> Map<String, Map<String, Op>> = { hh ->
            val out = LinkedHashMap<String, MutableMap<String, Op>>()
            ops.after(hh, 0, Int.MAX_VALUE).map { it.op }.filter { it.entityType == type }.forEach { op ->
                val m = out.getOrPut(op.entityId) { LinkedHashMap() }
                val prev = m[op.field]
                if (prev == null || op.hlc > prev.hlc) m[op.field] = op
            }
            out
        }
    }
}

/** The approval desk's routes and who may call them; null in [mekaSync] leaves them out. */
class ApprovalRoutes(val desk: ApprovalDesk, askers: List<ApprovalAsker>) {
    val askers: Map<String, ApprovalAsker> = askers.associateBy { it.id }
}
