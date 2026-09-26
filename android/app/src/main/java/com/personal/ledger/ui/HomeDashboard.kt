package com.personal.ledger.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.ledger.R
import com.personal.ledger.data.*
import com.personal.ledger.domain.Rules
import java.time.LocalDate
import java.time.YearMonth

@Composable private fun Character(chibi: Boolean, modifier: Modifier) {
    Image(painterResource(R.drawable.winter_characters),null,modifier,
        alignment=BiasAlignment(if(chibi) 1f else -1f,0f),contentScale=ContentScale.Crop)
}

@Composable fun HomeDashboard(owner: String, entries: List<Entry>, categories: List<Category>, plans: List<BudgetPlan>,
    alertCount: Int, onBudget: ()->Unit, onCalendar: ()->Unit, onSearch: ()->Unit, onEntry: (Entry)->Unit) {
    val winter=LocalAppearance.current.selected==LedgerAppearance.WINTER
    val today=Rules.today()
    val month=YearMonth.from(today).toString()
    val active=remember(entries,owner) {entries.filter{it.owner==owner&&it.deletedAt==null}}
    val monthly=remember(active,month) {active.filter{it.date.startsWith(month)}}
    val expense=monthly.filter{it.type=="expense"}.sumOf{it.amountFen}
    val income=monthly.filter{it.type=="income"}.sumOf{it.amountFen}
    val byCategory=remember(categories) {categories.associateBy{it.id}}
    val groups=remember(active,today) {
        val grouped=active.groupBy{it.date}
        (listOf(today.toString())+grouped.keys.filter{it!=today.toString()}.sortedDescending()).map{it to grouped[it].orEmpty()}
    }
    val plan=plans.firstOrNull{it.owner==owner&&it.startMonth<=month&&it.endMonth>=month}
    Box(Modifier.fillMaxSize()) {
    if(winter) Image(painterResource(R.drawable.winter_yukino),null,Modifier.matchParentSize(),alignment=Alignment.CenterStart,contentScale=ContentScale.Crop,alpha=.20f)
    LazyColumn(Modifier.fillMaxSize().testTag("home-dashboard"),verticalArrangement=Arrangement.spacedBy(0.dp),contentPadding=PaddingValues(top=8.dp,bottom=104.dp)) {
        item(key="header") {
            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("我的账本",fontSize=30.sp,fontWeight=FontWeight.Bold)
                    Text(if(winter) "雪乃 · 冬日  ❄" else "记录生活 · 心中有数",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${today.monthValue}月${today.dayOfMonth}日 · 本地保存",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(winter) Character(false,Modifier.size(92.dp).clip(CircleShape).border(2.dp,Color.White,CircleShape))
                else Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer,CircleShape),contentAlignment=Alignment.Center) {
                    Text("账",fontSize=28.sp,color=MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        item(key="summary") {
            DashboardCard(Modifier.testTag("home-summary")) {
                Text("本月支出",style=MaterialTheme.typography.titleMedium)
                Text("¥${Rules.money(expense)}",fontSize=34.sp,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("home-expense"))
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {Text("本月收入",style=MaterialTheme.typography.labelMedium);Text("¥${Rules.money(income)}",fontWeight=FontWeight.Bold)}
                    Column(Modifier.weight(1f)) {Text("本月结余",style=MaterialTheme.typography.labelMedium);Text("${if(income<expense) "−" else ""}¥${Rules.money(kotlin.math.abs(income-expense))}",fontWeight=FontWeight.Bold)}
                }
            }
        }
        item(key="budget") {
            DashboardCard(Modifier.testTag("open-budget").clickable(onClick=onBudget)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(if(plan==null || plan.startMonth==plan.endMonth) "本月预算  ›" else "本期预算  ›",fontWeight=FontWeight.Bold)
                        if(plan==null) Text("尚未设置，点击安排预算",style=MaterialTheme.typography.bodySmall)
                        else {
                            val usage=BudgetRules.usage(plan,active,categories).first()
                            if(plan.startMonth!=plan.endMonth) Text("${plan.startMonth} 至 ${plan.endMonth}",style=MaterialTheme.typography.labelSmall)
                            Text("¥${Rules.money(usage.spent)} / ¥${Rules.money(usage.limit)}",fontWeight=FontWeight.Medium)
                            LinearProgressIndicator(progress={(usage.spent.toFloat()/usage.limit).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                            val rest=usage.limit-usage.spent
                            Text("${if(rest>=0) "剩余" else "超出"} ¥${Rules.money(kotlin.math.abs(rest))}",style=MaterialTheme.typography.bodySmall,
                                color=if(rest<0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if(winter) Character(true,Modifier.width(66.dp).height(84.dp).clip(RoundedCornerShape(16.dp)))
                }
                if(alertCount>0) Text("${alertCount}项预算达到提醒阈值",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
                if(plans.any{it.endMonth<month&&!it.renewalDismissed}) Text("预算计划已到期，点击安排下一期",style=MaterialTheme.typography.labelSmall)
            }
        }
        item(key="tools") {
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                DashboardCard(Modifier.weight(1f).testTag("open-calendar").clickable(onClick=onCalendar)) {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {LedgerGlyph("calendar");Text("日历  ›",fontWeight=FontWeight.Bold)}}
                DashboardCard(Modifier.weight(1f).testTag("open-search").clickable(onClick=onSearch)) {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {LedgerGlyph("search");Text("搜索与筛选",fontWeight=FontWeight.Bold)}}
            }
        }
        groups.forEach { (date,rows) ->
            item(key="date-$date") {
                val parsed=LocalDate.parse(date)
                val prefix=when(date){today.toString()->"今天";today.minusDays(1).toString()->"昨天";else->if(parsed.year==today.year) "" else "${parsed.year}年"}
                Row(Modifier.fillMaxWidth().padding(top=14.dp,bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("$prefix${if(prefix=="今天"||prefix=="昨天") " · " else ""}${parsed.monthValue}月${parsed.dayOfMonth}日",modifier=Modifier.weight(1f),fontWeight=FontWeight.Bold)
                    Text("支出 ¥${Rules.money(rows.filter{it.type=="expense"}.sumOf{it.amountFen})}",style=MaterialTheme.typography.labelMedium)
                }
                if(rows.isEmpty()) Text("这一天还没有账目",modifier=Modifier.padding(vertical=16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(rows,key={_,entry->"entry/${entry.id}"}) {index,entry ->
                val name=byCategory[entry.categoryId]?.name?:"分类"
                val tone=when(name){"餐饮"->Color(0xFFFFE5C7);"交通"->Color(0xFFD9EAFE);"日用品"->Color(0xFFD7EEE5);else->MaterialTheme.colorScheme.primaryContainer}
                Surface(Modifier.fillMaxWidth().testTag("entry-${entry.id}").clickable{onEntry(entry)},shape=RoundedCornerShape(topStart=if(index==0) 16.dp else 0.dp,topEnd=if(index==0) 16.dp else 0.dp,bottomStart=if(index==rows.lastIndex) 16.dp else 0.dp,bottomEnd=if(index==rows.lastIndex) 16.dp else 0.dp),color=MaterialTheme.colorScheme.surface) {
                    Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(38.dp).background(tone,CircleShape),contentAlignment=Alignment.Center) {LedgerGlyph(name,color=Color(0xFF243C5A))}
                        Column(Modifier.weight(1f)) {Text(name,fontWeight=FontWeight.Bold);if(entry.note.isNotBlank()) Text(entry.note,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        Text("${if(entry.type=="expense") "−" else "+"}¥${Rules.money(entry.amountFen)}",fontWeight=FontWeight.Bold)
                        Text("›",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    }
}

@Composable private fun DashboardCard(modifier: Modifier=Modifier, content: @Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth().padding(bottom=10.dp),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)
    }
}
