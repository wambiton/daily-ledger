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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.ledger.R
import com.personal.ledger.data.*
import com.personal.ledger.domain.Rules
import java.time.LocalDate
import java.time.YearMonth

@Composable internal fun Character(chibi: Boolean, modifier: Modifier) {
    Image(painterResource(R.drawable.winter_characters),null,modifier,
        alignment=BiasAlignment(if(chibi) 1f else -1f,0f),contentScale=ContentScale.Crop)
}

@Composable fun HomeDashboard(owner: String, entries: List<Entry>, categories: List<Category>, plans: List<BudgetPlan>,
    alertCount: Int, onBudget: ()->Unit, onCalendar: ()->Unit, onSearch: ()->Unit, onEntry: (Entry)->Unit,
    automaticStatus: (@Composable () -> Unit)? = null) {
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
    if(winter) Image(painterResource(R.drawable.winter_landscape),null,Modifier.matchParentSize(),contentScale=ContentScale.Crop,alpha=.30f)
    LazyColumn(Modifier.fillMaxSize().testTag("home-dashboard"),verticalArrangement=Arrangement.spacedBy(0.dp),contentPadding=PaddingValues(start=12.dp,end=12.dp,top=8.dp,bottom=104.dp)) {
        item(key="header") {
            Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("我的账本",fontSize=24.sp,fontWeight=FontWeight.ExtraBold,fontFamily=if(winter) FontFamily.Serif else FontFamily.Default)
                    Text(if(winter) "雪乃 · 冬日  ❄" else "记录生活 · 心中有数",fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(winter) WinterQuote("认真地对待每一天。")
                    else Text("${today.monthValue}月${today.dayOfMonth}日 · 本地保存",style=MaterialTheme.typography.labelSmall)
                }
                if(winter) {
                    Character(false,Modifier.size(64.dp).clip(CircleShape).border(2.dp,Color.White,CircleShape))

                } else Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer,CircleShape),contentAlignment=Alignment.Center) {
                    Text("账",fontSize=24.sp,color=MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        if(automaticStatus != null) item(key="automatic-status") { automaticStatus() }
        item(key="summary") {
            DashboardCard(Modifier.testTag("home-summary")) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {LedgerGlyph("bars",color=Color(0xFF76ACED));Text("本月支出",style=MaterialTheme.typography.titleMedium)}
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Amount(expense,Modifier.weight(1f).testTag("home-expense"),large=true)
                    if(winter) Image(painterResource(R.drawable.winter_landscape),null,Modifier.width(84.dp).height(48.dp).clip(RoundedCornerShape(12.dp)),contentScale=ContentScale.Crop,alpha=.45f)
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {Text("本月收入",style=MaterialTheme.typography.labelMedium);Amount(income)}
                    Column(Modifier.weight(1f)) {Text("本月结余",style=MaterialTheme.typography.labelMedium);Amount(income-expense)}
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
                            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                Amount(usage.spent,Modifier.weight(1f))
                                Text("/ ¥${displayMoney(usage.limit)}",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                LinearProgressIndicator(progress={(usage.spent.toFloat()/usage.limit).coerceIn(0f,1f)},modifier=Modifier.weight(1f).height(10.dp).clip(CircleShape),color=Color(0xFF579AEC),trackColor=Color(0xFFE3EEFA),drawStopIndicator={})
                                Text(budgetPercent(usage.spent,usage.limit),fontFamily=MoneyFont,fontSize=14.sp,modifier=Modifier.testTag("budget-percent"))
                            }
                            val rest=usage.limit-usage.spent
                            Text("${if(rest>=0) "剩余" else "超出"} ¥${Rules.money(kotlin.math.abs(rest))}",style=MaterialTheme.typography.bodySmall,
                                color=if(rest<0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if(winter) Column(Modifier.width(56.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Character(true,Modifier.width(56.dp).height(68.dp))
                    }
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
                        Text("${if(entry.type=="expense") "−" else "+"}¥${displayMoney(entry.amountFen)}",fontFamily=MoneyFont,fontWeight=FontWeight.Bold,fontSize=17.sp)
                        Text("›",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if(winter) item(key="closing-quote") {WinterQuote("在有些寒冷的日子里，也好好管理生活吧。")}
    }
    }
}

@Composable private fun DashboardCard(modifier: Modifier=Modifier, content: @Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth().padding(bottom=10.dp),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface.copy(alpha=if(LocalAppearance.current.selected==LedgerAppearance.WINTER) .97f else 1f)) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp),content=content)
    }
}

internal val MoneyFont=FontFamily(android.graphics.Typeface.create("sans-serif-condensed",android.graphics.Typeface.BOLD))
private fun displayMoney(fen: Long): String = DecimalFormat("#,##0.00",DecimalFormatSymbols(Locale.CHINA)).format(BigDecimal.valueOf(fen,2))
fun budgetPercent(spent: Long, limit: Long): String = if(limit<=0) "—" else BigDecimal.valueOf(spent).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(limit),0,RoundingMode.HALF_UP).toPlainString()+"%"
@Composable private fun Amount(fen: Long, modifier: Modifier=Modifier, large: Boolean=false) {
    val number=displayMoney(kotlin.math.abs(fen))
    val size=if(large) if(number.length>11) 29.sp else 40.sp else 20.sp
    Text(buildAnnotatedString {
        withStyle(SpanStyle(fontSize=if(large) 23.sp else 16.sp)) {append(if(fen<0) "−¥" else "¥")}
        append(number)
    },modifier,fontFamily=MoneyFont,fontWeight=FontWeight.Black,fontSize=size,letterSpacing=(-.5).sp)
}
@Composable private fun WinterQuote(text: String, small: Boolean=false) {
    Text(text,fontFamily=FontFamily.Serif,fontStyle=FontStyle.Italic,fontSize=if(small) 9.5.sp else 11.sp,lineHeight=17.sp,color=Color(0xFF536F99),letterSpacing=if(small) .3.sp else .7.sp)
}
