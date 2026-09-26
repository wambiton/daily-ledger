package com.personal.ledger.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.content.SharedPreferences
import android.content.Context
import androidx.compose.ui.platform.LocalContext
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
enum class LedgerAppearance(val title: String) { DEFAULT("默认主题"), WINTER("雪乃·冬日") }
data class AppearanceState(val selected: LedgerAppearance, val select: (LedgerAppearance) -> Unit)
val LocalAppearance = staticCompositionLocalOf { AppearanceState(LedgerAppearance.DEFAULT, {}) }
private val WinterColors = LedgerColors.copy(
    primary=Color(0xFF316AB6), onPrimary=Color.White,
    primaryContainer=Color(0xFFDCEBFC), onPrimaryContainer=Color(0xFF1C416F),
    secondaryContainer=Color(0xFFDCEBFC), onSecondaryContainer=Color(0xFF1C416F),
    background=Color(0xFFEDF4FB), surface=Color(0xFFFCFDFF),
    surfaceContainer=Color(0xFFF5F9FF), onBackground=Color(0xFF203655),
    onSurface=Color(0xFF203655), onSurfaceVariant=Color(0xFF536783),
    outline=Color(0xFF71839B), outlineVariant=Color(0xFFD6E3F2)
)
fun readAppearance(prefs: SharedPreferences) = LedgerAppearance.entries.firstOrNull {
    it.name == prefs.getString("theme", null)
} ?: LedgerAppearance.DEFAULT
@Composable fun LedgerTheme(
    preferences: SharedPreferences = LocalContext.current.getSharedPreferences("appearance", Context.MODE_PRIVATE),
    content: @Composable () -> Unit
) {
    var selected by remember(preferences) { mutableStateOf(readAppearance(preferences)) }
    DisposableEffect(preferences) {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if(key=="theme") selected=readAppearance(preferences)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        selected=readAppearance(preferences)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    CompositionLocalProvider(LocalAppearance provides AppearanceState(selected) {
        preferences.edit().putString("theme",it.name).apply()
        selected=it
    }) {
        MaterialTheme(colorScheme=if(selected==LedgerAppearance.WINTER) WinterColors else LedgerColors,content=content)
    }
}
