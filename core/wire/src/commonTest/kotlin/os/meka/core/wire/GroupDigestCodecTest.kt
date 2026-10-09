package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The group digest's gist on the wire (build plan V1, messages assistant slice 4b). */
class GroupDigestCodecTest {
    private val lads = GroupDigestCodec.Group(
        "Barça lads",
        listOf(
            GroupDigestCodec.Line("Tunde", "12:01", "lineup for Getafe?"),
            GroupDigestCodec.Line("Femi", "12:05", "Lewandowski up top"),
        ),
    )
    private val req = GroupDigestCodec.Request("2026-10-09", "Friday 9 October 2026 · 12:30", listOf(lads))

    @Test
    fun aRequestRoundTripsAndLimitsAreRefusedNotTrimmed() {
        assertEquals(req, GroupDigestCodec.decodeRequest(GroupDigestCodec.encodeRequest(req)))
        fun bad(r: GroupDigestCodec.Request) =
            assertFailsWith<WireFormatException> { GroupDigestCodec.decodeRequest(GroupDigestCodec.encodeRequest(r)) }
        bad(req.copy(groups = emptyList()))
        bad(req.copy(groups = List(7) { lads }))
        bad(req.copy(groups = listOf(lads.copy(name = ""))))
        bad(req.copy(groups = listOf(lads.copy(name = "x".repeat(61)))))
        bad(req.copy(groups = listOf(lads.copy(lines = emptyList()))))
        bad(req.copy(groups = listOf(lads.copy(lines = List(41) { lads.lines[0] }))))
        bad(req.copy(groups = listOf(lads.copy(lines = listOf(lads.lines[0].copy(text = "x".repeat(301)))))))
        bad(req.copy(groups = listOf(lads.copy(lines = listOf(lads.lines[0].copy(at = "noon"))))))
        bad(req.copy(groups = listOf(lads.copy(lines = listOf(lads.lines[0].copy(from = " "))))))
        bad(req.copy(date = "today"))
        assertFailsWith<WireFormatException> { GroupDigestCodec.decodeRequest("not json") }
    }

    @Test
    fun aResponseRoundTrips() {
        val r = GroupDigestCodec.Response(
            "answered",
            listOf(
                GroupDigestCodec.GroupAnswer(
                    "Barça lads", "Lineup debate for Getafe",
                    listOf(
                        GroupDigestCodec.Ask("needs_reply", "Tunde", "Asks if you want a ticket"),
                        GroupDigestCodec.Ask("action", "Femi", "Asks you to bring the ball", listOf(MessageRequestCodec.Proposal("task", "Bring the ball", words = "Saturday"))),
                    ),
                ),
            ),
        )
        assertEquals(r, GroupDigestCodec.decodeResponse(GroupDigestCodec.encodeResponse(r)))
        assertEquals(GroupDigestCodec.Response("off", reason = "MEKA's AI is off"), GroupDigestCodec.decodeResponse(GroupDigestCodec.encodeResponse(GroupDigestCodec.Response("off", reason = "MEKA's AI is off"))))
    }

    @Test
    fun aModelAnswerIsReadOnlyAsTheJsonWithKnownLanesAndAtMostThreeAsks() {
        val text = """
            Here you go:
            ```json
            {"groups":[
              {"name":"Barça lads","gist":"Lineup debate; Saturday at 7","asks":[
                {"lane":"needs_reply","from":"Tunde","summary":"Asks if you're in for Saturday"},
                {"lane":"send_money","from":"Femi","summary":"Wants £20"},
                {"lane":"action","from":"Femi","summary":"Bring the ball","proposals":[{"kind":"task","title":"Bring the ball","words":"Saturday"},{"kind":"transfer","title":"x"}]},
                {"lane":"fyi","from":"Obi","summary":"Nothing"}
              ]},
              {"name":"Family","gist":"Pizza at Obi's","asks":[{"lane":"needs_reply","from":"Mum","summary":"Coming?"},{"lane":"needs_reply","from":"Ada","summary":"And you?"}]},
              {"gist":"no name"}
            ]}
            ```
        """.trimIndent()
        val groups = GroupDigestCodec.parseModelAnswer(text)
        assertEquals(listOf("Barça lads", "Family"), groups.map { it.name })
        assertEquals(listOf("needs_reply", "action"), groups[0].asks.map { it.lane })
        assertEquals(listOf("task"), groups[0].asks[1].proposals.map { it.kind })
        // Three asks in all: Family's second one is left out.
        assertEquals(listOf("Mum"), groups[1].asks.map { it.from })
        assertTrue(GroupDigestCodec.parseModelAnswer("Sorry, I can't help with that.").isEmpty())
        assertTrue(GroupDigestCodec.parseModelAnswer("{\"groups\": \"none\"}").isEmpty())
    }
}
