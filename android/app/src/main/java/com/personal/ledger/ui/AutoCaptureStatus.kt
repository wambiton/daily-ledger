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

internal data class CaptureStatusText(val title: String, val needsAttention: Boolean, val guidance: String = "")

internal fun captureStatusText(enabled: Boolean, granted: Boolean, hasTarget: Boolean, connected: Boolean,
    smsEnabled: Boolean = false, smsGranted: Boolean = false): CaptureStatusText = when {
    !enabled -> CaptureStatusText("自动记账未开启", false,"开启总开关后才会处理新消息；旧的待确认记录仍可处理。")
    !hasTarget -> CaptureStatusText("自动记账 · 请指定账本", true,"先选择自动记账账本，查看其他账本不会改变此目标。")
    smsEnabled && !smsGranted -> CaptureStatusText("自动记账 · 短信权限未开启",true,"银行短信识别需要短信权限；微信、支付宝仍取决于通知连接状态。")
    smsEnabled && smsGranted && (!granted || !connected) -> CaptureStatusText("银行短信已启用 · 支付通知未连接",true,"银行短信可接收；请在连接与后台设置中检查微信、支付宝通知权限和连接。")
    !granted -> CaptureStatusText("自动记账 · 通知权限未开启", true,"在连接与后台设置中打开通知使用权。")
    !connected -> CaptureStatusText("自动记账 · 通知未连接", true,"请检查后台运行设置并尝试重新连接；断开期间的消息不保证补回。")
    else -> CaptureStatusText("自动记账 · 通知已连接", false,"正在等待系统发出新消息。连接正常不代表每笔付款都能识别。")
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
        captureStatusText(enabled,AutoCaptureRecovery.granted(context),target,connected,
            prefs.getBoolean("sms_enabled",false),BankSmsReceiver.granted(context))
    }
    AutoCaptureStatusRow(status, pending, onOpen)
}

@Composable internal fun AutoCaptureStatusRow(status: CaptureStatusText, pending: Int, onOpen: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(bottom=8.dp).testTag("home-auto-status").clickable(onClick=onOpen),
        shape=MaterialTheme.shapes.medium,
        color=if(status.needsAttention || pending>0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        contentColor=if(status.needsAttention || pending>0) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface) {
        Row(Modifier.padding(horizontal=12.dp,vertical=8.dp), verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(status.title, style=MaterialTheme.typography.labelLarge)
                Text(if(pending<0) "正在读取待确认记录…" else if(pending>0) "全手机待确认 $pending 笔 · 点击处理" else "暂无待确认 · 点击排查漏记",
                    style=MaterialTheme.typography.labelSmall)
            }
            Text("›", Modifier.padding(start=8.dp))
        }
    }
}
