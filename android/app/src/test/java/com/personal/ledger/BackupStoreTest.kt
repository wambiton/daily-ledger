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
    @Test fun importsAppendAndDuplicatesNeedConfirmationEvenAfterBackup() = runBlocking {
        val repo = LedgerRepository(db,"source"); repo.seedDefaults()
        val cat = db.ledger().categorySnapshot("source").first()
        repo.save(null,cat.type,cat.id,"12.34",LocalDate.of(2025,1,2),"source")
        val payload = BackupStore(db,"source").export()
        val target = BackupStore(db,"target")
        db.ledger().setBackupState(SyncState("target", 4, 1))
        assertEquals(1,target.importLocal(payload))
        val before = db.ledger().entrySnapshot("target").single()
        assertTrue(runCatching { target.importLocal(payload) }.exceptionOrNull() is BackupStore.DuplicateImport)
        assertEquals(listOf(before),db.ledger().entrySnapshot("target"))
        assertEquals(1,target.importLocal(payload,true))
        assertEquals(2468L,db.ledger().entrySnapshot("target").sumOf { it.amountFen })
        assertEquals(2,db.ledger().entrySnapshot("target").map { it.id }.distinct().size)
        assertEquals(4L,db.ledger().backupState("target")!!.cursor)
        assertEquals(1,db.ledger().entrySnapshot("source").size)
        val restored = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),LedgerDatabase::class.java).build()
        try {
            val other = BackupStore(restored,"target")
            other.restore(target.export(),5)
            assertTrue(runCatching { other.importLocal(payload) }.exceptionOrNull() is BackupStore.DuplicateImport)
            restored.ledger().clearEntries("target")
            assertTrue(runCatching { other.importLocal(payload) }.exceptionOrNull() is BackupStore.DuplicateImport)
        } finally { restored.close() }
    }
    @Test fun versionOneMigrationPreservesExistingLedger() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${java.util.UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        val schema = JSONObject(java.io.File("schemas/com.personal.ledger.data.LedgerDatabase/1.json").readText()).getJSONObject("database")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
            val entities = schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
                val indices = entity.getJSONArray("indices")
                for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO categories VALUES ('category','owner','expense','餐饮',NULL,0)")
            old.execSQL("INSERT INTO entries VALUES ('entry','owner','expense','category',1234,'2025-01-02','migration',1,1,0,NULL)")
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context,LedgerDatabase::class.java,name).build()
        try {
            assertEquals(1234L,migrated.ledger().entrySnapshot("owner").single().amountFen)
            assertTrue(migrated.ledger().importedSources("owner").isEmpty())
            migrated.ledger().markImported(ImportedSource("owner","test-source"))
            assertEquals(listOf("test-source"),migrated.ledger().importedSources("owner"))
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
    @Test fun computerIdentitySurvivesAddressChangesAndRejectsForeignQr() {
        val computer = java.util.UUID.randomUUID().toString()
        val qr = JSONObject().put("type","personal-ledger-pair").put("version",1).put("server","https://one.example.com")
            .put("computer_id",computer).put("pair_key","a".repeat(43))
        val a = ComputerPair.parse(qr.toString()).put("user",JSONObject().put("id","user"))
        val name = ComputerPair.databaseName(a)
        a.put("server","https://two.example.com")
        assertEquals(name,ComputerPair.databaseName(a))
        a.put("computer_id",java.util.UUID.randomUUID().toString())
        assertNotEquals(name,ComputerPair.databaseName(a))
        qr.put("server","http://127.0.0.1:8765")
        assertTrue(runCatching { ComputerPair.parse(qr.toString()) }.isFailure)
        assertTrue(runCatching { ComputerPair.parse("https://example.com") }.isFailure)
    }
    @Test fun serverAddressesRejectUnsafeDestinations() {
        assertEquals("https://example.com",BackupClient.normalize("https://EXAMPLE.com/"))
        for(value in listOf("http://example.com","https://user:password@example.com","https://example.com/path","https://example.com/?secret=x")) {
            assertTrue(runCatching { BackupClient.normalize(value) }.isFailure)
        }
        assertNotEquals(AccountVault.databaseKey("https://a.test","user"),AccountVault.databaseKey("https://b.test","user"))
    }
}
