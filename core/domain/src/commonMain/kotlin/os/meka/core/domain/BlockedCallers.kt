package os.meka.core.domain

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
}

/** One row of Work mode's Blocked numbers. */
data class BlockedCallerRow(val key: String, val number: String, val line: String)

data class BlockedCallersView(val rows: List<BlockedCallerRow>, val line: String) {
    val keys: Set<String> get() = rows.map { it.key }.toSet()

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
        return BlockedCallersView(rows, BlockedCallerRules.summary(rows.size))
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
