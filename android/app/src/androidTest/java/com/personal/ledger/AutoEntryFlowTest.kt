package com.personal.ledger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.ledger.data.*
import com.personal.ledger.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutoEntryFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var inbox: AutoEntryDatabase
    private lateinit var ledger: LedgerDatabase
    private val context=ApplicationProvider.getApplicationContext<android.content.Context>()
    private val inboxName="auto-test-${UUID.randomUUID()}.db"
    @Before fun setup() {
        inbox=Room.databaseBuilder(context,AutoEntryDatabase::class.java,inboxName).build()
        ledger=Room.databaseBuilder(context,LedgerDatabase::class.java,"auto-ledger-${UUID.randomUUID()}.db").build()
    }
    @After fun close() {inbox.close();ledger.close()}
    @Test fun mergedBankAndAlipaySourcesCanBeSplitFromReview() {
        val now=System.currentTimeMillis()
        val sms=PaymentNotice("sms","招商银行短信","2026-10-22",1230,"expense","测试店","模拟短信",false,false,false,source="sms",transactionAt=now,channel="支付宝",cardTail="2468")
        val pay=sms.copy(id="alipay",platform="支付宝",source="notification",note="模拟支付通知")
        fun candidate(n: PaymentNotice)=PaymentCandidate(n.id,"synthetic.db","a","模拟账本",n.platform,n.date,n.amountFen,n.type,n.merchant,n.note,n.refund,"pending","")
        val processor=AutoEntryProcessor(inbox)
        val review=runBlocking {
            LedgerRepository(ledger,"a").seedDefaults()
            val root=processor.receive(sms,candidate(sms),ledger)!!
            val child=processor.receive(pay,candidate(pay),ledger)!!
            assertEquals("merged",child.status)
            assertEquals(1,ledger.ledger().entrySnapshot("a").size)
            AutoEntryViewModel.Review(root,ledger.ledger().categorySnapshot("a"),emptyList(),ledger.ledger().entry(root.entryId!!),listOf(root,child))
        }
        var split=false
        ui.setContent {LedgerTheme {AutoEntryReview(review,false,null,{}, {_,_,_,_,_,_,_->},{}, {id->runBlocking {processor.split(id,ledger)};split=true})}}
        ui.onNodeWithTag("auto-review").performScrollToNode(hasTestTag("split-source-alipay"))
        ui.onNodeWithTag("split-source-alipay").performClick()
        assertFalse(split)
        ui.onNodeWithText("确认拆分，保留两笔").performClick()
        assertTrue(split)
        runBlocking {
            val rows=ledger.ledger().entrySnapshot("a")
            assertEquals(2,rows.size);assertEquals(2460L,rows.sumOf {it.amountFen})
            assertTrue(ledger.ledger().entrySnapshot("b").isEmpty())
        }
    }
    @Test fun incompleteNoticeNeedsCategoryAndAmountBeforeConfirmation() {
        val n=requireNotNull(PaymentNoticeParser.parse("com.tencent.mm","test",System.currentTimeMillis(),"微信支付","支付成功，金额已隐藏\n商户：测试店"))
        val c=PaymentCandidate(n.id,"synthetic.db","a","固定账本",n.platform,n.date,n.amountFen,n.type,n.merchant,n.note,n.refund,"pending","")
        val processor=AutoEntryProcessor(inbox)
        val pending=runBlocking {LedgerRepository(ledger,"a").seedDefaults();processor.receive(n,c,ledger)!!}
        val cats=runBlocking {ledger.ledger().categorySnapshot("a")}
        val category=cats.first{it.type=="expense"}
        var completed=false
        ui.setContent {LedgerTheme {AutoEntryReview(AutoEntryViewModel.Review(pending,cats,emptyList(),null),false,null,{},
            {type,cat,amount,date,note,remember,replace -> runBlocking {processor.confirm(pending,ledger,cat,type,amount,date,note,remember,replace)};completed=true},{})}}
        ui.onNodeWithTag("auto-review").performScrollToNode(hasTestTag("auto-confirm"))
        ui.onNodeWithTag("auto-confirm").assertIsNotEnabled()
        ui.onNodeWithTag("auto-review").performScrollToNode(hasTestTag("auto-amount"))
        ui.onNodeWithTag("auto-amount").performTextInput("12.34")
        ui.onNodeWithTag("auto-review").performScrollToNode(hasTestTag("auto-category-${category.id}"))
        ui.onNodeWithTag("auto-category-${category.id}").performClick()
        ui.onNodeWithTag("auto-review").performScrollToNode(hasTestTag("auto-confirm"))
        ui.onNodeWithTag("auto-confirm").performClick()
        assertTrue(completed)
        runBlocking {assertEquals(1234L,ledger.ledger().entrySnapshot("a").single().amountFen);assertTrue(ledger.ledger().entrySnapshot("b").isEmpty())}
    }
    @Test fun reopeningInboxDoesNotRepeatEntryAndIgnoresSwitchToOtherBook() = runBlocking {
        val repo=LedgerRepository(ledger,"a");repo.seedDefaults();LedgerRepository(ledger,"b").seedDefaults()
        val n=requireNotNull(PaymentNoticeParser.parse("com.eg.android.AlipayGphone","test",System.currentTimeMillis(),"支付宝","退款到账，3.00元"))
        val c=PaymentCandidate(n.id,"fixed.db","a","固定账本",n.platform,n.date,n.amountFen,n.type,n.merchant,n.note,true,"pending","")
        assertEquals("booked",AutoEntryProcessor(inbox).receive(n,c,ledger)!!.status)
        inbox.close();inbox=Room.databaseBuilder(context,AutoEntryDatabase::class.java,inboxName).build()
        assertNull(AutoEntryProcessor(inbox).receive(n,c.copy(owner="b"),ledger))
        assertEquals(1,ledger.ledger().entrySnapshot("a").size)
        assertTrue(ledger.ledger().entrySnapshot("b").isEmpty())
    }
}
