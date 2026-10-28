package com.personal.ledger

import com.personal.ledger.data.*
import com.personal.ledger.domain.Rules
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class SmartRuleMatcherTest {
    private fun notice(at: String = "2026-10-26T07:30:00") = PaymentNotice("one", "支付宝", "2026-10-26",
        850, "expense", "校园食堂", "模拟支付", false, false, false,
        transactionAt = LocalDateTime.parse(at).atZone(Rules.zone).toInstant().toEpochMilli(), channel = "支付宝")
    private fun rule() = SmartNamingRule("breakfast", "a.db", "a", "早餐", "food", channel = "支付宝",
        startMinute = 390, endMinute = 540, allowAutomatic = true)
    private fun evaluate(n: PaymentNotice = notice(), rules: List<SmartNamingRule> = listOf(rule()),
                         trust: Set<NamingRuleTrust> = emptySet(), conflict: Boolean = false) =
        SmartRuleMatcher.evaluate("a.db", "a", n, rules, trust, conflict)
    private fun approve(n: PaymentNotice = notice(), rules: List<SmartNamingRule> = listOf(rule())) =
        requireNotNull(SmartRuleMatcher.trustAfterConfirmation("a.db", "a", n, rules))
    private fun reason(result: NamingDecision) = (result as NamingDecision.Review).reason

    @Test fun firstConfirmationOnlyTrustsExactMerchantAndChannelNotAmount() {
        assertEquals(NamingReviewReason.FIRST_CONFIRMATION, reason(evaluate()))
        val trust = setOf(approve())
        assertTrue(evaluate(notice().copy(amountFen = 900), trust = trust) is NamingDecision.Automatic)
        assertEquals(NamingReviewReason.FIRST_CONFIRMATION, reason(evaluate(notice().copy(merchant = "另一家店"), trust = trust)))
        assertEquals(NamingReviewReason.MISSING_MERCHANT, reason(evaluate(notice().copy(merchant = ""), trust = trust)))
        val broad = rule().copy(channel = "")
        val broadTrust = setOf(approve(rules = listOf(broad)))
        assertEquals(NamingReviewReason.FIRST_CONFIRMATION, reason(evaluate(notice().copy(channel = "微信"), listOf(broad), broadTrust)))
    }

    @Test fun explicitFuturePermissionAndCertainPaymentAreRequired() {
        val disabled = rule().copy(allowAutomatic = false)
        assertEquals(NamingReviewReason.AUTOMATIC_NOT_ALLOWED, reason(evaluate(rules = listOf(disabled))))
        assertNull(SmartRuleMatcher.trustAfterConfirmation("a.db", "a", notice(), listOf(disabled)))
        for(n in listOf(notice().copy(uncertain = true), notice().copy(amountFen = null), notice().copy(amountFen = 0))) {
            assertEquals(NamingReviewReason.UNCERTAIN_PAYMENT, reason(evaluate(n, trust = setOf(approve()))))
            assertNull(SmartRuleMatcher.trustAfterConfirmation("a.db", "a", n, listOf(rule())))
        }
        assertEquals(NamingReviewReason.SOURCE_CONFLICT, reason(evaluate(trust = setOf(approve()), conflict = true)))
        assertNull(SmartRuleMatcher.trustAfterConfirmation("a.db", "a", notice(), listOf(rule()), true))
    }

    @Test fun boundariesAndOvernightWeekdayUseTransactionTime() {
        assertTrue(evaluate(notice("2026-10-26T06:30:00")) is NamingDecision.Review)
        assertEquals(NamingDecision.NoRule, evaluate(notice("2026-10-26T09:00:00")))
        assertEquals(NamingDecision.NoRule, evaluate(notice("2026-10-26T06:29:59")))
        val night = rule().copy(startMinute = 1320, endMinute = 120, weekdays = setOf(1))
        assertTrue(evaluate(notice("2026-10-27T01:59:59"), listOf(night)) is NamingDecision.Review)
        assertEquals(NamingDecision.NoRule, evaluate(notice("2026-10-27T02:00:00"), listOf(night)))
        assertEquals(NamingDecision.NoRule, evaluate(notice("2026-10-28T01:00:00"), listOf(night)))
    }

    @Test fun priorityThenExactMerchantAndEqualRankConflict() {
        val exact = rule().copy(id = "exact", merchant = "校园食堂", title = "食堂早餐")
        assertEquals(exact, (evaluate(rules = listOf(rule(), exact)) as NamingDecision.Review).rule)
        val priority = rule().copy(priority = 1)
        assertEquals(priority, (evaluate(rules = listOf(priority, exact)) as NamingDecision.Review).rule)
        val other = exact.copy(id = "other", title = "午餐")
        assertEquals(NamingReviewReason.RULE_CONFLICT, reason(evaluate(rules = listOf(exact, other))))
        assertNull(SmartRuleMatcher.trustAfterConfirmation("a.db", "a", notice(), listOf(exact, other)))
    }

    @Test fun editsInvalidateTrustAndAccountsNeverShareRules() {
        val trust = setOf(approve())
        for(edited in listOf(rule().copy(title = "早饭"), rule().copy(categoryId = "other"),
            rule().copy(startMinute = 380), rule().copy(priority = 2), rule().copy(weekdays = setOf(1))))
            assertEquals(NamingReviewReason.FIRST_CONFIRMATION, reason(evaluate(rules = listOf(edited), trust = trust)))
        for(other in listOf(rule().copy(owner = "b"), rule().copy(databaseName = "b.db"), rule().copy(enabled = false)))
            assertEquals(NamingDecision.NoRule, evaluate(rules = listOf(other), trust = trust))
        assertEquals(rule().signature(), rule().copy(weekdays = (7 downTo 1).toSet()).signature())
    }

    @Test fun refundsTransfersAndIncomeCannotBecomeBreakfast() {
        for(n in listOf(notice().copy(refund = true), notice().copy(excluded = true),
            notice().copy(type = "income"), notice().copy(type = null))) {
            assertEquals(NamingDecision.NoRule, evaluate(n, trust = setOf(approve())))
            assertNull(SmartRuleMatcher.trustAfterConfirmation("a.db", "a", n, listOf(rule())))
        }
    }

    @Test fun validationRejectsBrokenRules() {
        for(make in listOf<() -> SmartNamingRule>({rule().copy(title = "")}, {rule().copy(title = "长".repeat(41))},
            {rule().copy(startMinute = 540)}, {rule().copy(endMinute = 1441)},
            {rule().copy(weekdays = emptySet())}, {rule().copy(weekdays = setOf(0))},
            {rule().copy(channel = "未知")}, {rule().copy(priority = -1)}))
            assertTrue(runCatching(make).isFailure)
    }
}
