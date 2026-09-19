package com.personal.ledger.data

import androidx.room.withTransaction
import com.personal.ledger.domain.Rules
import java.time.LocalDate
import java.util.UUID
import org.json.JSONObject

class LedgerRepository(private val db: LedgerDatabase, val owner: String) {
    private val dao = db.ledger()
    val categories = dao.categories(owner)
    val entries = dao.entries(owner)
    val pendingCount = dao.pendingCount(owner)
    val trash = dao.trash(owner)
    companion object { const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000 }

    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int = db.withTransaction {
        // Expiry changes the next full snapshot, including previously backed-up trash.
        dao.allEntries(owner).filter { it.deletedAt != null && it.deletedAt <= now - RETENTION_MS }
            .forEach { queueEntry(it, "delete") }
        dao.purgeTrash(owner, now - RETENTION_MS)
    }

    suspend fun restoreDeleted(id: String, now: Long = System.currentTimeMillis()) = db.withTransaction {
        val entry = requireNotNull(dao.entry(id)) { "账目已过期或不存在" }
        require(entry.owner == owner) { "无法访问此账目" }
        val deleted = requireNotNull(entry.deletedAt) { "账目已恢复" }
        require(deleted > now - RETENTION_MS) { "这笔账目已超过30天，无法恢复" }
        val restored = entry.copy(deletedAt = null, updatedAt = now)
        dao.upsertEntry(restored)
        queueEntry(restored, "upsert")
    }

    suspend fun seedDefaults() = db.withTransaction {
        val names = mapOf(
            "expense" to listOf("餐饮", "电子物品", "交通", "日用品", "学习", "娱乐", "其他支出"),
            "income" to listOf("生活费", "工资", "兼职", "红包", "其他收入"),
        )
        names.forEach { (type, values) -> values.forEachIndexed { index, name ->
            val key = "$type-$index"
            val id = UUID.nameUUIDFromBytes("$owner/default/$key".toByteArray(Charsets.UTF_8)).toString()
            val category = Category(id, owner, type, name, key)
            if (dao.seed(category) != -1L) queueCategory(category)
        } }
    }

    suspend fun addCategory(type: String, name: String): Category = db.withTransaction {
        require(type in listOf("income", "expense")) { "请选择收入或支出" }
        val category = Category(UUID.randomUUID().toString(), owner, type, Rules.category(name))
        dao.insertCategory(category)
        queueCategory(category)
        category
    }

    suspend fun save(id: String?, type: String, categoryId: String, amount: String, date: LocalDate, note: String): Entry = db.withTransaction {
        val category = requireNotNull(dao.category(categoryId)) { "请先选择分类" }
        require(category.owner == owner && category.type == type) { "分类与收支类型不一致" }
        Rules.date(date)
        Rules.note(note)
        val value = Rules.amount(amount)
        val previous = if (id != null) requireNotNull(dao.entry(id)) { "账目不存在" } else null
        require(previous == null || (previous.owner == owner && previous.deletedAt == null)) { "账目不可修改" }
        val now = System.currentTimeMillis()
        val entry = Entry(id ?: UUID.randomUUID().toString(), owner, type, categoryId, value,
            date.toString(), note, previous?.createdAt ?: now, now, previous?.version ?: 0)
        dao.upsertEntry(entry)
        queueEntry(entry, "upsert")
        entry
    }

    suspend fun delete(id: String) = db.withTransaction {
        val entry = requireNotNull(dao.entry(id)) { "账目不存在" }
        require(entry.owner == owner) { "无法访问此账目" }
        if (entry.deletedAt == null) {
            val deleted = entry.copy(deletedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            dao.upsertEntry(deleted)
            queueEntry(deleted, "delete")
        }
    }

    private suspend fun queueCategory(category: Category) {
        val payload = JSONObject().put("type", category.type).put("name", category.name)
            .put("default_key", category.defaultKey ?: JSONObject.NULL)
        enqueue(category.id, "category", "upsert", category.version, payload)
    }
    private suspend fun queueEntry(entry: Entry, action: String) {
        val payload = JSONObject().put("type", entry.type).put("category_id", entry.categoryId)
            .put("amount_fen", entry.amountFen).put("entry_date", entry.date).put("note", entry.note)
        enqueue(entry.id, "entry", action, entry.version, payload)
    }
    private suspend fun enqueue(id: String, type: String, action: String, version: Long, payload: JSONObject) {
        dao.enqueue(PendingOperation(UUID.randomUUID().toString(), owner, id, type, action, version,
            payload.toString(), System.currentTimeMillis()))
    }
}
