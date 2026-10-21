package com.personal.ledger

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.ledger.data.*
import com.personal.ledger.domain.Rules
import com.personal.ledger.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in documentation capture: real composables, synthetic data, no account host. */
@RunWith(AndroidJUnit4::class)
class ShowcaseCaptureTest {
    @get:Rule val ui = createComposeRule()

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalTestApi::class)
    @Test fun captureSyntheticGallery() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("gallery") == "true")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "showcase-${UUID.randomUUID()}.db"
        val prefs = app.getSharedPreferences("$dbName-appearance", 0)
        val owner = "showcase"
        val today = Rules.today()
        val month = today.toString().take(7)
        val cats = listOf(Category("demo-food", owner, "expense", "餐饮"),
            Category("demo-transport", owner, "expense", "交通"),
            Category("demo-daily", owner, "expense", "日用品"),
            Category("demo-income", owner, "income", "生活费"))
        fun row(id: String, cat: Int, amount: Long, ago: Long, note: String) = Entry(
            "demo-$id", owner, cats[cat].type, cats[cat].id, amount,
            today.minusDays(ago).toString(), note, System.currentTimeMillis()-ago*86400000, 1)
        val rows = listOf(row("lunch",0,2800,0,"午餐 · 微信"),row("metro",1,600,0,"地铁 · 支付宝"),
            row("daily",2,2200,1,"生活用品 · 微信"),row("dinner",0,3200,2,"晚餐 · 支付宝"),
            row("food-last",0,6800,7,"周末聚餐"),row("transport-last",1,1800,8,"出行"),
            row("income",3,200000,0,"模拟生活费"))
        val plan = BudgetPlan("demo-budget",owner,"日常生活预算",month,month,200000,
            BudgetRules.encode(mapOf(month to mapOf(cats[0].id to 100000L,cats[1].id to 20000L,cats[2].id to 30000L))))
        val db = LedgerDatabases.acquire(app,dbName)
        runBlocking {
            cats.forEach { db.ledger().seed(it) }
            rows.forEach { db.ledger().upsertEntry(it) }
            db.ledger().putBudget(plan)
        }
        val store = ViewModelStore()
        val vm = LedgerViewModel(app,dbName,owner)
        store.put("showcase",vm)
        var page by mutableIntStateOf(0)
        try {
            ui.setContent { LedgerTheme(preferences=prefs) {
                Scaffold(containerColor=MaterialTheme.colorScheme.background,
                    topBar={ if(page!=0) TopAppBar(title={Text(if(page==1) "比较" else "预算计划")}) }) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        when(page) {
                            0 -> HomeDashboard(owner,rows,cats,listOf(plan),0,{},{},{},{})
                            1 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal=20.dp)) {
                                ComparisonScreen(rows,cats) {}
                            }
                            else -> Box(Modifier.padding(horizontal=20.dp)) { BudgetScreen(vm,false) }
                        }
                    }
                }
            } }
            ui.waitUntil(15000) {vm.ready.value}
            val monthlySpent = rows.filter {it.type=="expense" && it.date.startsWith(month)}.sumOf {it.amountFen}
            ui.onNodeWithTag("home-expense").assertTextEquals("¥${Rules.money(monthlySpent)}")
            fun capture(name: String) {
                ui.waitForIdle()
                val image=ui.onRoot().captureToImage().asAndroidBitmap()
                val target=File(app.cacheDir,"github-gallery/$name.png")
                target.parentFile!!.mkdirs()
                target.outputStream().use { Assert.assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
            }
            capture("home-default")
            ui.runOnIdle {prefs.edit().putString("theme","WINTER").commit()}
            ui.onNodeWithTag("budget-percent",useUnmergedTree=true).assertTextEquals(budgetPercent(monthlySpent,plan.totalFen))
            capture("home-winter")
            ui.runOnIdle {page=1}
            ui.onNodeWithTag("total-a").assertExists()
            capture("comparison")
            ui.runOnIdle {page=2}
            ui.waitUntil(15000) {vm.budgets.value.isNotEmpty() && vm.entries.value.size==rows.size}
            ui.onNodeWithTag("budget-list").assertExists()
            capture("budget")
        } finally {
            store.clear()
            val manager=androidx.work.WorkManager.getInstance(app)
            for(name in listOf("budget/$dbName/$owner","trash-$dbName-$owner"))
                manager.cancelUniqueWork(name).result.get(20,TimeUnit.SECONDS)
            LedgerDatabases.release(dbName)
            // Retain the isolated fixture for inspection. Never delete a real ledger.
        }
    }
}
