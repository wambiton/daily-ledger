package com.personal.ledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BackupHistoryDialog(vm: BackupViewModel) {
    val open by vm.historyOpen.collectAsStateWithLifecycle()
    val rows by vm.historyRows.collectAsStateWithLifecycle()
    val next by vm.historyNext.collectAsStateWithLifecycle()
    val loaded by vm.historyLoaded.collectAsStateWithLifecycle()
    val preview by vm.historyPreview.collectAsStateWithLifecycle()
    val recovered by vm.recoveredBook.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    if(!open) return
    AlertDialog(onDismissRequest = { vm.closeHistory() }, title = { Text("电脑备份历史") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("history-list"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("仅查看当前登录账号在这台电脑的备份。恢复会新建独立手机账本，保留当前账本，不自动上传。") }
                if(busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if(message.isNotEmpty()) item { Text(message) }
                when {
                    recovered != null -> item {
                        Text("已创建：${recovered!!.getString("label")}")
                        TextButton(onClick = { val book = recovered!!; vm.closeHistory(); vm.selectBook(book) },
                            enabled = !busy, modifier = Modifier.testTag("open-recovered-book")) { Text("查看恢复的账本") }
                    }
                    preview != null -> item {
                        val p = preview!!
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("恢复预览 · 第${p.revision}版", style = MaterialTheme.typography.titleMedium)
                            Text(p.createdAt)
                            Text("正常账目 ${p.summary.normal} 笔 · 回收站 ${p.summary.trash} 笔\n预算计划 ${p.summary.budgets} 个")
                            Text(if(p.summary.dateFrom == null) "没有正常账目" else "账目日期：${p.summary.dateFrom} 至 ${p.summary.dateTo}")
                            Text("超过30天的回收站账目 ${p.summary.expired} 笔不会恢复；回收站保留原删除时间，可能在预览后到期。")
                            Text("当前账目不会被新增、合并或替换。恢复的账本没有账号登录信息，自动记账目标也不会改变。")
                            Button(onClick = { vm.recoverHistory() }, enabled = !busy,
                                modifier = Modifier.testTag("confirm-history-recovery")) { Text("恢复为独立账本") }
                            TextButton(onClick = { vm.dismissHistoryPreview() }, enabled = !busy) { Text("返回历史列表") }
                        }
                    }
                    else -> {
                        if(loaded && rows.isEmpty()) item { Text("这个账号尚无电脑备份") }
                        items(rows, key = { it.getLong("revision") }) { row ->
                            OutlinedButton(onClick = { vm.previewHistory(row.getLong("revision")) }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth().testTag("history-revision-${row.getLong("revision")}")) {
                                Column {
                                    Text("第${row.getLong("revision")}版 · ${row.getInt("entry_count")} 笔")
                                    Text(runCatching { java.time.OffsetDateTime.parse(row.getString("created_at"))
                                        .atZoneSameInstant(com.personal.ledger.domain.Rules.zone)
                                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                    }.getOrDefault(row.getString("created_at")))
                                    Text("回收站 ${row.optInt("trash_count")} 笔 · 预算 ${row.optInt("budget_count")} 个")
                                    if(!row.isNull("date_from")) Text("${row.getString("date_from")} 至 ${row.getString("date_to")}")
                                    Text("查看恢复预览")
                                }
                            }
                        }
                        if(next != null || !loaded) item {
                            TextButton(onClick = { vm.loadHistory() }, enabled = !busy) { Text(if(loaded) "加载更早版本" else "重试加载") }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { vm.closeHistory() }, enabled = !busy) { Text("关闭") } })
}
