package os.meka.core.domain

/** One of the countdowns after the big one on the widget: "Holiday" · "93 days". */
data class WidgetCountdownLine(val name: String, val count: String)

/**
 * The Countdowns home-screen widget (Countdowns, slice 4; `docs/design/countdowns/Countdowns.dc.html`: "Now on Today
 * and the home-screen widget"): the one chosen with Show on Today, else the soonest, with its ring and number, then the
 * next few as short lines.
 */
data class WidgetCountdown(
    /** "Christmas" */
    val name: String,
    /** "43", or "Today" on the day. */
    val number: String,
    /** "days" / "sleeps"; "" on the day. */
    val unit: String,
    /** "Fri 25 Dec" */
    val dateLine: String,
    /** "turns 9", "4 nights", "breaks up Fri 18 Dec"; null when there's nothing to add. */
    val extra: String?,
    /** 0..100: how much of the ring is drawn (fuller as it gets closer). */
    val ringPercent: Int,
    /** On the day: the number lit. */
    val lit: Boolean,
    /** The next ones, soonest first, at most [CountdownWidgetRules.NEXT]. */
    val next: List<WidgetCountdownLine>,
    /** The whole widget for a screen reader. */
    val spoken: String,
    /** Nothing to count down to (Christmas hidden and nothing else): the widget asks for one. */
    val empty: Boolean,
)

/** Non-AI and pure: the Countdowns screen cut to what fits a widget. A tap anywhere opens Countdowns. */
object CountdownWidgetRules {
    const val NEXT = 2
    const val EMPTY = "No countdowns yet"
    const val EMPTY_HINT = "Tap to add a holiday or a birthday"
    /** The Mac's desktop widget is clicked, not tapped. */
    const val DESK_EMPTY_HINT = "Click to add a holiday or a birthday"
    const val THEN = "Then"

    fun emptyHint(desk: Boolean): String = if (desk) DESK_EMPTY_HINT else EMPTY_HINT

    /** "today", "tomorrow" (or "1 sleep" with Sleeps on), "93 days" / "93 sleeps". */
    fun count(r: CountdownRow, sleeps: Boolean): String = when {
        r.days == 0L -> "today"
        r.days == 1L && !sleeps -> "tomorrow"
        else -> "${r.days} ${CountdownRules.unitWord(r.days, sleeps)}"
    }

    fun view(v: CountdownsView, next: Int = NEXT): WidgetCountdown {
        val h = v.hero ?: return WidgetCountdown(
            name = CountdownRules.TITLE, number = "", unit = "", dateLine = "", extra = null, ringPercent = 0, lit = false,
            next = emptyList(), spoken = "${CountdownRules.TITLE}. $EMPTY. ${CountdownRules.OPENS}", empty = true,
        )
        val lines = v.rows.take(next.coerceAtLeast(0)).map { WidgetCountdownLine(it.name, count(it, v.sleeps)) }
        val headline = if (h.days == 0L) "${h.name}, today" else "${h.name}, ${count(h, v.sleeps)} to go, ${h.dateLine}"
        val spoken = (
            listOf("Countdown", headline + (h.extra?.let { ", $it" } ?: "")) +
                lines.map { "$THEN ${it.name}, ${it.count}" } + CountdownRules.OPENS.removeSuffix(".")
            ).joinToString(". ") + "."
        return WidgetCountdown(
            name = h.name, number = h.number, unit = h.unit, dateLine = h.dateLine, extra = h.extra,
            ringPercent = (h.fill * 100).toInt().coerceIn(0, 100), lit = h.days == 0L, next = lines, spoken = spoken,
            empty = false,
        )
    }
}
