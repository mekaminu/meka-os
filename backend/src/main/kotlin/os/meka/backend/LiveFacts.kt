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
     * Ada's Live mode, slice 4: a search the Live model asked for, checked by [LiveFactsRules.searchable] (never his
     * own life) rather than Ask's "is it current" test; the same key, the same daily cap and the same wait.
     */
    fun lookQuery(query: String): Found? = run(LiveFactsRules.searchable(query) ?: return null)

    private fun run(q: String): Found? {
        if (!search.configured()) return null
        synchronized(this) {
            val d = Math.floorDiv(now(), 86_400_000L)
            if (d != day) { day = d; used = 0 }
            if (used >= LiveFactsRules.MAX_PER_DAY) return null
            used++
        }
        return try {
            val hits = CompletableFuture.supplyAsync({ search.search(q) }, pool).get(waitMs, TimeUnit.MILLISECONDS)
            lastFailure = null
            Found(q, hits.filter { LiveFactsRules.site(it.url) != null }.take(LiveFactsRules.MAX_RESULTS))
        } catch (e: java.util.concurrent.TimeoutException) {
            lastFailure = "timed out"; null
        } catch (e: java.util.concurrent.ExecutionException) {
            lastFailure = (e.cause as? SearchFailed)?.reason ?: "failed"; null
        } catch (e: Exception) {
            lastFailure = "failed"; null
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
