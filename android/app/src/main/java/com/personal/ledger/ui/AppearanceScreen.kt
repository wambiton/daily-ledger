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
                Text(if(theme==LedgerAppearance.DEFAULT) "蓝白配色 · 统一卡片排版" else "冰蓝雪景 · 动画风头像与Q版人物 · 清晰账目卡片")
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
