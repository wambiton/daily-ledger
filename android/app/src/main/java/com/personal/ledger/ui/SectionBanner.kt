package com.personal.ledger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.ledger.R

@Composable
internal fun SectionBanner(title: String, subtitle: String, glyph: String) {
    val winter = LocalAppearance.current.selected == LedgerAppearance.WINTER
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxWidth()) {
            if (winter) Image(painterResource(R.drawable.winter_landscape), null,
                Modifier.matchParentSize(), contentScale = ContentScale.Crop, alpha = .35f)
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (winter) Character(true, Modifier.size(64.dp)) else LedgerGlyph(glyph)
            }
        }
    }
}
