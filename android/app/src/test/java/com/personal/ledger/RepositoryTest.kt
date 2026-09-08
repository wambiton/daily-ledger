package com.personal.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.ledger.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RepositoryTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).build()

    @Test fun seedIsIdempotentAndCategoriesAreIsolated() = runBlocking {
        val db = database()
        try {
            val a = LedgerRepository(db, "a")
            a.seedDefaults(); a.seedDefaults()
            assertEquals(12, a.categories.first().size)
            assertEquals(12, db.ledger().pending("a").size)
            val b = LedgerRepository(db, "b")
            b.seedDefaults()
            assertEquals(12, b.categories.first().size)
            assertTrue(a.categories.first().map { it.id }.intersect(b.categories.first().map { it.id }.toSet()).isEmpty())
        } finally { db.close() }
    }

    @Test fun addEditDeleteUpdatesLocalDataAndQueue() = runBlocking {
        val db = database()
        try {
            val repo = LedgerRepository(db, "test")
            val category = repo.addCategory("expense", " 餐饮 ")
            val first = repo.save(null, "expense", category.id, "18.50", LocalDate.of(2026, 1, 1), "")
            assertEquals(1850L, repo.entries.first().single().amountFen)
            val updated = repo.save(first.id, "expense", category.id, "20", LocalDate.of(2026, 1, 2), "微信")
            assertEquals(first.createdAt, updated.createdAt)
            assertEquals("2026-01-02", repo.entries.first().single().date)
            assertEquals(3, db.ledger().pending("test").size)
            repo.delete(first.id); repo.delete(first.id)
            assertTrue(repo.entries.first().isEmpty())
            assertEquals(4, db.ledger().pending("test").size)
            assertNotNull(db.ledger().entry(first.id)?.deletedAt)
        } finally { db.close() }
    }

    @Test fun rejectsForeignCategoryAndWrongTypeWithoutPartialWrites() = runBlocking {
        val db = database()
        try {
            val a = LedgerRepository(db, "a")
            val cat = a.addCategory("income", "生活费")
            val b = LedgerRepository(db, "b")
            assertTrue(runCatching { b.save(null,"income",cat.id,"10",LocalDate.of(2026,1,1),"") }.isFailure)
            assertTrue(runCatching { a.save(null,"expense",cat.id,"10",LocalDate.of(2026,1,1),"") }.isFailure)
            assertTrue(b.entries.first().isEmpty())
            assertTrue(db.ledger().pending("b").isEmpty())
            assertEquals(1, db.ledger().pending("a").size)
        } finally { db.close() }
    }
}
