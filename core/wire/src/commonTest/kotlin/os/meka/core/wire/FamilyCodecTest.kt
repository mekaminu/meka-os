package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Family sharing's invites on the wire (slice 4): what the server lists and makes, read strictly. */
class FamilyCodecTest {
    private val id = "fam" + "0123456789abcdef0123"
    private val token = "ab".repeat(32)

    @Test
    fun aListingReadsAndBadEntriesAreLeftOut() {
        val body = """
            {"invites":[
              {"id":"$id","name":"jordan","state":"joined","createdAtMs":10,"claimedAtMs":20,"lastSeenAtMs":30,"publicKey":"never"},
              {"id":"fam0000000000000000000a","name":"jordan","state":"waiting","createdAtMs":5},
              {"id":"nope","name":"x","state":"waiting","createdAtMs":5},
              {"id":"fam0000000000000000000b","name":"x","state":"lost","createdAtMs":5},
              {"id":"fam0000000000000000000c","name":"x","state":"revoked","createdAtMs":"5"},
              "junk"
            ]}
        """.trimIndent()
        val list = FamilyCodec.decodeList(body)
        assertEquals(
            listOf(
                FamilyCodec.Invite(id, "jordan", "joined", 10, 20, 30),
                FamilyCodec.Invite("fam0000000000000000000a", "jordan", "waiting", 5),
            ),
            list,
        )
        assertEquals(emptyList(), FamilyCodec.decodeList("""{"invites":[]}"""))
        assertFailsWith<WireFormatException> { FamilyCodec.decodeList("{}") }
        assertFailsWith<WireFormatException> { FamilyCodec.decodeList("<html>") }
    }

    @Test
    fun aNewLinkMustBeTheFamilyPageAndAWholeToken() {
        val made = FamilyCodec.decodeMade("""{"id":"$id","name":"jordan","token":"$token","path":"/family#$token"}""")
        assertEquals(FamilyCodec.Made(id, "jordan", "/family#$token"), made)
        assertTrue(FamilyCodec.isLinkPath("/family#$token"))
        assertFalse(FamilyCodec.isLinkPath("/family#${token}0"))
        assertFalse(FamilyCodec.isLinkPath("/evil#$token"))
        assertFalse(FamilyCodec.isLinkPath("/family#" + "AB".repeat(32)))
        assertFalse(FamilyCodec.isLinkPath("//evil.example/family#$token"))
        assertFailsWith<WireFormatException> {
            FamilyCodec.decodeMade("""{"id":"$id","name":"jordan","path":"https://evil.example/family#$token"}""")
        }
        assertFailsWith<WireFormatException> { FamilyCodec.decodeMade("""{"id":"bad","name":"jordan","path":"/family#$token"}""") }
    }

    @Test
    fun requestsCarryOnlyTheNameOrTheId() {
        assertEquals("""{"name":"Jordan"}""", FamilyCodec.encodeCreate("Jordan"))
        assertEquals("""{"id":"$id"}""", FamilyCodec.encodeRevoke(id))
        assertEquals("{}", FamilyCodec.encodeList())
        assertTrue(FamilyCodec.isInviteId(id))
        assertFalse(FamilyCodec.isInviteId("fam" + "0123456789ABCDEF0123"))
    }
}
