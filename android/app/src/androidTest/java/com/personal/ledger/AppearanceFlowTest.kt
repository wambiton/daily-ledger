package com.personal.ledger

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.ledger.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppearanceFlowTest {
    @get:Rule val ui=createComposeRule()
    @Test fun selectionSurvivesThemeRecreationAndCanReturnToDefault() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="appearance-test-${UUID.randomUUID()}"
        val prefs=context.getSharedPreferences(name,Context.MODE_PRIVATE)
        var generation by mutableIntStateOf(0)
        ui.setContent {key(generation) {
            LedgerTheme(preferences=context.getSharedPreferences(name,Context.MODE_PRIVATE)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {AppearanceScreen()}
            }
        }}
        ui.onNodeWithTag("theme-DEFAULT").assertIsNotEnabled()
        ui.onNodeWithTag("theme-WINTER").performScrollTo().performClick()
        ui.onNodeWithTag("theme-WINTER").assertIsNotEnabled()
        ui.runOnIdle { assertEquals(LedgerAppearance.WINTER,readAppearance(prefs));generation++ }
        ui.onNodeWithTag("theme-WINTER").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("theme-DEFAULT").performScrollTo().performClick()
        ui.runOnIdle {assertEquals(LedgerAppearance.DEFAULT,readAppearance(prefs));generation++}
        ui.onNodeWithTag("theme-DEFAULT").assertIsNotEnabled()
        // Future/unknown settings fall back without touching a ledger or removing preferences.
        ui.runOnIdle {prefs.edit().putString("theme","future-theme").commit();generation++}
        ui.onNodeWithTag("theme-DEFAULT").assertIsNotEnabled()
    }
}
