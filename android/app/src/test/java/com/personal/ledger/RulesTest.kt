package com.personal.ledger

import com.personal.ledger.domain.Rules
import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    @Test fun exactMoney() {
        assertEquals(1L, Rules.amount("0.01"))
        assertEquals(1800L, Rules.amount("18"))
        assertEquals(1850L, Rules.amount("18.50"))
        assertEquals("18.00", Rules.money(1800))
    }
    @Test fun rejectsInvalidMoney() {
        listOf("0", "-1", "1.001", "1e2", "", "1000000000").forEach {
            assertTrue("Should reject $it", runCatching { Rules.amount(it) }.isFailure)
        }
    }
    @Test fun unicodeLimits() {
        assertEquals("餐饮", Rules.category(" 餐饮 "))
        assertTrue(runCatching { Rules.category(" ") }.isFailure)
        Rules.note("😀".repeat(500))
        assertTrue(runCatching { Rules.note("😀".repeat(501)) }.isFailure)
    }
}
