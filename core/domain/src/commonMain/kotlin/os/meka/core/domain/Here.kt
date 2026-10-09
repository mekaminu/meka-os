package os.meka.core.domain

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Where I am now" (build plan "Places, location weather, per-day work hours and trains", item 3; Meka 2026-10-09):
 * with Meka's OK (a switch, off by default, kept on each device) the Fold shares an **approximate** location — rounded
 * to [HereRules.ROUND_DEG] (about 1 km) on the device before it leaves — and only on demand: when Ask or Talk is asked
 * about "here" / "where I am", and for Today's weather line when MEKA opens away from both home and work. Never tracked
 * in the background, never stored on the server (it asks Open-Meteo for the rounded point's forecast and forgets it),
 * never synced; the device keeps the last fix in memory for [HereRules.FIX_FRESH_MS] at most.
 *
 * Non-AI, pure. The device does the asking; these rules decide when, what is sent and what the lines say.
 */
data class Coord(val lat: Double, val lon: Double)

/**
 * The last "where I am now" answer, kept in memory on the device only (never synced, never stored): the rounded point
 * that was sent, the forecast the server sent back, whether that point is away from home and work, and when.
 */
data class HereFix(val at: Coord, val forecast: WeatherForecast, val away: Boolean, val atMs: Long)

/** The settings row's words (Ask → More → Settings → Where I am now). */
data class HereSettingView(
    val on: Boolean,
    /** "Off · the weather is Biggleswade's and Canary Wharf's" / "On · when you ask about “here”, …" / "Needs …". */
    val statusLine: String,
    /** Lit in the accent: on, but the phone hasn't allowed location (so nothing can be shared). */
    val lit: Boolean,
    /** "Turn on" / "Turn off". */
    val actionLabel: String,
    /** What is shared and what never is (shown under the row). */
    val privacy: String,
)

object HereRules {
    /** Coordinates leave the device rounded to two decimal places: ~1.1 km north–south, ~0.7 km east–west here. */
    const val ROUND_DEG = 0.01
    private const val ROUND_SCALE = 100.0

    /** Farther than this from every known place (home, work) and Today's line says where Meka is instead. */
    const val AWAY_KM = 15.0

    /** A fix is reused for this long (in memory only), so a conversation or a morning doesn't ask again and again. */
    const val FIX_FRESH_MS = 30 * 60_000L

    /** The default places' coordinates (the server's own: Open-Meteo's Biggleswade and Canary Wharf). */
    val HOME = Coord(52.0868, -0.2645)
    val WORK = Coord(51.5054, -0.0235)

    private const val EARTH_KM = 6371.0

    /**
     * The point as it may leave the device: both coordinates rounded to [ROUND_DEG]. Null for anything that isn't a
     * place on Earth (NaN, out of range), so nothing odd is ever sent.
     */
    fun round(lat: Double, lon: Double): Coord? {
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Coord(snap(lat), snap(lon))
    }

    private fun snap(v: Double): Double = (v * ROUND_SCALE).roundToLong() / ROUND_SCALE

    /** True when [c] carries no more than [ROUND_DEG]'s precision (what the server insists on). */
    fun isRounded(c: Coord): Boolean = isRounded(c.lat) && isRounded(c.lon)

    fun isRounded(v: Double): Boolean {
        if (v.isNaN() || v.isInfinite()) return false
        val scaled = v * ROUND_SCALE
        return kotlin.math.abs(scaled - scaled.roundToLong()) < 1e-6
    }

    /** Great-circle distance in km (haversine). */
    fun distanceKm(a: Coord, b: Coord): Double {
        val r = PI / 180.0
        val dLat = (b.lat - a.lat) * r
        val dLon = (b.lon - a.lon) * r
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(a.lat * r) * cos(b.lat * r) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Away from every place MEKA already forecasts ([places]: home and work), so "here" says something new. */
    fun isAway(here: Coord, places: List<Coord> = listOf(HOME, WORK)): Boolean =
        places.isEmpty() || places.all { distanceKm(here, it) > AWAY_KM }

    private val HERE_PHRASES = listOf(
        Regex("""\bhere\b(?!['’]s)""", RegexOption.IGNORE_CASE),
        Regex("""\bwhere\s+i\s+am\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwhere\s+i['’]m\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(near|around)\s+me\b""", RegexOption.IGNORE_CASE),
        Regex("""\bnearby\b""", RegexOption.IGNORE_CASE),
        Regex("""\bmy\s+location\b""", RegexOption.IGNORE_CASE),
        Regex("""\boutside\b""", RegexOption.IGNORE_CASE),
    )

    /**
     * Whether a question to Ask or Talk is about where Meka is: "what's the weather here?", "will it rain where I am?",
     * "is it cold outside?", "anything near me?". "Here's the list" or "hear" is not.
     */
    fun asksAboutHere(text: String): Boolean = HERE_PHRASES.any { it.containsMatchIn(text) }

    /**
     * Whether the device may ask the phone for a fix now: the switch is on, the phone allows (approximate) location,
     * and either the question is about here, or Today is opening and the last fix is older than [FIX_FRESH_MS] (or
     * there is none). A fresh fix is reused instead.
     */
    fun shouldLocate(on: Boolean, permitted: Boolean, question: String?, lastFixMs: Long?, nowMs: Long): Boolean {
        if (!on || !permitted) return false
        val stale = lastFixMs == null || nowMs - lastFixMs >= FIX_FRESH_MS || nowMs < lastFixMs
        return if (question != null) asksAboutHere(question) && stale else stale
    }

    /**
     * Today's weather line while away: "Near you · 12° · light rain from 16:00". Null when the forecast has no reading
     * for now (Today keeps home's line).
     */
    fun nowLine(f: WeatherForecast, nowMs: Long, cal: LocalCalendar): String? =
        WeatherRules.nowLine(f, nowMs, cal)?.let { "$NEAR_YOU · $it" }

    const val NEAR_YOU = "Near you"

    /** A fix younger than [FIX_FRESH_MS] (and not from the future) is still used; an older one is forgotten. */
    fun fresh(fix: HereFix?, nowMs: Long): HereFix? =
        fix?.takeIf { nowMs >= it.atMs && nowMs - it.atMs < FIX_FRESH_MS && !it.forecast.isEmpty }

    /**
     * Today's weather line with a fix: "Near you · 12° · …" while a fresh fix is away from home and work; null (Today
     * keeps home's or both places' line) otherwise.
     */
    fun todayLine(fix: HereFix?, nowMs: Long, cal: LocalCalendar): String? =
        fresh(fix, nowMs)?.takeIf { it.away }?.let { nowLine(it.forecast, nowMs, cal) }

    /**
     * What Ask hears about where Meka is: only for a question about here ([asksAboutHere]) and a fresh fix. At home or
     * at work the fix adds nothing new, but the question still gets an answer from it (the lines say "approximate").
     */
    fun askLinesFor(question: String, fix: HereFix?, nowMs: Long, cal: LocalCalendar): List<String> {
        if (!asksAboutHere(question)) return emptyList()
        val f = fresh(fix, nowMs) ?: return emptyList()
        return askLines(f.forecast, nowMs, cal)
    }

    /**
     * Where Meka is, as Ask MEKA hears it (after home's and work's lines): now, the rest of today and tomorrow, saying
     * it is approximate. Empty without a forecast.
     */
    fun askLines(f: WeatherForecast, nowMs: Long, cal: LocalCalendar): List<String> {
        if (f.isEmpty) return emptyList()
        val today = cal.epochDayOf(nowMs)
        return listOfNotNull(
            WeatherRules.nowLine(f, nowMs, cal)?.let { "now where Meka is (approximate location): $it" },
            WeatherRules.dayLine("", f, today, cal)?.let { "today where Meka is: $it" },
            WeatherRules.dayLine("", f, today + 1, cal)?.let { "tomorrow where Meka is: $it" },
        )
    }

    const val PRIVACY =
        "Only when you ask about “here” or open MEKA away from home and work. Your location is rounded to about 1 km on " +
            "the phone; MEKA's server asks Open-Meteo for that point's forecast and keeps nothing. Never tracked in the " +
            "background, never synced to your other devices."

    /** The settings row: off by default; lit when on but the phone hasn't allowed location. */
    fun setting(on: Boolean, permitted: Boolean): HereSettingView = when {
        !on -> HereSettingView(false, "Off · the weather is home's and work's", lit = false, actionLabel = "Turn on", privacy = PRIVACY)
        !permitted -> HereSettingView(true, "Needs location (approximate is enough) · tap to allow", lit = true, actionLabel = "Turn off", privacy = PRIVACY)
        else -> HereSettingView(true, "On · when you ask about “here”, or away from home and work", lit = false, actionLabel = "Turn off", privacy = PRIVACY)
    }
}
