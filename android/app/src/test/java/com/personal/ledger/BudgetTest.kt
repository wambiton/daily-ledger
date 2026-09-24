package com.personal.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.ledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.YearMonth
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class BudgetTest {
    private lateinit var db: LedgerDatabase
    @Before fun start() { db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),LedgerDatabase::class.java).build() }
    @After fun close() { db.close() }
    private suspend fun category(): Category { LedgerRepository(db,"a").seedDefaults(); return db.ledger().categorySnapshot("a").first() }
    private fun plan(c: Category) = BudgetPlan(UUID.randomUUID().toString(),"a","生活费","2025-01","2025-03",450000,
        BudgetRules.encode(mapOf("2025-01" to mapOf(c.id to 170000L),"2025-02" to mapOf(c.id to 130000L))))
    @Test fun constraintsAndNoOverwritingPreviouslyPlannedMonths() = runBlocking {
        val c=category(); val p=plan(c); val repo=BudgetRepository(db,"a")
        repo.save(p)
        assertTrue(runCatching { repo.save(p.copy(id=UUID.randomUUID().toString())) }.isFailure)
        assertTrue(runCatching { repo.save(p.copy(totalFen=299999)) }.isFailure)
        repo.inheritDue(YearMonth.of(2025,3))
        val saved=db.ledger().budgetSnapshot("a").single()
        val values=BudgetRules.allocations(saved.allocations)
        assertEquals(170000L,values.getValue("2025-01").getValue(c.id))
        assertEquals(130000L,values.getValue("2025-02").getValue(c.id))
        assertEquals(130000L,values.getValue("2025-03").getValue(c.id))
        assertTrue(db.ledger().budgetSnapshot("b").isEmpty())
        val next=repo.nextPeriod(saved)
        assertEquals("2025-04",next.startMonth); assertEquals("2025-06",next.endMonth)
        assertEquals(450000L,next.totalFen)
    }
    @Test fun insufficientInheritanceDoesNotDestroyExistingAllocations() = runBlocking {
        val c=category(); val repo=BudgetRepository(db,"a"); val p=plan(c).copy(totalFen=300000)
        repo.save(p)
        assertEquals(1,repo.inheritDue(YearMonth.of(2025,3)).size)
        assertEquals(p,db.ledger().budgetSnapshot("a").single())
    }
    @Test fun thresholdRearmsAfterDecreaseAndDirectJumpOnlyNotifiesHundred() = runBlocking {
        val c=category(); val repo=BudgetRepository(db,"a"); val ledger=LedgerRepository(db,"a")
        repo.save(plan(c).copy(totalFen=10000,allocations="{}"))
        val e=ledger.save(null,"expense",c.id,"80",LocalDate.of(2025,1,1),"")
        assertEquals(listOf(80),repo.reconcile().map{it.level})
        assertTrue(repo.reconcile().isEmpty())
        ledger.save(e.id,"expense",c.id,"10",LocalDate.of(2025,1,1),""); assertTrue(repo.reconcile().isEmpty())
        ledger.save(e.id,"expense",c.id,"110",LocalDate.of(2025,1,1),"")
        assertEquals(listOf(100),repo.reconcile().map{it.level})
        ledger.delete(e.id); repo.reconcile(); ledger.restoreDeleted(e.id)
        assertEquals(listOf(100),repo.reconcile().map{it.level})
    }
    @Test fun spendingIsOwnerDateTypeAndTrashIsolated() = runBlocking {
        val c=category(); val p=plan(c)
        val e=Entry("a","a","expense",c.id,1234,"2025-01-01","",0,0)
        val rows=listOf(e,e.copy(id="b",owner="b"),e.copy(id="c",type="income"),e.copy(id="d",date="2024-12-31"),e.copy(id="e",deletedAt=1))
        assertEquals(1234L,BudgetRules.usage(p,rows,listOf(c)).first().spent)
        assertEquals(1234L,BudgetRules.usage(p,rows,listOf(c))[1].spent)
    }
    @Test fun budgetBackupRoundTripAndInvalidRestoreRetainsOriginal() = runBlocking {
        val c=category(); val repo=BudgetRepository(db,"a"); val p=plan(c); repo.save(p)
        val payload=BackupStore(db,"a").export()
        assertEquals(3,payload.getInt("format_version"))
        val other=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),LedgerDatabase::class.java).build()
        try {
            BackupStore(other,"b").restore(payload,1)
            assertEquals(listOf(p.copy(owner="b")),other.ledger().budgetSnapshot("b"))
            val invalid=org.json.JSONObject(payload.toString())
            invalid.getJSONArray("budgets").getJSONObject(0).put("total_fen",1)
            assertTrue(runCatching { BackupStore(other,"b").restore(invalid,2) }.isFailure)
            assertEquals(listOf(p.copy(owner="b")),other.ledger().budgetSnapshot("b"))
            assertEquals(1L,other.ledger().backupState("b")!!.cursor)
        } finally { other.close() }
    }

}
