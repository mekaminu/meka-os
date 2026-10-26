package os.meka.core.domain

enum class PaymentTapKind { CATEGORY, SHARED, SHARED_ALWAYS, BUSINESS }

/** One payment chip on its way ([InstantPaymentRules]): [txnId]'s [kind] set [on] (for CATEGORY, [category] is the key). */
data class PaymentTap(val txnId: String, val kind: PaymentTapKind, val on: Boolean = true, val category: String? = null)

/**
 * Taps feel instant for Money's payment chips (Meka 2026-10-25 01:45: "it should be instantaneous"; the instant-taps
 * item, (2)). A payment's category, Shared, Always shared and Business only changed once the core's call came back
 * from its one thread (and Money's view was rebuilt over every payment). Now the apps keep the taps on their way and
 * draw [shown] over the core's [MoneyView] in the same frame. A tap is forgotten when its call returns ([forget]; the
 * core publishes its own view before it returns), so a refused or failed call puts the payment back as the core has it.
 *
 * Only the rows are drawn: a category and Always shared / Business go on every row to the same payer (they're the
 * payer's marks), Shared on the one payment. The category totals, the shared balance and the side hustle's figures are
 * left to the core, which settles them a moment later. Non-AI and pure, so the Fold and the Mac draw the same.
 */
object InstantPaymentRules {
    fun categoryTap(txnId: String, key: String) = PaymentTap(txnId, PaymentTapKind.CATEGORY, category = key)
    fun sharedTap(txnId: String, on: Boolean) = PaymentTap(txnId, PaymentTapKind.SHARED, on)
    fun alwaysTap(txnId: String, on: Boolean) = PaymentTap(txnId, PaymentTapKind.SHARED_ALWAYS, on)
    fun businessTap(txnId: String, on: Boolean) = PaymentTap(txnId, PaymentTapKind.BUSINESS, on)

    /** [taps] with [tap] added; a newer tap of the same kind on the same payment replaces the older one. */
    fun add(taps: List<PaymentTap>, tap: PaymentTap): List<PaymentTap> = taps.filterNot { it.txnId == tap.txnId && it.kind == tap.kind } + tap

    /** [tap]'s call returned (done, refused or failed). A newer tap of the same kind is kept until its own call returns. */
    fun forget(taps: List<PaymentTap>, tap: PaymentTap): List<PaymentTap> = taps.filterNot { it == tap }

    /** The bar's line when [tap]'s call failed and the payment went back. */
    fun failedLine(tap: PaymentTap, reason: String?): String = InstantTapRules.failedLine(
        when (tap.kind) {
            PaymentTapKind.CATEGORY -> "The category"
            PaymentTapKind.SHARED -> "Shared"
            PaymentTapKind.SHARED_ALWAYS -> "Always shared"
            PaymentTapKind.BUSINESS -> "Business"
        },
        reason,
    )

    /** Money as the screen draws it: the core's [view] with [taps] applied to its payments. */
    fun shown(view: MoneyView, taps: List<PaymentTap>): MoneyView {
        val month = view.month ?: return view
        if (taps.isEmpty() || month.txns.isEmpty()) return view
        var txns = month.txns
        for (tap in taps) {
            val payer = txns.firstOrNull { it.id == tap.txnId }?.merchant ?: continue
            txns = txns.map { t -> if (t.id == tap.txnId || (tap.kind != PaymentTapKind.SHARED && t.merchant == payer)) apply(t, tap) else t }
        }
        if (txns == month.txns) return view
        // Money redesign (B2): the latest payments by day show the same tap at once.
        val byId = txns.associateBy { it.id }
        val spending = month.spending?.let { sp ->
            sp.copy(latest = sp.latest.map { d -> d.copy(rows = d.rows.map { r -> byId[r.id] ?: r }) })
        }
        return view.copy(month = month.copy(txns = txns, spending = spending))
    }

    private fun apply(t: MoneyTxnRow, tap: PaymentTap): MoneyTxnRow = when (tap.kind) {
        PaymentTapKind.CATEGORY -> {
            val key = tap.category
            val label = MoneyRules.CHOICES.firstOrNull { it.key == key }?.label
            if (key == null || label == null || key == t.category) t
            else t.copy(category = key, categoryLabel = label, taught = true)
        }
        PaymentTapKind.SHARED -> if (t.shared == tap.on) t else t.copy(shared = tap.on)
        PaymentTapKind.SHARED_ALWAYS -> if (t.sharedAlways == tap.on && t.shared == tap.on) t else t.copy(sharedAlways = tap.on, shared = tap.on)
        PaymentTapKind.BUSINESS -> if (t.business == tap.on) t else t.copy(business = tap.on)
    }
}
