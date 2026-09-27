package com.personal.ledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.ledger.domain.Rules
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun BackupStatusCard(vm: LedgerViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle()
    val changes by vm.pending.collectAsStateWithLifecycle()
    val pending by vm.pendingEntries.collectAsStateWithLifecycle()
    val state by vm.backupState.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth().testTag("local-backup-summary"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("当前账本 · 已保存在手机",style=MaterialTheme.typography.titleMedium)
            Text("正常账目${entries.size}笔 · 回收站${trash.size}笔")
            Text(when {
                pending > 0 -> "${pending}笔账目变更待备份"
                changes > 0 -> "分类或预算设置变更待备份"
                state?.lastSuccessAt == null -> "尚无电脑备份成功记录"
                else -> "当前手机账本没有待上传修改"
            },modifier=Modifier.testTag("pending-backup-summary"))
            state?.lastSuccessAt?.let { time ->
                Text("最近与电脑确认：${Instant.ofEpochMilli(time).atZone(Rules.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))} · 第${state?.cursor}版")
            }
            Text("以电脑确认成功为准",style=MaterialTheme.typography.bodySmall)
        }
    }
}
