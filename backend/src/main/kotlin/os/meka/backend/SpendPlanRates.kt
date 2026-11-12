package os.meka.backend

import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.PoundRates
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.SpendPlanFields
import os.meka.core.domain.SpendPlanRules
import os.meka.core.sync.DELETED_FIELD
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import java.security.SecureRandom

/**
 * Spending plans, slice 3: today's rate for every plan in another currency. The server reads the pound's rates
 * ([PoundRates], a free public service, nothing about Meka sent) and writes the plan's currency's rate, its day and its
 * code onto the `spend_plan` entity as server ops, only when they change, so it reaches the Fold and the Mac through
 * ordinary sync. His own typed rate stays his and wins on the devices. A calculator's input only: nothing is sent or
 * moved. Only for a sole household. Never logs a plan or a figure.
 */
class SpendPlanRates(
    private val ops: ServerOpStore,
    private val read: (householdId: String, entityType: String) -> Map<String, Map<String, Op>>,
    private val household: () -> String?,
    private val rates: PoundRates,
    private val now: () -> Long = System::currentTimeMillis,
    private val onWritten: (householdId: String) -> Unit = {},
) {
    private val clock = HlcClock(Integrations.SERVER_DEVICE, now)
    private val rng = SecureRandom()

    @Volatile var lastReport: String? = null
        private set

    /** Writes today's rate onto each plan in another currency that lacks it; returns whether anything changed. */
    fun tick(): Boolean {
        val hh = household() ?: return false
        val heads = read(hh, EntityTypes.SPEND_PLAN)
        val wanting = heads.filter { (_, f) -> !deleted(f) && foreign(f) != null }
        if (wanting.isEmpty()) return false
        val r = runCatching { rates.read() }.getOrNull() ?: return false
        var changed = 0
        for ((id, f) in wanting) {
            val code = foreign(f) ?: continue
            val milli = r.milliPerPound[code] ?: continue
            val fields = linkedMapOf<String, FieldValue>(
                SpendPlanFields.DAY_RATE to FieldValue.Int64(milli),
                SpendPlanFields.DAY_RATE_DAY to FieldValue.Int64(r.day),
                SpendPlanFields.DAY_RATE_CODE to FieldValue.Text(code),
            )
            // He may have removed it, or changed its currency, meanwhile: then write nothing.
            val latest = read(hh, EntityTypes.SPEND_PLAN)[id] ?: continue
            if (deleted(latest) || foreign(latest) != code) continue
            if (ops.transaction { write(hh, id, fields, latest) }) changed++
        }
        if (changed > 0) {
            lastReport = "$changed plan(s) given today's rate"
            runCatching { onWritten(hh) }
        }
        return changed > 0
    }

    private fun deleted(f: Map<String, Op>) =
        (f[DELETED_FIELD]?.value as? FieldValue.Bool)?.value == true || (f[SpendPlanFields.DELETED]?.value as? FieldValue.Bool)?.value == true

    /** The plan's currency when it's one of the plan currencies other than the pound. */
    private fun foreign(f: Map<String, Op>): String? {
        val code = (f[SpendPlanFields.CURRENCY]?.value as? FieldValue.Text)?.value ?: return null
        return code.takeIf { c -> c != SpendPlanRules.GBP && SpendPlanRules.CURRENCIES.any { it.code == c } }
    }

    /** Appends the fields whose value differs from the head; returns whether any did. */
    private fun write(hh: String, id: String, fields: Map<String, FieldValue>, heads: Map<String, Op>): Boolean {
        var news = false
        for ((field, value) in fields) {
            val last = heads[field]
            if ((last?.value ?: FieldValue.Null) == value) continue
            val hlc = synchronized(clock) {
                last?.let { runCatching { clock.receive(it.hlc) } }
                clock.now()
            }
            ops.append(Op(
                opId = "srvrate" + hex(12), householdId = hh, entityType = EntityTypes.SPEND_PLAN, entityId = id, field = field,
                value = value, hlc = hlc, baseOpIds = listOfNotNull(last?.opId), deviceId = Integrations.SERVER_DEVICE,
            ))
            news = true
        }
        return news
    }

    private fun hex(bytes: Int): String = ByteArray(bytes).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** How often the loop looks for a plan wanting a rate (the rates themselves are read at most every six hours). */
        const val LOOK_MS = 60_000L
    }
}

/** Looks two minutes after start, then every [SpendPlanRates.LOOK_MS]. */
fun startSpendPlanRates(sync: SpendPlanRates) {
    Thread.ofVirtual().name("spend-plan-rates").start {
        Thread.sleep(120_000)
        while (true) {
            runCatching { sync.tick() }
                .onSuccess { wrote -> if (wrote) System.err.println("spend plan rates: ${sync.lastReport}") }
                .onFailure { System.err.println("spend plan rates failed: ${it::class.simpleName}") }
            Thread.sleep(SpendPlanRates.LOOK_MS)
        }
    }
}
