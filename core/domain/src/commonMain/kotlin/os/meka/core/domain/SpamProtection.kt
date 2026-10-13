package os.meka.core.domain

import os.meka.core.sync.FieldValue

/**
 * Spam calls still ring (build plan "Fix first", Meka 2026-10-13: "filtering of spam calls should be on all the time,
 * and when it identifies one it should be added to blocked so such calls don't even ring"), slice 1. Non-AI and pure.
 *
 * Spam protection is on unless Meka turns it off in Work mode → Spam protection → Advanced, and no longer waits for the
 * call assistant's switch. A number is put on the block list automatically (Activity has Undo, and a quiet line says
 * "Blocked 07… · it won't ring again · Undo") when the network's caller check failed, or when the same unknown number
 * called [AutoBlockRules.REPEAT_CALLS]+ times in [AutoBlockRules.REPEAT_DAYS] days without leaving a voice message.
 * Never auto-blocked: contacts, anyone he called in 90 days, the household profile's numbers, withheld numbers, and
 * callers claiming to be the police, a bank, the NHS, a school or a public body ([OfficialCallerRules]): those ring,
 * and a heads-up afterwards says how to check them.
 *
 * Android doesn't let an app other than the dialer write the phone's own "Blocked numbers", so Navilife's list is
 * enforced by its own call screening (the same result: no ring).
 */
object SpamProtectionRules {
    const val TITLE = "Spam calls"
    /** Work mode → Spam protection's explanation (both apps). */
    const val HINT = "Spam protection is always on. Blocked numbers never ring, any time of day. A number your network " +
        "can't verify (often spoofed) is blocked before it rings, and an unknown number that calls twice in a week " +
        "without leaving a message, or hangs up on your call assistant, is blocked after the call; each one shows in Activity with Undo. Family, your " +
        "always-notify list, contacts, anyone you called in the last 90 days and the numbers in About you always get " +
        "through, and so do callers claiming to be the police, a bank, the NHS or a school (with a heads-up on how to check)."
    /** Rule (5): why the phone's own Blocked numbers list doesn't show them. */
    const val SYSTEM_LIST_LINE = "Android only lets the phone app change its own Blocked numbers list, so Navilife " +
        "keeps its own list and its call screening stops these calls before they ring."
    const val ADVANCED = "Advanced"
    const val SWITCH_LABEL = "Spam protection"
    const val ON_LINE = "On · always, whatever the call assistant's switch says"
    const val OFF_WARNING = "Off · spoofed numbers and repeat unknown callers can ring again. Your block list still works."
    const val OFF_CONFIRM = "Turn spam protection off? Spoofed and repeat unknown callers will ring until you turn it back on."

    /**
     * The Fold's Health row (role lost or switched off), or null when all's well or on the Mac. Health's attention rows
     * feed Today's line, so a lost role shows as "Spam calls · can ring … · Fix" on Today in red.
     */
    fun healthRow(roleHeld: Boolean?, protectionOn: Boolean, mac: Boolean): HealthRow? {
        if (mac) return null
        return when {
            roleHeld == false -> HealthRow(
                HEALTH_KEY, TITLE, "can ring · Navilife isn't your Caller ID & spam app", HealthState.BAD, HealthFix.CALL_ROLE, "Fix",
            )
            !protectionOn -> HealthRow(HEALTH_KEY, TITLE, "Spam protection is off (Work mode → Spam protection → Advanced)", HealthState.WARN, HealthFix.WORK, "Work mode")
            roleHeld == true -> HealthRow(HEALTH_KEY, TITLE, "Protected · spoofed and repeat unknown callers are blocked", HealthState.OK)
            else -> null
        }
    }

    const val HEALTH_KEY = "spam"

    /** The household profile's numbers (About you: partner, the call assistant, voicemail) as call keys: never screened as spam. */
    fun householdKeys(a: AboutYou): Set<String> =
        listOfNotNull(a.partnerPhone, a.assistantNumber, a.voicemailNumber).mapNotNull { BlockedCallerRules.keyOf(it) }.toSet()
}

/** The official body a caller's network name claims to be, for the heads-up wording. */
enum class OfficialClaim(val who: String, val check: String) {
    POLICE("the police", "call 101 to check, not this number back"),
    BANK("a bank", "call the number on the back of your card, not this one"),
    NHS("the NHS", "call your surgery or 111 on a number you know"),
    SCHOOL("a school", "call the school on the number you have for it"),
    GOVERNMENT("a public body", "call the number on gov.uk, not this one"),
}

object OfficialCallerRules {
    /** UK emergency and public-service numbers (and 112): never screened, never blocked. */
    val EMERGENCY_NUMBERS = setOf("999", "112", "101", "111", "105", "119")

    fun isEmergency(number: String?): Boolean {
        val d = number?.filter { it.isDigit() }.orEmpty()
        return d in EMERGENCY_NUMBERS
    }

    private val PATTERNS: List<Pair<OfficialClaim, Regex>> = listOf(
        OfficialClaim.POLICE to Regex("\\b(police|constabulary|met\\s*police|scotland\\s*yard|action\\s*fraud|cid)\\b"),
        OfficialClaim.BANK to Regex("\\b(bank|barclays|hsbc|lloyds|natwest|santander|nationwide|halifax|monzo|starling|revolut|fraud\\s*(team|dept|department)|card\\s*services)\\b"),
        OfficialClaim.NHS to Regex("\\b(nhs|hospital|surgery|gp|medical\\s*cent(re|er)|health\\s*cent(re|er)|clinic)\\b"),
        OfficialClaim.SCHOOL to Regex("\\b(school|academy|primary|college|nursery)\\b"),
        OfficialClaim.GOVERNMENT to Regex("\\b(hmrc|tax\\s*office|dvla|dwp|council|court|bailiff|home\\s*office|gov\\.?uk|government)\\b"),
    )

    /** What [networkName] claims to be, or null for an ordinary (or no) name. */
    fun claim(networkName: String?): OfficialClaim? {
        val n = networkName?.lowercase()?.trim().orEmpty()
        if (n.isEmpty()) return null
        return PATTERNS.firstOrNull { it.second.containsMatchIn(n) }?.first
    }

    /** The heads-up after such a call: "Calls claiming to be the police can be spoofed · call 101 to check, not this number back". */
    fun headsUp(claim: OfficialClaim): String = "Calls claiming to be ${claim.who} can be spoofed · ${claim.check}"
}

/** What put a number on the block list without asking. */
enum class AutoBlockTrigger(val why: String) {
    NETWORK_CHECK("Auto-blocked · failed the network's caller check"),
    REPEAT_CALLER("Auto-blocked · called again within a week without leaving a message"),
    ASSISTANT_HANGUP("Auto-blocked · hung up on your call assistant without a message"),
}

object AutoBlockRules {
    const val REPEAT_DAYS = 7
    const val REPEAT_CALLS = 2
    const val UNDO = "Undo"
    const val SOURCE = "calls:autoblock"
    const val ACTIVITY_WHY = "Spam protection · always on · Undo takes it off the block list"

    /**
     * Whether an unknown caller (already past the never-block checks: not a contact, not called lately, not household,
     * not withheld, not claiming to be an official body) should be blocked after this call: [callsInWindow] incoming
     * calls from the number in the last [REPEAT_DAYS] days (this one included), none of which left a voice message.
     */
    fun repeatCaller(callsInWindow: Int, leftVoiceMessage: Boolean): Boolean = callsInWindow >= REPEAT_CALLS && !leftVoiceMessage

    /** The quiet line and Activity summary: "Blocked 01632 960 123 · it won't ring again". */
    fun blockedLine(number: String): String = "Blocked ${BlockedCallerRules.display(number)} · it won't ring again"

    /** The block list's reason, with the day: "Auto-blocked · failed the network's caller check · Tue 13 Oct". */
    fun why(trigger: AutoBlockTrigger, atMs: Long, cal: LocalCalendar): String =
        "${trigger.why} · ${CivilDate.shortLabel(cal.epochDayOf(atMs))}".take(BlockedCallerRules.MAX_WHY)

    /** Activity's detail for the entry. */
    fun detail(trigger: AutoBlockTrigger, callsInWindow: Int = 0): String = when (trigger) {
        AutoBlockTrigger.NETWORK_CHECK -> "Your network couldn't verify the caller's number (often a spoofed one); it didn't ring"
        AutoBlockTrigger.REPEAT_CALLER -> "Called $callsInWindow times in $REPEAT_DAYS days and never left a message"
        AutoBlockTrigger.ASSISTANT_HANGUP -> "Reached your call assistant and hung up without saying who they were or why"
    }
}

/** A number that reached the call assistant and hung up without leaving a message ([BlockedCallers.hangups]). */
data class AssistantHangup(val key: String, val number: String, val atMs: Long)

/**
 * Spam calls still ring, slice 3 (build plan (3): "the call assistant screened it and the caller hung up or gave no
 * name/reason"). When a caller reaches the call assistant and hangs up without leaving a message, the server writes
 * [BlockedCallerFields.HUNG_UP_AT] on the number's `blocked_caller` entity (never a block: the server can't tell a
 * contact from a stranger). The Fold, after its next sync, looks at each one with what only the phone knows (contacts,
 * anyone he called in 90 days, the family and always-notify lists, a network name claiming an official body) and,
 * with spam protection on, blocks the stranger as an automatic action with Undo in Activity. Non-AI and pure.
 */
object AssistantHangupRules {
    /** Hang-ups older than this aren't acted on (a phone that was off for a week doesn't block on its return). */
    const val WINDOW_DAYS = 7

    /** Whether the server records a hang-up from [from]: a real number (never withheld) that isn't an emergency service. */
    fun shouldRecord(from: String?): Boolean = BlockedCallerRules.keyOf(from) != null && !OfficialCallerRules.isEmergency(from)

    /** The fields the server writes on the number's `blocked_caller` entity. */
    fun fields(number: String, atMs: Long): Map<String, FieldValue> = linkedMapOf(
        BlockedCallerFields.NUMBER to FieldValue.Text(number.trim().take(40)),
        BlockedCallerFields.HUNG_UP_AT to FieldValue.Int64(atMs),
    )

    /**
     * Whether the Fold blocks a hang-up: spam protection on, the phone could check who the caller is ([lookups]: contacts
     * and call log readable; without them nobody is blocked), the caller isn't [known] (contact, called in 90 days,
     * listed, this phone's own number, claimed an official body), not household, and left no voice message lately.
     */
    fun shouldBlock(protection: Boolean, lookups: Boolean, known: Boolean, household: Boolean, leftVoiceMessage: Boolean): Boolean =
        protection && lookups && !known && !household && !leftVoiceMessage
}
