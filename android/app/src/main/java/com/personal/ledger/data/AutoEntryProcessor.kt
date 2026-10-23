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
        inbox.withTransaction {
            if(inbox.auto().candidate(notice.id)!=null) return@withTransaction null
            var candidate=target.copy(source=notice.source,transactionAt=notice.transactionAt,channel=notice.channel,cardTail=notice.cardTail,
                status=if(notice.excluded) "ignored" else "pending",reason=if(notice.excluded) "充值、提现或本人账户互转不计收支" else "请确认分类与通知内容")
            inbox.auto().insert(candidate)
            if(notice.excluded) return@withTransaction candidate
            if(!notice.uncertain) {
                val possible=inbox.auto().nearby(candidate.databaseName,candidate.owner,candidate.transactionAt-300000,candidate.transactionAt+300000)
                    .filter { crossSourceMatch(candidate,it) }.sortedWith(compareBy<PaymentCandidate> {kotlin.math.abs(it.transactionAt-candidate.transactionAt)}.thenBy {it.id})
                for(root in possible) {
                    if(inbox.auto().children(root.id).isNotEmpty()) continue
                    var merged: PaymentCandidate?=null
                    ledger.withTransaction {
                        val existing=root.entryId?.let {ledger.ledger().entry(it)}
                        if(root.status=="booked" && (existing==null || existing.deletedAt!=null || existing.owner!=root.owner || existing.amountFen!=root.amountFen || existing.type!=root.type || existing.date!=root.date)) return@withTransaction
                        val category=categoryFor(root,ledger,true) ?: return@withTransaction
                        val entry=existing ?: write(root,ledger,category.id,root.type!!,root.amountFen!!,root.date,root.note,null)
                        inbox.auto().update(root.copy(status="booked",entryId=entry.id,reason="已合并银行短信与支付通知，仅计一笔；可查看来源并拆分"))
                        merged=candidate.copy(status="merged",entryId=entry.id,mergedInto=root.id,reason="同渠道、金额、收支及5分钟内时间匹配，已合并；若是两次消费可拆分")
                        inbox.auto().update(merged!!)
                    }
                    if(merged!=null) return@withTransaction merged
                }
            }
            val category=categoryFor(candidate,ledger,notice.source=="sms")
            ledger.withTransaction {
                val duplicate=matches(candidate,ledger)
                if(duplicate.isNotEmpty() && notice.source!="sms") candidate=candidate.copy(reason="疑似重复：当前账本已有同日期、金额和收支类型的账目")
                else if(category!=null && !notice.uncertain) {
                    val entry=write(candidate,ledger,category.id,notice.type!!,notice.amountFen!!,notice.date,notice.note,null)
                    candidate=candidate.copy(status="booked",entryId=entry.id,reason="已自动记账，可在指定账本查看或修改")
                }
            }
            inbox.auto().update(candidate)
            candidate
        }
    }
    private suspend fun categoryFor(c: PaymentCandidate, ledger: LedgerDatabase, fallback: Boolean): Category? {
        val categories=ledger.ledger().categorySnapshot(c.owner)
        if(c.refund) return categories.firstOrNull {it.type=="income" && it.name=="退款"}
        if(c.type==null) return null
        for(platform in listOf(c.platform,c.channel).distinct()) {
            val mapping=inbox.auto().mapping(c.databaseName,c.owner,platform,c.merchant,c.type)
            categories.firstOrNull {it.id==mapping?.categoryId && it.type==c.type}?.let {return it}
        }
        return if(fallback) categories.firstOrNull {it.type==c.type && it.name==if(c.type=="income") "其他收入" else "其他支出"} else null
    }
    /** Pair only one bank source with one platform source, never two platform payments. */
    private fun crossSourceMatch(a: PaymentCandidate,b: PaymentCandidate): Boolean {
        if(a.id==b.id || a.transactionAt<=0 || b.transactionAt<=0 || a.mergeDisabled || b.mergeDisabled) return false
        if(a.source==b.source || setOf(a.source,b.source)!=setOf("sms","notification")) return false
        val sms=if(a.source=="sms") a else b;val platform=if(a.source=="notification") a else b
        return sms.channel in setOf("微信","支付宝") && sms.channel==platform.platform &&
            a.amountFen!=null && a.amountFen==b.amountFen && a.type!=null && a.type==b.type && a.refund==b.refund &&
            kotlin.math.abs(a.transactionAt-b.transactionAt)<=300000 &&
            (a.cardTail.isEmpty() || b.cardTail.isEmpty() || a.cardTail==b.cardTail)
    }
    suspend fun split(id: String,ledger: LedgerDatabase): PaymentCandidate = lock.withLock {
        inbox.withTransaction {
            val c=requireNotNull(inbox.auto().candidate(id))
            require(c.status=="merged" && c.mergedInto!=null) {"此来源已拆分或未合并，请刷新"}
            val root=requireNotNull(inbox.auto().candidate(c.mergedInto))
            require(root.databaseName==c.databaseName && root.owner==c.owner)
            val original=requireNotNull(root.entryId?.let {ledger.ledger().entry(it)}) {"原账目已不存在"}
            require(original.owner==c.owner && original.deletedAt==null) {"请先恢复原账目，再拆分"}
            val category=requireNotNull(categoryFor(c,ledger,true))
            val entry=write(c,ledger,category.id,c.type!!,c.amountFen!!,c.date,c.note,null)
            val result=c.copy(status="booked",entryId=entry.id,mergedInto=null,mergeDisabled=true,reason="已拆分为独立账目，两笔均计入统计")
            inbox.auto().update(result)
            inbox.auto().update(root.copy(mergeDisabled=true,reason="用户已拆分合并来源，原账目保留"))
            result
        }
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
