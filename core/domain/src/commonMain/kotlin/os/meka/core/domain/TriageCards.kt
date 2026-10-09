package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Fields of a `triage_card` (build plan V1, "Messages assistant", slice 2; ADR-008 addendum): one per message the Fold's
 * listener triaged, synced through Meka's own server so the Mac shows the same Needs you cards. **The message text is
 * never stored here** (privacy, item 6: raw messages stay sealed on the phone) — only who wrote, when, the lane, the
 * model's one-line gist and, for Needs a reply, the drafted reply. An Action's proposals live as [RequestCards].
 * Written once by the Fold; afterwards only [RESOLVED] (TrueWins), [RESOLUTION], [RESOLVED_AT] and the blanked
 * [SUMMARY] / [DRAFT] change.
 */
object TriageCardFields {
    /** The captured message's id ([CapturedItem.id]); the entity's id is a hash of it. */
    const val MESSAGE_ID = "messageId"
    /** The sender's label as the notification named them ("Tunde"). */
    const val PERSON = "person"
    /** The group's name when a group message named Meka; absent for a 1:1 chat. */
    const val GROUP = "group"
    /** [CaptureApp] name, so a reply goes back through the right app. */
    const val APP = "app"
    const val AT = "atMs"
    /** [TriageLane.wire]. */
    const val LANE = "lane"
    /** The model's gist (checked, at most [MessageTriageRules.MAX_SUMMARY]); display only, untrusted (ADR-006). */
    const val SUMMARY = "summary"
    /** The drafted reply (checked by [MessageTriageRules.cleanDraft]); never sent without Meka's tap. */
    const val DRAFT = "draft"
    /** True when the message never went to the AI (a person or group Meka keeps from it). */
    const val LOCAL = "local"
    /** Sent, Not now or Seen on either device: gone from Needs you on both (TrueWins). */
    const val RESOLVED = "resolved"
    /** [TriageResolution.wire]. */
    const val RESOLUTION = "resolution"
    const val RESOLVED_AT = "resolvedAtMs"
}

/** How a triage card left Needs you. */
enum class TriageResolution(val wire: String) {
    /** Meka sent the reply (his tap). */
    SENT("sent"),
    /** Not now. */
    DISMISSED("dismissed"),
    /** An FYI read, or the chat opened. */
    SEEN("seen"),
}

/** One triaged message as both apps show it. */
data class TriageCard(
    /** The captured message's id. */
    val id: String,
    val lane: TriageLane,
    val personKey: String,
    /** "Tunde · 14:02", "Tunde in Barça lads · 14:02". */
    val from: String,
    /** The gist: "Asks if you're coming Saturday"; for a message kept from the AI, [LOCAL_LINE]. */
    val gist: String,
    /** The drafted reply for Needs a reply (none when the model's draft didn't pass the checks). */
    val draft: String?,
    val app: CaptureApp?,
    /** True for a group message that named Meka. */
    val mentioned: Boolean,
    val atMs: Long,
    /** One sentence for TalkBack / VoiceOver. */
    val spoken: String,
) {
    companion object {
        const val LOCAL_LINE = "Not sent to MEKA's AI"
        const val NO_GIST_LINE = "Sent you a message"
    }
}

/**
 * The triaged messages as synced entities, non-AI. The Fold [save]s one card per message (a re-post, or the same message
 * read again, writes nothing, and a resolved card is never revived); both apps read [open]; [resolve] on either clears
 * it everywhere and blanks the gist and draft. Needs a reply shows for [REPLY_RETENTION_MS], FYI for [FYI_RETENTION_MS];
 * an Action's card is kept only so it isn't triaged twice (its proposals are the request cards Needs you shows).
 */
class TriageCards(private val replica: Replica, private val nowMs: () -> Long, private val calendar: LocalCalendar) {
    /**
     * Writes [triage] for [item] unless that message already has a card; returns the card shown for it (null when it
     * was already stored, or it is an Action, which Needs you shows as request cards). [local]: kept from the AI.
     */
    fun save(item: CapturedItem, triage: MessageTriage, local: Boolean = false): TriageCard? {
        val id = entityId(item.id)
        if (replica.entity(EntityTypes.TRIAGE_CARD, id) != null) return null
        val group = item.conversation?.trim()?.takeIf { it.isNotEmpty() }
        replica.commitLocal(
            EntityTypes.TRIAGE_CARD, id,
            buildMap {
                put(TriageCardFields.MESSAGE_ID, item.id.take(200).fv())
                put(TriageCardFields.PERSON, item.personName.trim().take(200).fv())
                group?.let { put(TriageCardFields.GROUP, it.take(200).fv()) }
                put(TriageCardFields.APP, item.app.name.fv())
                put(TriageCardFields.AT, item.atMs.fv())
                put(TriageCardFields.LANE, triage.lane.wire.fv())
                triage.summary?.let { put(TriageCardFields.SUMMARY, it.take(MessageTriageRules.MAX_SUMMARY + 1).fv()) }
                if (triage.lane == TriageLane.NEEDS_REPLY) triage.draft?.let { put(TriageCardFields.DRAFT, it.fv()) }
                if (local) put(TriageCardFields.LOCAL, true.fv())
            },
        )
        return open().firstOrNull { it.id == item.id }
    }

    /** What is waiting: Needs a reply first, then FYI; newest first within each. Actions are request cards instead. */
    fun open(): List<TriageCard> {
        val now = nowMs()
        return stored().filter { s ->
            !s.resolved && when (s.lane) {
                TriageLane.NEEDS_REPLY -> s.atMs >= now - REPLY_RETENTION_MS
                TriageLane.FYI -> s.atMs >= now - FYI_RETENTION_MS
                else -> false
            }
        }
            .sortedWith(compareBy<Stored> { it.lane.ordinal }.thenByDescending { it.atMs })
            .map { card(it) }
    }

    /** The open card for the message [messageId], or null once it is resolved, gone or never was. */
    fun find(messageId: String): TriageCard? = open().firstOrNull { it.id == messageId }

    /** Whether the message [messageId] was triaged already (on any device), open or not. */
    fun known(messageId: String): Boolean = replica.entity(EntityTypes.TRIAGE_CARD, entityId(messageId)) != null

    /**
     * Takes the message [messageId]'s card out of Needs you on every device and blanks its gist and draft. False when it
     * was already resolved (the other device got there first) or isn't known.
     */
    fun resolve(messageId: String, how: TriageResolution): Boolean {
        val id = entityId(messageId)
        val e = replica.entity(EntityTypes.TRIAGE_CARD, id) ?: return false
        if (e[TriageCardFields.RESOLVED].boolOrNull == true) return false
        replica.commitLocal(
            EntityTypes.TRIAGE_CARD, id,
            mapOf(
                TriageCardFields.RESOLVED to true.fv(),
                TriageCardFields.RESOLUTION to how.wire.fv(),
                TriageCardFields.RESOLVED_AT to nowMs().fv(),
                TriageCardFields.SUMMARY to FieldValue.Null,
                TriageCardFields.DRAFT to FieldValue.Null,
            ),
        )
        return true
    }

    private class Stored(
        val messageId: String,
        val person: String,
        val group: String?,
        val app: CaptureApp?,
        val atMs: Long,
        val lane: TriageLane,
        val summary: String?,
        val draft: String?,
        val local: Boolean,
        val resolved: Boolean,
    )

    private fun stored(): List<Stored> = replica.entities(EntityTypes.TRIAGE_CARD).mapNotNull { e ->
        val messageId = e[TriageCardFields.MESSAGE_ID].textOrNull ?: return@mapNotNull null
        val person = e[TriageCardFields.PERSON].textOrNull ?: return@mapNotNull null
        val at = e[TriageCardFields.AT].longOrNull ?: return@mapNotNull null
        val lane = TriageLane.entries.firstOrNull { it.wire == e[TriageCardFields.LANE].textOrNull } ?: return@mapNotNull null
        Stored(
            messageId, person,
            group = e[TriageCardFields.GROUP].textOrNull,
            app = e[TriageCardFields.APP].textOrNull?.let { n -> CaptureApp.entries.firstOrNull { it.name == n } },
            atMs = at, lane = lane,
            summary = e[TriageCardFields.SUMMARY].textOrNull,
            draft = e[TriageCardFields.DRAFT].textOrNull,
            local = e[TriageCardFields.LOCAL].boolOrNull == true,
            resolved = e[TriageCardFields.RESOLVED].boolOrNull == true,
        )
    }

    private fun card(s: Stored): TriageCard {
        val who = s.person.trim().take(40)
        val time = TaskWhenRules.timeLabel(calendar.minuteOfDay(s.atMs))
        val from = if (s.group != null) "$who in ${s.group.trim().take(40)} · $time" else "$who · $time"
        val gist = when {
            s.local -> TriageCard.LOCAL_LINE
            else -> s.summary?.takeIf { it.isNotBlank() } ?: TriageCard.NO_GIST_LINE
        }
        val draft = if (s.lane == TriageLane.NEEDS_REPLY) s.draft else null
        val spoken = buildString {
            append(if (s.group != null) "$who in ${s.group.trim()}" else who)
            append(", ").append(s.lane.label).append(": ").append(gist.trimEnd('.')).append('.')
            draft?.let { append(" Suggested reply: ").append(it) }
        }
        return TriageCard(
            id = s.messageId, lane = s.lane, personKey = People.key(s.person), from = from, gist = gist, draft = draft,
            app = s.app, mentioned = s.group != null, atMs = s.atMs, spoken = spoken,
        )
    }

    companion object {
        const val REPLY_RETENTION_MS = 7 * 24 * 60 * 60_000L
        const val FYI_RETENTION_MS = 2 * 24 * 60 * 60_000L

        /** The `triage_card` id for a captured message: the same on every device and install. */
        fun entityId(messageId: String): String = "t" + ActivityRules.fnv64("triage:$messageId")
    }
}
