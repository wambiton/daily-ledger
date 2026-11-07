package com.personal.ledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.ledger.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DesktopSupplementDeviceTest {
    @Test fun desktopSupplementPersistsAndRequiresDuplicateChoice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets
            .open("desktop-supplement.json").bufferedReader().use { it.readText() })
        val name = "desktop-supplement-test-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        try {
            val store = BackupStore(db, "synthetic-owner")
            assertEquals(0, store.preview(payload).duplicates)
            assertEquals(1, store.append(payload, "confirm"))
            val original = db.ledger().entrySnapshot("synthetic-owner").single()
            assertEquals(850L, original.amountFen)
            assertEquals("微信 · 模拟早餐", original.note)
            db.close()
            db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
            val reopened = BackupStore(db, "synthetic-owner")
            assertEquals(original, db.ledger().entrySnapshot("synthetic-owner").single())
            assertEquals(1, reopened.preview(payload).duplicates)
            assertTrue(runCatching { reopened.append(payload, "confirm") }.exceptionOrNull() is BackupStore.DuplicateImport)
            assertEquals(0, reopened.append(payload, "skip"))
            assertEquals(listOf(original), db.ledger().entrySnapshot("synthetic-owner"))
            assertEquals(1, reopened.append(payload, "include"))
            val entries = db.ledger().entrySnapshot("synthetic-owner")
            assertEquals(2, entries.size)
            assertEquals(1700L, entries.sumOf { it.amountFen })
            assertTrue(entries.contains(original))
            assertEquals(0, BackupStore(db, "other-owner").preview(payload).duplicates)
            assertTrue(db.ledger().entrySnapshot("other-owner").isEmpty())
        } finally { db.close() }
    }
}
