package os.meka.core.domain

/**
 * Money's layout rules on Trading (Meka 2026-10-26 22:18: "it's sentences and text, not segmented, boxed, grouped,
 * paginated… make it easy on the eye"; approved mockup `docs/design/money/`, the Trading tab): the old TRADING ·
 * HYPERLIQUID block becomes cards, each under a small caps label. The equity card (the total, what it is in the other
 * currency and when it was read, Dollars · Pounds, the P&L rows, a "Read-only" mark), then OPEN POSITIONS (five, then
 * "See all N ›"), the P&L line, YOUR TRADES, the month in review, YOUR ADDRESSES, ALERTS and EXPORT FOR TAX. Each
 * block's source line leaves the page: the tab ends on one "Read-only · not trading advice" line whose ⓘ says them all,
 * once ([info]). Both apps draw from here, so the Fold and the Mac can't drift.
 *
 * Layout rule (e) on Trading (2026-10-28): the address field leaves the page. YOUR ADDRESSES keeps each address he
 * follows (with Remove) and one [addRow] ("Add an address ›", or "Add your Hyperliquid address" before the first),
 * which opens a sheet with the field ([ADD_HINT]) and one line saying it is the public address only ([ADD_SHEET_LINE]).
 * Words only; nothing new is stored.
 */
object MoneyTradingLayout {
    /** The mark on the equity card (mockup): nothing on this tab can trade. */
    const val READ_ONLY = "Read-only"
    const val READ_ONLY_SPOKEN = "Read-only: nothing here can trade"
    const val POSITIONS_HEAD = "OPEN POSITIONS"
    const val ADDRESSES_HEAD = "YOUR ADDRESSES"
    /** Layout rule (c) on Trading: said once, at the foot. Trading isn't financial advice either; it says so in its own words. */
    const val FOOT_SHORT = "Read-only · not trading advice"
    /** Layout rule (e): YOUR ADDRESSES' one row that opens the add sheet, and the sheet's words. */
    const val ADD_TITLE = "Add an address"
    const val ADD_FIRST = "Add your Hyperliquid address"
    const val ADD_LINE = "Public address only · never a key"
    const val ADD_FIRST_LINE = "Public address only · never a key"
    const val ADD_SHEET = "Add an address"
    const val ADD_HINT = TradingTracker.HINT
    const val ADD_SHEET_LINE = "Paste the public 0x… address and a name for it. Navilife reads it from Hyperliquid's public data; never a key, and nothing here can trade."
    const val ADD_DONE = "Done"
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

    /**
     * The walk-through's A4 (2026-10-30): before the first address there's no figure to show, so the card says so in
     * one short line, pointing at YOUR ADDRESSES' add row (which says it's the public address only). Read-only is said
     * once, at the foot, not in this line too.
     */
    const val EMPTY_LINE = "No address yet · add yours below to see equity, P&L and open positions"

    /**
     * The one line the equity card shows before figures are read: [EMPTY_LINE] with no address, [waiting] while the
     * first read is due; null once the card carries figures. With no figures the card shows no Read-only mark (the
     * foot says it) and no Dollars · Pounds row (there's nothing to show in either; the ⓘ explains the currency once
     * figures are in, and a tap on "≈ £…" switches).
     */
    fun note(t: TradingView): String? = when {
        t.equity != null || t.total != null -> null
        t.empty != null -> EMPTY_LINE
        else -> waiting(t)
    }

    /**
     * The equity card as the mockup (README item 5): once an address is read the card carries its own label
     * ("Hyperliquid · Main 0x…"), so the TRADING · HYPERLIQUID label above it goes, and the P&L line sits inside it
     * rather than in a card of its own. Before the first read the label and the set-up card stay as they were.
     */
    fun showHead(t: TradingView): Boolean = t.equity == null

    /** Whether the separate P&L · LAST 30 DAYS card shows: only while the equity card can't carry the line. */
    fun showCurveCard(t: TradingView): Boolean = t.curve != null && t.equity == null

    /** Whether the positions card shows at all (with no address yet, or none read, it doesn't). */
    fun hasPositions(t: TradingView): Boolean = t.positions.isNotEmpty() || t.unrealisedLine != null

    /**
     * What the ⓘ at Trading's foot says: where the figures come from and in which currency, how the P&L line, the
     * trades and the month in review are added up (the first line already says nothing here can trade and it isn't trading advice). The lines each card used to carry, said once.
     */
    fun info(t: TradingView): List<String> = listOfNotNull(
        t.foot,
        // The equity card (mockup): the Dollars · Pounds row left the page, so what the currency means is said here.
        t.currency?.line?.takeIf { t.equity != null },
        t.curve?.foot?.takeIf { t.equity == null },
        t.trades?.foot,
        t.review?.foot,
    ).distinct()

    /** YOUR ADDRESSES' add row: "Add an address" under the ones he follows, "Add your Hyperliquid address" before the first. */
    fun addRow(t: TradingView): TradingAddRow =
        if (t.wallets.isEmpty()) TradingAddRow(ADD_FIRST, ADD_FIRST_LINE, first = true, spoken = "$ADD_FIRST. $ADD_FIRST_LINE. Opens a sheet")
        else TradingAddRow(ADD_TITLE, ADD_LINE, first = false, spoken = "$ADD_TITLE. $ADD_LINE. Opens a sheet")
}

/** YOUR ADDRESSES' one row that opens the add sheet: [title] over [line]; [first] before any address is followed. */
data class TradingAddRow(val title: String, val line: String, val first: Boolean, val spoken: String)
