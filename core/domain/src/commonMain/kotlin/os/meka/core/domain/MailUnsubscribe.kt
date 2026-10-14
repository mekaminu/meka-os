package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Inbox autopilot, slice 5a: **Unsubscribe for him** (Meka, 2026-10-12: "unsubscribe done for him"). Non-AI, pure,
 * unit-tested; no regexes (Kotlin/Native reads some differently).
 *
 * The server keeps, per mail account, the senders whose mail it sorted as a newsletter or promo (their latest
 * `List-Unsubscribe` header, server-side only) and publishes each as a `mail_sender` entity: the sender's name and
 * domain, which account, how it can be unsubscribed and when its last email came — never an email id, subject or
 * body. Mail on both apps lists them under each account with **Unsubscribe**; the tap writes [MailSenderFields.ASKED_AT]
 * and the server does it at its next check, by the first that works of: RFC 8058 one-click (an HTTPS POST, nothing
 * else sent), an email to the list's `mailto:` address sent from that same account, or, when only a web page is
 * offered, the page handed back for him to open. Nothing is sent without his tap. Slice 5b: once the unsubscribe has
 * gone through, their later newsletters and promos go to Trash with Autopilot ([trashes], [MailTidy.TRASH]); 5c the
 * weekly "not opened in 30 days" offer ([MailUnopenedRules]).
 */
enum class UnsubscribeMethod {
    /** RFC 8058: `List-Unsubscribe-Post: List-Unsubscribe=One-Click` with an https link. */
    ONE_CLICK,
    /** An email to the list's mailto: address, from the account the newsletter came to. */
    MAILTO,
    /** Only a web page: Meka opens it to finish. */
    LINK,
    /** Outlook: the headers are read when he asks (Graph's change feed doesn't carry them). */
    CHECK,
    /** No unsubscribe offered. */
    NONE;

    companion object {
        fun of(stored: String?): UnsubscribeMethod? = entries.firstOrNull { it.name == stored }
    }
}

/** A `mailto:` unsubscribe address with the subject and body the list asks for (defaults "unsubscribe"). */
data class UnsubscribeMailto(val to: String, val subject: String, val body: String)

/** How one sender can be unsubscribed, read from its latest `List-Unsubscribe` (+ `-Post`) header. */
data class UnsubscribePlan(
    val method: UnsubscribeMethod,
    /** The https link (one-click's POST target, or the page to open). */
    val url: String? = null,
    val mailto: UnsubscribeMailto? = null,
    /** The page to open if the one-click or mailto attempt fails (the header's https link, when there is one). */
    val fallbackUrl: String? = null,
)

object MailUnsubscribeRules {
    const val MAX_URL = 1000
    const val MAX_NAME = 60

    /** The `<…>` entries of a `List-Unsubscribe` header, in order (a bare value without brackets counts as one). */
    fun entries(header: String?): List<String> {
        val h = header?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val out = mutableListOf<String>()
        var i = 0
        while (i < h.length) {
            val lt = h.indexOf('<', i)
            if (lt < 0) break
            val gt = h.indexOf('>', lt + 1)
            if (gt < 0) break
            h.substring(lt + 1, gt).trim().takeIf { it.isNotEmpty() }?.let(out::add)
            i = gt + 1
        }
        if (out.isEmpty() && '<' !in h) out += h
        return out
    }

    /**
     * An https link MEKA may POST to or hand to Meka: https only, no spaces or user info, a host with a dot that is not a
     * bare IP address or localhost. (The server also refuses a host that resolves to a private address.)
     */
    fun safeUrl(raw: String?): String? {
        val u = raw?.trim() ?: return null
        if (u.length > MAX_URL || !u.startsWith("https://", ignoreCase = true)) return null
        if (u.any { it.isWhitespace() || it.code < 0x21 || it == '"' || it == '\\' }) return null
        val rest = u.substring(8)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || '@' in authority) return null
        val host = authority.substringBefore(':').lowercase().trimEnd('.')
        if (host.isEmpty() || '.' !in host || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return null
        if (host.all { it.isDigit() || it == '.' }) return null
        if (host.any { !(it.isLetterOrDigit() || it == '-' || it == '.') }) return null
        val port = authority.substringAfter(':', "")
        if (port.isNotEmpty() && (port.any { !it.isDigit() } || port != "443")) return null
        return u
    }

    /** `mailto:list@example.com?subject=unsubscribe&body=…` → its address, subject and body (one address only). */
    fun mailto(raw: String?): UnsubscribeMailto? {
        val m = raw?.trim() ?: return null
        if (!m.startsWith("mailto:", ignoreCase = true)) return null
        val rest = m.substring(7)
        val to = percentDecode(rest.substringBefore('?')).trim().lowercase()
        if (to.isEmpty() || ',' in to || ' ' in to || '\r' in to || '\n' in to || to.count { it == '@' } != 1) return null
        val domain = to.substringAfter('@')
        if (to.substringBefore('@').isEmpty() || '.' !in domain || domain.startsWith('.') || domain.endsWith('.')) return null
        val params = rest.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { it.substringBefore('=').lowercase() to percentDecode(it.substringAfter('=')) }
        fun clean(s: String?, max: Int) = s?.replace('\r', ' ')?.replace('\n', ' ')?.trim()?.take(max)?.takeIf { it.isNotEmpty() }
        return UnsubscribeMailto(to, clean(params["subject"], 200) ?: "unsubscribe", clean(params["body"], 500) ?: "unsubscribe")
    }

    private fun percentDecode(s: String): String {
        val bytes = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { bytes += hex.toByte(); i += 3; continue }
            }
            if (c == '+') bytes += ' '.code.toByte() else c.toString().encodeToByteArray().forEach { bytes += it }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    /**
     * The method for a sender: one-click when the header offers an https link and says One-Click; else its mailto:;
     * else its https page; else none. [check] (Outlook before the headers are read) gives [UnsubscribeMethod.CHECK].
     */
    fun plan(listUnsubscribe: String?, oneClick: Boolean, check: Boolean = false): UnsubscribePlan {
        val es = entries(listUnsubscribe)
        val url = es.firstNotNullOfOrNull { safeUrl(it) }
        val mail = es.firstNotNullOfOrNull { mailto(it) }
        return when {
            oneClick && url != null -> UnsubscribePlan(UnsubscribeMethod.ONE_CLICK, url = url, mailto = mail, fallbackUrl = url)
            mail != null -> UnsubscribePlan(UnsubscribeMethod.MAILTO, mailto = mail, fallbackUrl = url)
            url != null -> UnsubscribePlan(UnsubscribeMethod.LINK, url = url, fallbackUrl = url)
            check && es.isEmpty() -> UnsubscribePlan(UnsubscribeMethod.CHECK)
            else -> UnsubscribePlan(UnsubscribeMethod.NONE)
        }
    }

    /** One entity per account and sender address, so a retried write lands on the same one. */
    fun senderId(accountId: String, address: String): String = "ms" + ActivityRules.fnv64("mailsender:$accountId:${address.lowercase()}")

    /** What Mail calls the sender: its display name, else its domain ("news.example.com" → "example.com"'s whole domain). */
    fun displayName(fromName: String?, address: String?): String {
        val n = fromName?.trim()?.removeSurrounding("\"")?.trim()?.takeIf { it.isNotEmpty() && '@' !in it }
        return (n ?: MailSortRules.domain(address) ?: "Unknown sender").take(MAX_NAME)
    }

    // ---- The synced entity ----

    fun senderFields(account: String, name: String, domain: String?, method: UnsubscribeMethod, lastAtMs: Long, count: Int): Map<String, FieldValue> =
        buildMap {
            put(MailSenderFields.ACCOUNT, account.fv())
            put(MailSenderFields.NAME, name.take(MAX_NAME).fv())
            domain?.let { put(MailSenderFields.DOMAIN, it.take(MAX_NAME).fv()) }
            put(MailSenderFields.METHOD, method.name.fv())
            put(MailSenderFields.LAST_AT, lastAtMs.fv())
            put(MailSenderFields.COUNT, count.toLong().fv())
        }

    fun read(id: String, f: Map<String, FieldValue>): MailSender? {
        fun text(k: String) = (f[k] as? FieldValue.Text)?.value?.takeIf { it.isNotBlank() }
        fun ms(k: String) = (f[k] as? FieldValue.Int64)?.value?.takeIf { it > 0 }
        return MailSender(
            id = id,
            account = text(MailSenderFields.ACCOUNT) ?: return null,
            name = text(MailSenderFields.NAME) ?: return null,
            domain = text(MailSenderFields.DOMAIN),
            method = UnsubscribeMethod.of(text(MailSenderFields.METHOD)) ?: UnsubscribeMethod.NONE,
            lastAtMs = ms(MailSenderFields.LAST_AT) ?: 0L,
            count = (f[MailSenderFields.COUNT] as? FieldValue.Int64)?.value?.toInt() ?: 0,
            askedAtMs = ms(MailSenderFields.ASKED_AT),
            triedAtMs = ms(MailSenderFields.TRIED_AT),
            state = UnsubscribeState.of(text(MailSenderFields.STATE)),
            note = text(MailSenderFields.NOTE),
            link = safeUrl(text(MailSenderFields.LINK)),
            trashed = (f[MailSenderFields.TRASHED] as? FieldValue.Int64)?.value?.toInt()?.coerceAtLeast(0) ?: 0,
            unopened = (f[MailSenderFields.UNOPENED] as? FieldValue.Int64)?.value?.toInt()?.coerceAtLeast(0) ?: 0,
            unopenedAtMs = ms(MailSenderFields.UNOPENED_AT),
            keptAtMs = ms(MailSenderFields.KEPT_AT),
            rule = MailSenderRule.of(text(MailSenderFields.RULE)),
        )
    }

    /** An Unsubscribe the server should act on: asked, and not tried since (asking again after a failure retries). */
    fun due(s: MailSender): Boolean = s.askedAtMs != null && (s.triedAtMs == null || s.askedAtMs > s.triedAtMs)

    /**
     * Slice 5b: an email from [s] that arrived at [receivedAtMs] goes to Trash: the unsubscribe went through (one-click
     * confirmed or the email sent), nothing newer is pending, and the email came after it.
     */
    fun trashes(s: MailSender?, receivedAtMs: Long): Boolean {
        if (s == null || due(s) || s.state?.finished != true) return false
        val tried = s.triedAtMs ?: return false
        return receivedAtMs > tried
    }

    /** Slice 5b: what the server adds to the sender after a check sent [more] of its later emails to Trash. */
    fun trashedFields(previous: Int, more: Int, nowMs: Long): Map<String, FieldValue> = mapOf(
        MailSenderFields.TRASHED to (previous + more).toLong().fv(),
        MailSenderFields.TRASHED_AT to nowMs.fv(),
    )

    /** What the server writes once it has tried: the outcome, the line Mail shows and (to finish by hand) the page. */
    fun outcomeFields(state: UnsubscribeState, nowMs: Long, link: String? = null): Map<String, FieldValue> = buildMap {
        put(MailSenderFields.TRIED_AT, nowMs.fv())
        put(MailSenderFields.STATE, state.name.fv())
        put(MailSenderFields.NOTE, state.note.fv())
        put(MailSenderFields.LINK, (link?.let { safeUrl(it) } ?: "").fv())
    }
}

/** The server's outcome of an Unsubscribe. */
enum class UnsubscribeState(val note: String) {
    /** One-click accepted by the sender. */
    DONE("Unsubscribed · the sender confirmed it"),
    /** The mailto: email went from this account. */
    SENT("Unsubscribed · Navilife emailed the sender's unsubscribe address from this account"),
    /** Only a page: Meka opens it. */
    OPEN_LINK("Open the sender's page to finish"),
    /** Nothing worked and no page was offered. */
    FAILED("Couldn't unsubscribe · the sender offers no way Navilife can use"),
    /** It couldn't be tried this time (offline, the sign-in ended): tap again later. */
    RETRY("Couldn't reach the sender · tap Unsubscribe to try again");

    val finished: Boolean get() = this == DONE || this == SENT

    companion object {
        fun of(stored: String?): UnsubscribeState? = entries.firstOrNull { it.name == stored }
    }
}

/**
 * `mail_sender` (ADR-008 addendum 2026-10-13, slice 5a), id [MailUnsubscribeRules.senderId]. The server writes [ACCOUNT]
 * (the account's label, never its address), [NAME], [DOMAIN], [METHOD], [LAST_AT] and [COUNT] (emails in the last 60
 * days it has seen) and, after an Unsubscribe, [TRIED_AT], [STATE], [NOTE] and [LINK]; an app asks by writing
 * [ASKED_AT]. LWW throughout.
 */
object MailSenderFields {
    const val ACCOUNT = "account"
    const val NAME = "name"
    const val DOMAIN = "domain"
    const val METHOD = "method"
    const val LAST_AT = "lastAtMs"
    const val COUNT = "count"
    const val ASKED_AT = "unsubAskedAtMs"
    const val TRIED_AT = "unsubTriedAtMs"
    const val STATE = "unsubState"
    const val NOTE = "unsubNote"
    const val LINK = "unsubLink"
    /** Slice 5b: how many of its later emails went to Trash since the unsubscribe (Int64), and when the last did. */
    const val TRASHED = "trashedCount"
    const val TRASHED_AT = "trashedAtMs"
    /**
     * Slice 5c (server): of its emails in the last 30 days, how many came while none was opened (Int64: the count when
     * none of them was opened, else 0), and when the server last worked it out.
     */
    const val UNOPENED = "unopened30"
    const val UNOPENED_AT = "unopenedAtMs"
    /** Slice 5c (an app): Keep on the weekly offer: never offered again (0 clears it, for Undo). */
    const val KEPT_AT = "keptAtMs"
    /** Completeness (an app): Meka's rule for this sender ([MailSenderRule] name, "" = none) and when he set it. */
    const val RULE = "rule"
    const val RULE_AT = "ruleAtMs"
}

data class MailSender(
    val id: String,
    val account: String,
    val name: String,
    val domain: String?,
    val method: UnsubscribeMethod,
    val lastAtMs: Long,
    val count: Int,
    val askedAtMs: Long?,
    val triedAtMs: Long?,
    val state: UnsubscribeState?,
    val note: String?,
    val link: String?,
    /** Slice 5b: later emails sent to Trash since the unsubscribe. */
    val trashed: Int = 0,
    /** Slice 5c: emails in the last 30 days, none opened (0 when one was, or not worked out yet). */
    val unopened: Int = 0,
    val unopenedAtMs: Long? = null,
    /** Slice 5c: Keep on the weekly offer. */
    val keptAtMs: Long? = null,
    /** Meka's own rule for the sender, or null. */
    val rule: MailSenderRule? = null,
)

/** One sender under its account in Mail. */
data class MailSenderRow(
    val id: String,
    /** "The Weekly Digest". */
    val name: String,
    /** "example.com · 8 emails · last Mon 12 Oct". */
    val line: String,
    /** Unsubscribe shows (not yet asked, or the last try failed). */
    val canUnsubscribe: Boolean,
    /** "Unsubscribing at the next check…" or the server's outcome; null before he asked. */
    val state: String?,
    /** The outcome was a success (the row reads done). */
    val done: Boolean,
    /** The sender's page to open to finish (OPEN_LINK), else null. */
    val link: String?,
    /** Meka's rule for the sender ("Always kept in the inbox · …"), or null. */
    val ruleLine: String? = null,
    /** The rule action: "Keep in inbox" without a rule, "Let Autopilot decide" with one. */
    val ruleAction: String = MailSenderRuleRules.KEEP_ACTION,
    /** The rule the action sets, by name ("KEEP"), or "" to take it off (a plain string, so both apps pass it as is). */
    val ruleSets: String = MailSenderRule.KEEP.name,
)

object MailSenderPanelRules {
    const val TITLE = "Newsletters and promos"
    const val UNSUBSCRIBE = "Unsubscribe"
    const val OPEN = "Open page"
    const val ASKED = "Unsubscribing at the next check…"
    const val ASKED_LINE = "Unsubscribing at Navilife's next check, in a few minutes"
    const val ALREADY = "Already unsubscribed"
    const val UNKNOWN = "Couldn't find that sender"
    /** At most this many senders per account, the busiest first. */
    const val SHOWN = 8

    /** "8 senders · Unsubscribe and Navilife does it for you" over the list; null with none. */
    fun header(rows: List<MailSenderRow>): String? = when (rows.size) {
        0 -> null
        1 -> "1 sender · tap Unsubscribe and Navilife does it for you"
        else -> "${rows.size} senders · tap Unsubscribe and Navilife does it for you"
    }

    /** One account's senders: the ones that offer a way out, busiest first (then newest), at most [SHOWN]. */
    fun rows(senders: List<MailSender>, account: String, nowMs: Long, cal: LocalCalendar): List<MailSenderRow> {
        val today = cal.epochDayOf(nowMs)
        return senders.filter { it.account == account && (it.method != UnsubscribeMethod.NONE || it.state != null) }
            .sortedWith(compareByDescending<MailSender> { it.count }.thenByDescending { it.lastAtMs }.thenBy { it.id })
            .take(SHOWN)
            .map { s ->
                val day = cal.epochDayOf(s.lastAtMs)
                val last = when {
                    s.lastAtMs <= 0 -> null
                    day == today -> "last today"
                    day == today - 1 -> "last yesterday"
                    else -> "last ${CivilDate.shortLabel(day)}"
                }
                val parts = listOfNotNull(
                    s.domain?.takeIf { !it.equals(s.name, ignoreCase = true) },
                    s.count.takeIf { it > 0 }?.let { "$it ${if (it == 1) "email" else "emails"}" },
                    last,
                    MailUnopenedRules.rowNote(s, nowMs),
                )
                val due = MailUnsubscribeRules.due(s)
                val done = !due && s.state?.finished == true
                MailSenderRow(
                    id = s.id, name = s.name, line = parts.joinToString(" · "),
                    canUnsubscribe = !due && !done && s.state != UnsubscribeState.OPEN_LINK,
                    state = when {
                        due -> ASKED
                        done && s.trashed > 0 -> listOfNotNull(s.note ?: s.state?.note, trashedLine(s.trashed)).joinToString(" · ")
                        else -> s.note ?: s.state?.note
                    },
                    done = done,
                    link = if (!due && s.state == UnsubscribeState.OPEN_LINK) s.link else null,
                    ruleLine = s.rule?.line,
                    ruleAction = if (s.rule == null) MailSenderRuleRules.KEEP_ACTION else MailSenderRuleRules.CLEAR_ACTION,
                    ruleSets = if (s.rule == null) MailSenderRule.KEEP.name else "",
                )
            }
    }

    /** "2 later emails sent to Trash" (slice 5b; Undo is on the tidy in Mail and Activity). */
    fun trashedLine(n: Int): String = if (n == 1) "1 later email sent to Trash" else "$n later emails sent to Trash"

    /** What tapping Unsubscribe writes: the ask (the server acts at its next check). Null when it's already done or asked. */
    fun ask(s: MailSender?, nowMs: Long): Map<String, FieldValue>? {
        if (s == null || MailUnsubscribeRules.due(s) || s.state?.finished == true) return null
        return mapOf(MailSenderFields.ASKED_AT to nowMs.fv())
    }
}
