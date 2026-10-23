package com.personal.ledger.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="payment_candidates")
data class PaymentCandidate(@PrimaryKey val id: String, val databaseName: String, val owner: String, val bookLabel: String,
    val platform: String, val date: String, val amountFen: Long?, val type: String?, val merchant: String,
    val note: String, val refund: Boolean, val status: String, val reason: String, val entryId: String?=null,
    val receivedAt: Long=System.currentTimeMillis(),
    @ColumnInfo(defaultValue="'notification'") val source: String="notification",
    @ColumnInfo(defaultValue="0") val transactionAt: Long=0,
    @ColumnInfo(defaultValue="''") val channel: String="",
    @ColumnInfo(defaultValue="''") val cardTail: String="",
    val mergedInto: String?=null,
    @ColumnInfo(defaultValue="0") val mergeDisabled: Boolean=false)

@Entity(tableName="merchant_categories",primaryKeys=["databaseName","owner","platform","merchant","type"])
data class MerchantCategory(val databaseName: String,val owner: String,val platform: String,val merchant: String,val type: String,val categoryId: String)

@Dao interface AutoEntryDao {
    @Query("SELECT COUNT(*) FROM payment_candidates WHERE status='pending'") fun pendingCount(): Flow<Int>
    @Query("SELECT * FROM payment_candidates ORDER BY receivedAt DESC") fun candidates(): Flow<List<PaymentCandidate>>
    @Query("SELECT * FROM payment_candidates WHERE id=:id") suspend fun candidate(id: String): PaymentCandidate?
    @Query("SELECT * FROM payment_candidates WHERE databaseName=:db AND owner=:owner AND transactionAt BETWEEN :start AND :end AND status IN ('pending','booked') AND mergedInto IS NULL AND mergeDisabled=0")
    suspend fun nearby(db: String,owner: String,start: Long,end: Long): List<PaymentCandidate>
    @Query("SELECT * FROM payment_candidates WHERE mergedInto=:id") suspend fun children(id: String): List<PaymentCandidate>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insert(candidate: PaymentCandidate): Long
    @Upsert suspend fun update(candidate: PaymentCandidate)
    @Query("SELECT * FROM merchant_categories WHERE databaseName=:db AND owner=:owner AND platform=:platform AND merchant=:merchant AND type=:type")
    suspend fun mapping(db: String,owner: String,platform: String,merchant: String,type: String): MerchantCategory?
    @Upsert suspend fun map(value: MerchantCategory)
}

@Database(entities=[PaymentCandidate::class,MerchantCategory::class],version=2,exportSchema=true)
abstract class AutoEntryDatabase: RoomDatabase() {
    abstract fun auto(): AutoEntryDao
    companion object {
        val MIGRATION_1_2 = object: androidx.room.migration.Migration(1,2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE payment_candidates ADD COLUMN source TEXT NOT NULL DEFAULT 'notification'")
                db.execSQL("ALTER TABLE payment_candidates ADD COLUMN transactionAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE payment_candidates ADD COLUMN channel TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE payment_candidates ADD COLUMN cardTail TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE payment_candidates ADD COLUMN mergedInto TEXT")
                db.execSQL("ALTER TABLE payment_candidates ADD COLUMN mergeDisabled INTEGER NOT NULL DEFAULT 0")
            }
        }
        @Volatile private var instance: AutoEntryDatabase?=null
        fun get(context: Context): AutoEntryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,AutoEntryDatabase::class.java,"automatic-notices.db").addMigrations(MIGRATION_1_2).build().also {instance=it}
        }
    }
}
