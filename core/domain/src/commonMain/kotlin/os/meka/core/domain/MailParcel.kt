package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Inbox autopilot, slice 3g (build plan, Deliveries; non-AI, pure): **parcels arriving today, from shipping emails**.
 * The server reads each order or carrier email of the last [ParcelRules.WINDOW_DAYS] days — the sender, the subject and
 * the provider's short preview, never the body — for a parcel's state and day: "Arriving today", "Out for delivery",
 * "Expected delivery: Fri 16 Oct", "Your parcel will be delivered tomorrow between 10:15 and 11:15", "Delivered to your
 * safe place". Emails about the same order (its number) or, with no number, the same shop's latest parcel fold into one
 * `parcel` entity per account and parcel. Today on both apps says "2 parcels arriving today · Amazon, DPD" (lit while one
 * is out for delivery); a delivered one drops off the line by itself, and one whose day has gone by stops showing.
 *
 * Deliveries, slice 2: each parcel also shows in the week ahead on its day (lit on an office day), and one that's out
 * for delivery today brings a heads-up through the governor.
 *
 * Every word of an email is someone else's (ADR-006): a parcel only ever makes a line or a heads-up. Nothing is sent, paid,
 * tracked or opened because of it; promos and newsletters ("free delivery tomorrow!") are never read.
 */

enum class ParcelState { COMING, OUT_FOR_DELIVERY, DELIVERED;
    companion object { fun of(s: String?): ParcelState? = entries.firstOrNull { it.name == s } }
}

/** One shipping email as read: what it says about its parcel. [ref] is the order number when the words have one. */
data class ParcelRead(
    val messageId: String,
    val receivedAtMs: Long,
    val state: ParcelState,
    /** The local day it's due (COMING / OUT_FOR_DELIVERY) or was delivered. */
    val day: Long,
    val window: String?,
    val carrier: String?,
    val merchant: String?,
    val ref: String?,
)

/** One parcel after its emails are folded together; [key] is the order number, else the first email's id. */
data class ParcelFolded(
    val key: String,
    val state: ParcelState,
    val day: Long,
    val window: String?,
    val carrier: String?,
    val merchant: String?,
    val lastAtMs: Long,
)

/** A parcel as the apps read it from the synced entity. */
data class Parcel(
    val id: String,
    val account: String,
    val state: ParcelState,
    val day: Long,
    val window: String?,
    val carrier: String?,
    val merchant: String?,
    val lastAtMs: Long,
) {
    /** Who it's from, for the line: the shop, else the carrier, else "a parcel". */
    val name: String? get() = merchant ?: carrier
}

/** Today's line: "2 parcels arriving today · Amazon, DPD"; [lit] while one is out for delivery. */
data class ParcelLine(val text: String, val spoken: String, val lit: Boolean, val parcelCount: Int)

/** Deliveries, slice 2: the week ahead's line for one day; [lit] when it's due on an office day (nobody home). */
data class ParcelWeekLine(val text: String, val spoken: String, val lit: Boolean)

object ParcelFields {
    const val ACCOUNT = "account"
    const val STATE = "state"
    const val DAY = "day"
    const val WINDOW = "window"
    const val CARRIER = "carrier"
    const val MERCHANT = "merchant"
    const val LAST_AT = "lastAtMs"
    const val TRUST = "trust"
}

object ParcelRules {
    /** Emails older than this aren't read for parcels. */
    const val WINDOW_DAYS = 14
    const val WINDOW_MS = WINDOW_DAYS * CivilDate.DAY_MS
    /** A day further than this after the email is a misread (a year, an order number). */
    const val MAX_AHEAD_DAYS = 31
    /** A parcel without a number takes a later email from its shop only until this many days after its day. */
    const val STALE_DAYS = 3
    /** Names Today's line lists before "+ N". */
    const val NAMES_SHOWN = 2

    /** Carriers by the sender's domain (with subdomains) and by name in the words. */
    private val CARRIER_DOMAINS = listOf(
        "royalmail.com" to "Royal Mail", "parcelforce.com" to "Parcelforce", "dpd.co.uk" to "DPD", "dpdlocal.co.uk" to "DPD",
        "evri.com" to "Evri", "myhermes.co.uk" to "Evri", "ups.com" to "UPS", "dhl.com" to "DHL", "dhl.co.uk" to "DHL",
        "yodel.co.uk" to "Yodel", "inpost.co.uk" to "InPost", "fedex.com" to "FedEx",
    )
    private val CARRIER_WORDS = listOf(
        "royal mail" to "Royal Mail", "parcelforce" to "Parcelforce", "dpd" to "DPD", "evri" to "Evri", "hermes" to "Evri",
        "ups" to "UPS", "dhl" to "DHL", "yodel" to "Yodel", "inpost" to "InPost", "fedex" to "FedEx",
    )

    private val MISSED = Regex(
        """\b(missed|couldn't|could not|unable to|failed|attempted|not been able to|undelivered|not delivered|returned to sender|on hold)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DELIVERED = Regex(
        """\b(?:(?:has|have|was|were|been|successfully)\s+(?:now\s+)?delivered|delivered\s+(?:to|today|at|by|in)\b|(?:parcel|order|package|delivery)\s+(?:has\s+)?arrived)|^\s*delivered\b""",
        RegexOption.IGNORE_CASE,
    )
    private val OUT = Regex(
        """\b(out for delivery|with (?:our|your|the) driver|on (?:its|it's) way to you today|being delivered today)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val COMING = Regex(
        """\b(arriving|arrives|arrive|expected(?:\s+delivery)?(?:\s+date)?|estimated\s+delivery(?:\s+date)?|delivery\s+date|will\s+be\s+delivered|will\s+arrive|scheduled\s+for\s+delivery|delivering)\b:?""",
        RegexOption.IGNORE_CASE,
    )
    /** Words that make an email about a parcel at all (a flight's "arrive 14 Nov" is not). */
    private val PARCEL_WORDS = Regex(
        """\b(parcel|package|order|delivery|delivered|shipped|shipping|dispatched|courier|tracking|shipment)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PROMISE = Regex("""\b(will|to)\s+be\s+delivered\b""", RegexOption.IGNORE_CASE)
    private val CLOCK_RANGE = Regex("""\b(\d{1,2})[:.](\d{2})\s*(?:-|–|to|and)\s*(\d{1,2})[:.](\d{2})\b""")
    private val AMPM_RANGE = Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?\s*(?:-|–|to|and)\s*(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)\b""", RegexOption.IGNORE_CASE)

    /** Words that make an email about a delivery for sure (narrower than [PARCEL_WORDS]: no "order" or "shipping"). */
    private val DELIVERY_WORDS = Regex(
        """\b(parcel|package|delivery|delivered|shipped|dispatched|courier|tracking|shipment|out\s+for\s+delivery)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Whether an email is about a parcel on its way (a carrier sent it or names it, or its words are a delivery's):
     * such an email is a Parcels item, never a promise for Waiting for (2026-10-16: "Your parcel is with DPD" read as
     * "Waiting for: Again").
     */
    fun isParcelMail(fromAddress: String?, subject: String?, preview: String?): Boolean =
        carrier(fromAddress, subject, preview) != null ||
            listOfNotNull(subject, preview).any { DELIVERY_WORDS.containsMatchIn(it) }

    /** The carrier the email is from, else one its words name; null for neither. */
    fun carrier(fromAddress: String?, subject: String?, preview: String?): String? {
        val domain = MailSortRules.domain(fromAddress)
        if (domain != null) CARRIER_DOMAINS.firstOrNull { (d, _) -> domain == d || domain.endsWith(".$d") }?.let { return it.second }
        val words = " " + listOfNotNull(subject, preview).joinToString(" ").lowercase().map { if (it.isLetter()) it else ' ' }.joinToString("") + " "
        return CARRIER_WORDS.firstOrNull { (w, _) -> " $w " in words }?.second
    }

    /** "10:15–11:15" from "between 10:15 and 11:15", "9am-1pm", "between 2 and 4pm"; null when the words give no window. */
    fun window(text: String): String? {
        CLOCK_RANGE.find(text)?.let { m ->
            val (h1, m1, h2, m2) = m.destructured
            val a = clock(h1.toInt(), m1.toInt()) ?: return@let
            val b = clock(h2.toInt(), m2.toInt()) ?: return@let
            return "$a–$b"
        }
        AMPM_RANGE.find(text)?.let { m ->
            val g = m.groupValues
            val endPm = g[6].equals("pm", true)
            val startMer = g[3].ifEmpty { g[6] }
            val h1 = hour24(g[1].toInt(), startMer) ?: return null
            val h2 = hour24(g[4].toInt(), g[6]) ?: return null
            // "between 11 and 1pm": the start is the morning one.
            val start = if (g[3].isEmpty() && endPm && h1 > h2) h1 - 12 else h1
            val a = clock(start, g[2].toIntOrNull() ?: 0) ?: return null
            val b = clock(h2, g[5].toIntOrNull() ?: 0) ?: return null
            return "$a–$b"
        }
        return null
    }

    private fun hour24(h: Int, mer: String): Int? {
        if (h !in 1..12) return null
        return when {
            mer.equals("pm", true) -> if (h == 12) 12 else h + 12
            else -> if (h == 12) 0 else h
        }
    }

    private fun clock(h: Int, m: Int): String? =
        if (h in 0..23 && m in 0..59) "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}" else null

    /**
     * What one email says about its parcel, or null when it says nothing sure: a missed or delayed delivery, a
     * "dispatched" with no day, a day before the email or more than [MAX_AHEAD_DAYS] after it. Delivered wins, then out
     * for delivery or arriving today, then a day after an arriving/expected/due phrase (subject first, then preview).
     */
    fun read(
        messageId: String, fromName: String?, fromAddress: String?, subject: String?, preview: String?, receivedAtMs: Long, receivedDay: Long,
    ): ParcelRead? {
        val texts = listOfNotNull(subject, preview).filter { it.isNotBlank() }
        if (texts.isEmpty() || texts.any { MISSED.containsMatchIn(it) }) return null
        val all = texts.joinToString(" · ")
        val carrier = carrier(fromAddress, subject, preview)
        if (!PARCEL_WORDS.containsMatchIn(all) && carrier == null) return null
        val fromCarrier = MailSortRules.domain(fromAddress)?.let { d -> CARRIER_DOMAINS.any { (c, _) -> d == c || d.endsWith(".$c") } } == true
        val merchant = if (fromCarrier) null else MailReceiptRules.merchant(fromName, fromAddress)
        val ref = MailLinkRules.orderRef(subject) ?: MailLinkRules.orderRef(preview)
        fun made(state: ParcelState, day: Long) = ParcelRead(messageId, receivedAtMs, state, day, window(all), carrier, merchant, ref)
        // "Will be delivered tomorrow" is a promise, not a delivery.
        if (texts.any { t -> DELIVERED.containsMatchIn(t) && !PROMISE.containsMatchIn(t) }) {
            return made(ParcelState.DELIVERED, receivedDay)
        }
        if (texts.any { OUT.containsMatchIn(it) }) return made(ParcelState.OUT_FOR_DELIVERY, receivedDay)
        for (t in texts) {
            for (m in COMING.findAll(t)) {
                val after = t.substring(m.range.last + 1).take(40)
                val day = dayIn(after, receivedDay) ?: continue
                if (day < receivedDay || day - receivedDay > MAX_AHEAD_DAYS) continue
                return made(ParcelState.COMING, day)
            }
        }
        return null
    }

    /** "today" (as a word), else what [MailWeekRules.dayOf] reads. */
    private fun dayIn(text: String, receivedDay: Long): Long? {
        val lower = " " + text.lowercase().map { if (it.isLetter()) it else ' ' }.joinToString("") + " "
        val today = lower.indexOf(" today ")
        val day = MailWeekRules.dayOf(text, receivedDay)
        if (today < 0) return day
        // Whichever comes first: "today, or Fri 16 Oct at the latest" is today.
        return if (day == null) receivedDay else receivedDay.takeIf { today <= 3 } ?: day
    }

    /**
     * The parcels [reads] describe, oldest email first: an email with an order number joins the parcel with that
     * number; one without joins its shop's (else its carrier's) latest parcel not yet delivered; a newer email moves the
     * day, the window and the state on. A delivery joins the parcel it names (or the shop's latest) and is never undone
     * by an older-dated email; one that names no known parcel is dropped (nothing to clear).
     */
    fun fold(reads: List<ParcelRead>): List<ParcelFolded> {
        val out = LinkedHashMap<String, ParcelFolded>()
        for (r in reads.sortedWith(compareBy<ParcelRead> { it.receivedAtMs }.thenBy { it.messageId })) {
            val who = r.merchant ?: r.carrier
            val target = r.ref?.let { out[it] }
                ?: if (r.ref == null && who != null) out.values.lastOrNull { p ->
                    // Only a recent one: a parcel whose day went by long ago is not this email's.
                    p.state != ParcelState.DELIVERED && (p.merchant ?: p.carrier) == who && p.day + STALE_DAYS >= r.day
                } else null
            if (target == null) {
                if (r.state == ParcelState.DELIVERED) continue
                val key = r.ref ?: "m:${r.messageId}"
                out[key] = ParcelFolded(key, r.state, r.day, r.window, r.carrier, r.merchant, r.receivedAtMs)
                continue
            }
            if (target.state == ParcelState.DELIVERED) continue
            out[target.key] = target.copy(
                state = r.state,
                day = r.day,
                // A window belongs to its day: a new day without one drops the old window.
                window = r.window ?: target.window.takeIf { r.day == target.day },
                carrier = target.carrier ?: r.carrier,
                merchant = target.merchant ?: r.merchant,
                lastAtMs = r.receivedAtMs,
            )
        }
        return out.values.toList()
    }

    /** One entity per account and parcel, so each check lands on the same one. */
    fun entityId(accountId: String, key: String): String = "pc" + ActivityRules.fnv64("parcel:$accountId:$key")

    /** The entity's fields as the server writes them (every check; unchanged fields write nothing). */
    fun fields(p: ParcelFolded, account: String): Map<String, FieldValue> = linkedMapOf(
        ParcelFields.ACCOUNT to account.fv(),
        ParcelFields.STATE to p.state.name.fv(),
        ParcelFields.DAY to p.day.fv(),
        ParcelFields.WINDOW to (p.window?.fv() ?: FieldValue.Null),
        ParcelFields.CARRIER to (p.carrier?.fv() ?: FieldValue.Null),
        ParcelFields.MERCHANT to (p.merchant?.take(40)?.fv() ?: FieldValue.Null),
        ParcelFields.LAST_AT to p.lastAtMs.fv(),
        ParcelFields.TRUST to Trust.UNTRUSTED_EXTERNAL.name.fv(),
    )

    fun read(id: String, f: Map<String, FieldValue>): Parcel? {
        fun text(k: String) = (f[k] as? FieldValue.Text)?.value?.takeIf { it.isNotBlank() }
        val state = ParcelState.of(text(ParcelFields.STATE)) ?: return null
        val day = (f[ParcelFields.DAY] as? FieldValue.Int64)?.value ?: return null
        return Parcel(
            id, text(ParcelFields.ACCOUNT) ?: "Mail", state, day, text(ParcelFields.WINDOW), text(ParcelFields.CARRIER),
            text(ParcelFields.MERCHANT), (f[ParcelFields.LAST_AT] as? FieldValue.Int64)?.value ?: 0L,
        )
    }

    /**
     * Today's line, or null when nothing is due today: "Parcel arriving today · Amazon · 10:15–11:15", "Parcel out
     * for delivery · DPD", "3 parcels arriving today · Amazon, Next + 1" (" · 1 delivered" once one of today's has
     * come). Delivered parcels and ones due another day aren't counted; lit while one is out for delivery.
     *
     * Deliveries completeness: on an office day it adds "· you're at the office" and is lit, so someone can be asked to
     * be in; and a parcel due yesterday that no email has called delivered is asked about for one day ("Not arrived? ·
     * Amazon was due yesterday", or " · Amazon was due yesterday" after today's), then counts as come.
     */
    fun todayLine(parcels: List<Parcel>, todayDay: Long, officeDay: Boolean = false): ParcelLine? {
        val due = parcels.filter { it.state != ParcelState.DELIVERED && it.day == todayDay }
            .sortedWith(compareBy<Parcel> { it.state != ParcelState.OUT_FOR_DELIVERY }.thenBy { it.window ?: "~" }.thenBy { it.lastAtMs })
        val late = late(parcels, todayDay)
        val lateNames = late.map { it.name ?: "A parcel" }.distinct()
        val lateShown = lateNames.take(NAMES_SHOWN).joinToString(", ") + if (lateNames.size > NAMES_SHOWN) " + ${lateNames.size - NAMES_SHOWN}" else ""
        val lateVerb = if (late.size == 1) "was" else "were"
        val lateSpoken = if (late.isEmpty()) "" else
            (if (late.size == 1) "Your parcel" + (late.single().name?.let { " from $it" } ?: "") + " was due yesterday"
            else "${late.size} parcels from yesterday, from ${lateNames.joinToString(", ")},") +
                " and no email has said it's delivered, so check it came."
        if (due.isEmpty()) {
            if (late.isEmpty()) return null
            return ParcelLine("Not arrived? · $lateShown $lateVerb due yesterday", lateSpoken, lit = false, parcelCount = 0)
        }
        val delivered = parcels.count { it.state == ParcelState.DELIVERED && it.day == todayDay }
        val out = due.count { it.state == ParcelState.OUT_FOR_DELIVERY }
        val doneSuffix = (if (delivered > 0) " · $delivered delivered" else "") +
            (if (officeDay) " · you're at the office" else "") +
            (if (late.isNotEmpty()) " · $lateShown $lateVerb due yesterday" else "")
        val spokenOffice = if (officeDay) ", while you're at the office" else ""
        val spokenLate = if (lateSpoken.isEmpty()) "" else " $lateSpoken"
        if (due.size == 1) {
            val p = due.single()
            val head = if (p.state == ParcelState.OUT_FOR_DELIVERY) "Parcel out for delivery" else "Parcel arriving today"
            val text = listOfNotNull(head, p.name, p.window).joinToString(" · ") + doneSuffix
            val spoken = head + (p.name?.let { " from $it" } ?: "") + (p.window?.let { ", between ${it.replace("–", " and ")}" } ?: "") +
                spokenOffice + "." + spokenLate
            return ParcelLine(text, spoken, lit = p.state == ParcelState.OUT_FOR_DELIVERY || officeDay, parcelCount = 1)
        }
        val names = due.mapNotNull { it.name }.distinct()
        val shown = names.take(NAMES_SHOWN).joinToString(", ") + if (names.size > NAMES_SHOWN) " + ${names.size - NAMES_SHOWN}" else ""
        val text = "${due.size} parcels arriving today" + (if (shown.isNotEmpty()) " · $shown" else "") + doneSuffix
        val spoken = "${due.size} parcels arriving today" + (if (names.isNotEmpty()) ", from ${names.joinToString(", ")}" else "") +
            spokenOffice + "." + spokenLate
        return ParcelLine(text, spoken, lit = out > 0 || officeDay, parcelCount = due.size)
    }

    /**
     * Deliveries completeness: the parcels due yesterday that no email has called delivered (a missed "delivered" email,
     * or one that didn't come). Asked about for that one day only; after it a parcel counts as come.
     */
    fun late(parcels: List<Parcel>, todayDay: Long): List<Parcel> =
        parcels.filter { it.state != ParcelState.DELIVERED && it.day == todayDay - 1 }.sortedBy { it.lastAtMs }

    // ---- Deliveries, slice 2: the week ahead and the out-for-delivery heads-up ----

    /**
     * The week ahead's line for [day], or null when no parcel is due then: "Parcel · Amazon · 10:15–11:15", "Parcel
     * out for delivery · DPD", "2 parcels · Amazon, DPD". On an office day it adds "· you're at the office" and is lit,
     * so a parcel that may need someone in is seen in time. Delivered parcels aren't shown.
     */
    fun weekLine(parcels: List<Parcel>, day: Long, officeDay: Boolean): ParcelWeekLine? {
        val due = parcels.filter { it.state != ParcelState.DELIVERED && it.day == day }
            .sortedWith(compareBy<Parcel> { it.state != ParcelState.OUT_FOR_DELIVERY }.thenBy { it.window ?: "~" }.thenBy { it.lastAtMs })
        if (due.isEmpty()) return null
        val office = if (officeDay) " · you're at the office" else ""
        val spokenOffice = if (officeDay) ", while you're at the office" else ""
        if (due.size == 1) {
            val p = due.single()
            val head = if (p.state == ParcelState.OUT_FOR_DELIVERY) "Parcel out for delivery" else "Parcel"
            val text = listOfNotNull(head, p.name, p.window).joinToString(" · ") + office
            val spoken = (if (p.state == ParcelState.OUT_FOR_DELIVERY) "Parcel out for delivery" else "Parcel arriving") +
                (p.name?.let { " from $it" } ?: "") + (p.window?.let { ", between ${it.replace("–", " and ")}" } ?: "") + spokenOffice
            return ParcelWeekLine(text, spoken, officeDay)
        }
        val names = due.mapNotNull { it.name }.distinct()
        val shown = names.take(NAMES_SHOWN).joinToString(", ") + if (names.size > NAMES_SHOWN) " + ${names.size - NAMES_SHOWN}" else ""
        val text = "${due.size} parcels" + (if (shown.isNotEmpty()) " · $shown" else "") + office
        val spoken = "${due.size} parcels arriving" + (if (names.isNotEmpty()) ", from ${names.joinToString(", ")}" else "") + spokenOffice
        return ParcelWeekLine(text, spoken, officeDay)
    }

    /**
     * The heads-up when a shipping email says a parcel is out for delivery today ([NoticeSource.PARCELS], through the
     * governor, so quiet hours hold it): "Parcel out for delivery · Amazon" · "Between 10:15 and 11:15 · with DPD".
     * Due when that email came (never before today), one per parcel and day, standing until the day ends; none once
     * it's delivered or for another day. Opens Today. Nothing is tracked, opened or sent because of it.
     */
    fun notices(parcels: List<Parcel>, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        val start = cal.toEpochMs(today, 0)
        return parcels.filter { it.state == ParcelState.OUT_FOR_DELIVERY && it.day == today }
            .sortedBy { it.lastAtMs }
            .map { p ->
                val text = listOfNotNull(
                    p.window?.let { "Between ${it.replace("–", " and ")}" },
                    p.carrier?.takeIf { p.merchant != null }?.let { "with $it" },
                ).joinToString(" · ").ifEmpty { "Arriving today" }
                Notice(
                    key = "parcel:${p.id}:$today:out", source = NoticeSource.PARCELS, tier = NoticeTier.HEADS_UP,
                    title = "Parcel out for delivery" + (p.name?.let { " · $it" } ?: ""), text = text,
                    atMs = maxOf(p.lastAtMs, start), target = NoticeTarget.TODAY, expiresAtMs = cal.toEpochMs(today + 1, 0),
                )
            }
    }
}

/**
 * Deliveries completeness: "any parcels coming?", "where's my Amazon parcel?", "is anything being delivered today?"
 * answered on the device from the parcels the server read from shipping emails (no AI, nothing sent). The shop's and
 * carrier's names are someone else's words (ADR-006): they are only repeated, never acted on.
 */
object ParcelAskRules {
    /** Parcels said at most; the rest are counted. */
    const val MAX_SAID = 4
    /** How far ahead a parcel is said. */
    const val AHEAD_DAYS = 14

    private val NOUN = Regex("""\b(parcels?|packages?|deliver(?:y|ies|ed|ing)?|couriers?)\b""", RegexOption.IGNORE_CASE)
    private val ASKING = Regex(
        """(\?|\b(any|anything|where|when|what|which|is|are|has|have|did|coming|arriving|arrive|due|expect(?:ing)?|today|tomorrow|this week|my)\b)""",
        RegexOption.IGNORE_CASE,
    )
    /** Questions about Navilife or a scam, not his parcels. */
    private val NOT = Regex("""\b(how do i|how does|set ?up|turn (?:on|off)|scam|fake|free delivery|delivery fee|deliveroo|uber eats|just eat)\b""", RegexOption.IGNORE_CASE)

    fun isAbout(question: String): Boolean =
        NOUN.containsMatchIn(question) && ASKING.containsMatchIn(question) && !NOT.containsMatchIn(question)

    /** "today", "tomorrow", "Thu 22 Oct". */
    fun dayLabel(day: Long, today: Long): String = when (day) {
        today -> "today"
        today + 1 -> "tomorrow"
        today - 1 -> "yesterday"
        else -> "on " + CivilDate.shortLabel(day)
    }

    /**
     * The answer, or null when [question] isn't about his parcels. A shop or carrier named in the question narrows it
     * ("where's my Amazon parcel?"); otherwise every parcel on its way in the next [AHEAD_DAYS] days, out for delivery
     * first, then by day, with yesterday's that no email called delivered and today's delivered ones after.
     */
    fun answer(question: String, parcels: List<Parcel>, todayDay: Long): String? {
        if (!isAbout(question)) return null
        val q = " " + question.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("") + " "
        fun nameIn(n: String): Boolean {
            val w = n.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim()
            return w.isNotEmpty() && " $w " in q
        }
        val narrowed = parcels.filter { p -> listOfNotNull(p.merchant, p.carrier).any(::nameIn) }
        val pool = narrowed.ifEmpty { parcels }
        val who = narrowed.firstOrNull()?.let { p -> listOfNotNull(p.merchant, p.carrier).firstOrNull(::nameIn) }
        val coming = pool.filter { it.state != ParcelState.DELIVERED && it.day in todayDay..(todayDay + AHEAD_DAYS) }
            .sortedWith(compareBy<Parcel> { it.day }.thenBy { it.state != ParcelState.OUT_FOR_DELIVERY }.thenBy { it.window ?: "~" })
        val late = ParcelRules.late(pool, todayDay)
        val delivered = pool.filter { it.state == ParcelState.DELIVERED && it.day >= todayDay - 1 }.sortedByDescending { it.day }
        if (coming.isEmpty() && late.isEmpty() && delivered.isEmpty()) {
            return if (who != null) "There's no $who parcel on its way in your shipping emails from the last two weeks."
            else "No parcels on their way in your shipping emails from the last two weeks."
        }
        val out = mutableListOf<String>()
        if (coming.isNotEmpty()) {
            val said = coming.take(MAX_SAID).map { p ->
                val name = p.name ?: "A parcel"
                val win = p.window?.let { " between ${it.replace("–", " and ")}" } ?: ""
                if (p.state == ParcelState.OUT_FOR_DELIVERY) "$name is out for delivery${if (p.day == todayDay) " today" else " " + dayLabel(p.day, todayDay)}$win"
                else "$name ${dayLabel(p.day, todayDay)}$win"
            }
            val head = if (coming.size == 1) "One parcel on its way: " else "${coming.size} parcels on their way: "
            out += head + said.joinToString("; ") + (if (coming.size > MAX_SAID) "; and ${coming.size - MAX_SAID} more" else "") + "."
        }
        if (late.isNotEmpty()) {
            val names = late.map { it.name ?: "a parcel" }.distinct().joinToString(", ")
            out += (if (late.size == 1) "$names was due yesterday" else "$names were due yesterday") +
                " and no email has said it's delivered, so check it came."
        }
        if (delivered.isNotEmpty()) {
            out += "Delivered: " + delivered.take(MAX_SAID).joinToString(", ") { "${it.name ?: "a parcel"} ${dayLabel(it.day, todayDay)}" } + "."
        }
        return out.joinToString(" ")
    }
}
