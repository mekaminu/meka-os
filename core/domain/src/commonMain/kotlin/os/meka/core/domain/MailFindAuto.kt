package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Promises, receipts and renewals from email, slice 3 (build plan; non-AI, pure): **trusted in What Navilife may do,
 * the finds add themselves**. A row "Renewals and promises from mail" under Your mail on the page (both apps), off until
 * Meka turns it on. While it is on (and nothing is paused), each renewal the server read from an email goes onto Lists →
 * Renewals and each promise onto Waiting for as soon as a device sees it, exactly as Add would put it there, and Activity
 * lists it ("Done for you") with Undo. Undo takes it off and the find comes back to Needs you with Add, never to be
 * added on its own again (its [MailFindFields.AUTO_AT] mark stays).
 *
 * The finds come from the server's fixed rules ([MailRenewalRules.read], [MailPromiseRules.read]), never a model, so
 * this is a deterministic rule Meka switched on (ADR-006 Level 4 under his rule, like Inbox autopilot); an email's words
 * never lift it. Only his own lists change: nothing is paid, cancelled, sent, replied to or opened. A find with no
 * company or person named, or a promise with nothing said, still waits for his tap; at most [MailFindAutoRules.PER_DAY]
 * a day are added on their own, the rest wait as cards.
 *
 * Stored as one `context_mode` entity, id [MailFindAutoRules.ENTITY_ID] (ADR-008 addendum 2026-10-15): `on` (Bool) and
 * `changedAtMs`, LWW, so turning it on on the Fold holds on the Mac. Absent = off.
 */
object MailFindAutoFields {
    const val ON = "on"
    const val CHANGED_AT = "changedAtMs"
}

object MailFindAutoRules {
    const val ENTITY_ID = "mail_finds_auto"

    const val TITLE = "Renewals and promises from mail"
    const val ON_LINE = "Puts renewals from your mail on Renewals and promises on Waiting for as they're spotted · each in " +
        "Activity with Undo · never pays, cancels or replies"
    const val OFF_LINE = "Off · each waits in Needs you for your Add"
    const val TURN_ON = "Turn on"
    const val TURN_OFF = "Turn off"

    /** At most this many finds are added on their own in a day (the rest wait as cards). */
    const val PER_DAY = 10

    const val SOURCE = "auto:mail_finds"
    const val WHY = "You turned on “Renewals and promises from mail” in What Navilife may do"

    /** The policy rule his switch made (ADR-006: Level 4 needs an explicit rule). */
    const val RULE_ID = "mail.finds"

    fun read(fields: Map<String, FieldValue>?): Boolean = fields?.get(MailFindAutoFields.ON)?.boolOrNull == true

    fun switchFields(on: Boolean, nowMs: Long): Map<String, FieldValue> =
        mapOf(MailFindAutoFields.ON to on.fv(), MailFindAutoFields.CHANGED_AT to nowMs.fv())

    fun switchedLine(on: Boolean): String =
        if (on) "Navilife will add renewals and promises from your mail for you · each in Activity with Undo"
        else "Renewals and promises from your mail wait for your Add again"

    /** One Activity entry per find, so two devices adding the same find write the same entry. */
    fun activityId(findId: String): String = "ma" + ActivityRules.fnv64("mailfindauto:$findId")

    /** The mark written (outside the recorded change) when a find is added on its own; an Undo leaves it. */
    fun markFields(nowMs: Long): Map<String, FieldValue> = mapOf(MailFindFields.AUTO_AT to nowMs.fv())

    /**
     * The finds to add now: open renewals and promises the card would show ([MailRenewalRules.card]'s rules), naming who
     * they're from (a promise also what was promised), never added on their own before, soonest first, at most what is
     * left of today's [PER_DAY] after [addedToday].
     */
    fun due(
        finds: List<MailFind>, obligations: Collection<Map<String, FieldValue>>, nowMs: Long, cal: LocalCalendar,
        waiting: List<WaitingItem>, addedToday: Int,
    ): List<MailFind> {
        val room = PER_DAY - addedToday
        if (room <= 0) return emptyList()
        val today = cal.epochDayOf(nowMs)
        return finds.filter { f ->
            f.autoAtMs == null && f.merchant != null && when (f.kind) {
                MailRenewalRules.KIND_RENEWAL -> f.state == MailFindState.OPEN && f.renewDay >= today &&
                    !MailRenewalRules.alreadyOnRadar(f.merchant, f.renewDay, obligations, cal)
                MailPromiseRules.KIND_PROMISE -> f.what != null && MailPromiseRules.showable(f, waiting, today)
                else -> false
            }
        }.sortedWith(compareBy<MailFind> { it.renewDay }.thenBy { it.id }).take(room)
    }

    /** How many finds were added on their own today (their marks). */
    fun addedToday(finds: List<MailFind>, nowMs: Long, cal: LocalCalendar): Int {
        val today = cal.epochDayOf(nowMs)
        return finds.count { f -> f.autoAtMs?.let { cal.epochDayOf(it) == today } == true }
    }

    /** Activity's line: "Added Netflix to Renewals · renews Tue 3 Nov" / "Added to Waiting for: Signed contract from Sam · chase Sat 17 Oct". */
    fun summary(f: MailFind, today: Long): String =
        if (f.kind == MailPromiseRules.KIND_PROMISE) "Added to Waiting for: " + MailPromiseRules.addedLine(f, today).removePrefix("On Waiting for: ")
        else "Added ${MailRenewalRules.title(f)} to Renewals · renews ${RenewalRules.dayWord(f.renewDay, today)}"

    /** Activity's detail: where it came from ("Personal mail · “Your membership renews soon”"). */
    fun detail(f: MailFind): String = MailRenewalRules.fromLine(f)

    /** Waiting for's note for a promise added on its own. */
    fun promiseNote(f: MailFind): String =
        "From an email in your ${f.account} mail on ${CivilDate.shortLabel(f.receivedDay)}" +
            (f.subject?.let { " (“$it”)" } ?: "") + ", added for you (Renewals and promises from mail is on)."

    /** The radar item for a renewal added on its own: as Add writes it, with a note saying it was added for him. */
    fun obligationFields(f: MailFind, nowMs: Long, cal: LocalCalendar): Map<String, FieldValue> =
        MailRenewalRules.obligationFields(f, nowMs, cal) + mapOf(
            ActionableFields.NOTES to (
                "From a renewal email in your ${f.account} mail on ${CivilDate.shortLabel(f.receivedDay)}, added for you " +
                    "(Renewals and promises from mail is on). Navilife never pays or cancels: do that on the company's own site or app."
                ).fv(),
        )

    /** The row on What Navilife may do (under Your mail). */
    fun row(on: Boolean, paused: Boolean) = AutonomyRow(
        key = ENTITY_ID,
        title = TITLE,
        line = when {
            on && paused -> AutonomyRules.PAUSED_LINE
            on -> ON_LINE
            else -> OFF_LINE
        },
        locked = false,
        action = if (on) TURN_OFF else TURN_ON,
        onItsOwn = on && !paused,
    )
}
