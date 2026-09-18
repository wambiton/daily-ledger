package com.personal.ledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.ledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrashTest {
    private lateinit var db: LedgerDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LedgerDatabase::class.java).build() }
    @After fun close() { db.close() }
    @Test fun restorePreservesIdentityAndStatistics() = runBlocking {
        val repo = LedgerRepository(db, "a"); repo.seedDefaults()
        val c = db.ledger().categorySnapshot("a").first()
        val e = repo.save(null,c.type,c.id,"12.34",LocalDate.of(2025,1,1),"retain")
        repo.delete(e.id)
        assertTrue(db.ledger().entrySnapshot("a").isEmpty())
        assertTrue(runCatching { LedgerRepository(db,"b").restoreDeleted(e.id) }.isFailure)
        repo.restoreDeleted(e.id)
        val restored = db.ledger().entrySnapshot("a").single()
        assertEquals(e.id,restored.id); assertEquals(e.createdAt,restored.createdAt)
        assertEquals(1234L,restored.amountFen); assertEquals("retain",restored.note)
    }
    @Test fun expiryBoundaryAndOwnerIsolation() = runBlocking {
        val repo = LedgerRepository(db,"a"); repo.seedDefaults()
        val other = LedgerRepository(db,"b"); other.seedDefaults()
        val c = db.ledger().categorySnapshot("a").first()
        val e = repo.save(null,c.type,c.id,"1",LocalDate.of(2025,1,1),"")
        repo.delete(e.id)
        val deleted = db.ledger().entry(e.id)!!.deletedAt!!
        val deadline = deleted + LedgerRepository.RETENTION_MS
        assertEquals(0,repo.purgeExpired(deadline-1))
        assertEquals(0,other.purgeExpired(deadline))
        assertTrue(runCatching { repo.restoreDeleted(e.id,deadline) }.isFailure)
        assertEquals(1,repo.purgeExpired(deadline))
        assertNull(db.ledger().entry(e.id))
        assertTrue(db.ledger().pending("a").any { it.entityId == e.id && it.action == "delete" })
    }
}
