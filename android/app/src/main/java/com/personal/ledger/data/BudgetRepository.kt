package com.personal.ledger.data

import androidx.room.withTransaction
import com.personal.ledger.domain.Rules
import java.time.YearMonth
import java.util.UUID

class BudgetRepository(private val db: LedgerDatabase, private val owner: String) {
    private val dao = db.ledger()
    suspend fun save(plan: BudgetPlan) = db.withTransaction {
        require(plan.owner == owner)
        val existing = dao.budgetSnapshot(owner)
        BudgetRules.validate(plan, existing, dao.categorySnapshot(owner))
        // IDs belong to the current book; the UI only creates fresh UUIDs or edits known plans.
        dao.putBudget(plan)
        dao.enqueue(PendingOperation(UUID.randomUUID().toString(),owner,plan.id,"budget","upsert",0,"{}",System.currentTimeMillis()))
    }
    suspend fun dismissRenewal(plan: BudgetPlan) = save(plan.copy(renewalDismissed=true))

    /** Fill only previously unset months. Never overwrite a month's explicit allocation. */
    suspend fun inheritDue(today: YearMonth): List<String> = db.withTransaction {
        val warnings = mutableListOf<String>()
        dao.budgetSnapshot(owner).forEach { plan ->
            val values = BudgetRules.allocations(plan.allocations).toMutableMap()
            var changed = false
            BudgetRules.months(plan).filter { it <= today.toString() }.forEach { m ->
                if(m !in values) {
                    val previous = values[YearMonth.parse(m).minusMonths(1).toString()]
                    if(previous != null) {
                        if(values.values.sumOf { it.values.sum() } + previous.values.sum() <= plan.totalFen) {
                            values[m] = previous.toMap(); changed = true
                        } else warnings.add("${plan.name}：$m 剩余可分配预算不足，未自动沿用，请调整")
                    }
                }
            }
            if(changed) save(plan.copy(allocations=BudgetRules.encode(values)))
        }
        warnings
    }
    /** Transactional crossing state survives process restarts. Falling below rearms that threshold. */
    suspend fun reconcile(): List<BudgetNotice> = db.withTransaction {
        val old = dao.noticeSnapshot(owner).associateBy { it.key }
        val entries = dao.entrySnapshot(owner); val categories = dao.categorySnapshot(owner)
        val usages = dao.budgetSnapshot(owner).flatMap { BudgetRules.usage(it, entries, categories) }
        val events = mutableListOf<BudgetNotice>()
        usages.forEach { u ->
            val level = u.level
            val message = if(level == 0) "" else "${u.label}已达到${level}%，已支出¥${Rules.money(u.spent)} / 预算¥${Rules.money(u.limit)}"
            val next = BudgetNotice(owner,u.key,level,message)
            if(old[u.key] != next) dao.putNotice(next)
            if(level > (old[u.key]?.level ?: 0)) events.add(next)
        }
        old.values.filter { n -> usages.none { it.key == n.key } && n.level != 0 }.forEach { dao.putNotice(it.copy(level=0,message="")) }
        events
    }
    fun nextPeriod(plan: BudgetPlan): BudgetPlan {
        val months = BudgetRules.months(plan)
        val nextStart = YearMonth.parse(plan.endMonth).plusMonths(1)
        val shifted = BudgetRules.allocations(plan.allocations).mapKeys { (month, _) ->
            YearMonth.parse(month).plusMonths(months.size.toLong()).toString()
        }
        return plan.copy(id=UUID.randomUUID().toString(), startMonth=nextStart.toString(),
            endMonth=nextStart.plusMonths(months.size-1L).toString(), allocations=BudgetRules.encode(shifted), renewalDismissed=false)
    }
}
