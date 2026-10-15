package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * School and family logistics from email, slice 1 (build plan; non-AI, pure): **the school's emails become dates for
 * School, on his tap**. Only mail from a sender he has marked as the school — the host of About you's **School's
 * website** ("oakfield.sch.uk") or anything listed under About you's **School's email** (an address, or a domain such
 * as the school's messaging service) — is read, from the subject and the provider's preview only. A day off ("INSET day
 * on Mon 2 Nov", "school will be closed on…"), a payment ("Trip payment of £12 due by 20 Nov"), a form to send back
 * ("Please return the consent form by Fri 13 Nov"), a trip ("Year 3 trip to the Science Museum on 20 Nov") or a school
 * event (non-uniform day, parents' evening, sports day…) with a date becomes one *find* (`mail_find`, kind SCHOOL). Both
 * apps show it in Needs you with the other finds from mail: "Trip payment · £12 · due Fri 20 Nov · Alex", the account
 * and the subject, with **Add** (it goes into School as the line he'd have typed, so School's reminders, Today's line,
 * the week-ahead "who's covering?" and Kids' THIS WEEK all follow; Undo takes it off) and **Not needed**. Nothing goes
 * into School without that tap; there is no second list.
 *
 * Every word of an email is someone else's (ADR-006): a find only proposes a date he can remove; nothing is paid, signed
 * or sent because of it. Codes, passwords and newsletters' unsubscribe noise propose nothing. Rule 13: the school, its
 * senders and the children's names come from his data (About you), never from the code; tests use invented ones.
 */

/** What one school email asks of him ([SchoolMailRules.read]). */
data class SchoolMailFind(
    /** [SchoolKind.OFF] for a day off, [SchoolKind.DAY] for a one-off date (a payment, a form, a trip, an event). */
    val kind: SchoolKind,
    /** School's title: "INSET day", "Trip payment", "Consent form back", "Trip to the Science Museum", "Non-uniform day". */
    val title: String,
    /** One child named in it, else "" for all of them. */
    val who: String,
    val startDay: Long,
    val endDay: Long,
    val pence: Long?,
)

object SchoolMailRules {
    const val KIND_SCHOOL = "SCHOOL"
    /** Emails this many days back are read each check. */
    const val WINDOW_DAYS = 30
    const val WINDOW_MS = WINDOW_DAYS * 86_400_000L
    /** A date further than this after the email is a misread (next year's calendar is School's yearly look's job). */
    const val MAX_DAYS = 120
    const val TITLE = "From the school's emails"
    const val NO_NAME = "The school"
    /** Where Add's School line came from ([SchoolFields.SOURCE]). */
    const val SOURCE = "mail"
    const val SOURCE_LINE = "From the school's email · added on your tap"
    const val MAX_SENDERS = 6
    private const val MAX_TITLE = 48

    private val I = setOf(RegexOption.IGNORE_CASE)

    /** Account mail, not school logistics. */
    private val NOISE = Regex("""\b(verification|one-time|passcode|password|sign-?in\s+code|login\s+code|security\s+code|reset\s+your)\b""", I)
    /** A day with no school. */
    private val OFF = Regex(
        """\b(inset(?:\s+days?)?|(?:staff\s+)?training\s+days?|school\s+(?:will\s+be\s+|is\s+)?closed|closure|strike\s+action|strike\s+day|no\s+school)\b""", I,
    )
    /** Something to pay. */
    private val PAY = Regex("""\b(payments?|pay|paid\s+by|contribution|cost\s+of|deposit|balance\s+due|parentpay|top[\s-]?up)\b""", I)
    private val AMOUNT = Regex("""£\s?(\d{1,4})(?:\.(\d{2}))?(?!\d)""")
    /** A form to send back. */
    private val FORM = Regex("""\b(consent\s+forms?|permission\s+(?:forms?|slips?)|reply\s+slips?|forms?)\b""", I)
    private val FORM_ACTION = Regex("""\b(return|returned|sign|signed|complete|completed|send\s+back|hand\s+in|fill\s+in|submit|by|deadline|due)\b""", I)
    private val CONSENT = Regex("""\b(consent|permission)\b""", I)
    /** A trip: "trip to the Science Museum", "visit to the farm". */
    private val TRIP = Regex("""\b(?:trip|visit|outing)\s+to\s+([^.,;:!?\n(]{2,60})""", I)
    private val TRIP_WORD = Regex("""\b(trip|outing|residential|visit)\b""", I)
    /** A school event with a day: the title Add gives it. */
    private val EVENTS: List<Pair<Regex, String>> = listOf(
        """non[\s-]?uniform\s+day|own\s+clothes\s+day|wear\s+your\s+own\s+clothes""" to "Non-uniform day",
        """christmas\s+jumper\s+day""" to "Christmas jumper day",
        """world\s+book\s+day|book\s+week""" to "World Book Day",
        """parents['’]?\s+evening|parent[\s-]teacher\s+(?:meeting|consultation)s?|parent\s+consultations?""" to "Parents' evening",
        """sports\s+day""" to "Sports day",
        """school\s+photo(?:graph)?s?|class\s+photo(?:graph)?s?|photographer""" to "School photos",
        """nativity|christmas\s+(?:play|performance|concert)""" to "Christmas performance",
        """harvest\s+festival""" to "Harvest festival",
        """cake\s+sale|bake\s+sale""" to "Cake sale",
        """(?:school\s+)?disco""" to "School disco",
        """flu\s+(?:vaccinations?|vaccine|spray)""" to "Flu vaccinations",
        """open\s+(?:morning|evening|day)""" to "Open morning",
        """assembly""" to "Class assembly",
        """fair|fayre""" to "School fair",
    ).map { (re, title) -> Regex("""\b(?:$re)\b""", I) to title }
    /** Already over or called off: nothing to add. */
    private val NOT_COMING = Regex("""\b(cancell?ed|postponed\s+indefinitely|thank\s+you\s+for\s+(?:paying|your\s+payment)|payment\s+(?:received|confirmed|successful)|receipt)\b""", I)

    // ---- Who is the school ----

    /**
     * The senders he has marked as the school, lower case: the host of [AboutYou.schoolWebsite] without "www." (a
     * domain), and each entry of [AboutYou.schoolMail] (an address or a domain). Empty when neither is set, and then no
     * email is read as the school's.
     */
    fun senders(a: AboutYou): Set<String> {
        val out = linkedSetOf<String>()
        a.schoolWebsite?.let { w ->
            val host = w.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
            host.removePrefix("www.").takeIf { '.' in it }?.let(out::add)
        }
        a.schoolMail?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.forEach(out::add)
        return out
    }

    /** Whether [fromAddress] is one of [senders]: the exact address, or an address at a listed domain (or under it). */
    fun fromSchool(fromAddress: String?, senders: Set<String>): Boolean {
        if (senders.isEmpty()) return false
        val addr = fromAddress?.trim()?.trimStart('<')?.trimEnd('>')?.lowercase() ?: return false
        val domain = MailSortRules.domain(addr) ?: return false
        return senders.any { s -> if ('@' in s) s == addr else domain == s || domain.endsWith(".$s") }
    }

    /**
     * About you's **School's email** typed as "office@oakfield.sch.uk, parentmail.co.uk": addresses or domains, split by
     * commas, semicolons or spaces, each cleaned ([AboutYouRules.email] / a website's host), kept once, up to
     * [MAX_SENDERS]; stored as "office@oakfield.sch.uk, parentmail.co.uk". Null when one can't be either.
     */
    fun cleanSenders(t: String): String? {
        val parts = t.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > MAX_SENDERS) return null
        val out = linkedSetOf<String>()
        for (p in parts) {
            out += if ('@' in p) AboutYouRules.email(p) ?: return null
            else {
                val w = AboutYouRules.website(p) ?: return null
                w.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').removePrefix("www.")
                    .takeIf { '.' in it } ?: return null
            }
        }
        return out.joinToString(", ")
    }

    // ---- Reading an email ----

    /**
     * What a school email received on [receivedDay] asks of him, or null when its subject and preview don't name one
     * thing with a date on or after the email (within [MAX_DAYS]). [children] are About you's names: one named alone
     * makes the find theirs. In order: a day off, something to pay, a form to send back, a trip, a school event.
     */
    fun read(subject: String?, preview: String?, receivedDay: Long, children: List<String> = emptyList()): SchoolMailFind? {
        val texts = listOfNotNull(subject, preview).map { it.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty() || texts.any { NOISE.containsMatchIn(it) }) return null
        val all = texts.joinToString(". ")
        if (NOT_COMING.containsMatchIn(all)) return null
        val (start, end) = span(texts, receivedDay) ?: return null
        val who = who(all, children)
        val tripTo = TRIP.find(all)?.let { tripTitle(it.groupValues[1]) }
        val isTrip = tripTo != null || TRIP_WORD.containsMatchIn(all)
        val event = EVENTS.firstOrNull { it.first.containsMatchIn(all) }?.second
        val amount = AMOUNT.find(all)?.let { m ->
            val p = (m.groupValues[1].toLongOrNull() ?: return@let null) * 100 + (m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0L)
            p.takeIf { it in 1..MailReceiptRules.MAX_PENCE }
        }
        OFF.find(all)?.let { m ->
            val word = m.value.lowercase()
            val title = when {
                word.startsWith("inset") -> if (end > start) "INSET days" else "INSET day"
                "training" in word -> if (end > start) "INSET days" else "INSET day"
                "strike" in word || Regex("""\bstrike""", I).containsMatchIn(all) -> "School closed · strike"
                else -> "School closed"
            }
            return SchoolMailFind(SchoolKind.OFF, title, who, start, end, null)
        }
        val topic = when {
            isTrip -> "Trip"
            event != null -> event
            else -> null
        }
        if (PAY.containsMatchIn(all) && (amount != null || Regex("""\b(?:due|by|deadline)\b""", I).containsMatchIn(all))) {
            return SchoolMailFind(SchoolKind.DAY, "${topic ?: "School"} payment", who, start, start, amount)
        }
        if (FORM.containsMatchIn(all) && FORM_ACTION.containsMatchIn(all)) {
            val form = if (CONSENT.containsMatchIn(all)) "consent form" else "form"
            val title = if (topic != null) "$topic $form back" else form.replaceFirstChar { it.uppercase() } + " back"
            return SchoolMailFind(SchoolKind.DAY, title, who, start, start, amount)
        }
        if (isTrip) return SchoolMailFind(SchoolKind.DAY, tripTo?.let { "Trip to $it" } ?: "School trip", who, start, start, amount)
        if (event != null) return SchoolMailFind(SchoolKind.DAY, event, who, start, start, amount)
        return null
    }

    /** "the Science Museum on Fri 20 Nov" → "the Science Museum" (cut at the date words), up to [MAX_TITLE]. */
    private fun tripTitle(raw: String): String? {
        val cut = Regex("""\s+(?:on|this|next|by|for|in|from|at|is|will|was|has|we|please|-|–)\b.*$|\s+\d.*$|\s+(?:mon|tue|wed|thu|fri|sat|sun)\w*\b.*$""", I)
        val t = raw.replace(cut, "").trim().trimEnd('.', ',', ' ')
        if (t.length < 3) return null
        return if (t.length <= MAX_TITLE) t else t.take(MAX_TITLE - 1).trimEnd() + "…"
    }

    /** The one child named in [text], else "". */
    private fun who(text: String, children: List<String>): String {
        val named = children.filter { it.isNotBlank() && Regex("""\b${Regex.escape(it)}(?:'s|’s)?\b""", I).containsMatchIn(text) }
        return named.singleOrNull() ?: ""
    }

    /** The first date or range named (the subject first, then the preview), on or after [receivedDay] and within [MAX_DAYS]. */
    private fun span(texts: List<String>, receivedDay: Long): Pair<Long, Long>? {
        for (t in texts) {
            when (val f = SchoolRules.findSpan(t, receivedDay)) {
                is SpanFound.Days -> if (f.startDay >= receivedDay && f.startDay - receivedDay <= MAX_DAYS) return f.startDay to f.endDay
                else -> {}
            }
        }
        for (t in texts) {
            val d = MailWeekRules.dayOf(t, receivedDay) ?: continue
            if (d >= receivedDay && d - receivedDay <= MAX_DAYS) return d to d
        }
        return null
    }

    // ---- The find ----

    /** One find per account, thing and day, so a reminder email about the same trip lands on the same one. */
    fun findId(accountId: String, s: SchoolMailFind): String =
        "mf" + ActivityRules.fnv64("mailschool:$accountId:${s.kind.wire}:${s.title.lowercase()}:${s.who.lowercase()}:${s.startDay}")

    /** The School line Add writes: one per find, so Add on both apps lands on the same line. */
    fun schoolItemId(findId: String): String = "sm" + ActivityRules.fnv64("mailfind:$findId")

    /** The find's fields, written once by the server. */
    fun serverFields(s: SchoolMailFind, school: String?, account: String, subject: String?, receivedDay: Long): Map<String, FieldValue> = linkedMapOf(
        MailFindFields.KIND to KIND_SCHOOL.fv(),
        MailFindFields.ACCOUNT to account.fv(),
        MailFindFields.MERCHANT to (school?.fv() ?: FieldValue.Null),
        MailFindFields.DAY to s.startDay.fv(),
        MailFindFields.END_DAY to s.endDay.fv(),
        MailFindFields.PENCE to (s.pence?.fv() ?: FieldValue.Null),
        MailFindFields.WHAT to s.title.fv(),
        MailFindFields.SCHOOL_KIND to s.kind.wire.fv(),
        MailFindFields.WHO to s.who.fv(),
        MailFindFields.RECEIVED_DAY to receivedDay.fv(),
        MailFindFields.SUBJECT to (subject?.trim()?.take(MailRenewalRules.MAX_SUBJECT)?.takeIf { it.isNotEmpty() }?.fv() ?: FieldValue.Null),
        MailFindFields.TRUST to Trust.UNTRUSTED_EXTERNAL.name.fv(),
    )

    /** The School line a find stands for. */
    fun entry(f: MailFind): SchoolEntry {
        val kind = SchoolKind.of(f.schoolKind) ?: SchoolKind.DAY
        val title = f.what ?: "From the school"
        val withPrice = f.pence?.takeIf { kind == SchoolKind.DAY }?.let { "$title · ${RenewalRules.formatPence(it)}" } ?: title
        return SchoolEntry(kind, withPrice.take(SchoolRules.MAX_TITLE), f.who.orEmpty(), f.renewDay, maxOf(f.renewDay, f.endDay ?: f.renewDay))
    }

    /** What Add writes into School: the line, marked as from the school's email. */
    fun schoolFields(f: MailFind, nowMs: Long): Map<String, FieldValue> =
        SchoolRules.fields(entry(f), nowMs) + mapOf(SchoolFields.SOURCE to SOURCE.fv(), SchoolFields.DELETED to false.fv())

    fun title(f: MailFind): String = f.merchant ?: NO_NAME

    /** "Trip payment · £12 · due Fri 20 Nov · Alex", "INSET day · Mon 2 Nov · no school · everyone". */
    fun line(f: MailFind, today: Long): String {
        val e = entry(f)
        val who = SchoolRules.whoLabel(e.who).takeIf { it.isNotBlank() }
        val what = f.what ?: "From the school"
        return when (e.kind) {
            SchoolKind.OFF -> listOfNotNull(what, SchoolRules.spanLabel(e.startDay, e.endDay), "no school", who).joinToString(" · ")
            else -> listOfNotNull(
                what, f.pence?.let(RenewalRules::formatPence),
                (if (SchoolRules.isDue(what)) "due " else "") + RenewalRules.dayWord(e.startDay, today), who,
            ).joinToString(" · ")
        }
    }

    fun row(f: MailFind, today: Long): MailFindRow {
        val title = title(f)
        val line = line(f, today)
        val what = f.what ?: "it"
        return MailFindRow(
            id = f.id, title = title, line = line, from = MailRenewalRules.fromLine(f),
            business = f.account == MailLabel.BUSINESS.label,
            spoken = "$title. $line. From your ${f.account} mail.",
            addSpoken = "Add $what to School",
            notNeededSpoken = "$what isn't needed in School",
        )
    }

    /**
     * Whether [f] is an open school find to show: not answered, not over, and not already in School (Add's own line, or
     * one of his of the same kind on an overlapping day with the same title — any day off overlapping, for a day off).
     */
    fun showable(f: MailFind, items: List<SchoolItem>, today: Long): Boolean {
        if (f.kind != KIND_SCHOOL || f.state != MailFindState.OPEN) return false
        val e = entry(f)
        if (e.endDay < today) return false
        val sid = schoolItemId(f.id)
        return items.none { it.id == sid || sameThing(it, e) }
    }

    private fun sameThing(it: SchoolItem, e: SchoolEntry): Boolean {
        if (it.kind != e.kind || it.endDay < e.startDay || it.startDay > e.endDay) return false
        if (e.who.isNotEmpty() && it.who.isNotEmpty() && it.who != e.who) return false
        if (e.kind == SchoolKind.OFF) return true
        val a = it.title.lowercase()
        val b = e.title.substringBefore(" · ").lowercase()
        return a == b || a.startsWith(b) || b.startsWith(a)
    }

    /** The bar's line after Add: "In School: Trip payment · due Fri 20 Nov · reminds you Wed 18 Nov, 09:00". */
    fun addedLine(f: MailFind, today: Long): String {
        val e = entry(f)
        val what = f.what ?: "it"
        val day = when (e.kind) {
            SchoolKind.OFF -> SchoolRules.spanLabel(e.startDay, e.endDay)
            else -> (if (SchoolRules.isDue(what)) "due " else "") + RenewalRules.dayWord(e.startDay, today)
        }
        val item = SchoolItem("", e.kind, e.title, e.who, e.startDay, e.endDay, 0, null, emptyList(), 0L)
        val note = SchoolRules.reminderNote(item, today)?.let { " · " + it.replaceFirstChar { c -> c.lowercase() }.replace("reminder", "reminds you") }
        return "In School: $what · $day" + (note ?: "")
    }
}
