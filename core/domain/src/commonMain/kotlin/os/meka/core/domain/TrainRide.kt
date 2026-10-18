package os.meka.core.domain

/**
 * Train-time queue (4) and Reading's commute slots (2026-10-18): the train Meka is on and how long until he gets off
 * ("On the 07:42 · 15 min to Oakfield Junction"). [depMin] its booked time, [leftMin] the minutes to [to] (where he
 * changes in the morning, his home station in the evening; null when About you doesn't name it), [morning] which way.
 */
data class TrainRide(val depMin: Int, val leftMin: Int, val to: String?, val morning: Boolean)

/**
 * The departures this device has seen on today's live board ([TrainBoard]), kept on the device because a train drops
 * off the board once it has left: [day] (epoch day) and [dir] ([TrainBoard.AM] / [TrainBoard.PM]) they belong to,
 * [yours] the booked minute of the train the leave-by last picked ([RouteRules.leaveBy]) before it left.
 */
data class TrainRideSeen(val day: Long, val dir: String, val departures: List<TrainDeparture>, val yours: Int? = null)

/** Non-AI, pure. Nothing here is synced or sent: it is read from the board the server already writes. */
object TrainRideRules {
    /** The device-local value (never synced) that keeps [TrainRideSeen] across restarts. */
    const val KEY = "train_ride_seen"
    /** No ride is longer than this; anything that would be is a misread. */
    const val MAX_RIDE_MIN = 180
    /** Departures kept per day and direction. */
    const val MAX_SEEN = 12

    /** "20381|am|462|432,437,3,461;444,x,,": the day, the way, his train (blank for none), then each departure. */
    fun encode(s: TrainRideSeen?): String? = s?.let {
        "${it.day}|${it.dir}|${it.yours ?: ""}|" + it.departures.joinToString(";") { d -> TrainBoardCodec.encode(listOf(d)) }
    }

    fun decode(text: String?): TrainRideSeen? {
        val f = text?.split('|', limit = 4)?.takeIf { it.size == 4 } ?: return null
        val day = f[0].toLongOrNull() ?: return null
        val dir = f[1].takeIf { it == TrainBoard.AM || it == TrainBoard.PM } ?: return null
        val deps = f[3].split(';').flatMap { TrainBoardCodec.decode(it) }.take(MAX_SEEN)
        return TrainRideSeen(day, dir, deps, f[2].toIntOrNull()?.takeIf { it in 0 until 1440 })
    }

    private fun leaves(d: TrainDeparture): Int = if (d.cancelled) d.depMin else (d.expMin ?: d.depMin)

    /** Minutes from [from] to [to] going forward round the clock. */
    private fun ahead(from: Int, to: Int): Int = (to - from).mod(1440)

    /** True once [d] has left at [nowMin] (and left within the longest ride). */
    private fun left(d: TrainDeparture, nowMin: Int): Boolean = ahead(leaves(d), nowMin) < MAX_RIDE_MIN

    /**
     * Today's seen departures after reading [board]: a fresh board ([RouteRules.boardFresh]) adds its departures (a
     * train's newest state replaces what was seen of it) to what was seen today the same way; another day or way
     * starts again. [leave] (the leave-by for this way, when there is one) names his train, but only while the train
     * named before hasn't left, so catching one never lets a later pick take its place.
     */
    fun merge(prev: TrainRideSeen?, board: TrainBoard, leave: LeaveBy?, nowMs: Long, cal: LocalCalendar): TrainRideSeen? {
        val day = cal.epochDayOf(nowMs)
        val kept = prev?.takeIf { it.day == day }
        if (!RouteRules.boardFresh(board, nowMs)) return kept
        val dir = board.dir?.takeIf { it == TrainBoard.AM || it == TrainBoard.PM } ?: return kept
        val base = kept?.takeIf { it.dir == dir }
        val byDep = LinkedHashMap<Int, TrainDeparture>()
        base?.departures?.forEach { byDep[it.depMin] = it }
        board.departures.forEach { byDep[it.depMin] = it }
        val nowMin = cal.minuteOfDay(nowMs)
        // In the order they leave, around now (gone ones before, within half a day); the oldest gone drop off past MAX_SEEN.
        fun signed(d: TrainDeparture) = ahead(nowMin, leaves(d)).let { a -> if (a > 720) a - 1440 else a }
        val deps = byDep.values.sortedBy(::signed).takeLast(MAX_SEEN)
        val morning = dir == TrainBoard.AM
        val before = base?.yours?.let { y -> byDep[y] }
        val yours = when {
            before != null && left(before, nowMin) -> before.depMin
            leave != null && leave.morning == morning -> leave.train.depMin
            else -> base?.yours
        }
        return TrainRideSeen(day, dir, deps, yours)
    }

    /**
     * The train he's on at [nowMs], from today's seen departures on an office day ([office]): the leave-by's train
     * once it has left and until it arrives (the arrival moved on by its lateness), and only it; without one (no Time to the train
     * in About you) the latest train that left at or after his usual time (work's start less the day's commute in the
     * morning, work's end in the evening). Never a cancelled train or one with no arrival where he gets off. Null when
     * he isn't on one.
     */
    fun ride(seen: TrainRideSeen?, office: OfficeWindow?, change: String?, homeStation: String?, nowMs: Long, cal: LocalCalendar): TrainRide? {
        office ?: return null
        val s = seen?.takeIf { it.day == cal.epochDayOf(nowMs) } ?: return null
        val morning = s.dir == TrainBoard.AM
        val nowMin = cal.minuteOfDay(nowMs)
        fun onIt(d: TrainDeparture): Int? {
            if (d.cancelled) return null
            val arr = d.arrMin ?: return null
            val arrives = (arr + (d.lateMin ?: 0)).mod(1440)
            val ride = ahead(leaves(d), arrives)
            val gone = ahead(leaves(d), nowMin)
            val leftMin = ahead(nowMin, arrives)
            if (ride == 0 || ride > MAX_RIDE_MIN || gone > ride || leftMin == 0 || leftMin > ride) return null
            return leftMin
        }
        // Once the leave-by has named his train, it's that one or none (a later train isn't his once he's off his).
        val yours = s.yours?.let { y -> s.departures.firstOrNull { it.depMin == y } }
        val pick = (if (yours != null) yours.takeIf { onIt(it) != null } else run {
            val usualMs = if (morning) office.toWorkMin?.let { office.startMs - it * 60_000L } else office.endMs
            val usual = usualMs?.let { cal.minuteOfDay(it) } ?: return null
            s.departures.filter { onIt(it) != null && ahead(usual, it.depMin) < MAX_RIDE_MIN }
                .minByOrNull { ahead(leaves(it), nowMin) }
        }) ?: return null
        val to = (if (morning) change else homeStation)?.trim()?.takeIf { it.isNotEmpty() }
        return TrainRide(pick.depMin, onIt(pick) ?: return null, to, morning)
    }

    /** "On the 07:42 · 15 min to Oakfield Junction" ("· 15 min to go" when where he gets off isn't named). */
    fun words(r: TrainRide): String =
        "On the ${LocalClock.formatMinute(r.depMin)} · ${r.leftMin} min " + (r.to?.let { "to $it" } ?: "to go")

    fun spoken(r: TrainRide): String =
        "On the ${LocalClock.formatMinute(r.depMin)} train, ${r.leftMin} ${if (r.leftMin == 1) "minute" else "minutes"} " +
            (r.to?.let { "to $it" } ?: "to go")
}
