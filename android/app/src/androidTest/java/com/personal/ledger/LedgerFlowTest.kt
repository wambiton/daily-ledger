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
        ui.onNodeWithText("我的").performClick()
        ui.onNodeWithText("回收站").performScrollTo().performClick()
        ui.onNodeWithText("expense-test").assertExists()
        ui.onNodeWithText("恢复账目").performScrollTo().performClick()
        ui.waitUntil(10000) { vm.entries.value.any { it.note == "expense-test" && it.amountFen == 2000L } }
        ui.onNodeWithText("回收站为空").assertExists()
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

    @Test fun fileRangeExportAndDuplicateImportPreview() {
        val day = Rules.today().toString()
        val previous = Rules.today().minusDays(1).toString()
        val c = vm.categories.value.first()
        ui.runOnIdle { vm.save(null,c.type,c.id,"12.34",day,"file-today") {} }
        ui.waitUntil(10000) { !vm.busy.value && vm.entries.value.size == 1 }
        ui.runOnIdle { vm.save(null,c.type,c.id,"2",previous,"file-previous") {} }
        ui.waitUntil(10000) { !vm.busy.value && vm.entries.value.size == 2 }
        val output = java.io.File(app.cacheDir,"export-${UUID.randomUUID()}.json")
        try {
            var prepared = false
            ui.runOnIdle { vm.prepareExport(false,day,day) { prepared = true } }
            ui.waitUntil(10000) { prepared && !vm.busy.value }
            ui.runOnIdle { vm.writeExport(android.net.Uri.fromFile(output)) }
            ui.waitUntil(10000) { !vm.busy.value && vm.fileMessage.value != null }
            val payload = org.json.JSONObject(output.readText())
            Assert.assertEquals(1,payload.getJSONArray("entries").length())
            ui.onNodeWithText("我的").performClick()
            ui.onNodeWithText("文件导入导出").performScrollTo().performClick()
            ui.runOnIdle { vm.readImport(android.net.Uri.fromFile(output)) }
            ui.waitUntil(10000) { vm.importPreview.value != null }
            ui.onNodeWithText("跳过重复并导入").performClick()
            ui.waitUntil(10000) { !vm.busy.value && vm.importPreview.value == null }
            Assert.assertEquals(2,vm.entries.value.size)
            ui.runOnIdle { vm.readImport(android.net.Uri.fromFile(output)) }
            ui.waitUntil(10000) { vm.importPreview.value != null }
            ui.onNodeWithText("仍然导入重复项").performClick()
            ui.waitUntil(10000) { !vm.busy.value && vm.entries.value.size == 3 }
            Assert.assertEquals(2668L,vm.entries.value.sumOf {it.amountFen})
        } finally { output.delete() }
    }

    @Test fun duplicateCategoryErrorRemainsVisibleInDialog() {
        ui.onNodeWithTag("new-entry").performClick()
        ui.onNodeWithText("＋ 新建分类").performClick()
        ui.onNodeWithText("分类名称").performTextInput("餐饮")
        ui.onNodeWithText("添加").performClick()
        ui.waitUntil(10000) { !vm.busy.value }
        ui.onNodeWithText("此分类已存在，请换一个名称").assertIsDisplayed()
    }

    @Test fun selectingHistoricalDateRevealsItsBillsAndCanReturnToToday() {
        val past = YearMonth.from(Rules.today()).minusMonths(1).atDay(15)
        ui.waitUntil(10000) { vm.categories.value.isNotEmpty() }
        ui.runOnIdle {
            vm.save(null, "expense", vm.categories.value.first { it.type == "expense" }.id,
                "23.45", past.toString(), "history-visible-test") { }
        }
        ui.waitUntil(10000) { vm.entries.value.any { it.note == "history-visible-test" } }
        ui.onNodeWithTag("previous-month").performScrollTo().performClick()
        ui.onNodeWithTag("date-$past").performScrollTo().performClick()
        ui.waitForIdle()
        // No performScrollTo: selecting a date must reveal its result automatically.
        ui.onNodeWithText("history-visible-test").assertIsDisplayed().performClick()
        ui.onNodeWithText("日期：$past").assertIsDisplayed()
        ui.onNodeWithText("−¥23.45").assertIsDisplayed()
        ui.onNodeWithText("返回").performClick()
        ui.onNodeWithText("回到今天").performScrollTo().performClick()
        ui.waitForIdle()
        ui.onNodeWithText("今天 · ${Rules.today().monthValue}月${Rules.today().dayOfMonth}日").assertIsDisplayed()
        ui.onNodeWithText("history-visible-test").assertDoesNotExist()
    }
}
