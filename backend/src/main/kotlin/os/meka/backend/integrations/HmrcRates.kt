package os.meka.backend.integrations

import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** HMRC's US dollars to £1 for a calendar month ([month] as yyyymm), or null when it can't be read just now. */
fun interface MonthlyDollarRates {
    /** US dollars per £1 × 1,000,000 for [month] (e.g. 202609). */
    fun usdPerGbpMicros(month: Int): Long?
}

/**
 * Trading tracker, slice 10: HMRC's monthly exchange rates (the ones HMRC publishes for each calendar month and accepts
 * for converting foreign amounts), from the UK Trade Tariff service's public files
 * (`https://www.trade-tariff.service.gov.uk/api/v2/exchange_rates/files/monthly_csv_YYYY-MM.csv`). A plain public GET:
 * nothing about Meka is sent, no key, free. A month's rate never changes once published, so a good read is kept for
 * good; a failed one is tried again after [RETRY_MS].
 */
class HmrcMonthlyRates internal constructor(
    private val http: Http,
    private val now: () -> Long = System::currentTimeMillis,
) : MonthlyDollarRates {
    /** (status, body) for a GET of [url]; injected so tests never touch the network. */
    fun interface Http { fun get(url: String): Pair<Int, String> }

    private val known = ConcurrentHashMap<Int, Long>()
    private val triedAt = ConcurrentHashMap<Int, Long>()

    override fun usdPerGbpMicros(month: Int): Long? {
        known[month]?.let { return it }
        if (month % 100 !in 1..12 || month / 100 !in 2019..2100) return null
        val t = now()
        val last = triedAt[month]
        if (last != null && t - last < RETRY_MS) return null
        triedAt[month] = t
        val rate = runCatching {
            val (status, body) = http.get(url(month))
            if (status == 200 && body.length <= MAX_BYTES) parse(body) else null
        }.getOrNull() ?: return null
        known[month] = rate
        return rate
    }

    companion object {
        const val RETRY_MS = 30 * 60_000L
        private const val MAX_BYTES = 200_000

        fun url(month: Int): String =
            "https://www.trade-tariff.service.gov.uk/api/v2/exchange_rates/files/monthly_csv_${month / 100}-${(month % 100).toString().padStart(2, '0')}.csv"

        /**
         * HMRC's monthly CSV → US dollars per £1 × 1,000,000: the header row names a "Currency Code" column and a
         * "Currency Units per £1" column (in whatever order); the row whose code is USD gives the rate. Null when the
         * file has no such row or the rate is unbelievable.
         */
        fun parse(csv: String): Long? {
            val rows = csv.lineSequence().map { it.trim().removePrefix("﻿") }.filter { it.isNotEmpty() }.map(::cells).toList()
            val headerAt = rows.indexOfFirst { r -> r.any { it.contains("currency code", true) } }
            if (headerAt < 0) return null
            val header = rows[headerAt]
            val codeCol = header.indexOfFirst { it.contains("currency code", true) }
            val rateCol = header.indexOfFirst { it.contains("per £1", true) || it.contains("per gbp", true) || it.contains("units per", true) }
            if (codeCol < 0 || rateCol < 0) return null
            val usd = rows.drop(headerAt + 1).firstOrNull { it.getOrNull(codeCol).equals("USD", true) } ?: return null
            val rate = usd.getOrNull(rateCol)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
            val micros = rate.movePointRight(6).setScale(0, RoundingMode.HALF_UP).toLong()
            return micros.takeIf { it in 500_000L..3_000_000L }
        }

        /** One CSV row's cells (quotes honoured). */
        private fun cells(line: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { out += cur.toString().trim(); cur.clear() }
                    else -> cur.append(c)
                }
                i++
            }
            out += cur.toString().trim()
            return out
        }

        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

        val NETWORK = Http { url ->
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("Accept", "text/csv").GET().build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            res.statusCode() to res.body()
        }

        fun network(): HmrcMonthlyRates = HmrcMonthlyRates(NETWORK)
    }
}
