package com.personal.ledger

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.personal.ledger.data.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AutoCaptureRecoveryTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() {
        AutoCaptureService.connected.value=false
        AutoCapture.prefs(context).edit().putBoolean("enabled",true)
            .putString("database","synthetic.db").putString("owner","fixture")
            .putLong("started_at",1234).commit()
        Settings.Secure.putString(context.contentResolver,"enabled_notification_listeners",
            ComponentName(context,AutoCaptureService::class.java).flattenToString())
    }
    @Test fun reconnectDoesNotClaimConnectionOrChangeCaptureTarget() {
        val before=AutoCapture.prefs(context).all
        var calls=0
        assertTrue(AutoCaptureRecovery.request(context,"test",0){calls++})
        assertEquals(1,calls)
        assertFalse(AutoCaptureService.connected.value)
        for(key in listOf("enabled","database","owner","started_at")) {
            assertEquals(before[key],AutoCapture.prefs(context).all[key])
        }
        AutoCaptureService.connected.value=true
        assertFalse(AutoCaptureRecovery.request(context,"already connected",20000){calls++})
        assertEquals(1,calls)
    }
    @Test fun disabledRevokedOrMissingTargetNeverRebinds() {
        var calls=0
        AutoCapture.prefs(context).edit().putBoolean("enabled",false).commit()
        assertFalse(AutoCaptureRecovery.request(context,"disabled",0){calls++})
        AutoCapture.prefs(context).edit().putBoolean("enabled",true).commit()
        Settings.Secure.putString(context.contentResolver,"enabled_notification_listeners","")
        assertFalse(AutoCaptureRecovery.request(context,"revoked",20000){calls++})
        Settings.Secure.putString(context.contentResolver,"enabled_notification_listeners",
            ComponentName(context,AutoCaptureService::class.java).flattenToString())
        AutoCapture.prefs(context).edit().remove("database").commit()
        assertFalse(AutoCaptureRecovery.request(context,"missing target",40000){calls++})
        assertEquals(0,calls)
    }
    @Test fun repeatedDisconnectsAreThrottledButLaterRecoveryCanRun() {
        var calls=0
        assertTrue(AutoCaptureRecovery.request(context,"first",0){calls++})
        assertFalse(AutoCaptureRecovery.request(context,"duplicate",9999){calls++})
        assertTrue(AutoCaptureRecovery.request(context,"later",10000){calls++})
        assertEquals(2,calls)
    }
    @Test fun systemRejectionIsReportedAndLaterRetryClearsIt() {
        assertFalse(AutoCaptureRecovery.request(context,"rejected",0){throw SecurityException()})
        assertFalse(AutoCaptureService.connected.value)
        assertNotNull(AutoCapture.prefs(context).getString("connection_error",null))
        assertTrue(AutoCaptureRecovery.request(context,"retry",10000){})
        assertNull(AutoCapture.prefs(context).getString("connection_error",null))
    }
}
