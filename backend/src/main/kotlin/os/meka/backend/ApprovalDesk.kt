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
import os.meka.core.domain.ApprovalKind
import os.meka.core.domain.ApprovalRules
import os.meka.core.domain.ApprovalState
import os.meka.core.domain.EntityTypes
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import java.security.SecureRandom

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
        all[id]?.let { existing -> return status(id, ref, existing) }
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
        return status(id, ref, readAll(householdId)[id].orEmpty())
    }

    /** Meka's answer to one of [asker]'s requests ([body]: {"ref"}); 404 for a ref it never filed. */
    fun status(householdId: String, asker: ApprovalAsker, body: String): Reply {
        val ref = parse(body)?.text("ref")?.takeIf(::validRef) ?: return Reply.Refused(400, "bad ref")
        val id = ApprovalRules.entityId(asker.label, ref)
        val f = readAll(householdId)[id] ?: return Reply.Refused(404, "no such request")
        return status(id, ref, f)
    }

    private fun status(id: String, ref: String, f: Map<String, Op>): Reply {
        val a = parseApproval(id, f) ?: return Reply.Refused(404, "no such request")
        val state = a.state(now())
        return Reply.Ok(
            buildJsonObject {
                put("id", id)
                put("ref", ref)
                put("state", state.name.lowercase())
                put("askedAtMs", a.askedAtMs)
                put("expiresAtMs", a.expiresAtMs)
                if (state == ApprovalState.APPROVED || state == ApprovalState.DECLINED) {
                    a.decidedAtMs?.let { put("decidedAtMs", it) }
                    a.confirmedBy?.let { put("confirmedBy", it) }
                    a.decisionKey?.let { put("decisionKey", it) }
                }
            }.toString(),
        )
    }

    private fun parseApproval(id: String, f: Map<String, Op>): Approval? = ApprovalRules.parse(id, f.mapValues { it.value.value })

    private fun parse(body: String): JsonObject? = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    private fun JsonObject.text(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun hex(bytes: Int): String = ByteArray(bytes).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val AUTH_SCHEME = "Asker"
        const val REQUEST_PATH = "/v1/approvals/request"
        const val STATUS_PATH = "/v1/approvals/status"
        /** The only routes an asker may call. */
        val ROUTES = setOf(REQUEST_PATH, STATUS_PATH)
        /** An asker can't have more than this many requests waiting for Meka at once (Needs you isn't flooded). */
        const val MAX_WAITING = 10

        /** The asker's own reference: letters, digits and `._:-`, up to 64 characters. */
        fun validRef(ref: String): Boolean = ref.length in 1..64 && ref.all { it.isLetterOrDigit() && it.code < 128 || it in "._:-" }

        /** Kestrel, the trading bot: trades only (build plan V1; approved by Meka 2026-10-07). */
        fun kestrel(key: PublisherKeySource) = ApprovalAsker("kestrel", "Kestrel", setOf(ApprovalKind.TRADE), key)

        /** Every op of every approval, newest per field: for tests and the in-memory store. */
        fun scanning(ops: ServerOpStore): (String) -> Map<String, Map<String, Op>> = { hh ->
            val out = LinkedHashMap<String, MutableMap<String, Op>>()
            ops.after(hh, 0, Int.MAX_VALUE).map { it.op }.filter { it.entityType == EntityTypes.APPROVAL }.forEach { op ->
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
