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
    /**
     * Requests from the same person (slice 3): shown inside the chat's card with their own Add · Change · Not a task,
     * never answered by Done. Only a 1:1 chat carries them ([TriageChatRules.chatsWith]).
     */
    val requests: List<RequestCard> = emptyList(),
) {
    /** One message and no request: the message's own card, as before. */
    val single: Boolean get() = cards.size == 1 && requests.isEmpty()
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

    /**
     * [chats] with each request from a person filed under that person's 1:1 chat (the latest one when they wrote on two
     * apps), so Jeanette's messages and what she asked are one card (slice 3). A request from a group, from Meka himself
     * ("Handle this", the brief's share) or from someone with no open message stays a card of its own ([looseRequests]).
     */
    fun chatsWith(cards: List<TriageCard>, requests: List<RequestCard>): List<TriageChat> {
        val plain = chats(cards)
        if (requests.isEmpty()) return plain
        val home = mutableMapOf<String, String>()
        plain.filter { !it.isGroup }.sortedByDescending { it.latestMs }.forEach { c ->
            val person = c.cards.first().personKey
            if (person.isNotBlank()) home.getOrPut(person) { c.key }
        }
        val byChat = requests.filter { it.person.isNotBlank() && it.personKey.isNotBlank() }
            .mapNotNull { r -> home[r.personKey]?.let { it to r } }
            .groupBy({ it.first }, { it.second })
        return plain.map { c ->
            val mine = byChat[c.key]?.sortedBy { it.atMs } ?: return@map c
            val n = mine.size
            c.copy(
                requests = mine,
                countLine = c.countLine + " · $n ${if (n == 1) "request" else "requests"}",
                spoken = c.spoken + " " + mine.joinToString(" ") { it.action.trimEnd('.') + "." },
            )
        }
    }

    /** The requests [chatsWith] didn't put inside a chat, in their own order: Needs you shows these on their own. */
    fun looseRequests(chats: List<TriageChat>, requests: List<RequestCard>): List<RequestCard> {
        val inside = chats.flatMap { c -> c.requests.map { it.id } }.toSet()
        return requests.filter { it.id !in inside }
    }

    /** "Show all 20". */
    fun expandLabel(chat: TriageChat): String = "Show all ${chat.cards.size}"

    /** The undo bar after Done: "Done · Barça lads · 20 messages". */
    fun doneLine(chat: TriageChat): String = buildString {
        append("$DONE · ${chat.title} · ${chat.cards.size} ${if (chat.cards.size == 1) "message" else "messages"}")
        // Done never answers a request: it stays in Needs you on its own.
        val n = chat.requests.size
        if (n > 0) append(" · $n ${if (n == 1) "request" else "requests"} still waiting")
    }

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
