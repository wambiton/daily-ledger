package com.personal.ledger.data

import androidx.room.withTransaction
import com.personal.ledger.domain.Rules
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** Portable snapshot; never contains passwords, tokens or another owner's rows. */
class BackupStore(private val db: LedgerDatabase, private val owner: String) {
    private val dao = db.ledger()
    suspend fun export(): JSONObject = db.withTransaction {
        val cats = JSONArray()
        dao.categorySnapshot(owner).forEach { c -> cats.put(JSONObject().put("id", c.id).put("type", c.type)
            .put("name", c.name).put("default_key", c.defaultKey ?: JSONObject.NULL)) }
        val entries = JSONArray()
        dao.entrySnapshot(owner).forEach { e -> entries.put(JSONObject().put("id", e.id).put("type", e.type)
            .put("category_id", e.categoryId).put("amount_fen", e.amountFen).put("date", e.date).put("note", e.note)
            .put("created_at", e.createdAt).put("updated_at", e.updatedAt)) }
        JSONObject().put("format_version", 1).put("categories", cats).put("entries", entries).put("import_sources", JSONArray(dao.importedSources(owner)))
    }
    class DuplicateImport(val count: Int): IllegalArgumentException("已有${count}笔本机账目重复导入")

    /** One transaction: append all rows, or change nothing until duplicates are confirmed. */
    suspend fun importLocal(payload: JSONObject, allowDuplicates: Boolean = false): Int = db.withTransaction {
        val items = payload.getJSONArray("entries")
        val sources = dao.importedSources(owner).toSet()
        val duplicates = (0 until items.length()).count {
            val id = items.getJSONObject(it).getString("id")
            id in sources || dao.entry(id)?.owner == owner
        }
        if(duplicates > 0 && !allowDuplicates) throw DuplicateImport(duplicates)
        val categories = dao.categorySnapshot(owner).toMutableList()
        val mapping = mutableMapOf<String, Category>()
        val cats = payload.getJSONArray("categories")
        val repo = LedgerRepository(db, owner)
        for(i in 0 until cats.length()) {
            val c = cats.getJSONObject(i)
            val category = categories.firstOrNull { it.type == c.getString("type") && it.name == c.getString("name") }
                ?: repo.addCategory(c.getString("type"), c.getString("name")).also { categories.add(it) }
            mapping[c.getString("id")] = category
        }
        for(i in 0 until items.length()) {
            val e = items.getJSONObject(i)
            val category = requireNotNull(mapping[e.getString("category_id")])
            require(category.type == e.getString("type"))
            val amount = java.math.BigDecimal.valueOf(e.getLong("amount_fen"), 2).toPlainString()
            repo.save(null, category.type, category.id, amount, LocalDate.parse(e.getString("date")), e.getString("note"))
            dao.markImported(ImportedSource(owner, e.getString("id")))
        }
        items.length()
    }

    suspend fun prepare(): JSONObject = db.withTransaction {
        JSONObject().put("operation_id", UUID.randomUUID().toString()).put("base_revision", dao.backupState(owner)?.cursor ?: 0)
            .put("payload", export()).put("pending_ids", JSONArray(dao.pending(owner).map { it.id }))
    }
    suspend fun acknowledge(revision: Long, ids: List<String>) = db.withTransaction {
        require(revision > 0)
        dao.setBackupState(SyncState(owner, revision, System.currentTimeMillis()))
        ids.chunked(500).forEach { dao.acknowledge(owner, it) }
    }
    suspend fun restore(payload: JSONObject, revision: Long, requireEmpty: Boolean = false) = db.withTransaction {
        require(payload.getInt("format_version") == 1 && revision >= 0) { "备份版本不支持" }
        if(requireEmpty) require(dao.entrySnapshot(owner).isEmpty()) { "当前账号已有账目，不能导入本机账本" }
        val cats = payload.getJSONArray("categories")
        val items = payload.getJSONArray("entries")
        require(cats.length() <= 2000 && items.length() <= 50000) { "备份记录过多" }
        val categories = (0 until cats.length()).map { i ->
            val c = cats.getJSONObject(i)
            val id = c.getString("id"); require(UUID.fromString(id).toString() == id)
            val type = c.getString("type"); require(type in listOf("expense", "income"))
            Category(id, owner, type, Rules.category(c.getString("name")), if(c.isNull("default_key")) null else c.getString("default_key"))
        }
        require(categories.map { it.id }.distinct().size == categories.size)
        val byId = categories.associateBy { it.id }
        val entries = (0 until items.length()).map { i ->
            val e = items.getJSONObject(i)
            val id = e.getString("id"); require(UUID.fromString(id).toString() == id)
            val category = requireNotNull(byId[e.getString("category_id")]) { "备份缺少分类" }
            require(e.getString("type") == category.type)
            val amount = e.getLong("amount_fen"); require(amount in 1..99999999999L)
            val day = LocalDate.parse(e.getString("date")); Rules.date(day)
            val note = e.getString("note"); Rules.note(note)
            val created = e.getLong("created_at"); val updated = e.getLong("updated_at")
            require(created >= 0 && updated >= created)
            Entry(id, owner, category.type, category.id, amount, day.toString(), note, created, updated)
        }
        require(entries.map { it.id }.distinct().size == entries.size)
        val sources = payload.optJSONArray("import_sources") ?: JSONArray()
        val sourceIds = (0 until sources.length()).map { sources.getString(it).also { id -> require(UUID.fromString(id).toString() == id) } }
        dao.clearImported(owner)
        sourceIds.forEach { dao.markImported(ImportedSource(owner, it)) }
        dao.clearEntries(owner); dao.clearOutbox(owner); dao.clearCategories(owner)
        categories.forEach { dao.insertCategory(it) }
        entries.forEach { dao.upsertEntry(it) }
        dao.setBackupState(SyncState(owner, revision, if(revision > 0) System.currentTimeMillis() else null))
    }
}
