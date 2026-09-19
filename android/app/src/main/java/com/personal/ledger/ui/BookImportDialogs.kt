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
import com.personal.ledger.domain.Rules
import com.personal.ledger.data.LedgerRepository
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun BookImportDialogs(vm: BackupViewModel, choose: Boolean, closeChooser: () -> Unit) {
    val preview by vm.bookImport.collectAsStateWithLifecycle()
    val selected by vm.importSelection.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    if(choose) AlertDialog(onDismissRequest=closeChooser,title={Text("选择来源账本")},text={
        LazyColumn(Modifier.heightIn(max=400.dp)) {
            items(vm.importSources()) { book ->
                TextButton(onClick={closeChooser();vm.previewBookImport(book)},enabled=!busy,
                    modifier=Modifier.testTag("import-source-${book.getString("database")}")) {Text(book.optString("label","手机账本"))}
            }
        }
    },confirmButton={TextButton(onClick=closeChooser) {Text("取消")}})
    preview?.let { p ->
        val duplicates = selected.count {it in p.duplicateIds}
        AlertDialog(onDismissRequest={if(!busy) vm.dismissImport()},title={Text("预览并选择账目")},text={
            Column {
                Text("来源：${p.label}。追加到当前登录账号，源账本保留。")
                Text("已选${selected.size}笔，其中重复${duplicates}笔。回收站保留原删除时间。")
                Row {
                    TextButton(onClick={vm.selectAllImports(true)},enabled=!busy,modifier=Modifier.testTag("import-select-all")) {Text("全选")}
                    TextButton(onClick={vm.selectAllImports(false)},enabled=!busy) {Text("全不选")}
                }
                LazyColumn(Modifier.heightIn(max=340.dp)) {
                    items(p.entries,key={it.id}) { e ->
                        Row(Modifier.fillMaxWidth()) {
                            Checkbox(checked=e.id in selected,onCheckedChange={vm.toggleImport(e.id)},enabled=!busy,modifier=Modifier.testTag("import-entry-${e.id}"))
                            Column(Modifier.weight(1f).padding(vertical=8.dp)) {
                                Text("${e.date} · ${if(e.type=="income") "收入" else "支出"} ¥${Rules.money(e.amountFen)}")
                                Text(p.categories[e.categoryId]?.name.orEmpty())
                                if(e.note.isNotEmpty()) Text(e.note)
                                if(e.id in p.duplicateIds) Text("重复账目")
                                e.deletedAt?.let { time -> Text("回收站 · 到期${Instant.ofEpochMilli(time+LedgerRepository.RETENTION_MS).atZone(Rules.zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}") }
                            }
                        }
                    }
                }
            }
        },confirmButton={Column {
            TextButton(onClick={vm.confirmBookImport(false)},enabled=!busy && selected.isNotEmpty(),modifier=Modifier.testTag("confirm-backup-action")) {Text(if(duplicates>0) "跳过重复并导入" else "确认导入")}
            if(duplicates>0) TextButton(onClick={vm.confirmBookImport(true)},enabled=!busy,modifier=Modifier.testTag("confirm-duplicate-import")) {Text("仍然导入重复项")}
        }},dismissButton={TextButton(onClick=vm::dismissImport,enabled=!busy) {Text("取消")}})
    }
}
