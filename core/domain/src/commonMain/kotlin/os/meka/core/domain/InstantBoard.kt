package os.meka.core.domain

/**
 * A tap on the Week board waiting for the core (taps feel instant, slice 13): the card ticked or un-ticked ([done]), or
 * moved to a day ([toDay], at [index] among its open cards) or to a week's lane ([toWeek], the week's Monday).
 */
data class BoardTap(
    val done: Boolean? = null,
    val toDay: Long? = null,
    val toWeek: Long? = null,
    val index: Int = Int.MAX_VALUE,
)

/**
 * Taps feel instant on the Week board (Meka 2026-10-25 01:45: "it should be instantaneous"). Done, Not done, a drop on a
 * day and a drop on a week's lane show in the next frame: the screen keeps the taps it has made (card id → [BoardTap])
 * and draws [shown] over the core's board while the write runs. The core's own board is published before its call
 * returns (local writes refresh at once), so the screen forgets a tap when the call returns ([forget]); if the call
 * failed or moved nothing, the card is back where the core has it and the bar says why ([InstantTapRules.failedLine]).
 *
 * Non-AI and pure, so the Fold and the Mac draw the same thing.
 */
object InstantBoardRules {
    /** [taps] with [id] ticked ([done] true) or un-ticked. A move waiting for the same card is kept. */
    fun done(taps: Map<String, BoardTap>, id: String, done: Boolean): Map<String, BoardTap> =
        taps + (id to (taps[id] ?: BoardTap()).copy(done = done))

    /** [taps] with [id] moved to [day] at [index] among its open cards (Int.MAX_VALUE: the end). */
    fun toDay(taps: Map<String, BoardTap>, id: String, day: Long, index: Int): Map<String, BoardTap> =
        taps + (id to (taps[id] ?: BoardTap()).copy(toDay = day, toWeek = null, index = index))

    /** [taps] with [id] parked in the lane of the week starting [weekStart], at [index]. */
    fun toWeek(taps: Map<String, BoardTap>, id: String, weekStart: Long, index: Int): Map<String, BoardTap> =
        taps + (id to (taps[id] ?: BoardTap()).copy(toDay = null, toWeek = weekStart, index = index))

    /** The call for [id] returned (done or failed): the core's board is the truth again. */
    fun forget(taps: Map<String, BoardTap>, id: String): Map<String, BoardTap> = if (id in taps) taps - id else taps

    /**
     * The board as the screen draws it: the core's [board] with [taps] applied. A tick only changes the card's done mark
     * (it keeps its place a moment, as the core settles it below the open ones after); a move takes the card out of its
     * day, lane or the Someday tray and puts it at its place among the target's open cards. A move to a day that isn't
     * on the board or can't take a card (before today), or to a week that isn't shown, is left to the core.
     */
    fun shown(board: WeekBoard, taps: Map<String, BoardTap>): WeekBoard {
        if (taps.isEmpty() || board.weeks.isEmpty()) return board
        var b = board
        for ((id, tap) in taps) {
            val spot = b.find(id) ?: continue
            var card = spot.card
            if (tap.done != null && tap.done != card.done) card = card.copy(done = tap.done)
            val day = tap.toDay?.let { b.day(it) }?.takeIf { it.canDrop && !card.done }
            val week = tap.toWeek?.let { b.week(it) }?.takeIf { !card.done }
            b = when {
                day != null && !(spot.source == BoardSource.DAY && spot.key == day.epochDay && sameOpenPlace(day, id, tap.index)) ->
                    place(remove(b, id)) { w -> w.copy(days = w.days.map { d -> if (d.epochDay == day.epochDay) insertOnDay(d, card, tap.index) else d }) }
                week != null && !(spot.source == BoardSource.WEEK && spot.key == week.startEpochDay) ->
                    place(remove(b, id)) { w -> if (w.startEpochDay == week.startEpochDay) w.copy(lane = insertAt(w.lane, card, tap.index)) else w }
                card !== spot.card -> replace(b, card)
                else -> b
            }
        }
        return b
    }

    private fun sameOpenPlace(day: BoardDay, id: String, index: Int): Boolean {
        val open = day.openCards
        val at = open.indexOfFirst { it.id == id }
        return at >= 0 && at == index.coerceIn(0, open.size - 1)
    }

    private fun insertAt(list: List<BoardCard>, card: BoardCard, index: Int): List<BoardCard> {
        val rest = list.filter { it.id != card.id }
        val at = index.coerceIn(0, rest.size)
        return rest.subList(0, at) + card + rest.subList(at, rest.size)
    }

    /** The open cards in his order with [card] at [index], then the day's done ones (as the core draws a day). */
    private fun insertOnDay(day: BoardDay, card: BoardCard, index: Int): BoardDay {
        val open = day.openCards.filter { it.id != card.id }
        val done = day.cards.filter { it.done && it.id != card.id }
        return day.copy(cards = insertAt(open, card, index) + done)
    }

    private fun remove(b: WeekBoard, id: String): WeekBoard = b.copy(
        weeks = b.weeks.map { w ->
            w.copy(days = w.days.map { d -> if (d.cards.any { it.id == id }) d.copy(cards = d.cards.filter { it.id != id }) else d }, lane = w.lane.filter { it.id != id })
        },
        someday = b.someday.filter { it.id != id },
    )

    private inline fun place(b: WeekBoard, week: (BoardWeek) -> BoardWeek): WeekBoard = b.copy(weeks = b.weeks.map(week))

    private fun replace(b: WeekBoard, card: BoardCard): WeekBoard = b.copy(
        weeks = b.weeks.map { w ->
            w.copy(
                days = w.days.map { d -> if (d.cards.any { it.id == card.id }) d.copy(cards = d.cards.map { if (it.id == card.id) card else it }) else d },
                lane = w.lane.map { if (it.id == card.id) card else it },
            )
        },
        someday = b.someday.map { if (it.id == card.id) card else it },
    )
}
