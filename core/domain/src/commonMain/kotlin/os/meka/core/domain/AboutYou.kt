package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica

/**
 * About you (build plan "move every personal detail out of the code into Meka's data", rule 13), slice 1: one synced
 * **household profile** — the name MEKA calls him, his partner (name, number, whether MEKA watches for her requests),
 * the children (name, club age group, school), home and work (place names; the server finds the coordinates, as it
 * does for the weather), the call assistant's number and his voicemail number. Edited in Ask → Settings → About you on
 * both apps; Setup's "About you" step asks for it when it's empty. The repository is public, so the code never holds
 * these: they are typed on a device or, once, copied from what MEKA already had (below).
 *
 * Stored as one `household_profile` entity, id [AboutYouRules.ENTITY_ID] (ADR-008 addendum 2026-10-13, additive): the
 * [AboutYouFields], all LWW, either app writes; an empty text field is cleared with Null.
 *
 * **The move, release 1** (this slice): the Fold copies today's values into the profile once ([AboutYouStore.seedOnce]),
 * after its first successful sync finds no profile, so a profile typed on the Mac, or seeded before a reinstall, is
 * never written over. Release 2 (a later slice) makes every feature read the profile and deletes the values from the
 * code. Blocked numbers are already his data (the synced block list), so they aren't copied here. Non-AI, pure.
 */
object AboutYouFields {
    const val SHOWN_NAME = "shownName"
    const val PARTNER_NAME = "partnerName"
    const val PARTNER_PHONE = "partnerPhone"
    /**
     * The partner's email address (Inbox autopilot, slice 3b-2b, 2026-10-13): mail from it is never tidied away
     * ([AboutYouRules.keepAddresses] → [MailTidyFacts.keepAddresses], read by the server's autopilot).
     */
    const val PARTNER_EMAIL = "partnerEmail"
    /** Bool: MEKA reads her messages for requests (absent = on once a partner is named). */
    const val PARTNER_WATCH = "partnerWatch"
    /** One child per line, "name|ageGroup|school" (age group a number or empty, school possibly empty). */
    const val CHILDREN = "children"
    const val HOME = "home"
    const val WORK = "work"
    /**
     * The children's clubs (release 2 slice 1, 2026-10-15): "OFC Oakfield Town, HRFC" — each a short name as calendar
     * titles write it, then optionally the full name said aloud ([AboutYouRules.clubList]). Club fixtures are spotted
     * by these ([FootballRules.CLUBS], via [Household]).
     */
    const val CLUBS = "clubs"
    /**
     * The children's school's website (School dates filled in, slice 3, 2026-10-14): School's INSET card opens it, as
     * INSET days are the school's own and are published there. A normalised https URL.
     */
    const val SCHOOL_WEBSITE = "schoolWebsite"
    /**
     * The school's email senders (School and family logistics from email, slice 1, 2026-10-15): addresses or domains,
     * "office@oakfield.sch.uk, parentmail.co.uk" ([SchoolMailRules.cleanSenders]). Mail from these (or from the
     * website's own domain) is read for school dates; nothing else is.
     */
    const val SCHOOL_MAIL = "schoolMail"
    const val ASSISTANT_NUMBER = "assistantNumber"
    /**
     * His own mobile number (item (c) slice 2, 2026-10-17): the call assistant rings it to put a genuine urgent caller
     * through ([PutThroughRules]); empty = urgent callers leave a message as before. Typed on a device only (rule 13).
     */
    const val MOBILE = "mobile"
    /**
     * The school's council's holidays (release 2 slice 5, 2026-10-15): the table the School pane fills in from, as
     * [SchoolTerms.encode] writes it. Not typed: the Fold copied the one Navilife carried once
     * ([AboutYouStore.seedCouncilOnce]), so no council is named in the code.
     */
    const val COUNCIL_DATES = "councilDates"
    const val VOICEMAIL_NUMBER = "voicemailNumber"
    /**
     * The scam call fix, slice 4 (2026-10-22): official switchboards scammers copy, as he typed them ("police 01632
     * 960000, bank 0306 999 0000", [SpoofHintRules.cleanTyped]). A stranger's call from the same exchange gets "Could
     * be spoofed" on the in-call warning. His local force and bank stay in his data, never in the code (rule 13).
     */
    const val OFFICIAL_NUMBERS = "officialNumbers"
    /**
     * The commute's train route (release 2 slice 6, 2026-10-15): "South Rail to Oakfield Junction, then Metro; or North
     * Rail to Riverside" ([RouteRules.parse]). Today's train line, the heads-ups, Leaving now's allowance, Ask and the
     * server's TfL questions read it; the Fold copies the route the code knew into it once.
     */
    const val ROUTE = "commuteRoute"
    /**
     * Live trains (Needs Meka #16, 2026-10-17): the station he catches the train from at home ("Oakfield"), as typed.
     * The server finds its National Rail code and reads the live departures from it to where he changes (the route's
     * change station), and back in the evening. Never in the code (rule 13).
     */
    const val HOME_STATION = "homeStation"
    /**
     * Live trains, slice 3 (2026-10-17): how long it takes him to reach the train, from the door at home and from his
     * desk at work, kept as "10 min from home · 15 min from work" ([AboutYouRules.walk]). The leave-by times follow it:
     * the live train he can still catch, less this. Absent = no leave-by (never guessed).
     */
    const val STATION_WALK = "stationWalk"
    /** Int64: when the Fold copied MEKA's earlier values in (release 1); absent when he typed it all. */
    const val SEEDED_AT = "seededAtMs"
    /** Int64: when the Fold first held a filled-in profile after a clean sync (slice 2, release 2's gate). */
    const val HELD_FOLD = "heldFoldAtMs"
    /** Int64: the same for the Mac. */
    const val HELD_MAC = "heldMacAtMs"
    /**
     * Int64: when the orb's hello asked what to call him (one name everywhere, 2026-10-21), so it asks once across
     * both apps ([CallNameRules.asks]). Not a typed line.
     */
    const val NAME_ASKED_AT = "nameAskedAtMs"
    /**
     * Text: the orb's last openers said on either app, newest first ("e3,m1,…", [HelloOpeners.encodeIds]), so the Fold
     * and the Mac don't echo each other (hello slice 2, 2026-10-21). Not a typed line.
     */
    const val HELLO_RECENT = "helloRecent"
}

/** Which app this core runs in, for [AboutYouStore.markHeld]. */
enum class AboutYouDevice { FOLD, MAC }

/**
 * Whether each app has the profile (slice 2): [filled] when anything is in it, and when the Fold and the Mac each
 * first held it after a clean sync. Release 2 (deleting the old values from the code) waits until both have it.
 */
data class AboutYouHeld(
    val filled: Boolean,
    val foldAtMs: Long?,
    val macAtMs: Long?,
    /**
     * Release 2's last one-off copies (slice 5, 2026-10-15): whether the profile holds the clubs and the council's
     * holidays, so a build run knows when the code's copies can go. Null when not known (an older server).
     */
    val clubs: Boolean? = null,
    val council: Boolean? = null,
    /** Release 2 slice 6: whether the profile holds the train route. Null when not known (an older server). */
    val route: Boolean? = null,
) {
    val onBoth: Boolean get() = filled && foldAtMs != null && macAtMs != null
}

/** A club as About you holds it: the [short] name calendar titles use ("OFC") and its full [name] said aloud. */
data class AboutClub(val short: String, val name: String? = null)

/** A child as About you holds them: "Alex" · under 7s · "Oakfield School". */
data class AboutChild(val name: String, val ageGroup: Int? = null, val school: String? = null)

/** The household profile, as stored. Every field is optional. */
data class AboutYou(
    val shownName: String? = null,
    val partnerName: String? = null,
    val partnerPhone: String? = null,
    val partnerWatch: Boolean = true,
    val partnerEmail: String? = null,
    val children: List<AboutChild> = emptyList(),
    val home: String? = null,
    val work: String? = null,
    val schoolWebsite: String? = null,
    /** The school's email senders as kept ([SchoolMailRules.senders] reads them). */
    val schoolMail: String? = null,
    /** The clubs as kept: "OFC Oakfield Town, HRFC" ([AboutYouRules.clubList] reads it). */
    val clubs: String? = null,
    val assistantNumber: String? = null,
    /** His own mobile, for putting urgent callers through ([AboutYouFields.MOBILE]). */
    val mobile: String? = null,
    val voicemailNumber: String? = null,
    /** Official switchboards scammers copy, as kept ([AboutYouFields.OFFICIAL_NUMBERS]). */
    val officialNumbers: String? = null,
    /** The train route as kept ([RouteRules.parse] reads it). */
    val route: String? = null,
    /** Live trains: the home station as typed ("Oakfield"). */
    val homeStation: String? = null,
    /** Live trains, slice 3: the time to the train as kept ("10 min from home · 15 min from work"). */
    val stationWalk: String? = null,
    val seededAtMs: Long? = null,
    /** The school's council's holidays ([AboutYouFields.COUNCIL_DATES]); not a typed line, so not part of [isEmpty]. */
    val councilDates: TermCalendar? = null,
) {
    /** Nothing at all filled in (Setup's step and the pane's welcome line). */
    val isEmpty: Boolean
        get() = listOf(shownName, partnerName, partnerPhone, partnerEmail, home, work, schoolWebsite, schoolMail, clubs, mobile, assistantNumber, voicemailNumber, officialNumbers, route, homeStation, stationWalk).all { it == null } &&
            children.isEmpty()

    companion object {
        val EMPTY = AboutYou()
    }
}

/**
 * The fields About you edits one line at a time, in the pane's order. [hint] is what the empty field shows: one short
 * example that fits the cover screen's field on one line (the quality gate's rule for hints, slice 25); [spokenHint]
 * is the fuller how-to TalkBack and the Mac's label read ("Train route, for example …").
 */
enum class AboutField(val key: String, val label: String, val hint: String, val emptyLine: String, val spokenHint: String = "$label, $hint") {
    SHOWN_NAME(AboutYouFields.SHOWN_NAME, "Your name", "What Ada calls you", "Ada says “you” until you add it"),
    PARTNER_NAME(AboutYouFields.PARTNER_NAME, "Partner", "Their first name", "Add your partner to get their requests flagged"),
    PARTNER_PHONE(AboutYouFields.PARTNER_PHONE, "Partner's number", "Their mobile number", "Add their number so their calls always ring"),
    PARTNER_EMAIL(AboutYouFields.PARTNER_EMAIL, "Partner's email", "Their email address", "Add it so Inbox autopilot never tidies their emails away"),
    HOME(AboutYouFields.HOME, "Home", "Your town", "Add home for its weather and the commute"),
    WORK(AboutYouFields.WORK, "Work", "Where the office is", "Add work for office-day weather"),
    ROUTE(AboutYouFields.ROUTE, "Train route", "South Rail, then Metro",
        "Add your train route so Today, the brief and Leaving now say how its lines are running",
        "Train route, for example South Rail, then Metro, or with where you change and the line you take instead: South Rail to Oakfield Junction, then Metro; or North Rail to Riverside"),
    HOME_STATION(AboutYouFields.HOME_STATION, "Home station", "Oakfield",
        "Add your home station for live departures on Today",
        "Home station, the station you catch the train from, for example Oakfield"),
    STATION_WALK(AboutYouFields.STATION_WALK, "Time to the train", "10, 15",
        "Add it so Today says when to leave for the next good train",
        "Time to the train, minutes from home to the train, then from work, for example 10, 15"),
    SCHOOL_WEBSITE(AboutYouFields.SCHOOL_WEBSITE, "School's website", "oakfield.sch.uk", "Add it to open the school's INSET days from School",
        "School's website, the school's web address, for example oakfield.sch.uk"),
    SCHOOL_MAIL(AboutYouFields.SCHOOL_MAIL, "School's email", "office@oakfield.sch.uk",
        "Add the addresses the school writes from so its trips, payments and days off are offered for School",
        "School's email, the addresses the school writes from, with commas, for example office@oakfield.sch.uk, parentmail.co.uk"),
    CLUBS(AboutYouFields.CLUBS, "Clubs", "OFC Oakfield Town, HRFC", "Add the children's clubs so their fixtures get kit reminders and their names",
        "Clubs, each club's short name first, for example OFC Oakfield Town, HRFC"),
    MOBILE(AboutYouFields.MOBILE, "Your mobile", "Your own mobile number", "Add it so the call assistant can put a genuine urgent caller through to you"),
    ASSISTANT_NUMBER(AboutYouFields.ASSISTANT_NUMBER, "Call assistant's number", "The number callers reach", "Add it once the call assistant has a number"),
    VOICEMAIL_NUMBER(AboutYouFields.VOICEMAIL_NUMBER, "Voicemail number", "Your network's voicemail", "Not added · only needed for call forwarding"),
    OFFICIAL_NUMBERS(AboutYouFields.OFFICIAL_NUMBERS, "Official numbers", "police 01632 960000",
        "Add them so a call faking one gets “Could be spoofed” on its warning",
        "Official numbers, your police force's and bank's switchboards, for example police 01632 960000, bank 0306 999 0000"),
}

/** One row of the pane: the label, what's held (null when empty) and the line under it. */
data class AboutRow(val field: AboutField, val label: String, val value: String?, val hint: String, val line: String, val spokenHint: String = "$label, $hint")

/** A child's row: "Alex" and "Under 7s · Oakfield School". */
data class AboutChildRow(val index: Int, val name: String, val line: String, val spoken: String)

/**
 * The About you pane. [summary] says where the details came from; [rows] are the one-line fields; [children] and
 * [childHint] the children; [watchLine] what the partner switch means (null with no partner named).
 */
data class AboutYouView(
    val summary: String,
    val rows: List<AboutRow>,
    val children: List<AboutChildRow>,
    val childHint: String,
    val childrenEmpty: String?,
    val partnerWatch: Boolean,
    val watchLine: String?,
    val isEmpty: Boolean,
    /** One name everywhere: what Today, the orb and the brief call him ([CallNameRules.called]); null when empty. */
    val callName: String? = null,
    /** The add-a-child field's fuller how-to for TalkBack and the Mac's label ([AboutYouRules.CHILD_DESCRIPTION]). */
    val childDescription: String = AboutYouRules.CHILD_DESCRIPTION,
) {
    companion object {
        val EMPTY: AboutYouView = AboutYouRules.view(AboutYou.EMPTY, LocalCalendar.UTC, 0L)
    }
}

/**
 * What a save said: [line] under the field ("Saved", "Cleared · Undo puts it back", or why it was refused, [refused]);
 * [undo] is the text Undo writes back (the value before a clear), null when there's nothing to undo.
 */
data class AboutSaved(val line: String, val refused: Boolean = false, val undo: String? = null)

/** Non-AI, pure. */
object AboutYouRules {
    const val ENTITY_ID = "me"
    const val TITLE = "About you"
    const val ROW_LINE = "Names, home and work · kept in your data, not in Navilife's code"
    const val MAX_NAME = 30
    const val MAX_PLACE = 60
    const val MAX_SCHOOL = 60
    const val MAX_CHILDREN = 8
    const val CHILD_HINT = "Alex, U7, Oakfield School"
    const val CHILD_DESCRIPTION = "Add a child: their name, age group and school, for example Alex, U7, Oakfield School"
    const val NO_CHILDREN = "No children added · add each one to name their clubs and school days"
    const val WELCOME = "Tell Navilife who's who once. It stays in your own data (synced, encrypted), never in Navilife's code."
    const val TYPED = "Kept in your own data (synced, encrypted) on both apps. Change anything here."
    const val SAVED = "Saved"
    const val NOT_NAME = "A name is letters, spaces, hyphens and apostrophes, up to 30."
    const val NOT_NUMBER = "That doesn't look like a phone number."
    const val NOT_PLACE = "Type a town or area, up to 60 letters."
    const val NOT_EMAIL = "That doesn't look like an email address."
    const val NOT_WEBSITE = "That doesn't look like a web address: “oakfield.sch.uk” or a link from the school's site."
    const val MAX_WEBSITE = 300
    const val NOT_SCHOOL_MAIL = "Type the school's email addresses or their domain, up to 6, with commas: “office@oakfield.sch.uk, parentmail.co.uk”."
    const val NOT_CLUBS = "Type each club's short name first, as your calendar writes it: “OFC Oakfield Town, HRFC”."
    const val NOT_ROUTE = "Type the line from home, where you change and the next line: “South Rail to Oakfield Junction, then Metro”, and after a semicolon the line you take instead: “; or North Rail to Riverside”."
    const val NOT_WALK = "Type the minutes it takes you to reach the train from home, then from work, each up to 90: “10, 15” or “10”."
    const val MAX_WALK = 90
    const val NOT_OFFICIAL = "Type each number with a word for whose it is, up to 8, with commas: “police 01632 960000, bank 0306 999 0000”."
    const val NOT_STATION = "Type the station's name as the station sign has it, up to 40 letters: “Oakfield”."
    const val MAX_CLUBS = 6
    const val MAX_CLUB_SHORT = 10
    const val MAX_CLUB_NAME = 40
    const val MAX_EMAIL = 254
    const val NOT_CHILD = "Type a name first: “Alex”, “Alex, U7” or “Alex, U7, Oakfield School”."
    const val TOO_MANY = "That's the most children About you holds."
    const val WATCH_ON = "On · Navilife flags what they ask you for in messages"
    const val WATCH_OFF = "Off · their messages are treated like anyone's"

    // ---- Slice 2: whether each app has it (release 2's gate) ----

    /** What [fields] say about who holds the profile; null when there is no profile at all. */
    fun held(fields: Map<String, FieldValue>?): AboutYouHeld? {
        if (fields == null) return null
        return AboutYouHeld(
            filled = !read(fields).isEmpty,
            foldAtMs = fields[AboutYouFields.HELD_FOLD]?.longOrNull,
            macAtMs = fields[AboutYouFields.HELD_MAC]?.longOrNull,
            clubs = AboutYouFields.CLUBS in fields,
            council = read(fields).councilDates != null,
            route = AboutYouFields.ROUTE in fields,
        )
    }

    /** The field [device] writes once it holds a filled-in profile. */
    fun heldField(device: AboutYouDevice): String = if (device == AboutYouDevice.MAC) AboutYouFields.HELD_MAC else AboutYouFields.HELD_FOLD

    /**
     * Health's About you row: OK once both apps have it; amber while one hasn't (saying which, and to open MEKA there)
     * or while it's empty; "checking" until a clean sync has confirmed it on this device. Null before any profile
     * exists (Setup's step asks for it then).
     */
    fun healthRow(h: AboutYouHeld?, nowMs: Long, cal: LocalCalendar): HealthRow? {
        h ?: return null
        val fold = h.foldAtMs
        val mac = h.macAtMs
        fun since(ms: Long) = HealthRules.at(ms, nowMs, cal).let { if (it == "just now") "" else " since $it" }
        return when {
            !h.filled -> HealthRow(HEALTH_KEY, TITLE, "Nothing in it yet · fill it in under Ask → Settings → About you", HealthState.WARN)
            fold != null && mac != null -> HealthRow(HEALTH_KEY, TITLE, "On the Fold and the Mac · kept in your data", HealthState.OK)
            fold != null -> HealthRow(HEALTH_KEY, TITLE,
                "On the Fold${since(fold)} · not on the Mac yet · open Navilife on the Mac", HealthState.WARN)
            mac != null -> HealthRow(HEALTH_KEY, TITLE,
                "On the Mac${since(mac)} · not on the Fold yet · open Navilife on the Fold", HealthState.WARN)
            else -> HealthRow(HEALTH_KEY, TITLE, "Filled in · waiting for a sync to confirm it", HealthState.UNKNOWN)
        }
    }

    /** The publish job's line for a build run (no values, only who has it and since when, UTC). */
    fun gateLine(h: AboutYouHeld?, utc: (Long) -> String): String = when {
        h == null -> "no profile yet (release 1's copy hasn't run)"
        !h.filled -> "the profile is empty"
        else -> listOf(
            h.foldAtMs?.let { "on the Fold since ${utc(it)}" } ?: "NOT on the Fold yet",
            h.macAtMs?.let { "on the Mac since ${utc(it)}" } ?: "NOT on the Mac yet",
            if (h.onBoth) "release 2 can start" else "release 2 waits",
            h.clubs?.let { if (it) "clubs copied" else "clubs NOT copied yet" },
            h.council?.let { if (it) "council dates copied" else "council dates NOT copied yet" },
            h.route?.let { if (it) "route copied" else "route NOT copied yet" },
        ).filterNotNull().joinToString(" · ")
    }

    const val HEALTH_KEY = "about"

    // ---- Reading and writing ----

    fun read(fields: Map<String, FieldValue>?): AboutYou {
        if (fields == null) return AboutYou.EMPTY
        fun text(k: String) = fields[k]?.textOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return AboutYou(
            shownName = text(AboutYouFields.SHOWN_NAME),
            partnerName = text(AboutYouFields.PARTNER_NAME),
            partnerPhone = text(AboutYouFields.PARTNER_PHONE),
            partnerWatch = fields[AboutYouFields.PARTNER_WATCH]?.boolOrNull ?: true,
            partnerEmail = text(AboutYouFields.PARTNER_EMAIL),
            children = decodeChildren(fields[AboutYouFields.CHILDREN]?.textOrNull),
            home = text(AboutYouFields.HOME),
            work = text(AboutYouFields.WORK),
            schoolWebsite = text(AboutYouFields.SCHOOL_WEBSITE)?.let(::website),
            schoolMail = text(AboutYouFields.SCHOOL_MAIL)?.let(SchoolMailRules::cleanSenders),
            clubs = text(AboutYouFields.CLUBS)?.let(::clubs),
            assistantNumber = text(AboutYouFields.ASSISTANT_NUMBER),
            mobile = text(AboutYouFields.MOBILE),
            voicemailNumber = text(AboutYouFields.VOICEMAIL_NUMBER),
            officialNumbers = text(AboutYouFields.OFFICIAL_NUMBERS)?.let(SpoofHintRules::cleanTyped),
            route = text(AboutYouFields.ROUTE)?.let { RouteRules.parse(it)?.text },
            homeStation = text(AboutYouFields.HOME_STATION)?.let(RouteRules::stationName),
            stationWalk = text(AboutYouFields.STATION_WALK)?.let(::walkText),
            seededAtMs = fields[AboutYouFields.SEEDED_AT]?.longOrNull,
            councilDates = SchoolTerms.decode(fields[AboutYouFields.COUNCIL_DATES]?.textOrNull),
        )
    }

    fun value(a: AboutYou, f: AboutField): String? = when (f) {
        AboutField.SHOWN_NAME -> a.shownName
        AboutField.PARTNER_NAME -> a.partnerName
        AboutField.PARTNER_PHONE -> a.partnerPhone
        AboutField.PARTNER_EMAIL -> a.partnerEmail
        AboutField.HOME -> a.home
        AboutField.WORK -> a.work
        AboutField.SCHOOL_WEBSITE -> a.schoolWebsite
        AboutField.SCHOOL_MAIL -> a.schoolMail
        AboutField.CLUBS -> a.clubs
        AboutField.ASSISTANT_NUMBER -> a.assistantNumber
        AboutField.MOBILE -> a.mobile
        AboutField.VOICEMAIL_NUMBER -> a.voicemailNumber
        AboutField.OFFICIAL_NUMBERS -> a.officialNumbers
        AboutField.ROUTE -> a.route
        AboutField.HOME_STATION -> a.homeStation
        AboutField.STATION_WALK -> a.stationWalk
    }

    /**
     * What [typed] becomes for [f]: the cleaned value, "" to clear it (blank typed), or null when it can't be one
     * (a name with digits, a number with letters, a place with odd characters).
     */
    fun clean(f: AboutField, typed: String): String? {
        val t = collapse(typed)
        if (t.isEmpty()) return ""
        return when (f) {
            AboutField.SHOWN_NAME, AboutField.PARTNER_NAME -> name(t)
            AboutField.PARTNER_PHONE, AboutField.MOBILE, AboutField.ASSISTANT_NUMBER, AboutField.VOICEMAIL_NUMBER -> phone(t)
            AboutField.PARTNER_EMAIL -> email(t)
            AboutField.HOME, AboutField.WORK -> place(t)
            AboutField.SCHOOL_WEBSITE -> website(t)
            AboutField.SCHOOL_MAIL -> SchoolMailRules.cleanSenders(t)
            AboutField.CLUBS -> clubs(t)
            AboutField.ROUTE -> RouteRules.parse(t)?.text
            AboutField.HOME_STATION -> RouteRules.stationName(t)
            AboutField.STATION_WALK -> walkText(t)
            AboutField.OFFICIAL_NUMBERS -> SpoofHintRules.cleanTyped(t)
        }
    }

    fun refusal(f: AboutField): String = when (f) {
        AboutField.SHOWN_NAME, AboutField.PARTNER_NAME -> NOT_NAME
        AboutField.PARTNER_PHONE, AboutField.MOBILE, AboutField.ASSISTANT_NUMBER, AboutField.VOICEMAIL_NUMBER -> NOT_NUMBER
        AboutField.PARTNER_EMAIL -> NOT_EMAIL
        AboutField.HOME, AboutField.WORK -> NOT_PLACE
        AboutField.SCHOOL_WEBSITE -> NOT_WEBSITE
        AboutField.SCHOOL_MAIL -> NOT_SCHOOL_MAIL
        AboutField.CLUBS -> NOT_CLUBS
        AboutField.ROUTE -> NOT_ROUTE
        AboutField.HOME_STATION -> NOT_STATION
        AboutField.STATION_WALK -> NOT_WALK
        AboutField.OFFICIAL_NUMBERS -> NOT_OFFICIAL
    }

    // ---- Live trains, slice 3: the time to the train ----

    /**
     * The minutes to the train from home and from work, read from what he typed: "10, 15", "10 min from home, 15 from
     * work", "work 15 home 10" (named the other way round), or one number for both ("10"). Each 1–[MAX_WALK]; null when
     * it can't be read (no number, more than two, a word other than min/mins/minutes/from/home/work/and). No regex.
     */
    fun walk(t: String?): Pair<Int, Int>? {
        val s = t?.lowercase() ?: return null
        val nums = mutableListOf<Int>()
        val words = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() -> { var j = i; while (j < s.length && s[j].isDigit()) j++; nums += s.substring(i, j).take(3).toInt(); words += "#"; i = j }
                c.isLetter() -> { var j = i; while (j < s.length && s[j].isLetter()) j++; words += s.substring(i, j); i = j }
                c == ' ' || c == ',' || c == '·' || c == ';' || c == '/' || c == '-' || c == '.' -> i++
                else -> return null
            }
        }
        if (words.any { it != "#" && it !in WALK_WORDS }) return null
        if (nums.size !in 1..2 || nums.any { it !in 1..MAX_WALK }) return null
        if (nums.size == 1) return nums[0] to nums[0]
        val home = words.indexOf("home")
        val work = words.indexOf("work")
        return if (home >= 0 && work >= 0 && work < home) nums[1] to nums[0] else nums[0] to nums[1]
    }

    private val WALK_WORDS = setOf("min", "mins", "minute", "minutes", "m", "from", "home", "work", "and", "the", "to", "train", "station", "office")

    /** As kept: "10 min from home · 15 min from work"; null when [walk] can't read it. */
    fun walkText(t: String): String? = walk(t)?.let { (h, w) -> "$h min from home · $w min from work" }

    /** Spaces collapsed and trimmed; no regex (Kotlin/Native reads some differently). */
    internal fun collapse(s: String): String {
        val out = StringBuilder()
        var space = false
        for (c in s.trim()) {
            if (c.isWhitespace()) { space = true; continue }
            if (space && out.isNotEmpty()) out.append(' ')
            space = false
            out.append(c)
        }
        return out.toString()
    }

    /** "alex" stays as typed; letters, spaces, hyphens and apostrophes, up to [MAX_NAME]. */
    internal fun name(t: String): String? =
        t.takeIf { it.length <= MAX_NAME && it.any(Char::isLetter) && it.all { c -> c.isLetter() || c == ' ' || c == '-' || c == '\'' || c == '’' } }

    /** A phone number shown the UK way when it is one ([BlockedCallerRules.display]); 6–15 digits, "+-() ." allowed. */
    internal fun phone(t: String): String? {
        val digits = t.count { it.isDigit() }
        if (digits < 6 || digits > 15 || t.any { !it.isDigit() && it !in "+-() ." }) return null
        return BlockedCallerRules.display(t)
    }

    /**
     * An email address, lower-cased: one @, something before it, a dotted domain after it (no spaces, no "..", no
     * leading or trailing dot), up to [MAX_EMAIL]; a pasted "<a@b.com>" or "mailto:a@b.com" is unwrapped. No regex.
     */
    internal fun email(t: String): String? {
        val s = t.trim().removePrefix("mailto:").trim().trimStart('<').trimEnd('>').trim().lowercase()
        if (s.isEmpty() || s.length > MAX_EMAIL || s.any { it.isWhitespace() || it in "<>(),;:\\\"[]" }) return null
        val at = s.indexOf('@')
        if (at <= 0 || at != s.lastIndexOf('@')) return null
        val local = s.substring(0, at)
        val domain = s.substring(at + 1)
        if (local.startsWith('.') || local.endsWith('.') || ".." in local) return null
        val labels = domain.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() || it.startsWith('-') || it.endsWith('-') }) return null
        if (!labels.all { l -> l.all { it.isLetterOrDigit() || it == '-' } } || labels.last().length < 2) return null
        return s
    }

    /**
     * A website as https: "oakfield.sch.uk", "www.Oakfield.sch.uk/term-dates/", "http://…" or a pasted "<https://…>";
     * the host lower-cased (labels of letters, digits and hyphens, at least one dot, a top level of 2+ letters), a
     * trailing "/" on a bare host dropped, an "http://" kept as typed; no spaces, quotes or angle brackets, up to
     * [MAX_WEBSITE]. Null when it can't be one (an email address, "ftp:", "javascript:"). No regex.
     */
    internal fun website(t: String): String? {
        var s = t.trim().trimStart('<').trimEnd('>').trim()
        if (s.isEmpty() || s.length > MAX_WEBSITE || s.any { it.isWhitespace() || it in "\"'<>`{}|\\^" }) return null
        val lower = s.lowercase()
        val scheme = when {
            lower.startsWith("https://") -> "https://"
            lower.startsWith("http://") -> "http://"
            "://" in lower || lower.startsWith("javascript:") || lower.startsWith("mailto:") -> return null
            else -> "https://"
        }
        s = if (lower.startsWith(scheme)) s.substring(scheme.length) else s
        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val host = (if (cut < 0) s else s.substring(0, cut)).lowercase()
        var rest = if (cut < 0) "" else s.substring(cut)
        if ('@' in host || ':' in host) return null
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() || it.startsWith('-') || it.endsWith('-') }) return null
        if (!labels.all { l -> l.all { it.isLetterOrDigit() || it == '-' } }) return null
        if (labels.last().length < 2 || !labels.last().all(Char::isLetter)) return null
        if (rest == "/") rest = ""
        return scheme + host + rest
    }

    /**
     * The addresses Inbox autopilot never tidies away (slice 3b-2b): the partner's email from About you, lower case;
     * later his contacts too. Empty when none is set.
     */
    fun keepAddresses(a: AboutYou): Set<String> = setOfNotNull(a.partnerEmail?.let(::email))

    /** [keepAddresses] straight from the stored profile (the server's autopilot). */
    fun keepAddresses(fields: Map<String, FieldValue>?): Set<String> = keepAddresses(read(fields))

    // ---- Clubs (release 2 slice 1) ----

    /**
     * The clubs typed as "OFC Oakfield Town, HRFC" (commas or semicolons between clubs; "OFC = Oakfield Town" and
     * "OFC: Oakfield Town" too): each club's first word is its short name (letters and digits, 2–[MAX_CLUB_SHORT], a
     * letter at least, upper-cased), the rest its full name ([place]'s characters, up to [MAX_CLUB_NAME]); a repeated
     * short name is kept once, up to [MAX_CLUBS]. Kept as "OFC Oakfield Town, HRFC"; null when a club can't be read.
     */
    internal fun clubs(t: String): String? {
        val parts = collapse(t).split(',', ';').map { collapse(it) }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > MAX_CLUBS) return null
        val out = mutableListOf<AboutClub>()
        for (p in parts) {
            val c = club(p) ?: return null
            if (out.none { it.short == c.short }) out += c
        }
        return out.joinToString(", ") { c -> listOfNotNull(c.short, c.name).joinToString(" ") }
    }

    private fun club(p: String): AboutClub? {
        val sp = p.indexOfFirst { it == ' ' || it == '=' || it == ':' }
        val short = (if (sp < 0) p else p.substring(0, sp)).trim().uppercase()
        if (short.length !in 2..MAX_CLUB_SHORT || !short.all(Char::isLetterOrDigit) || short.none(Char::isLetter)) return null
        val rest = if (sp < 0) "" else collapse(p.substring(sp).trimStart(' ', '=', ':'))
        if (rest.isEmpty()) return AboutClub(short)
        val name = place(rest)?.takeIf { it.length <= MAX_CLUB_NAME } ?: return null
        return AboutClub(short, name)
    }

    /** The clubs [kept] holds ([AboutYou.clubs]), in order; empty for none. */
    fun clubList(kept: String?): List<AboutClub> =
        kept?.let(::clubs).orEmpty().split(", ").mapNotNull { p -> p.trim().takeIf { it.isNotEmpty() }?.let(::club) }

    /** A town or area: a letter at least, letters, digits, spaces and . , - ' only, up to [MAX_PLACE]. */
    internal fun place(t: String): String? =
        t.takeIf { s -> s.length <= MAX_PLACE && s.any(Char::isLetter) && s.all { it.isLetterOrDigit() || it in " -'.,’" } }

    // ---- Children ----

    /**
     * "Alex, U7, Oakfield School" · "Alex U7" · "Alex, under 10" · "Alex": the name first, an age group ("U7", "U10s",
     * "under 7") anywhere after it, and whatever else is the school. Null without a usable name.
     */
    fun parseChild(typed: String): AboutChild? {
        val parts = collapse(typed).split(',').map { collapse(it) }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var age: Int? = null
        // The first part may end with the age group: "Alex U7", "Mary Jane under 10s".
        val words = parts[0].split(' ')
        var keep = words.size
        if (words.size >= 3 && ageGroupOf(words.takeLast(2).joinToString(" ")) != null) keep = words.size - 2
        else if (words.size >= 2 && ageGroupOf(words.last()) != null) keep = words.size - 1
        if (keep < words.size) age = ageGroupOf(words.drop(keep).joinToString(" "))
        val n = name(words.take(keep).joinToString(" ")) ?: return null
        var school: String? = null
        for (p in parts.drop(1)) {
            val a = ageGroupOf(p)
            if (a != null && age == null) age = a
            else if (a == null && school == null) school = place(p.take(MAX_SCHOOL)) ?: return null
        }
        return AboutChild(n, age, school)
    }

    /** "U7", "u10s", "U 7", "under 7", "under-10s" → the number (4–18); null otherwise. No regex. */
    fun ageGroupOf(s: String): Int? {
        var t = s.trim().lowercase().replace('-', ' ')
        t = when {
            t.startsWith("under ") -> t.removePrefix("under ")
            t.startsWith("u") -> t.removePrefix("u")
            else -> return null
        }.trim().removeSuffix("s")
        if (t.isEmpty() || t.length > 2 || !t.all(Char::isDigit)) return null
        return t.toInt().takeIf { it in 4..18 }
    }

    fun encodeChildren(children: List<AboutChild>): String =
        children.joinToString("\n") { c -> listOf(c.name, c.ageGroup?.toString().orEmpty(), c.school.orEmpty()).joinToString("|") { it.replace('|', ' ').replace('\n', ' ') } }

    fun decodeChildren(s: String?): List<AboutChild> =
        s.orEmpty().split('\n').mapNotNull { line ->
            val p = line.split('|')
            val n = p.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            AboutChild(n, p.getOrNull(1)?.trim()?.toIntOrNull(), p.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() })
        }

    /** "Under 7s · Oakfield School", "Under 10s", "Oakfield School", or "No club or school added". */
    fun childLine(c: AboutChild): String =
        listOfNotNull(c.ageGroup?.let { "Under ${it}s" }, c.school).joinToString(" · ").ifEmpty { "No club or school added" }

    /** What a child's field shows to edit: "Alex, U7, Oakfield School". */
    fun childText(c: AboutChild): String = listOfNotNull(c.name, c.ageGroup?.let { "U$it" }, c.school).joinToString(", ")

    // ---- The pane ----

    fun view(a: AboutYou, cal: LocalCalendar, nowMs: Long): AboutYouView {
        val rows = AboutField.entries.map { f ->
            val v = value(a, f)
            AboutRow(f, f.label, v, f.hint, if (v == null) f.emptyLine else SAVED_LINE, f.spokenHint)
        }
        val kids = a.children.mapIndexed { i, c ->
            AboutChildRow(i, c.name, childLine(c), "${c.name}, ${childLine(c)}")
        }
        return AboutYouView(
            summary = summary(a, cal, nowMs),
            rows = rows,
            children = kids,
            childHint = CHILD_HINT,
            childrenEmpty = if (a.children.isEmpty()) NO_CHILDREN else null,
            partnerWatch = a.partnerWatch,
            watchLine = a.partnerName?.let { if (a.partnerWatch) WATCH_ON else WATCH_OFF },
            isEmpty = a.isEmpty,
            callName = CallNameRules.called(a.shownName),
        )
    }

    private const val SAVED_LINE = "In your data"

    /** Where it came from: the welcome when empty, "MEKA filled this in … · check it" after the copy, else [TYPED]. */
    fun summary(a: AboutYou, cal: LocalCalendar, nowMs: Long): String = when {
        a.isEmpty -> WELCOME
        a.seededAtMs != null -> {
            val day = SearchRules.dayLabel(cal.epochDayOf(a.seededAtMs), cal.epochDayOf(nowMs))
            val on = if (day == "Today" || day == "Yesterday") day.lowercase() else "on $day"
            "Navilife filled this in $on from what it already knew · check it and change anything. It's kept in your own data, not in Navilife's code."
        }
        else -> TYPED
    }

    // ---- Setup ----

    const val SETUP_KEY = "about"

    /**
     * Setup's About you step: to do while nothing is filled in (nothing is pre-filled from code), ticked with what's
     * there ("Partner, 2 children, home and work").
     */
    fun setupStep(a: AboutYou): SetupStep =
        if (a.isEmpty) SetupStep(SETUP_KEY, TITLE, "Who's who, home and work · typed once, kept in your data", SetupState.TODO, SetupFix.ABOUT_YOU, "Add")
        else SetupStep(SETUP_KEY, TITLE, "${held(a)} · change in About you", SetupState.DONE)

    /** "Partner, 2 children, home and work", "Your name and home". */
    fun held(a: AboutYou): String {
        val parts = listOfNotNull(
            a.shownName?.let { "your name" },
            a.partnerName?.let { "partner" },
            a.children.size.takeIf { it > 0 }?.let { if (it == 1) "1 child" else "$it children" },
            a.home?.let { "home" },
            a.work?.let { "work" },
            a.route?.let { "train route" },
            a.homeStation?.let { "home station" },
            a.stationWalk?.let { "time to the train" },
            a.schoolWebsite?.let { "the school's website" },
            a.schoolMail?.let { "the school's email" },
            a.clubs?.let { "clubs" },
            (a.mobile ?: a.assistantNumber ?: a.voicemailNumber)?.let { "numbers" },
        )
        val s = when (parts.size) {
            0 -> "Nothing yet"
            1 -> parts[0]
            else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        }
        return s.replaceFirstChar { it.uppercase() }
    }

    /** [view] with the About you step at the top (its own section, first). */
    fun withSetupStep(v: SetupView, a: AboutYou): SetupView =
        SetupRules.withSection(v, SetupSection(TITLE, listOf(setupStep(a))))

    // ---- Release 1: the one-off copy ----

    /**
     * The fields the Fold's one-off copy writes ([AboutYouStore.seedOnce]): only the values given (each cleaned as if
     * typed), the partner watched, and when it happened.
     */
    fun seedFields(values: AboutYou, nowMs: Long): Map<String, FieldValue> {
        val out = linkedMapOf<String, FieldValue>()
        for (f in AboutField.entries) {
            val v = value(values, f)?.let { clean(f, it) }?.takeIf { it.isNotEmpty() } ?: continue
            out[f.key] = FieldValue.Text(v)
        }
        val kids = values.children.mapNotNull { c -> name(collapse(c.name))?.let { AboutChild(it, c.ageGroup, c.school?.let(::place)) } }
        if (kids.isNotEmpty()) out[AboutYouFields.CHILDREN] = FieldValue.Text(encodeChildren(kids.take(MAX_CHILDREN)))
        if (out.isEmpty()) return emptyMap()
        out[AboutYouFields.PARTNER_WATCH] = FieldValue.Bool(values.partnerWatch)
        out[AboutYouFields.SEEDED_AT] = FieldValue.Int64(nowMs)
        return out
    }
}

/** The household profile, synced. */
class AboutYouStore(private val replica: Replica, private val nowMs: () -> Long) {
    private fun fields() = replica.entity(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID)?.fields

    fun exists(): Boolean = replica.entity(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID) != null

    fun profile(): AboutYou = AboutYouRules.read(fields())

    fun view(cal: LocalCalendar): AboutYouView = AboutYouRules.view(profile(), cal, nowMs())

    /** Saves [typed] into [f]; blank clears it (with Undo); refused (nothing written) when it can't be one. */
    fun set(f: AboutField, typed: String): AboutSaved {
        val clean = AboutYouRules.clean(f, typed) ?: return AboutSaved(AboutYouRules.refusal(f), refused = true)
        val before = AboutYouRules.value(profile(), f)
        if ((before ?: "") == clean) return AboutSaved(if (clean.isEmpty()) f.emptyLine else AboutYouRules.SAVED)
        replica.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID,
            mapOf(f.key to (if (clean.isEmpty()) FieldValue.Null else FieldValue.Text(clean))))
        return if (clean.isEmpty()) AboutSaved("Cleared · Undo puts it back", undo = before) else AboutSaved(AboutYouRules.SAVED)
    }

    /** One name everywhere: whether the hello has already asked what to call him (on either app). */
    fun nameAsked(): Boolean = fields()?.get(AboutYouFields.NAME_ASKED_AT)?.longOrNull != null

    /**
     * Notes that the hello asked, once. Only into a profile that exists, so this never creates one (release 1's copy
     * checks for none); false when nothing was written.
     */
    fun markNameAsked(): Boolean {
        if (!exists() || nameAsked()) return false
        replica.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, mapOf(AboutYouFields.NAME_ASKED_AT to FieldValue.Int64(nowMs())))
        return true
    }

    /** The orb's last openers said on either app ([AboutYouFields.HELLO_RECENT]); empty when none. */
    fun helloRecent(): List<String> = HelloOpeners.decodeIds(fields()?.get(AboutYouFields.HELLO_RECENT)?.textOrNull)

    /** Keeps [ids] as the shared last openers; only into a profile that exists (never creates one), false when unchanged. */
    fun setHelloRecent(ids: List<String>): Boolean {
        val text = HelloOpeners.encodeIds(ids)
        if (!exists() || HelloOpeners.encodeIds(helloRecent()) == text) return false
        replica.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, mapOf(AboutYouFields.HELLO_RECENT to FieldValue.Text(text)))
        return true
    }

    /** MEKA watches the partner's messages for requests, or not. */
    fun setPartnerWatch(on: Boolean): Boolean {
        if (exists() && profile().partnerWatch == on) return false
        replica.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, mapOf(AboutYouFields.PARTNER_WATCH to FieldValue.Bool(on)))
        return true
    }

    /** Adds a child typed as "Alex, U7, Oakfield School"; refused when no name can be read or the list is full. */
    fun addChild(typed: String): AboutSaved {
        val c = AboutYouRules.parseChild(typed) ?: return AboutSaved(AboutYouRules.NOT_CHILD, refused = true)
        val now = profile().children
        if (now.size >= AboutYouRules.MAX_CHILDREN) return AboutSaved(AboutYouRules.TOO_MANY, refused = true)
        writeChildren(now + c)
        return AboutSaved("Added ${c.name} · ${AboutYouRules.childLine(c)}")
    }

    /** Replaces the child at [index] with what's typed (a name, age group and school). */
    fun editChild(index: Int, typed: String): AboutSaved {
        val now = profile().children
        if (index !in now.indices) return AboutSaved(AboutYouRules.NOT_CHILD, refused = true)
        val c = AboutYouRules.parseChild(typed) ?: return AboutSaved(AboutYouRules.NOT_CHILD, refused = true)
        if (now[index] == c) return AboutSaved(AboutYouRules.SAVED)
        writeChildren(now.toMutableList().also { it[index] = c })
        return AboutSaved(AboutYouRules.SAVED)
    }

    /** Takes the child at [index] off; [AboutSaved.undo] is what [addChild] puts back. */
    fun removeChild(index: Int): AboutSaved {
        val now = profile().children
        if (index !in now.indices) return AboutSaved(AboutYouRules.NOT_CHILD, refused = true)
        val gone = now[index]
        writeChildren(now.filterIndexed { i, _ -> i != index })
        return AboutSaved("Removed ${gone.name} · Undo puts them back", undo = AboutYouRules.childText(gone))
    }

    private fun writeChildren(list: List<AboutChild>) {
        replica.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, mapOf(
            AboutYouFields.CHILDREN to (if (list.isEmpty()) FieldValue.Null else FieldValue.Text(AboutYouRules.encodeChildren(list))),
        ))
    }

    /** Who holds the profile (slice 2). */
    fun held(): AboutYouHeld? = AboutYouRules.held(fields())

    /**
     * Slice 2: once this app holds a filled-in profile (after a clean sync), it writes when, once. Nothing for an
     * empty or missing profile, so this never creates one (release 1's copy checks for none). True when it wrote.
     */
    fun markHeld(device: AboutYouDevice): Boolean {
        val h = held() ?: return false
        if (!h.filled) return false
        if ((if (device == AboutYouDevice.MAC) h.macAtMs else h.foldAtMs) != null) return false
        replica.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID,
            mapOf(AboutYouRules.heldField(device) to FieldValue.Int64(nowMs())))
        return true
    }

    /**
     * Release 2 slice 1's one-off copy of the clubs (2026-10-15): the profile existed before About you had a Clubs
     * line, so the clubs MEKA already knew are written into it once, only while the line has never been written
     * (typed, cleared or copied) and a profile exists. True when it wrote.
     */
    fun seedClubsOnce(clubs: String): Boolean {
        val f = fields() ?: return false
        if (AboutYouFields.CLUBS in f) return false
        val v = AboutYouRules.clean(AboutField.CLUBS, clubs)?.takeIf { it.isNotEmpty() } ?: return false
        replica.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, mapOf(AboutYouFields.CLUBS to FieldValue.Text(v)))
        return true
    }

    /**
     * Release 2 slice 6's one-off copy of the train route (2026-10-15): the lines and stations Today and the heads-ups
     * named were in the code; they are written into the profile once, only while the line has never been written and
     * a profile exists, so a cleared route stays cleared. True when it wrote.
     */
    fun seedRouteOnce(route: String): Boolean {
        val f = fields() ?: return false
        if (AboutYouFields.ROUTE in f) return false
        val v = RouteRules.parse(route)?.text ?: return false
        replica.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, mapOf(AboutYouFields.ROUTE to FieldValue.Text(v)))
        return true
    }

    /**
     * Release 2 slice 5's one-off copy of the council's holidays (2026-10-15): the table the School pane offered was in
     * the code; it is written into the profile once, only while the field has never been written and a profile exists,
     * so the code can drop the council's name. True when it wrote.
     */
    fun seedCouncilOnce(cal: TermCalendar): Boolean {
        val f = fields() ?: return false
        if (AboutYouFields.COUNCIL_DATES in f) return false
        if (SchoolTerms.decode(SchoolTerms.encode(cal)) == null) return false
        replica.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID,
            mapOf(AboutYouFields.COUNCIL_DATES to FieldValue.Text(SchoolTerms.encode(cal))))
        return true
    }

    /**
     * Release 1's one-off copy: writes [values] only when there is no profile at all (never over one typed on either
     * app or copied before). True when it wrote.
     */
    fun seedOnce(values: AboutYou): Boolean {
        if (exists()) return false
        val f = AboutYouRules.seedFields(values, nowMs())
        if (f.isEmpty()) return false
        replica.commitLocal(EntityTypes.HOUSEHOLD_PROFILE, AboutYouRules.ENTITY_ID, f)
        return true
    }
}
