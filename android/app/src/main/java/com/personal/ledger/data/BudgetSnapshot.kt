package com.personal.ledger.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object BudgetSnapshot {
    fun encode(plans: List<BudgetPlan>): JSONArray = JSONArray().apply {
        plans.forEach { p -> put(JSONObject().put("id",p.id).put("name",p.name).put("start_month",p.startMonth)
            .put("end_month",p.endMonth).put("total_fen",p.totalFen).put("allocations",JSONObject(p.allocations))
            .put("renewal_dismissed",p.renewalDismissed)) }
    }
    fun parse(array: JSONArray, owner: String, categories: List<Category>): List<BudgetPlan> {
        require(array.length() <= 1000) { "预算计划过多" }
        val plans=mutableListOf<BudgetPlan>()
        repeat(array.length()) { i ->
            val p=array.getJSONObject(i)
            require(p.keys().asSequence().toSet()==setOf("id","name","start_month","end_month","total_fen","allocations","renewal_dismissed"))
            val id=p.getString("id"); require(UUID.fromString(id).toString()==id && plans.none{it.id==id})
            val amount=p.get("total_fen"); require(amount is Int || amount is Long)
            require(p.get("renewal_dismissed") is Boolean)
            val plan=BudgetPlan(id,owner,p.getString("name"),p.getString("start_month"),p.getString("end_month"),
                (amount as Number).toLong(),p.getJSONObject("allocations").toString(),p.getBoolean("renewal_dismissed"))
            BudgetRules.validate(plan,plans,categories)
            plans.add(plan)
        }
        return plans
    }
}
