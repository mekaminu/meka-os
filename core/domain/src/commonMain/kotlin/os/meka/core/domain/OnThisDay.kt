package os.meka.core.domain

/*
 * Screenshots and photos that do something, slice 4 (build plan V1): **"On this day"**. In the evening moment (Shut down
 * the day) a photo he took on this date in an earlier year, picked **on the phone** from its own picture library and
 * never uploaded, copied, synced or sent; off until he turns it on there (photo access is asked only then). "Another"
 * shows a different one, "Not this one" hides it for good (on this phone), a tap opens it in Gallery.
 *
 * Pure: which days count, which pictures are his own photos (not screenshots, downloads, memes or tiny thumbnails),
 * which one to show tonight, and the lines. The Fold's library query and the picture itself stay on the Fold.
 */

/** A picture the device's library listed for one of [OnThisDayRules.windows]' days. [localEpochDay] is the local date it was taken. */
data class MemoryPhoto(
    val id: String,
    val takenAtMs: Long,
    val localEpochDay: Long,
    val relativePath: String?,
    val displayName: String?,
    val width: Int,
    val height: Int,
)

/** Tonight's photo: which one, how long ago and the lines for it. */
data class OnThisDayPick(
    val photo: MemoryPhoto,
    val yearsAgo: Int,
    /** "3 years ago · Sat 15 Oct 2023" */
    val line: String,
    /** How many of his photos share this date across the years ("1 of 12 from this day"), for Another. */
    val ofCount: Int,
    /** The position among [ofCount] (0-based), so Another moves on. */
    val index: Int,
)

/** One past year's day, as a local epoch day. */
data class MemoryDay(val yearsAgo: Int, val epochDay: Long)

object OnThisDayRules {
    /** How far back to look. */
    const val YEARS_BACK = 15
    /** Pictures smaller than this on their short side are thumbnails, stickers or icons. */
    const val MIN_SIDE = 480
    /** Longer than this on one side is a long screenshot or a receipt scroll, not a photo. */
    const val MAX_ASPECT = 2.6
    /** At most this many pictures are listed from the library for one evening. */
    const val QUERY_LIMIT = 400

    const val LABEL = "ON THIS DAY"
    const val PRIVACY = "Picked on this phone · never uploaded"
    const val ANOTHER = "Another"
    const val HIDE = "Not this one"
    const val TURN_OFF = "Turn off"
    const val OPEN_HINT = "Opens in Gallery"
    const val OFFER = "📷 On this day · a photo from this date in past years, in the evening · picked on this phone, never uploaded · Turn on"
    const val OFFER_LABEL = "On this day photo"
    const val NOT_NOW = "Not now"
    const val NONE = "No photos from this date in past years · it'll look again tomorrow"
    const val PARTIAL = "On this day can see only the photos you picked · allow all photos to have it look through the years"
    const val DENIED = "On this day needs to see your photos · they stay on this phone"
    const val TURNED_ON = "On this day is on · a photo from this date each evening, picked on this phone"
    const val TURNED_OFF = "On this day is off"
    const val HIDDEN = "Won't show that one again"

    /** The same date in each of the last [YEARS_BACK] years; 29 February falls back to the 28th in other years. */
    fun windows(todayEpochDay: Long, years: Int = YEARS_BACK): List<MemoryDay> {
        val today = CivilDate.fromEpochDay(todayEpochDay)
        return (1..years).map { back ->
            val y = today.year - back
            val day = minOf(today.day, CivilDate.lengthOfMonth(y, today.month))
            MemoryDay(back, CivilDate.toEpochDay(y, today.month, day))
        }
    }

    private val NOT_PHOTOS = listOf(
        "download", "downloads", "documents", "whatsapp documents", "whatsapp stickers", "whatsapp animated gifs",
        "telegram documents", "screen recordings", "screenrecorder", "screenshots", "screenshot", "gifs", "stickers",
        "memes", "navilife", "meka",
    )

    /** His own photos: camera, or a chat/app's image folder, but never screenshots, downloads, stickers or tiny/long pictures. */
    fun isPhoto(p: MemoryPhoto): Boolean {
        if (ScreenshotRules.isScreenshot(p.relativePath, p.displayName)) return false
        val parts = p.relativePath.orEmpty().lowercase().replace('\\', '/').split('/').map { it.trim() }
        if (parts.any { it in NOT_PHOTOS }) return false
        val name = p.displayName.orEmpty().lowercase()
        if (name.endsWith(".gif") || name.endsWith(".webp") && "sticker" in name) return false
        if (p.width > 0 && p.height > 0) {
            val short = minOf(p.width, p.height)
            val long = maxOf(p.width, p.height)
            if (short < MIN_SIDE) return false
            if (long.toDouble() / short > MAX_ASPECT) return false
        }
        return true
    }

    /** From the phone's own camera (preferred over a chat's images). */
    fun fromCamera(p: MemoryPhoto): Boolean {
        val path = p.relativePath.orEmpty().lowercase().replace('\\', '/')
        return path.startsWith("dcim/")
    }

    /**
     * The photos worth showing tonight, in the order Another walks them: one year at a time starting from a year that
     * changes each day (so the same evening on two devices agrees, and tomorrow differs), camera photos first within a
     * year, then the middle of that day's photos first (the first shot of a burst is often the blurred one).
     */
    fun ordered(candidates: List<MemoryPhoto>, todayEpochDay: Long, hidden: Set<String> = emptySet()): List<Pair<MemoryPhoto, Int>> {
        val byDay = windows(todayEpochDay).associate { it.epochDay to it.yearsAgo }
        val kept = candidates.asSequence()
            .filter { it.id !in hidden && it.localEpochDay in byDay && isPhoto(it) }
            .distinctBy { it.id }
            .toList()
        if (kept.isEmpty()) return emptyList()
        val camera = kept.any(::fromCamera)
        val usable = if (camera) kept.filter(::fromCamera) else kept
        val years = usable.groupBy { byDay.getValue(it.localEpochDay) }.toSortedMap()
        val yearKeys = years.keys.toList()
        val start = (todayEpochDay.mod(yearKeys.size.toLong())).toInt()
        val rotated = yearKeys.drop(start) + yearKeys.take(start)
        val perYear = rotated.map { y ->
            val photos = years.getValue(y).sortedBy { it.takenAtMs }
            middleOut(photos).map { it to y }
        }
        // Interleave the years: one from each, then the next from each, so Another travels through time.
        val out = ArrayList<Pair<MemoryPhoto, Int>>()
        var i = 0
        while (out.size < usable.size) {
            for (list in perYear) if (i < list.size) out += list[i]
            i++
        }
        return out
    }

    private fun <T> middleOut(list: List<T>): List<T> {
        if (list.size <= 2) return list
        val mid = list.size / 2
        val out = ArrayList<T>(list.size)
        out += list[mid]
        var step = 1
        while (out.size < list.size) {
            if (mid + step < list.size) out += list[mid + step]
            if (mid - step >= 0 && out.size < list.size) out += list[mid - step]
            step++
        }
        return out
    }

    /** Tonight's photo at [index] (wrapping), or null when there's none. */
    fun pick(candidates: List<MemoryPhoto>, todayEpochDay: Long, hidden: Set<String> = emptySet(), index: Int = 0): OnThisDayPick? {
        val all = ordered(candidates, todayEpochDay, hidden)
        if (all.isEmpty()) return null
        val i = index.mod(all.size)
        val (photo, years) = all[i]
        return OnThisDayPick(photo, years, line(years, photo.localEpochDay), all.size, i)
    }

    /** "1 year ago · Wed 15 Oct 2025" */
    fun line(yearsAgo: Int, epochDay: Long): String {
        val ago = if (yearsAgo == 1) "1 year ago" else "$yearsAgo years ago"
        return "$ago · ${CivilDate.shortLabel(epochDay)} ${CivilDate.fromEpochDay(epochDay).year}"
    }

    /** "2 of 12 from this date" once there's more than one, else null. */
    fun countLine(pick: OnThisDayPick): String? =
        if (pick.ofCount > 1) "${pick.index + 1} of ${pick.ofCount} from this date" else null

    /** What a screen reader says for the photo. */
    fun description(pick: OnThisDayPick): String = "Photo from ${pick.line} · $OPEN_HINT"
}
