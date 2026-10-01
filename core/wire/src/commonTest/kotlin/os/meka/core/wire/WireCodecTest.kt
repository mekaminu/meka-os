package os.meka.core.wire

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Hlc
import os.meka.core.sync.Op
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PullResponse
import os.meka.core.sync.PushRequest
import os.meka.core.sync.PushResponse
import os.meka.core.sync.SequencedOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WireCodecTest {
    private fun op(v: FieldValue, base: List<String> = listOf("b1", "b2")) = Op(
        "op1", "hh", "task", "t1", "title", v, Hlc(1_790_000_000_123, 7, "android"), base, "android", 3,
    )

    @Test
    fun opsRoundTripForEveryValueType() {
        listOf(
            FieldValue.Text("Logan football — kit ✓ \"quoted\" \n newline"),
            FieldValue.Int64(Long.MAX_VALUE),
            FieldValue.Int64(Long.MIN_VALUE),
            FieldValue.Bool(true),
            FieldValue.Null,
        ).forEach { v ->
            val o = op(v)
            assertEquals(o, WireCodec.decodeOp(WireCodec.encodeOp(o)))
        }
        assertEquals(op(FieldValue.Null, emptyList()), WireCodec.decodeOp(WireCodec.encodeOp(op(FieldValue.Null, emptyList()))))
    }

    @Test
    fun protocolDocumentsRoundTrip() {
        val push = PushRequest("hh", "android", listOf(op(FieldValue.Text("x"))))
        assertEquals(push, WireCodec.decodePushRequest(WireCodec.encodePushRequest(push)))
        val pushResp = PushResponse(listOf("a", "b"), mapOf("c" to "household mismatch"))
        assertEquals(pushResp, WireCodec.decodePushResponse(WireCodec.encodePushResponse(pushResp)))
        val pull = PullRequest("hh", "mac", 42, 100)
        assertEquals(pull, WireCodec.decodePullRequest(WireCodec.encodePullRequest(pull)))
        val pullResp = PullResponse(listOf(SequencedOp(43, op(FieldValue.Bool(false)))), hasMore = true)
        assertEquals(pullResp, WireCodec.decodePullResponse(WireCodec.encodePullResponse(pullResp)))
    }

    @Test
    fun enrolmentRoundTripsAndValidatesIds() {
        val r = WireCodec.EnrolRequest("hh1", "android3f9a", "Fold 8")
        assertEquals(r, WireCodec.decodeEnrolRequest(WireCodec.encodeEnrolRequest(r)))
        assertEquals("s3cr3t", WireCodec.decodeEnrolResponse(WireCodec.encodeEnrolResponse("s3cr3t")))
        assertFailsWith<WireFormatException> { WireCodec.decodeEnrolRequest(WireCodec.encodeEnrolRequest(r.copy(deviceId = "../etc"))) }
        assertFailsWith<WireFormatException> { WireCodec.decodeEnrolRequest(WireCodec.encodeEnrolRequest(r.copy(householdId = ""))) }
    }

    @Test
    fun unknownFieldsAreIgnoredForForwardCompatibility() {
        val s = """{"w":1,"hh":"hh","dev":"mac","after":5,"limit":10,"newFieldFromTheFuture":{"x":1}}"""
        assertEquals(PullRequest("hh", "mac", 5, 10), WireCodec.decodePullRequest(s))
    }

    @Test
    fun malformedAndFutureVersionsAreRejected() {
        assertFailsWith<WireFormatException> { WireCodec.decodePullRequest("""{"w":2,"hh":"h","dev":"d","after":0}""") }
        assertFailsWith<WireFormatException> { WireCodec.decodePullRequest("""{"hh":"h"}""") }
        assertFailsWith<WireFormatException> { WireCodec.decodePushRequest("not json") }
        assertFailsWith<WireFormatException> {
            WireCodec.decodePushRequest("""{"w":1,"hh":"h","dev":"d","ops":[{"id":"x"}]}""")
        }
    }
}
