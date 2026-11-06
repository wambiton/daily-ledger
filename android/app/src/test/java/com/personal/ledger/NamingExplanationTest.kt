package com.personal.ledger

import com.personal.ledger.data.*
import org.junit.Test
import org.junit.Assert.*

class NamingExplanationTest {
    private val rule=SmartNamingRule("r","a.db","a","夜宵","cat",channel="支付宝",startMinute=1380,endMinute=120,weekdays=setOf(1,5),allowAutomatic=true)
    private fun candidate()=PaymentCandidate("c","a.db","a","模拟","支付宝","2026-11-05",850,"expense","商户","",false,"pending","首次",ruleSignature=rule.signature())
    @Test fun originalDefinitionExplainsOvernightWithoutClaimingHistoricalApproval() {
        val text=NamingExplanation.describe(candidate(),listOf(rule)).joinToString()
        assertTrue(text.contains("夜宵"));assertTrue(text.contains("23:00—02:00"))
        assertTrue(text.contains("跨日"));assertTrue(text.contains("一、五"))
        assertTrue(text.contains("不表示本次已经自动获准"))
    }
    @Test fun editedAndForeignRulesCannotBePresentedAsOriginal() {
        for(r in listOf(rule.copy(title="早餐"),rule.copy(owner="b"),rule.copy(databaseName="b.db"))) {
            val text=NamingExplanation.describe(candidate(),listOf(r)).joinToString()
            assertTrue(text.contains("无法还原"));assertFalse(text.contains("记录关联规则"))
        }
    }
    @Test fun missingFingerprintDoesNotGuessFromCurrentMatchingRule() {
        val text=NamingExplanation.describe(candidate().copy(ruleSignature=""),listOf(rule)).joinToString()
        assertTrue(text.contains("未保留"));assertFalse(text.contains("夜宵"))
    }
}
