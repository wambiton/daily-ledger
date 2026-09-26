package com.personal.ledger

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.ledger.data.*
import com.personal.ledger.domain.Rules
import com.personal.ledger.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HomeDashboardTest {
    @get:Rule val ui=createComposeRule()
    @Test fun bothThemesShowCurrentBookOnlyAndKeepNavigation() {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val prefs=app.getSharedPreferences("dashboard-test-${UUID.randomUUID()}",Context.MODE_PRIVATE)
        val today=Rules.today().toString()
        val c=Category("food","a","expense","餐饮")
        val entry=Entry("a","a","expense",c.id,2800,today,"午餐",0,0)
        val rows=listOf(entry,entry.copy(id="b",owner="b",amountFen=99999),entry.copy(id="trash",deletedAt=1,amountFen=11111))
        var destination=""
        ui.setContent {LedgerTheme(preferences=prefs) {
            HomeDashboard("a",rows,listOf(c),emptyList(),0,{destination="budget"},{destination="calendar"},{destination="search"},{destination=it.id})
        }}
        for(theme in listOf("DEFAULT","WINTER")) {
            ui.runOnIdle {prefs.edit().putString("theme",theme).commit()}
            ui.onNodeWithTag("home-dashboard").performScrollToNode(hasTestTag("home-expense"))
            ui.onNodeWithTag("home-expense").assertTextEquals("¥28.00")
            for((tag,target) in listOf("open-budget" to "budget","open-calendar" to "calendar","open-search" to "search","entry-a" to "a")) {
                ui.onNodeWithTag("home-dashboard").performScrollToNode(hasTestTag(tag))
                ui.onNodeWithTag(tag).performClick()
                ui.runOnIdle {assertEquals(target,destination)}
            }
            ui.onNodeWithTag("entry-b").assertDoesNotExist()
            ui.onNodeWithTag("entry-trash").assertDoesNotExist()
        }
    }
}
