package os.meka.core.domain

/**
 * The approved Needs you look (Meka 2026-11-09 22:33, `docs/design/needs-you/needs-you.dc.html`), non-AI and pure:
 * a header line with the counts, filter chips All · Decide · Reply · FYI · Mail, then TO DECIDE, MESSAGES as one list
 * of rows (a row opens in place to its messages), FYI as thin rows with a tick, and the mail Navilife tidied.
 * Nothing here sends, marks or answers anything; the apps keep their own taps.
 */
enum class NeedsYouFilter(val label: String) {
    ALL("All"), DECIDE("Decide"), REPLY("Reply"), FYI("FYI"), MAIL("Mail");
}

enum class NeedsYouSection { DECIDE, MESSAGES, FYI, MAIL }

/** One person's or group's messages as a row: avatar letters, name, time, one-line gist and a badge. */
data class NeedsYouMessageRow(
    val key: String,
    /** "W", "GT": up to two letters from the name. */
    val initials: String,
    val name: String,
    /** "19:07": the latest message's time. */
    val time: String,
    /** The one-line gist: MEKA's summary of a busy chat, else its first line that matters. */
    val line: String,
    /** "1 request", "2 to reply", "3 new". */
    val badge: String,
    /** True when something waits on Meka (a request or a reply): the badge is lit. */
    val hot: Boolean,
    /** One sentence for TalkBack / VoiceOver. */
    val spoken: String,
)

/** A chat that needs nothing from Meka (thanks, a thumbs up): a thin row and a tick. */
data class NeedsYouFyiRow(val key: String, val who: String, val what: String, val spoken: String)

data class NeedsYouChip(val filter: NeedsYouFilter, val label: String, val count: Int)

object NeedsYouLayoutRules {
    const val CLEAR_FYI = "Clear FYI"
    const val DECIDE_HEAD = "TO DECIDE"
    const val MESSAGES_HEAD = "MESSAGES"
    const val FYI_HEAD = "FYI · NO REPLY NEEDED"
    const val MAIL_HEAD = "MAIL NAVILIFE TIDIED TODAY"
    const val MESSAGES_HINT = NeedsYouOpenRules.HINT
    const val ALL_CLEAR = "All clear"
    const val SEEN = "Seen"

    /** "TO DECIDE · 2". */
    fun head(title: String, count: Int): String = "$title · $count"

    /** A chat is FYI when nothing in it waits on Meka: no reply, no request, no action, nobody he watches asked. */
    fun isFyi(chat: TriageChat): Boolean =
        chat.replies == 0 && chat.requests.isEmpty() && chat.askedBy == null &&
            chat.cards.isNotEmpty() && chat.cards.all { it.lane == TriageLane.FYI }

    fun messages(chats: List<TriageChat>): List<TriageChat> = chats.filterNot(::isFyi)
    fun fyi(chats: List<TriageChat>): List<TriageChat> = chats.filter(::isFyi)

    /** How many people or groups wait for a reply (a request counts): the header's "4 to reply". */
    fun replyCount(chats: List<TriageChat>): Int = messages(chats).count { it.replies > 0 || it.requests.isNotEmpty() || it.askedBy != null }

    /** "2 to decide · 4 to reply · 4 FYI", leaving out what is nothing; "All clear" when all are. */
    fun subtitle(decide: Int, reply: Int, fyi: Int): String {
        val parts = listOfNotNull(
            "$decide to decide".takeIf { decide > 0 },
            "$reply to reply".takeIf { reply > 0 },
            "$fyi FYI".takeIf { fyi > 0 },
        )
        return if (parts.isEmpty()) ALL_CLEAR else parts.joinToString(" · ")
    }

    /** All · Decide 2 · Reply 4 · FYI 4 · Mail 138: a count only when there is one. */
    fun chips(decide: Int, reply: Int, fyi: Int, mail: Int): List<NeedsYouChip> = listOf(
        NeedsYouChip(NeedsYouFilter.ALL, NeedsYouFilter.ALL.label, 0),
        chip(NeedsYouFilter.DECIDE, decide),
        chip(NeedsYouFilter.REPLY, reply),
        chip(NeedsYouFilter.FYI, fyi),
        chip(NeedsYouFilter.MAIL, mail),
    )

    private fun chip(f: NeedsYouFilter, n: Int) = NeedsYouChip(f, if (n > 0) "${f.label} $n" else f.label, n)

    /** Whether [section] shows under [filter]: All shows everything; each chip its own section. */
    fun shows(filter: NeedsYouFilter, section: NeedsYouSection): Boolean = when (filter) {
        NeedsYouFilter.ALL -> true
        NeedsYouFilter.DECIDE -> section == NeedsYouSection.DECIDE
        NeedsYouFilter.REPLY -> section == NeedsYouSection.MESSAGES
        NeedsYouFilter.FYI -> section == NeedsYouSection.FYI
        NeedsYouFilter.MAIL -> section == NeedsYouSection.MAIL
    }

    /** "W" for "Your wife"'s first word after "Your", "GT" for "Game testing group", "?" for no letters. */
    fun initials(name: String): String {
        val words = name.split(' ', '-', '_').map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        val use = if (words.size > 1 && words.first().lowercase() in setOf("your", "my", "the", "a", "an")) words.drop(1) else words
        val letters = use.take(2).map { it.first().uppercaseChar() }.joinToString("")
        return letters.ifEmpty { "?" }
    }

    /** "14:02" from "WhatsApp · 14:02"; the whole meta when it has no dot. */
    fun time(meta: String): String = meta.substringAfterLast(" · ", meta).trim()

    /** A 1:1 chat's line without its own name in front ("Tunde: Asks…" → "Asks…"); a group keeps who said it. */
    fun line(chat: TriageChat): String {
        val raw = chat.summary?.takeIf { it.isNotBlank() } ?: chat.top.firstOrNull() ?: chat.cards.lastOrNull()?.gist ?: chat.countLine
        val prefix = "${chat.title}: "
        // The summary may name them as WhatsApp did ("Mato kano: …") while the title is "Mato Kano".
        val trimmed = if (!chat.isGroup && raw.startsWith(prefix, ignoreCase = true)) raw.substring(prefix.length) else raw
        return trimmed.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { TriageCard.NO_GIST_LINE }
    }

    /** "1 request" first, then "2 to reply", else "3 new". */
    fun badge(chat: TriageChat): Pair<String, Boolean> = when {
        chat.requests.isNotEmpty() -> plural(chat.requests.size, "request", "requests") to true
        chat.replies > 0 -> "${chat.replies} to reply" to true
        chat.askedBy != null -> "1 to reply" to true
        else -> "${chat.cards.size} new" to false
    }

    fun row(chat: TriageChat): NeedsYouMessageRow {
        val (badge, hot) = badge(chat)
        val time = time(chat.meta)
        val line = line(chat)
        return NeedsYouMessageRow(
            key = chat.key, initials = initials(chat.title), name = chat.title, time = time, line = line,
            badge = badge, hot = hot, spoken = "${chat.title}, $time. $line. $badge.",
        )
    }

    fun fyiRow(chat: TriageChat): NeedsYouFyiRow {
        val what = line(chat)
        return NeedsYouFyiRow(chat.key, chat.title, what, "${chat.title}: $what. No reply needed.")
    }

    /** "FYI cleared · 4 chats" for the undo bar after Clear FYI. */
    fun clearedLine(n: Int): String = "FYI cleared · " + plural(n, "chat", "chats")

    private fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
}
