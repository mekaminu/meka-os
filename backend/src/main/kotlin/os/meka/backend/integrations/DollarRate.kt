package os.meka.backend.integrations

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate

/** Pounds per US dollar × 1,000,000 and the (epoch) day the rate is for. */
data class DollarRateRead(val gbpPerUsdMicros: Long, val day: Long)

/** Today's pounds-per-dollar rate, or null when it can't be read just now. */
fun interface DollarRate {
    fun read(): DollarRateRead?
}

/**
 * Trading tracker, slice 5: the European Central Bank's daily euro reference rates
 * (`https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml`, published about 16:00 CET on working days), from
 * which pounds per dollar = GBP's rate ÷ USD's. A plain public GET: nothing about Meka is sent, no key, free. Read at
 * most every [EVERY_MS]; a failed read keeps the last good rate (and is tried again after [RETRY_MS]).
 */
class EcbDollarRate internal constructor(
    private val http: Http,
    private val now: () -> Long = System::currentTimeMillis,
) : DollarRate {
    /** (status, body) for a GET of [url]; injected so tests never touch the network. */
    fun interface Http { fun get(url: String): Pair<Int, String> }

    @Volatile private var last: DollarRateRead? = null
    @Volatile private var triedAt = Long.MIN_VALUE / 2
    @Volatile private var readAt = Long.MIN_VALUE / 2

    override fun read(): DollarRateRead? {
        val t = now()
        if (last != null && t - readAt < EVERY_MS) return last
        if (t - triedAt < RETRY_MS) return last
        triedAt = t
        runCatching {
            val (status, body) = http.get(URL)
            if (status == 200 && body.length <= MAX_BYTES) parse(body) else null
        }.getOrNull()?.let { last = it; readAt = t }
        return last
    }

    companion object {
        const val URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"
        const val EVERY_MS = 6 * 60 * 60_000L
        const val RETRY_MS = 30 * 60_000L
        private const val MAX_BYTES = 64_000

        private val TIME = Regex("""time=['"](\d{4})-(\d{2})-(\d{2})['"]""")
        private fun rate(code: String) = Regex("""currency=['"]$code['"]\s+rate=['"](\d+(?:\.\d+)?)['"]""")

        /** The ECB's daily file → pounds per dollar; null when either rate or the day is missing or unbelievable. */
        fun parse(xml: String): DollarRateRead? {
            val d = TIME.find(xml) ?: return null
            val usd = rate("USD").find(xml)?.groupValues?.get(1)?.let(::BigDecimal)?.takeIf { it.signum() > 0 } ?: return null
            val gbp = rate("GBP").find(xml)?.groupValues?.get(1)?.let(::BigDecimal)?.takeIf { it.signum() > 0 } ?: return null
            val micros = gbp.divide(usd, MathContext.DECIMAL64).movePointRight(6).setScale(0, RoundingMode.HALF_UP).toLong()
            if (micros !in 300_000L..2_000_000L) return null
            val day = runCatching { LocalDate.of(d.groupValues[1].toInt(), d.groupValues[2].toInt(), d.groupValues[3].toInt()).toEpochDay() }
                .getOrNull() ?: return null
            return DollarRateRead(micros, day)
        }

        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

        val NETWORK = Http { url ->
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("Accept", "application/xml").GET().build()
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            res.statusCode() to res.body()
        }

        fun network(): EcbDollarRate = EcbDollarRate(NETWORK)
    }
}
