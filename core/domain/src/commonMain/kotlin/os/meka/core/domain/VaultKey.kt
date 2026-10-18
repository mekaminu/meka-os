package os.meka.core.domain

import os.meka.core.sync.Replica
import os.meka.core.sync.Sha256
import os.meka.core.sync.fv

/*
 * The Vault, slice 2a (2026-10-18): **the household's Vault key** (ADR-005 addendum 2026-10-18). The documents
 * themselves (slice 2b) are encrypted on a phone or Mac with one 256-bit household key before anything leaves it, so
 * MEKA's server only ever holds ciphertext. This file is the key's bookkeeping, pure and non-AI; the crypto (ECDH,
 * AES-256-GCM) is each platform's ([os.meka.core.domain.Hkdf] is shared).
 *
 * - **Made once**, on the first phone or Mac to finish a clean sync with no key in the household (`vault_key`,
 *   id "household", carries only its [VaultKeyRules.keyId]). The key itself stays on that device (its encrypted
 *   database, never synced).
 * - **Each phone or Mac** publishes a P-256 key-agreement key held in its secure hardware (`vault_member`, id its
 *   device id). A device that holds the Vault key shows the request with a six-digit code ([VaultKeyRules.code]) that
 *   the asking device shows too; only on Meka's Allow is the key sealed for it (`vault_wrap`, id the device id):
 *   a one-off P-256 key's ECDH with the member's key, HKDF-SHA256, AES-256-GCM. The server can't open it, and a key it
 *   slipped in itself would show a different code.
 * - **The recovery key** opens it too: when a recovery key is made on a device holding the Vault key, the key is also
 *   sealed with a key derived from the recovery words ([VaultKeyRules.recoveryKey]; `vault_wrap` id "recovery"). The
 *   server knows only a hash of a hash of those words, from which this can't be derived.
 * - A watch never gets it.
 *
 * Three synced entity types (ADR-008 addendum 2026-10-18), all opaque to the server: `vault_key`, `vault_member`,
 * `vault_wrap`. No migration.
 */

object VaultKeyFields {
    const val KEY_ID = "keyId"
    const val MADE_BY = "madeBy"
    const val MADE_AT = "madeAtMs"
}

object VaultMemberFields {
    /** The device's P-256 key-agreement key, X.509 DER, base64. */
    const val PUBLIC_KEY = "publicKey"
    /** "Fold", "Mac". */
    const val NAME = "name"
    /** When it asked for the key (published its key, or Ask again). */
    const val ASKED_AT = "askedAtMs"
    /** When a holder said Not this one. */
    const val REFUSED_AT = "refusedAtMs"
    /** The [VaultKeyRules.keyId] it holds. */
    const val HOLDS = "holdsKeyId"
}

object VaultWrapFields {
    const val KEY_ID = "keyId"
    /** The one-off P-256 key's public half (X.509 DER, base64); absent on the recovery wrap. */
    const val EPHEMERAL = "ephemeralKey"
    /** AES-256-GCM: 12-byte nonce, ciphertext, 16-byte tag, base64. */
    const val SEALED = "sealed"
    /** Which device sealed it ("Fold"). */
    const val BY = "byName"
    const val AT = "atMs"
}

data class VaultMember(
    val deviceId: String,
    val publicKey: String,
    val name: String,
    val askedAtMs: Long,
    val refusedAtMs: Long?,
    val holdsKeyId: String?,
)

data class VaultWrap(val id: String, val keyId: String, val ephemeralKey: String?, val sealed: String, val byName: String, val atMs: Long)

/** One device asking for the Vault key, shown on a device that holds it: Allow / Not this one. */
data class VaultRequest(val deviceId: String, val title: String, val line: String, val spoken: String)

/**
 * Settings → Vault's VAULT KEY block: [line] (lit when it waits on Meka), an optional [sub], the devices asking
 * ([requests], only on a device holding the key), and whether Ask again is offered ([canAskAgain]).
 */
data class VaultKeyView(
    val line: String,
    val lit: Boolean,
    val sub: String?,
    val requests: List<VaultRequest>,
    val canAskAgain: Boolean,
) {
    val shown: Boolean get() = line.isNotEmpty()

    companion object { val NONE = VaultKeyView("", false, null, emptyList(), false) }
}

/** What [VaultKeyRules.view] reads. [heldKeyId] is the key this device holds; [canonical] the household's. */
data class VaultState(
    val me: String,
    val myName: String?,
    val available: Boolean,
    val myPublicKey: String?,
    val heldKeyId: String?,
    val canonical: String?,
    val members: List<VaultMember>,
    val wraps: List<VaultWrap>,
)

object VaultKeyRules {
    const val KEY_BYTES = 32
    const val HOUSEHOLD = "household"
    const val RECOVERY = "recovery"
    /** Device-local values (never synced): the key this device holds ("<keyId> <base64>"), a recovered device's key from the words. */
    const val LOCAL_KEY = "vault:key"
    const val LOCAL_FORMER = "vault:former"
    const val LOCAL_RECOVERY = "vault:recovery"
    const val ALLOW = "Allow"
    const val REFUSE = "Not this one"
    const val ASK_AGAIN = "Ask again"

    private const val ID_DOMAIN = "MEKA-VAULT-ID-1"
    private const val CODE_DOMAIN = "MEKA-VAULT-CODE-1"
    private const val WRAP_DOMAIN = "MEKA-VAULT-WRAP-1"
    private const val RECOVERY_DOMAIN = "MEKA-VAULT-RECOVERY-1"

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Eight upper-case hex characters naming a key without revealing it. */
    fun keyId(key: ByteArray): String = Sha256.hexOf("$ID_DOMAIN\n" + hex(key)).take(8).uppercase()

    /** "4F2A 91D0". */
    fun display(keyId: String): String = if (keyId.length == 8) keyId.take(4) + " " + keyId.drop(4) else keyId

    /** The six digits both devices show for a member's key: "482 913". */
    fun code(publicKey: String): String {
        val d = Sha256.digest("$CODE_DOMAIN\n$publicKey".encodeToByteArray())
        var n = 0L
        for (i in 0 until 4) n = (n shl 8) or (d[i].toLong() and 0xff)
        val six = (n % 1_000_000).toString().padStart(6, '0')
        return six.take(3) + " " + six.drop(3)
    }

    /** The AES key that seals the Vault key for [deviceId], from the ECDH shared secret. */
    fun wrapKey(shared: ByteArray, deviceId: String, keyId: String): ByteArray =
        Hkdf.derive(shared, ByteArray(0), "$WRAP_DOMAIN\n$deviceId\n$keyId".encodeToByteArray(), KEY_BYTES)

    /** The AES key that seals the Vault key for the recovery words' 256 bits. */
    fun recoveryKey(entropy: ByteArray): ByteArray =
        Hkdf.derive(entropy, ByteArray(0), RECOVERY_DOMAIN.encodeToByteArray(), KEY_BYTES)

    /** "<keyId> <base64>" as kept on the device, read back; null for anything else. */
    fun readLocal(text: String?): Pair<String, String>? {
        val parts = text?.trim()?.split(' ') ?: return null
        if (parts.size != 2 || parts[0].length != 8 || parts[1].isEmpty()) return null
        return parts[0] to parts[1]
    }

    fun local(keyId: String, base64: String) = "$keyId $base64"

    /** Activity's entry for an Allow. */
    fun activityId(deviceId: String, atMs: Long): String = "d" + ActivityRules.fnv64("vault:allow:$deviceId:$atMs")

    /** Devices waiting for [canonical]: another device's key, not holding it, not sealed for since it asked, not refused since. */
    fun requests(members: List<VaultMember>, wraps: List<VaultWrap>, canonical: String?, me: String): List<VaultMember> {
        if (canonical == null) return emptyList()
        return members.filter { m ->
            m.deviceId != me && m.publicKey.isNotEmpty() && m.holdsKeyId != canonical &&
                wraps.none { it.id == m.deviceId && it.keyId == canonical && it.atMs >= m.askedAtMs } &&
                (m.refusedAtMs == null || m.refusedAtMs < m.askedAtMs)
        }.sortedBy { it.askedAtMs }
    }

    private fun names(list: List<String>): String = when (list.size) {
        0 -> ""
        1 -> list[0]
        else -> list.dropLast(1).joinToString(", ") + " and " + list.last()
    }

    fun view(s: VaultState): VaultKeyView {
        val name = s.myName ?: return VaultKeyView.NONE
        if (!s.available) return VaultKeyView(
            "This $name can't hold the Vault key", false, "Vault documents will open on your other devices", emptyList(), false,
        )
        val canonical = s.canonical
        if (canonical == null) return VaultKeyView(
            "Your Vault key is made the next time Navilife syncs", false,
            "It locks your documents on this $name before anything leaves it", emptyList(), false,
        )
        val holders = s.members.filter { it.holdsKeyId == canonical }
        if (s.heldKeyId == canonical) {
            val where = (listOf("this $name") + holders.filter { it.deviceId != s.me }.map { if (it.name == name) "your other ${it.name}" else "the ${it.name}" }).distinct()
            val recovery = s.wraps.any { it.id == RECOVERY && it.keyId == canonical }
            val requests = requests(s.members, s.wraps, canonical, s.me).map { m ->
                VaultRequest(
                    m.deviceId,
                    "The ${m.name} wants to open your Vault documents",
                    "Allow only if the ${m.name} shows ${code(m.publicKey)}",
                    "The ${m.name} wants to open your Vault documents. Allow only if it shows ${code(m.publicKey)}.",
                )
            }
            return VaultKeyView(
                "Vault key ${display(canonical)} · on ${names(where)}", false,
                if (recovery) "Your recovery key opens it too"
                else "Make a new recovery key (Settings → Recovery key) so it opens the Vault too",
                requests, false,
            )
        }
        val holderName = holders.firstOrNull { it.deviceId != s.me }?.name
        val other = holderName?.let { if (it == name) "your other $it" else "your $it" } ?: "your other device"
        val mine = s.members.firstOrNull { it.deviceId == s.me }
        val code = s.myPublicKey?.let { code(it) }
        if (mine != null && mine.refusedAtMs != null && mine.refusedAtMs >= mine.askedAtMs) return VaultKeyView(
            "${other.replaceFirstChar { it.uppercase() }} said no to this $name", true,
            "If it was you, Ask again and check the codes match", emptyList(), true,
        )
        return VaultKeyView(
            "Waiting for $other to let this $name open the Vault", true,
            "On ${holderName?.let { "the $it" } ?: "it"}: Ask → Settings → Vault → Allow" + (code?.let { " · this $name shows $it" } ?: ""),
            emptyList(), false,
        )
    }
}

/** The three entity types' reads and writes. */
class VaultKeys(private val replica: Replica, private val nowMs: () -> Long) {
    fun canonical(): String? =
        replica.entity(EntityTypes.VAULT_KEY, VaultKeyRules.HOUSEHOLD)?.fields?.get(VaultKeyFields.KEY_ID)?.textOrNull

    fun members(): List<VaultMember> = replica.entities(EntityTypes.VAULT_MEMBER).mapNotNull { e ->
        val key = e.fields[VaultMemberFields.PUBLIC_KEY]?.textOrNull ?: return@mapNotNull null
        VaultMember(
            e.ref.entityId, key,
            e.fields[VaultMemberFields.NAME]?.textOrNull ?: "device",
            e.fields[VaultMemberFields.ASKED_AT]?.longOrNull ?: 0L,
            e.fields[VaultMemberFields.REFUSED_AT]?.longOrNull,
            e.fields[VaultMemberFields.HOLDS]?.textOrNull,
        )
    }

    fun member(deviceId: String): VaultMember? = members().firstOrNull { it.deviceId == deviceId }

    fun wraps(): List<VaultWrap> = replica.entities(EntityTypes.VAULT_WRAP).mapNotNull { e ->
        VaultWrap(
            e.ref.entityId,
            e.fields[VaultWrapFields.KEY_ID]?.textOrNull ?: return@mapNotNull null,
            e.fields[VaultWrapFields.EPHEMERAL]?.textOrNull,
            e.fields[VaultWrapFields.SEALED]?.textOrNull ?: return@mapNotNull null,
            e.fields[VaultWrapFields.BY]?.textOrNull ?: "",
            e.fields[VaultWrapFields.AT]?.longOrNull ?: 0L,
        )
    }

    fun wrap(id: String): VaultWrap? = wraps().firstOrNull { it.id == id }

    /** This device's agreement key, written when it's new or changed (which asks afresh). True when written. */
    fun publish(publicKey: String, name: String): Boolean {
        val m = member(replica.deviceId)
        if (m != null && m.publicKey == publicKey && m.name == name) return false
        replica.commitLocal(
            EntityTypes.VAULT_MEMBER, replica.deviceId,
            buildMap {
                put(VaultMemberFields.PUBLIC_KEY, publicKey.fv())
                put(VaultMemberFields.NAME, name.fv())
                if (m?.publicKey != publicKey) put(VaultMemberFields.ASKED_AT, nowMs().fv())
            },
        )
        return true
    }

    fun askAgain(): Boolean {
        member(replica.deviceId) ?: return false
        replica.commitLocal(EntityTypes.VAULT_MEMBER, replica.deviceId, mapOf(VaultMemberFields.ASKED_AT to nowMs().fv()))
        return true
    }

    fun refuse(deviceId: String): Boolean {
        member(deviceId) ?: return false
        replica.commitLocal(EntityTypes.VAULT_MEMBER, deviceId, mapOf(VaultMemberFields.REFUSED_AT to nowMs().fv()))
        return true
    }

    fun markHolds(keyId: String): Boolean {
        val m = member(replica.deviceId) ?: return false
        if (m.holdsKeyId == keyId) return false
        replica.commitLocal(EntityTypes.VAULT_MEMBER, replica.deviceId, mapOf(VaultMemberFields.HOLDS to keyId.fv()))
        return true
    }

    fun setCanonical(keyId: String, madeBy: String) {
        replica.commitLocal(
            EntityTypes.VAULT_KEY, VaultKeyRules.HOUSEHOLD,
            mapOf(VaultKeyFields.KEY_ID to keyId.fv(), VaultKeyFields.MADE_BY to madeBy.fv(), VaultKeyFields.MADE_AT to nowMs().fv()),
        )
    }

    fun writeWrap(id: String, keyId: String, ephemeralKey: String?, sealed: String, byName: String) {
        replica.commitLocal(
            EntityTypes.VAULT_WRAP, id,
            buildMap {
                put(VaultWrapFields.KEY_ID, keyId.fv())
                if (ephemeralKey != null) put(VaultWrapFields.EPHEMERAL, ephemeralKey.fv())
                put(VaultWrapFields.SEALED, sealed.fv())
                put(VaultWrapFields.BY, byName.fv())
                put(VaultWrapFields.AT, nowMs().fv())
            },
        )
    }
}
