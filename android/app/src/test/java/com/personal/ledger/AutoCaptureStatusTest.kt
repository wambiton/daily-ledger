package com.personal.ledger

import com.personal.ledger.ui.captureStatusText
import org.junit.Assert.*
import org.junit.Test

class AutoCaptureStatusTest {
    @Test fun disabledCaptureNeverClaimsToBeRunningEvenWithLiveListener() {
        val state=captureStatusText(false,true,true,true)
        assertEquals("自动记账未开启",state.title)
        assertFalse(state.needsAttention)
    }
    @Test fun missingPermissionOrTargetTakesPriorityOverStaleConnection() {
        assertEquals("自动记账 · 通知权限未开启",captureStatusText(true,false,true,true).title)
        assertEquals("自动记账 · 请指定账本",captureStatusText(true,true,false,true).title)
        assertTrue(captureStatusText(true,false,true,true).needsAttention)
    }
    @Test fun disconnectedListenerRequiresAttentionAndConnectedDoesNotPromiseRecognition() {
        assertTrue(captureStatusText(true,true,true,false).needsAttention)
        val state=captureStatusText(true,true,true,true)
        assertEquals("自动记账 · 通知已连接",state.title)
        assertFalse(state.needsAttention)
    }
}
