package com.personal.ledger

import android.app.Application
import android.content.Intent
import android.util.Log
import android.app.NotificationManager
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.ledger.data.*
import com.personal.ledger.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.time.LocalDate
import java.util.UUID

class ExternalBackupFlowTest {
    @Before fun cleanInterruptedSyntheticJobs() {
        Log.i("LedgerExternalTest","Cleaning interrupted synthetic jobs")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val files = java.io.File(app.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty()
        files.filter { it.name.startsWith("backup-job-external-test-") && it.name.endsWith(".xml") }.forEach { file ->
            val namespace = file.name.removePrefix("backup-job-").removeSuffix(".xml")
            BackgroundBackup.cancel(app,namespace)
            WorkManager.getInstance(app).cancelUniqueWork(BackgroundBackup.name(namespace)).result.get(20,TimeUnit.SECONDS)
            runBlocking { BackgroundBackup.lock.withLock {
                AccountVault(app,namespace).read()?.let { account ->
                    val name = ComputerPair.databaseName(account)
                    app.deleteDatabase(name)
                    java.io.File(app.noBackupFilesDir,"$name.pending.json").delete()
                }
                listOf(namespace,"$namespace-pair","$namespace-carry").forEach { AccountVault(app,it).clear() }
            } }
        }
        Log.i("LedgerExternalTest","Synthetic cleanup finished")
    }
    private fun until(timeout: Long = 120000, condition: () -> Boolean) {
        val end = System.currentTimeMillis()+timeout
        while(!condition() && System.currentTimeMillis()<end) Thread.sleep(100)
        assertTrue("等待外网测试状态超时",condition())
    }
    @Test fun cellularPairRetryAddressChangeNewPhoneAndNewComputer() {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Requires isolated HTTPS fixtures",args.containsKey("external_pair"))
        fun qr(key: String) = String(Base64.decode(args.getString(key),Base64.NO_WRAP),Charsets.UTF_8)
        fun phase(text: String) { Log.i("LedgerExternalTest",text) }
        val firstQr = qr("external_pair")
        val updatedQr = qr("external_pair_updated")
        val nextQr = qr("external_pair_next")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val namespace = "external-test-${UUID.randomUUID()}"
        val holder = ViewModelStore()
        val names = mutableSetOf<String>()
        var vm = BackupViewModel(app,namespace)
        holder.put("backup",vm)
        val activity = ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        try {
            phase("Starting HTTPS pairing")
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.pairComputer(firstQr) }
            until { !vm.busy.value }
            assertEquals(JSONObject(firstQr).getString("server"),vm.server.value)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.signIn(vm.server.value,args.getString("external_email")!!,"12345678","","login") }
            until { !vm.busy.value }
            assertNotNull(vm.message.value,vm.account.value)
            phase("Paired and logged in over HTTPS")
            val account = vm.account.value!!
            val name = vm.databaseName(account);names.add(name)
            val owner = account.getJSONObject("user").getString("id")
            val db = LedgerDatabases.acquire(app,name)
            try { runBlocking {
                val repo = LedgerRepository(db,owner);repo.seedDefaults()
                val cat = db.ledger().categorySnapshot(owner).first()
                repo.save(null,cat.type,cat.id,"23.45",LocalDate.of(2025,2,3),"synthetic-cellular")
            } } finally { LedgerDatabases.release(name) }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.upload() }
            until { BackgroundBackup.prefs(app,namespace).getString("message","")!!.contains("后台重试") }
            phase("Retry detected; leaving app")
            assertTrue(BackgroundBackup.active(app,namespace))
            val manager = app.getSystemService(NotificationManager::class.java)
            assertTrue(manager.activeNotifications.any { it.notification.extras.getCharSequence("android.title").toString().contains("电脑备份") })
            // Destroy the controller and leave the app; WorkManager must finish without it.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { holder.clear() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 3").close()
            until { !BackgroundBackup.active(app,namespace) }
            assertTrue(BackgroundBackup.prefs(app,namespace).getString("message","")!!.startsWith("备份成功"))
            phase("Background upload succeeded")
            // Scanning/logging in requires returning to the app, as a user would.
            app.startActivity(Intent().setClassName(app.packageName,"androidx.activity.ComponentActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            vm = BackupViewModel(app,namespace);holder.put("backup",vm)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.pairComputer(updatedQr) }
            until { !vm.busy.value }
            assertEquals(name,vm.databaseName(vm.account.value!!))
            assertEquals(JSONObject(updatedQr).getString("server"),vm.account.value!!.getString("server"))
            // Cancel a queued upload through the same action used by the notification.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.upload(); vm.cancelUpload() }
            until { !BackgroundBackup.active(app,namespace) }
            assertFalse(BackgroundBackup.active(app,namespace))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.logout() };until { !vm.busy.value }
            phase("Address refreshed; simulating new phone")
            java.io.File(app.noBackupFilesDir,"$name.pending.json").delete()
            app.deleteDatabase(name)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.signIn(vm.server.value,args.getString("external_email")!!,"12345678","","login") }
            until { !vm.busy.value }
            assertNotNull(vm.message.value,vm.account.value)
            val restored = LedgerDatabases.acquire(app,name)
            try { runBlocking { assertEquals(2345L,restored.ledger().entrySnapshot(owner).single().amountFen) } }
            finally { LedgerDatabases.release(name) }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.pairComputer(nextQr) };until { !vm.busy.value }
            assertNull(vm.account.value)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.signIn(vm.server.value,args.getString("external_email")!!,"12345678",args.getString("external_code")!!,"register") }
            until { !vm.busy.value }
            assertNotNull(vm.message.value,vm.account.value)
            phase("New computer registration carried current entries")
            val newName = vm.databaseName(vm.account.value!!);names.add(newName)
            assertNotEquals(name,newName)
            val second = LedgerDatabases.acquire(app,newName)
            try { runBlocking { assertEquals(2345L,second.ledger().entrySnapshot(vm.account.value!!.getJSONObject("user").getString("id")).single().amountFen) } }
            finally { LedgerDatabases.release(newName) }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.upload() };until { !vm.uploading.value }
            assertTrue(vm.message.value,vm.message.value.startsWith("备份成功"))
        } finally {
            app.startActivity(Intent().setClassName(app.packageName,"androidx.activity.ComponentActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            BackgroundBackup.cancel(app,namespace)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { holder.clear() }
            names.forEach { app.deleteDatabase(it) }
            listOf(namespace,"$namespace-pair","$namespace-carry").forEach { AccountVault(app,it).clear() }
            activity.close()
        }
    }
}
