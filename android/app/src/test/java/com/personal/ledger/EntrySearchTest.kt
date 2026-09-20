package com.personal.ledger

import com.personal.ledger.data.Entry
import com.personal.ledger.domain.EntrySearch
import org.junit.Assert.*
import org.junit.Test

class EntrySearchTest {
    private val first=Entry("1","a","expense","food",1234,"2026-09-01","Lunch 微信",1,1)
    private val last=first.copy(id="2",date="2026-09-20",amountFen=567)
    private val income=first.copy(id="3",type="income",categoryId="salary",amountFen=9999)
    private val data=listOf(first,last,income,first.copy(id="other",owner="b"),first.copy(id="trash",deletedAt=5))

    @Test fun isolatesOwnerAndTrashAndSortsNewestFirst() {
        val result=EntrySearch().apply(data,"a")
        assertEquals(listOf("2","1","3"),result.map{it.id})
        assertEquals(1801L,result.filter{it.type=="expense"}.sumOf{it.amountFen})
        assertEquals(9999L,result.filter{it.type=="income"}.sumOf{it.amountFen})
        assertEquals(5,data.size)
    }
    @Test fun combinesFiltersWithInclusiveDatesAndLiteralCaseInsensitiveKeyword() {
        assertEquals(listOf(last,first),EntrySearch(" lunch ","2026-09-01","2026-09-20","expense","food").apply(data,"a"))
        assertEquals(listOf(last),EntrySearch(from="2026-09-20",to="2026-09-20").apply(data,"a"))
        assertTrue(EntrySearch(keyword="%_").apply(data,"a").isEmpty())
        assertTrue(EntrySearch(type="income",categoryId="food").apply(data,"a").isEmpty())
    }
    @Test fun invalidRangeAndEmptyLedgerDoNotProduceResults() {
        val invalid=EntrySearch(from="2026-09-20",to="2026-09-01")
        assertFalse(invalid.valid)
        assertTrue(invalid.apply(data,"a").isEmpty())
        assertTrue(EntrySearch().apply(emptyList(),"a").isEmpty())
    }
}
