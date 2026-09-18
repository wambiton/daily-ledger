package com.personal.ledger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.ledger.domain.Rules

@Composable
fun FileToolsScreen(vm: LedgerViewModel, busy: Boolean) {
    var range by rememberSaveable { mutableStateOf(false) }
    var from by rememberSaveable { mutableStateOf(Rules.today().withDayOfMonth(1).toString()) }
    var to by rememberSaveable { mutableStateOf(Rules.today().toString()) }
    val message by vm.fileMessage.collectAsStateWithLifecycle()
    val preview by vm.importPreview.collectAsStateWithLifecycle()
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"),vm::writeExport)
    val excel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),vm::writeExport)
    val input = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(),vm::readImport)
    Text("导出和导入均针对当前打开的账本。Excel用于查看，可恢复备份用于导入。")
    FilterChip(selected=range,onClick={range=!range},enabled=!busy,label={Text(if(range) "指定日期范围" else "全部日期")})
    if(range) {
        OutlinedTextField(from,{from=it},label={Text("开始日期（如2026-09-01）")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(to,{to=it},label={Text("结束日期（含当天）")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
    }
    OutlinedButton(onClick={vm.prepareExport(true,if(range) from else null,if(range) to else null) {excel.launch("账目-${Rules.today()}.xlsx")}},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("导出Excel（正常账目）")}
    OutlinedButton(onClick={vm.prepareExport(false,if(range) from else null,if(range) to else null) {json.launch("账目备份-${Rules.today()}.json")}},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("导出可恢复备份（含回收站）")}
    Text("回收站保留原删除时间，30天到期不会因导入而延长。文件中包含账目，请保存在自己信任的位置。")
    Button(onClick={input.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("从备份文件追加导入")}
    message?.let {Text(it)}
    preview?.let { p ->
        AlertDialog(onDismissRequest={if(!busy) vm.cancelImport()},title={Text("确认追加导入")},
            text={Text("正常账目：${p.normal}笔\n回收站：${p.trash}笔\n其中重复：${p.duplicates}笔\n过期回收站：${p.expired}笔（自动跳过）\n\n保留现有账目；继续导入重复项会新增独立账目，正常账目都计入统计。")},
            confirmButton={Column {
                TextButton(onClick={vm.confirmImport(false)},enabled=!busy) {Text(if(p.duplicates>0) "跳过重复并导入" else "确认导入")}
                if(p.duplicates>0) TextButton(onClick={vm.confirmImport(true)},enabled=!busy) {Text("仍然导入重复项")}
            }},dismissButton={TextButton(onClick=vm::cancelImport,enabled=!busy) {Text("取消")}})
    }
}
