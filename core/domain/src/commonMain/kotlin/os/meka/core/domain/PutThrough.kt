package os.meka.core.domain

import os.meka.core.sync.FieldValue

/**
 * The call assistant puts a genuine urgent caller through (build plan "spam calls still ring", item (c) slice 2; Meka
 * 2026-10-13 20:56, item (c): "genuine and urgent → it offers to put them through: it calls his mobile from the
 * assistant's number with a whisper … press 1 to take it, 2 for a message"). Non-AI and pure.
 *
 * After a caller who said who they were has left a message and said it's urgent, the assistant rings Meka's mobile (About
 * you → Your mobile) from its own number, which his Fold always lets ring ([CallReason.ASSISTANT]); when he answers he
 * hears who it is and what they said and presses 1 to take the call. Otherwise the caller hears that Meka has their
 * message. Nothing is lost either way: the message is already kept.
 *
 * Untrusted content (ADR-006): the caller's words only ever lead to an offer that Meka himself accepts with a key press;
 * they never connect anyone on their own. Never offered for: no real introduction (a machine), anything judged a scam,
 * anyone claiming to be the police, a bank, HMRC, a court or the DWP ("never put through on their word"), a number the
 * web reports for scams or nuisance calls, a message too short to be a person's, a call from his own mobile, or while
 * About you has no mobile.
 */
object PutThroughRules {
    /** Said to the caller while his mobile rings. */
    const val HOLD = "Thank you. I'll try to put you through to Meka now. Please hold."
    /** Said to the caller when he didn't take it. */
    const val MISSED = "Sorry, Meka couldn't take the call just now. He has your message and will see it straight away. Goodbye."
    /** Said to Meka after he pressed 1, just before the caller is connected. */
    const val CONNECTING = "Connecting you now."
    /** How long his mobile rings (under a network's usual 20 s before "forward when not answered"). */
    const val RING_SECONDS = 15
    /** How long the whisper waits for his key press. */
    const val WHISPER_SECONDS = 8
    /** The longest part of the caller's words read to him. */
    const val WHISPER_WORDS_MAX = 160

    /** [HeldMessageFields.PUT_THROUGH] values. */
    const val TAKEN = "taken"
    const val MISSED_VALUE = "missed"

    /** The after-work summary's and alert's words after what happened. */
    const val HAPPENED_TAKEN = "put through · you took it"
    const val HAPPENED_MISSED = "put through · you didn't take it"

    /**
     * Whether the assistant offers to put this caller through: [urgent] answered by a caller whose [intro] is a real
     * introduction judged plainly OK ([judged]), not reported online ([reputation]), whose message was at least
     * [CallNoticeRules.AUTOMATED_SECONDS] long ([seconds], null when not known), from a number other than his own
     * [mobile], with [mobile] and the assistant's own number ([assistant], the call's To) both usable.
     */
    fun offer(
        urgent: Boolean,
        intro: String?,
        judged: IntroJudgement,
        reputation: NumberReputation?,
        seconds: Int?,
        from: String?,
        mobile: String?,
        assistant: String?,
    ): Boolean {
        if (!urgent || !CallIntroRules.real(intro)) return false
        if (judged.verdict != IntroVerdict.OK || judged.reported) return false
        if (reputation?.verdict == NumberVerdict.SCAM || reputation?.verdict == NumberVerdict.NUISANCE) return false
        if (seconds != null && seconds < CallNoticeRules.AUTOMATED_SECONDS) return false
        val to = dialable(mobile) ?: return false
        val own = dialable(assistant) ?: return false
        if (to == own) return false
        if (dialable(from) == to) return false
        return true
    }

    /**
     * A UK or international number as the phone service dials it: "+447700900123" from "07700 900123", "+44 7700
     * 900123" or "0044 7700 900123"; null for anything that isn't a phone number.
     */
    fun dialable(number: String?): String? {
        val t = number?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (t.any { !it.isDigit() && it !in "+-() ." }) return null
        if (t.indexOf('+') > 0) return null
        val digits = t.filter { it.isDigit() }
        val e164 = when {
            t.startsWith("+") -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith("0") && digits.length == 11 -> "44" + digits.drop(1)
            else -> return null
        }
        if (e164.length !in 10..15 || e164.startsWith("0")) return null
        return "+$e164"
    }

    /**
     * What Meka hears when he answers: "Navilife call assistant. A caller says it's urgent. They said: It's Sam from
     * Oakfield School about Alex. Press 1 to take the call, or hang up and they'll know you have their message."
     */
    fun whisper(intro: String?): String {
        val words = CallIntroRules.clean(intro)?.let { if (it.length > WHISPER_WORDS_MAX) it.take(WHISPER_WORDS_MAX - 1).trimEnd() + "…" else it }
        val said = words?.let { " They said: " + it.trimEnd('.', ' ') + "." } ?: ""
        return "Navilife call assistant. A caller says it's urgent.$said Press 1 to take the call, or hang up and they'll know you have their message."
    }

    /** Whether his key press takes the call. */
    fun takes(digits: String?): Boolean = digits?.trim() == "1"

    /**
     * A call from the assistant's own number ([assistant]): on the Fold it is a put-through, so it rings
     * ([CallReason.ASSISTANT]); reaching the assistant itself, his mobile turned the put-through away and the network's
     * "forward when busy" sent it back, so it is turned away as busy and never loops.
     */
    fun fromAssistant(from: String?, assistant: String?): Boolean {
        val f = dialable(from) ?: return false
        return f == dialable(assistant)
    }

    /** The words after what happened for [putThrough] ([HeldMessageFields.PUT_THROUGH]); null when not tried. */
    fun happened(putThrough: String?): String? = when (putThrough) {
        TAKEN -> HAPPENED_TAKEN
        MISSED_VALUE -> HAPPENED_MISSED
        else -> null
    }

    /** The Activity entry: "Put a caller through to you · 07700 900123" · whether he took it. */
    fun activity(number: String?, taken: Boolean, atMs: Long): Map<String, FieldValue> = linkedMapOf(
        ActivityFields.AT to FieldValue.Int64(atMs),
        ActivityFields.KIND to FieldValue.Text(ActivityKind.CALL.name),
        ActivityFields.SUMMARY to FieldValue.Text(
            ("Put an urgent caller through to you · " + (number?.let(BlockedCallerRules::display) ?: CallAssistantRules.WITHHELD_NAME)).take(ActivityRules.MAX_LINE),
        ),
        ActivityFields.DETAIL to FieldValue.Text(if (taken) "You pressed 1 and took the call" else "You didn't take it · they left a message"),
        ActivityFields.WHY to FieldValue.Text(
            "Call assistant · they said who they were and that it's urgent, so it rang your mobile from its own number. " +
                "Nobody claiming to be the police, a bank or HMRC is ever put through; remove Your mobile in About you to stop it",
        ),
        ActivityFields.SOURCE to FieldValue.Text("calls"),
    )

    /** The Activity entry id for one call's put-through. */
    fun activityId(heldId: String): String = "v" + ActivityRules.fnv64("putthrough:$heldId")
}
