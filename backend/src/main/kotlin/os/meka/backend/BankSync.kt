package os.meka.backend

import os.meka.backend.integrations.BankFeed
import os.meka.backend.integrations.BankKeyRefused
import os.meka.backend.integrations.BankRateLimited
import os.meka.backend.integrations.Integrations
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MoneyAccountFields
import os.meka.core.domain.MoneyBank
import os.meka.core.domain.MoneyBankAccountFields
import os.meka.core.domain.MoneyBankRefreshFields
import os.meka.core.domain.MoneyBankSync
import os.meka.core.domain.MoneyTxnFields
import os.meka.core.sync.DELETED_FIELD
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import java.security.SecureRandom

/**
 * Money manager, bank sync (Needs Meka #38): every few hours, and when Meka taps Refresh in Money, the server reads his
 * accounts, balances and posted transactions through the [BankFeed] (Lunch Flow) and writes them into the household's
 * op log as server ops ([MoneyBank]: `money_account`, `money_txn`, and the state in `context_mode/money_sync`), only the
 * fields that changed, each over the field's current head, so they reach the Fold and the Mac through ordinary sync.
 *
 * Only for a sole household (the key is Meka's own). An account he removed in Navilife (deleted on a device) is left
 * alone, lines and all, until he brings it back. Read-only: nothing is ever written to a bank. Nothing about an account
 * or an amount is logged; [lastReport] holds counts only.
 */
class BankSync(
    private val ops: ServerOpStore,
    /** The latest op per field of every entity of a type in a household. */
    private val read: (householdId: String, entityType: String) -> Map<String, Map<String, Op>>,
    private val household: () -> String?,
    private val feed: BankFeed,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: (Long) -> Long = { ms -> os.meka.backend.LondonCalendar.epochDayOf(ms) },
    private val onWritten: (householdId: String) -> Unit = {},
) {
    private val clock = HlcClock(Integrations.SERVER_DEVICE, now)
    private val rng = SecureRandom()

    /** Syncs when [MoneyBank.due] says so; returns whether anything a device shows changed. */
    fun tick(): Boolean {
        if (!runCatching { feed.configured() }.getOrDefault(false)) return false
        val hh = household() ?: return false
        val modes = read(hh, EntityTypes.CONTEXT_MODE)
        val current = MoneyBank.read { f -> modes[MoneyBank.SYNC_ID]?.get(f)?.value }
        val asked = (modes[MoneyBank.REFRESH_ID]?.get(MoneyBankRefreshFields.ASKED)?.value as? FieldValue.Int64)?.value
        if (!MoneyBank.due(current, asked, now())) return false
        return sync(hh, current)
    }

    /** One sync of [hh]'s banks; returns whether anything a device shows changed. */
    fun sync(hh: String, previous: MoneyBankSync?): Boolean {
        val t = now()
        val day = today(t)
        val accountsNow = read(hh, EntityTypes.MONEY_ACCOUNT)
        var outcome: MoneyBankSync
        var changed = false
        try {
            val accounts = feed.accounts()
            val txnHeads = read(hh, EntityTypes.MONEY_TXN)
            var synced = 0
            var reconnect = 0
            var skipped = 0
            val writes = ArrayList<Triple<String, String, Map<String, FieldValue>>>()
            for (a in accounts) {
                if (a.currency != null && !a.currency.equals("GBP", ignoreCase = true)) { skipped++; continue }
                val id = MoneyBank.accountId(a.id)
                val heads = accountsNow[id]
                // He removed it in Navilife: leave it (and its lines) alone.
                if ((heads?.get(DELETED_FIELD)?.value as? FieldValue.Bool)?.value == true) continue
                synced++
                if (!a.active) reconnect++
                val fields = linkedMapOf<String, FieldValue>(
                    MoneyAccountFields.NAME to FieldValue.Text(MoneyBank.name(a.name, a.institution)),
                    MoneyAccountFields.SOURCE to FieldValue.Text(MoneyBank.SOURCE),
                    MoneyBankAccountFields.STATUS to FieldValue.Text(if (a.active) MoneyBank.OK else MoneyBank.RECONNECT),
                )
                if (heads?.get(DELETED_FIELD) != null) fields[DELETED_FIELD] = FieldValue.Bool(false)
                if (a.active) {
                    feed.balance(a.id)?.takeIf { it.second == null || it.second.equals("GBP", ignoreCase = true) }?.let { (p, _) ->
                        fields[MoneyAccountFields.BALANCE] = FieldValue.Int64(p)
                        fields[MoneyAccountFields.BALANCE_DAY] = FieldValue.Int64(day)
                        fields[MoneyAccountFields.BALANCE_TYPED] = FieldValue.Bool(false)
                    }
                    // First time: as far back as the bank gives; later: from a fortnight before the last sync.
                    val last = (heads?.get(MoneyAccountFields.IMPORTED_AT)?.value as? FieldValue.Int64)?.value
                    val from = if (last == null) day - MoneyBank.FIRST_DAYS else today(last) - MoneyBank.OVERLAP_DAYS
                    for (x in feed.transactions(a.id, from, day)) {
                        if (x.currency != null && !x.currency.equals("GBP", ignoreCase = true)) continue
                        val tid = MoneyBank.txnId(id, x.id)
                        val th = txnHeads[tid]
                        val tf = linkedMapOf<String, FieldValue>(
                            MoneyTxnFields.ACCOUNT to FieldValue.Text(id), MoneyTxnFields.DAY to FieldValue.Int64(x.day),
                            MoneyTxnFields.PENCE to FieldValue.Int64(x.pence), MoneyTxnFields.TEXT to FieldValue.Text(MoneyBank.text(x.merchant, x.description)),
                        )
                        if ((th?.get(DELETED_FIELD)?.value as? FieldValue.Bool)?.value == true) tf[DELETED_FIELD] = FieldValue.Bool(false)
                        writes += Triple(EntityTypes.MONEY_TXN, tid, tf)
                    }
                    fields[MoneyAccountFields.IMPORTED_AT] = FieldValue.Int64(t)
                }
                writes += Triple(EntityTypes.MONEY_ACCOUNT, id, fields)
            }
            ops.transaction {
                for ((type, id, fields) in writes) {
                    val heads = if (type == EntityTypes.MONEY_TXN) txnHeads[id] else accountsNow[id]
                    // The sync time alone isn't news for the devices.
                    if (writeFields(hh, type, id, fields, heads, quiet = setOf(MoneyAccountFields.IMPORTED_AT))) changed = true
                }
            }
            val held = read(hh, EntityTypes.MONEY_ACCOUNT).filter { (id, f) ->
                id.startsWith(MoneyBank.ID_PREFIX) && (f[DELETED_FIELD]?.value as? FieldValue.Bool)?.value != true
            }.keys
            val txnCount = read(hh, EntityTypes.MONEY_TXN).count { (_, f) ->
                (f[MoneyTxnFields.ACCOUNT]?.value as? FieldValue.Text)?.value in held && (f[DELETED_FIELD]?.value as? FieldValue.Bool)?.value != true
            }
            outcome = MoneyBankSync(if (reconnect > 0) MoneyBank.RECONNECT else MoneyBank.OK, t, t, synced, txnCount, reconnect, skipped)
            lastReport = "ok"
        } catch (e: BankRateLimited) {
            // Not a failure: try again at the usual pace, keeping what the devices have.
            writeState(hh, (previous ?: MoneyBankSync(MoneyBank.OK, null, null, 0, 0)).copy(lastTryMs = t))
            lastReport = "rate limited"
            return false
        } catch (e: BankKeyRefused) {
            outcome = (previous ?: MoneyBankSync(MoneyBank.KEY, null, null, 0, 0)).copy(state = MoneyBank.KEY, lastTryMs = t)
            lastReport = "key refused"
        } catch (e: Exception) {
            outcome = (previous ?: MoneyBankSync(MoneyBank.ERROR, null, null, 0, 0)).copy(state = MoneyBank.ERROR, lastTryMs = t)
            lastReport = "failed: ${e::class.simpleName}"
        }
        if (writeState(hh, outcome)) changed = true
        if (changed) runCatching { onWritten(hh) }
        return changed
    }

    /** What the last sync did, for the server's log: "ok", "key refused", "failed: …". Never a figure. */
    @Volatile var lastReport: String? = null
        private set

    /** Writes the state; returns whether more than the try time changed. */
    private fun writeState(hh: String, s: MoneyBankSync): Boolean = ops.transaction {
        val heads = read(hh, EntityTypes.CONTEXT_MODE)[MoneyBank.SYNC_ID]
        writeFields(hh, EntityTypes.CONTEXT_MODE, MoneyBank.SYNC_ID, MoneyBank.fields(s), heads,
            quiet = setOf(os.meka.core.domain.MoneyBankSyncFields.LAST_TRY, os.meka.core.domain.MoneyBankSyncFields.LAST_OK))
    }

    /** Appends the fields whose value differs from the head; returns whether any outside [quiet] did. */
    private fun writeFields(hh: String, type: String, id: String, fields: Map<String, FieldValue>, heads: Map<String, Op>?, quiet: Set<String>): Boolean {
        var news = false
        for ((field, value) in fields) {
            val last = heads?.get(field)
            if ((last?.value ?: FieldValue.Null) == value) continue
            val hlc = synchronized(clock) {
                last?.let { runCatching { clock.receive(it.hlc) } }
                clock.now()
            }
            ops.append(Op(
                opId = "srvbank" + hex(12), householdId = hh, entityType = type, entityId = id, field = field, value = value,
                hlc = hlc, baseOpIds = listOfNotNull(last?.opId), deviceId = Integrations.SERVER_DEVICE,
            ))
            if (field !in quiet) news = true
        }
        return news
    }

    private fun hex(bytes: Int): String = ByteArray(bytes).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** Every op of a type, newest per field per entity: for tests and the in-memory store. */
        fun scanning(ops: ServerOpStore): (String, String) -> Map<String, Map<String, Op>> = { hh, type ->
            val out = LinkedHashMap<String, LinkedHashMap<String, Op>>()
            ops.after(hh, 0, Int.MAX_VALUE).map { it.op }.filter { it.entityType == type }.forEach { op ->
                val m = out.getOrPut(op.entityId) { LinkedHashMap() }
                val prev = m[op.field]
                if (prev == null || op.hlc > prev.hlc) m[op.field] = op
            }
            out
        }

        /** How often the loop looks whether a sync is due (a Refresh is picked up within this). */
        const val LOOK_MS = 30_000L
    }
}

/** Looks a minute after start, then every [BankSync.LOOK_MS]; syncs when due. */
fun startBankSync(sync: BankSync) {
    Thread.ofVirtual().name("bank-sync").start {
        Thread.sleep(60_000)
        while (true) {
            runCatching { sync.tick() }
                .onSuccess { wrote -> if (wrote) System.err.println("bank sync: ${sync.lastReport}") }
                .onFailure { System.err.println("bank sync failed: ${it::class.simpleName}") }
            Thread.sleep(BankSync.LOOK_MS)
        }
    }
}
