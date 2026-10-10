package os.meka.android.ask

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import os.meka.core.domain.ReaderRules
import os.meka.core.facade.MekaCore
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * Train-time queue, slice 2: the phone fetches each waiting article itself, on Wi-Fi (an unmetered network) only, as
 * its browser would, and hands the page to the core, which keeps only its readable words on this phone
 * ([MekaCore.saveQueuePage]). Nothing goes through MEKA's server; podcasts are left to their own app. Runs when the
 * queue changes (a share saved, a link synced from the Mac) and after each background sync.
 */
object QueuePages {
    private const val TIMEOUT_MS = 15_000
    private val running = Mutex()

    /** On Wi-Fi (or another unmetered network) with the internet reachable. */
    fun unmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !cm.isActiveNetworkMetered
    }

    /** Fetches the waiting articles not yet saved (at most five a go); does nothing off Wi-Fi or while one runs. */
    suspend fun saveWaiting(context: Context, core: MekaCore) {
        if (!unmetered(context) || !running.tryLock()) return
        try {
            for (f in core.queueToFetch()) {
                if (!unmetered(context)) break
                core.saveQueuePage(f.id, fetch(f.url))
            }
        } finally {
            running.unlock()
        }
    }

    /** The page's HTML, or null when it couldn't be read (not HTML, too slow, an error). Follows redirects. */
    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching { read(URL(url), 0) }.getOrNull()
    }

    private fun read(target: URL, hops: Int): String? {
        if (target.protocol != "https" && target.protocol != "http") return null
        val c = target.openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) MEKA/1 (read later)")
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            val code = c.responseCode
            if (code in 300..399) {
                if (hops >= 5) return null
                val next = c.getHeaderField("Location") ?: return null
                return read(URL(target, next), hops + 1)
            }
            if (code !in 200..299) return null
            val type = c.contentType.orEmpty().lowercase()
            if (type.isNotEmpty() && "html" !in type) return null
            val charset = Regex("""charset=([\w-]+)""").find(type)?.groupValues?.get(1)
                ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            val bytes = c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < ReaderRules.MAX_HTML) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            return String(bytes, charset)
        } finally {
            c.disconnect()
        }
    }
}
