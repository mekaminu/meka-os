package os.meka.backend

import os.meka.core.domain.WebHit
import os.meka.core.wire.NumberLookupCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spam-call lookup on the server (2026-10-17): Brave's answer read, the 30-day cache, one search per number a day. Invented numbers, no real key. */
class NumberLookupTest {
    private var now = 1_792_000_000_000L
    private val day = 86_400_000L

    private val scamPage = listOf(
        WebHit("https://who-called.co.uk/Number/01632960691", "01632960691 - Who called?", "12 reports. Scam, claims to be HMRC and demands payment."),
        WebHit("https://www.tellows.co.uk/num/01632960691", "01632 960691", "Fraud: fake HMRC tax refund call. Dangerous."),
        WebHit("https://www.shouldianswer.co.uk/phone-number/01632960691", "01632 960691", "HMRC scam, threatened arrest."),
    )

    private inner class FakeSearch : NumberSearch {
        var key = true
        var hits = scamPage
        var fail: String? = null
        var gate: CountDownLatch? = null
        val queries = mutableListOf<String>()
        override fun configured() = key
        override fun search(query: String): List<WebHit> {
            synchronized(queries) { queries += query }
            gate?.await(5, TimeUnit.SECONDS)
            fail?.let { throw SearchFailed(it) }
            return hits
        }
    }

    private val search = FakeSearch()
    private val store = InMemoryNumberLookupStore()
    private val lookup = NumberLookup(search, store, { now }, Executors.newFixedThreadPool(2))

    @Test
    fun aNumberIsSearchedOnceAndItsVerdictKeptThirtyDays() {
        val r = lookup.lookUp("hh", "+44 1632 960691", 2_000)
        assertEquals(NumberLookupCodec.Response(NumberLookupCodec.Response.OK, "scam", 12, true, "an HMRC scam"), r)
        assertEquals(listOf("\"01632960691\""), search.queries)
        // Written another way, a day later: the cache answers.
        now += day
        assertEquals("scam", lookup.lookUp("hh", "01632 960691", 0).verdict)
        assertEquals(1, search.queries.size)
        // After 30 days it is searched again; another household never shares the answer.
        now += 30 * day
        lookup.lookUp("hh", "01632960691", 2_000)
        assertEquals(2, search.queries.size)
        lookup.lookUp("hh-2", "01632960691", 2_000)
        assertEquals(3, search.queries.size)
    }

    @Test
    fun aFailedSearchIsNoLookupAndIsntRepeatedForADay() {
        search.fail = "out of credit or rate limited (429)"
        assertEquals(NumberLookupCodec.Response(NumberLookupCodec.Response.FAILED), lookup.lookUp("hh", "01632 960691", 2_000))
        search.fail = null
        assertEquals(NumberLookupCodec.Response.FAILED, lookup.lookUp("hh", "01632 960691", 2_000).state)
        assertEquals(1, search.queries.size)
        now += day + 1
        assertEquals("scam", lookup.lookUp("hh", "01632 960691", 2_000).verdict)
        // Nothing known is kept a week.
        search.hits = emptyList()
        assertEquals("unknown", lookup.lookUp("hh", "01632 960692", 2_000).verdict)
        now += 6 * day
        lookup.lookUp("hh", "01632 960692", 2_000)
        assertEquals(3, search.queries.size)
        // No key: off, nothing searched; not a number worth searching: nothing known.
        assertEquals("unknown", lookup.lookUp("hh", "999", 2_000).verdict)
        search.key = false
        assertEquals(NumberLookupCodec.Response(NumberLookupCodec.Response.OFF), lookup.lookUp("hh", "01632 960693", 2_000))
        assertEquals(3, search.queries.size)
    }

    @Test
    fun aSlowSearchIsPendingThenAnswersTheNextAskAndIsNeverRunTwiceAtOnce() {
        search.gate = CountDownLatch(1)
        assertEquals(NumberLookupCodec.Response(NumberLookupCodec.Response.PENDING), lookup.lookUp("hh", "01632 960691", 50))
        assertEquals(NumberLookupCodec.Response.PENDING, lookup.lookUp("hh", "01632 960691", 50).state)
        search.gate!!.countDown()
        assertEquals("scam", lookup.lookUp("hh", "01632 960691", 3_000).verdict)
        assertEquals(1, search.queries.size)
    }

    @Test
    fun theDeployReportProbesAPublicNumberOnceADayAndCountsOnly() {
        search.hits = listOf(WebHit("https://www.actionfraud.police.uk/contact-us", "Contact us", "Call us on 0300 123 2040"))
        lookup.lookUp("hh", "01632 960691", 2_000)
        val line = lookup.report("hh")
        assertTrue(line.startsWith("probe OK · 1 results · 0 from report sites · verdict legit"), line)
        assertTrue(line.endsWith("1 numbers looked up in 30 days (0 scam, 0 nuisance, 0 business, 1 nothing known, 0 failed)"), line)
        assertFalse(line.contains("01632"))
        lookup.report("hh")
        assertEquals(2, search.queries.size)
        now += day
        lookup.report("hh")
        assertEquals(3, search.queries.size)
        search.fail = "key refused (401)"
        now += day
        assertTrue(lookup.report("hh").startsWith("probe failed · key refused (401)"))
        search.key = false
        assertEquals("off · the server's Brave secret holds no key", lookup.report("hh"))
        assertTrue(ReleasePublisherCli.spamLine("probe OK · …").startsWith("::notice title=Spam-call lookup::"))
        assertTrue(ReleasePublisherCli.spamLine("probe failed · x").startsWith("::warning"))
    }

    @Test
    fun bravesAnswerIsReadWithTheKeyInAHeaderOnly() {
        val seen = mutableListOf<Pair<String, Map<String, String>>>()
        val brave = BraveSearch({ "brave-key-123" }) { url, headers ->
            seen += url to headers
            200 to """{"type":"search","web":{"results":[
                {"title":"<strong>01632960691</strong> - Who called","url":"https://who-called.co.uk/Number/01632960691",
                 "description":"Scam &amp; fraud","extra_snippets":["12 reports"]},
                {"title":"no url"}]}}"""
        }
        val hits = brave.search("\"01632960691\"")
        assertEquals(listOf(WebHit("https://who-called.co.uk/Number/01632960691", "01632960691 - Who called", "Scam & fraud 12 reports")), hits)
        assertEquals("brave-key-123", seen.single().second["X-Subscription-Token"])
        assertFalse(seen.single().first.contains("brave-key-123"))
        assertTrue(seen.single().first.startsWith(BraveSearch.BASE + "?q=%2201632960691%22"))
        assertEquals(emptyList(), BraveSearch.parse("""{"type":"search"}"""))
        assertNull(BraveSearch.parse("nope"))
        val refused = BraveSearch({ "k-12345678" }) { _, _ -> 429 to "{}" }
        assertEquals("out of credit or rate limited (429)", runCatching { refused.search("x") }.exceptionOrNull()?.message)
        // A refused parameter: asked again with the query alone.
        var n = 0
        val retry = BraveSearch({ "k-12345678" }) { url, _ -> if (n++ == 0) 422 to "{}" else { assertFalse(url.contains("country")); 200 to "{}" } }
        assertEquals(emptyList(), retry.search("x"))
        assertEquals("abcdefgh123", BraveSearch.parseKey("""{"api_key":"abcdefgh123"}"""))
        assertNull(BraveSearch.parseKey("{}"))
    }
}
