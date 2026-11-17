package os.meka.backend

import os.meka.core.domain.LiveFactsRules
import os.meka.core.domain.WebHit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The companion, slice 2 (live world facts): a question about something current ("what's the latest on the strike?",
 * "weather in Madrid this weekend?", "who won the Arsenal game?") is searched on the web with the server's Brave Search
 * key (the spam-call lookup's) and the results go to the model as data, with the sources back to the device. Only the
 * question's own words are searched, and only when [LiveFactsRules.query] says it is about the world, not his life; at
 * most [LiveFactsRules.MAX_PER_DAY] a day so the key's free credit still covers the spam-call lookup; a slow or failed
 * search simply leaves the answer without (Navilife says it can't check live). Nothing is stored or logged.
 */
class LiveFacts(
    private val search: NumberSearch,
    private val now: () -> Long = System::currentTimeMillis,
    private val waitMs: Long = WAIT_MS,
    private val pool: ExecutorService = Executors.newFixedThreadPool(2) { r -> Thread(r, "live-facts").apply { isDaemon = true } },
) {
    /** What was searched and the results the model reads (at most [LiveFactsRules.MAX_RESULTS]; empty: nothing found). */
    data class Found(val query: String, val hits: List<WebHit>)

    private var day = -1L
    private var used = 0
    @Volatile private var lastFailure: String? = null

    /** The results for [question], or null when it isn't searched (not live, no key, the day's searches used, failed, slow). */
    fun look(question: String): Found? = run(LiveFactsRules.query(question) ?: return null)

    /**
     * Obi's Live mode, slice 4: a search the Live model asked for, checked by [LiveFactsRules.searchable] (never his
     * own life) rather than Ask's "is it current" test; the same key, the same daily cap and the same wait.
     */
    fun lookQuery(query: String): Found? = run(LiveFactsRules.searchable(query) ?: return null)

    /** How a search went: found (perhaps nothing), not searched (his own life, no key), the day's used, or failed. */
    sealed interface Look {
        data class Hit(val found: Found) : Look
        data object NotSearched : Look
        data object UsedUp : Look
        data object Failed : Look
    }

    /**
     * Obi answers live status, the safety net: the search for an answer that said it can't check live, with why it
     * couldn't run. [question] is checked by [LiveFactsRules.searchable] (never his own life); the same key, cap and wait.
     */
    fun retry(question: String): Look = attempt(LiveFactsRules.searchable(question) ?: return Look.NotSearched)

    private fun run(q: String): Found? = (attempt(q) as? Look.Hit)?.found

    private fun attempt(q: String): Look {
        if (!search.configured()) return Look.NotSearched
        synchronized(this) {
            val d = Math.floorDiv(now(), 86_400_000L)
            if (d != day) { day = d; used = 0 }
            if (used >= LiveFactsRules.MAX_PER_DAY) return Look.UsedUp
            used++
        }
        return try {
            val hits = CompletableFuture.supplyAsync({ search.search(q) }, pool).get(waitMs, TimeUnit.MILLISECONDS)
            lastFailure = null
            Look.Hit(Found(q, hits.filter { LiveFactsRules.site(it.url) != null }.take(LiveFactsRules.MAX_RESULTS)))
        } catch (e: java.util.concurrent.TimeoutException) {
            lastFailure = "timed out"; Look.Failed
        } catch (e: java.util.concurrent.ExecutionException) {
            lastFailure = (e.cause as? SearchFailed)?.reason ?: "failed"; Look.Failed
        } catch (e: Exception) {
            lastFailure = "failed"; Look.Failed
        }
    }

    /** The deploy report's line: today's searches against the cap and the last failure (never a query). */
    fun report(): String = synchronized(this) {
        val today = if (day == Math.floorDiv(now(), 86_400_000L)) used else 0
        "live answers: $today of ${LiveFactsRules.MAX_PER_DAY} searches today" + (lastFailure?.let { " · last failed: $it" } ?: "")
    }

    companion object {
        /** How long an answer waits for the search (Brave usually answers in well under a second). */
        const val WAIT_MS = 3_000L
    }
}

/**
 * Obi answers live train status: TfL's status for lines he names that aren't on his route, asked on demand through the
 * same free, keyless API the route's poll uses ([os.meka.backend.integrations.TflLineStatus]); only the lines' ids are
 * sent and only status codes kept. Each set of lines is kept for [KEEP_MS] and at most [MAX_PER_HOUR] asks go out an
 * hour (TfL's anonymous rate is low); slow (over [waitMs]) or failed reads give null. Nothing is stored or logged.
 */
class TflOnDemand(
    private val source: os.meka.backend.integrations.LineStatusProvider,
    private val now: () -> Long = System::currentTimeMillis,
    private val waitMs: Long = 2_500L,
    private val pool: ExecutorService = Executors.newFixedThreadPool(1) { r -> Thread(r, "tfl-on-demand").apply { isDaemon = true } },
) {
    private val kept = HashMap<String, Pair<Long, List<os.meka.core.domain.LineState>>>()
    private var hour = -1L
    private var asked = 0

    /** TfL's status now for [ids] (TfL line ids), or null when it can't be read just now. */
    fun statuses(ids: List<String>): List<os.meka.core.domain.LineState>? {
        val want = ids.distinct().sorted().take(os.meka.core.domain.TrainNowAskRules.MAX_NAMED)
        if (want.isEmpty()) return null
        val key = want.joinToString(",")
        synchronized(this) {
            kept[key]?.let { (at, states) -> if (now() - at < KEEP_MS) return states }
            val h = Math.floorDiv(now(), 3_600_000L)
            if (h != hour) { hour = h; asked = 0 }
            if (asked >= MAX_PER_HOUR) return null
            asked++
        }
        val states = runCatching {
            CompletableFuture.supplyAsync({ source.statuses(want) }, pool).get(waitMs, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return null
        synchronized(this) {
            if (kept.size > 50) kept.clear()
            kept[key] = now() to states
        }
        return states
    }

    companion object {
        const val KEEP_MS = 2 * 60_000L
        const val MAX_PER_HOUR = 30
    }
}
