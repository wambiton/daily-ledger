package com.personal.ledger

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.ledger.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Explicit two-process test; excluded from ordinary suites. Keeps synthetic evidence. */
@RunWith(AndroidJUnit4::class)
class NotificationRestartTest {
    @Test fun persistedCandidateAcrossProcessRestart() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val phase=InstrumentationRegistry.getArguments().getString("restart_phase")
        require(phase in setOf("prepare","verify"))
        val file=File(context.filesDir,"notification-restart-test.json")
        val manager=context.getSystemService(NotificationManager::class.java)
        val inbox=AutoEntryDatabase.get(context)
        if(phase=="prepare") {
            val name="notification-restart-${UUID.randomUUID()}.db"
            val owner=UUID.randomUUID().toString()
            val ledger=LedgerDatabases.acquire(context,name)
            try {
                LedgerRepository(ledger,owner).seedDefaults()
                val cat=ledger.ledger().categorySnapshot(owner).first {it.type=="expense"}
                NamingRuleStore(ledger,name,owner).save(SmartNamingRule(UUID.randomUUID().toString(),name,owner,"重启模拟账目",cat.id,channel="支付宝",allowAutomatic=true))
                val n=PaymentNotice(UUID.randomUUID().toString(),"支付宝","2026-10-26",1234,"expense","模拟重启商户","独立测试",false,false,false,transactionAt=System.currentTimeMillis(),channel="支付宝")
                val c=AutoEntryProcessor(inbox).receive(n,PaymentCandidate(n.id,name,owner,"重启模拟账本",n.platform,n.date,n.amountFen,n.type,n.merchant,n.note,false,"pending",""),ledger)!!
                assertEquals("pending",c.status)
                assertEquals(cat.id,c.suggestedCategory)
                assertTrue(c.ruleSignature.isNotBlank())
                file.writeText(JSONObject().put("db",name).put("owner",owner).put("id",c.id).put("pid",android.os.Process.myPid()).toString())
                AutoCapture.notify(context,c)
                val tasks=context.getSystemService(android.app.ActivityManager::class.java).appTasks
                tasks.forEach {it.finishAndRemoveTask()}
            } finally {LedgerDatabases.release(name)}
        } else {
            val saved=JSONObject(file.readText())
            assertNotEquals("A real process restart is required",saved.getInt("pid"),android.os.Process.myPid())
            val id=saved.getString("id");val name=saved.getString("db");val owner=saved.getString("owner")
            val candidate=inbox.auto().candidate(id)
            assertNotNull("Candidate must survive process exit",candidate)
            assertEquals(name,candidate!!.databaseName)
            assertEquals(owner,candidate.owner)
            assertEquals("pending",candidate.status)
            // Instrumentation startup can clear system notifications. This phase tests explicit
            // reconstruction from persisted evidence, not retention of the original system alert.
            AutoCapture.notify(context,candidate)
            val notification=manager.activeNotifications.single {it.tag==id}.notification
            assertEquals(3,notification.actions.size)
            notification.actions[0].actionIntent.send()
            val end=System.currentTimeMillis()+20000
            while(inbox.auto().candidate(id)!!.status!="booked" && System.currentTimeMillis()<end) kotlinx.coroutines.delay(100)
            assertEquals("booked",inbox.auto().candidate(id)!!.status)
            notification.actions[0].actionIntent.send()
            kotlinx.coroutines.delay(500)
            val ledger=LedgerDatabases.acquire(context,name)
            try {
                val rows=ledger.ledger().entrySnapshot(owner)
                assertEquals(1,rows.size);assertEquals(1234L,rows.single().amountFen)
                assertEquals("重启模拟账目",rows.single().title)
            } finally {LedgerDatabases.release(name);manager.cancel(id,8100)}
        }
    }
}
