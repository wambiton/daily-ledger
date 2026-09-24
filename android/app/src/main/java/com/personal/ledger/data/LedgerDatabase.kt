package com.personal.ledger.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "categories", indices = [Index(value = ["owner", "type", "name"], unique = true)])
data class Category(
    @PrimaryKey val id: String,
    val owner: String,
    val type: String,
    val name: String,
    val defaultKey: String? = null,
    val version: Long = 0,
)

@Entity(tableName = "entries", indices = [Index(value = ["owner", "date"]), Index("categoryId")],
    foreignKeys = [ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.RESTRICT)])
data class Entry(
    @PrimaryKey val id: String,
    val owner: String,
    val type: String,
    val categoryId: String,
    val amountFen: Long,
    val date: String,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Long = 0,
    val deletedAt: Long? = null,
)

@Entity(tableName = "outbox", indices = [Index(value = ["owner", "entityId"])])
data class PendingOperation(
    @PrimaryKey val id: String,
    val owner: String,
    val entityId: String,
    val entityType: String,
    val action: String,
    val baseVersion: Long,
    val payload: String,
    val createdAt: Long,
    val status: String = "pending",
)

@Entity(tableName = "sync_state")
data class SyncState(@PrimaryKey val owner: String, val cursor: Long = 0, val lastSuccessAt: Long? = null)

@Entity(tableName = "imported_sources", primaryKeys = ["owner", "sourceId"])
data class ImportedSource(val owner: String, val sourceId: String)

@Entity(tableName = "budget_plans", indices = [Index("owner")])
data class BudgetPlan(@PrimaryKey val id: String, val owner: String, val name: String,
    val startMonth: String, val endMonth: String, val totalFen: Long,
    val allocations: String = "{}", val renewalDismissed: Boolean = false)

@Entity(tableName = "budget_notices", primaryKeys = ["owner", "key"])
data class BudgetNotice(val owner: String, val key: String, val level: Int, val message: String)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM budget_plans WHERE owner = :owner ORDER BY startMonth DESC") fun budgets(owner: String): Flow<List<BudgetPlan>>
    @Query("SELECT * FROM budget_plans WHERE owner = :owner ORDER BY startMonth") suspend fun budgetSnapshot(owner: String): List<BudgetPlan>
    @Upsert suspend fun putBudget(plan: BudgetPlan)
    @Query("DELETE FROM budget_plans WHERE owner = :owner") suspend fun clearBudgets(owner: String)
    @Query("SELECT * FROM budget_notices WHERE owner = :owner") suspend fun noticeSnapshot(owner: String): List<BudgetNotice>
    @Query("SELECT * FROM budget_notices WHERE owner = :owner AND level > 0") fun notices(owner: String): Flow<List<BudgetNotice>>
    @Upsert suspend fun putNotice(notice: BudgetNotice)
    @Query("DELETE FROM budget_notices WHERE owner = :owner") suspend fun clearNotices(owner: String)

    @Query("SELECT * FROM entries WHERE owner = :owner ORDER BY createdAt, id") suspend fun allEntries(owner: String): List<Entry>
    @Query("SELECT * FROM entries WHERE owner = :owner AND deletedAt IS NOT NULL ORDER BY deletedAt DESC") fun trash(owner: String): Flow<List<Entry>>
    @Query("DELETE FROM entries WHERE owner = :owner AND deletedAt IS NOT NULL AND deletedAt <= :cutoff") suspend fun purgeTrash(owner: String, cutoff: Long): Int
    @Query("SELECT COUNT(DISTINCT entityId) FROM outbox WHERE owner = :owner AND entityType = 'entry'") fun pendingEntries(owner: String): Flow<Int>
    @Query("SELECT * FROM sync_state WHERE owner = :owner") fun observeBackupState(owner: String): Flow<SyncState?>
    @Query("SELECT sourceId FROM imported_sources WHERE owner = :owner") suspend fun importedSources(owner: String): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun markImported(source: ImportedSource)
    @Query("DELETE FROM imported_sources WHERE owner = :owner") suspend fun clearImported(owner: String)
    @Query("SELECT * FROM categories WHERE owner = :owner ORDER BY rowid") suspend fun categorySnapshot(owner: String): List<Category>
    @Query("SELECT * FROM entries WHERE owner = :owner AND deletedAt IS NULL ORDER BY createdAt, id") suspend fun entrySnapshot(owner: String): List<Entry>
    @Query("SELECT * FROM sync_state WHERE owner = :owner") suspend fun backupState(owner: String): SyncState?
    @Upsert suspend fun setBackupState(state: SyncState)
    @Query("DELETE FROM entries WHERE owner = :owner") suspend fun clearEntries(owner: String)
    @Query("DELETE FROM categories WHERE owner = :owner") suspend fun clearCategories(owner: String)
    @Query("DELETE FROM outbox WHERE owner = :owner") suspend fun clearOutbox(owner: String)
    @Query("DELETE FROM outbox WHERE owner = :owner AND id IN (:ids)") suspend fun acknowledge(owner: String, ids: List<String>)
    @Query("SELECT * FROM categories WHERE owner = :owner ORDER BY rowid")
    fun categories(owner: String): Flow<List<Category>>
    @Query("SELECT * FROM entries WHERE owner = :owner AND deletedAt IS NULL ORDER BY createdAt DESC, id DESC")
    fun entries(owner: String): Flow<List<Entry>>
    @Query("SELECT COUNT(*) FROM outbox WHERE owner = :owner")
    fun pendingCount(owner: String): Flow<Int>
    @Query("SELECT * FROM categories WHERE id = :id") suspend fun category(id: String): Category?
    @Query("SELECT * FROM entries WHERE id = :id") suspend fun entry(id: String): Entry?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun seed(category: Category): Long
    @Insert suspend fun insertCategory(category: Category)
    @Upsert suspend fun upsertEntry(entry: Entry)
    @Insert suspend fun enqueue(operation: PendingOperation)
    @Query("SELECT * FROM outbox WHERE owner = :owner ORDER BY createdAt, rowid") suspend fun pending(owner: String): List<PendingOperation>
}

@Database(entities = [Category::class, Entry::class, PendingOperation::class, SyncState::class, ImportedSource::class, BudgetPlan::class, BudgetNotice::class], version = 3, exportSchema = true, autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)])
abstract class LedgerDatabase : RoomDatabase() { abstract fun ledger(): LedgerDao }
