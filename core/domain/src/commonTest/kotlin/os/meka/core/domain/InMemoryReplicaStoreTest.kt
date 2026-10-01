package os.meka.core.domain

import os.meka.core.sync.InMemoryReplicaStore
import kotlin.test.Test

class InMemoryReplicaStoreTest {
    @Test
    fun satisfiesContract() = os.meka.core.testing.ReplicaStoreContract.verify { InMemoryReplicaStore() }
}
