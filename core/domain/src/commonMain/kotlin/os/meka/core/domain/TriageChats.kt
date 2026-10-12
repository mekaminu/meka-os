package os.meka.core.domain

/**
 * One conversation's open message cards as one Needs you card (build plan V1, "Messages grouped per chat", slice 1):
 * everything waiting from the same chat (a group that named Meka, or one person, per app) instead of a card per message.
 * A chat with one card shows that card as before ([single]); two or more show [title], [countLine], the [top] lines
 * and Expand for every card ([cards], oldest first, each keeping its own Send · Edit · Not now).
 */
data class TriageChat(
    /** The chat: app plus group name, or app plus person ([TriageChatRules.key]). */
    val key: String,
    /** "Barça lads", "Tunde". */
    val title: String,
    val app: CaptureApp?,
    /** True for a group chat. */
    val isGroup: Boolean,
    /** "20 new · 4 people", "3 new", "2 new · 1 needs a reply". */
    val countLine: String,
    /** "WhatsApp · 14:02": the app and the latest message's time. */
    val meta: String,
    /** Up to [TriageChatRules.TOP] lines of what matters, a reply first: "Tunde: Asks if you're coming Saturday". */
    val top: List<String>,
    /** Every card in the chat, oldest first, for Expand. */
    val cards: List<TriageCard>,
    /** How many of [cards] need a reply. */
    val replies: Int,
    val latestMs: Long,
    /** One sentence for TalkBack / VoiceOver. */
    val spoken: String,
) {
    val single: Boolean get() = cards.size == 1
    val ids: List<String> get() = cards.map { it.id }
}

/**
 * Groups the open triage cards per chat, non-AI and pure. Chats with something needing a reply come first, then the
 * most recent; within a chat the top lines are replies first, then the newest. Nothing here sends or marks anything.
 */
object TriageChatRules {
    const val TOP = 3
    const val DONE = "Done"
    const val SHOW_LESS = "Show less"

    /** The chat a card belongs to: the same on both devices (the person's key, or the group's name in lower case). */
    fun key(card: TriageCard): String {
        val app = card.app?.name ?: "APP"
        val who = card.group?.trim()?.lowercase()?.let { "g:$it" } ?: "p:${card.personKey}"
        return "$app|$who"
    }

    fun chats(cards: List<TriageCard>): List<TriageChat> =
        cards.groupBy { key(it) }.map { (k, list) -> chat(k, list) }
            .sortedWith(compareByDescending<TriageChat> { it.replies > 0 }.thenByDescending { it.latestMs })

    /** "Show all 20". */
    fun expandLabel(chat: TriageChat): String = "Show all ${chat.cards.size}"

    /** The undo bar after Done: "Done · Barça lads · 20 messages". */
    fun doneLine(chat: TriageChat): String =
        "$DONE · ${chat.title} · ${chat.cards.size} ${if (chat.cards.size == 1) "message" else "messages"}"

    /** How many chats have a reply waiting, for the Needs you badge (one per chat, not per message). */
    fun replyChats(cards: List<TriageCard>): Int =
        cards.filter { it.lane == TriageLane.NEEDS_REPLY }.map { key(it) }.distinct().size

    private fun chat(key: String, list: List<TriageCard>): TriageChat {
        val oldestFirst = list.sortedBy { it.atMs }
        val latest = oldestFirst.last()
        val group = latest.group ?: list.firstNotNullOfOrNull { it.group }
        val title = group ?: TriageReplyRules.who(latest).substringBefore(" in ")
        val people = list.map { it.personKey }.distinct()
        val replies = list.count { it.lane == TriageLane.NEEDS_REPLY }
        val n = list.size
        val countLine = buildString {
            append("$n new")
            if (group != null && people.size > 1) append(" · ${people.size} people")
            if (replies > 0 && n > 1) append(" · $replies ${if (replies == 1) "needs" else "need"} a reply")
        }
        val time = latest.from.substringAfterLast(" · ", "").takeIf { it.isNotBlank() }
        val meta = listOfNotNull(TriageReplyRules.appName(latest.app), time).joinToString(" · ")
        val ranked = list.sortedWith(
            compareBy<TriageCard> { if (it.lane == TriageLane.NEEDS_REPLY) 0 else 1 }
                .thenBy { if ('?' in it.gist) 0 else 1 }
                .thenByDescending { it.atMs },
        )
        val top = ranked.map { c ->
            val who = TriageReplyRules.who(c).substringBefore(" in ")
            if (group != null) "$who: ${c.gist}" else c.gist
        }.distinct().take(TOP)
        val spoken = buildString {
            append(title).append(", ").append(countLine.replace(" · ", ", ")).append(". ")
            top.forEach { append(it.trimEnd('.')).append(". ") }
        }.trim()
        return TriageChat(key, title, latest.app, group != null, countLine, meta, top, oldestFirst, replies, latest.atMs, spoken)
    }
}
