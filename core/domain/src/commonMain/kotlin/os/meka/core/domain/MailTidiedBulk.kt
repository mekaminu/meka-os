package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Tidied mail, slice 4 (Meka, 2026-11-18 23:34, part (3): "Bulk: Select, Select all on this page, then Trash · Put back;
 * and one 'Trash all 38 promos' style button per kind behind a confirm that says how many and that they go to Gmail's/
 * Outlook's Trash (recoverable there for 30 days), never a permanent delete"). The asks are the same per-email asks as
 * one tap ([MailTidiedAskRules.ask]), written once per tidy, so the server acts on them exactly as it does today and
 * Undo on the bar takes off the ones it hasn't acted on yet. An email that can't be asked that move now is left out and
 * counted out: "Trash 3" means three will go. Non-AI, pure, unit-tested.
 */

/** The bar while selecting: "3 selected", Select all, and the two moves with how many each will act on. */
data class ArchivedSelectBar(
    /** "3 selected" / "Tap emails to select them". */
    val line: String,
    /** "Select all" while some shown can still be picked; else "Clear". */
    val toggle: String,
    /** True when [toggle] selects every row shown; false when it clears the selection. */
    val toggleSelectsAll: Boolean,
    /** "Trash 3", or null when none of the selected can go to Trash. */
    val trash: String?,
    /** "Put back 2", or null when none can be put back. */
    val putBack: String?,
    val spoken: String,
)

/** "Trash all 38 promos": every email of a kind in the view that can still go to Trash, behind a confirm. */
data class ArchivedTrashAll(
    /** Row ids ("batchId|key"). */
    val ids: List<String>,
    /** "Trash all 38 promos". */
    val label: String,
    /** "Move 38 promos to Trash?" */
    val confirmTitle: String,
    /** "They go to Gmail's or Outlook's Trash, where they're kept for 30 days … Nothing is deleted for good." */
    val confirmBody: String,
    /** "Move 38 to Trash". */
    val confirmButton: String,
    val spoken: String,
)

object MailTidiedBulkRules {
    const val SELECT = "Select"
    const val DONE = "Done"
    const val SELECT_ALL = "Select all"
    const val CLEAR = "Clear"
    const val CANCEL = "Cancel"
    const val NONE_SELECTED = "Tap emails to select them"
    const val TRASH_BODY = "They go to Gmail's or Outlook's Trash, where they're kept for 30 days and can be put back from " +
        "there. Nothing is deleted for good, and their senders aren't blocked."

    private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    /** "batchId|key" → (batchId, key); null when it isn't one. */
    fun split(id: String): Pair<String, String>? {
        val i = id.indexOf('|')
        if (i <= 0 || i == id.length - 1) return null
        return id.substring(0, i) to id.substring(i + 1)
    }

    /** The rows a selection can hold: those with a move left (their [ArchivedEmailRow.swipe]). */
    fun selectable(rows: List<ArchivedAllRow>): List<ArchivedAllRow> = rows.filter { it.row.swipe?.any == true }

    /** The bar over [rows] (the rows shown) with [selected] row ids picked. */
    fun bar(rows: List<ArchivedAllRow>, selected: Set<String>): ArchivedSelectBar {
        val can = selectable(rows)
        val picked = can.filter { it.id in selected }
        val trash = picked.count { it.row.swipe?.left == MailTidiedAction.TRASH }
        val back = picked.count { it.row.swipe?.right == MailTidiedAction.PUT_BACK }
        val all = can.isNotEmpty() && picked.size < can.size
        val line = if (picked.isEmpty()) NONE_SELECTED else "${picked.size} selected"
        return ArchivedSelectBar(
            line = line,
            toggle = if (all || picked.isEmpty()) SELECT_ALL else CLEAR,
            toggleSelectsAll = all || picked.isEmpty(),
            trash = if (trash > 0) "Trash $trash" else null,
            putBack = if (back > 0) "Put back $back" else null,
            spoken = if (picked.isEmpty()) NONE_SELECTED else
                "${plural(picked.size, "email")} selected. " + listOfNotNull(
                    "$trash can go to Trash".takeIf { trash > 0 }, "$back can be put back".takeIf { back > 0 },
                ).joinToString(", "),
        )
    }

    /** The ids [toggle] picks: every selectable row shown, or none. */
    fun toggled(rows: List<ArchivedAllRow>, selected: Set<String>): Set<String> =
        if (bar(rows, selected).toggleSelectsAll) selectable(rows).map { it.id }.toSet() else emptySet()

    /**
     * The writes for asking [action] of every email in [ids], one per tidy (batch id → fields); emails that can't be
     * asked it now (gone, asked already, too old) are left out, and a tidy with none left gets no write.
     */
    fun ask(batches: List<MailTidyBatch>, ids: Collection<String>, action: MailTidiedAction, nowMs: Long): Map<String, Map<String, FieldValue>> {
        val byBatch = ids.mapNotNull { split(it) }.groupBy({ it.first }, { it.second })
        val out = LinkedHashMap<String, Map<String, FieldValue>>()
        for ((bid, keys) in byBatch) {
            val b = batches.firstOrNull { it.id == bid } ?: continue
            val adds = keys.distinct().mapNotNull { k ->
                val e = b.emails.firstOrNull { it.key == k } ?: return@mapNotNull null
                if (action in MailTidiedAskRules.available(b, e, nowMs)) MailTidiedAsk(k, action, nowMs) else null
            }
            if (adds.isNotEmpty()) out[bid] = mapOf(MailTidyBatchFields.EMAIL_ASKS to MailTidiedAskRules.encodeAsks(b.emailAsks + adds).fv())
        }
        return out
    }

    /** How many emails [ask]'s writes will act on. */
    fun asked(batches: List<MailTidyBatch>, ids: Collection<String>, action: MailTidiedAction, nowMs: Long): Int =
        ids.mapNotNull { split(it) }.distinct().count { (bid, k) ->
            val b = batches.firstOrNull { it.id == bid } ?: return@count false
            val e = b.emails.firstOrNull { it.key == k } ?: return@count false
            action in MailTidiedAskRules.available(b, e, nowMs)
        }

    /** Undo on the bar: the asks of [action] on [ids] the server hasn't acted on come off, one write per tidy. */
    fun unask(batches: List<MailTidyBatch>, ids: Collection<String>, action: MailTidiedAction): Map<String, Map<String, FieldValue>> {
        val byBatch = ids.mapNotNull { split(it) }.groupBy({ it.first }, { it.second })
        val out = LinkedHashMap<String, Map<String, FieldValue>>()
        for ((bid, keys) in byBatch) {
            val b = batches.firstOrNull { it.id == bid } ?: continue
            val answered = b.emailDone
            val off = b.emailAsks.filter { a ->
                a.action == action && a.key in keys &&
                    answered.none { it.key == a.key && it.action == a.action && it.atMs >= a.atMs } &&
                    // Only the latest ask of this move on each email (as one Undo would).
                    a == b.emailAsks.last { it.key == a.key && it.action == action }
            }
            if (off.isNotEmpty()) out[bid] = mapOf(MailTidyBatchFields.EMAIL_ASKS to MailTidiedAskRules.encodeAsks(b.emailAsks - off.toSet()).fv())
        }
        return out
    }

    /** What the bar says after a bulk move of [n] emails. */
    fun barLine(action: MailTidiedAction, n: Int, kind: TidiedKind? = null): String {
        val what = if (kind != null && kind != TidiedKind.ALL) plural(n, kind.one, kind.many) else plural(n, "email")
        return when (action) {
            MailTidiedAction.TRASH -> "Moving $what to Trash at Navilife's next check"
            MailTidiedAction.PUT_BACK -> "Putting $what back at Navilife's next check"
            else -> "${action.pending.removeSuffix("…")} · $what"
        }
    }

    /**
     * The view's "Trash all n" button: on a chosen kind other than all of today's (newsletters, promos, junk & scams),
     * every email of it in the view (the chosen sender's when one is) that can still go to Trash, not only the rows
     * shown; null when none can.
     */
    fun trashAll(kind: TidiedKind?, senderName: String?, ids: List<String>): ArchivedTrashAll? {
        if (kind == null || kind == TidiedKind.ALL || ids.isEmpty()) return null
        val n = ids.size
        val what = plural(n, kind.one, kind.many)
        val from = senderName?.let { " from $it" }.orEmpty()
        return ArchivedTrashAll(
            ids = ids,
            label = "Trash all $what$from",
            confirmTitle = "Move $what$from to Trash?",
            confirmBody = TRASH_BODY,
            confirmButton = "Move $n to Trash",
            spoken = "Trash all $what$from. Asks first",
        )
    }
}
