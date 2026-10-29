package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObiRestTest {
    private fun emergency(vararg rows: Pair<EmergencySection, Pair<String, String?>>) = EmergencyView(
        EmergencySection.entries.map { s ->
            EmergencyGroup(s.key, s.label, s.hint, rows.filter { it.first == s }.mapIndexed { i, (_, r) ->
                EmergencyRow("${s.key}$i", s.key, r.first, r.second, null, r.first)
            })
        },
        "x", rows.size,
    )

    @Test
    fun theEmergencyCardIsAllThereWhenAskedAndOneCountsLineOtherwise() {
        val v = emergency(
            EmergencySection.CALL to ("GP" to "Dr Lee, 01632 960000"),
            EmergencySection.CALL to ("Plumber" to null),
            EmergencySection.DOCUMENTS to ("Passports" to "study desk, top drawer"),
            EmergencySection.ACCOUNTS to ("Home insurance" to "Acme Mutual"),
        )
        val head = "Emergency card · 4 lines: 2 to call, 1 where things are, 1 key account"
        assertEquals(listOf(head), ObiRestRules.emergencyLines(v, full = false))
        val all = ObiRestRules.emergencyLines(v, full = true)
        assertEquals(head, all.first())
        assertEquals("Who to call · GP · Dr Lee, 01632 960000", all[1])
        assertEquals("Who to call · Plumber", all[2])
        assertEquals("Where things are · Passports · study desk, top drawer", all[3])
        assertEquals(emptyList(), ObiRestRules.emergencyLines(EmergencyView.EMPTY, full = true))
        assertTrue(ObiRestRules.emergencyAsked("Who do I call if the boiler floods?"))
        assertTrue(ObiRestRules.emergencyAsked("where are the passports"))
        assertFalse(ObiRestRules.emergencyAsked("will it rain tomorrow?"))
    }

    private fun story(id: String, title: String, read: Boolean = false) =
        NewsItem(id, title, "https://example.com/$id", "The Paper", "ai", "2 h ago", null, 0L, read = read)

    @Test
    fun newsCountsCarryNoHeadlineAndTheFullLinesPutNewStoriesFirst() {
        val place = NewsPlace(
            listOf(
                NewsLane("barca", "Barça", "", listOf(story("b1", "Old match report", read = true), story("b2", "Late winner"))),
                NewsLane("ai", "AI", "", listOf(story("a1", "New model out"))),
                NewsLane("tech", "Tech", "", emptyList()),
            ),
            emptyList(), null,
        )
        val counts = ObiRestRules.newsLines(place, full = false)
        assertEquals(listOf("News · 2 new · Barça 1, AI 1"), counts)
        assertFalse("winner" in counts.single())
        val all = ObiRestRules.newsLines(place, full = true)
        assertEquals("News · Barça · Late winner · The Paper · 2 h ago · new", all[1])
        assertEquals("News · Barça · Old match report · The Paper · 2 h ago", all[2])
        assertEquals("News · AI · New model out · The Paper · 2 h ago · new", all[3])
        assertEquals(emptyList(), ObiRestRules.newsLines(NewsPlace.EMPTY, full = true))
        assertTrue(ObiRestRules.newsAsked("Anything in the news?"))
        assertTrue(ObiArea.NEWS.untrusted)
    }

    @Test
    fun voiceNotesSayWhenAndWhatWasSaid() {
        val rows = listOf(
            VoiceNoteRow("n1", "Today 14:02 · 1:23", "Book the car in and call Sam about Saturday", heard = true,
                peopleLine = "Mentions Sam", keepLine = "Kept until Fri 13 Nov", kept = false, cardsLine = "2 cards offered", canMakeCards = false),
            VoiceNoteRow("n2", "Mon 26 Oct 08:10 · 0:12", "Nothing was heard", heard = false, keepLine = "k", kept = false, cardsLine = null, canMakeCards = false),
        )
        assertEquals(listOf("Voice notes · 2 notes kept · the newest Today 14:02"), ObiRestRules.voiceNoteLines(rows, full = false))
        val all = ObiRestRules.voiceNoteLines(rows, full = true)
        assertEquals("Voice note · Today 14:02 · 1:23 · Book the car in and call Sam about Saturday · Mentions Sam · 2 cards offered", all[1])
        assertEquals("Voice note · Mon 26 Oct 08:10 · 0:12 · no words heard", all[2])
        val long = rows[0].copy(text = "word ".repeat(100))
        assertTrue(ObiRestRules.voiceNoteLines(listOf(long), full = true)[1].contains("…"))
        assertTrue(ObiRestRules.voiceNotesAsked("what did I say in my voice notes?"))
    }

    @Test
    fun healthLeadsWithWhatNeedsALook() {
        val v = HealthView(
            listOf(
                HealthRow("server", "Server", "reached 14:05", HealthState.OK),
                HealthRow("calendar:google:a", "Calendar", "Google sign-in expired", HealthState.BAD, fixLabel = "Reconnect"),
            ),
            "1 thing needs a look", 1, true, null, 0L, checkedLine = "Checked 14:05",
        )
        assertEquals(listOf("Navilife's health · 1 thing needs a look · Checked 14:05"), ObiRestRules.healthLines(v, full = false))
        val all = ObiRestRules.healthLines(v, full = true)
        assertEquals("Calendar · Google sign-in expired · broken · fix: Reconnect", all[1])
        assertEquals("Server · reached 14:05 · fine", all[2])
        assertEquals(listOf(ObiRestRules.HEALTH_NOT_CHECKED), ObiRestRules.healthLines(null, full = true))
        assertEquals(emptyList(), ObiRestRules.healthLines(null, full = false))
        assertTrue(ObiRestRules.healthAsked("is navilife syncing?"))
        assertFalse(ObiRestRules.healthAsked("how's my working week?"))
    }

    @Test
    fun theVaultSendsNamesAndDates() {
        val v = DocumentsView(
            listOf(DocumentRow("d1", "Railcard", "Runs out Thu 20 May 2027", "7 months left", false, "x", copyLine = "Photo · 412 KB")),
            "1 document · next to run out: Railcard, May 2027",
        )
        assertEquals(listOf("Vault · 1 document · next to run out: Railcard, May 2027"), ObiRestRules.vaultLines(v, full = false))
        assertEquals("Vault · Railcard · Runs out Thu 20 May 2027 · 7 months left · copy kept", ObiRestRules.vaultLines(v, full = true)[1])
        assertEquals(emptyList(), ObiRestRules.vaultLines(DocumentsView.EMPTY, full = true))
        assertTrue(ObiRestRules.vaultAsked("when does my passport expire?"))
    }
}
