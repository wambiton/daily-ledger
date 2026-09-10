package com.personal.ledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.ledger.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupStoreTest {
    private lateinit var db: LedgerDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LedgerDatabase::class.java).build() }
    @After fun close() { db.close() }
    @Test fun snapshotAndRestorePreserveAmountsAndOwnerIsolation() = runBlocking {
        val repo = LedgerRepository(db, "first")
        repo.seedDefaults()
        val category = db.ledger().categorySnapshot("first").first { it.type == "expense" }
        repo.save(null,"expense",category.id,"12.34",LocalDate.of(2025,1,2),"roundtrip")
        val backup = BackupStore(db,"first")
        val payload = backup.export()
        val restoredDb = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),LedgerDatabase::class.java).build()
        try {
            BackupStore(restoredDb,"second").restore(payload,1)
            assertEquals(1234L,restoredDb.ledger().entrySnapshot("second").single().amountFen)
            assertTrue(restoredDb.ledger().entrySnapshot("first").isEmpty())
            assertEquals(1L,restoredDb.ledger().backupState("second")!!.cursor)
        } finally { restoredDb.close() }
    }
    @Test fun failedRestoreRollsBackAndAcknowledgementKeepsNewEdits() = runBlocking {
        val repo = LedgerRepository(db,"first"); repo.seedDefaults()
        val category = db.ledger().categorySnapshot("first").first { it.type == "expense" }
        val e = repo.save(null,"expense",category.id,"1",LocalDate.of(2025,1,2),"")
        val store = BackupStore(db,"first")
        val prepared = store.prepare()
        repo.save(e.id,"expense",category.id,"2",LocalDate.of(2025,1,2),"")
        val ids = prepared.getJSONArray("pending_ids")
        store.acknowledge(1,(0 until ids.length()).map { ids.getString(it) })
        assertEquals(1,db.ledger().pending("first").size)
        val invalid = JSONObject(store.export().toString())
        invalid.getJSONArray("entries").getJSONObject(0).put("category_id","missing")
        assertTrue(runCatching { store.restore(invalid,2) }.isFailure)
        assertEquals(200L,db.ledger().entrySnapshot("first").single().amountFen)
        assertEquals(1L,db.ledger().backupState("first")!!.cursor)
        assertTrue(runCatching { store.restore(store.export(),0,true) }.isFailure)
    }
    @Test fun serverAddressesRejectUnsafeDestinations() {
        assertEquals("https://example.com",BackupClient.normalize("https://EXAMPLE.com/"))
        for(value in listOf("http://example.com","https://user:password@example.com","https://example.com/path","https://example.com/?secret=x")) {
            assertTrue(runCatching { BackupClient.normalize(value) }.isFailure)
        }
        assertNotEquals(AccountVault.databaseKey("https://a.test","user"),AccountVault.databaseKey("https://b.test","user"))
    }
}
