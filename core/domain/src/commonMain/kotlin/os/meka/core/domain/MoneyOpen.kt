package os.meka.core.domain

/**
 * Speed (Meka 2026-10-25 01:53: "fingerprint accepted, the locked page stays"): what the Money screen shows the moment
 * the fingerprint (Touch ID) says yes, before the core has built the fresh view. The last view he saw this session
 * shows at once with [UPDATING] under the title; with none yet, [opening] (the title and [OPENING], nothing to tap).
 * A fingerprint that says no, or an open that fails, says so on the page instead of leaving it locked and silent.
 */
object MoneyOpenRules {
    const val OPENING = "Opening your money…"
    const val UPDATING = "Showing what you saw last · bringing it up to date…"
    const val REFUSED = "Didn't open · your fingerprint wasn't accepted. Open Money to try again."
    const val REFUSED_MAC = "Didn't open · Touch ID wasn't accepted. Open Money to try again."
    const val FAILED = "Couldn't open Money just now · Open Money to try again."

    /** The view to show in the same frame as the fingerprint's yes: [last] marked as being brought up to date, or [opening]. */
    fun instant(last: MoneyView?): MoneyView =
        last?.takeIf { !it.locked }?.copy(opening = UPDATING, skeleton = false) ?: opening()

    /** Before the first view of this session: the title and "Opening your money…", nothing else (no Import, no Delete). */
    fun opening(): MoneyView = MoneyView(false, OPENING, null, null, emptyList(), earlier = false, later = false, opening = OPENING, skeleton = true)

    /** Locked again, saying why: the fingerprint said no ([mac]: Touch ID's words). */
    fun refused(mac: Boolean = false): MoneyView = MoneyView.LOCKED.copy(summary = if (mac) REFUSED_MAC else REFUSED)

    /** Locked again after the open itself failed. */
    fun failed(): MoneyView = MoneyView.LOCKED.copy(summary = FAILED)

    /** Whether a built view is worth keeping as "the last view": this month, no what-ifs, opened (not a skeleton). */
    fun keep(view: MoneyView, offset: Int, whatIf: MoneyWhatIf): Boolean = !view.locked && !view.skeleton && offset == 0 && !whatIf.any
}
