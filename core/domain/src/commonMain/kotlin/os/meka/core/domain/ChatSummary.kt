package os.meka.core.domain

import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * A busy chat's one-line summary (build plan V1, "Messages grouped per chat", slice 3c). When a chat's card holds
 * [MIN_MESSAGES] or more messages, the Fold sends MEKA's AI the chat's name and each message's sender, time and gist (the
 * line MEKA's AI already wrote for that message; the text itself isn't kept on the phone after triage) in **one** batched
 * call, and gets one line back per chat: what the chat is about and what matters to Meka. Non-AI and pure: which chats
 * and lines are sent, what the answer may become, and how the card shows it.
 *
 * Messages are untrusted (ADR-006): a summary is display text only, never an action. With no summary (AI off, offline,
 * a quiet chat, a chat kept from MEKA's AI) the card keeps its deterministic top lines.
 */
object ChatSummaryRules {
    /** A chat needs this many messages before a summary is worth a call (fewer: the three top lines say it all). */
    const val MIN_MESSAGES = 4
    /** Chats in one call, most recent first. */
    const val MAX_CHATS = 6
    /** The latest lines of each chat sent, oldest first. */
    const val MAX_LINES = 40
    const val MAX_LINE_CHARS = 200
    const val MAX_SUMMARY = 140
    /** A chat with new messages since its summary is summarised again at most this often. */
    const val REFRESH_MS = 10 * 60_000L

    /** A chat as it is sent: its key, name, whether it is a group, and its lines; [upToMs] its newest message. */
    data class Chat(val chatKey: String, val name: String, val isGroup: Boolean, val lines: List<Line>, val upToMs: Long, val count: Int)

    /** One message as it is sent: the sender's label, the time ("13:02") and MEKA's gist of it. */
    data class Line(val from: String, val at: String, val text: String)

    /** One chat's summary as the model gave it (unchecked). */
    data class Raw(val name: String?, val summary: String?)

    /**
     * The chats the call carries: open chats with at least [MIN_MESSAGES] messages MEKA's AI read (a message kept from it
     * is never sent, nor is a chat Meka keeps from it), that have no summary for this batch, or have new messages and a
     * summary older than [REFRESH_MS]; most recent first, at most [MAX_CHATS].
     */
    fun toSummarise(chats: List<TriageChat>, have: List<ChatSummary>, settings: TriageSettings, nowMs: Long): List<Chat> {
        val byKey = have.associateBy { it.chatKey }
        return chats.asSequence()
            .filter { !settings.isPrivate(it.title) }
            .mapNotNull { c ->
                val lines = c.cards.filter { readable(it) && !settings.isPrivate(sender(it)) }
                if (lines.size < MIN_MESSAGES) return@mapNotNull null
                val s = byKey[c.key]
                val due = when {
                    s == null || !belongs(s, c) -> true
                    s.upToMs >= c.latestMs -> false
                    else -> nowMs - s.madeAtMs >= REFRESH_MS
                }
                if (!due) return@mapNotNull null
                Chat(
                    c.key, c.title.take(60), c.isGroup,
                    lines.takeLast(MAX_LINES).map { Line(sender(it).take(60), time(it), cut(it.gist, MAX_LINE_CHARS)) },
                    c.latestMs, c.cards.size,
                )
            }
            .sortedByDescending { it.upToMs }
            .take(MAX_CHATS)
            .toList()
    }

    /**
     * The model's answer checked against what was [sent]: a summary only for a chat that was sent (by name), one clean
     * line of at most [MAX_SUMMARY]. Every chat sent gets an entry (null when the answer had none), so it isn't sent again
     * until it has news.
     */
    fun check(sent: List<Chat>, raw: List<Raw>): Map<String, String?> {
        val byName = sent.associateBy { People.key(it.name) }
        val out = sent.associate { it.chatKey to null as String? }.toMutableMap()
        raw.forEach { r ->
            val chat = byName[People.key(r.name.orEmpty())] ?: return@forEach
            if (out[chat.chatKey] == null) out[chat.chatKey] = GroupGistRules.clean(r.summary, MAX_SUMMARY)
        }
        return out
    }

    /**
     * The chats with their summaries: a busy chat whose summary belongs to its open messages shows the summary first and
     * keeps only the lines that need a reply under it (at most [TriageChatRules.TOP] − 1), so what he must answer stays
     * in view. A chat with no summary is unchanged (its deterministic top lines are the fallback).
     */
    fun withSummaries(chats: List<TriageChat>, summaries: List<ChatSummary>): List<TriageChat> {
        if (summaries.isEmpty()) return chats
        val byKey = summaries.associateBy { it.chatKey }
        return chats.map { c ->
            val s = byKey[c.key]?.takeIf { belongs(it, c) && c.cards.size >= MIN_MESSAGES } ?: return@map c
            val text = s.summary ?: return@map c
            c.copy(
                summary = text,
                top = c.top.take(minOf(c.replies, TriageChatRules.TOP - 1)),
                spoken = c.spoken + " In short: " + text.trimEnd('.') + ".",
            )
        }
    }

    /** A summary belongs to the chat's open messages when it was made after the oldest of them arrived. */
    private fun belongs(s: ChatSummary, c: TriageChat): Boolean = s.upToMs >= (c.cards.firstOrNull()?.atMs ?: Long.MAX_VALUE)

    private fun readable(c: TriageCard) = c.gist.isNotBlank() && c.gist != TriageCard.LOCAL_LINE && c.gist != TriageCard.NO_GIST_LINE

    private fun sender(c: TriageCard) = TriageReplyRules.who(c).substringBefore(" in ")

    private fun time(c: TriageCard): String {
        val t = c.from.substringAfterLast(" · ", "")
        val ok = t.length == 5 && t[2] == ':' && t.removeRange(2, 3).all { it in '0'..'9' }
        return if (ok) t else "00:00"
    }

    private fun cut(s: String, max: Int): String = GroupGistRules.clean(s, max) ?: ""
}

/**
 * Fields of a `chat_summary` (ADR-008 addendum, 2026-10-12): one per chat, written by the Fold, synced so the Mac's chat
 * card shows it too. **Never the messages**: the chat's key and name, how many messages and the newest one's time when it
 * was made, the model's one-line summary (absent when the answer had none or the AI was off) and when it was made. LWW.
 */
object ChatSummaryFields {
    const val CHAT_KEY = "chatKey"
    const val TITLE = "title"
    const val UP_TO = "upToMs"
    const val COUNT = "count"
    /** The model's checked summary; display only, untrusted (ADR-006). */
    const val SUMMARY = "summary"
    const val MADE = "madeAtMs"
}

/** A chat's summary as both apps read it. */
data class ChatSummary(
    val chatKey: String,
    val title: String,
    val summary: String?,
    val upToMs: Long,
    val count: Int,
    val madeAtMs: Long,
)

/** The chats' synced summaries (non-AI): the Fold [save]s, both apps read [all] (the last [RETENTION_MS]). */
class ChatSummaries(private val replica: Replica, private val nowMs: () -> Long) {
    fun save(chat: ChatSummaryRules.Chat, summary: String?) {
        val fields = buildMap {
            put(ChatSummaryFields.CHAT_KEY, chat.chatKey.take(200).fv())
            put(ChatSummaryFields.TITLE, chat.name.take(60).fv())
            put(ChatSummaryFields.UP_TO, chat.upToMs.fv())
            put(ChatSummaryFields.COUNT, chat.count.toLong().fv())
            put(ChatSummaryFields.SUMMARY, (summary?.take(ChatSummaryRules.MAX_SUMMARY + 1) ?: "").fv())
            put(ChatSummaryFields.MADE, nowMs().fv())
        }
        replica.commitLocal(EntityTypes.CHAT_SUMMARY, entityId(chat.chatKey), fields)
    }

    fun all(): List<ChatSummary> {
        val cutoff = nowMs() - RETENTION_MS
        return replica.entities(EntityTypes.CHAT_SUMMARY).mapNotNull { e ->
            val key = e[ChatSummaryFields.CHAT_KEY].textOrNull ?: return@mapNotNull null
            val made = e[ChatSummaryFields.MADE].longOrNull ?: return@mapNotNull null
            if (made < cutoff) return@mapNotNull null
            ChatSummary(
                chatKey = key,
                title = e[ChatSummaryFields.TITLE].textOrNull.orEmpty(),
                summary = e[ChatSummaryFields.SUMMARY].textOrNull?.takeIf { it.isNotBlank() },
                upToMs = e[ChatSummaryFields.UP_TO].longOrNull ?: 0L,
                count = (e[ChatSummaryFields.COUNT].longOrNull ?: 0L).toInt(),
                madeAtMs = made,
            )
        }
    }

    companion object {
        /** Reply cards show for a few days at most; a summary is kept a little longer than its chat can be open. */
        const val RETENTION_MS = 4 * 24 * 60 * 60_000L

        /** The `chat_summary` id for a chat: the same on every device and install. */
        fun entityId(chatKey: String): String = "c" + ActivityRules.fnv64("chat:$chatKey")
    }
}
