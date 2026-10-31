package com.personal.ledger

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.ledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

/** Real PendingIntent -> receiver -> WorkManager -> isolated ledger, without touching capture preferences. */
@RunWith(AndroidJUnit4::class)
class NotificationActionFlowTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="notification-test-${UUID.randomUUID()}.db"
    private val owner=UUID.randomUUID().toString()
    private lateinit var ledger: LedgerDatabase
    private lateinit var root: PaymentCandidate
    private lateinit var child: PaymentCandidate
    private val inbox get()=AutoEntryDatabase.get(context)
    private val manager get()=context.getSystemService(NotificationManager::class.java)
    @Before fun setup() = runBlocking {
        ledger=LedgerDatabases.acquire(context,name)
        LedgerRepository(ledger,owner).seedDefaults()
        val category=ledger.ledger().categorySnapshot(owner).first {it.type=="expense"}
        NamingRuleStore(ledger,name,owner).save(SmartNamingRule(UUID.randomUUID().toString(),name,owner,"模拟早餐",category.id,channel="支付宝",allowAutomatic=true))
        val n=PaymentNotice(UUID.randomUUID().toString(),"支付宝","2026-10-26",850,"expense","模拟通知商店","独立测试",false,false,false,transactionAt=System.currentTimeMillis(),channel="支付宝")
        fun candidate(p: PaymentNotice)=PaymentCandidate(p.id,name,owner,"通知测试账本",p.platform,p.date,p.amountFen,p.type,p.merchant,p.note,p.refund,"pending","")
        root=AutoEntryProcessor(inbox).receive(n,candidate(n),ledger)!!
        val sms=n.copy(id=UUID.randomUUID().toString(),platform="招商银行短信",source="sms")
        child=AutoEntryProcessor(inbox).receive(sms,candidate(sms),ledger)!!
        assertEquals("linked",child.status)
        AutoCapture.notify(context,root)
    }
    @After fun close() {
        manager.cancel(root.id,8100);manager.cancel(child.id,8100)
        LedgerDatabases.release(name) // Keep all synthetic database rows for inspection.
    }
    private fun awaitState(check: suspend () -> Boolean) {
        val end=System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<end) {
            if(runBlocking {check()}) return
            Thread.sleep(100)
        }
        fail("Notification worker did not reach expected state")
    }
    @Test fun remoteInputThenRepeatedConfirmUsesOriginalBookAndKeepsRenamedTitle() {
        val prefs=AutoCapture.prefs(context).all.toMap()
        val n=manager.activeNotifications.single {it.tag==root.id}.notification
        assertEquals(3,n.actions.size)
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE,n.visibility)
        assertTrue(n.publicVersion.actions.isNullOrEmpty())
        val reply=Intent()
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(AutoEntryActions.NAME).build()),reply,Bundle().apply {putCharSequence(AutoEntryActions.NAME,"模拟午餐")})
        n.actions[1].actionIntent.send(context,0,reply)
        awaitState {inbox.auto().candidate(root.id)!!.suggestedTitle=="模拟午餐"}
        runBlocking {assertTrue(ledger.ledger().entrySnapshot(owner).isEmpty())}
        repeat(2) {n.actions[0].actionIntent.send()}
        awaitState {inbox.auto().candidate(root.id)!!.status=="booked"}
        runBlocking {
            assertEquals("模拟午餐",ledger.ledger().entrySnapshot(owner).single().title)
            assertEquals(root.id,inbox.auto().candidate(child.id)!!.entryId)
            assertTrue(NamingRuleStore(ledger,name,owner).snapshot().single().trusted.isEmpty())
        }
        // Wait for both queued commands, not merely the first database update.
        val work=androidx.work.WorkManager.getInstance(context)
        awaitState {work.getWorkInfosForUniqueWork("payment-action/${root.id}").get().all {it.state.isFinished}}
        runBlocking {assertEquals(1,ledger.ledger().entrySnapshot(owner).size)}
        assertEquals(prefs,AutoCapture.prefs(context).all)
    }
    @Test fun ignoreThenStaleConfirmDoesNotCreateEntryOrDeleteEvidence() {
        val n=manager.activeNotifications.single {it.tag==root.id}.notification
        n.actions[2].actionIntent.send()
        awaitState {inbox.auto().candidate(root.id)!!.status=="ignored"}
        n.actions[0].actionIntent.send()
        val work=androidx.work.WorkManager.getInstance(context)
        Thread.sleep(300)
        awaitState {work.getWorkInfosForUniqueWork("payment-action/${root.id}").get().all {it.state.isFinished}}
        runBlocking {
            assertTrue(ledger.ledger().entrySnapshot(owner).isEmpty())
            assertEquals("ignored",inbox.auto().candidate(child.id)!!.status)
            assertEquals("ignored",inbox.auto().candidate(root.id)!!.status)
        }
    }
}
