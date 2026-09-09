package com.personal.ledger.domain

import com.personal.ledger.data.Entry
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

data class LedgerPeriod(val start: LocalDate, val endExclusive: LocalDate, val fullEndExclusive: LocalDate) {
    val partial: Boolean get() = endExclusive < fullEndExclusive
    val label: String get() = "$start — ${fullEndExclusive.minusDays(1)}"
    val actualLabel: String get() = if(partial) "截至 ${endExclusive.minusDays(1)}" else "完整周期"
    fun contains(date: String): Boolean = date >= start.toString() && date < endExclusive.toString()
}

data class CategoryDifference(val id: String, val a: Long, val b: Long) {
    val delta: Long get() = a - b
}
data class ComparisonResult(val a: Long, val b: Long, val categories: List<CategoryDifference>) {
    val delta: Long get() = a - b
    val percent: String? get() = if(b == 0L) null else BigDecimal.valueOf(delta).multiply(BigDecimal(100))
        .divide(BigDecimal.valueOf(b), 1, RoundingMode.HALF_UP).toPlainString()
}

object Comparison {
    fun week(day: LocalDate, today: LocalDate = Rules.today()): LedgerPeriod {
        require(day <= today) { "不能选择未来日期" }
        val start = day.minusDays((day.dayOfWeek.value - 1).toLong())
        val end = start.plusDays(7)
        return LedgerPeriod(start, minOf(end, today.plusDays(1)), end)
    }
    fun month(month: YearMonth, today: LocalDate = Rules.today()): LedgerPeriod {
        require(month <= YearMonth.from(today)) { "不能选择未来月份" }
        val end = month.plusMonths(1).atDay(1)
        return LedgerPeriod(month.atDay(1), minOf(end, today.plusDays(1)), end)
    }
    fun calculate(entries: List<Entry>, type: String, a: LedgerPeriod, b: LedgerPeriod): ComparisonResult {
        require(type in listOf("expense", "income"))
        require(a.start != b.start || a.fullEndExclusive != b.fullEndExclusive) { "请选择不同的比较周期" }
        val aTotals = linkedMapOf<String, Long>()
        val bTotals = linkedMapOf<String, Long>()
        entries.filter { it.type == type && it.deletedAt == null }.forEach { entry ->
            if(a.contains(entry.date)) aTotals[entry.categoryId] = Math.addExact(aTotals[entry.categoryId] ?: 0, entry.amountFen)
            if(b.contains(entry.date)) bTotals[entry.categoryId] = Math.addExact(bTotals[entry.categoryId] ?: 0, entry.amountFen)
        }
        val categories = (aTotals.keys + bTotals.keys).map { CategoryDifference(it, aTotals[it] ?: 0, bTotals[it] ?: 0) }
            .sortedWith(compareByDescending<CategoryDifference> { Math.addExact(it.a, it.b) }.thenBy { it.id })
        return ComparisonResult(aTotals.values.fold(0L, Math::addExact), bTotals.values.fold(0L, Math::addExact), categories)
    }
}
