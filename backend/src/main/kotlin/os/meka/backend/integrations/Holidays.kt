package os.meka.backend.integrations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import os.meka.core.domain.BankHoliday
import os.meka.core.domain.BankHolidays
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A public list of days off (work mode, Meka 2026-10-07): read-only, no sign-in, nothing about the household is sent.
 * The server mirrors it weekly into one `context_mode/bank_holidays` entity, so the devices have it offline.
 */
interface HolidayProvider {
    val id: String
    /** Shown on the Calendars screen and stored with the list ("GOV.UK · England and Wales"). */
    val label: String
    fun holidays(): List<BankHoliday>
}

/** GOV.UK's bank holidays for England and Wales (https://www.gov.uk/bank-holidays.json, Open Government Licence). */
class GovUkBankHolidays internal constructor(
    private val fetch: (String) -> String,
    private val division: String = "england-and-wales",
) : HolidayProvider {
    constructor() : this(::httpGet)

    override val id = "bank_holidays"
    override val label = "GOV.UK · England and Wales"

    override fun holidays(): List<BankHoliday> = parse(fetch(URL))

    /** Entries without a real date are skipped; a missing division or no entries at all is an error (never "no holidays"). */
    internal fun parse(body: String): List<BankHoliday> {
        require(body.length <= MAX_BYTES) { "list too large" }
        val events = Json.parseToJsonElement(body).jsonObject[division]?.jsonObject?.get("events")?.jsonArray
            ?: error("no $division list")
        val out = events.mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            val date = (e["date"] as? JsonPrimitive)?.contentOrNull?.let(BankHolidays::parseDate) ?: return@mapNotNull null
            val title = (e["title"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: "Bank holiday"
            BankHoliday(date, title)
        }
        check(out.isNotEmpty()) { "empty $division list" }
        return out
    }

    companion object {
        const val URL = "https://www.gov.uk/bank-holidays.json"
        private const val MAX_BYTES = 1_000_000

        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
        fun httpGet(url: String): String {
            val resp = client.send(
                HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json").header("User-Agent", "MEKA-OS/1 (personal calendar)").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            check(resp.statusCode() == 200) { "holidays HTTP ${resp.statusCode()}" }
            return resp.body()
        }
    }
}
