package com.personal.ledger.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.personal.ledger.data.Category
import com.personal.ledger.data.Entry
import com.personal.ledger.domain.EntrySearch
import com.personal.ledger.domain.Rules
import java.time.LocalDate

@Composable
fun SearchScreen(entries: List<Entry>, categories: List<Category>, owner: String, onEntry: (Entry) -> Unit) {
    var keyword by rememberSaveable { mutableStateOf("") }
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var chooseCategory by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val result = remember(entries, owner, keyword, from, to, type, category) {
        EntrySearch(keyword, from, to, type, category).apply(entries, owner)
    }
    val byId = remember(categories) { categories.associateBy { it.id } }
    fun pick(value: String, set: (String) -> Unit) {
        val day = if(value.isEmpty()) Rules.today() else LocalDate.parse(value)
        DatePickerDialog(context, { _, y, m, d -> set(LocalDate.of(y,m+1,d).toString()) },
            day.year, day.monthValue-1, day.dayOfMonth).apply {
            datePicker.maxDate = Rules.today().plusDays(1).atStartOfDay(Rules.zone).toInstant().toEpochMilli()-1
        }.show()
    }
    LazyColumn(Modifier.fillMaxSize().testTag("search-list"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom=24.dp)) {
        item {
            Text("仅搜索当前账本，不含回收站", style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(keyword, { keyword=it }, label={Text("搜索备注关键词")}, singleLine=true,
                modifier=Modifier.fillMaxWidth().testTag("search-keyword"))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("" to "全部", "expense" to "支出", "income" to "收入").forEach { (value,label) ->
                    FilterChip(selected=type==value, onClick={type=value; category=""}, label={Text(label)}, modifier=Modifier.testTag("search-type-$value"))
                }
            }
            OutlinedButton(onClick={chooseCategory=true}, modifier=Modifier.fillMaxWidth()) {
                Text("分类：${byId[category]?.name ?: "全部分类"}")
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={pick(from){from=it}}, modifier=Modifier.weight(1f)) { Text(from.ifEmpty{"开始日期不限"}) }
                OutlinedButton(onClick={pick(to){to=it}}, modifier=Modifier.weight(1f)) { Text(to.ifEmpty{"结束日期不限"}) }
            }
            TextButton(onClick={keyword="";from="";to="";type="";category=""}, modifier=Modifier.testTag("search-reset")) {Text("重置全部条件")}
            if(!EntrySearch(from=from,to=to).valid) Text("开始日期不能晚于结束日期", color=MaterialTheme.colorScheme.error)
            else {
                val income=result.filter{it.type=="income"}.sumOf{it.amountFen}
                val expense=result.filter{it.type=="expense"}.sumOf{it.amountFen}
                Text("共 ${result.size} 笔", modifier=Modifier.testTag("search-count"))
                Text("收入 ¥${Rules.money(income)}　支出 ¥${Rules.money(expense)}")
                Text("结余 ¥${Rules.money(income-expense)}")
                if(result.isEmpty()) Text("没有符合条件的账目")
            }
        }
        items(result, key={it.id}) { entry ->
            Card(Modifier.fillMaxWidth().testTag("search-entry-${entry.id}").clickable{onEntry(entry)}) {
                Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("${entry.date} · ${byId[entry.categoryId]?.name.orEmpty()}")
                    Text("${if(entry.type=="income") "+" else "−"}¥${Rules.money(entry.amountFen)}")
                    if(entry.note.isNotBlank()) Text(entry.note)
                }
            }
        }
    }
    if(chooseCategory) AlertDialog(onDismissRequest={chooseCategory=false}, title={Text("选择分类")}, text={
        LazyColumn(Modifier.heightIn(max=360.dp)) {
            item { TextButton(onClick={category="";chooseCategory=false}) {Text("全部分类")} }
            items(categories.filter{it.owner==owner && (type.isEmpty() || it.type==type)}, key={it.id}) { c ->
                TextButton(onClick={category=c.id;chooseCategory=false}) {Text("${if(c.type=="expense") "支出" else "收入"} · ${c.name}")}
            }
        }
    }, confirmButton={TextButton(onClick={chooseCategory=false}){Text("取消")}})
}
