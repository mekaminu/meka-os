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
 * from it reaches the AI in this slice.
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
)

/** A kept post on its channel's card. */
data class DiscordPostRow(val id: String, val author: String, val text: String, val timeLine: String)

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
)

/** Needs you's Discord block: every watched channel with new posts. */
data class DiscordCards(val headline: String, val cards: List<DiscordChannelCard>)

object DiscordWatchFields {
    /** Folded channel keys he follows, one per line. */
    const val WATCHED = "watched"
    /** Channels Discord notified about: "key\tserver\tchannel\tlastSeenMs" per line, newest first. */
    const val CHANNELS = "channels"
    const val CHANGED_AT = "changedAtMs"
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
    )
    const val EMPTY = "No Discord channels yet · once Discord shows a notification from a channel, it's listed here to pick."
    const val NOT_SEEN = "That channel isn't listed any more"
    const val TOO_MANY = "You can follow up to $MAX_WATCHED channels"

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

    fun view(seen: List<DiscordSeenChannel>, watched: Set<String>, nowMs: Long, cal: LocalCalendar): DiscordWatchView {
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
        return DiscordWatchView(rows, status, if (rows.isEmpty()) EMPTY else null)
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
    )

    /** One card per watched channel with posts not yet Done (newest channel first); null when there are none. */
    fun cards(posts: List<Post>, watched: Set<String>, nowMs: Long, cal: LocalCalendar, hidden: Set<String> = emptySet()): DiscordCards? {
        val open = posts.filter { !it.done && it.id !in hidden && it.channel.key in watched && nowMs - it.atMs < KEEP_MS && it.text.isNotBlank() }
        if (open.isEmpty()) return null
        val cards = open.groupBy { it.channel.key }.map { (k, ps) ->
            val sorted = ps.sortedBy { it.atMs }
            val latest = sorted.last()
            val shown = sorted.takeLast(SHOWN)
            val earlier = sorted.size - shown.size
            val authors = sorted.map { it.author }.distinct()
            DiscordChannelCard(
                key = k,
                title = "#" + latest.channel.channel,
                server = latest.channel.server,
                line = "${sorted.size} post${if (sorted.size == 1) "" else "s"} since you last looked · latest " +
                    whenLine(latest.atMs, nowMs, cal) + " · " + authorsLine(authors),
                posts = shown.map { DiscordPostRow(it.id, it.author, it.text, whenLine(it.atMs, nowMs, cal)) },
                moreLine = if (earlier > 0) "+ $earlier earlier post${if (earlier == 1) "" else "s"}" else null,
                ids = sorted.map { it.id },
                latestMs = latest.atMs,
            )
        }.sortedByDescending { it.latestMs }
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
}

/** The synced setting and the kept posts on a replica (both apps write; only the Fold captures). */
class DiscordStore(private val replica: Replica) {
    private fun setting(): Map<String, FieldValue>? = replica.entity(EntityTypes.CONTEXT_MODE, DiscordRules.ENTITY_ID)?.fields

    fun watched(): Set<String> = DiscordRules.watched(setting())
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
        )
    }

    /** Notes the channels and keeps the watched ones' new posts; returns how many posts were new. */
    fun note(incoming: List<DiscordCaptured>, nowMs: Long): Int {
        if (incoming.isEmpty()) return 0
        val watched = watched()
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
            replica.commitLocal(EntityTypes.DISCORD_POST, id, mapOf(
                DiscordPostFields.CHANNEL to p.channel.key.fv(),
                DiscordPostFields.SERVER to p.channel.server.fv(),
                DiscordPostFields.CHANNEL_NAME to p.channel.channel.fv(),
                DiscordPostFields.AUTHOR to p.author.trim().take(100).fv(),
                DiscordPostFields.TEXT to text.fv(),
                DiscordPostFields.AT to p.atMs.fv(),
                DiscordPostFields.DONE to false.fv(),
            ))
            fresh++
        }
        prune(nowMs)
        return fresh
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
