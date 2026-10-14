package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelpTest {
    private fun strongId(q: String): String? = HelpRules.lookup(q)?.takeIf { it.strong }?.entry?.id

    @Test
    fun featureNamesAndHowQuestionsAreAnsweredFromHelp() {
        val cases = mapOf(
            "flex mode" to "bedside-clock",
            "Flex mode?" to "bedside-clock",
            "bedside clock" to "bedside-clock",
            "Why isn't the bedside clock showing?" to "bedside-clock",
            "how do I get the night clock when it's half folded" to "bedside-clock",
            "how do I set the screen saver" to "screen-saver",
            "call assistant" to "call-assistant",
            "How does the call assistant work?" to "call-assistant",
            "what's the call assistant?" to "call-assistant",
            "mark read" to "mark-read",
            "what does mark read do?" to "mark-read",
            "how do I block a number" to "spam-calls",
            "spam calls still ring, not working" to "spam-calls",
            "where is the morning brief" to "morning-brief",
            "how do I turn on dark mode" to "appearance",
            "where's Leaving now?" to "leaving-now",
            "how do I connect Gmail" to "mail",
            "how do I link my watch" to "watch",
            "how do I share the shopping list" to "family",
            "how do I export my data" to "your-data",
            "where do I find the recovery key" to "recovery-key",
            "how do I add a widget" to "widgets",
            "tell me about shut down the day" to "shut-down",
            "how do I install updates" to "updates",
        )
        cases.forEach { (q, id) -> assertEquals(id, strongId(q), q) }
    }

    @Test
    fun questionsAboutHisOwnDataAreLeftToTheRules() {
        // Mentions a feature but asks about the day: no help answer (the AI gets the help as context instead).
        listOf(
            "when is the morning brief",
            "which day is the week ahead from",
            "what's on the morning brief today",
            "how long is the call assistant message",
        ).forEach { q -> assertFalse(HelpRules.lookup(q)?.strong == true, q) }
        // No feature at all.
        assertNull(HelpRules.lookup("what's for dinner tonight?"))
        assertNull(HelpRules.lookup("buy milk"))
        assertNull(HelpRules.lookup(""))
    }

    @Test
    fun theLongestPhraseWins() {
        // "voice message" (the call assistant) beats "voice" (Talk).
        assertEquals("call-assistant", HelpRules.lookup("how do I hear a voice message")?.entry?.id)
        // "the bedside clock" beats "clock" alone; "while charging" is the screen saver's.
        assertEquals("bedside-clock", HelpRules.lookup("how do I set the bedside clock as screen saver")?.entry?.id)
        assertEquals("screen-saver", HelpRules.lookup("how do I show the clock while charging")?.entry?.id)
    }

    @Test
    fun theFoldAnswerSaysWhereAndTakesHimThere() {
        val e = assertNotNull(HelpRules.entry("bedside-clock"))
        val a = HelpRules.answer(e, mac = false)
        assertTrue(a.text.startsWith("Bedside clock: "), a.text)
        assertTrue("On the Fold: Ask → Daily → Bedside clock." in a.text, a.text)
        assertTrue("Flex mode panel" in a.text, a.text)
        assertTrue(a.cards.isEmpty())
        assertEquals(HelpLink("bedside-clock", HelpPlace.BEDSIDE, "Take me there"), a.help)
    }

    @Test
    fun theMacSaysFoldOnlyOrGivesItsOwnPath() {
        val fold = HelpRules.answer(assertNotNull(HelpRules.entry("bedside-clock")), mac = true)
        assertTrue(fold.text.startsWith("Bedside clock is on the Fold only: "), fold.text)
        assertNull(fold.help)
        val mail = HelpRules.answer(assertNotNull(HelpRules.entry("mail")), mac = true)
        assertTrue("On the Mac: Ask → Settings → Mail." in mail.text, mail.text)
        assertEquals(HelpPlace.MAIL, mail.help?.place)
        // The Mac's own words where it works differently.
        val call = HelpRules.answer(assertNotNull(HelpRules.entry("call-assistant")), mac = true)
        assertTrue("the screening itself happens on the Fold" in call.text, call.text)
    }

    @Test
    fun androidSettingsPagesAreFoldOnly() {
        val saver = HelpRules.answer(assertNotNull(HelpRules.entry("screen-saver")), mac = false)
        assertEquals(HelpPlace.SYSTEM_SCREEN_SAVER, saver.help?.place)
        assertTrue(HelpPlace.SYSTEM_SCREEN_SAVER.system)
        assertFalse(HelpPlace.WORK.system)
        HelpRules.entries.forEach { e -> assertFalse(e.mac?.system == true, e.id) }
    }

    @Test
    fun theAiGetsTheEntriesAQuestionMentions() {
        val lines = HelpRules.askLines("can the call assistant take a message from my boss?", mac = false)
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.startsWith("Help · Call assistant") && it.length <= HelpRules.MAX_LINE }, lines.toString())
        assertTrue(lines.any { "on the Fold: Ask → Settings → Work mode → Call assistant" in it }, lines.toString())
        val mac = HelpRules.askLines("does the bedside clock show the weather?", mac = true)
        assertTrue(mac.any { "Fold only" in it }, mac.toString())
        assertTrue(HelpRules.askLines("what's for dinner", mac = false).isEmpty())
    }

    @Test
    fun helpLinesGoToTheModelFirstAndAreNotUntrusted() {
        val ctx = AskRules.context(Today(emptyList(), null, emptyList(), emptyList()), 0L, LocalCalendar.UTC, help = listOf("Help · Bedside clock: a calm night clock"))
        assertEquals(AskItemKind.HELP, ctx.items.first().kind)
        assertEquals("help", AskItemKind.HELP.wire)
        assertFalse(ctx.untrusted)
    }

    @Test
    fun everyEntryIsWellFormed() {
        val ids = HelpRules.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(HelpRules.entries.size >= 20)
        HelpRules.entries.forEach { e ->
            assertTrue(e.keywords.isNotEmpty(), e.id)
            assertTrue(e.what.isNotBlank() && e.how.isNotBlank() && e.foldWhere.isNotBlank(), e.id)
            assertFalse("MEKA" in e.what + e.how + e.tips.joinToString(), e.id)
        }
        // Every Navilife place has a Mac key; Android's pages have none.
        HelpPlace.entries.forEach { p -> assertEquals(p.system, p.key.isEmpty(), p.name) }
    }


    private fun search(q: String, mac: Boolean = false): SearchView = Search.run(
        q, SearchSources(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            help = HelpRules.entries, helpOnMac = mac),
        0L, LocalCalendar.UTC)

    @Test
    fun searchFindsHelpEntriesAndOpensWhereTheyLive() {
        // Slice 2: "flex" (a keyword) finds the bedside clock; the hit opens it like Take me there.
        val hit = search("flex").groups.single { it.kind == SearchKind.HELP }.hits.first()
        assertEquals("help:bedside-clock", hit.id)
        assertEquals("Bedside clock", hit.title)
        assertEquals("Ask → Daily → Bedside clock", hit.detail)
        assertEquals(SearchTarget.HELP, hit.target)
        assertEquals(HelpPlace.BEDSIDE, hit.place)
        // In Ask's field it reads "Help · …" and opens.
        val row = AskFieldRules.row(hit)
        assertEquals("Help · Ask → Daily → Bedside clock", row.line)
        assertTrue(row.opens)
        // An Android page is still a place on the Fold.
        assertEquals(HelpPlace.SYSTEM_SCREEN_SAVER, search("screen saver").groups.single { it.kind == SearchKind.HELP }.hits
            .first { it.id == "help:screen-saver" }.place)
        // The title weighs most: "call assistant" puts the Call assistant first.
        assertEquals("help:call-assistant", search("call assistant").groups.single { it.kind == SearchKind.HELP }.hits.first().id)
        // Help comes after his own things and before done tasks.
        assertTrue(SearchKind.HELP.ordinal > SearchKind.HABIT.ordinal && SearchKind.HELP.ordinal < SearchKind.DONE.ordinal)
        // No help unless the sources carry it (the other searches are unchanged).
        assertTrue(Search.run("flex", SearchSources(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList()), 0L, LocalCalendar.UTC).groups.isEmpty())
    }

    @Test
    fun onTheMacAFoldOnlyFeatureIsShownNotOpened() {
        val bedside = search("bedside", mac = true).groups.single { it.kind == SearchKind.HELP }.hits.first()
        assertEquals("Fold only · Ask → Daily → Bedside clock", bedside.detail)
        assertEquals(SearchTarget.INFO, bedside.target)
        assertNull(bedside.place)
        assertFalse(AskFieldRules.row(bedside).opens)
        val mail = search("gmail", mac = true).groups.single { it.kind == SearchKind.HELP }.hits.first { it.id == "help:mail" }
        assertEquals(HelpPlace.MAIL, mail.place)
        assertEquals(SearchTarget.HELP, mail.target)
        // An Android page never opens on the Mac.
        search("screen saver", mac = true).hits.forEach { assertFalse(it.place?.system == true, it.id) }
    }

    @Test
    fun everyHelpAnswerOffersStillStuck() {
        HelpRules.entries.forEach { e ->
            assertEquals("Still stuck? Ask Navilife", HelpRules.answer(e, mac = false).stillStuck, e.id)
            assertEquals("Still stuck? Ask Navilife", HelpRules.answer(e, mac = true).stillStuck, e.id)
        }
        assertNull(AskAnswer("Fine", emptyList()).stillStuck)
    }

    // ---- Slice 3: every feature has an entry; names that are also his data stay data questions ----

    @Test
    fun everyScreenHasAnEntry() {
        val opened = HelpRules.entries.flatMap { listOfNotNull(it.fold, it.mac) }.toSet()
        HelpPlace.entries.filter { !it.system && it != HelpPlace.SETTINGS }.forEach { p -> assertTrue(p in opened, p.name) }
    }

    @Test
    fun theNewEntriesAnswerHowAndWhere() {
        mapOf(
            "how do I add a bin" to "bins",
            "where is the shopping list" to "lists",
            "life admin hour" to "life-admin",
            "what's protected time?" to "protected-time",
            "how do I add an INSET day" to "school",
            "how do I save a link for the train" to "train-queue",
            "where's the emergency card" to "emergency",
            "date night" to "date-night",
            "how do I add a passport to the vault" to "vault",
            "how do I connect my calendar" to "calendars",
            "how do I choose news topics" to "news",
        ).forEach { (q, id) -> assertEquals(id, strongId(q), q) }
    }

    @Test
    fun aFeatureNamedLikeHisDataIsLeftToTheData() {
        listOf(
            "what's the news?", "news", "bins?", "what's on my calendar", "what's on the shopping list",
            "what's in my train queue", "when are the bins", "is there any news", "tell me about my trips",
            "what are my goals", "start a 16-hour fast",
        ).forEach { q -> assertFalse(HelpRules.lookup(q)?.strong == true, q) }
    }
}
