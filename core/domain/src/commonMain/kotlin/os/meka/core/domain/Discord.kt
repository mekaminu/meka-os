package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Discord channel watch (Fix first, Meka 2026-10-15 21:06), slice 1: follow one or two Discord channels through the
 * Fold's notification listener, the same official route as WhatsApp. Never a self-bot, a user token or any Discord API
 * (against Discord's terms and a risk to his account). Information only: nothing is ever posted to Discord, and a
 * trade idea is shown as text with no action (trading stays event/approval based; Navilife never holds exchange
 * credentials).
 *
 * Every channel Discord notifies about is noted as "seen" (its name kept in his synced data, never in the repo, rule
 * 13) so he can pick it in Work mode → Discord; posts from channels he hasn't picked are dropped where they arrive.
 * A picked channel's posts are kept (synced, so the Mac shows them) and become one Needs you card per channel with
 * Done. Discord's text is untrusted (ADR-006): it is only displayed, never read by rules into actions, and nothing
 * from it reaches the AI in slice 1 (slice 3 sends a busy channel's words for its card's one line: DiscordSummaryRules).
 */
data class DiscordChannel(
    /** Folded "server#channel" (or "#channel" when Discord named no server): the same on every device. */
    val key: String,
    val server: String?,
    val channel: String,
) {
    /** "#trading-ideas · Example Traders", or "#trading-ideas" alone. */
    val label: String get() = DiscordRules.label(channel, server)
}

/** One post as the Fold's listener read it. */
data class DiscordCaptured(val channel: DiscordChannel, val author: String, val text: String, val atMs: Long)

/** A channel Discord has notified about, and whether he follows it. */
data class DiscordSeenChannel(val channel: DiscordChannel, val lastSeenMs: Long)

/** One row in Work mode → Discord. */
data class DiscordChannelRow(val key: String, val label: String, val watched: Boolean, val line: String)

/** Work mode → Discord, on both apps. */
data class DiscordWatchView(
    val rows: List<DiscordChannelRow>,
    val statusLine: String,
    val emptyLine: String?,
    /** Slice 2: "Alerts straight away for posts from Admin, or saying BTC or stop" (or that none are set). */
    val alertLine: String = DiscordRules.NO_ALERTS,
    /** Slice 2: the people whose posts alert at once, as he typed them. */
    val people: List<String> = emptyList(),
    /** Slice 2: the words (a ticker, "entry", "stop") that alert at once. */
    val words: List<String> = emptyList(),
    /** Slice 2: who has posted in the channels he follows lately, busiest first, to add with one tap. */
    val suggestedPeople: List<String> = emptyList(),
)

/** A kept post on its channel's card. */
data class DiscordPostRow(
    val id: String,
    val author: String,
    val text: String,
    val timeLine: String,
    /** Slice 2: why this post alerted ("From Admin · says “stop”"), or null. */
    val alert: String? = null,
    /** Slice 4: kept as a note in Lists → Someday → Trading ideas ("Kept" on the post instead of "Keep as idea"). */
    val kept: Boolean = false,
)

/**
 * Slice 2: the Fold's one notification for a channel whose post matched a watched person or word, updated in place as
 * more arrive (tag = [key]). Information only: it opens Needs you; it never posts to Discord and never trades.
 */
data class DiscordAlertNotice(
    val key: String,
    /** "#trade-ideas · Example Traders". */
    val title: String,
    /** The newest alerting post: "Admin: BTC long above 68k, stop 66.5k". */
    val text: String,
    /** Why it alerted: "From Admin · says “stop”". */
    val why: String,
    /** Up to five alerting posts, oldest first, for the expanded notification. */
    val lines: List<String>,
    val count: Int,
    val latestMs: Long,
)

/** What the Fold's listener noted: how many posts were new, and the channels whose alert to post or update now. */
data class DiscordNoted(val fresh: Int, val alerts: List<DiscordAlertNotice>)

/** One watched channel's card in Needs you. */
data class DiscordChannelCard(
    val key: String,
    val title: String,
    val server: String?,
    /** "4 posts since you last looked · latest 14:02". */
    val line: String,
    /** The newest few, oldest first so they read down like the chat. */
    val posts: List<DiscordPostRow>,
    /** "+ 6 earlier posts" when more were kept than shown. */
    val moreLine: String?,
    val ids: List<String>,
    val latestMs: Long,
    /** Slice 2: "2 alerts · latest 14:02" when posts on it matched a watched person or word. */
    val alertLine: String? = null,
    /**
     * Slice 3: Navilife's AI's one line on what was said since he last looked ("Admin's BTC long above 68k, stop
     * 66.5k; two chart images"), or null (fewer than three posts, AI off, offline). Display text only (ADR-006).
     */
    val summary: String? = null,
)

/** Needs you's Discord block: every watched channel with new posts. */
data class DiscordCards(val headline: String, val cards: List<DiscordChannelCard>)

object DiscordWatchFields {
    /** Folded channel keys he follows, one per line. */
    const val WATCHED = "watched"
    /** Channels Discord notified about: "key\tserver\tchannel\tlastSeenMs" per line, newest first. */
    const val CHANNELS = "channels"
    const val CHANGED_AT = "changedAtMs"
    /** Slice 2: people whose posts alert straight away, one per line, as typed. */
    const val ALERT_PEOPLE = "alertPeople"
    /** Slice 2: words that alert straight away, one per line, as typed. */
    const val ALERT_WORDS = "alertWords"
}

object DiscordPostFields {
    const val CHANNEL = "channel"
    const val SERVER = "server"
    const val CHANNEL_NAME = "channelName"
    const val AUTHOR = "author"
    const val TEXT = "text"
    const val AT = "atMs"
    const val DONE = "done"
    const val DONE_AT = "doneAtMs"
    /** Slice 2: why the post alerted when it arrived ("From Admin · says “stop”"); absent = it didn't. */
    const val ALERT = "alert"
    /** Slice 4: the Someday task this post was kept as (Trading ideas); absent or null = not kept. */
    const val KEPT = "keptAs"
}

object DiscordRules {
    val PACKAGES = setOf("com.discord")
    const val ENTITY_ID = "discord_watch"
    const val TITLE = "Discord"
    const val MAX_SEEN = 40
    const val MAX_WATCHED = 10
    const val MAX_TEXT = 600
    const val SHOWN = 8
    /** Posts older than this leave the card and their words are blanked on the Fold. */
    const val KEEP_MS = 7 * 24 * 60 * 60_000L
    /** A seen channel's time is rewritten at most this often (fewer synced writes for a busy channel). */
    const val SEEN_REFRESH_MS = 60 * 60_000L

    const val SETUP_LINE = "In Discord, set the channel you follow to \"All messages\" and mute the rest of the server, " +
        "so its posts reach your phone's notifications. Navilife reads only those notifications and never posts to Discord."
    val LIMITS = listOf(
        "Navilife sees what Discord's notification shows: an image reads \"sent an image\", and a long post may be cut.",
        "Nothing from before you picked a channel, and nothing from channels you haven't picked.",
        "Information only: a trade idea stays text. Navilife never trades and holds no exchange keys.",
        DiscordSummaryRules.LIMIT_LINE,
    )
    const val EMPTY = "No Discord channels yet · once Discord shows a notification from a channel, it's listed here to pick."
    const val NOT_SEEN = "That channel isn't listed any more"
    const val TOO_MANY = "You can follow up to $MAX_WATCHED channels"

    // ---- Slice 2: watched people and words ----
    const val MAX_PEOPLE = 20
    const val MAX_WORDS = 30
    const val MAX_ENTRY = 40
    /** A post older than this when the listener sees it is marked on the card but doesn't alert (a replayed notice). */
    const val ALERT_FRESH_MS = 15 * 60_000L
    /** The alert notification lists the channel's alerting posts from this long. */
    const val ALERT_WINDOW_MS = 6 * 60 * 60_000L
    const val MAX_SUGGESTED = 6
    const val NO_ALERTS = "No alerts set · posts wait on the channel's card in Needs you"
    const val ALERTS_HINT = "A post from one of these people, or with one of these words (a ticker, \"entry\", \"stop\"), " +
        "alerts you straight away as a Discord alert notification. Everything else waits on the channel's card."
    const val ALERT_CHANNEL_NAME = "Discord alerts"
    const val ALERT_CHANNEL_WHY = "Posts in the Discord channels you follow from people or with words you chose. Information only."

    private val IMAGE_TEXT = Regex(
        "^(sent|uploaded|shared|posted)\\s+(an?\\s+)?(image|photo|picture|gif|sticker|file|attachment|video)s?\\.?$",
        RegexOption.IGNORE_CASE,
    )
    private val COUNT_ONLY = Regex("^\\d+\\s+(new\\s+)?(messages?|mentions?)\\.?$", RegexOption.IGNORE_CASE)
    private val AUTHOR_IN_TITLE = Regex("^(.+?)\\s*\\(\\s*#([^,()]+?)\\s*,\\s*([^()]+?)\\s*\\)\\s*$")
    private val CHANNEL_THEN_SERVER = Regex("^#([^()]+?)\\s*\\(\\s*([^()]+?)\\s*\\)\\s*$")
    private val SEPARATORS = listOf(" > ", " › ", " • ", " · ", " | ", ", ", " – ", " - ", ": ")

    fun isDiscord(packageName: String): Boolean = packageName in PACKAGES

    fun label(channel: String, server: String?): String = "#$channel" + (server?.let { " · $it" } ?: "")

    fun fold(s: String): String = s.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    fun key(server: String?, channel: String): String = (server?.let { fold(it) } ?: "") + "#" + fold(channel)

    private fun channel(server: String?, channel: String): DiscordChannel? {
        val c = channel.trim().trimStart('#').trim().take(100)
        if (c.isEmpty()) return null
        val s = server?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("#") && !COUNT_ONLY.matches(it) }?.take(100)
        return DiscordChannel(key(s, c), s, c)
    }

    /**
     * The server channel a Discord notification came from, read from its conversation title, title and sub-text in
     * the forms Discord uses ("#channel", "Server > #channel", "#channel, Server", "#channel (Server)", "Author
     * (#channel, Server)"); a group DM (a conversation with no #) is kept under its name. Null for a one-to-one DM, a
     * count-only summary or anything else: those are left alone.
     */
    fun channelOf(conversationTitle: String?, title: String?, subText: String?, isGroup: Boolean): DiscordChannel? {
        val conv = conversationTitle?.let { ChatTitleRules.clean(it) }?.takeIf { it.isNotBlank() }
        val sub = subText?.trim()?.takeIf { it.isNotEmpty() && !COUNT_ONLY.matches(it) }
        for (candidate in listOfNotNull(conv, title?.trim()?.takeIf { it.isNotEmpty() })) {
            AUTHOR_IN_TITLE.matchEntire(candidate)?.let { m -> return channel(m.groupValues[3], m.groupValues[2]) }
            CHANNEL_THEN_SERVER.matchEntire(candidate)?.let { m -> return channel(m.groupValues[2], m.groupValues[1]) }
            if ('#' !in candidate) continue
            if (candidate.startsWith("#") && SEPARATORS.none { it in candidate }) return channel(sub, candidate)
            for (sep in SEPARATORS) {
                val parts = candidate.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size < 2) continue
                val hash = parts.indexOfFirst { it.startsWith("#") }
                if (hash < 0) continue
                val server = parts.filterIndexed { i, _ -> i != hash }.joinToString(" · ")
                return channel(server, parts[hash])
            }
            val at = candidate.indexOf('#')
            if (at > 0) return channel(candidate.substring(0, at).trim().trimEnd('>', '›', '•', '·', '|', ',', '-', ':').trim(), candidate.substring(at))
        }
        // A group DM: a named conversation with several people and no channel.
        if (isGroup && conv != null && '#' !in conv) return DiscordChannel("dm:" + fold(conv), null, conv.take(100))
        return null
    }

    /** Discord's words made fit for a card: images named plainly, whitespace squashed, a long post cut with "…". */
    fun cleanText(raw: String?): String? {
        val t = raw?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (IMAGE_TEXT.matches(t)) return "sent an image"
        if (t.length <= MAX_TEXT) return t
        val cut = t.take(MAX_TEXT)
        val space = cut.lastIndexOf(' ')
        return (if (space > MAX_TEXT / 2) cut.substring(0, space) else cut).trimEnd() + "…"
    }

    fun postId(channelKey: String, author: String, atMs: Long, text: String): String =
        "dp" + ActivityRules.fnv64("discord:$channelKey|${fold(author)}|$atMs|$text")

    // ---- The synced setting ----

    fun watched(fields: Map<String, FieldValue>?): Set<String> =
        fields?.get(DiscordWatchFields.WATCHED)?.textOrNull?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun seen(fields: Map<String, FieldValue>?): List<DiscordSeenChannel> =
        fields?.get(DiscordWatchFields.CHANNELS)?.textOrNull?.lines()?.mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4) return@mapNotNull null
            val c = p[2].trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            DiscordSeenChannel(DiscordChannel(p[0], p[1].trim().takeIf { it.isNotEmpty() }, c), p[3].toLongOrNull() ?: 0L)
        }?.distinctBy { it.channel.key } ?: emptyList()

    fun seenText(seen: List<DiscordSeenChannel>): String = seen.take(MAX_SEEN).joinToString("\n") { s ->
        listOf(s.channel.key, s.channel.server.orEmpty(), s.channel.channel, s.lastSeenMs.toString())
            .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
    }

    /**
     * [seen] with [incoming]'s channels noted at their newest post; null when nothing worth a write changed (a known
     * channel seen again within [SEEN_REFRESH_MS]). Watched channels are never pushed off the list.
     */
    fun noteSeen(seen: List<DiscordSeenChannel>, incoming: List<DiscordCaptured>, watched: Set<String>): List<DiscordSeenChannel>? {
        if (incoming.isEmpty()) return null
        val byKey = seen.associateBy { it.channel.key }.toMutableMap()
        var changed = false
        incoming.groupBy { it.channel.key }.forEach { (k, posts) ->
            val newest = posts.maxOf { it.atMs }
            val before = byKey[k]
            if (before == null || newest - before.lastSeenMs >= SEEN_REFRESH_MS || before.channel != posts.first().channel) {
                byKey[k] = DiscordSeenChannel(posts.first().channel, maxOf(newest, before?.lastSeenMs ?: 0L))
                changed = true
            }
        }
        if (!changed) return null
        val sorted = byKey.values.sortedByDescending { it.lastSeenMs }
        val kept = sorted.filter { it.channel.key in watched } + sorted.filter { it.channel.key !in watched }
        return kept.take(maxOf(MAX_SEEN, kept.count { it.channel.key in watched })).sortedByDescending { it.lastSeenMs }
    }

    /** Only posts from channels he follows are kept; the rest of Discord is ignored. */
    fun toKeep(incoming: List<DiscordCaptured>, watched: Set<String>): List<DiscordCaptured> =
        incoming.filter { it.channel.key in watched && it.text.isNotBlank() }

    fun view(
        seen: List<DiscordSeenChannel>, watched: Set<String>, nowMs: Long, cal: LocalCalendar,
        people: List<String> = emptyList(), words: List<String> = emptyList(), posts: List<Post> = emptyList(),
    ): DiscordWatchView {
        val rows = seen.sortedWith(compareByDescending<DiscordSeenChannel> { it.channel.key in watched }.thenByDescending { it.lastSeenMs })
            .map { s ->
                val on = s.channel.key in watched
                DiscordChannelRow(
                    s.channel.key, s.channel.label, on,
                    (if (on) "Following · " else "") + "last post " + whenLine(s.lastSeenMs, nowMs, cal),
                )
            }
        val n = rows.count { it.watched }
        val status = when {
            rows.isEmpty() -> "Nothing from Discord yet"
            n == 0 -> "${rows.size} channel${if (rows.size == 1) "" else "s"} seen · pick the ones to follow; the rest is ignored"
            else -> "Following $n channel${if (n == 1) "" else "s"} · their posts come to Needs you · the rest of Discord is ignored"
        }
        return DiscordWatchView(
            rows, status, if (rows.isEmpty()) EMPTY else null,
            alertLine(people, words), people, words, suggestedPeople(posts, watched, people, nowMs),
        )
    }

    /**
     * Slice 2: a person or word as he typed it, tidied: spaces squashed, a leading "@" dropped from a person, at most
     * [MAX_ENTRY] characters; null when nothing is left (or a word is a single character, which would match too much).
     */
    fun entry(raw: String, person: Boolean): String? {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        if (person) t = t.trimStart('@').trim()
        t = t.trim('"', '“', '”', '\'').trim().take(MAX_ENTRY).trim()
        if (t.isEmpty() || (!person && t.length < 2)) return null
        return t
    }

    fun entries(fields: Map<String, FieldValue>?, field: String): List<String> =
        fields?.get(field)?.textOrNull?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinctBy { fold(it) } ?: emptyList()

    /** [list] with [raw] added (no duplicates, case aside); null with the reason when it can't be. */
    fun added(list: List<String>, raw: String, person: Boolean): Pair<List<String>?, String> {
        val e = entry(raw, person) ?: return null to (if (person) "Type the name as Discord shows it" else "Type a word of 2 letters or more")
        if (list.any { fold(it) == fold(e) }) return null to "Already alerting for ${shown(e, person)}"
        val max = if (person) MAX_PEOPLE else MAX_WORDS
        if (list.size >= max) return null to "Up to $max ${if (person) "people" else "words"}"
        return (list + e) to "Alerts straight away for ${if (person) "posts from $e" else "posts saying ${shown(e, false)}"}"
    }

    fun removed(list: List<String>, raw: String, person: Boolean): Pair<List<String>?, String> {
        val gone = list.firstOrNull { fold(it) == fold(raw) } ?: return null to "Not on the list"
        return list.filter { it != gone } to "No longer alerting for ${shown(gone, person)}"
    }

    private fun shown(e: String, person: Boolean): String = if (person) e else "“$e”"

    /**
     * Whether [word] appears in [text] as a whole word or phrase (case aside): "stop" matches "Stop 66.5k" but not
     * "stopped" or "nonstop"; "BTC" matches "$BTC" and "BTC/USDT". Plain string search (no regex built from his words).
     */
    fun saysWord(text: String, word: String): Boolean {
        val t = text.lowercase()
        val w = word.lowercase().trim()
        if (w.isEmpty()) return false
        var from = 0
        while (true) {
            val i = t.indexOf(w, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else t[i - 1]
            val after = if (i + w.length >= t.length) ' ' else t[i + w.length]
            val edgeStart = !w.first().isLetterOrDigit() || !before.isLetterOrDigit()
            val edgeEnd = !w.last().isLetterOrDigit() || !after.isLetterOrDigit()
            if (edgeStart && edgeEnd) return true
            from = i + 1
        }
    }

    /**
     * Slice 2: why a post alerts — its author is a watched person, or its words include a watched word — or null.
     * Discord's words are untrusted (ADR-006): matching them against his own list only decides whether he is told
     * now; nothing is ever acted on.
     */
    fun alertWhy(author: String, text: String, people: List<String>, words: List<String>): String? {
        val who = people.firstOrNull { fold(it) == fold(author.trimStart('@')) }
        val said = if (text == "sent an image") emptyList() else words.filter { saysWord(text, it) }.take(2)
        if (who == null && said.isEmpty()) return null
        val parts = listOfNotNull(who?.let { "From $it" }, said.takeIf { it.isNotEmpty() }?.let { s -> "says " + s.joinToString(", ") { "“$it”" } })
        return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }

    fun alertLine(people: List<String>, words: List<String>): String {
        if (people.isEmpty() && words.isEmpty()) return NO_ALERTS
        val from = people.takeIf { it.isNotEmpty() }?.let { "posts from " + orList(it) }
        val saying = words.takeIf { it.isNotEmpty() }?.let { "saying " + orList(it) }
        return "Alerts straight away for " + listOfNotNull(from, saying).joinToString(", or ")
    }

    private fun orList(xs: List<String>): String = when (xs.size) {
        1 -> xs[0]
        2 -> "${xs[0]} or ${xs[1]}"
        3 -> "${xs[0]}, ${xs[1]} or ${xs[2]}"
        else -> "${xs[0]}, ${xs[1]}, ${xs[2]} or ${xs.size - 3} more"
    }

    /** Who has posted in the channels he follows in the last week, busiest first, not already watched. */
    fun suggestedPeople(posts: List<Post>, watched: Set<String>, people: List<String>, nowMs: Long): List<String> =
        posts.filter { it.channel.key in watched && nowMs - it.atMs < KEEP_MS && it.author.isNotBlank() && it.author != "Someone" }
            .groupBy { fold(it.author) }
            .filterKeys { k -> people.none { fold(it) == k } }
            .entries.sortedWith(compareByDescending<Map.Entry<String, List<Post>>> { it.value.size }.thenByDescending { e -> e.value.maxOf { it.atMs } })
            .take(MAX_SUGGESTED).map { e -> e.value.maxBy { it.atMs }.author }

    /**
     * The channel's alert notification: its alerting posts not yet Done from the last [ALERT_WINDOW_MS], or null
     * (nothing left to show: the notification comes down).
     */
    fun alertNotice(posts: List<Post>, key: String, nowMs: Long): DiscordAlertNotice? {
        val hits = posts.filter { it.channel.key == key && !it.done && it.alert != null && it.text.isNotBlank() && nowMs - it.atMs < ALERT_WINDOW_MS }
            .sortedBy { it.atMs }
        val latest = hits.lastOrNull() ?: return null
        fun line(p: Post) = "${p.author}: ${p.text.take(160)}"
        return DiscordAlertNotice(
            key = key, title = latest.channel.label, text = line(latest), why = latest.alert!!,
            lines = hits.takeLast(5).map(::line), count = hits.size, latestMs = latest.atMs,
        )
    }

    fun switchedLine(label: String, on: Boolean): String =
        if (on) "Following $label · its posts come to Needs you" else "Stopped following $label"

    internal fun whenLine(ms: Long, nowMs: Long, cal: LocalCalendar): String {
        val day = cal.epochDayOf(ms)
        val today = cal.epochDayOf(nowMs)
        val hm = LocalClock.formatMinute(cal.minuteOfDay(ms))
        return when {
            day == today -> hm
            day == today - 1 -> "yesterday $hm"
            today - day < 7 -> "${FastingRules.dayLabel(day)} $hm"
            else -> "${FastingRules.dayLabel(day)} ${CivilDate.fromEpochDay(day).day} ${MONTHS[CivilDate.fromEpochDay(day).month - 1]}"
        }
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    // ---- Needs you ----

    data class Post(
        val id: String,
        val channel: DiscordChannel,
        val author: String,
        val text: String,
        val atMs: Long,
        val done: Boolean,
        /** Slice 2: why it alerted when it arrived, or null. */
        val alert: String? = null,
        /** Slice 4: the Trading ideas task it was kept as, or null. */
        val kept: String? = null,
    )

    /** One card per watched channel with posts not yet Done (newest channel first); null when there are none. */
    fun cards(
        posts: List<Post>, watched: Set<String>, nowMs: Long, cal: LocalCalendar, hidden: Set<String> = emptySet(),
        summaries: List<ChatSummary> = emptyList(),
    ): DiscordCards? {
        val summaryByKey = summaries.associateBy { it.chatKey }
        val open = posts.filter { !it.done && it.id !in hidden && it.channel.key in watched && nowMs - it.atMs < KEEP_MS && it.text.isNotBlank() }
        if (open.isEmpty()) return null
        val cards = open.groupBy { it.channel.key }.map { (k, ps) ->
            val sorted = ps.sortedBy { it.atMs }
            val latest = sorted.last()
            val shown = sorted.takeLast(SHOWN)
            val earlier = sorted.size - shown.size
            val authors = sorted.map { it.author }.distinct()
            val alerts = sorted.filter { it.alert != null }
            DiscordChannelCard(
                key = k,
                title = "#" + latest.channel.channel,
                server = latest.channel.server,
                line = "${sorted.size} post${if (sorted.size == 1) "" else "s"} since you last looked · latest " +
                    whenLine(latest.atMs, nowMs, cal) + " · " + authorsLine(authors),
                posts = shown.map { DiscordPostRow(it.id, it.author, it.text, whenLine(it.atMs, nowMs, cal), it.alert, it.kept != null) },
                moreLine = if (earlier > 0) "+ $earlier earlier post${if (earlier == 1) "" else "s"}" else null,
                ids = sorted.map { it.id },
                latestMs = latest.atMs,
                alertLine = alerts.lastOrNull()?.let { a ->
                    "${alerts.size} alert${if (alerts.size == 1) "" else "s"} · latest " + whenLine(a.atMs, nowMs, cal)
                },
                summary = DiscordSummaryRules.summaryFor(summaryByKey[DiscordSummaryRules.chatKey(k)], sorted),
            )
        }.sortedWith(compareByDescending<DiscordChannelCard> { it.alertLine != null }.thenByDescending { it.latestMs })
        val total = cards.sumOf { it.ids.size }
        val headline = "${cards.size} channel${if (cards.size == 1) "" else "s"} · $total new post${if (total == 1) "" else "s"} · " +
            "information only, nothing is posted"
        return DiscordCards(headline, cards)
    }

    private fun authorsLine(authors: List<String>): String = when (authors.size) {
        1 -> authors[0]
        2 -> "${authors[0]} and ${authors[1]}"
        else -> "${authors[0]}, ${authors[1]} and ${authors.size - 2} more"
    }

    fun doneLine(title: String, n: Int): String = "Done · $title · $n post${if (n == 1) "" else "s"}"

    // ---- Slice 4: keep a post as a trade idea (a note in Lists, never an order) ----

    const val IDEA_MAX = 160
    const val KEEP_ACTION = "Keep as idea"
    const val KEPT_CHIP = "In Trading ideas"
    const val KEPT_LINE = "Kept in Lists → Someday → Trading ideas · a note, nothing is traded"
    const val ALREADY_KEPT = "Already in Trading ideas"
    const val CANT_KEEP = "That post's words are gone · nothing to keep"
    const val UNKEPT_LINE = "Taken out of Trading ideas"

    /**
     * The idea's title: "Admin in #signals: BTC long above 68k, stop 66.5k", cut at a word to [IDEA_MAX] with "…".
     * The words are Discord's (untrusted, ADR-006): one line, kept as text, never acted on.
     */
    fun ideaTitle(author: String, channel: String, text: String): String {
        val flat = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val who = author.trim().ifEmpty { "Someone" }
        val head = "$who in #${channel.trim().removePrefix("#")}: "
        val room = (IDEA_MAX - head.length).coerceAtLeast(20)
        val body = if (flat.length <= room) flat else {
            val cut = flat.take(room - 1)
            val sp = cut.lastIndexOf(' ')
            (if (sp >= room / 2) cut.take(sp) else cut).trimEnd(',', ';', ':', '.', ' ') + "…"
        }
        return head + body
    }

    /** The idea's notes: the whole post and where it came from, and that it's information only. */
    fun ideaNotes(p: Post, cal: LocalCalendar): String {
        val epochDay = cal.epochDayOf(p.atMs)
        val date = CivilDate.fromEpochDay(epochDay)
        val day = "${FastingRules.dayLabel(epochDay)} ${date.day} ${MONTHS[date.month - 1]} " + LocalClock.formatMinute(cal.minuteOfDay(p.atMs))
        return p.text + "\n\nFrom Discord · " + label(p.channel.channel, p.channel.server) + " · ${p.author} · $day\n" +
            "Information only · Navilife never trades"
    }
}

/**
 * Discord channel watch, slice 3: Navilife's AI's one line on each channel's card. A followed channel with
 * [MIN_POSTS] or more posts not yet Done goes, with the other due channels, in **one** call to the same
 * `POST /v1/ai/chat-summary` busy chats use: the channel's label and each post's author, time and words (as the card
 * shows them: an image is "sent an image", a long post is already cut). Never a number, a contact or anything else of
 * the phone's; a channel or server named under Messages → "Kept from Navilife's AI" is never sent. The answer is kept in
 * the same synced `chat_summary` (keyed "discord:" + the channel), so the Mac's card shows it too. Discord's words are
 * untrusted (ADR-006): the summary is display text only, checked again here (one line, no links or addresses), and
 * nothing is posted, traded or acted on. Pure and non-AI.
 */
object DiscordSummaryRules {
    const val MIN_POSTS = 3
    const val PREFIX = "discord:"
    const val LIMIT_LINE = "With Navilife's AI on, a channel with $MIN_POSTS or more new posts gets a one-line summary on its card: " +
        "the posts' words go to the AI for that line only, and channels named under Messages → \"Kept from Navilife's AI\" never do."

    private val LINKISH = Regex("""(https?://|www\.|\S+@\S+\.\S+|discord\.gg)""", RegexOption.IGNORE_CASE)

    fun chatKey(channelKey: String): String = PREFIX + channelKey

    private fun open(posts: List<DiscordRules.Post>, watched: Set<String>, nowMs: Long) =
        posts.filter { !it.done && it.channel.key in watched && nowMs - it.atMs < DiscordRules.KEEP_MS && it.text.isNotBlank() }

    /** A summary belongs to the card when it was made after the oldest open post arrived. */
    private fun belongs(s: ChatSummary, sorted: List<DiscordRules.Post>): Boolean =
        sorted.isNotEmpty() && s.upToMs >= sorted.first().atMs

    /**
     * The channels the call carries: followed channels with at least [MIN_POSTS] open posts that have no summary for
     * them, or new posts and a summary older than [ChatSummaryRules.REFRESH_MS]; most recent first, at most
     * [ChatSummaryRules.MAX_CHATS], names unique in the batch.
     */
    fun toSummarise(
        posts: List<DiscordRules.Post>, watched: Set<String>, have: List<ChatSummary>, settings: TriageSettings,
        nowMs: Long, cal: LocalCalendar,
    ): List<ChatSummaryRules.Chat> {
        val byKey = have.associateBy { it.chatKey }
        val names = mutableSetOf<String>()
        return open(posts, watched, nowMs).groupBy { it.channel.key }.mapNotNull { (k, ps) ->
            val sorted = ps.sortedBy { it.atMs }
            val ch = sorted.last().channel
            if (sorted.size < MIN_POSTS) return@mapNotNull null
            if (listOfNotNull(ch.label, "#" + ch.channel, ch.channel, ch.server).any { settings.isPrivate(it) }) return@mapNotNull null
            val s = byKey[chatKey(k)]
            val latest = sorted.last().atMs
            val due = when {
                s == null || !belongs(s, sorted) -> true
                s.upToMs >= latest -> false
                else -> nowMs - s.madeAtMs >= ChatSummaryRules.REFRESH_MS
            }
            if (!due) return@mapNotNull null
            ChatSummaryRules.Chat(
                chatKey(k), ch.label.take(60), true,
                sorted.takeLast(ChatSummaryRules.MAX_LINES).map { p ->
                    ChatSummaryRules.Line(
                        (p.author.ifBlank { "Someone" }).take(60),
                        LocalClock.formatMinute(cal.minuteOfDay(p.atMs)),
                        GroupGistRules.clean(p.text, ChatSummaryRules.MAX_LINE_CHARS) ?: "sent an image",
                    )
                },
                latest, sorted.size,
            )
        }.sortedByDescending { it.upToMs }
            .filter { names.add(People.key(it.name)) }
            .take(ChatSummaryRules.MAX_CHATS)
    }

    /** The model's answer checked: one clean line per channel sent, dropped whole with a link, invite or address. */
    fun check(sent: List<ChatSummaryRules.Chat>, raw: List<ChatSummaryRules.Raw>): Map<String, String?> =
        ChatSummaryRules.check(sent, raw).mapValues { (_, v) -> v?.takeUnless { LINKISH.containsMatchIn(it) } }

    /** The card's line: the channel's summary when it belongs to its open posts and there are enough of them. */
    fun summaryFor(s: ChatSummary?, sorted: List<DiscordRules.Post>): String? =
        s?.takeIf { sorted.size >= MIN_POSTS && belongs(it, sorted) }?.summary

    /** The due channels, from the store's posts and setting. */
    fun due(store: DiscordStore, have: List<ChatSummary>, settings: TriageSettings, nowMs: Long, cal: LocalCalendar) =
        toSummarise(store.posts(), store.watched(), have, settings, nowMs, cal)
}

/** The synced setting and the kept posts on a replica (both apps write; only the Fold captures). */
class DiscordStore(private val replica: Replica) {
    private fun setting(): Map<String, FieldValue>? = replica.entity(EntityTypes.CONTEXT_MODE, DiscordRules.ENTITY_ID)?.fields

    fun watched(): Set<String> = DiscordRules.watched(setting())
    fun people(): List<String> = DiscordRules.entries(setting(), DiscordWatchFields.ALERT_PEOPLE)
    fun words(): List<String> = DiscordRules.entries(setting(), DiscordWatchFields.ALERT_WORDS)
    fun seen(): List<DiscordSeenChannel> = DiscordRules.seen(setting())

    fun posts(): List<DiscordRules.Post> = replica.entities(EntityTypes.DISCORD_POST).mapNotNull { e ->
        val key = e[DiscordPostFields.CHANNEL].textOrNull ?: return@mapNotNull null
        val name = e[DiscordPostFields.CHANNEL_NAME].textOrNull ?: return@mapNotNull null
        DiscordRules.Post(
            e.ref.entityId,
            DiscordChannel(key, e[DiscordPostFields.SERVER].textOrNull, name),
            e[DiscordPostFields.AUTHOR].textOrNull.orEmpty(),
            e[DiscordPostFields.TEXT].textOrNull.orEmpty(),
            e[DiscordPostFields.AT].longOrNull ?: 0L,
            e[DiscordPostFields.DONE].boolOrNull == true,
            e[DiscordPostFields.ALERT].textOrNull?.takeIf { it.isNotBlank() },
            e[DiscordPostFields.KEPT].textOrNull?.takeIf { it.isNotBlank() },
        )
    }

    fun post(id: String): DiscordRules.Post? = posts().firstOrNull { it.id == id }

    /** Slice 4: mark [id] as kept as task [taskId] (null = no longer kept); synced so the Mac's card shows it. */
    fun setKept(id: String, taskId: String?) {
        if (replica.entity(EntityTypes.DISCORD_POST, id) == null) return
        replica.commitLocal(EntityTypes.DISCORD_POST, id, mapOf(DiscordPostFields.KEPT to taskId.fv()))
    }

    /**
     * Notes the channels and keeps the watched ones' new posts, marking any from a watched person or with a watched
     * word; returns how many were new and the channels whose alert notification to post now (a fresh alerting post).
     */
    fun note(incoming: List<DiscordCaptured>, nowMs: Long): DiscordNoted {
        if (incoming.isEmpty()) return DiscordNoted(0, emptyList())
        val watched = watched()
        val people = people()
        val words = words()
        val alerting = mutableSetOf<String>()
        DiscordRules.noteSeen(seen(), incoming, watched)?.let { list ->
            replica.commitLocal(EntityTypes.CONTEXT_MODE, DiscordRules.ENTITY_ID, mapOf(
                DiscordWatchFields.CHANNELS to DiscordRules.seenText(list).fv(),
            ))
        }
        var fresh = 0
        DiscordRules.toKeep(incoming, watched).forEach { p ->
            val text = DiscordRules.cleanText(p.text) ?: return@forEach
            val id = DiscordRules.postId(p.channel.key, p.author, p.atMs, text)
            if (replica.entity(EntityTypes.DISCORD_POST, id) != null) return@forEach
            val author = p.author.trim().take(100)
            val why = DiscordRules.alertWhy(author, text, people, words)
            if (why != null && nowMs - p.atMs < DiscordRules.ALERT_FRESH_MS) alerting += p.channel.key
            replica.commitLocal(EntityTypes.DISCORD_POST, id, mapOf(
                DiscordPostFields.CHANNEL to p.channel.key.fv(),
                DiscordPostFields.SERVER to p.channel.server.fv(),
                DiscordPostFields.CHANNEL_NAME to p.channel.channel.fv(),
                DiscordPostFields.AUTHOR to author.fv(),
                DiscordPostFields.TEXT to text.fv(),
                DiscordPostFields.AT to p.atMs.fv(),
                DiscordPostFields.DONE to false.fv(),
                DiscordPostFields.ALERT to why.fv(),
            ))
            fresh++
        }
        prune(nowMs)
        val all = if (alerting.isEmpty()) emptyList() else posts()
        return DiscordNoted(fresh, alerting.mapNotNull { DiscordRules.alertNotice(all, it, nowMs) })
    }

    /** Slice 2: add or remove a watched person ([person]) or word; synced. Returns the line for the bar. */
    fun setAlert(raw: String, person: Boolean, add: Boolean, nowMs: Long): String {
        val field = if (person) DiscordWatchFields.ALERT_PEOPLE else DiscordWatchFields.ALERT_WORDS
        val list = if (person) people() else words()
        val (next, line) = if (add) DiscordRules.added(list, raw, person) else DiscordRules.removed(list, raw, person)
        if (next != null) {
            replica.commitLocal(EntityTypes.CONTEXT_MODE, DiscordRules.ENTITY_ID, mapOf(
                field to next.joinToString("\n").fv(),
                DiscordWatchFields.CHANGED_AT to nowMs.fv(),
            ))
        }
        return line
    }

    /** Words of posts that are Done (after the Undo window) or past [DiscordRules.KEEP_MS] are blanked. */
    fun prune(nowMs: Long) {
        replica.entities(EntityTypes.DISCORD_POST).forEach { e ->
            val text = e[DiscordPostFields.TEXT].textOrNull.orEmpty()
            if (text.isEmpty()) return@forEach
            val at = e[DiscordPostFields.AT].longOrNull ?: 0L
            val doneAt = e[DiscordPostFields.DONE_AT].longOrNull
            val done = e[DiscordPostFields.DONE].boolOrNull == true
            if (nowMs - at >= DiscordRules.KEEP_MS || (done && doneAt != null && nowMs - doneAt >= 10 * 60_000L)) {
                replica.commitLocal(EntityTypes.DISCORD_POST, e.ref.entityId, mapOf(DiscordPostFields.TEXT to "".fv()))
            }
        }
    }

    /** Follow or stop following [key] (a seen channel); returns the line, or null when refused with [why]. */
    fun setWatched(key: String, on: Boolean, nowMs: Long): Pair<String, Boolean> {
        val seen = seen().firstOrNull { it.channel.key == key } ?: return DiscordRules.NOT_SEEN to false
        val now = watched()
        if (on && key !in now && now.size >= DiscordRules.MAX_WATCHED) return DiscordRules.TOO_MANY to false
        val next = if (on) now + key else now - key
        if (next != now) {
            replica.commitLocal(EntityTypes.CONTEXT_MODE, DiscordRules.ENTITY_ID, mapOf(
                DiscordWatchFields.WATCHED to next.sorted().joinToString("\n").fv(),
                DiscordWatchFields.CHANGED_AT to nowMs.fv(),
            ))
        }
        return DiscordRules.switchedLine(seen.channel.label, on) to true
    }

    fun markDone(ids: List<String>, done: Boolean, nowMs: Long) {
        ids.forEach { id ->
            if (replica.entity(EntityTypes.DISCORD_POST, id) == null) return@forEach
            replica.commitLocal(EntityTypes.DISCORD_POST, id, mapOf(
                DiscordPostFields.DONE to done.fv(),
                DiscordPostFields.DONE_AT to (if (done) nowMs else null).fv(),
            ))
        }
    }
}
