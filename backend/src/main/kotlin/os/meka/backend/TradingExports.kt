package os.meka.backend

import os.meka.backend.integrations.TradingFeed
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.TradingTracker
import os.meka.core.domain.TradingWalletFields
import os.meka.core.sync.DELETED_FIELD
import os.meka.core.sync.FieldValue
import os.meka.core.sync.Op
import os.meka.core.wire.TradingExportCodec
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Trading tracker, slice 8: the tax export's server half. When a device asks for a window (a UK tax year), every
 * Hyperliquid account the household follows (`trading_wallet`, by public address only) is read from Hyperliquid's
 * public data ([TradingFeed.export]) one after another in the background; the device waits up to its wait and asks
 * again while it is still going. The answer is kept **in memory only** for [KEEP_MS] (nothing financial is stored or
 * logged: [lastReport] holds counts only), so asking twice reads once. Read-only, no key, nothing that can trade.
 */
class TradingExports(
    private val read: (householdId: String, entityType: String) -> Map<String, Map<String, Op>>,
    private val feed: TradingFeed,
    private val now: () -> Long = System::currentTimeMillis,
    private val pool: ExecutorService = Executors.newFixedThreadPool(2) { r -> Thread(r, "trading-export").apply { isDaemon = true } },
) {
    private class Job(val startedAt: Long, val future: CompletableFuture<TradingExportCodec.Response>)

    private val jobs = ConcurrentHashMap<String, Job>()

    @Volatile var lastReport: String? = null
        private set

    fun export(householdId: String, request: TradingExportCodec.Request): TradingExportCodec.Response {
        val t = now()
        jobs.entries.removeIf { (_, j) -> t - j.startedAt > KEEP_MS }
        val key = "$householdId|${request.fromMs}|${request.toMs}"
        val job = jobs.compute(key) { _, old ->
            // A failed read is tried again on the next ask rather than kept.
            val failed = old != null && old.future.isDone &&
                (old.future.isCompletedExceptionally || old.future.getNow(null)?.state == TradingExportCodec.Response.FAILED)
            if (old != null && !failed) old
            else Job(t, CompletableFuture.supplyAsync({ readAll(householdId, request.fromMs, request.toMs) }, pool))
        }!!
        return try {
            job.future.get(request.waitMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            TradingExportCodec.Response(TradingExportCodec.Response.PENDING)
        } catch (e: Exception) {
            TradingExportCodec.Response(TradingExportCodec.Response.FAILED)
        }
    }

    private fun readAll(householdId: String, fromMs: Long, toMs: Long): TradingExportCodec.Response {
        val wallets = read(householdId, EntityTypes.TRADING_WALLET).mapNotNull { (id, f) ->
            if ((f[DELETED_FIELD]?.value as? FieldValue.Bool)?.value == true) return@mapNotNull null
            val address = (f[TradingWalletFields.ADDRESS]?.value as? FieldValue.Text)?.value?.trim()?.lowercase()
                ?.takeIf { TradingTracker.isAddress(it) } ?: return@mapNotNull null
            id to address
        }.take(TradingTracker.MAX_WALLETS)
        val accounts = wallets.map { (id, address) ->
            val r = runCatching { feed.export(address, fromMs, toMs) }.getOrNull()
            if (r == null) TradingExportCodec.Account(id, emptyList(), emptyList(), partial = false, failed = true)
            else TradingExportCodec.Account(
                id,
                r.orders.map { o -> TradingExportCodec.Order(o.atMs, o.coin, o.direction, o.size, o.averagePrice, o.valueCents, o.closedPnlCents, o.feeCents) },
                r.funding.map { f -> TradingExportCodec.Funding(f.day, f.coin, f.cents) },
                partial = r.partial, failed = false,
            )
        }
        lastReport = "${accounts.count { !it.failed }} read · ${accounts.count { it.failed }} failed · ${accounts.sumOf { it.orders.size }} orders"
        return if (accounts.isNotEmpty() && accounts.all { it.failed }) TradingExportCodec.Response(TradingExportCodec.Response.FAILED)
        else TradingExportCodec.Response(TradingExportCodec.Response.OK, accounts)
    }

    companion object {
        /** How long a finished export is kept in memory for the device to collect (or ask again). */
        const val KEEP_MS = 10 * 60_000L
    }
}
