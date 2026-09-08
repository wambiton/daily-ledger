package com.personal.ledger

import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import androidx.room.withTransaction
import com.personal.ledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalDatabaseTest {
    @Test fun durableAndAtomic() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.deleteDatabase("foundation-test")
        var db = Room.databaseBuilder(ctx, LedgerDatabase::class.java, "foundation-test").build()
        db.ledger().insertCategory(Category("cat", "test", "expense", "餐饮"))
        try {
            db.withTransaction {
                db.ledger().upsertEntry(Entry("entry", "test", "expense", "cat", 1850, "2026-09-07", "", 1, 1))
                error("queue failure")
            }
        } catch (_: IllegalStateException) { }
        assertNull(db.ledger().entry("entry"))
        db.close()
        db = Room.databaseBuilder(ctx, LedgerDatabase::class.java, "foundation-test").build()
        assertEquals("餐饮", db.ledger().category("cat")?.name)
        db.close()
        ctx.deleteDatabase("foundation-test")
        Unit
    }
}
