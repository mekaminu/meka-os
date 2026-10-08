package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * MEKA's own keys that run out (build plan: Outlook calendar; AI layer). The secrets MEKA's server uses to sign in to
 * Microsoft and to call the AI have an end date the owner set when he made them; when one passes, Outlook (or the AI)
 * just stops. So each one is a renewal on the radar from the start: a one-off `obligation` the server writes once per
 * household with fixed ids and op ids (backend `OwnKeyReminders`), so it shows under Lists → Renewals ("Later"), comes
 * into Needs you's lists card from [OwnKey.showFromDay] ("1 renewal due") and can be edited, renewed or stopped like
 * any other. Written once only: Meka's later edits win (they are newer), and a deleted one never comes back.
 *
 * Non-AI, pure. No secret value is ever here: only what the key is, where it lives and its end date.
 */
data class OwnKey(
    /** The obligation's entity id (wire ids are `[a-z0-9]`). */
    val id: String,
    val title: String,
    /** What it is for: "MEKA · Outlook calendar". */
    val subject: String,
    /** How to renew it, step by step (the obligation's notes). */
    val notes: String,
    /** The day it stops working (local epoch day). */
    val expiresDay: Long,
    /** The first day it shows in Needs you (local epoch day). */
    val showFromDay: Long,
    /** How it comes round once renewed; null for a one-off. */
    val repeat: Recurrence?,
) {
    /** Days before the end date it shows: the obligation's lead time. */
    val leadDays: Int get() = (expiresDay - showFromDay).toInt()
}

object OwnKeyRules {
    /** The obligation's provenance: written by MEKA's server, not typed by Meka. */
    const val SOURCE = "meka:keys"

    /**
     * The Outlook sign-in secret (Azure "Default Directory", app registration made 2026-10-07): a client secret runs
     * at most 24 months, so "Renewed" moves it on two years. Shown in Needs you from 20 Sep 2028.
     */
    val OUTLOOK = OwnKey(
        id = "keyoutlooksecret",
        title = "Renew MEKA's Outlook sign-in secret",
        subject = "MEKA · Outlook calendar",
        notes = listOf(
            "When it runs out (6 Oct 2028) MEKA can no longer read or change your Outlook calendar.",
            "1. portal.azure.com → App registrations → MEKA OS → Certificates & secrets → New client secret (24 months).",
            "2. Copy its Value into AWS Secrets Manager, meka-os-dev/oauth/microsoft, as \"client_secret\" (keep \"client_id\").",
            "3. Tap Renewed here, or set the new secret's end date.",
            "Nothing needs reconnecting: the server reads the secret again on its own.",
        ).joinToString("\n"),
        expiresDay = CivilDate.toEpochDay(2028, 10, 6),
        showFromDay = CivilDate.toEpochDay(2028, 9, 20),
        repeat = Recurrence.Yearly(2, 10, 6),
    )

    /** The AI key (Anthropic Console, saved 2026-10-07; one year). Shown in Needs you from 20 Sep 2027. */
    val AI = OwnKey(
        id = "keyaiapikey",
        title = "Renew MEKA's AI key",
        subject = "MEKA · AI layer",
        notes = listOf(
            "When it runs out (7 Oct 2027) MEKA's AI features stop; everything else keeps working.",
            "1. console.anthropic.com → API keys → Create key (keep the monthly cap).",
            "2. Paste it into AWS Secrets Manager, meka-os-dev/ai/anthropic, as \"api_key\".",
            "3. Tap Renewed here, or set the new key's end date.",
        ).joinToString("\n"),
        expiresDay = CivilDate.toEpochDay(2027, 10, 7),
        showFromDay = CivilDate.toEpochDay(2027, 9, 20),
        repeat = Recurrence.Yearly(1, 10, 7),
    )

    val ALL: List<OwnKey> = listOf(OUTLOOK, AI)

    /**
     * The obligation's fields for [key], as written once. The end date sits at 09:00 on its day in [calendar] (like
     * every renewal); [nowMs] is when it was written.
     */
    fun fields(key: OwnKey, nowMs: Long, calendar: LocalCalendar): Map<String, FieldValue> = linkedMapOf(
        ActionableFields.TITLE to key.title.fv(),
        ObligationFields.KIND to ObligationKind.LICENCE.name.fv(),
        ObligationFields.SUBJECT_LABEL to key.subject.fv(),
        ActionableFields.NOTES to key.notes.fv(),
        ActionableFields.DUE_AT to calendar.toEpochMs(key.expiresDay, Lists.DATE_MINUTE).fv(),
        ObligationFields.LEAD_DAYS to key.leadDays.fv(),
        ObligationFields.RECURRENCE to (key.repeat?.encode()?.fv() ?: FieldValue.Null),
        ActionableFields.LIFECYCLE to Lifecycle.ACTIVE.name.fv(),
        ActionableFields.CREATED_AT to nowMs.fv(),
        ActionableFields.VISIBILITY to Visibility.PRIVATE.name.fv(),
        ActionableFields.PROVENANCE_SOURCE to SOURCE.fv(),
        ActionableFields.PROVENANCE_TRUST to Trust.DERIVED.name.fv(),
    )
}
