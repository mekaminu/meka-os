package os.meka.core.domain

/**
 * Money bugs (A4), Meka 2026-10-24: the month in review said "From your statements… worked out on this device, nothing
 * sent" when his lines came from his banks through Lunch Flow. Each Money footer now names where its lines really came
 * from: statement files he imported ([FILES]), his banks through Lunch Flow ([BANK]), or both ([BOTH]). "Nothing sent"
 * is said only for statement files alone (bank lines reach the device through Navilife's server from Lunch Flow).
 * Pure; nothing stored.
 */
enum class MoneySource {
    FILES, BANK, BOTH;

    /** "your statements" / "your banks through Lunch Flow" / "your banks through Lunch Flow and your statement files". */
    val phrase: String get() = when (this) {
        FILES -> "your statements"
        BANK -> "your banks through Lunch Flow"
        BOTH -> "your banks through Lunch Flow and your statement files"
    }

    /** "From your statements" and the like, to start a footer. */
    val from: String get() = "From $phrase"

    /**
     * Where the lines are held, mid-sentence ("nothing paid abroad in your bank payments yet", "Your payments reach Tue 6 Oct"):
     * "your statements" for statement files alone, "your bank payments" through Lunch Flow, "your payments" for both.
     */
    val held: String get() = when (this) {
        FILES -> "your statements"
        BANK -> "your bank payments"
        BOTH -> "your payments"
    }

    /** [held] starting a sentence: "Your bank payments". */
    val heldCap: String get() = held.replaceFirstChar { it.uppercaseChar() }

    /** The end of a money answer: " From your own statements · not financial advice." or where the lines really came from. */
    val notAdvice: String get() = if (this == FILES) MoneyAskRules.NOT_ADVICE else " $from · not financial advice."

    /** How the figures were worked out: on this device, and for statement files alone, nothing sent. */
    val worked: String get() = if (this == FILES) "Worked out on this device, nothing sent." else "Worked out on this device."

    companion object {
        /** Where the lines of [accounts] came from; [FILES] when there are none (the old wording). */
        fun of(accounts: Iterable<MoneyAccount>): MoneySource {
            var bank = false
            var files = false
            for (a in accounts) if (a.source == MoneyBank.SOURCE) bank = true else files = true
            return when {
                bank && files -> BOTH
                bank -> BANK
                else -> FILES
            }
        }

        /** [of] over the accounts that have at least one of [lineAccounts] (an account with no lines says nothing). */
        fun ofLines(accounts: List<MoneyAccount>, lineAccounts: Set<String>): MoneySource =
            of(accounts.filter { it.id in lineAccounts })
    }
}
