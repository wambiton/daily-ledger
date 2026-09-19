package com.personal.ledger

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.ledger.data.*
import com.personal.ledger.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Rule
import org.junit.Assume
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID

class PhoneBookRecoveryTest {
    @get:Rule val ui=createComposeRule()
    @Test fun returningLoginReusesUnbackedBookAndSelectedImportOnlyAppends() {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.containsKey("backup_email"))
        val app=ApplicationProvider.getApplicationContext<Application>()
        val namespace="alias-test-${UUID.randomUUID()}"
        val email=args.getString("backup_email")!!
        val login=runBlocking { BackupClient("http://127.0.0.1:8767").call("auth/login/",JSONObject().put("email",email).put("password","12345678")) }
        val owner=login.getJSONObject("user").getString("id")
        val name="account-${AccountVault.databaseKey(namespace,owner)}.db"
        val db=LedgerDatabases.acquire(app,name)
        val sourceName="$namespace-source.db"
        val source=LedgerDatabases.acquire(app,sourceName)
        val holder=ViewModelStore()
        try {
            runBlocking {
                for((database,who) in listOf(db to owner,source to "local-preview")) {
                    val repo=LedgerRepository(database,who);repo.seedDefaults()
                    val cat=database.ledger().categorySnapshot(who).first()
                    repo.save(null,cat.type,cat.id,"12.34",LocalDate.of(2026,9,15),"synthetic-preserve")
                }
            }
            val before=runBlocking { db.ledger().entrySnapshot(owner) }
            val sourceBefore=runBlocking { source.ledger().entrySnapshot("local-preview") }
            AccountVault(app,"$namespace-carry").write(JSONObject().put("database",name).put("owner",owner))
            val vm=BackupViewModel(app,namespace,"$namespace-local.db");holder.put("vm",vm)
            ui.setContent { LedgerTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AccountScreen(vm) } } }
            fun signIn() {
                ui.runOnIdle { vm.signIn("http://127.0.0.1:8767",email,"12345678","","login") }
                ui.waitUntil(30000) { !vm.busy.value }
                assertNotNull(vm.message.value,vm.account.value)
                assertEquals(name,vm.databaseName(vm.account.value!!))
                assertEquals(before,runBlocking { db.ledger().entrySnapshot(owner) })
            }
            signIn()
            ui.runOnIdle { vm.logout() };ui.waitUntil(30000) { !vm.busy.value }
            signIn()
            ui.runOnIdle { vm.selectBook(JSONObject().put("database",sourceName).put("owner","local-preview").put("label","测试手机账本")) }
            ui.onNodeWithTag("upload-backup").assertIsNotEnabled()
            ui.onNodeWithTag("import-selected-book").performScrollTo().performClick()
            ui.waitUntil(10000) { vm.bookImport.value != null && !vm.busy.value }
            ui.onNodeWithTag("import-select-all").performClick()
            ui.onNodeWithTag("confirm-backup-action").performClick()
            ui.waitUntil(10000) { !vm.busy.value }
            assertEquals(2,runBlocking { db.ledger().entrySnapshot(owner).size })
            ui.runOnIdle { vm.previewBookImport(vm.selectedBook.value!!) };ui.waitUntil(10000) { !vm.busy.value }
            assertEquals(1,vm.bookImport.value!!.duplicateIds.size)
            ui.onNodeWithTag("import-select-all").performClick()
            assertEquals(2,runBlocking { db.ledger().entrySnapshot(owner).size })
            ui.onNodeWithTag("confirm-duplicate-import").performClick();ui.waitUntil(10000) { !vm.busy.value }
            assertEquals(3,runBlocking { db.ledger().entrySnapshot(owner).size })
            assertEquals(sourceBefore,runBlocking { source.ledger().entrySnapshot("local-preview") })
            assertTrue(runBlocking { db.ledger().entrySnapshot(owner).containsAll(before) })
        } finally { holder.clear();LedgerDatabases.release(name);LedgerDatabases.release(sourceName) }
    }
    @Test fun oldScanCarryIsAccessibleOfflineAndSurvivesReopeningWithoutChangingEntries() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val namespace="recovery-test-${UUID.randomUUID()}"
        val owner=UUID.randomUUID().toString()
        val name="account-${AccountVault.databaseKey(namespace,owner)}.db"
        val local="$namespace-local.db"
        val db=LedgerDatabases.acquire(app,name)
        val holder=ViewModelStore()
        try {
            runBlocking {
                val repo=LedgerRepository(db,owner);repo.seedDefaults()
                val cat=db.ledger().categorySnapshot(owner).first()
                repo.save(null,cat.type,cat.id,"23.45",LocalDate.of(2026,9,14),"synthetic-unbacked")
            }
            val before=runBlocking { db.ledger().entrySnapshot(owner) }
            AccountVault(app,"$namespace-carry").write(JSONObject().put("database",name).put("owner",owner))
            val vm=BackupViewModel(app,namespace,local);holder.put("first",vm)
            assertNull(vm.account.value)
            assertEquals(name,vm.selectedBook.value!!.getString("database"))
            ui.setContent { LedgerTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AccountScreen(vm) } } }
            ui.onNodeWithTag("choose-phone-book").performScrollTo().performClick()
            ui.onNodeWithTag("choose-original-book").performClick()
            assertEquals(local,vm.selectedBook.value!!.getString("database"))
            ui.onNodeWithTag("choose-phone-book").performClick()
            ui.onNodeWithTag("choose-saved-book-0").performClick()
            assertEquals(name,vm.selectedBook.value!!.getString("database"))
            val reopened=BackupViewModel(app,namespace,local);holder.put("second",reopened)
            assertEquals(name,reopened.selectedBook.value!!.getString("database"))
            assertEquals(before,runBlocking { db.ledger().entrySnapshot(owner) })
            assertTrue(runBlocking { db.ledger().pending(owner).isNotEmpty() })
        } finally { holder.clear();LedgerDatabases.release(name) }
    }

    @Test fun confirmedComputerChangeKeepsBookAndCancellationLeavesAccountUntouched() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val namespace="pair-preserve-test-${UUID.randomUUID()}"
        val owner=UUID.randomUUID().toString()
        val account=JSONObject().put("server","https://old.example.com").put("computer_id",UUID.randomUUID().toString())
            .put("user",JSONObject().put("id",owner).put("email","synthetic@qq.com"))
        val name=ComputerPair.databaseName(account)
        val db=LedgerDatabases.acquire(app,name)
        val holder=ViewModelStore()
        try {
            runBlocking {
                val repo=LedgerRepository(db,owner);repo.seedDefaults()
                val cat=db.ledger().categorySnapshot(owner).first()
                repo.save(null,cat.type,cat.id,"67.89",LocalDate.of(2026,9,15),"synthetic-original")
            }
            val before=runBlocking { db.ledger().entrySnapshot(owner) }
            AccountVault(app,namespace).write(account)
            val vm=BackupViewModel(app,namespace,"$namespace-local.db");holder.put("vm",vm)
            val verifiedFixture=JSONObject().put("server","https://new.example.com").put("computer_id",UUID.randomUUID().toString()).put("pair_key","x".repeat(43))
            ui.setContent { LedgerTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AccountScreen(vm) } } }
            ui.runOnIdle { vm.pendingPair.value=verifiedFixture;vm.dismissPair() }
            assertNotNull(vm.account.value)
            ui.runOnIdle { vm.pendingPair.value=verifiedFixture }
            ui.onNodeWithTag("confirm-pair").performClick()
            ui.waitUntil(10000) { !vm.busy.value }
            assertNull(vm.account.value)
            assertEquals(name,vm.selectedBook.value!!.getString("database"))
            assertEquals(before,runBlocking { db.ledger().entrySnapshot(owner) })
            assertTrue(vm.message.value.contains("尚未登录或备份"))
        } finally { holder.clear();LedgerDatabases.release(name) }
    }
}
