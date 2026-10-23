package com.personal.ledger.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.ledger.data.*
import com.personal.ledger.domain.Rules
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable fun AutoEntryScreen(ledger: LedgerViewModel, vm: AutoEntryViewModel=viewModel()) {
    val context=LocalContext.current
    val prefs=remember {AutoCapture.prefs(context)}
    var tick by remember {mutableIntStateOf(0)}
    var choose by remember {mutableStateOf(false)}
    var settingsExpanded by rememberSaveable {mutableStateOf(false)}
    var diagnosticsExpanded by rememberSaveable {mutableStateOf(false)}
    var history by rememberSaveable {mutableStateOf(false)}
    val rows by vm.rows.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val review by vm.review.collectAsStateWithLifecycle()
    val connected by AutoCaptureService.connected.collectAsStateWithLifecycle()
    val granted=remember(tick){AutoCaptureRecovery.granted(context)}
    val enabled=remember(tick){prefs.getBoolean("enabled",false)}
    val smsGranted=remember(tick){BankSmsReceiver.granted(context)}
    val smsEnabled=remember(tick){prefs.getBoolean("sms_enabled",false)}
    val smsPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){allowed ->
        if(allowed) prefs.edit().putBoolean("sms_enabled",true).putLong("sms_started_at",System.currentTimeMillis()).apply()
        tick++
    }
    val target=remember(tick){AutoCapture.target(context)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){tick++}
    LaunchedEffect(Unit){while(true){tick++;delay(1500)}}
    LaunchedEffect(granted,enabled){AutoCaptureRecovery.sync(context,"自动记账设置")}
    if(review!=null) {
        key(review!!.candidate.id) {AutoEntryReview(review!!,busy,error,{vm.review.value=null},{type,category,amount,date,note,remember,replace->vm.save(type,category,amount,date,note,remember,replace)},{vm.ignore(review!!.candidate.id)},{vm.split(it)})}
        return
    }
    fun whenText(time: Long)=if(time<=0) "尚无" else Instant.ofEpochMilli(time).atZone(Rules.zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
    LazyColumn(Modifier.fillMaxSize().testTag("auto-list"),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {
            Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    val health=captureStatusText(enabled,granted,target!=null,connected,smsEnabled,smsGranted)
                    Text(health.title,style=MaterialTheme.typography.titleMedium)
                    Text(health.guidance,style=MaterialTheme.typography.bodySmall)
                    Text("固定目标：${target?.third ?: "尚未指定账本"}",modifier=Modifier.testTag("auto-target"))
                    Text("全手机待确认 ${rows.count{it.status=="pending"}} 笔 · 待确认不计入统计",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={diagnosticsExpanded=!diagnosticsExpanded},modifier=Modifier.testTag("capture-diagnostics-toggle")) {
                        Text(if(diagnosticsExpanded) "收起漏记排查" else "查看接收与入账情况")
                    }
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("自动记账")
                Switch(checked=enabled,onCheckedChange={value ->
                    prefs.edit().putBoolean("enabled",value).putLong("started_at",System.currentTimeMillis()).apply()
                    AutoCaptureRecovery.sync(context,if(value) "开启自动记账" else "关闭自动记账");tick++
                },enabled=(!busy && (enabled || (granted || smsEnabled&&smsGranted)&&target!=null)),modifier=Modifier.testTag("auto-enabled"))
            }
                    TextButton(onClick={choose=true},enabled=!busy) {Text("选择自动记账账本")}
                }
            }
        }
        if(diagnosticsExpanded) {
            item {Text("漏记排查 · 本手机所有自动记账账本",style=MaterialTheme.typography.titleMedium)
                Text("先看是否收到，再看是否识别，最后查看记录结果。支付宝应用内账单不等于系统通知。",style=MaterialTheme.typography.bodySmall)}
            items(listOf("微信","支付宝","银行短信")) {source ->
                val key=if(source=="银行短信") "sms" else source
                val latest=rows.filter {if(key=="sms") it.source=="sms" else it.source=="notification"&&it.platform==source}.maxByOrNull {it.receivedAt}
                CaptureSourceDiagnostics(source,prefs.getLong("seen_$key",0),prefs.getLong("recognized_$key",0),
                    prefs.getString("reason_$key",null),latest,{vm.open(it)})
            }
            item {Text("未收到：检查来源应用通知、短信权限与后台限制。收到但未识别：可能为普通消息、隐藏金额或未适配模板。识别后没有对应记录：稍后刷新并检查处理错误；请先核对账本再手动补记。",style=MaterialTheme.typography.bodySmall)}
        }
        item {
            Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("银行短信自动记账",style=MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(if(smsEnabled && smsGranted) "短信识别已启用" else if(smsEnabled) "短信权限未授权" else "短信识别未开启")
                    Switch(checked=smsEnabled,onCheckedChange={value ->
                        if(!value) prefs.edit().putBoolean("sms_enabled",false).apply()
                        else if(smsGranted) prefs.edit().putBoolean("sms_enabled",true).putLong("sms_started_at",System.currentTimeMillis()).apply()
                        else smsPermission.launch(Manifest.permission.RECEIVE_SMS)
                        tick++
                    },enabled=!busy,modifier=Modifier.testTag("sms-enabled"))
                }
                if(smsEnabled && !smsGranted) TextButton(onClick={smsPermission.launch(Manifest.permission.RECEIVE_SMS)}) {Text("允许接收新短信")}
                if(!enabled) Text("还需开启上方自动记账总开关并指定账本")
                Text("目前适配招商银行95555的新交易短信，不读取历史短信；验证码和普通短信不保存。系统未送达短信时无法识别。")
                Text("普通消费、存入和退款自动入账；未知分类使用其他支出/其他收入。充值、提现、明确本人互转不计收支。")
                Text("同一账本内，银行卡短信与微信/支付宝通知按渠道、金额、收支及5分钟时间窗口自动配对，仅计一笔；已处理记录中可查看来源和拆分。相近的不同交易仍可能误合并，请核对。")
                Text("最近识别银行短信：${whenText(prefs.getLong("recognized_sms",0))}",style=MaterialTheme.typography.bodySmall)
            }}
            TextButton(onClick={settingsExpanded=!settingsExpanded},modifier=Modifier.testTag("auto-settings-toggle")) {
                Text(if(settingsExpanded) "收起连接与后台设置" else "连接与后台设置  ›")
            }
            if(settingsExpanded) {
            Text("仅接收开启后的新通知，不读取历史账单。没有系统通知或信息隐藏时可能无法识别。")
            Text("查看其他账本不会改变目标；更换目标前收到的记录仍保留原账本。")
            Text(if(granted) if(connected) "通知读取：已连接" else "权限已开启，正在等待系统连接" else "通知读取：未授权")
            OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}) {Text("打开通知使用权设置")}
            if(granted&&enabled&&!connected) TextButton(onClick={AutoCaptureRecovery.sync(context,"手动重连")}) {Text("重新连接通知服务")}
            Text("普通消费仅沿用你确认并记住的商户分类；首次出现的商户待确认。退款使用收入分类“退款”。")
            Text("后台运行",style=MaterialTheme.typography.titleMedium)
            Text("开启后会在监听断开、手机启动及软件更新时尝试重连，并由系统定期检查。无需每次付款前打开软件；系统限制后台时仍可能延迟或漏收。")
            val power=context.getSystemService(android.os.PowerManager::class.java)
            Text(if(power.isIgnoringBatteryOptimizations(context.packageName)) "电池优化：已豁免" else "电池优化：未豁免，可在系统设置中调整")
            OutlinedButton(onClick={
                val intent=Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            }) {Text("打开应用后台设置")}
            OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}) {Text("打开电池优化设置")}
            Text("realme GT7 需分别设置：① 应用详情 → 耗电管理 → 完全允许后台行为；② 系统设置 → 应用 → 自启动 → 开启个人记账。电池优化已豁免不代表已允许自启动。其他机型以系统实际选项为准。",style=MaterialTheme.typography.bodySmall)
            Text("重启并解锁后可能需要等待系统连接。若划掉后仍断开，可在最近任务中锁定本应用。不要点“强行停止”，之后需手动打开一次。",style=MaterialTheme.typography.bodySmall)
            Text("最近连接：${whenText(prefs.getLong("connected_at",0))}；最近断开：${whenText(prefs.getLong("disconnected_at",0))}",style=MaterialTheme.typography.bodySmall)
            Text("最近重连请求：${whenText(prefs.getLong("reconnect_at",0))}；请求不代表已经连接。断开期间漏掉的通知无法保证补回。",style=MaterialTheme.typography.bodySmall)
            prefs.getString("connection_error",null)?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            OutlinedButton(onClick={
                if(Build.VERSION.SDK_INT>=33 && androidx.core.content.ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
            }) {Text("设置记账结果通知")}
            Text("系统权限会向软件提供通知；软件只筛选微信、支付宝支付信息，不保存普通聊天原文。关闭开关后停止接收。",style=MaterialTheme.typography.bodySmall)
            listOf("微信","支付宝").forEach {platform ->
                Text("$platform：应用通知 ${whenText(prefs.getLong("seen_$platform",0))}；支付识别 ${whenText(prefs.getLong("recognized_$platform",0))}",style=MaterialTheme.typography.bodySmall)
                Text(prefs.getString("reason_$platform",null) ?: "尚无可判断的新通知；应用内账单不等于系统通知",style=MaterialTheme.typography.bodySmall)
            }
            Text("来源、合并关系、待确认和商户规则仅在本手机保存；正式入账后随原有账号备份。",style=MaterialTheme.typography.bodySmall)
            }
            prefs.getString("error",null)?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            Text("首次商户或信息不完整时需确认，待确认项不计入统计。",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=!history,onClick={history=false},label={Text("待确认（${rows.count{it.status=="pending"}}）")})
                FilterChip(selected=history,onClick={history=true},label={Text("已处理记录")})
            }
        }
        val visible=rows.filter {if(history) it.status!="pending" else it.status=="pending"}
        if(visible.isEmpty()) item {Text(if(history) "暂无已处理记录" else "暂无待确认记录；这不代表所有付款均已被识别")}
        items(visible,key={it.id}) {c ->
            Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("${c.platform} · ${c.date} · ${c.amountFen?.let{ "¥${Rules.money(it)}" } ?: "金额待确认"}")
                Text("目标：${c.bookLabel}")
                Text(c.reason)
                if(c.merchant.isNotEmpty()) Text(c.merchant)
                if(c.status!="ignored") OutlinedButton(onClick={vm.open(c)},enabled=!busy,modifier=Modifier.testTag("auto-review-${c.id}")) {Text(if(c.status=="booked" || c.status=="merged") "查看账目与来源" else "确认这笔账目")}
            }}
        }
    }
    if(choose) {
        val current=JSONObject().put("database",ledger.databaseName).put("owner",ledger.owner).put("label",if(ledger.owner=="local-preview") "原本机账本" else "当前查看的账号账本")
        val books=(runCatching {PhoneLedgers(context,"account").books()}.getOrDefault(emptyList())+current+
            JSONObject().put("database","ledger.db").put("owner","local-preview").put("label","原本机账本"))
            .distinctBy{it.getString("database") to it.getString("owner")}.filter{context.getDatabasePath(it.getString("database")).exists()}
        AlertDialog(onDismissRequest={choose=false},title={Text("指定自动记账账本")},text={LazyColumn(Modifier.heightIn(max=360.dp)) {
            items(books){book -> TextButton(onClick={
                prefs.edit().putString("database",book.getString("database")).putString("owner",book.getString("owner")).putString("label",book.getString("label")).apply();choose=false;tick++
            }){Text(book.getString("label"))}}
        }},confirmButton={TextButton(onClick={choose=false}){Text("取消")}})
    }
}

@Composable fun AutoEntryReview(review: AutoEntryViewModel.Review,busy: Boolean,error: String?,cancel: ()->Unit,
    save: (String,String,String,String,String,Boolean,String?)->Unit,ignore: ()->Unit,split: (String)->Unit = {}) {
    val c=review.candidate
    var splitId by remember {mutableStateOf<String?>(null)}
    var type by rememberSaveable{mutableStateOf(review.entry?.type ?: c.type ?: "expense")}
    var category by rememberSaveable{mutableStateOf(review.entry?.categoryId ?: if(c.refund) review.categories.firstOrNull{it.name=="退款"&&it.type=="income"}?.id.orEmpty() else "")}
    var amount by rememberSaveable{mutableStateOf((review.entry?.amountFen ?: c.amountFen)?.let{Rules.money(it)}.orEmpty())}
    var date by rememberSaveable{mutableStateOf(review.entry?.date ?: c.date)}
    var note by rememberSaveable{mutableStateOf(review.entry?.note ?: c.note)}
    var rememberMerchant by rememberSaveable{mutableStateOf(false)}
    var replace by rememberSaveable{mutableStateOf<String?>(null)}
    LazyColumn(Modifier.fillMaxSize().testTag("auto-review"),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {Text("${c.bookLabel} · ${if(c.status=="pending") "确认后才入账" else "修改已入账记录"}",style=MaterialTheme.typography.titleLarge);Text(c.reason)}
        if(review.sources.size>1) item {
            Text("识别来源（合并账目只计一笔）",style=MaterialTheme.typography.titleMedium)
            review.sources.forEach {source ->
                Text("${source.platform} · ${source.date} · ${source.amountFen?.let {Rules.money(it)} ?: "待确认"}")
                Text(source.note,style=MaterialTheme.typography.bodySmall)
                if(source.status=="merged") OutlinedButton(onClick={splitId=source.id},enabled=!busy,modifier=Modifier.testTag("split-source-${source.id}")) {Text("这不是同一笔，拆分此来源")}
            }
        }
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("expense" to "支出","income" to "收入").forEach{(value,label)->FilterChip(selected=type==value,onClick={type=value;category="";replace=null},enabled=!busy,label={Text(label)})}}}
        item {OutlinedTextField(amount,{amount=it;replace=null},label={Text("金额（元）")},enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("auto-amount"))}
        item {OutlinedTextField(date,{date=it;replace=null},label={Text("日期（YYYY-MM-DD）")},enabled=!busy,modifier=Modifier.fillMaxWidth())}
        item {Text("必须选择分类")}
        items(review.categories.filter{it.type==type},key={it.id}) {cat -> FilterChip(selected=category==cat.id,onClick={category=cat.id},enabled=!busy,label={Text(cat.name)},modifier=Modifier.testTag("auto-category-${cat.id}"))}
        item {OutlinedTextField(note,{note=it},label={Text("备注，可修改")},enabled=!busy,modifier=Modifier.fillMaxWidth())}
        if(c.status=="pending") item {
            if(c.merchant.isNotBlank()) Row {Checkbox(checked=rememberMerchant,onCheckedChange={rememberMerchant=it},enabled=!busy);Text("记住${c.platform}商户“${c.merchant}”的分类，下次信息明确时自动记账")}
            if(review.matches.isNotEmpty()) {
                Text("发现疑似重复。保留原账可选择不入账；确认新增会让两笔都计入统计。")
                FilterChip(selected=replace==null,onClick={replace=null},enabled=!busy,label={Text("保留原账，并新增这笔")})
                review.matches.forEach {old ->
                    val matches=old.date==date && old.type==type && runCatching{Rules.amount(amount)==old.amountFen}.getOrDefault(false)
                    FilterChip(selected=replace==old.id,onClick={replace=old.id},enabled=!busy&&matches,label={Text("仅保留新账，原账移入回收站：${old.date} ¥${Rules.money(old.amountFen)} ${old.note.take(60)}")})
                }
            }
        }
        item {
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            Button(onClick={save(type,category,amount,date,note,rememberMerchant,replace)},enabled=!busy&&category.isNotEmpty()&&amount.isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("auto-confirm")) {Text(if(c.status=="booked" || c.status=="merged") "保存修改" else if(replace!=null) "旧账移入回收站并确认新账" else if(review.matches.isNotEmpty()) "确认新增，保留两笔" else "确认入账")}
            if(c.status=="pending") OutlinedButton(onClick=ignore,enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("不入账，保留原有账目")}
            TextButton(onClick=cancel,enabled=!busy){Text("返回列表")}
        }
    }
    if(splitId!=null) AlertDialog(onDismissRequest={splitId=null},title={Text("拆分为两笔账目？")},
        text={Text("保留原账目，并根据这条来源新增一笔，两笔都会计入统计和预算。仅在它们确实是不同交易时确认。")},
        confirmButton={TextButton(onClick={val id=splitId!!;splitId=null;split(id)},enabled=!busy){Text("确认拆分，保留两笔")}},
        dismissButton={TextButton(onClick={splitId=null}){Text("取消")}})

}
