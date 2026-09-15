package com.personal.ledger

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.ledger.data.*
import com.personal.ledger.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID

class AccountBackupFlowTest {
    @get:Rule val ui = createComposeRule()
    @Test fun resetRequiresMatchingPasswordsAndNewPasswordLogsIn() {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.containsKey("reset_email"))
        val app=ApplicationProvider.getApplicationContext<Application>()
        val namespace="reset-test-${UUID.randomUUID()}"
        val vm=BackupViewModel(app,namespace)
        val holder=ViewModelStore().apply { put("backup",vm) }
        try {
            ui.setContent { LedgerTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AccountScreen(vm) } } }
            ui.onNodeWithTag("auth-mode-reset").performScrollTo().performClick()
            ui.onNodeWithTag("server-address").performScrollTo().performTextInput("http://127.0.0.1:8767")
            ui.onNodeWithTag("account-email").performScrollTo().performTextInput(args.getString("reset_email")!!)
            ui.onNodeWithTag("account-password").performScrollTo().performTextInput("new-12345678")
            ui.onNodeWithTag("account-password-confirm").performScrollTo().performTextInput("different123")
            ui.onNodeWithTag("toggle-password-confirm").performScrollTo().performClick()
            assertFalse(ui.onNodeWithTag("account-password-confirm").fetchSemanticsNode().config.contains(SemanticsProperties.Password))
            ui.onNodeWithTag("toggle-password-confirm").performClick()
            assertTrue(ui.onNodeWithTag("account-password-confirm").fetchSemanticsNode().config.contains(SemanticsProperties.Password))
            ui.onNodeWithTag("account-code").performScrollTo().performTextInput("654321")
            ui.onNodeWithTag("account-submit").performScrollTo().performClick()
            ui.waitUntil(10000) { !vm.busy.value }
            assertTrue(vm.message.value,vm.message.value.contains("两次输入的新密码不一致"))
            ui.onNodeWithTag("account-password-confirm").performScrollTo().performTextReplacement("new-12345678")
            ui.onNodeWithTag("account-submit").performScrollTo().performClick()
            ui.waitUntil(30000) { !vm.busy.value }
            assertTrue(vm.message.value,vm.message.value.startsWith("密码已重置"))
            ui.onNodeWithTag("auth-mode-login").performScrollTo().performClick()
            ui.onNodeWithTag("account-password").performScrollTo().performTextInput("new-12345678")
            ui.onNodeWithTag("toggle-password").performScrollTo().performClick()
            assertFalse(ui.onNodeWithTag("account-password").fetchSemanticsNode().config.contains(SemanticsProperties.Password))
            ui.onNodeWithTag("account-submit").performScrollTo().performClick()
            ui.waitUntil(30000) { !vm.busy.value }
            assertNotNull(vm.message.value,vm.account.value)
            assertEquals(args.getString("reset_email"),vm.account.value!!.getJSONObject("user").getString("email"))
        } finally { holder.clear() }
    }
    @Test fun loginUploadRestoreAndKeepOtherAccountIsolated() {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Needs isolated local fixture server", args.containsKey("backup_email"))
        val app = ApplicationProvider.getApplicationContext<Application>()
        val namespace = "test-${UUID.randomUUID()}"
        val vm = BackupViewModel(app, namespace)
        val holder = ViewModelStore().apply { put("backup",vm) }
        var db: LedgerDatabase? = null
        var name: String? = null
        try {
            ui.setContent { LedgerTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AccountScreen(vm) } } }
            ui.onNodeWithTag("server-address").performTextInput("http://127.0.0.1:8767")
            ui.onNodeWithTag("account-email").performTextInput(args.getString("backup_email")!!)
            ui.onNodeWithTag("account-password").performTextInput("1234567")
            ui.onNodeWithTag("account-submit").performScrollTo().performClick()
            ui.waitForIdle()
            ui.onNodeWithTag("account-message").assertTextContains("密码至少需要8个字符，不限制字符类型")
            assertEquals(7,ui.onNodeWithTag("account-password").fetchSemanticsNode().config[SemanticsProperties.EditableText].text.length)
            ui.onNodeWithTag("account-password").performScrollTo().performTextReplacement("12345678")
            ui.onNodeWithTag("account-submit").performScrollTo().performClick()
            ui.waitUntil(30000) { !vm.busy.value && !vm.uploading.value }
            assertNotNull(vm.message.value,vm.account.value)
            val account = vm.account.value!!
            name = vm.databaseName(account)
            db = Room.databaseBuilder(app,LedgerDatabase::class.java,name).build()
            val owner = account.getJSONObject("user").getString("id")
            val repo = LedgerRepository(db,owner)
            runBlocking {
                repo.seedDefaults()
                val cat = db.ledger().categorySnapshot(owner).first { it.type == "expense" }
                repo.save(null,"expense",cat.id,"45.67",LocalDate.of(2025,3,4),"synthetic-device-backup")
            }
            ui.onNodeWithTag("upload-backup").performScrollTo().performClick()
            ui.waitUntil(60000) { vm.message.value.startsWith("备份成功") }
            assertTrue(vm.message.value,vm.message.value.startsWith("备份成功"))
            // A fresh fixture database simulates another phone; real ledgers are never opened.
            ui.runOnIdle { vm.logout() }
            ui.waitUntil(30000) { !vm.busy.value && !vm.uploading.value }
            db.close()
            app.deleteDatabase(name)
            ui.runOnIdle { vm.signIn("http://127.0.0.1:8767",args.getString("backup_email")!!,"12345678","","login") }
            ui.waitUntil(30000) { !vm.busy.value && !vm.uploading.value }
            assertNotNull(vm.message.value, vm.account.value)
            db = Room.databaseBuilder(app,LedgerDatabase::class.java,name).build()
            runBlocking {
                assertEquals(4567L,db.ledger().entrySnapshot(owner).single().amountFen)
                assertTrue(db.ledger().entrySnapshot("unrelated-account").isEmpty())
                assertEquals(1L,db.ledger().backupState(owner)!!.cursor)
            }
            // Encrypted session survives recreating the account controller.
            val reopened = BackupViewModel(app,namespace)
            holder.put("reopened",reopened)
            assertEquals(owner,reopened.account.value!!.getJSONObject("user").getString("id"))
            ui.runOnIdle { vm.logout() }
            ui.waitUntil(30000) { !vm.busy.value && !vm.uploading.value }
            assertNull(vm.account.value)
            runBlocking { assertEquals(1,db.ledger().entrySnapshot(owner).size) }
        } finally {
            holder.clear(); db?.close()
            name?.let { app.deleteDatabase(it) }
            AccountVault(app,namespace).clear()
        }
    }
    @Test fun registrationImportsAndDuplicateConfirmationAppends() {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.containsKey("register_email"))
        val app = ApplicationProvider.getApplicationContext<Application>()
        val namespace = "register-test-${UUID.randomUUID()}"
        val local = "$namespace.db"
        val source = LedgerDatabases.acquire(app, local)
        val vm = BackupViewModel(app, namespace, local)
        val holder = ViewModelStore().apply { put("backup",vm) }
        var accountName: String? = null
        try {
            runBlocking {
                val repo = LedgerRepository(source,"local-preview"); repo.seedDefaults()
                val cat = source.ledger().categorySnapshot("local-preview").first()
                repo.save(null,cat.type,cat.id,"10.00",LocalDate.of(2025,1,2),"synthetic-import")
            }
            ui.setContent { LedgerTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AccountScreen(vm) } } }
            ui.runOnIdle { vm.signIn("http://127.0.0.1:8767", args.getString("register_email")!!,"12345678",args.getString("register_code")!!,"register") }
            ui.waitUntil(30000) { !vm.busy.value && !vm.uploading.value }
            assertNotNull(vm.message.value,vm.account.value)
            val account = vm.account.value!!
            accountName = vm.databaseName(account)
            val target = LedgerDatabases.acquire(app, accountName)
            try {
                val owner = account.getJSONObject("user").getString("id")
                runBlocking { assertEquals(1,target.ledger().entrySnapshot(owner).size) }
                ui.onNodeWithTag("upload-backup").performScrollTo().performClick()
                ui.waitUntil(60000) { vm.message.value.startsWith("备份成功") }
                assertTrue(vm.message.value,vm.message.value.startsWith("备份成功"))
                ui.onNodeWithTag("import-local").performScrollTo().performClick()
                ui.onNodeWithTag("confirm-backup-action").performClick()
                ui.waitUntil(10000) { vm.duplicateCount.value == 1 }
                runBlocking { assertEquals(1,target.ledger().entrySnapshot(owner).size) }
                ui.onNodeWithTag("confirm-duplicate-import").performClick()
                ui.waitUntil(10000) { !vm.busy.value && vm.duplicateCount.value == 0 }
                runBlocking {
                    val entries = target.ledger().entrySnapshot(owner)
                    assertEquals(2,entries.map { it.id }.distinct().size)
                    assertEquals(2000L,entries.sumOf { it.amountFen })
                    assertEquals(1,source.ledger().entrySnapshot("local-preview").size)
                }
                ui.onNodeWithText("查看电脑备份").assertDoesNotExist()
                ui.onNodeWithTag("restore-backup").assertDoesNotExist()
            } finally { LedgerDatabases.release(accountName) }
        } finally {
            holder.clear(); LedgerDatabases.release(local); app.deleteDatabase(local)
            accountName?.let { app.deleteDatabase(it) }; AccountVault(app,namespace).clear()
        }
    }

    @Test fun offlineLoginWaitsWithoutPublishingEmptyAccountThenResumes() {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.containsKey("waiting_email"))
        val app = ApplicationProvider.getApplicationContext<Application>()
        val namespace = "waiting-test-${UUID.randomUUID()}"
        val vm = BackupViewModel(app, namespace)
        val holder = ViewModelStore().apply { put("backup",vm) }
        var name: String? = null
        try {
            ui.setContent { LedgerTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AccountScreen(vm) } } }
            ui.runOnIdle { vm.signIn("http://127.0.0.1:8767",args.getString("waiting_email")!!,"12345678","","login") }
            ui.waitUntil(10000) { vm.waiting.value }
            assertNull(vm.account.value)
            assertTrue(vm.message.value.contains("电脑尚未连接，请等候"))
            ui.waitUntil(30000) { !vm.busy.value && !vm.uploading.value }
            assertNotNull(vm.message.value,vm.account.value)
            name = vm.databaseName(vm.account.value!!)
            ui.runOnIdle { vm.logout() }
            ui.waitUntil(30000) { !vm.busy.value && !vm.uploading.value }
            ui.runOnIdle { vm.signIn("http://127.0.0.1:8768",args.getString("waiting_email")!!,"12345678","","login") }
            ui.waitUntil(30000) { vm.waiting.value }
            ui.runOnIdle { vm.cancelWaiting() }
            ui.waitUntil(10000) { !vm.busy.value && !vm.uploading.value }
            assertNull(vm.account.value)
        } finally {
            holder.clear(); name?.let { app.deleteDatabase(it) }; AccountVault(app,namespace).clear()
        }
    }

}
