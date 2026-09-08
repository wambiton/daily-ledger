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

@Dao
interface LedgerDao {
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

@Database(entities = [Category::class, Entry::class, PendingOperation::class, SyncState::class], version = 1, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() { abstract fun ledger(): LedgerDao }
