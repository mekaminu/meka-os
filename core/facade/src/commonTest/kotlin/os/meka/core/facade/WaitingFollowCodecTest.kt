package os.meka.core.facade

import os.meka.core.domain.Lifecycle
import os.meka.core.domain.WaitingFollow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaitingFollowCodecTest {
    private val renamed = WaitingFollow("t1", true, 1_760_000_000_000, "Write the “Q3” report\t\n\\", Lifecycle.ACTIVE, 1_759_999_000_000)
    private val deleted = WaitingFollow("t:2", false, null, "", null, 5)

    @Test
    fun roundTripsEveryShapeOfWait() {
        val text = WaitingFollowCodec.encode(listOf(renamed, deleted))!!
        assertEquals(setOf(renamed, deleted), WaitingFollowCodec.decode(text).toSet())
    }

    @Test
    fun nothingWaitingIsNoValue() {
        assertNull(WaitingFollowCodec.encode(emptyList()))
        assertTrue(WaitingFollowCodec.decode(null).isEmpty())
        assertTrue(WaitingFollowCodec.decode("").isEmpty())
    }

    @Test
    fun anUnreadableValueOrEntryIsDroppedNotFatal() {
        assertTrue(WaitingFollowCodec.decode("not json").isEmpty())
        assertTrue(WaitingFollowCodec.decode("{\"task\":\"t1\"}").isEmpty())
        val good = WaitingFollowCodec.encode(listOf(renamed))!!.removeSurrounding("[", "]")
        val mixed = "[$good,{\"task\":\"t3\",\"present\":true,\"since\":1,\"lifecycle\":\"LATER\"},{\"task\":\"\"},7,{\"present\":true,\"since\":1}]"
        assertEquals(listOf(renamed), WaitingFollowCodec.decode(mixed))
    }

    @Test
    fun oneWaitPerTask() {
        val older = renamed.copy(title = "Write report")
        val text = "[" + WaitingFollowCodec.encode(listOf(older))!!.removeSurrounding("[", "]") + "," +
            WaitingFollowCodec.encode(listOf(renamed))!!.removeSurrounding("[", "]") + "]"
        assertEquals(listOf(renamed), WaitingFollowCodec.decode(text))
    }
}
