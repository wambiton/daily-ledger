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
class AutoEntryTest {
    private lateinit var inbox: AutoEntryDatabase
    private lateinit var ledger: LedgerDatabase
    private val time=1750000000000L
    @Before fun start() {
        inbox=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AutoEntryDatabase::class.java).build()
        ledger=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),LedgerDatabase::class.java).build()
    }
    @After fun close() {inbox.close();ledger.close()}
    private fun notice(key: String="a",text: String="支付成功，支出18.50元\n商户：校园食堂")=requireNotNull(PaymentNoticeParser.parse("com.tencent.mm",key,time,"微信支付",text))
    private fun target(n: PaymentNotice)=PaymentCandidate(n.id,"test.db","a","测试账本",n.platform,n.date,n.amountFen,n.type,n.merchant,n.note,n.refund,"pending","")
    @Test fun parserIgnoresChatAndAmbiguousAmountsNeverAutoBook() {
        assertNull(PaymentNoticeParser.parse("other","k",time,"微信支付","支付成功18元"))
        assertNull(PaymentNoticeParser.parse("com.tencent.mm","k",time,"朋友","支付成功18元"))
        assertNull(PaymentNoticeParser.parse("com.tencent.mm","k",time,"微信支付","验证码123456，支付成功18元"))
        assertEquals(1850L,notice().amountFen)
        assertTrue(notice(text="支付成功，原价20元，实付18元").uncertain)
        assertNull(notice(text="支付成功，金额已隐藏").amountFen)
        assertTrue(notice(text="充值成功，20元").excluded)
        assertEquals("income",notice(text="退款到账，18元").type)
    }
    @Test fun mappingThenAutomaticEntryAndRepeatedNotificationStayIdempotent() = runBlocking {
        val repo=LedgerRepository(ledger,"a");repo.seedDefaults()
        val cat=ledger.ledger().categorySnapshot("a").first{it.type=="expense"}
        val processor=AutoEntryProcessor(inbox);val first=notice()
        val pending=processor.receive(first,target(first),ledger)!!
        assertEquals("pending",pending.status);assertTrue(ledger.ledger().entrySnapshot("a").isEmpty())
        processor.confirm(pending,ledger,cat.id,"expense","18.50",first.date,first.note,true)
        val second=notice("b", "支付成功，支出20.50元\n商户：校园食堂")
        assertEquals("booked",processor.receive(second,target(second),ledger)!!.status)
        assertNull(processor.receive(second,target(second),ledger))
        assertEquals(2,ledger.ledger().entrySnapshot("a").size)
        assertTrue(ledger.ledger().entrySnapshot("other").isEmpty())
        val repeat=notice("c", "支付成功，支出20.50元\n商户：校园食堂")
        assertEquals("pending",processor.receive(repeat,target(repeat),ledger)!!.status)
        assertEquals(2,ledger.ledger().entrySnapshot("a").size)
    }
    @Test fun replacementMovesOnlySelectedOriginalToTrashAndRefundDoesNotReduceExpense() = runBlocking {
        val repo=LedgerRepository(ledger,"a");repo.seedDefaults()
        val cat=ledger.ledger().categorySnapshot("a").first{it.type=="expense"}
        val n=notice();val old=repo.save(null,"expense",cat.id,"18.50",java.time.LocalDate.parse(n.date),"original")
        val processor=AutoEntryProcessor(inbox);val p=processor.receive(n,target(n),ledger)!!
        processor.confirm(p,ledger,cat.id,"expense","18.50",n.date,n.note,false,old.id)
        assertNotNull(ledger.ledger().entry(old.id)!!.deletedAt)
        assertEquals(1,ledger.ledger().entrySnapshot("a").size)
        repo.addCategory("income","退款")
        val refund=notice("refund","退款到账，3.00元")
        assertEquals("booked",processor.receive(refund,target(refund),ledger)!!.status)
        val rows=ledger.ledger().entrySnapshot("a")
        assertEquals(1850L,rows.filter{it.type=="expense"}.sumOf{it.amountFen})
        assertEquals(300L,rows.filter{it.type=="income"}.sumOf{it.amountFen})
    }
}
