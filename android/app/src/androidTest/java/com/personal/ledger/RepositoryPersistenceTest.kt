package com.personal.ledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.ledger.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RepositoryPersistenceTest {
    @Test fun entriesAndPendingWritesSurviveDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "persistence-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        try {
            var repo = LedgerRepository(db, "isolated-test")
            val cat = repo.addCategory("income", "测试收入")
            repo.save(null,"income",cat.id,"12.34", LocalDate.of(2026,1,1),"")
            db.close()
            db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
            repo = LedgerRepository(db, "isolated-test")
            assertEquals(1234L, repo.entries.first().single().amountFen)
            assertEquals(2, db.ledger().pending("isolated-test").size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
