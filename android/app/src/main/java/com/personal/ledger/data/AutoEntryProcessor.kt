package com.personal.ledger.data

import androidx.room.withTransaction
import com.personal.ledger.domain.Rules
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.util.UUID

class AutoEntryProcessor(private val inbox: AutoEntryDatabase) {
    companion object { val lock=Mutex() }
    suspend fun receive(notice: PaymentNotice, target: PaymentCandidate, ledger: LedgerDatabase): PaymentCandidate? = lock.withLock {
        if(inbox.auto().candidate(notice.id)!=null) return@withLock null
        var candidate=target.copy(status=if(notice.excluded) "ignored" else "pending",reason=if(notice.excluded) "充值、提现或本人账户互转不计收支" else "请确认分类与通知内容")
        inbox.auto().insert(candidate)
        if(notice.excluded) return@withLock candidate
        val categories=ledger.ledger().categorySnapshot(candidate.owner)
        val category=if(notice.refund) categories.firstOrNull{it.type=="income" && it.name=="退款"}
            else if(notice.merchant.isNotEmpty() && notice.type!=null) inbox.auto().mapping(candidate.databaseName,candidate.owner,notice.platform,notice.merchant,notice.type)?.let {mapping -> categories.firstOrNull{it.id==mapping.categoryId && it.type==notice.type} } else null
        val duplicate=matches(candidate,ledger)
        if(duplicate.isNotEmpty()) candidate=candidate.copy(reason="疑似重复：当前账本已有同日期、金额和收支类型的账目")
        else if(category!=null && !notice.uncertain) {
            val entry=write(candidate,ledger,category.id,notice.type!!,notice.amountFen!!,notice.date,notice.note,null)
            candidate=candidate.copy(status="booked",entryId=entry.id,reason="已自动记账，可在指定账本查看或修改")
        }
        inbox.auto().update(candidate)
        candidate
    }
    suspend fun matches(candidate: PaymentCandidate, db: LedgerDatabase): List<Entry> = db.ledger().entrySnapshot(candidate.owner)
        .filter{it.date==candidate.date && it.amountFen==candidate.amountFen && it.type==candidate.type && it.id!=candidate.id}

    suspend fun confirm(candidate: PaymentCandidate, ledger: LedgerDatabase, categoryId: String, type: String, amount: String,
        date: String, note: String, remember: Boolean, replaceId: String?=null): PaymentCandidate = lock.withLock {
        val fresh=requireNotNull(inbox.auto().candidate(candidate.id))
        require(fresh.status=="pending") {"此记录已处理，请刷新"}
        val value=Rules.amount(amount);Rules.date(LocalDate.parse(date));Rules.note(note)
        val entry=write(fresh,ledger,categoryId,type,value,date,note,replaceId)
        val saved=fresh.copy(status="booked",entryId=entry.id,reason="已确认入账")
        inbox.auto().update(saved)
        if(remember && fresh.merchant.isNotBlank()) inbox.auto().map(MerchantCategory(fresh.databaseName,fresh.owner,fresh.platform,fresh.merchant,type,categoryId))
        saved
    }
    suspend fun ignore(id: String) = lock.withLock {
        val candidate=requireNotNull(inbox.auto().candidate(id))
        require(candidate.status=="pending")
        inbox.auto().update(candidate.copy(status="ignored",reason="用户选择不入账"))
    }
    /** Deterministic entry ID and imported source survive a crash between the two databases. */
    private suspend fun write(c: PaymentCandidate,db: LedgerDatabase,category: String,type: String,amount: Long,date: String,note: String,replace: String?): Entry = db.withTransaction {
        val dao=db.ledger()
        dao.entry(c.id)?.let { existing -> require(existing.owner==c.owner);return@withTransaction existing }
        require(c.id !in dao.importedSources(c.owner)) {"此通知对应账目已处理，不能重复入账"}
        val cat=requireNotNull(dao.category(category));require(cat.owner==c.owner && cat.type==type) {"请选择此账本对应的收支分类"}
        Rules.date(LocalDate.parse(date));Rules.note(note);require(amount>0)
        if(replace!=null) {
            val old=requireNotNull(dao.entry(replace));require(old.owner==c.owner && old.deletedAt==null && old.date==date && old.amountFen==amount && old.type==type) {"待替换账目已变更，请重新确认"}
            LedgerRepository(db,c.owner).delete(replace)
        }
        val now=System.currentTimeMillis()
        val entry=Entry(c.id,c.owner,type,category,amount,date,note,now,now)
        dao.upsertEntry(entry)
        dao.enqueue(PendingOperation(UUID.randomUUID().toString(),c.owner,entry.id,"entry","upsert",0,"{}",now))
        dao.markImported(ImportedSource(c.owner,c.id))
        entry
    }
}
