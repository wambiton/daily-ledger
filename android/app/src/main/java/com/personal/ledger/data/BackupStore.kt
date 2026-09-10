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
        JSONObject().put("format_version", 1).put("categories", cats).put("entries", entries)
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
        dao.clearEntries(owner); dao.clearOutbox(owner); dao.clearCategories(owner)
        categories.forEach { dao.insertCategory(it) }
        entries.forEach { dao.upsertEntry(it) }
        dao.setBackupState(SyncState(owner, revision, if(revision > 0) System.currentTimeMillis() else null))
    }
}
