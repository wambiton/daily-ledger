package com.personal.ledger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.personal.ledger.data.*
import kotlinx.coroutines.delay

internal data class CaptureStatusText(val title: String, val needsAttention: Boolean)

internal fun captureStatusText(enabled: Boolean, granted: Boolean, hasTarget: Boolean, connected: Boolean): CaptureStatusText = when {
    !enabled -> CaptureStatusText("自动记账未开启", false)
    !granted -> CaptureStatusText("自动记账 · 通知权限未开启", true)
    !hasTarget -> CaptureStatusText("自动记账 · 请指定账本", true)
    !connected -> CaptureStatusText("自动记账 · 通知未连接", true)
    else -> CaptureStatusText("自动记账 · 通知已连接", false)
}

/** Read-only overview: looking at a ledger must never retarget capture or enable it. */
@Composable fun AutoCaptureStatus(onOpen: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs = remember(context) { AutoCapture.prefs(context) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { tick++; delay(2000) }
        }
    }
    val connected by AutoCaptureService.connected.collectAsStateWithLifecycle()
    val countFlow = remember(context) { AutoEntryDatabase.get(context).auto().pendingCount() }
    val pending by countFlow.collectAsStateWithLifecycle(initialValue = -1)
    val status = remember(tick, connected) {
        val enabled=prefs.getBoolean("enabled", false)
        val target=AutoCapture.target(context)!=null
        when {
            enabled && target && prefs.getBoolean("sms_enabled",false) && !BankSmsReceiver.granted(context) -> CaptureStatusText("自动记账 · 短信权限未开启",true)
            enabled && target && BankSmsReceiver.enabled(context) && !connected -> CaptureStatusText("银行短信已启用 · 支付通知未连接",true)
            else -> captureStatusText(enabled, AutoCaptureRecovery.granted(context),target,connected)
        }
    }
    AutoCaptureStatusRow(status, pending, onOpen)
}

@Composable internal fun AutoCaptureStatusRow(status: CaptureStatusText, pending: Int, onOpen: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(bottom=8.dp).testTag("home-auto-status").clickable(onClick=onOpen),
        shape=MaterialTheme.shapes.medium,
        color=if(status.needsAttention) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        contentColor=if(status.needsAttention) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.padding(horizontal=12.dp,vertical=8.dp), verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(status.title, style=MaterialTheme.typography.labelLarge)
                Text(if(pending<0) "正在读取待确认记录…" else if(pending>0) "全手机待确认 $pending 笔 · 点击处理" else "暂无待确认 · 点击查看设置",
                    style=MaterialTheme.typography.labelSmall)
            }
            Text("›", Modifier.padding(start=8.dp))
        }
    }
}
