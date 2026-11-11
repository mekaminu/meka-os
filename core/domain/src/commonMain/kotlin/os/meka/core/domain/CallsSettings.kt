package os.meka.core.domain

/**
 * Obi on calls, slice 4 (mockup `docs/design/calls/settings.dc.html`, approved by Meka 2026-11-07 11:52): one page for
 * how Obi talks to callers and makes bookings. THIS MONTH (spent, calls, booked, a bar against the cap and the cap
 * chips), WHEN SOMEONE CALLS YOU (Live conversation, Offer a callback slot, Booking calls), WHAT CALLERS MAY KNOW (the
 * chips [LiveCallRules.Knows]), BOOKING CALLS (the fixed limits, shown, not changeable) and RECORDINGS
 * ([VoiceRecordingRules.KEEP_CHOICES]). Rules on the device; the switches and chips are synced on the work entity and
 * the server reads them before each call.
 */
object CallsSettingsRules {
    const val TITLE = "Obi on calls"
    const val SUB = "How he talks to callers and makes bookings"
    /** The row in Work mode → Call assistant that opens the page. */
    const val ENTRY = "Obi on calls"

    const val THIS_MONTH = "This month"
    const val SPENT = "Spent"
    const val CALLS = "Calls"
    const val BOOKED = "Booked"
    const val CAP_NOTE = "At the cap Obi goes back to taking simple messages until the 1st."

    const val WHEN_CALLS = "When someone calls you"
    const val SLOTS_TITLE = "Offer a callback slot"
    const val SLOTS_SUB = "From your free times, never the details"
    const val BOOKING_TITLE = "Booking calls"
    const val BOOKING_SUB = "Obi can ring businesses after your tap"

    const val KNOWS_TITLE = "What callers may know"
    const val NEVER = "Never shared: your calendar, address, family, money or mobile."

    const val LIMITS_TITLE = "Booking calls"
    const val RECORDINGS_TITLE = "Recordings"
    const val RECORDINGS_NOTE = "Callers' voice messages, and live calls with both voices from Obi's first word (his booking calls too), are kept encrypted on Navilife's server, then deleted. Obi tells callers the call is recorded. A booking call's words and recording are kept 7 days."
    const val FOOTER = "Obi always says he's your AI assistant"

    /** Said when Call now is tapped with Booking calls switched off. */
    const val BOOKING_OFF = "Booking calls are off · turn them on in Work mode → Obi on calls"
    /** A card approved before Booking calls went off waits; the server doesn't ring it. */
    const val BOOKING_OFF_WAITING = "Waiting · Booking calls are off in Obi on calls"

    /** How the chips read. */
    fun knowsLabel(k: LiveCallRules.Knows): String = when (k) {
        LiveCallRules.Knows.BUSY -> "You're busy"
        LiveCallRules.Knows.BACK -> "When you'll call back"
        LiveCallRules.Knows.NAME -> "Your first name"
        LiveCallRules.Knows.WORK -> "Where you work"
        LiveCallRules.Knows.EMAIL -> "Your work email"
    }

    /** The fixed booking limits ([BookingCallRules]): shown so he knows them, never changeable here. */
    val LIMITS = listOf(
        CallsLimit("Hours", "${LocalClock.formatMinute(BookingCallRules.CALL_FROM_MIN)}–${LocalClock.formatMinute(BookingCallRules.CALL_TO_MIN)}"),
        CallsLimit("Longest", "${BookingCallRules.MAX_MINUTES} min"),
        CallsLimit("Calls", "Businesses only"),
        CallsLimit("Payments", "Never"),
    )

    fun keepLabel(days: Int): String = if (days == 0) "Don't keep" else "$days days"

    /** Booking cards that ended Booked in [month] ("2026-11", the meter's UTC month). */
    fun bookedIn(calls: List<BookingCall>, month: String): Int =
        calls.count { !it.cancelled && it.state == BookingCallState.BOOKED && (it.endedAtMs ?: it.calledAtMs)?.let { t -> LiveCallRules.month(t) == month } == true }

    fun view(work: WorkModeState, calls: List<BookingCall>, nowMs: Long): CallsSettingsView {
        val month = LiveCallRules.month(nowMs)
        val meter = work.callLiveMeter?.takeIf { it.month == month }
        val spent = meter?.spentPence ?: 0L
        val capPence = work.callLiveCapGbp * 100L
        val atCap = spent >= capPence
        val booked = bookedIn(calls, month)
        val live = LiveCallRules.line(work.callLive, work.callAssistant, meter, work.callLiveCapGbp)
        val slotsLine = when {
            !work.callSlots -> null
            LiveCallRules.Knows.BACK !in work.callKnows -> "Needs When you'll call back under What callers may know"
            !work.callLive -> "Only on live calls · turn Live conversation on"
            else -> null
        }
        val bookingLine = if (!work.callBooking) "Off · call cards wait and nothing rings" else null
        return CallsSettingsView(
            capGbp = work.callLiveCapGbp,
            capLine = "cap £${work.callLiveCapGbp}",
            tiles = listOf(
                CallsTile(SPENT, LiveModeRules.money(spent)),
                CallsTile(CALLS, (meter?.calls ?: 0L).toString()),
                CallsTile(BOOKED, booked.toString()),
            ),
            fraction = if (capPence <= 0) 0f else (spent.toFloat() / capPence).coerceIn(0f, 1f),
            atCap = atCap,
            caps = LiveCallRules.CAP_CHOICES.map { CallsChip(it.toString(), "£$it", it == work.callLiveCapGbp) },
            toggles = listOf(
                // The design check (2026-11-07): "On · £x of £y" repeats THIS MONTH, so the page's row keeps the line only
                // when it says something else (off, at the cap, the assistant off); Work mode's entry row keeps it all.
                CallsToggle(CallsSwitch.LIVE, LiveCallRules.TITLE, LiveCallRules.SUB, work.callLive,
                    live.takeUnless { work.callAssistant && work.callLive && !atCap }),
                CallsToggle(CallsSwitch.SLOTS, SLOTS_TITLE, SLOTS_SUB, work.callSlots, slotsLine),
                CallsToggle(CallsSwitch.BOOKING, BOOKING_TITLE, BOOKING_SUB, work.callBooking, bookingLine),
            ),
            knows = LiveCallRules.Knows.entries.map { CallsChip(it.wire, knowsLabel(it), it in work.callKnows) },
            limits = LIMITS,
            keeps = VoiceRecordingRules.KEEP_CHOICES.sortedBy { it }.map { CallsChip(it.toString(), keepLabel(it), it == work.recordingDays) },
            entryLine = live,
        )
    }

    /** [knows] with [key] switched; unknown keys leave it as it was. */
    fun toggledKnows(knows: Set<LiveCallRules.Knows>, key: String): Set<LiveCallRules.Knows> {
        val k = LiveCallRules.Knows.of(key) ?: return knows
        return if (k in knows) knows - k else knows + k
    }

    /** Whether the server may offer callback times on a live call: the switch on and "when you'll call back" allowed. */
    fun offersSlots(slotsOn: Boolean, knows: Set<LiveCallRules.Knows>): Boolean = slotsOn && LiveCallRules.Knows.BACK in knows
}

/** Which switch a row of the "Obi on calls" page is ([CallsSettingsRules]). */
enum class CallsSwitch { LIVE, SLOTS, BOOKING }

data class CallsToggle(val kind: CallsSwitch, val title: String, val sub: String, val on: Boolean, val line: String?)
data class CallsChip(val key: String, val label: String, val on: Boolean)
data class CallsLimit(val label: String, val value: String)
data class CallsTile(val label: String, val value: String)

/** The "Obi on calls" page as it reads ([CallsSettingsRules.view]). */
data class CallsSettingsView(
    val capGbp: Int,
    /** "cap £20" */
    val capLine: String,
    val tiles: List<CallsTile>,
    /** Spent against the cap, 0..1 (full at the cap). */
    val fraction: Float,
    val atCap: Boolean,
    val caps: List<CallsChip>,
    val toggles: List<CallsToggle>,
    val knows: List<CallsChip>,
    val limits: List<CallsLimit>,
    val keeps: List<CallsChip>,
    /** The line on Work mode's entry row. */
    val entryLine: String,
)
