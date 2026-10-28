package com.personal.ledger.data

import com.personal.ledger.domain.Rules
import java.security.MessageDigest
import java.time.Instant

/** Immutable rule definition. Empty channel/merchant means a suggestion, never blanket trust. */
data class SmartNamingRule(
    val id: String,
    val databaseName: String,
    val owner: String,
    val title: String,
    val categoryId: String,
    val channel: String = "",
    val merchant: String = "",
    val startMinute: Int = 0,
    val endMinute: Int = 1440,
    val weekdays: Set<Int> = (1..7).toSet(),
    val priority: Int = 0,
    val enabled: Boolean = true,
    val allowAutomatic: Boolean = false,
) {
    init {
        require(id.isNotBlank() && databaseName.isNotBlank() && owner.isNotBlank() && categoryId.isNotBlank())
        require(title.isNotBlank() && Rules.title(title) == title)
        require(channel in setOf("", "微信", "支付宝", "银行卡"))
        require(merchant == merchant.trim() && merchant.codePointCount(0, merchant.length) <= 80)
        require(startMinute in 0..1439 && endMinute in 0..1440 && startMinute != endMinute)
        require(weekdays.isNotEmpty() && weekdays.all { it in 1..7 })
        require(priority in 0..100)
    }

    /** Any rule edit invalidates old approval, including changing its name or category. */
    fun signature(): String {
        val fields = listOf(id, databaseName, owner, title, categoryId, channel, merchant,
            startMinute.toString(), endMinute.toString(), weekdays.sorted().joinToString(","),
            priority.toString(), enabled.toString(), allowAutomatic.toString())
        val canonical = fields.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

data class NamingRuleTrust(val ruleSignature: String, val channel: String, val merchant: String)

enum class NamingReviewReason { FIRST_CONFIRMATION, AUTOMATIC_NOT_ALLOWED, MISSING_MERCHANT,
    UNCERTAIN_PAYMENT, SOURCE_CONFLICT, RULE_CONFLICT }

sealed interface NamingDecision {
    data object NoRule : NamingDecision
    data class Review(val rule: SmartNamingRule?, val reason: NamingReviewReason) : NamingDecision
    data class Automatic(val rule: SmartNamingRule) : NamingDecision
}

/** Pure decision core. It does not write entries, grant permission, or replace legacy merchant memory. */
object SmartRuleMatcher {
    fun evaluate(databaseName: String, owner: String, notice: PaymentNotice,
                 rules: List<SmartNamingRule>, trust: Set<NamingRuleTrust>,
                 sourceConflict: Boolean = false): NamingDecision {
        // Ordinary purchase naming must never turn refunds/transfers into breakfast expenses.
        if(notice.excluded || notice.refund || notice.type != "expense") return NamingDecision.NoRule
        val candidates = rules.filter { it.enabled && it.databaseName == databaseName && it.owner == owner && matches(it, notice) }
        if(candidates.isEmpty()) return NamingDecision.NoRule
        val priority = candidates.maxOf { it.priority }
        val ranked = candidates.filter { it.priority == priority }
        val specificity = ranked.maxOf { specificity(it) }
        val winners = ranked.filter { specificity(it) == specificity }.distinct()
        // Equal-rank rules remain explicit conflicts, including separate rules with identical output.
        if(winners.size != 1) return NamingDecision.Review(null, NamingReviewReason.RULE_CONFLICT)
        val rule = winners.single()
        if(sourceConflict) return NamingDecision.Review(rule, NamingReviewReason.SOURCE_CONFLICT)
        if(notice.uncertain || notice.amountFen == null || notice.amountFen !in 1..99_999_999_999L)
            return NamingDecision.Review(rule, NamingReviewReason.UNCERTAIN_PAYMENT)
        if(notice.merchant.isBlank() || notice.channel !in setOf("微信", "支付宝", "银行卡"))
            return NamingDecision.Review(rule, NamingReviewReason.MISSING_MERCHANT)
        if(!rule.allowAutomatic) return NamingDecision.Review(rule, NamingReviewReason.AUTOMATIC_NOT_ALLOWED)
        return if(fingerprint(rule, notice) in trust) NamingDecision.Automatic(rule)
        else NamingDecision.Review(rule, NamingReviewReason.FIRST_CONFIRMATION)
    }

    /** Call only after the user confirms the entry; a suggestion or name edit cannot create trust. */
    fun trustAfterConfirmation(databaseName: String, owner: String, notice: PaymentNotice,
                               rules: List<SmartNamingRule>, sourceConflict: Boolean = false): NamingRuleTrust? {
        val decision = evaluate(databaseName, owner, notice, rules, emptySet(), sourceConflict)
        return if(decision is NamingDecision.Review && decision.reason == NamingReviewReason.FIRST_CONFIRMATION)
            fingerprint(requireNotNull(decision.rule), notice) else null
    }

    private fun fingerprint(rule: SmartNamingRule, notice: PaymentNotice) =
        NamingRuleTrust(rule.signature(), notice.channel, notice.merchant.trim())

    private fun specificity(rule: SmartNamingRule) =
        (if(rule.merchant.isNotEmpty()) 2 else 0) + (if(rule.channel.isNotEmpty()) 1 else 0)

    private fun matches(rule: SmartNamingRule, notice: PaymentNotice): Boolean {
        if(notice.transactionAt <= 0) return false
        if(rule.channel.isNotEmpty() && rule.channel != notice.channel) return false
        if(rule.merchant.isNotEmpty() && rule.merchant != notice.merchant.trim()) return false
        val time = Instant.ofEpochMilli(notice.transactionAt).atZone(Rules.zone)
        val minute = time.hour * 60 + time.minute
        // An overnight window belongs to the weekday on which the window starts.
        val overnight = rule.startMinute > rule.endMinute
        val inside = if(overnight) minute >= rule.startMinute || minute < rule.endMinute
                     else minute >= rule.startMinute && minute < rule.endMinute
        val day = if(overnight && minute < rule.endMinute) time.minusDays(1).dayOfWeek.value else time.dayOfWeek.value
        return inside && day in rule.weekdays
    }
}
