package com.personal.ledger.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A row with [] records deliberate removal of all rules; absence means legacy format. */
@Entity(tableName = "naming_settings")
data class NamingSettings(@PrimaryKey val owner: String, val rulesJson: String)

data class StoredNamingRule(val rule: SmartNamingRule, val trusted: Set<NamingRuleTrust>)

object NamingRuleSnapshot {
    private val fields = setOf("id", "title", "category_id", "channel", "merchant", "start_minute",
        "end_minute", "weekdays", "priority", "enabled", "allow_automatic", "trusted")
    fun parse(array: JSONArray, databaseName: String, owner: String, categories: List<Category>): List<StoredNamingRule> {
        require(array.length() <= 200) { "命名规则不能超过200条" }
        fun text(o: JSONObject, key: String): String = (o.get(key) as? String) ?: error("规则文字格式不正确")
        fun number(o: JSONObject, key: String): Int {
            val v = o.get(key); require(v is Int || v is Long)
            val n = (v as Number).toLong(); require(n in Int.MIN_VALUE..Int.MAX_VALUE); return n.toInt()
        }
        fun flag(o: JSONObject, key: String) = (o.get(key) as? Boolean) ?: error("规则开关格式不正确")
        val result = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            require(o.keys().asSequence().toSet() == fields)
            val id = text(o, "id"); require(UUID.fromString(id).toString() == id)
            val category = text(o, "category_id")
            require(categories.any { it.id == category && it.owner == owner && it.type == "expense" }) { "命名规则的支出分类不存在" }
            val days = o.getJSONArray("weekdays"); require(days.length() in 1..7)
            val weekdays = (0 until days.length()).map { d ->
                val v = days.get(d); require(v is Int || v is Long)
                (v as Number).toLong().also { require(it in 1..7) }.toInt()
            }
            require(weekdays.distinct().size == weekdays.size)
            val rule = SmartNamingRule(id, databaseName, owner, text(o, "title"), category, text(o, "channel"),
                text(o, "merchant"), number(o, "start_minute"), number(o, "end_minute"), weekdays.toSet(),
                number(o, "priority"), flag(o, "enabled"), flag(o, "allow_automatic"))
            val trust = o.getJSONArray("trusted"); require(trust.length() <= 1000)
            require(rule.allowAutomatic || trust.length() == 0)
            val trusted = (0 until trust.length()).map { t ->
                val item = trust.getJSONObject(t)
                require(item.keys().asSequence().toSet() == setOf("channel", "merchant"))
                val channel = text(item, "channel"); val merchant = text(item, "merchant")
                require(channel in setOf("微信", "支付宝", "银行卡"))
                require(merchant.isNotBlank() && merchant == merchant.trim() && merchant.codePointCount(0, merchant.length) <= 80)
                require(rule.channel.isEmpty() || channel == rule.channel)
                require(rule.merchant.isEmpty() || merchant == rule.merchant)
                NamingRuleTrust(rule.signature(), channel, merchant)
            }
            require(trusted.distinct().size == trusted.size)
            StoredNamingRule(rule, trusted.toSet())
        }
        require(result.map { it.rule.id }.distinct().size == result.size)
        return result
    }

    fun encode(items: List<StoredNamingRule>): JSONArray = JSONArray().apply {
        items.forEach { (r, trusted) ->
            require(trusted.all { it.ruleSignature == r.signature() }) { "规则已修改，请重新确认" }
            put(JSONObject().put("id", r.id).put("title", r.title).put("category_id", r.categoryId)
                .put("channel", r.channel).put("merchant", r.merchant).put("start_minute", r.startMinute)
                .put("end_minute", r.endMinute).put("weekdays", JSONArray(r.weekdays.sorted())).put("priority", r.priority)
                .put("enabled", r.enabled).put("allow_automatic", r.allowAutomatic)
                .put("trusted", JSONArray().apply { trusted.sortedWith(compareBy({ it.channel }, { it.merchant })).forEach {
                    put(JSONObject().put("channel", it.channel).put("merchant", it.merchant))
                } }))
        }
    }
}

class NamingRuleStore(private val db: LedgerDatabase, private val databaseName: String, private val owner: String) {
    suspend fun snapshot(): List<StoredNamingRule> = db.withTransaction {
        val json = db.ledger().namingSettings(owner)?.rulesJson ?: return@withTransaction emptyList()
        NamingRuleSnapshot.parse(JSONArray(json), databaseName, owner, db.ledger().categorySnapshot(owner))
    }
    suspend fun save(rule: SmartNamingRule) = db.withTransaction {
        require(rule.owner == owner && rule.databaseName == databaseName)
        val all = snapshot()
        val old = all.firstOrNull { it.rule.id == rule.id }
        val item = StoredNamingRule(rule, if(old?.rule == rule) old.trusted else emptySet())
        persist(all.filterNot { it.rule.id == rule.id } + item, rule.id)
    }
    suspend fun remove(id: String) = db.withTransaction {
        val all = snapshot(); require(all.any { it.rule.id == id })
        persist(all.filterNot { it.rule.id == id }, id)
    }
    /** Caller must have confirmed this payment; no rule or trust is inferred from a name edit. */
    suspend fun recordConfirmation(notice: PaymentNotice, sourceConflict: Boolean = false): Boolean = db.withTransaction {
        val all = snapshot()
        val trust = SmartRuleMatcher.trustAfterConfirmation(databaseName, owner, notice, all.map { it.rule }, sourceConflict)
            ?: return@withTransaction false
        val match = all.single { it.rule.signature() == trust.ruleSignature }
        if(trust in match.trusted) return@withTransaction true
        persist(all.map { if(it == match) it.copy(trusted = it.trusted + trust) else it }, match.rule.id)
        true
    }
    private suspend fun persist(items: List<StoredNamingRule>, id: String) {
        val json = NamingRuleSnapshot.encode(items)
        NamingRuleSnapshot.parse(json, databaseName, owner, db.ledger().categorySnapshot(owner))
        db.ledger().putNamingSettings(NamingSettings(owner, json.toString()))
        db.ledger().enqueue(PendingOperation(UUID.randomUUID().toString(), owner, id, "naming_rule", "upsert", 0, "{}", System.currentTimeMillis()))
    }
}
