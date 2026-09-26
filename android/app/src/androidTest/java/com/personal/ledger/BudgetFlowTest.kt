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
        ui.onNodeWithTag("home-dashboard").performScrollToNode(hasTestTag("open-budget"))
        ui.onNodeWithTag("open-budget").performClick()
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
        val app=ApplicationProvider.getApplicationContext<Application>()
        if(BudgetNotifications.allowed(app)) {
            ui.waitUntil(10000) { app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any {
                it.tag?.contains(plan.id)==true && it.notification.channelId=="ledger-budget"
            } }
        }
    }
    @Test fun invalidBudgetAllocationIsRejectedWithoutChangingSavedPlan() {
        val cat=vm.categories.value.first{it.type=="expense"}
        ui.onNodeWithTag("home-dashboard").performScrollToNode(hasTestTag("open-budget"))
        ui.onNodeWithTag("open-budget").performClick()
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
    @Test fun shorteningPlanRequiresExplicitRemovalOfOutsideAllocation() {
        ui.onNodeWithTag("home-dashboard").performScrollToNode(hasTestTag("open-budget"))
        ui.onNodeWithTag("open-budget").performClick()
        val cat=vm.categories.value.first{it.type=="expense"}
        val plan=BudgetPlan(UUID.randomUUID().toString(),vm.owner,"未来安排","2027-01","2027-02",10000,
            BudgetRules.encode(mapOf("2027-01" to mapOf(cat.id to 5000L),"2027-02" to mapOf(cat.id to 5000L))))
        ui.runOnIdle {vm.saveBudget(plan)}
        ui.waitUntil(10000){vm.budgets.value.size==1 && !vm.busy.value}
        ui.runOnIdle {vm.editBudget(plan)}
        ui.onNodeWithTag("budget-end").performTextReplacement("2027-01")
        ui.onNodeWithTag("budget-editor").performScrollToNode(hasText("移除范围外的分配"))
        ui.onNodeWithText("移除范围外的分配").performClick()
        ui.onNodeWithText("保留").performClick()
        Assert.assertEquals(plan,vm.budgets.value.single())
        ui.onNodeWithText("移除范围外的分配").performClick()
        ui.onNodeWithText("确认移除").performClick()
        ui.onNodeWithTag("budget-editor").performScrollToNode(hasTestTag("save-budget"))
        ui.onNodeWithTag("save-budget").performClick()
        ui.waitUntil(10000){vm.budgets.value.single().endMonth=="2027-01" && !vm.busy.value}
        Assert.assertEquals(setOf("2027-01"),BudgetRules.allocations(vm.budgets.value.single().allocations).keys)
    }

}
