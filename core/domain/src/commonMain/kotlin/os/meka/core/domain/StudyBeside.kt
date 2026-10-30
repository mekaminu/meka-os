package os.meka.core.domain

/**
 * Study beside its approved mockup (docs/design/study/study-plan.dc.html; the Sat 31 Oct "screenshot checks against every
 * mockup", 2026-10-30): what the side-by-side render of CI's cover shots showed, shared by the Fold and the Mac so the two
 * can't drift.
 *
 * - YOUR PLAN and THIS WEEK are cards with 16 dp inside, as the mockup (and Money's layout rules); the Fold drew 24.
 * - FINISH BY's three dates sat one under another on the closed Fold ("Mon 23 Nov · 4 wks" twice didn't fit a row of
 *   the 344 dp cover); the mockup puts two on a row. Where the card's inside is narrower than [NARROW_DP], a date chip
 *   drops its weekday ("23 Nov · 4 wks"), which two to a row fits; its spoken label keeps the whole date.
 * - LESSONS had the outline field always open at the top (layout rule (e): no open inputs on the page). As the mockup,
 *   the modules come first and a dashed "+ Add lessons · paste the course outline" row closes the list; a tap opens the
 *   field there (with From a screenshot and the how-to line). A course with no lessons yet opens on the field, and
 *   anything typed or read from a screenshot keeps it open.
 */
object StudyBesideRules {
    /** The card's inside in dp under which FINISH BY's chips drop their weekday. */
    const val NARROW_DP = 300

    /** Inside YOUR PLAN and THIS WEEK, in dp. */
    const val CARD_PAD_DP = 16

    /** The dashed row at the foot of LESSONS. */
    const val ADD_ROW = "+ Add lessons · paste the course outline"
    const val ADD_ROW_DESCRIPTION = "Add lessons: opens the field to paste the course outline"

    private val WEEKDAYS = setOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** "Mon 23 Nov · 4 wks" → "23 Nov · 4 wks" when [narrow]; anything not starting with a weekday stays as it is. */
    fun endChipLabel(label: String, narrow: Boolean): String {
        if (!narrow) return label
        val first = label.substringBefore(' ', "")
        return if (first in WEEKDAYS) label.substringAfter(' ') else label
    }

    /** Whether FINISH BY's chips drop their weekday for a card whose inside is [innerDp] wide. */
    fun narrow(innerDp: Float): Boolean = innerDp < NARROW_DP

    /**
     * Whether the outline field shows at the foot of LESSONS: always for a course with no lessons, after the dashed row
     * was tapped, and while anything is in the field or a screenshot's read is showing.
     */
    fun fieldOpen(lessons: Int, tapped: Boolean, typed: String, scanning: Boolean): Boolean =
        lessons == 0 || tapped || typed.isNotBlank() || scanning
}
