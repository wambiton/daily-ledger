package com.personal.ledger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.ledger.data.Category
import com.personal.ledger.data.Entry
import com.personal.ledger.ui.LedgerTheme
import com.personal.ledger.ui.SearchScreen
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntrySearchFlowTest {
    @get:Rule val ui=createComposeRule()
    @Test fun filtersSummaryCategoriesEmptyAndResetWithoutChangingRecords() {
        val food=Category("food","a","expense","餐饮")
        val salary=Category("salary","a","income","工资")
        val lunch=Entry("1","a","expense","food",1234,"2026-09-01","午餐微信",1,1)
        val rows=listOf(lunch,lunch.copy(id="2",type="income",categoryId="salary",amountFen=50000,note="工资"),lunch.copy(id="other",owner="b"),lunch.copy(id="trash",deletedAt=5))
        var selected=""
        ui.setContent { LedgerTheme { SearchScreen(rows,listOf(food,salary),"a"){selected=it.id} } }
        ui.onNodeWithText("共 2 笔").assertExists()
        ui.onNodeWithText("结余 ¥487.66").assertExists()
        ui.onNodeWithTag("search-keyword").performTextInput("午餐")
        ui.onNodeWithText("共 1 笔").assertExists()
        ui.onNodeWithTag("search-list").performScrollToNode(hasTestTag("search-entry-1"))
        ui.onNodeWithTag("search-entry-1").performClick()
        Assert.assertEquals("1",selected)
        ui.onNodeWithTag("search-list").performScrollToIndex(0)
        ui.onNodeWithTag("search-keyword").performTextReplacement("找不到")
        ui.onNodeWithText("没有符合条件的账目").assertExists()
        ui.onNodeWithTag("search-reset").performClick()
        ui.onNodeWithTag("search-type-income").performClick()
        ui.onNodeWithText("分类：全部分类").performClick()
        ui.onNodeWithText("收入 · 工资").performClick()
        ui.onNodeWithText("共 1 笔").assertExists()
        ui.onNodeWithText("结余 ¥500.00").assertExists()
        ui.onNodeWithTag("search-reset").performClick()
        ui.onNodeWithText("共 2 笔").assertExists()
        Assert.assertEquals(4,rows.size)
    }
}
