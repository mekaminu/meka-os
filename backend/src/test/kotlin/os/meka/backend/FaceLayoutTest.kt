package os.meka.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Meka 2026-10-24 13:12: the face's files found where he put them, and the deploy's line about them. */
class FaceLayoutTest {
    private val names = listOf("face-loop.mp4", "mouth-rest.png", "mouth-m.png", "mouth-f.png", "mouth-ee.png", "mouth-oh.png", "mouth-ah.png")
    private fun o(key: String, size: Long = 100) = FaceLayout.Object(key, size, "t-$key")

    @Test
    fun filesOneFolderDownAreFoundAndTheTopLevelWins() {
        val objects = names.map { o("face/obi-face/$it") } + o("face/mouth-m.png") + o("face/__MACOSX/obi-face/._mouth-oh.png") +
            o("face/obi-face/._mouth-ah.png") + o("face/notes.txt") + o("vault/mouth-rest.png")
        val found = FaceLayout.resolve(objects)
        assertEquals(names.toSet(), found.keys)
        assertEquals("face/mouth-m.png", found.getValue("mouth-m.png").key)
        assertEquals("face/obi-face/mouth-oh.png", found.getValue("mouth-oh.png").key)
    }

    @Test
    fun namesMatchWithoutCaseAndEmptyOrHugeFilesDont() {
        val found = FaceLayout.resolve(listOf(o("face/Mouth-M.PNG"), o("face/mouth-f.png", 0), o("face/face-loop.mp4", 41L * 1024 * 1024)))
        assertEquals(setOf("mouth-m.png"), found.keys)
    }

    @Test
    fun theDeployLineSaysHowManyAndWhatsWrong() {
        assertEquals(listOf("::notice title=Obi's face::7 of 7 files"), FaceLayout.report(names.map { o("face/$it") }))
        assertEquals(
            listOf(
                "::warning title=Obi's face::0 of 7 files in face/ · missing ${names.joinToString(", ")}",
                "::warning title=Obi's face::nothing under face/ in storage · the bucket's top level is empty",
            ),
            FaceLayout.report(emptyList()),
        )
        val odd = FaceLayout.report(names.drop(1).map { o("face/obi-face/$it") } + o("face/face-loop.mp4", 52L * 1024 * 1024))
        assertEquals("::warning title=Obi's face::6 of 7 files in face/ · missing face-loop.mp4", odd.first())
        assertTrue(odd.contains("::notice title=Obi's face::mouth-m.png is under face/obi-face/ (served from there; face/mouth-m.png is where it belongs)"), odd.toString())
        assertTrue(odd.contains("::warning title=Obi's face::face-loop.mp4 is 52 MB, over the 40 MB limit"), odd.toString())
    }

    @Test
    fun theAwsListingParsesAndAnEmptyFolderIsNull() {
        val listing = """[{"Key":"face/mouth-m.png","Size":1234},{"Key":"face/face-loop.mp4","Size":5}]"""
        assertEquals(listOf(FaceLayout.Object("face/mouth-m.png", 1234, ""), FaceLayout.Object("face/face-loop.mp4", 5, "")), FaceLayout.parseListing(listing))
        assertEquals(emptyList(), FaceLayout.parseListing("null"))
        assertEquals(emptyList(), FaceLayout.parseListing(""))
        assertEquals(1, FaceLayout.parseListing("""{"Contents":[{"Key":"face/mouth-m.png","Size":1}]}""").size)
    }

    /** Meka 2026-10-24 19:26: "0 of 7 on the server" while he'd uploaded all seven; a folder named a little differently. */
    @Test
    fun aFolderNamedLikeTheFaceOrLooseFilesAreFoundTooButNotOtherFolders() {
        assertTrue(FaceLayout.faceFolder("face/"))
        assertTrue(FaceLayout.faceFolder("Face/"))
        assertTrue(FaceLayout.faceFolder("face /"))
        assertTrue(FaceLayout.faceFolder("obi-face/"))
        assertTrue(!FaceLayout.faceFolder("voice/"))
        assertTrue(!FaceLayout.faceFolder("vault/"))
        val found = FaceLayout.resolve(names.map { o("Face/$it") } + o("mouth-m.png") + o("voice/face-loop.mp4"))
        assertEquals(names.toSet(), found.keys)
        assertEquals("Face/mouth-m.png", found.getValue("mouth-m.png").key, "a folder beats loose files")
        assertEquals("Face/face-loop.mp4", found.getValue("face-loop.mp4").key)
        assertEquals(setOf("mouth-ah.png"), FaceLayout.resolve(listOf(o("mouth-ah.png"))).keys, "loose at the top level")
        assertEquals(emptyMap(), FaceLayout.resolve(listOf(o("voice/mouth-ah.png"), o("vault/x/mouth-m.png"))))
        // face/ itself still wins over a look-alike folder.
        assertEquals("face/mouth-oh.png", FaceLayout.resolve(listOf(o("Face/mouth-oh.png"), o("face/mouth-oh.png"))).getValue("mouth-oh.png").key)
    }

    @Test
    fun theNoteSaysWhereTheyWereOrWhatTheTopLevelHolds() {
        assertEquals(null, FaceLayout.note(FaceLayout.resolve(names.map { o("face/$it") }), emptyList()))
        assertEquals(
            "7 of 7 found, under Face/ (face/ is where they belong)",
            FaceLayout.note(FaceLayout.resolve(names.map { o("Face/$it") }), listOf("Face/", "voice/")),
        )
        assertEquals("nothing under face/ in storage · top-level folders: voice/, vault/", FaceLayout.note(emptyMap(), listOf("voice/", "vault/")))
        assertEquals("couldn't list its storage: IllegalStateException", FaceLayout.errorNote(IllegalStateException("x")))
    }

    @Test
    fun theManifestCarriesTheNoteAndAFailedListingIsA200WithIt() {
        val store = object : FaceFileStore {
            var fail = true
            override fun list(): List<os.meka.core.wire.FaceCodec.Entry> = if (fail) throw IllegalStateException("denied") else emptyList()
            override fun get(name: String): ByteArray? = null
            override fun note(): String? = if (fail) "couldn't list its storage: AccessDenied" else "nothing under face/ in storage · top-level folders: voice/"
        }
        val files = FaceFiles(store)
        val phone = DeviceIdentity("hh", "phone-1")
        val r = files.manifest(phone)
        assertEquals(200, r.status)
        assertEquals("couldn't list its storage: AccessDenied", os.meka.core.wire.FaceCodec.decodeManifestNote(r.body))
        assertEquals(emptyList(), os.meka.core.wire.FaceCodec.decodeManifest(r.body))
        assertEquals("the server lists 0 of 7 · couldn't list its storage: AccessDenied", files.report())
        store.fail = false // a failure isn't kept: the next ask lists again
        assertEquals("nothing under face/ in storage · top-level folders: voice/", os.meka.core.wire.FaceCodec.decodeManifestNote(files.manifest(phone).body))
    }
}

class FaceProbeLineTest {
    @Test
    fun theDeployProbeWarnsUnlessTheServerListsAllSevenUnderFace() {
        assertEquals("::notice title=Obi's face (server)::the server lists 7 of 7", ReleasePublisherCli.faceLine("the server lists 7 of 7"))
        assertEquals(
            "::warning title=Obi's face (server)::the server lists 0 of 7 · couldn't list its storage: AccessDenied",
            ReleasePublisherCli.faceLine("the server lists 0 of 7 · couldn't list its storage: AccessDenied"),
        )
        assertEquals("::warning title=Obi's face (server)::the server doesn't report it yet", ReleasePublisherCli.faceLine(null))
    }
}
