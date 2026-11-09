package com.personal.ledger

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.personal.ledger.data.*
import com.personal.ledger.domain.Rules
import com.personal.ledger.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AutoRecoveryFlowTest {
    @get:Rule val ui = createComposeRule()
    @Test fun restoreAndRevokeRequireConfirmationAndPreserveSources() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val name = "auto-recovery-${UUID.randomUUID()}.db"
        val owner = UUID.randomUUID().toString()
        val db = LedgerDatabases.acquire(app,name)
        val inbox = AutoEntryDatabase.get(app)
        val processor = AutoEntryProcessor(inbox)
        val holder = ViewModelStore()
        val ledger = LedgerViewModel(app,name,owner).also { holder.put("ledger",it) }
        val vm = AutoEntryViewModel(app).also { holder.put("auto",it) }
        try {
            val root = runBlocking {
                LedgerRepository(db,owner).seedDefaults()
                val n = PaymentNotice(UUID.randomUUID().toString(),"支付宝",Rules.today().toString(),850,"expense","模拟恢复商户","模拟测试",false,false,false,transactionAt=System.currentTimeMillis(),channel="支付宝")
                val c = PaymentCandidate(n.id,name,owner,"独立恢复测试",n.platform,n.date,n.amountFen,n.type,n.merchant,n.note,false,"pending","")
                val received = requireNotNull(processor.receive(n,c,db))
                processor.ignore(received.id)
                received
            }
            ui.setContent { LedgerTheme { AutoEntryScreen(ledger,vm) } }
            ui.waitUntil(10000) { vm.rows.value.any { it.id == root.id } }
            ui.onNodeWithTag("auto-list").performScrollToNode(hasText("已处理记录"))
            ui.onNodeWithText("已处理记录").performClick()
            fun openRestore() {
                ui.onNodeWithTag("auto-list").performScrollToNode(hasTestTag("auto-restore-${root.id}"))
                ui.onNodeWithTag("auto-restore-${root.id}").performClick()
            }
            openRestore()
            ui.onNodeWithText("取消").performClick()
            assertEquals("ignored",runBlocking {inbox.auto().candidate(root.id)!!.status})
            openRestore()
            ui.onNode(isDialog()).onChildren().fetchSemanticsNodes()
            ui.onAllNodesWithText("恢复到待确认").onLast().performClick()
            ui.waitUntil(10000) { !vm.busy.value && vm.rows.value.any {it.id==root.id && it.status=="pending"} }
            assertTrue(runBlocking {db.ledger().entrySnapshot(owner).isEmpty()})
            runBlocking {
                val category = db.ledger().categorySnapshot(owner).first {it.type=="expense"}
                processor.confirm(inbox.auto().candidate(root.id)!!,db,category.id,"expense","8.50",root.date,"模拟确认",false)
            }
            ui.waitUntil(10000) { vm.rows.value.any {it.id==root.id && it.status=="booked"} }
            ui.onNodeWithTag("auto-list").performScrollToNode(hasText("已处理记录"))
            ui.onNodeWithText("已处理记录").performClick()
            fun openRevoke() {
                ui.onNodeWithTag("auto-list").performScrollToNode(hasTestTag("auto-revoke-${root.id}"))
                ui.onNodeWithTag("auto-revoke-${root.id}").performClick()
            }
            openRevoke()
            ui.onNodeWithText("取消").performClick()
            assertEquals(850L,runBlocking {db.ledger().entrySnapshot(owner).single().amountFen})
            openRevoke()
            ui.onNodeWithText("撤销并移入回收站").performClick()
            ui.waitUntil(10000) { !vm.busy.value && vm.rows.value.any {it.id==root.id && it.status=="revoked"} }
            runBlocking {
                assertTrue(db.ledger().entrySnapshot(owner).isEmpty())
                assertNotNull(db.ledger().allEntries(owner).single().deletedAt)
                assertNotNull(inbox.auto().candidate(root.id))
            }
            assertNull(vm.error.value)
        } finally {
            holder.clear()
            val work = androidx.work.WorkManager.getInstance(app)
            for (job in listOf("budget/$name/$owner","trash-$name-$owner")) work.cancelUniqueWork(job).result.get()
            LedgerDatabases.release(name)
        }
    }
}
