package com.personal.ledger

import com.personal.ledger.data.Entry
import com.personal.ledger.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ComparisonTest {
    @Test fun manyMonthsKeepSelectionOrderAndCategoryUnion() {
        val periods = listOf(9, 1, 3, 6).map { Comparison.month(YearMonth.of(2026,it),today) }
        val entries = listOf(e("2026-01-02","food",101), e("2026-03-31","bus",202),
            e("2026-09-09","food",303), e("2026-09-10","food",999),
            e("2026-06-01","food",777).copy(deletedAt=1), e("2026-03-02","food",404,"income"))
        val result = Comparison.calculateMany(entries,"expense",periods)
        assertEquals(listOf(303L,101L,202L,0L),result.totals)
        assertEquals(listOf(303L,101L,0L,0L),result.categories.first { it.id == "food" }.totals)
        assertEquals(listOf(0L,0L,202L,0L),result.categories.first { it.id == "bus" }.totals)
        assertEquals(listOf(0L,0L,404L,0L),Comparison.calculateMany(entries,"income",periods).totals)
        assertTrue(runCatching { Comparison.calculateMany(entries,"expense",listOf(periods[0])) }.isFailure)
        assertTrue(runCatching { Comparison.calculateMany(entries,"expense",periods + periods[0]) }.isFailure)
    }
    @Test fun manyWeeksRespectMondayBoundaries() {
        val periods = listOf("2026-09-09","2026-08-31","2026-08-17").map { Comparison.week(LocalDate.parse(it),today) }
        val entries = listOf(e("2026-09-06","x",100),e("2026-09-07","x",200),e("2026-08-23","x",300),e("2026-08-24","x",999))
        assertEquals(listOf(200L,100L,300L),Comparison.calculateMany(entries,"expense",periods).totals)
    }
    private val today = LocalDate.of(2026, 9, 9)
    private fun e(date: String, category: String, amount: Long, type: String = "expense") = Entry("$date-$category-$type", "test", type, category, amount, date, "", 1, 1)
    @Test fun fullWeeksCrossMonthAndYear() {
        val p = Comparison.week(LocalDate.of(2026,9,1),today)
        assertEquals(LocalDate.of(2026,8,31), p.start)
        assertEquals(LocalDate.of(2026,9,7), p.endExclusive)
        assertFalse(p.partial)
        val year = Comparison.week(LocalDate.of(2026,1,1),today)
        assertEquals(LocalDate.of(2025,12,29),year.start)
        assertEquals(LocalDate.of(2026,1,5),year.endExclusive)
    }
    @Test fun arbitraryMonthsAndUnionOfCategories() {
        val a = Comparison.month(YearMonth.of(2026,1),today)
        val b = Comparison.month(YearMonth.of(2026,3),today)
        val result = Comparison.calculate(listOf(e("2026-01-01","food",100),e("2026-01-31","food",200),
            e("2026-02-01","food",9999),e("2026-03-31","bus",50),e("2026-04-01","bus",9999),e("2026-01-02","food",9999,"income")),"expense",a,b)
        assertEquals(300L,result.a); assertEquals(50L,result.b)
        assertEquals(250L,result.delta); assertEquals("500.0",result.percent)
        assertEquals(listOf(CategoryDifference("food",300,0),CategoryDifference("bus",0,50)),result.categories)
    }
    @Test fun currentPeriodStopsTodayAndHistoryStaysWhole() {
        val a = Comparison.month(YearMonth.of(2026,9),today)
        val b = Comparison.month(YearMonth.of(2026,8),today)
        assertTrue(a.partial); assertFalse(b.partial)
        val result = Comparison.calculate(listOf(e("2026-09-09","x",1,"income"),e("2026-09-10","x",999,"income"),e("2026-08-31","x",2,"income")),"income",a,b)
        assertEquals(1L,result.a); assertEquals(2L,result.b)
        assertEquals("-50.0",result.percent)
        assertEquals(LocalDate.of(2026,9,10),Comparison.week(today,today).endExclusive)
    }
    @Test fun leapMonthZerosDeletedAndInvalidPeriods() {
        val leap = Comparison.month(YearMonth.of(2024,2),today)
        assertTrue(leap.contains("2024-02-29")); assertFalse(leap.contains("2024-03-01"))
        val b = Comparison.month(YearMonth.of(2024,1),today)
        val result = Comparison.calculate(listOf(e("2024-02-29","x",10).copy(deletedAt=1)),"expense",leap,b)
        assertEquals(0L,result.a); assertNull(result.percent)
        assertTrue(result.categories.isEmpty())
        assertNull(ComparisonResult(100,0,emptyList()).percent)
        assertTrue(runCatching { Comparison.calculate(emptyList(),"expense",leap,leap) }.isFailure)
        assertTrue(runCatching { Comparison.month(YearMonth.of(2026,10),today) }.isFailure)
    }
}
