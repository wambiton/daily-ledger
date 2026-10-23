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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class BankMergeTest {
    private lateinit var inbox: AutoEntryDatabase
    private lateinit var ledger: LedgerDatabase
    private lateinit var processor: AutoEntryProcessor
    private val time=1750000000000L
    private fun platform(id: String="platform")=PaymentNotice(id,"支付宝","2025-06-15",1230,"expense","测试店","模拟支付",false,false,false,transactionAt=time,channel="支付宝")
    private fun sms(id: String="sms")=platform(id).copy(platform="招商银行短信",source="sms",cardTail="2468")
    private fun target(n: PaymentNotice,owner: String="a")=PaymentCandidate(n.id,"test.db",owner,"模拟账本",n.platform,n.date,n.amountFen,n.type,n.merchant,n.note,n.refund,"pending","")
    @Before fun setup() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        inbox=Room.inMemoryDatabaseBuilder(context,AutoEntryDatabase::class.java).build()
        ledger=Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).build()
        processor=AutoEntryProcessor(inbox)
        LedgerRepository(ledger,"a").seedDefaults();LedgerRepository(ledger,"b").seedDefaults()
    }
    @After fun close(){inbox.close();ledger.close()}
    @Test fun migrationKeepsOldCandidatesAndMerchantRules() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="migration-bank-${java.util.UUID.randomUUID()}.db"
        val old=context.openOrCreateDatabase(name,0,null)
        old.execSQL("CREATE TABLE payment_candidates (id TEXT NOT NULL PRIMARY KEY,databaseName TEXT NOT NULL,owner TEXT NOT NULL,bookLabel TEXT NOT NULL,platform TEXT NOT NULL,date TEXT NOT NULL,amountFen INTEGER,type TEXT,merchant TEXT NOT NULL,note TEXT NOT NULL,refund INTEGER NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL,entryId TEXT,receivedAt INTEGER NOT NULL)")
        old.execSQL("CREATE TABLE merchant_categories (databaseName TEXT NOT NULL,owner TEXT NOT NULL,platform TEXT NOT NULL,merchant TEXT NOT NULL,type TEXT NOT NULL,categoryId TEXT NOT NULL,PRIMARY KEY(databaseName,owner,platform,merchant,type))")
        old.execSQL("INSERT INTO payment_candidates VALUES ('old','test.db','a','模拟','支付宝','2025-06-15',1230,'expense','测试店','保留备注',0,'booked','原记录','entry',123)")
        old.execSQL("INSERT INTO merchant_categories VALUES ('test.db','a','支付宝','测试店','expense','food')")
        old.version=1;old.close()
        val migrated=Room.databaseBuilder(context,AutoEntryDatabase::class.java,name).addMigrations(AutoEntryDatabase.MIGRATION_1_2).build()
        try {
            val row=migrated.auto().candidate("old")!!
            assertEquals("保留备注",row.note);assertEquals("entry",row.entryId)
            assertEquals(0L,row.transactionAt);assertEquals("notification",row.source)
            assertEquals("food",migrated.auto().mapping("test.db","a","支付宝","测试店","expense")!!.categoryId)
        } finally {migrated.close()}
    }
    @Test fun smsFirstMergesOnceAndSplitPreservesBoth() = runBlocking {
        val first=processor.receive(sms(),target(sms()),ledger)!!
        assertEquals("booked",first.status)
        val second=processor.receive(platform(),target(platform()),ledger)!!
        assertEquals("merged",second.status)
        assertEquals(1,ledger.ledger().entrySnapshot("a").size)
        assertEquals(1230L,ledger.ledger().entrySnapshot("a").sumOf {it.amountFen})
        assertNull(processor.receive(platform(),target(platform()),ledger))
        processor.split(second.id,ledger)
        assertEquals(2460L,ledger.ledger().entrySnapshot("a").sumOf {it.amountFen})
        assertTrue(runCatching {processor.split(second.id,ledger)}.isFailure)
        assertNotNull(ledger.ledger().entry(first.entryId!!))
    }
    @Test fun pendingPlatformFirstIsResolvedByBankEvidence() = runBlocking {
        assertEquals("pending",processor.receive(platform(),target(platform()),ledger)!!.status)
        assertEquals("merged",processor.receive(sms(),target(sms()),ledger)!!.status)
        assertEquals("booked",inbox.auto().candidate("platform")!!.status)
        val row=ledger.ledger().entrySnapshot("a").single()
        assertEquals("其他支出",ledger.ledger().category(row.categoryId)!!.name)
    }
    @Test fun sameAmountSeparatePaymentsCannotAllCollapseIntoOne() = runBlocking {
        processor.receive(sms(),target(sms()),ledger)
        processor.receive(platform(),target(platform()),ledger)
        processor.receive(sms("sms2"),target(sms("sms2")),ledger)
        processor.receive(platform("platform2"),target(platform("platform2")),ledger)
        assertEquals(2,ledger.ledger().entrySnapshot("a").size)
        assertEquals(1,inbox.auto().children("sms").size)
        assertEquals(1,inbox.auto().children("sms2").size)
    }
    @Test fun differentOwnerChannelTimeAndRefundDoNotMerge() = runBlocking {
        processor.receive(sms(),target(sms()),ledger)
        val notices=listOf(platform("wx").copy(platform="微信",channel="微信"),
            platform("late").copy(transactionAt=time+300001),
            platform("refund").copy(type="income",refund=true))
        for(n in notices) assertNotEquals("merged",processor.receive(n,target(n),ledger)!!.status)
        val other=platform("other")
        assertNotEquals("merged",processor.receive(other,target(other,"b"),ledger)!!.status)
    }
    @Test fun deletedAndEditedEntriesAreNeverReplacedByMerge() = runBlocking {
        processor.receive(sms(),target(sms()),ledger)
        LedgerRepository(ledger,"a").delete("sms")
        assertNotEquals("merged",processor.receive(platform(),target(platform()),ledger)!!.status)
        assertNotNull(ledger.ledger().entry("sms")!!.deletedAt)
    }
    @Test fun refundsAndIncomeAutoBookWhileExcludedNotCounted() = runBlocking {
        for(n in listOf(sms("refund").copy(refund=true,type="income"),sms("income").copy(type="income"),sms("excluded").copy(excluded=true)))
            processor.receive(n,target(n),ledger)
        assertEquals(2,ledger.ledger().entrySnapshot("a").size)
        assertEquals(2460L,ledger.ledger().entrySnapshot("a").sumOf {it.amountFen})
        assertEquals("ignored",inbox.auto().candidate("excluded")!!.status)
    }
    @Test fun crashAfterLedgerWriteCanReplayWithoutDoubleEntry() = runBlocking {
        val n=sms()
        processor.receive(n,target(n),ledger)
        // Simulate an inbox transaction rollback while the separate ledger commit survived.
        inbox.openHelper.writableDatabase.execSQL("DELETE FROM payment_candidates")
        processor.receive(n,target(n),ledger)
        assertEquals(1,ledger.ledger().entrySnapshot("a").size)
    }
}
