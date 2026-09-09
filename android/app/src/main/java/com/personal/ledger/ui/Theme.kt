package com.personal.ledger.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LedgerColors = lightColorScheme(
    primary = Color(0xFF3563D9), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EEFC), onPrimaryContainer = Color(0xFF244BAA),
    secondaryContainer = Color(0xFFE8EEFC), onSecondaryContainer = Color(0xFF244BAA),
    surfaceContainer = Color.White,
    background = Color(0xFFF6F7F9), surface = Color.White,
    onBackground = Color(0xFF1F2937), onSurface = Color(0xFF1F2937),
    onSurfaceVariant = Color(0xFF667085), outlineVariant = Color(0xFFE5E7EB),
)
@Composable fun LedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LedgerColors, content = content)
}
