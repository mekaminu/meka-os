package os.meka.backend

import os.meka.core.wire.WireCodec
import javax.sql.DataSource

/** One device's push address. The token is opaque (an FCM registration token) and never logged. */
data class PushAddress(val deviceId: String, val service: String, val token: String)

/** Where devices' push addresses live (build plan M1: push via Firebase). One per device. */
interface PushTokenStore {
    fun put(device: DeviceIdentity, service: String, token: String)
    fun remove(device: DeviceIdentity)
    /** FCM said the token is gone (app uninstalled, data cleared): forget it wherever it is. */
    fun forget(householdId: String, token: String)
    /** The household's addresses, revoked devices left out. */
    fun household(householdId: String): List<PushAddress>
}

/** What one send came to. */
enum class SendResult {
    SENT,
    /** The token no longer exists; the server forgets it. */
    GONE,
    FAILED,
    /** Push isn't set up on the server (no service account yet). */
    OFF,
}

/**
 * Sends a "sync now" wake-up. The message carries nothing about Meka's data: only `{"t": "sync"}`, so Google sees
 * that a device was woken and nothing else (no titles, no ids). The app pulls the change over its own signed sync.
 */
fun interface PushSender {
    fun wake(address: PushAddress): SendResult
}

/**
 * Wakes the household's other devices when one of them stored ops, so an edit on the Mac reaches the Fold in seconds
 * rather than at its next 15-minute background sync. Coalesced per device: at most one wake every [minGapMs]; edits
 * inside the gap are covered by one trailing wake at its end, so the last edit always gets through. Sends happen off
 * the request ([schedule]); a failure only means the device catches up at its next sync, as before.
 */
class Push(
    private val store: PushTokenStore,
    private val sender: PushSender,
    private val now: () -> Long = System::currentTimeMillis,
    private val minGapMs: Long = 20_000,
    /** Runs [task] after [delayMs] off the caller's thread. */
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit = { d, task ->
        Thread.ofVirtual().name("push").start { if (d > 0) Thread.sleep(d); runCatching(task) }
    },
) {
    private data class Key(val householdId: String, val deviceId: String)
    private val lastSentAt = HashMap<Key, Long>()
    private val pending = HashSet<Key>()

    fun register(who: DeviceIdentity, t: WireCodec.PushToken) {
        if (t.token.isEmpty()) store.remove(who) else store.put(who, t.service, t.token)
    }

    /** [from] stored ops: wake every other device of its household that has an address. */
    fun changed(from: DeviceIdentity) {
        val targets = store.household(from.householdId).filter { it.deviceId != from.deviceId }
        for (a in targets) {
            val delay = plan(Key(from.householdId, a.deviceId)) ?: continue
            schedule(delay) { send(Key(from.householdId, a.deviceId)) }
        }
    }

    /** Null when a wake is already on its way; else how long to wait before sending it. */
    @Synchronized
    private fun plan(k: Key): Long? {
        if (k in pending) return null
        pending += k
        val last = lastSentAt[k] ?: return 0
        return (last + minGapMs - now()).coerceAtLeast(0)
    }

    private fun send(k: Key) {
        synchronized(this) { pending -= k; lastSentAt[k] = now() }
        // Read the address at send time: the device may have changed or removed it meanwhile.
        val a = store.household(k.householdId).firstOrNull { it.deviceId == k.deviceId } ?: return
        if (sender.wake(a) == SendResult.GONE) store.forget(k.householdId, a.token)
    }
}

class InMemoryPushTokenStore : PushTokenStore {
    private val byDevice = LinkedHashMap<DeviceIdentity, PushAddress>()

    @Synchronized override fun put(device: DeviceIdentity, service: String, token: String) {
        byDevice[device] = PushAddress(device.deviceId, service, token)
    }
    @Synchronized override fun remove(device: DeviceIdentity) { byDevice.remove(device) }
    @Synchronized override fun forget(householdId: String, token: String) {
        byDevice.entries.removeAll { it.key.householdId == householdId && it.value.token == token }
    }
    @Synchronized override fun household(householdId: String) = byDevice.filterKeys { it.householdId == householdId }.values.toList()
}

class PostgresPushTokenStore(private val ds: DataSource) : PushTokenStore {
    override fun put(device: DeviceIdentity, service: String, token: String) = ds.connection.use { c ->
        c.prepareStatement(
            """INSERT INTO push_token(household_id, device_id, service, token) VALUES (?,?,?,?)
               ON CONFLICT (household_id, device_id) DO UPDATE SET service = EXCLUDED.service, token = EXCLUDED.token, updated_at = now()""",
        ).use {
            it.setString(1, device.householdId); it.setString(2, device.deviceId); it.setString(3, service); it.setString(4, token)
            it.executeUpdate()
        }
        Unit
    }

    override fun remove(device: DeviceIdentity) = ds.connection.use { c ->
        c.prepareStatement("DELETE FROM push_token WHERE household_id = ? AND device_id = ?").use {
            it.setString(1, device.householdId); it.setString(2, device.deviceId); it.executeUpdate()
        }
        Unit
    }

    override fun forget(householdId: String, token: String) = ds.connection.use { c ->
        c.prepareStatement("DELETE FROM push_token WHERE household_id = ? AND token = ?").use {
            it.setString(1, householdId); it.setString(2, token); it.executeUpdate()
        }
        Unit
    }

    override fun household(householdId: String): List<PushAddress> = ds.connection.use { c ->
        c.prepareStatement(
            """SELECT p.device_id, p.service, p.token FROM push_token p
               JOIN device d ON d.household_id = p.household_id AND d.id = p.device_id
               WHERE p.household_id = ? AND d.revoked_at IS NULL ORDER BY p.device_id""",
        ).use { st ->
            st.setString(1, householdId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(PushAddress(rs.getString(1), rs.getString(2), rs.getString(3))) } }
        }
    }
}
