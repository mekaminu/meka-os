package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import os.meka.backend.integrations.Integrations
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.MealRules
import os.meka.core.domain.ShoppingItem
import os.meka.core.domain.ShoppingRules
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** What a family route answers: an HTTP status and a JSON body (`{"error": …}` when refused). */
data class FamilyReply(val status: Int, val body: String) {
    companion object {
        fun ok(o: JsonObject) = FamilyReply(200, o.toString())
        fun refused(status: Int, reason: String) = FamilyReply(status, buildJsonObject { put("error", reason) }.toString())
    }
}

/**
 * Meka's family page (build plan "Family sharing with Jeanette", slice 2; Meka approved 2026-10-09). Meka makes a
 * private invite link ([invite]); the first browser to open it makes its own non-extractable P-256 key with WebCrypto
 * and registers it ([claim], signed with that key, so possession is proved); from then on the page's requests are
 * signed like the apps' ([guest]) and the link alone is no use. Only the shopping list and the dinners are shared: the
 * page reads the list ([shopping]) and adds, ticks and puts back items ([add], [got], [putBack]); it reads the week's
 * dinners and the favourites ([meals], meal plan slice 3) and adds a favourite ([addMeal]). Writes are server ops with
 * `by` = her name, so they sync into Meka's Lists and Dinners like any other change ("From Jeanette"). Nothing else of
 * Meka's data is reachable: [read] is only ever asked for [SHARED_TYPES].
 *
 * Kept free of Ktor so its rules are tested directly; the routes in [mekaSync] only authenticate and forward.
 */
class FamilyShare(
    private val invites: FamilyInviteStore,
    private val ops: ServerOpStore,
    /** Each entity's latest op per field (id → field → op) of one type, for one household; only [SHARED_TYPES] are asked. */
    private val read: (householdId: String, entityType: String) -> Map<String, Map<String, Op>>,
    private val verifier: RequestVerifier,
    private val now: () -> Long = System::currentTimeMillis,
    private val calendar: LocalCalendar = LondonCalendar,
    /** Called after something was written for a household, so push can wake its devices. */
    private val onWritten: (householdId: String) -> Unit = {},
) {
    private val clock = HlcClock(Integrations.SERVER_DEVICE, now)
    private val rng = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    // ---- Meka's side (a keyed device of the household) ----

    /** Makes an invite for [body]'s `name` ("Jeanette"); the answer carries the link's token, the only time it is shown. */
    fun invite(householdId: String, body: String): FamilyReply {
        val raw = field(body, "name") ?: return FamilyReply.refused(400, "name")
        val name = nameKey(raw) ?: return FamilyReply.refused(400, "name")
        val token = hex(32)
        val invite = FamilyInvite(householdId, "fam" + hex(10), name, now())
        invites.create(invite, Secrets.sha256Hex(token))
        return FamilyReply.ok(
            buildJsonObject {
                put("id", invite.id)
                put("name", invite.name)
                put("token", token)
                put("path", "$PAGE_PATH#$token")
            },
        )
    }

    /** The household's invites (never a token or a key). */
    fun list(householdId: String): FamilyReply = FamilyReply.ok(
        buildJsonObject {
            putJsonArray("invites") {
                invites.list(householdId).forEach { i ->
                    addJsonObject {
                        put("id", i.id)
                        put("name", i.name)
                        put("state", i.state)
                        put("createdAtMs", i.createdAtMs)
                        i.claimedAtMs?.let { put("claimedAtMs", it) }
                        i.lastSeenAtMs?.let { put("lastSeenAtMs", it) }
                    }
                }
            }
        },
    )

    /** Revokes [body]'s `id` at once: her page's next request is refused. */
    fun revoke(householdId: String, body: String): FamilyReply {
        val id = field(body, "id")?.takeIf(::isInviteId) ?: return FamilyReply.refused(400, "id")
        if (!invites.revoke(householdId, id, now())) return FamilyReply.refused(404, "unknown")
        return FamilyReply.ok(buildJsonObject { put("id", id); put("state", FamilyInvite.REVOKED) })
    }

    // ---- Her page ----

    /**
     * First open: `{"token", "publicKey"}`, signed with that key over [path] (WebCrypto's raw signature). The key is
     * registered once; the same key again is fine (a retried first open), a different one is refused (`used`).
     */
    fun claim(path: String, body: String, time: String?, nonce: String?, signature: String?): FamilyReply {
        val token = field(body, "token")?.takeIf { it.length == 64 && it.all { c -> c in HEX } } ?: return FamilyReply.refused(400, "token")
        val key = field(body, "publicKey")?.takeIf { it.length <= 400 && verifier.isP256(it) } ?: return FamilyReply.refused(400, "key")
        if (!verifier.verify(key, "POST", path, body, time, nonce, signature, p1363 = true)) return FamilyReply.refused(401, "signature")
        val invite = invites.byToken(Secrets.sha256Hex(token)) ?: return FamilyReply.refused(401, "unknown")
        if (invite.revokedAtMs != null) return FamilyReply.refused(403, FamilyInvite.REVOKED)
        if (invite.publicKey != null && invite.publicKey != key) return FamilyReply.refused(403, "used")
        if (invite.publicKey == null && !invites.claim(invite.id, key, now())) {
            // Lost a race with another browser, or revoked meanwhile.
            return FamilyReply.refused(403, if (invites.byId(invite.id)?.revokedAtMs != null) FamilyInvite.REVOKED else "used")
        }
        return FamilyReply.ok(buildJsonObject { put("id", invite.id); put("name", display(invite.name)); putJsonArray("shares") { add(SHOPPING); add(MEALS) } })
    }

    /** Her browser, by its invite id and a signature over this exact request; null when it isn't let in. */
    fun guest(inviteId: String?, method: String, path: String, body: String, time: String?, nonce: String?, signature: String?): FamilyInvite? {
        val id = inviteId?.takeIf(::isInviteId) ?: return null
        val invite = invites.byId(id) ?: return null
        val key = invite.publicKey ?: return null
        if (invite.revokedAtMs != null) return null
        if (!verifier.verify(key, method, path, body, time, nonce, signature, p1363 = true)) return null
        runCatching { invites.seen(id, now()) }
        return invite
    }

    /** The list as her page shows it. */
    fun shopping(guest: FamilyInvite): FamilyReply = FamilyReply.ok(view(guest, current(guest)))

    /** `{"text": "milk, eggs"}`: the same rules as Lists → Shopping (split on commas, never "and"; no second row). */
    fun add(guest: FamilyInvite, body: String): FamilyReply {
        val text = field(body, "text")?.takeIf { it.length <= MAX_TEXT } ?: return FamilyReply.refused(400, "text")
        if (ShoppingRules.split(text).size > MAX_PER_ADD) return FamilyReply.refused(400, "too many")
        val items = current(guest)
        val fields = items.mapValues { (_, f) -> f.mapValues { it.value.value } }
        if (ShoppingRules.view(fields, now(), calendar).toBuy.size >= MAX_TO_BUY) return FamilyReply.refused(403, "full")
        val plan = ShoppingRules.add(text, fields, guest.name, now()) { "srvfam" + hex(9) }
        write(guest.householdId, EntityTypes.SHOPPING_ITEM, items, plan.writes)
        return shopping(guest)
    }

    /** `{"id"}`: ticks it as bought. Something not on the list (or already got) changes nothing. */
    fun got(guest: FamilyInvite, body: String): FamilyReply = change(guest, body) { f -> ShoppingRules.gotFields(f, now()) }

    /** `{"id"}`: puts a got item back to buy. */
    fun putBack(guest: FamilyInvite, body: String): FamilyReply = change(guest, body) { f -> ShoppingRules.putBackFields(f) }

    private fun change(guest: FamilyInvite, body: String, fieldsFor: (Map<String, FieldValue>?) -> Map<String, FieldValue>?): FamilyReply {
        val id = field(body, "id")?.takeIf { it.length in 1..80 } ?: return FamilyReply.refused(400, "id")
        val items = current(guest)
        // Only an existing shopping item can be touched; any other id writes nothing.
        val item = items[id] ?: return shopping(guest)
        fieldsFor(item.mapValues { it.value.value })?.let { write(guest.householdId, EntityTypes.SHOPPING_ITEM, items, listOf(id to it)) }
        return shopping(guest)
    }

    /**
     * The week's dinners from today (London) and the favourites, as her page shows them (meal plan slice 3): her own
     * favourites without "from Jeanette", Meka's without a name. Read only; planning a day stays on Meka's apps.
     */
    fun meals(guest: FamilyInvite): FamilyReply {
        val v = mealView(guest, fieldsOf(read(guest.householdId, EntityTypes.MEAL)))
        return FamilyReply.ok(
            buildJsonObject {
                put("name", display(guest.name))
                put("summary", v.summary)
                putJsonArray("week") {
                    v.week.forEach { d ->
                        addJsonObject {
                            put("day", d.day)
                            put("label", d.label)
                            d.title?.let { put("title", it) }
                            put("line", d.line)
                        }
                    }
                }
                putJsonArray("favourites") {
                    v.favourites.forEach { f -> addJsonObject { put("id", f.id); put("title", f.title); put("line", f.line) } }
                }
            },
        )
    }

    /**
     * `{"text": "Chilli: mince, beans, rice"}`: a favourite dinner, read as Lists' Dinners reads it ([MealRules.addPlan]);
     * the same name again updates its ingredients. Refused past [MealRules.MAX_FAVOURITES] new ones (`full`), or when
     * nothing could be read (`text`).
     */
    fun addMeal(guest: FamilyInvite, body: String): FamilyReply {
        val text = field(body, "text")?.takeIf { it.length <= MAX_MEAL_TEXT } ?: return FamilyReply.refused(400, "text")
        val existing = read(guest.householdId, EntityTypes.MEAL)
        val favourites = MealRules.meals(fieldsOf(existing))
        val plan = MealRules.addPlan(text, favourites, guest.name, now()) { "srvfam" + hex(9) } ?: return FamilyReply.refused(400, "text")
        if (!plan.updated && favourites.size >= MealRules.MAX_FAVOURITES) return FamilyReply.refused(403, "full")
        write(guest.householdId, EntityTypes.MEAL, existing, plan.writes)
        return meals(guest)
    }

    private fun mealView(guest: FamilyInvite, mealFields: Map<String, Map<String, FieldValue>>) = MealRules.view(
        MealRules.meals(mealFields),
        MealRules.plan(fieldsOf(read(guest.householdId, EntityTypes.MEAL_DAY))),
        calendar.epochDayOf(now()),
        ShoppingRules.view(fieldsOf(current(guest)), now(), calendar).toBuy.map { ShoppingRules.key(it.title) }.toSet(),
        viewer = guest.name,
    )

    private fun fieldsOf(ops: Map<String, Map<String, Op>>) = ops.mapValues { (_, f) -> f.mapValues { it.value.value } }

    private fun current(guest: FamilyInvite) = read(guest.householdId, EntityTypes.SHOPPING_ITEM)

    private fun view(guest: FamilyInvite, items: Map<String, Map<String, Op>>): JsonObject {
        val v = ShoppingRules.view(items.mapValues { (_, f) -> f.mapValues { it.value.value } }, now(), calendar)
        fun row(i: ShoppingItem) = buildJsonObject {
            put("id", i.id)
            put("title", i.title)
            // Her own things need no "From Jeanette" on her own page.
            val meta = if (!i.got && i.by == guest.name) null else i.meta
            meta?.let { put("meta", it) }
        }
        return buildJsonObject {
            put("name", display(guest.name))
            put("line", v.line)
            putJsonArray("toBuy") { v.toBuy.forEach { add(row(it)) } }
            putJsonArray("got") { v.got.forEach { add(row(it)) } }
        }
    }

    /** Appends each field as a server op over the field's current head, after it in HLC order. */
    private fun write(
        householdId: String,
        entityType: String,
        current: Map<String, Map<String, Op>>,
        writes: List<Pair<String, Map<String, FieldValue>>>,
    ) {
        require(entityType in SHARED_TYPES)
        if (writes.isEmpty()) return
        ops.transaction {
            for ((id, fields) in writes) {
                for ((field, value) in fields) {
                    val last = current[id]?.get(field)
                    val hlc = synchronized(clock) {
                        last?.let { runCatching { clock.receive(it.hlc) } }
                        clock.now()
                    }
                    ops.append(
                        Op(
                            opId = "srvfam" + hex(12), householdId = householdId, entityType = entityType, entityId = id,
                            field = field, value = value, hlc = hlc, baseOpIds = listOfNotNull(last?.opId), deviceId = Integrations.SERVER_DEVICE,
                        ),
                    )
                }
            }
        }
        runCatching { onWritten(householdId) }
    }

    private fun field(body: String, name: String): String? = runCatching {
        (json.parseToJsonElement(body).jsonObject[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }.getOrNull()

    private fun hex(bytes: Int): String = ByteArray(bytes).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val PAGE_PATH = "/family"
        const val SHOPPING = "shopping"
        const val MEALS = "meals"
        const val MAX_MEAL_TEXT = 300

        /** The only entity types the page reads or writes. */
        val SHARED_TYPES = setOf(EntityTypes.SHOPPING_ITEM, EntityTypes.MEAL, EntityTypes.MEAL_DAY)
        const val MAX_TEXT = 1_000
        const val MAX_PER_ADD = 30
        const val MAX_TO_BUY = 200
        private const val HEX = "0123456789abcdef"

        /** "Jeanette" → "jeanette"; letters, spaces, hyphens and apostrophes only, up to 30; else null. */
        fun nameKey(raw: String): String? {
            val t = raw.trim().replace(Regex("\\s+"), " ")
            if (t.isEmpty() || t.length > 30) return null
            if (!t.all { it.isLetter() || it == ' ' || it == '-' || it == '\'' }) return null
            if (t.equals(ShoppingRules.OWNER, ignoreCase = true)) return null
            return t.lowercase()
        }

        /** "jeanette" → "Jeanette". */
        fun display(name: String): String = ShoppingRules.byName(name) ?: name

        fun isInviteId(id: String) = id.length == 23 && id.startsWith("fam") && id.drop(3).all { it in HEX }

        /** Each entity's latest op per field by HLC, scanning the op log (tests and small stores). */
        fun scanning(ops: ServerOpStore): (String, String) -> Map<String, Map<String, Op>> = { hh, type ->
            val out = LinkedHashMap<String, LinkedHashMap<String, Op>>()
            ops.after(hh, 0, Int.MAX_VALUE).map { it.op }.filter { it.entityType == type }.forEach { op ->
                val f = out.getOrPut(op.entityId) { LinkedHashMap() }
                val prev = f[op.field]
                if (prev == null || op.hlc > prev.hlc) f[op.field] = op
            }
            out
        }
    }
}

/** Europe/London days for the page's "today" and "Got Thu 8 Oct" (the household is in the UK). */
object LondonCalendar : LocalCalendar {
    private val zone: ZoneId = ZoneId.of("Europe/London")

    override fun epochDayOf(epochMs: Long): Long = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toEpochDay()

    override fun minuteOfDay(epochMs: Long): Int = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

    override fun toEpochMs(epochDay: Long, minuteOfDay: Int): Long =
        LocalDate.ofEpochDay(epochDay).atTime(LocalTime.MIDNIGHT).plusMinutes(minuteOfDay.toLong()).atZone(zone).toInstant().toEpochMilli()
}
