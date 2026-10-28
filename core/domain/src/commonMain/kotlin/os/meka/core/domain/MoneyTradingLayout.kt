package os.meka.core.domain

/**
 * Money's layout rules on Trading (Meka 2026-10-26 22:18: "it's sentences and text, not segmented, boxed, grouped,
 * paginated… make it easy on the eye"; approved mockup `docs/design/money/`, the Trading tab): the old TRADING ·
 * HYPERLIQUID block becomes cards, each under a small caps label. The equity card (the total, what it is in the other
 * currency and when it was read, Dollars · Pounds, the P&L rows, a "Read-only" mark), then OPEN POSITIONS (five, then
 * "See all N ›"), the P&L line, YOUR TRADES, the month in review, YOUR ADDRESSES, ALERTS and EXPORT FOR TAX. Each
 * block's source line leaves the page: the tab ends on one "Read-only · not trading advice" line whose ⓘ says them all,
 * once ([info]). Both apps draw from here, so the Fold and the Mac can't drift.
 */
object MoneyTradingLayout {
    /** The mark on the equity card (mockup): nothing on this tab can trade. */
    const val READ_ONLY = "Read-only"
    const val READ_ONLY_SPOKEN = "Read-only: nothing here can trade"
    const val POSITIONS_HEAD = "OPEN POSITIONS"
    const val ADDRESSES_HEAD = "YOUR ADDRESSES"
    /** Layout rule (c) on Trading: said once, at the foot. Trading isn't financial advice either; it says so in its own words. */
    const val FOOT_SHORT = "Read-only · not trading advice"
    /** Layout rule (d): open positions show this many, then "See all N ›". */
    const val SHOWN = 5

    /** The positions the card shows: nearest liquidation first (as the view orders them), five unless [all]. */
    fun positions(t: TradingView, all: Boolean): List<TradingPositionRow> = if (all) t.positions else t.positions.take(SHOWN)

    /** "See all 7 ›" under the fifth position, "Show fewer" once open; null with five or fewer. */
    fun more(t: TradingView, all: Boolean): String? = when {
        t.positions.size <= SHOWN -> null
        all -> "Show fewer"
        else -> "See all ${t.positions.size} ›"
    }

    /** What the equity card says while no address has been read yet (so the card is never empty); null otherwise. */
    fun waiting(t: TradingView): String? = TradingTracker.WAITING.takeIf { t.total == null && t.empty == null }

    /** Whether the positions card shows at all (with no address yet, or none read, it doesn't). */
    fun hasPositions(t: TradingView): Boolean = t.positions.isNotEmpty() || t.unrealisedLine != null

    /**
     * What the ⓘ at Trading's foot says: where the figures come from and in which currency, how the P&L line, the
     * trades and the month in review are added up (the first line already says nothing here can trade and it isn't trading advice). The lines each card used to carry, said once.
     */
    fun info(t: TradingView): List<String> = listOfNotNull(
        t.foot,
        t.curve?.foot,
        t.trades?.foot,
        t.review?.foot,
    ).distinct()
}
