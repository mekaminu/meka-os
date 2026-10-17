package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Money manager, slice 14 (Meka approved 2026-10-12 21:22), part 1: **shared costs with his partner and who paid what**.
 * Facts on his own numbers only: Navilife never moves money, never asks anyone to pay and sends nothing.
 *
 * What counts as shared (his choice, synced; ADR-008 addendum 2026-10-17, additive, no migration):
 * - one payment he marks **Shared** (`money_txn.shared` true; false keeps one out of an always-shared payer);
 * - a payer he marks **Always shared** (`money_rule.shared`, on the merchant rule entity his categories use): every
 *   payment to them, on any account (the weekly shop, the energy bill).
 * Income and transfers never count.
 *
 * What his partner paid isn't in his statements, so he types it ("Tesco £42.50"): a new synced entity `money_shared`
 * ([MoneySharedFields]), one per payment, on the day typed. One `context_mode` entity [ENTITY_ID] holds his share of the
 * shared costs ([SHARE_CHOICES], half unless changed) and when they last **settled up**: from then on only what was paid
 * after counts. The running balance says who owes whom, never as a demand. The partner's first name comes from About you
 * (else "your partner"). Her own view on the family page waits on the family-calendar choice (Needs Meka #18).
 */
object MoneySharedFields {
    /** On `money_txn`: true when this payment is shared, false to keep it out; null follows the payer. */
    const val TXN = "shared"
    /** On `money_rule`: true when every payment to this payer is shared; null otherwise. */
    const val MERCHANT = "shared"
    /** On `money_shared` (what his partner paid). */
    const val NAME = "name"
    const val PENCE = "pence"
    const val DAY = "day"
    const val ADDED_AT = "addedAtMs"
    /** On the `context_mode` entity [MoneyShared.ENTITY_ID]. */
    const val MY_PCT = "myPct"
    const val SETTLED_DAY = "settledDay"
    const val SETTLED_AT = "settledAtMs"
    const val CHANGED_AT = "changedAtMs"
}

/** One statement line as the shared costs see it. */
data class MoneySharedLine(
    val txnId: String, val day: Long, val pence: Long, val merchantKey: String, val category: MoneyCategory,
)

/** One payment his partner made that he typed: [pence] positive, on [day], added at [addedAtMs]. */
data class MoneySharedEntry(val id: String, val name: String, val pence: Long, val day: Long, val addedAtMs: Long)

/** His share ([myPct]) and the last settle-up (the day and the moment), from the synced setting. */
data class MoneySharedSetting(val myPct: Int = MoneyShared.DEFAULT_PCT, val settledDay: Long? = null, val settledAtMs: Long? = null)

/** One row of what his partner paid: "Tesco" · "£42.50" · "Sat 17 Oct". */
data class MoneySharedRow(val id: String, val name: String, val amount: String, val line: String, val spoken: String)

/**
 * SHARED WITH <PARTNER> on the Money screen. [intro] (and no figures) until something is shared or typed; then since
 * when, what each paid, the split, who owes whom ([balanceLine], lit when someone owes), the share chips, this month's
 * shared total, what the partner paid (with Remove), the typing [hint] and Settled up ([settle], only when not square).
 */
data class MoneySharedView(
    val head: String,
    val partner: String,
    val intro: String?,
    val sinceLine: String?,
    val youLine: String?,
    val partnerLine: String?,
    val splitLine: String?,
    val balanceLine: String?,
    val balanceLit: Boolean,
    val chips: List<MoneyBusinessChip>,
    val monthLine: String?,
    val rows: List<MoneySharedRow>,
    /** "Always shared: Tesco, Octopus Energy", or null. */
    val merchantsLine: String?,
    val hint: String,
    val settle: String?,
    val foot: String,
    val spoken: String,
) {
    /** Index access for Swift. */
    fun chipAt(i: Int): MoneyBusinessChip = chips[i]
    fun rowAt(i: Int): MoneySharedRow = rows[i]
}

object MoneyShared {
    const val ENTITY_ID = "money_shared"
    const val DEFAULT_PCT = 50
    val SHARE_CHOICES = listOf(40, 50, 60, 70)
    const val MENU_ON = "Shared ✓"
    const val MENU_OFF = "Shared"
    const val MENU_ALWAYS_ON = "Always shared ✓"
    const val MENU_ALWAYS_OFF = "Always shared"
    const val SETTLE = "Settled up"
    const val MAX_ENTRIES = 200
    const val SHOWN_ROWS = 12
    const val FOOT = "Your own numbers: Navilife never moves money or asks anyone to pay. Settled up starts the count again from today."

    fun partnerName(raw: String?): String = raw?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.any(Char::isLetter) } ?: "your partner"

    private fun cap(name: String) = name.replaceFirstChar { it.titlecase() }

    fun head(partner: String): String = if (partner == "your partner") "SHARED COSTS" else "SHARED WITH ${partner.uppercase()}"

    fun intro(partner: String): String =
        "Tap a payment and choose Shared (just that one) or Always shared (every payment there, like the weekly shop), and type what ${partner} paid below: Navilife keeps a running who-paid-what."

    fun hint(partner: String): String = "What ${partner} paid, e.g. Tesco £42.50"

    const val BAD = "Type what it was and the amount, e.g. Tesco £42.50."

    fun setting(fields: Map<String, FieldValue>?): MoneySharedSetting = MoneySharedSetting(
        myPct = fields?.get(MoneySharedFields.MY_PCT)?.longOrNull?.toInt()?.takeIf { it in SHARE_CHOICES } ?: DEFAULT_PCT,
        settledDay = fields?.get(MoneySharedFields.SETTLED_DAY)?.longOrNull,
        settledAtMs = fields?.get(MoneySharedFields.SETTLED_AT)?.longOrNull,
    )

    fun shareFields(pct: Int, nowMs: Long): Map<String, FieldValue> =
        mapOf(MoneySharedFields.MY_PCT to pct.toLong().fv(), MoneySharedFields.CHANGED_AT to nowMs.fv())

    fun settleFields(day: Long?, atMs: Long?, nowMs: Long): Map<String, FieldValue> = mapOf(
        MoneySharedFields.SETTLED_DAY to (day?.fv() ?: FieldValue.Null),
        MoneySharedFields.SETTLED_AT to (atMs?.fv() ?: FieldValue.Null),
        MoneySharedFields.CHANGED_AT to nowMs.fv(),
    )

    /** "50" → 50 when it's a choice; else null. */
    fun parseChoice(key: String): Int? = key.trim().removeSuffix("%").toIntOrNull()?.takeIf { it in SHARE_CHOICES }

    /** A typed payment ("Tesco £42.50", "£18 cinema"): what it was and its amount; null without both. */
    fun parse(text: String): Pair<String, Long>? {
        val p = MoneyWorth.parse(text) ?: return null
        return p.name to p.pence
    }

    fun entryId(name: String, day: Long, nowMs: Long): String = "s" + ActivityRules.fnv64("moneyshared:${name.lowercase()}:$day:$nowMs")

    fun shareSaid(pct: Int): String = "Your share is $pct% · ${100 - pct}% theirs · on both devices"
    fun txnSaid(merchant: String, on: Boolean): String =
        if (on) "$merchant on this day is shared · counted in who paid what" else "That $merchant payment isn't shared any more"
    fun merchantSaid(merchant: String, on: Boolean): String =
        if (on) "Every $merchant payment is shared from now on · on every account" else "$merchant payments aren't shared any more"
    fun addedSaid(partner: String, name: String, pence: Long): String = "${cap(partner)} paid ${MoneyRules.fmt(pence)} for $name · counted in who paid what"
    fun removedSaid(name: String): String = "Removed $name from what was paid"
    fun settledSaid(partner: String): String = "Settled up with $partner · counting again from today"

    /** Whether a statement line is shared: its own mark first, then the payer's; income and transfers never. */
    fun counts(l: MoneySharedLine, txns: Map<String, Boolean>, merchants: Map<String, Boolean>): Boolean {
        if (l.category == MoneyCategory.TRANSFERS || l.category == MoneyCategory.INCOME) return false
        txns[l.txnId]?.let { return it }
        return merchants[l.merchantKey] == true
    }

    private fun fmt(p: Long) = MoneyRules.fmt(p)
    private fun n(k: Int, one: String) = "$k $one${if (k == 1) "" else "s"}"

    fun view(
        hasAccounts: Boolean, lines: List<MoneySharedLine>, txns: Map<String, Boolean>, merchants: Map<String, Boolean>,
        entries: List<MoneySharedEntry>, setting: MoneySharedSetting, partnerRaw: String?, today: Long,
    ): MoneySharedView? {
        if (!hasAccounts && entries.isEmpty()) return null
        val partner = partnerName(partnerRaw)
        val head = head(partner)
        val chips = SHARE_CHOICES.map { MoneyBusinessChip(it.toString(), "$it%", it == setting.myPct) }
        val always = merchants.filterValues { it }.keys
        val merchantsLine = always.takeIf { it.isNotEmpty() }?.sorted()?.let { ks ->
            "Always shared: " + ks.take(4).joinToString(", ") { MoneyCategoryRules.merchantLabel(it) } + (if (ks.size > 4) " and ${ks.size - 4} more" else "")
        }
        val shared = lines.filter { counts(it, txns, merchants) }
        if (shared.isEmpty() && entries.isEmpty()) {
            val intro = intro(partner)
            return MoneySharedView(head, partner, intro, null, null, null, null, null, false, chips, null, emptyList(),
                merchantsLine, hint(partner), null, FOOT, "Shared costs. $intro")
        }
        val sd = setting.settledDay
        val mine = if (sd == null) shared else shared.filter { it.day > sd }
        val theirs = entries.filter { e -> sd == null || e.day > sd || (setting.settledAtMs != null && e.addedAtMs > setting.settledAtMs) }
        val you = -mine.sumOf { it.pence }
        val them = theirs.sumOf { it.pence }
        val total = you + them
        val yourShare = (total * setting.myPct + 50) / 100
        val diff = you - yourShare
        val sinceDay = sd ?: (mine.map { it.day } + theirs.map { it.day }).minOrNull()
        val sinceLine = when {
            sd != null -> "Since you settled up · ${CivilDate.shortLabel(sd)}"
            sinceDay != null -> "Since ${CivilDate.shortLabel(sinceDay)}"
            else -> null
        }
        val youLine = "You paid ${fmt(you.coerceAtLeast(0))} · ${n(mine.count { it.pence < 0 }, "payment")}"
        val partnerLine = "${cap(partner)} paid ${fmt(them)} · ${n(theirs.size, "payment")}"
        val splitLine = if (setting.myPct == 50) "Split half each · ${fmt(yourShare.coerceAtLeast(0))} each"
            else "Split ${setting.myPct}/${100 - setting.myPct} · your share ${fmt(yourShare.coerceAtLeast(0))}"
        val balanceLine = when {
            kotlin.math.abs(diff) < 100 -> "All square"
            diff > 0 -> "${cap(partner)} owes you ${fmt(diff)}"
            else -> "You owe $partner ${fmt(-diff)}"
        }
        val square = kotlin.math.abs(diff) < 100
        val month = MoneyRules.monthOf(today)
        val mMine = -shared.filter { MoneyRules.monthOf(it.day) == month }.sumOf { it.pence }
        val mThem = entries.filter { MoneyRules.monthOf(it.day) == month }.sumOf { it.pence }
        val monthLine = if (mMine + mThem <= 0L) null else
            "${CivilDate.MONTH_LONG[month % 12]} so far · ${fmt(mMine + mThem)} shared · ${fmt(mMine.coerceAtLeast(0))} by you, ${fmt(mThem)} by $partner"
        val rows = theirs.sortedWith(compareByDescending<MoneySharedEntry> { it.day }.thenByDescending { it.addedAtMs }).take(SHOWN_ROWS).map { e ->
            MoneySharedRow(e.id, e.name, fmt(e.pence), CivilDate.shortLabel(e.day), "${cap(partner)} paid ${fmt(e.pence)} for ${e.name}, ${CivilDate.shortLabel(e.day)}")
        }
        val spoken = listOfNotNull("Shared costs", sinceLine, youLine, partnerLine, splitLine, balanceLine).joinToString(". ")
        return MoneySharedView(head, partner, null, sinceLine, youLine, partnerLine, splitLine, balanceLine, !square, chips,
            monthLine, rows, merchantsLine, hint(partner), if (square) null else SETTLE, FOOT, spoken)
    }
}
