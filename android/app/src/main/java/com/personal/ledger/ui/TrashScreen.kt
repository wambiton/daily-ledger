package com.personal.ledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.ledger.data.Category
import com.personal.ledger.data.LedgerRepository
import com.personal.ledger.domain.Rules
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun TrashScreen(vm: LedgerViewModel, categories: Map<String, Category>, busy: Boolean) {
    val trash by vm.trash.collectAsStateWithLifecycle()
    Text("删除的账目保留30天，不计入收支统计。恢复后重新计入统计。设备关闭期间的到期账目会在下次运行时清理。")
    if (trash.isEmpty()) Text("回收站为空")
    trash.forEach { entry ->
        val expiry = entry.deletedAt!! + LedgerRepository.RETENTION_MS
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${entry.date} · ${categories[entry.categoryId]?.name.orEmpty()} · ¥${Rules.money(entry.amountFen)}")
                if(entry.note.isNotBlank()) Text(entry.note)
                Text("到期：${Instant.ofEpochMilli(expiry).atZone(Rules.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
                OutlinedButton(onClick = { vm.restoreDeleted(entry.id) }, enabled = !busy && expiry > System.currentTimeMillis()) { Text("恢复账目") }
            }
        }
    }
}
