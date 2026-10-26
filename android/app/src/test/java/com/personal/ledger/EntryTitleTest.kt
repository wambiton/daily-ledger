package com.personal.ledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.ledger.data.*
import com.personal.ledger.domain.EntrySearch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class EntryTitleTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun titleSurvivesEditingBackupAppendTrashAndIndependentRecovery() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).build()
        try {
            val repo = LedgerRepository(db,"source"); repo.seedDefaults()
            val category = db.ledger().categorySnapshot("source").first {it.type=="expense"}
            val entry = repo.save(null,category.type,category.id,"8.50",LocalDate.of(2025,1,1),"支付宝","早餐 🍞")
            val edited = repo.save(entry.id,entry.type,entry.categoryId,"9.00",LocalDate.parse(entry.date),"修改备注")
            assertEquals("早餐 🍞",edited.title)
            assertEquals(entry.categoryId,edited.categoryId)
            assertEquals(listOf(edited),EntrySearch(keyword="早餐").apply(listOf(edited),"source"))
            assertTrue(EntrySearch(keyword="早餐").apply(listOf(edited),"other").isEmpty())
            val snapshot = BackupStore(db,"source").export()
            assertEquals(4,snapshot.getInt("format_version"))
            assertEquals(0,snapshot.getJSONArray("budgets").length())
            val output=java.io.ByteArrayOutputStream()
            ExcelExport.write(output,PortableSnapshot.parse(snapshot,"excel"))
            var sheet=""
            java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while(true) { val item=zip.nextEntry ?: break
                    if(item.name=="xl/worksheets/sheet1.xml") sheet=zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            assertTrue(sheet.contains("早餐 🍞"));assertTrue(sheet.contains("名称"))
            assertEquals("早餐 🍞",PortableSnapshot.parse(snapshot,"copy").entries.single().title)
            assertTrue(runCatching {BackupCompatibility.requireSupported(JSONObject("""{"backup_formats":[1,2,3]}"""),4)}.isFailure)
            BackupStore(db,"copy").append(snapshot,"skip")
            assertEquals("早餐 🍞",db.ledger().allEntries("copy").single().title)
            val recovered = HistoryRecovery.create(context,snapshot,2,"synthetic")
            val restored = LedgerDatabases.acquire(context,recovered.getString("database"))
            try { assertEquals("早餐 🍞",restored.ledger().allEntries(recovered.getString("owner")).single().title) }
            finally { LedgerDatabases.release(recovered.getString("database")) }
            repo.delete(entry.id)
            assertEquals("早餐 🍞",PortableSnapshot.parse(BackupStore(db,"source").export(),"trash-copy").entries.single().title)
            repo.restoreDeleted(entry.id)
            val before = db.ledger().allEntries("source")
            assertTrue(runCatching {repo.save(entry.id,entry.type,entry.categoryId,"9",LocalDate.parse(entry.date),"", "长".repeat(41))}.isFailure)
            assertEquals(before,db.ledger().allEntries("source"))
            val broken = JSONObject(snapshot.toString())
            broken.getJSONArray("entries").getJSONObject(0).put("title",123)
            assertTrue(runCatching {BackupStore(db,"source").restore(broken,3)}.isFailure)
            assertEquals(before,db.ledger().allEntries("source"))
            val cleared = repo.save(entry.id,entry.type,entry.categoryId,"9",LocalDate.parse(entry.date),"", "")
            assertEquals("",cleared.title)
            assertEquals(2,BackupStore(db,"source").export().getInt("format_version"))
        } finally {db.close()}
    }

    @Test fun versionThreeMigrationRetainsEveryExistingTableAndDefaultsOnlyTitle() = runBlocking {
        val name="title-migration-${UUID.randomUUID()}.db"
        val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val schema=JSONObject(java.io.File("schemas/com.personal.ledger.data.LedgerDatabase/3.json").readText()).getJSONObject("database")
        val tables=mutableListOf<String>()
        val before=mutableMapOf<String,List<List<String?>>>()
        fun rows(db: android.database.sqlite.SQLiteDatabase, table: String): List<List<String?>> =
            db.rawQuery("SELECT * FROM $table ORDER BY rowid",null).use { c -> buildList {
                while(c.moveToNext()) add((0 until c.columnCount).map { if(c.isNull(it)) null else c.getString(it) })
            } }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val e=entities.getJSONObject(i);val table=e.getString("tableName");tables.add(table)
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=e.getJSONArray("indices")
                for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val setup=schema.getJSONArray("setupQueries")
            for(i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO categories VALUES ('c','owner','expense','餐饮',NULL,0)")
            old.execSQL("INSERT INTO entries VALUES ('e','owner','expense','c',850,'2025-01-01','旧备注',1,2,0,NULL)")
            old.execSQL("INSERT INTO entries VALUES ('trash','owner','expense','c',300,'2025-01-01','回收站',1,2,0,2)")
            old.execSQL("INSERT INTO outbox VALUES ('o','owner','e','entry','upsert',0,'{}',2,'pending')")
            old.execSQL("INSERT INTO budget_plans VALUES ('b','owner','模拟预算','2025-01','2025-01',10000,'{}',0)")
            old.execSQL("INSERT INTO budget_notices VALUES ('owner','b-notice',80,'模拟提醒')")
            old.execSQL("INSERT INTO sync_state VALUES ('owner',9,2)")
            old.execSQL("INSERT INTO imported_sources VALUES ('owner','source')")
            tables.forEach {before[it]=rows(old,it)};old.version=3
        }
        val migrated=Room.databaseBuilder(context,LedgerDatabase::class.java,name).build()
        try {
            assertEquals("",migrated.ledger().allEntries("owner").first().title)
            assertEquals(850L,migrated.ledger().entrySnapshot("owner").single().amountFen)
            android.database.sqlite.SQLiteDatabase.openDatabase(path.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { current ->
                tables.forEach { table ->
                    val after=rows(current,table).map { if(table=="entries") it.dropLast(1) else it }
                    assertEquals(table,before.getValue(table),after)
                }
            }
        } finally {migrated.close()}
    }
}
