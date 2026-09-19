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
        dao.allEntries(owner).filter { it.deletedAt == null || it.deletedAt > System.currentTimeMillis() - LedgerRepository.RETENTION_MS }.forEach { e -> entries.put(JSONObject().put("id", e.id).put("type", e.type)
            .put("category_id", e.categoryId).put("amount_fen", e.amountFen).put("date", e.date).put("note", e.note)
            .put("created_at", e.createdAt).put("updated_at", e.updatedAt).put("deleted_at", e.deletedAt ?: JSONObject.NULL)) }
        JSONObject().put("format_version", 2).put("categories", cats).put("entries", entries).put("import_sources", JSONArray(dao.importedSources(owner)))
    }
    class DuplicateImport(val count: Int): IllegalArgumentException("已有${count}笔本机账目重复导入")

    data class Preview(val normal: Int, val trash: Int, val duplicates: Int, val expired: Int)
    suspend fun duplicateIds(payload: JSONObject): Set<String> = db.withTransaction {
        val known = dao.importedSources(owner).toSet() + dao.allEntries(owner).map { it.id }
        PortableSnapshot.parse(payload,owner).entries.filter { it.id in known }.map { it.id }.toSet()
    }
    suspend fun preview(payload: JSONObject): Preview = db.withTransaction {
        val parsed = PortableSnapshot.parse(payload, owner)
        val retained = parsed.retained(System.currentTimeMillis())
        val known = dao.importedSources(owner).toSet() + dao.allEntries(owner).map { it.id }
        Preview(retained.count { it.deletedAt == null }, retained.count { it.deletedAt != null },
            retained.count { it.id in known }, parsed.entries.size - retained.size)
    }
    suspend fun importLocal(payload: JSONObject, allowDuplicates: Boolean = false): Int =
        append(payload, if(allowDuplicates) "include" else "confirm")

    /** Append only, including trash. Preview and writes are rechecked in one transaction. */
    suspend fun append(payload: JSONObject, duplicates: String): Int = db.withTransaction {
        require(duplicates in listOf("include", "skip", "confirm"))
        val parsed = PortableSnapshot.parse(payload, owner)
        val known = dao.importedSources(owner).toSet() + dao.allEntries(owner).map { it.id }
        val retained = parsed.retained(System.currentTimeMillis())
        val duplicateCount = retained.count { it.id in known }
        if(duplicateCount > 0 && duplicates == "confirm") throw DuplicateImport(duplicateCount)
        val items = retained.filter { duplicates != "skip" || it.id !in known }
        val categories = dao.categorySnapshot(owner).toMutableList()
        val mapping = mutableMapOf<String, Category>()
        val repo = LedgerRepository(db, owner)
        val needed = items.map { it.categoryId }.toSet()
        parsed.categories.filter { it.id in needed }.forEach { c ->
            val category = categories.firstOrNull { it.type == c.type && it.name == c.name }
                ?: repo.addCategory(c.type,c.name).also { categories.add(it) }
            mapping[c.id] = category
        }
        items.forEach { source ->
            val entry = source.copy(id = UUID.randomUUID().toString(),categoryId = mapping.getValue(source.categoryId).id)
            dao.upsertEntry(entry)
            dao.enqueue(PendingOperation(UUID.randomUUID().toString(),owner,entry.id,"entry",
                if(entry.deletedAt == null) "upsert" else "delete",0,"{}",System.currentTimeMillis()))
            dao.markImported(ImportedSource(owner,source.id))
        }
        items.size
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
        require(revision >= 0)
        val parsed = PortableSnapshot.parse(payload, owner)
        if(requireEmpty) require(dao.allEntries(owner).isEmpty()) { "当前账号已有账目，不能替换账本" }
        val categories = parsed.categories
        val entries = parsed.retained(System.currentTimeMillis())
        val sourceIds = parsed.sources
        dao.clearImported(owner)
        sourceIds.forEach { dao.markImported(ImportedSource(owner, it)) }
        dao.clearEntries(owner); dao.clearOutbox(owner); dao.clearCategories(owner)
        categories.forEach { dao.insertCategory(it) }
        entries.forEach { dao.upsertEntry(it) }
        dao.setBackupState(SyncState(owner, revision, if(revision > 0) System.currentTimeMillis() else null))
    }
}
