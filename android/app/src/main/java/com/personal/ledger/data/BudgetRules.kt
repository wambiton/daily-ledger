package com.personal.ledger.data

import org.json.JSONObject
import java.time.YearMonth
import java.time.temporal.ChronoUnit

object BudgetRules {
    fun month(value: String): YearMonth = YearMonth.parse(value).also {
        require(it.year in 1900..9999 && it.toString() == value) { "月份格式应为2026-09" }
    }
    fun months(plan: BudgetPlan): List<String> {
        val start = month(plan.startMonth); val end = month(plan.endMonth)
        val count = ChronoUnit.MONTHS.between(start, end)
        require(count in 0..119) { "计划需为连续1—120个月" }
        return (0..count).map { start.plusMonths(it).toString() }
    }
    fun allocations(text: String): Map<String, Map<String, Long>> {
        val json = JSONObject(text)
        return json.keys().asSequence().associateWith { m ->
            month(m)
            val values = json.getJSONObject(m)
            values.keys().asSequence().associateWith { c ->
                val amount = values.get(c)
                require(amount is Int || amount is Long) { "预算金额必须精确到分" }
                (amount as Number).toLong().also { require(it in 1..99_999_999_999L) { "分类预算必须大于零" } }
            }
        }
    }
    fun encode(values: Map<String, Map<String, Long>>): String = JSONObject().apply {
        values.toSortedMap().forEach { (month, cats) -> put(month, JSONObject(cats)) }
    }.toString()
    fun validate(plan: BudgetPlan, others: List<BudgetPlan>, categories: List<Category>) {
        require(plan.name.isNotBlank() && plan.name.length <= 40) { "计划名称需要1—40个字" }
        require(plan.totalFen in 1..99_999_999_999L) { "请输入有效的总预算" }
        val months = months(plan).toSet()
        require(others.none { it.owner == plan.owner && it.id != plan.id && it.startMonth <= plan.endMonth && it.endMonth >= plan.startMonth }) { "计划月份与已有计划重叠" }
        val values = allocations(plan.allocations)
        require(months.containsAll(values.keys)) { "分类分配包含计划外的月份，请先调整分配" }
        val allowed = categories.filter { it.owner == plan.owner && it.type == "expense" }.map { it.id }.toSet()
        require(values.values.all { allowed.containsAll(it.keys) }) { "分类预算只能使用当前账本的支出分类" }
        require(values.values.sumOf { it.values.sum() } <= plan.totalFen) { "所有月份的分类预算合计不能超过总预算" }
    }
    fun level(spent: Long, budget: Long): Int = when {
        spent >= budget -> 100
        spent * 5 >= budget * 4 -> 80
        else -> 0
    }
    data class Usage(val key: String, val label: String, val spent: Long, val limit: Long) {
        val level: Int get() = BudgetRules.level(spent, limit)
    }
    fun usage(plan: BudgetPlan, entries: List<Entry>, categories: List<Category>): List<Usage> {
        val rows = entries.filter { it.owner == plan.owner && it.deletedAt == null && it.type == "expense" && it.date.take(7) >= plan.startMonth && it.date.take(7) <= plan.endMonth }
        val names = categories.associate { it.id to it.name }
        return listOf(Usage("${plan.id}/total", "${plan.name} · 总预算", rows.sumOf { it.amountFen }, plan.totalFen)) +
            allocations(plan.allocations).flatMap { (m, cats) -> cats.map { (id, amount) ->
                Usage("${plan.id}/$m/$id", "$m · ${names[id].orEmpty()}", rows.filter { it.date.take(7) == m && it.categoryId == id }.sumOf { it.amountFen }, amount)
            } }
    }
}
