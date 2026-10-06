package os.meka.android.work

import os.meka.core.domain.CaptureApp

/**
 * Which notifications the work-mode listener reads, kept free of Android types so they are unit-tested.
 * Official notification access only (hard constraint): no WhatsApp libraries, nothing replies or marks read.
 */
object NotificationRules {
    val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    val SMS_PACKAGES = setOf("com.samsung.android.messaging", "com.google.android.apps.messaging")
    val DIALER_PACKAGES = setOf("com.samsung.android.dialer", "com.google.android.dialer", "com.android.server.telecom", "com.android.phone")
    const val CATEGORY_MISSED_CALL = "missed_call" // Notification.CATEGORY_MISSED_CALL

    /** Null for everything MEKA leaves alone. [defaultSms] is the phone's default SMS app, if any. */
    fun appFor(packageName: String, category: String?, defaultSms: String?): CaptureApp? = when {
        packageName in WHATSAPP_PACKAGES -> CaptureApp.WHATSAPP
        packageName in SMS_PACKAGES || packageName == defaultSms -> CaptureApp.SMS
        packageName in DIALER_PACKAGES && (category == CATEGORY_MISSED_CALL || category == null) -> CaptureApp.PHONE
        else -> null
    }

    private val missedCallTitle = Regex("^missed (voice |video )?call", RegexOption.IGNORE_CASE)
    private val countOnly = Regex("^\\d+\\s+(new\\s+)?(messages?|missed calls?)(\\s+from\\s+\\d+\\s+chats?)?\\.?$", RegexOption.IGNORE_CASE)

    /**
     * Missed-call notifications name the caller either as the title, or as the text under a "Missed call" title.
     * Null when it's only a count ("2 missed calls").
     */
    fun missedCallPerson(title: String?, text: String?): String? {
        val t = title?.trim().orEmpty()
        val x = text?.trim().orEmpty()
        val who = if (missedCallTitle.containsMatchIn(t) || t.isEmpty()) x else t
        return who.takeIf { it.isNotEmpty() && !countOnly.matches(it) }
    }

    /** Whether a dialer notification is a missed call at all (Samsung leaves the category unset on some builds). */
    fun looksLikeMissedCall(category: String?, title: String?): Boolean =
        category == CATEGORY_MISSED_CALL || missedCallTitle.containsMatchIn(title?.trim().orEmpty())

    /** App-level summaries ("5 new messages from 2 chats", a title of just "WhatsApp") carry no person: skip them. */
    fun isSummaryOnly(title: String?, text: String?): Boolean {
        val t = title?.trim().orEmpty()
        val x = text?.trim().orEmpty()
        return t.isEmpty() || t.equals("WhatsApp", ignoreCase = true) || countOnly.matches(x) || x.isEmpty()
    }

    /** WhatsApp group fallbacks title messages "Group: Sender"; split them. */
    fun splitGroupTitle(title: String): Pair<String?, String> {
        val i = title.indexOf(": ")
        return if (i in 1 until title.length - 2) title.substring(0, i) to title.substring(i + 2) else null to title
    }
}
