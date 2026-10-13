package os.meka.backend.integrations

import os.meka.core.domain.MailUnsubscribeRules
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Inbox autopilot, slice 5a: RFC 8058 one-click unsubscribe — a POST of `List-Unsubscribe=One-Click` to the list's
 * https link, nothing else (no cookies, no account details, redirects not followed). Only done because Meka tapped
 * Unsubscribe. True when the sender answered 2xx.
 */
fun interface OneClickUnsubscriber {
    fun post(url: String): Boolean

    companion object {
        /** Tests and a server without outbound access: never posts (the mailto or the page is tried instead). */
        val NONE = OneClickUnsubscriber { false }
    }
}

class HttpOneClickUnsubscriber(
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) : OneClickUnsubscriber {
    override fun post(url: String): Boolean {
        val safe = MailUnsubscribeRules.safeUrl(url) ?: return false
        val uri = runCatching { URI(safe) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        // Never a request into MEKA's own network: every address the name resolves to must be public.
        val addresses = runCatching { resolve(host) }.getOrNull().orEmpty()
        if (addresses.isEmpty() || !addresses.all(::isPublic)) return false
        val req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", "Navilife-Unsubscribe/1.0")
            .POST(HttpRequest.BodyPublishers.ofString("List-Unsubscribe=One-Click"))
            .build()
        return runCatching { client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299 }.getOrDefault(false)
    }

    companion object {
        /** Not loopback, link-local, private (10/8, 172.16/12, 192.168/16, fc00::/7), carrier-grade NAT (100.64/10), multicast or unspecified. */
        fun isPublic(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
            val b = a.address
            if (a is Inet6Address) {
                if ((b[0].toInt() and 0xfe) == 0xfc) return false
                // IPv4-mapped: judge the IPv4 address.
                if (b.take(10).all { it.toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1) {
                    return isPublic(InetAddress.getByAddress(b.copyOfRange(12, 16)))
                }
                return true
            }
            val first = b[0].toInt() and 0xff
            val second = b[1].toInt() and 0xff
            if (first == 0 || first >= 240) return false
            if (first == 100 && second in 64..127) return false
            if (first == 169 && second == 254) return false
            return true
        }
    }
}
