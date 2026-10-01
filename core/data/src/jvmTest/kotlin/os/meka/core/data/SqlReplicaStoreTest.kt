package os.meka.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import os.meka.core.testing.ReplicaStoreContract
import kotlin.test.Test

class SqlReplicaStoreTest {
    @Test
    fun satisfiesReplicaStoreContract() = ReplicaStoreContract.verify {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MekaDatabase.Schema.create(driver)
        SqlReplicaStore(driver)
    }
}
