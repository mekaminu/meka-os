package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ask MEKA on the wire (build plan V1, AI layer slice 3). */
class AskCodecTest {
    private val req = AskCodec.Request(
        "What's left today?", "2026-10-08", "Thursday 8 October 2026 · 17:05",
        listOf(AskCodec.Item("t1", "task", "Book dentist · anytime today"), AskCodec.Item("", "event", "19:00–20:00 · Training")),
    )

    @Test
    fun aRequestRoundTripsAndLimitsAreRefusedNotTrimmed() {
        assertEquals(req, AskCodec.decodeRequest(AskCodec.encodeRequest(req)))
        // The shopping list's one line (family sharing): a kind of its own.
        val shop = req.copy(items = listOf(AskCodec.Item("", "shopping", "Shopping list · 2 to buy: milk, eggs")))
        assertEquals(shop, AskCodec.decodeRequest(AskCodec.encodeRequest(shop)))
        // Theo's and Finn's school year (school rhythm, slice 2b): a kind of its own.
        val school = req.copy(items = listOf(AskCodec.Item("", "school", "Day off · Half term · Mon 26 – Fri 30 Oct · Theo and Finn")))
        assertEquals(school, AskCodec.decodeRequest(AskCodec.encodeRequest(school)))
        // The week's dinners (meal plan, slice 2): a kind of its own.
        val meals = req.copy(items = listOf(AskCodec.Item("", "meals", "Dinners · tonight: Chilli · Tomorrow: not planned")))
        assertEquals(meals, AskCodec.decodeRequest(AskCodec.encodeRequest(meals)))
        // Date night's coming nights (date night, slice 3): a kind of its own.
        val night = req.copy(items = listOf(AskCodec.Item("", "date_night", "Date night · every other Friday from 19:00 · coming: Fri 23 Oct")))
        assertEquals(night, AskCodec.decodeRequest(AskCodec.encodeRequest(night)))
        // The life admin hour (slice 3): a kind of its own.
        val admin = req.copy(items = listOf(AskCodec.Item("", "life_admin", "Life admin hour · Saturdays from 10:00 · 1 job: Call the GP")))
        assertEquals(admin, AskCodec.decodeRequest(AskCodec.encodeRequest(admin)))
        // The gift planner (slice 2): a kind of its own.
        val gifts = req.copy(items = listOf(AskCodec.Item("", "gifts", "Mum's birthday · Tue 3 Nov · in 22 days · ideas: Silk scarf")))
        assertEquals(gifts, AskCodec.decodeRequest(AskCodec.encodeRequest(gifts)))
        // People (slice 3): a kind of its own.
        val people = req.copy(items = listOf(AskCodec.Item("", "people", "Theo · you promised: Fix his bike · by tomorrow")))
        assertEquals(people, AskCodec.decodeRequest(AskCodec.encodeRequest(people)))
        // Travel mode (slice 2a): a kind of its own.
        val trips = req.copy(items = listOf(AskCodec.Item("", "trips", "Barcelona · Flight · Sat 14 Nov 07:40 · Flight BA478 · ref ABC123")))
        val notify = req.copy(items = listOf(AskCodec.Item("", "notifications", "Trace · 12 Oct 20:41 · Looked · nothing due · next look 21:10")))
        assertEquals(notify, AskCodec.decodeRequest(AskCodec.encodeRequest(notify)))
        assertEquals(trips, AskCodec.decodeRequest(AskCodec.encodeRequest(trips)))
        // The renewals radar (home upkeep, slice 2): a kind of its own.
        val radar = req.copy(items = listOf(AskCodec.Item("", "renewals", "Radar · Boiler service · due Mon 9 Nov · every year")))
        assertEquals(radar, AskCodec.decodeRequest(AskCodec.encodeRequest(radar)))
        // The work wins log (work wins, slice 2): a kind of its own.
        val wins = req.copy(items = listOf(AskCodec.Item("", "wins", "Win · Fri 9 Oct · Closed the Henley deal")))
        assertEquals(wins, AskCodec.decodeRequest(AskCodec.encodeRequest(wins)))
        // The train queue (train-time queue, slice 3): a kind of its own.
        val queue = req.copy(items = listOf(AskCodec.Item("", "queue", "Waiting · Article · bbc.co.uk · Why trains run late · saved Fri 9 Oct")))
        assertEquals(queue, AskCodec.decodeRequest(AskCodec.encodeRequest(queue)))
        // Annual leave (annual leave, slice 2b): a kind of its own.
        val leave = req.copy(items = listOf(AskCodec.Item("", "leave", "Annual leave · 4 of 25 days booked · 21 days left · Leave year Jan – Dec 2026")))
        assertEquals(leave, AskCodec.decodeRequest(AskCodec.encodeRequest(leave)))
        // What MEKA knows (facts page, slice 1): a kind of its own.
        val facts = req.copy(items = listOf(AskCodec.Item("", "facts", "Fact · Car · Car reg: AB12 CDE")))
        assertEquals(facts, AskCodec.decodeRequest(AskCodec.encodeRequest(facts)))
        // Protected time (slice 4): a kind of its own.
        val shield = req.copy(items = listOf(AskCodec.Item("", "protected", "Protected today · from 19:00 tonight")))
        assertEquals(shield, AskCodec.decodeRequest(AskCodec.encodeRequest(shield)))
        // Errands by location (slice 2b): a kind of its own.
        val errands = req.copy(items = listOf(AskCodec.Item("", "errands", "At Tesco · milk, batteries")))
        assertEquals(errands, AskCodec.decodeRequest(AskCodec.encodeRequest(errands)))
        // Checklists in tasks (slice 3b): a kind of its own.
        val lists = req.copy(items = listOf(AskCodec.Item("", "checklists", "Checklist “Party” · 1 of 3 ticked · to tick: balloons")))
        assertEquals(lists, AskCodec.decodeRequest(AskCodec.encodeRequest(lists)))
        // Goals, properly (slice 8): a kind of its own.
        val goals = req.copy(items = listOf(AskCodec.Item("", "goals", "Goal “Run 5k” · 40% · This month · Next · Buy shoes")))
        assertEquals(goals, AskCodec.decodeRequest(AskCodec.encodeRequest(goals)))
        // Bin days (slice 2): a kind of its own.
        val bins = req.copy(items = listOf(AskCodec.Item("", "bins", "Collection · Tue 13 Oct (tomorrow) · Black bin and food caddy")))
        assertEquals(bins, AskCodec.decodeRequest(AskCodec.encodeRequest(bins)))
        // Car on autopilot (slice 3): a kind of its own.
        val car = req.copy(items = listOf(AskCodec.Item("", "car", "DVLA · Ford · MOT due Mon 2 Nov · taxed until Tue 1 Dec")))
        assertEquals(car, AskCodec.decodeRequest(AskCodec.encodeRequest(car)))
        // The commute counts everywhere (slice 5): a kind of its own.
        val commute = req.copy(items = listOf(AskCodec.Item("", "commute", "Tomorrow · Fri 9 Oct · office day · leave 07:40 · work 09:00–17:30 · home about 18:50")))
        assertEquals(commute, AskCodec.decodeRequest(AskCodec.encodeRequest(commute)))
        // Edit your calendars, completeness slice 4: an event Navilife may move carries its handle ("e1").
        val movable = req.copy(items = listOf(AskCodec.Item("e1", "event", "15:00–16:00 · Dentist · Personal")))
        assertEquals(movable, AskCodec.decodeRequest(AskCodec.encodeRequest(movable)))
        fun bad(r: AskCodec.Request) = assertFailsWith<WireFormatException> { AskCodec.decodeRequest(AskCodec.encodeRequest(r)) }
        bad(req.copy(items = listOf(AskCodec.Item("e100", "event", "x"))))
        bad(req.copy(items = listOf(AskCodec.Item("e0", "event", "x"))))
        bad(req.copy(question = "  "))
        bad(req.copy(question = "q".repeat(501)))
        bad(req.copy(date = "tomorrow"))
        bad(req.copy(items = listOf(AskCodec.Item("task-uuid-123", "task", "x"))))
        bad(req.copy(items = listOf(AskCodec.Item("t1", "email", "x"))))
        bad(req.copy(items = listOf(AskCodec.Item("t1", "task", "x".repeat(161)))))
        bad(req.copy(items = List(61) { AskCodec.Item("", "event", "x") }))
        assertFailsWith<WireFormatException> { AskCodec.decodeRequest("""{"w":2,"question":"q","date":"2026-10-08","now":"","items":[]}""") }
        assertFailsWith<WireFormatException> { AskCodec.decodeRequest("not json") }
    }

    @Test
    fun aConversationsHistoryAndVoiceRoundTripAndAOneOffQuestionIsUnchangedOnTheWire() {
        val talk = req.copy(
            history = listOf(
                AskCodec.Turn("What's on today?", "Just the dentist."),
                AskCodec.Turn("Move it to tomorrow", "Tomorrow at 9.", listOf("Moved “Book dentist” to Tomorrow · 09:00")),
            ),
            voice = true,
        )
        assertEquals(talk, AskCodec.decodeRequest(AskCodec.encodeRequest(talk)))
        // An older server sees exactly what it saw before when there's no conversation.
        val plain = AskCodec.encodeRequest(req)
        assertTrue("history" !in plain && "voice" !in plain, plain)
        fun bad(r: AskCodec.Request) = assertFailsWith<WireFormatException> { AskCodec.decodeRequest(AskCodec.encodeRequest(r)) }
        bad(talk.copy(history = List(7) { AskCodec.Turn("q", "a") }))
        bad(talk.copy(history = listOf(AskCodec.Turn(" ", "a"))))
        bad(talk.copy(history = listOf(AskCodec.Turn("q", "a".repeat(1201)))))
        bad(talk.copy(history = listOf(AskCodec.Turn("q", "a", List(4) { "x" }))))
        bad(talk.copy(history = listOf(AskCodec.Turn("q", "a", listOf("x".repeat(161))))))
        assertEquals("""{"answer":"Tomorrow at 9.","actions":[]}""", AskCodec.encodeModelAnswer("Tomorrow at 9."))
    }

    @Test
    fun aResponseRoundTrips() {
        val r = AskCodec.Response(
            AskCodec.Response.ANSWERED, "Two things left.",
            listOf(AskCodec.Action("move_task", ref = "t1", date = "2026-10-09", time = "09:00"), AskCodec.Action("set_timer", minutes = 20)),
        )
        assertEquals(r, AskCodec.decodeResponse(AskCodec.encodeResponse(r)))
        val off = AskCodec.Response(AskCodec.Response.OFF, reason = "No AI key set")
        assertEquals(off, AskCodec.decodeResponse(AskCodec.encodeResponse(off)))
    }

    @Test
    fun aModelAnswerIsReadAsWordsAndActionsAndAnythingElseIsJustWords() {
        val (words, actions) = AskCodec.parseModelAnswer(
            """```json
            {"answer": "Moved it.", "actions": [{"kind": "move_task", "ref": "t1", "date": "2026-10-09", "time": "09:00", "hours": "12"},
              {"kind": 5}, "junk", {"kind": "set_timer", "minutes": 20}]}
            ```""",
        )
        assertEquals("Moved it.", words)
        // Wrong types are dropped field by field ("12" as a string isn't hours); an action with no kind is dropped.
        assertEquals(listOf(AskCodec.Action("move_task", "t1", null, "2026-10-09", "09:00", null, null), AskCodec.Action("set_timer", minutes = 20)), actions)

        val (plain, none) = AskCodec.parseModelAnswer("  You have two things left: dentist and the CR.  ")
        assertEquals("You have two things left: dentist and the CR.", plain)
        assertTrue(none.isEmpty())
        // JSON without an answer is not trusted for actions either.
        assertTrue(AskCodec.parseModelAnswer("""{"actions": [{"kind": "set_timer", "minutes": 5}]}""").second.isEmpty())
        // At most five actions are read.
        val many = (1..9).joinToString(",") { """{"kind":"set_timer","minutes":$it}""" }
        assertEquals(5, AskCodec.parseModelAnswer("""{"answer":"ok","actions":[$many]}""").second.size)
        assertEquals(AskCodec.MAX_ANSWER, AskCodec.parseModelAnswer("z".repeat(3000)).first.length)
    }

    @Test
    fun theStatusReadsOnOffAndTheMonthsSpend() {
        val on = AskCodec.decodeStatus(
            """{"state":"on","checkedAtMs":1,"budget":{"month":"2026-10","spentCents":120,"budgetCents":2000,"level":"ok","features":[{"feature":"ask","calls":3,"cents":120}]}}""",
        )
        assertEquals(AskCodec.Status("on", null, 120, 2000, "ok", month = listOf(AskCodec.MonthUse("ask", 3, 120))), on)
        assertEquals(AskCodec.Status("off", "No AI key set", null, null, null), AskCodec.decodeStatus("""{"state":"off","reason":"No AI key set"}"""))
        assertFailsWith<WireFormatException> { AskCodec.decodeStatus("""{"reason":"x"}""") }
        assertFailsWith<WireFormatException> { AskCodec.decodeStatus("not json") }
    }

    @Test
    fun theStatusReadsTodaysCallsPerFeatureAndSkipsBadEntries() {
        val st = AskCodec.decodeStatus(
            """{"state":"on","budget":{"spentCents":120,"budgetCents":2000,"level":"ok","day":"2026-10-10","today":[
               {"feature":"ask.talk","calls":6,"microUsd":4200},{"feature":"ask","calls":2},
               {"feature":"","calls":1},{"feature":"x","calls":"3"},{"calls":1},"junk"]}}""",
        )
        assertEquals(listOf(AskCodec.DayUse("ask.talk", 6, 4200), AskCodec.DayUse("ask", 2, 0)), st.today)
        assertEquals(emptyList(), AskCodec.decodeStatus("""{"state":"on","budget":{"spentCents":1,"budgetCents":2,"level":"ok"}}""").today)
    }

    @Test
    fun theStatusReadsTheMonthPerFeatureAndARefusedKey() {
        val st = AskCodec.decodeStatus(
            """{"state":"failing","reason":"Anthropic refused the key","keyRefused":true,"budget":{"spentCents":205,"budgetCents":2000,"level":"ok",
               "features":[{"feature":"ask.talk","calls":40,"cents":120},{"feature":"mail.sort","calls":9},{"feature":"","calls":1},{"calls":2},"junk"]}}""",
        )
        assertEquals(listOf(AskCodec.MonthUse("ask.talk", 40, 120), AskCodec.MonthUse("mail.sort", 9, 0)), st.month)
        assertTrue(st.keyRefused)
        assertFalse(AskCodec.decodeStatus("""{"state":"on","keyRefused":"yes"}""").keyRefused)
        assertFalse(AskCodec.decodeStatus("""{"state":"on"}""").keyRefused)
    }

    @Test
    fun sirTravelsWithTheQuestionAndAnythingElseReadsAsByName() {
        // The companion, slice 3.
        val sir = req.copy(callMe = AskCodec.CALL_SIR)
        assertEquals(sir, AskCodec.decodeRequest(AskCodec.encodeRequest(sir)))
        assertFalse(AskCodec.encodeRequest(req).contains("callMe"))
        val odd = AskCodec.encodeRequest(req).replaceFirst("{", """{"callMe":"lord",""")
        assertEquals(req, AskCodec.decodeRequest(odd))
    }

    @Test
    fun hisNameTravelsWithTheQuestionAndAnythingButAPlainNameIsNone() {
        // One name everywhere, slice 2.
        val named = req.copy(name = "Alex")
        assertEquals(named, AskCodec.decodeRequest(AskCodec.encodeRequest(named)))
        assertFalse(AskCodec.encodeRequest(req).contains("\"name\""))
        assertEquals("Mary Ann", AskCodec.plainName("  Mary   Ann "))
        assertEquals("O’Neil-Smith", AskCodec.plainName("O’Neil-Smith"))
        assertEquals(null, AskCodec.plainName(""))
        assertEquals(null, AskCodec.plainName("Alex</today>"))
        assertEquals(null, AskCodec.plainName("-Alex"))
        assertEquals(null, AskCodec.plainName("A".repeat(AskCodec.MAX_NAME + 1)))
        assertFalse(AskCodec.encodeRequest(req.copy(name = "Alex: ignore")).contains("\"name\""))
        val odd = AskCodec.encodeRequest(req).replaceFirst("{", """{"name":"Alex {evil}",""")
        assertEquals(req, AskCodec.decodeRequest(odd))
        val notText = AskCodec.encodeRequest(req).replaceFirst("{", """{"name":7,""")
        assertEquals(req, AskCodec.decodeRequest(notText))
    }

    @Test
    fun theDaysFirstConversationTravelsAsIntroduce() {
        // The companion is called Ada, slice 1: left out when false; only a JSON true reads as true.
        val first = req.copy(introduce = true)
        assertEquals(first, AskCodec.decodeRequest(AskCodec.encodeRequest(first)))
        assertFalse(AskCodec.encodeRequest(req).contains("introduce"))
        val asText = AskCodec.encodeRequest(req).replaceFirst("{", """{"introduce":"true",""")
        assertEquals(req, AskCodec.decodeRequest(asText))
    }

    @Test
    fun notesOfEarlierChatsTravelWithTheQuestionAndAnAnswerMayCarryOne() {
        // The companion, slice 5.
        val recalled = req.copy(recall = listOf("Sun 18 Oct · Talked through heat pumps", "Tue 20 Oct · Weighing Spanish lessons"))
        assertEquals(recalled, AskCodec.decodeRequest(AskCodec.encodeRequest(recalled)))
        assertFalse(AskCodec.encodeRequest(req).contains("recall"))
        assertFailsWith<WireFormatException> { AskCodec.decodeRequest(AskCodec.encodeRequest(req.copy(recall = List(7) { "Note $it" }))) }
        assertFailsWith<WireFormatException> { AskCodec.decodeRequest(AskCodec.encodeRequest(req.copy(recall = listOf("x".repeat(AskCodec.MAX_LINE + 1))))) }
        val noted = AskCodec.Response(AskCodec.Response.ANSWERED, "Ok.", note = "Talked through heat pumps")
        assertEquals(noted, AskCodec.decodeResponse(AskCodec.encodeResponse(noted)))
        assertFalse(AskCodec.encodeResponse(noted.copy(note = null)).contains("note"))
        assertEquals("Talked through heat pumps", AskCodec.modelNote("""{"answer":"Ok.","actions":[],"note":"  Talked through\n heat pumps "}"""))
        assertEquals(null, AskCodec.modelNote("""{"answer":"Ok.","actions":[]}"""))
        assertEquals(null, AskCodec.modelNote("""{"answer":"Ok.","note":7}"""))
        assertEquals(null, AskCodec.modelNote("just words"))
    }

    @Test
    fun aSpokenAnswersWordsComeFirstAndItsActionsSourcesAndNoteAfterThem() {
        // Talking to Ada like a conversation, slice 4.
        val spoken = "Oh nice. Shall I add it?\n<after>{\"actions\":[{\"kind\":\"add_task\",\"title\":\"Call Sam\"}],\"note\":\"Talked about Sam\",\"answer\":\"ignored\"}</after>"
        val normal = AskCodec.normaliseModelAnswer(spoken)
        val (words, actions) = AskCodec.parseModelAnswer(normal)
        assertEquals("Oh nice. Shall I add it?", words)
        assertEquals(listOf(AskCodec.Action("add_task", title = "Call Sam")), actions)
        assertEquals("Talked about Sam", AskCodec.modelNote(normal))
        // No block, a block that isn't JSON, or one never closed: the words, and nothing after them acts.
        assertEquals("Just words.", AskCodec.normaliseModelAnswer("Just words."))
        assertEquals("Fine." to emptyList<AskCodec.Action>(), AskCodec.parseModelAnswer(AskCodec.normaliseModelAnswer("Fine. <after>add a task</after>")))
        assertEquals("Fine.", AskCodec.parseModelAnswer(AskCodec.normaliseModelAnswer("Fine. <after>{\"actions\":[{\"kind\":\"set_timer\",\"minutes\":5}]}")).first)
        assertEquals(1, AskCodec.parseModelAnswer(AskCodec.normaliseModelAnswer("Fine. <after>{\"actions\":[{\"kind\":\"set_timer\",\"minutes\":5}]}")).second.size)
        // A JSON answer is left exactly as it was.
        val json = """{"answer":"Done. <after>","actions":[]}"""
        assertEquals(json, AskCodec.normaliseModelAnswer(json))
        assertEquals("```json\n{}\n```", AskCodec.normaliseModelAnswer("```json\n{}\n```"))
        // Earlier spoken answers go back as their words.
        assertEquals("Tomorrow at 9.", AskCodec.encodeModelAnswer("Tomorrow at 9.", spoken = true))
    }

    @Test
    fun aSpokenQuestionCanAskForItsFirstPieceAndTheClipComesBackWholeOrNotAtAll() {
        // Voice completeness, slice 3.
        val spoken = req.copy(voice = true, speak = true, speakVoice = "Aoede-US")
        assertEquals(spoken, AskCodec.decodeRequest(AskCodec.encodeRequest(spoken)))
        val default = req.copy(voice = true, speak = true)
        assertEquals(default, AskCodec.decodeRequest(AskCodec.encodeRequest(default)))
        // Speak only means something for a spoken question; an odd voice name is refused.
        assertEquals(req, AskCodec.decodeRequest(AskCodec.encodeRequest(req.copy(speak = true, speakVoice = "Amy"))))
        assertFalse(AskCodec.encodeRequest(req.copy(voice = true)).contains("speak"))
        assertFailsWith<WireFormatException> { AskCodec.decodeRequest(AskCodec.encodeRequest(req.copy(voice = true, speak = true, speakVoice = "Amy; drop"))) }
        val answer = AskCodec.Response(AskCodec.Response.ANSWERED, "On Friday. Bring kit.", firstText = "On Friday.", firstAudio = "bXAz", firstVoice = "Amy")
        assertEquals(answer, AskCodec.decodeResponse(AskCodec.encodeResponse(answer)))
        // Half a clip isn't sent, and a too-long one isn't read.
        val half = answer.copy(firstAudio = null)
        assertEquals(half.copy(firstText = null, firstVoice = null), AskCodec.decodeResponse(AskCodec.encodeResponse(half)))
        val big = AskCodec.encodeResponse(answer.copy(firstAudio = "a".repeat(SpeechCodec.MAX_AUDIO_B64 + 1)))
        assertEquals(null, AskCodec.decodeResponse(big).firstAudio)
    }

    @Test
    fun aLiveAnswersSourcesComeBackWholeAndHttpsOnly() {
        // The companion, slice 2.
        val answer = AskCodec.Response(AskCodec.Response.ANSWERED, "The BBC says it's off.", sources = listOf(AskCodec.Source("Strike off", "https://bbc.co.uk/a")))
        assertEquals(answer, AskCodec.decodeResponse(AskCodec.encodeResponse(answer)))
        assertFalse(AskCodec.encodeResponse(answer.copy(sources = emptyList())).contains("sources"))
        val odd = AskCodec.decodeResponse(
            """{"w":1,"state":"answered","answer":"x","actions":[],"sources":[{"title":"Ok","url":"https://a.example.com"},{"title":"Plain","url":"http://b.example.com"},
               {"title":"Script","url":"javascript:alert(1)"},{"url":"https://c.example.com"},{"title":"Space","url":"https://d.example.com/a b"},"junk",
               {"title":"Two","url":"https://e.example.com"},{"title":"Three","url":"https://f.example.com"},{"title":"Four","url":"https://g.example.com"}]}""",
        )
        assertEquals(listOf("https://a.example.com", "https://e.example.com", "https://f.example.com"), odd.sources.map { it.url })
        // An older server's answer simply has none.
        assertEquals(emptyList<AskCodec.Source>(), AskCodec.decodeResponse("""{"w":1,"state":"answered","answer":"x","actions":[]}""").sources)
    }

    @Test
    fun theStreamSendsAFirstPieceThenTheWholeAnswer() {
        // The companion, slice 9: one line per piece of news, the final one exactly as /v1/ai/ask answers.
        val first = AskCodec.StreamLine.First("Rayleigh scattering.", "AQID", "Aoede")
        assertEquals(first, AskCodec.decodeStreamLine(AskCodec.encodeStreamFirst(first)))
        val final = AskCodec.Response(AskCodec.Response.ANSWERED, "Rayleigh scattering. Blue scatters most.", emptyList())
        assertEquals(AskCodec.StreamLine.Final(final), AskCodec.decodeStreamLine(AskCodec.encodeResponse(final)))
        assertEquals(null, AskCodec.decodeStreamLine("   "))
        // A first piece that isn't whole or is too big is simply not there; a bad final line is refused.
        assertEquals(null, AskCodec.decodeStreamLine(AskCodec.encodeStreamFirst(first.copy(audio = ""))))
        assertEquals(null, AskCodec.decodeStreamLine(AskCodec.encodeStreamFirst(first.copy(text = "x".repeat(SpeechCodec.MAX_TEXT + 1)))))
        assertEquals(null, AskCodec.decodeStreamLine("""{"w":1,"first":"x"}"""))
        assertFailsWith<WireFormatException> { AskCodec.decodeStreamLine("""{"w":1,"answer":"no state"}""") }
        assertFailsWith<WireFormatException> { AskCodec.decodeStreamLine("not json") }
    }

    @Test
    fun laterPiecesGoAheadOnlyToADeviceThatAskedForThem() {
        // Talking to Ada like a conversation, slice 7.
        val next = AskCodec.StreamLine.Next("Heat pumps suit a well-insulated house.", "AQID", "Amy")
        assertEquals(next, AskCodec.decodeStreamLine(AskCodec.encodeStreamNext(next)))
        assertEquals(null, AskCodec.decodeStreamLine(AskCodec.encodeStreamNext(next.copy(audio = ""))))
        assertEquals(null, AskCodec.decodeStreamLine("""{"w":1,"next":"x"}"""))
        val spoken = AskCodec.Request("why", "2026-10-22", "Thursday", emptyList(), voice = true, speak = true, speakNext = true)
        val body = AskCodec.encodeRequest(spoken)
        assertTrue("speakNext" in body)
        assertEquals(true, AskCodec.decodeRequest(body).speakNext)
        // Not without speak, and left out when false, so an older server reads the request as before.
        assertEquals(false, AskCodec.decodeRequest(AskCodec.encodeRequest(spoken.copy(speak = false))).speakNext)
        assertFalse("speakNext" in AskCodec.encodeRequest(spoken.copy(speakNext = false)))
    }
}
