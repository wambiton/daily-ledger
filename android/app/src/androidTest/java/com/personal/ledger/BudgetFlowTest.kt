package com.personal.ledger

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.ledger.ui.*
import com.personal.ledger.data.*
import com.personal.ledger.domain.Rules
import org.junit.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BudgetFlowTest {
    @get:Rule val ui=createComposeRule()
    private val store=ViewModelStore()
    private lateinit var vm: LedgerViewModel
    @Before fun setup() {
        vm=LedgerViewModel(ApplicationProvider.getApplicationContext<Application>(),"budget-ui-${UUID.randomUUID()}.db")
        store.put("vm",vm)
        ui.setContent {LedgerTheme {LedgerApp(vm)}}
        ui.waitUntil(10000){vm.ready.value && vm.categories.value.isNotEmpty()}
    }
    @After fun close() {store.clear()}
    @Test fun createBudgetThroughEditorThenShowThresholdAndRearm() {
        ui.onNodeWithTag("open-budget").performScrollTo().performClick()
        ui.onNodeWithTag("new-budget").performClick()
        ui.onNodeWithTag("budget-name").performTextInput("本月生活费")
        ui.onNodeWithTag("budget-total").performTextInput("100")
        ui.onNodeWithTag("budget-editor").performScrollToNode(hasTestTag("save-budget"))
        ui.onNodeWithTag("save-budget").performClick()
        ui.waitUntil(10000){vm.budgets.value.size==1 && !vm.busy.value}
        val plan=vm.budgets.value.single()
        val c=vm.categories.value.first{it.type=="expense"}
        ui.runOnIdle {vm.save(null,"expense",c.id,"110",Rules.today().toString(),"预算测试") {}}
        ui.waitUntil(10000){vm.budgetNotices.value.singleOrNull()?.level==100}
        ui.onNodeWithTag("budget-list").performScrollToNode(hasTestTag("budget-notice"))
        ui.onNodeWithTag("budget-notice").assertTextContains("已达到100%",substring=true)
        val id=vm.entries.value.single().id
        ui.runOnIdle {vm.delete(id){}}
        ui.waitUntil(10000){vm.budgetNotices.value.isEmpty() && !vm.busy.value}
        ui.runOnIdle {vm.restoreDeleted(id)}
        ui.waitUntil(10000){vm.budgetNotices.value.singleOrNull()?.level==100}
        Assert.assertEquals(plan,vm.budgets.value.single())
    }
    @Test fun invalidBudgetAllocationIsRejectedWithoutChangingSavedPlan() {
        val cat=vm.categories.value.first{it.type=="expense"}
        ui.onNodeWithTag("open-budget").performScrollTo().performClick()
        ui.onNodeWithTag("new-budget").performClick()
        ui.onNodeWithTag("budget-name").performTextInput("提前计划")
        ui.onNodeWithTag("budget-total").performTextInput("100")
        ui.onNodeWithTag("budget-editor").performScrollToNode(hasTestTag("budget-category-${cat.id}"))
        ui.onNodeWithTag("budget-category-${cat.id}").performTextInput("101")
        ui.onNodeWithTag("budget-editor").performScrollToNode(hasTestTag("save-budget"))
        ui.onNodeWithTag("save-budget").performClick()
        ui.onNodeWithTag("budget-error").assertTextContains("不能超过总预算",substring=true)
        Assert.assertTrue(vm.budgets.value.isEmpty())
        ui.onNodeWithTag("budget-editor").performScrollToNode(hasTestTag("budget-category-${cat.id}"))
        ui.onNodeWithTag("budget-category-${cat.id}").performTextReplacement("80")
        ui.onNodeWithTag("budget-editor").performScrollToNode(hasTestTag("save-budget"))
        ui.onNodeWithTag("save-budget").performClick()
        ui.waitUntil(10000){vm.budgets.value.size==1}
        Assert.assertEquals(8000L,BudgetRules.allocations(vm.budgets.value.single().allocations).values.single().getValue(cat.id))
    }
}
