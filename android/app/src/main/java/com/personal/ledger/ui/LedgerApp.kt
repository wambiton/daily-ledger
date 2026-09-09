package com.personal.ledger.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.ledger.data.Category
import com.personal.ledger.data.Entry
import com.personal.ledger.domain.Rules
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerApp(vm: LedgerViewModel = viewModel()) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var route by rememberSaveable { mutableStateOf("home") }
    var selectedDate by rememberSaveable { mutableStateOf(Rules.today().toString()) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now(Rules.zone).toString()) }
    var type by rememberSaveable { mutableStateOf("expense") }
    var categoryId by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(Rules.today().toString()) }
    var note by rememberSaveable { mutableStateOf("") }
    var entryId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var categoryReturn by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var deleteConfirm by rememberSaveable { mutableStateOf(false) }
    var newCategory by rememberSaveable { mutableStateOf(false) }
    var categoryName by rememberSaveable { mutableStateOf("") }
    var categoryError by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val today = Rules.today()
    val byId = remember(categories) { categories.associateBy { it.id } }

    fun chooseDate(initial: String, complete: (LocalDate) -> Unit) {
        val d = LocalDate.parse(initial)
        DatePickerDialog(context, { _, year, month, day -> complete(LocalDate.of(year, month + 1, day)) },
            d.year, d.monthValue - 1, d.dayOfMonth).apply {
            datePicker.maxDate = today.plusDays(1).atStartOfDay(Rules.zone).toInstant().toEpochMilli() - 1
        }.show()
    }
    fun beginNew(onDate: String) {
        type = "expense"; categoryId = ""; amount = ""; note = ""; date = onDate
        entryId = null; dirty = false; categoryReturn = false; route = "choose"
    }
    fun leave() {
        if (busy) return
        when {
            route == "choose" && categoryReturn -> { route = "form"; categoryReturn = false }
            (route == "form" || route == "choose") && dirty -> discard = true
            route == "form" && entryId != null -> route = "detail"
            else -> route = "home"
        }
    }
    BackHandler(enabled = route != "home") { leave() }
    LaunchedEffect(error) { error?.let {
        if(newCategory) categoryError = it else snackbar.showSnackbar(it)
        vm.error.value = null
    } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(
            title = { Text(when(route) { "choose" -> "选择分类"; "form" -> if(entryId == null) "记一笔" else "编辑账目"; "detail" -> "账目详情"; "manage" -> "分类管理"; else -> if(tab == 0) "账本" else "我的" }, fontWeight = FontWeight.Bold) },
            navigationIcon = { if(route != "home") TextButton(onClick = { leave() }, enabled = !busy) { Text("返回") } },
            actions = { if(route == "home" && tab == 0) Text("本地保存", modifier = Modifier.padding(end = 20.dp), style = MaterialTheme.typography.labelMedium) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        ) },
        bottomBar = { if(route == "home") NavigationBar {
            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("▤") }, label = { Text("账本") })
            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("○") }, label = { Text("我的") })
        } },
        floatingActionButton = { if(route == "home" && tab == 0 && ready) ExtendedFloatingActionButton(
            onClick = { beginNew(today.toString()) }, modifier = Modifier.testTag("new-entry")) { Text("＋ 记一笔") } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!ready) {
                Text("正在打开账本…")
                if(!busy) Button(onClick = { vm.initialize() }) { Text("重试") }
            } else when(route) {
                "home" -> if(tab == 0) {
                    DayCard("今天 · ${today.monthValue}月${today.dayOfMonth}日", entries.filter { it.date == today.toString() }, byId,
                        onEntry = { detailId = it.id; route = "detail" }, compact = true)
                    CalendarCard(YearMonth.parse(monthText), LocalDate.parse(selectedDate), entries.map { it.date }.toSet(),
                        onMonth = { monthText = it.toString() },
                        onJump = { chooseDate(selectedDate) { selectedDate = it.toString(); monthText = YearMonth.from(it).toString() } },
                        onDate = { selectedDate = it.toString() })
                    TextButton(onClick = { selectedDate = today.toString(); monthText = YearMonth.from(today).toString() }) { Text("回到今天") }
                    if(selectedDate != today.toString()) {
                        DayCard(selectedDate, entries.filter { it.date == selectedDate }, byId, onEntry = { detailId = it.id; route = "detail" })
                        OutlinedButton(onClick = { beginNew(selectedDate) }, modifier = Modifier.fillMaxWidth()) { Text("补记这一天") }
                    }
                    Spacer(Modifier.height(90.dp))
                } else {
                    LedgerCard {
                        Text("个人账本", style = MaterialTheme.typography.titleLarge)
                        Text("本地预览版 · 暂未启用云端备份", style = MaterialTheme.typography.bodyMedium)
                        Text("账目保存在这台手机。请勿卸载应用或清除应用数据。", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { type = "expense"; route = "manage" }, modifier = Modifier.fillMaxWidth()) { Text("分类管理") }
                }
                "choose", "manage" -> {
                    TypeSelector(type, !busy) { type = it; categoryId = ""; if(categoryReturn) dirty = true }
                    Text(if(route == "choose") "先选择分类，再填写金额" else "收入与支出分类分别管理", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    categories.filter { it.type == type }.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { category ->
                                OutlinedButton(onClick = {
                                    if(route == "choose") { categoryId = category.id; dirty = true; route = "form"; categoryReturn = false }
                                }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 66.dp), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(8.dp)) { Text(category.name) }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    OutlinedButton(onClick = { categoryName = ""; categoryError = null; newCategory = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("＋ 新建分类") }
                    Spacer(Modifier.height(24.dp))
                }
                "form" -> {
                    TextButton(onClick = { categoryReturn = true; route = "choose" }, enabled = !busy) { Text("${if(type == "expense") "支出" else "收入"}分类：${byId[categoryId]?.name ?: "请选择"}  ›") }
                    OutlinedTextField(amount, { amount = it; dirty = true }, label = { Text("金额（元）") }, prefix = { Text("¥ ") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("amount"))
                    OutlinedButton(onClick = { chooseDate(date) { date = it.toString(); dirty = true } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("日期：$date") }
                    OutlinedTextField(note, { note = it; dirty = true }, label = { Text("备注（选填）") }, placeholder = { Text("例如：午餐，支付宝支付") },
                        enabled = !busy, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("note"))
                    Button(onClick = { vm.save(entryId, type, categoryId, amount, date, note) {
                        selectedDate = date; monthText = YearMonth.from(LocalDate.parse(date)).toString(); route = "home"; tab = 0; dirty = false
                    } }, enabled = !busy && categoryId.isNotEmpty() && amount.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("save-entry")) { Text(if(busy) "正在保存…" else "保存账目") }
                }
                "detail" -> {
                    val entry = entries.find { it.id == detailId }
                    if(entry == null) Text("这笔账目已不存在") else {
                        LedgerCard {
                            Text(if(entry.type == "expense") "支出" else "收入", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${if(entry.type == "expense") "−" else "+"}¥${Rules.money(entry.amountFen)}", style = MaterialTheme.typography.headlineLarge)
                            Text("分类：${byId[entry.categoryId]?.name.orEmpty()}")
                            Text("日期：${entry.date}")
                            Text("备注：${entry.note.ifBlank { "未填写" }}")
                        }
                        Button(onClick = {
                            entryId = entry.id; type = entry.type; categoryId = entry.categoryId; amount = Rules.money(entry.amountFen)
                            date = entry.date; note = entry.note; dirty = false; route = "form"
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("编辑") }
                        TextButton(onClick = { deleteConfirm = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("删除这笔账目", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if(discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃本次编辑？") },
        text = { Text("尚未保存的内容将丢失。") }, confirmButton = { TextButton(onClick = { discard = false; dirty = false; route = if(entryId == null) "home" else "detail" }) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
    if(deleteConfirm) AlertDialog(onDismissRequest = { if(!busy) deleteConfirm = false }, title = { Text("删除这笔账目？") },
        text = { val e = entries.find { it.id == detailId }; Text("${e?.let { byId[it.categoryId]?.name }.orEmpty()} ¥${e?.let { Rules.money(it.amountFen) }.orEmpty()}") },
        confirmButton = { TextButton(enabled = !busy, onClick = { detailId?.let { vm.delete(it) { deleteConfirm = false; route = "home" } } }) { Text("删除") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { deleteConfirm = false }) { Text("取消") } })
    if(newCategory) AlertDialog(onDismissRequest = { if(!busy) newCategory = false }, title = { Text("新建${if(type == "expense") "支出" else "收入"}分类") },
        text = { OutlinedTextField(categoryName, { categoryName = it; categoryError = null }, label = { Text("分类名称") }, singleLine = true, enabled = !busy,
            isError = categoryError != null, supportingText = { categoryError?.let { Text(it) } }) },
        confirmButton = { TextButton(enabled = !busy && categoryName.isNotBlank(), onClick = { vm.addCategory(type, categoryName) {
            newCategory = false; if(route == "choose") { categoryId = it.id; dirty = true; route = "form"; categoryReturn = false }
        } }) { Text("添加") } }, dismissButton = { TextButton(enabled = !busy, onClick = { newCategory = false }) { Text("取消") } })
}

@Composable private fun TypeSelector(type: String, enabled: Boolean, onType: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("expense" to "支出", "income" to "收入").forEach { (value, label) ->
            FilterChip(selected = type == value, onClick = { onType(value) }, enabled = enabled, label = { Text(label) })
        }
    }
}

@Composable private fun LedgerCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable private fun DayCard(title: String, entries: List<Entry>, categories: Map<String, Category>, onEntry: (Entry) -> Unit, compact: Boolean = false) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    LedgerCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf("income" to "收入", "expense" to "支出").forEach { (type, label) ->
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("¥${Rules.money(entries.filter { it.type == type }.sumOf { it.amountFen })}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
        if(entries.isEmpty()) Text("这一天还没有账目", color = MaterialTheme.colorScheme.onSurfaceVariant)
        (if(compact && !expanded) entries.take(3) else entries).forEach { entry ->
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().clickable { onEntry(entry) }.padding(vertical = 8.dp).testTag("entry-${entry.id}"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(categories[entry.categoryId]?.name ?: "分类", fontWeight = FontWeight.Medium)
                    if(entry.note.isNotBlank()) Text(entry.note, maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${if(entry.type == "expense") "−" else "+"}${Rules.money(entry.amountFen)}", fontWeight = FontWeight.Bold)
            }
        }
        if(compact && entries.size > 3) TextButton(onClick = { expanded = !expanded }) { Text(if(expanded) "收起今日账目" else "展开今日账目（${entries.size}笔）") }
    }
}

@Composable private fun CalendarCard(month: YearMonth, selected: LocalDate, recorded: Set<String>, onMonth: (YearMonth) -> Unit, onJump: () -> Unit, onDate: (LocalDate) -> Unit) {
    val today = Rules.today()
    LedgerCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onMonth(month.minusMonths(1)) }, modifier = Modifier.testTag("previous-month")) { Text("‹") }
            TextButton(onClick = onJump) { Text("${month.year}年${month.monthValue}月") }
            TextButton(onClick = { onMonth(month.plusMonths(1)) }, enabled = month < YearMonth.from(today)) { Text("›") }
        }
        Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(it, style = MaterialTheme.typography.labelSmall) } } }
        val offset = month.atDay(1).dayOfWeek.value - 1
        val rows = (offset + month.lengthOfMonth() + 6) / 7
        repeat(rows) { row -> Row {
            repeat(7) { col ->
                val day = row * 7 + col - offset + 1
                if(day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(40.dp)) else {
                    val value = month.atDay(day)
                    val isSelected = value == selected
                    val shape = RoundedCornerShape(10.dp)
                    val decoration = if(isSelected) Modifier.background(MaterialTheme.colorScheme.primary, shape) else if(value == today) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape) else Modifier
                    Column(Modifier.weight(1f).height(40.dp).then(decoration).testTag("date-$value").clickable(enabled = value <= today) { onDate(value) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(day.toString(), color = if(isSelected) MaterialTheme.colorScheme.onPrimary else if(value > today) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                        if(value.toString() in recorded) Text("•", style = MaterialTheme.typography.labelSmall, color = if(isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        } }
    }
}
