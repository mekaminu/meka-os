package os.meka.core.domain

/**
 * Requests from people Meka watches become proposals (build plan V1, "Requests from my wife become tasks", slice 1).
 * Non-AI and pure: which messages may be read, what a model's proposals are allowed to become, the dates resolved
 * again from the message's own words, duplicates merged, and how the Needs you card reads on both apps.
 *
 * Messages are untrusted (ADR-006): a proposal never acts by itself; it is a card with Add · Change · Not a task, and
 * nothing here replies to anyone, marks anything read or dismisses a notification.
 */
enum class RequestKind(val wire: String, val verb: String, val decline: String) {
    TASK("task", "Add task", "Not a task"),
    WORK_FROM_HOME("work_from_home", "Work from home", "Not needed"),
    EVENT("event", "Add event", "Not an event"),
    REMINDER("reminder", "Remind me", "Not needed"),
    /** Onto the renewals radar ("Handle this", slice 2): only ever proposed for something Meka shared. */
    RENEWAL("renewal", "Add to renewals", "Not needed"),
    /**
     * A drafted reply Meka copies and sends himself from his mail or chat app ("Handle this", slice 3): only ever
     * proposed for something Meka shared, and MEKA never sends it.
     */
    REPLY("reply", "Draft reply", "Not needed"),
    /**
     * A travel booking into Trips (Travel mode, slice 2b): a flight, train, stay or hire car from a confirmation Meka
     * shared. Only ever proposed for something Meka shared; it joins the trip it falls on ([HandleThisRules.withTrips]).
     */
    BOOKING("booking", "Add to trip", "Not needed"),
    /**
     * An idea for Someday → Ideas (Voice notes into actions, slice 2): only ever proposed for one of Meka's own voice
     * notes; it has a title and nothing else (no day, no time).
     */
    IDEA("idea", "Add to Ideas", "Not needed"),
    ;

    companion object {
        fun of(wire: String): RequestKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** A proposal as the server passed it on (the model's words, unchecked). */
data class RawRequestProposal(
    val kind: String,
    val title: String? = null,
    val date: String? = null,
    val time: String? = null,
    val words: String? = null,
    /** A renewal's type, repeat and cost as the model wrote them (the wire already kept only known values). */
    val type: String? = null,
    val repeat: String? = null,
    val cost: String? = null,
    /** A reply's drafted words as the model wrote them ("Handle this", slice 3); checked by [HandleThisRules.cleanReply]. */
    val body: String? = null,
    /** A booking's reference and its last day (check-out, a hire car's return) as the model wrote them (Travel mode, slice 2b). */
    val ref: String? = null,
    val until: String? = null,
)

/**
 * What a booking card adds to Trips besides its title, day and time (Travel mode, slice 2b): its kind, a stay's or hire
 * car's last day, its reference, and the trip it joins ([tripName], "" until [HandleThisRules.withTrips] finds it).
 */
data class BookingDetails(val kind: BookingKind, val endDay: Long?, val ref: String?, val tripName: String = "")

/** What a renewal card adds to the radar besides its title and day: its kind, how it repeats and its cost. */
data class RenewalDetails(val kind: ObligationKind, val repeat: RenewalRepeat, val costPence: Long?)

/**
 * A checked proposal: a known kind, a clean title, a day within reach (when it has one) and a valid time; a renewal
 * also has its [renewal] details (and no time).
 */
data class RequestProposal(
    val kind: RequestKind,
    val title: String,
    val day: Long?,
    val minute: Int?,
    val renewal: RenewalDetails? = null,
    /** A reply card's drafted words (checked: no links, addresses or numbers); null on every other kind. */
    val reply: String? = null,
    /** A booking card's details (Travel mode, slice 2b); null on every other kind. */
    val booking: BookingDetails? = null,
)

/** One message from a watched person, as the listener read it. */
data class RequestMessage(
    /** The captured item's id (stable across WhatsApp's re-posts). */
    val id: String,
    val personName: String,
    val text: String,
    val atMs: Long,
)

/** The Needs you card for one proposal. */
data class RequestCard(
    /** The message's id and the proposal's place in it: the same card when the same message is read again. */
    val id: String,
    val messageId: String,
    val personKey: String,
    val proposal: RequestProposal,
    /** "From Wife · 14:02". */
    val from: String,
    /** "“can you pick up the dry cleaning tomorrow?”" (shortened at a word past [MessageRequestRules.QUOTE_CHARS]). */
    val quote: String,
    /** "Add task: Pick up dry cleaning · Tomorrow". */
    val action: String,
    /** What happens on Add, when it does more than its line says ("Thu 15 Oct shows as work from home on both apps"). */
    val detail: String?,
    val addLabel: String = "Add",
    /** "Change" (makes it a task and opens its detail); null on a work-from-home card, which has nothing to edit. */
    val changeLabel: String? = "Change",
    val declineLabel: String,
    /** One sentence for TalkBack / VoiceOver. */
    val spoken: String,
    /** When the message came (its notification's time): when the card's heads-up is due. */
    val atMs: Long = 0L,
    /** A reply card's drafted words, shown whole under the action and copied by its button ("Handle this", slice 3). */
    val draft: String? = null,
    /** Who asked, as the notification named them ("Wife"); "" on a card Meka shared himself (morning brief slice 4). */
    val person: String = "",
)

object MessageRequestRules {
    const val MAX_TITLE = 80
    const val QUOTE_CHARS = 140
    /** A proposed day further than this ahead is dropped (a model's misread year, not a plan). */
    const val MAX_DAYS_AHEAD = 366

    private val voiceNote = Regex("""^\W*(voice (message|note)|audio)\b""", RegexOption.IGNORE_CASE)
    private val media = Regex("""^\W*(photo|image|video|gif|sticker|document|contact card|location)\b""", RegexOption.IGNORE_CASE)

    /**
     * Whether [item] may be read for requests: a message (not a missed call) with text, from someone on the family list
     * or [watching] (Work mode → People → Watch for requests from), and from a 1:1 chat unless its group is in
     * [groups] (named groups Meka turned on).
     */
    fun shouldRead(item: CapturedItem, lists: PeopleLists, watching: Set<String>, groups: Set<String> = emptySet()): Boolean {
        if (item.kind != CaptureKind.MESSAGE || item.text.isNullOrBlank()) return false
        val group = item.conversation
        if (group != null && People.key(group) !in groups.map(People::key).toSet()) return false
        return isWatched(item.personName, lists, watching)
    }

    fun isWatched(name: String, lists: PeopleLists, watching: Set<String>): Boolean =
        lists.isFamily(name) || People.key(name) in watching.map(People::key).toSet()

    /** A voice note can't be read: MEKA offers to remind Meka to listen instead of asking the AI. */
    fun isVoiceNote(text: String): Boolean = voiceNote.containsMatchIn(text.trim())

    /** A photo, video, sticker…: nothing to read, nothing to propose. */
    fun isMediaOnly(text: String): Boolean = media.containsMatchIn(text.trim()) && text.trim().length <= 40

    /** Whether the AI should see [text] at all (not a voice note, not a bare photo). */
    fun worthAsking(text: String): Boolean = text.isNotBlank() && !isVoiceNote(text) && !isMediaOnly(text)

    /** The one proposal for a voice note, made on the phone with no AI: "Listen to Wife's voice note". */
    fun voiceNoteProposal(personName: String): RequestProposal =
        RequestProposal(RequestKind.REMINDER, cleanTitle("Listen to ${personName.trim()}'s voice note")!!, null, null)

    /**
     * The model's proposals checked: known kinds only; the day resolved again from the message's own when-words when
     * they read (the model's date is used only when they don't), never in the past or more than [MAX_DAYS_AHEAD]
     * ahead; work from home and events need a day; a task or reminder needs a title; duplicates within the answer
     * merged. [today] and [nowMinute] are the time the message came. A renewal is read only when [allowRenewal]
     * ("Handle this"; never a watched person's message or a group) and needs a day. A reply likewise only when
     * [allowReply] ("Handle this", slice 3): its draft checked by [HandleThisRules.cleanReply] (no day, no time; at most
     * one).
     */
    fun check(
        raw: List<RawRequestProposal>,
        today: Long,
        nowMinute: Int,
        allowRenewal: Boolean = false,
        allowReply: Boolean = false,
        allowBooking: Boolean = false,
        allowIdea: Boolean = false,
    ): List<RequestProposal> {
        val out = mutableListOf<RequestProposal>()
        raw.forEach { r ->
            val kind = RequestKind.of(r.kind) ?: return@forEach
            if (kind == RequestKind.RENEWAL && !allowRenewal) return@forEach
            if (kind == RequestKind.BOOKING && !allowBooking) return@forEach
            if (kind == RequestKind.IDEA) {
                if (!allowIdea) return@forEach
                val p = RequestProposal(kind, cleanTitle(r.title) ?: return@forEach, null, null)
                if (out.none { sameRequest(it, p) }) out += p
                return@forEach
            }
            if (kind == RequestKind.REPLY) {
                if (!allowReply || out.any { it.kind == RequestKind.REPLY }) return@forEach
                val draft = HandleThisRules.cleanReply(r.body) ?: return@forEach
                out += RequestProposal(kind, cleanTitle(r.title) ?: HandleThisRules.REPLY_TITLE, null, null, reply = draft)
                return@forEach
            }
            val fromWords = r.words?.let { RequestDates.resolve(it, today, nowMinute) }
            val day = (fromWords?.day ?: r.date?.let(::isoDay))?.takeIf { it in today..today + MAX_DAYS_AHEAD }
            val minute = r.time?.let(::minuteOf) ?: fromWords?.minute
            val title = when (kind) {
                RequestKind.WORK_FROM_HOME -> "Work from home"
                else -> cleanTitle(r.title) ?: return@forEach
            }
            if ((kind == RequestKind.WORK_FROM_HOME || kind == RequestKind.EVENT || kind == RequestKind.RENEWAL || kind == RequestKind.BOOKING) && day == null) return@forEach
            val p = when (kind) {
                RequestKind.RENEWAL -> RequestProposal(kind, title, day, null, renewalDetails(r))
                RequestKind.BOOKING -> RequestProposal(kind, title.take(TripRules.MAX_TITLE).trimEnd(), day, minute, booking = bookingDetails(r, day!!))
                RequestKind.WORK_FROM_HOME -> RequestProposal(kind, title, day, null)
                else -> RequestProposal(kind, title, day, minute)
            }
            if (out.none { sameRequest(it, p) }) out += p
        }
        return out
    }

    /**
     * A renewal's details from the model's words: its type (else "other"), its repeat (else the type's usual one,
     * [RenewalRules.defaultRepeat]) and its cost in pence (unknown when it doesn't read).
     */
    fun renewalDetails(r: RawRequestProposal): RenewalDetails {
        val kind = when (r.type) {
            "mot" -> ObligationKind.MOT
            "car_tax" -> ObligationKind.CAR_TAX
            "insurance" -> ObligationKind.INSURANCE
            "boiler" -> ObligationKind.BOILER
            "subscription" -> ObligationKind.SUBSCRIPTION
            "bill" -> ObligationKind.BILL
            "licence" -> ObligationKind.LICENCE
            "warranty" -> ObligationKind.WARRANTY
            else -> ObligationKind.OTHER
        }
        val repeat = when (r.repeat) {
            "none" -> RenewalRepeat.NONE
            "month" -> RenewalRepeat.MONTHLY
            "quarter" -> RenewalRepeat.QUARTERLY
            "half_year" -> RenewalRepeat.HALF_YEARLY
            "year" -> RenewalRepeat.YEARLY
            else -> RenewalRules.defaultRepeat(kind)
        }
        val pence = try { RenewalRules.parseCost(r.cost) } catch (e: ValidationException) { null }
        return RenewalDetails(kind, repeat, pence?.takeIf { it > 0 })
    }

    /** The line under an idea card: where Add puts it. */
    const val IDEA_DETAIL = "Kept in Lists → Someday → Ideas · no date, nothing to plan"

    /** A booking stays this many days at most (a stay's check-out, a hire car's return); a later one is dropped. */
    const val MAX_BOOKING_DAYS = 60

    /**
     * A booking's details from the model's words: its kind (else other), a stay's or hire car's last day (after [day],
     * within [MAX_BOOKING_DAYS]; never for a flight or train) and its reference (3–[TripRules.MAX_REF] letters, digits
     * or hyphens, in capitals; else none).
     */
    fun bookingDetails(r: RawRequestProposal, day: Long): BookingDetails {
        val kind = BookingKind.entries.firstOrNull { it.wire == r.type } ?: BookingKind.OTHER
        val end = r.until?.let(::isoDay)
            ?.takeIf { (kind == BookingKind.STAY || kind == BookingKind.CAR) && it > day && it <= day + MAX_BOOKING_DAYS }
        val ref = r.ref?.trim()?.takeIf { it.length in 3..TripRules.MAX_REF && it.all { c -> c.isLetterOrDigit() || c == '-' } }?.uppercase()
        return BookingDetails(kind, end, ref)
    }

    /** The same request asked twice: the same kind and day, and titles that match ignoring case and punctuation. */
    fun sameRequest(a: RequestProposal, b: RequestProposal): Boolean =
        a.kind == b.kind && a.day == b.day && titleKey(a.title) == titleKey(b.title)

    /**
     * Cards for [proposals] read from [message], leaving out any [open] card already asks for (the same person asking
     * again, or WhatsApp re-posting the message). [today] is the day the cards are shown; [calendar] reads the time.
     */
    fun cards(
        message: RequestMessage,
        proposals: List<RequestProposal>,
        open: List<RequestCard>,
        today: Long,
        calendar: LocalCalendar,
    ): List<RequestCard> {
        val person = People.key(message.personName)
        val made = mutableListOf<RequestCard>()
        proposals.forEachIndexed { i, p ->
            val id = "${message.id}#$i"
            if (open.any { it.id == id || (it.personKey == person && sameRequest(it.proposal, p)) }) return@forEachIndexed
            if (made.any { sameRequest(it.proposal, p) }) return@forEachIndexed
            made += card(message, i, p, today, calendar)
        }
        return made
    }

    /** The card for proposal [index] of [message]; also how a stored card is shown again ([RequestCards.open]). */
    fun card(message: RequestMessage, index: Int, p: RequestProposal, today: Long, calendar: LocalCalendar): RequestCard {
        val quote = "“${shorten(message.text.replace(Regex("""\s+"""), " ").trim(), QUOTE_CHARS)}”"
        val action = actionLine(p, today)
        // "Handle this": Meka shared it himself, so the card says so rather than "From …".
        val shared = HandleThisRules.isShared(message.id)
        val at = TaskWhenRules.timeLabel(calendar.minuteOfDay(message.atMs))
        return RequestCard(
            id = "${message.id}#$index",
            messageId = message.id,
            personKey = People.key(message.personName),
            proposal = p,
            from = if (shared) "${HandleThisRules.PERSON} · $at" else "From ${message.personName.trim().take(40)} · $at",
            quote = quote,
            action = action,
            detail = detailLine(p),
            // A reply's button copies it (Meka sends it himself; MEKA never does).
            addLabel = if (p.kind == RequestKind.REPLY) HandleThisRules.COPY_REPLY else "Add",
            // Nothing to edit as a task: a work-from-home day; a renewal is changed on the radar once added; a reply is
            // edited where Meka pastes it; a booking in Trips.
            changeLabel = if (p.kind == RequestKind.WORK_FROM_HOME || p.kind == RequestKind.RENEWAL || p.kind == RequestKind.REPLY ||
                p.kind == RequestKind.BOOKING || p.kind == RequestKind.IDEA) null else "Change",
            declineLabel = p.kind.decline,
            spoken = (if (shared) "${HandleThisRules.PERSON} $quote. $action?" else "${message.personName.trim()} wrote $quote. $action?") +
                (p.reply?.let { " The draft says: $it" } ?: ""),
            atMs = message.atMs,
            draft = p.reply,
            person = if (shared) "" else message.personName.trim().take(40),
        )
    }

    /** "Add task: Pick up dry cleaning · Tomorrow", "Work from home · Thu 15 Oct", "Add event: Parents' evening · Tue 20 Oct · 18:00". */
    fun actionLine(p: RequestProposal, today: Long): String {
        val whenLine = p.day?.let { TaskWhenRules.label(it, p.minute, today) }
            ?: p.minute?.let { "Today · ${TaskWhenRules.timeLabel(it)}" }
        return when (p.kind) {
            RequestKind.WORK_FROM_HOME -> "Work from home · ${whenLine ?: "?"}"
            RequestKind.RENEWAL -> "${p.kind.verb}: ${p.title}" + (p.day?.let { day ->
                val k = p.renewal?.kind ?: ObligationKind.OTHER
                " · ${RenewalRules.verb(k)} ${RenewalRules.dayWord(day, today)}"
            } ?: "")
            // "Add to Barcelona · Flight BA478 · Sat 14 Nov 07:40 · ref ABC123", as Ask's booking card reads.
            RequestKind.BOOKING -> bookingEntry(p)?.let { e -> "Add to " + TripRules.bookingLine(e, p.booking!!.tripName.ifEmpty { "trip" }) }
                ?: "${p.kind.verb}: ${p.title}"
            else -> "${p.kind.verb}: ${p.title}" + (whenLine?.let { " · $it" } ?: "")
        }
    }

    /**
     * The line under a work-from-home card (what Add will change) or a renewal card ("Insurance · every year · £412 a
     * year", what goes on the radar).
     */
    fun detailLine(p: RequestProposal): String? = when (p.kind) {
        RequestKind.WORK_FROM_HOME -> p.day?.let { "${CivilDate.shortLabel(it)} shows as work from home on both apps" }
        RequestKind.RENEWAL -> p.renewal?.let { r -> renewalDetailLine(r, p.day) }
        RequestKind.IDEA -> IDEA_DETAIL
        else -> null
    }

    /** A booking card's proposal as the booking it adds (its trip still to be found by day), or null for another kind. */
    fun bookingEntry(p: RequestProposal, tripId: String = ""): BookingEntry? {
        val b = p.booking ?: return null
        val day = p.day ?: return null
        return BookingEntry(tripId, b.kind, p.title, day, b.endDay, p.minute, b.ref)
    }

    /** "Insurance · every year · £412 a year" · "Subscription · every month" · "Warranty · doesn't repeat · £45". */
    fun renewalDetailLine(r: RenewalDetails, day: Long?): String {
        val rule = day?.let { RenewalRules.ruleFor(r.repeat, it) }
        return listOfNotNull(
            RenewalRules.kindLabel(r.kind),
            RenewalRules.repeatLabel(r.repeat).replaceFirstChar { it.lowercaseChar() },
            r.costPence?.let { RenewalRules.costLabel(it, rule) },
        ).joinToString(" · ")
    }

    /** One line, no control characters, at most [MAX_TITLE] characters (shortened at a word); null when empty. */
    fun cleanTitle(s: String?): String? {
        val t = s?.replace(Regex("""[\u0000-\u001F\u007F]"""), " ")?.replace(Regex("""\s+"""), " ")?.trim()?.trim('"', '“', '”')?.trim()
        if (t.isNullOrEmpty()) return null
        val first = t.first().uppercaseChar() + t.drop(1)
        return shorten(first, MAX_TITLE)
    }

    private fun titleKey(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun shorten(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.take(max)
        val at = cut.lastIndexOf(' ').takeIf { it >= max / 2 } ?: max
        return cut.take(at).trimEnd() + "…"
    }

    private fun isoDay(s: String): Long? {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(s) ?: return null
        val (y, mo, d) = m.destructured
        val month = mo.toInt()
        if (month !in 1..12 || d.toInt() !in 1..CivilDate.lengthOfMonth(y.toInt(), month)) return null
        return CivilDate.toEpochDay(y.toInt(), month, d.toInt())
    }

    private fun minuteOf(s: String): Int? {
        val m = Regex("""(\d{2}):(\d{2})""").matchEntire(s) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }
}

/**
 * The when-words of a message ("tomorrow", "Thursday", "next Friday", "on the 15th", "15 Oct", "Thu at 6pm")
 * resolved against the day it came. "the 15th" alone is the next 15th (this month, else next month); everything else
 * reads as Add event's typing does ([EventTypingRules]), so the two agree.
 */
object RequestDates {
    data class Resolved(val day: Long?, val minute: Int?)

    private val deadline = Regex("""^(?:by|before|no later than)\s+""", RegexOption.IGNORE_CASE)
    private val ordinalOnly = Regex("""^(?:on\s+)?(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)$""", RegexOption.IGNORE_CASE)

    fun resolve(words: String, today: Long, nowMinute: Int): Resolved? {
        // A deadline is its day: "by Friday", "before the 15th", "no later than Tue" ("Handle this", slice 1).
        val w = words.replace(Regex("""\s+"""), " ").trim().trimEnd('.', ',', '?', '!').replace(deadline, "")
        if (w.isEmpty()) return null
        ordinalOnly.matchEntire(w)?.let { m -> return nthOfMonth(m.groupValues[1].toInt(), today)?.let { Resolved(it, null) } }
        val p = EventTypingRules.parse("x $w", today, nowMinute) ?: return null
        // Everything must be read: "Thursday after school" is not a date MEKA can trust.
        if (p.title != "x") return null
        if (p.day == null && p.minute == null) return null
        return Resolved(p.day, p.minute)
    }

    /** The next [n]th: this month when it is still to come (today counts), else the next month that has one. */
    private fun nthOfMonth(n: Int, today: Long): Long? {
        if (n !in 1..31) return null
        var (y, m, _) = CivilDate.fromEpochDay(today)
        repeat(3) {
            if (n <= CivilDate.lengthOfMonth(y, m)) {
                val day = CivilDate.toEpochDay(y, m, n)
                if (day >= today) return day
            }
            m += 1
            if (m > 12) { m = 1; y += 1 }
        }
        return null
    }
}
