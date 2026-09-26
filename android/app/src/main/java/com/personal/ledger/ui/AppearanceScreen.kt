package com.personal.ledger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.ledger.R
import com.personal.ledger.data.*
import com.personal.ledger.domain.Rules
import java.time.YearMonth

@Composable private fun WinterBanner() {
    Image(painterResource(R.drawable.winter_yukino), "雪乃动画风人物与Q版人物的冬日插画",
        modifier=Modifier.fillMaxWidth().aspectRatio(3f).clip(RoundedCornerShape(20.dp)), contentScale=ContentScale.Fit)
}

@Composable fun AppearanceScreen() {
    val appearance=LocalAppearance.current
    Text("选择你喜欢的记账风格",style=MaterialTheme.typography.titleLarge)
    Text("立即应用到整个软件，重启后保留；仅保存在本手机，不随账号备份。")
    LedgerAppearance.entries.forEach { theme ->
        Card(Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(theme==LedgerAppearance.WINTER) WinterBanner()
                Text(theme.title,style=MaterialTheme.typography.titleLarge)
                Text(if(theme==LedgerAppearance.DEFAULT) "原有蓝白界面 · 简洁清爽" else "冰蓝雪景 · 动画风头像与Q版人物 · 清晰账目卡片")
                Button(onClick={appearance.select(theme)},enabled=appearance.selected!=theme,
                    modifier=Modifier.testTag("theme-${theme.name}")) {
                    Text(if(appearance.selected==theme) "正在使用" else "使用此主题")
                }
            }
        }
    }
    Text("人物图片为AI生成的主题插画，并非动画正片截图。",style=MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(24.dp))
}

@Composable fun WinterHome(owner: String, entries: List<Entry>, categories: List<Category>, vm: LedgerViewModel, budget: () -> Unit) {
    val month=YearMonth.from(Rules.today()).toString()
    val current=entries.filter {it.owner==owner && it.deletedAt==null && it.date.startsWith(month)}
    val expense=current.filter {it.type=="expense"}.sumOf {it.amountFen}
    val income=current.filter {it.type=="income"}.sumOf {it.amountFen}
    val plans by vm.budgets.collectAsStateWithLifecycle()
    WinterBanner()
    Text("雪乃 · 冬日",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    Card(Modifier.fillMaxWidth().testTag("winter-summary"),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("${month} · 本月支出",style=MaterialTheme.typography.titleMedium)
            Text("¥${Rules.money(expense)}",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            HorizontalDivider()
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {Text("本月收入");Text("¥${Rules.money(income)}",fontWeight=FontWeight.Bold)}
                Column(Modifier.weight(1f)) {Text("本月结余");Text("¥${Rules.money(income-expense)}",fontWeight=FontWeight.Bold)}
            }
        }
    }
    val plan=plans.firstOrNull {it.owner==owner && it.startMonth<=month && it.endMonth>=month}
    if(plan!=null) {
        val total=BudgetRules.usage(plan,entries,categories).first()
        Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(plan.name,style=MaterialTheme.typography.titleMedium)
                Text("${plan.startMonth} 至 ${plan.endMonth} · 整期预算")
                Text("已用 ¥${Rules.money(total.spent)} / ¥${Rules.money(total.limit)}")
                LinearProgressIndicator(progress={(total.spent.toFloat()/total.limit).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                val remaining=total.limit-total.spent
                Text(if(remaining>=0) "剩余 ¥${Rules.money(remaining)}" else "超出 ¥${Rules.money(-remaining)}",
                    color=if(remaining<0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                TextButton(onClick=budget) {Text("查看计划与分类预算")}
            }
        }
    }
}
