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
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PortableBackupTest {
    private lateinit var db: LedgerDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LedgerDatabase::class.java).build() }
    @After fun close() { db.close() }
    private suspend fun payload(): JSONObject {
        val repo = LedgerRepository(db,"source"); repo.seedDefaults()
        val c = db.ledger().categorySnapshot("source").first()
        repo.save(null,c.type,c.id,"12.34",LocalDate.of(2025,1,1),"=SUM(A1) & <test> 中文")
        val removed = repo.save(null,c.type,c.id,"2",LocalDate.of(2025,1,2),"trash")
        repo.delete(removed.id)
        return BackupStore(db,"source").export()
    }
    @Test fun appendPreservesTrashDeletionAndDuplicateChoice() = runBlocking {
        val payload = payload(); val store = BackupStore(db,"target")
        val deleted = payload.getJSONArray("entries").getJSONObject(1).getLong("deleted_at")
        assertEquals(2,store.append(payload,"skip"))
        assertEquals(deleted,db.ledger().allEntries("target").single {it.deletedAt!=null}.deletedAt)
        assertEquals(2,store.preview(payload).duplicates)
        assertEquals(0,store.append(payload,"skip"))
        assertEquals(2,store.append(payload,"include"))
        assertEquals(4,db.ledger().allEntries("target").size)
        assertEquals(2468L,db.ledger().entrySnapshot("target").sumOf {it.amountFen})
        assertEquals(2,db.ledger().allEntries("source").size)
    }
    @Test fun malformedFileRollsBackAndExpiredTrashCannotResurrect() = runBlocking {
        val payload = payload(); val store = BackupStore(db,"target")
        store.append(payload,"skip")
        val before = db.ledger().allEntries("target")
        val broken = JSONObject(payload.toString())
        broken.getJSONArray("entries").getJSONObject(1).put("amount_fen",1.5)
        assertTrue(runCatching {store.append(broken,"include")}.isFailure)
        assertEquals(before,db.ledger().allEntries("target"))
        val expired = payload.getJSONArray("entries").getJSONObject(1)
        val deletion = System.currentTimeMillis() - LedgerRepository.RETENTION_MS - 1
        expired.put("created_at",1).put("updated_at",deletion).put("deleted_at",deletion)
        assertEquals(1,store.preview(payload).expired)
        assertEquals(1,BackupStore(db,"third").append(payload,"include"))
    }
    @Test fun excelIsNumericAndDoesNotExecuteTextOrExportTrash() = runBlocking {
        val payload = payload(); val output = ByteArrayOutputStream()
        ExcelExport.write(output,PortableSnapshot.parse(payload,"export"))
        val parts = mutableMapOf<String,ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while(true) { val entry=zip.nextEntry ?: break; parts[entry.name]=zip.readBytes() }
        }
        val factory = DocumentBuilderFactory.newInstance(); factory.isNamespaceAware = true
        parts.filterKeys {it.endsWith(".xml") || it.endsWith(".rels")}.forEach { (_,bytes) -> factory.newDocumentBuilder().parse(bytes.inputStream()) }
        val sheet=factory.newDocumentBuilder().parse(parts.getValue("xl/worksheets/sheet1.xml").inputStream())
        val ns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        assertEquals(2,sheet.getElementsByTagNameNS(ns,"row").length)
        assertEquals(0,sheet.getElementsByTagNameNS(ns,"f").length)
        assertEquals("12.34",sheet.getElementsByTagNameNS(ns,"v").item(0).textContent)
        assertTrue(sheet.documentElement.textContent.contains("=SUM(A1) & <test> 中文"))
    }
    @Test fun versionOneStillLoadsAndVersionTwoRestoresTrash() = runBlocking {
        val payload=payload()
        val other=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),LedgerDatabase::class.java).build()
        try {
            BackupStore(other,"target").restore(payload,2)
            assertEquals(2,other.ledger().allEntries("target").size)
            assertEquals(1,other.ledger().entrySnapshot("target").size)
            val legacy=JSONObject(payload.toString()).put("format_version",1)
            val rows=legacy.getJSONArray("entries")
            for(i in 0 until rows.length()) rows.getJSONObject(i).remove("deleted_at")
            BackupStore(other,"target").restore(legacy,3)
            assertEquals(2,other.ledger().entrySnapshot("target").size)
        } finally {other.close()}
    }
}
