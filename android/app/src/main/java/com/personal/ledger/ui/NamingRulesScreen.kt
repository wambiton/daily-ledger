package com.personal.ledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.personal.ledger.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@Composable fun NamingRulesScreen(databaseName: String, owner: String, label: String, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<StoredNamingRule>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SmartNamingRule?>(null) }
    var removing by remember { mutableStateOf<SmartNamingRule?>(null) }
    fun execute(block: suspend (NamingRuleStore) -> Unit) {
        if(busy) return
        busy = true; error = null
        scope.launch {
            var db: LedgerDatabase? = null
            try {
                require(AutoCapture.validName(databaseName) && context.getDatabasePath(databaseName).exists()) { "指定账本暂不可用" }
                db = LedgerDatabases.acquire(context, databaseName)
                val store = NamingRuleStore(db, databaseName, owner)
                block(store)
                categories = db.ledger().categorySnapshot(owner).filter { it.type == "expense" }
                rows = store.snapshot(); loaded = true
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { error = e.message ?: "规则未保存，请检查输入" }
            finally { if(db != null) LedgerDatabases.release(databaseName); busy = false }
        }
    }
    LaunchedEffect(databaseName, owner) { execute {} }
    val edit = editing
    if(edit != null) {
        key(edit.id) { NamingRuleEditor(edit, categories, busy, error, { editing = null; error = null }) { updated ->
            execute { it.save(updated); editing = null }
        } }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("naming-rules"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TextButton(onClick = back, enabled = !busy) { Text("返回自动记账") }
            Text("自动命名规则", style = MaterialTheme.typography.headlineSmall)
            Text("适用账本：$label")
            Text("规则只处理新收到的普通支出。首次确认明确商户后，按你允许的范围自动记账；查看其他账本不会改变固定目标。")
            Text("规则随电脑备份保存，需要支持新格式的电脑端；未备份前仍保存在手机。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { editing = SmartNamingRule(UUID.randomUUID().toString(), databaseName, owner, "早餐", categories.first().id) },
                enabled = loaded && !busy && categories.isNotEmpty(), modifier = Modifier.testTag("naming-add")) { Text("新增规则") }
            if(loaded && categories.isEmpty()) Text("请先在这个账本添加支出分类")
            if(loaded && rows.isEmpty()) Text("暂无命名规则，原有商家分类记忆继续生效")
        }
        items(rows, key = { it.rule.id }) { stored ->
            val r = stored.rule
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text(r.title, style = MaterialTheme.typography.titleMedium)
                Text("${if(r.enabled) "已启用" else "已暂停"} · ${r.channel.ifEmpty { "所有渠道" }} · ${r.merchant.ifEmpty { "商户不限，分别确认" }}")
                Text("${clockText(r.startMinute)}—${clockText(r.endMinute)} · 星期${r.weekdays.sorted().joinToString("、")} · 优先级${r.priority}")
                Text(if(r.allowAutomatic) "允许明确匹配后自动记账 · 已确认${stored.trusted.size}组商户/渠道" else "仅建议名称，每次确认")
                Row {
                    TextButton(onClick = { editing = r }, enabled = !busy) { Text("编辑") }
                    TextButton(onClick = { execute { it.save(r.copy(enabled = !r.enabled)) } }, enabled = !busy) { Text(if(r.enabled) "暂停" else "启用") }
                    TextButton(onClick = { removing = r }, enabled = !busy) { Text("删除") }
                }
            } }
        }
    }
    removing?.let { r -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("删除“${r.title}”规则？") },
        text = { Text("已有账目保留，后续不再使用这条规则。") },
        confirmButton = { TextButton(onClick = { execute { it.remove(r.id); removing = null } }, enabled = !busy) { Text("删除规则") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}

private fun clockText(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
private fun parseClock(text: String): Int {
    require(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]|24:00").matches(text)) { "时间请填写HH:mm，例如06:30" }
    val parts = text.split(":"); return parts[0].toInt() * 60 + parts[1].toInt()
}

@Composable fun NamingRuleEditor(initial: SmartNamingRule, categories: List<Category>, busy: Boolean, error: String?,
                                 cancel: () -> Unit, save: (SmartNamingRule) -> Unit) {
    var title by remember { mutableStateOf(initial.title) }
    var merchant by remember { mutableStateOf(initial.merchant) }
    var channel by remember { mutableStateOf(initial.channel) }
    var category by remember { mutableStateOf(initial.categoryId) }
    var start by remember { mutableStateOf(clockText(initial.startMinute)) }
    var end by remember { mutableStateOf(clockText(initial.endMinute)) }
    var days by remember { mutableStateOf(initial.weekdays) }
    var priority by remember { mutableStateOf(initial.priority.toString()) }
    var automatic by remember { mutableStateOf(initial.allowAutomatic) }
    var validation by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("naming-editor"), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("编辑命名规则", style = MaterialTheme.typography.headlineSmall)
            Text("修改或暂停规则会清除该规则已确认的商户，下次重新确认。已有账目不变。") }
        item { OutlinedTextField(title, { title = it }, label = { Text("名称，例如早餐（最多40字）") }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("naming-title")) }
        item { Text("支出分类") }
        items(categories, key = { it.id }) { c -> FilterChip(selected = category == c.id, onClick = { category = c.id }, enabled = !busy, label = { Text(c.name) }) }
        item { Text("支付渠道")
            Row { listOf("", "微信", "支付宝", "银行卡").forEach { value -> FilterChip(selected = channel == value, onClick = { channel = value }, enabled = !busy, label = { Text(value.ifEmpty { "不限" }) }) } } }
        item { OutlinedTextField(merchant, { merchant = it }, label = { Text("精确商户，可留空分别确认") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(start, { start = it }, label = { Text("开始 HH:mm") }, enabled = !busy, modifier = Modifier.weight(1f))
            OutlinedTextField(end, { end = it }, label = { Text("结束 HH:mm") }, enabled = !busy, modifier = Modifier.weight(1f))
        }; Text("开始时间包含，结束时间不包含；跨午夜按开始日的星期判断。", style = MaterialTheme.typography.bodySmall) }
        item { Text("星期（1为周一，7为周日）")
            for(group in listOf(1..4, 5..7)) Row { group.forEach { day -> FilterChip(selected = day in days,
                onClick = { days = if(day in days) days - day else days + day }, enabled = !busy, label = { Text("$day") }) } } }
        item { OutlinedTextField(priority, { priority = it }, label = { Text("优先级0—100，越大越优先") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
        item { Row { Checkbox(checked = automatic, onCheckedChange = { automatic = it }, enabled = !busy, modifier = Modifier.testTag("naming-automatic")); Text("首次确认后，允许同商户、同渠道明确匹配时自动记账") }
            Text("不勾选时仅建议名称。商户缺失或规则冲突时仍需确认。") }
        item { (validation ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                runCatching { initial.copy(title = title.trim(), merchant = merchant.trim(), channel = channel, categoryId = category,
                    startMinute = parseClock(start), endMinute = parseClock(end), weekdays = days, priority = priority.toInt(), allowAutomatic = automatic) }
                    .onSuccess { validation = null; save(it) }.onFailure { validation = "请检查名称、时间、星期和优先级：${it.message.orEmpty()}" }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("naming-save")) { Text("保存规则") }
            TextButton(onClick = cancel, enabled = !busy) { Text("取消") } }
    }
}
