package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Spam call protection's block list (build plan "Call assistant live — polish" 8b, Meka 2026-10-09 20:59 after a
 * spoofed "police station" call). One synced `blocked_caller` entity per number (ADR-008 addendum 2026-10-09), id the
 * number's key ([CallScreeningRules.callerKey]), so blocking the same number on two devices writes the same entity and
 * unblocking is a field, never a delete. The Fold's call screening rejects a blocked number silently, any time of day;
 * family, the always-notify list, contacts and anyone Meka called lately are never blocked (see
 * [CallScreeningRules.decide]). Numbers only: nothing here leaves Meka's own synced data.
 */
object BlockedCallerFields {
    /** The number as Meka blocked it (display only; matching uses the entity id). */
    const val NUMBER = "number"
    /** True while blocked; Unblock sets false (last writer wins). */
    const val BLOCKED = "blocked"
    const val AT = "atMs"
    /** Why it was blocked ("Scam call: claimed to be the police"), display only. */
    const val WHY = "why"
    /**
     * MEKA's AI thought a message this number left sounded like a scam ("Suspected spam", call assistant polish 8b c;
     * written true by the server, false by Not spam on either device; last writer wins). Absent = never flagged. A
     * suspect is not blocked: its calls go to the assistant until Meka blocks it or says it isn't spam. Added 2026-10-10.
     */
    const val SUSPECTED = "suspected"
    /** The AI's reason in a few words ("Claims to be the police and asks for payment"), display only (untrusted). */
    const val SUSPECTED_WHY = "suspectedWhy"
    const val SUSPECTED_AT = "suspectedAtMs"
}

/** One row of Spam protection's Suspected spam: a number MEKA's AI flagged, waiting for Meka's Block or Not spam. */
data class SuspectedCallerRow(val key: String, val number: String, val line: String)

/** One row of Work mode's Blocked numbers. */
data class BlockedCallerRow(val key: String, val number: String, val line: String)

data class BlockedCallersView(
    val rows: List<BlockedCallerRow>,
    val line: String,
    /** Numbers MEKA's AI flagged and Meka hasn't answered yet (never a blocked one), newest first. */
    val suspects: List<SuspectedCallerRow> = emptyList(),
) {
    val keys: Set<String> get() = rows.map { it.key }.toSet()
    val suspectedKeys: Set<String> get() = suspects.map { it.key }.toSet()

    /** Whether [number] is on the list now (a held message's Block shows "Blocked" instead). */
    fun has(number: String?): Boolean = BlockedCallerRules.keyOf(number)?.let { it in keys } == true

    companion object { val EMPTY = BlockedCallersView(emptyList(), BlockedCallerRules.EMPTY_LINE) }
}

object BlockedCallerRules {
    /** The spoofed "police station" scam call Meka had on 2026-10-09; seeded once on the Fold. */
    const val SEED_NUMBER = "01904 618691"
    const val SEED_WHY = "Scam call claiming to be the police (9 Oct)"
    const val EMPTY_LINE = "No blocked numbers"
    const val MAX_WHY = 120

    /**
     * The key a number is blocked by, or null for something that can't be a phone number (too short, letters,
     * withheld): only a real number can go on the list.
     */
    fun keyOf(number: String?): String? {
        val n = number?.trim().orEmpty()
        if (n.filter { it.isDigit() }.length < 6 || n.any { !it.isDigit() && it !in "+-() ." }) return null
        return CallScreeningRules.callerKey(n).takeIf { it != CallScreeningRules.WITHHELD }
    }

    /**
     * A UK number as people write it: "01904 618691", "07700 900123", "020 7946 0000"; +44 numbers shown the national
     * way. Anything else is returned tidied, as it came.
     */
    fun display(number: String): String {
        val t = number.trim()
        var digits = t.filter { it.isDigit() }
        if (t.startsWith("+44") || (t.startsWith("0044"))) digits = "0" + digits.removePrefix("00").removePrefix("44")
        if (digits.length != 11 || !digits.startsWith("0")) return t.replace(Regex("\\s+"), " ")
        return when {
            digits.startsWith("02") -> "${digits.take(3)} ${digits.substring(3, 7)} ${digits.substring(7)}"
            digits.startsWith("011") || digits[3] == '1' -> "${digits.take(4)} ${digits.substring(4, 7)} ${digits.substring(7)}"
            else -> "${digits.take(5)} ${digits.substring(5)}"
        }
    }

    /** "Blocked today · Scam call claiming to be the police", "Blocked Fri 9 Oct". */
    fun line(atMs: Long, why: String?, nowMs: Long, cal: LocalCalendar): String {
        val label = SearchRules.dayLabel(cal.epochDayOf(atMs), cal.epochDayOf(nowMs))
        val day = if (label == "Today" || label == "Yesterday") label.lowercase() else label
        return "Blocked $day" + (why?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    }

    /** The free scam-report number on every UK network (Ofcom's 7726: "spam" on a keypad). */
    const val REPORT_TO = "7726"
    const val REPORT_LABEL = "Report to 7726"
    const val BLOCK_LABEL = "Block"
    const val BLOCKED_LINE = "Blocked · their calls won't ring"
    /** Under a held message's Block and Report on the Fold: what each does. */
    const val REPORT_HINT = "Report opens Messages with a text to 7726, your network's free scam line; you send it."
    /** The Mac can't send texts: what to send from the phone. */
    fun macReportHint(number: String): String = "To report it, text \u201c${reportText(number)}\u201d to $REPORT_TO from your phone."

    /**
     * The text a scam call is reported with: 7726 takes "Call" and the number that called, written the UK way with
     * no spaces ("Call 01904618691"); null for something that isn't a number.
     */
    fun reportText(number: String?): String? {
        keyOf(number) ?: return null
        return "Call " + display(number!!).filter { it.isDigit() || it == '+' }
    }

    /** Why a number blocked from a held message is on the list: "Left a message · Fri 9 Oct", "Missed call · today". */
    fun whyFromHeld(kind: CaptureKind, atMs: Long, nowMs: Long, cal: LocalCalendar): String {
        val label = SearchRules.dayLabel(cal.epochDayOf(atMs), cal.epochDayOf(nowMs))
        val day = if (label == "Today" || label == "Yesterday") label.lowercase() else label
        val what = when (kind) {
            CaptureKind.VOICE_MESSAGE -> "Left a message"
            CaptureKind.MISSED_CALL -> "Missed call"
            CaptureKind.MESSAGE -> "Texted"
        }
        return "$what · $day"
    }

    fun summary(count: Int): String = when (count) {
        0 -> EMPTY_LINE
        1 -> "1 blocked number · rejected silently, any time"
        else -> "$count blocked numbers · rejected silently, any time"
    }
}

/** The block list on a replica. */
class BlockedCallers(
    private val replica: Replica,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun view(): BlockedCallersView {
        val now = nowMs()
        val rows = replica.entities(EntityTypes.BLOCKED_CALLER)
            .filter { it[BlockedCallerFields.BLOCKED].boolOrNull == true }
            .map { s ->
                val at = s[BlockedCallerFields.AT].longOrNull ?: 0L
                Triple(at, s.ref.entityId, s)
            }
            .sortedWith(compareByDescending<Triple<Long, String, *>> { it.first }.thenBy { it.second })
            .map { (at, key, s) ->
                val number = s[BlockedCallerFields.NUMBER].textOrNull ?: key.removePrefix("tel:")
                BlockedCallerRow(key, BlockedCallerRules.display(number), BlockedCallerRules.line(at, s[BlockedCallerFields.WHY].textOrNull, now, calendar))
            }
        val suspects = replica.entities(EntityTypes.BLOCKED_CALLER)
            .filter { it[BlockedCallerFields.SUSPECTED].boolOrNull == true && it[BlockedCallerFields.BLOCKED].boolOrNull != true }
            .map { s -> Triple(s[BlockedCallerFields.SUSPECTED_AT].longOrNull ?: 0L, s.ref.entityId, s) }
            .sortedWith(compareByDescending<Triple<Long, String, *>> { it.first }.thenBy { it.second })
            .map { (at, key, s) ->
                val number = s[BlockedCallerFields.NUMBER].textOrNull ?: key.removePrefix("tel:")
                SuspectedCallerRow(key, BlockedCallerRules.display(number), SuspectedSpamRules.line(at, s[BlockedCallerFields.SUSPECTED_WHY].textOrNull, now, calendar))
            }
        return BlockedCallersView(rows, BlockedCallerRules.summary(rows.size), suspects)
    }

    /**
     * Block on a Suspected spam row: the number goes on the block list with the AI's reason ("Suspected scam · Claims
     * to be the police"), which takes it off Suspected spam. False when [key] isn't a suspect.
     */
    fun confirmSuspect(key: String): Boolean {
        val s = replica.entity(EntityTypes.BLOCKED_CALLER, key) ?: return false
        if (s[BlockedCallerFields.SUSPECTED].boolOrNull != true || s[BlockedCallerFields.BLOCKED].boolOrNull == true) return false
        val number = s[BlockedCallerFields.NUMBER].textOrNull ?: key.removePrefix("tel:")
        return block(number, SuspectedSpamRules.blockWhy(s[BlockedCallerFields.SUSPECTED_WHY].textOrNull))
    }

    /**
     * Not spam on a Suspected spam row: off the list, its calls are screened as before, and the server never flags
     * this number again ([SuspectedSpamRules.shouldCheck]). False when [key] isn't a suspect.
     */
    fun dismissSuspect(key: String): Boolean {
        val s = replica.entity(EntityTypes.BLOCKED_CALLER, key) ?: return false
        if (s[BlockedCallerFields.SUSPECTED].boolOrNull != true) return false
        replica.commitLocal(EntityTypes.BLOCKED_CALLER, key, mapOf(BlockedCallerFields.SUSPECTED to false.fv()))
        return true
    }

    /** Blocks [number]; false (nothing saved) when it can't be a phone number. Blocking it again just updates why. */
    fun block(number: String, why: String?): Boolean {
        val key = BlockedCallerRules.keyOf(number) ?: return false
        replica.commitLocal(
            EntityTypes.BLOCKED_CALLER, key,
            buildMap {
                put(BlockedCallerFields.NUMBER, number.trim().take(40).fv())
                put(BlockedCallerFields.BLOCKED, true.fv())
                put(BlockedCallerFields.AT, nowMs().fv())
                why?.trim()?.takeIf { it.isNotEmpty() }?.let { put(BlockedCallerFields.WHY, it.take(BlockedCallerRules.MAX_WHY).fv()) }
            },
        )
        return true
    }

    /** Takes [key] off the list (calls from it ring again); false when it wasn't blocked. */
    fun unblock(key: String): Boolean {
        val s = replica.entity(EntityTypes.BLOCKED_CALLER, key) ?: return false
        if (s[BlockedCallerFields.BLOCKED].boolOrNull != true) return false
        replica.commitLocal(EntityTypes.BLOCKED_CALLER, key, mapOf(BlockedCallerFields.BLOCKED to false.fv()))
        return true
    }

    /**
     * Puts the 9 Oct scam number on the list the first time the Fold screens calls. Only when it has never been on
     * this replica's list, so once Meka unblocks it (on either device, synced) it is never added back.
     */
    fun seed(): Boolean {
        val key = BlockedCallerRules.keyOf(BlockedCallerRules.SEED_NUMBER) ?: return false
        if (replica.entity(EntityTypes.BLOCKED_CALLER, key) != null) return false
        return block(BlockedCallerRules.SEED_NUMBER, BlockedCallerRules.SEED_WHY)
    }
}

/**
 * Suspected spam (call assistant polish 8b c, Meka 2026-10-09: "this is the police, you owe…" → flagged scam,
 * auto-added for Meka to confirm). Once a voice message's words are in, MEKA's server asks the small model, with no
 * tools, whether they sound like a scam (ADR-006 addendum 2026-10-10; the words only, never the number); a yes puts the
 * number on Suspected spam ([BlockedCallerFields.SUSPECTED]). Nothing is blocked by the AI: the number's calls go to the
 * assistant instead of ringing (never for family, always-notify, contacts or anyone Meka called lately) until Meka taps
 * Block or Not spam. Non-AI and pure.
 */
object SuspectedSpamRules {
    const val TITLE = "Suspected spam"
    const val HINT = "MEKA's AI thought these callers' messages sounded like scams. Until you choose, their calls go to " +
        "the assistant instead of ringing; family, contacts and people you've called still ring."
    const val NOT_SPAM_LABEL = "Not spam"
    const val MAX_WHY = 80
    /** The voice message's words must be at least this long to be worth asking about. */
    const val MIN_WORDS = 3

    /**
     * Whether the server should ask about a new message from a number: a real number, not on the block list, never
     * flagged before (true: already a suspect) and never cleared with Not spam (false: Meka's answer stands).
     */
    fun shouldCheck(numberKey: String?, blocked: Boolean?, suspected: Boolean?, text: String?): Boolean =
        numberKey != null && blocked != true && suspected == null &&
            (text?.trim()?.split(Regex("\\s+"))?.count { it.isNotEmpty() } ?: 0) >= MIN_WORDS

    /** The AI's reason as shown: one line, no angle brackets or links, at most [MAX_WHY]; null when empty. */
    fun cleanWhy(why: String?): String? {
        val t = why?.replace(Regex("\\s+"), " ")?.replace(Regex("[<>]"), "")?.trim()?.trimEnd('.')?.take(MAX_WHY)?.trim()
        if (t.isNullOrEmpty() || t.contains("://") || t.contains("www.", ignoreCase = true)) return null
        return t
    }

    /** "Flagged today · Claims to be the police and asks for payment", "Flagged Fri 9 Oct". */
    fun line(atMs: Long, why: String?, nowMs: Long, cal: LocalCalendar): String {
        val label = SearchRules.dayLabel(cal.epochDayOf(atMs), cal.epochDayOf(nowMs))
        val day = if (label == "Today" || label == "Yesterday") label.lowercase() else label
        return "Flagged $day" + (cleanWhy(why)?.let { " · $it" } ?: "")
    }

    /** Why a confirmed suspect is on the block list: "Suspected scam · Claims to be the police". */
    fun blockWhy(why: String?): String = ("Suspected scam" + (cleanWhy(why)?.let { " · $it" } ?: "")).take(BlockedCallerRules.MAX_WHY)

    /** The fields the server writes on the number's `blocked_caller` entity when the AI flags it. */
    fun flagFields(number: String, why: String?, atMs: Long): Map<String, FieldValue> = buildMap {
        put(BlockedCallerFields.NUMBER, FieldValue.Text(number.trim().take(40)))
        put(BlockedCallerFields.SUSPECTED, FieldValue.Bool(true))
        put(BlockedCallerFields.SUSPECTED_AT, FieldValue.Int64(atMs))
        cleanWhy(why)?.let { put(BlockedCallerFields.SUSPECTED_WHY, FieldValue.Text(it)) }
    }

    /** The Activity entry for a flag: "Added 01904 618691 to Suspected spam" · the reason. */
    fun activity(number: String, why: String?, atMs: Long): Map<String, FieldValue> = linkedMapOf(
        ActivityFields.AT to FieldValue.Int64(atMs),
        ActivityFields.KIND to FieldValue.Text(ActivityKind.CALL.name),
        ActivityFields.SUMMARY to FieldValue.Text("Added ${BlockedCallerRules.display(number)} to Suspected spam".take(ActivityRules.MAX_LINE)),
        ActivityFields.DETAIL to FieldValue.Text((cleanWhy(why) ?: "Their message sounded like a scam").take(ActivityRules.MAX_LINE)),
        ActivityFields.WHY to FieldValue.Text("Call assistant · MEKA's AI read their message; you choose Block or Not spam"),
        ActivityFields.SOURCE to FieldValue.Text("calls"),
    )

    /** The entry id of that flag: one per voice message, however often it is settled. */
    fun activityId(heldId: String): String = "v" + ActivityRules.fnv64("suspected:$heldId")
}
