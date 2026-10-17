package os.meka.backend

import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.TradingFeed
import os.meka.backend.integrations.TradingRateLimited
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.TradingTracker
import os.meka.core.domain.TradingWallet
import os.meka.core.domain.TradingWalletFields
import os.meka.core.sync.DELETED_FIELD
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Op
import os.meka.core.sync.ServerOpStore
import java.security.SecureRandom

/**
 * Trading tracker, slice 1: for each Hyperliquid public address Meka follows in Money (`trading_wallet`), the server
 * reads the account from Hyperliquid's public Info API ([TradingFeed]) at [TradingTracker.due]'s pace and writes what
 * it read onto the same entity as server ops — only the fields whose value changed, each over the field's head — so it
 * reaches the Fold and the Mac through ordinary sync. Read-only, no key, nothing that can trade. Only for a sole
 * household. Never logs an address or a figure: [lastReport] holds counts only.
 */
class TradingSync(
    private val ops: ServerOpStore,
    private val read: (householdId: String, entityType: String) -> Map<String, Map<String, Op>>,
    private val household: () -> String?,
    private val feed: TradingFeed,
    private val now: () -> Long = System::currentTimeMillis,
    private val onWritten: (householdId: String) -> Unit = {},
) {
    private val clock = HlcClock(Integrations.SERVER_DEVICE, now)
    private val rng = SecureRandom()

    @Volatile var lastReport: String? = null
        private set

    /** Reads every address that's due; returns whether anything a device shows changed. */
    fun tick(): Boolean {
        val hh = household() ?: return false
        val heads = read(hh, EntityTypes.TRADING_WALLET)
        var changed = false
        var ok = 0
        var failed = 0
        for ((id, f) in heads) {
            if ((f[DELETED_FIELD]?.value as? FieldValue.Bool)?.value == true) continue
            val w = wallet(id, f) ?: continue
            if (!TradingTracker.due(w, now())) continue
            val t = now()
            val fields = linkedMapOf<String, FieldValue>(TradingWalletFields.TRIED_AT to FieldValue.Int64(t))
            try {
                val a = feed.read(w.address)
                fields[TradingWalletFields.STATE] = FieldValue.Text(TradingTracker.OK)
                fields[TradingWalletFields.READ_AT] = FieldValue.Int64(t)
                fields[TradingWalletFields.EQUITY] = FieldValue.Int64(a.equityCents)
                fields[TradingWalletFields.PNL_DAY] = a.pnlDayCents?.let { FieldValue.Int64(it) } ?: FieldValue.Null
                fields[TradingWalletFields.PNL_WEEK] = a.pnlWeekCents?.let { FieldValue.Int64(it) } ?: FieldValue.Null
                fields[TradingWalletFields.PNL_MONTH] = a.pnlMonthCents?.let { FieldValue.Int64(it) } ?: FieldValue.Null
                fields[TradingWalletFields.PNL_ALL] = a.pnlAllCents?.let { FieldValue.Int64(it) } ?: FieldValue.Null
                fields[TradingWalletFields.UNREALISED] = FieldValue.Int64(a.unrealisedCents)
                fields[TradingWalletFields.POSITIONS] = FieldValue.Text(TradingTracker.encode(a.positions))
                // Slice 2: the window's trades, hourly; a failure here keeps the last ones and tries at the next read.
                if (TradingTracker.tradesDue(w, t)) {
                    val from = t - TradingTracker.TRADES_DAYS * 86_400_000L
                    runCatching { feed.trades(w.address, from, t) }.getOrNull()?.let { stats ->
                        fields[TradingWalletFields.TRADES] = FieldValue.Text(TradingTracker.encodeTrades(stats))
                        fields[TradingWalletFields.TRADES_AT] = FieldValue.Int64(t)
                        fields[TradingWalletFields.TRADES_FROM] = FieldValue.Int64(from)
                    }
                }
                ok++
            } catch (e: TradingRateLimited) {
                // Not a failure: the try time alone, so it waits its usual pace.
                failed++
            } catch (e: Exception) {
                fields[TradingWalletFields.STATE] = FieldValue.Text(TradingTracker.ERROR)
                failed++
            }
            // He may have removed it while it was being read: then write nothing.
            val latest = read(hh, EntityTypes.TRADING_WALLET)[id]
            if ((latest?.get(DELETED_FIELD)?.value as? FieldValue.Bool)?.value == true ||
                (latest?.get(TradingWalletFields.ADDRESS)?.value as? FieldValue.Text)?.value?.lowercase() != w.address) continue
            if (ops.transaction { write(hh, id, fields, latest) }) changed = true
        }
        if (ok + failed > 0) lastReport = "$ok read · $failed failed"
        if (changed) runCatching { onWritten(hh) }
        return changed
    }

    private fun wallet(id: String, f: Map<String, Op>): TradingWallet? {
        val address = (f[TradingWalletFields.ADDRESS]?.value as? FieldValue.Text)?.value?.trim()?.lowercase()
            ?.takeIf { TradingTracker.isAddress(it) } ?: return null
        fun long(k: String) = (f[k]?.value as? FieldValue.Int64)?.value
        return TradingWallet(
            id, address, "", (f[TradingWalletFields.STATE]?.value as? FieldValue.Text)?.value, long(TradingWalletFields.READ_AT),
            long(TradingWalletFields.TRIED_AT), long(TradingWalletFields.EQUITY), null, null, null, null, null,
            TradingTracker.decode((f[TradingWalletFields.POSITIONS]?.value as? FieldValue.Text)?.value),
            tradesAtMs = long(TradingWalletFields.TRADES_AT),
        )
    }

    /** Appends the fields whose value differs from the head; returns whether any but the read/try times did. */
    private fun write(hh: String, id: String, fields: Map<String, FieldValue>, heads: Map<String, Op>?): Boolean {
        var news = false
        for ((field, value) in fields) {
            val last = heads?.get(field)
            if ((last?.value ?: FieldValue.Null) == value) continue
            val hlc = synchronized(clock) {
                last?.let { runCatching { clock.receive(it.hlc) } }
                clock.now()
            }
            ops.append(Op(
                opId = "srvtrade" + hex(12), householdId = hh, entityType = EntityTypes.TRADING_WALLET, entityId = id, field = field,
                value = value, hlc = hlc, baseOpIds = listOfNotNull(last?.opId), deviceId = Integrations.SERVER_DEVICE,
            ))
            if (field !in QUIET) news = true
        }
        return news
    }

    private fun hex(bytes: Int): String = ByteArray(bytes).also(rng::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** How often the loop looks for an address that's due (a newly added one is read within this). */
        const val LOOK_MS = 30_000L

        /** Fields that move on every read and alone don't wake the devices. */
        private val QUIET = setOf(TradingWalletFields.TRIED_AT, TradingWalletFields.READ_AT, TradingWalletFields.TRADES_AT, TradingWalletFields.TRADES_FROM)
    }
}

/** Looks a minute after start, then every [TradingSync.LOOK_MS]. */
fun startTradingSync(sync: TradingSync) {
    Thread.ofVirtual().name("trading-sync").start {
        Thread.sleep(60_000)
        while (true) {
            runCatching { sync.tick() }
                .onSuccess { wrote -> if (wrote) System.err.println("trading sync: ${sync.lastReport}") }
                .onFailure { System.err.println("trading sync failed: ${it::class.simpleName}") }
            Thread.sleep(TradingSync.LOOK_MS)
        }
    }
}
