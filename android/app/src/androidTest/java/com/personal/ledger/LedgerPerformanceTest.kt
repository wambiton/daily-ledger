package com.personal.ledger

import android.content.Context
import android.os.Bundle
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.ledger.data.*
import com.personal.ledger.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth
import java.util.UUID
import kotlin.system.measureTimeMillis

class LedgerPerformanceTest {
    @Test fun tenThousandRecordsRoundTrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val firstName = "performance-${UUID.randomUUID()}.db"
        val secondName = "performance-restored-${UUID.randomUUID()}.db"
        val first = Room.databaseBuilder(context,LedgerDatabase::class.java,firstName).build()
        val second = Room.databaseBuilder(context,LedgerDatabase::class.java,secondName).build()
        try {
            val owner = "performance-only"
            val repo = LedgerRepository(first,owner)
            repo.seedDefaults()
            val categories = first.ledger().categorySnapshot(owner).filter { it.type == "expense" }
            val today = Rules.today()
            first.withTransaction {
                repeat(10000) { index ->
                    first.ledger().upsertEntry(Entry(UUID.randomUUID().toString(),owner,"expense",categories[index % categories.size].id,
                        100L + index,today.minusDays((index % 1095).toLong()).toString(),"synthetic",1,1))
                }
            }
            val saveMs = measureTimeMillis { repo.save(null,"expense",categories.first().id,"1.23",today,"new-synthetic") }
            var records = emptyList<Entry>()
            val loadMs = measureTimeMillis { records = first.ledger().entrySnapshot(owner) }
            assertEquals(10001,records.size)
            val periods = (0..11).map { Comparison.month(YearMonth.from(today).minusMonths(it.toLong()),today) }
            val compareMs = measureTimeMillis {
                val result = Comparison.calculateMany(records,"expense",periods)
                assertEquals(12,result.totals.size)
                periods.forEachIndexed { index, period ->
                    assertEquals(records.filter { period.contains(it.date) }.sumOf { it.amountFen },result.totals[index])
                }
            }
            val source = BackupStore(first,owner)
            val snapshot = source.export()
            val restoreMs = measureTimeMillis { BackupStore(second,owner).restore(snapshot,1) }
            val recovered = second.ledger().entrySnapshot(owner)
            assertEquals(records.map { it.id }.toSet(),recovered.map { it.id }.toSet())
            assertEquals(50995123L,recovered.sumOf { it.amountFen })
            InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {
                putString("stream","\nP6 synthetic 10001 records: save=${saveMs}ms, load=${loadMs}ms, compare12=${compareMs}ms, restore=${restoreMs}ms\n")
            })
        } finally {
            first.close(); second.close()
            context.deleteDatabase(firstName); context.deleteDatabase(secondName)
        }
    }
}
