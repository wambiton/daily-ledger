package com.personal.ledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.personal.ledger.data.PaymentCandidate
import com.personal.ledger.domain.Rules
import java.time.Instant
import java.time.format.DateTimeFormatter

internal fun captureRecordResult(status: String?): String = when(status) {
    "booked" -> "已入账记录"
    "merged" -> "已合并来源，只计一笔"
    "pending" -> "待确认，尚未计入统计"
    "ignored" -> "已忽略，不计入统计"
    else -> "尚无处理记录，不能判断是否漏记"
}

internal fun captureTime(time: Long): String = if(time<=0) "尚无记录" else
    Instant.ofEpochMilli(time).atZone(Rules.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

/** Reception and parser timestamps are independent of persisted accounting outcomes. */
@Composable internal fun CaptureSourceDiagnostics(source: String,seen: Long,recognized: Long,reason: String?,
    latest: PaymentCandidate?,open: (PaymentCandidate)->Unit) {
    Card(Modifier.fillMaxWidth().testTag("capture-source-$source")) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(source,style=MaterialTheme.typography.titleSmall)
            Text("最近接收：${captureTime(seen)}",style=MaterialTheme.typography.bodySmall)
            Text("最近识别支付信息：${captureTime(recognized)}",style=MaterialTheme.typography.bodySmall)
            Text(reason ?: "尚无接收诊断；没有记录不代表没有消费。",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("最近记录结果：${captureRecordResult(latest?.status)}",modifier=Modifier.testTag("capture-result-$source"))
            if(latest!=null) {
                Text("该记录建立于 ${captureTime(latest.receivedAt)} · 目标：${latest.bookLabel}",style=MaterialTheme.typography.bodySmall)
                Text(latest.reason,style=MaterialTheme.typography.bodySmall)
                if(latest.status!="ignored") TextButton(onClick={open(latest)}) {Text(if(latest.status=="pending") "处理这笔待确认" else "查看账目与来源")}
            }
            if(recognized>0) Text("识别时间不等于入账时间；新消息与下方记录可能不是同一笔。入账记录反映处理结果，之后仍可手动修改或删除账目。",style=MaterialTheme.typography.bodySmall)
        }
    }
}
