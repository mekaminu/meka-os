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
    /**
     * The same group's digest chatter not yet caught up (slice 3b): shown once, inside this card, instead of a digest
     * card of its own. 0 for a 1:1 chat or a group with nothing in the digest.
     */
    val chatter: Int = 0,
    /** "+ 14 more in the group · Lineup debate for Getafe": the chatter's count and MEKA's gist when there is one. */
    val chatterLine: String? = null,
    /** The digest's group key ([GroupDigestRules.groupKey]) for a group chat, so Done catches the chatter up too. */
    val groupKey: String? = null,
    /**
     * MEKA's one-line summary of a busy chat (slice 3c, [ChatSummaryRules.withSummaries]): shown first, with only the
     * lines that need a reply kept under it. Null: the top lines are the card's summary.
     */
    val summary: String? = null,
    /**
     * Cards for a message already in [cards], seen a second time under another notification (Fold review 2026-10-12,
     * bug 1): never shown, but Done and Not now clear them with the chat so they can't come back as a card of their own.
     */
    val repeatIds: List<String> = emptyList(),
    /**
     * "Jeanette" when someone Meka watches for asked something in this group (Fold review 2026-10-12, bug 3): the card
     * shows [TriageChatRules.askedLine] in brass above its name and the chat sits at the top of Needs you.
     */
    val askedBy: String? = null,
) {
    /** One message, no request and no chatter: the message's own card, as before. */
    val single: Boolean get() = cards.size == 1 && requests.isEmpty() && chatter == 0
    val ids: List<String> get() = cards.map { it.id } + repeatIds
}

/**
 * Groups the open triage cards per chat, non-AI and pure. Chats with something needing a reply come first, then the
 * most recent; within a chat the top lines are replies first, then the newest. Nothing here sends or marks anything.
 */
object TriageChatRules {
    const val TOP = 3
    const val DONE = "Done"
    const val SHOW_LESS = "Show less"
    /** Above the group's digest chatter under Show all (slice 3b). */
    const val CHATTER_LABEL = "ALSO IN THE GROUP"

    /** The chat a card belongs to: the same on both devices (the person's key, or the group's name in lower case). */
    fun key(card: TriageCard): String {
        val app = card.app?.name ?: "APP"
        val who = ChatTitleRules.cleanOrNull(card.group)?.lowercase()?.let { "g:$it" } ?: "p:${card.personKey}"
        return "$app|$who"
    }

    /** "Jeanette asked": above a group's card when someone Meka watches for asked something there. */
    fun askedLine(name: String): String = "$name asked"

    /** A group where someone Meka watches for asked something first, then chats needing a reply, then the newest. */
    fun chats(cards: List<TriageCard>): List<TriageChat> =
        cards.groupBy { key(it) }.map { (k, list) -> chat(k, list) }
            .sortedWith(
                compareByDescending<TriageChat> { it.askedBy != null }
                    .thenByDescending { it.replies > 0 }
                    .thenByDescending { it.latestMs },
            )

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

    /**
     * The digest's group key for a group chat ([People.key] of its name, as [GroupDigestRules.groupKey] makes it from
     * the notification's conversation); null for a 1:1 chat.
     */
    fun groupKeyOf(chat: TriageChat): String? = if (chat.isGroup) People.key(chat.title).takeIf { it.isNotEmpty() } else null

    /** Every group chat's digest key: the digest leaves these groups out (they are shown once, in the chat's card). */
    fun groupKeys(chats: List<TriageChat>): List<String> = chats.mapNotNull { groupKeyOf(it) }.distinct()

    /**
     * Folds the Fold's digest cards into the group chats that also have a Needs you card (slice 3b): the chat's count
     * line gains "+ 14 more in the group" and its [TriageChat.chatterLine], with MEKA's gist from [gists] when one was
     * made for the group. A chat with no chatter is unchanged. The digest itself drops those groups
     * ([GroupDigestRules.view] with `inChats`), so nothing is shown twice.
     */
    fun withDigest(chats: List<TriageChat>, digest: List<GroupDigestCard>, gists: List<GroupGist> = emptyList()): List<TriageChat> {
        val counts = digest.associate { it.groupKey to it.count }
        val gistOf = gists.mapNotNull { g -> g.gist?.let { g.groupKey to it } }.toMap()
        return chats.map { c -> fold(c, counts, gistOf) }
    }

    /**
     * The Mac's half (slice 3b): the synced digest cards ([GroupGist], the Fold's count and MEKA's gist, never the
     * messages) folded into the matching group chats, as [withDigest] does on the Fold.
     */
    fun withGists(chats: List<TriageChat>, gists: List<GroupGist>): List<TriageChat> {
        val counts = gists.associate { it.groupKey to it.count }
        val gistOf = gists.mapNotNull { g -> g.gist?.let { g.groupKey to it } }.toMap()
        return chats.map { c -> fold(c, counts, gistOf) }
    }

    /** The synced digest cards no chat carries: the Mac's digest shows only these. */
    fun gistsOutside(chats: List<TriageChat>, gists: List<GroupGist>): List<GroupGist> {
        val inside = groupKeys(chats).toSet()
        return gists.filter { it.groupKey !in inside }
    }

    /** The synced digest cards a chat carries, for Done to catch them up with the chat. */
    fun gistsInside(chat: TriageChat, gists: List<GroupGist>): List<GroupGist> {
        val key = chat.groupKey ?: return emptyList()
        return gists.filter { it.groupKey == key }
    }

    /** "+ 14 more in the group", with " · " and MEKA's gist when there is one. */
    fun chatterLine(count: Int, gist: String?): String = buildString {
        append("+ $count more in the group")
        gist?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
    }

    private fun fold(c: TriageChat, counts: Map<String, Int>, gistOf: Map<String, String>): TriageChat {
        val key = groupKeyOf(c) ?: return c
        val n = counts[key]?.takeIf { it > 0 } ?: return c.copy(groupKey = key)
        val line = chatterLine(n, gistOf[key])
        return c.copy(
            chatter = n, chatterLine = line, groupKey = key,
            countLine = c.countLine + " · +$n in the group",
            spoken = c.spoken + " " + line.replace(" · ", ". ").removePrefix("+ ").replaceFirstChar { it.uppercase() } + ".",
        )
    }

    /** "Show all 20". */
    fun expandLabel(chat: TriageChat): String = "Show all ${chat.cards.size}"

    /** The undo bar after Done: "Done · Barça lads · 20 messages". */
    fun doneLine(chat: TriageChat): String = buildString {
        append("$DONE · ${chat.title} · ${chat.cards.size} ${if (chat.cards.size == 1) "message" else "messages"}")
        // Done never answers a request: it stays in Needs you on its own.
        if (chat.chatter > 0) append(" · group caught up")
        val n = chat.requests.size
        if (n > 0) append(" · $n ${if (n == 1) "request" else "requests"} still waiting")
    }

    /** How many chats have a reply waiting, for the Needs you badge (one per chat, not per message). */
    fun replyChats(cards: List<TriageCard>): Int =
        cards.filter { it.lane == TriageLane.NEEDS_REPLY }.map { key(it) }.distinct().size

    /**
     * The same message stored twice (once from the group's notification, once from WhatsApp's bundled "… (2 messages)"
     * one): same sender at the same moment. The one needing a reply is kept, else the first stored.
     */
    internal fun repeatKey(card: TriageCard): String = "${card.personKey}|${card.atMs}"

    private fun chat(key: String, all: List<TriageCard>): TriageChat {
        val kept = all.sortedBy { if (it.lane == TriageLane.NEEDS_REPLY) 0 else 1 }.distinctBy { repeatKey(it) }
        val keptIds = kept.map { it.id }.toSet()
        val repeats = all.map { it.id }.filter { it !in keptIds }
        val list = all.filter { it.id in keptIds }
        val oldestFirst = list.sortedBy { it.atMs }
        val latest = oldestFirst.last()
        val group = ChatTitleRules.cleanOrNull(latest.group ?: list.firstNotNullOfOrNull { it.group })
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
        val askedBy = if (group != null) list.filter { it.askedBy != null }.maxByOrNull { it.atMs }?.askedBy else null
        val ranked = list.sortedWith(
            compareBy<TriageCard> { if (it.askedBy != null) 0 else 1 }
                .thenBy { if (it.lane == TriageLane.NEEDS_REPLY) 0 else 1 }
                .thenBy { if ('?' in it.gist) 0 else 1 }
                .thenByDescending { it.atMs },
        )
        val top = ranked.map { c ->
            val who = TriageReplyRules.who(c).substringBefore(" in ")
            if (group != null) "$who: ${c.gist}" else c.gist
        }.distinct().take(TOP)
        val spoken = buildString {
            askedBy?.let { append(askedLine(it)).append(" in ") }
            append(title).append(", ").append(countLine.replace(" · ", ", ")).append(". ")
            top.forEach { append(it.trimEnd('.')).append(". ") }
        }.trim()
        return TriageChat(
            key, title, latest.app, group != null, countLine, meta, top, oldestFirst, replies, latest.atMs, spoken,
            repeatIds = repeats, askedBy = askedBy,
        )
    }
}
