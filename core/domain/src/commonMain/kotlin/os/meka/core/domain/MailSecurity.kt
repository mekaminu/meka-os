package os.meka.core.domain

/**
 * Account-security mail is never tidied (Meka, 2026-10-17 04:05: two Google "Security alert" emails were archived as
 * notifications). An email from a provider's **real** domain whose subject is about the account's security (a security
 * alert, a new sign-in, a password or 2-Step change, a recovered account) is sorted Needs you for sure and kept in the
 * inbox whatever else is true of it (a no-reply sender, Gmail's Updates, his "Always tidy" rule on the sender), and its
 * card in Needs you says "Security alert from Google · check it was you". A sign-in or verification code alone is kept
 * in the inbox as FYI (he asked for it, usually) but never tidied either. In Junk, a real one is never trashed (left for
 * him, like a bank's). The same words from a domain that isn't the brand's are not this: the scam rules see those.
 * Headers only (sender address and subject), pure, unit-tested.
 */
enum class MailSecurityKind { ALERT, CODE }

object MailSecurityRules {
    /**
     * Providers whose account-security mail Navilife recognises, with the domains that are really theirs. Never a domain
     * anyone can get a mailbox on (gmail.com, outlook.com, icloud.com, yahoo.com): a stranger there is not the provider.
     */
    val SENDERS: List<Pair<String, List<String>>> = listOf(
        "Google" to listOf("google.com", "accounts.google.com"),
        "Microsoft" to listOf("microsoft.com", "accountprotection.microsoft.com"),
        "Apple" to listOf("apple.com", "id.apple.com"),
        "Amazon" to listOf("amazon.co.uk", "amazon.com"),
        "PayPal" to listOf("paypal.co.uk", "paypal.com"),
        "Samsung" to listOf("samsung.com", "samsung-mail.com"),
        "Facebook" to listOf("facebookmail.com", "facebook.com"),
        "Instagram" to listOf("instagram.com", "mail.instagram.com"),
        "WhatsApp" to listOf("whatsapp.com"),
        "X" to listOf("x.com", "twitter.com"),
        "LinkedIn" to listOf("linkedin.com"),
        "GitHub" to listOf("github.com"),
        "Dropbox" to listOf("dropbox.com", "dropboxmail.com"),
        "Yahoo" to listOf("yahoo-inc.com"),
        "Netflix" to listOf("netflix.com"),
        "Spotify" to listOf("spotify.com"),
        "Discord" to listOf("discord.com"),
        "Lunch Flow" to listOf("lunchflow.app"),
    )

    /** Subject words that make it about the account's security (lower case). */
    val ALERT_WORDS = listOf(
        "security alert", "security notification", "security info", "security information", "security settings",
        "critical security", "account security", "new sign-in", "new sign in", "sign-in attempt", "sign in attempt",
        "signed in", "unusual sign", "suspicious sign", "suspicious activity", "unusual activity", "new login", "new device",
        "login attempt", "logged in", "password was changed", "password changed", "password has been changed",
        "password was reset", "password has been reset", "reset your password", "password reset", "2-step", "two-step",
        "2-factor", "two-factor", "2fa", "account was recovered", "account recovered", "recovered successfully",
        "recovery email", "recovery phone", "account recovery", "access to your account", "account is locked",
        "account was locked", "account has been locked", "was used to sign in", "passkey",
    )

    /** Subject words of a one-time code (lower case). */
    val CODE_WORDS = listOf(
        "verification code", "security code", "sign-in code", "sign in code", "login code", "one-time code",
        "one time code", "passcode", "confirmation code", "your code",
    )

    private fun endsWithDomain(domain: String, suffix: String) = domain == suffix || domain.endsWith(".$suffix")

    /** The provider an address really belongs to ("Google" for no-reply@accounts.google.com), or null. */
    fun provider(fromAddress: String?): String? {
        val domain = MailSortRules.domain(fromAddress) ?: return null
        return SENDERS.firstOrNull { (_, ds) -> ds.any { endsWithDomain(domain, it) } }?.first
    }

    /** What kind of account-security email it is, or null: only from a provider's real domain. */
    fun kind(fromAddress: String?, subject: String?): MailSecurityKind? {
        provider(fromAddress) ?: return null
        val s = subject?.lowercase()?.replace('‑', '-')?.replace('‐', '-') ?: return null
        return when {
            ALERT_WORDS.any { it in s } -> MailSecurityKind.ALERT
            CODE_WORDS.any { it in s } -> MailSecurityKind.CODE
            else -> null
        }
    }

    /** True for a sort these rules made: such an email is never tidied, archived or trashed. */
    fun kept(reason: MailSortReason?): Boolean = reason == MailSortReason.SECURITY || reason == MailSortReason.SECURITY_CODE

    /** The sort for an account-security email, or null for anything else. */
    fun sorted(fromAddress: String?, subject: String?): MailSorted? = when (kind(fromAddress, subject)) {
        MailSecurityKind.ALERT -> MailSorted(MailSort.NEEDS_YOU, MailSortReason.SECURITY, true)
        MailSecurityKind.CODE -> MailSorted(MailSort.FYI, MailSortReason.SECURITY_CODE, true)
        null -> null
    }

    /** The card's line in Needs you: "Security alert from Google · check it was you". */
    fun line(provider: String): String = "Security alert from $provider · check it was you"

    /** What the server writes on the card's `mail_item` ([MailItemFields.SECURITY]): the provider, or "" for none. */
    fun itemProvider(reason: MailSortReason?, fromAddress: String?): String =
        if (reason == MailSortReason.SECURITY) provider(fromAddress).orEmpty() else ""
}
