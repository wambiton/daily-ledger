package com.personal.ledger

import com.personal.ledger.data.BackupCompatibility
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class BackupCompatibilityTest {
    @Test fun legacyHealthFailsWithActionableUpgradeMessage() {
        val error=runCatching {BackupCompatibility.requireSupported(JSONObject("""{"status":"ok","database":true}"""))}.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("本次备份未成功"))
        assertTrue(error.message!!.contains("退出旧电脑程序"))
    }
    @Test fun supportedFormatsPermitCurrentBackup() {
        BackupCompatibility.requireSupported(JSONObject("""{"backup_formats":[1,2]}"""))
        assertTrue(runCatching {BackupCompatibility.requireSupported(JSONObject("""{"backup_formats":[1]}"""))}.isFailure)
    }
}
