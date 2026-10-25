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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HistoryRecoveryTest {
    private lateinit var db: LedgerDatabase
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build() }
    @After fun close() { db.close() }
    private suspend fun payload(): JSONObject {
        val repo = LedgerRepository(db, "account-owner")
        repo.seedDefaults()
        val category = db.ledger().categorySnapshot("account-owner").first { it.type == "expense" }
        repo.save(null, "expense", category.id, "12.34", LocalDate.of(2025, 1, 2), "synthetic")
        db.ledger().putBudget(BudgetPlan(UUID.randomUUID().toString(), "account-owner", "test budget", "2025-01", "2025-01", 10000))
        return BackupStore(db, "account-owner").export()
    }
    @Test fun createsIndependentPersistentBooksWithoutChangingSource() = runBlocking {
        val snapshot = payload()
        val source = BackupStore(db, "account-owner").export().toString()
        val pending = db.ledger().pending("account-owner")
        val first = HistoryRecovery.create(context, snapshot, 7, "test@qq.com")
        val second = HistoryRecovery.create(context, snapshot, 7, "test@qq.com")
        assertNotEquals(first.getString("database"), second.getString("database"))
        assertNotEquals("account-owner", first.getString("owner"))
        assertTrue(context.getDatabasePath(first.getString("database")).exists())
        assertTrue(context.getDatabasePath(second.getString("database")).exists())
        val name = first.getString("database")
        val restored = LedgerDatabases.acquire(context, name)
        try {
            val owner = first.getString("owner")
            assertEquals(1234L, restored.ledger().allEntries(owner).single().amountFen)
            assertEquals(1, restored.ledger().budgetSnapshot(owner).size)
            assertEquals(0L, restored.ledger().backupState(owner)!!.cursor)
            assertNull(restored.ledger().backupState(owner)!!.lastSuccessAt)
            assertTrue(restored.ledger().pending(owner).isEmpty())
            assertTrue(restored.ledger().allEntries("account-owner").isEmpty())
        } finally { LedgerDatabases.release(name) }
        assertEquals(source, BackupStore(db, "account-owner").export().toString())
        assertEquals(pending, db.ledger().pending("account-owner"))
    }
    @Test fun invalidSnapshotCreatesNoDatabase() = runBlocking {
        val snapshot = payload()
        HistoryRecovery.create(context, snapshot, 1, "test")
        val before = context.databaseList().toSet()
        snapshot.getJSONArray("entries").getJSONObject(0).put("amount_fen", -1)
        assertTrue(runCatching { HistoryRecovery.create(context, snapshot, 2, "test") }.isFailure)
        assertEquals(before, context.databaseList().toSet())
    }
    @Test fun previewAndRecoveryRespectOriginalTrashExpiry() = runBlocking {
        val snapshot = payload()
        val entries = snapshot.getJSONArray("entries")
        val now = System.currentTimeMillis()
        val old = now - LedgerRepository.RETENTION_MS - 1000
        val base = entries.getJSONObject(0)
        base.put("created_at", old - 1).put("updated_at", old).put("deleted_at", old)
        entries.put(JSONObject(base.toString()).put("id", UUID.randomUUID().toString())
            .put("updated_at", now).put("deleted_at", now))
        val preview = HistoryRecovery.preview(snapshot, now)
        assertEquals(0, preview.normal); assertEquals(1, preview.trash); assertEquals(1, preview.expired)
        assertNull(preview.dateFrom)
        val book = HistoryRecovery.create(context, snapshot, 1, "test")
        val name = book.getString("database")
        val restored = LedgerDatabases.acquire(context, name)
        try { assertEquals(now, restored.ledger().allEntries(book.getString("owner")).single().deletedAt) }
        finally { LedgerDatabases.release(name) }
    }
}
