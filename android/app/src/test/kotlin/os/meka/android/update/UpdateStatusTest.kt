package os.meka.android.update

import android.content.pm.PackageInstaller
import os.meka.core.domain.UpdateFailureRules
import kotlin.test.Test
import kotlin.test.assertEquals

/** The core's copy of Android's install statuses (phone updates sometimes fail to install) matches the platform's. */
class UpdateStatusTest {
    @Test
    fun theCoresStatusesAreAndroids() {
        assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, UpdateFailureRules.STATUS_PENDING_USER_ACTION)
        assertEquals(PackageInstaller.STATUS_SUCCESS, UpdateFailureRules.STATUS_SUCCESS)
        assertEquals(PackageInstaller.STATUS_FAILURE, UpdateFailureRules.STATUS_FAILURE)
        assertEquals(PackageInstaller.STATUS_FAILURE_BLOCKED, UpdateFailureRules.STATUS_FAILURE_BLOCKED)
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, UpdateFailureRules.STATUS_FAILURE_ABORTED)
        assertEquals(PackageInstaller.STATUS_FAILURE_INVALID, UpdateFailureRules.STATUS_FAILURE_INVALID)
        assertEquals(PackageInstaller.STATUS_FAILURE_CONFLICT, UpdateFailureRules.STATUS_FAILURE_CONFLICT)
        assertEquals(PackageInstaller.STATUS_FAILURE_STORAGE, UpdateFailureRules.STATUS_FAILURE_STORAGE)
        assertEquals(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, UpdateFailureRules.STATUS_FAILURE_INCOMPATIBLE)
    }
}
