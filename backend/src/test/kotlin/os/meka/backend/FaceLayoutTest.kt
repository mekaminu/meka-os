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
            listOf("::warning title=Obi's face::0 of 7 files in face/ · missing ${names.joinToString(", ")}"),
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
}
