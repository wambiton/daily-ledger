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
            ui.waitUntil(30000) { !vm.busy.value }
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
            ui.waitUntil(30000) { !vm.busy.value }
            assertTrue(vm.message.value,vm.message.value.startsWith("备份成功"))
            // Only this newly created fixture account is cleared to simulate a new phone.
            runBlocking { db.ledger().clearEntries(owner) }
            ui.onNodeWithTag("restore-backup").performScrollTo().performClick()
            ui.onNodeWithTag("confirm-backup-action").performClick()
            ui.waitUntil(30000) { !vm.busy.value }
            assertTrue(vm.message.value,vm.message.value.startsWith("恢复成功"))
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
            ui.waitUntil(30000) { !vm.busy.value }
            assertNull(vm.account.value)
            runBlocking { assertEquals(1,db.ledger().entrySnapshot(owner).size) }
        } finally {
            holder.clear(); db?.close()
            name?.let { app.deleteDatabase(it) }
            AccountVault(app,namespace).clear()
        }
    }
}
