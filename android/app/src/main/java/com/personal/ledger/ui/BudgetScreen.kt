package com.personal.ledger.ui

import android.Manifest
import android.os.Build
import android.content.Intent
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
import com.personal.ledger.data.*
import com.personal.ledger.domain.Rules
import java.time.YearMonth
import org.json.JSONObject
import kotlinx.coroutines.delay

@Composable
fun BudgetScreen(vm: LedgerViewModel, busy: Boolean) {
    val plans by vm.budgets.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val notices by vm.budgetNotices.collectAsStateWithLifecycle()
    val warnings by vm.budgetWarnings.collectAsStateWithLifecycle()
    val draft by vm.budgetDraft.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var currentMonth by remember { mutableStateOf(YearMonth.from(Rules.today()).toString()) }
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications=NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    LaunchedEffect(vm) {
        while(true) {
            vm.refreshBudgets()
            currentMonth=YearMonth.from(Rules.today()).toString()
            notifications=NotificationManagerCompat.from(context).areNotificationsEnabled()
            delay(60000)
        }
    }
    if(draft != null) {
        key(draft!!.id) { BudgetEditor(draft!!,categories,plans,busy,{vm.saveBudget(it)},{vm.cancelBudget()}) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("budget-list"),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {
            Text("当前账本预算",style=MaterialTheme.typography.titleLarge)
            Text("支出计入预算，收入和回收站不计入。分类未设预算的支出仍计入总额。")
            Button(onClick={vm.newBudget()},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("new-budget")) {Text("新建预算计划")}
            Text(if(notifications) "系统通知已允许；也可在本页查看提醒" else "系统通知未开启，软件内提醒仍有效")
            TextButton(onClick={
                if(Build.VERSION.SDK_INT >= 33 && !notifications) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
            }) {Text("设置预算通知")}
        }
        items(warnings) {Text(it,color=MaterialTheme.colorScheme.error)}
        items(notices,key={"notice/${it.key}"}) { Text(it.message,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("budget-notice")) }
        if(plans.isEmpty()) item {Text("尚未设置预算，可提前安排未来月份")}
        items(plans,key={it.id}) { plan ->
            val usage=BudgetRules.usage(plan,entries,categories)
            val total=usage.first()
            val values=BudgetRules.allocations(plan.allocations)
            val months=BudgetRules.months(plan)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(plan.name,style=MaterialTheme.typography.titleLarge)
                    Text("${plan.startMonth} 至 ${plan.endMonth} · ${months.size}个月")
                    BudgetUsage(total)
                    Text("剩余${months.count { it >= currentMonth }}个月（含当月）；月均参考 ¥${Rules.money(plan.totalFen/months.size)}")
                    Text("未分配预算 ¥${Rules.money(plan.totalFen-values.values.sumOf{it.values.sum()})}")
                    var detail by rememberSaveable(plan.id) { mutableStateOf(false) }
                    TextButton(onClick={detail=!detail}) {Text(if(detail) "收起月度分类" else "查看月度分类")}
                    if(detail) usage.drop(1).sortedByDescending{it.spent.toDouble()/it.limit}.forEach {BudgetUsage(it)}
                    OutlinedButton(onClick={vm.editBudget(plan)},enabled=!busy,modifier=Modifier.testTag("edit-budget-${plan.id}")) {Text("编辑计划与分类预算")}
                    if(plan.endMonth < currentMonth && !plan.renewalDismissed) {
                        Text("本期已结束，如何安排下一期？")
                        TextButton(onClick={vm.newBudget(plan,true)},enabled=!busy) {Text("沿用上一期")}
                        TextButton(onClick={vm.newBudget(plan,false)},enabled=!busy) {Text("自主安排")}
                        TextButton(onClick={vm.dismissBudgetRenewal(plan)},enabled=!busy) {Text("暂不创建")}
                        Text("沿用将生成相同时长、总额和逐月分配的草稿；确认保存才会创建。已有月份不会覆盖。",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable private fun BudgetUsage(usage: BudgetRules.Usage) {
    Text(usage.label,style=MaterialTheme.typography.titleMedium)
    LinearProgressIndicator(progress={ (usage.spent.toFloat()/usage.limit).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
    Text("已花 ¥${Rules.money(usage.spent)} / ¥${Rules.money(usage.limit)} · ${usage.spent*100/usage.limit}%")
    Text(if(usage.spent>usage.limit) "超支 ¥${Rules.money(usage.spent-usage.limit)}" else "剩余 ¥${Rules.money(usage.limit-usage.spent)}")
}

@Composable
fun BudgetEditor(initial: BudgetPlan, categories: List<Category>, plans: List<BudgetPlan>, busy: Boolean, onSave: (BudgetPlan)->Unit, onCancel: ()->Unit) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var start by rememberSaveable { mutableStateOf(initial.startMonth) }
    var end by rememberSaveable { mutableStateOf(initial.endMonth) }
    var total by rememberSaveable { mutableStateOf(if(initial.name.isEmpty()) "" else Rules.money(initial.totalFen)) }
    var month by rememberSaveable { mutableStateOf(initial.startMonth) }
    var raw by rememberSaveable { mutableStateOf(JSONObject().apply {
        BudgetRules.allocations(initial.allocations).forEach { (m, cats) -> put(m,JSONObject().apply {cats.forEach{(id,fen)->put(id,Rules.money(fen))}}) }
    }.toString()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var selectMonth by remember { mutableStateOf(false) }
    val months=runCatching {BudgetRules.months(initial.copy(startMonth=start,endMonth=end))}.getOrDefault(emptyList())
    val values=JSONObject(raw)
    fun parse(): Map<String,Map<String,Long>> = values.keys().asSequence().associateWith { m ->
        val cats=values.getJSONObject(m)
        cats.keys().asSequence().filter {cats.getString(it).isNotBlank()}.associateWith {Rules.amount(cats.getString(it))}
    }
    fun perform(block: ()->Unit) {
        try { block();error=null } catch(e: Exception) {error=e.message ?: "请检查预算内容"}
    }
    LazyColumn(Modifier.fillMaxSize().testTag("budget-editor"),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item {OutlinedTextField(name,{name=it},label={Text("计划名称")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth().testTag("budget-name"))}
        item {OutlinedTextField(start,{start=it},label={Text("开始月份（YYYY-MM）")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth().testTag("budget-start"))}
        item {OutlinedTextField(end,{end=it},label={Text("结束月份（YYYY-MM）")},enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth().testTag("budget-end"))}
        item {OutlinedTextField(total,{total=it},label={Text("全期总预算（元）")},enabled=!busy,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("budget-total"))}
        item {
            Text("选择连续月份，单月可超过月均值；所有月份的分类分配合计不能超过总额。")
            val sum=runCatching {parse().values.sumOf{it.values.sum()}}.getOrNull()
            if(sum!=null) Text("已分配 ¥${Rules.money(sum)}")
            OutlinedButton(onClick={selectMonth=true},enabled=months.isNotEmpty()&&!busy) {Text("编辑分类月份：$month")}
            if(month !in months) Text("请先填写有效月份，再选择分类月份",color=MaterialTheme.colorScheme.error)
            else {
                Text(if(values.has(month)) "本月已单独设置，不会被自动沿用覆盖" else "本月尚未设置，到达该月时将尝试沿用上月；额度不足会提示")
                TextButton(onClick={perform {
                    require(!values.has(month)) {"本月已设置，将保留原分配；可直接修改下方金额"}
                    val previous=YearMonth.parse(month).minusMonths(1).toString()
                    val source=values.optJSONObject(previous) ?: error("上个月尚无分类分配")
                    val copy=JSONObject(raw).put(month,JSONObject(source.toString()))
                    val allocated=copy.keys().asSequence().sumOf { m -> val c=copy.getJSONObject(m); c.keys().asSequence().filter{c.getString(it).isNotBlank()}.sumOf{Rules.amount(c.getString(it))} }
                    require(allocated<=Rules.amount(total)) {"剩余可分配预算不足，请先调整总额或分配"}
                    raw=copy.toString()
                }},enabled=!busy) {Text("沿用上个月分类预算")}
                TextButton(onClick={raw=JSONObject(raw).put(month,JSONObject()).toString()},enabled=!busy) {Text("本月不分配分类预算")}
            }
        }
        if(month in months) items(categories.filter {it.owner==initial.owner && it.type=="expense"},key={it.id}) {c ->
            OutlinedTextField(values.optJSONObject(month)?.optString(c.id).orEmpty(),{text ->
                val all=JSONObject(raw);val cats=all.optJSONObject(month) ?: JSONObject();cats.put(c.id,text);all.put(month,cats);raw=all.toString()
            },label={Text("${c.name}（元，留空不设置）")},enabled=!busy,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth().testTag("budget-category-${c.id}"))
        }
        item {
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("budget-error"))}
            Button(onClick={perform {
                val plan=initial.copy(name=name.trim(),startMonth=start,endMonth=end,totalFen=Rules.amount(total),allocations=BudgetRules.encode(parse()))
                BudgetRules.validate(plan,plans,categories);onSave(plan)
            }},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("save-budget")) {Text("保存预算计划")}
            OutlinedButton(onClick=onCancel,enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("取消本次编辑")}
        }
    }
    if(selectMonth) AlertDialog(onDismissRequest={selectMonth=false},title={Text("选择分类月份")},text={
        LazyColumn(Modifier.heightIn(max=360.dp)) {items(months){m -> TextButton(onClick={month=m;selectMonth=false}){Text(m)}}}
    },confirmButton={TextButton(onClick={selectMonth=false}){Text("取消")}})
}
