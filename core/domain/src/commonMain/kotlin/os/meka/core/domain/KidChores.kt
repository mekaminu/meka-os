package os.meka.core.domain

import os.meka.core.sync.DELETED_FIELD
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Kids' admin, slice 5: **chores and pocket money**, per child on the Kids pane on both apps (only if he wants them:
 * nothing shows until a chore or an amount is typed). A chore is one typed line ("Tidy room £1", "Feed the cat",
 * "Bins 50p"); each has a dot per day of this week (Mon–Sun) to tick on the day or catch up on an earlier day; a weekly
 * amount ("£5") is the pocket money. The week adds up ("£5 pocket money + £3 for chores = £8"), **Paid £8** marks it paid
 * (with Undo), and on Sunday what's still to pay lights the Kids tile.
 *
 * Stored (ADR-008 addendum 2026-10-15, slice 5, all additive and LWW): `kid_chore` per chore, id
 * `<the child's kid_admin id>~<title folded>` so both apps write the same chore ([KidChoreFields]); `kid_chore_tick`
 * per chore and day, id `<chore id>@<epoch day>`, field `done`; and three fields on the child's `kid_admin`
 * ([KidFields.POCKET], [KidFields.PAID_WEEK], [KidFields.PAID_PENCE]).
 *
 * Non-AI, pure rules ([KidChoreRules]) over a thin store ([KidChores]). Never moves money. Tests use invented children.
 */
object KidChoreFields {
    /** The child's `kid_admin` id ("c-alex"). */
    const val CHILD = "child"
    const val TITLE = "title"
    /** Int64 pence earned each time it's ticked; Null for a chore with no money on it. */
    const val REWARD = "rewardPence"
    const val DELETED = DELETED_FIELD
}

object KidChoreTickFields {
    /** Bool: ticked for that day (false once unticked). */
    const val DONE = "done"
}

/** A chore as stored. */
data class KidChore(val id: String, val child: String, val title: String, val rewardPence: Long?)

/** A typed chore read ([KidChoreRules.readChore]). */
data class KidChoreDraft(val title: String, val rewardPence: Long?)

/** One day's dot on a chore: [letter] "M", ticked ([done]), tappable ([canTick]: this week up to today), [today]. */
data class KidChoreDay(val day: Long, val letter: String, val done: Boolean, val canTick: Boolean, val today: Boolean, val spoken: String)

/** One chore on a child's card: [line] "£1 each · 3 this week", seven [days] Mon–Sun. */
data class KidChoreRow(val id: String, val title: String, val line: String, val days: List<KidChoreDay>, val spoken: String)

/**
 * A child's chores and pocket money this week. [shown] false when he hasn't set any (the card then offers only the two
 * fields). [pocketValue] "£5" for the field; [weekLine] "This week · £5 pocket money + £3 for chores = £8 · 4 chores
 * done"; [paidLine] "Paid £8 this week" / "Paid £5 · £3 more since"; [payLabel] "Paid £3" while some is still to pay;
 * [lit] on Sunday with some still to pay.
 */
data class KidMoney(
    val shown: Boolean,
    val pocketValue: String?,
    val chores: List<KidChoreRow>,
    val weekLine: String?,
    val paidLine: String?,
    val payLabel: String?,
    val toPayPence: Long,
    val lit: Boolean,
) {
    companion object {
        val NONE = KidMoney(false, null, emptyList(), null, null, null, 0, false)
    }
}

/** Non-AI, pure. */
object KidChoreRules {
    const val HEAD = "CHORES AND POCKET MONEY"
    const val CHORE_HINT = "Add a chore… (Tidy room £1, or just Feed the cat)"
    const val POCKET_HINT = "Pocket money a week… (£5)"
    const val POCKET_LABEL = "Pocket money"
    const val FOOT = "Tick a chore on the day (or catch up on an earlier one this week). Paid notes what you gave them; Navilife never moves money."
    const val NO_TITLE = "Say what the chore is: “Tidy room £1” or “Feed the cat”."
    const val BAD_REWARD = "Type money as £1, £1.50 or 50p, up to £20 a chore."
    const val BAD_POCKET = "Type the weekly amount as £5 or £2.50, up to £100 (empty it to stop)."
    const val TOO_MANY = "That's 12 chores already · remove one first."
    const val GONE = "That chore has changed since · it's no longer on the card."
    const val NOT_THIS_WEEK = "Only this week's days up to today can be ticked."
    const val NOTHING_TO_PAY = "Nothing to pay this week yet."
    const val NOTHING_TO_UNDO = "Nothing to undo"
    const val NO_POCKET = "No pocket money set"
    const val MAX_CHORES = 12
    const val MAX_TITLE = 40
    const val MAX_REWARD_PENCE = 2_000L
    const val MAX_POCKET_PENCE = 10_000L
    /** Pocket money day: Sunday (ISO 7). */
    const val PAY_DAY = 7

    private val LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
    private val I = setOf(RegexOption.IGNORE_CASE)
    private val poundsRe = Regex("""£\s?(\d{1,4}(?:\.\d{1,2})?)""", I)
    private val penceRe = Regex("""\b(\d{1,4})\s?p\b""", I)
    private val wordsRe = Regex("""\b(\d{1,4}(?:\.\d{1,2})?)\s?(?:pounds?|quid)\b""", I)
    private val connectors = Regex("""^(?:\s|,|;|:|-|–|—|·|\bfor\b|\beach\b|\ba\b)+|(?:\s|,|;|:|-|–|—|·|\bfor\b|\beach\b|\ba\b|\bper\b|\btime\b|\bgo\b)+$""", I)

    /** Monday of [today]'s week. */
    fun weekStart(today: Long): Long = today - (CivilDate.isoDayOfWeek(today) - 1)

    /** The chore id for a child's chore: "c-alex~tidy-room"; null when the title has no letters or digits. */
    fun choreId(childId: String, title: String): String? = KidsRules.idFor(title)?.let { "$childId~${it.removePrefix("c-")}" }

    fun tickId(choreId: String, day: Long): String = "$choreId@$day"

    /** Money in a line: "£1", "£1.50", "50p", "2 pounds"; the pence and the line without it, or null when none is said. */
    private fun money(s: String): Pair<Long, String>? {
        poundsRe.find(s)?.let { m -> return pence(m.groupValues[1]) to s.replaceRange(m.range, " ") }
        wordsRe.find(s)?.let { m -> return pence(m.groupValues[1]) to s.replaceRange(m.range, " ") }
        penceRe.find(s)?.let { m -> return m.groupValues[1].toLong() to s.replaceRange(m.range, " ") }
        return null
    }

    private fun pence(pounds: String): Long {
        val parts = pounds.split('.')
        val p = parts.getOrNull(1)?.padEnd(2, '0')?.toLong() ?: 0L
        return parts[0].toLong() * 100 + p
    }

    /** "Tidy room £1" → Tidy room, 100p; "Feed the cat" → no money. Or the line saying what's wrong. */
    fun readChore(typed: String): Pair<KidChoreDraft?, String?> {
        var s = " " + typed.trim().replace(Regex("""\s+"""), " ") + " "
        var reward: Long? = null
        money(s)?.let { (p, rest) -> reward = p; s = rest }
        if (reward == null && s.contains('£')) return null to BAD_REWARD
        reward?.let { if (it <= 0 || it > MAX_REWARD_PENCE) return null to BAD_REWARD }
        val title = s.replace(connectors, "").replace(Regex("""\s+"""), " ").trim().replaceFirstChar { it.uppercase() }
        if (title.isEmpty() || title.none { it.isLetterOrDigit() }) return null to NO_TITLE
        return KidChoreDraft(title.take(MAX_TITLE).trim(), reward) to null
    }

    /** A weekly amount: "£5", "5", "£2.50 a week", "5 pounds"; null for blank (stop); the refusal otherwise. */
    fun readPocket(typed: String): Pair<Long?, String?> {
        val t = typed.trim()
        if (t.isEmpty()) return null to null
        val s = " $t "
        val p = money(s)?.first ?: Regex("""^\s*(\d{1,4}(?:\.\d{1,2})?)\b""").find(s)?.let { pence(it.groupValues[1]) }
            ?: return null to BAD_POCKET
        if (p <= 0 || p > MAX_POCKET_PENCE) return null to BAD_POCKET
        return p to null
    }

    fun fmt(pence: Long): String = RenewalRules.formatPence(pence)

    /** "£1 each · 3 this week" · "3 this week" · "Not done yet this week". */
    fun choreLine(rewardPence: Long?, ticks: Int): String {
        val done = if (ticks == 0) "not done yet this week" else "$ticks this week"
        return if (rewardPence != null) "${fmt(rewardPence)} each · $done" else done.replaceFirstChar { it.uppercase() }
    }

    /**
     * A child's chores and pocket money this week, from what's stored: [chores] theirs, [ticked] the tick ids that are
     * done, [record] their kid_admin (pocket money and what was paid).
     */
    fun money(chores: List<KidChore>, ticked: Set<String>, record: KidRecord?, today: Long): KidMoney {
        val pocket = record?.pocketPence
        if (chores.isEmpty() && pocket == null) return KidMoney.NONE
        val start = weekStart(today)
        var earned = 0L
        var count = 0
        val rows = chores.sortedBy { it.title.lowercase() }.map { c ->
            val days = (0 until 7).map { i ->
                val d = start + i
                val done = tickId(c.id, d) in ticked
                val name = CivilDate.DAY_LONG[i]
                KidChoreDay(d, LETTERS[i], done, d <= today, d == today, "$name, ${if (done) "done" else if (d <= today) "not done" else "to come"}")
            }
            val n = days.count { it.done }
            count += n
            earned += (c.rewardPence ?: 0L) * n
            val line = choreLine(c.rewardPence, n)
            KidChoreRow(c.id, c.title, line, days, "${c.title}. $line")
        }
        val parts = listOfNotNull(
            pocket?.let { "${fmt(it)} pocket money" },
            earned.takeIf { it > 0 }?.let { "${fmt(it)} for chores" },
        )
        val total = (pocket ?: 0L) + earned
        val doneWord = if (count > 0) "$count ${if (count == 1) "chore" else "chores"} done" else null
        val weekLine = when {
            parts.isEmpty() && doneWord == null -> null
            parts.isEmpty() -> "This week · $doneWord"
            else -> "This week · " + parts.joinToString(" + ") + (if (parts.size > 1) " = ${fmt(total)}" else "") +
                (doneWord?.let { " · $it" } ?: "")
        }
        val paid = if (record?.paidWeek == start) record.paidPence ?: 0L else 0L
        val toPay = (total - paid).coerceAtLeast(0)
        val paidLine = when {
            paid <= 0 -> null
            toPay > 0 -> "Paid ${fmt(paid)} · ${fmt(toPay)} more since"
            else -> "Paid ${fmt(paid)} this week"
        }
        return KidMoney(
            shown = true,
            pocketValue = pocket?.let(::fmt),
            chores = rows,
            weekLine = weekLine,
            paidLine = paidLine,
            payLabel = if (toPay > 0) "Paid ${fmt(toPay)}" else null,
            toPayPence = toPay,
            lit = toPay > 0 && CivilDate.isoDayOfWeek(today) == PAY_DAY,
        )
    }

    fun addedLine(d: KidChoreDraft): String = "Added ${d.title}" + (d.rewardPence?.let { " · ${fmt(it)} each time" } ?: "")
    fun removedLine(title: String): String = "Removed $title · Undo puts it back"
    fun pocketLine(name: String, pence: Long?): String =
        if (pence == null) "Stopped $name's pocket money · Undo puts it back" else "$name's pocket money · ${fmt(pence)} a week"
    fun paidLine(name: String, pence: Long): String = "Marked ${fmt(pence)} paid to $name for this week"
    fun tickedLine(title: String, day: Long, today: Long, done: Boolean): String {
        val whenWord = if (day == today) "today" else CivilDate.DAY_LONG[CivilDate.isoDayOfWeek(day) - 1]
        return if (done) "$title · done $whenWord" else "$title · unticked $whenWord"
    }
}

/** What Undo puts back for a chore removal or a Paid. */
sealed class KidMoneyUndo {
    data class Chore(val id: String) : KidMoneyUndo()
    data class Pay(val childId: String, val week: Long?, val pence: Long?) : KidMoneyUndo()
    data class Pocket(val childId: String, val pence: Long?) : KidMoneyUndo()
}

/** Chores and pocket money on a replica (both apps). The facade checks the child is in About you. */
class KidChores(private val replica: Replica) {
    fun chores(): List<KidChore> = replica.entities(EntityTypes.KID_CHORE).mapNotNull { e ->
        val child = e[KidChoreFields.CHILD].textOrNull ?: return@mapNotNull null
        val title = e[KidChoreFields.TITLE].textOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        KidChore(e.ref.entityId, child, title, e[KidChoreFields.REWARD].longOrNull)
    }

    /** The done ticks of [chores] this week ([today]'s Monday to Sunday). */
    fun ticked(chores: List<KidChore>, today: Long): Set<String> {
        val start = KidChoreRules.weekStart(today)
        return chores.flatMap { c -> (0 until 7).map { KidChoreRules.tickId(c.id, start + it) } }
            .filter { replica.entity(EntityTypes.KID_CHORE_TICK, it)?.get(KidChoreTickFields.DONE)?.boolOrNull == true }
            .toSet()
    }

    fun money(childId: String, record: KidRecord?, today: Long): KidMoney {
        val mine = chores().filter { it.child == childId }
        return KidChoreRules.money(mine, ticked(mine, today), record, today)
    }

    fun addChore(childName: String, typed: String): KidSaved {
        val childId = KidsRules.idFor(childName) ?: return KidSaved(KidsRules.NOT_CHILD, refused = true)
        val (draft, why) = KidChoreRules.readChore(typed)
        if (draft == null) return KidSaved(why ?: KidChoreRules.NO_TITLE, refused = true)
        val id = KidChoreRules.choreId(childId, draft.title) ?: return KidSaved(KidChoreRules.NO_TITLE, refused = true)
        val existing = chores().filter { it.child == childId }
        if (existing.none { it.id == id } && existing.size >= KidChoreRules.MAX_CHORES) return KidSaved(KidChoreRules.TOO_MANY, refused = true)
        replica.commitLocal(EntityTypes.KID_CHORE, id, mapOf(
            KidChoreFields.CHILD to childId.fv(),
            KidChoreFields.TITLE to draft.title.fv(),
            KidChoreFields.REWARD to draft.rewardPence.fv(),
            KidChoreFields.DELETED to false.fv(),
        ))
        return KidSaved(KidChoreRules.addedLine(draft))
    }

    fun removeChore(id: String): Pair<KidSaved, KidMoneyUndo?> {
        val c = chores().firstOrNull { it.id == id } ?: return KidSaved(KidChoreRules.GONE, refused = true) to null
        replica.commitLocal(EntityTypes.KID_CHORE, id, mapOf(KidChoreFields.DELETED to true.fv()))
        return KidSaved(KidChoreRules.removedLine(c.title)) to KidMoneyUndo.Chore(id)
    }

    /** Ticks (or unticks) [id] on [day]: only this week's days up to today. */
    fun tick(id: String, day: Long, today: Long): KidSaved {
        val c = chores().firstOrNull { it.id == id } ?: return KidSaved(KidChoreRules.GONE, refused = true)
        if (day > today || day < KidChoreRules.weekStart(today)) return KidSaved(KidChoreRules.NOT_THIS_WEEK, refused = true)
        val tid = KidChoreRules.tickId(id, day)
        val done = replica.entity(EntityTypes.KID_CHORE_TICK, tid)?.get(KidChoreTickFields.DONE)?.boolOrNull != true
        replica.commitLocal(EntityTypes.KID_CHORE_TICK, tid, mapOf(KidChoreTickFields.DONE to done.fv()))
        return KidSaved(KidChoreRules.tickedLine(c.title, day, today, done))
    }

    fun record(childId: String): KidRecord? =
        replica.entity(EntityTypes.KID_ADMIN, childId)?.let { KidsRules.record(childId, it.fields) }

    fun setPocket(childName: String, typed: String): Pair<KidSaved, KidMoneyUndo?> {
        val childId = KidsRules.idFor(childName) ?: return KidSaved(KidsRules.NOT_CHILD, refused = true) to null
        val (pence, why) = KidChoreRules.readPocket(typed)
        if (why != null) return KidSaved(why, refused = true) to null
        val before = record(childId)?.pocketPence
        if (pence == null && before == null) return KidSaved(KidChoreRules.NO_POCKET) to null
        replica.commitLocal(EntityTypes.KID_ADMIN, childId, mapOf(KidFields.NAME to childName.trim().fv(), KidFields.POCKET to pence.fv()))
        return KidSaved(KidChoreRules.pocketLine(childName.trim(), pence)) to KidMoneyUndo.Pocket(childId, before)
    }

    /** Paid: this week's whole amount so far counts as given; Undo puts back what was noted before. */
    fun markPaid(childName: String, today: Long): Pair<KidSaved, KidMoneyUndo?> {
        val childId = KidsRules.idFor(childName) ?: return KidSaved(KidsRules.NOT_CHILD, refused = true) to null
        val r = record(childId)
        val m = money(childId, r, today)
        if (m.toPayPence <= 0) return KidSaved(KidChoreRules.NOTHING_TO_PAY, refused = true) to null
        val start = KidChoreRules.weekStart(today)
        val already = if (r?.paidWeek == start) r.paidPence ?: 0L else 0L
        replica.commitLocal(EntityTypes.KID_ADMIN, childId, mapOf(
            KidFields.NAME to childName.trim().fv(),
            KidFields.PAID_WEEK to start.fv(),
            KidFields.PAID_PENCE to (already + m.toPayPence).fv(),
        ))
        return KidSaved(KidChoreRules.paidLine(childName.trim(), m.toPayPence)) to KidMoneyUndo.Pay(childId, r?.paidWeek, r?.paidPence)
    }

    fun undo(u: KidMoneyUndo): Boolean = when (u) {
        is KidMoneyUndo.Chore -> {
            if (replica.entity(EntityTypes.KID_CHORE, u.id) == null) false
            else { replica.commitLocal(EntityTypes.KID_CHORE, u.id, mapOf(KidChoreFields.DELETED to false.fv())); true }
        }
        is KidMoneyUndo.Pay -> {
            replica.commitLocal(EntityTypes.KID_ADMIN, u.childId, mapOf(KidFields.PAID_WEEK to u.week.fv(), KidFields.PAID_PENCE to u.pence.fv())); true
        }
        is KidMoneyUndo.Pocket -> {
            replica.commitLocal(EntityTypes.KID_ADMIN, u.childId, mapOf(KidFields.POCKET to u.pence.fv())); true
        }
    }
}
