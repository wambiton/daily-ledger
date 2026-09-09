package com.personal.ledger.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
    var mode by rememberSaveable { mutableStateOf("week") }
    var type by rememberSaveable { mutableStateOf("expense") }
    var weekA by rememberSaveable { mutableStateOf(today.toString()) }
    var weekB by rememberSaveable { mutableStateOf(today.minusWeeks(1).toString()) }
    var monthA by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var monthB by rememberSaveable { mutableStateOf(YearMonth.from(today).minusMonths(1).toString()) }
    var monthPicker by rememberSaveable { mutableStateOf<String?>(null) }
    var selectionError by rememberSaveable { mutableStateOf<String?>(null) }
    var detailCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val a = if(mode == "week") Comparison.week(LocalDate.parse(weekA),today) else Comparison.month(YearMonth.parse(monthA),today)
    val b = if(mode == "week") Comparison.week(LocalDate.parse(weekB),today) else Comparison.month(YearMonth.parse(monthB),today)
    val result = remember(entries, type, a, b) { Comparison.calculate(entries, type, a, b) }
    val names = remember(categories) { categories.associate { it.id to it.name } }
    val aColor = MaterialTheme.colorScheme.primary
    val bColor = Color(0xFF9AA9C5)

    fun pickWeek(side: String) {
        val initial = LocalDate.parse(if(side == "A") weekA else weekB)
        DatePickerDialog(context, { _, year, month, day ->
            val selected = LocalDate.of(year,month + 1,day)
            val other = if(side == "A") b else a
            if(Comparison.week(selected,today).start == other.start) selectionError = "请选择不同的比较周期"
            else { if(side == "A") weekA = selected.toString() else weekB = selected.toString(); detailCategory = null }
        },initial.year,initial.monthValue - 1,initial.dayOfMonth).apply {
            datePicker.maxDate = today.plusDays(1).atStartOfDay(Rules.zone).toInstant().toEpochMilli() - 1
        }.show()
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = mode == "week", onClick = { mode = "week"; detailCategory = null }, label = { Text("按周") })
            FilterChip(selected = mode == "month", onClick = { mode = "month"; detailCategory = null }, label = { Text("按月") })
        }
        listOf("A" to a, "B" to b).forEach { (side, period) ->
            OutlinedButton(onClick = { if(mode == "week") pickWeek(side) else monthPicker = side }, modifier = Modifier.fillMaxWidth().testTag("period-$side")) {
                Column(Modifier.fillMaxWidth()) {
                    Text("$side · ${if(mode == "month") YearMonth.from(period.start) else "周比较"}", fontWeight = FontWeight.Bold)
                    Text(period.label, style = MaterialTheme.typography.bodySmall)
                    Text(period.actualLabel, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = type == "expense", onClick = { type = "expense"; detailCategory = null }, label = { Text("支出") })
            FilterChip(selected = type == "income", onClick = { type = "income"; detailCategory = null }, label = { Text("收入") })
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("A" to result.a, "B" to result.b).forEach { (side,total) ->
                        Column(Modifier.weight(1f)) {
                            Text("$side ${if(type == "expense") "支出" else "收入"}", style = MaterialTheme.typography.labelMedium)
                            Text("¥${Rules.money(total)}", modifier = Modifier.testTag("total-${side.lowercase()}"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Canvas(Modifier.fillMaxWidth().height(100.dp)) {
                    val max = maxOf(result.a,result.b,1).toFloat()
                    val width = size.width / 5f
                    val ha = size.height * (result.a.toFloat() / max)
                    val hb = size.height * (result.b.toFloat() / max)
                    drawRect(aColor,Offset(width,size.height-ha),Size(width,ha))
                    drawRect(bColor,Offset(width*3,size.height-hb),Size(width,hb))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) { Text("A",color=aColor); Text("B",color=bColor) }
                Text(if(result.delta == 0L) "A 与 B 金额相同" else "A 比 B ${if(result.delta > 0) "多" else "少"} ¥${Rules.money(kotlin.math.abs(result.delta))}")
                Text(result.percent?.let { "变化 $it%（以 B 为基准）" } ?: if(result.a == 0L) "无变化" else "B 无金额记录，不计算变化比例", style = MaterialTheme.typography.bodySmall)
                if(a.partial || b.partial) Text("未结束周期仅统计截至当天；历史周期保持完整范围。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(detailCategory == null) {
            Text("分类比较",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            if(result.categories.isEmpty()) Text("所选周期暂无记录")
            val scale = result.categories.maxOfOrNull { maxOf(it.a,it.b) }?.coerceAtLeast(1) ?: 1L
            result.categories.forEach { row ->
                Card(modifier = Modifier.fillMaxWidth().clickable { detailCategory = row.id }.testTag("compare-category-${row.id}"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(names[row.id] ?: "分类", fontWeight = FontWeight.Bold)
                        Text("A ¥${Rules.money(row.a)} · B ¥${Rules.money(row.b)}",style=MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { row.a.toFloat()/scale }, modifier = Modifier.fillMaxWidth(), color = aColor)
                        LinearProgressIndicator(progress = { row.b.toFloat()/scale }, modifier = Modifier.fillMaxWidth(), color = bColor)
                        Text("差额（A−B）：¥${Rules.money(row.delta)}  ›",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            TextButton(onClick = { detailCategory = null }) { Text("返回分类比较") }
            Text("${names[detailCategory] ?: "分类"} · 明细",style=MaterialTheme.typography.titleMedium)
            listOf("A" to a,"B" to b).forEach { (side,period) ->
                Text("$side · ${period.actualLabel}",fontWeight=FontWeight.Bold)
                val matching = entries.filter { it.type == type && it.categoryId == detailCategory && it.deletedAt == null && period.contains(it.date) }
                if(matching.isEmpty()) Text("暂无记录")
                matching.forEach { entry ->
                    OutlinedButton(onClick = { onEntry(entry) },modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("${entry.date} · ¥${Rules.money(entry.amountFen)}")
                            if(entry.note.isNotBlank()) Text(entry.note,maxLines=2)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    monthPicker?.let { side -> MonthPicker(YearMonth.parse(if(side == "A") monthA else monthB), onDismiss = { monthPicker = null }) { selected ->
        val other = if(side == "A") monthB else monthA
        if(selected.toString() == other) selectionError = "请选择不同的比较周期"
        else { if(side == "A") monthA = selected.toString() else monthB = selected.toString(); detailCategory = null }
        monthPicker = null
    } }
    selectionError?.let { AlertDialog(onDismissRequest = { selectionError = null },title = { Text(it) },confirmButton = { TextButton(onClick = { selectionError = null }) { Text("知道了") } }) }
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
