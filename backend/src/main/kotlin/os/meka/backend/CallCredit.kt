package os.meka.backend

import os.meka.backend.integrations.Integrations
import os.meka.core.domain.CallCreditReading
import os.meka.core.domain.CallCreditRules
import os.meka.core.domain.EntityTypes
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import java.security.SecureRandom

/** Where the call assistant's credit is read from (the phone service; [TwilioVoice]). */
interface CreditSource {
    /** Whether the account's details are in place; while not, nothing is read or written. */
    fun creditConfigured(): Boolean
    /** The balance and account status, or null when the read failed. */
    fun readCredit(): CallCreditReading?
}

/**
 * The call assistant's low-balance guard (build plan M1, call assistant; Meka, 2026-10-08: Twilio's auto-recharge is
 * off by choice). A few times a day the server reads the phone service's balance and account status and writes the
 * outcome into `context_mode/call_credit` ([CallCreditRules]; ADR-008 addendum 2026-10-10) as server ops, only the
 * fields that changed, each over the field's current head. A change of state wakes the household's devices, so Needs
 * you's card, the heads-up and the Fold's paused screening follow within minutes.
 *
 * A failed read keeps what the devices have; reads failing for a day ([CallCreditRules.UNREADABLE_AFTER_MS]) pause the
 * assistant, since a call sent to it might not be answered.
 */
class CallCreditWatch(
    private val ops: ServerOpStore,
    /** The latest op per field of `context_mode/call_credit` for a household. */
    private val read: (householdId: String) -> Map<String, Op>,
    private val household: () -> String?,
    private val source: CreditSource,
    private val now: () -> Long = System::currentTimeMillis,
    private val onWritten: (householdId: String) -> Unit = {},
) {
    private val clock = HlcClock(Integrations.SERVER_DEVICE, now)
    private val rng = SecureRandom()
    /** When reads started failing in this process (null while they work). */
    @Volatile private var failingSince: Long? = null

    /** Whether the last read worked (the loop checks again sooner when it didn't). */
    val failing: Boolean get() = failingSince != null

    /** Reads the credit and writes what changed; returns whether anything was written. */
    fun check(): Boolean {
        if (!runCatching { source.creditConfigured() }.getOrDefault(false)) return false
        val hh = household() ?: return false
        val reading = runCatching { source.readCredit() }.getOrNull()
        val t = now()
        failingSince = if (reading != null && (reading.accountActive == false || CallCreditRules.pence(reading.balance) != null)) null
        else failingSince ?: t
        val current = read(hh)
        val previous = CallCreditRules.read { current[it]?.value }
        val next = CallCreditRules.assess(reading, previous, t, failingSince) ?: return false
        val writes = CallCreditRules.fields(next).filter { (field, value) -> (current[field]?.value ?: FieldValue.Null) != value }
        if (writes.isEmpty()) return false
        ops.transaction {
            for ((field, value) in writes) {
                val last = current[field]
                val hlc = synchronized(clock) {
                    last?.let { runCatching { clock.receive(it.hlc) } }
                    clock.now()
                }
                ops.append(
                    Op(
                        opId = "srvcredit" + hex(12), householdId = hh, entityType = EntityTypes.CONTEXT_MODE,
                        entityId = CallCreditRules.ENTITY_ID, field = field, value = value, hlc = hlc,
                        baseOpIds = listOfNotNull(last?.opId), deviceId = Integrations.SERVER_DEVICE,
                    ),
                )
            }
        }
        runCatching { onWritten(hh) }
        return true
    }

    private fun hex(bytes: Int): String = ByteArray(bytes).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** Every op of the credit entity, newest per field: for tests and the in-memory store. */
        fun scanning(ops: ServerOpStore): (String) -> Map<String, Op> = { hh ->
            val out = LinkedHashMap<String, Op>()
            ops.after(hh, 0, Int.MAX_VALUE).map { it.op }
                .filter { it.entityType == EntityTypes.CONTEXT_MODE && it.entityId == CallCreditRules.ENTITY_ID }
                .forEach { op -> val prev = out[op.field]; if (prev == null || op.hlc > prev.hlc) out[op.field] = op }
            out
        }

        /** How often the balance is read while reads work, and how soon a failed read is tried again. */
        const val PERIOD_MS = 6 * 60 * 60_000L
        const val RETRY_MS = 60 * 60_000L
    }
}

/** Reads the call assistant's credit two minutes after start, then every 6 hours (hourly while reads fail). */
fun startCreditWatch(watch: CallCreditWatch) {
    Thread.ofVirtual().name("call-credit").start {
        Thread.sleep(120_000)
        while (true) {
            runCatching { watch.check() }
                .onSuccess { wrote -> if (wrote) System.err.println("call credit: state written") }
                .onFailure { System.err.println("call credit check failed: ${it::class.simpleName}") }
            Thread.sleep(if (watch.failing) CallCreditWatch.RETRY_MS else CallCreditWatch.PERIOD_MS)
        }
    }
}
