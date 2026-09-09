package com.personal.ledger

import android.view.View
import android.widget.DatePicker
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.ledger.data.*
import com.personal.ledger.domain.Rules
import com.personal.ledger.ui.*
import org.hamcrest.Matcher
import org.junit.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ComparisonFlowTest {
    @get:Rule val ui = createComposeRule()
    private var opened: String? = null
    private val wa = Rules.today().minusWeeks(3)
    private val wb = Rules.today().minusWeeks(5)
    private fun e(id: String, date: String, amount: Long, type: String = "expense") = Entry(id,"test",type,"food",amount,date,"",1,1)
    @Before fun start() {
        val fixtures = listOf(e("jan","2025-01-01",1850),e("mar","2025-03-15",1000),
            e("jan-income","2025-01-15",20000,"income"),e("mar-income","2025-03-20",10000,"income"),
            e("wa",wa.toString(),1234),e("wb",wb.toString(),5678))
        ui.setContent { LedgerTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            ComparisonScreen(fixtures,listOf(Category("food","test","expense","餐饮"))) { opened = it.id }
        } } }
    }
    private fun month(side: String, year: Int, month: Int) {
        ui.onNodeWithTag("period-$side").performScrollTo().performClick()
        ui.onNodeWithTag("month-year").performTextReplacement(year.toString())
        ui.onNodeWithTag("month-$month").performClick()
    }
    @Test fun arbitraryMonthsIncomeAndCategoryDetails() {
        ui.onNodeWithText("按月").performScrollTo().performClick()
        month("A",2025,1); month("B",2025,3)
        ui.onNodeWithTag("total-a").assertTextEquals("¥18.50")
        ui.onNodeWithTag("total-b").assertTextEquals("¥10.00")
        ui.onNodeWithText("收入").performScrollTo().performClick()
        ui.onNodeWithTag("total-a").assertTextEquals("¥200.00")
        ui.onNodeWithText("支出").performScrollTo().performClick()
        ui.onNodeWithTag("compare-category-food").performScrollTo().performClick()
        ui.onNodeWithText("2025-01-01 · ¥18.50").performScrollTo().performClick()
        Assert.assertEquals("jan",opened)
        ui.onNodeWithText("按周").performScrollTo().performClick()
        ui.onNodeWithText("按月").performScrollTo().performClick()
        ui.onNodeWithTag("total-a").assertTextEquals("¥18.50")
        month("B",2025,1)
        ui.onNodeWithText("请选择不同的比较周期").assertIsDisplayed()
    }
    private fun week(side: String, date: LocalDate) {
        ui.onNodeWithTag("period-$side").performScrollTo().performClick()
        onView(isAssignableFrom(DatePicker::class.java)).perform(object: ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(DatePicker::class.java)
            override fun getDescription(): String = "Choose an arbitrary week by date"
            override fun perform(controller: UiController, view: View) {
                (view as DatePicker).updateDate(date.year,date.monthValue-1,date.dayOfMonth)
                controller.loopMainThreadUntilIdle()
            }
        })
        onView(withId(android.R.id.button1)).perform(click())
    }
    @Test fun arbitraryWeeksThroughNativePicker() {
        week("A",wa); week("B",wb)
        ui.onNodeWithTag("total-a").assertTextEquals("¥12.34")
        ui.onNodeWithTag("total-b").assertTextEquals("¥56.78")
    }
}
