package os.meka.android.push

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushTest {
    @Test
    fun onlyASyncWakeRunsASync() {
        assertTrue(PushMessages.isSyncWake(mapOf("t" to "sync")))
        assertFalse(PushMessages.isSyncWake(emptyMap()))
        assertFalse(PushMessages.isSyncWake(mapOf("t" to "other")))
    }

    @Test
    fun aTokenIsSentOncePerNewToken() {
        assertTrue(PushMessages.needsSending("abc", null))
        assertFalse(PushMessages.needsSending("abc", "abc"))
        assertTrue(PushMessages.needsSending("def", "abc"))
        assertFalse(PushMessages.needsSending(null, "abc"))
        assertFalse(PushMessages.needsSending("", null))
    }

    /** res/values/firebase.xml must say what google-services.json says (we don't run the google-services plugin). */
    @Test
    fun firebaseResourcesMatchGoogleServicesJson() {
        val json = File("google-services.json").readText()
        val xml = File("src/main/res/values/firebase.xml").readText()
        fun j(key: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(json)!!.groupValues[1]
        fun x(name: String) = Regex("<string name=\"$name\"[^>]*>([^<]+)</string>").find(xml)!!.groupValues[1]
        assertEquals(j("mobilesdk_app_id"), x("google_app_id"))
        assertEquals(j("project_number"), x("gcm_defaultSenderId"))
        assertEquals(j("current_key"), x("google_api_key"))
        assertEquals(j("project_id"), x("project_id"))
        assertEquals(j("storage_bucket"), x("google_storage_bucket"))
        assertEquals("os.meka.android", j("package_name"))
    }
}
