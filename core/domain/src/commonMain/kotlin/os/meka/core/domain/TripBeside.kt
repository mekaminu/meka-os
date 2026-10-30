package os.meka.core.domain

/**
 * Trip lists beside the approved mockup (docs/design/trip/trip-lists.dc.html; the Sat 31 Oct "screenshot checks against
 * every mockup", 2026-10-30): what the side-by-side render of CI's cover shot showed, shared by the Fold and the Mac so
 * the two can't drift.
 *
 * - Bought and Packed were loose text over bars; in the mockup each is a small card ("Bought" left, "1 of 9" right in
 *   the secondary grey, the bar under them). Money's layout rule (a): nothing floats as loose text.
 * - Obi's chips, Add all · Not now and the Everyone · Me · Kids filters were drawn 48 dp tall (the touch target painted
 *   in full); in the mockup they're slim pills. The pill is now drawn [PILL_DP] high inside the same 48 dp target.
 * - The add field had no button; the mockup's field ends on a gold Add inside it ([ADD]).
 * - Each group was a caps label over loose rows; in the mockup a group is a card: its name in title case, the count
 *   and the chevron on the head, a faint line between the rows. The Fold draws a group as several list items, so each
 *   item knows which piece of the card it is ([piece]): the head rounds the top, the last row the bottom.
 */
object TripBesideRules {
    /** A slim pill's drawn height in dp (inside its 48 dp touch target). */
    const val PILL_DP = 32

    /** Inside a group's card and the Bought / Packed cards, either side, in dp. */
    const val CARD_PAD_DP = 16

    /** The add field's button. */
    const val ADD = "Add"
    const val ADD_DESCRIPTION = "Add it to the list"

    /** Which piece of a group's card a list item is. */
    enum class Piece {
        /** The whole card: a folded or empty group's head. */
        ONLY,

        /** The head of an open group: rounded at the top. */
        FIRST,

        /** A row between: square, with a faint line on top. */
        MIDDLE,

        /** The last row: rounded at the bottom, a faint line on top. */
        LAST;

        val roundTop: Boolean get() = this == ONLY || this == FIRST
        val roundBottom: Boolean get() = this == ONLY || this == LAST
        val lineOnTop: Boolean get() = this == MIDDLE || this == LAST
    }

    /** The piece for the [index]th of [count] items making up one group's card. */
    fun piece(index: Int, count: Int): Piece = when {
        count <= 1 -> Piece.ONLY
        index <= 0 -> Piece.FIRST
        index >= count - 1 -> Piece.LAST
        else -> Piece.MIDDLE
    }

    /**
     * The items one group's card is made of, in order: its head, the group's menu when open under it, then (when the
     * group is open) each row by id, each followed by its sheet when that row's sheet is open ("sheet:<id>").
     */
    fun pieces(open: Boolean, rowIds: List<String>, menuOpen: Boolean, sheetFor: String?): List<String> = buildList {
        add(HEAD)
        if (menuOpen) add(MENU)
        if (open) for (id in rowIds) {
            add(id)
            if (sheetFor == id) add(SHEET + id)
        }
    }

    const val HEAD = "head"
    const val MENU = "menu"
    const val SHEET = "sheet:"

    /** The piece of the card [key] is, among [pieces]; ONLY when it isn't one of them. */
    fun pieceOf(key: String, pieces: List<String>): Piece {
        val at = pieces.indexOf(key)
        return if (at < 0) Piece.ONLY else piece(at, pieces.size)
    }
}
