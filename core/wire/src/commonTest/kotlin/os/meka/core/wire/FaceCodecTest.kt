package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FaceCodecTest {
    @Test
    fun onlyTheSevenFaceFilesAreEverAskedFor() {
        assertEquals(7, FaceCodec.FILES.size)
        assertEquals("face-loop.mp4", FaceCodec.decodeFileRequest(FaceCodec.encodeFileRequest(FaceCodec.LOOP)))
        assertEquals("mouth-ah.png", FaceCodec.decodeFileRequest(FaceCodec.encodeFileRequest("mouth-ah.png")))
        for (bad in listOf("../db", "face/face-loop.mp4", "mouth-x.png", "", "MOUTH-AH.PNG")) {
            assertFailsWith<WireFormatException> { FaceCodec.decodeFileRequest("""{"w":${WireCodec.VERSION},"file":"$bad"}""") }
        }
        assertFailsWith<IllegalArgumentException> { FaceCodec.encodeFileRequest("secrets.json") }
        assertEquals("video/mp4", FaceCodec.contentType(FaceCodec.LOOP))
        assertEquals("image/png", FaceCodec.contentType("mouth-m.png"))
    }

    @Test
    fun theManifestDropsAnythingUnexpectedAndKnowsWhenTheFaceIsComplete() {
        val all = FaceCodec.FILES.map { FaceCodec.Entry(it, "etag-$it", 1000) }
        val back = FaceCodec.decodeManifest(FaceCodec.encodeManifest(all))
        assertEquals(all.toSet(), back.toSet())
        assertTrue(FaceCodec.complete(back))
        assertFalse(FaceCodec.complete(back.drop(1)))
        assertFalse(FaceCodec.complete(emptyList()))
        val odd = """{"w":${WireCodec.VERSION},"files":[{"name":"evil.sh","tag":"t","size":5},{"name":"mouth-m.png","tag":"","size":5},
            {"name":"mouth-f.png","tag":"t","size":0},{"name":"mouth-oh.png","tag":"t","size":99999999999},{"name":"mouth-ee.png","tag":"t","size":7}]}"""
        assertEquals(listOf(FaceCodec.Entry("mouth-ee.png", "t", 7)), FaceCodec.decodeManifest(odd))
        FaceCodec.decodeManifestRequest(FaceCodec.encodeManifestRequest())
    }
}
