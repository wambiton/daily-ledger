package com.personal.ledger.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="payment_candidates")
data class PaymentCandidate(@PrimaryKey val id: String, val databaseName: String, val owner: String, val bookLabel: String,
    val platform: String, val date: String, val amountFen: Long?, val type: String?, val merchant: String,
    val note: String, val refund: Boolean, val status: String, val reason: String, val entryId: String?=null,
    val receivedAt: Long=System.currentTimeMillis())

@Entity(tableName="merchant_categories",primaryKeys=["databaseName","owner","platform","merchant","type"])
data class MerchantCategory(val databaseName: String,val owner: String,val platform: String,val merchant: String,val type: String,val categoryId: String)

@Dao interface AutoEntryDao {
    @Query("SELECT * FROM payment_candidates ORDER BY receivedAt DESC") fun candidates(): Flow<List<PaymentCandidate>>
    @Query("SELECT * FROM payment_candidates WHERE id=:id") suspend fun candidate(id: String): PaymentCandidate?
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insert(candidate: PaymentCandidate): Long
    @Upsert suspend fun update(candidate: PaymentCandidate)
    @Query("SELECT * FROM merchant_categories WHERE databaseName=:db AND owner=:owner AND platform=:platform AND merchant=:merchant AND type=:type")
    suspend fun mapping(db: String,owner: String,platform: String,merchant: String,type: String): MerchantCategory?
    @Upsert suspend fun map(value: MerchantCategory)
}

@Database(entities=[PaymentCandidate::class,MerchantCategory::class],version=1,exportSchema=true)
abstract class AutoEntryDatabase: RoomDatabase() {
    abstract fun auto(): AutoEntryDao
    companion object {
        @Volatile private var instance: AutoEntryDatabase?=null
        fun get(context: Context): AutoEntryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,AutoEntryDatabase::class.java,"automatic-notices.db").build().also {instance=it}
        }
    }
}
