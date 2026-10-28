package com.personal.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.ledger.data.*
import com.personal.ledger.domain.Rules
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NamingRuleStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun notice() = PaymentNotice("synthetic", "支付宝", "2026-10-26", 850, "expense", "模拟食堂", "模拟支付",
        false, false, false, transactionAt = LocalDateTime.parse("2026-10-26T07:30:00").atZone(Rules.zone).toInstant().toEpochMilli(), channel = "支付宝")

    @Test fun persistedTrustSurvivesReopenButEditsAndRemovalRevokeIt() = runBlocking {
        val name = "naming-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
        try {
            LedgerRepository(db, "a").seedDefaults()
            val category = db.ledger().categorySnapshot("a").first { it.type == "expense" }
            val rule = SmartNamingRule(UUID.randomUUID().toString(), name, "a", "早餐", category.id, channel = "支付宝", allowAutomatic = true)
            var store = NamingRuleStore(db, name, "a")
            store.save(rule)
            assertTrue(store.snapshot().single().trusted.isEmpty())
            assertTrue(store.recordConfirmation(notice()))
            db.close(); db = Room.databaseBuilder(context, LedgerDatabase::class.java, name).build()
            store = NamingRuleStore(db, name, "a")
            val saved = store.snapshot().single()
            assertTrue(SmartRuleMatcher.evaluate(name, "a", notice(), listOf(saved.rule), saved.trusted) is NamingDecision.Automatic)
            assertTrue(NamingRuleStore(db, name, "b").snapshot().isEmpty())
            store.save(rule.copy(title = "早饭"))
            assertTrue(store.snapshot().single().trusted.isEmpty())
            store.save(rule)
            assertTrue(store.snapshot().single().trusted.isEmpty())
            store.remove(rule.id)
            assertEquals("[]", db.ledger().namingSettings("a")!!.rulesJson)
            assertEquals(5, BackupStore(db, "a").export().getInt("format_version"))
            assertTrue(db.ledger().pending("a").any { it.entityType == "naming_rule" })
        } finally { db.close() }
    }

    @Test fun backupRebasesTrustToRestoredBookAndAppendDoesNotGrantAutomaticPermission() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        val destination = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            LedgerRepository(db, "source").seedDefaults()
            val category = db.ledger().categorySnapshot("source").first { it.type == "expense" }
            val store = NamingRuleStore(db, "source.db", "source")
            store.save(SmartNamingRule(UUID.randomUUID().toString(), "source.db", "source", "早餐", category.id,
                channel = "支付宝", allowAutomatic = true))
            store.recordConfirmation(notice())
            val snapshot = BackupStore(db, "source").export()
            assertEquals(5, snapshot.getInt("format_version"))
            BackupStore(destination, "restored").restore(snapshot, 0, requireEmpty = true)
            val restored = NamingRuleStore(destination, "restored.db", "restored").snapshot().single()
            assertTrue(SmartRuleMatcher.evaluate("restored.db", "restored", notice(), listOf(restored.rule), restored.trusted) is NamingDecision.Automatic)
            assertEquals(NamingDecision.NoRule, SmartRuleMatcher.evaluate("other.db", "restored", notice(), listOf(restored.rule), restored.trusted))
            BackupStore(db, "append").append(snapshot, "include")
            assertNull(db.ledger().namingSettings("append"))
            val empty = JSONObject(snapshot.toString()).put("naming_rules", JSONArray())
            BackupStore(destination, "restored").restore(empty, 0)
            assertTrue(NamingRuleStore(destination, "restored.db", "restored").snapshot().isEmpty())
            assertEquals(5, BackupStore(destination, "restored").export().getInt("format_version"))
        } finally { db.close(); destination.close() }
    }

    @Test fun invalidRestoreIsAtomicAndLegacySnapshotCannotSilentlyRemoveRules() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db, "a"); repo.seedDefaults()
            val category = db.ledger().categorySnapshot("a").first { it.type == "expense" }
            val store = NamingRuleStore(db, "a.db", "a")
            store.save(SmartNamingRule(UUID.randomUUID().toString(), "a.db", "a", "早餐", category.id, allowAutomatic = true))
            store.recordConfirmation(notice())
            val backup = BackupStore(db, "a")
            val original = backup.export()
            val pending = db.ledger().pending("a")
            val broken = JSONObject(original.toString())
            broken.getJSONArray("naming_rules").getJSONObject(0).put("allow_automatic", false)
            assertTrue(runCatching { backup.restore(broken, 9) }.isFailure)
            assertEquals(original.toString(), backup.export().toString())
            assertEquals(pending, db.ledger().pending("a"))
            val legacy = JSONObject(original.toString()).put("format_version", 4).apply { remove("naming_rules") }
            backup.restore(legacy, 0)
            assertEquals(1, store.snapshot().size)
            assertEquals(1, store.snapshot().single().trusted.size)
            assertTrue(runCatching { backup.restore(original, 1, requireEmpty = true) }.isFailure)
            assertEquals(1, store.snapshot().size)
        } finally { db.close() }
    }
}
