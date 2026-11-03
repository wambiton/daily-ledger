package com.personal.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.ledger.data.*
import com.personal.ledger.domain.Rules
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class NamingEntryTest {
    private lateinit var db: LedgerDatabase
    private lateinit var inbox: AutoEntryDatabase
    private lateinit var processor: AutoEntryProcessor
    private lateinit var rule: SmartNamingRule
    private lateinit var store: NamingRuleStore
    private fun notice(id: String="first", sms: Boolean=false) = PaymentNotice(id, if(sms) "招商银行短信" else "支付宝", "2026-10-26",850,"expense","模拟食堂","模拟支付",false,false,false,
        source=if(sms) "sms" else "notification",transactionAt=LocalDateTime.parse("2026-10-26T07:30:00").atZone(Rules.zone).toInstant().toEpochMilli(),channel="支付宝")
    private suspend fun receive(n: PaymentNotice) = processor.receive(n,PaymentCandidate(n.id,"naming.db","a","模拟",n.platform,n.date,n.amountFen,n.type,n.merchant,n.note,n.refund,"pending",""),db)!!
    @Before fun setup() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        db=Room.inMemoryDatabaseBuilder(context,LedgerDatabase::class.java).build()
        inbox=Room.inMemoryDatabaseBuilder(context,AutoEntryDatabase::class.java).build()
        LedgerRepository(db,"a").seedDefaults()
        rule=SmartNamingRule(UUID.randomUUID().toString(),"naming.db","a","早餐",db.ledger().categorySnapshot("a").first{it.type=="expense"}.id,channel="支付宝",allowAutomatic=true)
        store=NamingRuleStore(db,"naming.db","a");store.save(rule)
        processor=AutoEntryProcessor(inbox)
    }
    @After fun close() { db.close();inbox.close() }
    private suspend fun confirm(c: PaymentCandidate,title: String="早餐") = processor.confirm(c,db,rule.categoryId,"expense","8.50",c.date,c.note,false,title=title)

    @Test fun firstConfirmationThenChangedAmountAutomaticAndDifferentMerchantWaits() = runBlocking {
        val first=receive(notice())
        assertEquals("pending",first.status);assertEquals("早餐",first.suggestedTitle)
        assertTrue(db.ledger().entrySnapshot("a").isEmpty())
        confirm(first)
        assertEquals("早餐",db.ledger().entry(first.id)!!.title)
        assertEquals(1,store.snapshot().single().trusted.size)
        val next=receive(notice("next").copy(amountFen=900))
        assertEquals("booked",next.status);assertEquals("早餐",db.ledger().entry(next.id)!!.title)
        assertEquals("pending",receive(notice("other").copy(merchant="另一家店",amountFen=1000)).status)
        assertEquals(1750L,db.ledger().entrySnapshot("a").sumOf {it.amountFen})
    }
    @Test fun changedNameOrRuleDoesNotGrantFutureTrust() = runBlocking {
        val first=receive(notice());confirm(first,"偶尔加餐")
        assertTrue(store.snapshot().single().trusted.isEmpty())
        assertEquals("偶尔加餐",db.ledger().entry(first.id)!!.title)
        val pending=receive(notice("second").copy(amountFen=850))
        store.save(rule.copy(title="早饭"))
        confirm(pending)
        assertTrue(store.snapshot().single().trusted.isEmpty())
    }
    @Test fun secondSourceCannotBypassFirstConfirmationAndLinkedSourcesBecomeOneEntry() = runBlocking {
        val first=receive(notice(sms=true))
        val second=receive(notice("platform"))
        assertEquals("pending",first.status);assertEquals("linked",second.status)
        assertTrue(db.ledger().entrySnapshot("a").isEmpty())
        confirm(first)
        val linked=inbox.auto().candidate(second.id)!!
        assertEquals("merged",linked.status);assertEquals(first.id,linked.entryId)
        assertEquals(1,db.ledger().entrySnapshot("a").size)
        assertTrue(runCatching {confirm(first)}.isFailure)
        processor.split(linked.id,db)
        assertEquals(2,db.ledger().entrySnapshot("a").size)
    }
    @Test fun ignoredRootAlsoIgnoresLinkedEvidenceWithoutTrust() = runBlocking {
        val root=receive(notice())
        val child=receive(notice("sms",true))
        assertEquals("linked",child.status)
        processor.ignore(root.id)
        assertEquals("ignored",inbox.auto().candidate(child.id)!!.status)
        assertTrue(store.snapshot().single().trusted.isEmpty())
        assertTrue(db.ledger().entrySnapshot("a").isEmpty())
    }
    @Test fun suggestionOnlyRulesNeverAutomaticallyConfirmThroughSms() = runBlocking {
        store.save(rule.copy(allowAutomatic=false))
        val first=receive(notice(sms=true));confirm(first)
        assertTrue(store.snapshot().single().trusted.isEmpty())
        assertEquals("pending",receive(notice("next",true).copy(amountFen=900)).status)
        assertEquals(1,db.ledger().entrySnapshot("a").size)
    }

    @Test fun notificationRenameThenConfirmIsIdempotentAndDoesNotTrustChangedTitle() = runBlocking {
        val first=receive(notice())
        processor.notificationAction(first.id,"rename",db,"naming.db","午餐")
        assertTrue(db.ledger().entrySnapshot("a").isEmpty())
        assertTrue(store.snapshot().single().trusted.isEmpty())
        repeat(2) {processor.notificationAction(first.id,"confirm",db,"naming.db")}
        processor.notificationAction(first.id,"rename",db,"naming.db","不可覆盖")
        processor.notificationAction(first.id,"ignore",db,"naming.db")
        assertEquals(1,db.ledger().entrySnapshot("a").size)
        assertEquals("午餐",db.ledger().entry(first.id)!!.title)
        assertEquals("booked",inbox.auto().candidate(first.id)!!.status)
        assertTrue(store.snapshot().single().trusted.isEmpty())
    }
    @Test fun notificationIgnoreLinkedSourceCannotBeReconfirmed() = runBlocking {
        val root=receive(notice())
        val child=receive(notice("sms",true))
        processor.notificationAction(child.id,"ignore",db,"naming.db")
        processor.notificationAction(root.id,"confirm",db,"naming.db")
        assertEquals("ignored",inbox.auto().candidate(root.id)!!.status)
        assertEquals("ignored",inbox.auto().candidate(child.id)!!.status)
        assertTrue(db.ledger().entrySnapshot("a").isEmpty())
    }
    @Test fun notificationRejectsWrongBookAndChangedRules() = runBlocking {
        val first=receive(notice())
        assertTrue(runCatching {processor.notificationAction(first.id,"confirm",db,"other.db")}.isFailure)
        store.save(rule.copy(title="新规则"))
        assertEquals("pending",processor.notificationAction(first.id,"confirm",db,"naming.db")!!.status)
        assertTrue(db.ledger().entrySnapshot("a").isEmpty())
        assertTrue(store.snapshot().single().trusted.isEmpty())
    }
    @Test fun notificationConfirmLinkedSourceCreatesOneEntryAndTrust() = runBlocking {
        val first=receive(notice())
        val child=receive(notice("sms",true))
        processor.notificationAction(child.id,"confirm",db,"naming.db")
        processor.notificationAction(first.id,"confirm",db,"naming.db")
        assertEquals(1,db.ledger().entrySnapshot("a").size)
        assertEquals("merged",inbox.auto().candidate(child.id)!!.status)
        assertEquals(1,store.snapshot().single().trusted.size)
    }

    /** Model interruption after the ledger commit but before the inbox commit. */
    private suspend fun interruptedWrite(c: PaymentCandidate): Entry {
        val entry=Entry(c.id,c.owner,"expense",rule.categoryId,850,c.date,c.note,1,2,title="已保存名称")
        db.ledger().upsertEntry(entry)
        db.ledger().markImported(ImportedSource(c.owner,c.id))
        assertEquals("pending",inbox.auto().candidate(c.id)!!.status)
        return entry
    }
    @Test fun interruptedWriteRepeatedConfirmReconcilesBothSourcesWithoutDuplicate() = runBlocking {
        val root=receive(notice())
        val child=receive(notice("sms",true))
        val saved=interruptedWrite(root)
        val restarted=AutoEntryProcessor(inbox)
        repeat(3) {restarted.notificationAction(child.id,"confirm",db,"naming.db")}
        assertEquals(listOf(saved),db.ledger().entrySnapshot("a"))
        assertEquals("booked",inbox.auto().candidate(root.id)!!.status)
        assertEquals("merged",inbox.auto().candidate(child.id)!!.status)
        assertEquals(saved.id,inbox.auto().candidate(child.id)!!.entryId)
        assertTrue(store.snapshot().single().trusted.isEmpty())
    }
    @Test fun interruptedWriteCannotBeRenamedOrIgnoredByStaleActions() = runBlocking {
        val root=receive(notice())
        val saved=interruptedWrite(root)
        val restarted=AutoEntryProcessor(inbox)
        restarted.notificationAction(root.id,"rename",db,"naming.db","不可覆盖")
        restarted.notificationAction(root.id,"ignore",db,"naming.db")
        assertEquals(listOf(saved),db.ledger().entrySnapshot("a"))
        assertEquals("booked",inbox.auto().candidate(root.id)!!.status)
        assertTrue(store.snapshot().single().trusted.isEmpty())
    }
    @Test fun interruptedWriteAlreadyInTrashIsNotResurrectedByConfirm() = runBlocking {
        val root=receive(notice())
        val saved=interruptedWrite(root).copy(deletedAt=3,updatedAt=3)
        db.ledger().upsertEntry(saved)
        AutoEntryProcessor(inbox).notificationAction(root.id,"confirm",db,"naming.db")
        assertEquals(saved,db.ledger().entry(root.id))
        assertTrue(db.ledger().entrySnapshot("a").isEmpty())
        assertEquals("booked",inbox.auto().candidate(root.id)!!.status)
        assertTrue(store.snapshot().single().trusted.isEmpty())
    }

    @Test fun ignoredSourcesRestoreWithoutBookingOrTrustAndRequireManualConfirmation() = runBlocking {
        val root=receive(notice());val child=receive(notice("sms",true))
        processor.ignore(root.id)
        val restored=processor.restoreIgnored(root.id,db,"naming.db")
        assertEquals("pending",restored.status)
        assertEquals("linked",inbox.auto().candidate(child.id)!!.status)
        assertTrue(db.ledger().entrySnapshot("a").isEmpty())
        assertTrue(store.snapshot().single().trusted.isEmpty())
        processor.notificationAction(root.id,"confirm",db,"naming.db")
        assertEquals("pending",inbox.auto().candidate(root.id)!!.status)
        assertNull(receiveReplay(notice()))
        confirm(restored)
        assertEquals(1,db.ledger().entrySnapshot("a").size)
        assertEquals("merged",inbox.auto().candidate(child.id)!!.status)
        assertTrue(store.snapshot().single().trusted.isEmpty())
    }
    private suspend fun receiveReplay(n: PaymentNotice)=processor.receive(n,PaymentCandidate(n.id,"naming.db","a","模拟",n.platform,n.date,n.amountFen,n.type,n.merchant,n.note,n.refund,"pending",""),db)
    @Test fun restoredIgnoredEntryCannotBeAutomaticallyConfirmedByLateSmsWithoutRules() = runBlocking {
        val root=receive(notice());processor.ignore(root.id)
        processor.restoreIgnored(root.id,db,"naming.db")
        store.save(rule.copy(enabled=false))
        val child=receive(notice("late-sms",true))
        assertEquals("linked",child.status)
        assertTrue(db.ledger().entrySnapshot("a").isEmpty())
    }
    @Test fun ignoredRestoreRejectsWrongBookSystemExclusionsAndExistingEntries() = runBlocking {
        val root=receive(notice());processor.ignore(root.id)
        assertTrue(runCatching {processor.restoreIgnored(root.id,db,"other.db")}.isFailure)
        assertEquals("ignored",inbox.auto().candidate(root.id)!!.status)
        interruptedWriteForIgnored(root)
        assertTrue(runCatching {processor.restoreIgnored(root.id,db,"naming.db")}.isFailure)
        assertEquals("ignored",inbox.auto().candidate(root.id)!!.status)
        val excluded=receive(notice("excluded").copy(excluded=true))
        assertTrue(runCatching {processor.restoreIgnored(excluded.id,db,"naming.db")}.isFailure)
        assertEquals("ignored",inbox.auto().candidate(excluded.id)!!.status)
        assertEquals(1,db.ledger().entrySnapshot("a").size)
    }
    private suspend fun interruptedWriteForIgnored(c: PaymentCandidate) {
        db.ledger().upsertEntry(Entry(c.id,c.owner,"expense",rule.categoryId,850,c.date,c.note,1,2,title="已有账目"))
    }

    @Test fun revokeMergedSourcesPreservesEvidenceAndMovesOneEntryToTrash() = runBlocking {
        val root=receive(notice());val child=receive(notice("sms",true));confirm(root)
        val before=db.ledger().entry(root.id)!!
        processor.revoke(child.id,db,"naming.db")
        val removed=db.ledger().entry(root.id)!!
        assertNotNull(removed.deletedAt)
        assertEquals(before.copy(deletedAt=removed.deletedAt,updatedAt=removed.updatedAt),removed)
        assertTrue(db.ledger().entrySnapshot("a").isEmpty())
        assertEquals("revoked",inbox.auto().candidate(root.id)!!.status)
        assertEquals("revoked",inbox.auto().candidate(child.id)!!.status)
        assertTrue(root.id in db.ledger().importedSources("a"))
        assertEquals(1,store.snapshot().single().trusted.size)
        processor.revoke(root.id,db,"naming.db")
        processor.notificationAction(root.id,"confirm",db,"naming.db")
        assertEquals(removed,db.ledger().entry(root.id))
    }
    @Test fun revokedEntryLateSourcesWaitAndDifferentAmountCanStillBook() = runBlocking {
        val root=receive(notice());confirm(root);processor.revoke(root.id,db,"naming.db")
        val late=receive(notice("late",true))
        assertEquals("pending",late.status);assertTrue(late.uncertain)
        assertTrue(db.ledger().entrySnapshot("a").isEmpty())
        assertNull(receiveReplay(notice()))
        val different=receive(notice("different").copy(amountFen=950))
        assertEquals("booked",different.status)
        assertEquals(950L,db.ledger().entrySnapshot("a").single().amountFen)
    }
    @Test fun revokeRetryAfterLedgerCommitAndAfterTrashRestoreIsSafe() = runBlocking {
        val root=receive(notice());confirm(root)
        // Simulate interruption before the candidate status commit.
        LedgerRepository(db,"a").delete(root.id)
        val removed=db.ledger().entry(root.id)!!
        val late=receive(notice("late",true))
        assertEquals("pending",late.status)
        processor.revoke(root.id,db,"naming.db")
        assertEquals(removed,db.ledger().entry(root.id))
        val restored=removed.copy(deletedAt=null)
        db.ledger().upsertEntry(restored)
        processor.revoke(root.id,db,"naming.db")
        assertEquals(restored,db.ledger().entry(root.id))
    }
    @Test fun revokeRejectsWrongBookAndPendingCandidate() = runBlocking {
        val root=receive(notice())
        assertTrue(runCatching {processor.revoke(root.id,db,"naming.db")}.isFailure)
        confirm(root)
        val saved=db.ledger().entry(root.id)
        assertTrue(runCatching {processor.revoke(root.id,db,"other.db")}.isFailure)
        assertEquals(saved,db.ledger().entry(root.id))
        assertEquals("booked",inbox.auto().candidate(root.id)!!.status)
    }
}
