package com.personal.ledger

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.ledger.domain.Rules
import com.personal.ledger.ui.*
import org.junit.*
import org.junit.runner.RunWith
import java.time.YearMonth
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerFlowTest {
    @get:Rule val ui = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val dbName = "ui-test-${UUID.randomUUID()}.db"
    private val store = ViewModelStore()
    private lateinit var vm: LedgerViewModel
    @Before fun start() {
        vm = LedgerViewModel(app, dbName)
        store.put("test", vm)
        ui.setContent { LedgerTheme { LedgerApp(vm) } }
        ui.waitUntil(10000) { vm.ready.value }
        ui.waitForIdle()
    }
    @After fun cleanup() { store.clear(); app.deleteDatabase(dbName) }

    @Test fun expenseCreateEditAndDelete() {
        ui.onNodeWithTag("new-entry").performClick()
        ui.onNodeWithTag("amount").assertDoesNotExist()
        ui.onNodeWithText("餐饮").performClick()
        ui.onNodeWithTag("amount").performTextInput("18.50")
        ui.onNodeWithTag("note").performTextInput("expense-test")
        ui.onNodeWithTag("save-entry").performScrollTo().performClick()
        ui.waitUntil(10000) { vm.entries.value.any { it.note == "expense-test" } }
        ui.onNodeWithText("expense-test").performScrollTo().performClick()
        ui.onNodeWithText("−¥18.50").assertIsDisplayed()
        ui.onNodeWithText("编辑").performClick()
        ui.onNodeWithTag("amount").performTextReplacement("20")
        ui.onNodeWithTag("save-entry").performScrollTo().performClick()
        ui.waitUntil(10000) { vm.entries.value.any { it.amountFen == 2000L } }
        ui.onNodeWithText("expense-test").performScrollTo().performClick()
        ui.onNodeWithText("删除这笔账目").performClick()
        ui.onNodeWithText("删除", useUnmergedTree = true).performClick()
        ui.waitUntil(10000) { vm.entries.value.isEmpty() }
    }

    @Test fun backdateIncomeWithCustomCategoryAndEmptyNote() {
        val yesterday = Rules.today().minusDays(1)
        if(YearMonth.from(yesterday) != YearMonth.from(Rules.today())) ui.onNodeWithTag("previous-month").performScrollTo().performClick()
        ui.onNodeWithTag("date-$yesterday").performScrollTo().performClick()
        ui.onNodeWithText("补记这一天").performScrollTo().performClick()
        ui.onNodeWithText("收入").performClick()
        ui.onNodeWithText("＋ 新建分类").performClick()
        ui.onNodeWithText("分类名称").performTextInput("补记收入")
        ui.onNodeWithText("添加").performClick()
        ui.waitUntil(10000) { vm.categories.value.any { it.name == "补记收入" } }
        ui.onNodeWithTag("amount").performTextInput("12.34")
        ui.onNodeWithText("日期：$yesterday").assertExists()
        ui.onNodeWithTag("save-entry").performScrollTo().performClick()
        ui.waitUntil(10000) { vm.entries.value.isNotEmpty() }
        val entry = vm.entries.value.single()
        Assert.assertEquals("income", entry.type)
        Assert.assertEquals(yesterday.toString(), entry.date)
        Assert.assertEquals(1234L, entry.amountFen)
        Assert.assertEquals("", entry.note)
    }

    @Test fun duplicateCategoryErrorRemainsVisibleInDialog() {
        ui.onNodeWithTag("new-entry").performClick()
        ui.onNodeWithText("＋ 新建分类").performClick()
        ui.onNodeWithText("分类名称").performTextInput("餐饮")
        ui.onNodeWithText("添加").performClick()
        ui.waitUntil(10000) { !vm.busy.value }
        ui.onNodeWithText("此分类已存在，请换一个名称").assertIsDisplayed()
    }
}
