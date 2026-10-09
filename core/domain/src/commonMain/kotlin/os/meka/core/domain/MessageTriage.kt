package os.meka.core.domain

/**
 * The messages assistant (build plan V1, "Messages assistant — all WhatsApp and texts", slice 1): every incoming message
 * the notification listener reads is sorted into **Needs a reply** · **Action for you** · **FYI**, and group chatter
 * that doesn't mention Meka goes to a **Group digest** with no AI at all. Non-AI and pure: which messages go to the AI,
 * what a model's triage is allowed to become, and the digest's cards.
 *
 * Messages are untrusted (ADR-006): a triage never acts by itself. A draft reply is text shown to Meka, sent only by
 * his tap (personal messages stay at "ask me"); an action is a Needs you card ([MessageRequestRules]).
 */
enum class TriageLane(val wire: String, val label: String) {
    NEEDS_REPLY("needs_reply", "Needs a reply"),
    ACTION("action", "Action for you"),
    FYI("fyi", "FYI"),
    GROUP_DIGEST("group_digest", "Group digest"),
    ;

    companion object {
        /** The lanes a model may choose; the group digest is MEKA's own (no mention, no AI). */
        val fromModel = listOf(NEEDS_REPLY, ACTION, FYI)

        fun of(wire: String?): TriageLane? = fromModel.firstOrNull { it.wire == wire }
    }
}

/** What MEKA does with a busy group's messages (Work mode → Messages, per group). */
enum class GroupMode(val wire: String, val label: String) {
    /** Gathered into the lunchtime and evening digest; MEKA clears the group's notifications after reading them. */
    DIGEST("digest", "Digest"),
    /** Left as WhatsApp shows them; only a mention of Meka is triaged. */
    NORMAL("normal", "Normal"),
    /** Never read, not even a mention. */
    IGNORE("ignore", "Ignore"),
    ;

    companion object {
        fun of(wire: String?): GroupMode = entries.firstOrNull { it.wire == wire } ?: DIGEST
    }
}

/**
 * Meka's choices for the assistant: each group's [GroupMode] (by the group's name; unlisted groups are [GroupMode.DIGEST]),
 * the people and groups whose messages never go to the AI, and the names a group message mentions him by.
 */
data class TriageSettings(
    val groupModes: Map<String, GroupMode> = emptyMap(),
    val neverToAi: Set<String> = emptySet(),
    val names: List<String> = MessageTriageRules.DEFAULT_NAMES,
) {
    private val modeByKey = groupModes.mapKeys { People.key(it.key) }
    private val privateKeys = neverToAi.map(People::key).toSet()

    fun modeOf(group: String): GroupMode = modeByKey[People.key(group)] ?: GroupMode.DIGEST
    fun isPrivate(name: String?): Boolean = name != null && People.key(name) in privateKeys
}

/** Where one captured message goes before any AI. */
sealed interface TriageRoute {
    /** Not a message with text, an ignored group, or a group left as WhatsApp shows it. */
    data object Skip : TriageRoute
    /** To the AI for a lane ([mentioned]: a group message that names Meka). */
    data class AskAi(val mentioned: Boolean) : TriageRoute
    /** A busy group's chatter, kept for the digest; no AI. */
    data class Digest(val groupKey: String) : TriageRoute
    /** A voice note: "Listen to Tunde's voice note", no AI. */
    data object VoiceNote : TriageRoute
    /** FYI with no AI: a person or group Meka keeps from the AI, or a bare photo. */
    data object LocalFyi : TriageRoute
}

/** A model's triage as the server passed it on (unchecked). */
data class RawTriage(
    val lane: String?,
    val draft: String? = null,
    val summary: String? = null,
    val proposals: List<RawRequestProposal> = emptyList(),
)

/** A checked triage: a lane, a reply draft only when it needs a reply, the gist, and checked proposals for an action. */
data class MessageTriage(
    val lane: TriageLane,
    val draft: String? = null,
    val summary: String? = null,
    val proposals: List<RequestProposal> = emptyList(),
)

/** One busy group's card in the digest. */
data class GroupDigestCard(
    val groupKey: String,
    /** "Barça lads". */
    val title: String,
    /** "47 messages". */
    val countLine: String,
    /** "Tunde, Ade and 3 others". */
    val people: String,
    /** The latest few, oldest first: "Tunde: lineup for Getafe?". */
    val recent: List<String>,
    val count: Int,
    val latestMs: Long,
    /** One sentence for TalkBack / VoiceOver. */
    val spoken: String,
)

object MessageTriageRules {
    val DEFAULT_NAMES = listOf("Meka", "Chukwuemeka")
    const val MAX_DRAFT = 400
    const val MAX_SUMMARY = 120
    /** Lines shown under a group's digest card before it is expanded. */
    const val DIGEST_RECENT = 3
    const val DIGEST_LINE_CHARS = 90

    private val url = Regex("""\b(?:https?://|www\.)|\b[\w.-]+\.(?:com|net|org|io|co|uk|ly|me|app|link)(?:/\S*)?\b""", RegexOption.IGNORE_CASE)
    private val email = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
    private val phone = Regex("""\+?\d[\d ()-]{7,}\d""")

    /**
     * Whether a group message names Meka: "@Meka", "Meka," or "Chukwuemeka" as a whole word, any case. A reply to him
     * isn't visible in a notification, so it only counts when the text names him.
     */
    fun mentions(text: String, names: List<String> = DEFAULT_NAMES): Boolean = names.any { n ->
        val name = n.trim()
        if (name.isEmpty()) return@any false
        var at = text.indexOf(name, ignoreCase = true)
        while (at >= 0) {
            val before = text.getOrNull(at - 1)
            val after = text.getOrNull(at + name.length)
            if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return@any true
            at = text.indexOf(name, at + 1, ignoreCase = true)
        }
        false
    }

    /**
     * Where [item] goes: a group message that names Meka is triaged like a 1:1 message (unless the group is ignored);
     * other group messages are digested or left alone by the group's mode; a voice note becomes a reminder and a
     * person or group in [TriageSettings.neverToAi] is FYI, both with no AI.
     */
    fun route(item: CapturedItem, settings: TriageSettings = TriageSettings()): TriageRoute {
        val text = item.text?.trim().orEmpty()
        if (item.kind == CaptureKind.MISSED_CALL || text.isEmpty()) return TriageRoute.Skip
        val group = item.conversation?.trim()?.takeIf { it.isNotEmpty() }
        val voice = item.kind == CaptureKind.VOICE_MESSAGE || MessageRequestRules.isVoiceNote(text)
        if (group != null) {
            val mode = settings.modeOf(group)
            if (mode == GroupMode.IGNORE) return TriageRoute.Skip
            if (!mentions(text, settings.names)) {
                return if (mode == GroupMode.DIGEST) TriageRoute.Digest(People.key(group)) else TriageRoute.Skip
            }
        }
        return when {
            voice -> TriageRoute.VoiceNote
            settings.isPrivate(item.personName) || settings.isPrivate(group) -> TriageRoute.LocalFyi
            !MessageRequestRules.worthAsking(text) -> TriageRoute.LocalFyi
            else -> TriageRoute.AskAi(mentioned = group != null)
        }
    }

    /**
     * The model's triage checked: an unknown lane is FYI; a draft is kept only for a message that needs a reply, as one
     * clean paragraph of at most [MAX_DRAFT] characters, and dropped when it carries a link, an email address or a phone
     * number of nine digits or more (a message can't get MEKA to put an address in Meka's mouth); an action keeps only proposals
     * [MessageRequestRules.check] allows, and with none it is FYI. [today] and [nowMinute] are the time the message came.
     */
    fun check(raw: RawTriage, today: Long, nowMinute: Int): MessageTriage {
        val summary = cleanLine(raw.summary, MAX_SUMMARY)
        return when (TriageLane.of(raw.lane)) {
            TriageLane.NEEDS_REPLY -> MessageTriage(TriageLane.NEEDS_REPLY, cleanDraft(raw.draft), summary)
            TriageLane.ACTION -> {
                val proposals = MessageRequestRules.check(raw.proposals, today, nowMinute)
                if (proposals.isEmpty()) MessageTriage(TriageLane.FYI, summary = summary)
                else MessageTriage(TriageLane.ACTION, summary = summary, proposals = proposals)
            }
            else -> MessageTriage(TriageLane.FYI, summary = summary)
        }
    }

    /**
     * The messages from one notification the listener triages now (slice 2): not [seen] already, from the last
     * [RequestWatchRules.MAX_AGE_MS], not [route]d to Skip, once each, the newest [RequestWatchRules.MAX_PER_NOTIFICATION]
     * in time order (WhatsApp re-posts a chat's unread history; only the new ones cost a call).
     */
    fun toTriage(items: List<CapturedItem>, settings: TriageSettings, seen: Set<String>, nowMs: Long): List<CapturedItem> =
        items.asSequence()
            .filter { it.id !in seen && it.atMs >= nowMs - RequestWatchRules.MAX_AGE_MS && it.atMs <= nowMs + FUTURE_SLACK_MS }
            .filter { route(it, settings) != TriageRoute.Skip }
            .distinctBy { it.id }
            .sortedBy { it.atMs }
            .toList()
            .takeLast(RequestWatchRules.MAX_PER_NOTIFICATION)

    /** How long the phone keeps a group's chatter for the digest (sealed on the phone, never synced). */
    const val DIGEST_RETENTION_MS = 7 * 24 * 60 * 60_000L
    /** A message stamped a little ahead of the phone's clock still counts. */
    const val FUTURE_SLACK_MS = 5 * 60_000L

    /** [kept] plus [incoming] for the digest: once each by id, nothing older than [DIGEST_RETENTION_MS], oldest first. */
    fun keepForDigest(kept: List<CapturedItem>, incoming: List<CapturedItem>, nowMs: Long): List<CapturedItem> =
        (kept + incoming).filter { it.atMs >= nowMs - DIGEST_RETENTION_MS }.distinctBy { it.id }.sortedBy { it.atMs }

    /** One paragraph (spaces collapsed), no control characters, at most [MAX_DRAFT] characters; null when empty or unsafe. */
    fun cleanDraft(s: String?): String? {
        val t = s?.replace(Regex("""[\u0000-\u001F\u007F]"""), " ")?.replace(Regex("""\s+"""), " ")?.trim()?.trim('"', '“', '”')?.trim()
        if (t.isNullOrEmpty() || t.length > MAX_DRAFT) return null
        if (url.containsMatchIn(t) || email.containsMatchIn(t)) return null
        if (phone.findAll(t).any { m -> m.value.count(Char::isDigit) >= 9 }) return null
        return t
    }

    /**
     * The digest's cards from [items] (captured messages) that [route] sends to the digest and that came after
     * [sinceMs]: one per group, busiest first (then the latest), each with its count, who wrote and the latest
     * [DIGEST_RECENT] lines. A group with nothing new has no card.
     */
    fun digest(items: List<CapturedItem>, settings: TriageSettings, sinceMs: Long): List<GroupDigestCard> =
        items.asSequence()
            .filter { it.atMs > sinceMs }
            .mapNotNull { item -> (route(item, settings) as? TriageRoute.Digest)?.let { it.groupKey to item } }
            .groupBy({ it.first }, { it.second })
            .map { (key, msgs) -> digestCard(key, msgs.distinctBy { it.id }.sortedBy { it.atMs }) }
            .sortedWith(compareByDescending<GroupDigestCard> { it.count }.thenByDescending { it.latestMs })

    private fun digestCard(key: String, msgs: List<CapturedItem>): GroupDigestCard {
        val title = msgs.last().conversation!!.trim().replace(Regex("""\s+"""), " ").take(40)
        val count = msgs.size
        val countLine = if (count == 1) "1 message" else "$count messages"
        val writers = msgs.reversed().map { it.personName.trim() }.distinctBy(People::key)
        val people = when (writers.size) {
            1 -> writers[0]
            2 -> "${writers[0]} and ${writers[1]}"
            3 -> "${writers[0]}, ${writers[1]} and ${writers[2]}"
            else -> "${writers[0]}, ${writers[1]} and ${writers.size - 2} others"
        }
        val recent = msgs.takeLast(DIGEST_RECENT).map { "${it.personName.trim()}: ${cleanLine(it.text, DIGEST_LINE_CHARS).orEmpty()}" }
        return GroupDigestCard(key, title, countLine, people, recent, count, msgs.last().atMs, "$title, $countLine from $people.")
    }

    private fun cleanLine(s: String?, max: Int): String? {
        val t = s?.replace(Regex("""[\u0000-\u001F\u007F]"""), " ")?.replace(Regex("""\s+"""), " ")?.trim()
        if (t.isNullOrEmpty()) return null
        if (t.length <= max) return t
        val cut = t.take(max)
        val at = cut.lastIndexOf(' ').takeIf { it >= max / 2 } ?: max
        return cut.take(at).trimEnd() + "…"
    }
}
