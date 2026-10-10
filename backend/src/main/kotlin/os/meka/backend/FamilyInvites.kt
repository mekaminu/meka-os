package os.meka.backend

import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * An invite to Meka's family page (build plan "Family sharing with Jeanette", slice 2). The link's token is never
 * stored, only its SHA-256; the first browser to open it registers its own P-256 key ([publicKey]), and from then on
 * only that browser's signed requests are let in, so the link alone is no use. Revoking shuts it at once.
 */
data class FamilyInvite(
    val householdId: String,
    val id: String,
    /** The family member's name key ("jeanette"): what her items carry in `by`, shown as "From Jeanette". */
    val name: String,
    val createdAtMs: Long,
    val claimedAtMs: Long? = null,
    val revokedAtMs: Long? = null,
    val lastSeenAtMs: Long? = null,
    val publicKey: String? = null,
) {
    /** waiting (not opened yet) · joined (her browser holds the key) · revoked. */
    val state: String get() = when {
        revokedAtMs != null -> REVOKED
        publicKey != null -> JOINED
        else -> WAITING
    }

    companion object {
        const val WAITING = "waiting"
        const val JOINED = "joined"
        const val REVOKED = "revoked"
    }
}

interface FamilyInviteStore {
    fun create(invite: FamilyInvite, tokenSha256: String)
    fun byToken(tokenSha256: String): FamilyInvite?
    fun byId(id: String): FamilyInvite?
    /** Registers the browser's key once. False when the invite is revoked or already has a key. */
    fun claim(id: String, publicKey: String, nowMs: Long): Boolean
    /** False when [id] isn't one of [householdId]'s invites. Revoking twice keeps the first time. */
    fun revoke(householdId: String, id: String, nowMs: Long): Boolean
    /** Newest first. */
    fun list(householdId: String): List<FamilyInvite>
    fun seen(id: String, nowMs: Long)
}

class InMemoryFamilyInviteStore : FamilyInviteStore {
    private val byId = LinkedHashMap<String, FamilyInvite>()
    private val tokens = HashMap<String, String>()

    @Synchronized override fun create(invite: FamilyInvite, tokenSha256: String) {
        byId[invite.id] = invite
        tokens[tokenSha256] = invite.id
    }

    @Synchronized override fun byToken(tokenSha256: String) = tokens[tokenSha256]?.let { byId[it] }
    @Synchronized override fun byId(id: String) = byId[id]

    @Synchronized override fun claim(id: String, publicKey: String, nowMs: Long): Boolean {
        val i = byId[id] ?: return false
        if (i.revokedAtMs != null || i.publicKey != null) return false
        byId[id] = i.copy(publicKey = publicKey, claimedAtMs = nowMs, lastSeenAtMs = nowMs)
        return true
    }

    @Synchronized override fun revoke(householdId: String, id: String, nowMs: Long): Boolean {
        val i = byId[id]?.takeIf { it.householdId == householdId } ?: return false
        if (i.revokedAtMs == null) byId[id] = i.copy(revokedAtMs = nowMs)
        return true
    }

    @Synchronized override fun list(householdId: String) =
        byId.values.filter { it.householdId == householdId }.sortedByDescending { it.createdAtMs }

    @Synchronized override fun seen(id: String, nowMs: Long) {
        byId[id]?.let { byId[id] = it.copy(lastSeenAtMs = nowMs) }
    }
}

class PostgresFamilyInviteStore(private val ds: DataSource) : FamilyInviteStore {
    override fun create(invite: FamilyInvite, tokenSha256: String) {
        ds.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO family_invite(id, household_id, name, token_sha256, created_at) VALUES (?,?,?,?,?)",
            ).use {
                it.setString(1, invite.id); it.setString(2, invite.householdId); it.setString(3, invite.name)
                it.setString(4, tokenSha256); it.setTimestamp(5, Timestamp(invite.createdAtMs))
                it.executeUpdate()
            }
        }
    }

    override fun byToken(tokenSha256: String): FamilyInvite? = one("token_sha256 = ?", tokenSha256)
    override fun byId(id: String): FamilyInvite? = one("id = ?", id)

    override fun claim(id: String, publicKey: String, nowMs: Long): Boolean = ds.connection.use { c ->
        c.prepareStatement(
            "UPDATE family_invite SET public_key = ?, claimed_at = ?, last_seen_at = ? WHERE id = ? AND public_key IS NULL AND revoked_at IS NULL",
        ).use {
            it.setString(1, publicKey); it.setTimestamp(2, Timestamp(nowMs)); it.setTimestamp(3, Timestamp(nowMs)); it.setString(4, id)
            it.executeUpdate() == 1
        }
    }

    override fun revoke(householdId: String, id: String, nowMs: Long): Boolean = ds.connection.use { c ->
        c.prepareStatement(
            "UPDATE family_invite SET revoked_at = COALESCE(revoked_at, ?) WHERE household_id = ? AND id = ?",
        ).use {
            it.setTimestamp(1, Timestamp(nowMs)); it.setString(2, householdId); it.setString(3, id)
            it.executeUpdate() == 1
        }
    }

    override fun list(householdId: String): List<FamilyInvite> = ds.connection.use { c ->
        c.prepareStatement("SELECT * FROM family_invite WHERE household_id = ? ORDER BY created_at DESC LIMIT 50").use { st ->
            st.setString(1, householdId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toInvite()) } }
        }
    }

    override fun seen(id: String, nowMs: Long) {
        ds.connection.use { c ->
            c.prepareStatement("UPDATE family_invite SET last_seen_at = ? WHERE id = ?").use {
                it.setTimestamp(1, Timestamp(nowMs)); it.setString(2, id); it.executeUpdate()
            }
        }
    }

    private fun one(where: String, value: String): FamilyInvite? = ds.connection.use { c ->
        c.prepareStatement("SELECT * FROM family_invite WHERE $where").use { st ->
            st.setString(1, value)
            st.executeQuery().use { rs -> if (rs.next()) rs.toInvite() else null }
        }
    }

    private fun ResultSet.toInvite() = FamilyInvite(
        householdId = getString("household_id"), id = getString("id"), name = getString("name"),
        createdAtMs = getTimestamp("created_at").time,
        claimedAtMs = getTimestamp("claimed_at")?.time,
        revokedAtMs = getTimestamp("revoked_at")?.time,
        lastSeenAtMs = getTimestamp("last_seen_at")?.time,
        publicKey = getString("public_key"),
    )
}
