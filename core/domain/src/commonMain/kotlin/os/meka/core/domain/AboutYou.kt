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
    const val ASSISTANT_NUMBER = "assistantNumber"
    const val VOICEMAIL_NUMBER = "voicemailNumber"
    /** Int64: when the Fold copied MEKA's earlier values in (release 1); absent when he typed it all. */
    const val SEEDED_AT = "seededAtMs"
    /** Int64: when the Fold first held a filled-in profile after a clean sync (slice 2, release 2's gate). */
    const val HELD_FOLD = "heldFoldAtMs"
    /** Int64: the same for the Mac. */
    const val HELD_MAC = "heldMacAtMs"
}

/** Which app this core runs in, for [AboutYouStore.markHeld]. */
enum class AboutYouDevice { FOLD, MAC }

/**
 * Whether each app has the profile (slice 2): [filled] when anything is in it, and when the Fold and the Mac each
 * first held it after a clean sync. Release 2 (deleting the old values from the code) waits until both have it.
 */
data class AboutYouHeld(val filled: Boolean, val foldAtMs: Long?, val macAtMs: Long?) {
    val onBoth: Boolean get() = filled && foldAtMs != null && macAtMs != null
}

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
    val assistantNumber: String? = null,
    val voicemailNumber: String? = null,
    val seededAtMs: Long? = null,
) {
    /** Nothing at all filled in (Setup's step and the pane's welcome line). */
    val isEmpty: Boolean
        get() = listOf(shownName, partnerName, partnerPhone, partnerEmail, home, work, assistantNumber, voicemailNumber).all { it == null } &&
            children.isEmpty()

    companion object {
        val EMPTY = AboutYou()
    }
}

/** The fields About you edits one line at a time, in the pane's order. */
enum class AboutField(val key: String, val label: String, val hint: String, val emptyLine: String) {
    SHOWN_NAME(AboutYouFields.SHOWN_NAME, "Your name", "What Navilife calls you", "Navilife says “you” until you add it"),
    PARTNER_NAME(AboutYouFields.PARTNER_NAME, "Partner", "Their first name", "Add your partner to get their requests flagged"),
    PARTNER_PHONE(AboutYouFields.PARTNER_PHONE, "Partner's number", "Their mobile number", "Add their number so their calls always ring"),
    PARTNER_EMAIL(AboutYouFields.PARTNER_EMAIL, "Partner's email", "Their email address", "Add it so Inbox autopilot never tidies their emails away"),
    HOME(AboutYouFields.HOME, "Home", "Your town", "Add home for its weather and the commute"),
    WORK(AboutYouFields.WORK, "Work", "Where the office is", "Add work for office-day weather"),
    ASSISTANT_NUMBER(AboutYouFields.ASSISTANT_NUMBER, "Call assistant's number", "The number callers reach", "Add it once the call assistant has a number"),
    VOICEMAIL_NUMBER(AboutYouFields.VOICEMAIL_NUMBER, "Voicemail number", "Your network's voicemail", "Not added · only needed for call forwarding"),
}

/** One row of the pane: the label, what's held (null when empty) and the line under it. */
data class AboutRow(val field: AboutField, val label: String, val value: String?, val hint: String, val line: String)

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
    const val CHILD_HINT = "Add a child… (Alex, U7, Oakfield School)"
    const val NO_CHILDREN = "No children added · add each one to name their clubs and school days"
    const val WELCOME = "Tell Navilife who's who once. It stays in your own data (synced, encrypted), never in Navilife's code."
    const val TYPED = "Kept in your own data (synced, encrypted) on both apps. Change anything here."
    const val SAVED = "Saved"
    const val NOT_NAME = "A name is letters, spaces, hyphens and apostrophes, up to 30."
    const val NOT_NUMBER = "That doesn't look like a phone number."
    const val NOT_PLACE = "Type a town or area, up to 60 letters."
    const val NOT_EMAIL = "That doesn't look like an email address."
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
        ).joinToString(" · ")
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
            assistantNumber = text(AboutYouFields.ASSISTANT_NUMBER),
            voicemailNumber = text(AboutYouFields.VOICEMAIL_NUMBER),
            seededAtMs = fields[AboutYouFields.SEEDED_AT]?.longOrNull,
        )
    }

    fun value(a: AboutYou, f: AboutField): String? = when (f) {
        AboutField.SHOWN_NAME -> a.shownName
        AboutField.PARTNER_NAME -> a.partnerName
        AboutField.PARTNER_PHONE -> a.partnerPhone
        AboutField.PARTNER_EMAIL -> a.partnerEmail
        AboutField.HOME -> a.home
        AboutField.WORK -> a.work
        AboutField.ASSISTANT_NUMBER -> a.assistantNumber
        AboutField.VOICEMAIL_NUMBER -> a.voicemailNumber
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
            AboutField.PARTNER_PHONE, AboutField.ASSISTANT_NUMBER, AboutField.VOICEMAIL_NUMBER -> phone(t)
            AboutField.PARTNER_EMAIL -> email(t)
            AboutField.HOME, AboutField.WORK -> place(t)
        }
    }

    fun refusal(f: AboutField): String = when (f) {
        AboutField.SHOWN_NAME, AboutField.PARTNER_NAME -> NOT_NAME
        AboutField.PARTNER_PHONE, AboutField.ASSISTANT_NUMBER, AboutField.VOICEMAIL_NUMBER -> NOT_NUMBER
        AboutField.PARTNER_EMAIL -> NOT_EMAIL
        AboutField.HOME, AboutField.WORK -> NOT_PLACE
    }

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
     * The addresses Inbox autopilot never tidies away (slice 3b-2b): the partner's email from About you, lower case;
     * later his contacts too. Empty when none is set.
     */
    fun keepAddresses(a: AboutYou): Set<String> = setOfNotNull(a.partnerEmail?.let(::email))

    /** [keepAddresses] straight from the stored profile (the server's autopilot). */
    fun keepAddresses(fields: Map<String, FieldValue>?): Set<String> = keepAddresses(read(fields))

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
            AboutRow(f, f.label, v, f.hint, if (v == null) f.emptyLine else SAVED_LINE)
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
            (a.assistantNumber ?: a.voicemailNumber)?.let { "numbers" },
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
