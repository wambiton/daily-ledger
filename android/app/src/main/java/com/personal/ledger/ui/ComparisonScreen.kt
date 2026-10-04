package com.personal.ledger.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.personal.ledger.data.*
import com.personal.ledger.domain.*
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun ComparisonScreen(entries: List<Entry>, categories: List<Category>, onEntry: (Entry) -> Unit) {
    val today = Rules.today()
    val context = LocalContext.current
    var editingPeriods by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("week") }
    var type by rememberSaveable { mutableStateOf("expense") }
    // Separate, saveable selections survive mode switches and detail navigation.
    var weeks by rememberSaveable { mutableStateOf("$today|${today.minusWeeks(1)}") }
    var months by rememberSaveable { mutableStateOf("${YearMonth.from(today)}|${YearMonth.from(today).minusMonths(1)}") }
    var monthPicker by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectionError by rememberSaveable { mutableStateOf<String?>(null) }
    var detailCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = (if(mode == "week") weeks else months).split("|")
    val periods = selected.map { if(mode == "week") Comparison.week(LocalDate.parse(it), today) else Comparison.month(YearMonth.parse(it), today) }
    val result = remember(entries, type, periods) { Comparison.calculateMany(entries, type, periods) }
    val names = remember(categories) { categories.associate { it.id to it.name } }
    fun label(index: Int) = if(index < 26) ('A'.code + index).toChar().toString() else "${index + 1}"
    fun saveSelection(values: List<String>) {
        if(mode == "week") weeks = values.joinToString("|") else months = values.joinToString("|")
        detailCategory = null
    }
    fun select(index: Int, value: String) {
        val period = if(mode == "week") Comparison.week(LocalDate.parse(value), today) else Comparison.month(YearMonth.parse(value), today)
        if(periods.withIndex().any { it.index != index && it.value.start == period.start }) {
            selectionError = "请选择不同的比较周期"
        } else {
            val updated = selected.toMutableList()
            if(index == -1) updated.add(value) else updated[index] = value
            saveSelection(updated)
        }
    }
    fun pickWeek(index: Int) {
        val initial = if(index < 0) today else LocalDate.parse(selected[index])
        DatePickerDialog(context, { _, year, month, day -> select(index, LocalDate.of(year, month + 1, day).toString()) },
            initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            datePicker.maxDate = today.plusDays(1).atStartOfDay(Rules.zone).toInstant().toEpochMilli() - 1
        }.show()
    }
    @Composable fun AmountBars(totals: List<Long>, totalTags: Boolean = false) {
        val scale = totals.maxOrNull()?.coerceAtLeast(1) ?: 1L
        totals.forEachIndexed { index, amount ->
            Text("${label(index)} · ${if(mode == "month") YearMonth.from(periods[index].start) else periods[index].label}", style = MaterialTheme.typography.labelMedium)
            Text("¥${Rules.money(amount)}", modifier = if(totalTags) Modifier.testTag("total-${label(index).lowercase()}") else Modifier,
                style = MaterialTheme.typography.titleLarge, fontFamily = MoneyFont, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { amount.toFloat() / scale }, modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)))
            if(index > 0) {
                val difference = ComparisonResult(amount, totals.first(), emptyList())
                Text("较 A 差额 ¥${Rules.money(difference.delta)} · " + (difference.percent?.let { "$it%" } ?: "A 为零，不计算比例"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionBanner("看见每一段生活的变化", "自由选择多周或多月，比较真实收支", "bars", com.personal.ledger.R.drawable.winter_comparison)
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = mode == "week", onClick = { mode = "week"; detailCategory = null }, label = { Text("按周") })
            FilterChip(selected = mode == "month", onClick = { mode = "month"; detailCategory = null }, label = { Text("按月") })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("已选 ${periods.size} ${if(mode == "week") "周" else "个月"}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { editingPeriods = !editingPeriods }, modifier = Modifier.testTag("toggle-period-editor")) {
                Text(if(editingPeriods) "收起周期选择" else "调整比较周期  ›")
            }
        }
        if (!editingPeriods) {
            Text(periods.take(2).mapIndexed { index, period -> "${label(index)}：${period.label}" }.joinToString("\n") +
                if(periods.size > 2) "\n另有 ${periods.size - 2} 个周期" else "", style = MaterialTheme.typography.bodySmall)
        } else {
        periods.forEachIndexed { index, period ->
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { if(mode == "week") pickWeek(index) else monthPicker = index }, modifier = Modifier.weight(1f).testTag("period-${label(index)}")) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${label(index)} · ${if(mode == "month") YearMonth.from(period.start) else "周比较"}", fontWeight = FontWeight.Bold)
                        Text(period.label, style = MaterialTheme.typography.bodySmall)
                        Text(period.actualLabel, style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = { saveSelection(selected.filterIndexed { i, _ -> i != index }) }, enabled = periods.size > 2,
                    modifier = Modifier.testTag("remove-${label(index)}")) { Text("移除") }
            }
        }
        OutlinedButton(onClick = { if(mode == "week") pickWeek(-1) else monthPicker = -1 }, modifier = Modifier.fillMaxWidth().testTag("add-period")) {
            Text(if(mode == "week") "＋ 添加一周" else "＋ 添加一个月")
        }
        Text("至少保留两个周期；差额及比例以 A 为基准。", style = MaterialTheme.typography.bodySmall)
        }
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = type == "expense", onClick = { type = "expense"; detailCategory = null }, label = { Text("支出") })
            FilterChip(selected = type == "income", onClick = { type = "income"; detailCategory = null }, label = { Text("收入") })
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if(type == "expense") "支出比较" else "收入比较", style = MaterialTheme.typography.titleLarge)
                AmountBars(result.totals, true)
                if(periods.any { it.partial }) Text("未结束周期仅统计截至当天；历史周期保持完整范围。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if(detailCategory == null) {
            Text("分类比较", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if(result.categories.isEmpty()) SectionBanner("所选周期暂无记录", "记下第一笔后，就能在这里看到变化", "book")
            result.categories.sortedByDescending { it.totals.sum() }.forEachIndexed { rank, row ->
                Card(modifier = Modifier.fillMaxWidth().clickable { detailCategory = row.id }.testTag("compare-category-${row.id}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${rank + 1} · ${names[row.id] ?: "分类"}", fontWeight = FontWeight.Bold)
                        AmountBars(row.totals)
                        Text("查看各周期明细 ›")
                    }
                }
            }
        } else {
            TextButton(onClick = { detailCategory = null }) { Text("返回分类比较") }
            Text("${names[detailCategory] ?: "分类"} · 明细", style = MaterialTheme.typography.titleMedium)
            periods.forEachIndexed { index, period ->
                Text("${label(index)} · ${period.label} · ${period.actualLabel}", fontWeight = FontWeight.Bold)
                val matching = entries.filter { it.type == type && it.categoryId == detailCategory && it.deletedAt == null && period.contains(it.date) }
                if(matching.isEmpty()) Text("暂无记录")
                matching.forEach { entry ->
                    OutlinedButton(onClick = { onEntry(entry) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("${entry.date} · ¥${Rules.money(entry.amountFen)}")
                            if(entry.note.isNotBlank()) Text(entry.note, maxLines = 2)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    monthPicker?.let { index -> MonthPicker(if(index < 0) YearMonth.from(today) else YearMonth.parse(selected[index]), onDismiss = { monthPicker = null }) {
        select(index, it.toString()); monthPicker = null
    } }
    selectionError?.let { AlertDialog(onDismissRequest = { selectionError = null }, title = { Text(it) },
        confirmButton = { TextButton(onClick = { selectionError = null }) { Text("知道了") } }) }
}

@Composable private fun MonthPicker(initial: YearMonth, onDismiss: () -> Unit, onSelected: (YearMonth) -> Unit) {
    var yearText by rememberSaveable { mutableStateOf(initial.year.toString()) }
    val today = YearMonth.from(Rules.today())
    val year = yearText.toIntOrNull()?.takeIf { it in 1900..today.year }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择月份") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(yearText,{ if(it.length <= 4) yearText = it },label={ Text("年份") },keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.testTag("month-year"))
            if(year == null) Text("请输入 1900—${today.year} 的年份",color=MaterialTheme.colorScheme.error)
            (1..12).chunked(3).forEach { row -> Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                row.forEach { month ->
                    TextButton(onClick={ year?.let { onSelected(YearMonth.of(it,month)) } },enabled=year != null && YearMonth.of(year,month) <= today,modifier=Modifier.weight(1f).testTag("month-$month")) { Text("${month}月") }
                }
            } }
        }
    }, confirmButton = { TextButton(onClick=onDismiss) { Text("取消") } })
}
